#include <jni.h>
#include <android/native_window_jni.h>
#include <android/log.h>
#include <cstdlib>
#include <cstdio>
#include <string>
#include <cstdint>

extern "C" {
int anhyprland_run(ANativeWindow*, int, int, const char*, void (*)(void*), void*);
int anhyprland_window(ANativeWindow*, int, int);
int anhyprland_pointer(float, float, uint32_t, int);
int anhyprland_touch(int, int, float, float);
int anhyprland_axis(float, float);
int anhyprland_key(uint32_t, int);
int anhyprland_unicode(uint32_t);
int anhyprland_stop();
}

#define JNI_FN(name) Java_dev_omamobile_host_NativeCompositor_##name
static JavaVM* vm;
static jclass bridge;
static std::string string(JNIEnv* env, jstring value) {
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string copy(chars);
    env->ReleaseStringUTFChars(value, chars);
    return copy;
}
static void ready(void*) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return;
    jmethodID method = env->GetStaticMethodID(bridge, "onReady", "(Ljava/lang/String;Ljava/lang/String;)V");
    jstring display = env->NewStringUTF(getenv("WAYLAND_DISPLAY") ? getenv("WAYLAND_DISPLAY") : "wayland-0");
    jstring signature = env->NewStringUTF(getenv("HYPRLAND_INSTANCE_SIGNATURE") ? getenv("HYPRLAND_INSTANCE_SIGNATURE") : "");
    env->CallStaticVoidMethod(bridge, method, display, signature);
    env->DeleteLocalRef(display);
    env->DeleteLocalRef(signature);
}
extern "C" JNIEXPORT jint JNICALL JNI_FN(run)(JNIEnv* env, jclass cls, jobject surface, jint width, jint height,
        jstring jhome, jstring jruntime, jstring jconfig, jstring jrootfs, jstring jwrapper, jstring jlibraries, jstring jmagic, jstring jxkb) {
    env->GetJavaVM(&vm);
    bridge = static_cast<jclass>(env->NewGlobalRef(cls));
    auto home = string(env, jhome), runtime = string(env, jruntime), config = string(env, jconfig);
    auto rootfs = string(env, jrootfs), wrapper = string(env, jwrapper), libraries = string(env, jlibraries), magic = string(env, jmagic);
    freopen((home + "/hyprland-native.log").c_str(), "w", stderr);
    setvbuf(stderr, nullptr, _IONBF, 0);
    setenv("HOME", home.c_str(), 1);
    setenv("XDG_RUNTIME_DIR", runtime.c_str(), 1);
    setenv("XDG_CONFIG_HOME", home.c_str(), 1);
    setenv("XKB_CONFIG_ROOT", string(env, jxkb).c_str(), 1);
    setenv("XCURSOR_PATH", (rootfs + "/usr/share/icons").c_str(), 1);
    setenv("XCURSOR_THEME", "Adwaita", 1);
    setenv("XCURSOR_SIZE", "24", 1);
    setenv("LD_LIBRARY_PATH", libraries.c_str(), 1);
    setenv("MAGIC", magic.c_str(), 1);
    setenv("FONTCONFIG_FILE", (home + "/fonts.conf").c_str(), 1);
    setenv("TMPDIR", runtime.c_str(), 1);
    setenv("ARLINUX_EXEC_WRAPPER", wrapper.c_str(), 1);
    setenv("GIO_USE_VFS", "local", 1);
    auto window = ANativeWindow_fromSurface(env, surface);
    if (!window) return -1;
    int result = anhyprland_run(window, width, height, config.c_str(), ready, nullptr);
    ANativeWindow_release(window);
    env->DeleteGlobalRef(bridge);
    bridge = nullptr;
    return result;
}
extern "C" JNIEXPORT jint JNICALL JNI_FN(window)(JNIEnv* env, jclass, jobject surface, jint width, jint height) {
    auto window = surface ? ANativeWindow_fromSurface(env, surface) : nullptr;
    int result = anhyprland_window(window, width, height);
    if (window) ANativeWindow_release(window);
    return result;
}
extern "C" JNIEXPORT jint JNICALL JNI_FN(pointer)(JNIEnv*, jclass, jfloat x, jfloat y, jint button, jboolean pressed) { return anhyprland_pointer(x, y, button, pressed); }
extern "C" JNIEXPORT jint JNICALL JNI_FN(touch)(JNIEnv*, jclass, jint id, jint action, jfloat x, jfloat y) { return anhyprland_touch(id, action, x, y); }
extern "C" JNIEXPORT jint JNICALL JNI_FN(axis)(JNIEnv*, jclass, jfloat x, jfloat y) { return anhyprland_axis(x, y); }
extern "C" JNIEXPORT jint JNICALL JNI_FN(key)(JNIEnv*, jclass, jint code, jboolean pressed) { return anhyprland_key(code, pressed); }
extern "C" JNIEXPORT jint JNICALL JNI_FN(unicode)(JNIEnv*, jclass, jint code) { return anhyprland_unicode(code); }
extern "C" JNIEXPORT jint JNICALL JNI_FN(stop)(JNIEnv*, jclass) { return anhyprland_stop(); }
