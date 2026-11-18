#include "MobileHD.h"
#include "MobileHdContext.h"
#include "FramePixels.h"

#ifdef __APPLE__
#define GLES_SILENCE_DEPRECATION
#include <OpenGLES/ES3/gl.h>
#else
#include <GLES3/gl3.h>
#endif
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <new>
#include <string>
#include <vector>

// The captured-geometry renderer and shader bodies below are carried over
// from Newscape Android. Only context, render target and presentation differ.
namespace {
constexpr int METADATA = 16, SHADOW_SIZE = 1024;
constexpr int MAX_VERTICES = 600000, VERTEX_BYTES = 13 * 4, LAYER_BYTES = 128 * 128 * 4;
constexpr float SHADOW_EXTENT = 1536.0f, SHADOW_DEPTH = 3072.0f;
GLuint sceneProgram=0,uiProgram=0,vao=0,vbo=0,uiTexture=0,atlas=0;
GLuint shadowSceneProgram=0,shadowProgram=0,shadowFramebuffer=0,shadowTexture=0;
bool shadowAttempted=false,shadowUnavailable=false;
bool gpuReady=false;
int uploadedLayers=0,textureGeneration=-1,sceneGeneration=-1;
int uiWidth=0,uiHeight=0;
jclass bridge=nullptr;
jmethodID copyGeometry=nullptr,copyTexture=nullptr,failMethod=nullptr,presentedMethod=nullptr,shadowsMethod=nullptr;
jobject meshDirect=nullptr,textureDirect=nullptr;
jintArray parameters=nullptr;
std::vector<uint8_t> mesh,readback;
std::array<uint8_t, LAYER_BYTES> texture{};

const char* sceneVertex=R"(#version 300 es
precision highp float;
layout(location=0)in vec3 p;layout(location=1)in vec4 c;
layout(location=2)in vec3 n;layout(location=3)in vec3 uv;
uniform vec3 projection;uniform vec2 center;
uniform mat3 cameraToWorld;uniform mat4 shadowMatrix;
out vec4 color;out vec3 normal;out vec3 tex;out float distanceToCamera;
#ifdef MOBILE_HD_SHADOWS
out vec3 shadowPosition;
#endif
void main(){float near=50.0,far=6144.0;float w=p.z;
gl_Position=vec4(2.0*p.x*projection.z/projection.x+(2.0*center.x/projection.x-1.0)*w,
-2.0*p.y*projection.z/projection.y+(1.0-2.0*center.y/projection.y)*w,
(far+near)/(far-near)*w-2.0*far*near/(far-near),w);
vec3 worldRelative=cameraToWorld*p;
color=c;normal=n;tex=uv;distanceToCamera=length(worldRelative.xz);
#ifdef MOBILE_HD_SHADOWS
shadowPosition=(shadowMatrix*vec4(worldRelative,1.0)).xyz;
#endif
})";
const char* sceneFragment=R"(#version 300 es
precision highp float;precision highp sampler2DArray;
in vec4 color;in vec3 normal;in vec3 tex;in float distanceToCamera;
uniform sampler2DArray atlas;uniform int phase;out vec4 result;
#ifdef MOBILE_HD_SHADOWS
precision highp sampler2DShadow;
in vec3 shadowPosition;uniform sampler2DShadow shadowMap;
float sunVisibility(float ndl){
if(ndl<=0.0)return 1.0;
vec3 q=shadowPosition*0.5+0.5;
if(q.z<=0.0||q.z>=1.0||any(lessThanEqual(q.xy,vec2(0.0)))||any(greaterThanEqual(q.xy,vec2(1.0))))return 1.0;
float bias=0.00006+0.00025*(1.0-ndl);
vec2 stepSize=vec2(0.65/1024.0);float visibility=0.0;
visibility+=texture(shadowMap,vec3(q.xy+vec2(-stepSize.x,-stepSize.y),q.z-bias));
visibility+=texture(shadowMap,vec3(q.xy+vec2( stepSize.x,-stepSize.y),q.z-bias));
visibility+=texture(shadowMap,vec3(q.xy+vec2(-stepSize.x, stepSize.y),q.z-bias));
visibility+=texture(shadowMap,vec3(q.xy+vec2( stepSize.x, stepSize.y),q.z-bias));
float border=smoothstep(0.82,0.98,max(abs(shadowPosition.x),abs(shadowPosition.y)));
float farFade=smoothstep(2300.0,3300.0,distanceToCamera);
return mix(visibility*0.25,1.0,max(border,farFade));
}
#else
float sunVisibility(float ndl){return 1.0;}
#endif
void main(){vec4 base=color;if(tex.z>=0.0)base*=texture(atlas,tex);
if(base.a<0.01)discard;
if(phase==0&&base.a<0.99)discard;if(phase==1&&base.a>=0.99)discard;
// Capture normals are world-space with negative Y up. Never abs/face-forward:
// the unlit side of a wall receives sky/ground ambient rather than a second sun.
vec3 N=normal*inversesqrt(max(dot(normal,normal),0.000001));
float ndl=max(dot(N,normalize(vec3(-0.45,-0.82,-0.35))),0.0);
float sky=clamp(0.5-0.5*N.y,0.0,1.0);
vec3 ambient=mix(vec3(0.25,0.27,0.31),vec3(0.42,0.48,0.58),sky);
vec3 illumination=ambient+vec3(0.70,0.63,0.518)*ndl*sunVisibility(ndl);
vec3 linear=pow(max(base.rgb,vec3(0.0)),vec3(2.2))*illumination;
// A neutral, white-preserving shoulder; cache hue and dark detail survive.
linear=linear*1.12/(vec3(1.0)+linear*0.12);
vec3 lit=pow(max(linear,vec3(0.0)),vec3(1.0/2.2));
float fog=smoothstep(3100.0,6144.0,distanceToCamera);
result=vec4(mix(lit,vec3(0.46,0.49,0.50),fog),base.a);})";
const char* shadowVertex=R"(#version 300 es
precision highp float;
layout(location=0)in vec3 p;layout(location=1)in vec4 c;layout(location=3)in vec3 uv;
uniform mat3 cameraToWorld;uniform mat4 shadowMatrix;
out vec3 tex;out float opacity;
void main(){gl_Position=shadowMatrix*vec4(cameraToWorld*p,1.0);tex=uv;opacity=c.a;})";
const char* shadowFragment=R"(#version 300 es
precision highp float;precision highp sampler2DArray;
in vec3 tex;in float opacity;uniform sampler2DArray atlas;
void main(){float alpha=opacity;if(tex.z>=0.0)alpha*=texture(atlas,tex).a;
// Cutout foliage casts its real silhouette; blended surfaces are not opaque
// blockers. No guessed cache texture IDs or synthetic shadow blobs.
if(alpha<0.99)discard;})";
const char* uiVertex=R"(#version 300 es
precision highp float;out vec2 uv;
void main(){vec2 p=vec2((gl_VertexID==1||gl_VertexID==2)?1.0:-1.0,gl_VertexID>=2?1.0:-1.0);
gl_Position=vec4(p,0.0,1.0);uv=vec2((p.x+1.0)*0.5,(1.0-p.y)*0.5);})";
const char* uiFragment=R"(#version 300 es
precision highp float;in vec2 uv;uniform sampler2D image;out vec4 result;
void main(){result=texture(image,uv).bgra;})";
void log(const std::string& message) { std::fprintf(stderr, "[Newscape Mobile HD] %s\n", message.c_str()); }
GLuint shader(GLenum type,const char* source,bool shadows=false){
 GLuint s=glCreateShader(type);std::string expanded(source);
 if(shadows)expanded.insert(expanded.find('\n')+1,"#define MOBILE_HD_SHADOWS 1\n");
 const char* text=expanded.c_str();glShaderSource(s,1,&text,nullptr);glCompileShader(s);
 GLint ok=0;glGetShaderiv(s,GL_COMPILE_STATUS,&ok);if(!ok){char detail[2048]{};glGetShaderInfoLog(s,sizeof(detail),nullptr,detail);log(detail);glDeleteShader(s);return 0;}return s;
}
GLuint program(const char* vertex,const char* fragment,bool shadows=false){GLuint vs=shader(GL_VERTEX_SHADER,vertex,shadows),fs=shader(GL_FRAGMENT_SHADER,fragment,shadows);if(!vs||!fs){if(vs)glDeleteShader(vs);if(fs)glDeleteShader(fs);return 0;}GLuint p=glCreateProgram();glAttachShader(p,vs);glAttachShader(p,fs);glLinkProgram(p);glDeleteShader(vs);glDeleteShader(fs);GLint ok=0;glGetProgramiv(p,GL_LINK_STATUS,&ok);if(!ok){char detail[2048]{};glGetProgramInfoLog(p,sizeof(detail),nullptr,detail);log(detail);glDeleteProgram(p);return 0;}return p;}
void clearErrors(){for(int i=0;i<16&&glGetError()!=GL_NO_ERROR;i++){}}
#include "UltraResolve.h"
void releaseShadows(){
 if(shadowSceneProgram)glDeleteProgram(shadowSceneProgram);if(shadowProgram)glDeleteProgram(shadowProgram);
 if(shadowFramebuffer)glDeleteFramebuffers(1,&shadowFramebuffer);if(shadowTexture)glDeleteTextures(1,&shadowTexture);
 shadowSceneProgram=shadowProgram=shadowFramebuffer=shadowTexture=0;
}
void disableShadows(const char* reason){
 glBindFramebuffer(GL_FRAMEBUFFER,0);glUseProgram(0);releaseShadows();clearErrors();
 shadowUnavailable=true;log(std::string("Mobile HD soft shadows unavailable; continuing without shadows: ")+reason);
}
bool extension(const char* wanted){
 GLint n=0;glGetIntegerv(GL_NUM_EXTENSIONS,&n);
 for(GLint i=0;i<n;i++){const char* name=reinterpret_cast<const char*>(glGetStringi(GL_EXTENSIONS,i));if(name&&std::strcmp(name,wanted)==0)return true;}
 return false;
}
bool prepareShadows(){
 if(shadowAttempted)return !shadowUnavailable&&shadowFramebuffer!=0;
 shadowAttempted=true;
 GLint textureSize=0,units=0;glGetIntegerv(GL_MAX_TEXTURE_SIZE,&textureSize);glGetIntegerv(GL_MAX_TEXTURE_IMAGE_UNITS,&units);
 if(textureSize<SHADOW_SIZE||units<2){disableShadows("GLES limits");return false;}
 shadowSceneProgram=program(sceneVertex,sceneFragment,true);shadowProgram=program(shadowVertex,shadowFragment);
 if(!shadowSceneProgram||!shadowProgram){disableShadows("shadow shader compilation/linking");return false;}
 glActiveTexture(GL_TEXTURE1);glGenTextures(1,&shadowTexture);glBindTexture(GL_TEXTURE_2D,shadowTexture);
 glTexStorage2D(GL_TEXTURE_2D,1,GL_DEPTH_COMPONENT24,SHADOW_SIZE,SHADOW_SIZE);
 glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
 glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
 glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_COMPARE_MODE,GL_COMPARE_REF_TO_TEXTURE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_COMPARE_FUNC,GL_LEQUAL);
 glGenFramebuffers(1,&shadowFramebuffer);glBindFramebuffer(GL_FRAMEBUFFER,shadowFramebuffer);
 glFramebufferTexture2D(GL_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_TEXTURE_2D,shadowTexture,0);
 const GLenum none=GL_NONE;glDrawBuffers(1,&none);glReadBuffer(GL_NONE);
 GLenum complete=glCheckFramebufferStatus(GL_FRAMEBUFFER),error=glGetError();glBindFramebuffer(GL_FRAMEBUFFER,0);glActiveTexture(GL_TEXTURE0);
 if(complete!=GL_FRAMEBUFFER_COMPLETE||error!=GL_NO_ERROR){disableShadows("depth framebuffer allocation/completeness");return false;}
 log("Mobile HD soft shadows ready: 1024 depth24, four filtered comparison taps, near-scene coverage");return true;
}
struct Vec3 {float x,y,z;};
float dot(Vec3 a,Vec3 b){return a.x*b.x+a.y*b.y+a.z*b.z;}
Vec3 normalize(Vec3 v){float r=1.0f/std::sqrt(dot(v,v));return {v.x*r,v.y*r,v.z*r};}
Vec3 cross(Vec3 a,Vec3 b){return {a.y*b.z-a.z*b.y,a.z*b.x-a.x*b.z,a.x*b.y-a.y*b.x};}
void frameMatrices(const jint* p,float* camera,float* light){
 // Match the capture's fixed-point camera basis, including its truncation.
 constexpr double angle=6.28318530717958647692/2048.0;
 double pitch=(p[9]&2047)*angle,yaw=((p[9]>>11)&2047)*angle;
 float ps=static_cast<int>(65536.0*std::sin(pitch))/65536.0f,pc=static_cast<int>(65536.0*std::cos(pitch))/65536.0f;
 float ys=static_cast<int>(65536.0*std::sin(yaw))/65536.0f,yc=static_cast<int>(65536.0*std::cos(yaw))/65536.0f;
 const float basis[9]={yc,0,ys,ys*ps,pc,-yc*ps,-ys*pc,ps,yc*pc};std::memcpy(camera,basis,sizeof(basis));
 Vec3 sun=normalize({-.45f,-.82f,-.35f}),right=normalize({sun.z,0,-sun.x}),up=cross(sun,right),away={-sun.x,-sun.y,-sun.z};
 Vec3 origin={static_cast<float>(p[12]),static_cast<float>(p[13]),static_cast<float>(p[14])};
 // Fixed coverage/focus avoids scene-bound breathing. Snap in WORLD light space
 // so the sun and texel grid remain world-oriented as the camera turns/moves.
 Vec3 focus={origin.x+camera[6]*1800.0f,origin.y+camera[7]*1800.0f,origin.z+camera[8]*1800.0f};
 constexpr float texel=2.0f*SHADOW_EXTENT/SHADOW_SIZE;
 float cx=std::floor(dot(right,focus)/texel+.5f)*texel,cy=std::floor(dot(up,focus)/texel+.5f)*texel,cz=dot(away,focus);
 const float matrix[16]={right.x/SHADOW_EXTENT,up.x/SHADOW_EXTENT,away.x/SHADOW_DEPTH,0,
 right.y/SHADOW_EXTENT,up.y/SHADOW_EXTENT,away.y/SHADOW_DEPTH,0,
 right.z/SHADOW_EXTENT,up.z/SHADOW_EXTENT,away.z/SHADOW_DEPTH,0,
 (dot(right,origin)-cx)/SHADOW_EXTENT,(dot(up,origin)-cy)/SHADOW_EXTENT,(dot(away,origin)-cz)/SHADOW_DEPTH,1};
 std::memcpy(light,matrix,sizeof(matrix));
}
void matrixUniforms(GLuint p,const float* camera,const float* light){
 glUniformMatrix3fv(glGetUniformLocation(p,"cameraToWorld"),1,GL_FALSE,camera);
 glUniformMatrix4fv(glGetUniformLocation(p,"shadowMatrix"),1,GL_FALSE,light);
}
bool drawShadows(int vertices,const float* camera,const float* light){
 if(!prepareShadows())return false;
 glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D_ARRAY,atlas);
 glBindFramebuffer(GL_FRAMEBUFFER,shadowFramebuffer);glViewport(0,0,SHADOW_SIZE,SHADOW_SIZE);
 glDisable(GL_SCISSOR_TEST);glDisable(GL_BLEND);glDisable(GL_CULL_FACE);glEnable(GL_DEPTH_TEST);glDepthFunc(GL_LEQUAL);glDepthMask(GL_TRUE);
 glColorMask(GL_FALSE,GL_FALSE,GL_FALSE,GL_FALSE);glClearDepthf(1);glClear(GL_DEPTH_BUFFER_BIT);
 glEnable(GL_POLYGON_OFFSET_FILL);glPolygonOffset(1.5f,2.0f);glUseProgram(shadowProgram);
 matrixUniforms(shadowProgram,camera,light);glUniform1i(glGetUniformLocation(shadowProgram,"atlas"),0);glDrawArrays(GL_TRIANGLES,0,vertices);
 glDisable(GL_POLYGON_OFFSET_FILL);glColorMask(GL_TRUE,GL_TRUE,GL_TRUE,GL_TRUE);glBindFramebuffer(GL_FRAMEBUFFER,0);
 if(glGetError()!=GL_NO_ERROR){disableShadows("depth pass GPU error");return false;}return true;
}

bool javaError(JNIEnv* env) {
    if (!env->ExceptionCheck()) return false;
    env->ExceptionClear();
    return true;
}

template<class Ref>
Ref keepGlobal(JNIEnv* env, Ref local) {
    if (javaError(env) || !local) return nullptr;
    auto global = static_cast<Ref>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    return javaError(env) ? nullptr : global;
}

void releaseCapture(JNIEnv* env) {
    if (meshDirect) env->DeleteGlobalRef(meshDirect);
    if (textureDirect) env->DeleteGlobalRef(textureDirect);
    if (parameters) env->DeleteGlobalRef(parameters);
    if (bridge) env->DeleteGlobalRef(bridge);
    meshDirect = textureDirect = nullptr;
    parameters = nullptr;
    bridge = nullptr;
    std::vector<uint8_t>().swap(mesh);
    std::vector<uint8_t>().swap(readback);
}

bool methods(JNIEnv* env) {
    if (bridge) return true;
    bridge = keepGlobal(env, env->FindClass("net/runelite/standalone/MobileHdRenderer"));
    if (!bridge) return false;
    auto bind = [&](jmethodID& method, const char* name, const char* signature) {
        method = env->GetStaticMethodID(bridge, name, signature);
        return !javaError(env) && method;
    };
    if (!bind(copyGeometry, "copyGeometry", "(Ljava/nio/ByteBuffer;[IJ)I") ||
        !bind(copyTexture, "copyTexture", "(IILjava/nio/ByteBuffer;)I") ||
        !bind(failMethod, "fail", "(Ljava/lang/String;)V") ||
        !bind(presentedMethod, "presented", "(I)V") ||
        !bind(shadowsMethod, "configureShadows", "(Z)V")) {
        releaseCapture(env);
        return false;
    }
    parameters = keepGlobal(env, env->NewIntArray(METADATA));
    textureDirect = keepGlobal(env, env->NewDirectByteBuffer(texture.data(), texture.size()));
    if (parameters && textureDirect) return true;
    releaseCapture(env);
    return false;
}

void fail(JNIEnv* env, const char* reason) {
    log(reason);
    if (bridge && failMethod) {
        jstring message = env->NewStringUTF(reason);
        if (!javaError(env) && message) {
            env->CallStaticVoidMethod(bridge, failMethod, message);
            env->DeleteLocalRef(message);
            javaError(env);
        }
    }
}

struct Targets {
    GLuint draw = 0, color = 0, depth = 0, resolve = 0, resolveColor = 0;
    int width = 0, height = 0;

    void release() {
        glDeleteFramebuffers(1, &draw);
        glDeleteFramebuffers(1, &resolve);
        glDeleteRenderbuffers(1, &color);
        glDeleteRenderbuffers(1, &depth);
        glDeleteRenderbuffers(1, &resolveColor);
        *this = {};
    }

    bool allocate(int w, int h, bool multisample) {
        glGenFramebuffers(1, &draw);
        glBindFramebuffer(GL_FRAMEBUFFER, draw);
        auto attach = [=](GLuint& buffer, GLenum format, GLenum attachment, bool msaa) {
            glGenRenderbuffers(1, &buffer);
            glBindRenderbuffer(GL_RENDERBUFFER, buffer);
            if (msaa)
                glRenderbufferStorageMultisample(GL_RENDERBUFFER, 2, format, w, h);
            else
                glRenderbufferStorage(GL_RENDERBUFFER, format, w, h);
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, attachment, GL_RENDERBUFFER, buffer);
        };
        attach(color, GL_RGBA8, GL_COLOR_ATTACHMENT0, multisample);
        attach(depth, GL_DEPTH_COMPONENT24, GL_DEPTH_ATTACHMENT, multisample);
        const bool ready = draw && glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
        const bool good = glGetError() == GL_NO_ERROR;
        if (!ready || !good) return false;

        if (multisample) {
            glGenFramebuffers(1, &resolve);
            glBindFramebuffer(GL_FRAMEBUFFER, resolve);
            attach(resolveColor, GL_RGBA8, GL_COLOR_ATTACHMENT0, false);
            const bool ready = resolve && glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
            const bool good = glGetError() == GL_NO_ERROR;
            if (!ready || !good) return false;
        }

        width = w;
        height = h;
        return true;
    }

    bool resize(int w, int h) {
        if (width == w && height == h && draw) return true;
        release();
        GLint samples = 0, renderbufferSize = 0;
        glGetIntegerv(GL_MAX_SAMPLES, &samples);
        glGetIntegerv(GL_MAX_RENDERBUFFER_SIZE, &renderbufferSize);
        if (w > renderbufferSize || h > renderbufferSize || glGetError() != GL_NO_ERROR) return false;
        if (samples >= 2) {
            if (allocate(w, h, true)) return true;
            release();
            clearErrors();
        }
        return allocate(w, h, false);
    }

    void read(uint8_t* bytes) const {
        if (resolve) {
            glBindFramebuffer(GL_READ_FRAMEBUFFER, draw);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, resolve);
            glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL_COLOR_BUFFER_BIT, GL_NEAREST);
        }
        glBindFramebuffer(GL_READ_FRAMEBUFFER, resolve ? resolve : draw);
        glReadBuffer(GL_COLOR_ATTACHMENT0);
        glPixelStorei(GL_PACK_ALIGNMENT, 4);
        glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, bytes);
    }
} targets;

bool setMeshCapacity(JNIEnv* env, size_t bytes) {
    if (meshDirect && mesh.size() >= bytes) return true;
    if (meshDirect) env->DeleteGlobalRef(meshDirect);
    meshDirect = nullptr;
    mesh.resize(bytes);
    meshDirect = keepGlobal(env, env->NewDirectByteBuffer(mesh.data(), mesh.size()));
    return meshDirect != nullptr;
}

bool uploadTextures(JNIEnv* env, const jint* metadata) {
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D_ARRAY, atlas);
    if (sceneGeneration != metadata[8]) {
        uploadedLayers = 0;
        textureGeneration = -1;
        sceneGeneration = metadata[8];
    }
    if (textureGeneration == metadata[11]) return true;

    if (metadata[10] < uploadedLayers) uploadedLayers = 0;
    for (; uploadedLayers < metadata[10]; ++uploadedLayers) {
        int length = env->CallStaticIntMethod(bridge, copyTexture, uploadedLayers, metadata[8], textureDirect);
        if (javaError(env)) {
            fail(env, "Mobile HD texture capture failed");
            return false;
        }
        if (length != LAYER_BYTES) return false;
        glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0, 0, 0, uploadedLayers,
                       128, 128, 1, GL_RGBA, GL_UNSIGNED_BYTE, texture.data());
    }
    textureGeneration = metadata[11];
    glGenerateMipmap(GL_TEXTURE_2D_ARRAY);
    return true;
}

bool initializeGraphics() {
    const char* version = reinterpret_cast<const char*>(glGetString(GL_VERSION));
    if (version) log(version);
 sceneProgram=program(sceneVertex,sceneFragment);uiProgram=program(uiVertex,uiFragment);if(!sceneProgram||!uiProgram)return false;
 glGenVertexArrays(1,&vao);glGenBuffers(1,&vbo);glBindVertexArray(vao);glBindBuffer(GL_ARRAY_BUFFER,vbo);
 const int size[4]={3,4,3,3},offset[4]={0,3,7,10};for(int i=0;i<4;i++){glEnableVertexAttribArray(i);glVertexAttribPointer(i,size[i],GL_FLOAT,GL_FALSE,13*sizeof(float),reinterpret_cast<void*>(offset[i]*sizeof(float)));}
 glGenTextures(1,&uiTexture);glBindTexture(GL_TEXTURE_2D,uiTexture);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
 glGenTextures(1,&atlas);glBindTexture(GL_TEXTURE_2D_ARRAY,atlas);glTexStorage3D(GL_TEXTURE_2D_ARRAY,8,GL_RGBA8,128,128,256);glTexParameteri(GL_TEXTURE_2D_ARRAY,GL_TEXTURE_MIN_FILTER,GL_LINEAR_MIPMAP_LINEAR);glTexParameteri(GL_TEXTURE_2D_ARRAY,GL_TEXTURE_MAG_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D_ARRAY,GL_TEXTURE_WRAP_S,GL_REPEAT);glTexParameteri(GL_TEXTURE_2D_ARRAY,GL_TEXTURE_WRAP_T,GL_REPEAT);
 // Do not hide mandatory allocation errors when trying optional filtering.
 if(glGetError()!=GL_NO_ERROR)return false;
 // Restrict anisotropy to a conservative 4x, only when actually advertised.
 bool anisotropy=extension("GL_EXT_texture_filter_anisotropic")||extension("GL_ARB_texture_filter_anisotropic");
 if(glGetError()!=GL_NO_ERROR){log("Mobile HD optional filtering query failed; retaining trilinear filtering");clearErrors();}
 else if(anisotropy){
  constexpr GLenum maxAnisotropy=0x84FF,textureAnisotropy=0x84FE;GLfloat limit=1;glGetFloatv(maxAnisotropy,&limit);
  if(glGetError()!=GL_NO_ERROR){log("Mobile HD anisotropy limit query failed; retaining trilinear filtering");clearErrors();}
  else{
   glTexParameterf(GL_TEXTURE_2D_ARRAY,textureAnisotropy,std::min(4.0f,std::max(1.0f,limit)));
   if(glGetError()!=GL_NO_ERROR){log("Mobile HD anisotropy unavailable; retaining trilinear filtering");clearErrors();}
   else log("Mobile HD cache texture filtering: trilinear and supported anisotropy up to 4x");
  }
 }

    uploadedLayers = 0;
    textureGeneration = sceneGeneration = -1;
    gpuReady = glGetError() == GL_NO_ERROR;
    return gpuReady;
}

void clearGraphicsState() {
    targets = {};
    sceneProgram = uiProgram = vbo = vao = uiTexture = atlas = 0;
    shadowSceneProgram = shadowProgram = shadowFramebuffer = shadowTexture = 0;
    ultra::framebuffer = ultra::color = ultra::depth = ultra::resolve = 0;
    ultra::surface[0] = ultra::surface[1] = 0;
    ultra::width = ultra::height = 0;
    ultra::failed = false;
    shadowAttempted = shadowUnavailable = gpuReady = false;
    uiWidth = uiHeight = 0;
}

void releaseGraphics() {
    if (enterMobileHdContext(false)) {
        ultra::release();
        releaseShadows();
        targets.release();
        if (sceneProgram) glDeleteProgram(sceneProgram);
        if (uiProgram) glDeleteProgram(uiProgram);
        glDeleteBuffers(1, &vbo);
        glDeleteVertexArrays(1, &vao);
        glDeleteTextures(1, &uiTexture);
        glDeleteTextures(1, &atlas);
        leaveMobileHdContext();
    }
    destroyMobileHdContext();
    clearGraphicsState();
}

class ContextScope {
    bool current;
public:
    ContextScope() : current(enterMobileHdContext(true)) {}
    ~ContextScope() { leave(); }
    bool ready() const { return current; }
    void leave() {
        if (current) {
            leaveMobileHdContext();
            current = false;
        }
    }
};

void drawFrame(const uint8_t* ui, int width, int height, const jint* p, bool& softShadows) {
    float camera[9], light[16];
    frameMatrices(p, camera, light);
    softShadows = p[15] != 0 && drawShadows(p[0], camera, light);
    if (!ultra::options && ultra::framebuffer) ultra::release();
    const bool premium = ultra::begin(width, height, softShadows);
    if (!premium) glBindFramebuffer(GL_FRAMEBUFFER, targets.draw);

    glViewport(0, 0, width, height);
    glDisable(GL_SCISSOR_TEST);
    glClearColor(.46f, .49f, .50f, 1);
    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
    glViewport(p[1], height - p[2] - p[4], p[3], p[4]);
    glEnable(GL_DEPTH_TEST);
    glDepthFunc(GL_LEQUAL);
    glDisable(GL_CULL_FACE);
    glEnable(GL_BLEND);
    glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);

    const GLuint scene = premium ? ultra::surface[softShadows] : softShadows ? shadowSceneProgram : sceneProgram;
    glUseProgram(scene);
    if (premium) glUniform1i(glGetUniformLocation(scene, "settings"), ultra::options);
    matrixUniforms(scene, camera, light);
    glUniform3f(glGetUniformLocation(scene, "projection"), p[3], p[4], p[7]);
    glUniform2f(glGetUniformLocation(scene, "center"), p[5], p[6]);
    glUniform1i(glGetUniformLocation(scene, "atlas"), 0);
    if (softShadows) {
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, shadowTexture);
        glUniform1i(glGetUniformLocation(scene, "shadowMap"), 1);
        glActiveTexture(GL_TEXTURE0);
    }

    glDepthMask(GL_TRUE);
    glUniform1i(glGetUniformLocation(scene, "phase"), 0);
    glDrawArrays(GL_TRIANGLES, 0, p[0]);
    glDepthMask(GL_FALSE);
    glUniform1i(glGetUniformLocation(scene, "phase"), 1);
    glDrawArrays(GL_TRIANGLES, 0, p[0]);
    glDepthMask(GL_TRUE);
    if (premium) ultra::finish(static_cast<float>(p[7]), targets.draw);

    glDisable(GL_DEPTH_TEST);
    glViewport(0, 0, width, height);
    glUseProgram(uiProgram);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, uiTexture);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
    if (width != uiWidth || height != uiHeight) {
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, ui);
        uiWidth = width;
        uiHeight = height;
    } else {
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, ui);
    }
    glUniform1i(glGetUniformLocation(uiProgram, "image"), 0);
    glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
    glDrawArrays(GL_TRIANGLE_FAN, 0, 4);
}

IosMobileHdFrame composeFrame(JNIEnv* env, uint8_t* bgra, size_t capacity, int width, int height, int64_t sequence) {
    const size_t frameBytes = MobileHdPixels::length(width, height);
    if (!bgra || !frameBytes || capacity < frameBytes) return IosMobileHdFrame::Hold;

    const jint bytes = env->CallStaticIntMethod(bridge, copyGeometry, nullptr, parameters, static_cast<jlong>(sequence));
    if (javaError(env)) {
        fail(env, "Mobile HD scene capture failed");
        return IosMobileHdFrame::Hold;
    }
    if (!bytes) {
        iosMobileHdRelease(env);
        return IosMobileHdFrame::Classic;
    }
    if (bytes < 0) return IosMobileHdFrame::Hold;
    if (bytes > MAX_VERTICES * VERTEX_BYTES || !setMeshCapacity(env, bytes)) {
        fail(env, "Mobile HD scene buffer exceeds the available capacity");
        return IosMobileHdFrame::Hold;
    }
    const jint copied = env->CallStaticIntMethod(bridge, copyGeometry, meshDirect, parameters, static_cast<jlong>(sequence));
    if (javaError(env)) {
        fail(env, "Mobile HD geometry copy failed");
        return IosMobileHdFrame::Hold;
    }
    if (copied != bytes) return IosMobileHdFrame::Hold;

    jint p[METADATA]{};
    env->GetIntArrayRegion(parameters, 0, METADATA, p);
    if (javaError(env) || p[0] < 1 || p[0] > MAX_VERTICES || p[3] < 1 || p[4] < 1 ||
        p[7] < 1 || p[10] < 0 || p[10] > 256 || bytes != p[0] * VERTEX_BYTES ||
        p[1] < -width || p[1] > width || p[2] < -height || p[2] > height ||
        p[3] > 2 * width || p[4] > 2 * height) {
        fail(env, "Mobile HD scene metadata is invalid");
        return IosMobileHdFrame::Hold;
    }

    readback.resize(frameBytes);
    ContextScope context;
    auto gpuFailed = [&](const char* reason) {
        context.leave();
        releaseGraphics();
        fail(env, reason);
        return IosMobileHdFrame::Hold;
    };

    if (!context.ready() || (!gpuReady && !initializeGraphics()) || !targets.resize(width, height))
        return gpuFailed("This device could not initialize the Mobile HD GLES 3 renderer");

    if (!uploadTextures(env, p)) return IosMobileHdFrame::Hold;
    glBindVertexArray(vao);
    glBindBuffer(GL_ARRAY_BUFFER, vbo);
    glBufferData(GL_ARRAY_BUFFER, bytes, mesh.data(), GL_STREAM_DRAW);
    if (glGetError() != GL_NO_ERROR) return gpuFailed("Mobile HD scene upload failed");

    bool softShadows = false;
    drawFrame(bgra, width, height, p, softShadows);
    if (glGetError() != GL_NO_ERROR) return gpuFailed("Mobile HD scene or UI pass failed");
    targets.read(readback.data());
    if (glGetError() != GL_NO_ERROR) return gpuFailed("Mobile HD frame resolve or readback failed");

    if (!MobileHdPixels::fromOpenGl(readback.data(), readback.size(), bgra, capacity, width, height))
        return IosMobileHdFrame::Hold;
    env->CallStaticVoidMethod(bridge, presentedMethod, static_cast<jint>(p[15] == 0 ? 0 : softShadows ? 1 : -1));
    javaError(env);
    return IosMobileHdFrame::Composed;
}
}

IosMobileHdFrame iosMobileHdCompose(JNIEnv* env, uint8_t* bgra, size_t capacity,
                                  int width, int height, int64_t sequence) {
    if (!env || !methods(env)) {
        log("Native Mobile HD bridge not available");
        return IosMobileHdFrame::Classic;
    }
    try {
        return composeFrame(env, bgra, capacity, width, height, sequence);
    } catch (const std::bad_alloc&) {
        releaseGraphics();
        fail(env, "Mobile HD could not allocate frame storage");
        return IosMobileHdFrame::Hold;
    }
}

void iosMobileHdSetUltraOptions(int mask) {
    ultra::options = mask;
    ultra::failed = false;
}

bool iosMobileHdSetShadows(JNIEnv* env, bool enabled) {
    if (!env || !methods(env)) return false;
    env->CallStaticVoidMethod(bridge, shadowsMethod, enabled ? JNI_TRUE : JNI_FALSE);
    return !javaError(env);
}

void iosMobileHdRelease(JNIEnv* env) {
    if (gpuReady || sceneProgram || vao) releaseGraphics();
    if (env) releaseCapture(env);
}
