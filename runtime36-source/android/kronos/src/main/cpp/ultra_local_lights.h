#pragma once
#include <GLES3/gl3.h>
#include <jni.h>

namespace ultra_lights {
// Camera-relative x, y, z and style for each emitter. Keep in sync with UltraSceneLights.
constexpr int COUNT = 16, LIMIT = 4, METADATA = COUNT + 1 + LIMIT * 4;
struct Emitter { float radius, red, green, blue; };
constexpr Emitter styles[] = {
    {0, 0, 0, 0}, {460, 2.45f, .93f, .28f}, {460, 2.8f, .32f, .13f},
    {460, .34f, 1.9f, .38f}, {460, .27f, .76f, 2.65f}, {460, 1.9f, 1.75f, 1.55f},
    {460, 1.5f, .37f, 2.35f}, {370, 2.0f, .86f, .28f}, {520, 2.55f, 1.08f, .32f}
};

inline void bind(GLuint program, const jint* frame) {
    GLfloat points[LIMIT * 4], colors[LIMIT * 3];
    int count = 0;
    for (int i = 0; i < frame[COUNT] && i < LIMIT; ++i) {
        const jint* entry = frame + COUNT + 1 + i * 4;
        const int style = entry[3];
        if (style <= 0 || style >= static_cast<int>(sizeof(styles) / sizeof(styles[0]))) continue;
        const Emitter& emitter = styles[style];
        for (int axis = 0; axis < 3; ++axis) points[count * 4 + axis] = static_cast<float>(entry[axis]);
        points[count * 4 + 3] = emitter.radius;
        colors[count * 3] = emitter.red;
        colors[count * 3 + 1] = emitter.green;
        colors[count * 3 + 2] = emitter.blue;
        ++count;
    }
    glUniform1i(glGetUniformLocation(program, "localLightCount"), count);
    if (count) {
        glUniform4fv(glGetUniformLocation(program, "localLights[0]"), count, points);
        glUniform3fv(glGetUniformLocation(program, "localLightColors[0]"), count, colors);
    }
}
} // namespace ultra_lights
