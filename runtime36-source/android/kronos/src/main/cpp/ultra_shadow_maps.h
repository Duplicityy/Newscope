// Included inside the mobile GLES presenter's render-thread namespace.
namespace ultra_shadows {

constexpr float nearExtent = 1024.0f;
constexpr float farExtent = 3072.0f;
constexpr float singleExtent = 1792.0f;
constexpr float depthExtent = 4096.0f;

GLuint caster = 0, framebuffer = 0, depthTexture = 0, rawDepthSampler = 0;
GLint cameraLocation = -1, projectionLocation = -1;
int requestedCascades = 0, cascades = 0;

const char* casterFragment = R"(#version 300 es
precision highp float;
precision highp sampler2DArray;
in vec3 tex;
in float opacity;
uniform sampler2DArray atlas;
void main() {
    // Opaque faces can carry a binary cutout (leaves, fences, cape edges).
    // The filtered alpha at a silhouette describes area coverage.
    if (opacity < 0.99) discard;
    if (tex.z >= 0.0 && texture(atlas, tex).a < 0.5) discard;
})";

Vec3 direction(bool night) {
    return normalize(night ? Vec3{.55f, -.67f, .50f} : Vec3{-.52f, -.74f, -.42f});
}

void freeAtlas() {
    if (framebuffer) glDeleteFramebuffers(1, &framebuffer);
    if (depthTexture) glDeleteTextures(1, &depthTexture);
    framebuffer = depthTexture = 0;
    cascades = 0;
}

void freeObjects() {
    freeAtlas();
    if (caster) glDeleteProgram(caster);
    if (rawDepthSampler) glDeleteSamplers(1, &rawDepthSampler);
    caster = rawDepthSampler = 0;
}

void release() {
    freeObjects();
    requestedCascades = 0;
}

bool allocateAtlas(int count) {
    glActiveTexture(GL_TEXTURE1);
    glGenTextures(1, &depthTexture);
    glBindTexture(GL_TEXTURE_2D, depthTexture);
    glTexStorage2D(GL_TEXTURE_2D, 1, GL_DEPTH_COMPONENT24, count * SHADOW_SIZE, SHADOW_SIZE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_MODE, GL_COMPARE_REF_TO_TEXTURE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_FUNC, GL_LEQUAL);

    glGenFramebuffers(1, &framebuffer);
    glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, depthTexture, 0);
    const GLenum none = GL_NONE;
    glDrawBuffers(1, &none);
    glReadBuffer(GL_NONE);
    const GLenum status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glActiveTexture(GL_TEXTURE0);
    if (!framebuffer || !depthTexture || status != GL_FRAMEBUFFER_COMPLETE || glGetError() != GL_NO_ERROR) {
        freeAtlas();
        clearErrors();
        return false;
    }
    cascades = count;
    return true;
}

bool prepare(bool detailed) {
    const int requested = detailed ? 2 : 1;
    if (requested == requestedCascades) return cascades > 0;
    release();
    requestedCascades = requested;

    GLint maxTexture = 0, units = 0;
    glGetIntegerv(GL_MAX_TEXTURE_SIZE, &maxTexture);
    glGetIntegerv(GL_MAX_TEXTURE_IMAGE_UNITS, &units);
    if (maxTexture < SHADOW_SIZE || units < 4) {
        log("Ultra shadows unavailable on this GLES context");
        return false;
    }

    caster = program(shadowVertex, casterFragment);
    if (!caster) {
        clearErrors();
        return false;
    }
    cameraLocation = glGetUniformLocation(caster, "cameraToWorld");
    projectionLocation = glGetUniformLocation(caster, "shadowMatrix");
    glUseProgram(caster);
    glUniform1i(glGetUniformLocation(caster, "atlas"), 0);
    // The same depth image supplies both regular blocker depths and filtered
    // comparisons. A separate sampler object avoids any copy or host readback.
    glGenSamplers(1, &rawDepthSampler);
    glSamplerParameteri(rawDepthSampler, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glSamplerParameteri(rawDepthSampler, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glSamplerParameteri(rawDepthSampler, GL_TEXTURE_COMPARE_MODE, GL_NONE);
    glSamplerParameteri(rawDepthSampler, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glSamplerParameteri(rawDepthSampler, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    if (!rawDepthSampler || glGetError() != GL_NO_ERROR) {
        freeObjects();
        clearErrors();
        log("Ultra shadow sampler unavailable");
        return false;
    }
    if (detailed && maxTexture >= 2 * SHADOW_SIZE && allocateAtlas(2)) return true;
    if (allocateAtlas(1)) {
        if (detailed) log("Ultra shadows using one cascade after atlas allocation limit");
        return true;
    }
    freeObjects();
    log("Ultra shadow framebuffer unavailable");
    return false;
}

void matrices(const jint* p, const float* camera, Vec3 light, int count,
              float* nearMatrix, float* farMatrix) {
    if (count == 2) {
        shadowMatrix(p, camera, light, 1050.0f, nearExtent, depthExtent, nearMatrix);
        shadowMatrix(p, camera, light, 2750.0f, farExtent, depthExtent, farMatrix);
    } else {
        shadowMatrix(p, camera, light, 1500.0f, singleExtent, depthExtent, nearMatrix);
        std::memcpy(farMatrix, nearMatrix, sizeof(float) * 16);
    }
}

bool draw(const jint* p, const float* camera, Vec3 light, bool detailed,
          float* nearMatrix, float* farMatrix) {
    if (!prepare(detailed)) return false;
    matrices(p, camera, light, cascades, nearMatrix, farMatrix);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D_ARRAY, atlas);
    glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
    glDisable(GL_SCISSOR_TEST);
    glDisable(GL_BLEND);
    glDisable(GL_CULL_FACE);
    glEnable(GL_DEPTH_TEST);
    glDepthFunc(GL_LEQUAL);
    glDepthMask(GL_TRUE);
    glClearDepthf(1);
    glClear(GL_DEPTH_BUFFER_BIT);
    glColorMask(GL_FALSE, GL_FALSE, GL_FALSE, GL_FALSE);
    glEnable(GL_POLYGON_OFFSET_FILL);
    glPolygonOffset(1.0f, 1.0f);
    glEnable(GL_SCISSOR_TEST);
    glUseProgram(caster);
    glUniformMatrix3fv(cameraLocation, 1, GL_FALSE, camera);
    for (int i = 0; i < cascades; ++i) {
        glViewport(i * SHADOW_SIZE, 0, SHADOW_SIZE, SHADOW_SIZE);
        glScissor(i * SHADOW_SIZE, 0, SHADOW_SIZE, SHADOW_SIZE);
        glUniformMatrix4fv(projectionLocation, 1, GL_FALSE, i == 0 ? nearMatrix : farMatrix);
        glDrawArrays(GL_TRIANGLES, 0, p[0]);
    }
    glDisable(GL_SCISSOR_TEST);
    glDisable(GL_POLYGON_OFFSET_FILL);
    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    if (glGetError() != GL_NO_ERROR) {
        freeObjects();
        clearErrors();
        log("Ultra shadow depth pass failed; drawing the scene without shadows");
        return false;
    }
    return true;
}

void bind(GLuint scene, const float* farMatrix, bool night) {
    glUniformMatrix4fv(glGetUniformLocation(scene, "shadowFarMatrix"), 1, GL_FALSE, farMatrix);
    glUniform1i(glGetUniformLocation(scene, "shadowCascadeCount"), cascades);
    const float nearPitch = 2.0f * (cascades == 2 ? nearExtent : singleExtent) / SHADOW_SIZE;
    const float farPitch = cascades == 2 ? 2.0f * farExtent / SHADOW_SIZE : nearPitch;
    glUniform4f(glGetUniformLocation(scene, "shadowFilterScale"),
                nearPitch, farPitch, 2.0f * depthExtent, night ? .028f : .022f);
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, depthTexture);
    glBindSampler(1, 0);
    glUniform1i(glGetUniformLocation(scene, "shadowMap"), 1);
    glActiveTexture(GL_TEXTURE3);
    glBindTexture(GL_TEXTURE_2D, depthTexture);
    glBindSampler(3, rawDepthSampler);
    glUniform1i(glGetUniformLocation(scene, "shadowBlockers"), 3);
}

} // namespace ultra_shadows
