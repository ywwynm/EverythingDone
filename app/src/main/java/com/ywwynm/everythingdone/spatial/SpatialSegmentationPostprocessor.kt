package com.ywwynm.everythingdone.spatial

import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sqrt

data class SpatialSegmentationInstance(
    val label: Int,
    val classId: Int,
    val confidence: Float,
    val pixelCount: Int,
    /** RF-DETR 检测框，归一化到输入图；prompt refiner 可直接复用，旧调用可为空。 */
    val box: SpatialNormalizedBox? = null
)

data class SpatialNormalizedBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    init {
        require(left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite())
        require(left in 0f..1f && top in 0f..1f && right in 0f..1f && bottom in 0f..1f)
        require(right > left && bottom > top)
    }
}

/**
 * 互斥实例标签图。0 表示不接管该像素，1..254 表示独立对象。alpha 是同分辨率的软覆盖率，
 * 只在 labels 非零处有效。
 */
data class SpatialSegmentationData(
    val width: Int,
    val height: Int,
    val labels: ByteArray,
    val alpha: ByteArray,
    val instances: List<SpatialSegmentationInstance>
) {
    init {
        require(width > 1 && height > 1)
        require(labels.size == width * height)
        require(alpha.size == labels.size)
        require(instances.size <= SpatialSegmentationPostprocessor.MAX_OBJECTS)
    }

    val personLabels: Set<Int>
        get() = instances.asSequence()
            .filter { it.classId == SpatialSegmentationPostprocessor.PERSON_CLASS_ID }
            .map { it.label }
            .toSet()
}

internal object SpatialSegmentationPostprocessor {

    fun process(
        logits: FloatArray,
        masks: FloatArray,
        model: SpatialSegmentationModel,
        boxes: FloatArray? = null
    ): SpatialSegmentationData {
        require(logits.size == model.queryCount * model.classLogitCount)
        require(masks.size == model.queryCount * model.maskSize * model.maskSize)
        require(boxes == null || boxes.size == model.queryCount * 4)
        require(model.firstForegroundClassId >= 0)
        require(model.lastForegroundClassId < model.classLogitCount)
        val maskPlane = model.maskSize * model.maskSize
        val candidates = ArrayList<Candidate>()
        for (query in 0 until model.queryCount) {
            var bestClass = model.firstForegroundClassId
            var bestLogit = Float.NEGATIVE_INFINITY
            val logitOffset = query * model.classLogitCount
            // RF-DETR COCO checkpoint 使用稀疏 category ID 作为 logit slot：0 是保留空槽，
            // 1..90 都是前景类。它采用 sigmoid/focal 分类，no-object 由所有类低置信隐式
            // 表达，不存在可删除的“最后一槽”。
            for (classId in model.firstForegroundClassId..model.lastForegroundClassId) {
                val value = logits[logitOffset + classId]
                require(value.isFinite()) { "实例分割分类输出包含 NaN/Infinity" }
                if (value > bestLogit) {
                    bestLogit = value
                    bestClass = classId
                }
            }
            val confidence = sigmoid(bestLogit)
            if (confidence <= model.confidenceThreshold) continue

            var positive = 0
            val maskOffset = query * maskPlane
            for (index in 0 until maskPlane) {
                val value = masks[maskOffset + index]
                require(value.isFinite()) { "实例分割 mask 输出包含 NaN/Infinity" }
                if (value > 0f) positive++
            }
            val coverage = positive.toFloat() / maskPlane
            if (!eligible(bestClass, coverage, positive)) continue
            val semanticWeight = when (bestClass) {
                in PRIMARY_CLASSES -> 1.35f
                in SUPPORT_CLASSES -> 0.72f
                else -> 1f
            }
            val priority = confidence * (0.35f + sqrt(coverage.coerceAtMost(0.35f))) *
                semanticWeight
            candidates += Candidate(query, bestClass, confidence, coverage, priority)
        }

        val selected = candidates
            .sortedByDescending { it.priority }
            .take(MAX_OBJECTS)
        if (selected.isEmpty()) return empty(model.inputSize)

        val outputSize = model.inputSize
        val labels = ByteArray(outputSize * outputSize)
        val alpha = ByteArray(labels.size)
        val winningStrength = FloatArray(labels.size)
        for ((candidateIndex, candidate) in selected.withIndex()) {
            val proposedLabel = candidateIndex + 1
            val maskOffset = candidate.query * maskPlane
            for (y in 0 until outputSize) {
                val sourceY = (y + 0.5f) * model.maskSize / outputSize - 0.5f
                for (x in 0 until outputSize) {
                    val sourceX = (x + 0.5f) * model.maskSize / outputSize - 0.5f
                    val maskLogit = bilinear(
                        masks,
                        maskOffset,
                        model.maskSize,
                        sourceX,
                        sourceY
                    )
                    if (maskLogit <= 0f) continue
                    val probability = sigmoid(maskLogit)
                    val strength = probability * candidate.confidence
                    val index = y * outputSize + x
                    if (strength <= winningStrength[index]) continue
                    winningStrength[index] = strength
                    labels[index] = proposedLabel.toByte()
                    alpha[index] = (probability * 255f + 0.5f)
                        .toInt().coerceIn(0, 255).toByte()
                }
            }
        }

        // 遮挡竞争后可能有候选完全消失；压紧 label，避免落盘无效层。
        val counts = IntArray(selected.size + 1)
        for (value in labels) counts[value.toInt() and 0xff]++
        // 小实例的轮廓误差/面积比很高；强行切成刚性平面时，数个 mask 像素的
        // 偏差会在大视差下放大成整圈锯齿与补图块。它们仍由连续深度表面产生
        // 空间位移，只是不再承担独立的遮挡关系。
        val minimumOutputPixels = maxOf(
            32,
            (labels.size * MIN_RIGID_LAYER_OUTPUT_RATIO).toInt()
        )
        val remap = IntArray(selected.size + 1)
        val instances = ArrayList<SpatialSegmentationInstance>()
        for ((candidateIndex, candidate) in selected.withIndex()) {
            val oldLabel = candidateIndex + 1
            if (counts[oldLabel] < minimumOutputPixels) continue
            val newLabel = instances.size + 1
            remap[oldLabel] = newLabel
            instances += SpatialSegmentationInstance(
                label = newLabel,
                classId = candidate.classId,
                confidence = candidate.confidence,
                pixelCount = counts[oldLabel],
                box = boxes?.let { normalizedBox(it, candidate.query) }
            )
        }
        for (index in labels.indices) {
            val mapped = remap[labels[index].toInt() and 0xff]
            labels[index] = mapped.toByte()
            if (mapped == 0) alpha[index] = 0
        }
        return SpatialSegmentationData(outputSize, outputSize, labels, alpha, instances)
    }

    private fun eligible(classId: Int, coverage: Float, positivePixels: Int): Boolean {
        if (positivePixels < MIN_MASK_PIXELS) return false
        // 单张 RGB 无法恢复透明透射与镜面反射随视点的变化。把餐具类强行切成不透明
        // 刚性平面会产生白边、重复背景和漂浮感；它们保留在连续深度表面更稳定。
        if (classId in VIEW_DEPENDENT_TABLEWARE_CLASSES) return false
        if (classId in SUPPORT_CLASSES && coverage > MAX_SUPPORT_COVERAGE) return false
        if (coverage > MAX_GENERIC_COVERAGE && classId !in PRIMARY_CLASSES) return false
        return true
    }

    private fun bilinear(
        values: FloatArray,
        offset: Int,
        width: Int,
        x: Float,
        y: Float
    ): Float {
        val x0 = floor(x).toInt().coerceIn(0, width - 1)
        val y0 = floor(y).toInt().coerceIn(0, width - 1)
        val x1 = (x0 + 1).coerceAtMost(width - 1)
        val y1 = (y0 + 1).coerceAtMost(width - 1)
        val fractionX = (x - x0).coerceIn(0f, 1f)
        val fractionY = (y - y0).coerceIn(0f, 1f)
        val top = lerp(values[offset + y0 * width + x0], values[offset + y0 * width + x1], fractionX)
        val bottom = lerp(values[offset + y1 * width + x0], values[offset + y1 * width + x1], fractionX)
        return lerp(top, bottom, fractionY)
    }

    private fun lerp(first: Float, second: Float, fraction: Float): Float =
        first + (second - first) * fraction

    private fun sigmoid(value: Float): Float =
        (1.0 / (1.0 + exp(-value.coerceIn(-80f, 80f).toDouble()))).toFloat()

    private fun normalizedBox(values: FloatArray, query: Int): SpatialNormalizedBox? {
        val offset = query * 4
        val centerX = values[offset]
        val centerY = values[offset + 1]
        val width = values[offset + 2]
        val height = values[offset + 3]
        if (!centerX.isFinite() || !centerY.isFinite() ||
            !width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f
        ) {
            return null
        }
        val left = (centerX - width * 0.5f).coerceIn(0f, 1f)
        val top = (centerY - height * 0.5f).coerceIn(0f, 1f)
        val right = (centerX + width * 0.5f).coerceIn(0f, 1f)
        val bottom = (centerY + height * 0.5f).coerceIn(0f, 1f)
        if (right - left < MIN_BOX_SIDE || bottom - top < MIN_BOX_SIDE) return null
        return SpatialNormalizedBox(left, top, right, bottom)
    }

    private fun empty(size: Int) = SpatialSegmentationData(
        width = size,
        height = size,
        labels = ByteArray(size * size),
        alpha = ByteArray(size * size),
        instances = emptyList()
    )

    private data class Candidate(
        val query: Int,
        val classId: Int,
        val confidence: Float,
        val coverage: Float,
        val priority: Float
    )

    const val PERSON_CLASS_ID = 1
    const val MAX_OBJECTS = 12
    private const val MIN_MASK_PIXELS = 4
    private const val MIN_BOX_SIDE = 1e-4f
    private const val MAX_SUPPORT_COVERAGE = 0.18f
    private const val MAX_GENERIC_COVERAGE = 0.52f
    private const val MIN_RIGID_LAYER_OUTPUT_RATIO = 0.012f
    private val SUPPORT_CLASSES = setOf(63, 65, 67)
    private val PRIMARY_CLASSES = setOf(
        1, // person
        2, 3, 4, 5, 6, 7, 8, 9, // vehicles
        16, 17, 18, 19, 20, 21, 22, 23, 24, 25 // animals
    )
    private val VIEW_DEPENDENT_TABLEWARE_CLASSES = setOf(
        44, // bottle
        46, // wine glass
        47, // cup
        48, 49, 50, // fork, knife, spoon
        51 // bowl
    )
}
