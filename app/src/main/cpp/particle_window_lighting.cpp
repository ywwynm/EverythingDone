#include <jni.h>
#include <cstring>
#include <sys/system_properties.h>

// 与 HWUI Properties::peekRenderPipelineType 读取同一组属性，不修改系统配置。
extern "C" JNIEXPORT jboolean JNICALL
Java_com_ywwynm_everythingdone_views_particledismiss_ParticleWindowLighting_nativeUsesVulkan(
        JNIEnv*, jobject) {
    char value[PROP_VALUE_MAX] = {};
    if (__system_property_get("debug.hwui.renderer", value) > 0) {
        return std::strcmp(value, "skiavk") == 0;
    }
    __system_property_get("ro.hwui.use_vulkan", value);
    return std::strcmp(value, "true") == 0 || std::strcmp(value, "1") == 0;
}
