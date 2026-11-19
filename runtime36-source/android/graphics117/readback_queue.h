// Owned by the engine GL thread. Dependencies are the GLES3 declarations supplied
// by the including translation unit. No CPU wait or per-frame allocation.
struct ReadbackQueue {
    static constexpr int capacity = 3;
    struct Slot {
        GLuint buffer = 0;
        GLsync fence = nullptr;
    } slots[capacity];
    int head = 0;
    int count = 0;
    int width = 0;
    int height = 0;

    void reset() {
        for (auto &slot : slots) {
            if (slot.fence) glDeleteSync(slot.fence);
            if (slot.buffer) glDeleteBuffers(1, &slot.buffer);
            slot.fence = nullptr;
            slot.buffer = 0;
        }
        head = count = width = height = 0;
    }

    void resize(int w, int h) {
        if (width == w && height == h) return;
        reset();
        width = w;
        height = h;
    }

    bool hasRoom() const { return count < capacity; }

    // Return the latest contiguous completed slot, which the caller must copy before enqueue(). A timeout leaves all in-flight storage intact.
    int completed() {
        int latest = -1;
        while (count > 0) {
            Slot &slot = slots[head];
            GLenum status = glClientWaitSync(slot.fence, 0, 0);
            if (status == GL_TIMEOUT_EXPIRED) break;
            if (status == GL_WAIT_FAILED) return -2;
            if (status != GL_ALREADY_SIGNALED && status != GL_CONDITION_SATISFIED) return -2;
            glDeleteSync(slot.fence);
            slot.fence = nullptr;
            latest = head;
            head = (head + 1) % capacity;
            --count;
        }
        return latest;
    }

    bool enqueue() {
        if (!hasRoom()) return false;
        Slot &slot = slots[(head + count) % capacity];
        if (!slot.buffer) {
            glGenBuffers(1, &slot.buffer);
            glBindBuffer(GL_PIXEL_PACK_BUFFER, slot.buffer);
            glBufferData(GL_PIXEL_PACK_BUFFER, GLsizeiptr(width) * height * 4, nullptr, GL_STREAM_READ);
        } else glBindBuffer(GL_PIXEL_PACK_BUFFER, slot.buffer);
        glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
        slot.fence = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        if (!slot.fence) return false;
        ++count;
        glFlush();
        return true;
    }
};
