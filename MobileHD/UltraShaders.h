// Independent Ultra scene shaders, sharing the code22 vertex layout.
const char* ultraVertex=R"(#version 300 es
precision highp float;
layout(location=0)in vec3 p;layout(location=1)in vec4 c;
layout(location=2)in vec3 n;layout(location=3)in vec3 uv;
uniform vec3 projection;uniform vec2 center;
uniform mat3 cameraToWorld;uniform mat4 shadowMatrix;
out vec4 color;out vec3 normal;out vec3 tex;out float distanceToCamera;
#ifdef MOBILE_HD_SHADOWS
out vec3 shadowPosition;
#endif
out vec3 eyeVector;
void main(){float near=50.0,far=6144.0;float w=p.z;
gl_Position=vec4(2.0*p.x*projection.z/projection.x+(2.0*center.x/projection.x-1.0)*w,
-2.0*p.y*projection.z/projection.y+(1.0-2.0*center.y/projection.y)*w,
(far+near)/(far-near)*w-2.0*far*near/(far-near),w);
vec3 worldRelative=cameraToWorld*p;
eyeVector=-worldRelative;
color=c;normal=n;tex=uv;distanceToCamera=length(worldRelative.xz);
#ifdef MOBILE_HD_SHADOWS
shadowPosition=(shadowMatrix*vec4(worldRelative,1.0)).xyz;
#endif
})";
const char* ultraFragment=R"(#version 300 es
precision highp float;precision highp sampler2DArray;
in vec4 color;in vec3 normal;in vec3 tex;in float distanceToCamera;
uniform sampler2DArray atlas;uniform int phase;out vec4 result;
in vec3 eyeVector;uniform int settings;
#ifdef MOBILE_HD_SHADOWS
precision highp sampler2DShadow;
in vec3 shadowPosition;uniform sampler2DShadow shadowMap;
float sunVisibility(float ndl){
if(ndl<=0.0)return 1.0;
vec3 q=shadowPosition*0.5+0.5;
if(q.z<=0.0||q.z>=1.0||any(lessThanEqual(q.xy,vec2(0.0)))||any(greaterThanEqual(q.xy,vec2(1.0))))return 1.0;
float bias=0.00006+0.00025*(1.0-ndl);
float visibility=0.0;
int count=(settings&128)!=0?12:4;
for(int i=0;i<12;i++){
 if(i>=count)break;
 float angle=float(i)*2.399963;
 vec2 offset=vec2(cos(angle),sin(angle))*sqrt((float(i)+.5)/float(count));
 visibility+=texture(shadowMap,vec3(q.xy+offset*(count==12?2.1:.9)/1024.0,q.z-bias));
}
float border=smoothstep(0.82,0.98,max(abs(shadowPosition.x),abs(shadowPosition.y)));
float farFade=smoothstep(2300.0,3300.0,distanceToCamera);
return mix(visibility/float(count),1.0,max(border,farFade));
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
float sunlight=sunVisibility(ndl);
vec3 illumination=ambient+vec3(0.70,0.63,0.518)*ndl*sunlight;
vec3 linear=pow(max(base.rgb,vec3(0.0)),vec3(2.2))*illumination;

 vec3 viewDir=normalize(eyeVector);
 vec3 halfDir=normalize(viewDir+normalize(vec3(-.45,-.82,-.35)));
 float chroma=max(max(base.r,base.g),base.b)-min(min(base.r,base.g),base.b);
 float hard=(settings&16)!=0 && tex.z<0.0 ? (1.0-smoothstep(.04,.19,chroma))*smoothstep(.07,.3,length(base.rgb)):0.0;
 float glint=pow(max(dot(N,halfDir),0.0),mix(12.0,48.0,hard));
 linear+=sunlight*ndl*glint*mix(.009,.23,hard)*mix(vec3(1),base.rgb,.4);
 linear+=pow(1.0-clamp(dot(N,viewDir),0.0,1.0),4.0)*vec3(.025,.034,.048)*sky;
 
// A neutral, white-preserving shoulder; cache hue and dark detail survive.
linear=linear*1.12/(vec3(1.0)+linear*0.12);
vec3 lit=pow(max(linear,vec3(0.0)),vec3(1.0/2.2));
float fog=((settings&32)!=0?smoothstep(2000.0,6100.0,distanceToCamera):0.0);
result=vec4(mix(lit,vec3(0.46,0.49,0.50),fog),base.a);})";
