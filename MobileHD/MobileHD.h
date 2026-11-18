#pragma once
#include <cstddef>
#include <cstdint>
#include <jni.h>

enum class IosMobileHdFrame { Classic, Hold, Composed };

// These calls are confined to the JVM frame worker. Pixels are BGRA,
// beginning at the top row. Only Composed replaces the caller's bytes.
// Hold retains the previous visible frame while Java geometry catches up.
IosMobileHdFrame iosMobileHdCompose(JNIEnv* env, uint8_t* bgra, size_t capacity,
                                  int width, int height, int64_t sequence);
void iosMobileHdSetUltraOptions(int mask);
bool iosMobileHdSetShadows(JNIEnv* env, bool enabled);
void iosMobileHdRelease(JNIEnv* env);
