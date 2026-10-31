package com.ljyh.mei.thor.ui

import android.os.Build
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import com.ljyh.mei.constants.*
import com.ljyh.mei.playback.EqualizerBand
import com.ljyh.mei.playback.EqualizerPreset
import com.ljyh.mei.playback.PlayerConnection
import com.ljyh.mei.playback.UsbAudioStatus
import com.ljyh.mei.thor.ThorShellViewModel
import com.ljyh.mei.thor.components.ThorActionButton
import com.ljyh.mei.thor.components.ThorSurfaceRole
import com.ljyh.mei.thor.model.ThorItem
import com.ljyh.mei.thor.settings.*
import com.ljyh.mei.thor.theme.LocalThorTheme
import java.util.Locale

private data class AudioSettingRow(
    val key: String,
    val title: String,
    val value: String = "",
    val description: String = "",
    val enabled: Boolean = true,
    val checked: Boolean? = null,
    val action: () -> Unit,
)

private data class AudioChoiceItem(val value: String, val label: String, val enabled: Boolean = true,
    val description: String = "")

private class AudioSettingEditor {
    var choice: ThorAudioChoice? by mutableStateOf(null)
    var number: ThorAudioNumber? by mutableStateOf(null)
    var draft by mutableFloatStateOf(0f)
    val open get() = choice != null || number != null
    fun close() { choice = null; number = null }
    fun edit(option: ThorAudioNumber, value: Float) {
        choice = null
        number = option
        draft = finiteAudioValue(value, option.minimum, option.minimum..option.maximum)
    }
    fun adjust(direction: Int) {
        val option = number ?: return
        draft = (draft + direction * option.step).coerceIn(option.minimum, option.maximum)
    }
}

/** A Thor-owned page: no legacy navigation controller, Intent, or extra service binding. */
@Composable
@UnstableApi
internal fun ThorAudioSettingsPage(
    vm: ThorShellViewModel,
    registry: ThorFocusRegistry,
    onBack: () -> Unit,
    onOpenEqualizer: () -> Unit,
    playerConnection: PlayerConnection?,
    pageActions: ThorPageActionBindings? = null,
    settings: ThorAudioSettingsViewModel = viewModel(),
) {
    val state by settings.state.collectAsStateWithLifecycle()
    val error by settings.error.collectAsStateWithLifecycle()
    val editor = remember { AudioSettingEditor() }
    val latestConnection by rememberUpdatedState(playerConnection)
    val usbStatus = playerConnection?.service?.audioOutputStatus?.collectAsStateWithLifecycle()?.value ?: UsbAudioStatus.Inactive
    val cancelOrBack = { if (editor.open) editor.close() else onBack() }
    if (editor.open) BindThorPageAction(pageActions, "page:back", editor::close)
    if (state.loaded && state.processingAvailable && !editor.open) {
        BindThorPageAction(pageActions, "audio:equalizer", onOpenEqualizer)
    }
    val choose: (ThorAudioChoice, String) -> Unit = { option, value ->
        if (option == ThorAudioChoice.Quality) {
            val quality = MusicQuality.valueOf(value)
            if (quality != state.quality) {
                latestConnection?.changeQuality(quality, preferOnlineSource = false)
                    ?: settings.setQualityWithoutPlayer(quality)
            }
        } else settings.setChoice(option, value)
        editor.close()
    }
    AudioSettingsFrame("音频与播放", registry, cancelOrBack, error) {
        when {
            editor.choice != null -> AudioChoicePage(vm, registry, checkNotNull(editor.choice), state, choose)
            editor.number != null -> AudioNumberPage(vm, registry, editor) { option, value -> settings.setNumber(option, value); editor.close() }
            else -> {
                val rows = buildList {
                    if (error != null && !state.loaded) add(AudioSettingRow("audio:retry", "重新读取音频设置", action = settings::retry))
                    fun toggle(id: String, title: String, value: Boolean, option: ThorAudioToggle,
                        description: String = "", enabled: Boolean = true) {
                        add(AudioSettingRow("audio:$id", title, if (value) "开启" else "关闭", description,
                            enabled = state.loaded && enabled, checked = value) { settings.toggle(option) })
                    }
                    fun choice(id: String, title: String, value: String, option: ThorAudioChoice, description: String = "") {
                        add(AudioSettingRow("audio:$id", title, value, description, state.loaded) { editor.choice = option })
                    }
                    add(AudioSettingRow("audio:equalizer", "十段均衡器", if (state.equalizer) "开启" else "关闭",
                        if (state.processingAvailable) "" else "USB 原样输出绕过均衡器",
                        state.loaded && state.processingAvailable, action = onOpenEqualizer))
                    choice("quality", "默认播放音质", state.quality.explanation, ThorAudioChoice.Quality)
                    choice("output", "音频输出", outputLabel(state.output), ThorAudioChoice.Output,
                        if (state.output == AudioOutputMode.UsbBitPerfect) "${usbStatusLabel(usbStatus)} · USB 原样输出绕过均衡器与 AutoMix"
                        else "")
                    toggle("loop", "队列循环播放", state.loopPlayback, ThorAudioToggle.Loop)
                    toggle("concurrent", "允许与其他应用同时播放", state.concurrentPlayback, ThorAudioToggle.Concurrent)
                    toggle("cloud-shuffle", "云随机", state.cloudShuffle, ThorAudioToggle.CloudShuffle)
                    toggle("skip", "跳过不可用歌曲", state.skipUnavailable, ThorAudioToggle.SkipUnavailable)
                    add(AudioSettingRow("audio:previous", "上一首按键", if (state.previousDirect) "切换曲目" else "先回到开头",
                        enabled = state.loaded) { settings.toggle(ThorAudioToggle.PreviousDirect) })
                    toggle("restore-position", "恢复播放位置", state.restorePosition, ThorAudioToggle.RestorePosition)
                    toggle("restore-state", "恢复播放状态", state.restoreState, ThorAudioToggle.RestoreState)
                    choice("notification-left", "通知左侧按钮", notificationLabel(state.notificationLeft), ThorAudioChoice.NotificationLeft)
                    choice("notification-right", "通知右侧按钮", notificationLabel(state.notificationRight), ThorAudioChoice.NotificationRight)
                    toggle("automix", "AutoMix 自动衔接", state.autoMix, ThorAudioToggle.AutoMix,
                        if (state.processingAvailable) "" else "USB 原样输出不支持 AutoMix", state.processingAvailable)
                    if (state.autoMix && state.processingAvailable) {
                        choice("mix-mode", "AutoMix 模式", if (state.mixMode == AutoMixMode.Smart) "智能" else "固定", ThorAudioChoice.MixMode)
                        add(AudioSettingRow("audio:duration", "过渡时长", numberLabel(state.mixDuration, "秒"), enabled = state.loaded) {
                            editor.edit(ThorAudioNumber.MixDuration, state.mixDuration)
                        })
                        choice("fade", "淡入淡出曲线", fadeLabel(state.fadeCurve), ThorAudioChoice.FadeCurve)
                        choice("bars", "过渡小节数", "${state.transitionBars} 小节", ThorAudioChoice.TransitionBars)
                        choice("tail", "裁去结尾", if (state.tailCutBars == 0) "不裁剪" else "${state.tailCutBars} 小节", ThorAudioChoice.TailCutBars)
                        toggle("tempo-match", "节拍匹配", state.tempoMatching, ThorAudioToggle.TempoMatching)
                        if (state.tempoMatching) add(AudioSettingRow("audio:maximum-tempo", "最大节拍调整", numberLabel(state.maximumTempo, "%"), enabled = state.loaded) {
                            editor.edit(ThorAudioNumber.MaximumTempo, state.maximumTempo)
                        })
                    }
                }
                AudioSettingsList(vm, registry, "audio:root", rows)
            }
        }
    }
}

@Composable
@UnstableApi
internal fun ThorEqualizerSettingsPage(
    vm: ThorShellViewModel,
    registry: ThorFocusRegistry,
    onBack: () -> Unit,
    pageActions: ThorPageActionBindings? = null,
    settings: ThorAudioSettingsViewModel = viewModel(),
) {
    val state by settings.state.collectAsStateWithLifecycle()
    val error by settings.error.collectAsStateWithLifecycle()
    val editor = remember { AudioSettingEditor() }
    val cancelOrBack = { if (editor.open) editor.close() else onBack() }
    if (editor.open) BindThorPageAction(pageActions, "page:back", editor::close)
    if (state.loaded && state.processingAvailable && !editor.open) BindThorPageAction(pageActions, "equalizer:reset") {
        settings.setChoice(ThorAudioChoice.Preset, EqualizerPreset.Flat.name)
    }
    AudioSettingsFrame("十段均衡器", registry, cancelOrBack, error) {
        when {
            editor.choice != null -> AudioChoicePage(vm, registry, checkNotNull(editor.choice), state) { option, value -> settings.setChoice(option, value); editor.close() }
            editor.number != null -> AudioNumberPage(vm, registry, editor) { option, value -> settings.setNumber(option, value); editor.close() }
            else -> {
                val available = state.loaded && state.processingAvailable
                val rows = buildList {
                    if (error != null && !state.loaded) add(AudioSettingRow("equalizer:retry", "重新读取均衡器设置", action = settings::retry))
                    add(AudioSettingRow("equalizer:enabled", "应用内均衡器", if (state.equalizer) "开启" else "关闭",
                        if (state.processingAvailable) "对播放音频生效，包括自动衔接" else "USB 原样输出绕过均衡器；已有配置保留",
                        available, state.equalizer) { settings.toggle(ThorAudioToggle.Equalizer) })
                    add(AudioSettingRow("equalizer:preset", "预设", presetLabel(state.preset), enabled = available && state.equalizer) {
                        editor.choice = ThorAudioChoice.Preset
                    })
                    add(AudioSettingRow("equalizer:preamp", "前级增益", numberLabel(state.preamp, "dB"), enabled = available && state.equalizer) {
                        editor.edit(ThorAudioNumber.Preamp, state.preamp)
                    })
                    EqualizerBand.entries.forEach { band ->
                        add(AudioSettingRow("equalizer:band:${band.ordinal}", band.title, numberLabel(state.bandGains[band.ordinal], "dB"), enabled = available && state.equalizer) {
                            editor.edit(ThorAudioNumber.band(band.ordinal), state.bandGains[band.ordinal])
                        })
                    }
                    add(AudioSettingRow("equalizer:reset", "恢复平直预设", "0 dB", "前级及十段增益归零，不改变开关", available) {
                        settings.setChoice(ThorAudioChoice.Preset, EqualizerPreset.Flat.name)
                    })
                }
                AudioSettingsList(vm, registry, "equalizer:root", rows)
            }
        }
    }
}

@Composable
private fun AudioSettingsFrame(title: String, registry: ThorFocusRegistry, onBack: () -> Unit,
    error: String?, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        ThorPageHeader(registry, title, onBack = onBack)
        if (error != null) AudioNotice(error)
        content()
    }
}

@Composable
private fun AudioNotice(text: String) {
    Text(text, Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
        color = LocalThorTheme.current.secondary, fontSize = 12.sp, lineHeight = 17.sp)
}

/** Enabled items come first so logical list indexes always match the actual LazyColumn indexes. */
@Composable
@UnstableApi
private fun AudioSettingsList(vm: ThorShellViewModel, registry: ThorFocusRegistry, scene: String,
    rows: List<AudioSettingRow>, modifier: Modifier = Modifier, preferredKey: String? = null) {
    val region = "${vm.navigation.value.sceneKey}:$scene"
    val enabled = rows.filter { it.enabled }
    val ordered = enabled + rows.filterNot { it.enabled }
    val latestRows by rememberUpdatedState(enabled)
    val list = rememberLazyListState()
    val memory = remember(vm, region, preferredKey) { object : ThorListFocusMemory {
        override fun readFocus(scene: String) = vm.rememberedFocus(scene) ?: preferredKey
        override fun readScroll(scene: String) = vm.rememberedListScroll(scene)
        override fun saveFocus(scene: String, key: String) = vm.rememberFocus(scene, key)
        override fun saveScroll(scene: String, key: String, offset: Int) = vm.rememberListScroll(scene, key, offset)
        override fun preview(item: ThorItem?) = Unit
    } }
    BindThorListFocus(registry, region, enabled.map { it.key }, List(enabled.size) { null }, list, memory) { index ->
        latestRows.getOrNull(index)?.takeIf { it.enabled }?.action?.invoke()
    }
    LazyColumn(modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        itemsIndexed(ordered, key = { _, row -> row.key }) { _, row ->
            val theme = LocalThorTheme.current
            ThorActionButton(onClick = { if (row.enabled && !registry.blocked) { registry.select(row.key); row.action() } },
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .thorFocus(registry, row.key, enabled = row.enabled, onClick = row.action)
                    .semantics { row.checked?.let { selected = it }; stateDescription = row.value },
                role = ThorSurfaceRole.MusicRow, enabled = row.enabled,
                selected = row.checked == true && row.key.startsWith("audio-choice:"), focused = row.enabled && registry.selectedKey == row.key,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(row.title, fontSize = theme.typography.body, lineHeight = 21.sp)
                    if (row.description.isNotBlank()) Text(row.description, color = if (row.enabled) theme.secondary else theme.disabledText,
                        fontSize = 12.sp, lineHeight = 17.sp)
                }
                if (row.value.isNotBlank()) Text(row.value, Modifier.padding(start = 12.dp).widthIn(max = 150.dp),
                    color = if (!row.enabled) theme.disabledText else if (row.checked == true) theme.accent else theme.secondary, fontSize = 13.sp)
            }
        }
    }
}

@Composable
@UnstableApi
private fun AudioChoicePage(vm: ThorShellViewModel, registry: ThorFocusRegistry, option: ThorAudioChoice,
    state: ThorAudioSettingsState, choose: (ThorAudioChoice, String) -> Unit) {
    val current = currentChoice(option, state)
    val rows = choiceItems(option).map { item -> AudioSettingRow("audio-choice:${option.name}:${item.value}",
        item.label, if (item.value == current) "已选择" else "", item.description, item.enabled, item.value == current) {
        if (item.enabled) choose(option, item.value)
    } }
    AudioSettingsList(vm, registry, "audio:choice:${option.name}", rows,
        preferredKey = "audio-choice:${option.name}:$current")
}

@Composable
@UnstableApi
private fun AudioNumberPage(vm: ThorShellViewModel, registry: ThorFocusRegistry, editor: AudioSettingEditor,
    save: (ThorAudioNumber, Float) -> Unit) {
    val theme = LocalThorTheme.current
    val option = checkNotNull(editor.number)
    val keys = listOf("audio-number:minus", "audio-number:plus", "audio-number:apply", "audio-number:cancel")
    BindThorOptionFocus(vm, registry, keys, ThorFocusLayout.Spatial, "audio-number:apply")
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(option.title, Modifier.weight(1f), fontSize = theme.typography.pageTitle, color = theme.text)
            Text(numberLabel(editor.draft, option.unit), color = theme.accent, fontSize = 28.sp)
        }
        Slider(value = editor.draft, onValueChange = { editor.draft = it },
            valueRange = option.minimum..option.maximum, modifier = Modifier.fillMaxWidth().focusProperties { canFocus = false },
            colors = SliderDefaults.colors(thumbColor = theme.accent, activeTrackColor = theme.accent,
                inactiveTrackColor = theme.outline))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ThorButton(registry, keys[0], "− ${numberLabel(option.step, option.unit)}", Modifier.weight(1f), onClick = { editor.adjust(-1) })
            ThorButton(registry, keys[1], "+ ${numberLabel(option.step, option.unit)}", Modifier.weight(1f), onClick = { editor.adjust(1) })
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ThorButton(registry, keys[3], "取消", Modifier.weight(1f), onClick = editor::close)
            ThorButton(registry, keys[2], "应用", Modifier.weight(1f), primary = true,
                onClick = { save(option, editor.draft) })
        }
    }
}

private fun choiceItems(option: ThorAudioChoice): List<AudioChoiceItem> = when (option) {
    ThorAudioChoice.Quality -> MusicQuality.entries.map { AudioChoiceItem(it.name, it.explanation) }
    ThorAudioChoice.Output -> AudioOutputMode.entries.map { AudioChoiceItem(it.name, outputLabel(it),
        it in supportedThorAudioOutputs(Build.VERSION.SDK_INT), if (it == AudioOutputMode.UsbBitPerfect) "Android 14 及以上，需支持原样输出的 USB 设备与格式" else "") }
    ThorAudioChoice.NotificationLeft, ThorAudioChoice.NotificationRight -> NotificationAction.entries.map {
        AudioChoiceItem(it.name, notificationLabel(it))
    }
    ThorAudioChoice.MixMode -> AutoMixMode.entries.map { AudioChoiceItem(it.name, if (it == AutoMixMode.Smart) "智能" else "固定") }
    ThorAudioChoice.FadeCurve -> AutoMixFadeCurve.entries.map { AudioChoiceItem(it.name, fadeLabel(it)) }
    ThorAudioChoice.TransitionBars -> listOf(4, 8, 16).map { AudioChoiceItem("$it", "$it 小节") }
    ThorAudioChoice.TailCutBars -> listOf(0, 2, 4, 8).map { AudioChoiceItem("$it", if (it == 0) "不裁剪" else "$it 小节") }
    ThorAudioChoice.Preset -> EqualizerPreset.entries.map { AudioChoiceItem(it.name, presetLabel(it)) }
}

private fun currentChoice(option: ThorAudioChoice, s: ThorAudioSettingsState): String = when (option) {
    ThorAudioChoice.Quality -> s.quality.name; ThorAudioChoice.Output -> s.output.name
    ThorAudioChoice.NotificationLeft -> s.notificationLeft.name; ThorAudioChoice.NotificationRight -> s.notificationRight.name
    ThorAudioChoice.MixMode -> s.mixMode.name; ThorAudioChoice.FadeCurve -> s.fadeCurve.name
    ThorAudioChoice.TransitionBars -> "${s.transitionBars}"; ThorAudioChoice.TailCutBars -> "${s.tailCutBars}"
    ThorAudioChoice.Preset -> s.preset.name
}

private fun outputLabel(value: AudioOutputMode) = when (value) {
    AudioOutputMode.AudioTrack -> "AudioTrack"; AudioOutputMode.AAudio -> "AAudio"
    AudioOutputMode.OpenSLES -> "OpenSL ES"; AudioOutputMode.UsbBitPerfect -> "USB 原样输出"
}
private fun notificationLabel(value: NotificationAction) = when (value) {
    NotificationAction.PlayMode -> "播放模式"; NotificationAction.Like -> "喜欢"
    NotificationAction.FloatingLyrics -> "上屏歌词"; NotificationAction.None -> "无"
}
private fun fadeLabel(value: AutoMixFadeCurve) = when (value) {
    AutoMixFadeCurve.EqualPower -> "等功率"; AutoMixFadeCurve.Smooth -> "平滑"; AutoMixFadeCurve.Linear -> "线性"
}
private fun usbStatusLabel(value: UsbAudioStatus) = when (value) {
    UsbAudioStatus.Inactive -> "未启用"; UsbAudioStatus.WaitingForDevice -> "等待 USB 设备"
    UsbAudioStatus.UnsupportedDevice -> "设备不支持"; UsbAudioStatus.UnsupportedFormat -> "格式不支持"
    UsbAudioStatus.Active -> "正在原样输出"; UsbAudioStatus.Unavailable -> "当前系统不可用"
}
private fun presetLabel(value: EqualizerPreset) = when (value) {
    EqualizerPreset.Flat -> "平直"; EqualizerPreset.Acoustic -> "原声"; EqualizerPreset.BassBoost -> "低音增强"
    EqualizerPreset.Classical -> "古典"; EqualizerPreset.Dance -> "舞曲"; EqualizerPreset.Electronic -> "电子"
    EqualizerPreset.HipHop -> "嘻哈"; EqualizerPreset.Jazz -> "爵士"; EqualizerPreset.Pop -> "流行"
    EqualizerPreset.Rock -> "摇滚"; EqualizerPreset.SpokenWord -> "人声朗读"; EqualizerPreset.TrebleBoost -> "高音增强"
    EqualizerPreset.Vocal -> "人声"; EqualizerPreset.Custom -> "自定义"
}
private fun numberLabel(value: Float, unit: String) = String.format(Locale.getDefault(), "%s%.1f %s",
    if (unit == "dB" && value > 0) "+" else "", value, unit)
