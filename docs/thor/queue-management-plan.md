# Thor 队列管理下一轮实施计划

状态：后续实施计划，以下重排、清空待播、完整会话历史、repeat/autoplay 联动和持久化扩展尚未开发。依据为 [已批准设计 P13](design.md#p13-播放队列) 与 [实施计划后续项](implementation-plan.md#10-首批集成后的后续项)。本文不表示相关按钮或服务能力已经可用。

已有基线：**QueueEntry ID 归一化与 seen undo 已由 `17ae995e` 解决**，下一轮直接复用，不重新实现。有效且唯一的传入 ID 会被保留；缺失、空白、批次重复及与保留项冲突的 ID 才重新生成。同曲的不同 occurrence 不合并；入队撤销保护当前项及本次插入后曾成为当前项的条目，并由连接/队列 epoch 隔离旧撤销。实际验证状态以 [verification.md](verification.md) 为准。

## 1. 产品契约与范围

| 动作 | 必须满足的行为 |
| --- | --- |
| 浏览队列 | 下屏区分当前、即将播放、已播历史；展示真实来源与随机、循环、自动续播状态。上屏保持当前播放曲，移动焦点不切歌。 |
| 播放队列项 | A 明确跳转目标 entry；相同歌曲的重复记录按 entryId 区分。 |
| 下一首播放 | 插入当前 occurrence 之后；专辑整张按曲目顺序插入，加入队尾追加末尾，不切换当前曲。 |
| 移除待播项 | 仅影响目标待播 occurrence；当前项提供“跳过此曲”，不提供移除。提交时重新核对目标身份与分区。 |
| 移动待播项 | 方向调整草稿位置，A 提交，B 取消；不移动当前或历史，不重置当前音源和进度。 |
| 清空待播 | 确认后删除后续内容；当前曲继续；历史保留为记录，但不再参与清空后的循环候选。 |
| 清空后结束行为 | 顺序播放停止；单曲循环继续当前；列表循环仅循环当前，不能重新把历史带回来。 |
| 自动续播 | 清空后不立即推荐补曲；保留用户启用状态，提供“继续推荐”作为主动恢复入口。 |
| 撤销 | 只还原本次队列修改，不回滚音频；新队列或不兼容的后续编辑使旧撤销失效。 |

不扩展多平台音乐、账号同步或新的历史数据库。不把现有有限 `ListQueue` 宣称为具备远程分页；分页来源、冻结查询条件与单独重试需要真实 Source API 后再接入。界面仅展示内容、状态和动作，不展示本文的状态机解释。

## 2. 现有 API 的可复用边界

| 文件 | 已有能力 | 本轮后续工作 |
| --- | --- | --- |
| `playback/PlaybackOrder.kt`、`QueueEntryIdentity.kt` | occurrence ID、实际播放顺序遍历、身份归一化。 | 复用已有规则；物理索引只用于提交时查找，不作为 UI 身份。 |
| `playback/StableDeckPlayer.kt` | `moveMediaItem`、`setPlaybackOrder(userEdit=true)`、内部队列更新、metadata 替换保留身份、queueIdentityEpoch。 | 沿用底层操作与 epoch；不要覆盖 QueueManager 已有回调，也不要用重新播放整份队列实现编辑。 |
| `playback/PlaybackQueueManager.kt` | 云随机请求失效、队列构建 generation、自动续播及推荐结果回填校验、metadata 补全。 | 增加统一编辑入口、服务会话状态、真实历史、推荐暂停与恢复。 |
| `playback/MusicService.kt` | 服务生命周期、播放监听、另一处 FM 预取触发、播放快照保存/恢复。 | 两处推荐触发共用最终闸门；队列状态变化触发保存，恢复完成前禁止推荐补曲。 |
| `playback/PlaybackPersistence.kt` | schema 2 的活动条目、进度、模式、shuffle permutation、来源和 autoplay 边界。 | 增加 entryId、会话历史和清空暂停状态，兼容读取旧快照。 |
| `thor/playback/ThorPlaybackBridge.kt` | 播放状态投影、entryId 命令转发、已有撤销保护。 | 接收服务侧编辑结果，不再在 UI 桥接层拼装复杂清空/重排算法。 |
| `thor/playback/ThorQueueUndoTracker.kt` | 保守的“曾成为当前项”保护。 | 继续保护入队撤销；它不等同于真实已播历史，不作为播放统计。 |

`queueBuildGeneration` 还用于取消推荐等异步工作，不能直接当作“用户换了播放队列”的会话 ID。现有 `mode` 文案也不能承载业务判断：shuffle、repeat、autoplay 应分别投影实际状态。

## 3. 服务状态与命令

建议新增 `playback/QueueSessionState.kt`，由 QueueManager 在播放器 application looper 上单写，包含：

- `sessionId`：真正替换播放来源时更新；服务重建也不能与旧撤销混淆。
- `revision`：活动成员或有效顺序变化时递增；同 entry 的 metadata 补全不算队列编辑。
- `currentEntryId`、活动 entry 集合、当前有效播放顺序及待播 entryId 列表。
- 会话历史：entryId、真实 metadata、最后离开时间/原因及是否曾实际播放。
- 队列来源摘要、shuffle、repeat、`autoplayEnabled`、`autoplaySuspendedAfterClear`。
- 推荐请求 generation，以及恢复过程中的 `restoring` 标记。

UI 中的 `queueIndex` 表示当前有效顺序中的位置，不是永久 ID；归档历史可以没有活动索引。历史按 occurrence 保存，重复同曲可分别存在。先以每个 entry 的最近记录呈现；单曲循环不无限制造同一个条目的历史行，当前项也不在历史分区重复显示。全局播放时长、上报及原有听歌历史逻辑保持独立。

建议冻结以下服务命令；签名为实施提案，尚未加入源码：

```kotlin
data class QueueEditStamp(val sessionId: String, val revision: Long, val currentEntryId: String?)

fun movePending(stamp: QueueEditStamp, orderedPendingIds: List<String>): QueueEditResult
fun removePending(stamp: QueueEditStamp, entryId: String): QueueEditResult
fun clearPending(stamp: QueueEditStamp): QueueEditResult
fun continueRecommendations(stamp: QueueEditStamp): QueueEditResult
fun undoQueueEdit(token: String): QueueEditResult
```

结果包含真实成功/失败状态、必要提示与可选撤销 token。业务状态只由服务发布；UI 不先删除真实行再假定操作成功。命令内不持有 JVM monitor 执行挂起工作；网络请求在外部进行，回到 application looper 后再次核对会话与请求版本。

## 4. 移动与移除算法

进入移动模式时捕获 stamp、目标 entryId 与完整待播 ID 顺序。方向键只改本地草稿，列表边界不循环；A 才提交，B 丢弃。移动期间消费菜单及除Start以外的全局快捷键，不把方向移动变成切歌或跨页面焦点移动；Start丢弃尚未提交的移动草稿并进入播放器，不自动提交重排。触摸拖动如接入，也提交同一个服务命令。

提交须验证：会话、revision 和 currentEntryId 没变；草稿是原待播集合的无重复完整 permutation；目标没有进入当前或历史。自动切歌、其他客户端编辑或推荐追加改变结构时，拒绝旧草稿并保留真实队列，提示重新移动。不得丢弃后来追加的推荐来迁就旧草稿。

随机模式按 entryId 计算新 permutation，调用 `setPlaybackOrder(..., userEdit=true)`；顺序模式移动真实时间线中的待播项。源/目标原始索引在提交瞬间重新查找。取消旧云随机请求，避免迟到结果覆盖手动顺序；保留当前 entry、进度、播放/暂停及模式。切换随机与顺序时继续使用现有模式策略，并始终从播放器重新投影真实显示顺序。

移除同样只在提交瞬间确认待播身份后执行。若菜单打开时尚为待播、提交时已变当前，返回必要提示，不转成“播完移出”。当前项只能使用既有“跳过此曲”。历史不是删除待播命令的合法目标。

## 5. 清空、真实历史与推荐暂停

历史不能用“当前索引前的所有条目”冒充：从列表中间开始播放时，前面可能从未听过。服务应按实际当前项转换及播放状态记录；仅被选中但从未开始播放的项不自动成为真实已播记录。已有 seen undo 的保守保护继续保留，不要求它承担这个判定。

清空命令按以下顺序执行：

1. 校验确认时的 stamp，冻结当前项、实际待播与已有历史；取消云随机与待播补载等会改成员的旧任务。
2. 设置 `autoplaySuspendedAfterClear=true`，递增推荐 generation。不能通过“关闭再开启 autoplay”实现暂停，开启入口当前会立即请求推荐。
3. 保留历史 metadata 与 occurrence 记录。清空后的历史移出播放器循环候选，但继续显示在历史分区；待播内容被删除。活动时间线最终仅保留当前项。
4. 使用 remove/move 等时间线操作排除非当前候选，不调用 stop、seek、prepare 或重新 set 整份列表来重建当前音源。保持当前进度和播放状态，发布新 revision 与撤销 token。
5. 保持原 repeat/shuffle 设置：OFF 到结尾停止；ONE 与 ALL 都只会继续当前，历史没有机会重新进入循环。

若原队列中有位于初始选中索引之前、但从未播放的项，它们不能被标成已播。建议保留为原来源上下文而非播放历史，清空后也不纳入循环候选。点击归档历史是明确的新播放动作；建议恢复该项并保留用户当前待播，不自动恢复整段历史。该边缘语义在接 UI 前冻结为产品约定，不在本计划中冒充已批准实现。

推荐的最终闸门放在 `fetchAndAppendFmRecommendations()`：发请求前和 append 前都检查 session、推荐 generation、enabled、暂停和 restoring。QueueManager 的转换触发与 MusicService 的阈值触发均必须经过它，不能仅屏蔽一个调用点。清空前已发出的请求即使无法取消，也必须丢弃回填。

“继续推荐”是显式命令：校验当前项可作推荐种子，解除清空暂停，启动一个请求；失败保留真实错误与可重试入口，不切当前曲，也不伪造推荐行。普通暂停、恢复、切模式、重新打开队列和显示重连都不隐式解除暂停。

## 6. 撤销与持久化

复用 `17ae995e` 的入队撤销保护。移动与清空的撤销另记录提交前顺序、受影响条目、提交后 stamp 和必要的推荐暂停状态，不保存或回退音频位置。只有队列会话和兼容 revision 仍符合时才允许撤销；后续换队列、修改成员或切换到不同当前项时，宁可明确失效，也不整份覆盖新队列。撤销 token 不跨进程保存。

播放快照升级为兼容 1/2 的新 schema，建议为 3，增加活动条目 entryId、会话历史、清空暂停及所需会话状态。恢复仍复用 ID 归一化；旧快照缺少身份时生成，损坏的重复身份要修复，无法明确归属的历史引用丢弃，不错误指向另一 occurrence。

恢复顺序必须先设置 restoring 和推荐暂停，再恢复 Fm/autoplay 配置、活动条目、历史、shuffle 顺序与进度，最后结束 restoring。否则恢复时间线产生的转换事件可能先偷跑推荐。`PlaybackPersistence.save()` 的队列相等比较须包含新字段；即使 active items 没变，暂停状态变化也要落盘。过期保存不得覆盖较新会话或 revision；原进度 checkpoint 的独立恢复偏好继续生效。不新增账号凭据字段或第二数据库。

## 7. 有意义的回归与交付门槛

| 场景 | 必须断言 |
| --- | --- |
| 同曲重复 occurrence、预分配 ID、seen undo | 复用已有身份和撤销回归，不复制另一套规则。 |
| 顺序/随机各移动一个待播项 | UI 顺序等于实际下一首顺序；当前 entry、连续进度、播放状态和模式不重置。 |
| 移动后 B 取消 | Player 没有收到实际编辑；原 permutation 保留。 |
| 草稿期间切歌、追加推荐或外部换队列 | 旧提交被拒绝；新成员和新来源不被覆盖。 |
| metadata 补全期间移动 | 同 ID 的新 metadata 得以保留；不因补全误判为新 occurrence。 |
| 菜单打开后待播变当前 | 移除被拒绝，不停止当前音频，也不偷偷排队播完移出。 |
| 从列表中间开始播放 | 未播放的前缀不能出现在真实已播历史。 |
| 清空时有历史、当前、待播 | 待播为空，历史仍可读，当前音源和位置不变。 |
| OFF / ONE / ALL × 随机开关 | 清空后分别停止/循环当前/循环当前；历史不重新进入自动下一首。 |
| 清空前推荐请求在途，随后返回 | 迟到响应不追加；两处自动预取触发均不能绕过暂停。 |
| 主动继续推荐：成功、失败、重复点击 | 成功只追加一次；失败可重试；不切当前曲、不重复发起同代请求。 |
| 清空/移动后撤销及失效撤销 | 精确恢复本次编辑；不 seek、不回退音频；不兼容的新编辑不被覆盖。 |
| 重启恢复清空后的快照 | ID、历史、模式和暂停保持一致；恢复过程中不补曲。 |
| 旧快照、损坏 ID/历史、迟到保存 | 兼容恢复或安全舍弃歧义记录；不崩溃、不覆盖较新会话。 |

建议新增 `QueueEditPlanTest`、`QueueHistoryProjectionTest`、`QueueAutoplayPauseTest`，扩展 `PlaybackShuffleSnapshotTest`；在 `PlaybackQueueIntegrationTest` 使用实际 StableDeck 验证时间线命令、下一首与不重置进度，不只断言 reducer 输出。UI 回归覆盖手柄进入移动、边界、A/B、确认层、自动切歌打断以及触摸/手柄交接。服务测试通过后再展示对应动作，不放 disabled 假功能占位。

## 8. 实施批次与文件所有权

1. **服务状态和纯算法**：新增 `playback/QueueSessionState.kt`、可纯测的编辑 plan/reducer；修改 `PlaybackQueueManager.kt` 的统一命令、历史与推荐闸门。此阶段不增加 UI 按钮。
2. **真实 Player 集成与持久化**：`StableDeckPlayer.kt` 仅补必要原子编辑支持；`MusicService.kt` 接恢复顺序和保存；`PlaybackPersistence.kt` 扩 schema 与兼容测试。保持单一写入者，避免两路同时修改 QueueManager。
3. **Thor 投影与交互**：服务契约冻结后修改 `ThorPlaybackBridge.kt`、`ThorShellViewModel.kt`、`ThorPlayerPages.kt` 和必要公共模型；复用现有锚定菜单、确认层及独占输入规则。
4. **统一验证**：JVM、实际 Player 仪器测试后，再进行设备交互与两设备档检查；本计划不要求立即运行任何构建、安装、ADB 或账号写入。

模块可以在稳定接口后并行，但服务编辑入口与持久化必须先闭环。完成时分别记录算法、集成、设备证据；不能把本计划、原型或测试定义记成已开发通过。
