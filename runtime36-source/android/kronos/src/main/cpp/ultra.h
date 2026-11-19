// Render-thread-owned Ultra scene and resolve. The game UI is composited afterwards.
#pragma once
namespace ultra {
#include "ultra_surface.h"

static int options = 0;
static GLuint framebuffer = 0, color = 0, depth = 0, resolve = 0, surface[2] = {};
static int width = 0, height = 0;
static bool failed = false;
struct ResolveUniforms {
    GLint pixel, viewLens, settings;
};
static ResolveUniforms resolveUniforms{};

const char* vertex=R"(#version 300 es
out vec2 uv;
void main() {
    vec2 p = vec2(gl_VertexID == 1 ? 3.0 : -1.0, gl_VertexID == 2 ? 3.0 : -1.0);
    gl_Position = vec4(p, 0.0, 1.0);
    uv = p * 0.5 + 0.5;
})";

const char* fragment=R"(#version 300 es
precision highp float;
precision highp int;
in vec2 uv;
uniform highp sampler2D scene;
uniform highp sampler2D depthMap;
uniform vec2 pixel;
uniform vec4 viewLens; // focal lengths and principal point, in physical framebuffer pixels
uniform int settings;
out vec4 result;

float cameraDepth(vec2 coord) {
    float d = texture(depthMap, coord).r;
    return 307200.0 / (6144.0 - d * 6094.0);
}

vec3 viewPoint(vec2 coord, float z) {
    return vec3((coord / pixel - viewLens.zw) * (z / viewLens.xy), z);
}

const vec2 directions[12] = vec2[12](
    vec2(1.0000, 0.0000), vec2(-0.7374,  0.6755), vec2( 0.0874, -0.9962),
    vec2(0.6084, 0.7936), vec2(-0.9847, -0.1742), vec2( 0.8438, -0.5367),
    vec2(-0.2596,0.9657), vec2(-0.4609, -0.8874), vec2( 0.9393,  0.3430),
    vec2(-0.9243,0.3816), vec2( 0.4238, -0.9057), vec2( 0.2993,  0.9542)
);

float contactVisibility(int quality) {
    const float radius = 51.0; // game units; one tile is 128
    float z = cameraDepth(uv);
    if (z >= 5800.0) return 1.0;
    vec2 radiusPixels = (radius / z) * viewLens.xy;
    float longest = max(radiusPixels.x, radiusPixels.y);
    if (longest < 1.0) return 1.0;
    float screenLimit = 0.065 / max(pixel.x, pixel.y);
    radiusPixels *= min(1.0, screenLimit / longest);

    // At a silhouette the nearest continuous neighbour on each axis reconstructs
    // the receiver plane without borrowing the normal of the object behind it.
    vec2 stepX = vec2(pixel.x, 0.0), stepY = vec2(0.0, pixel.y);
    float left = cameraDepth(uv - stepX), right = cameraDepth(uv + stepX);
    float down = cameraDepth(uv - stepY), up = cameraDepth(uv + stepY);
    vec3 p = viewPoint(uv, z);
    vec3 dx = abs(right - z) <= abs(left - z)
        ? viewPoint(uv + stepX, right) - p : p - viewPoint(uv - stepX, left);
    vec3 dy = abs(up - z) <= abs(down - z)
        ? viewPoint(uv + stepY, up) - p : p - viewPoint(uv - stepY, down);
    vec3 plane = cross(dx, dy);
    vec3 n = plane * inversesqrt(max(dot(plane, plane), 1e-9));
    if (dot(n, p) > 0.0) n = -n;

    int count = quality * 4;
    float obstruction = 0.0;
    for (int i = 0; i < 12; ++i) {
        if (i >= count) break;
        float ring = (float(i) + 0.65) / float(count);
        vec2 at = uv + directions[i] * (pixel * radiusPixels) * mix(0.08, 1.0, ring);
        if (any(lessThan(at, vec2(0.0))) || any(greaterThanEqual(at, vec2(1.0)))) continue;
        // The depth attachment uses nearest filtering. Reconstruct the location of
        // that texel's centre to avoid micro-occlusion on otherwise flat surfaces.
        at = (floor(at / pixel) + 0.5) * pixel;
        vec3 delta = viewPoint(at, cameraDepth(at)) - p;
        float d2 = dot(delta, delta);
        float cosine = max(dot(n, delta) - 1.2, 0.0) * inversesqrt(max(d2, 0.25));
        float falloff = max(1.0 - d2 / (radius * radius), 0.0);
        obstruction += cosine * falloff * falloff;
    }
    float fading = (1.0 - smoothstep(4000.0, 5800.0, z)) * smoothstep(1.0, 3.0, longest);
    return max(0.48, 1.0 - obstruction * (1.65 * fading / float(count)));
}

float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

float edgeLuma(vec2 coord) {
    return sqrt(luma(texture(scene, coord).rgb));
}

// Follow the high-contrast edge in perceptual luminance, but interpolate colour in linear space.
vec3 smoothEdge(vec3 center) {
    float nw = edgeLuma(uv + vec2(-pixel.x, pixel.y));
    float ne = edgeLuma(uv + pixel);
    float sw = edgeLuma(uv - pixel);
    float se = edgeLuma(uv + vec2(pixel.x, -pixel.y));
    float middle = sqrt(luma(center));
    float low = min(middle, min(min(nw, ne), min(sw, se)));
    float high = max(middle, max(max(nw, ne), max(sw, se)));
    if (high - low < max(0.025, high * 0.12)) return center;

    vec2 dir = vec2((nw + ne) - (sw + se), (sw + nw) - (ne + se));
    float damping = max((nw + ne + sw + se) / 32.0, 0.006);
    dir = clamp(dir / (min(abs(dir.x), abs(dir.y)) + damping), -4.0, 4.0) * pixel;
    vec3 inner = (texture(scene, uv - dir / 6.0).rgb + texture(scene, uv + dir / 6.0).rgb) * 0.5;
    vec3 outer = inner * 0.5
        + (texture(scene, uv - 0.5 * dir).rgb + texture(scene, uv + 0.5 * dir).rgb) * 0.25;
    float estimated = sqrt(luma(outer));
    return estimated < low || estimated > high ? inner : outer;
}

vec3 brightPart(vec2 coord) {
    vec3 rgb = 2.0 * texture(scene, coord).rgb;
    float peak = max(max(rgb.r, rgb.g), rgb.b);
    return rgb * (smoothstep(0.78, 1.18, peak) * max(peak - 0.78, 0.0) / max(peak, 0.001));
}

vec3 displayColor(vec3 radiance) {
    // A differentiable luminance shoulder leaves the original palette and shadow levels intact.
    float y = luma(radiance);
    float excess = max(y - 0.55, 0.0);
    float mapped = min(y, 0.55) + excess / (1.0 + excess / 0.45);
    vec3 rgb = radiance * (mapped / max(y, 1e-6));
    float peak = max(max(rgb.r, rgb.g), rgb.b);
    if (peak > 1.0) rgb = mix(vec3(mapped), rgb, (1.0 - mapped) / (peak - mapped));
    rgb = max(rgb, vec3(0.0));
    return mix(12.92 * rgb, 1.055 * pow(rgb, vec3(1.0 / 2.4)) - 0.055, step(0.0031308, rgb));
}

void main() {
    vec3 color = texture(scene, uv).rgb;
    if ((settings & 4) != 0) color = smoothEdge(color);
    vec3 radiance = color * 2.0;
    int quality = settings & 3;
    if (quality > 0) radiance *= contactVisibility(quality);
    if ((settings & 8) != 0) {
        vec2 blur = pixel * max(1.5, 0.0035 / max(pixel.x, pixel.y));
        vec3 glow = brightPart(uv - blur) + brightPart(uv + blur)
                  + brightPart(uv + vec2(blur.x, -blur.y)) + brightPart(uv + vec2(-blur.x, blur.y));
        radiance += glow * 0.025;
    }
    result = vec4(displayColor(radiance), 1.0);
})";

void freeTargets() {
    glDeleteTextures(1, &color);
    glDeleteTextures(1, &depth);
    glDeleteFramebuffers(1, &framebuffer);
    framebuffer = color = depth = 0;
    width = height = 0;
}

void release() {
    freeTargets();
    if (resolve) glDeleteProgram(resolve);
    for (GLuint& p : surface) {
        if (p) glDeleteProgram(p);
        p = 0;
    }
    resolve = 0;
    failed = false;
}

void texture2D(GLuint& id, GLenum format, int w, int h, GLenum filter) {
    glGenTextures(1, &id);
    glBindTexture(GL_TEXTURE_2D, id);
    glTexStorage2D(GL_TEXTURE_2D, 1, format, w, h);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
}

bool begin(int w, int h, bool shadows) {
    if (!options || failed || w < 1 || h < 1) return false;
    if (!resolve) {
        resolve = program(vertex, fragment);
        if (resolve) {
            glUseProgram(resolve);
            glUniform1i(glGetUniformLocation(resolve, "scene"), 0);
            glUniform1i(glGetUniformLocation(resolve, "depthMap"), 2);
            resolveUniforms = {glGetUniformLocation(resolve, "pixel"),
                               glGetUniformLocation(resolve, "viewLens"),
                               glGetUniformLocation(resolve, "settings")};
        }
    }
    if (!surface[shadows]) surface[shadows] = program(ultraVertex, ultraFragment, shadows);
    if (width != w || height != h) {
        freeTargets();
        texture2D(color, GL_SRGB8_ALPHA8, w, h, GL_LINEAR);
        texture2D(depth, GL_DEPTH_COMPONENT24, w, h, GL_NEAREST);
        glGenFramebuffers(1, &framebuffer);
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, color, 0);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, depth, 0);
        width = w;
        height = h;
    } else {
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
    }
    if (!resolve || !surface[shadows] || glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE
        || glGetError() != GL_NO_ERROR) {
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        release();
        failed = true;
        clearErrors();
        log("Ultra unavailable: using Mobile HD");
        return false;
    }
    return true;
}

void finish(float focalX, float focalY, float centerX, float centerY) {
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glViewport(0, 0, width, height);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_BLEND);
    glUseProgram(resolve);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, color);
    glActiveTexture(GL_TEXTURE2);
    glBindTexture(GL_TEXTURE_2D, depth);
    glUniform2f(resolveUniforms.pixel, 1.f / width, 1.f / height);
    glUniform4f(resolveUniforms.viewLens, focalX, focalY, centerX, centerY);
    glUniform1i(resolveUniforms.settings, options);
    glDrawArrays(GL_TRIANGLES, 0, 3);
    glActiveTexture(GL_TEXTURE0);
    glEnable(GL_BLEND);
}
}
