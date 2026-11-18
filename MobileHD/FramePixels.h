#pragma once
#include <stddef.h>
#include <stdint.h>

namespace MobileHdPixels {
inline size_t length(int width, int height) {
    constexpr int maxAxis = 8192;
    constexpr size_t maxPixels = 4096 * 4096;
    if (width <= 0 || height <= 0 || width > maxAxis || height > maxAxis) return 0;
    const size_t pixels = static_cast<size_t>(width) * height;
    return pixels <= maxPixels ? pixels * 4 : 0;
}

// ReadPixels returns RGBA starting with the bottom row; KronosBoot and Core
// Graphics share the top-row-first BGRA representation.
inline bool fromOpenGl(const uint8_t* rgba, size_t rgbaCapacity, uint8_t* bgra,
                      size_t bgraCapacity, int width, int height) {
    const size_t bytes = length(width, height);
    if (!rgba || !bgra || rgba == bgra || !bytes || rgbaCapacity < bytes || bgraCapacity < bytes)
        return false;
    const size_t stride = static_cast<size_t>(width) * 4;
    for (int y = 0; y < height; ++y) {
        const uint8_t* source = rgba + static_cast<size_t>(height - 1 - y) * stride;
        uint8_t* dest = bgra + static_cast<size_t>(y) * stride;
        for (size_t x = 0; x < stride; x += 4) {
            dest[x] = source[x + 2];
            dest[x + 1] = source[x + 1];
            dest[x + 2] = source[x];
            dest[x + 3] = source[x + 3];
        }
    }
    return true;
}
}
