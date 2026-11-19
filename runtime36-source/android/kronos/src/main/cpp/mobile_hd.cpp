#include "mobile_hd.h"
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <android/log.h>
#include <vector>
#include <string>
#include <cstring>
#include <cmath>
#include <algorithm>
#include <atomic>
#include "ultra_local_lights.h"

namespace {
constexpr int METADATA=ultra_lights::METADATA, SHADOW_SIZE=1024;
// Low two bits are the renderer. Bits 2 and 3 distinguish soft shadows
// from a failed optional shadow setup. Only successful swaps publish.
constexpr int FRAME_HD=1, FRAME_ULTRA=2, FRAME_ULTRA_FALLBACK=3;
constexpr int FRAME_SOFT_SHADOWS=4, FRAME_SHADOWS_UNAVAILABLE=8;
std::atomic<jint> displayedHdFrame{0};
constexpr float SHADOW_EXTENT=1536.0f,SHADOW_DEPTH=3072.0f;
EGLDisplay display=EGL_NO_DISPLAY;
EGLDisplay processDisplay=EGL_NO_DISPLAY;
EGLContext context=EGL_NO_CONTEXT;
EGLSurface target=EGL_NO_SURFACE;
uint64_t generation=0;
GLuint sceneProgram=0,uiProgram=0,vao=0,vbo=0,uiTexture=0,atlas=0;
GLuint shadowSceneProgram=0,shadowProgram=0,shadowFramebuffer=0,shadowTexture=0;
bool shadowAttempted=false,shadowUnavailable=false;
int uploadedLayers=0,textureGeneration=-1,sceneGeneration=-1;
jclass bridge=nullptr;
jmethodID copyGeometry=nullptr,copyTexture=nullptr,failMethod=nullptr,presentedMethod=nullptr;
jobject meshDirect=nullptr,textureDirect=nullptr;
jintArray parameters=nullptr;
std::vector<uint8_t> mesh(1024*1024),texture(128*128*4);
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
void log(const std::string& message){__android_log_print(ANDROID_LOG_WARN,"KronosHD","%s",message.c_str());}
GLuint shader(GLenum type,const char* source,bool shadows=false){
 GLuint s=glCreateShader(type);std::string expanded(source);
 if(shadows)expanded.insert(expanded.find('\n')+1,"#define MOBILE_HD_SHADOWS 1\n");
 const char* text=expanded.c_str();glShaderSource(s,1,&text,nullptr);glCompileShader(s);
 GLint ok=0;glGetShaderiv(s,GL_COMPILE_STATUS,&ok);if(!ok){char detail[2048]{};glGetShaderInfoLog(s,sizeof(detail),nullptr,detail);log(detail);glDeleteShader(s);return 0;}return s;
}
GLuint program(const char* vertex,const char* fragment,bool shadows=false){GLuint vs=shader(GL_VERTEX_SHADER,vertex,shadows),fs=shader(GL_FRAGMENT_SHADER,fragment,shadows);if(!vs||!fs){if(vs)glDeleteShader(vs);if(fs)glDeleteShader(fs);return 0;}GLuint p=glCreateProgram();glAttachShader(p,vs);glAttachShader(p,fs);glLinkProgram(p);glDeleteShader(vs);glDeleteShader(fs);GLint ok=0;glGetProgramiv(p,GL_LINK_STATUS,&ok);if(!ok){char detail[2048]{};glGetProgramInfoLog(p,sizeof(detail),nullptr,detail);log(detail);glDeleteProgram(p);return 0;}return p;}
void clearErrors(){for(int i=0;i<16&&glGetError()!=GL_NO_ERROR;i++){}}
#include "ultra.h"
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
void shadowMatrix(const jint* p,const float* camera,Vec3 sun,float focusDistance,
                  float extent,float depthExtent,float* light){
 Vec3 right=normalize({sun.z,0,-sun.x}),up=cross(sun,right),away={-sun.x,-sun.y,-sun.z};
 Vec3 origin={static_cast<float>(p[12]),static_cast<float>(p[13]),static_cast<float>(p[14])};
 // Snap the center in absolute world light space. Moving the camera only shifts
 // the map by whole texels; the projection of stationary geometry stays stable.
 Vec3 focus={origin.x+camera[6]*focusDistance,origin.y+camera[7]*focusDistance,origin.z+camera[8]*focusDistance};
 const float texel=2.0f*extent/SHADOW_SIZE;
 float cx=std::floor(dot(right,focus)/texel+.5f)*texel,cy=std::floor(dot(up,focus)/texel+.5f)*texel,cz=dot(away,focus);
 const float matrix[16]={right.x/extent,up.x/extent,away.x/depthExtent,0,
 right.y/extent,up.y/extent,away.y/depthExtent,0,
 right.z/extent,up.z/extent,away.z/depthExtent,0,
 (dot(right,origin)-cx)/extent,(dot(up,origin)-cy)/extent,(dot(away,origin)-cz)/depthExtent,1};
 std::memcpy(light,matrix,sizeof(matrix));
}
void frameMatrices(const jint* p,float* camera,float* light){
 // Match the capture's fixed-point camera basis, including its truncation.
 constexpr double angle=6.28318530717958647692/2048.0;
 double pitch=(p[9]&2047)*angle,yaw=((p[9]>>11)&2047)*angle;
 float ps=static_cast<int>(65536.0*std::sin(pitch))/65536.0f,pc=static_cast<int>(65536.0*std::cos(pitch))/65536.0f;
 float ys=static_cast<int>(65536.0*std::sin(yaw))/65536.0f,yc=static_cast<int>(65536.0*std::cos(yaw))/65536.0f;
 const float basis[9]={yc,0,ys,ys*ps,pc,-yc*ps,-ys*pc,ps,yc*pc};std::memcpy(camera,basis,sizeof(basis));
 shadowMatrix(p,camera,normalize({-.45f,-.82f,-.35f}),1800.0f,SHADOW_EXTENT,SHADOW_DEPTH,light);
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
#include "ultra_shadow_maps.h"
void fail(JNIEnv* e,const char* reason){log(reason);if(failMethod){jstring text=e->NewStringUTF(reason);e->CallStaticVoidMethod(bridge,failMethod,text);e->DeleteLocalRef(text);if(e->ExceptionCheck()){e->ExceptionDescribe();e->ExceptionClear();}}}
bool methods(JNIEnv* e){if(bridge)return true;jclass local=e->FindClass("net/runelite/standalone/MobileHdRenderer");if(!local){e->ExceptionClear();return false;}copyGeometry=e->GetStaticMethodID(local,"copyGeometry","(Ljava/nio/ByteBuffer;[IJ)I");copyTexture=e->GetStaticMethodID(local,"copyTexture","(IILjava/nio/ByteBuffer;)I");failMethod=e->GetStaticMethodID(local,"fail","(Ljava/lang/String;)V");presentedMethod=e->GetStaticMethodID(local,"presented","(I)V");if(e->ExceptionCheck()||!copyGeometry||!copyTexture||!failMethod||!presentedMethod){e->ExceptionDescribe();e->ExceptionClear();e->DeleteLocalRef(local);return false;}bridge=(jclass)e->NewGlobalRef(local);e->DeleteLocalRef(local);jintArray array=e->NewIntArray(METADATA);parameters=(jintArray)e->NewGlobalRef(array);e->DeleteLocalRef(array);jobject buf=e->NewDirectByteBuffer(texture.data(),texture.size());textureDirect=e->NewGlobalRef(buf);e->DeleteLocalRef(buf);return parameters&&textureDirect;}
bool init(ANativeWindow* window){
 if(processDisplay==EGL_NO_DISPLAY){EGLDisplay selected=eglGetDisplay(EGL_DEFAULT_DISPLAY);if(selected==EGL_NO_DISPLAY||!eglInitialize(selected,nullptr,nullptr))return false;processDisplay=selected;}
 display=processDisplay;
 EGLint attributes[]={EGL_SURFACE_TYPE,EGL_WINDOW_BIT,EGL_RENDERABLE_TYPE,EGL_OPENGL_ES3_BIT,EGL_RED_SIZE,8,EGL_GREEN_SIZE,8,EGL_BLUE_SIZE,8,EGL_ALPHA_SIZE,8,EGL_DEPTH_SIZE,24,EGL_SAMPLE_BUFFERS,1,EGL_SAMPLES,2,EGL_NONE};
 EGLConfig config;EGLint count=0;
 if(!eglChooseConfig(display,attributes,&config,1,&count)||count==0){attributes[15]=0;attributes[17]=0;if(!eglChooseConfig(display,attributes,&config,1,&count)||count==0)return false;}
 EGLint format=0;eglGetConfigAttrib(display,config,EGL_NATIVE_VISUAL_ID,&format);
 ANativeWindow_setBuffersGeometry(window,0,0,format);
 const EGLint contextAttributes[]={EGL_CONTEXT_CLIENT_VERSION,3,EGL_NONE};
 context=eglCreateContext(display,config,EGL_NO_CONTEXT,contextAttributes);target=eglCreateWindowSurface(display,config,window,nullptr);
 if(context==EGL_NO_CONTEXT||target==EGL_NO_SURFACE||!eglMakeCurrent(display,target,target,context))return false;
 const char* version=reinterpret_cast<const char*>(glGetString(GL_VERSION));log(version?version:"No GL version");
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
 uploadedLayers=0;textureGeneration=-1;sceneGeneration=-1;shadowAttempted=false;shadowUnavailable=false;eglSwapInterval(display,1);return glGetError()==GL_NO_ERROR;
}
}
void hdUltraOptions(int mask){ultra::options=mask;ultra::failed=false;}
extern "C" JNIEXPORT jint JNICALL Java_local_kronos_mobile_MainActivity_customHdFrame(JNIEnv*,jclass) {
 return displayedHdFrame.load(std::memory_order_relaxed);
}
void hdReleaseSurface(){
 displayedHdFrame.store(0,std::memory_order_relaxed);
 // EGL_DEFAULT_DISPLAY is shared with genuine117 in this process. Release only
 // this presenter's resources: eglTerminate would also invalidate the other context.
 // The task-close policy ends our process, which owns the display connection lifetime.
 if(display!=EGL_NO_DISPLAY){if(context!=EGL_NO_CONTEXT&&target!=EGL_NO_SURFACE)eglMakeCurrent(display,target,target,context);ultra::release();ultra_shadows::release();releaseShadows();if(sceneProgram)glDeleteProgram(sceneProgram);if(uiProgram)glDeleteProgram(uiProgram);if(vbo)glDeleteBuffers(1,&vbo);if(vao)glDeleteVertexArrays(1,&vao);if(uiTexture)glDeleteTextures(1,&uiTexture);if(atlas)glDeleteTextures(1,&atlas);eglMakeCurrent(display,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);if(target!=EGL_NO_SURFACE)eglDestroySurface(display,target);if(context!=EGL_NO_CONTEXT)eglDestroyContext(display,context);}
 display=EGL_NO_DISPLAY;context=EGL_NO_CONTEXT;target=EGL_NO_SURFACE;sceneProgram=uiProgram=vbo=vao=uiTexture=atlas=0;generation=0;shadowAttempted=false;shadowUnavailable=false;
}
HdPresentation hdPresent(JNIEnv* e,ANativeWindow* window,uint64_t surfaceGeneration,const uint32_t* ui,int width,int height,long long frameSequence){
 if(!methods(e))return HdPresentation::CLASSIC;
 jint bytes=e->CallStaticIntMethod(bridge,copyGeometry,nullptr,parameters,static_cast<jlong>(frameSequence));
 if(e->ExceptionCheck()){e->ExceptionDescribe();e->ExceptionClear();fail(e,"Scene capture failed");return HdPresentation::HOLD;}
 if(bytes==0){hdReleaseSurface();return HdPresentation::CLASSIC;}if(bytes<0)return HdPresentation::HOLD;
 if(bytes>600000*13*4){fail(e,"Scene buffer exceeds safe limit");return HdPresentation::HOLD;}
 if(static_cast<size_t>(bytes)>mesh.size()||!meshDirect){if(meshDirect)e->DeleteGlobalRef(meshDirect);mesh.resize(bytes);meshDirect=e->NewGlobalRef(e->NewDirectByteBuffer(mesh.data(),mesh.size()));}
 jint copied=e->CallStaticIntMethod(bridge,copyGeometry,meshDirect,parameters,static_cast<jlong>(frameSequence));if(copied!=bytes)return HdPresentation::HOLD;
 jint p[METADATA]{};e->GetIntArrayRegion(parameters,0,METADATA,p);if(p[0]<1||p[0]>600000||p[3]<1||p[4]<1||width<1||height<1||bytes!=p[0]*13*4)return HdPresentation::HOLD;
 if(surfaceGeneration!=generation||target==EGL_NO_SURFACE){hdReleaseSurface();if(!init(window)){hdReleaseSurface();fail(e,"This device could not initialize GLES HD");return HdPresentation::HOLD;}generation=surfaceGeneration;}
 if(!eglMakeCurrent(display,target,target,context)){fail(e,"GPU surface was lost");hdReleaseSurface();return HdPresentation::HOLD;}
 glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D_ARRAY,atlas);
 if(sceneGeneration!=p[8]){uploadedLayers=0;textureGeneration=-1;sceneGeneration=p[8];}
 if(textureGeneration!=p[11]){if(p[10]<uploadedLayers)uploadedLayers=0;for(int layer=uploadedLayers;layer<p[10]&&layer<256;layer++){int n=e->CallStaticIntMethod(bridge,copyTexture,layer,p[8],textureDirect);if(e->ExceptionCheck()){e->ExceptionDescribe();e->ExceptionClear();fail(e,"Texture upload failed");return HdPresentation::HOLD;}if(n!=128*128*4)return HdPresentation::HOLD;glTexSubImage3D(GL_TEXTURE_2D_ARRAY,0,0,0,layer,128,128,1,GL_RGBA,GL_UNSIGNED_BYTE,texture.data());}uploadedLayers=p[10];textureGeneration=p[11];glGenerateMipmap(GL_TEXTURE_2D_ARRAY);}
 glBindVertexArray(vao);glBindBuffer(GL_ARRAY_BUFFER,vbo);glBufferData(GL_ARRAY_BUFFER,bytes,mesh.data(),GL_STREAM_DRAW);
 // Mandatory upload errors retain the original Classic fallback. Optional shadow
 // errors are checked/cleared separately and select the shadow-free scene shader.
 if(glGetError()!=GL_NO_ERROR){fail(e,"GPU scene upload failed");hdReleaseSurface();return HdPresentation::HOLD;}
 float camera[9],light[16];frameMatrices(p,camera,light);
 EGLint sw=0,sh=0;eglQuerySurface(display,target,EGL_WIDTH,&sw);eglQuerySurface(display,target,EGL_HEIGHT,&sh);
 if(!ultra::options && ultra::framebuffer)ultra::release();
 const bool night=(ultra::options&256)!=0;
 const Vec3 source=ultra_shadows::direction(night);
 const Vec3 haze=night ? Vec3{.0153f,.0245f,.0470f} : Vec3{.274f,.367f,.462f};
 const bool tryUltra=ultra::options!=0 && !ultra::failed;
 bool softShadows=false;
 float nearLight[16]{},farLight[16]{};
 // Choose and draw the caster pass first. Ultra and the original HD shader
 // must each see a map produced from their own lighting direction and coverage.
 if(tryUltra && p[15]!=0) {
  softShadows=ultra_shadows::draw(p,camera,source,(ultra::options&128)!=0,nearLight,farLight);
 } else if(ultra_shadows::requestedCascades) {
  ultra_shadows::release();
 }
 const bool premium=tryUltra && ultra::begin(sw,sh,softShadows);
 if(!premium){
  if(ultra_shadows::requestedCascades)ultra_shadows::release();
  softShadows=p[15]!=0 && drawShadows(p[0],camera,light);
  glBindFramebuffer(GL_FRAMEBUFFER,0);
 }
 glViewport(0,0,sw,sh);glDisable(GL_SCISSOR_TEST);
 if(premium)glClearColor(.5f*haze.x,.5f*haze.y,.5f*haze.z,1);
 else glClearColor(.46f,.49f,.50f,1);
 glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);
 const int viewportX=p[1]*sw/width,viewportY=sh-(p[2]+p[4])*sh/height;
 const int viewportWidth=p[3]*sw/width,viewportHeight=p[4]*sh/height;
 glViewport(viewportX,viewportY,viewportWidth,viewportHeight);
 glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D_ARRAY,atlas);
 glEnable(GL_DEPTH_TEST);glDepthFunc(GL_LEQUAL);
 // Cache models can pair coincident faces for the outside and lining of cloth.
 // The native client projects front faces counterclockwise in GLES space.
 // Terrain and scenery can rely on both sides. Ultra's fragment shader selects the
 // cache-facing side only on real player triangles, including both sides of a cape.
 if(premium)glFrontFace(GL_CCW);
 glDisable(GL_CULL_FACE);
 glEnable(GL_BLEND);glBlendFuncSeparate(GL_SRC_ALPHA,GL_ONE_MINUS_SRC_ALPHA,GL_ONE,GL_ONE_MINUS_SRC_ALPHA);
 GLuint scene=premium?ultra::surface[softShadows]:softShadows?shadowSceneProgram:sceneProgram;
 glUseProgram(scene);
 if(premium){
  glUniform1i(glGetUniformLocation(scene,"settings"),ultra::options);
  glUniform3f(glGetUniformLocation(scene,"lightDirection"),source.x,source.y,source.z);
  glUniform3f(glGetUniformLocation(scene,"hazeColor"),haze.x,haze.y,haze.z);
  ultra_lights::bind(scene,p);
 }
 matrixUniforms(scene,camera,premium?nearLight:light);
 glUniform3f(glGetUniformLocation(scene,"projection"),p[3],p[4],p[7]);glUniform2f(glGetUniformLocation(scene,"center"),p[5],p[6]);glUniform1i(glGetUniformLocation(scene,"atlas"),0);
 if(softShadows){
  if(premium)ultra_shadows::bind(scene,farLight,night);
  else{glActiveTexture(GL_TEXTURE1);glBindTexture(GL_TEXTURE_2D,shadowTexture);glUniform1i(glGetUniformLocation(scene,"shadowMap"),1);}
  glActiveTexture(GL_TEXTURE0);
 }
 glDepthMask(GL_TRUE);glUniform1i(glGetUniformLocation(scene,"phase"),0);glDrawArrays(GL_TRIANGLES,0,p[0]);glDepthMask(GL_FALSE);glUniform1i(glGetUniformLocation(scene,"phase"),1);glDrawArrays(GL_TRIANGLES,0,p[0]);glDepthMask(GL_TRUE);
 if(premium){
  glDisable(GL_CULL_FACE);
  const float sx=float(viewportWidth)/p[3],sy=float(viewportHeight)/p[4];
  ultra::finish(p[7]*sx,p[7]*sy,viewportX+p[5]*sx,viewportY+viewportHeight-p[6]*sy);
 }
 glActiveTexture(GL_TEXTURE0);
 glDisable(GL_DEPTH_TEST);glViewport(0,0,sw,sh);glUseProgram(uiProgram);glBindTexture(GL_TEXTURE_2D,uiTexture);glPixelStorei(GL_UNPACK_ALIGNMENT,4);glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA,width,height,0,GL_RGBA,GL_UNSIGNED_BYTE,ui);glUniform1i(glGetUniformLocation(uiProgram,"image"),0);glBlendFunc(GL_ONE,GL_ONE_MINUS_SRC_ALPHA);glDrawArrays(GL_TRIANGLE_FAN,0,4);
 if(glGetError()!=GL_NO_ERROR||!eglSwapBuffers(display,target)){fail(e,"GPU frame presentation failed");hdReleaseSurface();return HdPresentation::HOLD;}
 const int mode=premium?FRAME_ULTRA:ultra::options?FRAME_ULTRA_FALLBACK:FRAME_HD;
 const int shadows=p[15]==0?0:softShadows?FRAME_SOFT_SHADOWS:FRAME_SHADOWS_UNAVAILABLE;
 displayedHdFrame.store(mode|shadows,std::memory_order_relaxed);
 e->CallStaticVoidMethod(bridge,presentedMethod,static_cast<jint>(p[15]==0?0:softShadows?1:-1));if(e->ExceptionCheck()){e->ExceptionDescribe();e->ExceptionClear();}
 return HdPresentation::PRESENTED;
}
