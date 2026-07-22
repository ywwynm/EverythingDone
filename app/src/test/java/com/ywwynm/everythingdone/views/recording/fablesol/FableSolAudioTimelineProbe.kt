package com.ywwynm.everythingdone.views.recording.fablesol

import java.io.File
import java.util.Locale
import kotlin.math.max
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Android 侧音画耦合的离线回放探针，用于与 Python 模拟器逐帧比对。
 *
 * 它不是常规回归测试：只有当环境变量 `FABLESOL_PROBE_WAV` 指向一个 16bit PCM WAV
 * 时才真正跑，否则直接跳过（CI/常规 `testDebugUnitTest` 不受影响）。输出一份
 * 与 Python 实时 probe 同口径的 CSV。源声道声明会保留为诊断列；分析按产品的
 * 512-sample 观测批次推进，事件不得按声学边界时间回插到更早的 batch。
 *
 * 用法：
 * ```
 * gradlew -Dfablesol.probe.wav=<wav> -Dfablesol.probe.out=<csv> \
 *     -Dfablesol.probe.profile=capture :app:testDebugUnitTest \
 *     --tests "*FableSolAudioTimelineProbe.dumpTimeline*"
 * ```
 */
class FableSolAudioTimelineProbe {

    @Test
    fun timelineHeaderCarriesDisplayAndRawGateColumnsInParityOrder() {
        val columns = TIMELINE_HEADER.trim().split(',')
        val arousal = columns.indexOf("arousal")
        assertEquals(
            listOf(
                "display_water",
                "display_grade",
                "display_lift_score",
                "display_climax_score",
                "display_rel_low",
                "display_rel_mid",
                "display_rel_high",
                "display_centroid",
                "gate_state"
            ),
            columns.subList(arousal + 1, arousal + 10)
        )
    }

    /** 可选的 BeatTracker 差分探针；输入 CSV 至少包含 t,onset_env 两列。 */
    @Test
    fun dumpBeatTimeline() {
        val inputPath = System.getenv("FABLESOL_BEAT_PROBE_IN") ?: return
        val outputPath = System.getenv("FABLESOL_BEAT_PROBE_OUT") ?: return
        val lines = File(inputPath).readLines()
        if (lines.size <= 1) return
        val header = lines.first().split(',')
        val tIndex = header.indexOf("t")
        val onsetIndex = header.indexOf("onset_env")
        require(tIndex >= 0 && onsetIndex >= 0) { "beat probe requires t,onset_env" }
        val tracker = FableSolBeatTracker(48000.0 / FableSolRealtimeAnalyzer.HOP)
        val output = StringBuilder("t,bpm,raw_conf\n")
        for (line in lines.drop(1)) {
            if (line.isBlank()) continue
            val columns = line.split(',')
            val t = columns[tIndex].toDouble()
            tracker.push(columns[onsetIndex].toDouble(), t)
            val state = tracker.state(t)
            output.append(fmt(t)).append(',')
                .append(String.format(Locale.US, "%.9f", state.first)).append(',')
                .append(String.format(Locale.US, "%.9f", state.third)).append('\n')
        }
        File(outputPath).apply { parentFile?.mkdirs() }.writeText(output.toString())
        println("fablesol beat probe written: $outputPath (${lines.size - 1} hops)")
    }

    @Test
    fun dumpTimeline() {
        val wavPath = System.getProperty("fablesol.probe.wav")
            ?: System.getenv("FABLESOL_PROBE_WAV") ?: return
        val outPath = System.getProperty("fablesol.probe.out")
            ?: System.getenv("FABLESOL_PROBE_OUT") ?: return
        val profileName = System.getProperty("fablesol.probe.profile")
            ?: System.getenv("FABLESOL_PROBE_PROFILE") ?: "capture"
        val wav = File(wavPath)
        if (!wav.isFile) return

        val decoded = readWav(wav)
        require(decoded.sourceChannels == 1) {
            "Android 产品分析器只接收 AudioRecord 单声道；不能把 ${decoded.sourceChannels} 声道文件降混后当作产品路径。"
        }
        require(profileName == "capture" || profileName == "master") {
            "unknown probe profile: $profileName"
        }
        val profile = if (profileName == "capture") {
            FableSolCaptureProfile.PHONE_CAPTURE_V1
        } else {
            null
        }
        val analyzer = FableSolRealtimeAnalyzer(decoded.sampleRate, profile)
        val batches = ArrayList<ObservedBatch>()
        val chunk = 512
        var offset = 0
        while (offset < decoded.mono.size) {
            val count = minOf(chunk, decoded.mono.size - offset)
            val block = DoubleArray(count)
            System.arraycopy(decoded.mono, offset, block, 0, count)
            val (f, e) = analyzer.feed(block, count)
            if (f.isNotEmpty() || e.isNotEmpty()) {
                batches.add(
                    ObservedBatch(
                        sequence = batches.size,
                        availableAt = (offset + count).toDouble() / decoded.sampleRate,
                        batch = FableSolAnalysisBatch(ArrayList(f), e.sortedBy { it.t })
                    )
                )
            }
            offset += count
        }

        val params = FableSolParams()
        val mapper = FableSolFeatureMapper(params)
        val sim = FableSolSimulation(params)
        val rows = StringBuilder(TIMELINE_HEADER)
        val duration = decoded.mono.size.toDouble() / decoded.sampleRate
        var batchIndex = 0
        var lastBatchSequence = -1
        var lastFrame: FableSolFeatureFrame? = null
        var sectionCount = 0
        val grandAudioTimes = ArrayList<Double>()
        val total = (duration * 60.0).toInt()
        for (index in 0 until total) {
            val t = (index + 1) / 60.0
            val grandBefore = sim.grandWave.triggerCount
            var grandAudioT = Double.NaN
            // 最后一个输入 sample 到达后 batch 才可见；批内顺序复用产品消费器。
            while (batchIndex < batches.size && batches[batchIndex].availableAt <= t + 1e-9) {
                val observed = batches[batchIndex++]
                lastBatchSequence = observed.sequence
                FableSolAnalysisBatchConsumer.consume(
                    listOf(observed.batch),
                    { frame ->
                        val beforeFrame = sim.grandWave.triggerCount
                        mapper.applyFrame(sim, frame)
                        lastFrame = frame
                        if (sim.grandWave.triggerCount > beforeFrame) {
                            grandAudioT = frame.t
                            grandAudioTimes.add(frame.t)
                        }
                    },
                    { event ->
                        val beforeEvent = sim.grandWave.triggerCount
                        applyEvent(mapper, sim, event)
                        if (event is FableSolEvent.Section) sectionCount++
                        if (sim.grandWave.triggerCount > beforeEvent) {
                            grandAudioT = lastFrame?.t ?: event.t
                            grandAudioTimes.add(grandAudioT)
                        }
                    }
                )
            }
            val grandDelta = sim.grandWave.triggerCount - grandBefore
            sim.update(1.0 / 60.0)
            val fr = lastFrame
            rows.append(
                listOf(
                    fmt(t),
                    fmt(if (fr?.isSilent == true) 1.0 else 0.0),
                    fmt(fr?.waterDrive01 ?: 0.0),
                    fmt(fr?.kineticDrive01 ?: 0.0),
                    fmt(fr?.gradeDrive01 ?: 0.0),
                    fmt(fr?.climaxScore01 ?: 0.0),
                    fmt(sim.flow01),
                    fmt(sim.layers[0].flowDps),
                    fmt(sim.layers[6].flowDps),
                    fmt(sim.layers[8].flowDps),
                    fmt(sim.visualWaveScale),
                    fmt(stateIndex(sim.visualState).toDouble()),
                    fmt(sim.visualLevelDp),
                    sim.grandWave.triggerCount.toString(),
                    fmt(fr?.loudDb ?: -120.0),
                    fmt(fr?.floorDb ?: -120.0),
                    fmt(fr?.speed01 ?: 0.0),
                    fmt(fr?.percussiveMotion01 ?: 0.0),
                    fmt(fr?.harmonicMotion01 ?: 0.0),
                    fmt(fr?.vocalMotion01 ?: 0.0),
                    fmt(fr?.beatMotion01 ?: 0.0),
                    fmt(fr?.musicArousal01 ?: 0.0),
                    fmt(fr?.displayWaterDrive01 ?: 0.0),
                    fmt(fr?.displayGradeDrive01 ?: 0.0),
                    fmt(fr?.displayLiftScore01 ?: 0.0),
                    fmt(fr?.displayClimaxScore01 ?: 0.0),
                    fmt(fr?.displayRelLow ?: (1.0 / 3.0)),
                    fmt(fr?.displayRelMid ?: (1.0 / 3.0)),
                    fmt(fr?.displayRelHigh ?: (1.0 / 3.0)),
                    fmt(fr?.displayCentroid01 ?: 0.5),
                    fmt(stateIndex(mapper.currentGateState().name).toDouble()),
                    fmt(fr?.t ?: Double.NaN),
                    grandDelta.toString(),
                    fmt(grandAudioT),
                    lastBatchSequence.toString(),
                    decoded.sourceChannels.toString(),
                    sectionCount.toString(),
                    fmt(fr?.intensityDrive01 ?: 0.0),
                    fmt(fr?.energyRising01 ?: 0.0),
                    fmt(fr?.motionContextBoost01 ?: 0.0),
                    fmt(fr?.punch01 ?: 0.0),
                    fmt(fr?.punchLu01 ?: 0.0),
                    fmt(max(fr?.percussiveMotion01 ?: 0.0, fr?.harmonicMotion01 ?: 0.0)),
                    fmt(fr?.grooveMotion01 ?: 0.0),
                    fmt(fr?.kineticTarget01 ?: 0.0),
                    fmt(fr?.energy01 ?: 0.0),
                    fmt(fr?.buildUp01 ?: 0.0),
                    fmt(fr?.tempoBpm ?: 0.0),
                    fmt(fr?.beatConf01 ?: 0.0),
                    fmt(fr?.onsetEnv ?: 0.0),
                    fmt(fr?.music01 ?: 0.0)
                ).joinToString(",")
            ).append('\n')
        }
        File(outPath).apply { parentFile?.mkdirs() }.writeText(rows.toString())
        val hopCount = batches.sumOf { it.batch.frames.size }
        println(
            "fablesol probe written: $outPath (${total} render frames, $hopCount hops, " +
                "${batches.size} observed batches, sourceChannels=${decoded.sourceChannels}, " +
                "grand=${grandAudioTimes.joinToString(prefix = "[", postfix = "]") { "%.3f".format(it) }})"
        )
    }

    private fun applyEvent(
        mapper: FableSolFeatureMapper,
        sim: FableSolSimulation,
        event: FableSolEvent
    ) {
        when (event) {
            is FableSolEvent.Onset -> mapper.applyOnset(sim, event)
            is FableSolEvent.Section -> mapper.applySection(sim, event)
            is FableSolEvent.Prominence -> mapper.applyProminence(sim, event)
            is FableSolEvent.Drop -> mapper.applyDrop(sim, event)
            is FableSolEvent.NoveltyMinor -> Unit
        }
    }

    private fun stateIndex(name: String): Int = STATES.indexOf(name)

    private fun fmt(value: Double): String = String.format(Locale.US, "%.4f", value)

    private class ObservedBatch(
        val sequence: Int,
        val availableAt: Double,
        val batch: FableSolAnalysisBatch
    )

    private class DecodedWav(
        val mono: DoubleArray,
        val sampleRate: Int,
        val sourceChannels: Int
    )

    private fun readWav(file: File): DecodedWav {
        val bytes = file.readBytes()
        require(bytes.size > 44) { "wav too short" }
        fun u32(at: Int) = (bytes[at].toInt() and 0xFF) or
            ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or
            ((bytes[at + 3].toInt() and 0xFF) shl 24)

        fun u16(at: Int) = (bytes[at].toInt() and 0xFF) or
            ((bytes[at + 1].toInt() and 0xFF) shl 8)

        var pos = 12
        var channels = 1
        var sampleRate = 44100
        var bits = 16
        var audioFormat = 1
        var dataOffset = -1
        var dataLength = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = u32(pos + 4)
            val body = pos + 8
            when (id) {
                "fmt " -> {
                    audioFormat = u16(body)
                    channels = u16(body + 2)
                    sampleRate = u32(body + 4)
                    bits = u16(body + 14)
                }
                "data" -> {
                    dataOffset = body
                    dataLength = size
                }
            }
            pos = body + size + (size and 1)
            if (dataOffset >= 0 && id == "data") break
        }
        require(dataOffset >= 0) { "no data chunk" }
        require(audioFormat == 1) { "only PCM WAV supported, got format $audioFormat" }
        require(bits == 16) { "only 16-bit PCM supported, got $bits" }
        require(channels > 0) { "invalid source channel count: $channels" }
        val frameCount = dataLength / (2 * channels)
        val mono = DoubleArray(frameCount)
        for (i in 0 until frameCount) {
            var sum = 0.0
            for (c in 0 until channels) {
                val at = dataOffset + (i * channels + c) * 2
                val sample = ((bytes[at].toInt() and 0xFF) or (bytes[at + 1].toInt() shl 8)).toShort()
                sum += sample.toDouble() / 32768.0
            }
            mono[i] = sum / channels
        }
        return DecodedWav(mono, sampleRate, channels)
    }

    companion object {
        private const val TIMELINE_HEADER =
            "t,is_silent,water,kinetic,grade,climax_score,flow01,flow_l0,flow_l6," +
                "flow_l8,wave_scale,state,level,grand_count," +
                "loud_db,floor_db,speed01,perc_motion,harm_motion,vocal_motion," +
                "beat_motion,arousal,display_water,display_grade,display_lift_score," +
                "display_climax_score,display_rel_low,display_rel_mid,display_rel_high," +
                "display_centroid,gate_state,audio_t,grand_delta,grand_audio_t," +
                "analysis_batch,source_channels,section_count,intensity,energy_rising," +
                "context,punch,punch_lu,music_motion,groove_motion,kinetic_target," +
                "energy,build,tempo_bpm,beat_conf,onset_env,music_gate_state\n"
        private val STATES = listOf(
            "IDLE", "SILENCE", "CALM", "GROOVE", "LIFT", "PEAK", "CLIMAX"
        )
    }
}
