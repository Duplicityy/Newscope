package io.ruin.model.activities.raids.toa.port;

import io.ruin.model.activities.raids.auto.RaidAutoAccess;
import io.ruin.model.entity.player.Player;
import io.ruin.model.item.Item;
import java.lang.ref.WeakReference;
import java.lang.reflect.*;
import java.util.*;

/** Read-only ToA observation/planning. Returns normal-action descriptors; never applies damage or changes encounter state. */
public final class ToaAutoBossPlanner {
    private static final Map<Object,Memory> MEMORY=new WeakHashMap<Object,Memory>();
    private static final class Memory {
        WeakReference<Object> encounter=new WeakReference<Object>(null);
        int preferredQuadrant=-1;
    }
    private static final int[][] SHADOWS={{3685,5412},{3685,5401},{3674,5401},{3674,5412}};
    private static final int[][] MEMORY_TILES={{3682,5409},{3682,5406},{3679,5406},{3679,5409}};
    private ToaAutoBossPlanner(){}
    public static void reset(Object player){MEMORY.remove(player);}
    private static Object f(Object o,String name)throws Exception{return o==null?null:RaidAutoAccess.get(o,name);}
    private static Object optional(Object o,String name)throws Exception{try{return f(o,name);}catch(NoSuchFieldException missing){return null;}}
    private static int n(Object o,String name)throws Exception{Object value=f(o,name);return value instanceof Number?((Number)value).intValue():0;}
    private static boolean b(Object o,String name)throws Exception{return Boolean.TRUE.equals(f(o,name));}
    private static Object call(Object o,String name)throws Exception{return RaidAutoAccess.call(o,name);}
    private static Object invoke(Object target,String method,Object...args)throws Exception{
        for(Method m:target.getClass().getMethods())if(m.getName().equals(method)&&m.getParameterTypes().length==args.length){
            try{return m.invoke(target,args);}catch(IllegalArgumentException mismatch){}
        }
        throw new NoSuchMethodException(target.getClass().getName()+"."+method);
    }
    private static Iterable<?> list(Object value){return value instanceof Iterable?(Iterable<?>)value:Collections.emptyList();}
    private static List<Object> actors(Object value)throws Exception{
        List<Object> out=new ArrayList<Object>();for(Object entry:list(value)){
            Object actor=optional(entry,"npc");if(actor==null)actor=entry;if(present(actor))out.add(actor);
        }return out;
    }
    private static boolean present(Object npc)throws Exception{
        return npc!=null&&npc.getClass().getName().equals("io.ruin.model.entity.npc.NPC")&&!Boolean.TRUE.equals(call(npc,"isRemoved"));
    }
    private static boolean alive(Object npc)throws Exception{
        return present(npc)&&((Number)call(npc,"getHp")).intValue()>0;
    }
    private static int[] pos(Object session,Object position)throws Exception{
        int ax=((Number)call(position,"getX")).intValue(),ay=((Number)call(position,"getY")).intValue();
        // Authored pattern arrays contain source coordinates; spawned entities use the dynamic lease.
        if(ax>=3000&&ax<4500&&ay>=5000&&ay<5500)return new int[]{ax,ay};
        return new int[]{((Number)invoke(session,"sourceX",position)).intValue(),((Number)invoke(session,"sourceY",position)).intValue()};
    }
    private static int[] at(Object s,Object entity)throws Exception{return pos(s,call(entity,"getPosition"));}
    private static int distance(int x,int y,int tx,int ty){return Math.max(Math.abs(x-tx),Math.abs(y-ty));}
    private static int size(Object npc)throws Exception{return Math.max(1,((Number)call(npc,"getSize")).intValue());}
    private static boolean overlaps(Object s,Object npc,int x,int y)throws Exception{if(!present(npc))return false;int[] p=at(s,npc);int size=size(npc);return x>=p[0]&&x<p[0]+size&&y>=p[1]&&y<p[1]+size;}
    private static boolean free(Object s,int x,int y)throws Exception{
        Object tile=invoke(s,"tile",x,y);Class<?> type=Class.forName("io.ruin.model.map.Tile",false,s.getClass().getClassLoader());
        Object floor=type.getMethod("get",int.class,int.class,int.class,boolean.class).invoke(null,((Number)call(tile,"getX")).intValue(),((Number)call(tile,"getY")).intValue(),((Number)call(tile,"getZ")).intValue(),false);
        return floor!=null&&Boolean.TRUE.equals(call(floor,"isFloorFreeCheckDecor"));
    }
    private static long key(int x,int y){return ((long)x<<32)|(y&0xffffffffL);}
    private static void mark(Set<Long> danger,int x,int y,int radius){for(int dx=-radius;dx<=radius;dx++)for(int dy=-radius;dy<=radius;dy++)danger.add(key(x+dx,y+dy));}
    private static void captured(Object s,Object value,int depth,List<int[]> points,Set<Object> seen)throws Exception{
        if(value==null||depth>4||points.size()>1600||!seen.add(value))return;
        String name=value.getClass().getName();
        if(name.equals("io.ruin.model.map.Position")){points.add(pos(s,value));return;}
        if(value instanceof Iterable){for(Object entry:(Iterable<?>)value)captured(s,entry,depth+1,points,seen);return;}
        if(value.getClass().isArray()&&!value.getClass().getComponentType().isPrimitive()){for(Object entry:(Object[])value)captured(s,entry,depth+1,points,seen);return;}
        if(!(value instanceof Runnable))return;
        for(Field field:value.getClass().getDeclaredFields())if(!Modifier.isStatic(field.getModifiers())){
            field.setAccessible(true);Object child=field.get(value);
            if(child instanceof Runnable||child instanceof Iterable||child!=null&&(child.getClass().isArray()||child.getClass().getName().equals("io.ruin.model.map.Position")))captured(s,child,depth+1,points,seen);
        }
    }
    private static void pending(Object e,Object s,Set<Long> danger,int radius)throws Exception{
        int cycle=n(e,"cycle");Object tasks=optional(e,"pending");if(tasks==null)tasks=optional(e,"due");
        for(Object due:list(tasks)){
            int when=n(due,"at");if(when<cycle||when>cycle+4)continue;
            List<int[]> points=new ArrayList<int[]>();captured(s,f(due,"task"),0,points,Collections.newSetFromMap(new IdentityHashMap<Object,Boolean>()));
            for(int[] p:points)mark(danger,p[0],p[1],radius);
        }
    }
    private static String prayerStyle(Object style){String name=String.valueOf(style);return name.equals("MAGIC")?"PROTECT_FROM_MAGIC":name.equals("RANGED")?"PROTECT_FROM_MISSILES":"PROTECT_FROM_MELEE";}
    private static String queuedPrayer(Object p,String fallback)throws Exception{
        int earliest=Integer.MAX_VALUE;String prayer=fallback;
        for(Object hit:list(optional(p,"queuedHits"))){Object style=optional(hit,"attackStyle");if(style==null||Boolean.TRUE.equals(optional(hit,"removed")))continue;int ticks=n(hit,"ticks");if(ticks>=0&&ticks<earliest){earliest=ticks;prayer=prayerStyle(style);}}
        return prayer;
    }
    private static Object capturedStyle(Object value,int depth)throws Exception{
        if(value==null||depth>3||!(value instanceof Runnable))return null;
        for(Field field:value.getClass().getDeclaredFields())if(!Modifier.isStatic(field.getModifiers())){
            field.setAccessible(true);Object child=field.get(value);
            if(child!=null&&child.getClass().getName().equals("io.ruin.model.combat.AttackStyle"))return child;
            if(child instanceof Runnable){Object answer=capturedStyle(child,depth+1);if(answer!=null)return answer;}
        }return null;
    }
    private static String divinePrayer(Object e,String fallback)throws Exception{
        int cycle=n(e,"cycle"),earliest=Integer.MAX_VALUE;String prayer=fallback;
        for(Object due:list(optional(e,"pending"))){int when=n(due,"at");if(when<cycle||when>cycle+6||when>=earliest)continue;Object style=capturedStyle(f(due,"task"),0);if(style!=null){earliest=when;prayer=prayerStyle(style);}}
        return prayer;
    }
    private static Map<String,Object> action(String kind,String style,String prayer,String reason){
        Map<String,Object> out=new LinkedHashMap<String,Object>();out.put("kind",kind);if(style!=null){out.put("style",style);out.put("boost",style.equals("MAGIC")?"MYSTIC_MIGHT":style.equals("RANGED")||style.equals("DARTS")?"EAGLE_EYE":"PIETY");}if(prayer!=null)out.put("prayer",prayer);out.put("reason",reason);return out;
    }
    private static Map<String,Object> waitFor(String style,String prayer,String reason){return action("wait",style,prayer,reason);}
    private static Map<String,Object> move(int x,int y,String style,String prayer,String reason){Map<String,Object> out=action("move",style,prayer,reason);out.put("x",x);out.put("y",y);return out;}
    private static Map<String,Object> attack(Object p,Object npc,String style,String prayer,String reason)throws Exception{
        if(!alive(npc))return waitFor(style,prayer,"No live attackable actor: "+reason);
        Object target=call(call(p,"getCombat"),"getTarget");Map<String,Object> out=action(target==npc?"wait":"attack",style,prayer,reason);out.put("npc",npc);return out;
    }
    private static Map<String,Object> stop(String style,String prayer,String reason){Map<String,Object> out=waitFor(style,prayer,reason);out.put("stopCombat",true);return out;}
    private static Map<String,Object> dodge(Object p,Object s,Object boss,Set<Long> danger,String style,String prayer,String reason)throws Exception{
        int[] here=at(s,p);if(!danger.contains(key(here[0],here[1])))return null;
        int[] best=null;int score=Integer.MAX_VALUE;
        for(int radius=1;radius<=3;radius++)for(int dx=-radius;dx<=radius;dx++)for(int dy=-radius;dy<=radius;dy++){
            if(Math.max(Math.abs(dx),Math.abs(dy))!=radius)continue;int x=here[0]+dx,y=here[1]+dy;
            if(danger.contains(key(x,y))||!free(s,x,y)||overlaps(s,boss,x,y))continue;
            int candidate=radius*100+Math.abs(dx)+Math.abs(dy);if(candidate<score){score=candidate;best=new int[]{x,y};}
        }
        return best==null?stop(style,prayer,"No immediately free safe tile: "+reason):move(best[0],best[1],style,prayer,reason);
    }
    private static Map<String,Object> go(Object p,Object s,int x,int y,String style,String prayer,String reason)throws Exception{
        int[] here=at(s,p);if(here[0]==x&&here[1]==y)return stop(style,prayer,reason);
        if(free(s,x,y))return move(x,y,style,prayer,reason);
        for(int r=1;r<=2;r++)for(int dx=-r;dx<=r;dx++)for(int dy=-r;dy<=r;dy++)if(free(s,x+dx,y+dy))return move(x+dx,y+dy,style,prayer,reason);
        return stop(style,prayer,"Authored destination currently clipped: "+reason);
    }
    public static Map<String,Object> plan(Object p,Object s,Object e,long worldTick)throws Exception{
        if(p==null||s==null||e==null)return waitFor(null,null,"Await active native boss encounter");
        Memory m=MEMORY.get(p);if(m==null||m.encounter.get()!=e){m=new Memory();m.encounter=new WeakReference<Object>(e);MEMORY.put(p,m);}
        String room=String.valueOf(f(s,"room"));Object boss=optional(e,"boss");
        if(room.equals("ZEBAK"))return zebak(p,s,e,boss);
        if(room.equals("KEPHRI"))return kephri(p,s,e,boss);
        if(room.equals("AKKHA"))return akkha(p,s,e,boss,m);
        if(room.equals("BABA"))return baba(p,s,e,boss);
        if(room.equals("WARDENS")||room.equals("WARDENS_FINAL"))return wardens(p,s,e,boss);
        return waitFor(null,null,"Boss planner does not own "+room);
    }
    private static Map<String,Object> zebak(Object p,Object s,Object e,Object boss)throws Exception{
        String prayer=b(e,"mage")?"PROTECT_FROM_MAGIC":"PROTECT_FROM_MISSILES";int[] here=at(s,p);Set<Long> danger=new HashSet<Long>();
        for(Object pool:list(f(e,"poison"))){int[] at=pos(s,call(pool,"getPosition"));mark(danger,at[0],at[1],0);}
        int special=n(e,"specialTick");
        if(special>=0&&b(e,"specialJugs")){
            List<Object> rocks=actors(f(e,"boulders"));int[] destination=null;int best=Integer.MAX_VALUE;
            for(Object rock:rocks){int[] at=at(s,rock);for(int dx=1;dx<=3;dx++)if(free(s,at[0]+dx,at[1])&&!danger.contains(key(at[0]+dx,at[1]))){int d=distance(here[0],here[1],at[0]+dx,at[1]);if(d<best){best=d;destination=new int[]{at[0]+dx,at[1]};}}}
            if(destination!=null)return go(p,s,destination[0],destination[1],"RANGED",prayer,"Hide in actual boulder's protected east lane during Zebak roar");
            Object jug=nearest(s,here,actors(f(e,"jugs")));if(jug!=null)return npcOption(p,jug,"Hit","RANGED",prayer,"Break genuine jug to clear roar-lane poison");
            if(special>=6)return stop("RANGED",prayer,"Await free roar lane/jug");
        }
        if(special>=0&&!b(e,"specialJugs")){
            int nearestY=0,nearestDistance=Integer.MAX_VALUE;Set<Integer> occupied=new HashSet<Integer>();
            for(Object wave:list(f(e,"waves"))){Object npc=f(wave,"actor");if(!present(npc))continue;int[] at=at(s,npc);int direction=n(wave,"direction");int d=(here[1]-at[1])*direction;if(d>=-1&&d<nearestDistance){nearestDistance=d;nearestY=at[1];}}
            if(nearestDistance<Integer.MAX_VALUE){for(Object wave:list(f(e,"waves"))){Object npc=f(wave,"actor");if(present(npc)){int[] at=at(s,npc);if(at[1]==nearestY)occupied.add(at[0]);}}
                int best=Integer.MAX_VALUE;int[] gap=null;for(int x=3926;x<=3942;x++)if(!occupied.contains(x))for(int y=5402;y<=5415;y++)if(!danger.contains(key(x,y))&&free(s,x,y)){int d=distance(here[0],here[1],x,y)*10+Math.abs(y-here[1]);if(d<best){best=d;gap=new int[]{x,y};}}
                if(gap!=null)return go(p,s,gap[0],gap[1],"RANGED",prayer,"Move through genuine approaching wave's current gap");
            }
            Map<String,Object> escape=dodge(p,s,boss,danger,"RANGED",prayer,"Leave Zebak poison during waves");if(escape!=null)return escape;
            return stop("RANGED",prayer,"Hold source floor while waiting for visible wave gap");
        }
        Map<String,Object> escape=dodge(p,s,boss,danger,"RANGED",prayer,"Avoid active Zebak poison");if(escape!=null)return escape;
        return attack(p,boss,"RANGED",prayer,"Normal Zebak ranged attack with source-telegraphed protection");
    }
    static boolean kephriArena(int x,int y){return x>=3543&&x<=3559&&y>=5400&&y<=5416;}
    static boolean kephriPhysicalArena(int x,int y){return x>=3542&&x<=3560&&y>=5398&&y<=5417;}
    static int kephriHelperRank(int source,boolean shield){
        if(source==11726)return 0;
        if(source==11724)return 1;
        if(source==11725)return 2;
        if(shield&&source==11723)return 3;
        if(shield&&source==11727)return 4;
        return Integer.MAX_VALUE;
    }
    interface KephriStep {boolean open(int fromX,int fromY,int x,int y)throws Exception;}
    static int kephriBossDistance(int x,int y,int bx,int by,int size){
        int dx=x<bx?bx-x:Math.max(0,x-(bx+size-1));
        int dy=y<by?by-y:Math.max(0,y-(by+size-1));return Math.max(dx,dy);
    }
    static int[] kephriDodgeChoice(int x,int y,int bx,int by,int size,Set<Long> danger,KephriStep step)throws Exception{
        int[] best=null;int score=Integer.MAX_VALUE;
        for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++){
            if(dx==0&&dy==0)continue;int nx=x+dx,ny=y+dy;
            if(!kephriArena(nx,ny)||danger.contains(key(nx,ny))||(nx>=bx&&nx<bx+size&&ny>=by&&ny<by+size)||!step.open(x,y,nx,ny))continue;
            int distance=kephriBossDistance(nx,ny,bx,by,size),cx=2*nx-(2*bx+size-1),cy=2*ny-(2*by+size-1);
            int edge=Math.min(Math.min(nx-3543,3559-nx),Math.min(ny-5400,5416-ny));
            int candidate=(distance>10?100000:0)+distance*1000+cx*cx+cy*cy+Math.max(0,2-edge)*100;
            if(candidate<score){score=candidate;best=new int[]{nx,ny};}
        }
        return best;
    }
    static int[] kephriEscapeChoice(int x,int y,int bx,int by,int size,Set<Long> danger,Map<Long,Integer> eggDeadlines,KephriStep step)throws Exception{
        int[] direct=kephriDodgeChoice(x,y,bx,by,size,danger,step);if(direct!=null)return direct;
        // A radius-one future egg can surround the player. Emit only the first
        // adjacent native step of a complete escape with one tick to spare.
        for(int budget=2;budget<=3;budget++){
            ArrayDeque<int[]> queue=new ArrayDeque<int[]>();Set<Long> seen=new HashSet<Long>();
            queue.add(new int[]{x,y,x,y,0});seen.add(key(x,y));int[] best=null;int bestDepth=Integer.MAX_VALUE,bestScore=Integer.MAX_VALUE;
            while(!queue.isEmpty()){
                int[] current=queue.remove();if(current[4]>=budget||current[4]>=bestDepth)continue;
                for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++){
                    if(dx==0&&dy==0)continue;int nx=current[0]+dx,ny=current[1]+dy,depth=current[4]+1;long cell=key(nx,ny);
                    if(!kephriArena(nx,ny)||seen.contains(cell)||(nx>=bx&&nx<bx+size&&ny>=by&&ny<by+size)||!step.open(current[0],current[1],nx,ny))continue;
                    if(danger.contains(cell)){Integer deadline=eggDeadlines.get(cell);if(deadline==null||deadline<budget+1)continue;}
                    seen.add(cell);int fx=current[4]==0?nx:current[2],fy=current[4]==0?ny:current[3];
                    if(!danger.contains(cell)){
                        int score=kephriBossDistance(nx,ny,bx,by,size)*100+Math.abs(2*nx-(2*bx+size-1))+Math.abs(2*ny-(2*by+size-1));
                        if(depth<bestDepth||depth==bestDepth&&score<bestScore){bestDepth=depth;bestScore=score;best=new int[]{fx,fy};}
                    }else queue.add(new int[]{nx,ny,fx,fy,depth});
                }
            }
            if(best!=null)return best;
        }
        return null;
    }
    static int[] kephriReentryChoice(int x,int y,int bx,int by,int size,Set<Long> danger,KephriStep step)throws Exception{
        if(kephriArena(x,y)||!kephriPhysicalArena(x,y))return null;
        // Authored dung knockback can land in the physical outer band. Only
        // an already-outside player may traverse that band back to challenge.
        ArrayDeque<int[]> queue=new ArrayDeque<int[]>();Set<Long> seen=new HashSet<Long>();
        queue.add(new int[]{x,y,x,y,0});seen.add(key(x,y));int[] best=null;int bestDepth=Integer.MAX_VALUE,bestScore=Integer.MAX_VALUE;
        while(!queue.isEmpty()){
            int[] current=queue.remove();if(current[4]>=40||current[4]>=bestDepth)continue;
            for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++){
                if(dx==0&&dy==0)continue;int nx=current[0]+dx,ny=current[1]+dy,depth=current[4]+1;long cell=key(nx,ny);
                if(!kephriPhysicalArena(nx,ny)||seen.contains(cell)||danger.contains(cell)||(nx>=bx&&nx<bx+size&&ny>=by&&ny<by+size)||!step.open(current[0],current[1],nx,ny))continue;
                seen.add(cell);int fx=current[4]==0?nx:current[2],fy=current[4]==0?ny:current[3];
                if(kephriArena(nx,ny)){
                    int score=kephriBossDistance(nx,ny,bx,by,size)*100+Math.abs(2*nx-(2*bx+size-1))+Math.abs(2*ny-(2*by+size-1));
                    if(depth<bestDepth||depth==bestDepth&&score<bestScore){bestDepth=depth;bestScore=score;best=new int[]{fx,fy};}
                }else queue.add(new int[]{nx,ny,fx,fy,depth});
            }
        }
        return best;
    }
    static boolean kephriNativeStep(Object s,int x,int y,int nx,int ny)throws Exception{
        if(Math.max(Math.abs(nx-x),Math.abs(ny-y))!=1||!free(s,nx,ny))return false;
        Object from=invoke(s,"tile",x,y),to=invoke(s,"tile",nx,ny);
        Class<?> route=Class.forName("io.ruin.model.map.route.routes.DumbRoute",false,s.getClass().getClassLoader());
        return route.getMethod("getDirection",int.class,int.class,int.class,int.class,int.class,int.class).invoke(null,
                call(from,"getX"),call(from,"getY"),call(from,"getZ"),1,call(to,"getX"),call(to,"getY"))!=null;
    }
    private static Map<String,Object> kephri(Object p,final Object s,Object e,Object boss)throws Exception{
        String prayer="PROTECT_FROM_MAGIC";Set<Long> danger=new HashSet<Long>();pending(e,s,danger,0);Set<Long> hard=new HashSet<Long>(danger);Map<Long,Integer> eggDeadlines=new HashMap<Long,Integer>();int cycle=n(e,"cycle");
        for(Object egg:list(f(e,"eggs")))if(n(egg,"source")==11728&&n(egg,"explode")<=cycle+4&&present(f(egg,"npc"))){
            int[] at=at(s,f(egg,"npc"));int remaining=n(egg,"explode")-cycle;
            for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++){long cell=key(at[0]+dx,at[1]+dy);danger.add(cell);Integer old=eggDeadlines.get(cell);if(old==null||remaining<old)eggDeadlines.put(cell,remaining);}
        }
        for(Object dung:list(f(e,"dung"))){int[] at=pos(s,dung);long cell=key(at[0],at[1]);danger.add(cell);hard.add(cell);}
        for(Long cell:hard)eggDeadlines.remove(cell);
        final int[] here=at(s,p);
        if(danger.contains(key(here[0],here[1]))||!kephriArena(here[0],here[1])){
            if(!present(boss))return stop("RANGED",prayer,"Await live Kephri before selecting bounded dodge");
            int[] at=at(s,boss);KephriStep step=new KephriStep(){public boolean open(int fromX,int fromY,int x,int y)throws Exception{return kephriNativeStep(s,fromX,fromY,x,y);}};
            int[] safe=!kephriArena(here[0],here[1])?kephriReentryChoice(here[0],here[1],at[0],at[1],size(boss),danger,step):
                    kephriEscapeChoice(here[0],here[1],at[0],at[1],size(boss),danger,eggDeadlines,step);
            return safe==null?stop("RANGED",prayer,"No adjacent native corner-valid Kephri safe tile inside donor arena"):
                    move(safe[0],safe[1],"RANGED",prayer,"One adjacent native step on fully checked timed Kephri escape, retaining ranged gear");
        }
        boolean shield=b(e,"invulnerable")||n(f(e,"rules"),"shieldTicks")>0;
        Object target=null;int priority=Integer.MAX_VALUE;
        for(Object helper:list(f(e,"helpers"))){
            Object npc=f(helper,"npc");if(!alive(npc)||n(helper,"flight")>0)continue;
            int rank=kephriHelperRank(n(helper,"source"),shield);if(rank==Integer.MAX_VALUE)continue;
            int[] at=at(s,npc);int candidate=rank*100+distance(here[0],here[1],at[0],at[1]);
            if(candidate<priority){priority=candidate;target=npc;}
        }
        if(target!=null)return attack(p,target,"RANGED",prayer,"Ranged actual priority Kephri helper; recurring Agile scarabs cannot preempt vulnerable boss");
        if(shield)return stop("RANGED",prayer,"Kephri genuine timed shield intermission");
        return attack(p,boss,"RANGED",prayer,"Ranged vulnerable Kephri with stable gear; prioritize boss over recurring Agile scarabs");
    }
    private static int quadrant(int x,int y){return x>=3681?(y>=5408?0:1):(y>=5408?3:2);}
    private static Map<String,Object> akkha(Object p,Object s,Object e,Object boss,Memory m)throws Exception{
        int style=n(e,"style");boolean last=b(e,"finalPhase");String gear=last?"MELEE":style==0?"MAGIC":style==1?"MELEE":"RANGED";
        String prayer=style==0?"PROTECT_FROM_MELEE":style==1?"PROTECT_FROM_MISSILES":"PROTECT_FROM_MAGIC";
        int memory=n(e,"memoryTick");if(memory>=0){int[] sequence=(int[])f(e,"memory");int index=Math.min(3,Math.max(0,(memory-12+3)/4));int[] safe=MEMORY_TILES[sequence[index]];return go(p,s,safe[0],safe[1],gear,prayer,"Repeat actual four-quadrant memory telegraph "+index);}
        Set<Long> danger=new HashSet<Long>();for(Object trail:list(f(e,"trails"))){Object npc=f(trail,"npc");if(present(npc)){int[] at=at(s,npc);mark(danger,at[0],at[1],0);}}
        for(Object orb:list(f(e,"orbs"))){Object npc=f(orb,"npc");if(present(npc)){int[] at=at(s,npc);for(int t=0;t<=2;t++)mark(danger,at[0]+n(orb,"dx")*t,at[1]+n(orb,"dy")*t,0);}}
        Map<String,Object> escape=dodge(p,s,boss,danger,gear,prayer,"Avoid live Akkha trail/unstable orb path");if(escape!=null)return escape;
        if(!last){Object[] shadows=(Object[])f(e,"shadows");int[] vulnerable=(int[])f(e,"vulnerable"),at=at(s,boss);int q=quadrant(at[0]+size(boss)/2,at[1]+size(boss)/2);
            if(alive(shadows[q])){Object target=null;int best=Integer.MAX_VALUE;int[] here=at(s,p);
                for(int i=0;i<shadows.length;i++)if(alive(shadows[i])&&vulnerable[i]==0){int d=distance(here[0],here[1],SHADOWS[i][0],SHADOWS[i][1]);if(d<best){best=d;target=shadows[i];m.preferredQuadrant=i;}}
                if(target!=null)return attack(p,target,"RANGED",prayer,"Destroy actually vulnerable Akkha shadow (vulnerable=0)");
                int cleared=-1;for(int i=0;i<shadows.length;i++)if(!alive(shadows[i])){cleared=i;if(i==m.preferredQuadrant)break;}
                if(cleared>=0)return go(p,s,SHADOWS[cleared][0],SHADOWS[cleared][1],gear,prayer,"Lure Akkha into genuinely shadow-cleared quadrant");
                return stop(gear,prayer,"All actual shadows currently immune; await authored transition");
            }
        }
        return attack(p,boss,gear,prayer,last?"Melee actual final Akkha, ignoring fake shadows":"Use actual Akkha weakness and prayer");
    }
    private static Map<String,Object> baba(Object p,Object s,Object e,Object boss)throws Exception{
        String prayer="PROTECT_FROM_MELEE";Set<Long> danger=new HashSet<Long>();pending(e,s,danger,2);int[] here=at(s,p);
        for(Object rock:list(f(e,"rolling"))){Object npc=f(rock,"npc");if(alive(npc)){int[] at=at(s,npc);for(int dx=-2;dx<=1;dx++)for(int dy=0;dy<=2;dy++)mark(danger,at[0]+dx,at[1]+dy,0);}}
        Map<String,Object> escape=dodge(p,s,boss,danger,"MELEE",prayer,"Leave Ba-ba shadow/landing or rolling boulder footprint");if(escape!=null)return escape;
        int boulder=n(e,"boulderTick");if(boulder>=0){
            if(boulder<5)return stop("RANGED",prayer,"Wait for genuine boulder knockback/start");
            Object cracked=null;int mapped=mapped(p,11783),best=Integer.MAX_VALUE;
            for(Object rock:list(f(e,"rolling"))){Object npc=f(rock,"npc");if(alive(npc)&&((Number)call(npc,"getId")).intValue()==mapped){int[] at=at(s,npc);int d=distance(here[0],here[1],at[0],at[1]);if(d<best){best=d;cracked=npc;}}}
            if(cracked!=null)return attack(p,cracked,"RANGED",prayer,"Break the real cracked one-HP boulder with ranged");
            int safeY=5402+Math.max(0,Math.min(4,n(e,"lastCracked")))*3;
            if(here[0]<3815){int tx=Math.min(3816,here[0]+2),ty=here[1]+Integer.signum(safeY-here[1])*Math.min(2,Math.abs(safeY-here[1]));if(!danger.contains(key(tx,ty))&&free(s,tx,ty))return move(tx,ty,"MELEE",prayer,"Advance normally through the cracked boulder lane");return stop("RANGED",prayer,"Await actual breakable boulder gap");}
            return attack(p,boss,"MELEE",prayer,"Melee Ba-ba beyond boulder start to trigger authored early cancellation");
        }
        if(b(e,"throwingRock")){int[] best=null;int score=Integer.MAX_VALUE;
            for(Object rubble:list(f(e,"rubble"))){Object npc=f(rubble,"npc");if(!present(npc))continue;int[] at=at(s,npc);int size=size(npc);
                for(int x=at[0]-1;x<=at[0]+size;x++)for(int y=at[1]-1;y<=at[1]+size;y++)if(!overlaps(s,npc,x,y)&&free(s,x,y)&&!danger.contains(key(x,y))){int d=distance(here[0],here[1],x,y);if(d<score){score=d;best=new int[]{x,y};}}}
            if(best!=null)return go(p,s,best[0],best[1],"MELEE",prayer,"Stand beside actual rubble to absorb Ba-ba thrown boulder");
        }
        Object baboon=nearest(s,here,actors(f(e,"baboons")));if(baboon!=null)return attack(p,baboon,"RANGED",prayer,"Remove Ba-ba's genuine ranged baboon helper");
        return attack(p,boss,"MELEE",prayer,"Normal Ba-ba melee with protection");
    }
    private static Map<String,Object> wardens(Object p,Object s,Object e,Object boss)throws Exception{
        String phase=String.valueOf(f(e,"phase")),prayer=divinePrayer(e,queuedPrayer(p,"PROTECT_FROM_MISSILES"));
        String gear=phase.equals("SHIELD")?(n(e,"shieldForm")==11753||n(e,"shieldForm")==11756?"MAGIC":"RANGED"):
                phase.equals("SKULLS")?"FAST_MELEE":phase.equals("CORE")||phase.equals("OBELISK")?"MELEE":"RANGED";
        if(phase.equals("SKULLS")){Object skull=nearest(s,at(s,p),actors(f(e,"skulls")));return skull==null?stop(gear,prayer,"Await actual siphon spawn/return transition"):attack(p,skull,gear,prayer,"Melee real one-HP energy siphon before source deadline");}
        Set<Long> danger=new HashSet<Long>();pending(e,s,danger,0);
        if(phase.equals("ENRAGED")){for(Object at:list(f(e,"occupiedThunder"))){int[] p0=pos(s,at);mark(danger,p0[0],p0[1],0);}int[] here=at(s,p);int top=5165-n(e,"floorIndex")/4;if(here[1]>top)return go(p,s,here[0],5157,gear,prayer,"Step onto actual surviving front row before floor erosion");}
        Map<String,Object> escape=dodge(p,s,boss,danger,gear,prayer,"Avoid actual queued Warden floor/bomb/lightning telegraph");if(escape!=null)return escape;
        if(phase.equals("CORE")){Object core=f(e,"core");return core==null?stop(gear,prayer,"Await actual core exposure"):attack(p,core,gear,prayer,"Normal melee on actual exposed core; controller applies its own fivefold mechanic");}
        if(phase.equals("TRANSITION")||phase.equals("COMPLETE")||b(e,"invulnerable")||n(e,"cycle")<n(e,"stoneUntil"))return stop(gear,prayer,"Wait through authored Warden transition/invulnerability/stone");
        return attack(p,boss,gear,prayer,"Normal Warden "+phase+" target/style");
    }
    private static Object nearest(Object s,int[] here,List<Object> values)throws Exception{Object best=null;int distance=Integer.MAX_VALUE;for(Object npc:values){int[] at=at(s,npc);int d=distance(here[0],here[1],at[0],at[1]);if(d<distance){distance=d;best=npc;}}return best;}
    private static int mapped(Object p,int source)throws Exception{return ((Number)Class.forName("io.ruin.model.activities.raids.NearRealityRaidAssets",false,p.getClass().getClassLoader()).getMethod("npc",int.class).invoke(null,source)).intValue();}
    private static Map<String,Object> npcOption(Object p,Object npc,String name,String gear,String prayer,String reason)throws Exception{
        Object def=call(npc,"getDef");int option=((Number)def.getClass().getMethod("getOption",String[].class).invoke(def,(Object)new String[]{name})).intValue();
        if(option<1)return attack(p,npc,gear,prayer,reason);Map<String,Object> out=action("npc",gear,prayer,reason);out.put("npc",npc);out.put("option",option);return out;
    }
}
