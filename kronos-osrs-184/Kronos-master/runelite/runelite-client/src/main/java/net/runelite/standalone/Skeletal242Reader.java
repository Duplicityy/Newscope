package net.runelite.standalone;

/** Bounded big-endian reader used only by explicitly marked 242 additions. */
final class Skeletal242Reader {
    final byte[] data;
    int position;
    Skeletal242Reader(byte[] data) { this(data, 0); }
    Skeletal242Reader(byte[] data, int position) {
        if (data == null || data.length > 16777216 || position < 0 || position > data.length)
            throw new IllegalArgumentException("Invalid skeletal record");
        this.data = data; this.position = position;
    }
    void require(int count) {
        if (count < 0 || count > data.length - position) throw new IllegalArgumentException("Truncated skeletal record");
    }
    int u8() { require(1); return data[position++] & 255; }
    int u16() { return (u8() << 8) | u8(); }
    int i16() { return (short)u16(); }
    int i32() { return (u16() << 16) | u16(); }
    int smart() { require(1); return (data[position] & 255) < 128 ? u8() - 64 : u16() - 49152; }
    float f32() {
        float value = Float.intBitsToFloat(i32());
        if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite skeletal value");
        return value;
    }
    void end() { if (position != data.length) throw new IllegalArgumentException("Trailing skeletal data"); }
}
