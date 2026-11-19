// The Java client writes these camera-space floats. The CPU oracle uses the same
// signed screen-area test as Model.method2375, with screen Y increasing downward.
function nativeVisible(mesh) {
    const faces = [];
    for (let i = 0; i < mesh.length; i += 39) {
        const x = [0, 13, 26].map(k => mesh[i + k] / mesh[i + k + 2]);
        const y = [0, 13, 26].map(k => mesh[i + k + 1] / mesh[i + k + 2]);
        if ((x[0] - x[1]) * (y[2] - y[1]) - (x[2] - x[1]) * (y[0] - y[1]) > 0)
            faces.push(...mesh.subarray(i, i + 39));
    }
    return new Float32Array(faces);
}

function check(ok, message) { if (!ok) throw new Error(message); }

function run() {
    const gl = canvas.getContext("webgl2", {antialias:false, alpha:false, preserveDrawingBuffer:true});
    check(gl, "WebGL2 unavailable");
    const size = [canvas.width, canvas.height], lens = 560;
    function link(vs, fs) {
        const prog = gl.createProgram();
        for (const [type, src] of [[gl.VERTEX_SHADER, vs], [gl.FRAGMENT_SHADER, fs]]) {
            const s = gl.createShader(type);
            gl.shaderSource(s, src);
            gl.compileShader(s);
            check(gl.getShaderParameter(s, gl.COMPILE_STATUS), gl.getShaderInfoLog(s));
            gl.attachShader(prog, s);
        }
        gl.linkProgram(prog);
        check(gl.getProgramParameter(prog, gl.LINK_STATUS), gl.getProgramInfoLog(prog));
        return prog;
    }
    const scene = link(shaders.sceneVertex, shaders.sceneFragment);
    const resolve = link(shaders.resolveVertex, shaders.resolveFragment);
    const priorScene = link(previous.sceneVertex, previous.sceneFragment);
    const priorResolve = link(previous.resolveVertex, previous.resolveFragment);
    const idColor = "result = tex.z >= 0.0 ? vec4(1,0,0,1) : vec4(0,1,0,1);";
    const probed = shaders.sceneFragment.replace(/result\s*=\s*vec4\([^;]+;\s*}\s*$/, idColor + "}");
    check(probed !== shaders.sceneFragment, "Could not instrument the production material output");
    const material = link(shaders.sceneVertex, probed);
    const oldMaterial = link(previous.sceneVertex, `#version 300 es
        precision highp float;
        in vec3 tex; out vec4 result;
        void main(){` + idColor + "}");
    const target = gl.createFramebuffer();
    gl.bindFramebuffer(gl.FRAMEBUFFER, target);
    function attach(format, attachment, filter) {
        const tex = gl.createTexture();
        gl.bindTexture(gl.TEXTURE_2D, tex);
        gl.texStorage2D(gl.TEXTURE_2D, 1, format, ...size);
        for (const [property, value] of [[gl.TEXTURE_MIN_FILTER,filter],[gl.TEXTURE_MAG_FILTER,filter],
                [gl.TEXTURE_WRAP_S,gl.CLAMP_TO_EDGE],[gl.TEXTURE_WRAP_T,gl.CLAMP_TO_EDGE]])
            gl.texParameteri(gl.TEXTURE_2D, property, value);
        gl.framebufferTexture2D(gl.FRAMEBUFFER, attachment, gl.TEXTURE_2D, tex, 0);
        return tex;
    }
    const legacyColor = attach(gl.RGBA8, gl.COLOR_ATTACHMENT0, gl.LINEAR);
    const color = attach(gl.SRGB8_ALPHA8, gl.COLOR_ATTACHMENT0, gl.LINEAR);
    const depth = attach(gl.DEPTH_COMPONENT24, gl.DEPTH_ATTACHMENT, gl.NEAREST);
    check(gl.checkFramebufferStatus(gl.FRAMEBUFFER) === gl.FRAMEBUFFER_COMPLETE, "Scene framebuffer");
    const vao = gl.createVertexArray(), buffer = gl.createBuffer();
    gl.bindVertexArray(vao);
    gl.bindBuffer(gl.ARRAY_BUFFER, buffer);
    for (const [i, count, offset] of [[0,3,0],[1,4,3],[2,3,7],[3,3,10]]) {
        gl.enableVertexAttribArray(i);
        gl.vertexAttribPointer(i, count, gl.FLOAT, false, 52, offset * 4);
    }
    function bytes(raw) { return Uint8Array.from(atob(raw), ch => ch.charCodeAt(0)); }
    const textures = {};
    for (const [id, raw] of Object.entries(fixture.atlases)) {
        const tex = gl.createTexture();
        gl.bindTexture(gl.TEXTURE_2D_ARRAY, tex);
        gl.texImage3D(gl.TEXTURE_2D_ARRAY, 0, gl.RGBA8, 128, 128, 1, 0, gl.RGBA, gl.UNSIGNED_BYTE, bytes(raw));
        gl.texParameteri(gl.TEXTURE_2D_ARRAY, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
        gl.texParameteri(gl.TEXTURE_2D_ARRAY, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
        textures[id] = tex;
    }
    function checkShadowLayers() {
        const caster = link(shaders.casterVertex, shaders.casterFragment);
        const probe = link(shaders.resolveVertex, `#version 300 es
            precision highp float; uniform highp sampler2D z; out vec4 c;
            void main(){c = vec4(vec3(texelFetch(z, ivec2(10,10), 0).r < .9 ? 1.0 : 0.0),1.0);}`);
        const alpha = gl.createTexture();
        gl.activeTexture(gl.TEXTURE0);
        gl.bindTexture(gl.TEXTURE_2D_ARRAY, alpha);
        gl.texImage3D(gl.TEXTURE_2D_ARRAY,0,gl.RGBA8,1,1,3,0,gl.RGBA,gl.UNSIGNED_BYTE,
                      new Uint8Array([255,255,255,255, 255,255,255,0, 255,255,255,255]));
        gl.texParameteri(gl.TEXTURE_2D_ARRAY,gl.TEXTURE_MIN_FILTER,gl.NEAREST);
        gl.texParameteri(gl.TEXTURE_2D_ARRAY,gl.TEXTURE_MAG_FILTER,gl.NEAREST);
        const position = [[-1,-1],[3,-1],[-1,3]];
        const answers = [];
        for (const index of [0,.25,1,1.25,2,2.25]) {
            const triangle = new Float32Array(39);
            position.forEach(([x,y],i) => triangle.set([x,y,0, 1,1,1,1, 0,0,1, .5,.5,index], i*13));
            gl.bindFramebuffer(gl.FRAMEBUFFER,target);
            gl.drawBuffers([gl.NONE]);
            gl.viewport(0,0,...size);
            gl.clear(gl.DEPTH_BUFFER_BIT);
            gl.enable(gl.DEPTH_TEST);
            gl.disable(gl.CULL_FACE);
            gl.useProgram(caster);
            gl.uniformMatrix3fv(gl.getUniformLocation(caster,"cameraToWorld"),false,[1,0,0,0,1,0,0,0,1]);
            gl.uniformMatrix4fv(gl.getUniformLocation(caster,"shadowMatrix"),false,[1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1]);
            gl.uniform1i(gl.getUniformLocation(caster,"atlas"),0);
            gl.bufferData(gl.ARRAY_BUFFER,triangle,gl.STREAM_DRAW);
            gl.drawArrays(gl.TRIANGLES,0,3);
            gl.drawBuffers([gl.COLOR_ATTACHMENT0]);
            gl.bindFramebuffer(gl.FRAMEBUFFER,null);
            gl.disable(gl.DEPTH_TEST);
            gl.useProgram(probe);
            gl.activeTexture(gl.TEXTURE2);
            gl.bindTexture(gl.TEXTURE_2D,depth);
            gl.uniform1i(gl.getUniformLocation(probe,"z"),2);
            gl.drawArrays(gl.TRIANGLES,0,3);
            const pixel = new Uint8Array(4);
            gl.readPixels(10,10,1,1,gl.RGBA,gl.UNSIGNED_BYTE,pixel);
            answers.push(pixel[0]);
        }
        check(answers.join() === "255,255,0,0,255,255", "Wrong shadow alpha layer: " + answers);
        check(gl.getError() === gl.NO_ERROR, "Shadow sampler probe GL error");
        return answers;
    }
    const shadowLayerAlpha = checkShadowLayers();
    function draw(p, mesh, textureId) {
        gl.bindFramebuffer(gl.FRAMEBUFFER, target);
        const attachment = p === priorScene ? legacyColor : color;
        gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, attachment, 0);
        gl.viewport(0, 0, ...size);
        gl.enable(gl.DEPTH_TEST);
        gl.depthFunc(gl.LEQUAL);
        gl.frontFace(gl.CCW);
        gl.disable(gl.CULL_FACE);
        gl.clearColor(0, 0, 0, 1);
        gl.clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT);
        gl.useProgram(p);
        const u = name => gl.getUniformLocation(p, name);
        gl.uniform3f(u("projection"), ...size, lens);
        gl.uniform2f(u("center"), size[0] / 2, size[1] / 2);
        gl.uniformMatrix3fv(u("cameraToWorld"), false, [1,0,0, 0,1,0, 0,0,1]);
        gl.uniform3f(u("lightDirection"), -0.45, -0.82, -0.35);
        gl.uniform1i(u("phase"), 0);
        gl.uniform1i(u("settings"), 16);
        gl.activeTexture(gl.TEXTURE0);
        gl.bindTexture(gl.TEXTURE_2D_ARRAY, textures[textureId]);
        gl.uniform1i(u("atlas"), 0);
        gl.bufferData(gl.ARRAY_BUFFER, mesh, gl.STREAM_DRAW);
        gl.drawArrays(gl.TRIANGLES, 0, mesh.length / 13);
        gl.disable(gl.CULL_FACE);
        if (p === scene || p === priorScene) {
            const pass = p === priorScene ? priorResolve : resolve;
            gl.bindFramebuffer(gl.FRAMEBUFFER, null);
            gl.disable(gl.DEPTH_TEST);
            gl.useProgram(pass);
            gl.bindTexture(gl.TEXTURE_2D, attachment);
            gl.activeTexture(gl.TEXTURE2);
            gl.bindTexture(gl.TEXTURE_2D, depth);
            gl.uniform1i(gl.getUniformLocation(pass, "scene"), 0);
            gl.uniform1i(gl.getUniformLocation(pass, "depthMap"), 2);
            gl.uniform2f(gl.getUniformLocation(pass,"pixel"),1/size[0],1/size[1]);
            gl.uniform4f(gl.getUniformLocation(pass,"viewLens"),lens,lens,size[0]/2,size[1]/2);
            gl.uniform1f(gl.getUniformLocation(pass,"focal"),lens);
            gl.uniform1i(gl.getUniformLocation(pass,"settings"),16);
            gl.drawArrays(gl.TRIANGLES, 0, 3);
        }
        check(gl.getError() === gl.NO_ERROR, "Unexpected replay GL error");
        const pixels = new Uint8Array(size[0] * size[1] * 4);
        gl.readPixels(0, 0, ...size, gl.RGBA, gl.UNSIGNED_BYTE, pixels);
        return pixels;
    }
    const report = [], images = {}, firstFrames = new Map();
    const comparison = document.createElement("canvas");
    comparison.width = 440; comparison.height = 320;
    const ctx = comparison.getContext("2d");
    ctx.fillStyle = "#101414"; ctx.fillRect(0,0,440,320);
    ctx.font = "600 16px sans-serif"; ctx.textAlign = "center"; ctx.fillStyle = "#faf8ee";
    ctx.fillText("Previous cape",110,24); ctx.fillText("Corrected cape",330,24);
    function comparisonPanel(x) { ctx.drawImage(canvas,68,40,184,280,x+18,34,184,280); }
    for (const [name, raw] of Object.entries(fixture.meshes)) {
        const [id, yaw, step] = name.split("-").map(Number);
        const tex = id > 72000 ? 59 : 40;
        const mesh = new Float32Array(bytes(raw).buffer);
        const legacy = new Float32Array(bytes(fixture.oldMeshes[name]).buffer);
        const old = draw(oldMaterial, legacy, tex);
        const fixed = draw(material, mesh, tex);
        const scenery = draw(material, legacy, tex);
        const oracle = draw(oldMaterial, nativeVisible(legacy), tex);
        let wrongOld = 0, wrongFixed = 0, sceneryDiff = 0, outsideOld = 0, outsideFixed = 0, oldOutsideFixedLining = 0;
        for (let i = 0; i < old.length; i += 4) {
            if (fixed[i] !== oracle[i] || fixed[i+1] !== oracle[i+1]) wrongFixed++;
            if (old[i] !== oracle[i] || old[i+1] !== oracle[i+1]) wrongOld++;
            if (old[i] !== scenery[i] || old[i+1] !== scenery[i+1]) sceneryDiff++;
            if (old[i]) outsideOld++;
            if (fixed[i]) outsideFixed++;
            if (old[i] && fixed[i+1]) oldOutsideFixedLining++;
        }
        check(wrongFixed <= 2, name + " contradicts native projected winding at " + wrongFixed + " pixels");
        check(sceneryDiff === 0, name + " non-player backfaces unexpectedly culled");
        const present = draw(scene, mesh, tex);
        const key = id + "-" + yaw;
        let motion = 0;
        if (step === 0) firstFrames.set(key, present);
        else {
            const prior = firstFrames.get(key);
            for (let i = 0; i < present.length; i += 4)
                if ([0,1,2].some(k => Math.abs(present[i+k] - prior[i+k]) > 2)) motion++;
        }
        if (id === 72204 && (yaw === 0 || yaw === 1024 || yaw === 192 || yaw === 832) && step === 0) {
            images[key + "-fixed"] = canvas.toDataURL();
            if (yaw === 1024) comparisonPanel(220);
            draw(priorScene, legacy, tex);
            images[key + "-v39-winding"] = canvas.toDataURL();
            if (yaw === 1024) {
                comparisonPanel(0);
                images["infernal-cape-comparison"] = comparison.toDataURL();
            }
        }
        report.push({id, yaw, step, wrongOld, wrongFixed, sceneryDiff, outsideOld, outsideFixed, oldOutsideFixedLining, motion});
    }
    for (const id of [72204, 72205, 70827, 70828]) {
        const frames = report.filter(r => r.id === id);
        check(frames.length === 12, "Incomplete model " + id);
        check(frames.some(r => r.wrongOld > 300), "Negative control failed to reproduce the old bug " + id);
        check(frames.some(r => r.step && r.motion > 300), "No animated texture movement " + id);
        check(frames.some(r => r.yaw === 1024 && r.outsideFixed > r.outsideOld + 500),
              "Outside texture was covered by lining " + id);
        check(frames.some(r => r.yaw === 0 && r.outsideOld > r.outsideFixed + 500),
              "Lining was overwritten by outside texture " + id);
    }
    const debug = gl.getExtension("WEBGL_debug_renderer_info");
    return {report, images, shadowLayerAlpha, renderer: gl.getParameter(debug ? debug.UNMASKED_RENDERER_WEBGL : gl.RENDERER)};
}
try { document.getElementById("result").textContent = JSON.stringify(run()); }
catch (error) { document.getElementById("result").textContent = JSON.stringify({error:String(error), stack:error.stack}); }
