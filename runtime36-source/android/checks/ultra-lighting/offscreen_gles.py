"""Small GLES 3 pbuffer / production shader loader for the offline renderer checks."""

import ctypes
import ctypes.util
import os
import re
import sys
from pathlib import Path


uint = ctypes.c_uint
integer = ctypes.c_int
real = ctypes.c_float
ptr = ctypes.c_void_p
chars = ctypes.c_char_p
uint8 = ctypes.c_ubyte
ref = ctypes.byref
pointer = ctypes.POINTER

EGL_NONE = 0x3038
GL = {
    "ARRAY_BUFFER": 0x8892, "BLEND": 0x0BE2, "COLOR_ATTACHMENT0": 0x8CE0,
    "COLOR_BUFFER_BIT": 0x4000, "DEPTH_ATTACHMENT": 0x8D00, "DEPTH_BUFFER_BIT": 0x0100,
    "DEPTH_COMPONENT24": 0x81A6, "DEPTH_TEST": 0x0B71, "FLOAT": 0x1406, "FRAMEBUFFER": 0x8D40,
    "FRAMEBUFFER_COMPLETE": 0x8CD5, "LINEAR": 0x2601, "NEAREST": 0x2600, "RENDERER": 0x1F01,
    "RGBA": 0x1908, "RGBA8": 0x8058, "SCISSOR_TEST": 0x0C11, "SRGB8_ALPHA8": 0x8C43,
    "STATIC_DRAW": 0x88E4, "TEXTURE0": 0x84C0, "TEXTURE_2D": 0x0DE1,
    "TEXTURE_2D_ARRAY": 0x8C1A, "TRIANGLES": 4, "UNSIGNED_BYTE": 0x1401,
}


def bind(library, name, result, *args):
    function = getattr(library, name)
    function.restype = result
    function.argtypes = args
    return function


def attributes(*args):
    return (integer * (len(args) + 1))(*args, EGL_NONE)


def load_shader(directory, filename, name):
    """Read the same concatenated C++ literals (including .inc files) used in the APK."""
    text = (directory / filename).read_text(encoding="utf-8")
    declaration = re.search(r'const char\*\s+' + re.escape(name) + r'\s*=\s*R"\(', text)
    if not declaration:
        raise ValueError(f"Shader {name} is missing from {filename}")
    source = text[declaration.end():].split(')";', 1)[0]

    def include(match):
        part = (directory / match.group(1)).read_text(encoding="utf-8").strip()
        if not (part.startswith('R"(') and part.endswith(')"')):
            raise ValueError(f"{match.group(1)} is not a raw GLSL string")
        return part[3:-2]

    return re.sub(r'\)"\s*#include\s+"([^"]+)"\s*R"\(', include, source)


class OffscreenGles:
    def __init__(self, width, height, angle_dir=None):
        self.width, self.height = width, height
        # Keep the directory handle alive for ANGLE's own dependent DLL lookups.
        self.dll_directory = None
        if sys.platform == "win32":
            factory = ctypes.WINFUNCTYPE
            if angle_dir:
                folder = Path(angle_dir).resolve(strict=True)
                self.dll_directory = os.add_dll_directory(str(folder))
                self.egl = ctypes.WinDLL(str(folder / "libEGL.dll"))
                self.gles = ctypes.WinDLL(str(folder / "libGLESv2.dll"))
            else:
                self.egl = ctypes.WinDLL("libEGL.dll")
                self.gles = ctypes.WinDLL("libGLESv2.dll")
        else:
            factory = ctypes.CFUNCTYPE
            self.egl = ctypes.CDLL(ctypes.util.find_library("EGL") or "libEGL.so.1")
            self.gles = ctypes.CDLL(ctypes.util.find_library("GLESv2") or "libGLESv2.so.2")

        get_proc = bind(self.egl, "eglGetProcAddress", ptr, chars)
        address = get_proc(b"eglGetPlatformDisplayEXT")
        get_platform = factory(ptr, uint, ptr, pointer(integer))(address) if address else None
        init = bind(self.egl, "eglInitialize", uint, ptr, pointer(integer), pointer(integer))
        self.display = None
        if get_platform:
            # WARP keeps Windows Server / headless VMs reproducible. Mesa surfaceless
            # works without a display server on Linux. The default EGL route is the fallback.
            candidates = [(0x3202, (0x3203, 0x3208, 0x3209, 0x320B)), (0x3202, ())] \
                if sys.platform == "win32" else [(0x31DD, ())]
            for platform, config in candidates:
                display = get_platform(platform, None, attributes(*config))
                if display and init(display, None, None):
                    self.display = display
                    break
        if not self.display:
            self.display = bind(self.egl, "eglGetDisplay", ptr, ptr)(None)
            if not self.display or not init(self.display, None, None):
                raise RuntimeError("No usable EGL display. Supply an ANGLE installation or Mesa EGL.")

        config, count = ptr(), integer()
        # Pbuffer + OPENGL_ES3_BIT + 8-bit color channels. Tests allocate their own scene depth.
        spec = attributes(0x3033, 1, 0x3040, 0x40, 0x3024, 8, 0x3023, 8, 0x3022, 8, 0x3021, 8)
        choose = bind(self.egl, "eglChooseConfig", uint, ptr, pointer(integer), pointer(ptr), integer, pointer(integer))
        if not choose(self.display, spec, ref(config), 1, ref(count)) or not count.value:
            raise RuntimeError("EGL did not offer an RGBA8 GLES 3 pbuffer configuration")
        self.surface = bind(self.egl, "eglCreatePbufferSurface", ptr, ptr, ptr, pointer(integer))(
            self.display, config, attributes(0x3057, width, 0x3056, height))
        self.context = bind(self.egl, "eglCreateContext", ptr, ptr, ptr, ptr, pointer(integer))(
            self.display, config, None, attributes(0x3098, 3))
        self.make_current = bind(self.egl, "eglMakeCurrent", uint, ptr, ptr, ptr, ptr)
        if not self.surface or not self.context or not self.make_current(
                self.display, self.surface, self.surface, self.context):
            raise RuntimeError("EGL could not create and activate the GLES 3 context")
        self.calls = {}

    def close(self):
        self.make_current(self.display, None, None, None)
        bind(self.egl, "eglDestroyContext", uint, ptr, ptr)(self.display, self.context)
        bind(self.egl, "eglDestroySurface", uint, ptr, ptr)(self.display, self.surface)
        bind(self.egl, "eglTerminate", uint, ptr)(self.display)
        if self.dll_directory:
            self.dll_directory.close()

    def gl(self, name, result, *args):
        if name not in self.calls:
            self.calls[name] = bind(self.gles, "gl" + name, result, *args)
        return self.calls[name]

    def check(self):
        error = self.gl("GetError", uint)()
        if error:
            raise RuntimeError(f"GLES error 0x{error:04x}")

    def generate(self, kind):
        value = uint()
        self.gl("Gen" + kind, None, integer, pointer(uint))(1, ref(value))
        return value.value

    def link(self, vertex, fragment):
        def check_build(kind, object_id, status_id):
            status = integer()
            self.gl("Get" + kind + "iv", None, uint, uint, pointer(integer))(object_id, status_id, ref(status))
            if not status.value:
                size = integer()
                self.gl("Get" + kind + "iv", None, uint, uint, pointer(integer))(object_id, 0x8B84, ref(size))
                message = ctypes.create_string_buffer(max(1, size.value))
                self.gl("Get" + kind + "InfoLog", None, uint, integer, ptr, ptr)(object_id, len(message), None, message)
                raise RuntimeError(message.value.decode(errors="replace"))

        program = self.gl("CreateProgram", uint)()
        for kind, source in [(0x8B31, vertex), (0x8B30, fragment)]:
            shader = self.gl("CreateShader", uint, uint)(kind)
            body = chars(source.encode())
            self.gl("ShaderSource", None, uint, integer, pointer(chars), ptr)(shader, 1, ref(body), None)
            self.gl("CompileShader", None, uint)(shader)
            check_build("Shader", shader, 0x8B81)
            self.gl("AttachShader", None, uint, uint)(program, shader)
            self.gl("DeleteShader", None, uint)(shader)
        self.gl("LinkProgram", None, uint)(program)
        check_build("Program", program, 0x8B82)
        return program

    def uniform(self, program, name, value):
        location = self.gl("GetUniformLocation", integer, uint, chars)(program, name.encode())
        if location < 0:
            raise RuntimeError(f"Missing uniform {name}")
        if isinstance(value, int):
            self.gl("Uniform1i", None, integer, integer)(location, value)
        else:
            self.gl(f"Uniform{len(value)}f", None, integer, *([real] * len(value)))(location, *value)
