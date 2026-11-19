#version 300 es
precision highp float;
layout(location=0) in vec3 p;
layout(location=1) in vec4 c;
layout(location=2) in vec3 n;
layout(location=3) in vec3 uv;

uniform vec3 projection;
uniform vec2 center;
uniform mat3 cameraToWorld;
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
}