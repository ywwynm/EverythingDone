package com.ywwynm.everythingdone.taptransfer.transport.wifi

/** 仅匹配本次实测的兼容环境特征；不把所有华为 Android 都当作卓易通。 */
internal object TapGroupRolePolicy {
    fun isKnownCompatibilityEnvironment(manufacturer: String, display: String): Boolean =
        manufacturer.equals("HUAWEI", ignoreCase = true) && display.startsWith("System ")

    fun preferReceiverGroup(debug: Boolean, manufacturer: String, display: String, override: Boolean?): Boolean =
        debug && (override ?: isKnownCompatibilityEnvironment(manufacturer, display))
}
