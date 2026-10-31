# Thor 平台实现记录

此文件保留并行模块交接时的实现记录；后续统一构建、测试及设备结果见 [verification.md](verification.md)。
首批平台交接记录整理于 2026-10-30，历史环境和测试快照保留在下文。当前控制器输入与焦点的实现范围、417 项选定 JVM／29 项真机仪器记录及最终复验结论统一见 [控制器验证](controller-validation.md)；设计边界见 [实施计划](implementation-plan.md) 和 [设计稿](design.md)。

## 当前输入修订（2026-10-31）

本轮采用列表/显式选项 scope、长 Select 页面操作及首页二次 B 契约，实现与回归范围统一见 [控制器验证](controller-validation.md)。下方首批构建数字属于历史交接快照，不替代本轮记录。

## 实现与集成契约

- `thor/display/ThorDisplayHost`：上屏 Activity 作为所有者，在下屏建立普通 Presentation；下屏 ComposeView 使用自己的 Context 与实际密度，decor 与 ComposeView 均显式设置 Lifecycle、ViewModelStore、SavedStateRegistry、OnBackPressedDispatcher owner。下屏控制内容由调用者传入，不重新绑定 MusicService。
- `ThorDisplaySnapshot`：根据实际宿主与显示器像素形状识别 Thor（1920×1080＋1240×1080）和标准 RP Duo（1920×1080＋1280×960），保留实际 display ID、密度、旋转与状态；不写死 0/4。只有下屏窗口实际显示后 `isDualScreen` 才为 true。
- 显示器无实质变化时不重建已显示窗口；对已被拒绝的同一显示配置不因重复 DISPLAY_INFO 通知不断重试。显式 refresh、前台恢复或真实显示变化可重试。断开/关闭/后台释放下屏，业务状态由外部 VM 保留，音乐服务不受影响。窗口失败通过 problem 暴露给单屏兼容 UI。
- `thor/input/ThorInputRouter`：Activity/Presentation 共用实例。A/B/X、Start、L3/R3 与播放动作不随系统 repeat 重发；L1/R1 保留 Press/Tap/HoldStart/HoldStep/Release/Cancel，一级页 Press 切 Tab，正常 Player 仍按原肩键合同调整进度，其他二级页不跨级。Select 短按 UP 发 ToggleQueue，500ms 长按发一次 PageActions，长按后 UP 不开队列；reset/失焦后的持有须先 UP，迟到释放不执行。方向、HAT 与左摇杆统一输出 Move(repeated)，到当前 scope 边界停止，不以新按越到页头/页脚/Tab/迷你条。
- 同设备数字 L2/R2 与 BRAKE/GAS、LTRIGGER/RTRIGGER 轴保持共用锁存：0.55 触发，数字 UP 且轴≤0.05 才待发；中段回弹不连切。失焦/reset 清持有，恢复后先归中。System IME 继续拥有文字/方向/物理 Enter/系统 Back；A/B 表单确认/返回及 Start 全局入口保留。编辑时 Y/L2/R2/L3/R3 不穿透为业务，Select/肩键保留 Native 交接并观察 UP 清理；系统媒体播放键按现有独立来源路由。Name 只在明确编辑区域操作，Start 同账号挂起草稿、返回恢复不提交。
- `thor/ui/BindThorListFocus`：浏览、Queue、歌词使用完整稳定 key 索引处理未组合行，取消旧滚动任务，等目标注册再请求焦点；快速 A/X 不作用旧行。新进入列表选第一项，包括 Queue；每次新搜索结果从首项开始，不继承旧查询焦点；返回恢复 scene 的列表 key、首项 key 与像素偏移，队列/歌词定位当前的唯一入口是长 Select 页面操作菜单，独立底部按钮已移除。注册与请求受显式 scope/owner 约束；无 scope 默认关闭，边界不回落默认 Compose，也不因松开新按进入 Header。空态清旧预览，在显式允许区域或页面菜单提供下一步。
- 列表行局部配置长按 460ms、移动阈值 8dp，不影响上屏拖动；菜单开启时原有行手势取消并禁用。通用按钮有显式控制器焦点环，禁用/blocked 焦点不能确认；纯图标返回键有可读描述，封面空位使用 Album 图标。
- `thor/audio/ThorAirflowSource`：默认停止；Activity 明确开启且已获 RECORD_AUDIO 时才采集，模块不发起权限 UI，不请求音频焦点、不改变播放。16000Hz 单声道 PCM 只进入一个可复用内存缓冲，不落盘、不上传。安静/轻吹/强吹校准、噪声门控和持续输入门槛输出 0..1 强度；强度不产生选择、确认或播放命令。平滑与盒体动力学由渲染模块承担。
- 音频模块 onPause/onStop 停止采集并释放读线程资源；恢复后由 Activity 在偏好仍开启、场景允许时显式 start。系统静音/权限缺失/录音失败暴露状态，不影响普通音乐控制。
- 校准的噪声/轻吹/重吹 RMS 存入现有 `context.dataStore` 的 `thor.airflow.*_rms` 三个 Float key；一次事务保存，只有完整、有限且大小有序的三值才恢复。初次读盘完成后才决定是否自动采集安静基线，新的用户校准优先于迟到的读盘；已接受的保存任务在 Activity 销毁后完成。保存的只有数值参数，不含 PCM。
- `thor/menu/ThorContextMenu`：同锚点同宽，按各层实际内容自然高度，上限 360dp；无目标歌单仅新建入口时总高约 109dp，Name 约 201dp，水平切层与高度均 170ms 可中断，减少动态时直接替换。首次打开预留三层初始最大高度并缓存锚点，切层/异步目标刷新不重新定位；窗口/IME/密度/覆盖层原点变化才重算，大列表在锚点下方剩余空间内滚动。边缘维持总计 24dp、单侧 12dp 留白。48dp 菜单项、目标歌单直接执行、名称表单与现名、busy 禁用，业务与手柄菜单动作均由根 VM/LowerScreen 负责。
- 菜单仅订阅失败 notice 并在面板内显示最多两行的非交互错误 footer，不显示成功反馈或 Undo。footer 计入自然高度，受空间上限时正文缩短并滚动；不遮盖按钮，也不重算缓存锚点。全局 notice 在菜单开启时隐藏由根 LowerScreen 处理，避免旧成功消息覆盖 header 或触摸撤销背景业务。

## 集成调用

`ThorDisplayHost` 接收宿主、命令回调与下屏内容，暴露 state/inputRouter；Activity 与 Presentation 将事件送给同一个 Router，并按实际显示状态启停。`ThorInputRouter(onNonBackInput = ..., onCommand = ...)` 可在非 Back 的有效输入时清首页退出计时，保留旧单命令回调构造。recognized 非 Back DOWN 即通知，Select 尚未发命令的按下也会清计时；摇杆/帽键超过有效死区或扳机行程>0.05 才通知，neutral 与漂移不通知。触摸、导航、失焦和后台清理由宿主/VM 接入。导航/菜单 scope 切换 reset 持有输入，seek 进入编辑保留肩键 hold。Legacy 账号页只复用既有流程/返回握手，不属于本轮 Thor 壳焦点改写。文本编辑通过 setTextEditing 同步；列表只消费自己的完整 key scope，边界返回 false 后保持本区，不再由默认 Compose 额外移动。

`ThorExitGuard` 是纯状态策略：onBack(nowMs, repeated) 返回 Armed/Exit/IgnoreRepeat；首次提示，≤2,000ms 的第二次独立 Back 允许退出，重复 DOWN 不确认也不延期，过期或时钟回退重新 Armed。clear() 清计时，Exit 消费状态，armedUntilMs 仅供宿主提示；helper 不自行 finish Activity。Player 仅播放按钮主焦点；显式 seek 仅 Apply，方向左/右±10秒、A 应用/B 取消，期间再长按肩键仍沿用松开提交；Appearance/Controls/Timer 及 Name 仅在明确选项/编辑区域导航。

`ThorAirflowSource(context, activity)` 暴露 strength/status/error 和 calibration 相关 Flow，提供 start/stop/calibrate/applyCalibration/close。首次启用会采集一段安静基线；录音期间打开其它 Activity、进入后台或关闭偏好必须停止。上屏 Activity 因下屏 Presentation 得到焦点而失去窗口焦点，并不等同于应用后台；入口需按整体生命周期/场景协调。

## 首批环境与历史验证

已按 CI 安装 JDK21、canonical SDK `platforms;android-37.0` revision2、build-tools37.0.0、NDK27.1.12297006、CMake3.22.1；Gradle9.6.0 官方压缩包 SHA-256 已核准并准备缓存。同级 Glasmio 固定 `bbba63b2f2584c4819bd88043332e241820d1ca0`。

首批集成当时 `compileDebugKotlin` 和 APK 通过；临时 `/tmp/thor-build.log` 记录 `BUILD SUCCESSFUL in 42s`，对应 XML 合计 152 项、0 失败/错误，其中平台输入 7、设备识别 3、音频及保存值恢复 7、菜单几何 5，共 22 项均通过。根另报告 4 项 Room 仪器测试通过。以上为当时快照，本轮源码范围和复验结论以 [控制器验证](controller-validation.md) 为准。平台分工没有单独运行 Gradle、Git 或模拟器。

根在专用 `emulator-5560` 安装 APK，原生扫描 3 首 FLAC；注入 A 建立真实队列，Y 使真实 MediaSession 从 PLAYING 转 PAUSED，Start/Select 页面在两屏工作。该结果验证模拟器键码与真实服务路径，不代表实体手柄和厂商多屏策略已经验收。后续仍需完整长列表/持有边界/快速确认、触摸长按与菜单、系统返回/IME、失焦/重连、下屏窗口拒绝和两档画布验收。

设备验证使用 `/Users/bytedance/projects/thor-agent/scripts/thor-emulator.py` 启动/核准专用 AVD `thor_agent_dual_api36`，显式指定输出的 ADB serial，保留原有数据。参考逻辑显示为上0/下4、369dpi，但产品识别不能以这些编号为常量。不得用向默认上屏建立 Presentation 的失败退路冒充双屏成功。

## 真机待验

Thor Android13 厂商多屏窗口策略、标准 RP Duo Android16 的实际 display ID/density/自然旋转、实体手柄键码/扳机双重事件/摇杆死区、实际 IME 的手柄输入、麦克风位置/外放干扰/呼吸与说话误触/功耗、合盖、耳机与蓝牙路由仍需实机验证。当前能量与噪声门控不是能可靠区别一切声音的吹气分类器；模拟器不能替代上述结论。
