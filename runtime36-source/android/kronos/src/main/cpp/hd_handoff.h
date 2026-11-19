#pragma once

/** Producer handoff policy; no JNI/Android calls, exercised independently. */
class HdSurfaceHandoff {
    bool waiting_ = false, delivered_ = false, cancelling_ = false, failureReported_ = false, detached_ = false;
    unsigned long long minimumGeneration_ = 0, deadline_ = 0;
public:
    void prepare(unsigned long long generation, unsigned long long now) {
        waiting_ = true; delivered_ = cancelling_ = failureReported_ = detached_ = false;
        minimumGeneration_ = generation + 1; deadline_ = now + 5000;
    }
    bool pending() const { return waiting_; }
    bool delivered() const { return delivered_; }
    void requestDelivered(bool hd) { if (waiting_) { delivered_ = true; if (!hd) cancelling_ = true; } }
    void cancel() { if (waiting_) cancelling_ = true; }
    void surfaceDetached() { if (waiting_) detached_ = true; }
    bool takeTimeout(unsigned long long now) {
        if (!waiting_ || failureReported_ || now < deadline_) return false;
        failureReported_ = true; cancelling_ = true; return true;
    }
    bool allowFrame(unsigned long long generation, int mode) {
        if (!waiting_) return true;
        if (cancelling_) {
            if (mode != 0) return false; // A real completed software frame is required.
            complete(); return true;
        }
        // Never attach EGL to the old CPU producer and never reconnect a CPU
        // producer to the new SurfaceView before its first genuine HD frame.
        return delivered_ && detached_ && generation >= minimumGeneration_ && mode > 0;
    }
    void complete() { waiting_ = delivered_ = cancelling_ = failureReported_ = detached_ = false; }
};
