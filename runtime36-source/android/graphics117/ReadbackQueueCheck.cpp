// Host-only fake GL boundary exercises the actual production queue. No GPU.
using GLuint = unsigned int;
using GLenum = unsigned int;
using GLsync = void*;
using GLsizeiptr = long long;
constexpr GLenum GL_TIMEOUT_EXPIRED=1, GL_WAIT_FAILED=2, GL_ALREADY_SIGNALED=3,
    GL_CONDITION_SATISFIED=4, GL_PIXEL_PACK_BUFFER=5, GL_STREAM_READ=6,
    GL_RGBA=7, GL_UNSIGNED_BYTE=8, GL_SYNC_GPU_COMMANDS_COMPLETE=9;
static int createdBuffers,deletedBuffers,createdFences,deletedFences;
static int completedTicket, reads, allocations, invalidWaits;
static bool waitFailure;
static GLsizeiptr allocatedBytes;
void glGenBuffers(int,GLuint* out){*out=++createdBuffers;}
void glBindBuffer(GLenum,GLuint){}
void glBufferData(GLenum,GLsizeiptr bytes,const void*,GLenum){++allocations;allocatedBytes=bytes;}
void glDeleteBuffers(int,const GLuint*){++deletedBuffers;}
void glDeleteSync(GLsync){++deletedFences;}
GLenum glClientWaitSync(GLsync fence,unsigned flags,unsigned long long timeout){
    if(flags||timeout)++invalidWaits;
    if(waitFailure)return GL_WAIT_FAILED;
    return (unsigned long)fence<=(unsigned)completedTicket?GL_ALREADY_SIGNALED:GL_TIMEOUT_EXPIRED;
}
void glReadPixels(int,int,int,int,GLenum,GLenum,void*){++reads;}
GLsync glFenceSync(GLenum,unsigned){return (void*)(unsigned long)(++createdFences);}
void glFlush(){}
#include "readback_queue.h"
extern "C" int run_checks(){
    ReadbackQueue queue;
    queue.resize(4,3);
    for(int i=0;i<3;i++)if(!queue.enqueue())return 1;
    if(queue.hasRoom()||queue.enqueue()||reads!=3||allocations!=3||allocatedBytes!=48)return 2;
    if(queue.completed()!=-1||queue.count!=3)return 3;
    completedTicket=1;
    if(queue.completed()!=0||queue.count!=2||!queue.hasRoom())return 4;
    if(!queue.enqueue()||allocations!=3||reads!=4)return 5;
    completedTicket=4;
    if(queue.completed()!=0||queue.count!=0||deletedFences!=4)return 6;
    queue.enqueue();
    waitFailure=true;
    if(queue.completed()!=-2||queue.count!=1)return 7;
    waitFailure=false;
    queue.resize(7,9);
    if(queue.count||queue.width!=7||queue.height!=9)return 8;
    for(int round=0;round<100;round++){
        queue.enqueue();queue.enqueue();
        if(queue.completed()!=-1)return 9;
        completedTicket=createdFences;
        if(queue.completed()<0||queue.count)return 10;
        queue.resize(round+1,round+2);
    }
    queue.enqueue();queue.reset();
    if(createdBuffers!=deletedBuffers||createdFences!=deletedFences||invalidWaits)return 11;
    if(queue.count||queue.width||queue.height)return 12;
    return 0;
}
