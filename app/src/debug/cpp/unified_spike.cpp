#include <jni.h>
#include <android/native_window_jni.h>
#include <cstdint>
#include <cerrno>
#include <dlfcn.h>

// Private debug-only API: absence in a legacy compositor fails the spike.
#define JNI_FN(name) Java_dev_omamobile_host_UnifiedOutputSpikeActivity_##name
extern "C" JNIEXPORT jint JNICALL JNI_FN(output)(JNIEnv* env, jclass, jint id, jobject surface, jint width, jint height) {
    using Output = int (*)(int, ANativeWindow*, int, int);
    auto call = reinterpret_cast<Output>(dlsym(RTLD_DEFAULT, "anhyprland_output"));
    if (!call) return -ENOSYS;
    auto window = surface ? ANativeWindow_fromSurface(env, surface) : nullptr;
    int result = call(id, window, width, height);
    if (window) ANativeWindow_release(window);
    return result;
}
extern "C" JNIEXPORT jint JNICALL JNI_FN(pointerOutput)(JNIEnv*, jclass, jint id, jfloat x, jfloat y, jint button, jboolean pressed) {
    using Pointer = int (*)(int, float, float, uint32_t, int);
    auto call = reinterpret_cast<Pointer>(dlsym(RTLD_DEFAULT, "anhyprland_pointer_output"));
    return call ? call(id, x, y, button, pressed) : -ENOSYS;
}
