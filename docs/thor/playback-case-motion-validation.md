# 播放期间的吹气门禁与封面呼吸

日期：2026-10-31。代码提交 `59d30dda`。仅使用双屏专用模拟器 `thor_agent_dual_api36`，ADB `emulator-5560 / server 5038`；未测试真机麦克风或播放手感。

## 行为

音乐播放时，Activity 停止吹气的 AudioRecord 采集，同时上屏 `ThorUpperScreen → ThorCdCase → CaseMotion` 禁止气流输入。门禁覆盖播放页、队列和列表等场景，不会因切页重新启用；用户的 `airflowEnabled` 偏好保留。暂停、播放器已连接且应用恢复前台后，按原偏好恢复采集。断开连接不能被当作已暂停而短暂打开麦克风。播放期间不执行吹气校准，设置状态显示“播放中已暂停吹气”。

已有吹气转动被播放打断时，清除气流请求、滤波和角速度，封面只向最近正面／背面柔和归位，目标转角最多 90°；不保留多圈的惯性目标。触摸拖动和手动转面仍有自己的所有权，播放门禁不会把它们取消。禁用期间不积存气流输入，重新启用也不会重放旧请求。

播放期间的音乐封面改用独立 `CaseBreathing` 装饰姿态：

| 分量 | 幅度 | 周期 |
|---|---|---|
| 上下浮动 | ±6dp | 5.8s |
| 左右偏转 Yaw | ±2.5° | 7s |
| 轻微俯仰 Pitch | ±1° | 8.6s |
| 轻微倾斜 Roll | ±0.6° | 9.4s |

各轴异相，避免机械地同步摇摆。淡入时间常数 0.70s，暂停衰减时间常数 0.45s，约 3.2 秒完全归零。拖动时冻结相位并快速减幅，松手后沿原相位恢复。隐藏或减少动态立即清除装饰偏移并停止帧循环；减少动态下仍保留触摸的离散翻面。音乐暂停后的列表保留原先 ±3dp／7.5s 浏览浮动，暂停播放页完全收敛后不继续装饰帧。

姿态在 Canvas 投影前叠加，不移动组件布局或触摸热区，不改变封面贴图、背景取色、歌词排版、焦点与播放控制。

## 实现与验证

所有麦克风启动入口统一为 `ThorActivity.syncAirflowCapture()`，由同一策略检查开关、权限、RESUMED、兼容页面、服务连接和播放状态；RESUMED collector 同时观察偏好与播放状态，退出时停止采集。`CaseMotion.setAirflowAllowed(false)` 另行保证即使原始气流为 1，模型也不会因扬声器反馈而旋转。

Debug 与 AndroidTest APK 构建成功，最终选定 JVM 回归 **524 项全部通过**，包括新增 17 项：采集策略 4 项、模型气流门禁 6 项、呼吸物理与过渡 7 项。过滤范围沿用 `com.ljyh.mei.thor.*`、`com.ljyh.mei.playback.*`、`*ReadableLyrics*Test`、`*LyricManualScrollStateTest` 与 `*NeteaseLoginSessionGuardTest`。

最终一轮原生回归 **29 项全部通过**，耗时 211.993 秒。新增 `ThorPlaybackCaseMotionInstrumentedTest` 的 5 项使用生产上屏接线、纯净背景和测试封套，检查真实合成姿态、实际 Canvas 像素和帧循环：

1. 音乐播放且原始气流持续为 1，只能产生小幅呼吸，不能进入 Airflow；返回专辑浏览仍保持相同门禁。
2. 暂停时先吹转超过一圈，再开始播放，气流阶段立即结束，无残留多圈旋转。
3. 从浮动位置暂停，平滑回落；收敛后实际帧计数与画面均保持静止。
4. 隐藏与减少动态停止帧循环，减少动态下触摸仍可离散翻面 180°。
5. 播放且原始气流持续为 1，真实触摸可以接管、拖动、抬升；松手后气流不能夺回模型。

同轮还运行先前的 24 项回归：PCM→Detector→Canvas 2 项、外观设置 1 项、播放背景 3 项、真实帧耗时采样 1 项、歌词 12 项、主题动效 1 项、视觉契约 4 项。这里的最大气流为合成输入，不代表已在真机播放音乐时进行了麦克风验收。

原始记录：[原生测试日志](playback-case-motion-evidence/native-tests.txt)、[构建与安装校验](playback-case-motion-evidence/build.json)。安装包为 `app/build/outputs/apk/debug/app-debug.apk`，已保留模拟器数据安装，并从 display 0 打开播放页；下屏仍由既有 Presentation 宿主管理。已安装 base.apk 的 SHA-256 与本地产物一致。
