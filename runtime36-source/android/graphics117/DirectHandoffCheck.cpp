#include "../kronos/src/main/cpp/direct117_handoff.h"
extern "C" int run_checks(){
    Direct117Handoff handoff;
    if(handoff.pending())return 1;
    handoff.prepare(8,100);
    if(handoff.ready(9,true)||!handoff.pending())return 2;
    handoff.detached();
    if(handoff.ready(8,true)||handoff.ready(9,false))return 3;
    if(!handoff.ready(9,true))return 4;
    if(handoff.expired(5099)||!handoff.expired(5100))return 5;
    handoff.cancel();
    if(handoff.pending()||handoff.ready(9,true))return 6;
    for(int i=0;i<100;i++){
        handoff.prepare(i,10000);
        if(handoff.ready(i+1,true))return 7;
        handoff.detached();
        if(!handoff.ready(i+1,true))return 8;
        handoff.cancel();
    }
    return 0;
}
