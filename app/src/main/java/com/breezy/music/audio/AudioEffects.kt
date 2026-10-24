package com.breezy.music.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.breezy.music.model.AudioEngine
import com.breezy.music.model.Preferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 音频输出设备类型。
 *
 * 不同设备（手机扬声器 / 听筒 / 有线耳机 / 蓝牙耳机 / USB 声卡）的频响差异很大，
 * 同一个 EQ 曲线换设备后往往很难听，因此本应用按设备**分别记忆**音效状态，
 * 与 Poweramp 等播放器的「按输出设备保存音效」做法一致。
 */
enum class AudioDeviceType(val key: String, val label: String) {
    SPEAKER("speaker", "扬声器"),
    EARPIECE("earpiece", "听筒"),
    WIRED("wired", "有线耳机"),
    BLUETOOTH("bluetooth", "蓝牙音频"),
    USB("usb", "USB 音频");

    companion object {
        fun fromKey(key: String?): AudioDeviceType =
            entries.firstOrNull { it.key == key } ?: SPEAKER
    }
}

/** 均衡器形态：图示（等分频段，只调增益）与参数（自定频率/ Q / 类型） */
enum class EqMode(val key: String, val label: String) {
    GRAPHIC("graphic", "图示均衡"),
    PARAMETRIC("parametric", "参数均衡");

    companion object {
        fun fromKey(key: String?): EqMode =
            entries.firstOrNull { it.key == key } ?: GRAPHIC
    }
}

/**
 * 混响预设。四组参数对应四种典型空间，用户可在此基础上继续微调。
 */
data class ReverbPreset(
    val id: String,
    val name: String,
    val mix: Float,
    val room: Float,
    val enabled: Boolean = true
)

/**
 * 音效面板的全局状态与持久化。
 *
 * 状态机：UI 修改 → [apply] 写入 [AudioEngine.dsp]（实时生效）→ [persist] 落盘到当前设备档位。
 * 切换输出设备时先保存旧设备档位，再载入新设备档位，保证「换耳机不用重新调 EQ」。
 *
 * 锁定语义（v1.1）：
 *  - 选中任意预设后默认处于**锁定**状态，滑杆不可拖动，避免误触改坏官方曲线；
 *  - 解锁后可直接修改，修改只影响当前设备档位的“工作副本”，预设库本身不变；
 *  - 内置预设有「还原」，一键回到出厂曲线；自定义预设没有还原（它的“出厂”就是用户自己）。
 */
object AudioEffects {

    private const val TAG = "AudioEffects"

    // ── 可观察状态（Compose 直接读取）──
    var enabled by mutableStateOf(true)
        private set
    var mode by mutableStateOf(EqMode.GRAPHIC)
        private set
    var bandCount by mutableStateOf(BreezyDspProcessor.MAX_BANDS)

    /** 曲线是否做平滑（关闭后按段直接连线，能看清每一段的落点） */
    var curveSmooth by mutableStateOf(true)
        private set
    var gains by mutableStateOf(FloatArray(BreezyDspProcessor.MAX_BANDS))
        private set
    var paramBands by mutableStateOf(BreezyDspProcessor.DEFAULT_PARAM_BANDS.toList())
        private set
    var preamp by mutableStateOf(0f)
        private set

    /** 限压器开关：与 EQ 独立，按设备档位各自记忆 */
    var limiterEnabled by mutableStateOf(false)
        private set

    /** 限压阈值（dBFS，−12…0），越小压得越狠 */
    var limiterThresholdDb by mutableStateOf(-1f)
        private set
    var reverbEnabled by mutableStateOf(false)
        private set
    var reverbMix by mutableStateOf(0.25f)
        private set
    var reverbRoom by mutableStateOf(0.72f)
        private set
    var reverbPresetId by mutableStateOf(ReverbPresets.OFF_ID)
        private set
    var presetId by mutableStateOf(EqPresets.FLAT.id)
        private set
    var customPresets by mutableStateOf<List<EqPreset>>(emptyList())
        private set
    var device by mutableStateOf(AudioDeviceType.SPEAKER)
        private set

    /** 当前是否锁定（锁定时滑杆不可拖动） */
    var locked by mutableStateOf(true)
        private set

    /** 当前曲线是否已偏离所选预设（内置预设可据此显示「还原」） */
    var modified by mutableStateOf(false)
        private set

    /** 套用当前预设时的段数：还原时据此把段数也一起恢复 */
    private var presetBandCount = 0

    /** 每个预设自己的段数（id -> 段数），段数只跟当前均衡器走 */
    private var presetBandCounts: Map<String, Int> = emptyMap()

    private var appContext: Context? = null
    private var initialized = false

    /** 当前段数下的中心频率标签，供 UI 画刻度 */
    fun bandLabels(): List<String> =
        BreezyDspProcessor.centerFrequencies(bandCount).map { BreezyDspProcessor.formatFrequency(it) }

    fun bandFrequencies(): FloatArray = BreezyDspProcessor.centerFrequencies(bandCount)

    val allPresets: List<EqPreset> get() = EqPresets.BUILTIN + customPresets

    fun currentPreset(): EqPreset? = allPresets.firstOrNull { it.id == presetId }

    fun isCurrentBuiltin(): Boolean = currentPreset()?.builtin == true

    /** 启动时调用一次：读取段数与当前设备档位并应用到引擎 */
    fun init(context: Context) {
        if (initialized) return
        val ctx = context.applicationContext
        appContext = ctx
        curveSmooth = Preferences.isCurveSmooth(ctx)
        presetBandCounts = Preferences.getPresetBandCounts(ctx)
        bandCount = Preferences.getEqBandCount(ctx)  // 仅兜底，真正生效的是当前预设的段数
        customPresets = loadCustomPresets(ctx)
        device = detectDevice(ctx)
        loadProfile(ctx, device)
        initialized = true
        apply(updateEngine = true)
    }

    /** 重新探测输出设备；变化时切换档位 */
    fun refreshDevice(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        val newDevice = detectDevice(ctx)
        if (newDevice != device) {
            persist(ctx)
            device = newDevice
            loadProfile(ctx, newDevice)
            apply(updateEngine = true)
        }
    }

    // ── 修改接口（UI 调用）──

    fun enable(value: Boolean) {
        enabled = value
        apply()
        persistAsync()
    }

    /** 内部使用：只允许在新建预设时把曲线折算成另一种模型，不开放给界面。 */
    private fun setMode(context: Context, newMode: EqMode) {
        if (newMode == mode) return
        val previous = mode
        mode = newMode
        if (newMode == EqMode.PARAMETRIC) {
            // 图示 → 参数：把当前曲线按 ISO 十段映射到参数频段的增益上
            paramBands = mapGraphicToParam(gains, bandCount)
        } else {
            // 参数 → 图示：按参数频段的实测响应重采样回图示频段
            val response = EqCurve.parametricDb(BreezyDspProcessor.centerFrequencies(bandCount), paramBands)
            gains = response
        }
        if (previous != newMode) modified = true
        persistAsync()
        apply()
    }

    fun setBandGain(index: Int, db: Float) {
        if (locked) return
        if (index !in 0 until bandCount) return
        val copy = gains.copyOf()
        copy[index] = db.coerceIn(-12f, 12f)
        gains = copy
        modified = true
        apply()
        persistAsync()
        syncToCurrentCustom()
    }

    fun setBandCount(context: Context, count: Int) {
        val c = count.coerceIn(3, BreezyDspProcessor.MAX_BANDS)
        if (c == bandCount) return
        val previous = gains
        val previousCount = bandCount
        bandCount = c
        gains = if (locked) {
            val base = currentPreset() ?: EqPresets.FLAT
            EqPresets.gainsForBandCount(base, c)
        } else {
            resample(previous, previousCount, c)
        }
        Preferences.setEqBandCount(context.applicationContext, c)
        apply()
        persistAsync()
    }

    /**
     * 改段数：追加式。
     * 加段时不动已有段，新的段按默认频率补在末尾；减段时从末尾截断。
     * 图示模式的频段是按段数重新等分频率的，所以"第 k 段"对应的中心频率会变，
     * 这是用户明确要的行为（先把段数加上去，再逐个调），不做隐式重采样。
     */
    fun setBandCountPreserving(context: Context, count: Int) {
        if (locked) return
        val limit = if (mode == EqMode.PARAMETRIC) BreezyDspProcessor.MAX_PARAM_BANDS else BreezyDspProcessor.MAX_BANDS
        val c = count.coerceIn(3, limit)
        if (mode == EqMode.PARAMETRIC) {
            if (c == paramBands.size) return
            val old = paramBands
            val next = ArrayList<ParamBand>(c)
            for (i in 0 until c) {
                next += if (i < old.size) old[i] else BreezyDspProcessor.DEFAULT_PARAM_BANDS[i]
            }
            paramBands = next
        } else {
            if (c == bandCount) return
            val old = gains
            val next = FloatArray(c)
            for (i in 0 until c) next[i] = if (i < old.size) old[i] else 0f
            bandCount = c
            gains = next
        }
        // 段数是「这个预设自己的属性」：无论图示还是参数模式，
        // 都只记在当前预设 id 下，切到别的预设读不到这条记录
        if (presetId.isNotEmpty()) {
            presetBandCounts = presetBandCounts + (presetId to currentBandCount())
            Preferences.setPresetBandCounts(context.applicationContext, presetBandCounts)
        }
        modified = true
        apply()
        persistAsync()
    }

    /** 当前模式的段数上限，给 UI 划范围用 */
    fun bandCountLimit(): Int =
        if (mode == EqMode.PARAMETRIC) BreezyDspProcessor.MAX_PARAM_BANDS else BreezyDspProcessor.MAX_BANDS

    fun currentBandCount(): Int =
        if (mode == EqMode.PARAMETRIC) paramBands.size else bandCount

    /**
     * 某个预设"自己的"段数。
     *
     * 优先级：用户改过的记录 → 预设本身携带的段数 → 预设曲线的长度（内置预设 10 段等频程）。
     * 这样做的好处是段数完全跟着预设走：改 A 的段数不会影响 B，
     * 重启后也不会被设备档位里那个全局数字盖掉。
     */
    /** 给界面用：某个预设自己的段数（不受当前选中的预设影响） */
    fun bandCountOf(preset: EqPreset): Int =
        bandCountFor(preset)?.coerceIn(3, BreezyDspProcessor.MAX_BANDS) ?: preset.gains.size.coerceIn(3, BreezyDspProcessor.MAX_BANDS)

    private fun bandCountFor(preset: EqPreset?): Int? {
        if (preset == null) return null
        presetBandCounts[preset.id]?.let { return it }
        if (preset.mode == EqMode.PARAMETRIC) {
            return preset.paramBands.size.takeIf { it > 0 } ?: preset.bandCount.takeIf { it > 0 }
        }
        return preset.bandCount.takeIf { it > 0 } ?: preset.gains.size.takeIf { it > 0 }
    }

    fun applyPreset(preset: EqPreset) {
        presetId = preset.id
        mode = preset.mode
        // 记住"套用这个预设时是多少段"：还原时要连段数一起回来，
        // 否则用户改过段数后点还原，曲线回去了、推子数量还是改过的样子。
        presetBandCount = if (preset.mode == EqMode.PARAMETRIC) {
            if (preset.paramBands.isNotEmpty()) preset.paramBands.size else bandCount
        } else {
            bandCount
        }
        if (preset.mode == EqMode.PARAMETRIC && preset.paramBands.isNotEmpty()) {
            paramBands = normalizeParamBands(preset.paramBands)
        } else if (preset.mode == EqMode.PARAMETRIC) {
            paramBands = mapGraphicToParam(EqPresets.gainsForBandCount(preset, bandCount), bandCount)
        }
        if (preset.mode == EqMode.GRAPHIC) {
            // 段数跟着预设走：每个均衡器记住自己被调成多少段
            bandCount = bandCountFor(preset) ?: bandCount
        }
        gains = EqPresets.gainsForBandCount(preset, bandCount)
        if (preset.reverbMix > 0f) {
            reverbEnabled = true
            reverbMix = preset.reverbMix
            reverbRoom = preset.reverbRoom
            reverbPresetId = ReverbPresets.matchByParams(reverbMix, reverbRoom)?.id ?: ReverbPresets.CUSTOM_ID
        }
        // 预设默认锁定；自定义预设默认不锁，方便继续调
        locked = preset.builtin
        modified = false
        apply()
        persistAsync()
    }

    fun setCurveSmooth(context: Context, smooth: Boolean) {
        curveSmooth = smooth
        Preferences.setCurveSmooth(context.applicationContext, smooth)
    }

    fun toggleLock() {
        locked = !locked
        persistAsync()
    }

    /**
     * 新建均衡器。
     *
     * `newMode` 是**预设自己的属性**：图示均衡与参数均衡是两种不同的编辑模型，
     * 不能像切页签那样在同一个预设上互相切换。所以类型只在新建这一刻选定，
     * 之后这个预设就固定属于那一种；要换类型就再新建一个。
     *
     * @param fromCurrent true = 复制当前曲线/参数座标作为初始值；false = 平直曲线。
     */
    fun createCustomPreset(
        context: Context,
        name: String,
        fromCurrent: Boolean,
        newMode: EqMode = mode
    ): EqPreset? {
        val trimmed = name.trim().ifEmpty { "自定义 ${customPresets.size + 1}" }
        val startsFromCurrent = fromCurrent
        // 先按目标类型准备好两套数据：新建时允许把当前曲线「折算」成另一种模型的初始值，
        // 这是唯一一次跨模型转换，之后两者互不影响。
        val seedGains: FloatArray = if (startsFromCurrent) {
            if (newMode == EqMode.GRAPHIC) resample(gains, bandCount, EqPresets.isoFrequencies.size)
            else resample(
                EqCurve.parametricDb(BreezyDspProcessor.centerFrequencies(bandCount), paramBands),
                bandCount,
                EqPresets.isoFrequencies.size
            )
        } else FloatArray(EqPresets.isoFrequencies.size)

        val seedParams: List<ParamBand> = if (startsFromCurrent) {
            if (newMode == EqMode.PARAMETRIC) paramBands else mapGraphicToParam(gains, bandCount)
        } else {
            mapGraphicToParam(FloatArray(bandCount), bandCount)
        }

        val preset = EqPreset(
            id = "custom_${System.currentTimeMillis()}",
            name = trimmed,
            gains = seedGains,
            builtin = false,
            reverbMix = if (startsFromCurrent && reverbEnabled) reverbMix else 0f,
            reverbRoom = reverbRoom,
            mode = newMode,
            paramBands = seedParams
        )
        customPresets = customPresets + preset
        saveCustomPresets(context.applicationContext, customPresets)
        presetId = preset.id
        mode = newMode
        gains = if (newMode == EqMode.GRAPHIC) {
            resample(seedGains, seedGains.size, bandCount)
        } else {
            // 参数模式下曲线由频段参数决定，图示增益只是它的投影，供曲线可视化使用
            EqCurve.parametricDb(BreezyDspProcessor.centerFrequencies(bandCount), seedParams)
        }
        paramBands = seedParams
        locked = false
        modified = false
        apply()
        persistAsync()
        return preset
    }

    /** 一键还原：内置预设回到出厂曲线；自定义预设没有还原 */
    fun restorePreset() {
        val preset = currentPreset() ?: return
        if (!preset.builtin) return
        // 还原 = 把这个预设身上"用户改过的东西"全部丢掉，包括段数记录
        if (presetBandCounts.containsKey(preset.id)) {
            presetBandCounts = presetBandCounts - preset.id
            appContext?.let { Preferences.setPresetBandCounts(it, presetBandCounts) }
        }
        if (mode == EqMode.GRAPHIC) {
            // 段数也属于「预设状态」：按该预设自己保存的段数恢复，
            // 没保存过就退回预设定义本身的段数（内置预设是 10 段等频程）
            // 还原回到「预设定义本身的段数」，而不是用户上次调成多少段：
            // 后者相当于把改动也当成预设的一部分，还原就永远没有效果
            val target = if (preset.gains.isNotEmpty()) preset.gains.size else 10
            bandCount = target
            gains = EqPresets.gainsForBandCount(preset, target)
        } else {
            gains = EqPresets.gainsForBandCount(preset, bandCount)
            paramBands = if (preset.paramBands.isNotEmpty()) normalizeParamBands(preset.paramBands)
            else mapGraphicToParam(gains, bandCount)
        }
        modified = false
        apply()
        persistAsync()
    }

    fun enableLimiter(enabled: Boolean) {
        limiterEnabled = enabled
        apply()
        persistAsync()
    }

    fun applyLimiterThreshold(db: Float) {
        limiterThresholdDb = db.coerceIn(-12f, 0f)
        apply()
        persistAsync()
    }

    /** 实时增益衰减量（0~1），UI 轮询 */
    fun limiterLevel(): Float = AudioEngine.dsp.limiterGainReduction()

    fun applyPreamp(db: Float) {
        preamp = db.coerceIn(-12f, 12f)
        modified = true
        apply()
        persistAsync()
    }

    fun enableReverb(value: Boolean) {
        reverbEnabled = value
        reverbPresetId = if (!value) ReverbPresets.OFF_ID else reverbPresetId
        apply()
        persistAsync()
    }

    fun applyReverbPreset(preset: ReverbPreset) {
        reverbPresetId = preset.id
        reverbEnabled = preset.enabled
        reverbMix = preset.mix
        reverbRoom = preset.room
        apply()
        persistAsync()
    }

    fun applyReverbMix(value: Float) {
        reverbMix = value.coerceIn(0f, 1f)
        reverbPresetId = ReverbPresets.matchByParams(reverbMix, reverbRoom)?.id ?: ReverbPresets.CUSTOM_ID
        apply()
        persistAsync()
    }

    fun applyReverbRoom(value: Float) {
        reverbRoom = value.coerceIn(0.5f, 0.92f)
        reverbPresetId = ReverbPresets.matchByParams(reverbMix, reverbRoom)?.id ?: ReverbPresets.CUSTOM_ID
        apply()
        persistAsync()
    }

    fun resetAll() {
        presetId = EqPresets.FLAT.id
        mode = EqMode.GRAPHIC
        gains = FloatArray(bandCount)
        paramBands = BreezyDspProcessor.DEFAULT_PARAM_BANDS.toList()
        preamp = 0f
        reverbEnabled = false
        reverbMix = 0.25f
        reverbRoom = 0.72f
        reverbPresetId = ReverbPresets.OFF_ID
        locked = true
        modified = false
        apply()
        persistAsync()
    }

    fun deleteCustomPreset(id: String) {
        val ctx = appContext ?: return
        customPresets = customPresets.filterNot { it.id == id }
        saveCustomPresets(ctx, customPresets)
        if (presetId == id) {
            presetId = EqPresets.FLAT.id
            gains = FloatArray(bandCount)
            paramBands = BreezyDspProcessor.DEFAULT_PARAM_BANDS.toList()
            locked = true
            modified = false
            apply()
        }
        persistAsync()
    }

    fun renameCustomPreset(id: String, name: String) {
        val ctx = appContext ?: return
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        customPresets = customPresets.map { if (it.id == id) it.copy(name = trimmed) else it }
        saveCustomPresets(ctx, customPresets)
    }

    // ── 参数均衡操作 ──

    fun setParamBand(index: Int, band: ParamBand) {
        if (locked) return
        if (index !in paramBands.indices) return
        val copy = paramBands.toMutableList()
        copy[index] = band.sanitized()
        paramBands = copy
        modified = true
        apply()
        persistAsync()
        syncToCurrentCustom()
    }

    fun setParamGain(index: Int, db: Float) =
        paramBands.getOrNull(index)?.let { setParamBand(index, it.copy(gainDb = db)) }

    fun setParamFrequency(index: Int, hz: Float) =
        paramBands.getOrNull(index)?.let { setParamBand(index, it.copy(freqHz = hz)) }

    fun setParamQ(index: Int, q: Float) =
        paramBands.getOrNull(index)?.let { setParamBand(index, it.copy(q = q)) }

    fun setParamType(index: Int, type: Int) =
        paramBands.getOrNull(index)?.let { setParamBand(index, it.copy(type = type)) }

    // ── 引擎同步 ──

    private fun apply(updateEngine: Boolean = true) {
        if (!updateEngine) return
        val dsp = AudioEngine.dsp
        dsp.setBandCount(bandCount)
        val padded = FloatArray(BreezyDspProcessor.MAX_BANDS)
        for (i in 0 until minOf(gains.size, BreezyDspProcessor.MAX_BANDS)) padded[i] = gains[i]
        dsp.setAllGains(padded)
        dsp.setParamBands(paramBands)
        dsp.setMode(mode == EqMode.PARAMETRIC)
        dsp.setEnabled(enabled)
        dsp.setPreamp(preamp)
        dsp.setReverb(reverbEnabled, reverbMix, reverbRoom)
        dsp.setLimiterEnabled(limiterEnabled)
        dsp.setLimiterThresholdDb(limiterThresholdDb)
    }

    /** 播放开始后调用：确保引擎侧参数与 UI 状态一致（ExoPlayer 可能重建过 AudioSink） */
    fun syncToEngine() = apply()

    // ── 设备探测 ──

    fun detectDevice(context: Context): AudioDeviceType {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return AudioDeviceType.SPEAKER
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            // 优先级：USB > 蓝牙 > 有线 > 听筒 > 扬声器
            if (devices.any { it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_DEVICE }) {
                return AudioDeviceType.USB
            }
            if (devices.any {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                            it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
                }
            ) {
                return AudioDeviceType.BLUETOOTH
            }
            if (devices.any {
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                        it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET
                }
            ) {
                return AudioDeviceType.WIRED
            }
            if (devices.any { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }) {
                return AudioDeviceType.EARPIECE
            }
            return AudioDeviceType.SPEAKER
        }
        @Suppress("DEPRECATION")
        return when {
            am.isBluetoothA2dpOn -> AudioDeviceType.BLUETOOTH
            am.isWiredHeadsetOn -> AudioDeviceType.WIRED
            am.isSpeakerphoneOn -> AudioDeviceType.SPEAKER
            else -> AudioDeviceType.EARPIECE
        }
    }

    // ── 持久化 ──

    private fun persistAsync() {
        val ctx = appContext ?: return
        persist(ctx)
    }

    private fun persist(context: Context) {
        try {
            val json = JSONObject()
                .put("enabled", enabled)
                .put("mode", mode.key)
                .put("bandCount", bandCount)
                .put("gains", JSONArray(gains.toList()))
                .put("paramBands", paramBandsToJson(paramBands))
                .put("preamp", preamp.toDouble())
                .put("limiter", limiterEnabled)
                .put("limiterDb", limiterThresholdDb.toDouble())
                .put("reverbEnabled", reverbEnabled)
                .put("reverbMix", reverbMix.toDouble())
                .put("reverbRoom", reverbRoom.toDouble())
                .put("reverbPresetId", reverbPresetId)
                .put("presetId", presetId)
                .put("locked", locked)
                .put("presetBands", presetBandCount)
            Preferences.setAudioProfile(context, device.key, json.toString())
        } catch (e: Exception) {
            Log.w(TAG, "persist failed", e)
        }
    }

    private fun loadProfile(context: Context, forDevice: AudioDeviceType) {
        val raw = Preferences.getAudioProfile(context, forDevice.key)
        if (raw == null) {
            // 该设备第一次使用：回到平直曲线
            enabled = true
            presetId = EqPresets.FLAT.id
            mode = EqMode.GRAPHIC
            gains = FloatArray(BreezyDspProcessor.MAX_BANDS)
            paramBands = BreezyDspProcessor.DEFAULT_PARAM_BANDS.toList()
            preamp = 0f
            reverbEnabled = false
            reverbPresetId = ReverbPresets.OFF_ID
            locked = true
            modified = false
            return
        }
        try {
            val json = JSONObject(raw)
            enabled = json.optBoolean("enabled", true)
            mode = EqMode.fromKey(json.optString("mode", "graphic"))
            // 段数不再从设备档位恢复：它是预设的属性，
            // 由 applyPreset / presetBandCounts 决定，这里读回来会把所有预设拉成同一个数
            bandCount = bandCountFor(currentPreset())?.coerceIn(3, BreezyDspProcessor.MAX_BANDS) ?: bandCount
            val arr = json.optJSONArray("gains")
            val loaded = FloatArray(BreezyDspProcessor.MAX_BANDS)
            if (arr != null) {
                for (i in 0 until minOf(arr.length(), BreezyDspProcessor.MAX_BANDS)) loaded[i] = arr.optDouble(i, 0.0).toFloat()
            }
            gains = loaded
            paramBands = normalizeParamBands(paramBandsFromJson(json.optJSONArray("paramBands")))
            preamp = json.optDouble("preamp", 0.0).toFloat()
            limiterEnabled = json.optBoolean("limiter", false)
            limiterThresholdDb = json.optDouble("limiterDb", -1.0).toFloat().coerceIn(-12f, 0f)
            reverbEnabled = json.optBoolean("reverbEnabled", false)
            reverbMix = json.optDouble("reverbMix", 0.25).toFloat()
            reverbRoom = json.optDouble("reverbRoom", 0.72).toFloat()
            reverbPresetId = json.optString("reverbPresetId", ReverbPresets.OFF_ID)
            presetId = json.optString("presetId", "")
            locked = json.optBoolean("locked", true)
            presetBandCount = json.optInt("presetBands", 0)
            modified = false
            // 旧存档里可能出现「曲线和预设已经不一致，但锁定标记还是内置预设」的情况
            // （早期版本改推子时还没有锁定语义）。这里做一次校验：
            // 只要曲线和预设对不上，就标成「已修改」，让「还原预设」可用，
            // 否则界面会显示「内置预设 · 锁定」却摆着一条改过的曲线，用户既改不动也还原不了。
            val refer = allPresets.firstOrNull { it.id == presetId }
            if (refer != null) {
                modified = !matchesPreset(refer)
            }
        } catch (e: Exception) {
            Log.w(TAG, "loadProfile failed", e)
        }
    }

    /** 当前曲线是否与某个预设一致（用于推导 modified，不参与音频计算） */
    private fun matchesPreset(preset: EqPreset): Boolean {
        if (preset.mode == EqMode.PARAMETRIC && preset.paramBands.isNotEmpty()) {
            if (mode != EqMode.PARAMETRIC) return false
            val a = normalizeParamBands(preset.paramBands)
            if (a.size != paramBands.size) return false
            return a.indices.all { i ->
                val x = a[i]
                val y = paramBands[i]
                x.type == y.type &&
                    kotlin.math.abs(x.freqHz - y.freqHz) < 1f &&
                    kotlin.math.abs(x.q - y.q) < 0.02f &&
                    kotlin.math.abs(x.gainDb - y.gainDb) < 0.05f
            }
        }
        // 段数不同也算「已修改」：内置预设定义在固定段数上，
        // 用户把它调成别的段数之后，界面必须给出「还原」的入口
        if (preset.gains.isNotEmpty() && preset.gains.size != bandCount) return false
        val target = EqPresets.gainsForBandCount(preset, bandCount)
        if (target.size != bandCount || gains.size < bandCount) return false
        for (i in 0 until bandCount) {
            if (kotlin.math.abs(target[i] - gains[i]) > 0.05f) return false
        }
        return true
    }

    private fun loadCustomPresets(context: Context): List<EqPreset> {
        val raw = Preferences.getCustomPresets(context) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val gainsArr = o.optJSONArray("gains") ?: JSONArray()
                val g = FloatArray(10) { idx -> gainsArr.optDouble(idx, 0.0).toFloat() }
                EqPreset(
                    id = o.optString("id", "custom_$i"),
                    name = o.optString("name", "自定义"),
                    gains = g,
                    builtin = false,
                    reverbMix = o.optDouble("reverbMix", 0.0).toFloat(),
                    reverbRoom = o.optDouble("reverbRoom", 0.72).toFloat(),
                    mode = EqMode.fromKey(o.optString("mode", "graphic")),
                    paramBands = paramBandsFromJson(o.optJSONArray("paramBands"))
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveCustomPresets(context: Context, presets: List<EqPreset>) {
        val arr = JSONArray()
        presets.forEach { p ->
            arr.put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("gains", JSONArray(p.gains.toList()))
                    .put("mode", p.mode.key)
                    .put("paramBands", paramBandsToJson(p.paramBands))
                    .put("reverbMix", p.reverbMix.toDouble())
                    .put("reverbRoom", p.reverbRoom.toDouble())
            )
        }
        Preferences.setCustomPresets(context, arr.toString())
    }

    /** 自定义预设解锁编辑时，把改动同步回预设库，避免“改完退出就丢” */
    private fun syncToCurrentCustom() {
        val ctx = appContext ?: return
        val preset = currentPreset() ?: return
        if (preset.builtin) return
        val updated = customPresets.map {
            if (it.id == preset.id) {
                it.copy(
                    gains = resample(gains, bandCount, EqPresets.isoFrequencies.size),
                    mode = mode,
                    paramBands = paramBands
                )
            } else it
        }
        customPresets = updated
        saveCustomPresets(ctx, updated)
    }

    private fun paramBandsToJson(bands: List<ParamBand>): JSONArray {
        val arr = JSONArray()
        bands.forEach { b ->
            arr.put(
                JSONObject()
                    .put("type", b.type)
                    .put("freq", b.freqHz.toDouble())
                    .put("q", b.q.toDouble())
                    .put("gain", b.gainDb.toDouble())
            )
        }
        return arr
    }

    private fun paramBandsFromJson(arr: JSONArray?): List<ParamBand> {
        if (arr == null || arr.length() == 0) return BreezyDspProcessor.DEFAULT_PARAM_BANDS.toList()
        val out = ArrayList<ParamBand>(BreezyDspProcessor.MAX_PARAM_BANDS)
        for (i in 0 until minOf(arr.length(), BreezyDspProcessor.MAX_PARAM_BANDS)) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                ParamBand(
                    type = o.optInt("type", BreezyDspProcessor.TYPE_PEAK),
                    freqHz = o.optDouble("freq", BreezyDspProcessor.DEFAULT_PARAM_FREQS.getOrElse(i) { 1000f }.toDouble()).toFloat(),
                    q = o.optDouble("q", 1.0).toFloat(),
                    gainDb = o.optDouble("gain", 0.0).toFloat()
                ).sanitized()
            )
        }
        return normalizeParamBands(out)
    }

    /**
     * 只清洗数值、不动段数。
     *
     * 之前这里按上限段数补齐列表，结果"上限从 10 调到 20"把所有旧预设
     * 悄悄从 10 段撑成了 20 段（多出来的全是 0 dB 的默认频段）。
     * 段数是预设自己的属性，读档时必须保持原样。
     */
    private fun normalizeParamBands(bands: List<ParamBand>): List<ParamBand> {
        if (bands.isEmpty()) return emptyList()
        val n = bands.size.coerceIn(3, BreezyDspProcessor.MAX_PARAM_BANDS)
        return List(n) { i ->
            bands.getOrElse(i) { BreezyDspProcessor.DEFAULT_PARAM_BANDS[0] }.sanitized()
        }
    }

    /** 把图示曲线映射到参数均衡的十个频段（频率相同，只取增益） */
    private fun mapGraphicToParam(graphicGains: FloatArray, count: Int): List<ParamBand> {
        val freqs = BreezyDspProcessor.centerFrequencies(count)
        val iso = BreezyDspProcessor.DEFAULT_PARAM_FREQS
        val base = BreezyDspProcessor.DEFAULT_PARAM_BANDS
        return List(BreezyDspProcessor.MAX_PARAM_BANDS) { i ->
            val f = iso[i]
            val db = when {
                count <= 1 -> graphicGains.getOrElse(0) { 0f }
                f <= freqs.first() -> graphicGains.firstOrNull() ?: 0f
                f >= freqs.last() -> graphicGains.getOrElse(count - 1) { 0f }
                else -> {
                    var idx = 0
                    while (idx < count - 2 && freqs[idx + 1] < f) idx++
                    val t = (kotlin.math.ln(f / freqs[idx]) / kotlin.math.ln(freqs[idx + 1] / freqs[idx])).toFloat()
                    val a = graphicGains.getOrElse(idx) { 0f }
                    val b = graphicGains.getOrElse(idx + 1) { 0f }
                    a + (b - a) * t
                }
            }
            base[i].copy(gainDb = db)
        }
    }

    /**
     * 段数变化时的曲线重采样：把 [source]（共 sourceCount 段）按对数频率插值到 targetCount 段。
     */
    private fun resample(source: FloatArray, sourceCount: Int, targetCount: Int): FloatArray {
        val from = sourceCount.coerceAtLeast(3)
        val oldFreqs = BreezyDspProcessor.centerFrequencies(from)
        val newFreqs = BreezyDspProcessor.centerFrequencies(targetCount)
        return FloatArray(targetCount) { i ->
            val f = newFreqs[i]
            if (f <= oldFreqs.first()) source.firstOrNull() ?: 0f
            else if (f >= oldFreqs.last()) source.getOrElse(from - 1) { 0f }
            else {
                var idx = 0
                while (idx < from - 2 && oldFreqs[idx + 1] < f) idx++
                val f0 = oldFreqs[idx]
                val f1 = oldFreqs[idx + 1]
                val t = (kotlin.math.ln(f / f0) / kotlin.math.ln(f1 / f0)).toFloat()
                val a = source.getOrElse(idx) { 0f }
                val b = source.getOrElse(idx + 1) { 0f }
                a + (b - a) * t
            }
        }
    }
}

/** 混响预设库：参数取自常见空间感的经验值，用户可在此基础上微调湿度与房间大小 */
object ReverbPresets {
    const val OFF_ID = "off"
    const val CUSTOM_ID = "custom"

    val ALL: List<ReverbPreset> = listOf(
        ReverbPreset(OFF_ID, "关闭", 0f, 0.5f, enabled = false),
        ReverbPreset("studio", "录音室", 0.12f, 0.55f),
        ReverbPreset("room", "房间", 0.22f, 0.68f),
        ReverbPreset("club", "俱乐部", 0.30f, 0.76f),
        ReverbPreset("hall", "大厅", 0.36f, 0.82f),
        ReverbPreset("church", "教堂", 0.44f, 0.88f),
        ReverbPreset("plate", "板式", 0.28f, 0.62f),
        ReverbPreset("live", "现场", 0.32f, 0.74f)
    )

    fun byId(id: String?): ReverbPreset? = ALL.firstOrNull { it.id == id }

    fun matchByParams(mix: Float, room: Float): ReverbPreset? = ALL.firstOrNull {
        kotlin.math.abs(it.mix - mix) < 0.005f && kotlin.math.abs(it.room - room) < 0.005f
    }

    fun current(id: String?): ReverbPreset? = byId(id)
}
