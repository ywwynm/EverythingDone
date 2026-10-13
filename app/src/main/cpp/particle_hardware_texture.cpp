// 飘散零拷贝快照：把 HardwareBuffer 经 EGLImage 绑到当前的 GL_TEXTURE_2D 上。
//
// 新建页的截图本来就渲染在一块 GPU 可采样的 HardwareBuffer 里（ParticleHardwareSnapshot 的 ImageReader）。
// Java 侧没有 eglGetNativeClientBufferANDROID，也没有好用的 glEGLImageTargetTexture2DOES 绑定，所以走 JNI。
// 四个入口都用 eglGetProcAddress 动态取，取不到就整体报不可用，Kotlin 侧退回「拷回内存再上传」的老路。

#include <jni.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>

namespace {

using GetClientBuffer = EGLClientBuffer (*)(const AHardwareBuffer *);
using CreateImage = EGLImageKHR (*)(EGLDisplay, EGLContext, EGLenum, EGLClientBuffer, const EGLint *);
using DestroyImage = EGLBoolean (*)(EGLDisplay, EGLImageKHR);
using TargetTexture = void (*)(unsigned int, void *);

constexpr unsigned int kTexture2D = 0x0DE1;

struct Entries {
    GetClientBuffer clientBuffer = nullptr;
    CreateImage createImage = nullptr;
    DestroyImage destroyImage = nullptr;
    TargetTexture targetTexture = nullptr;
    bool resolved = false;
    bool ok = false;
};

Entries &entries() {
    static Entries e;
    if (!e.resolved) {
        e.resolved = true;
        e.clientBuffer = reinterpret_cast<GetClientBuffer>(
                eglGetProcAddress("eglGetNativeClientBufferANDROID"));
        e.createImage = reinterpret_cast<CreateImage>(eglGetProcAddress("eglCreateImageKHR"));
        e.destroyImage = reinterpret_cast<DestroyImage>(eglGetProcAddress("eglDestroyImageKHR"));
        e.targetTexture = reinterpret_cast<TargetTexture>(
                eglGetProcAddress("glEGLImageTargetTexture2DOES"));
        e.ok = e.clientBuffer != nullptr && e.createImage != nullptr && e.destroyImage != nullptr &&
               e.targetTexture != nullptr;
    }
    return e;
}

inline EGLDisplay display(jlong handle) { return reinterpret_cast<EGLDisplay>(handle); }

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_ywwynm_everythingdone_views_particledismiss_ParticleHardwareTexture_nAvailable(JNIEnv *, jclass) {
    return entries().ok ? JNI_TRUE : JNI_FALSE;
}

/**
 * 为 [buffer] 建 EGLImage，并绑到当前上下文里当前绑定的 GL_TEXTURE_2D 上（调用方先 glBindTexture）。
 * 返回 EGLImage 句柄，失败返回 0。EGLImage 自己持有缓冲的引用，Java 侧的 HardwareBuffer 之后可以关掉。
 */
JNIEXPORT jlong JNICALL
Java_com_ywwynm_everythingdone_views_particledismiss_ParticleHardwareTexture_nBind(
        JNIEnv *env, jclass, jlong dpy, jobject hardwareBuffer) {
    Entries &e = entries();
    if (!e.ok || hardwareBuffer == nullptr) return 0;
    AHardwareBuffer *buffer = AHardwareBuffer_fromHardwareBuffer(env, hardwareBuffer);
    if (buffer == nullptr) return 0;
    EGLClientBuffer client = e.clientBuffer(buffer);
    if (client == nullptr) return 0;
    const EGLint attributes[] = {EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE};
    EGLImageKHR image = e.createImage(display(dpy), EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, client,
                                      attributes);
    if (image == EGL_NO_IMAGE_KHR) return 0;
    e.targetTexture(kTexture2D, image);
    return reinterpret_cast<jlong>(image);
}

JNIEXPORT void JNICALL
Java_com_ywwynm_everythingdone_views_particledismiss_ParticleHardwareTexture_nRebind(
        JNIEnv *, jclass, jlong image) {
    Entries &e = entries();
    if (e.ok && image != 0) e.targetTexture(kTexture2D, reinterpret_cast<void *>(image));
}

JNIEXPORT void JNICALL
Java_com_ywwynm_everythingdone_views_particledismiss_ParticleHardwareTexture_nDestroy(
        JNIEnv *, jclass, jlong dpy, jlong image) {
    Entries &e = entries();
    if (!e.ok || image == 0) return;
    e.destroyImage(display(dpy), reinterpret_cast<EGLImageKHR>(image));
}

}  // extern "C"
