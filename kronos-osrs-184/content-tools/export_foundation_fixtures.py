"""Export assembled records plus independent terrain field hashes for native Java readers."""
import argparse,json,struct
from pathlib import Path
from cache_store import Store,Reader,name_hash
from modern_definitions import TERRAIN_MAGIC


def expected_terrain(raw):
    if not raw.startswith(TERRAIN_MAGIC): raise ValueError('Expected extended terrain')
    r=Reader(raw[len(TERRAIN_MAGIC):]);heights=[[[0]*64 for _ in range(64)] for _ in range(4)]
    result=0x811c9dc5
    for z in range(4):
        for x in range(64):
            for y in range(64):
                under=over=flag=shape=rotation=0
                while True:
                    op=r.uint(2)
                    if op<=1:
                        if op==0:
                            if z==0: raise ValueError('Relocated noise height was not made explicit')
                            height=heights[z-1][x][y]-240
                        else:
                            h=r.uint();h=0 if h==1 else h
                            height=(heights[z-1][x][y] if z else 0)-h*8
                        break
                    if op<=49: over=r.uint(2);shape=(op-2)//4;rotation=(op-2)&3
                    elif op<=81: flag=op-49
                    else: under=op-81
                heights[z][x][y]=height
                for value in (under,over,flag,height,shape,rotation):
                    result=((result^(value&0xffffffff))*16777619)&0xffffffff
    if r.offset!=len(r.data): raise ValueError('Unread terrain bytes')
    return result


def main():
    p=argparse.ArgumentParser();p.add_argument('stage',type=Path);p.add_argument('output',type=Path);a=p.parse_args()
    a.output.mkdir(parents=True,exist_ok=True)
    manifest=json.loads((a.stage/'data/content242.json').read_text())
    with Store(a.stage/'Cache') as cache:
        groups={g:cache.files(2,g) for g in (3,6,9,10,13)}
        with (a.output/'definitions.bin').open('wb') as out:
            out.write(struct.pack('>I',sum(map(len,groups.values()))))
            for g,records in groups.items():
                for i,raw in records.items():out.write(struct.pack('>III',g,i,len(raw))+raw)
        banks={g:record for g,record in cache.table(7)[3].items() if 65408<=g<=65534}
        with (a.output/'banks.bin').open('wb') as out:
            out.write(struct.pack('>I',sum(len(record.files) for record in banks.values())))
            for g in banks:
                for file,raw in cache.files(7,g).items():
                    logical=65536+(g-65408)*512+file
                    out.write(struct.pack('>II',logical,len(raw))+raw)
        names={g.name:i for i,g in cache.table(5)[3].items()}
        maps=manifest['mappings']['regions']
        with (a.output/'terrain.bin').open('wb') as out:
            out.write(struct.pack('>I',len(maps)))
            for source,entry in maps.items():
                region=entry['destination'];x,y=region>>8,region&255
                raw=cache.files(5,names[name_hash(f'm{x}_{y}')])[0]
                out.write(struct.pack('>III',region,expected_terrain(raw),len(raw))+raw)
    print('Exported actual assembled definitions and terrain expectations')


if __name__=='__main__':main()
