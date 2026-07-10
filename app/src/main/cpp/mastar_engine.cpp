#include <jni.h>

#include <memory>
#include <string>

#include "transition_renderer.h"

namespace mastar {
bool audioEngineStart();
void audioEngineStop();
} // namespace mastar

namespace {

std::string toStdString(JNIEnv *env, jstring jstr) {
    const char *chars = env->GetStringUTFChars(jstr, nullptr);
    std::string result(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(jstr, chars);
    return result;
}

} // namespace

// JNI surface for com.mastar.editor.engine.gl.NativeBridge.
// All transition calls must happen on the thread owning the EGL context.
extern "C" {

JNIEXPORT jlong JNICALL
Java_com_mastar_editor_engine_gl_NativeBridge_nativeCreateTransition(
        JNIEnv *env, jobject /*thiz*/, jstring transitionGlsl) {
    auto renderer = std::make_unique<mastar::TransitionRenderer>();
    if (!renderer->compile(toStdString(env, transitionGlsl))) {
        renderer->destroy();
        return 0;
    }
    return reinterpret_cast<jlong>(renderer.release());
}

JNIEXPORT void JNICALL
Java_com_mastar_editor_engine_gl_NativeBridge_nativeDrawTransition(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle, jfloat progress,
        jint fromTexture, jint toTexture, jint width, jint height) {
    if (handle == 0) return;
    auto *renderer = reinterpret_cast<mastar::TransitionRenderer *>(handle);
    renderer->draw(progress, static_cast<GLuint>(fromTexture),
                   static_cast<GLuint>(toTexture), width, height);
}

JNIEXPORT void JNICALL
Java_com_mastar_editor_engine_gl_NativeBridge_nativeDestroyTransition(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    if (handle == 0) return;
    auto *renderer = reinterpret_cast<mastar::TransitionRenderer *>(handle);
    renderer->destroy();
    delete renderer;
}

JNIEXPORT jboolean JNICALL
Java_com_mastar_editor_engine_gl_NativeBridge_nativeStartAudioEngine(
        JNIEnv * /*env*/, jobject /*thiz*/) {
    return mastar::audioEngineStart() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_mastar_editor_engine_gl_NativeBridge_nativeStopAudioEngine(
        JNIEnv * /*env*/, jobject /*thiz*/) {
    mastar::audioEngineStop();
}

} // extern "C"
