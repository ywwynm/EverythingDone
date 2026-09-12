package com.ywwynm.everythingdone.views.particledismiss

import kotlin.math.*

/** 素材无关的材料输入。384 dp 对应 720 逻辑坐标，纹理保留设备原始分辨率。 */
internal object ParticleMicroflakeModel {
    const val STEP = 1f / 240f
    const val DURATION = 1f
    private data class Normals(val x: FloatArray, val y: FloatArray, val milliseconds: Double)

    data class Materials(
        val columns: Int,
        val rows: Int,
        val cellX: Float,
        val cellY: Float,
        val bodyWeight: Float,
        val values: FloatArray,
        val pigment: FloatArray,
        val peelCompression: FloatArray,
        val variation: ParticleMicroflakeVariation,
        val statistics: Map<String, Double> = emptyMap()
    ) {
        val count get() = pigment.size
    }

    fun build(
        width: Float,
        height: Float,
        pixels: IntArray,
        pixelWidth: Int,
        pixelHeight: Int,
        directionDegrees: Float,
        seed: Long,
        rules: ParticleMicroflakeRules
    ): Materials {
        val started = System.nanoTime()
        require(width > 0 && height > 0 && pixels.size == pixelWidth * pixelHeight)
        val panel = ParticleMicroflakeContent.panel(pixels, pixelWidth, pixelHeight, rules)
        val copyLimit = rules.number("content_copies").toInt()
        val budget = 1.0 + copyLimit * panel.weight.toDouble() * (1.0 - panel.fraction)
        val cell = max(rules.decimal("cell"), sqrt(width.toDouble() * height * budget / (rules.number("max_cells") - 1000)))
        val nx = ceil(width / cell).toInt().coerceAtLeast(2)
        val ny = ceil(height / cell).toInt().coerceAtLeast(2)
        val count = nx * ny
        val cellX = width / nx
        val cellY = height / ny
        var opaque = 0
        var white = 0
        val sampleStep = ceil(sqrt(pixels.size / 65536.0)).toInt().coerceAtLeast(1)
        for (y in 0 until pixelHeight step sampleStep) for (x in 0 until pixelWidth step sampleStep) {
            val c = pixels[y * pixelWidth + x]
            if (c ushr 24 > 242) {
                opaque++
                if (min(c shr 16 and 255, min(c shr 8 and 255, c and 255)) > 229) white++
            }
        }
        val body = smooth(((if (opaque == 0) 0f else white.toFloat() / opaque) - .30f) / .35f)
        val classified = System.nanoTime()
        val metrics = linkedMapOf("classificationMs" to (classified-started)/1e6)
        val variation = ParticleMicroflakeVariation.fromSeed(seed, rules)
        val offsetTimes = FloatArray(count)
        val initialRelease = releaseField(nx, ny, directionDegrees, rules, width, height, variation, offsetTimes) { name, value -> metrics[name] = value }
        val finishedField = if (ParticleMaterialNative.enabled) {
            val angle = Math.toRadians(directionDegrees.toDouble())
            ParticleMaterialNative.finishField(initialRelease, nx, ny, width, height, cos(angle), -sin(angle),
                panel.weight, gaussianWeights(1), gaussianWeights(3), gaussianWeights(7))
        } else null
        val release = finishedField?.get(0) ?: refineRelease(initialRelease, nx, ny, width, height, directionDegrees, panel.weight)
        val released = System.nanoTime()
        // 本地批次同时返回梯度结果；原 Kotlin 顺序计算保留为独立参考与加载失败回退。
        val normals = if (finishedField == null) buildNormals(release, nx, ny, cellX, cellY)
            else Normals(finishedField[1], finishedField[2], 0.0)
        val compressionStarted = System.nanoTime()
        val compression = finishedField?.get(3) ?: buildPeelCompression(normals, nx, ny, cellX, cellY, min(width, height), directionDegrees)
        metrics["compressionMs"] = (System.nanoTime() - compressionStarted) / 1e6
        val normalsReady = System.nanoTime()
        val releaseSpread = rules.number("release_spread") + rules.number("white_spread") * body
        val lifeGain = rules.number("life_gain")
        val earlyLifeGain = rules.number("life_early_gain")
        val lifeBirthStart = rules.number("life_birth_start")
        val lifeBirthSpan = rules.number("life_birth_span")
        val distanceStart = rules.number("content_distance_start")
        val distanceSpan = rules.number("content_distance_span")
        val randomSeed = (seed xor (seed ushr 32)).toInt()
        if (ParticleMaterialNative.enabled) {
            val result = ParticleMaterialNative.populate(nx, ny, width, height, pixels, pixelWidth,
                pixelHeight, randomSeed, release, offsetTimes, normals.x, normals.y, compression,
                floatArrayOf(panel.color[0], panel.color[1], panel.color[2], panel.weight),
                floatArrayOf(releaseSpread, lifeGain, earlyLifeGain, lifeBirthStart, lifeBirthSpan,
                    distanceStart, distanceSpan), copyLimit, rules.number("max_cells").toInt())
            return Materials(nx, ny, cellX, cellY, body, result[0], result[1], result[2], variation,
                metrics + mapOf("releaseMs" to (released-started)/1e6, "normalsMs" to normals.milliseconds,
                    "populationMs" to (System.nanoTime()-normalsReady)/1e6, "sortMs" to 0.0,
                    "panelWeight" to panel.weight.toDouble(), "replicaCount" to (result[1].size-count).toDouble()))
        }
        val values = FloatArray(count * 12)
        val pigment = FloatArray(count)
        val colors = IntArray(count)
        for (i in 0 until count) {
            val x = (i % nx + .5f) * cellX
            val y = (i / nx + .5f) * cellY
            val rx = randomValue(i * 4 + 0, randomSeed)
            val ry = randomValue(i * 4 + 1, randomSeed)
            val rz = randomValue(i * 4 + 2, randomSeed)
            val rw = randomValue(i * 4 + 3, randomSeed)
            val born = max(release[i] + releaseSpread * (rw - .5f), .001f)
            val px = (x / width * pixelWidth).toInt().coerceIn(0, pixelWidth - 1)
            val py = (y / height * pixelHeight).toInt().coerceIn(0, pixelHeight - 1)
            val c = pixels[py * pixelWidth + px]
            colors[i] = c
            val content = ParticleMicroflakeContent.weight(c, panel, distanceStart, distanceSpan)
            val cap = .865f + .115f * rz - born
            // 与桌面使用同一连续寿命分布，避免早释放的慢速片长时间留下孤立尘缕。
            val gain = earlyLifeGain + (lifeGain - earlyLifeGain) * smooth((born - lifeBirthStart) / lifeBirthSpan)
            var life = (.10f + .22f * (-ln(max(rx, .004f))).pow(.85f) + .20f * born) * gain
            life = max(min(life, cap), .11f)
            life = max(min(life * (1f + .30f * content), cap), .11f)
            val p = i * 12
            values[p] = x; values[p + 1] = y; values[p + 2] = born; values[p + 3] = i.toFloat()
            values[p + 6] = life; values[p + 7] = offsetTimes[i]
            values[p + 8] = rx; values[p + 9] = ry; values[p + 10] = rz; values[p + 11] = rw
            pigment[i] = content
        }
        // 原始密铺面保留，每个内容格最多补两片；不复制预乘量化的透明边缘。
        val candidates = ArrayList<Int>()
        for (layer in 1..copyLimit) for (i in 0 until count) {
            val id = i + layer * count
            val amount = pigment[i] * (copyLimit * panel.weight)
            if (colors[i] ushr 24 == 255 && randomValue(id * 4, randomSeed) < amount - (layer - 1)) candidates.add(id)
        }
        val capacity = (rules.number("max_cells").toInt() - count).coerceAtLeast(0)
        if (candidates.size > capacity) {
            candidates.sortWith(compareBy<Int> { randomValue(it * 4 + 1, randomSeed) }.thenBy { it })
            candidates.subList(capacity, candidates.size).clear()
        }
        candidates.sort()
        val total = count + candidates.size
        val allValues = values.copyOf(total * 12)
        val allPigment = pigment.copyOf(total)
        for ((index, id) in candidates.withIndex()) {
            val source = id % count
            val p = (count + index) * 12
            values.copyInto(allValues, p, source * 12, source * 12 + 12)
            allValues[p + 3] = id.toFloat()
            for (k in 0..3) allValues[p + 8 + k] = randomValue(id * 4 + k, randomSeed)
            val rx = allValues[p + 8]; val rz = allValues[p + 10]; val born = allValues[p + 2]
            val cap = .865f + .115f * rz - born
            val gain = earlyLifeGain + (lifeGain - earlyLifeGain) * smooth((born - lifeBirthStart) / lifeBirthSpan)
            var life = (.10f + .22f * (-ln(max(rx, .004f))).pow(.85f) + .20f * born) * gain
            life = max(min(life, cap), .11f)
            allValues[p + 6] = max(min(life * (1f + .30f * pigment[source]), cap), .11f)
            allPigment[count + index] = pigment[source]
        }
        val order = LongArray(total)
        for (i in 0 until total) {
            val p = i * 12
            val depth = sin(allValues[p] * .014f + allValues[p + 1] * .021f) * .6f + (allValues[p + 9] - .5f) * .15f
            order[i] = ((depth + 1f).times(1_000_000).toLong() shl 32) or i.toLong()
        }
        val populated = System.nanoTime()
        // 原始索引破除等深度排序的歧义，不依赖对象排序或逐帧重排。
        order.sort()
        val sorted = FloatArray(allValues.size)
        val sortedPigment = FloatArray(total)
        val sortedCompression = FloatArray(total)
        for (i in order.indices) {
            val original = order[i].toInt()
            allValues.copyInto(sorted, i * 12, original * 12, original * 12 + 12)
            val source = allValues[original * 12 + 3].toInt() % count
            sorted[i * 12 + 4] = normals.x[source]
            sorted[i * 12 + 5] = normals.y[source]
            sortedPigment[i] = allPigment[original]
            sortedCompression[i] = compression[source]
        }
        return Materials(nx, ny, cellX, cellY, body, sorted, sortedPigment, sortedCompression, variation, metrics + mapOf(
            "releaseMs" to (released-started)/1e6, "normalsMs" to normals.milliseconds,
            "populationMs" to (populated-normalsReady)/1e6, "sortMs" to (System.nanoTime()-populated)/1e6,
            "panelWeight" to panel.weight.toDouble(), "replicaCount" to candidates.size.toDouble()))
    }

    /** 只修正进入流动的时刻；原随机流场时钟不变，不识别素材或内容位置。 */
    internal fun refineRelease(field: FloatArray, nx: Int, ny: Int, width: Float, height: Float,
        directionDegrees: Float, panelWeight: Float): FloatArray {
        val blurred = gaussian(field, nx, ny, 3)
        val span = min(width, height).toDouble()
        val cellX = width / nx; val cellY = height / ny
        val angle = Math.toRadians(directionDegrees.toDouble())
        val windX = cos(angle); val windY = -sin(angle)
        val edgeX = DoubleArray(nx) { x -> min((x + .5) / nx * width, width - (x + .5) / nx * width) }
        val edgeY = DoubleArray(ny) { y -> min((y + .5) / ny * height, height - (y + .5) / ny * height) }
        fun sm(value: Double): Double { val t = value.coerceIn(0.0, 1.0); return t*t*(3-2*t) }
        val againstX = DoubleArray(nx) { x -> sm((if (x + .5 < nx * .5) windX else -windX) / .65) * exp(-edgeX[x] / (span * .14)) }
        val againstY = DoubleArray(ny) { y -> sm((if (y + .5 < ny * .5) windY else -windY) / .65) * exp(-edgeY[y] / (span * .14)) }
        val coreGain = .025 * (1.0 - .65 * panelWeight)
        return FloatArray(field.size) { i ->
            val x = i % nx; val y = i / nx
            val dx = (blurred[y*nx+min(x+1,nx-1)]-blurred[y*nx+max(x-1,0)]) / (cellX * if (x == 0 || x == nx-1) 1 else 2)
            val dy = (blurred[min(y+1,ny-1)*nx+x]-blurred[max(y-1,0)*nx+x]) / (cellY * if (y == 0 || y == ny-1) 1 else 2)
            val norm = max(hypot(dx, dy), 1e-8f)
            val against = max(againstX[x], againstY[y]) * sm(((dx*windX+dy*windY)/norm-.40)/.50)
            val interior = 1-exp(-min(edgeX[x],edgeY[y])/(span*.10))
            // 与桌面 float32 前沿插值一致，余下几何运算使用双精度。
            val late = smooth((field[i]-.40f)/.17f)
            val ready = smooth((field[i]-.23f)/.25f)
            (field[i]+coreGain*interior*late-.065*against*ready).toFloat()
        }
    }

    private fun buildNormals(release: FloatArray, nx: Int, ny: Int, cellX: Float, cellY: Float): Normals {
        val started = System.nanoTime()
        val blurred = gaussian(release, nx, ny, 3)
        val gx = FloatArray(release.size)
        val gy = FloatArray(release.size)
        for (y in 0 until ny) for (x in 0 until nx) {
            val i = y * nx + x
            val dx = (blurred[y * nx + min(x + 1, nx - 1)] - blurred[y * nx + max(x - 1, 0)]) /
                (cellX * if (x == 0 || x == nx - 1) 1 else 2)
            val dy = (blurred[min(y + 1, ny - 1) * nx + x] - blurred[max(y - 1, 0) * nx + x]) /
                (cellY * if (y == 0 || y == ny - 1) 1 else 2)
            val length = max(hypot(dx, dy), 1e-6f)
            gx[i] = dx / length; gy[i] = dy / length
        }
        val normalsX = gaussian(gx, nx, ny, 7)
        val normalsY = gaussian(gy, nx, ny, 7)
        return Normals(normalsX, normalsY, (System.nanoTime() - started) / 1e6)
    }

    /** 对称速度导数的压缩部分。只减轻过度汇聚，不修改供表面与旋转使用的原法向。 */
    private fun buildPeelCompression(normals: Normals, nx: Int, ny: Int, cellX: Float,
        cellY: Float, span: Float, directionDegrees: Float): FloatArray {
        val angle = Math.toRadians(directionDegrees.toDouble())
        val wx = cos(angle); val wy = -sin(angle)
        val px = FloatArray(nx * ny); val py = FloatArray(nx * ny)
        for (i in px.indices) {
            var x = -normals.x[i]; var y = -normals.y[i]
            val along = x * wx + y * wy
            x = (x - min(along, 0.0) * wx).toFloat()
            y = (y - min(along, 0.0) * wy).toFloat()
            px[i] = (x - max(along, 0.0) * wx * .82).toFloat()
            py[i] = (y - max(along, 0.0) * wy * .82).toFloat()
        }
        val compression = FloatArray(nx * ny)
        for (y in 0 until ny) for (x in 0 until nx) {
            val i = y * nx + x
            val left = y * nx + max(x - 1, 0); val right = y * nx + min(x + 1, nx - 1)
            val top = max(y - 1, 0) * nx + x; val bottom = min(y + 1, ny - 1) * nx + x
            val sx = cellX * if (x == 0 || x == nx - 1) 1 else 2
            val sy = cellY * if (y == 0 || y == ny - 1) 1 else 2
            val a = (px[right] - px[left]) / sx
            val b = ((py[right] - py[left]) / sx + (px[bottom] - px[top]) / sy) * .5f
            val d = (py[bottom] - py[top]) / sy
            val halfDifference = (a - d) * .5f
            val lowest = (a + d) * .5f - sqrt(halfDifference * halfDifference + b * b)
            compression[i] = max(-lowest, 0f)
        }
        return gaussian(compression, nx, ny, 1).also { values ->
            for (i in values.indices) values[i] *= span
        }
    }

    /** 两个场共用矩形归一化方向，避免长弹窗的速度与释放范围错位。 */
    fun fieldRotation(directionDegrees: Float, width: Float, height: Float): Double {
        val angle = Math.toRadians(directionDegrees.toDouble())
        return atan2(sin(angle) / height, cos(angle) / width) - PI / 2
    }

    fun releaseField(
        nx: Int, ny: Int, directionDegrees: Float, rules: ParticleMicroflakeRules,
        width: Float = nx.toFloat(), height: Float = ny.toFloat(),
        variation: ParticleMicroflakeVariation = ParticleMicroflakeVariation.fromSeed(0L, rules),
        offsetTimes: FloatArray? = null,
        stage: ((String, Double) -> Unit)? = null
    ): FloatArray {
        val started = System.nanoTime()
        val geometry = ParticleFlowGeometry.from(width, height)
        val angle = geometry.rotation(directionDegrees)
        val c = cos(angle); val s = sin(angle)
        val gridWidth = rules.number("release_width").toInt()
        val gridHeight = rules.number("release_height").toInt()
        val low = rules.decimal("field_min"); val size = rules.decimal("field_size")
        val anchors = geometry.anchors(if (geometry.vertical) ny else nx, width, height, directionDegrees, variation.values[10])
        if (ParticleMaterialNative.enabled) {
            val wind = Math.toRadians(directionDegrees.toDouble())
            return ParticleMaterialNative.release(nx, ny, width, height, gridWidth, gridHeight,
                rules.release, low, size,
                doubleArrayOf(geometry.width, geometry.height, geometry.blend, if (geometry.vertical) 1.0 else 0.0, c, s),
                anchors, variation.values, variation.inverseSamples,
                ParticleReleaseTopology.nativePatches(width, height, variation.seed), cos(wind), -sin(wind),
                ParticleReleaseTopology.locality(width, height, directionDegrees, variation.seed), offsetTimes
            ).also { stage?.invoke("fieldMs", (System.nanoTime()-started)/1e6) }
        }
        val result = FloatArray(nx * ny)
        val originalTimes = DoubleArray(nx * ny)
        val detailTimes = DoubleArray(nx * ny)
        fun sample(rx: Double, ry: Double): Double {
            val u = ((variation.sampleX(rx, ry) - low) / size * gridWidth - .5).coerceIn(0.0, (gridWidth - 1).toDouble())
            val v = ((variation.sampleY(rx, ry) - low) / size * gridHeight - .5).coerceIn(0.0, (gridHeight - 1).toDouble())
            val ix = u.toInt(); val iy = v.toInt()
            val ax = u - ix; val ay = v - iy
            val nextX = min(ix + 1, gridWidth - 1); val nextY = min(iy + 1, gridHeight - 1)
            val a = rules.release[iy * gridWidth + ix].toDouble() * (1 - ax) + rules.release[iy * gridWidth + nextX] * ax
            val b = rules.release[nextY * gridWidth + ix].toDouble() * (1 - ax) + rules.release[nextY * gridWidth + nextX] * ax
            // 桌面 map_coordinates 对 float32 资源先产生 float32 插值结果。
            // 在随机时钟反解之前对齐精度，避免微小差异被后续释放梯度放大。
            return (a * (1 - ay) + b * ay).toFloat().toDouble()
        }
        for (y in 0 until ny) for (x in 0 until nx) {
            val ax = if (geometry.vertical) y * 5 else x * 5
            val qx = ((x + .5) / nx - .5) * width
            val qy = ((y + .5) / ny - .5) * height
            val xx = (qx - anchors[ax]) / geometry.width; val yy = (qy - anchors[ax + 1]) / geometry.height
            val rx = c * xx - s * yy; val ry = s * xx + c * yy
            var field = sample(rx, ry)
            var delay = variation.localDelay(rx + .5, ry + .5)
            if (geometry.blend > 0) {
                val xx1 = (qx - anchors[ax + 2]) / geometry.width; val yy1 = (qy - anchors[ax + 3]) / geometry.height
                val rx1 = c * xx1 - s * yy1; val ry1 = s * xx1 + c * yy1
                val weight = anchors[ax + 4]
                field = field * (1 - weight) + sample(rx1, ry1) * weight
                delay = delay * (1 - weight) + variation.localDelay(rx1 + .5, ry1 + .5) * weight
            }
            val original = variation.inverseTime(field)
            originalTimes[y * nx + x] = original
            detailTimes[y * nx + x] = original + delay
        }
        val released = ParticleReleaseTopology.release(nx, ny, width, height, directionDegrees, variation.seed, detailTimes)
        for (i in result.indices) {
            result[i] = released[i].toFloat()
            offsetTimes?.set(i, (released[i] - originalTimes[i]).toFloat())
        }
        stage?.invoke("fieldMs", (System.nanoTime() - started) / 1e6)
        return result
    }

    /** 与桌面逐位一致的整数随机函数；不使用场景名或图片散列作种子。 */
    internal fun randomValue(index: Int, seed: Int): Float {
        var value = index + seed
        value = (value xor (value ushr 16)) * 0x7feb352d
        value = (value xor (value ushr 15)) * 0x846ca68b.toInt()
        value = value xor (value ushr 16)
        return (value ushr 8) / 16777216f
    }

    private fun smooth(value: Float): Float {
        val t = value.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun gaussianWeights(sigma: Int): DoubleArray {
        val radius = sigma * 4
        val weights = DoubleArray(radius * 2 + 1) { exp(-.5 * ((it - radius).toDouble() / sigma).pow(2)) }
        val sum = weights.sum()
        for (i in weights.indices) weights[i] /= sum
        return weights
    }

    /** scipy.ndimage 的 reflect 边界和截断半径，两个方向分开卷积。 */
    private fun gaussian(input: FloatArray, width: Int, height: Int, sigma: Int): FloatArray {
        val radius = sigma * 4
        val weights = gaussianWeights(sigma)
        if (ParticleMaterialNative.enabled) return ParticleMaterialNative.gaussian(input, width, height, weights)
        fun reflect(index: Int, size: Int): Int {
            var i = index
            while (i < 0 || i >= size) i = if (i < 0) -i - 1 else 2 * size - i - 1
            return i
        }
        // 将 reflect 移出内层；按 SciPy 相同的远到近次序累加对称权重。
        val xIndex = Array(width) { x -> IntArray(radius * 2 + 1) { reflect(x + it - radius, width) } }
        val yIndex = Array(height) { y -> IntArray(radius * 2 + 1) { reflect(y + it - radius, height) * width } }
        val vertical = FloatArray(input.size)
        val output = FloatArray(input.size)
        for (y in 0 until height) for (x in 0 until width) {
            val indices = yIndex[y]
            var value = input[y * width + x] * weights[radius]
            for (k in radius downTo 1) value += (input[indices[radius - k] + x].toDouble() + input[indices[radius + k] + x]) * weights[radius + k]
            vertical[y * width + x] = value.toFloat()
        }
        for (y in 0 until height) for (x in 0 until width) {
            val indices = xIndex[x]
            val row = y * width
            var value = vertical[row + x] * weights[radius]
            for (k in radius downTo 1) value += (vertical[row + indices[radius - k]].toDouble() + vertical[row + indices[radius + k]]) * weights[radius + k]
            output[y * width + x] = value.toFloat()
        }
        return output
    }
}
