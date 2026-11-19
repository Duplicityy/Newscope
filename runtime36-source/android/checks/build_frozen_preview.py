"""Build a host WebGL2 replay from the code22 frozen mesh preview."""
import json
import re
import sys
from pathlib import Path

source, output = map(Path, sys.argv[1:])
cpp = Path(__file__).resolve().parents[1] / 'kronos/src/main/cpp'
text = source.read_text(encoding='utf-8')
def shader(file, name):
    return json.dumps(re.search(r'const char\* '+name+r'=R"\((.*?)\)";',(cpp/file).read_text(), re.S)[1])
programs = 'const premium=program('+shader('ultra_surface.h','ultraVertex')+','+shader('ultra_surface.h','ultraFragment')+',true), resolve=program('+shader('ultra.h','vertex')+','+shader('ultra.h','fragment')+');'
text=text.replace(' const vao=',programs+'\n const vao=')
text=text.replace(' function render(shadows,drawCaster)', '''
 const target=gl.createFramebuffer();gl.bindFramebuffer(gl.FRAMEBUFFER,target);
 function attachment(format,point,filter){
  const tex=gl.createTexture();gl.bindTexture(gl.TEXTURE_2D,tex);
  gl.texStorage2D(gl.TEXTURE_2D,1,format,canvas.width,canvas.height);
  for(const [p,v] of [[gl.TEXTURE_MIN_FILTER,filter],[gl.TEXTURE_MAG_FILTER,filter],[gl.TEXTURE_WRAP_S,gl.CLAMP_TO_EDGE],[gl.TEXTURE_WRAP_T,gl.CLAMP_TO_EDGE]])gl.texParameteri(gl.TEXTURE_2D,p,v);
  gl.framebufferTexture2D(gl.FRAMEBUFFER,point,gl.TEXTURE_2D,tex,0);return tex;
 }
 const color=attachment(gl.RGBA8,gl.COLOR_ATTACHMENT0,gl.LINEAR),z=attachment(gl.DEPTH_COMPONENT24,gl.DEPTH_ATTACHMENT,gl.NEAREST);
 check('Ultra scene framebuffer',gl.checkFramebufferStatus(gl.FRAMEBUFFER)===gl.FRAMEBUFFER_COMPLETE);
 function render(shadows,drawCaster,mask=0,timeOnly=false)''')
text=text.replace('gl.bindFramebuffer(gl.FRAMEBUFFER,null);gl.viewport','gl.bindFramebuffer(gl.FRAMEBUFFER,mask?target:null);gl.viewport')
text=text.replace('const scene=shadows?lit:base;gl.useProgram(scene);','const scene=mask?premium:shadows?lit:base;gl.useProgram(scene);if(mask)gl.uniform1i(gl.getUniformLocation(scene,"settings"),mask);')
text=text.replace('gl.useProgram(ui);','''if(mask){
 gl.bindFramebuffer(gl.FRAMEBUFFER,null);gl.disable(gl.BLEND);gl.useProgram(resolve);
 gl.activeTexture(gl.TEXTURE0);gl.bindTexture(gl.TEXTURE_2D,color);
 gl.activeTexture(gl.TEXTURE2);gl.bindTexture(gl.TEXTURE_2D,z);
 gl.uniform1i(gl.getUniformLocation(resolve,'scene'),0);gl.uniform1i(gl.getUniformLocation(resolve,'depthMap'),2);
 gl.uniform2f(gl.getUniformLocation(resolve,'pixel'),1/canvas.width,1/canvas.height);
 gl.uniform1f(gl.getUniformLocation(resolve,'focal'),params[7]);gl.uniform1i(gl.getUniformLocation(resolve,'settings'),mask);
 gl.drawArrays(gl.TRIANGLES,0,3);gl.activeTexture(gl.TEXTURE0);gl.enable(gl.BLEND);
 }
 gl.useProgram(ui);''')
text=text.replace('  const pixels=new Uint8Array', '  if(timeOnly){gl.readPixels(0,0,1,1,gl.RGBA,gl.UNSIGNED_BYTE,new Uint8Array(4));return;}\n  const pixels=new Uint8Array')
a=text.index(' const off=render');b=text.index('}catch(error)',a)
text=text[:a]+'''
 const off=render(true,true),on=render(true,true,255),again=render(true,true);
 let changed=0,opaque=0,interfaceDelta=0,switchDelta=0,total=0;
 for(let i=0;i<on.pixels.length;i+=4){
 const pi=i/4,x=pi%canvas.width,y=Math.floor(pi/canvas.width),alpha=originalUi[4*((canvas.height-y-1)*canvas.width+x)+3];
 const delta=Math.max(...[0,1,2].map(k=>Math.abs(on.pixels[i+k]-off.pixels[i+k])));
 if(delta>2)changed++;
 if(alpha===255){opaque++;if(delta)interfaceDelta++;}else total+=delta;
 if([0,1,2].some(k=>again.pixels[i+k]!==off.pixels[i+k]))switchDelta++;
 }
 check('Scene visibly changes',changed>50,{changed,total});
 check('Opaque interface stable',interfaceDelta===0,{interfaceDelta,opaque});
 check('HD after Ultra reproduces HD',switchDelta===0,{switchDelta});
 report.vertices=params[0];report.pass=true;
 const times={};
 for(const mask of [0,80,117,254,255]){
  const samples=[];
  for(let i=0;i<4;i++){const t=performance.now();render(true,true,mask,true);if(i)samples.push(performance.now()-t);}
  times[mask]=samples;
 }
 report.hostFrameMilliseconds=times;
 document.getElementById('images').textContent=JSON.stringify({hd:off.png,ultra:on.png});
'''+text[b:]
output.write_text(text,encoding='utf-8')
