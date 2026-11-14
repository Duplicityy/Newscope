// Adapted from RuneMobile's jvm_bootstrap.cpp: dedicated HotSpot VM, multi-pass
// native preloading, and a SurfaceView CPU blit. See repository LICENSE.
#include <jni.h>
#include <android/native_window_jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <dirent.h>
#include <unistd.h>
#include <cstdio>
#include <thread>
#include <mutex>
#include <vector>
#include <deque>
#include <string>
#include <atomic>
#include <algorithm>
#include <chrono>
#include <cstdint>

static std::mutex surfaceLock, queueLock, statusLock;
static ANativeWindow* surface = nullptr;
static bool refreshSurface = true;
static uint64_t surfaceGeneration = 0;
static std::string statusText = "Ready";
static std::mutex editorLock;
static std::mutex interactionsLock;
static std::vector<jint> interactionsData;
static std::string editorText = "0\n0\n";
static std::atomic<int> frameWidth{765},frameHeight{503};
static std::atomic<bool> started{false}, foreground{true};
static std::string bootLog;
struct Input { int kind,x,y,value; std::string text; };
static std::deque<Input> queue;
static std::string str(JNIEnv* e,jstring s) { if (!s) return ""; const char* p=e->GetStringUTFChars(s,nullptr); std::string r=p; e->ReleaseStringUTFChars(s,p); return r; }
static void status(const std::string& s) {
    std::lock_guard<std::mutex> lock(statusLock);
    if(statusText==s)return;
    statusText=s;
    if(!bootLog.empty()) {
        if(FILE* f=fopen(bootLog.c_str(),"a")) {
            fprintf(f,"%s\n",s.c_str());
            fclose(f);
        }
    }
    __android_log_print(ANDROID_LOG_INFO,"KronosJVM","%s",s.c_str());
}
static void preload(const std::string& dir) {
    DIR* d=opendir(dir.c_str()); if(!d) return;
    std::vector<std::string> pending;
    while(auto* e=readdir(d)) { std::string n=e->d_name; if(n.size()>3 && n.substr(n.size()-3)==".so" && n!="libjvm.so") pending.push_back(dir+"/"+n); }
    closedir(d);
    while(!pending.empty()) { std::vector<std::string> remaining; for(auto& p:pending) if(!dlopen(p.c_str(),RTLD_NOW|RTLD_GLOBAL)) remaining.push_back(p); if(remaining.size()==pending.size()) break; pending.swap(remaining); }
}
static void run(std::string root,std::string host) {
    bootLog=root+"/boot-stages.log"; std::remove(bootLog.c_str()); status("Native worker started");
    // Persistent app-private log, never record input text or credentials.
    freopen((root+"/client.log").c_str(),"w",stdout);
    freopen((root+"/client-error.log").c_str(),"w",stderr);
    setvbuf(stdout,nullptr,_IOLBF,0); setvbuf(stderr,nullptr,_IOLBF,0);
    std::string jre=root+"/jvm/jre", lib=jre+"/lib";
    setenv("HOME",root.c_str(),1); setenv("TMPDIR",(root+"/tmp").c_str(),1);
    void* handle=dlopen((lib+"/server/libjvm.so").c_str(),RTLD_NOW|RTLD_GLOBAL);
    if(!handle) { status(std::string("Cannot load Java runtime: ")+dlerror()); return; }
    using Create=jint(*)(JavaVM**,void**,void*);
    auto create=reinterpret_cast<Create>(dlsym(handle,"JNI_CreateJavaVM"));
    if(!create) {status("Java runtime entry point missing");return;}
    status("Java library loaded; resolving native dependencies"); preload(lib);
    std::vector<std::string> opts={
        "-Djava.home="+jre,"-Djava.library.path="+lib,
        "-Djava.class.path="+root+"/jvm/bridge.jar:"+root+"/jvm/client.jar:"+root+"/jvm/cacio-tta-1.18.jar:"+root+"/jvm/cacio-shared-1.18.jar",
        "-Duser.home="+root,"-Djava.io.tmpdir="+root+"/tmp","-Dkronos.host="+host,
        "-XX:ErrorFile="+root+"/hs_err_pid%p.log","-Djava.awt.headless=false","-Dcacio.managed.screensize=2304x1728","-Xms64m","-Xmx768m",
        "-XX:+ExitOnOutOfMemoryError","-Dfile.encoding=UTF-8",
        "--add-opens=java.base/java.lang=ALL-UNNAMED","--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
        "--add-opens=java.base/java.util=ALL-UNNAMED","--add-opens=java.desktop/java.awt=ALL-UNNAMED",
        "--add-opens=java.desktop/sun.java2d=ALL-UNNAMED",
        "--add-exports=java.desktop/java.awt.peer=ALL-UNNAMED","--add-exports=java.desktop/java.awt.dnd.peer=ALL-UNNAMED",
        "--add-exports=java.desktop/sun.awt=ALL-UNNAMED","--add-exports=java.desktop/sun.awt.image=ALL-UNNAMED",
        "--add-exports=java.desktop/sun.awt.event=ALL-UNNAMED","--add-exports=java.desktop/sun.awt.datatransfer=ALL-UNNAMED",
        "--add-exports=java.desktop/sun.java2d=ALL-UNNAMED","--add-exports=java.base/sun.security.action=ALL-UNNAMED"
    };
    std::vector<JavaVMOption> options(opts.size());
    for(size_t i=0;i<opts.size();++i) options[i].optionString=const_cast<char*>(opts[i].c_str());
    JavaVMInitArgs args{}; args.version=0x00150000; args.nOptions=options.size(); args.options=options.data(); args.ignoreUnrecognized=JNI_FALSE;
    JavaVM* vm=nullptr; JNIEnv* e=nullptr;
    status("Starting Java runtime");
    int rc=create(&vm,reinterpret_cast<void**>(&e),&args);
    if(rc!=JNI_OK) {status("Java startup failed: "+std::to_string(rc));return;}
    status("Java runtime ready; activating mobile graphics");
    // Match RuneMobile's required ordering: initialize Cacio BEFORE resolving
    // any game bridge class whose verifier may resolve AWT types on Android.
    jclass activation=e->FindClass("UnsafePokeActivation");
    if(!activation){e->ExceptionDescribe();e->ExceptionClear();status("Graphics activation class missing");return;}
    jmethodID activate=e->GetStaticMethodID(activation,"activate","()V");
    if(!activate){e->ExceptionDescribe();e->ExceptionClear();status("Graphics activation method missing");return;}
    e->CallStaticVoidMethod(activation,activate);
    if(e->ExceptionCheck()){e->ExceptionDescribe();e->ExceptionClear();status("Mobile graphics initialization failed; open Crash report");return;}
    e->DeleteLocalRef(activation);
    status("Mobile graphics ready; loading Kronos");
    jclass bridge=e->FindClass("KronosBoot");
    if(!bridge) {e->ExceptionDescribe();status("Kronos bridge missing");return;}
    auto boot=e->GetStaticMethodID(bridge,"boot","()V");
    auto copyFrame=e->GetStaticMethodID(bridge,"copyFrame","(Ljava/nio/ByteBuffer;[IZ)I");
    auto input=e->GetStaticMethodID(bridge,"input","(IIIILjava/lang/String;)V");
    auto state=e->GetStaticMethodID(bridge,"status","()Ljava/lang/String;");
    auto editor=e->GetStaticMethodID(bridge,"editor","()Ljava/lang/String;");
    auto interactions=e->GetStaticMethodID(bridge,"interactions","()[I");
    if(e->ExceptionCheck() || !boot || !copyFrame || !input || !state || !editor || !interactions) {e->ExceptionDescribe();status("Incompatible Kronos bridge");return;}
    e->CallStaticVoidMethod(bridge,boot);
    if(e->ExceptionCheck()) {e->ExceptionDescribe();e->ExceptionClear();status("Client startup failed; see client-error.log");return;}
    int counter=0;
    std::vector<jint> framePixels;
    jobject frameBuffer=nullptr;
    jintArray dimensions=e->NewIntArray(2);
    int surfaceWidth=0,surfaceHeight=0;
    uint64_t displayedSurfaceGeneration=0;
    while(true) {
        const auto iterationStart=std::chrono::steady_clock::now();
        std::deque<Input> events;
        { std::lock_guard<std::mutex> l(queueLock); events.swap(queue); }
        for(auto& ev:events) { jstring txt=e->NewStringUTF(ev.text.c_str()); e->CallStaticVoidMethod(bridge,input,ev.kind,ev.x,ev.y,ev.value,txt); e->DeleteLocalRef(txt); }
        if(e->ExceptionCheck()) {e->ExceptionDescribe();e->ExceptionClear();status("Input error; see client-error.log");}
        if(counter++%30==0) {auto s=(jstring)e->CallStaticObjectMethod(bridge,state); if(s){status(str(e,s));e->DeleteLocalRef(s);} }
        if(counter%3==0) {
            auto value=(jstring)e->CallStaticObjectMethod(bridge,editor);
            if(value){std::lock_guard<std::mutex> l(editorLock);editorText=str(e,value);e->DeleteLocalRef(value);}
        }
        if(counter%2==0) {
            auto regions=(jintArray)e->CallStaticObjectMethod(bridge,interactions);
            if(regions){
                int n=e->GetArrayLength(regions);
                if(n>=8&&n<4096){
                    std::lock_guard<std::mutex> lock(interactionsLock);
                    interactionsData.resize(n);
                    e->GetIntArrayRegion(regions,0,n,interactionsData.data());
                }
                e->DeleteLocalRef(regions);
            }
        }
        if(foreground.load()) {
            std::lock_guard<std::mutex> lock(surfaceLock);
            if(surface) {
                jint bytes=e->CallStaticIntMethod(bridge,copyFrame,frameBuffer,dimensions,static_cast<jboolean>(refreshSurface));
                if(bytes<0 && bytes>=-4096*4096*static_cast<int>(sizeof(jint))) {
                    if(frameBuffer)e->DeleteLocalRef(frameBuffer);
                    framePixels.resize(static_cast<size_t>(-bytes)/sizeof(jint));
                    frameBuffer=e->NewDirectByteBuffer(framePixels.data(),-bytes);
                    if(frameBuffer)bytes=e->CallStaticIntMethod(bridge,copyFrame,frameBuffer,dimensions,JNI_TRUE);
                }
                if(bytes>0 && !e->ExceptionCheck()) {
                    jint size[2];
                    e->GetIntArrayRegion(dimensions,0,2,size);
                    int w=size[0],h=size[1];
                    bool configured=w==surfaceWidth && h==surfaceHeight && displayedSurfaceGeneration==surfaceGeneration;
                    if(!configured)configured=ANativeWindow_setBuffersGeometry(surface,w,h,WINDOW_FORMAT_RGBA_8888)==0;
                    ANativeWindow_Buffer buffer;
                    if(configured && ANativeWindow_lock(surface,&buffer,nullptr)==0) {
                        auto* dst=static_cast<uint32_t*>(buffer.bits);
                        const int copyWidth=std::min(w,buffer.width);
                        const int copyHeight=std::min(h,buffer.height);
                        for(int y=0;y<copyHeight;++y) {
                            auto* row=dst+y*buffer.stride;
                            const auto* src=framePixels.data()+y*w;
                            for(int x=0;x<copyWidth;++x) {
                                uint32_t p=src[x];
                                row[x]=0xff000000u|((p&255)<<16)|(p&0xff00)|((p>>16)&255);
                            }
                        }
                        const bool posted=ANativeWindow_unlockAndPost(surface)==0;
                        refreshSurface=!posted || copyWidth!=w || copyHeight!=h;
                        if(!refreshSurface){
                            surfaceWidth=w;
                            surfaceHeight=h;
                            displayedSurfaceGeneration=surfaceGeneration;
                            frameWidth.store(w);
                            frameHeight.store(h);
                        } else surfaceWidth=0;
                    } else refreshSurface=true;
                }
            } else {
                surfaceWidth=0;
                surfaceHeight=0;
            }
        }
        if(e->ExceptionCheck()) {e->ExceptionDescribe();e->ExceptionClear();}
        std::this_thread::sleep_until(iterationStart+std::chrono::milliseconds(foreground.load()?20:200));
    }
}
extern "C" JNIEXPORT void JNICALL Java_local_kronos_mobile_MainActivity_start(JNIEnv* e,jclass,jstring root,jstring host) {
    if(started.exchange(true)) return;
    std::thread(run,str(e,root),str(e,host)).detach();
}
extern "C" JNIEXPORT void JNICALL Java_local_kronos_mobile_MainActivity_surface(JNIEnv* e,jclass,jobject s) {
    std::lock_guard<std::mutex> l(surfaceLock); if(surface) ANativeWindow_release(surface); surface=s?ANativeWindow_fromSurface(e,s):nullptr; refreshSurface=true; ++surfaceGeneration;
}
extern "C" JNIEXPORT void JNICALL Java_local_kronos_mobile_MainActivity_event(JNIEnv* e,jclass,jint k,jint x,jint y,jint v,jstring t) {
    std::lock_guard<std::mutex> l(queueLock); if(queue.size()<512) queue.push_back({k,x,y,v,str(e,t)});
}
extern "C" JNIEXPORT jstring JNICALL Java_local_kronos_mobile_MainActivity_status(JNIEnv* e,jclass) {std::lock_guard<std::mutex> l(statusLock);return e->NewStringUTF(statusText.c_str());}
extern "C" JNIEXPORT void JNICALL Java_local_kronos_mobile_MainActivity_foreground(JNIEnv*,jclass,jboolean active) {foreground.store(active);}

extern "C" JNIEXPORT jstring JNICALL Java_local_kronos_mobile_MainActivity_editor(JNIEnv* e,jclass) {
    std::lock_guard<std::mutex> l(editorLock);return e->NewStringUTF(editorText.c_str());
}
extern "C" JNIEXPORT jintArray JNICALL Java_local_kronos_mobile_MainActivity_frameSize(JNIEnv* e,jclass) {
    jint dims[2]={frameWidth.load(),frameHeight.load()};jintArray out=e->NewIntArray(2);e->SetIntArrayRegion(out,0,2,dims);return out;
}
extern "C" JNIEXPORT jintArray JNICALL Java_local_kronos_mobile_MainActivity_interactions(JNIEnv* e,jclass) {
    std::lock_guard<std::mutex> l(interactionsLock);
    jintArray out=e->NewIntArray(interactionsData.size());
    if(!interactionsData.empty())e->SetIntArrayRegion(out,0,interactionsData.size(),interactionsData.data());
    return out;
}
