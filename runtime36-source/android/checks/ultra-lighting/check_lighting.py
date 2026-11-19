"""Render actual Ultra shaders offscreen and check their display pixels.

Run with Python 3 on an EGL/GLES 3 host. Windows: pass --angle-dir with a matching
libEGL.dll and libGLESv2.dll (for example, the Android emulator or a JBR bin directory).
Linux: install the host's EGL/GLES implementation. No pip packages are required.
Add --output result.json to record the measured values and GL renderer identity.
"""

import argparse
import ctypes
import json
import math
import os
from pathlib import Path

from offscreen_gles import GL, OffscreenGles, chars, integer, load_shader, pointer, ptr, real, ref, uint, uint8


SCENE_SIZE = 64
DAY_HAZE = (0.274, 0.367, 0.462)
NIGHT_HAZE = (0.0153, 0.0245, 0.0470)
SOURCE = Path(__file__).resolve().parents[2] / "kronos/src/main/cpp"


def unit(vector):
    length = math.sqrt(sum(value * value for value in vector))
    return tuple(value / length for value in vector)


def require(condition, detail):
    if not condition:
        raise AssertionError(detail)


class LightingFixture:
    def __init__(self, host):
        self.host = host
        self.gl = host.gl
        vertex = load_shader(SOURCE, "ultra_surface.h", "ultraVertex")
        fragment = load_shader(SOURCE, "ultra_surface.h", "ultraFragment")
        self.surface = host.link(vertex, fragment)
        with_shadows = "#version 300 es\n#define MOBILE_HD_SHADOWS 1"
        host.link(vertex.replace("#version 300 es", with_shadows, 1),
                  fragment.replace("#version 300 es", with_shadows, 1))
        self.resolve = host.link(load_shader(SOURCE, "ultra.h", "vertex"), load_shader(SOURCE, "ultra.h", "fragment"))

        self.bind_framebuffer = self.gl("BindFramebuffer", None, uint, uint)
        self.attach = self.gl("FramebufferTexture2D", None, uint, uint, uint, uint, integer)
        self.active_texture = self.gl("ActiveTexture", None, uint)
        self.bind_texture = self.gl("BindTexture", None, uint, uint)
        self.use = self.gl("UseProgram", None, uint)
        self.draw = self.gl("DrawArrays", None, uint, integer, integer)
        self.clear = self.gl("Clear", None, uint)
        self.clear_color = self.gl("ClearColor", None, real, real, real, real)
        self.clear_depth = self.gl("ClearDepthf", None, real)
        self.enable = self.gl("Enable", None, uint)
        self.disable = self.gl("Disable", None, uint)
        self.scene_framebuffer = host.generate("Framebuffers")
        self.bind_framebuffer(GL["FRAMEBUFFER"], self.scene_framebuffer)
        self.color = self.attachment("SRGB8_ALPHA8", "COLOR_ATTACHMENT0", "LINEAR")
        self.depth = self.attachment("DEPTH_COMPONENT24", "DEPTH_ATTACHMENT", "NEAREST")
        self.check_framebuffer()
        self.output_framebuffer = host.generate("Framebuffers")
        self.bind_framebuffer(GL["FRAMEBUFFER"], self.output_framebuffer)
        self.attachment("RGBA8", "COLOR_ATTACHMENT0", "NEAREST")
        self.check_framebuffer()

        # Distinct opaque and translucent source texels expose wrong layer rounding,
        # including at both ends of the capture's 256-layer budget.
        atlas = host.generate("Textures")
        self.active_texture(GL["TEXTURE0"])
        self.bind_texture(GL["TEXTURE_2D_ARRAY"], atlas)
        self.gl("TexStorage3D", None, uint, integer, uint, integer, integer, integer)(
            GL["TEXTURE_2D_ARRAY"], 1, GL["RGBA8"], 1, 1, 256)
        upload = self.gl("TexSubImage3D", None, uint, integer, integer, integer, integer, integer, integer, integer,
                         uint, uint, ptr)
        for layer, rgba in ((0, (255, 255, 255, 255)), (1, (81, 161, 219, 255)),
                            (2, (249, 74, 26, 105)), (255, (152, 82, 187, 255))):
            texel = (uint8 * 4)(*rgba)
            upload(GL["TEXTURE_2D_ARRAY"], 0, 0, 0, layer, 1, 1, 1, GL["RGBA"], GL["UNSIGNED_BYTE"], texel)

        self.gl("BindVertexArray", None, uint)(host.generate("VertexArrays"))
        self.gl("BindBuffer", None, uint, uint)(GL["ARRAY_BUFFER"], host.generate("Buffers"))
        self.gl("EnableVertexAttribArray", None, uint)(0)
        self.gl("VertexAttribPointer", None, uint, integer, uint, uint8, integer, ptr)(0, 3, GL["FLOAT"], 0, 0, None)
        self.gl("Viewport", None, integer, integer, integer, integer)(0, 0, SCENE_SIZE, SCENE_SIZE)
        self.mesh_key = None
        self.gl("FrontFace", None, uint)(0x0901)  # GL_CCW, as in the native game pass
        self.disable(0x0B44)  # GL_CULL_FACE: scenery remains two-sided in the game
        self.use(self.surface)
        identity = (real * 9)(1, 0, 0, 0, 1, 0, 0, 0, 1)
        location = self.gl("GetUniformLocation", integer, uint, chars)(self.surface, b"cameraToWorld")
        self.gl("UniformMatrix3fv", None, integer, integer, uint8, pointer(real))(location, 1, 0, identity)
        for name, value in {"projection": (64, 64, 64), "center": (32, 32), "atlas": 0}.items():
            host.uniform(self.surface, name, value)
        host.check()

    def attachment(self, fmt, point, filter_mode):
        tex = self.host.generate("Textures")
        self.bind_texture(GL["TEXTURE_2D"], tex)
        self.gl("TexStorage2D", None, uint, integer, uint, integer, integer)(
            GL["TEXTURE_2D"], 1, GL[fmt], SCENE_SIZE, SCENE_SIZE)
        params = self.gl("TexParameteri", None, uint, uint, integer)
        for key in (0x2800, 0x2801):  # magnification and minification
            params(GL["TEXTURE_2D"], key, GL[filter_mode])
        for key in (0x2802, 0x2803):  # wrap S and T
            params(GL["TEXTURE_2D"], key, 0x812F)
        self.attach(GL["FRAMEBUFFER"], GL[point], GL["TEXTURE_2D"], tex, 0)
        return tex

    def check_framebuffer(self):
        result = self.gl("CheckFramebufferStatus", uint, uint)(GL["FRAMEBUFFER"])
        require(result == GL["FRAMEBUFFER_COMPLETE"], f"Incomplete framebuffer 0x{result:x}")

    def draw_surface(self, mask, normal=(0, -1, 0), shade=(.48, .48, .48), layer=-1,
                     light=None, alpha=1.0, clear=True, depth=1000, clear_only=False, reverse=False, phase=None):
        self.bind_framebuffer(GL["FRAMEBUFFER"], self.scene_framebuffer)
        self.use(self.surface)
        night = mask & 256
        haze = NIGHT_HAZE if night else DAY_HAZE
        sun = unit((.55, -.67, .50) if night else (-.52, -.74, -.42))
        if phase is None:
            phase = int(alpha < .99)
        values = {"lightDirection": sun, "settings": mask, "hazeColor": haze,
                  "localLightCount": int(light is not None), "phase": phase}
        if light is not None:
            values.update({"localLights[0]": light, "localLightColors[0]": (2.8, 1.0, .34)})
        for name, value in values.items():
            self.host.uniform(self.surface, name, value)
        if clear:
            self.clear_color(*(c * .5 for c in haze), 1)
            self.clear_depth(1)
            self.clear(GL["COLOR_BUFFER_BIT"] | GL["DEPTH_BUFFER_BIT"])
        if clear_only:
            return

        # With the measured focal length, this one triangle fills the scene at any tested depth.
        if self.mesh_key != (depth, reverse):
            corners = [(-.5, .5, 1), (1.5, .5, 1), (-.5, -1.5, 1)]
            if reverse:
                corners[1], corners[2] = corners[2], corners[1]
            positions = (real * 9)(*(coordinate * depth for v in corners for coordinate in v))
            self.gl("BufferData", None, uint, ctypes.c_ssize_t, ptr, uint)(
                GL["ARRAY_BUFFER"], ctypes.sizeof(positions), positions, GL["STATIC_DRAW"])
            self.mesh_key = (depth, reverse)
        self.gl("VertexAttrib4f", None, uint, real, real, real, real)(1, *shade, alpha)
        attr3 = self.gl("VertexAttrib3f", None, uint, real, real, real)
        attr3(2, *normal)
        attr3(3, 0, 0, layer)
        self.disable(GL["DEPTH_TEST"])
        if phase == 1:
            self.enable(GL["BLEND"])
            self.gl("BlendFuncSeparate", None, uint, uint, uint, uint)(0x302, 0x303, 1, 0x303)
        else:
            self.disable(GL["BLEND"])
        self.draw(GL["TRIANGLES"], 0, 3)

    def read_display(self, mask):
        self.bind_framebuffer(GL["FRAMEBUFFER"], self.output_framebuffer)
        self.disable(GL["DEPTH_TEST"])
        self.disable(GL["BLEND"])
        self.use(self.resolve)
        for texture_unit, texture_id in ((0, self.color), (2, self.depth)):
            self.active_texture(GL["TEXTURE0"] + texture_unit)
            self.bind_texture(GL["TEXTURE_2D"], texture_id)
        values = {"scene": 0, "depthMap": 2, "viewLens": (64, 64, 32, 32),
                  "pixel": (1 / SCENE_SIZE, 1 / SCENE_SIZE), "settings": mask}
        for name, value in values.items():
            self.host.uniform(self.resolve, name, value)
        self.draw(GL["TRIANGLES"], 0, 3)
        pixel = (uint8 * 4)()
        self.gl("ReadPixels", None, integer, integer, integer, integer, uint, uint, ptr)(
            32, 32, 1, 1, GL["RGBA"], GL["UNSIGNED_BYTE"], ref(pixel))
        self.host.check()
        return tuple(pixel[:3])

    def probe(self, mask, **args):
        self.draw_surface(mask, **args)
        return self.read_display(mask)

    def depth_step(self, foreground):
        self.bind_framebuffer(GL["FRAMEBUFFER"], self.scene_framebuffer)
        self.clear_color(.13, .13, .13, 1)
        self.clear(GL["COLOR_BUFFER_BIT"])
        self.enable(GL["SCISSOR_TEST"])
        for x, z in ((32, 1000), (0, foreground)):
            self.gl("Scissor", None, integer, integer, integer, integer)(x, 0, 32, 64)
            self.clear_depth((6144.0 - 307200.0 / z) / 6094.0)
            self.clear(GL["DEPTH_BUFFER_BIT"])
        self.disable(GL["SCISSOR_TEST"])


def linear(byte):
    s = byte / 255
    return s / 12.92 if s <= .04045 else ((s + .055) / 1.055) ** 2.4


def display(linear_value):
    s = 12.92 * linear_value if linear_value <= .0031308 else 1.055 * linear_value ** (1 / 2.4) - .055
    return round(255 * s)


def run(host):
    fixture = LightingFixture(host)
    probe = fixture.probe
    day = probe(16)
    night = probe(272)
    require(min(a - b for a, b in zip(day, night)) >= 15, ("night exposure", day, night))
    require(night[2] / night[0] > 1.15 * day[2] / day[0], ("night chromaticity", day, night))
    lit = probe(272, light=(0, -110, 1000, 330))
    outside = probe(272, light=(0, -110, 3400, 220))
    require(lit[0] > night[0] + 12 and lit[0] - night[0] > lit[2] - night[2], ("warm light", lit, night))
    require(outside == night, ("outside local-light radius", outside, night))

    sun = unit((-.52, -.74, -.42))
    highlight = unit((sun[0], sun[1], sun[2] - 1))
    stone = probe(16, normal=highlight)
    armor = probe(16, normal=highlight, layer=-2)
    cloth = probe(16, normal=highlight, shade=(.65, .08, .10), layer=-2)
    pigment = probe(16, normal=highlight, shade=(.65, .08, .10))
    require(armor[0] - stone[0] > 8, ("neutral player highlight", armor, stone))
    require(cloth[0] - pigment[0] < armor[0] - stone[0], ("saturated fabric", cloth, pigment))

    # Paired, oppositely wound cape sides must select the visible face. Existing scenery
    # remains two-sided and fractional tags preserve the cache's original layer and alpha.
    clear = probe(0, clear_only=True)
    winding = {}
    for layer in (-1, 0, 1, 2, 255):
        tag = -2 if layer == -1 else layer + .25
        phase = int(layer == 2)  # only the red texel has partial alpha
        front = probe(0, layer=layer, phase=phase)
        tagged = probe(0, layer=tag, phase=phase)
        world_back = probe(0, layer=layer, phase=phase, reverse=True)
        player_back = probe(0, layer=tag, phase=phase, reverse=True)
        require(tagged == front == world_back and front != clear and player_back == clear,
                ("visible face/texture layer", layer, front, tagged, world_back, player_back, clear))
        winding[str(layer)] = {"front": front, "sceneryBack": world_back, "playerBack": player_back}
    require(len({tuple(winding[str(layer)]["front"]) for layer in (0, 1, 2, 255)}) == 4, "atlas probes collapsed")
    require(probe(0, layer=2.25, phase=0) == clear, "translucent actor texel leaked into the opaque pass")

    # Native cache alpha is byte-based. Both immediate neighbours of each phase
    # threshold must continue to render in exactly the same pass as the fragment test.
    alpha_cases = 0
    for layer in (-2, -1, 0, .25, 2, 2.25):
        texture_alpha = 105 / 255 if layer in (2, 2.25) else 1.0
        for byte_alpha in (2, 3, 252, 253, 255):
            alpha = byte_alpha / 255
            effective = alpha * texture_alpha
            for phase in (0, 1):
                visible = effective >= .99 if phase == 0 else .01 <= effective < .99
                pixel = probe(0, layer=layer, shade=(1, 0, 0), alpha=alpha, phase=phase)
                require((pixel != clear) == visible, ("phase threshold", layer, byte_alpha, phase, pixel, clear))
                alpha_cases += 1

    back, front = (.23, .09, .04), (.04, .17, .39)
    back_pixel, front_pixel = probe(0, shade=back), probe(0, shade=front)
    fixture.draw_surface(0, shade=back)
    fixture.draw_surface(0, shade=front, alpha=.5, clear=False)
    blended = fixture.read_display(0)
    predicted = tuple(display(.5 * (linear(a) + linear(b))) for a, b in zip(back_pixel, front_pixel))
    require(max(abs(a - b) for a, b in zip(blended, predicted)) <= 3, ("linear blend", blended, predicted))
    haze = {}
    for label, mask in (("day", 32), ("night", 288)):
        background = probe(mask, clear_only=True)
        far_geometry = probe(mask, depth=6050)
        require(max(abs(a - b) for a, b in zip(background, far_geometry)) <= 1,
                ("fog/clear seam", label, background, far_geometry))
        haze[label] = {"clear": background, "farSurface": far_geometry}

    fixture.depth_step(1000)
    flat, plain = fixture.read_display(3), fixture.read_display(0)
    require(flat == plain, ("flat-plane AO", flat, plain))
    fixture.depth_step(979)
    contact = fixture.read_display(3)
    require(contact[0] < plain[0] - 2, ("close-depth AO", contact, plain))
    fixture.depth_step(820)
    halo = fixture.read_display(3)
    require(halo == plain, ("out-of-range AO halo", halo, plain))
    # Includes all quality masks as configured by the launcher plus day/night.
    for mask in (80, 117, 254, 255):
        for night_bit in (0, 256):
            probe(mask | night_bit)
    return {
        "renderer": host.gl("GetString", chars, uint)(GL["RENDERER"]).decode(),
        "programsLinked": ["surface", "surface+shadows", "resolve"],
        "day": day, "night": night, "warmSource": lit, "outsideSource": outside,
        "neutralPlayer": armor, "stone": stone, "fabric": cloth, "pigment": pigment,
        "linearBlend": {"measured": blended, "expected": predicted}, "skySeam": haze, "winding": winding,
        "alphaPhaseCases": alpha_cases,
        "flatAmbientOcclusion": flat, "plain": plain, "contact": contact, "outOfRange": halo,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--angle-dir", default=os.environ.get("ANGLE_LIB_DIR"), help="directory containing ANGLE DLLs")
    parser.add_argument("--output", type=Path, help="optional JSON report; stdout is always populated")
    args = parser.parse_args()
    host = OffscreenGles(SCENE_SIZE, SCENE_SIZE, args.angle_dir)
    try:
        report = json.dumps(run(host), indent=2) + "\n"
    finally:
        host.close()
    if args.output:
        args.output.write_text(report, encoding="utf-8")
    print(report, end="")


if __name__ == "__main__":
    main()
