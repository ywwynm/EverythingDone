package com.ywwynm.everythingdone.views.recording

/** 录音 Dialog 可选择的稳定输入来源；[preferenceValue] 不随语言或枚举顺序变化。 */
enum class AudioInputMode(
    val preferenceValue: String,
    val requiresMicrophone: Boolean,
    val requiresSystemAudio: Boolean,
    val outputChannels: Int
) {
    MICROPHONE(
        preferenceValue = "microphone",
        requiresMicrophone = true,
        requiresSystemAudio = false,
        outputChannels = 1
    ),
    SYSTEM(
        preferenceValue = "system",
        requiresMicrophone = false,
        requiresSystemAudio = true,
        outputChannels = 2
    ),
    SYSTEM_AND_MICROPHONE(
        preferenceValue = "system_and_microphone",
        requiresMicrophone = true,
        requiresSystemAudio = true,
        outputChannels = 2
    );

    companion object {
        fun fromPreference(value: String?): AudioInputMode =
            entries.firstOrNull { it.preferenceValue == value } ?: MICROPHONE
    }
}
