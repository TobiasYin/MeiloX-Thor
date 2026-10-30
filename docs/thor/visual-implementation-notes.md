# Thor 原生视觉实现与验证记录

此文件保留并行模块交接时的实现记录；后续统一构建、测试及设备结果见 [verification.md](verification.md)。
更新于 2026-10-30。本文记录原生主题、材质、CD 盒和上屏的实际实现，设计基线仍是 [design.md](design.md) 与 [theme-system.md](theme-system.md)。HTML 概念预览不算原生验收证据。

## 已实现的公共契约

- `thor/theme/ThorTheme.kt`：`ThorTheme(preferences, content)` 提供 Ceramic、Glass、Titanium 三种材质的完整 Day / Night 色板，并桥接 Material3 的颜色、形状和字体。`LocalThorTheme.current` 暴露平铺的语义 tokens，供上下屏共用。`ThorAppearance.System` 跟随系统；减少动态与减少透明度也属于主题投影。切换主题不拥有导航状态。
- `thor/components/ThorMaterials.kt`：可选的 `Modifier.thorMaterial`、`ThorMaterialSurface`、`ThorActionButton` 提供 Panel / Raised / Inset / Primary 等材质。控件保持原有几何尺寸，按下通过光影变化反馈，默认交互区域至少 48dp。选中态用实色；Primary 使用 `onAccent`，普通按钮使用 `text`；禁用态用不透明的 `secondary`。2dp 的焦点轮廓不依赖阴影。
- `thor/artwork/ThorCdCase.kt`：原生 Canvas 投影真实有厚度的六面盒体，背面 UV 保持正向，无背封时复用正封。图片通过 Coil 的缓存与固定解码尺寸取得 Bitmap，不逐帧解码；画笔、路径和投影矩阵复用。`Matrix.setPolyToPoly` 贴图，不新增大型 3D 依赖。加载失败显示真实名称和艺术家的中性占位；缺图文字位于反光层之上并保留不透明语义颜色。盒脊与占位的截断按 Unicode code point 处理，不截断 UTF-16 代理对；格式化只随作品变化进行。
- `CaseMotion` 为不依赖 Android 的物理模型：输入强度为 0…1，气流只施加单向加速，力度影响转速，停吹后减速；拖动接管并保留释放方向与惯性；结束吸附至 `−16° + k × 180°` 的近正面/近背面。轻触、键盘左右和可访问性动作可转动盒体。封面切换有 320ms 小幅入场，静态场景不持续请求动画帧；隐藏/停止生命周期会清除惯性并停帧。减少动态使用离散翻面，禁用连续浮动、气流旋转和入场。
- `thor/upper/ThorUpperScreen.kt`：消费 `ThorItem`、`ThorPreferences`、气流、`LyricData` 和时间 provider。浏览显示模型与真实资料；播放场景左侧模型、右侧歌词，曲名/艺术家保持同组。播放场景 `playbackScene` 与实际 `playing` 分开，避免浏览预览改动当前播放。没有播放项时显示中性空态，不构造示例专辑。
- 上屏复用 `PlayerLyricsView` 的数据与同步辅助函数，增加可选 `ReadableLyricsPalette` 分支；未提供该参数时保留原客户端行为。Thor 的当前歌词、普通歌词、翻译和音译均使用不透明的 `text` / `secondary`；不使用库默认的低透明度遮罩。逐字歌词保留同步着色、翻译和伴唱；减少动态关闭逐字渐变及平滑自动滚动。上屏歌词不可交互，歌词定位仍由下屏独立处理。

上屏公开签名为：

```kotlin
@Composable
fun ThorUpperScreen(
    item: ThorItem?,
    playing: Boolean,
    preferences: ThorPreferences,
    airflow: Float,
    lyricData: LyricData? = null,
    positionProvider: () -> Long = { 0L },
    onOpenPlayer: () -> Unit = {},
    modifier: Modifier = Modifier,
    playbackScene: Boolean = false,
    visible: Boolean = true,
)
```

`positionProvider` 必须读取根层的可观察进度状态，而不是只调用无 Compose State 的 player getter。组件不创建播放器、连接服务、加载歌词或申请麦克风权限；同一个真实播放连接和进度源由入口集成。气流只影响模型，不选择列表项，不开始播放。

## 六套颜色与实际材料叠加审计

采用 WCAG 的 sRGB 相对亮度公式检查实际 Kotlin token。背景集合包含 `background`、`surface`、`elevated`、`inset`、`selected`、`raisedTop`、`raisedBottom`，另合成 Panel / Inset / Raised 的最亮反光端点，以及 Titanium 的 Panel / Inset 细纹。以集合中的最低值记录；Primary 独立检查 `onAccent` 对渐变两端的最低值。顶部 1dp 边缘不进入文字区域，焦点轮廓为不透明颜色。上屏作品光晕限制在模型区域，歌词背景维持语义 `background`。

| 材质 | 模式 | `text` 最低 | `secondary` 最低 | 普通焦点最低 | `onAccent` / Primary 最低 |
|---|---|---:|---:|---:|---:|
| Ceramic | Day | 11.497:1 | 5.204:1 | 5.797:1 | 5.668:1 |
| Ceramic | Night | 8.405:1 | 5.279:1 | 6.571:1 | 7.739:1 |
| Glass | Day | 10.918:1 | 5.281:1 | 5.921:1 | 5.250:1 |
| Glass | Night | 7.685:1 | 5.166:1 | 5.339:1 | 6.486:1 |
| Titanium | Day | 9.481:1 | 4.928:1 | 5.989:1 | 6.280:1 |
| Titanium | Night | 7.496:1 | 4.814:1 | 5.616:1 | 7.026:1 |

所有普通文字与辅助文字至少 4.5:1，普通焦点至少 3:1。Primary 的焦点使用 `onAccent`，其最低值与表中 Primary 相同。该数值不代表任意封面图内印刷文字的可读性，也不代替截图与真机检查。

审计修正了两个实际叠加问题：Glass 夜间的 `paneGlint` 从概念 token 的 `#26BDD3FF` 降至 `#13BDD3FF`；Titanium 夜间从 `#17FFF3CA` 降至 `#0AFFF3CA`。金属细纹只用于宽面板和凹槽，不叠加于 Raised / Primary 或选中行。减少透明度后关闭反光与细纹，保留不透明底色。下屏若自行叠加新的反光、蒙层或透明度，需另按最终合成颜色检查。

## 已运行的自动验证

为避免并发 Gradle 构建占用同一构建目录，本模块使用本机缓存的 Kotlin 2.4.21 / JDK 21 编译器与 JUnit 4.13.2 独立验证纯逻辑；未运行 Gradle。最初 9 项物理/几何测试已通过，补充 Unicode 与进度边界后共 **14 / 14 通过**：

| 测试类 | 数量 | 覆盖 |
|---|---:|---|
| `CaseMotionTest` | 6 | 气流单向与力度、停吹吸附、拖动优先与释放方向、隐藏清理、减少动态、异常样本及长帧间隔 |
| `CaseGeometryTest` | 3 | 背面贴图方向、侧面厚度与剔除、有限坐标及深度顺序 |
| `CaseLabelsTest` | 2 | 补充平面汉字/emoji 完整截断，空字符串及长度边界 |
| `ReadableLyricsProgressTest` | 3 | 逐字边界不拆代理对，进度与零时长音节，Int 时间差不溢出 |

物理、投影、标签 11 项从当前源码直接编译运行；歌词 3 项使用已成功主编译的 classes 及 Kotlin friend-path 运行。测试文件保留在 `app/src/test`，供根集成统一 Gradle 执行。颜色检查是上述六套实际绘制值的数值审计，未添加镜像常量的伪单元测试。

根集成已报告首轮 `compileDebugKotlin` 成功，该轮包括主题、材质、模型、上屏与可选可读歌词分支。此后反光强度、细纹范围、占位标签和封面入场的最终补丁仍需以根层统一编译、Gradle 单测与 assemble 结果为准。

## 仍须在集成与设备验证的项目

1. 在 Thor 与 RP Duo 真实窗口密度下检查三材质 × 日夜的上下屏完整页面、实际字体缩放、歌词长行/翻译/音译，以及模型与列表的焦点可见性。
2. 验证已缓存、首次加载、离线、无封面、快速切换与正背不同图片；确认封面未镜像、侧面不会消失，快速切换不会回显旧图。
3. 验证真实手柄、触摸接管、释放惯性、停吹归位、减少动态与减少透明度；麦克风校准、环境声误触、录音停止和功耗由平台气流实现一起验收。
4. 双屏接入/失联、应用退后台、合盖/休眠、暂停时静态场景：确认没有隐藏动画持续请求帧，没有额外进度 ticker 或播放连接，回到前台仍使用同一正在播放项。
5. 在目标硬件上测帧时间、图片解码内存与长时间功耗。当前无真机帧率或能耗数据，纯物理测试不能代替真实绘制性能结论。
