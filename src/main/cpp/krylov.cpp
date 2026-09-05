#include <jni.h>

extern "C" JNIEXPORT jstring JNICALL
Java_dev_psyclyx_krylov_MainActivity_nativeGreeting(JNIEnv *env, jobject) {
    return env->NewStringUTF("Hello from the Android NDK.");
}
