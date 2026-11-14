"""Explicit new-client K242 v1 models; legacy geometry plus lossless bone weights.

Never use this encoder for an unmodified revision184 client. Complex texture
mapping remains rejected by model_layout; large ordinary meshes are supported
by the new renderer's bounded dynamic scratch arrays.
"""
from cache_store import Reader
from legacy_assets import model_layout, remap_model_textures, short


def weighted_model184(data, texture_id=None):
    layout = model_layout(data)
    original = data
    version = layout["version"]
    if version in (2, 3):
        footer = data[-layout["footer_size"]:-2]
        start = layout["skin_offset"]
        keep = layout["vertices"] if layout["legacy_vertex_groups"] else 0
        end = -26 if version == 3 else layout["mesh_end"]
        data = data[:start + keep] + data[start + layout["skin_size"]:end]
        data += (footer[:11] + footer[12:-2] + b"\xff\xff" if version == 3
                 else footer[:10] + footer[11:-2])
    if texture_id is not None:
        data = remap_model_textures(data, texture_id)
    if not layout["weighted"]:
        return data
    skin = Reader(original[layout["skin_offset"]:layout["skin_offset"] + layout["skin_size"]])
    skin.take(layout["vertices"] if layout["legacy_vertex_groups"] else 0)
    out = bytearray(b"K242\1" + len(data).to_bytes(4, "big") + data + short(layout["vertices"]))
    for _ in range(layout["vertices"]):
        count = skin.uint()
        out.append(count)
        for _ in range(count):
            bone, weight = skin.uint(), skin.uint()
            out.extend(short(bone) + bytes([weight]))
    if skin.offset != len(skin.data):
        raise ValueError("Unread weighted model skin")
    return bytes(out)
