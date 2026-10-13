// EGL_ANDROID_get_frame_timestamps 的 JNI 绑定。
//
// Java 侧的 android.opengl.EGLExt 没有暴露这三个入口，而「粒子首帧真的上屏了没有」
// 只有 EGL_DISPLAY_PRESENT_TIME_ANDROID 能直答（撤真实弹窗、撤快照、启动暗层都挂在这一刻）。
// 三个函数都用 eglGetProcAddress 动态取，取不到就整体报不可用，Kotlin 侧退回保守判定。

#include <jni.h>
#include <EGL/egl.h>
#include <cstdint>

namespace {

using EGLnsecsANDROID = khronos_stime_nanoseconds_t;

constexpr EGLint kTimestampsAndroid = 0x3430;
constexpr EGLint kDisplayPresentTimeAndroid = 0x343A;
constexpr EGLint kRenderingCompleteTimeAndroid = 0x3435;

using PFNEGLGETNEXTFRAMEIDANDROID =
        EGLBoolean (*)(EGLDisplay, EGLSurface, khronos_uint64_t *);
using PFNEGLGETFRAMETIMESTAMPSUPPORTEDANDROID =
        EGLBoolean (*)(EGLDisplay, EGLSurface, EGLint);
using PFNEGLGETFRAMETIMESTAMPSANDROID =
        EGLBoolean (*)(EGLDisplay, EGLSurface, khronos_uint64_t, EGLint, const EGLint *,
                       EGLnsecsANDROID *);

struct Entries {
    PFNEGLGETNEXTFRAMEIDANDROID nextFrameId = nullptr;
    PFNEGLGETFRAMETIMESTAMPSUPPORTEDANDROID supported = nullptr;
    PFNEGLGETFRAMETIMESTAMPSANDROID timestamps = nullptr;
    bool resolved = false;
    bool ok = false;
};

Entries &entries() {
    static Entries e;
    if (!e.resolved) {
        e.resolved = true;
        e.nextFrameId = reinterpret_cast<PFNEGLGETNEXTFRAMEIDANDROID>(
                eglGetProcAddress("eglGetNextFrameIdANDROID"));
        e.supported = reinterpret_cast<PFNEGLGETFRAMETIMESTAMPSUPPORTEDANDROID>(
                eglGetProcAddress("eglGetFrameTimestampSupportedANDROID"));
        e.timestamps = reinterpret_cast<PFNEGLGETFRAMETIMESTAMPSANDROID>(
                eglGetProcAddress("eglGetFrameTimestampsANDROID"));
        e.ok = e.nextFrameId != nullptr && e.supported != nullptr && e.timestamps != nullptr;
    }
    return e;
}

inline EGLDisplay display(jlong handle) { return reinterpret_cast<EGLDisplay>(handle); }

inline EGLSurface surface(jlong handle) { return reinterpret_cast<EGLSurface>(handle); }

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_ywwynm_everythingdone_views_particledismiss_ParticleFrameTimestamps_nEnable(
        JNIEnv *, jclass, jlong dpy, jlong surf) {
    Entries &e = entries();
    if (!e.ok) return JNI_FALSE;
    // 采集默认关闭，必须显式打开；打开之后入队的帧才会被记录。
    if (eglSurfaceAttrib(display(dpy), surface(surf), kTimestampsAndroid, EGL_TRUE) != EGL_TRUE) {
        return JNI_FALSE;
    }
    // 驱动可以逐项拒绝；只认 DISPLAY_PRESENT_TIME，其余项本项目用不上。
    return e.supported(display(dpy), surface(surf), kDisplayPresentTimeAndroid) == EGL_TRUE
           ? JNI_TRUE : JNI_FALSE;
}

/** 下一次 eglSwapBuffers 将要产生的帧号；失败返回 -1。 */
JNIEXPORT jlong JNICALL
Java_com_ywwynm_everythingdone_views_particledismiss_ParticleFrameTimestamps_nNextFrameId(
        JNIEnv *, jclass, jlong dpy, jlong surf) {
    Entries &e = entries();
    if (!e.ok) return -1;
    khronos_uint64_t id = 0;
    if (e.nextFrameId(display(dpy), surface(surf), &id) != EGL_TRUE) return -1;
    return static_cast<jlong>(id);
}

/**
 * 该帧的实际呈现时刻（CLOCK_MONOTONIC 纳秒）。
 * 尚未确定返回 -2（PENDING），该帧不会再有这一项返回 -1（INVALID），调用失败返回 -3。
 */
JNIEXPORT jlong JNICALL
Java_com_ywwynm_everythingdone_views_particledismiss_ParticleFrameTimestamps_nPresentTime(
        JNIEnv *, jclass, jlong dpy, jlong surf, jlong frameId) {
    Entries &e = entries();
    if (!e.ok) return -3;
    const EGLint names[1] = {kDisplayPresentTimeAndroid};
    EGLnsecsANDROID values[1] = {0};
    if (e.timestamps(display(dpy), surface(surf), static_cast<khronos_uint64_t>(frameId), 1, names,
                     values) != EGL_TRUE) {
        return -3;
    }
    return static_cast<jlong>(values[0]);
}

JNIEXPORT jlong JNICALL
Java_com_ywwynm_everythingdone_views_particledismiss_ParticleFrameTimestamps_nRenderingCompleteTime(
        JNIEnv *, jclass, jlong dpy, jlong surf, jlong frameId) {
    Entries &e = entries();
    if (!e.ok || e.supported(display(dpy), surface(surf), kRenderingCompleteTimeAndroid) != EGL_TRUE) return -3;
    const EGLint name = kRenderingCompleteTimeAndroid;
    EGLnsecsANDROID value = 0;
    if (e.timestamps(display(dpy), surface(surf), static_cast<khronos_uint64_t>(frameId), 1, &name, &value) != EGL_TRUE) return -3;
    return static_cast<jlong>(value);
}

}  // extern "C"
