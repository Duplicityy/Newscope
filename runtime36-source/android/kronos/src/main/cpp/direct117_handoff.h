#pragma once

// All accesses occur under surfaceLock. Only a detached and replaced Surface
// may change from the Android CPU producer to an EGL producer.
class Direct117Handoff {
    bool pending_ = false;
    bool detached_ = false;
    unsigned long long original_ = 0;
    unsigned long long deadline_ = 0;
public:
    void prepare(unsigned long long generation, unsigned long long now) {
        pending_ = true;
        detached_ = false;
        original_ = generation;
        deadline_ = now + 5000;
    }
    void detached() { if (pending_) detached_ = true; }
    bool pending() const { return pending_; }
    bool ready(unsigned long long generation, bool surface) const {
        return pending_ && detached_ && surface && generation > original_;
    }
    bool expired(unsigned long long now) const { return pending_ && now >= deadline_; }
    void cancel() { pending_ = detached_ = false; }
};
