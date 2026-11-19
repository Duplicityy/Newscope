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
#include <condition_variable>
#include "mobile_hd.h"
#include "hd_handoff.h"
#include "direct117_handoff.h"

static std::mutex surfaceLock, queueLock, statusLock;
static std::mutex wakeLock;
static std::condition_variable activityWake;
static ANativeWindow* surface = nullptr;
static std::vector<ANativeWindow*> retiredSurfaces;
static std::atomic<bool> refreshSurface{true};
static uint64_t surfaceGeneration = 0;
static jlong surfaceOwner=0;
static std::atomic<jlong> foregroundOwner{0};
static HdSurfaceHandoff hdHandoff; // guarded by surfaceLock

static unsigned long long monotonicMillis() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();
}
static std::string statusText = "Ready";
static std::string hdStatusText = "Classic";
static std::mutex editorLock;
static std::mutex interactionsLock;
static std::vector<jint> interactionsData;
static std::string editorText = "0\n0\n";
static constexpr uint64_t frameDimensions(uint32_t width,uint32_t height) {
    return (static_cast<uint64_t>(width)<<32)|height;
}
static std::atomic<uint64_t> displayedDimensions{frameDimensions(765,503)};
static std::atomic<bool> started{false}, foreground{true};
static Direct117Handoff direct117Handoff;
struct Direct117Api {
    void (*setCallbacks)(void*(*)(uint64_t*),uint64_t(*)(),void(*)(void*),void(*)(int,int));
    int (*state)();
    uint64_t (*generation)();
    void (*enable)(int);
};
static Direct117Api direct117Storage{};
static std::atomic<Direct117Api*> direct117Api{nullptr};
static void* acquire117Window(uint64_t* generation){
    std::lock_guard<std::mutex> lock(surfaceLock);
    *generation=surfaceGeneration;
    if(!surface || !foreground.load())return nullptr;
    ANativeWindow_acquire(surface);
    return surface;
}
static uint64_t generation117Window(){
    std::lock_guard<std::mutex> lock(surfaceLock);
    return surface && foreground.load()?surfaceGeneration:0;
}
static void release117Window(void* window){ANativeWindow_release(static_cast<ANativeWindow*>(window));}
static void publishFrameDimensions(int width,int height) {
    displayedDimensions.store(frameDimensions(width,height));
}
static void bind117Display(const std::string& root){
    if(direct117Api.load())return;
    void* handle=dlopen((root+"/jvm/jre/lib/libnewscape117.so").c_str(),RTLD_NOW|RTLD_NOLOAD);
    if(!handle)return;
    direct117Storage.setCallbacks=reinterpret_cast<decltype(direct117Storage.setCallbacks)>(dlsym(handle,"RouteC_setPresentCallbacks"));
    direct117Storage.state=reinterpret_cast<decltype(direct117Storage.state)>(dlsym(handle,"RouteC_directState"));
    direct117Storage.generation=reinterpret_cast<decltype(direct117Storage.generation)>(dlsym(handle,"RouteC_contextGeneration"));
    direct117Storage.enable=reinterpret_cast<decltype(direct117Storage.enable)>(dlsym(handle,"RouteC_enableDirect"));
    if(direct117Storage.setCallbacks && direct117Storage.state && direct117Storage.generation && direct117Storage.enable){
        direct117Storage.setCallbacks(acquire117Window,generation117Window,release117Window,publishFrameDimensions);
        direct117Api.store(&direct117Storage);
    }else dlclose(handle);
}

static std::string bootLog;
static std::atomic<JavaVM*> ownerPluginVm{nullptr};
static std::atomic<bool> ownerPluginReady{false};
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
    if(host!=NEWSCAPE_OWNER_HOST) { status("This owner test build connects only to " NEWSCAPE_OWNER_HOST); return; }
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
        "-Djava.class.path="+root+"/jvm/owner-plugins.jar:"+root+"/jvm/owner-graphics.jar:"+root+"/jvm/bridge.jar:"+root+"/jvm/client.jar:"+root+"/jvm/cacio-tta-1.18.jar:"+root+"/jvm/cacio-shared-1.18.jar",
        "-Duser.home="+root,"-Djava.io.tmpdir="+root+"/tmp","-Dkronos.athletics.client=true","-Dkronos.dungeoneering.enabled=true","-Dkronos.host="+host,
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
    if(!bridge) {
        jthrowable cause=e->ExceptionOccurred();e->ExceptionDescribe();e->ExceptionClear();
        std::string detail="Unknown Java linkage error";
        if(cause){jclass type=e->GetObjectClass(cause);jmethodID describe=e->GetMethodID(type,"toString","()Ljava/lang/String;");
            if(describe){auto message=static_cast<jstring>(e->CallObjectMethod(cause,describe));if(message&&!e->ExceptionCheck()){detail=str(e,message);e->DeleteLocalRef(message);}}
            e->DeleteLocalRef(type);e->DeleteLocalRef(cause);if(e->ExceptionCheck())e->ExceptionClear();}
        status("Bridge load failed: "+detail);return;
    }
    auto boot=e->GetStaticMethodID(bridge,"boot","()V");
    auto copyFrame=e->GetStaticMethodID(bridge,"copyFrame","(Ljava/nio/ByteBuffer;[IZ)I");
    auto input=e->GetStaticMethodID(bridge,"input","(IIIILjava/lang/String;)V");
    auto state=e->GetStaticMethodID(bridge,"status","()Ljava/lang/String;");
    auto editor=e->GetStaticMethodID(bridge,"editor","()Ljava/lang/String;");
    auto interactions=e->GetStaticMethodID(bridge,"interactions","()[I");
    auto visibility=e->GetStaticMethodID(bridge,"foreground","(Z)V");
    auto copySequence=e->GetStaticMethodID(bridge,"copySequence","()J");
    auto hdState=e->GetStaticMethodID(bridge,"hdStatus","()Ljava/lang/String;");
    auto hdRequested=e->GetStaticMethodID(bridge,"hdRequested","()Z");
    auto hdFailed=e->GetStaticMethodID(bridge,"hdFailed","(Ljava/lang/String;)V");
    auto hdFrameMode=e->GetStaticMethodID(bridge,"hdFrameMode","(J)I");
    if(e->ExceptionCheck() || !boot || !copyFrame || !input || !state || !editor || !interactions || !visibility || !copySequence || !hdState || !hdRequested || !hdFailed || !hdFrameMode) {e->ExceptionDescribe();status("Incompatible Kronos bridge");return;}
    jclass ownerFrames=e->FindClass("Owner117Frames");
    jmethodID copied117=ownerFrames?e->GetStaticMethodID(ownerFrames,"copied117Frame","()Z"):nullptr;
    if(e->ExceptionCheck()||!copied117){e->ExceptionDescribe();status("Incompatible owner frame bridge");return;}
    e->CallStaticVoidMethod(bridge,boot);
    if(e->ExceptionCheck()) {e->ExceptionDescribe();e->ExceptionClear();status("Client startup failed; see client-error.log");return;}
    ownerPluginVm.store(vm,std::memory_order_release);
    ownerPluginReady.store(true,std::memory_order_release);
    // Custom Mobile HD preference only. This stays on our HotSpot worker and never
    // reaches the game-input dispatcher or another plugin's configuration.
    jclass mobileHdPreferences=e->FindClass("net/runelite/standalone/MobileHdRenderer");
    jmethodID configureMobileHdShadows=mobileHdPreferences?e->GetStaticMethodID(mobileHdPreferences,"configureShadows","(Z)V"):nullptr;
    if(e->ExceptionCheck()){e->ExceptionDescribe();e->ExceptionClear();}
    jmethodID configureMobileUltra=mobileHdPreferences?e->GetStaticMethodID(mobileHdPreferences,"configureUltra","(Z)V"):nullptr;
    if(e->ExceptionCheck()){e->ExceptionDescribe();e->ExceptionClear();}
    int counter=0;
    std::vector<jint> framePixels;
    jobject frameBuffer=nullptr;
    jintArray dimensions=e->NewIntArray(2);
    int surfaceWidth=0,surfaceHeight=0;
    uint64_t displayedSurfaceGeneration=0;
    bool javaForeground=true;
    while(true) {
        const auto iterationStart=std::chrono::steady_clock::now();
        const bool iterationForeground=foreground.load();
        if(counter%30==0)bind117Display(root);
        if(javaForeground!=iterationForeground) {
            e->CallStaticVoidMethod(bridge,visibility,static_cast<jboolean>(iterationForeground));
            javaForeground=iterationForeground;
            if(iterationForeground)refreshSurface=true;
        }
        std::deque<Input> events;
        { std::lock_guard<std::mutex> l(queueLock); events.swap(queue); }
        for(auto& ev:events) {
            if(ev.kind==22){
                if(configureMobileUltra)e->CallStaticVoidMethod(mobileHdPreferences,configureMobileUltra,static_cast<jboolean>(ev.value!=0));
                hdUltraOptions(ev.value);
                continue;
            }
            if(ev.kind==21){
                if(configureMobileHdShadows)e->CallStaticVoidMethod(mobileHdPreferences,configureMobileHdShadows,static_cast<jboolean>(ev.value!=0));
                continue;
            }
            jstring txt=e->NewStringUTF(ev.text.c_str());
            e->CallStaticVoidMethod(bridge,input,ev.kind,ev.x,ev.y,ev.value,txt);e->DeleteLocalRef(txt);
            if(ev.kind==20){std::lock_guard<std::mutex> lock(surfaceLock);hdHandoff.requestDelivered(ev.value!=0);}
        }
        if(e->ExceptionCheck()) {e->ExceptionDescribe();e->ExceptionClear();status("Input error; see client-error.log");}
        if(counter++%(iterationForeground?30:5)==0) {auto s=(jstring)e->CallStaticObjectMethod(bridge,state); if(s){status(str(e,s));e->DeleteLocalRef(s);} }
        if(iterationForeground && counter%5==0) {
            auto s=(jstring)e->CallStaticObjectMethod(bridge,hdState);
            if(s){std::lock_guard<std::mutex> lock(statusLock);hdStatusText=str(e,s);e->DeleteLocalRef(s);}
        }
        if(iterationForeground && counter%3==0) {
            auto value=(jstring)e->CallStaticObjectMethod(bridge,editor);
            if(value){std::lock_guard<std::mutex> l(editorLock);editorText=str(e,value);e->DeleteLocalRef(value);}
        }
        if(iterationForeground && counter%2==0) {
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
            if(hdHandoff.pending()) {
                if(hdHandoff.delivered() && !e->CallStaticBooleanMethod(bridge,hdRequested))hdHandoff.cancel();
                if(hdHandoff.takeTimeout(monotonicMillis())) {
                    jstring why=e->NewStringUTF("HD startup timed out; restoring Standard graphics");
                    e->CallStaticVoidMethod(bridge,hdFailed,why);e->DeleteLocalRef(why);
                }
            }
            auto* directApi=direct117Api.load();
            int directState=directApi?directApi->state():0;
            if(direct117Handoff.pending()){
                if(directState!=1 || direct117Handoff.expired(monotonicMillis()))direct117Handoff.cancel();
                else if(direct117Handoff.ready(surfaceGeneration,surface!=nullptr)){
                    directApi->enable(1);
                    direct117Handoff.cancel();
                    directState=directApi->state();
                }
            }
            if(!retiredSurfaces.empty()) {
                // The EGL producer must disconnect before the last old-window
                // reference can be released after Activity/surface recreation.
                hdReleaseSurface();
                for(auto* old:retiredSurfaces)ANativeWindow_release(old);
                retiredSurfaces.clear();
                surfaceWidth=surfaceHeight=0;
            }
            if(surface && !direct117Handoff.pending() && directState!=2) {
                jint bytes=e->CallStaticIntMethod(bridge,copyFrame,frameBuffer,dimensions,static_cast<jboolean>(refreshSurface.load()));
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
                    const jlong sequence=e->CallStaticLongMethod(bridge,copySequence);
                    bool allow=true;
                    if(hdHandoff.pending()) {
                        int mode=e->CallStaticIntMethod(bridge,hdFrameMode,sequence);
                        allow=!e->ExceptionCheck() && hdHandoff.allowFrame(surfaceGeneration,mode);
                    }
                    // A completed 117 frame already contains scene and UI. It
                    // must bypass MobileHD's CPU-sequence/geometry correlation.
                    const bool complete117=e->CallStaticBooleanMethod(ownerFrames,copied117);
                    HdPresentation hd=complete117?HdPresentation::CLASSIC:allow?hdPresent(e,surface,surfaceGeneration,
                            reinterpret_cast<const uint32_t*>(framePixels.data()),w,h,sequence):HdPresentation::HOLD;
                    if(hd==HdPresentation::PRESENTED) {
                        hdHandoff.complete();
                        refreshSurface=false;surfaceWidth=w;surfaceHeight=h;
                        displayedSurfaceGeneration=surfaceGeneration;
                        publishFrameDimensions(w,h);
                    } else if(hd==HdPresentation::HOLD) {
                        // A GPU UI frame is not a software scene. Retain the last
                        // complete surface until geometry or a classic frame arrives.
                        refreshSurface=true;
                    } else {
                        // EGL must disconnect before a software producer locks it.
                        hdReleaseSurface();
                        bool configured=w==surfaceWidth && h==surfaceHeight && displayedSurfaceGeneration==surfaceGeneration
                                && ANativeWindow_getWidth(surface)==w && ANativeWindow_getHeight(surface)==h
                                && ANativeWindow_getFormat(surface)==WINDOW_FORMAT_RGBA_8888;
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
                                publishFrameDimensions(w,h);
                            } else surfaceWidth=0;
                        } else refreshSurface=true;
                    }
                }
            } else {
                hdReleaseSurface();
                surfaceWidth=0;
                surfaceHeight=0;
            }
        } else {
            // EGL belongs to this worker, never to Android's callback thread.
            hdReleaseSurface();
            std::lock_guard<std::mutex> lock(surfaceLock);
            for(auto* old:retiredSurfaces)ANativeWindow_release(old);
            retiredSurfaces.clear();
            refreshSurface=true;
        }
        if(e->ExceptionCheck()) {e->ExceptionDescribe();e->ExceptionClear();}
        std::unique_lock<std::mutex> wake(wakeLock);
        auto* frameApi=direct117Api.load();
        const int intervalUs=frameApi && frameApi->state()!=0 ? 8000 : 20000;
        activityWake.wait_until(wake,iterationStart+std::chrono::microseconds(iterationForeground ? intervalUs : 200000),
                [iterationForeground]{return foreground.load()!=iterationForeground;});
    }
}
extern "C" JNIEXPORT void JNICALL Java_local_kronos_mobile_MainActivity_start(JNIEnv* e,jclass,jstring root,jstring host) {
    if(started.exchange(true)) return;
    std::thread(run,str(e,root),str(e,host)).detach();
}
extern "C" JNIEXPORT void JNICALL Java_local_kronos_mobile_MainActivity_surface(JNIEnv* e,jclass,jobject s,jlong owner) {
    std::lock_guard<std::mutex> l(surfaceLock);
    if(!s && owner!=surfaceOwner)return;
    if(s && owner<surfaceOwner)return;
    ANativeWindow* next=s?ANativeWindow_fromSurface(e,s):nullptr;
    if(s && !next)return;
    // Size/format callbacks can refer to the same producer. Keep its EGL
    // connection and balance the reference acquired by ANativeWindow_fromSurface.
    if(next==surface && owner==surfaceOwner) {
        if(next)ANativeWindow_release(next);
        refreshSurface=true;
        activityWake.notify_one();
        return;
    }
    if(!next){hdHandoff.surfaceDetached();direct117Handoff.detached();}
    if(surface)retiredSurfaces.push_back(surface);
    surface=next;
    surfaceOwner=owner;refreshSurface=true;++surfaceGeneration;
    activityWake.notify_one();
}

extern "C" JNIEXPORT jint JNICALL Java_local_kronos_mobile_MainActivity_direct117State(JNIEnv*,jclass){
    auto* api=direct117Api.load();
    return api?api->state():0;
}
extern "C" JNIEXPORT jlong JNICALL Java_local_kronos_mobile_MainActivity_direct117Session(JNIEnv*,jclass){
    auto* api=direct117Api.load();
    return api?api->generation():0;
}
extern "C" JNIEXPORT jboolean JNICALL Java_local_kronos_mobile_MainActivity_prepare117Surface(JNIEnv*,jclass,jlong owner){
    std::lock_guard<std::mutex> lock(surfaceLock);
    auto* api=direct117Api.load();
    if(!api || api->state()!=1 || !surface || owner!=surfaceOwner)return JNI_FALSE;
    direct117Handoff.prepare(surfaceGeneration,monotonicMillis());
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL Java_local_kronos_mobile_MainActivity_event(JNIEnv* e,jclass,jint k,jint x,jint y,jint v,jstring t) {
    if(k==20&&v==0){std::lock_guard<std::mutex> lock(surfaceLock);hdHandoff.cancel();refreshSurface=true;}
    std::lock_guard<std::mutex> l(queueLock); if(queue.size()<512) queue.push_back({k,x,y,v,str(e,t)});
}
extern "C" JNIEXPORT jboolean JNICALL Java_local_kronos_mobile_MainActivity_prepareHdSurface(JNIEnv*,jclass,jlong owner) {
    std::lock_guard<std::mutex> lock(surfaceLock);
    if(!surface || owner!=surfaceOwner || owner!=foregroundOwner.load() || !foreground.load())return JNI_FALSE;
    hdHandoff.prepare(surfaceGeneration,monotonicMillis());refreshSurface=true;
    activityWake.notify_one();return JNI_TRUE;
}
extern "C" JNIEXPORT jstring JNICALL Java_local_kronos_mobile_MainActivity_status(JNIEnv* e,jclass) {std::lock_guard<std::mutex> l(statusLock);return e->NewStringUTF(statusText.c_str());}
extern "C" JNIEXPORT jstring JNICALL Java_local_kronos_mobile_MainActivity_hdStatus(JNIEnv* e,jclass) {std::lock_guard<std::mutex> l(statusLock);return e->NewStringUTF(hdStatusText.c_str());}
extern "C" JNIEXPORT void JNICALL Java_local_kronos_mobile_MainActivity_foreground(JNIEnv*,jclass,jboolean active,jlong owner) {
    if(active){if(owner<foregroundOwner.load())return;foregroundOwner.store(owner);}
    else if(owner!=foregroundOwner.load())return;
    foreground.store(active);activityWake.notify_one();
}

extern "C" JNIEXPORT jstring JNICALL Java_local_kronos_mobile_MainActivity_editor(JNIEnv* e,jclass) {
    std::lock_guard<std::mutex> l(editorLock);return e->NewStringUTF(editorText.c_str());
}
extern "C" JNIEXPORT jintArray JNICALL Java_local_kronos_mobile_MainActivity_frameSize(JNIEnv* e,jclass) {
    const uint64_t dimensions=displayedDimensions.load();
    jint dims[2]={static_cast<jint>(dimensions>>32),static_cast<jint>(dimensions&0xffffffffu)};
    jintArray out=e->NewIntArray(2);e->SetIntArrayRegion(out,0,2,dims);return out;
}
extern "C" JNIEXPORT jintArray JNICALL Java_local_kronos_mobile_MainActivity_interactions(JNIEnv* e,jclass) {
    std::lock_guard<std::mutex> l(interactionsLock);
    jintArray out=e->NewIntArray(interactionsData.size());
    if(!interactionsData.empty())e->SetIntArrayRegion(out,0,interactionsData.size(),interactionsData.data());
    return out;
}

// ART objects never cross into HotSpot: only copied UTF-8 requests and responses do.
// PluginBridgeClient calls this on its dedicated background executor, never the Android UI thread.
extern "C" JNIEXPORT jstring JNICALL Java_local_kronos_mobile_MainActivity_pluginRequest(JNIEnv* art,jclass,jstring request) {
    if(!ownerPluginReady.load(std::memory_order_acquire))
        return art->NewStringUTF("{\"ok\":true,\"ready\":false,\"message\":\"The desktop client is still loading\"}");
    std::string input=str(art,request);
    if(input.size()>16384)return art->NewStringUTF("{\"ok\":false,\"error\":\"Plugin request is too large\"}");
    JavaVM* vm=ownerPluginVm.load(std::memory_order_acquire);JNIEnv* target=nullptr;bool attached=false;
    if(!vm)return art->NewStringUTF("{\"ok\":false,\"error\":\"The game runtime is unavailable\"}");
    // Android's public jni.h exposes the common 1.6 ABI; HotSpot21 supports this stable table too.
    jint state=vm->GetEnv(reinterpret_cast<void**>(&target),JNI_VERSION_1_6);
    if(state==JNI_EDETACHED){if(vm->AttachCurrentThreadAsDaemon(&target,nullptr)!=JNI_OK)return art->NewStringUTF("{\"ok\":false,\"error\":\"Could not reach the game runtime\"}");attached=true;}
    else if(state!=JNI_OK)return art->NewStringUTF("{\"ok\":false,\"error\":\"The game runtime is not ready\"}");
    std::string output="{\"ok\":false,\"error\":\"Plugin bridge unavailable\"}";
    jclass bridge=target->FindClass("NewscapePluginBridge");
    if(bridge){
        jmethodID method=target->GetStaticMethodID(bridge,"request","(Ljava/lang/String;)Ljava/lang/String;");
        if(method){
            jstring value=target->NewStringUTF(input.c_str());
            auto result=static_cast<jstring>(target->CallStaticObjectMethod(bridge,method,value));
            if(result&&!target->ExceptionCheck()){output=str(target,result);target->DeleteLocalRef(result);}
            target->DeleteLocalRef(value);
        }
        target->DeleteLocalRef(bridge);
    }
    if(target->ExceptionCheck()){target->ExceptionDescribe();target->ExceptionClear();}
    if(attached)vm->DetachCurrentThread();
    return art->NewStringUTF(output.c_str());
}
