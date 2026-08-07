package com.ywwynm.everythingdone.spatial

data class SpatialBoundaryRefinementComponent(
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String
)

/** Promptable segmentation 只负责收紧既有实例的轮廓，不发现或重定义实例身份。 */
enum class SpatialBoundaryRefinementModel(
    val stableId: String,
    val displayName: String,
    val version: String,
    val archiveFileName: String,
    val archiveSizeBytes: Long,
    val archiveSha256: String,
    val inputSize: Int,
    val maskSize: Int,
    val minimumTotalRamMb: Int,
    val minimumAvailableRamMb: Int,
    val licenseId: String,
    val components: List<SpatialBoundaryRefinementComponent>
) {
    EDGETAM(
        stableId = "edgetam_boundary_refiner",
        displayName = "EdgeTAM",
        version = "1.0.0",
        archiveFileName = "edgetam_boundary_refiner_1.0.0.zip",
        archiveSizeBytes = 33_502_118L,
        archiveSha256 = "289cea7ff30df047f432ef9fd2c99a4554a3057a2ed28df03ff73f0aeeeef09d",
        inputSize = 1024,
        maskSize = 256,
        minimumTotalRamMb = 8192,
        minimumAvailableRamMb = 2048,
        licenseId = "Apache-2.0",
        components = listOf(
            SpatialBoundaryRefinementComponent(
                "edgetam_image_encoder_1024.onnx",
                19_755_129L,
                "b7b39202e8ff7330d89da5a19bde09936b338858d2c4729472b71dd56e6021fe"
            ),
            SpatialBoundaryRefinementComponent(
                "edgetam_box_prompt_encoder.onnx",
                52_939L,
                "0bdf0bf63bb3e142fb4180bf4884f9cbde59b8bc79202774febe9864063638a0"
            ),
            SpatialBoundaryRefinementComponent(
                "edgetam_mask_decoder.onnx",
                16_384_875L,
                "b2b85965a9e30392d671957cf5bab73acb75f2eecf25a76d2780d8786f5b8208"
            )
        )
    );

    val unpackedSizeBytes: Long
        get() = components.sumOf(SpatialBoundaryRefinementComponent::sizeBytes)

    companion object {
        fun fromStableId(id: String?): SpatialBoundaryRefinementModel? =
            entries.firstOrNull { it.stableId == id }
    }
}
