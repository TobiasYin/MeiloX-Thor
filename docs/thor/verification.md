> 最新真机验证（2026-10-31）：登录迁移与服务器同账号验证、本地及在线样本播放、412 项选定 JVM 与 10 项真机仪器通过，见 [真机记录](hardware-validation.md) 与 [真机截图](hardware-review.html)。实体手柄、吹气强弱与合盖仍待配合。

# Thor 首批原生集成验证记录

> 本文保留首批集成的历史验证记录。2026-10-31 按确认稿重建视觉后的最新结果见 [视觉还原记录](design-parity.md) 和 [设计稿／原生对照](design-parity.html)；当前为 399 项选定 JVM、9 项本轮 UI 仪器回归通过。

记录日期：2026-10-30 至 2026-10-31。首批实现已提交至 `feat/thor-dual-screen-player`，代码收口提交为 `d3845a32`。当前有可安装的原生 APK，**383 项选定 JVM 测试及累计 15 项 Android 仪器测试通过**。设计中的 18 页及全部用户故事仍有后续项，状态见 [开发计划](implementation-plan.md)。

## 1. 产物与自动验证

| 项目 | 实际结果 |
|---|---|
| Android 构建 | `assembleDebug`、`assembleDebugAndroidTest` 已通过；最后一次 UI 提示收口后再次构建与 383 项单测通过。 |
| 选定 JVM 单测 | **383 项，0 失败、0 错误、0 跳过**；包含 Thor、现有 playback 包、ReadableLyrics、Netease、LibraryCollections、Playlist、PlayerRepository 等过滤组，不代表全仓库所有测试。 |
| Android 仪器 | **15 项通过**：Room 4、真实 StableDeck 队列身份 4、输入路由 2、真实 Compose 长按/名称输入 5。删除确认另有 7 项 JVM 状态回归与原生操作证据。 |
| APK | `app/build/outputs/apk/debug/app-debug.apk`；73,642,440 字节。 |
| APK SHA-256 | `4d0de0ca521c57f09742332dfa58ffd440168492677cac0c18f4fe0b438a5184` |
| 报告 | `app/build/reports/tests/testDebugUnitTest/index.html`；XML 在 `app/build/test-results/testDebugUnitTest/`。 |
| 执行日志 | `/tmp/thor-build.log`、`/tmp/thor-instrumented-manual.log`、`/tmp/thor-ui-instrumented.log`，本机临时文件不纳入仓库。 |

Room 四项实际检查为持久化与本地/云来源隔离、创建并加入的外键失败事务回滚、18→19 迁移保留旧探针且可幂等执行、迁移后完整 Room schema 校验。历史 v18 fixture 从本次未改动的旧实体结构派生；仓库没有导出的真实历史 v18 schema，因此没有把它记作用户历史备份升级验收。

账号回归使用实际的 `ThorAccountFence` 与可控挂起，覆盖内部账号先变化而 UI 投影尚旧、load 期间切换、等待 mutation 锁和 undo guard 期间切换、A→B→A 后旧票据失效，以及同账号关闭 UI 后继续原操作。共享歌单接口还校验了出站 expectedSession。合成会话测试不发送真实账号写请求。

## 2. 原生模拟器与实际操作

使用专用 AVD `thor_agent_dual_api36`，serial 为 `emulator-5560`、Android API 36。正常 Thor 窗口为上屏 display 0 / **1920×1080**、下屏 display 4 / **1240×1080**，本轮均 369dpi。产品按真实 descriptor 识别，未把本轮 ID 写为设备常量。

| 操作链 | 实际结果 |
|---|---|
| 双屏 | 上屏 Activity 与下屏 Presentation 同时显示，各自使用显示上下文与密度。 |
| 本地扫描 | 复用原扫描器导入 3 个真实合成 FLAC，列表显示嵌入标题、艺术家、专辑及封面。 |
| 贴图 | 2,000×1,500 封面先中心裁正方形后贴盒面，不拉伸；六面盒体侧边、背面贴图和浮影有原生截图。 |
| 音频 | A 通过原 MusicService/Media3 播放，Y 后实际会话为 PAUSED。早期观察到 1,420ms；最终资料为本地晨光、暂停 462ms、缓冲 8,000ms，error=null。 |
| 播放与队列 | Start/Select 打开对应页面，B 返回；下一首插入重复 occurrence、仅删除非当前重复项、当前项菜单提供跳过，已实际操作。入队身份与 seen undo 的修复另有真实播放器仪器测试。 |
| 长按与管理菜单 | 600ms 长按在实际触摸点 (300,210) 打开菜单，松手没有再点击；X、方向、A、右进入子级、B 返回并保留入口选择均已实测。 |
| 快速名称输入 | 原本地 TextFieldValue 被旧父快照反写的问题已修复；连续输入在原生表单中保留完整末尾，5 项 Compose 回归还断言完整保存值、80 字限制与更换对象重置。 |
| 删除确认 | 进入确认层默认选取消；默认 A 返回父菜单且歌单仍在；重新进入、显式选删除后才发请求。成功清理见下一节。 |
| RP Duo 档 | 受控将下屏改为 **1280×960 / 369dpi**，列表与播放页可用；实际暂停位置始终为 462ms，未重置队列。完成后已恢复 Thor 的 1240×1080。此为分辨率模拟，非实体 Duo 验收。 |
| 上屏直接拖动 | 捕获了手指仍按住时的斜侧面与书脊，以及释放后的近正面；下屏仍为原列表，暂停位置未变化。真实吹气未开启。 |

临时 QA 资产为 `/tmp/thor-qa-assets/` 中 3 个 8 秒、44.1kHz、16-bit、双声道 FLAC，音量峰值约 −39dBFS，带淡入淡出。两张封面复用仓库 `screenshot/logo.png` 和 `screenshot/mei.png`，资产与详细 SHA 留在该临时目录，未加入生产资源。模拟器中的测试文件位于 `Music/ThorQA`。

## 3. 真实账号管理与清理

用户亲自在模拟器完成手机账号登录。原生“我的”同步身份；多次 `install -r` 与仪器测试后登录态仍保留，没有在聊天或仓库传递凭据。

| 验证 | 结果 |
|---|---|
| 在线搜索 | 同一查询的歌曲、专辑、歌单三请求均 HTTP 200，并显示实际结果。首次三请求发生 UnknownHostException，网络恢复后重试成功。 |
| 创建 | 新建唯一专用测试歌单 `ThorQA20261030`，服务端成功后创建列表为 1 项。 |
| 重命名 | 服务端 name update 返回 200，界面与刷新后的结果可见；撤销期间有连接错误，后续列表恢复原名，不据此宣称完整撤销矩阵通过。 |
| 加入歌曲 | X → 方向 → 右进入二级歌单 → B 返回 → 右重入 → A 加入；manipulate/tracks 返回 200，重新打开歌单确认 **1 首**及真实歌曲行。 |
| 移除成员 | 从自建歌单内长按歌曲，方向选“从歌单移除”再 A；服务端返回 200，详情变为 **0 首**，播放队列保持独立。 |
| 删除清理 | 显式确认后 `/api/playlist/remove` 返回 **200**，重新打开创建列表为 **0 项**。首次 ownership detail 读超时，重试后完成；没有绕过 ownership 或 session 校验。 |

只操作本轮创建的测试歌单，原有歌单成员、喜欢和收藏未主动修改。临时歌单已清理，用户登录态保留。

环境中出现过 DNS 失败及部分 10–65 秒请求。一次在线歌曲播放出现错误，尚未完成网络、会员/地区与资源失败的原因分类，也未把在线播放与完整歌词记为通过。在线搜索或管理成功不代替音源可播性验收。

## 4. 主题、比例与截图

语义主题包含微砂陶瓷、晶雾玻璃、钛金，各自白天/夜间模式。六套配色按实际材质合成校验的最低对比度为：正文 7.496:1、次要文字 4.814:1、普通焦点 5.339:1、Primary 上 onAccent 5.250:1；这是颜色审计，不是所有页面的全矩阵验收。

本轮保存了 **12 张无账号信息的原生截图**，位于 [native-screenshots](native-screenshots/manifest.json)：Thor 陶瓷日间浏览/播放、Thor 钛金夜间浏览/播放，以及 Duo 分辨率的钛金夜间浏览/播放，每场景上下屏各一张。所有 PNG 的真实尺寸已检查。

[原生截图查看器](native-review.html) 按相同像素比例整体缩放：上屏 1920×1080，Thor 下屏宽为上屏 64.583333%、Duo 下屏为 66.666667%，没有拉伸或裁剪。它是静态运行证据，实际触摸与手柄交互在模拟器中进行。图库未包含私人账号页面。

当前模拟器已恢复 **Thor 分辨率、微砂陶瓷、随系统模式、麦克风关闭**，停在原生本地音乐列表。

## 5. 明确待完成范围

- 手柄中文/英文字符面板尚未实现，名称与查询仍依赖系统 IME；具体离线字表、输入所有权及 Duo 高度预算见 [文字输入计划](text-input-plan.md)。
- 队列重排、高级清空待播、真实会话历史与 repeat/autoplay、持久化恢复语义仍待实施，见 [队列管理计划](queue-management-plan.md)。
- 在线音源可播性、错误分类、实际歌词/定位、seek 完整确认/取消、定时结束、喜欢/收藏远端完整矩阵、批量管理、下载、退出和多账号来回切换仍待验收。复杂账号与部分设置页面继续复用原客户端。
- 首帧与连续输入性能还需采样；模拟器冷启动观察到约 2–13 秒，兼容账号页返回也有窗口首帧延迟。没有承诺帧率或认为该项已经顺滑通过。
- 实体 Thor / RP Duo、真实手柄键码、麦克风校准与误触、合盖、长时功耗、失屏恢复、耳机及蓝牙音频路由均未验收。分辨率模拟与按键注入不代替真机。

## 6. 复现环境与命令

JDK 21 位于 `/Library/Java/JavaVirtualMachines/microsoft-21.jdk/Contents/Home`，Android SDK 为 `/Users/bytedance/Library/Android/sdk`，使用项目原有 Gradle 9.6、platforms;android-37.0 revision2、build-tools 37.0.0、NDK 27.1.12297006 和 CMake 3.22.1。同级源码 `../Glasmio` 固定 `NEORUAA/Glasmio@bbba63b2f2584c4819bd88043332e241820d1ca0`，未降级项目依赖。模拟器复用 `/Users/bytedance/projects/thor-agent/scripts/thor-emulator.py`。

构建及选定单测：

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest \
  --tests 'com.ljyh.mei.thor.*' --tests 'com.ljyh.mei.playback.*' \
  --tests '*ReadableLyrics*Test' --tests '*Netease*Test' \
  --tests '*LibraryCollections*Test' --tests '*Playlist*Test' \
  --tests '*PlayerRepository*Test' --console=plain --max-workers=4
```

Android 仪器分两次实际执行：先 Room、StableDeck、输入路由共 10 项，再 Compose 输入 5 项；均用手动 AndroidJUnitRunner，安装使用 `adb -s emulator-5560 install -r` 保留数据，没有在用户登录后执行卸载目标应用的 connected 流程。

```sh
adb -s emulator-5560 shell am instrument -w -r \
  -e class com.ljyh.mei.thor.data.ThorLocalStoreInstrumentedTest,com.ljyh.mei.playback.StableDeckQueueIdentityInstrumentedTest,com.ljyh.mei.thor.input.ThorTextInputInstrumentedTest \
  com.neoruaa.meilox.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5560 shell am instrument -w -r \
  -e class com.ljyh.mei.thor.ui.ThorMenuInputInstrumentedTest \
  com.neoruaa.meilox.test/androidx.test.runner.AndroidJUnitRunner
```
