"use strict";

function run() {
    const canvas = document.querySelector("canvas");
    const {width, height, parameters: p} = capture.metadata;
    canvas.width = width;
    canvas.height = height;
    const gl = canvas.getContext("webgl2", {alpha: false, antialias: false, preserveDrawingBuffer: true});
    if (!gl) throw Error("WebGL2 unavailable");
    const check = (condition, message) => { if (!condition) throw Error(message); };
    const bytes = raw => Uint8Array.from(atob(raw), c => c.charCodeAt(0));
    const size = width * height;
    const uiPixels = bytes(capture.ui);
    const get = (prog, name) => gl.getUniformLocation(prog, name);
    const noErrors = where => {
        const e = gl.getError();
        if (e !== gl.NO_ERROR) throw Error(where + " GL 0x" + e.toString(16));
    };

    function program(vertex, fragment, shadows = false) {
        const result = gl.createProgram();
        for (let [kind, source] of [[gl.VERTEX_SHADER, vertex], [gl.FRAGMENT_SHADER, fragment]]) {
            if (shadows) source = source.replace("\n", "\n#define MOBILE_HD_SHADOWS 1\n");
            const shader = gl.createShader(kind);
            gl.shaderSource(shader, source);
            gl.compileShader(shader);
            check(gl.getShaderParameter(shader, gl.COMPILE_STATUS), gl.getShaderInfoLog(shader));
            gl.attachShader(result, shader);
            gl.deleteShader(shader);
        }
        gl.linkProgram(result);
        check(gl.getProgramParameter(result, gl.LINK_STATUS), gl.getProgramInfoLog(result));
        return result;
    }

    const programs = {};
    for (const [version, src] of Object.entries(capture.source)) {
        programs[version] = {
            caster: program(src.shadowVertex, src.caster),
            surface: [program(src.ultraVertex, src.ultraFragment), program(src.ultraVertex, src.ultraFragment, true)],
            resolve: program(src.vertex, src.fragment),
            ui: program(src.uiVertex, src.uiFragment)
        };
    }

    // Test instrumentation reads the actual opaque pass depth as 24 bits.
    const witness = program(capture.source.current.vertex, `#version 300 es
        precision highp float; precision highp int; precision highp sampler2D;
        in vec2 uv; uniform sampler2D depthMap; out vec4 color;
        void main(){uint d = uint(round(texture(depthMap, uv).r * 16777215.0));
            color=vec4(float((d>>16u)&255u),float((d>>8u)&255u),float(d&255u),255.0)/255.0;}`);
    const vao = gl.createVertexArray();
    gl.bindVertexArray(vao);
    gl.bindBuffer(gl.ARRAY_BUFFER, gl.createBuffer());
    const packedMesh = bytes(capture.mesh);
    gl.bufferData(gl.ARRAY_BUFFER, packedMesh, gl.STATIC_DRAW);
    const samples = new Float32Array(packedMesh.buffer, packedMesh.byteOffset, packedMesh.byteLength/4);
    const phaseInputs = {triangles:p[0]/3, constantAlphaAndLayer:0, skippedOpaqueTriangles:0, skippedTranslucentTriangles:0};
    for (let i = 0; i < samples.length; i += 39) {
        const alpha = samples[i+6], layer = samples[i+12];
        if (alpha===samples[i+19] && alpha===samples[i+32] && layer===samples[i+25] && layer===samples[i+38])
            ++phaseInputs.constantAlphaAndLayer;
        if (alpha < .99) ++phaseInputs.skippedOpaqueTriangles;
        if (alpha < .01 || (layer < 0 && alpha >= .99)) ++phaseInputs.skippedTranslucentTriangles;
    }
    [3,4,3,3].forEach((length, i) => {
        gl.enableVertexAttribArray(i);
        gl.vertexAttribPointer(i, length, gl.FLOAT, false, 52, [0,12,28,40][i]);
    });

    const tex2 = gl.TEXTURE_2D, texArray = gl.TEXTURE_2D_ARRAY, target = gl.FRAMEBUFFER;
    const unit = n => gl.activeTexture(gl.TEXTURE0 + n);
    function filtering(kind, min, mag, wrap) {
        for (const [key, val] of [[gl.TEXTURE_MIN_FILTER, min], [gl.TEXTURE_MAG_FILTER, mag],
                [gl.TEXTURE_WRAP_S, wrap], [gl.TEXTURE_WRAP_T, wrap]]) gl.texParameteri(kind, key, val);
    }
    unit(0);
    const atlas = gl.createTexture();
    gl.bindTexture(texArray, atlas);
    gl.texStorage3D(texArray, 8, gl.RGBA8, 128, 128, 256);
    capture.layers.forEach((layer, i) => gl.texSubImage3D(texArray, 0, 0, 0, i, 128, 128, 1, gl.RGBA, gl.UNSIGNED_BYTE, bytes(layer)));
    gl.generateMipmap(texArray);
    filtering(texArray, gl.LINEAR_MIPMAP_LINEAR, gl.LINEAR, gl.REPEAT);
    const aniso = gl.getExtension("EXT_texture_filter_anisotropic");
    if (aniso) gl.texParameterf(texArray, aniso.TEXTURE_MAX_ANISOTROPY_EXT, Math.min(4, gl.getParameter(aniso.MAX_TEXTURE_MAX_ANISOTROPY_EXT)));
    const uiTex = gl.createTexture(), uiUpload = uiPixels.slice();
    for (let i = 0; i < uiUpload.length; i += 4) {
        const red = uiUpload[i]; uiUpload[i] = uiUpload[i+2]; uiUpload[i+2] = red;
    }
    gl.bindTexture(tex2, uiTex);
    filtering(tex2, gl.LINEAR, gl.LINEAR, gl.CLAMP_TO_EDGE);
    gl.texImage2D(tex2, 0, gl.RGBA, width, height, 0, gl.RGBA, gl.UNSIGNED_BYTE, uiUpload);

    function texture(format, w, h, filter) {
        const tex = gl.createTexture();
        gl.bindTexture(tex2, tex);
        gl.texStorage2D(tex2, 1, format, w, h);
        filtering(tex2, filter, filter, gl.CLAMP_TO_EDGE);
        return tex;
    }
    function framebuffer() {
        const fbo = gl.createFramebuffer();
        gl.bindFramebuffer(target, fbo);
        return fbo;
    }
    function complete() { check(gl.checkFramebufferStatus(target) === gl.FRAMEBUFFER_COMPLETE, "Incomplete draw target"); }
    function attach(point, tex) { gl.framebufferTexture2D(target, point, tex2, tex, 0); }
    function sceneTarget(format) {
        const fbo = framebuffer(), color = texture(format, width, height, gl.LINEAR);
        attach(gl.COLOR_ATTACHMENT0, color);
        const depth = texture(gl.DEPTH_COMPONENT24, width, height, gl.NEAREST);
        attach(gl.DEPTH_ATTACHMENT, depth); complete();
        return {fbo, color, depth};
    }
    const sceneTargets = {old: sceneTarget(gl.RGBA8), current: sceneTarget(gl.SRGB8_ALPHA8)};
    function shadowTarget(count) {
        const fbo = framebuffer(), depth = texture(gl.DEPTH_COMPONENT24, count * 1024, 1024, gl.LINEAR);
        gl.texParameteri(tex2, gl.TEXTURE_COMPARE_MODE, gl.COMPARE_REF_TO_TEXTURE);
        gl.texParameteri(tex2, gl.TEXTURE_COMPARE_FUNC, gl.LEQUAL);
        attach(gl.DEPTH_ATTACHMENT, depth); gl.drawBuffers([gl.NONE]); gl.readBuffer(gl.NONE); complete();
        return {fbo, depth};
    }
    const single = shadowTarget(1), dual = shadowTarget(2);
    const sampler = gl.createSampler();
    for (const [key, value] of [[gl.TEXTURE_COMPARE_MODE, gl.NONE], [gl.TEXTURE_MIN_FILTER, gl.NEAREST],
        [gl.TEXTURE_MAG_FILTER, gl.NEAREST], [gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE], [gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE]]) {
        gl.samplerParameteri(sampler, key, value);
    }
    function readPixels() {
        const pixels = new Uint8Array(size * 4);
        gl.readPixels(0, 0, width, height, gl.RGBA, gl.UNSIGNED_BYTE, pixels);
        return pixels;
    }
    const timingPixel = new Uint8Array(4);
    const viewport = [p[1], height-p[2]-p[4], p[3], p[4]];
    function render({version="current", night=false, casts=true, cull=false, cascades=2, image=false, measure=false, captureScene=false}) {
        const begin = performance.now();
        const old = version === "old", mode = (cascades === 2 ? 255 : 117) | (night ? 256 : 0);
        const bands = old ? 1 : cascades, shadow = bands === 2 ? dual : single;
        const m = capture.matrices[old ? "legacy" : (night ? "night" : "day") + bands];
        const a = programs[version], scene = a.surface[casts ? 1 : 0], surface = sceneTargets[old ? "old" : "current"];
        const haze = night ? [.0153, .0245, .047] : [.274, .367, .462];
        const mat3 = (s, name, data) => gl.uniformMatrix3fv(get(s, name), false, data);
        const mat4 = (s, name, data) => gl.uniformMatrix4fv(get(s, name), false, data);
        unit(0); gl.bindTexture(texArray, atlas);
        gl.bindVertexArray(vao); gl.enable(gl.DITHER); gl.disable(gl.SCISSOR_TEST); gl.disable(gl.CULL_FACE);
        gl.enable(gl.DEPTH_TEST); gl.depthFunc(gl.LEQUAL); gl.depthMask(true); gl.disable(gl.BLEND);
        if (casts) {
            gl.bindFramebuffer(target, shadow.fbo); gl.clearDepth(1); gl.clear(gl.DEPTH_BUFFER_BIT);
            gl.colorMask(false, false, false, false); gl.enable(gl.POLYGON_OFFSET_FILL);
            gl.polygonOffset(old ? 1.5 : 1, old ? 2 : 1); gl.useProgram(a.caster);
            gl.uniform1i(get(a.caster, "atlas"), 0); mat3(a.caster, "cameraToWorld", m.cameraToWorld);
            gl.enable(gl.SCISSOR_TEST);
            for (let i = 0; i < bands; ++i) {
                gl.viewport(i*1024, 0, 1024, 1024); gl.scissor(i*1024, 0, 1024, 1024);
                mat4(a.caster, "shadowMatrix", i === 0 ? m.shadowMatrix : m.shadowFarMatrix);
                gl.drawArrays(gl.TRIANGLES, 0, p[0]);
            }
            gl.disable(gl.SCISSOR_TEST); gl.disable(gl.POLYGON_OFFSET_FILL); gl.colorMask(true, true, true, true);
        }
        gl.bindFramebuffer(target, surface.fbo); gl.viewport(0, 0, width, height);
        gl.clearColor(...(old ? [.46,.49,.50] : haze.map(v => .5*v)), 1); gl.clearDepth(1);
        gl.clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT); gl.viewport(...viewport);
        gl.useProgram(scene);
        if (!old && cull) {
            gl.frontFace(gl.CCW); gl.cullFace(gl.BACK); gl.enable(gl.CULL_FACE);
        }
        gl.enable(gl.BLEND); gl.blendFuncSeparate(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA, gl.ONE, gl.ONE_MINUS_SRC_ALPHA);
        gl.uniform3f(get(scene, "projection"), p[3], p[4], p[7]); gl.uniform2f(get(scene, "center"), p[5], p[6]);
        gl.uniform1i(get(scene, "atlas"), 0); gl.uniform1i(get(scene, "settings"), mode);
        mat3(scene, "cameraToWorld", m.cameraToWorld);
        if (!old) {
            gl.uniform3fv(get(scene, "lightDirection"), m.lightDirection); gl.uniform3fv(get(scene, "hazeColor"), haze);
            // This historical capture predates object-light and player-face metadata.
            gl.uniform1i(get(scene, "localLightCount"), 0);
        }
        if (casts) {
            mat4(scene, "shadowMatrix", m.shadowMatrix);
            unit(1); gl.bindTexture(tex2, shadow.depth); gl.bindSampler(1, null); gl.uniform1i(get(scene, "shadowMap"), 1);
            if (!old) {
                mat4(scene, "shadowFarMatrix", m.shadowFarMatrix); gl.uniform1i(get(scene, "shadowCascadeCount"), bands);
                gl.uniform4fv(get(scene, "shadowFilterScale"), m.shadowFilterScale); unit(3); gl.bindTexture(tex2, shadow.depth);
                gl.bindSampler(3, sampler); gl.uniform1i(get(scene, "shadowBlockers"), 3);
            }
        }
        unit(0); gl.depthMask(true); gl.uniform1i(get(scene,"phase"), 0); gl.drawArrays(gl.TRIANGLES, 0, p[0]);
        gl.depthMask(false); gl.uniform1i(get(scene, "phase"), 1); gl.drawArrays(gl.TRIANGLES, 0, p[0]); gl.depthMask(true);
        gl.disable(gl.CULL_FACE); gl.disable(gl.DEPTH_TEST); gl.disable(gl.BLEND);
        gl.bindFramebuffer(target, null); gl.viewport(0, 0, width, height); gl.useProgram(a.resolve);
        unit(0); gl.bindTexture(tex2, surface.color); gl.uniform1i(get(a.resolve, "scene"), 0);
        unit(2); gl.bindTexture(tex2, surface.depth); gl.uniform1i(get(a.resolve, "depthMap"), 2);
        gl.uniform2f(get(a.resolve,"pixel"), 1/width, 1/height); gl.uniform1i(get(a.resolve,"settings"), mode);
        if (old) gl.uniform1f(get(a.resolve, "focal"), p[7]);
        else gl.uniform4f(get(a.resolve, "viewLens"), p[7], p[7], viewport[0]+p[5], viewport[1]+p[4]-p[6]);
        gl.drawArrays(gl.TRIANGLES, 0, 3);
        const scenePixels = captureScene ? readPixels() : null;
        unit(0); gl.bindTexture(tex2, uiTex); gl.useProgram(a.ui); gl.uniform1i(get(a.ui,"image"), 0);
        gl.enable(gl.BLEND); gl.blendFunc(gl.ONE, gl.ONE_MINUS_SRC_ALPHA); gl.drawArrays(gl.TRIANGLE_FAN, 0, 4);
        if (measure) {
            gl.readPixels(viewport[0]+16,viewport[1]+16,1,1,gl.RGBA,gl.UNSIGNED_BYTE,timingPixel);
            const elapsed = performance.now() - begin;
            noErrors("Timed scene");
            return elapsed;
        }
        const pixels = readPixels(), png = image && canvas.toDataURL("image/png").split(",")[1];
        gl.disable(gl.BLEND); gl.disable(gl.DITHER); gl.useProgram(witness);
        gl.uniform1i(get(witness,"depthMap"), 2); gl.drawArrays(gl.TRIANGLES, 0, 3);
        const depth = readPixels(); noErrors("Frame"); return {pixels, png, depth, scenePixels};
    }
    const old = render({version:"old",image:true}), day = render({image:true}), night = render({night:true,image:true});
    const broadCulling = render({cull:true}), noShadow = render({casts:false}), singleBand = render({cascades:1});
    const repeat = render({}), oldAgain = render({version:"old"});
    const packed = (rgb, i) => (rgb[i] << 16) + (rgb[i+1] << 8) + rgb[i+2];
    const toDistance = d => 307200/(6144-(d/16777215)*6094);
    const maxDiff = (a,b,i) => Math.max(Math.abs(a[i]-b[i]), Math.abs(a[i+1]-b[i+1]), Math.abs(a[i+2]-b[i+2]));
    const counts = {opaqueUi:0, changedOpaqueUi:0, sceneViewport:0, beforeAfterChanged:0, dayNightChanged:0,
        shadowAffected:0, shadowDarker:0, singleBandDifferences:0, changedRepeat:0, changedLegacyReturn:0,
        geometryPixelsV39:0, geometryPixelsNow:0, exposedSkyPixels:0, depthChangedOver3Units:0,
        depthChangedOverOneTile:0, changedWorldDepthFromV39:0, changedNightDepth:0, broadCullingColorChanged:0, broadCullingLostPixels:0, broadCullingLostOverOneTile:0};
    for (let at=0; at < size; ++at) {
        const x=at%width, y=Math.floor(at/width), i=at*4, uiAt=((height-1-y)*width+x)*4;
        if (uiPixels[uiAt+3] === 255) {
            ++counts.opaqueUi;
            if ([day,night,oldAgain,noShadow,singleBand].some(frame => maxDiff(old.pixels,frame.pixels,i)>0)
                  || [0,1,2].some(k => old.pixels[i+k] !== uiPixels[uiAt+k])) ++counts.changedOpaqueUi;
        }
        if (maxDiff(day.pixels,repeat.pixels,i)>0) ++counts.changedRepeat;
        if (maxDiff(old.pixels,oldAgain.pixels,i)>0) ++counts.changedLegacyReturn;
        if (x<viewport[0] || y<viewport[1] || x>=viewport[0]+viewport[2] || y>=viewport[1]+viewport[3]) continue;
        ++counts.sceneViewport;
        if (maxDiff(old.pixels, day.pixels,i)>3) ++counts.beforeAfterChanged;
        if (maxDiff(day.pixels, night.pixels,i)>3) ++counts.dayNightChanged;
        if (maxDiff(day.pixels,singleBand.pixels,i)>3) ++counts.singleBandDifferences;
        if (maxDiff(day.pixels, noShadow.pixels,i)>3) {
            ++counts.shadowAffected;
            if (day.pixels[i]+day.pixels[i+1]+day.pixels[i+2] < noShadow.pixels[i]+noShadow.pixels[i+1]+noShadow.pixels[i+2]) ++counts.shadowDarker;
        }
        const farther = packed(day.depth,i), reference = packed(old.depth,i), broad = packed(broadCulling.depth,i);
        if (reference !== 16777215) ++counts.geometryPixelsV39;
        if (farther !== 16777215) ++counts.geometryPixelsNow;
        if (farther === 16777215 && reference !== farther) ++counts.exposedSkyPixels;
        if (toDistance(farther)-toDistance(reference)>3) ++counts.depthChangedOver3Units;
        if (toDistance(farther)-toDistance(reference)>128) ++counts.depthChangedOverOneTile;
        if (farther !== reference) ++counts.changedWorldDepthFromV39;
        if (packed(night.depth,i) !== farther) ++counts.changedNightDepth;
        if (maxDiff(day.pixels,broadCulling.pixels,i)>3) ++counts.broadCullingColorChanged;
        if (broad === 16777215 && farther !== broad) ++counts.broadCullingLostPixels;
        if (toDistance(broad)-toDistance(farther)>128) ++counts.broadCullingLostOverOneTile;
    }
    // Full-frame readback, screenshot encoding and the independent depth witness are outside
    // the timed section. A single-pixel read synchronizes the displayed result.
    const hostMilliseconds = {};
    for (const [name, options] of Object.entries({v39:{version:"old"}, v40Single:{cascades:1}, v40Dual:{}, v40Moon:{night:true}})) {
        render({...options,measure:true});
        const samples = Array.from({length:5}, () => render({...options,measure:true}));
        const sorted = samples.slice().sort((a,b) => a-b);
        hostMilliseconds[name] = {median:sorted[2], samples};
    }
    const phaseComparison = {};
    if (programs.preclip) {
        const configurations = [
            ["dayDual", {}], ["nightDual", {night:true}], ["daySingle", {cascades:1}]
        ];
        const median = values => values.slice().sort((a,b) => a-b)[Math.floor(values.length / 2)];
        function changedBytes(a,b) {
            if (a.length !== b.length) throw Error("Readback sizes differ");
            let changed = 0;
            for (let i = 0; i < a.length; ++i) if (a[i] !== b[i]) ++changed;
            return changed;
        }
        for (const [name, options] of configurations) {
            const before = render({...options,version:"preclip",captureScene:true});
            const after = render({...options,captureScene:true});
            phaseComparison[name] = {
                differentSceneBytes:changedBytes(before.scenePixels,after.scenePixels),
                differentCompositedBytes:changedBytes(before.pixels,after.pixels),
                differentDepthBytes:changedBytes(before.depth,after.depth),
                before:[], after:[], pairedPercentSaved:[]
            };
            for (let i = 0; i < 2; ++i) {
                render({...options,version:"preclip",measure:true});
                render({...options,measure:true});
            }
        }
        // Alternate AB and BA and rotate configuration order. All pairs share
        // a single driver/context, geometry, atlas, targets, uniforms and readback.
        for (let round = 0; round < 11; ++round) {
            for (let k = 0; k < configurations.length; ++k) {
                const [name, options] = configurations[(k+round)%configurations.length];
                const result = phaseComparison[name];
                const pair = ((round+k) & 1) ? ["current","preclip"] : ["preclip","current"];
                for (const version of pair) {
                    const time = render({...options,version,measure:true});
                    (version === "preclip" ? result.before : result.after).push(time);
                }
            }
        }
        for (const result of Object.values(phaseComparison)) {
            result.beforeMedian = median(result.before);
            result.afterMedian = median(result.after);
            result.pairedPercentSaved = result.before.map((v,i) => 100*(v-result.after[i])/v);
            result.medianPairedPercentSaved = median(result.pairedPercentSaved);
            result.fasterPairs = result.pairedPercentSaved.filter(p => p>0).length;
        }
    }
    const phaseEquivalent = Object.values(phaseComparison).every(v =>
        v.differentSceneBytes===0 && v.differentCompositedBytes===0 && v.differentDepthBytes===0);
    const info=gl.getExtension("WEBGL_debug_renderer_info");
    const report={pass:phaseEquivalent && phaseInputs.constantAlphaAndLayer===phaseInputs.triangles && counts.opaqueUi>0 && !counts.changedOpaqueUi && !counts.changedLegacyReturn && !counts.changedRepeat
        && !counts.changedWorldDepthFromV39 && !counts.changedNightDepth && counts.broadCullingLostPixels>0 && counts.shadowAffected>100 && counts.shadowDarker>counts.shadowAffected*.95 && counts.beforeAfterChanged>1000 && counts.dayNightChanged>1000,
        renderer:info?gl.getParameter(info.UNMASKED_RENDERER_WEBGL):gl.getParameter(gl.RENDERER),
        vertices:p[0], dimensions:[width,height], cameraFixture:"Archived yaw/pitch and explicit origin (0,0,0)",
        scope:"Archived game mesh, nine textures, game UI, native shaders on host WebGL2; no Android or live gameplay.",
        counts, phaseInputs, hostMilliseconds, phaseComparison};
    document.querySelector("#images").textContent = JSON.stringify({"v39-ultra":old.png,"v40-ultra-day":day.png,"v40-ultra-night":night.png});
    return report;
}
try {
    document.querySelector("#result").textContent = JSON.stringify(run());
} catch (e) {
    document.querySelector("#result").textContent = JSON.stringify({error:String(e.stack||e)});
}
