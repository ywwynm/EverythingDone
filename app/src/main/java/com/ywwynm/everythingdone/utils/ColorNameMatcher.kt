package com.ywwynm.everythingdone.utils

import android.content.Context
import android.graphics.Color
import androidx.core.graphics.ColorUtils

import com.ywwynm.everythingdone.R

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object ColorNameMatcher {

    const val DATASET_VERSION: String = "14.38.0"

    private const val ASSET_PATH = "color_names/meodai_color_names_14_38_0.tsv"
    private const val CACHE_MAX_SIZE = 256

    @Volatile
    private var sEntries: List<Entry>? = null

    private val sCache = object : LinkedHashMap<Int, SearchResult>(CACHE_MAX_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, SearchResult>?): Boolean {
            return size > CACHE_MAX_SIZE
        }
    }

    data class Match(
        val localizedName: String,
        val englishName: String,
        val matchedHex: String,
        val distance: Double,
        val hasLocalizedTranslation: Boolean
    )

    data class ColorDescription(
        val color: Int,
        val rgb: String,
        val hex: String,
        val hsl: String,
        val match: Match
    )

    private data class Entry(
        val color: Int,
        val hex: String,
        val englishName: String,
        val zhName: String,
        val lab: DoubleArray
    )

    private data class SearchResult(val index: Int, val distance: Double)

    @JvmStatic
    fun describeColor(context: Context, color: Int): ColorDescription {
        val opaque = Color.rgb(Color.red(color), Color.green(color), Color.blue(color))
        return ColorDescription(
            opaque,
            formatRgb(opaque),
            formatHex(opaque),
            formatHsl(opaque),
            match(context, opaque)
        )
    }

    @JvmStatic
    fun match(context: Context, color: Int): Match {
        val entries = loadEntries(context)
        if (entries.isEmpty()) {
            val hex = formatHex(color)
            return Match(hex, hex, hex, 0.0, true)
        }

        val key = color and 0x00FFFFFF
        val result = synchronized(sCache) {
            sCache[key]
        } ?: findNearest(entries, color).also {
            synchronized(sCache) { sCache[key] = it }
        }
        val entry = entries[result.index]
        val useChinese = LocaleUtil.isChinese(context)
        val localized = if (useChinese && entry.zhName.isNotBlank()) entry.zhName else entry.englishName
        return Match(
            localized,
            entry.englishName,
            entry.hex,
            result.distance,
            !useChinese || entry.zhName.isNotBlank()
        )
    }

    @JvmStatic
    fun sourceValue(context: Context): String {
        return context.getString(R.string.color_info_source_value)
    }

    @JvmStatic
    fun matchValue(context: Context, match: Match): String {
        return context.getString(
            R.string.color_info_match_value,
            String.format(Locale.US, "%.2f", match.distance)
        )
    }

    private fun findNearest(entries: List<Entry>, color: Int): SearchResult {
        val lab = DoubleArray(3)
        ColorUtils.RGBToLAB(Color.red(color), Color.green(color), Color.blue(color), lab)

        var bestIndex = 0
        var bestDistance = Double.MAX_VALUE
        for (i in entries.indices) {
            val distance = ciede2000(lab, entries[i].lab)
            if (distance < bestDistance) {
                bestDistance = distance
                bestIndex = i
                if (distance == 0.0) break
            }
        }
        return SearchResult(bestIndex, bestDistance)
    }

    private fun loadEntries(context: Context): List<Entry> {
        val cached = sEntries
        if (cached != null) return cached

        synchronized(this) {
            val cachedAgain = sEntries
            if (cachedAgain != null) return cachedAgain

            val loaded = ArrayList<Entry>(32000)
            val input = context.assets.open(ASSET_PATH)
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                reader.readLine()
                var line: String?
                while (true) {
                    line = reader.readLine() ?: break
                    val parts = line!!.split('\t', limit = 3)
                    if (parts.size < 2) continue
                    val hex = parts[0]
                    val color = Color.parseColor(hex)
                    val lab = DoubleArray(3)
                    ColorUtils.RGBToLAB(Color.red(color), Color.green(color), Color.blue(color), lab)
                    loaded.add(Entry(
                        color,
                        hex,
                        parts[1],
                        if (parts.size >= 3) parts[2] else "",
                        lab
                    ))
                }
            }
            sEntries = loaded
            return loaded
        }
    }

    private fun formatRgb(color: Int): String {
        return "${Color.red(color)}, ${Color.green(color)}, ${Color.blue(color)}"
    }

    @JvmStatic
    fun formatHex(color: Int): String {
        return String.format(
            Locale.US,
            "#%02X%02X%02X",
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )
    }

    private fun formatHsl(color: Int): String {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color, hsl)
        return String.format(
            Locale.US,
            "%.0f°, %.0f%%, %.0f%%",
            hsl[0],
            hsl[1] * 100f,
            hsl[2] * 100f
        )
    }

    private fun ciede2000(lab1: DoubleArray, lab2: DoubleArray): Double {
        val l1 = lab1[0]
        val a1 = lab1[1]
        val b1 = lab1[2]
        val l2 = lab2[0]
        val a2 = lab2[1]
        val b2 = lab2[2]

        val avgLp = (l1 + l2) * 0.5
        val c1 = sqrt(a1 * a1 + b1 * b1)
        val c2 = sqrt(a2 * a2 + b2 * b2)
        val avgC = (c1 + c2) * 0.5
        val avgC7 = avgC.pow(7.0)
        val g = 0.5 * (1.0 - sqrt(avgC7 / (avgC7 + 25.0.pow(7.0))))

        val a1p = (1.0 + g) * a1
        val a2p = (1.0 + g) * a2
        val c1p = sqrt(a1p * a1p + b1 * b1)
        val c2p = sqrt(a2p * a2p + b2 * b2)

        val h1p = hueDegrees(a1p, b1)
        val h2p = hueDegrees(a2p, b2)

        val deltaLp = l2 - l1
        val deltaCp = c2p - c1p
        val deltahp = when {
            c1p * c2p == 0.0 -> 0.0
            abs(h2p - h1p) <= 180.0 -> h2p - h1p
            h2p <= h1p -> h2p - h1p + 360.0
            else -> h2p - h1p - 360.0
        }
        val deltaHp = 2.0 * sqrt(c1p * c2p) * sin(Math.toRadians(deltahp * 0.5))

        val avgCp = (c1p + c2p) * 0.5
        val avgHp = when {
            c1p * c2p == 0.0 -> h1p + h2p
            abs(h1p - h2p) <= 180.0 -> (h1p + h2p) * 0.5
            h1p + h2p < 360.0 -> (h1p + h2p + 360.0) * 0.5
            else -> (h1p + h2p - 360.0) * 0.5
        }

        val t = 1.0 -
            0.17 * cos(Math.toRadians(avgHp - 30.0)) +
            0.24 * cos(Math.toRadians(2.0 * avgHp)) +
            0.32 * cos(Math.toRadians(3.0 * avgHp + 6.0)) -
            0.20 * cos(Math.toRadians(4.0 * avgHp - 63.0))
        val deltaTheta = 30.0 * exp(-((avgHp - 275.0) / 25.0).pow(2.0))
        val avgCp7 = avgCp.pow(7.0)
        val rc = 2.0 * sqrt(avgCp7 / (avgCp7 + 25.0.pow(7.0)))
        val sl = 1.0 + (0.015 * (avgLp - 50.0).pow(2.0)) /
            sqrt(20.0 + (avgLp - 50.0).pow(2.0))
        val sc = 1.0 + 0.045 * avgCp
        val sh = 1.0 + 0.015 * avgCp * t
        val rt = -sin(Math.toRadians(2.0 * deltaTheta)) * rc

        val lTerm = deltaLp / sl
        val cTerm = deltaCp / sc
        val hTerm = deltaHp / sh
        return sqrt(lTerm * lTerm + cTerm * cTerm + hTerm * hTerm + rt * cTerm * hTerm)
    }

    private fun hueDegrees(a: Double, b: Double): Double {
        if (a == 0.0 && b == 0.0) return 0.0
        var hue = Math.toDegrees(atan2(b, a))
        if (hue < 0.0) hue += 360.0
        return hue
    }
}
