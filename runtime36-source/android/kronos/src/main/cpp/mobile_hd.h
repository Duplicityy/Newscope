#pragma once
#include <jni.h>
#include <android/native_window.h>
#include <cstdint>
enum class HdPresentation { CLASSIC=0, PRESENTED=1, HOLD=2 };
// Call only from the native presentation worker, holding the existing surface lock.
HdPresentation hdPresent(JNIEnv*,ANativeWindow*,uint64_t,const uint32_t*,int,int,long long);
void hdReleaseSurface();
void hdUltraOptions(int mask);
