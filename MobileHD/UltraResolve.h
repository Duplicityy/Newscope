// Render-thread owned scene pass. UI is composited after resolve.
#pragma once
namespace ultra {
#include "UltraShaders.h"
static int options=0;
static GLuint framebuffer=0, color=0, depth=0, resolve=0, surface[2]={};
static int width=0,height=0;
static bool failed=false;
const char* vertex=R"(#version 300 es
out vec2 uv;
void main(){
 vec2 p=vec2(gl_VertexID==1?3.0:-1.0,gl_VertexID==2?3.0:-1.0);
 gl_Position=vec4(p,0,1); uv=p*.5+.5;
})";
const char* fragment=R"(#version 300 es
precision highp float;
in vec2 uv;
uniform sampler2D scene, depthMap;
uniform vec2 pixel;
uniform float focal;
uniform int settings;
out vec4 result;
float distanceAt(vec2 coord){
 float d=texture(depthMap,coord).r;
 return 307200.0/(6144.0-d*6094.0);
}
float luma(vec3 c){return dot(c,vec3(.2126,.7152,.0722));}
void main(){
 vec3 c=texture(scene,uv).rgb;
 float z=distanceAt(uv);
 vec2 slope=clamp(vec2(dFdx(z),dFdy(z)),vec2(-3.0),vec2(3.0));
 int quality=settings&3;
 if(quality>0 && z<6000.0){
  float radius=clamp(48.0*focal/z,1.5,20.0);
  float occlusion=0.0;
  int count=quality*4;
  for(int i=0;i<12;i++){
   if(i>=count)break;
   float angle=float(i)*2.399963;
   vec2 dir=vec2(cos(angle),sin(angle));
   float r=sqrt((float(i)+.5)/float(count));
   float dz=z+dot(slope,dir*radius*r)-distanceAt(uv+pixel*dir*radius*r);
   occlusion+=smoothstep(2.0,15.0,dz)*(1.0-smoothstep(25.0,78.0,dz));
  }
  c*=1.0-.47*occlusion/float(count);
 }
 vec3 left=texture(scene,uv-vec2(pixel.x,0)).rgb,right=texture(scene,uv+vec2(pixel.x,0)).rgb;
 vec3 up=texture(scene,uv+vec2(0,pixel.y)).rgb,down=texture(scene,uv-vec2(0,pixel.y)).rgb;
 if((settings&4)!=0){
  float dx=abs(luma(right)-luma(left)),dy=abs(luma(up)-luma(down));
  vec2 along=dx>dy?vec2(0,pixel.y):vec2(pixel.x,0);
  vec3 aa=(texture(scene,uv+.5*along).rgb+texture(scene,uv-.5*along).rgb)*.5;
  c+=smoothstep(.05,.24,max(dx,dy))*(aa-texture(scene,uv).rgb)*.75;
 }
 if((settings&8)!=0){
  vec3 bloom=vec3(0);
  for(int i=0;i<6;i++){
   float a=float(i)*1.0472;
   vec3 s=texture(scene,uv+pixel*3.5*vec2(cos(a),sin(a))).rgb;
   bloom+=s*smoothstep(.68,.94,luma(s));
  }
  c+=bloom*.025;
 }
 result=vec4(clamp(c,0.0,1.0),1.0);
})";
void freeTargets(){
 glDeleteTextures(1,&color);glDeleteTextures(1,&depth);glDeleteFramebuffers(1,&framebuffer);
 framebuffer=color=depth=0;width=height=0;
}
void release(){
 freeTargets();if(resolve)glDeleteProgram(resolve);if(surface[0])glDeleteProgram(surface[0]);if(surface[1])glDeleteProgram(surface[1]);
 resolve=surface[0]=surface[1]=0;failed=false;
}
void texture2D(GLuint& id,GLenum format,int w,int h,GLenum filter){
 glGenTextures(1,&id);glBindTexture(GL_TEXTURE_2D,id);glTexStorage2D(GL_TEXTURE_2D,1,format,w,h);
 glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,filter);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,filter);
 glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
}
bool begin(int w,int h,bool shadows){
 if(!options||failed)return false;
 if(!resolve)resolve=program(vertex,fragment);
 if(!surface[shadows]){
  surface[shadows]=program(ultraVertex,ultraFragment,shadows);
 }
 if(width!=w||height!=h){
  freeTargets();texture2D(color,GL_RGBA8,w,h,GL_LINEAR);texture2D(depth,GL_DEPTH_COMPONENT24,w,h,GL_NEAREST);
  glGenFramebuffers(1,&framebuffer);glBindFramebuffer(GL_FRAMEBUFFER,framebuffer);
  glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,color,0);
  glFramebufferTexture2D(GL_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_TEXTURE_2D,depth,0);
  width=w;height=h;
 }else glBindFramebuffer(GL_FRAMEBUFFER,framebuffer);
 if(!resolve||!surface[shadows]||glCheckFramebufferStatus(GL_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE||glGetError()!=GL_NO_ERROR){
  glBindFramebuffer(GL_FRAMEBUFFER,0);release();failed=true;clearErrors();log("Ultra unavailable: using Mobile HD");return false;
 }
 return true;
}
void finish(float focal, GLuint destination){
 glBindFramebuffer(GL_FRAMEBUFFER,destination);glViewport(0,0,width,height);glDisable(GL_DEPTH_TEST);glDisable(GL_BLEND);glUseProgram(resolve);
 glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,color);glUniform1i(glGetUniformLocation(resolve,"scene"),0);
 glActiveTexture(GL_TEXTURE2);glBindTexture(GL_TEXTURE_2D,depth);glUniform1i(glGetUniformLocation(resolve,"depthMap"),2);
 glUniform2f(glGetUniformLocation(resolve,"pixel"),1.f/width,1.f/height);glUniform1f(glGetUniformLocation(resolve,"focal"),focal);
 glUniform1i(glGetUniformLocation(resolve,"settings"),options);glDrawArrays(GL_TRIANGLES,0,3);
 glActiveTexture(GL_TEXTURE0);glEnable(GL_BLEND);
}
}
