#!/usr/bin/env python3
"""Stage Newscape's pinned 117 JNI facade against the iOS GLES 3.0 context."""

import argparse
import hashlib
from pathlib import Path
import re
import shutil


SOURCE_SHA = "81d24e231ac48c8a7689bfea099fee7f2c509db2b53b8cb80a760e9eb7308e61"
READBACK_SHA = "d0864fa32956d0ebe0a504abed1ac0159a3340cc39cacf8f10ee347da0d3bea7"
NON_GLES_30 = (
    "rc_glClipControl", "rc_glTexBuffer", "rc_glFramebufferTexture", "rc_glBindImageTexture",
    "rc_glDispatchCompute", "rc_glMemoryBarrier", "rc_glDrawArraysIndirect", "rc_glDrawElementsIndirect",
)


def literal(text, old, new):
    if text.count(old) != 1:
        raise ValueError(f"The pinned native source no longer has exactly one {old[:75]!r}")
    return text.replace(old, new)


def section(text, first, last, new=""):
    start = text.index(first)
    stop = text.index(last, start + len(first))
    return text[:start] + new + text[stop:]


def api_method(text, name, replacement):
    # The pinned native methods use balanced scopes with the JNI signature on one line.
    pattern = re.compile(r"(?m)^(?:GLF |static .*? JNICALL )" + re.escape(name) + r"\([^\n]*?\)\s*\{")
    match = pattern.search(text)
    if not match:
        raise ValueError(f"Missing native function: {name}")
    level = 1
    end = match.end()
    while end < len(text) and level:
        level += (text[end] == "{") - (text[end] == "}")
        end += 1
    if level:
        raise ValueError(f"Missing closing scope: {name}")
    return text[:match.end()] + replacement + "}" + text[end:]


def stage(snapshot, output):
    files = {"newscape117.cpp": SOURCE_SHA, "readback_queue.h": READBACK_SHA}
    for filename, expected in files.items():
        if hashlib.sha256((snapshot / filename).read_bytes()).hexdigest() != expected:
            raise ValueError(f"Native baseline changed: {filename}")
    if output.exists():
        raise ValueError("Choose a fresh facade output directory")

    text = (snapshot / "newscape117.cpp").read_text(encoding="utf-8")
    text = section(text, "#include <EGL/egl.h>", "#include <pthread.h>", """\
#ifdef __APPLE__
#include <OpenGLES/ES3/gl.h>
#else
#include <GLES3/gl3.h>
#endif
extern "C" bool newscape117OpenContext();
extern "C" bool newscape117Current();
extern "C" bool newscape117MakeCurrent();
extern "C" void newscape117CloseContext();
""")
    text = section(text, "#define LOG_TAG", "#ifndef GL_BGRA", """\
#define LOGI(...) do { fprintf(stderr, __VA_ARGS__); fputc('\\n', stderr); } while(false)
#define LOGE(...) LOGI(__VA_ARGS__)
#ifndef GL_MULTISAMPLE
#define GL_MULTISAMPLE 0x809D
#endif
#ifndef GL_READ_ONLY
#define GL_READ_ONLY 0x88B8
#endif
#ifndef GL_READ_WRITE
#define GL_READ_WRITE 0x88BA
#endif

""")
    text = section(text, "// EXT_clip_control", "static GLuint sFbo")
    text = section(text, "// GLSL ES target for", "// glReadPixels scratch", 'static constexpr char sEsVer[] = "300 es";\n')
    text = section(text, "static std::atomic<bool> sOwnerActive", "static double rc_now_ms()")
    text = section(text, "// ---- Phase 4 present probe", "static jclass sJbCls", """\
static bool sDrawCountOn = false;
static int sDcDrawArrays = 0, sDcDrawElements = 0, sDcMultiCalls = 0, sDcMultiRanges = 0;

""")
    text = literal(text, "static jmethodID sJbDirectPublish=nullptr;\n", "")
    text = literal(text, '    sJbDirectPublish=env->GetStaticMethodID(sJbCls,"gpuDirectPublish","(II)V");\n', "")
    text = text.replace("sJbBackBuf && sJbPublish && sJbDirectPublish", "sJbBackBuf && sJbPublish && sJbCanvasW && sJbCanvasH")
    text = section(text, "// Re-bind the thread-affine", "// ---- GLSL 330", """\
static bool rc_make_current() { return newscape117MakeCurrent(); }
static void rc_frame_error(JNIEnv* env, const char* message);

""")
    text = section(text, "static bool rcEglPrepareSurface", "// Offscreen FBO")
    text = section(text, "// ---- Phase 1: reverse-Z", "static jobject rc_native_order")
    for name in NON_GLES_30:
        text = api_method(text, name, ' rc_frame_error(env,"Unsupported GLES 3.0 call in 117 HD: ' + name.removeprefix("rc_") + '"); ')
        text, count = re.subn(r"(" + name + r"\()JNIEnv\*,", r"\1JNIEnv* env,", text, count=1)
        if count != 1:
            raise ValueError(f"Native signature unexpectedly changed: {name}")
    text = section(text, "static void JNICALL rc_AWT_createGLContext", "static jint JNICALL rc_AWT_getBufferMode", """\
static void JNICALL rc_AWT_createGLContext(JNIEnv* env, jobject){
    if(!newscape117OpenContext()){
        rc_frame_error(env,"117 HD could not create an iOS OpenGL ES 3 context");
        return;
    }
    if(!rc_jb_cache(env)){
        rc_frame_error(env,"117 HD frame publisher binding is unavailable");
        newscape117CloseContext();
        return;
    }
    const int width=env->CallStaticIntMethod(sJbCls,sJbCanvasW);
    if(env->ExceptionCheck()){newscape117CloseContext();return;}
    const int height=env->CallStaticIntMethod(sJbCls,sJbCanvasH);
    if(env->ExceptionCheck()){newscape117CloseContext();return;}
    GLint maxSize=0;
    glGetIntegerv(GL_MAX_RENDERBUFFER_SIZE,&maxSize);
    if(width<1||height<1||width>maxSize||height>maxSize||!rcBuildFbo(width,height)){
        rc_frame_error(env,"117 HD could not create its iOS framebuffer");
    }
}
static void JNICALL rc_AWT_destroy(JNIEnv* env, jobject){
    if(!rc_make_current()){
        if(sFbo)rc_frame_error(env,"117 HD context teardown called off the render thread");
        return;
    }
    sReadbacks.reset();
    sLastPublishedMs=0;
    if(sFbo)glDeleteFramebuffers(1,&sFbo);
    if(sColorRbo)glDeleteRenderbuffers(1,&sColorRbo);
    if(sDepthRbo)glDeleteRenderbuffers(1,&sDepthRbo);
    sFbo=sColorRbo=sDepthRbo=0;
    sFboW=sFboH=0;
    std::free(sReadback);
    sReadback=nullptr;
    sReadbackN=0;
    newscape117CloseContext();
}
""")
    text = section(text, "// Per-frame (the gpu plugin", "static jint JNICALL rc_AWT_getFramebuffer")
    text = section(text, "// Store the plugin's requested swap interval", "static void rc_frame_error(JNIEnv* env, const char* message) {", """\
static jint JNICALL rc_AWT_setSwapInterval(JNIEnv*, jobject, jint) {
    // Offscreen frame delivery is clocked by the client and native view.
    return 0;
}

""")
    text = literal(text, """\
    if((w!=sFboW||h!=sFboH||!sFbo)&&!rcResizeFbo(w,h)){
        jclass e=env->FindClass("java/lang/IllegalStateException");env->ThrowNew(e,"117 HD framebuffer resize is incomplete");return 0;
    }
""", """\
    if(w!=sFboW||h!=sFboH||!sFbo){
        GLint maximum=0;
        glGetIntegerv(GL_MAX_RENDERBUFFER_SIZE,&maximum);
        if(w>maximum||h>maximum||!rcResizeFbo(w,h)){
            rc_frame_error(env,"117 HD framebuffer resize is incomplete");
            return 0;
        }
    }
""")
    text = literal(text, "    if(sDirectEnabled.load())return JNI_TRUE;\n", "")
    text = section(text, "    if(sDirectEnabled.load()){", "    sReadbacks.resize(w,h);")
    signatures = {
        "static jint JNICALL rc_AWT_getFramebuffer": "return 0;",
        "Java_rs117_hd_android_AndroidGl_nativeBeginFrame": "return JNI_FALSE;",
        "static void JNICALL rc_AWT_swapBuffers": "return;",
        "Java_rs117_hd_android_AndroidGl_nativeSnapshot": "return nullptr;",
    }
    for name, failure in signatures.items():
        start = text.index(name)
        index = text.index("    rc_make_current();", start)
        text = text[:index] + text[index:].replace(
            "    rc_make_current();",
            '    if(!rc_make_current()){rc_frame_error(env,"117 HD context is not current on the render thread");' + failure + '}',
            1,
        )
    text = section(text, 'extern "C" JNIEXPORT jstring JNICALL Java_rs117_hd_android_AndroidGl_nativeValidate', 'extern "C" JNIEXPORT jint JNICALL JNI_OnLoad', """\
extern "C" JNIEXPORT jstring JNICALL Java_rs117_hd_android_AndroidGl_nativeValidate(JNIEnv* env,jclass){
    if(!newscape117Current()||!sFbo||glCheckFramebufferStatus(GL_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE){
        rc_frame_error(env,"117 HD iOS framebuffer is incomplete");
        return nullptr;
    }
    GLint major=0,uniformBytes=0,textureUnits=0;
    glGetIntegerv(GL_MAJOR_VERSION,&major);
    glGetIntegerv(GL_MAX_UNIFORM_BLOCK_SIZE,&uniformBytes);
    glGetIntegerv(GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS,&textureUnits);
    if(major<3||uniformBytes<16384||textureUnits<5){
        rc_frame_error(env,"117 HD requires GLES 3.0, 16 KB uniform blocks and five texture units");
        return nullptr;
    }
    const char* renderer=(const char*)glGetString(GL_RENDERER);
    std::string detail=std::string(renderer ? renderer : "OpenGL ES") + " | iOS GLES 3.0";
    return env->NewStringUTF(detail.c_str());
}
""")
    code = re.sub(r"//[^\n]*|/\*.*?\*/", "", text, flags=re.DOTALL)
    if re.search(r"\b(?:egl[A-Z]\w*|EGL_[A-Z_]+|__android_log_print|__system_property_get|sDirectEnabled)\b", code):
        raise ValueError("Unexpected Android-only native symbol")
    output.mkdir(parents=True)
    (output / "Facade.cpp").write_text(text, encoding="utf-8", newline="\n")
    shutil.copy2(snapshot / "readback_queue.h", output)
    shutil.copy2(Path(__file__).with_name("EaglContext.mm"), output)
    print(f"Staged the JNI facade and iOS EAGL context at {output}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("snapshot", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    stage(args.snapshot, args.output)


if __name__ == "__main__":
    main()