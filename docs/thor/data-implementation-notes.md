# Thor 数据层与播放组页面实现记录

本文件记录当前原生增量的实际代码能力、静态审查结果和待验证事项。设计预览中的本地模拟结果不作为原生服务、登录或真机测试证据。该批开发没有主动发送真实账号的创建、删除、喜欢或收藏请求。

## 已接入的能力

`ThorMusicRepository` 为 Hilt `@Singleton`，通过构造器复用现有 `AppDatabase`、`SongRepository`、`LibraryCollectionsRepository`、`PlaylistRepository`、`PlayerRepository`、`HomeRepository` 和 `SearchRepository`。公共状态为 `StateFlow<ThorLibraryState>`，公共操作保持实施计划冻结的 `refresh/load/search/toggleLike/setCollected/addToPlaylist/removeFromPlaylist/createPlaylist/renamePlaylist/deletePlaylist/undo` 接口。

- **本地内容**：读取原有歌曲表中的本地歌曲、下载项和历史记录；按专辑名及首位艺术家生成稳定本地专辑身份。本地与网易云歌曲使用不同来源身份，添加本地歌单时按来源加歌曲 ID 去重，保留原顺序。
- **账号音乐库**：复用既有账号恢复、Cookie/DataStore 和服务仓库，读取喜欢歌曲、收藏专辑、自建歌单及收藏歌单。喜欢歌曲与收藏专辑分别建模；自建歌单和只读收藏歌单按服务端创建者分开投影。
- **首页与发现**：首页使用真实历史与首页服务中可路由的实际资源；发现使用现有精品歌单接口。无法识别、没有有效资源 ID 或不支持的首页模块不会被伪装成可播放卡片。没有样例歌曲兜底。
- **详情与搜索**：专辑只有明确持有完整曲目时才复用快照；可变云歌单每次读取最新完整曲目，不能把之前的详情快照当作修改后的结果。歌曲、专辑和歌单搜索并发调用现有服务，并合并设备内真实匹配项。
- **管理操作**：设备喜欢、专辑收藏、本地歌单创建、重命名、删除及成员增删持久化；云端喜欢、收藏、歌单成员增删、创建、重命名及删除复用现有仓库。远程只读歌单的内容修改必须先由服务读取并确认创建者属于当前账号。

`ThorPlayerPages.kt` 提供播放器、播放队列、歌词浏览和定时停止四页。播放页使用现有播放桥接状态，紧凑地安排标题、进度与主控、真实下一首以及三个功能入口，不复用列表页 mini 播放器或底部动作 dock。进度草稿由 ViewModel 管理；歌词直接观察现有 LyricManager 数据，支持普通歌词、逐字歌词内容与译文显示，按行选择定位。定时停止复用原 SleepTimer，包含定时分钟数、本曲结束和关闭定时。队列按独立 entry ID 操作，重复歌曲不会被合并成同一个菜单对象。队列和歌词均接入全量 stable-key 列表焦点绑定，手柄导航可先滚动再聚焦尚未组合的行，歌词 key 还包含歌曲来源及时间身份。高级清空待播与队列移动的完整语义留待下一实施批次，本页没有展示未完成的清空按钮。

## 持久化与迁移

仍使用原 `app_database`，版本由 18 升到 19，新增以下三个 Room 实体及 DAO，没有第二数据库，也没有破坏性迁移：

| 表 | 用途与约束 |
| --- | --- |
| `thor_favorite` | 设备侧喜欢与收藏；主键为 `scope + kind + itemId + local`，来源及收藏实体类型独立。 |
| `thor_local_playlist` | 本地歌单 UUID、名称及创建/修改时间。 |
| `thor_local_playlist_track` | 歌单成员完整 MediaMetadata JSON、来源身份和位置；主键为歌单加歌曲身份，歌单删除时成员外键级联删除。 |

18→19 使用 `CREATE TABLE IF NOT EXISTS` 和 `CREATE INDEX IF NOT EXISTS`；静态核对了非空列、复合主键、可空封面、外键级联规则与实体声明。DDL 不修改或清空旧表。创建歌单并加入成员、追加去重和替换成员均使用 Room 事务；成员失败时创建不得留下空歌单。歌单修改时间用数据库内的单调递增值兜底，避免同一毫秒连续修改无法区分；本地重命名撤销核对完整修改后记录。

离线设备收藏不会自动归属登录后的网易云账号。未登录时保存的云歌曲喜欢留在设备域；登录后显示当前账号的云音乐库与设备本地歌曲喜欢。本地歌单保持可用，不执行隐式云同步。`songLikes` 这一沿用数字 ID 的公共投影只包含云歌曲；本地喜欢通过完整 `Song(id, local)` 身份判断，避免来源之间数字 ID 相同导致串状态。

## 会话、只读保护与撤销审查

凭据只在既有账号和数据仓库内部流动；Thor UI 状态只收到用户 ID、昵称、头像、会话类型和代次。没有新增 Session 实现，MediaMetadata 与本地管理表也不存 Cookie。错误文案采用有限公开信息，不把服务异常中的请求细节传给页面。

账号变化时取消旧刷新任务、清除旧账号投影与乐观收藏状态，并重置既有喜欢状态缓存。每类刷新带请求 revision；状态回填在 `StateFlow.update` 内重新核对账号、账号代次及 revision，避免先检查后切换账号的旧响应回填。详情与搜索也在远程读后检查账号代次。只有查询开始时已有、且当前值仍相同的乐观收藏覆盖，才能随对应成功刷新被清除。

喜欢歌曲另有独立的成员状态覆盖，包含账号代次、操作版本、期望喜欢状态及本次真实歌曲元数据。取消喜欢会从旧列表快照过滤云歌曲；喜欢后即使旧快照为空，也保留本次歌曲。只有在该版本已存在时启动、并成功读到期望成员状态的后续请求才能确认并清除覆盖。请求失败或仍返回旧状态时继续保留；新操作版本和新账号代次不能被旧读确认。该逻辑通过纯投影函数测试，不在 JVM monitor 内执行挂起操作。

变更通过单一 mutation mutex 串行。每次操作捕获账号上下文，撤销继续携带原账号和原代次；获得锁后再核对上下文，因此等待期间切账号不能让旧撤销使用新 Session。相同账号退出后再进入，也会因代次变化使旧撤销失效。局部状态校验在锁内执行，收藏、喜欢和重命名撤销不能悄悄覆盖已观察到的后续修改。本地撤销还核对数据库中的实际行或成员快照。

云歌单增删、重命名和删除必须读取服务端所有者；成员修改不能将本地歌曲写入云歌单。添加成员先排除已存在的 ID；移除只处理目标歌单实际包含的曲目，撤销不会凭空加入原本不在歌单中的歌曲。成员撤销核对预期成员集合；移除的撤销在重新加入后调用既有排序接口恢复原顺序。远端删除歌单没有伪造可恢复的撤销。

云端“新建并加入”包含两个服务请求。加入失败时，仅当账号仍一致、歌单仍属于当前用户、名称仍为本次名称且仍为空，才尝试补偿删除；否则返回实际失败并保留已创建歌单的 ID。服务端没有事务或条件写接口，已经发出的请求无法收回，外部客户端也可能并发编辑；客户端的身份、快照和排序校验不能等同于服务端原子事务。若撤销中重新加入成功但排序请求失败，最终顺序仍需用户检查。

远程重命名接口为 `/api/playlist/update/name`，请求字段为 `id/name`，依据上游一手模块核对：[playlist_name_update.js](https://github.com/xgxdmx/NeteaseMusic-API/blob/master/module/playlist_name_update.js)。现有 create/delete 增加可选 expected-session 参数，原调用方式保持兼容；Thor 路径始终传入会话指纹，重命名同样携带会话校验。

## 验证记录与待验

本批没有自行运行 Gradle 或设备测试，由根任务统一执行构建和测试。集中构建首轮报告了 Kotlin 2.4 对 `synchronized` 内定义挂起回调的诊断；已将挂起回调的构造移到 JVM monitor 外，只在 monitor 内保存函数引用。该修正需以之后的集中构建结果为准，不能将首轮失败写成通过。

| 验证对象 | 已编写覆盖 | 本记录的执行状态 |
| --- | --- | --- |
| `ThorDataProjectionTest` | 来源去重与顺序、稳定专辑 ID 的区域无关性、完整曲目判断、首页实际资源过滤、本地与云同数字 ID 的喜欢隔离。 | 未执行。 |
| `ThorPlaylistSessionGuardTest` | 会话不匹配时 create/delete 零请求；create 返回前切账号；rename 所有者不符；所有者读取期间切账号。仅使用合成代理传输。 | 未执行，没有真实账号写入。 |
| `ThorLikeProjectionTest` | 七个定向用例：取消最后喜欢过滤旧快照、保留同 ID 本地歌曲、刚喜欢元数据保留、请求开始版本与期望状态双重确认、二次切换不能被旧读确认、账号切换清覆盖、确认后更新服务元数据且不重复。 | 本轮新编写，待统一增量测试。 |
| `ThorLocalStoreInstrumentedTest` | 关闭再打开数据库后的收藏和成员持久性、来源隔离、成员去重、删除级联及事务回滚。 | 未执行，需 Android 环境。 |
| 18→19 DDL 与 Room 校验 | DDL 重入保留新旧数据；从未改变的旧实体结构派生的 v18 fixture，经迁移后由 Room 校验完整结构。 | 未执行。仓库没有导出的历史 v18 schema；该 fixture 不等同于用户历史数据库备份。 |
| 四个播放组页面 | 已按现有 VM、bridge、LyricData/SyncedLyrics 和 SleepTimer 静态核对接口与主题接入。 | 仍需统一编译、模拟器/真机焦点与布局验收。 |

后续必须完成：统一构建及 JVM 测试；Android 上执行持久化与迁移测试并使用真实旧版本数据库补验升级；账号 A→退出→账号 B 时旧请求、旧弹层与旧撤销不得回填；普通用户自建与只读收藏歌单分别验证权限失败；断网、登录失效、歌曲不可播和部分远程失败保留实际状态；Thor 与 Pocket Duo 固定画布下长队列、长歌词、手柄连续移动、触摸进度编辑与定时结束。真实账号写入回归应使用明确指定的测试歌单，不以开发验证名义修改用户已有歌单。

## 文件落点

- `app/src/main/java/com/ljyh/mei/thor/data/ThorMusicRepository.kt`
- `app/src/main/java/com/ljyh/mei/thor/data/ThorLikeProjection.kt`
- `app/src/main/java/com/ljyh/mei/thor/data/local/ThorLocalStore.kt`
- `app/src/main/java/com/ljyh/mei/di/AppDatabase.kt`
- `app/src/main/java/com/ljyh/mei/data/network/api/ApiService.kt`
- `app/src/main/java/com/ljyh/mei/data/repository/PlaylistRepository.kt`
- `app/src/main/java/com/ljyh/mei/thor/ui/ThorPlayerPages.kt`
- `app/src/test/java/com/ljyh/mei/thor/data/ThorDataProjectionTest.kt`
- `app/src/test/java/com/ljyh/mei/thor/data/ThorPlaylistSessionGuardTest.kt`
- `app/src/test/java/com/ljyh/mei/thor/data/ThorLikeProjectionTest.kt`
- `app/src/androidTest/java/com/ljyh/mei/thor/data/ThorLocalStoreInstrumentedTest.kt`
