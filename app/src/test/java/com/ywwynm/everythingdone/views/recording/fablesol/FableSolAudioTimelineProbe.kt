package com.ywwynm.everythingdone.views.recording.fablesol

import java.io.File
import org.junit.Test

/**
 * Android 侧音画耦合的离线回放探针，用于与 Python 模拟器逐帧比对。
 *
 * 它不是常规回归测试：只有当环境变量 `FABLESOL_PROBE_WAV` 指向一个 16bit PCM WAV
 * 时才真正跑，否则直接跳过（CI/常规 `testDebugUnitTest` 不受影响）。输出一份
 * 与 `scratch/av_probe.py --csv` 同列名的 CSV，便于两侧做同口径比较。
 *
 * 用法：
 * ```
 * gradlew :app:testDebugUnitTest --tests "*FableSolAudioTimelineProbe*" \
 *     -PfablesolProbeWav=<wav> -PfablesolProbeOut=<csv> [-PfablesolProbeProfile=capture|master]
 * ```
 */
class FableSolAudioTimelineProbe {

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
        val profile = if (profileName == "capture") {
            FableSolCaptureProfile.PHONE_CAPTURE_V1
        } else {
            null
        }
        val analyzer = FableSolRealtimeAnalyzer(decoded.sampleRate, profile)
        val frames = ArrayList<FableSolFeatureFrame>()
        val events = ArrayList<FableSolEvent>()
        val chunk = 4096
        var offset = 0
        while (offset < decoded.mono.size) {
            val count = minOf(chunk, decoded.mono.size - offset)
            val block = DoubleArray(count)
            System.arraycopy(decoded.mono, offset, block, 0, count)
            val (f, e) = analyzer.feed(block, count)
            frames.addAll(f)
            events.addAll(e)
            offset += count
        }
        events.sortBy { it.t }

        val params = FableSolParams()
        val mapper = FableSolFeatureMapper(params)
        val sim = FableSolSimulation(params)
        val rows = StringBuilder()
        rows.append(
            "t,is_silent,water,kinetic,grade,climax_score,flow01,flow_l0,flow_l6," +
                "flow_l8,wave_scale,state,level,grand_count," +
                "loud_db,floor_db,speed01,perc_motion,harm_motion,vocal_motion," +
                "beat_motion,arousal\n"
        )
        val duration = decoded.mono.size.toDouble() / decoded.sampleRate
        var frameIndex = 0
        var eventIndex = 0
        var lastFrame: FableSolFeatureFrame? = null
        val total = (duration * 60.0).toInt()
        for (index in 0 until total) {
            val t = (index + 1) / 60.0
            // 与 Android GlRenderer 一致：一次渲染帧内逐个消费权威 hop 与其间事件。
            while (frameIndex < frames.size && frames[frameIndex].t <= t) {
                val frame = frames[frameIndex++]
                while (eventIndex < events.size && events[eventIndex].t < frame.t) {
                    applyEvent(mapper, sim, events[eventIndex++])
                }
                mapper.applyFrame(sim, frame)
                lastFrame = frame
                while (eventIndex < events.size && events[eventIndex].t <= frame.t) {
                    applyEvent(mapper, sim, events[eventIndex++])
                }
            }
            sim.update(1.0 / 60.0)
            val fr = lastFrame
            rows.append(
                ("%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f," +
                    "%.4f,%d,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f\n").format(
                    t,
                    if (fr?.isSilent == true) 1.0 else 0.0,
                    fr?.waterDrive01 ?: 0.0,
                    fr?.kineticDrive01 ?: 0.0,
                    fr?.gradeDrive01 ?: 0.0,
                    fr?.climaxScore01 ?: 0.0,
                    sim.flow01,
                    sim.layers[0].flowDps,
                    sim.layers[6].flowDps,
                    sim.layers[8].flowDps,
                    sim.visualWaveScale,
                    stateIndex(sim.visualState).toDouble(),
                    sim.visualLevelDp,
                    sim.grandWave.triggerCount,
                    fr?.loudDb ?: -120.0,
                    fr?.floorDb ?: -120.0,
                    fr?.speed01 ?: 0.0,
                    fr?.percussiveMotion01 ?: 0.0,
                    fr?.harmonicMotion01 ?: 0.0,
                    fr?.vocalMotion01 ?: 0.0,
                    fr?.beatMotion01 ?: 0.0,
                    fr?.musicArousal01 ?: 0.0
                )
            )
        }
        File(outPath).apply { parentFile?.mkdirs() }.writeText(rows.toString())
        println("fablesol probe written: $outPath (${total} frames, ${frames.size} hops)")
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
        }
    }

    private fun stateIndex(name: String): Int = STATES.indexOf(name)

    private class DecodedWav(val mono: DoubleArray, val sampleRate: Int)

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
        var dataOffset = -1
        var dataLength = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = u32(pos + 4)
            val body = pos + 8
            when (id) {
                "fmt " -> {
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
        require(bits == 16) { "only 16-bit PCM supported, got $bits" }
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
        return DecodedWav(mono, sampleRate)
    }

    companion object {
        private val STATES = listOf(
            "IDLE", "SILENCE", "CALM", "GROOVE", "LIFT", "PEAK", "CLIMAX"
        )
    }
}
