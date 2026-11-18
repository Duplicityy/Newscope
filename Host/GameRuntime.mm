#import "GameRuntime.h"
#import "jni/jni.h"
#include "../MobileHD/MobileHD.h"
#import <QuartzCore/QuartzCore.h>
#import <TargetConditionals.h>
#import <sys/sysctl.h>
#import <sys/proc.h>
#import <unistd.h>
#import <dlfcn.h>
#include <atomic>
#include <cstdint>
#include <string>
#include <vector>

namespace {
constexpr jint Java21 = 0x00150000;
constexpr size_t MaxFrameBytes = 32 * 1024 * 1024;

struct Bridge {
    JNIEnv *env = nullptr;
    jclass game = nullptr, input = nullptr;
    jclass layout = nullptr, rendererFrames = nullptr;
    jmethodID screen = nullptr, is117Frame = nullptr, sequence = nullptr, frameMode = nullptr,
              hdRequested = nullptr, hdFailed = nullptr;
    jmethodID event = nullptr, frame = nullptr, status = nullptr, hd = nullptr,
              editor = nullptr, regions = nullptr, visibility = nullptr,
              touch = nullptr, position = nullptr, move = nullptr, tick = nullptr,
              canvas = nullptr, settings = nullptr, layoutEdit = nullptr,
              layoutSelection = nullptr, layoutPlace = nullptr;
};

bool DebuggerAttached() {
    struct kinfo_proc process{};
    size_t length = sizeof(process);
    int query[] = {CTL_KERN, KERN_PROC, KERN_PROC_PID, getpid()};
    return sysctl(query, 4, &process, &length, nullptr, 0) == 0 &&
        length == sizeof(process) && (process.kp_proc.p_flag & P_TRACED) != 0;
}

bool ClearFailure(JNIEnv *env) {
    if (!env->ExceptionCheck()) return false;
    // Java exceptions may include account input. Keep the native error generic.
    env->ExceptionClear();
    return true;
}

jstring ToJava(JNIEnv *env, NSString *text) {
    if (!text.length) return env->NewString(nullptr, 0);
    std::vector<jchar> chars(text.length);
    [text getCharacters:reinterpret_cast<unichar *>(chars.data()) range:NSMakeRange(0, text.length)];
    return env->NewString(chars.data(), static_cast<jsize>(chars.size()));
}

NSString *FromJava(JNIEnv *env, jstring value) {
    if (!value) return @"";
    const jchar *chars = env->GetStringChars(value, nullptr);
    if (!chars) { ClearFailure(env); return @""; }
    NSString *text = [[NSString alloc] initWithCharacters:reinterpret_cast<const unichar *>(chars)
                                                  length:env->GetStringLength(value)];
    env->ReleaseStringChars(value, chars);
    return text ?: @"";
}

jlong Millis() {
    return static_cast<jlong>(CACurrentMediaTime() * 1000);
}

jclass Global(JNIEnv *env, const char *name) {
    jclass local = env->FindClass(name);
    if (ClearFailure(env) || !local) return nullptr;
    jclass result = static_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    if (ClearFailure(env)) return nullptr;
    return result;
}

bool Bind(Bridge &b) {
    b.game = Global(b.env, "KronosBoot");
    b.input = Global(b.env, "NewscapeIosInput");
    b.layout = Global(b.env, "local/kronos/mobile/ScreenLayout");
    b.rendererFrames = Global(b.env, "Owner117Frames");
    if (!b.game || !b.input || !b.layout || !b.rendererFrames) return false;
    auto bind = [&](jmethodID &method, jclass type, const char *name, const char *signature) {
        method = b.env->GetStaticMethodID(type, name, signature);
        return !ClearFailure(b.env) && method;
    };
    return bind(b.is117Frame,b.rendererFrames,"copied117Frame","()Z") &&
           bind(b.sequence,b.game,"copySequence","()J") &&
           bind(b.frameMode,b.game,"hdFrameMode","(J)I") &&
           bind(b.hdRequested,b.game,"hdRequested","()Z") &&
           bind(b.hdFailed,b.game,"hdFailed","(Ljava/lang/String;)V") &&
           bind(b.screen,b.layout,"canvas","(IIF)[I") &&
           bind(b.event,b.game,"input","(IIIILjava/lang/String;)V") &&
           bind(b.frame,b.game,"copyFrame","(Ljava/nio/ByteBuffer;[IZ)I") &&
           bind(b.status,b.game,"status","()Ljava/lang/String;") &&
           bind(b.hd,b.game,"hdStatus","()Ljava/lang/String;") &&
           bind(b.editor,b.game,"editor","()Ljava/lang/String;") &&
           bind(b.regions,b.game,"interactions","()[I") &&
           bind(b.visibility,b.game,"foreground","(Z)V") &&
           bind(b.touch,b.input,"touch","(IIFFJ)V") &&
           bind(b.position,b.input,"position","(IFF)V") &&
           bind(b.move,b.input,"move","(J)V") &&
           bind(b.tick,b.input,"tick","(J)V") &&
           bind(b.canvas,b.input,"canvas","(IIF)V") &&
           bind(b.settings,b.input,"settings","(JJFZ)V") &&
           bind(b.layoutEdit,b.input,"layoutEdit","(ZZ)V") &&
           bind(b.layoutSelection,b.input,"layoutSelection","()I") &&
           bind(b.layoutPlace,b.input,"layoutPlace","(IFF)Z");
}

void Send(Bridge &b, jint kind, jint x, jint y, jint value, NSString *text) {
    jstring body = ToJava(b.env, text);
    if (!ClearFailure(b.env) && body)
        b.env->CallStaticVoidMethod(b.game, b.event, kind, x, y, value, body);
    if (body) b.env->DeleteLocalRef(body);
}

NSString *Asset(NSString *root, NSString *relative) {
    return [root stringByAppendingPathComponent:relative];
}

NSArray<NSString *> *EngineJars() {
    return @[@"newscape-ios-bridge.jar", @"owner-plugins.jar", @"owner-graphics.jar",
             @"bridge.jar", @"client.jar", @"cacio-tta-1.18.jar", @"cacio-shared-1.18.jar"];
}
}

typedef void (^NSCCommand)(Bridge &);

@implementation NSCGameRuntime {
    NSLock *_commandLock;
    NSMutableArray<NSCCommand> *_commands;
    dispatch_queue_t _pluginQueue;
    std::atomic<JavaVM *> _vm;
    std::atomic<bool> _running, _started, _visible, _frameWaiting;
    std::atomic<unsigned> _requests;
    jclass _plugin;
    jmethodID _pluginMethod;
    BOOL _repeatFrame, _ownsMobileHd;
    CFTimeInterval _holdStarted;
}

- (instancetype)init {
    if ((self = [super init])) {
        _commandLock = [[NSLock alloc] init];
        _commands = [[NSMutableArray alloc] init];
        _pluginQueue = dispatch_queue_create("local.newscape.ios.plugin", DISPATCH_QUEUE_SERIAL);
        _vm.store(nullptr);
        _running.store(false);
        _started.store(false);
        _visible.store(true);
        _frameWaiting.store(false);
        _requests.store(0);
    }
    return self;
}

- (BOOL)isRunning { return _running.load(); }

- (void)notify:(NSString *)status {
    dispatch_async(dispatch_get_main_queue(), ^{ [self.delegate engineStatus:status]; });
}

- (void)start {
    bool expected = false;
    if (!_started.compare_exchange_strong(expected, true)) return;
    [NSThread detachNewThreadWithBlock:^{
        @autoreleasepool { [self run]; }
    }];
}

- (void)run {
#if TARGET_OS_SIMULATOR
    [self notify:@"This runtime bundle requires a physical iPhone or iPad."];
    _started.store(false);
    return;
#endif
    if (!DebuggerAttached()) {
        [self notify:@"Launch this development build from Xcode with its debugger attached."];
        _started.store(false);
        return;
    }
    NSString *runtime = Asset(NSBundle.mainBundle.resourcePath, @"Runtime");
    NSString *jre = Asset(runtime, @"jre");
    NSString *javaLib = Asset(jre, @"lib");
    NSString *hotspot = Asset(javaLib, @"server/libjvm.dylib");
    NSString *awt = Asset(javaLib, @"libawt_xawt.dylib");
    NSFileManager *files = NSFileManager.defaultManager;
    if (![files isReadableFileAtPath:hotspot] || ![files isReadableFileAtPath:awt]) {
        [self notify:@"The signed iOS Java runtime or AWT bridge is missing."];
        _started.store(false);
        return;
    }
    NSMutableArray<NSString *> *jars = [NSMutableArray array];
    for (NSString *name in EngineJars()) {
        NSString *jar = Asset(runtime, name);
        if (![files isReadableFileAtPath:jar]) {
            [self notify:[@"Missing bundled game module " stringByAppendingString:name]];
            _started.store(false);
            return;
        }
        [jars addObject:jar];
    }
    NSURL *support = [files URLsForDirectory:NSApplicationSupportDirectory inDomains:NSUserDomainMask].firstObject;
    NSString *storage = [support.URLByStandardizingPath.path stringByAppendingPathComponent:@"Newscape"];
    NSString *temporary = Asset(storage, @"tmp");
    if (!storage || ![files createDirectoryAtPath:temporary withIntermediateDirectories:YES
                                        attributes:@{NSFileProtectionKey:NSFileProtectionCompleteUntilFirstUserAuthentication}
                                             error:nullptr]) {
        [self notify:@"Could not prepare game storage."];
        _started.store(false);
        return;
    }
    [self notify:@"Starting Newscape"];
    void *library = dlopen(hotspot.fileSystemRepresentation, RTLD_NOW | RTLD_GLOBAL);
    if (!library) {
        [self notify:@"iOS could not link the signed Java runtime. Check the Xcode console."];
        _started.store(false);
        return;
    }
    using CreateVM = jint (*)(JavaVM **, void **, void *);
    auto createVM = reinterpret_cast<CreateVM>(dlsym(library, "JNI_CreateJavaVM"));
    if (!createVM) {
        [self notify:@"The iOS Java runtime does not expose JNI."];
        return;
    }
    NSString *frameworks = Asset(NSBundle.mainBundle.bundlePath, @"Frameworks");
    NSString *renderer = Asset(frameworks, @"libnewscape117.dylib");
    NSString *rendererInRuntime = Asset(runtime, @"libnewscape117.dylib");
    if (![files isReadableFileAtPath:renderer] && [files isReadableFileAtPath:rendererInRuntime])
        renderer = rendererInRuntime;
    NSArray<NSString *> *settings = @[
        [@"-Djava.home=" stringByAppendingString:jre],
        [@"-Djava.library.path=" stringByAppendingString:javaLib],
        [@"-Djava.class.path=" stringByAppendingString:[jars componentsJoinedByString:@":"]],
        [@"-Duser.home=" stringByAppendingString:storage],
        [@"-Djava.io.tmpdir=" stringByAppendingString:temporary],
        [@"-Dnewscape.117.library=" stringByAppendingString:renderer],
        [@"-XX:ErrorFile=" stringByAppendingString:Asset(storage, @"hs_err_pid%p.log")],
        @"-Dkronos.host=51.91.58.148",
        @"-Dkronos.athletics.client=true",
        @"-Dkronos.dungeoneering.enabled=true",
        @"-Djava.awt.headless=false",
        @"-Dcacio.managed.screensize=2304x1728",
        @"-Dfile.encoding=UTF-8", @"-Djna.nounpack=true", @"-Xms64m", @"-Xmx768m",
        @"--add-opens=java.base/java.lang=ALL-UNNAMED",
        @"--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
        @"--add-opens=java.base/java.util=ALL-UNNAMED",
        @"--add-opens=java.desktop/java.awt=ALL-UNNAMED",
        @"--add-opens=java.desktop/sun.java2d=ALL-UNNAMED",
        @"--add-exports=java.desktop/java.awt.peer=ALL-UNNAMED",
        @"--add-exports=java.desktop/java.awt.dnd.peer=ALL-UNNAMED",
        @"--add-exports=java.desktop/sun.awt=ALL-UNNAMED",
        @"--add-exports=java.desktop/sun.awt.image=ALL-UNNAMED",
        @"--add-exports=java.desktop/sun.awt.event=ALL-UNNAMED",
        @"--add-exports=java.desktop/sun.awt.datatransfer=ALL-UNNAMED",
        @"--add-exports=java.desktop/sun.java2d=ALL-UNNAMED",
        @"--add-exports=java.base/sun.security.action=ALL-UNNAMED"
    ];
    std::vector<std::string> strings;
    strings.reserve(settings.count);
    for (NSString *setting in settings) strings.emplace_back(setting.UTF8String);
    std::vector<JavaVMOption> options;
    options.reserve(strings.size());
    for (std::string &setting : strings) options.push_back({setting.data(), nullptr});
    JavaVMInitArgs args{Java21, static_cast<jint>(options.size()), options.data(), JNI_FALSE};
    JavaVM *vm = nullptr;
    Bridge bridge;
    const jint result = createVM(&vm, reinterpret_cast<void **>(&bridge.env), &args);
    if (result != JNI_OK || !vm || !bridge.env) {
        [self notify:[NSString stringWithFormat:@"The iOS Java runtime did not start (%d).", result]];
        return;
    }
    JNIEnv *env = bridge.env;
    jclass awtActivation = env->FindClass("UnsafePokeActivation");
    if (ClearFailure(env) || !awtActivation) {
        [self notify:@"The Java graphics activation module could not load."];
        return;
    }
    jmethodID activate = env->GetStaticMethodID(awtActivation, "activate", "()V");
    if (ClearFailure(env) || !activate) {
        [self notify:@"The Java graphics bridge has an incompatible version."];
        return;
    }
    env->CallStaticVoidMethod(awtActivation, activate);
    env->DeleteLocalRef(awtActivation);
    if (ClearFailure(env) || !Bind(bridge)) {
        [self notify:@"The iOS runtime could not initialize the Newscape graphics and input bridge."];
        return;
    }
    _plugin = Global(env, "NewscapePluginBridge");
    if (_plugin) _pluginMethod = env->GetStaticMethodID(_plugin, "request", "(Ljava/lang/String;)Ljava/lang/String;");
    const bool pluginFailed = ClearFailure(env) || !_plugin || !_pluginMethod;
    jmethodID boot = env->GetStaticMethodID(bridge.game, "boot", "()V");
    if (ClearFailure(env) || pluginFailed || !boot) {
        [self notify:@"The bundled Newscape client ABI is incompatible."];
        return;
    }
    env->CallStaticVoidMethod(bridge.game, boot);
    if (ClearFailure(env)) {
        [self notify:@"The engine failed to start. Check the Xcode console."];
        return;
    }
    _vm.store(vm, std::memory_order_release);
    _running.store(true);
    [self gameLoop:bridge];
}

- (void)gameLoop:(Bridge &)bridge {
    JNIEnv *env = bridge.env;
    jintArray dimensions = env->NewIntArray(2);
    if (ClearFailure(env) || !dimensions) {
        _running.store(false);
        [self notify:@"The engine could not allocate a frame header."];
        return;
    }
    std::vector<unsigned char> pixels;
    jobject buffer = nullptr;
    BOOL lastVisible = YES;
    double lastPoll = 0;
    NSString *lastStatus = nil;
    for (;;) {
        @autoreleasepool {
            const double start = CACurrentMediaTime();
            [_commandLock lock];
            NSArray<NSCCommand> *batch = [_commands copy];
            [_commands removeAllObjects];
            [_commandLock unlock];
            for (NSCCommand command in batch) {
                command(bridge);
                if (ClearFailure(env)) [self notify:@"An engine command failed."];
            }
            const BOOL visible = _visible.load();
            if (lastVisible != visible) {
                if (!visible) env->CallStaticVoidMethod(bridge.input, bridge.touch, 3, 0, 0.0f, 0.0f, Millis());
                if (!visible && _ownsMobileHd) {
                    iosMobileHdRelease(env);
                    _ownsMobileHd = NO;
                }
                _repeatFrame = YES;
                _holdStarted = 0;
                env->CallStaticVoidMethod(bridge.game, bridge.visibility, static_cast<jboolean>(visible));
                ClearFailure(env);
                lastVisible = visible;
            }
            if (visible && !ClearFailure(env)) {
                env->CallStaticVoidMethod(bridge.input, bridge.tick, Millis());
                if (!ClearFailure(env) && !_frameWaiting.load())
                    [self copyFrame:bridge dimensions:dimensions buffer:buffer bytes:pixels];
            }
            if (visible && start - lastPoll >= 0.10) {
                lastPoll = start;
                NSString *stage = [self read:bridge method:bridge.status];
                NSString *hd = [self read:bridge method:bridge.hd];
                NSString *stageAndRenderer = hd.length && ![hd isEqualToString:@"Classic"]
                    ? [NSString stringWithFormat:@"%@ · %@", stage, hd] : stage;
                if (![lastStatus isEqualToString:stageAndRenderer]) {
                    lastStatus = stageAndRenderer;
                    [self notify:stageAndRenderer];
                }
                NSString *editor = [self read:bridge method:bridge.editor];
                jintArray values = static_cast<jintArray>(env->CallStaticObjectMethod(bridge.game, bridge.regions));
                NSMutableArray<NSNumber *> *regions = [NSMutableArray array];
                if (!ClearFailure(env) && values) {
                    jsize count = env->GetArrayLength(values);
                    if (!ClearFailure(env) && count > 0 && count <= 2048) {
                        std::vector<jint> data(count);
                        env->GetIntArrayRegion(values, 0, count, data.data());
                        if (!ClearFailure(env)) for (jint value : data) [regions addObject:@(value)];
                    }
                    env->DeleteLocalRef(values);
                }
                jint selection = env->CallStaticIntMethod(bridge.input, bridge.layoutSelection);
                if (ClearFailure(env)) selection = -1;
                dispatch_async(dispatch_get_main_queue(), ^{
                    [self.delegate engineEditor:editor regions:regions layoutSelection:selection];
                });
            }
            NSTimeInterval interval = visible ? 1.0 / 60.0 : 0.15;
            NSTimeInterval remaining = interval - (CACurrentMediaTime() - start);
            if (remaining > 0) [NSThread sleepForTimeInterval:remaining];
        }
    }
}

- (NSString *)read:(Bridge &)bridge method:(jmethodID)method {
    JNIEnv *env = bridge.env;
    jstring value = static_cast<jstring>(env->CallStaticObjectMethod(bridge.game, method));
    NSString *text = ClearFailure(env) ? @"" : FromJava(env, value);
    if (value) env->DeleteLocalRef(value);
    return text;
}

- (void)copyFrame:(Bridge &)bridge dimensions:(jintArray)dimensions buffer:(jobject &)buffer
             bytes:(std::vector<unsigned char> &)pixels {
    JNIEnv *env = bridge.env;
    jint size = env->CallStaticIntMethod(bridge.game, bridge.frame, buffer, dimensions, static_cast<jboolean>(_repeatFrame));
    if (ClearFailure(env)) return;
    if (size < 0 && size != INT32_MIN && static_cast<size_t>(-size) <= MaxFrameBytes) {
        if (buffer) env->DeleteLocalRef(buffer);
        pixels.resize(static_cast<size_t>(-size));
        buffer = env->NewDirectByteBuffer(pixels.data(), static_cast<jlong>(pixels.size()));
        if (ClearFailure(env) || !buffer) return;
        size = env->CallStaticIntMethod(bridge.game, bridge.frame, buffer, dimensions, JNI_TRUE);
    }
    if (ClearFailure(env) || size <= 0 || static_cast<size_t>(size) > pixels.size()) return;
    jint shape[2];
    env->GetIntArrayRegion(dimensions, 0, 2, shape);
    if (ClearFailure(env) || shape[0] <= 0 || shape[1] <= 0 ||
        static_cast<uint64_t>(shape[0]) * shape[1] * 4 != static_cast<uint64_t>(size)) return;
    const bool frame117 = env->CallStaticBooleanMethod(bridge.rendererFrames, bridge.is117Frame);
    if (ClearFailure(env)) return;
    if (frame117) {
        if (_ownsMobileHd) iosMobileHdRelease(env);
        _ownsMobileHd = NO;
    } else {
        const jlong sequence = env->CallStaticLongMethod(bridge.game, bridge.sequence);
        if (ClearFailure(env)) return;
        const jint mode = env->CallStaticIntMethod(bridge.game, bridge.frameMode, sequence);
        if (ClearFailure(env)) return;
        IosMobileHdFrame presentation = IosMobileHdFrame::Classic;
        if (mode > 0) {
            presentation = iosMobileHdCompose(env, pixels.data(), pixels.size(), shape[0], shape[1], sequence);
            _ownsMobileHd = YES;
        } else if (mode < 0) {
            presentation = IosMobileHdFrame::Hold;
        }
        ClearFailure(env);
        if (presentation == IosMobileHdFrame::Hold) {
            _repeatFrame = YES;
            const CFTimeInterval now = CACurrentMediaTime();
            if (_holdStarted == 0) _holdStarted = now;
            if (now - _holdStarted >= 5.0) {
                const bool requested = env->CallStaticBooleanMethod(bridge.game, bridge.hdRequested);
                if (!ClearFailure(env) && requested) {
                    jstring reason = ToJava(env, @"Mobile HD did not produce a complete image. Returning to Classic.");
                    if (!ClearFailure(env) && reason)
                        env->CallStaticVoidMethod(bridge.game, bridge.hdFailed, reason);
                    if (reason) env->DeleteLocalRef(reason);
                    ClearFailure(env);
                }
                if (_ownsMobileHd) iosMobileHdRelease(env);
                _ownsMobileHd = NO;
                _holdStarted = now;
            }
            return;
        }
        if (presentation == IosMobileHdFrame::Classic && _ownsMobileHd) {
            iosMobileHdRelease(env);
            _ownsMobileHd = NO;
        }
    }
    _holdStarted = 0;
    _repeatFrame = NO;
    NSData *owned = [NSData dataWithBytes:pixels.data() length:size];
    CGDataProviderRef provider = CGDataProviderCreateWithCFData((__bridge CFDataRef)owned);
    if (!provider) return;
    CGColorSpaceRef color = CGColorSpaceCreateDeviceRGB();
    CGImageRef frame = CGImageCreate(shape[0], shape[1], 8, 32, static_cast<size_t>(shape[0]) * 4,
                                     color, static_cast<CGBitmapInfo>(kCGImageAlphaNoneSkipFirst | kCGBitmapByteOrder32Little),
                                     provider, nullptr, false, kCGRenderingIntentDefault);
    CGColorSpaceRelease(color);
    CGDataProviderRelease(provider);
    if (!frame) return;
    const NSInteger width = shape[0], height = shape[1];
    _frameWaiting.store(true);
    dispatch_async(dispatch_get_main_queue(), ^{
        [self.delegate engineFrame:frame width:width height:height];
        CGImageRelease(frame);
        self->_frameWaiting.store(false);
    });
}

- (void)command:(NSCCommand)command {
    if (!_running.load()) return;
    [_commandLock lock];
    // Cancel partial gestures if the UI outpaces the game worker for a sustained interval.
    if (_commands.count >= 256) {
        [_commands removeAllObjects];
        [_commands addObject:^(Bridge &b) {
            b.env->CallStaticVoidMethod(b.input, b.touch, 3, 0, 0.0f, 0.0f, Millis());
            Send(b, 19, 0, 0, 0, @"");
        }];
    }
    [_commands addObject:[command copy]];
    [_commandLock unlock];
}

- (void)setVisible:(BOOL)visible { _visible.store(visible); }

- (void)touch:(NSInteger)phase finger:(NSInteger)finger point:(CGPoint)point {
    const jlong time = Millis();
    [self command:^(Bridge &b) {
        b.env->CallStaticVoidMethod(b.input, b.touch, (jint)phase, (jint)finger,
                                    (jfloat)point.x, (jfloat)point.y, time);
    }];
}

- (void)moveFingers:(NSDictionary<NSNumber *, NSValue *> *)points {
    const jlong time = Millis();
    [self command:^(Bridge &b) {
        for (NSNumber *key in points) {
            CGPoint position = points[key].CGPointValue;
            b.env->CallStaticVoidMethod(b.input, b.position, (jint)key.intValue,
                                        (jfloat)position.x, (jfloat)position.y);
            if (b.env->ExceptionCheck()) return;
        }
        b.env->CallStaticVoidMethod(b.input, b.move, time);
    }];
}

- (void)setCanvas:(CGSize)canvas gestureThreshold:(CGFloat)threshold {
    [self command:^(Bridge &b) {
        b.env->CallStaticVoidMethod(b.input, b.canvas, (jint)canvas.width, (jint)canvas.height, (jfloat)threshold);
    }];
}

- (void)setViewport:(CGSize)viewport scale:(CGFloat)scale {
    [self command:^(Bridge &b) {
        jintArray shape = static_cast<jintArray>(b.env->CallStaticObjectMethod(b.layout, b.screen,
            (jint)viewport.width, (jint)viewport.height, (jfloat)scale));
        if (b.env->ExceptionCheck() || !shape) return;
        jint dimensions[2];
        b.env->GetIntArrayRegion(shape, 0, 2, dimensions);
        b.env->DeleteLocalRef(shape);
        if (!b.env->ExceptionCheck()) Send(b, 7, dimensions[0], dimensions[1], 0, @"");
    }];
}

- (void)setArmMillis:(NSInteger)arm menuMillis:(NSInteger)menu sensitivity:(CGFloat)sensitivity observer:(BOOL)observer {
    [self command:^(Bridge &b) {
        b.env->CallStaticVoidMethod(b.input, b.settings, (jlong)arm, (jlong)menu, (jfloat)sensitivity, (jboolean)observer);
    }];
}

- (void)setLayoutEditing:(BOOL)editing save:(BOOL)save {
    [self command:^(Bridge &b) {
        b.env->CallStaticVoidMethod(b.input, b.layoutEdit, (jboolean)editing, (jboolean)save);
    }];
}

- (void)placeControl:(NSInteger)control atPoint:(CGPoint)point {
    [self command:^(Bridge &b) {
        b.env->CallStaticBooleanMethod(b.input, b.layoutPlace, (jint)control, (jfloat)point.x, (jfloat)point.y);
    }];
}

- (void)setMobileHd:(BOOL)enabled ultraMask:(NSInteger)ultra shadows:(BOOL)shadows {
    [self command:^(Bridge &b) {
        iosMobileHdSetUltraOptions(enabled ? (int)(ultra & 255) : 0);
        if (!iosMobileHdSetShadows(b.env, shadows)) {
            [self notify:@"Mobile HD shadow settings could not be applied."];
        }
        Send(b, 20, 0, 0, enabled ? 1 : 0, @"");
        self->_repeatFrame = YES;
        self->_holdStarted = 0;
    }];
}

- (void)event:(NSInteger)kind x:(NSInteger)x y:(NSInteger)y value:(NSInteger)value text:(NSString *)text {
    [self command:^(Bridge &b) { Send(b, (jint)kind, (jint)x, (jint)y, (jint)value, text ?: @""); }];
}

- (void)plugin:(NSDictionary<NSString *,id> *)request completion:(void (^)(NSDictionary<NSString *,id> *))completion {
    JavaVM *vm = _vm.load(std::memory_order_acquire);
    if (!vm || !_running.load()) { completion(@{@"ok": @NO, @"error": @"The client is still starting."}); return; }
    if (_requests.fetch_add(1) >= 8) {
        _requests.fetch_sub(1);
        completion(@{@"ok": @NO, @"error": @"The plugin request queue is busy."});
        return;
    }
    NSData *json = [NSJSONSerialization dataWithJSONObject:request options:0 error:nullptr];
    NSString *payload = [[NSString alloc] initWithData:json encoding:NSUTF8StringEncoding];
    dispatch_async(_pluginQueue, ^{
        NSDictionary *reply = @{@"ok": @NO, @"error": @"The plugin bridge did not return a valid response."};
        JNIEnv *env = nullptr;
        if (payload && vm->AttachCurrentThreadAsDaemon(reinterpret_cast<void **>(&env), nullptr) == JNI_OK && env) {
            jstring input = ToJava(env, payload);
            if (!ClearFailure(env) && input) {
                jstring result = static_cast<jstring>(env->CallStaticObjectMethod(self->_plugin, self->_pluginMethod, input));
                if (!ClearFailure(env) && result) {
                    NSData *data = [FromJava(env, result) dataUsingEncoding:NSUTF8StringEncoding];
                    if (data.length && data.length <= 5 * 1024 * 1024) {
                        id value = [NSJSONSerialization JSONObjectWithData:data options:0 error:nullptr];
                        if ([value isKindOfClass:NSDictionary.class]) reply = value;
                    }
                }
                if (result) env->DeleteLocalRef(result);
            }
            if (input) env->DeleteLocalRef(input);
            vm->DetachCurrentThread();
        }
        self->_requests.fetch_sub(1);
        dispatch_async(dispatch_get_main_queue(), ^{ completion(reply); });
    });
}

@end
