# Thor 平台实现记录

此文件保留并行模块交接时的实现记录；后续统一构建、测试及设备结果见 [verification.md](verification.md)。
日期：2026-10-30。根集成 APK、152 项选定单测及 4 项 Room 仪器测试已经通过，专用模拟器已有双屏与真实播放记录；最新菜单错误 footer 仍需最后统一构建复验，实体硬件与麦克风仍待真机验收。设计与边界以 [实施计划](implementation-plan.md) 和 [设计稿](design.md) 为准。

## 已实现

- `thor/display/ThorDisplayHost`：上屏 Activity 作为所有者，在下屏建立普通 Presentation；下屏 ComposeView 使用自己的 Context 与实际密度，decor 与 ComposeView 均显式设置 Lifecycle、ViewModelStore、SavedStateRegistry、OnBackPressedDispatcher owner。下屏控制内容由调用者传入，不重新绑定 MusicService。
- `ThorDisplaySnapshot`：根据实际宿主与显示器像素形状识别 Thor（1920×1080＋1240×1080）和标准 RP Duo（1920×1080＋1280×960），保留实际 display ID、密度、旋转与状态；不写死 0/4。只有下屏窗口实际显示后 `isDualScreen` 才为 true。
- 显示器无实质变化时不重建已显示窗口；对已被拒绝的同一显示配置不因重复 DISPLAY_INFO 通知不断重试。显式 refresh、前台恢复或真实显示变化可重试。断开/关闭/后台释放下屏，业务状态由外部 VM 保留，音乐服务不受影响。窗口失败通过 problem 暴露给单屏兼容 UI。
- `thor/input/ThorInputRouter`：Activity 和 Presentation 共同使用一个实例。A/B/X、L3/R3及播放动作按单次边沿处理；L1/R1输出Press、Tap、HoldStart、HoldStep、Release、Cancel阶段，一级页仅Press立即切Tab，播放器Tap±15秒、长按预览/松开应用，其他二级页仍预留。长按320ms进入，370ms首步、后续每50ms最多一步，不补积压；失焦取消并吞掉迟到UP。方向键、HAT 与左摇杆使用统一输出、死区和连发，Move 标记 repeated。回调拒绝边界移动后停止该次持有，须松开/归中后才重新开始。
- 同设备数字L2/R2与BRAKE/GAS、LTRIGGER/RTRIGGER轴合并为一次上一/下一首动作；归一化轴达到0.55触发，数字释放且轴回到不高于0.05后才重新待发，低/中行程抖动不会连切。失焦、后台与控制器移除清理持有；已偏转的轴在恢复后须先归中，防止自动重播。文本编辑允许 IME 接管方向/物理 Enter/系统 Back，手柄 A/B 仍交应用表单确认/返回；名称字段的A可重新唤起键盘。文字态拦截Y/媒体播放、L2/R2、Select与肩键，扳机只观察归零；Start可隐藏IME并进入播放器，Name草稿同账号返回恢复，不提交业务。L3/R3仅在当前集合/详情存在随机/全部播放按钮时与按钮共用入口。Main兼容账号页Start走现有返回握手进入Thor播放器；返回不取消Presentation。
- `thor/ui/BindThorListFocus`：浏览列表、队列和歌词共用完整稳定 key 索引，列表内移动不依赖当前已组合行；取消旧滚动任务，等待目标行真实注册后请求焦点。尚未组合的目标也保留 item/action，快速 A/X 不作用于旧行。父层恢复识别 pending 请求，不抢到 Header。按住到边界返回 false；松开重按允许跨区。VM 保留各 scene 的稳定焦点与首项 key/像素偏移，空态清除旧预览并请求可用 CTA。
- 列表行局部配置长按 460ms、移动阈值 8dp，不影响上屏拖动；菜单开启时原有行手势取消并禁用。通用按钮有显式控制器焦点环，禁用/blocked 焦点不能确认；纯图标返回键有可读描述，封面空位使用 Album 图标。
- `thor/audio/ThorAirflowSource`：默认停止；Activity 明确开启且已获 RECORD_AUDIO 时才采集，模块不发起权限 UI，不请求音频焦点、不改变播放。16000Hz 单声道 PCM 只进入一个可复用内存缓冲，不落盘、不上传。安静/轻吹/强吹校准、噪声门控和持续输入门槛输出 0..1 强度；强度不产生选择、确认或播放命令。平滑与盒体动力学由渲染模块承担。
- 音频模块 onPause/onStop 停止采集并释放读线程资源；恢复后由 Activity 在偏好仍开启、场景允许时显式 start。系统静音/权限缺失/录音失败暴露状态，不影响普通音乐控制。
- 校准的噪声/轻吹/重吹 RMS 存入现有 `context.dataStore` 的 `thor.airflow.*_rms` 三个 Float key；一次事务保存，只有完整、有限且大小有序的三值才恢复。初次读盘完成后才决定是否自动采集安静基线，新的用户校准优先于迟到的读盘；已接受的保存任务在 Activity 销毁后完成。保存的只有数值参数，不含 PCM。
- `thor/menu/ThorContextMenu`：同锚点同宽，按各层实际内容自然高度，上限 360dp；无目标歌单仅新建入口时总高约 109dp，Name 约 201dp，水平切层与高度均 170ms 可中断，减少动态时直接替换。首次打开预留三层初始最大高度并缓存锚点，切层/异步目标刷新不重新定位；窗口/IME/密度/覆盖层原点变化才重算，大列表在锚点下方剩余空间内滚动。边缘维持总计 24dp、单侧 12dp 留白。48dp 菜单项、目标歌单直接执行、名称表单与现名、busy 禁用，业务与手柄菜单动作均由根 VM/LowerScreen 负责。
- 菜单仅订阅失败 notice 并在面板内显示最多两行的非交互错误 footer，不显示成功反馈或 Undo。footer 计入自然高度，受空间上限时正文缩短并滚动；不遮盖按钮，也不重算缓存锚点。全局 notice 在菜单开启时隐藏由根 LowerScreen 处理，避免旧成功消息覆盖 header 或触摸撤销背景业务。

## 集成调用

`ThorDisplayHost(activity, onCommand, lowerContent)` 暴露 `state` 和 `inputRouter`；Activity 将 dispatchKeyEvent/dispatchGenericMotionEvent 送给同一个 router，调用 start/close，并根据 isDualScreen 呈现真实双屏或兼容状态。文本编辑状态通过 setTextEditing 同步。列表回调须先在自己的稳定 key/完整索引上处理方向，再决定新按键是否跨焦点区；自动 repeated 到列表边界时返回 false，不能让默认 Compose 导航再移动一次。

`ThorAirflowSource(context, activity)` 暴露 strength/status/error 和 calibration 相关 Flow，提供 start/stop/calibrate/applyCalibration/close。首次启用会采集一段安静基线；录音期间打开其它 Activity、进入后台或关闭偏好必须停止。上屏 Activity 因下屏 Presentation 得到焦点而失去窗口焦点，并不等同于应用后台；入口需按整体生命周期/场景协调。

## 构建与待执行测试

已按 CI 安装 JDK21、canonical SDK `platforms;android-37.0` revision2、build-tools37.0.0、NDK27.1.12297006、CMake3.22.1；Gradle9.6.0 官方压缩包 SHA-256 已核准并准备缓存。同级 Glasmio 固定 `bbba63b2f2584c4819bd88043332e241820d1ca0`。

根集成首轮 `compileDebugKotlin` 和 APK 通过；最新 `/tmp/thor-build.log` 记录 `BUILD SUCCESSFUL in 42s`，对应 XML 合计 152 项、0 失败/错误，其中平台输入 7、设备识别 3、音频及保存值恢复 7、菜单几何 5，共 22 项均通过。根另报告 4 项 Room 仪器测试通过。菜单内错误 footer 是其后的源码补丁，仍需最后统一构建，不能以这次成功代替最新补丁验证。平台分工没有单独运行 Gradle、Git 或模拟器。

根在专用 `emulator-5560` 安装 APK，原生扫描 3 首 FLAC；注入 A 建立真实队列，Y 使真实 MediaSession 从 PLAYING 转 PAUSED，Start/Select 页面在两屏工作。该结果验证模拟器键码与真实服务路径，不代表实体手柄和厂商多屏策略已经验收。后续仍需完整长列表/持有边界/快速确认、触摸长按与菜单、系统返回/IME、失焦/重连、下屏窗口拒绝和两档画布验收。

设备验证使用 `/Users/bytedance/projects/thor-agent/scripts/thor-emulator.py` 启动/核准专用 AVD `thor_agent_dual_api36`，显式指定输出的 ADB serial，保留原有数据。参考逻辑显示为上0/下4、369dpi，但产品识别不能以这些编号为常量。不得用向默认上屏建立 Presentation 的失败退路冒充双屏成功。

## 真机待验

Thor Android13 厂商多屏窗口策略、标准 RP Duo Android16 的实际 display ID/density/自然旋转、实体手柄键码/扳机双重事件/摇杆死区、实际 IME 的手柄输入、麦克风位置/外放干扰/呼吸与说话误触/功耗、合盖、耳机与蓝牙路由仍需实机验证。当前能量与噪声门控不是能可靠区别一切声音的吹气分类器；模拟器不能替代上述结论。
