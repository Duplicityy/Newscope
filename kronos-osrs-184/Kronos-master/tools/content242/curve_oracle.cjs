// Execute the pinned donor's unmodified curve evaluator as an independent oracle.
const fs=require('fs'),path=require('path'),Module=require('module');
const [donor,compiler,fixture,out]=process.argv.slice(2);
const ts=require(path.resolve(compiler));
const originalLoad=Module._load;
Module._load=function(name,parent,main){if(name==='gl-matrix')return {vec2:{fromValues:(a,b)=>new Float32Array([a,b])}};return originalLoad.call(this,name,parent,main);};
require.extensions['.ts']=function(module,file){module._compile(ts.transpileModule(fs.readFileSync(file,'utf8'),{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2020}}).outputText,file);};
const {ByteBuffer}=require(path.resolve(donor,'client/rs/io/ByteBuffer.ts'));
const {Curve}=require(path.resolve(donor,'client/rs/model/skeletal/Curve.ts'));
const {interpolateCurve}=require(path.resolve(donor,'client/rs/model/skeletal/CurveInterp.ts'));
const input=fs.readFileSync(fixture);let offset=0;
function u32(){const n=input.readUInt32BE(offset);offset+=4;return n;}
function blob(){const n=u32(),v=input.subarray(offset,offset+n);offset+=n;return v;}
const records=[];if(u32()!==0x4b32414e)throw Error('fixture magic');const n=u32();
let curves=0;
for(let seq=0;seq<n;seq++){
 const id=u32();u32();blob();blob();const raw=blob(),b=new ByteBuffer(new Uint8Array(raw));
 const version=b.readUnsignedByte();b.readUnsignedShort();b.readUnsignedShort();b.readUnsignedShort();b.readUnsignedByte();const count=b.readUnsignedShort();
 for(let i=0;i<count;i++){
  b.readUnsignedByte();b.readSmart2();b.readUnsignedByte();const start=b.offset;
  const c=new Curve(i);c.decode(b,version);
  if(i>2&&i%97!==0)continue;
  // Retain keys for direct interpolation; the donor load() discards them before
  // deriving its out-of-range values. Compare meaningful in-range curve semantics.
  c.startTick=c.points[0].x;c.endTick=c.points.at(-1).x;
  const span=c.endTick-c.startTick;
  const times=[c.startTick,c.startTick+span*.123,c.startTick+span*.5,c.startTick+span*.877,c.endTick];
  const head=Buffer.alloc(12);head.writeUInt32BE(id);head.writeUInt32BE(i,4);head.writeUInt32BE(b.offset-start,8);
  const samples=Buffer.alloc(4+times.length*8);samples.writeUInt32BE(times.length);
  times.forEach((t,j)=>{const time=Math.fround(t);samples.writeFloatBE(time,4+j*8);samples.writeFloatBE(interpolateCurve(c,time),8+j*8);});
  records.push(head,raw.subarray(start,b.offset),samples);curves++;
 }
}
const header=Buffer.alloc(8);header.writeUInt32BE(0x4b32434f);header.writeUInt32BE(curves,4);
fs.writeFileSync(out,Buffer.concat([header,...records]));console.log(JSON.stringify({curves,samples:curves*5,donorSequences:n,oracle:'pinned TSPS CurveInterp.ts unmodified'}));
