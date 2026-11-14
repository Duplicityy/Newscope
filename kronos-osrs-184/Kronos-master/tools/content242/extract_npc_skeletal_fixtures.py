import json
from pathlib import Path
import struct
import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[3] / "content-tools"))
from cache_store import Store
from modern_animation import sequence242
from modern_model import weighted_model184

CASES = [("Scurrius", 7221, [50146,50145,50144,50147], [10687,10690,10691]),
         ("Amoxliatl",13685,[54438,54437],[11528,11529]),
         ("Phantom Muspah ranged",12077,[47149],[9913,9914,9916]),
         ("Phantom Muspah melee",12078,[47147],[9913,9914,9916]),
         ("Phantom Muspah shield",12079,[47156],[9913,9914,9916])]

def blob(out, data):out.write(struct.pack(">I",len(data)));out.write(data)

with Store(Path(sys.argv[1])) as store, Path(sys.argv[2]).open('wb') as out:
    out.write(struct.pack(">II",0x4b324e50,len(CASES)))
    metadata=store.files(2,12);mapping={i:i for i in range(65024)}
    for name,npc,models,seqs in CASES:
        out.write(struct.pack(">II",npc,len(models)))
        for model in models:blob(out,weighted_model184(store.files(7,model)[0]))
        out.write(struct.pack(">I",len(seqs)))
        for seq in seqs:
            cached=[]
            converted,weighted=sequence242(metadata[seq],lambda i:i,lambda i:cached.append(i)or i,mapping)
            if not weighted:raise ValueError(f"Expected weighted fixture {seq}")
            raw=store.files(22,cached[0]>>16)[cached[0]&65535]
            base=int.from_bytes(raw[1:3],'big')
            out.write(struct.pack(">II",seq,base));blob(out,converted);blob(out,store.files(1,base)[0]);blob(out,raw)
print(json.dumps([dict(name=n,npc=i,models=m,sequences=s)for n,i,m,s in CASES]))
