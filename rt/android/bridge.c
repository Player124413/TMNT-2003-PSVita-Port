/* All UI calls are lightweight; game loading and rendering stay on SDL's native thread. */
#include <jni.h>
#include <stdlib.h>
#include "touch.h"
#define JNI_NAME(name) Java_org_tmnt_port_NativeBridge_##name
JNIEXPORT void JNICALL JNI_NAME(touchButtons)(JNIEnv *env, jclass cls, jint mask)
{ (void)env; (void)cls; android_touch_buttons((uint32_t)mask); }
JNIEXPORT void JNICALL JNI_NAME(touchStick)(JNIEnv *env, jclass cls, jfloat x, jfloat y)
{
    (void)env; (void)cls;
    if (!(x >= -1.f && x <= 1.f && y >= -1.f && y <= 1.f)) { x = y = 0.f; }
    android_touch_stick((int32_t)(x * 1000.f), (int32_t)(y * 1000.f));
}
JNIEXPORT void JNICALL JNI_NAME(clearInput)(JNIEnv *env, jclass cls)
{ (void)env; (void)cls; android_touch_clear(); }
JNIEXPORT void JNICALL JNI_NAME(setPaused)(JNIEnv *env, jclass cls, jboolean paused)
{ (void)env; (void)cls; android_set_paused(paused); }
JNIEXPORT void JNICALL JNI_NAME(setPerf)(JNIEnv *env, jclass cls, jboolean enabled)
{ (void)env; (void)cls; android_set_perf(enabled); }
extern uint32_t android_stats_bits(int which);
static jfloat bits_float(uint32_t u) { float f; __builtin_memcpy(&f, &u, 4); return f; }
JNIEXPORT jfloat JNICALL JNI_NAME(fps)(JNIEnv *env, jclass cls)
{ (void)env; (void)cls; return bits_float(android_stats_bits(0)); }
JNIEXPORT jfloat JNICALL JNI_NAME(frameMs)(JNIEnv *env, jclass cls)
{ (void)env; (void)cls; return bits_float(android_stats_bits(1)); }

JNIEXPORT void JNICALL JNI_NAME(requestQuit)(JNIEnv *env, jclass cls) { (void)env; (void)cls; android_request_quit(); }
