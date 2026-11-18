#import <Foundation/Foundation.h>
#import <OpenGLES/EAGL.h>
#include <pthread.h>

static EAGLContext *context;
static EAGLContext *previous;
static pthread_t renderThread;

extern "C" bool newscape117OpenContext() {
    @autoreleasepool {
        if (context) return false;
        EAGLContext *candidate = [[EAGLContext alloc] initWithAPI:kEAGLRenderingAPIOpenGLES3];
        if (!candidate) return false;
        EAGLContext *current = [EAGLContext currentContext];
        if (![EAGLContext setCurrentContext:candidate]) return false;
        previous = current;
        renderThread = pthread_self();
        context = candidate;
        return true;
    }
}

extern "C" bool newscape117Current() {
    return context && pthread_equal(renderThread, pthread_self()) && [EAGLContext currentContext] == context;
}

extern "C" bool newscape117MakeCurrent() {
    return context && pthread_equal(renderThread, pthread_self())
        && ([EAGLContext currentContext] == context || [EAGLContext setCurrentContext:context]);
}

extern "C" void newscape117CloseContext() {
    if (!context || !pthread_equal(renderThread, pthread_self())) return;
    @autoreleasepool {
        if ([EAGLContext currentContext] == context) [EAGLContext setCurrentContext:previous];
        context = nil;
        previous = nil;
    }
}