package io.ruin.model.activities.raids.toa.port;

import io.ruin.model.activities.raids.auto.RaidAutoAccess;
import io.ruin.model.entity.player.Player;
import io.ruin.model.item.Item;
import java.lang.reflect.*;
import java.util.*;

/** Read-only authored-puzzle planning. Every result is a normal player action. */
public final class ToaAutoPuzzlePlanner {
    private ToaAutoPuzzlePlanner() {}
    static Object item(Object player,int id){for(Item item:((Player)player).getInventory().getItems())if(item!=null&&item.getId()==id)return item;return null;}
    static void reset(Object player,Object encounter){SCANNED.remove(encounter);SHOTS.remove(player);PILLAR_MODULE.remove(player);MEMORY_MODULE.remove(player);}
    static final int[][] BASE={{3539,5283},{3556,5283},{3539,5271},{3556,5271}};
    static final int[][] LIGHT={{1,1},{1,3},{1,5},{3,5},{5,5},{5,3},{5,1},{3,1}};
    static final int[][] MEMORY={{2,4},{1,3},{2,2},{3,5},{3,3},{3,1},{4,4},{4,2},{5,3}};
    static final int[][][] SUM={{{1,1},{4,4},{5,4}},{{2,2},{4,2},{4,5}},{{2,5},{3,3},{5,1}},{{4,1},{5,2},{3,4}},{{3,1},{5,3}},{{1,2},{2,3},{2,4}},{{1,4},{3,2},{3,5}},{{1,3},{4,3}},{{1,5},{2,1},{5,5}}};
    static final int[][][] HET={{{3672,5280,0},{3672,5287,1},{3683,5287,2}},{{3675,5280,1},{3675,5274,0},{3683,5274,3}},{{3675,5280,0},{3675,5282,1},{3682,5282,2}},{{3675,5280,1},{3675,5276,0},{3687,5276,3}},{{3675,5280,1},{3675,5274,0},{3683,5274,3}}};
    static final int[] DX={-1,1,0,0},DY={0,0,-1,1},MASK={0x1240108,0x1240180,0x1240102,0x1240120};
    static final Map<Object,List<Object>> SCANNED=new IdentityHashMap<>();
    static final Map<Object,PillarShot> SHOTS=new WeakHashMap<>();
    static final Map<Object,java.lang.ref.WeakReference<Object>> PILLAR_MODULE=new WeakHashMap<>();
    static final Map<Object,java.lang.ref.WeakReference<Object>> MEMORY_MODULE=new WeakHashMap<>();
    static boolean moduleKnown(Object p,Object e){java.lang.ref.WeakReference<Object> known=PILLAR_MODULE.get(p);return known!=null&&known.get()==e;}
    static boolean markModuleReady(Object p,Object e,int x,int y,int bx,int by){if(moduleKnown(p,e))return true;if(x!=bx+3||y!=by+3)return false;PILLAR_MODULE.put(p,new java.lang.ref.WeakReference<Object>(e));SHOTS.remove(p);return true;}
    static boolean memoryKnown(Object p,Object e){java.lang.ref.WeakReference<Object> known=MEMORY_MODULE.get(p);return known!=null&&known.get()==e;}
    static boolean markMemoryReady(Object p,Object e,int x,int y,int bx,int by){if(memoryKnown(p,e))return true;if(x!=bx+3||y!=by+3)return false;MEMORY_MODULE.put(p,new java.lang.ref.WeakReference<Object>(e));return true;}
    static final class PillarShot{Object encounter,npc;int index;long beforeAttack,requested;boolean stopped;}
    static int ownPending(Object p,Object npc)throws Exception{Object q=get(npc,"queuedHits");int count=0;if(q!=null)for(Object h:(List<?>)q)if(get(h,"attacker")==p&&!Boolean.TRUE.equals(get(h,"removed")))count++;return count;}
    static Map<String,Object> pillar(Object p,Object s,Object e,long tick,Set<Long> avoid,int bx,int by)throws Exception{
        if(!moduleKnown(p,e)&&!markModuleReady(p,e,px(p,s),py(p,s),bx,by)){
            SHOTS.remove(p);
            return move(p,s,bx+3,by+3,avoid,"One-time normal traversal into the actual pillar module before first Hit");
        }
        PillarShot shot=SHOTS.get(p);
        if(shot!=null&&shot.encounter!=e){SHOTS.remove(p);shot=null;}
        int index=num(e,"pillarIndex");
        if(shot!=null){
            long last=((Number)get(call(p,"getCombat"),"lastAttackTick")).longValue();
            if(last>shot.beforeAttack&&!shot.stopped){
                shot.stopped=true;
                return result("kind","wait","style","MAGIC","prayer","PROTECT_FROM_MISSILES","stopCombat",true,"stopAlways",true,"reason","One native powered staff launch observed; same-tile native click stops repeat fire");
            }
            // The source validates order on launch. A previous genuine projectile
            // may still be flying while the next authored pillar must be hit.
            if(index!=shot.index){SHOTS.remove(p);shot=null;}
            else if(ownPending(p,shot.npc)>0)
                return result("kind","wait","style","MAGIC","prayer","PROTECT_FROM_MISSILES","reason","Wait for native resolution of an unadvanced pillar launch");
            else if(tick-shot.requested>12){SHOTS.remove(p);shot=null;}
            else return result("kind","wait","style","MAGIC","prayer","PROTECT_FROM_MISSILES","reason","Allow normal Hit approach/attack; no recentering or repeated NPC clicks");
        }
        int[] order=(int[])get(e,"pillarOrder");
        if(index>=order.length)
            return result("kind","wait","style","MAGIC","prayer","PROTECT_FROM_MISSILES","reason","All five actual pillar launches accepted; await genuine native projectile impact and module completion");
        Object npc=((Object[])get(e,"pillars"))[order[index]];
        PillarShot next=new PillarShot();next.encounter=e;next.npc=npc;next.index=index;
        next.beforeAttack=((Number)get(call(p,"getCombat"),"lastAttackTick")).longValue();next.requested=tick;SHOTS.put(p,next);
        return result("kind","attack","npc",npc,"style","MAGIC","prayer","PROTECT_FROM_MISSILES","ctrlRun",true,"reason","Single normal ctrl-click Hit on next real pillar; advance on native accepted launch");
    }
    static Object get(Object o,String f)throws Exception{return RaidAutoAccess.get(o,f);}
    static Object call(Object o,String m)throws Exception{return RaidAutoAccess.call(o,m);}
    static int num(Object o,String f)throws Exception{return ((Number)get(o,f)).intValue();}
    static Class<?> type(Object p,String name)throws Exception{return Class.forName(name,false,p.getClass().getClassLoader());}
    static Object invoke(Object o,String m,Class<?>[] t,Object...v)throws Exception{Method method=(o instanceof Class?(Class<?>)o:o.getClass()).getMethod(m,t);method.setAccessible(true);return method.invoke(o instanceof Class?null:o,v);}
    static Map<String,Object> result(Object...v){Map<String,Object> m=new LinkedHashMap<>();for(int i=0;i<v.length;i+=2)m.put(String.valueOf(v[i]),v[i+1]);return m;}
    static Map<String,Object> waitFor(String reason){return result("kind","wait","reason",reason);}
    static Map<String,Object> object(Object o,int option,String why){return result("kind","object","object",o,"option",option,"reason",why);}
    static Map<String,Object> inv(Object i,String action,String why)throws Exception{return result("kind","inventory","id",call(i,"getId"),"action",action,"reason",why);}
    static int sx(Object s,Object pos)throws Exception{return ((Number)invoke(s,"sourceX",new Class[]{pos.getClass()},pos)).intValue();}
    static int sy(Object s,Object pos)throws Exception{return ((Number)invoke(s,"sourceY",new Class[]{pos.getClass()},pos)).intValue();}
    static int px(Object p,Object s)throws Exception{return sx(s,call(p,"getPosition"));}
    static int py(Object p,Object s)throws Exception{return sy(s,call(p,"getPosition"));}
    static long key(int x,int y){return ((long)x<<32)|(y&0xffffffffL);}
    static int nativeId(Object s,String kind,int source)throws Exception{return ((Number)invoke(type(s,"io.ruin.model.activities.raids.NearRealityRaidAssets"),kind,new Class[]{int.class},source)).intValue();}
    static int option(Object o,String name)throws Exception{Object d=call(o,"getDef");return ((Number)invoke(d,"getOption",new Class[]{String[].class},(Object)new String[]{name})).intValue();}
    static Object supply(Object p,Object s,int source)throws Exception{String name=source==27295?"Water container":source==27296?"Mirror":source==2347?"Hammer":"Neutralising potion";int id=((Number)invoke(type(s,"io.ruin.model.activities.raids.NearRealityRaidAssets"),"item",new Class[]{int.class,String.class},source,name)).intValue();return item(p,id);}
    static Iterable<?> iterable(Object a){return a instanceof Iterable?(Iterable<?>)a:Arrays.asList((Object[])a);}
    static List<Object> scan(Object s,Object controller)throws Exception{List<Object> cached=SCANNED.get(controller);if(cached!=null)return cached;List<Object> found=new ArrayList<>();Object map=get(s,"map"),room=get(s,"room");Class<?> tile=type(s,"io.ruin.model.map.Tile");for(Object region:iterable(call(map,"getRegions")))if(region!=null){int bx=num(region,"baseX"),by=num(region,"baseY"),z=num(room,"plane");for(int x=bx;x<bx+64;x++)for(int y=by;y<by+64;y++){Object t=invoke(tile,"get",new Class[]{int.class,int.class,int.class,boolean.class},x,y,z,false);if(t!=null&&get(t,"gameObjects")!=null)for(Object o:iterable(get(t,"gameObjects")))if(o!=null&&num(o,"id")>=0)found.add(o);}}SCANNED.put(controller,found);return found;}
    static Object findObject(Object s,Object e,int source)throws Exception{int id=nativeId(s,"object",source);for(Object o:scan(s,e))if(get(o,"tile")!=null&&num(o,"id")==id)return o;throw new IllegalStateException("Actual owned native puzzle object absent "+source);}
    static int clip(Object s,int x,int y)throws Exception{Object at=invoke(s,"tile",new Class[]{int.class,int.class},x,y);Object c=type(s,"io.ruin.model.map.ClipUtils").getField("REGULAR").get(null);return ((Number)invoke(c,"getAbs",new Class[]{int.class,int.class,int.class},call(at,"getX"),call(at,"getY"),call(at,"getZ"))).intValue();}
    static boolean floor(Object s,int x,int y)throws Exception{return (clip(s,x,y)&(0x200000|0x40000|0x100))==0;}
    static int[] next(Object p,Object s,int tx,int ty,Set<Long> avoid)throws Exception{
        int x=px(p,s),y=py(p,s);if(x==tx&&y==ty)return null;int bx=num(get(s,"room"),"baseX"),by=num(get(s,"room"),"baseY");Map<Long,Long> prev=new HashMap<>();ArrayDeque<int[]> q=new ArrayDeque<>();long start=key(x,y),goal=key(tx,ty);q.add(new int[]{x,y});prev.put(start,start);while(!q.isEmpty()){int[] a=q.remove();if(key(a[0],a[1])==goal)break;for(int d=0;d<4;d++){int nx=a[0]+DX[d],ny=a[1]+DY[d];long k=key(nx,ny);if(nx<bx||nx>=bx+64||ny<by||ny>=by+64||prev.containsKey(k)||(avoid!=null&&avoid.contains(k)&&k!=goal)||(clip(s,nx,ny)&MASK[d])!=0)continue;prev.put(k,key(a[0],a[1]));q.add(new int[]{nx,ny});}}
        if(!prev.containsKey(goal))return null;long at=goal;while(prev.get(at)!=start)at=prev.get(at);return new int[]{(int)(at>>32),(int)at};
    }
    static final class Edge{final int x,y,tx,ty;final Object object;Edge(int x,int y,int tx,int ty,Object object){this.x=x;this.y=y;this.tx=tx;this.ty=ty;this.object=object;}}
    static List<Edge> traversals(Object s)throws Exception{List<Edge> edges=new ArrayList<>();if(!String.valueOf(get(s,"room")).equals("SCABARAS"))return edges;Object e=get(s,"encounter");for(Object o:scan(s,e))if(get(o,"tile")!=null){int id=num(o,"id"),x=sx(s,call(o,"getPosition")),y=sy(s,call(o,"getPosition"));if(id==nativeId(s,"object",45135)&&x==3532&&(y==5274||y==5286)){edges.add(new Edge(3531,y,3533,y,o));edges.add(new Edge(3533,y,3531,y,o));}else if(id==nativeId(s,"object",45343)&&x==3548&&(y==5277||y==5283)){int from=y<5280?5276:5284;edges.add(new Edge(3548,from,3548,from==5276?5284:5276,o));}else if(id==nativeId(s,"object",45396)&&x==3560&&y==5280){edges.add(new Edge(3560,5277,3560,5283,o));edges.add(new Edge(3560,5283,3560,5277,o));}}return edges;}
    static Map<String,Object> move(Object p,Object s,int x,int y,Set<Long> avoid,String why)throws Exception{int[] n=next(p,s,x,y,avoid);if(n!=null)return result("kind","move","x",n[0],"y",n[1],"reason",why,"stopCombat",true);if(px(p,s)==x&&py(p,s)==y)return waitFor("At "+why);
        List<Edge> edges=traversals(s);int bx=num(get(s,"room"),"baseX"),by=num(get(s,"room"),"baseY");long start=key(px(p,s),py(p,s)),goal=key(x,y);Map<Long,Long> prev=new HashMap<>();Map<Long,Object> action=new HashMap<>();ArrayDeque<Long> q=new ArrayDeque<>();prev.put(start,start);q.add(start);while(!q.isEmpty()){long at=q.remove();if(at==goal)break;int ax=(int)(at>>32),ay=(int)at;for(int d=0;d<4;d++){int nx=ax+DX[d],ny=ay+DY[d];long k=key(nx,ny);if(nx<bx||nx>=bx+64||ny<by||ny>=by+64||prev.containsKey(k)||(avoid!=null&&avoid.contains(k)&&k!=goal)||(clip(s,nx,ny)&MASK[d])!=0)continue;prev.put(k,at);q.add(k);}for(Edge edge:edges)if(edge.x==ax&&edge.y==ay){long k=key(edge.tx,edge.ty);if(prev.containsKey(k)||(avoid!=null&&avoid.contains(k)&&k!=goal)||!floor(s,edge.tx,edge.ty))continue;prev.put(k,at);action.put(k,edge.object);q.add(k);}}
        if(!prev.containsKey(goal))throw new IllegalStateException("No native cardinal/traversal route to "+x+","+y+" for "+why);long at=goal;while(prev.get(at)!=start)at=prev.get(at);Object object=action.get(at);if(object!=null)return object(object,1,"Use actual authored native traversal: "+why);return result("kind","move","x",(int)(at>>32),"y",(int)at,"stopCombat",true,"reason","Approach native traversal: "+why);
    }
    static Map<String,Object> stepOn(Object p,Object s,int x,int y,Set<Long> avoid,String why)throws Exception{if(px(p,s)!=x||py(p,s)!=y)return move(p,s,x,y,avoid,why);for(int d=0;d<4;d++){int nx=x+DX[d],ny=y+DY[d];if((avoid==null||!avoid.contains(key(nx,ny)))&&(clip(s,nx,ny)&MASK[d])==0)return result("kind","move","x",nx,"y",ny,"reason","Step off before "+why,"stopCombat",true);}throw new IllegalStateException("No neutral step off authored pressure tile");}
    static boolean nativeStep(Object s,int x,int y,int nx,int ny)throws Exception{
        if(Math.max(Math.abs(nx-x),Math.abs(ny-y))!=1)return false;
        Object from=invoke(s,"tile",new Class[]{int.class,int.class},x,y),to=invoke(s,"tile",new Class[]{int.class,int.class},nx,ny);
        return invoke(type(s,"io.ruin.model.map.route.routes.DumbRoute"),"getDirection",new Class[]{int.class,int.class,int.class,int.class,int.class,int.class},call(from,"getX"),call(from,"getY"),call(from,"getZ"),1,call(to,"getX"),call(to,"getY"))!=null;
    }
    static int[] nextEight(Object p,Object s,int tx,int ty,Set<Long> avoid,List<Edge> edges,Map<Long,Object> actions)throws Exception{
        int x=px(p,s),y=py(p,s);if(x==tx&&y==ty)return null;
        int bx=num(get(s,"room"),"baseX"),by=num(get(s,"room"),"baseY");long start=key(x,y),goal=key(tx,ty);
        Map<Long,Long> prev=new HashMap<>();ArrayDeque<Long> queue=new ArrayDeque<>();prev.put(start,start);queue.add(start);
        while(!queue.isEmpty()){
            long at=queue.remove();if(at==goal)break;int ax=(int)(at>>32),ay=(int)at;
            for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++){
                if(dx==0&&dy==0)continue;int nx=ax+dx,ny=ay+dy;long k=key(nx,ny);
                if(nx<bx||nx>=bx+64||ny<by||ny>=by+64||prev.containsKey(k)||(avoid!=null&&avoid.contains(k)&&k!=goal)||!nativeStep(s,ax,ay,nx,ny))continue;
                prev.put(k,at);queue.add(k);
            }
            for(Edge edge:edges)if(edge.x==ax&&edge.y==ay){long k=key(edge.tx,edge.ty);if(prev.containsKey(k)||(avoid!=null&&avoid.contains(k)&&k!=goal)||!floor(s,edge.tx,edge.ty))continue;prev.put(k,at);actions.put(k,edge.object);queue.add(k);}
        }
        if(!prev.containsKey(goal))return null;long at=goal;while(prev.get(at)!=start)at=prev.get(at);
        return new int[]{(int)(at>>32),(int)at};
    }
    static Map<String,Object> stepOnMemory(Object p,Object s,int x,int y,Set<Long> avoid,String why)throws Exception{
        int px=px(p,s),py=py(p,s);
        if(px==x&&py==y){
            for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++){int nx=x+dx,ny=y+dy;if((dx!=0||dy!=0)&&(avoid==null||!avoid.contains(key(nx,ny)))&&nativeStep(s,x,y,nx,ny))return result("kind","move","x",nx,"y",ny,"stopCombat",true,"reason","Step off before "+why);}
            throw new IllegalStateException("No native neutral step off current memory pressure tile");
        }
        Map<Long,Object> actions=new HashMap<>();int[] next=nextEight(p,s,x,y,avoid,Collections.emptyList(),actions);
        if(next==null)next=nextEight(p,s,x,y,avoid,traversals(s),actions);
        if(next==null)throw new IllegalStateException("No native diagonal/traversal route to requested memory tile");
        Object object=actions.get(key(next[0],next[1]));
        if(object!=null)return object(object,1,"Use actual authored traversal before "+why);
        return result("kind","move","x",next[0],"y",next[1],"stopCombat",true,"reason",why);
    }
    static Set<Long> pressure(Object e)throws Exception{boolean[] done=(boolean[])get(e,"done");int[] slots=(int[])get(e,"slots");Set<Long> set=new HashSet<>();for(int t:new int[]{0,1,3})if(!done[t]){int bx=BASE[slots[t]][0],by=BASE[slots[t]][1];if(t==1){for(int[][] row:SUM)for(int[] a:row)set.add(key(bx+a[0],by+a[1]));}else for(int[] a:t==0?LIGHT:MEMORY)set.add(key(bx+a[0],by+a[1]));}return set;}
    static int lightFirst(int start){int[] prev=new int[256],chosen=new int[256];Arrays.fill(prev,-1);ArrayDeque<Integer> q=new ArrayDeque<>();prev[start]=start;q.add(start);while(!q.isEmpty()){int a=q.remove();if(a==255)break;for(int i=0;i<8;i++){int n=a^(1<<i)^(1<<((i+7)%8))^(1<<((i+1)%8));if(prev[n]<0){prev[n]=a;chosen[n]=i;q.add(n);}}}if(start==255)return -1;int at=255;while(prev[at]!=start)at=prev[at];return chosen[at];}
    static int sumValue(int x,int y){for(int v=0;v<9;v++)for(int[] a:SUM[v])if(a[0]==x&&a[1]==y)return v+1;return 0;}
    interface StepAllowed{boolean allowed(int x,int y,int direction)throws Exception;}
    static int[] sumPathNext(int x,int y,int startSum,int goal,StepAllowed allowed)throws Exception{int total=7*7*(goal+1),start=(startSum*7+x)*7+y;int[] prev=new int[total];Arrays.fill(prev,-1);prev[start]=start;ArrayDeque<Integer> q=new ArrayDeque<>();q.add(start);int found=-1;while(!q.isEmpty()){int a=q.remove(),ay=a%7,ax=(a/7)%7,sum=a/49;if(sum==goal){found=a;break;}for(int d=0;d<4;d++){int nx=ax+DX[d],ny=ay+DY[d];if(nx<0||nx>6||ny<0||ny>6)continue;int ns=sum+sumValue(nx,ny);if(ns>goal||!allowed.allowed(nx,ny,d))continue;int at=(ns*7+nx)*7+ny;if(prev[at]>=0)continue;prev[at]=a;q.add(at);}}if(found<0)throw new IllegalStateException("No actual pressure-sum route from current total");while(prev[found]!=start&&found!=start)found=prev[found];return new int[]{(found/7)%7,found%7};}
    static int[] sumNext(Object p,Object s,int bx,int by,int startSum,int goal)throws Exception{int x=px(p,s)-bx,y=py(p,s)-by;if(x<0||x>6||y<0||y>6)return null;int[] n=sumPathNext(x,y,startSum,goal,(nx,ny,d)->(clip(s,bx+nx,by+ny)&MASK[d])==0);return new int[]{bx+n[0],by+n[1]};}
    static Map<String,Object> memory(Object p,Object s,Object e,Set<Long> avoid,int bx,int by)throws Exception{
        if(num(e,"display")>=0)return waitFor("Observe native five-tile flashing sequence");
        if(!(Boolean)get(e,"sequenceReady")){
            if(!memoryKnown(p,e)&&!markMemoryReady(p,e,px(p,s),py(p,s),bx,by)){
                Set<Long> approach=avoid==null?null:new HashSet<>(avoid);
                if(approach!=null)for(int[] offset:MEMORY)approach.remove(key(bx+offset[0],by+offset[1]));
                return move(p,s,bx+3,by+3,approach,"One-time normal traversal into the actual memory module before its first button action");
            }
            return object(ownedObjectAt(s,e,nativeId(s,"object",45338),bx-1,by+5),1,"Request actual memory display");
        }
        int index=((int[])get(e,"memory"))[num(e,"sequenceIndex")];
        return stepOnMemory(p,s,bx+MEMORY[index][0],by+MEMORY[index][1],avoid,"Repeat displayed memory pressure tile");
    }
    static Object ownedObjectAt(Object s,Object e,int id,int x,int y)throws Exception{
        Object found=null;
        for(Object object:(List<?>)get(e,"objects"))if(get(object,"tile")!=null&&num(object,"id")==id
                &&sx(s,call(object,"getPosition"))==x&&sy(s,call(object,"getPosition"))==y){
            if(found!=null)throw new IllegalStateException("Ambiguous live owned memory control");
            found=object;
        }
        if(found==null)throw new IllegalStateException("Exact live owned memory control absent");
        return found;
    }
    static boolean apmekenCratePosition(int source,int x,int y){
        if(source==45497)return x==3793&&y==5284||x==3798&&y==5287||x==3818&&y==5273;
        if(source==45498)return x==3795&&y==5285||x==3798&&y==5273||x==3818&&y==5287;
        return false;
    }
    static Object selectApmekenCrate(Object p,Object s,Object e,int source,int nativeId,List<?> candidates)throws Exception{
        if((source!=45497&&source!=45498)||nativeId<0||get(s,"player")!=p||get(s,"encounter")!=e
                ||!String.valueOf(get(s,"room")).equals("APMEKEN")||!Boolean.TRUE.equals(call(s,"valid")))
            throw new IllegalStateException("Current owned Apmeken supply selection required");
        int lease=num(s,"lease");Object sw=get(get(s,"map"),"swRegion");
        if(sw==null||num(sw,"dynamicIndex")!=lease||num(call(call(p,"getPosition"),"getRegion"),"dynamicIndex")!=lease)
            throw new IllegalStateException("Apmeken supply lease mismatch");
        int x=px(p,s),y=py(p,s);boolean arenaSide=x>=3797;Object best=null;int score=Integer.MAX_VALUE;
        for(Object object:candidates){
            if(object==null||get(object,"tile")==null||num(object,"id")!=nativeId||num(object,"type")!=10)continue;
            Object position=call(object,"getPosition");int cx=sx(s,position),cy=sy(s,position);
            if((cx>=3797)!=arenaSide||!apmekenCratePosition(source,cx,cy)
                    ||num(call(position,"getRegion"),"dynamicIndex")!=lease)continue;
            if(((Number)call(position,"getZ")).intValue()!=((Number)call(call(p,"getPosition"),"getZ")).intValue())continue;
            Object[] actions=(Object[])get(object,"actions");
            if(option(object,"Take")!=1||actions==null||actions.length<1||actions[0]==null)continue;
            int dx=Math.abs(cx-x),dy=Math.abs(cy-y),candidate=Math.max(dx,dy)*100+dx+dy;
            if(candidate<score){score=candidate;best=object;}
        }
        if(best==null)throw new IllegalStateException("No live bound native supply crate on current Apmeken side");
        return best;
    }
    static Object apmekenCrate(Object p,Object s,Object e,int source)throws Exception{
        return selectApmekenCrate(p,s,e,source,nativeId(s,"object",source),scan(s,e));
    }
    static final class VolatileBlast {final int x,y,remaining;VolatileBlast(int x,int y,int remaining){this.x=x;this.y=y;this.remaining=remaining;}}
    interface VolatileStep {boolean open(int x,int y,int nx,int ny)throws Exception;}
    static boolean apmekenArena(int x,int y){return x>=3797&&x<=3819&&y>=5267&&y<=5293;}
    static boolean volatileSafe(int x,int y,List<VolatileBlast> blasts){for(VolatileBlast b:blasts)if(Math.max(Math.abs(x-b.x),Math.abs(y-b.y))<=1)return false;return true;}
    static int[] volatileEscapeStep(int x,int y,List<VolatileBlast> blasts,Set<Long> venom,VolatileStep physical)throws Exception{
        int deadline=Integer.MAX_VALUE;for(VolatileBlast b:blasts)if(Math.max(Math.abs(x-b.x),Math.abs(y-b.y))<=1)deadline=Math.min(deadline,b.remaining);
        for(int budget=1;budget<=3&&budget+1<=deadline;budget++){
            ArrayDeque<int[]> queue=new ArrayDeque<>();Set<Long> seen=new HashSet<>();queue.add(new int[]{x,y,x,y,0});seen.add(key(x,y));
            while(!queue.isEmpty()){
                int[] at=queue.remove();if(at[4]>=budget)continue;
                for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++){
                    if(dx==0&&dy==0)continue;int nx=at[0]+dx,ny=at[1]+dy;long cell=key(nx,ny);
                    if(!apmekenArena(nx,ny)||seen.contains(cell)||venom.contains(cell)||!physical.open(at[0],at[1],nx,ny))continue;
                    boolean transit=true;for(VolatileBlast b:blasts)if(Math.max(Math.abs(nx-b.x),Math.abs(ny-b.y))<=1&&b.remaining<budget+1){transit=false;break;}
                    if(!transit)continue;seen.add(cell);int firstX=at[4]==0?nx:at[2],firstY=at[4]==0?ny:at[3];
                    if(volatileSafe(nx,ny,blasts))return new int[]{firstX,firstY};
                    queue.add(new int[]{nx,ny,firstX,firstY,at[4]+1});
                }
            }
        }
        return null;
    }
    static boolean apmekenNativeStep(Object s,int x,int y,int nx,int ny)throws Exception{
        if(!nativeStep(s,x,y,nx,ny))return false;Object at=invoke(s,"tile",new Class[]{int.class,int.class},nx,ny);
        Object tile=invoke(type(s,"io.ruin.model.map.Tile"),"get",new Class[]{int.class,int.class,int.class,boolean.class},call(at,"getX"),call(at,"getY"),call(at,"getZ"),false);
        return tile!=null&&Boolean.TRUE.equals(call(tile,"isFloorFreeCheckDecor"));
    }
    static Map<String,Object> apmekenVolatile(Object p,final Object s,Object e)throws Exception{
        List<VolatileBlast> blasts=new ArrayList<>();int cycle=num(e,"cycle");
        for(Object baboon:(List<?>)get(e,"monkeys")){Object npc=get(baboon,"npc");if(num(baboon,"source")!=11716||num(baboon,"explodeAt")<=0||Boolean.TRUE.equals(get(baboon,"dying"))||Boolean.TRUE.equals(call(npc,"isRemoved"))||Boolean.TRUE.equals(call(call(npc,"getCombat"),"isDead")))continue;blasts.add(new VolatileBlast(sx(s,call(npc,"getPosition")),sy(s,call(npc,"getPosition")),num(baboon,"explodeAt")-cycle));}
        if(blasts.isEmpty())return null;int x=px(p,s),y=py(p,s);Set<Long> venom=((Map<Long,?>)get(e,"venom")).keySet();
        if(volatileSafe(x,y,blasts)&&!venom.contains(key(x,y)))return result("kind","wait","style","RANGED","stopCombat",true,"stopAlways",true,"reason","Hold safe native tile until live volatile countdowns resolve");
        int[] next=volatileEscapeStep(x,y,blasts,venom,new VolatileStep(){public boolean open(int fx,int fy,int tx,int ty)throws Exception{return apmekenNativeStep(s,fx,fy,tx,ty);}});
        if(next==null)return result("kind","wait","style","RANGED","stopCombat",true,"stopAlways",true,"reason","No margin-safe native dodge; hold normally and accept genuine volatile damage");
        return result("kind","move","x",next[0],"y",next[1],"style","RANGED","stopCombat",true,"reason","One adjacent native step avoids all live volatile blasts and venom");
    }
    public static Map<String,Object> plan(Object p,Object s,Object e,long tick)throws Exception{String room=String.valueOf(get(s,"room"));switch(room){case "CRONDIS":return crondis(p,s,e);case "HET":return het(p,s,e);case "SCABARAS":return scabaras(p,s,e,tick);case "APMEKEN":return apmeken(p,s,e);default:throw new IllegalArgumentException("No puzzle plan for "+room);}}
    static Map<String,Object> crondis(Object p,Object s,Object e)throws Exception{Object jar=supply(p,s,27295);if(jar!=null&&((Number)invoke(jar,"getAttributeInt",new Class[]{String.class,int.class},"toa.water",0)).intValue()>0)return result("kind","npc","npc",get(e,"palm"),"option",1,"style","MELEE","prayer","PROTECT_FROM_MELEE","reason","Deliver actual resident water to palm");int id=nativeId(s,"object",45398);Object best=null;int distance=Integer.MAX_VALUE;for(Object o:(List<?>)get(e,"objects"))if(get(o,"tile")!=null&&num(o,"id")==id){int d=Math.abs(sx(s,call(o,"getPosition"))-px(p,s))+Math.abs(sy(s,call(o,"getPosition"))-py(p,s));if(d<distance){distance=d;best=o;}}if(best==null)return waitFor("Wait for an actual waterfall to refill");Map<String,Object> out=object(best,1,"Fill resident water container via real waterfall");out.put("prayer","PROTECT_FROM_MELEE");return out;}
    static Map<String,Object> het(Object p,Object s,Object e)throws Exception{if(num(e,"downTicks")>0){if((Boolean)get(e,"mining"))return waitFor("Native pickaxe mining ticks in progress");return result("kind","npc","npc",get(e,"seal"),"option",1,"reason","Mine genuinely weakened seal with real pickaxe");}for(Object o:(List<?>)get(e,"layout"))if(get(o,"tile")!=null&&(num(o,"id")==nativeId(s,"object",45462)||num(o,"id")==nativeId(s,"object",45464)))return object(o,1,"Remove authored movable barrier through native object action");
        int[][] targets=HET[num(e,"layoutIndex")];Collection<?> mirrors=((Map<?,?>)get(e,"mirrors")).values();for(Object mirror:new ArrayList<>(mirrors))if(num(mirror,"source")==45455){boolean target=false;for(int[] a:targets)if(num(mirror,"x")==a[0]&&num(mirror,"y")==a[1])target=true;if(!target){Object o=get(mirror,"object");return object(o,option(o,"Pick-up"),"Pick up actual movable mirror");}}
        for(int[] a:targets){Object installed=null;for(Object mirror:mirrors)if(num(mirror,"source")==45455&&num(mirror,"x")==a[0]&&num(mirror,"y")==a[1])installed=mirror;if(installed!=null){if(num(installed,"rotation")!=a[2]){Object o=get(installed,"object");return object(o,option(o,"Rotate-clockwise"),"Rotate real mirror to authored beam solution");}continue;}Object held=supply(p,s,27296);if(held==null)throw new IllegalStateException("No native mirror loan retained for remaining placement");if(px(p,s)!=a[0]||py(p,s)!=a[1])return move(p,s,a[0],a[1],null,"Walk to real mirror placement");return inv(held,"Place","Place actual resident mirror on player tile");}return waitFor("Await native nine-tick beam and shield weakening");}
    static boolean usable(int type,int[] slots,boolean[] done){if(done[type])return false;int opposite=slots[type]==1?2:slots[type]==3?0:-1;if(opposite<0)return true;for(int t=0;t<4;t++)if(slots[t]==opposite)return done[t];return false;}
    static Map<String,Object> scabaras(Object p,Object s,Object e,long tick)throws Exception{PillarShot launched=SHOTS.get(p);if(launched!=null&&launched.encounter==e&&!launched.stopped&&((Number)get(call(p,"getCombat"),"lastAttackTick")).longValue()>launched.beforeAttack){launched.stopped=true;return result("kind","wait","style","MAGIC","prayer","PROTECT_FROM_MISSILES","stopCombat",true,"stopAlways",true,"reason","Observed one real powered staff launch; native same-tile click stops repeating, even on module completion");}int[] slots=(int[])get(e,"slots");boolean[] done=(boolean[])get(e,"done");Set<Long> avoid=pressure(e);for(int t:new int[]{0,1,2,3})if(usable(t,slots,done)){int bx=BASE[slots[t]][0],by=BASE[slots[t]][1];if(t==0){int i=lightFirst(num(e,"lights"));return stepOn(p,s,bx+LIGHT[i][0],by+LIGHT[i][1],avoid,"Toggle lights by normal pressure step");}if(t==1){int[] n=sumNext(p,s,bx,by,num(e,"sum"),num(e,"sumGoal"));if(n==null)return move(p,s,bx+6,by+3,avoid,"Approach sum puzzle along neutral border");return result("kind","move","x",n[0],"y",n[1],"stopCombat",true,"prayer","PROTECT_FROM_MISSILES","reason","Normal pressure route reaches exact authored sum");}if(t==2)return pillar(p,s,e,tick,avoid,bx,by);return memory(p,s,e,avoid,bx,by);}
        if((Boolean)get(e,"matchPending"))return waitFor("Native symbol matching callback pending");boolean[] matched=(boolean[])get(e,"matched");for(int value=0;value<9;value++)if(!matched[value]){int side=num(e,"selectedSouth")==value?1:0;int[] order=(int[])get(e,side==0?"south":"north");int index=-1;for(int i=0;i<9;i++)if(order[i]==value)index=i;return object(((Object[][])get(e,"matchTiles"))[side][index],1,"Match actual final symbol pair");}return waitFor("All native Scabaras devices complete; await transition");}
    static Map<String,Object> apmeken(Object p,Object s,Object e)throws Exception{Map<String,Object> volatilePlan=apmekenVolatile(p,s,e);if(volatilePlan!=null)return volatilePlan;Object hammer=supply(p,s,2347),potion=supply(p,s,27297);if(hammer==null)return object(apmekenCrate(p,s,e,45497),1,"Take real hammer from authored supply crate");if(potion==null)return object(apmekenCrate(p,s,e,45498),1,"Take real neutralising potion from authored crate");int special=num(e,"special");boolean any=false;for(boolean b:(boolean[])get(e,"actions"))any|=b;if(special>=0&&!any){int[][] at=special==0?new int[][]{{3800,5284},{3816,5284},{3816,5276},{3800,5276}}:new int[][]{{3803,5289},{3811,5289},{3811,5269},{3803,5269}};int best=0;for(int i=1;i<4;i++)if(Math.abs(px(p,s)-at[i][0])+Math.abs(py(p,s)-at[i][1])<Math.abs(px(p,s)-at[best][0])+Math.abs(py(p,s)-at[best][1]))best=i;if(special==0){if(px(p,s)!=at[best][0]||py(p,s)!=at[best][1])return move(p,s,at[best][0],at[best][1],((Map<Long,?>)get(e,"venom")).keySet(),"Reach actual active vent");return inv(potion,"Pour","Pour actual neutralising supply onto active vent");}for(Object o:(List<?>)get(e,"objects"))if(get(o,"tile")!=null&&num(o,"id")==nativeId(s,"object",45494)&&sx(s,call(o,"getPosition"))==at[best][0]&&sy(s,call(o,"getPosition"))==at[best][1])return object(o,1,"Repair real roof support during active warning");}
        Object best=null;int score=Integer.MIN_VALUE;for(Object b:(List<?>)get(e,"monkeys")){Object n=get(b,"npc");if((Boolean)get(b,"dying")||(Boolean)call(n,"isRemoved"))continue;int source=num(b,"source"),d=Math.max(Math.abs(px(p,s)-sx(s,call(n,"getPosition"))),Math.abs(py(p,s)-sy(s,call(n,"getPosition"))));int weight=(source==11715?200:source==11716?150:((Boolean)get(b,"critical"))?100:0)-d;if(weight>score){score=weight;best=b;}}
        if(best==null)return waitFor("Await next authored baboon wave");int source=num(best,"source");return result("kind","attack","npc",get(best,"npc"),"style","RANGED","prayer",source==11710||source==11713?"PROTECT_FROM_MISSILES":source==11711||source==11714||source==11715?"PROTECT_FROM_MAGIC":"PROTECT_FROM_MELEE","boost","EAGLE_EYE","reason","Defeat real authored baboons with ordinary combat ticks");}
    public static Object rewardChest(Object s)throws Exception{Set<Integer> ids=new HashSet<>();for(int source:new int[]{46082,46217,44545,46215,46219,46218,44547,29994,46216,46224})ids.add(nativeId(s,"object",source));Object p=get(s,"player"),best=null;int dist=Integer.MAX_VALUE;for(Object o:scan(s,get(s,"encounter")))if(get(o,"tile")!=null&&ids.contains(num(o,"id"))){int d=Math.abs(px(p,s)-sx(s,call(o,"getPosition")))+Math.abs(py(p,s)-sy(s,call(o,"getPosition")));if(d<dist){dist=d;best=o;}}if(best==null)throw new IllegalStateException("No real bound burial chest in actual native room");return best;}
    public static String signature(Object e)throws Exception{StringBuilder s=new StringBuilder(e.getClass().getSimpleName());for(String f:new String[]{"water","layoutIndex","downTicks","lights","sum","pillarIndex","sequenceIndex","wave","phase","style","orbPhase","skullsKilled"})try{Object value=get(e,f);s.append(':').append(f).append('=').append(value);}catch(NoSuchFieldException ignored){}for(String f:new String[]{"done","matched"})try{s.append(':').append(Arrays.toString((boolean[])get(e,f)));}catch(NoSuchFieldException ignored){}try{for(Object n:(List<?>)get(e,"actors"))if(!(Boolean)call(n,"isRemoved"))s.append(':').append(call(n,"getIndex")).append('=').append(call(n,"getHp"));}catch(NoSuchFieldException ignored){}return s.toString();}
}
