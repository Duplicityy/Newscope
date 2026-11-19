// Ultra consumes the existing capture in camera space. Its normals already include model orientation.
const char* ultraVertex=R"(#version 300 es
precision highp float;
layout(location=0) in vec3 p;
layout(location=1) in vec4 c;
layout(location=2) in vec3 n;
layout(location=3) in vec3 uv;

uniform vec3 projection;
uniform vec2 center;
uniform mat3 cameraToWorld;
uniform highp int phase;
out vec4 color;
out vec3 normal;
out vec3 tex;
flat out int playerFace;
out vec3 eyeVector;
out float distanceToCamera;

#ifdef MOBILE_HD_SHADOWS
uniform mat4 shadowMatrix;
uniform mat4 shadowFarMatrix;
out vec3 shadowPosition;
out vec3 shadowPositionFar;
#endif

void main() {
    // Capture emits identical alpha and layer for all three vertices of a face.
    // Texture alpha can still split a textured face across the two passes.
    if (c.a < 0.01 || (phase == 0 && c.a < 0.99)
            || (phase == 1 && uv.z < 0.0 && c.a >= 0.99)) {
        gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
        return;
    }
    const float nearPlane = 50.0, farPlane = 6144.0;
    float w = p.z;
    gl_Position = vec4(
        2.0 * p.x * projection.z / projection.x + (2.0 * center.x / projection.x - 1.0) * w,
       -2.0 * p.y * projection.z / projection.y + (1.0 - 2.0 * center.y / projection.y) * w,
        (farPlane + nearPlane) / (farPlane - nearPlane) * w
            - 2.0 * farPlane * nearPlane / (farPlane - nearPlane), w);
    vec3 worldRelative = cameraToWorld * p;
    eyeVector = -worldRelative;
    distanceToCamera = length(worldRelative.xz);
    normal = n;
    color = c;
    // Existing array layers are integers. The Ultra capture adds a quarter layer
    // on textured player faces and uses -2 for solid player faces. Read the tag
    // before interpolation and always sample the original integer layer.
    playerFace = (uv.z < -1.5 || (uv.z >= 0.0 && fract(uv.z) > 0.125)) ? 1 : 0;
    tex = vec3(uv.xy, floor(uv.z));
#ifdef MOBILE_HD_SHADOWS
    shadowPosition = (shadowMatrix * vec4(worldRelative, 1.0)).xyz;
    shadowPositionFar = (shadowFarMatrix * vec4(worldRelative, 1.0)).xyz;
#endif
})";

const char* ultraFragment=R"(#version 300 es
precision highp float;
precision highp int;
precision highp sampler2DArray;
in vec4 color;
in vec3 normal;
in vec3 tex;
flat in int playerFace;
in vec3 eyeVector;
in float distanceToCamera;
uniform sampler2DArray atlas;
uniform vec3 lightDirection;
uniform vec3 hazeColor;
uniform int localLightCount;
uniform vec4 localLights[4];
uniform vec3 localLightColors[4];
uniform int phase;
uniform int settings;
out vec4 result;
)"
#include "ultra_shadow_filter.inc"
R"(
vec3 cacheToLinear(vec3 rgb) {
    return mix(rgb / 12.92, pow((rgb + 0.055) / 1.055, vec3(2.4)), step(0.04045, rgb));
}

float maxChannel(vec3 rgb) { return max(max(rgb.r, rgb.g), rgb.b); }

float fifthPower(float x) {
    float x2 = x * x;
    return x2 * x2 * x;
}

// Correlated Smith visibility, GGX distribution and Schlick Fresnel. The roughness
// floor also limits the subpixel sparkle of small animated faces on a phone.
vec3 reflectedLight(vec3 N, vec3 V, vec3 L, float nv, float nl, float roughness, vec3 f0) {
    vec3 halfVector = V + L;
    vec3 H = halfVector * inversesqrt(max(dot(halfVector, halfVector), 0.0001));
    float nh = clamp(dot(N, H), 0.0, 1.0);
    float vh = clamp(dot(V, H), 0.0, 1.0);
    float alpha = roughness * roughness;
    float alpha2 = alpha * alpha;
    float d = 1.0 + nh * nh * (alpha2 - 1.0);
    float visibility = 0.5 / max(nl * mix(nv, 1.0, alpha) + nv * mix(nl, 1.0, alpha), 0.001);
    vec3 fresnel = f0 + (1.0 - f0) * fifthPower(1.0 - vh);
    // Irradiance already includes the Lambert 1/pi normalization.
    return fresnel * (alpha2 * visibility / (d * d));
}

void main() {
    if (playerFace != 0 && !gl_FrontFacing) discard;
    vec4 base = color;
    if (tex.z >= 0.0) base *= texture(atlas, tex);
    if (base.a < 0.01 || (phase == 0 && base.a < 0.99) || (phase == 1 && base.a >= 0.99)) discard;

    vec3 albedo = cacheToLinear(base.rgb);
    vec3 N = normal * inversesqrt(max(dot(normal, normal), 0.000001));
    vec3 V = normalize(eyeVector);
    // Only untextured player faces carry the -2 hint. -1 also covers gray walls and ground.
    bool playerSurface = tex.z < -1.5;
    float nv = clamp(dot(N, V), 0.02, 1.0);
    bool materials = (settings & 16) != 0;
    bool night = (settings & 256) != 0;

    float value = maxChannel(base.rgb);
    float saturation = (value - min(min(base.r, base.g), base.b)) / max(value, 0.04);
    float hardSurface = playerSurface
        ? (1.0 - smoothstep(0.10, 0.40, saturation)) * smoothstep(0.08, 0.32, value) : 0.0;
    float roughness = mix(playerSurface ? 0.79 : 0.91, 0.45, hardSurface);
    vec3 f0 = mix(vec3(playerSurface ? 0.022 : 0.014),
                  mix(vec3(0.10), albedo * 0.32, 0.35), hardSurface);

    // Capture space uses negative Y for up. The lower hemisphere receives bounced
    // light and upper faces see the open sky, including inside a cast shadow.
    float sky = clamp(0.5 - 0.5 * N.y, 0.0, 1.0);
    vec3 ground = night ? vec3(0.052, 0.053, 0.068) : vec3(0.22, 0.17, 0.13);
    vec3 zenith = night ? vec3(0.105, 0.157, 0.254) : vec3(0.38, 0.47, 0.61);
    vec3 direct = night ? vec3(0.30, 0.40, 0.59) : vec3(1.54, 1.28, 0.98);
    vec3 skyLight = mix(ground, zenith, sky);
    vec3 radiance = albedo * skyLight;

    float nl = max(dot(N, lightDirection), 0.0);
    if (nl > 0.0) {
        vec3 response = albedo;
        if (materials) response += reflectedLight(N, V, lightDirection, nv, nl, roughness, f0);
        radiance += response * direct * (nl * sunVisibility(nl));
    }
    if (materials) {
        // Broad sky reflection on the hard faces complements the punctual highlight.
        vec3 reflection = reflect(-V, N);
        float reflectionSky = clamp(0.5 - 0.5 * reflection.y, 0.0, 1.0);
        vec3 environment = mix(ground, zenith, reflectionSky);
        vec3 fresnel = f0 + (max(vec3(1.0 - roughness), f0) - f0) * fifthPower(1.0 - nv);
        radiance += environment * fresnel * mix(0.12, 0.52, hardSurface);
    }

    // CPU supplies up to four sources from this scene. Radii are in game units.
    for (int i = 0; i < 4; ++i) {
        if (i >= localLightCount) break;
        vec3 delta = localLights[i].xyz + eyeVector;
        float d2 = dot(delta, delta);
        float radius = localLights[i].w;
        float fraction = d2 / max(radius * radius, 1.0);
        if (fraction >= 1.0) continue;
        vec3 L = delta * inversesqrt(max(d2, 1.0));
        float cosine = max(dot(N, L), 0.0);
        if (cosine <= 0.0) continue;
        float fade = 1.0 - fraction;
        float attenuation = fade * fade / (1.0 + 9.0 * fraction);
        vec3 response = albedo;
        if (materials) response += reflectedLight(N, V, L, nv, cosine, roughness, f0);
        radiance += response * localLightColors[i] * (cosine * attenuation);
    }

    if ((settings & 32) != 0) {
        float haze = smoothstep(night ? 1550.0 : 2050.0, 6000.0, distanceToCamera);
        // Gentle forward scattering toward the light; both endpoints match the haze clear.
        float towardsLight = max(dot(-V, lightDirection), 0.0);
        vec3 inscatter = direct * (0.11 * haze * (1.0 - haze) * fifthPower(towardsLight));
        radiance = mix(radiance, hazeColor, haze) + inscatter;
    }
    // SRGB8_ALPHA8 encodes the pre-exposed value. GL blends translucent materials in linear light.
    result = vec4(radiance * 0.5, base.a);
})";
