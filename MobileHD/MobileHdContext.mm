#include "MobileHdContext.h"

#define GLES_SILENCE_DEPRECATION
#import <OpenGLES/EAGL.h>

namespace {
EAGLContext* mobileContext;
EAGLContext* previousContext;
bool active;
}

bool enterMobileHdContext(bool create) {
    if (active) return false;
    if (!mobileContext && create)
        mobileContext = [[EAGLContext alloc] initWithAPI:kEAGLRenderingAPIOpenGLES3];
    if (!mobileContext) return false;
    EAGLContext* previous = [EAGLContext currentContext];
    if (previous != mobileContext && ![EAGLContext setCurrentContext:mobileContext]) return false;
    previousContext = previous;
    active = true;
    return true;
}

void leaveMobileHdContext() {
    if (!active) return;
    if ([EAGLContext currentContext] != previousContext)
        [EAGLContext setCurrentContext:previousContext];
    previousContext = nil;
    active = false;
}

void destroyMobileHdContext() {
    if (active) leaveMobileHdContext();
    if (mobileContext && [EAGLContext currentContext] == mobileContext)
        [EAGLContext setCurrentContext:nil];
    mobileContext = nil;
}
