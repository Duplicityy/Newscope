R"(
#ifdef MOBILE_HD_SHADOWS
precision highp sampler2DShadow;
in vec3 shadowPosition;
in vec3 shadowPositionFar;
uniform sampler2DShadow shadowMap;
uniform highp sampler2D shadowBlockers;
uniform int shadowCascadeCount;
// World-space texel widths for each cascade, depth span, and light angular slope.
uniform vec4 shadowFilterScale;

float cascadeWeight(vec3 clip) {
    float edge = max(abs(clip.x), max(abs(clip.y), abs(clip.z)));
    return 1.0 - smoothstep(0.81, 0.98, edge);
}

float filteredCascade(vec3 clip, int tile, float ndl) {
    vec3 q = clip * .5 + .5;
    vec2 texel = 1.0 / vec2(textureSize(shadowMap, 0));
    float count = float(shadowCascadeCount);
    vec2 uv = vec2((q.x + float(tile)) / count, q.y);
    vec2 lo = vec2(float(tile) / count, 0.0) + .55 * texel;
    vec2 hi = vec2(float(tile + 1) / count, 1.0) - .55 * texel;
    float pitch = tile == 0 ? shadowFilterScale.x : shadowFilterScale.y;
    float slope = min(2.0, sqrt(max(0.0, 1.0 - ndl * ndl)) / max(.20, ndl));
    float reference = q.z - (.20 + pitch * .24 * slope) / shadowFilterScale.z;

    if (shadowCascadeCount == 1) {
        // Each comparison is bilinearly filtered in hardware.
        vec2 s = .68 * texel;
        return .25 * (texture(shadowMap, vec3(clamp(uv + s, lo, hi), reference))
                    + texture(shadowMap, vec3(clamp(uv - s, lo, hi), reference))
                    + texture(shadowMap, vec3(clamp(uv + vec2(-s.x, s.y), lo, hi), reference))
                    + texture(shadowMap, vec3(clamp(uv + vec2(s.x, -s.y), lo, hi), reference)));
    }

    // Estimate separation in world units for a directional area light. Contact
    // stays sharp. Elevated roofs, branches and equipment cast wider penumbras.
    const vec2 search[4] = vec2[4](
        vec2(-.83,-.37), vec2(.37,-.83), vec2(.83,.37), vec2(-.37,.83));
    float gapSum = 0.0, blockers = 0.0;
    for (int i = 0; i < 4; ++i) {
        float depth = texture(shadowBlockers, clamp(uv + search[i] * 2.5 * texel, lo, hi)).r;
        float gap = (reference - depth) * shadowFilterScale.z;
        if (gap > pitch * (.45 + slope)) {
            gapSum += gap;
            blockers += 1.0;
        }
    }
    float radius = clamp(.55 + gapSum / max(blockers, 1.0) * shadowFilterScale.w / pitch, .55, 3.25);
    const vec2 disk[8] = vec2[8](
        vec2(.92,.17), vec2(-.92,-.17), vec2(-.23,.91), vec2(.23,-.91),
        vec2(.37,.39), vec2(-.37,-.39), vec2(-.42,.32), vec2(.42,-.32));
    float visibility = 0.0;
    for (int i = 0; i < 8; ++i) {
        vec2 sampleUv = clamp(uv + disk[i] * texel * radius, lo, hi);
        visibility += texture(shadowMap, vec3(sampleUv, reference));
    }
    return visibility * .125;
}

float sunVisibility(float ndl) {
    if (ndl <= 0.0) return 1.0;
    float nearWeight = cascadeWeight(shadowPosition);
    if (nearWeight >= 1.0) return filteredCascade(shadowPosition, 0, ndl);
    float distant = 1.0;
    if (shadowCascadeCount == 2) {
        float farWeight = cascadeWeight(shadowPositionFar);
        if (farWeight > 0.0)
            distant = mix(1.0, filteredCascade(shadowPositionFar, 1, ndl), farWeight);
    }
    if (nearWeight <= 0.0) return distant;
    return mix(distant, filteredCascade(shadowPosition, 0, ndl), nearWeight);
}
#else
float sunVisibility(float ndl) { return 1.0; }
#endif
)"
