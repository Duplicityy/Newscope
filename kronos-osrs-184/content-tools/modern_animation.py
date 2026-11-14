"""Revision242 sequence metadata -> explicitly extended184 opcodes.

The raw cached-model curve body is retained separately, not baked into guessed
legacy frames. Opcode13 still means legacy sound data in the target decoder.
"""
from cache_store import Reader
from legacy_assets import short


def sequence242(data, frame_id, cached_id, item_map):
    r, out = Reader(data), bytearray()
    weighted = False
    has_frames = False
    start = end = None
    while op := r.uint():
        if op in (1, 12):
            count = r.uint(2 if op == 1 else 1)
            if op == 1:
                has_frames = count > 0
            lengths = r.take(count * 2) if op == 1 else b""
            frames = r.take(count * 2)
            groups = [r.uint(2) for _ in range(count)]
            out.extend(bytes([op]) + (short(count) if op == 1 else bytes([count])) + lengths
                       + frames + b"".join(short(frame_id(i)) for i in groups))
        elif op in (3, 17):
            count = r.uint()
            out.extend(bytes([117 if op == 17 else op, count]) + r.take(count))
        elif op in (6, 7):
            value = r.uint(2)
            if value:
                source = value - 512
                if source not in item_map:
                    raise ValueError(f"Sequence held-item override {source} has no verified target mapping")
                target = item_map[source]
                if not 0 <= target <= 65023:
                    raise ValueError("Sequence held-item override exceeds appearance encoding")
                value = target + 512
            out.extend(bytes([op]) + short(value))
        elif 2 <= op <= 11:
            out.extend(bytes([op]) + r.take(2 if op == 2 else 0 if op == 4 else 1))
        elif op == 13:
            weighted = True
            out.extend(bytes([114]) + cached_id(r.uint(4)).to_bytes(4, "big"))
        elif op == 14:
            # Sound IDs are a different cache namespace, never silently reused.
            r.take(r.uint(2) * 8)
        elif op == 15:
            start, end = r.uint(2), r.uint(2)
            out.extend(bytes([115]) + short(start) + short(end))
        elif op == 16:
            r.take(1)  # modern sequence visual offset; no184 equivalent
        elif op == 18:
            r.string()
        elif op != 19:
            raise ValueError(f"Unsupported sequence242 opcode {op}")
    if r.offset != len(data):
        raise ValueError("Trailing sequence242 bytes")
    if weighted and (start is None or not 0 < end - start <= 32768):
        raise ValueError("Invalid weighted timeline")
    if not weighted and not has_frames:
        raise ValueError("No complete legacy or weighted animation")
    return bytes(out) + b"\0", weighted
