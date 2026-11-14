"""Convert revision 242 asset records to the encodings read by Kronos 184."""

import struct
from functools import lru_cache

from cache_store import Reader


def short(value):
    return struct.pack(">H", value)


def smart(value):
    if not 0 <= value <= 32767:
        raise ValueError(f"Unsigned smart out of range: {value}")
    return bytes([value]) if value < 128 else short(value + 32768)


def model_layout(data):
    """Describe the four OSRS mesh encodings without changing their payload.

    Maya weights are a separate extension, not legacy animation groups. Removing
    them preserves the bind mesh and any genuine legacy groups, but cannot make
    a Maya-only animation playable by 184.
    """
    marker = data[-2:]
    version = {b"\xff\xff": 1, b"\xff\xfe": 2, b"\xff\xfd": 3}.get(marker, 0)
    footer_size = (18, 23, 23, 26)[version]
    if len(data) < footer_size:
        raise ValueError("Truncated model footer")
    r = Reader(data[-footer_size:-2] if version else data[-footer_size:])
    v, f, tex = r.uint(2), r.uint(2), r.uint()
    render, priority, alpha, face_skin = (r.uint() for _ in range(4))
    textured = r.uint() if version in (1, 3) else render
    vertex_skin = r.uint()
    weighted = r.uint() if version in (2, 3) else 0
    x, y, z, indices = (r.uint(2) for _ in range(4))
    coordinates = r.uint(2) if version in (1, 3) else 0
    skin_size = r.uint(2) if version in (2, 3) else v * vertex_skin
    if any(flag not in (0, 1) for flag in (render, alpha, face_skin, textured, vertex_skin, weighted)):
        raise ValueError("Unsupported model flags")
    if priority != 255 and priority > 11:
        raise ValueError("Unsupported model priority")
    texture_types = data[:tex] if version in (1, 3) else bytes(tex)
    if any(kind > 3 for kind in texture_types):
        raise ValueError("Unsupported texture triangle type")
    start = tex if version in (1, 3) else 0
    # Old/V2 place the render flags after priority and face groups; V1/V3
    # place them before the triangle opcodes. Both reach the same skin offset.
    skin_offset = start + v + f * (1 + render + (priority == 255) + face_skin)
    after_indices = skin_offset + skin_size + f * alpha + indices
    texture_offset = after_indices if version in (1, 3) else None
    color_offset = after_indices + (f * 2 * textured + coordinates if version in (1, 3) else 0)
    render_offset = start + v if version in (1, 3) else v + f * (1 + (priority == 255) + face_skin)
    texture_bytes = sum(6 if kind == 0 else 19 if kind == 2 else 17 for kind in texture_types)
    mesh_end = color_offset + f * 2 + x + y + z + texture_bytes
    footer_offset = len(data) - footer_size
    if mesh_end > footer_offset or skin_size < v * vertex_skin:
        raise ValueError("Truncated model streams")
    skin = Reader(data[skin_offset:skin_offset + skin_size])
    skin.take(v * vertex_skin)
    if weighted:
        for _ in range(v):
            skin.take(skin.uint() * 2)
    if skin.offset != skin_size:
        raise ValueError("Unread model skin bytes")
    extra = data[mesh_end:footer_offset]
    # The modern cache includes additional exporter metadata after the mesh.
    # V2 ignores it; V1/V3 consume only the optional ModelData0 attachment.
    # Preserve it for V3, discard it for V2, as the original client does.
    if version in (1, 3) and not extra:
        raise ValueError("Missing model attachment marker")
    if version in (1, 3) and extra[0] and len(extra) < 11:
        raise ValueError("Truncated model attachment")
    if version in (1, 3) and textured and tex:
        coordinate = texture_offset + f * 2
        for face in range(f):
            value = int.from_bytes(data[texture_offset + face * 2:texture_offset + face * 2 + 2], "big")
            if value:
                if coordinate >= color_offset:
                    raise ValueError("Truncated texture coordinates")
                mapping = data[coordinate] - 1
                coordinate += 1
                if mapping >= tex:
                    raise ValueError("Invalid texture coordinate")
                if mapping >= 0 and texture_types[mapping] != 0:
                    raise ValueError("Used complex texture triangle unsupported by 184")
        if coordinate != color_offset:
            raise ValueError("Unread texture coordinates")
    return dict(version=version, vertices=v, faces=f, textures=tex,
                weighted=bool(weighted), legacy_vertex_groups=bool(vertex_skin),
                skin_offset=skin_offset, skin_size=skin_size, mesh_end=mesh_end,
                footer_size=footer_size, texture_offset=texture_offset,
                color_offset=color_offset, render_offset=render_offset,
                textured=bool(textured), render=bool(render))


def model184(data, texture_id=None):
    """Return a static/legacy-skinned 184 mesh, optionally remapping textures.

    This does not translate skeletal animations. The 4700 bound is per mesh;
    callers must also check combined equipment meshes against client limits.
    """
    layout = model_layout(data)
    if max(layout["vertices"], layout["faces"]) > 4700:
        raise ValueError("Model exceeds 184 software renderer's 4700 vertex/face limit")
    version = layout["version"]
    if version in (2, 3):
        footer = data[-layout["footer_size"]:-2]
        start = layout["skin_offset"]
        keep = layout["vertices"] if layout["legacy_vertex_groups"] else 0
        end = -26 if version == 3 else layout["mesh_end"]
        data = data[:start + keep] + data[start + layout["skin_size"]:end]
        data += (footer[:11] + footer[12:-2] + b"\xff\xff" if version == 3
                 else footer[:10] + footer[11:-2])
    return remap_model_textures(data, texture_id) if texture_id is not None else data


def model_texture_ids(data):
    """Return texture IDs referenced by a validated source or converted mesh."""
    return {texture for _, texture, _ in _model_texture_slots(data)}


def _model_texture_slots(data):
    layout = model_layout(data)
    if not layout["textured"]:
        return
    separate = layout["version"] in (1, 3)
    offset = layout["texture_offset"] if separate else layout["color_offset"]
    for face in range(layout["faces"]):
        if not separate and not data[layout["render_offset"] + face] & 2:
            continue
        position = offset + face * 2
        value = int.from_bytes(data[position:position + 2], "big")
        texture = value - 1 if separate else value
        if texture not in (-1, 65535):
            if texture > 32767:
                raise ValueError("Texture ID exceeds signed-short renderer range")
            yield position, texture, int(separate)


def remap_model_textures(data, texture_id):
    """Remap existing texture references; never silently substitute a material."""
    result = bytearray(data)
    for offset, source, bias in _model_texture_slots(data):
        target = texture_id(source)
        if not 0 <= target <= 32767:
            raise ValueError(f"Texture ID out of 184 range: {target}")
        result[offset:offset + 2] = short(target + bias)
    return bytes(result)


def texture184(data, sprite_id):
    """Convert a revision-242 simplified texture, remapping its sprite group."""
    if len(data) != 7:
        raise ValueError("Expected a seven-byte revision-242 texture")
    sprite, average, opaque, direction, speed = struct.unpack(">HH3B", data)
    if opaque not in (0, 1) or direction > 4:
        raise ValueError("Unsupported texture flags")
    return short(average) + bytes([opaque, 1]) + short(sprite_id(sprite)) + bytes(4) + bytes([direction, speed])


def sprite184(data):
    """Validate classic indexed sprites; per-pixel alpha requires a new renderer."""
    if len(data) < 7:
        raise ValueError("Truncated sprite")
    count = int.from_bytes(data[-2:], "big")
    footer_offset = len(data) - 7 - count * 8
    if not count or footer_offset < 0:
        raise ValueError("Invalid sprite footer")
    footer = Reader(data[footer_offset:-2])
    width, height, colors = footer.uint(2), footer.uint(2), footer.uint() + 1
    xs = [footer.uint(2) for _ in range(count)]
    ys = [footer.uint(2) for _ in range(count)]
    widths = [footer.uint(2) for _ in range(count)]
    heights = [footer.uint(2) for _ in range(count)]
    palette_offset = footer_offset - (colors - 1) * 3
    if palette_offset < 0:
        raise ValueError("Truncated sprite palette")
    pixels = Reader(data[:palette_offset])
    for x, y, w, h in zip(xs, ys, widths, heights):
        if x + w > width or y + h > height:
            raise ValueError("Sprite pixels exceed canvas")
        flags = pixels.uint()
        if flags not in (0, 1):
            raise ValueError("Per-pixel alpha sprite unsupported by 184")
        if any(pixel >= colors for pixel in pixels.take(w * h)):
            raise ValueError("Sprite pixel exceeds palette")
    if pixels.offset != palette_offset:
        raise ValueError("Unread sprite pixels")
    return data


ITEM_KEEP = {1: 2, 4: 2, 5: 2, 6: 2, 7: 2, 8: 2, 11: 0, 12: 4, 16: 0,
             23: 3, 24: 2, 25: 3, 26: 2, 65: 0, 78: 2, 79: 2, 90: 2, 91: 2,
             92: 2, 93: 2, 95: 2, 97: 2, 98: 2, 110: 2, 111: 2, 112: 2,
             113: 1, 114: 1, 115: 1, 139: 2, 140: 2, 148: 2, 149: 2}
ITEM_MODEL = {1, 23, 24, 25, 26, 78, 79, 90, 91, 92, 93}
ITEM_WIDE = dict(zip(range(44, 55), (1, 23, 24, 78, 25, 26, 79, 90, 92, 91, 93)))
ITEM_LINK = {97, 98, 139, 140, 148, 149}
ITEM_SKIP = {10: 2, 13: 1, 14: 1, 15: 0, 27: 1, 42: 1, 66: 2, 67: 2, 68: 2,
             69: 1, 70: 2, 71: 2, 72: 2, 75: 2, 76: 2, 77: 2, 94: 2, 99: 2, 116: 2, 117: 2,
             118: 2, 119: 2, 121: 2, 122: 2, 125: 3, 126: 3, 127: 3, 128: 3,
             129: 3, 130: 3, 160: 0, 251: 0}
ITEM_SKIP.update({i: 2 for i in (*range(80, 88), *range(142, 147), *range(150, 155))})


def read_params(r):
    values = {}
    for _ in range(r.uint()):
        string = r.uint()
        key = r.uint(3)
        values[key] = r.string() if string else int.from_bytes(r.take(4), "big", signed=True)
    return values


def read_item(data, metadata=None, spans=None):
    r = Reader(data)
    fields = {}
    while op := r.uint():
        start = r.offset - 1
        if op in ITEM_KEEP:
            fields[op] = r.take(ITEM_KEEP[op])
        elif op in ITEM_WIDE:
            old = ITEM_WIDE[op]
            model = r.uint(4)
            offset = r.take(1) if old in (23, 25) else b""
            fields[old] = short(model) + offset
        elif op == 2 or 30 <= op < 40:
            fields[op] = r.string()
        elif op in (3, 9):
            value = r.string()
            if op == 3 and metadata is not None:
                metadata["examine"] = value[:-1].decode("cp1252", errors="replace")
        elif op in (40, 41):
            count = r.take(1)
            fields[op] = count + r.take(count[0] * 4)
        elif 100 <= op < 110:
            fields[op] = r.take(4)
        elif op in ITEM_SKIP:
            value = r.take(ITEM_SKIP[op])
            if metadata is not None and op in (13, 14, 27, 75):
                metadata[op] = int.from_bytes(value, "big", signed=op == 75)
        elif op == 43:
            r.take(1)
            while r.uint():
                r.string()
        elif op == 132:
            r.take(r.uint() * 2)
        elif op == 161:
            r.take(r.uint(2) * 2)
        elif op in (200, 201, 202):
            r.take({200: 2, 201: 13, 202: 15}[op])
            r.string()
        elif op == 249:
            values = read_params(r)
            if metadata is not None:
                metadata["params"] = values
        else:
            raise ValueError(f"Unsupported item opcode {op}")
        if spans is not None:
            spans.append((op, data[start:r.offset]))
    if r.offset != len(data):
        raise ValueError("Unread item bytes")
    return fields


def write_fields(fields):
    return b"".join(bytes([op]) + payload for op, payload in sorted(fields.items())) + b"\0"


@lru_cache(maxsize=65536)
def _smooth_noise(x, y):
    def noise(nx, ny):
        value = nx + ny * 57
        value = (value << 13) ^ value
        value = (value * (value * value * 15731 + 789221) + 1376312589) & 0x7fffffff
        return (value >> 19) & 255

    corners = sum(noise(x + dx, y + dy) for dx in (-1, 1) for dy in (-1, 1))
    sides = noise(x - 1, y) + noise(x + 1, y) + noise(x, y - 1) + noise(x, y + 1)
    return corners // 16 + sides // 8 + noise(x, y) // 4


def terrain_height(world_x, world_y):
    """Original OSRS procedural plane-zero height, in unsigned 8-unit steps."""
    def interpolated(x, y, scale):
        ix, fx = divmod(x, scale)
        iy, fy = divmod(y, scale)

        def blend(a, b, fraction):
            # Only cosine table entries 0,256,512,768 are used for scales 1,2,4.
            weight = (65536 - (65536, 46340, 0, -46340)[fraction * 4 // scale]) >> 1
            return ((65536 - weight) * a >> 16) + (weight * b >> 16)

        row0 = blend(_smooth_noise(ix, iy), _smooth_noise(ix + 1, iy), fx)
        row1 = blend(_smooth_noise(ix, iy + 1), _smooth_noise(ix + 1, iy + 1), fx)
        return blend(row0, row1, fy)

    x, y = world_x + 932731, world_y + 556238
    value = (interpolated(x + 45365, y + 91923, 4) - 128
             + ((interpolated(x + 10294, y + 37821, 2) - 128) >> 1)
             + ((interpolated(x, y, 1) - 128) >> 2))
    return max(10, min(60, int(value * 0.3) + 35))


def terrain184(data, overlay_id, underlay_id, source_origin=None):
    """Convert terrain; source_origin=(worldX, worldY) fixes relocated noise."""
    r = Reader(data)
    output = bytearray()
    for tile in range(4 * 64 * 64):
        while True:
            op = r.uint(2)
            if op <= 1:
                if op == 0 and tile < 64 * 64 and source_origin is not None:
                    x, y = divmod(tile, 64)
                    output.extend((1, terrain_height(source_origin[0] + x, source_origin[1] + y)))
                    break
                output.append(op)
                if op == 1:
                    output.extend(r.take(1))
                break
            if op <= 49:
                overlay = r.uint(2)
                output.extend((op, overlay_id(overlay - 1) + 1) if overlay else (op, 0))
            elif op <= 81:
                output.append(op)
            else:
                output.append(82 + underlay_id(op - 82))
    # Revision 242's terrain files carry one zero terminator after the tiles.
    # The donor and 184 clients ignore this tail; only accept the observed empty
    # trailer so an unfamiliar environment extension cannot silently disappear.
    if data[r.offset:] not in (b"", b"\0"):
        raise ValueError("Unread terrain bytes")
    return bytes(output)


def landscape(data):
    r = Reader(data)
    objects = {}
    object_id = -1
    while True:
        delta = r.smart()
        if delta == 0:
            break
        increment = delta
        while increment == 32767:
            increment = r.smart()
            delta += increment
        object_id += delta
        position = 0
        placements = []
        while offset := r.smart():
            position += offset - 1
            flags = r.uint()
            if position >= 16384:
                raise ValueError("Invalid object position")
            placements.append((position, flags))
        objects[object_id] = placements
    if r.offset != len(data):
        raise ValueError("Unread landscape bytes")
    return objects


def landscape184(objects):
    output = bytearray()
    previous = -1
    for object_id, placements in sorted(objects.items()):
        gap = object_id - previous
        # The legacy server reads one unsigned smart per object ID delta.
        # A continuation is represented by an unused ID with zero placements.
        # 32767 is a continuation sentinel for the client, but the server
        # reads one smart. Use 32766 so both decoders consume the dummy alike.
        if gap <= 0:
            raise ValueError("Object IDs must be nonnegative and increasing")
        while gap >= 32767:
            output.extend(smart(32766) + b"\0")
            gap -= 32766
        output.extend(smart(gap))
        previous = object_id
        position = 0
        for offset, flags in sorted(placements):
            if not 0 <= offset < 16384 or not 0 <= flags <= 255:
                raise ValueError("Invalid object placement")
            output.extend(smart(offset - position + 1))
            output.append(flags)
            position = offset
        output.append(0)
    output.append(0)
    return bytes(output)


LOC_KEEP = {14: 1, 15: 1, 17: 0, 18: 0, 19: 1, 21: 0, 22: 0, 23: 0, 27: 0,
            28: 1, 29: 1, 39: 1, 62: 0, 64: 0, 65: 2, 66: 2, 67: 2, 69: 1,
            70: 2, 71: 2, 72: 2, 73: 0, 74: 0, 75: 1, 81: 1}
LOC_SKIP = {25: 0, 44: 2, 45: 2, 60: 2, 61: 2, 68: 2, 82: 2, 88: 0, 89: 0,
            90: 0, 91: 1, 93: 6, 94: 0, 95: 1, 96: 1, 99: 3, 103: 0, 104: 1,
            105: 0, 107: 2, 163: 4, 167: 2, 168: 0, 169: 0, 173: 4, 176: 0,
            177: 1, 178: 1, 189: 0, 190: 1, 191: 1}


def object184(data, model_id):
    r = Reader(data)
    fields = {}
    morphs = []
    while op := r.uint():
        if op in LOC_KEEP:
            fields[op] = r.take(LOC_KEEP[op])
        elif op in (1, 5, 6, 7):
            count = r.uint()
            payload = bytearray([count])
            for _ in range(count):
                source = r.uint(4 if op in (6, 7) else 2)
                payload.extend(short(model_id(source)))
                if op in (1, 6):
                    payload.extend(r.take(1))
            fields[{6: 1, 7: 5}.get(op, op)] = bytes(payload)
        elif op == 2:
            fields[2] = r.string()
        elif op == 3 or 30 <= op < 39 or 150 <= op < 155:
            r.string()
        elif op in (40, 41):
            count = r.uint()
            fields[op] = bytes([count]) + r.take(count * 4)
        elif op in LOC_SKIP:
            r.take(LOC_SKIP[op])
        elif op == 24:
            r.take(2)
        elif op in (77, 92, 97, 98):
            r.take(4)
            width = 3 if op in (97, 98) else 2
            fallback = r.uint(width) if op in (92, 98) else -1
            variants = [r.uint(width) for _ in range(r.uint() + 1)]
            morphs = [i for i in [fallback] + variants if 0 <= i < 63334]
        elif op == 78:
            r.take(4)
        elif op == 79:
            r.take(6)
            r.take(r.uint() * 2)
        elif op in (80, 106, 160):
            r.take(r.uint() * {80: 4, 106: 3, 160: 2}[op])
        elif op in (100, 101, 102):
            r.take({100: 2, 101: 13, 102: 15}[op])
            r.string()
        elif op in (170, 171):
            r.smart()
        elif op == 249:
            read_params(r)
        else:
            raise ValueError(f"Unsupported scenery opcode {op}")
    if r.offset != len(data):
        raise ValueError("Unread scenery bytes")
    return fields, morphs


def sequence184(data, frame_id):
    r = Reader(data)
    out = bytearray()
    has_frames = False
    while op := r.uint():
        if op in (1, 12):
            count = r.uint(2 if op == 1 else 1)
            lengths = r.take(count * 2) if op == 1 else b""
            frames = r.take(count * 2)
            group_ids = [r.uint(2) for _ in range(count)]
            if op == 1:
                has_frames = bool(count)
            out.extend(bytes([op]) + (short(count) if op == 1 else bytes([count])) + lengths
                       + frames + b"".join(short(frame_id(i)) for i in group_ids))
        elif op == 3:
            count = r.uint()
            out.extend(bytes([op, count]) + r.take(count))
        elif 2 <= op <= 11:
            count = 2 if op in (2, 6, 7) else 0 if op == 4 else 1
            out.extend(bytes([op]) + r.take(count))
        elif op == 13:
            raise ValueError("Weighted animation has no legacy frame sequence")
        elif op == 14:
            r.take(r.uint(2) * 8)
        elif op == 15:
            r.take(4)
        elif op == 16:
            r.take(1)
        elif op == 17:
            r.take(r.uint())
        elif op == 18:
            r.string()
        elif op != 19:
            raise ValueError(f"Unsupported animation opcode {op}")
    if r.offset != len(data) or not has_frames:
        raise ValueError("No complete legacy animation")
    return bytes(out) + b"\0"
