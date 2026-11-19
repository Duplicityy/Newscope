package io.ruin.model.activities.raids.toa.port;

import io.ruin.api.utils.Random;
import io.ruin.cache.ObjectDef;
import io.ruin.cache.ItemDef;
import io.ruin.data.impl.npcs.npc_combat;
import io.ruin.model.activities.raids.NearRealityRaidAssets;
import io.ruin.model.combat.*;
import io.ruin.model.entity.Entity;
import io.ruin.model.entity.npc.*;
import io.ruin.model.entity.player.Player;
import io.ruin.model.entity.shared.listeners.HitListener;
import io.ruin.model.entity.shared.StepType;
import io.ruin.model.item.Item;
import io.ruin.model.item.actions.ItemAction;
import io.ruin.model.map.*;
import io.ruin.model.map.object.GameObject;
import io.ruin.model.map.object.actions.ObjectAction;
import io.ruin.model.skills.prayer.Prayer;
import io.ruin.model.skills.mining.Pickaxe;
import io.ruin.model.stat.StatType;
import java.util.*;
import java.util.function.*;

/** Native interaction adapters for NR's four authored path puzzles, scoped to each lease. */
public final class RootToaPuzzles {
    private static final Map<Player,Puzzle> OWNED=new IdentityHashMap<>();
    private static final Set<Integer> BOUND_ITEMS=new HashSet<>();
    private RootToaPuzzles() {}
    /** Called after each real native step, including intermediate running tiles. */
    public static void onStep(Entity entity){if(entity.player==null)return;Puzzle puzzle=OWNED.get(entity.player);if(puzzle==null||!puzzle.live())return;int x=puzzle.px(),y=puzzle.py();puzzle.lastX=x;puzzle.lastY=y;puzzle.moved(x,y);}
    public static void install() {
        register(ToaRoom.CRONDIS, Crondis::new, new int[]{11700,11701,11702,11703,11704,11705},new int[]{45398,45399,45403,45414,45415,45416},new int[]{2129},new int[]{827,9562,9563,9565});
        register(ToaRoom.SCABARAS,Scabaras::new,new int[]{11697,11698,11699},scabarasObjects(),new int[]{302,317,1766},new int[]{741,827,832,2796,9490,9587,9589});
        register(ToaRoom.HET,Het::new,new int[]{11706,11707,11708},hetObjects(),new int[]{2064,2114,2116,2117,2118,2119,2120,2122,2123,2124},new int[]{827,810});
        register(ToaRoom.APMEKEN,Apmeken::new,new int[]{11709,11710,11711,11712,11713,11714,11715,11716,11717,11718},new int[]{45493,45494,45495,45497,45498},new int[]{2132,2134,2135,2138,2139,2242,2247,2249,131},new int[]{3676,2295,9742,9745,9746,9747,9756});
        bindSupply(27296,"Mirror");bindSupply(27297,"Neutralising potion");bindContainer();
    }
    private static int supply(int source){return NearRealityRaidAssets.item(source,source==27295?"Water container":source==27296?"Mirror":source==2347?"Hammer":"Neutralising potion");}
    private static void bindSupply(int source,String expected){int id=NearRealityRaidAssets.item(source,expected);if(id<0||!BOUND_ITEMS.add(id))return;ItemDef def=ItemDef.get(id);int option=def.getOption(source==27296?"Place":"Pour");if(option<1||option>5)throw new IllegalStateException("Puzzle supply missing its native action: "+expected);final ItemAction old=def.inventoryActions==null?null:def.inventoryActions[option-1];ItemAction.registerInventory(id,option,(p,item)->{Puzzle puzzle=OWNED.get(p);if(puzzle!=null&&puzzle.owns(p)&&puzzle.loan.equals(item.getAttributeString("toa.puzzle.loan",null))){if(puzzle instanceof Het)((Het)puzzle).place(item);else if(puzzle instanceof Apmeken)((Apmeken)puzzle).pour(item);}else if(old!=null)old.handle(p,item);});}
    private static void bindContainer(){int id=supply(27295);if(id<0||!BOUND_ITEMS.add(id))return;ItemDef def=ItemDef.get(id);for(String name:new String[]{"Check","Empty","Destroy"}){int option=def.getOption(name);if(option<1)continue;ItemAction old=def.inventoryActions==null?null:def.inventoryActions[option-1];ItemAction.registerInventory(id,option,(p,item)->{Puzzle puzzle=OWNED.get(p);if(puzzle instanceof Crondis&&puzzle.owns(p)&&puzzle.loan.equals(item.getAttributeString("toa.puzzle.loan",null))){if(name.equals("Check"))p.sendMessage("The container is "+item.getAttributeInt("toa.water",0)+"% full.");else if(name.equals("Empty")){item.putAttribute("toa.water",0);p.sendMessage("You empty the container.");}else item.remove();}else if(old!=null)old.handle(p,item);});}}
    private static int[] scabarasObjects(){Set<Integer> ids=new TreeSet<>();for(int id:new int[]{45135,45335,45338,45339,45340,45341,45342,45343,45344,45360,45384,45396})ids.add(id);for(int id:RootToaPuzzleData.SUM_PRESSURE_LIGHT_IDS)ids.add(id);for(int id=45345;id<=45353;id++)ids.add(id);return ids.stream().mapToInt(i->i).toArray();}
    private static int[] hetObjects(){Set<Integer> ids=new TreeSet<>();for(int[][] layout:RootToaPuzzleData.HET_ROOMS)for(int[] object:layout){ids.add(object[0]);if(object[0]!=45455&&object[0]!=45456&&object[0]!=45457)ids.add(object[0]-1);}for(int id:new int[]{45457,45466,45485,45486})ids.add(id);return ids.stream().mapToInt(i->i).toArray();}
    private static void register(ToaRoom room,Function<TombsOfAmascutRaid.Session,ToaEncounter> create,int[] npcs,int[] objects,int[] graphics,int[] animations){
        TombsOfAmascutRaid.register(room,new TombsOfAmascutRaid.Factory(){
            public List<String> missingAssets(){List<String> missing=NativeToaEncounter.assets(room,npcs,animations,graphics,objects);if(room==ToaRoom.CRONDIS){if(supply(27295)<0)missing.add("Water container 27295");if(NearRealityRaidAssets.combatInfo(11705)==null)missing.add("Crocodile combat stats");}if(room==ToaRoom.SCABARAS&&NearRealityRaidAssets.combatInfo(11697)==null)missing.add("Scarab combat stats");if(room==ToaRoom.HET){int mirror=supply(27296);if(mirror<0||ItemDef.get(mirror).getOption("Place")<1)missing.add("Mirror Place action");npc_combat.Info seal=NearRealityRaidAssets.combatInfo(11706);if(seal==null||seal.hitpoints<=0)missing.add("Het seal hitpoints");}if(room==ToaRoom.APMEKEN){int potion=supply(27297);if(potion<0||ItemDef.get(potion).getOption("Pour")<1)missing.add("Neutralising potion Pour action");if(supply(2347)<0)missing.add("Hammer 2347");for(int id=11709;id<=11718;id++)if(NearRealityRaidAssets.combatInfo(id)==null)missing.add("Baboon combat stats "+id);}return missing;}
            public ToaEncounter create(TombsOfAmascutRaid.Session s){return create.apply(s);}
        });
    }
    /** Only loaned puzzle supplies are removed; existing equal-ID items retain their attributes. */
    public static void recoverLoans(Player p){for(Item item:p.getInventory().getItems())if(item!=null&&item.copyOfAttributes().containsKey("toa.puzzle.loan"))item.remove();}
    /** NR prevents a new action delay only for a real owned obelisk launch this tick. */
    public static int scabarasAttackDelay(Player p,int normalDelay){
        Puzzle puzzle=OWNED.get(p);
        return puzzle instanceof Scabaras&&puzzle.owns(p)&&((Scabaras)puzzle).lastPillarLaunchTick==io.ruin.Server.currentTick()?0:normalDelay;
    }
    /** Stop after the native weapon caller has completed its post-hit target reads. */
    public static void scabarasAttackFinished(Player p){
        Puzzle puzzle=OWNED.get(p);
        if(puzzle instanceof Scabaras&&puzzle.owns(p)&&((Scabaras)puzzle).lastPillarLaunchTick==io.ruin.Server.currentTick()){
            Scabaras scabaras=(Scabaras)puzzle;
            scabaras.lastPillarLaunchTick=Long.MIN_VALUE;scabaras.lastPillarHit=null;scabaras.lastPillarTarget=null;
            if(p.getCombat().getTarget()!=null)p.getCombat().reset();
        }
    }
    /** Retain only this accepted zero-damage obelisk projectile through a native miss policy. */
    public static boolean retainScabarasPillarImpact(Player p,Entity target,Hit hit){
        Puzzle puzzle=OWNED.get(p);
        if(!(puzzle instanceof Scabaras)||!puzzle.owns(p)||hit==null||hit.isNullified())return false;
        Scabaras scabaras=(Scabaras)puzzle;
        return scabaras.lastPillarLaunchTick==io.ruin.Server.currentTick()&&scabaras.lastPillarTarget==target&&scabaras.lastPillarHit==hit;
    }
    /** NR obelisks skip projectile clipping; native combat still checks range and overlap. */
    public static boolean ignoreScabarasPillarProjectileClip(Entity from,Entity target){
        if(from==null||from.player==null||target==null||target.npc==null||target.npc.isRemoved()
                ||from.getHeight()!=target.getHeight())return false;
        Puzzle puzzle=OWNED.get(from.player);
        if(!(puzzle instanceof Scabaras)||!puzzle.owns(from.player))return false;
        Scabaras scabaras=(Scabaras)puzzle;
        if(!scabaras.usableQuiet(2)||target.npc.getId()!=NearRealityRaidAssets.npc(11698)
                ||target.getPosition().getRegion().dynamicIndex!=scabaras.s.lease)return false;
        for(int i=0;i<5;i++)if(scabaras.pillars[i]==target)return true;
        return false;
    }

    private abstract static class Puzzle implements ToaEncounter {
        final TombsOfAmascutRaid.Session s;
        final List<NPC> actors=new ArrayList<>();
        final List<GameObject> objects=new ArrayList<>();
        final List<Due> tasks=new ArrayList<>();
        final String loan=UUID.randomUUID().toString();
        int cycle,lastX=Integer.MIN_VALUE,lastY;boolean closed,solved;
        Puzzle(TombsOfAmascutRaid.Session s){this.s=s;OWNED.put(s.player,this);}
        private static final class Due {final int tick;final Runnable run;Due(int t,Runnable r){tick=t;run=r;}}
        boolean live(){return !closed&&!solved&&s.valid();}
        boolean owns(Player p){return p==s.player&&live();}
        Position pos(int x,int y){return s.tile(x,y);}
        int px(){return s.sourceX(s.player.getPosition());} int py(){return s.sourceY(s.player.getPosition());}
        int sx(Position p){return s.sourceX(p);} int sy(Position p){return s.sourceY(p);}
        void message(String text){s.player.sendMessage(text);}
        int anim(int id){return NearRealityRaidAssets.animation(id);}
        int gfx(int id){return NearRealityRaidAssets.graphic(id);}
        void graphic(int id,int x,int y){int g=gfx(id);if(g>=0){Position p=pos(x,y);s.player.getPacketSender().sendGraphics(g,0,0,p.getX(),p.getY(),p.getZ());}}
        void damage(int amount){if(live()){Hit h=new Hit().fixedDamage(amount).ignoreDefence().ignorePrayer();h.preDamage(e->{if(!owns(e.player))h.block().nullify();});s.player.hit(h);}}
        void later(int ticks,Runnable action){tasks.add(new Due(cycle+Math.max(1,ticks),action));}
        @Override public final void tick(){if(!live())return;cycle++;List<Due> ready=new ArrayList<>();for(Due d:tasks)if(d.tick<=cycle)ready.add(d);tasks.removeAll(ready);for(Due d:ready){if(!live())return;d.run.run();}if(!live())return;int x=px(),y=py();if(x!=lastX||y!=lastY){lastX=x;lastY=y;moved(x,y);}pulse();}
        void moved(int x,int y){} void pulse(){}
        void complete(){if(solved||closed)return;solved=true;tasks.clear();s.completed();}
        GameObject object(int source,int x,int y,int type,int direction){int id=NearRealityRaidAssets.object(source);if(id<0)throw new IllegalStateException("Missing puzzle object "+source);Position p=pos(x,y);Tile t=Tile.get(p,true);if(t.gameObjects!=null)for(GameObject o:new ArrayList<>(t.gameObjects))if(o.type==type&&o.id!=-1)o.remove();GameObject o=GameObject.spawn(id,p,type,direction);objects.add(o);return o;}
        void action(GameObject o,int option,Consumer<GameObject> callback){ObjectAction.register(o,option,(p,obj)->{if(owns(p))callback.accept(obj);});}
        void action(GameObject o,String name,Consumer<GameObject> callback){ObjectAction.register(o,name,(p,obj)->{if(owns(p))callback.accept(obj);});}
        int bindExisting(int source,Consumer<GameObject> callback){int count=0,id=NearRealityRaidAssets.object(source);for(Region r:s.map.getRegions())if(r!=null)for(int x=r.baseX;x<r.baseX+64;x++)for(int y=r.baseY;y<r.baseY+64;y++){Tile t=Tile.get(x,y,s.room.plane,false);if(t!=null&&t.gameObjects!=null)for(GameObject o:new ArrayList<>(t.gameObjects))if(o.id==id){action(o,1,callback);count++;}}return count;}
        void remove(GameObject o){if(o!=null&&o.tile!=null&&o.id!=-1)o.remove();objects.remove(o);}
        void remove(NPC n){if(n!=null&&!n.isRemoved())n.remove();actors.remove(n);if(s.map!=null)s.map.getNpcs().remove(n);}
        void removeSourceObjects(IntPredicate source){Set<Integer> ids=new HashSet<>();for(int id=45000;id<46000;id++)if(source.test(id)){int n=NearRealityRaidAssets.object(id);if(n>=0)ids.add(n);}for(Region r:s.map.getRegions())if(r!=null)for(int x=r.baseX;x<r.baseX+64;x++)for(int y=r.baseY;y<r.baseY+64;y++){Tile t=Tile.get(x,y,s.room.plane,false);if(t!=null&&t.gameObjects!=null)for(GameObject o:new ArrayList<>(t.gameObjects))if(ids.contains(o.id))o.remove();}}
        NPC actor(int source,int x,int y){int id=NearRealityRaidAssets.npc(source);if(id<0)throw new IllegalStateException("Missing puzzle NPC "+source);NPC n=new NPC(id).spawn(pos(x,y));n.sixSystemsRewardless=true;if(n.getCombat()!=null){n.getCombat().setAllowRespawn(false);n.getCombat().setAllowRetaliate(false);n.getCombat().reset();}actors.add(n);s.map.addNpc(n);return n;}
        void npcAction(NPC n,int option,Runnable action){NPCAction.register(n,option,(p,npc)->{if(owns(p))action.run();});}
        NPC fighter(int source,int x,int y,boolean follows,Runnable death){NPC n=actor(source,x,y);npc_combat.Info info=NearRealityRaidAssets.combatInfo(source);if(info==null)throw new IllegalStateException("Missing puzzle combat "+source);n.installCombat(new Fighter(follows,death),info);n.attackNpcListener=(p,npc,m)->owns(p);n.hitListener=new HitListener().preDefend(h->{if(h.attacker==null||!owns(h.attacker.player))h.block().nullify();}).postDamage(h->s.recordDamage(h,source==11697?0.5:1));return n;}
        Item findLoan(int source){int id=supply(source);for(Item item:s.player.getInventory().getItems())if(item!=null&&item.getId()==id&&loan.equals(item.getAttributeString("toa.puzzle.loan",null)))return item;return null;}
        Item loan(int source){
            Item old=source==27296?null:findLoan(source);if(old!=null)return old;
            int id=supply(source);if(id<0)throw new IllegalStateException("Missing puzzle supply "+source);
            int slot=s.player.getInventory().freeSlot();
            if(slot<0){message("You need one free inventory slot for the puzzle supply.");return null;}
            Item item=new Item(id,1);item.putAttribute("toa.puzzle.loan",loan);
            if(s.player.getInventory().add(item)!=1)return null;
            // ItemContainer.add copies the supplied item. Later water/quantity
            // mutations must target that resident copy, including each new mirror.
            Item resident=s.player.getInventory().get(slot);
            if(resident==null||resident.getId()!=id||resident.getContainer()!=s.player.getInventory()||!loan.equals(resident.getAttributeString("toa.puzzle.loan",null)))
                throw new IllegalStateException("Puzzle supply was not installed in its owned inventory slot");
            return resident;
        }
        @Override public void close(){if(closed)return;closed=true;OWNED.remove(s.player,this);tasks.clear();for(NPC n:new ArrayList<>(actors))remove(n);for(GameObject o:new ArrayList<>(objects))remove(o);for(Item item:s.player.getInventory().getItems())if(item!=null&&loan.equals(item.getAttributeString("toa.puzzle.loan",null)))item.remove();}
        private final class Fighter extends NPCCombat {
            final boolean follows;final Runnable death;Fighter(boolean f,Runnable d){follows=f;death=d;}
            public void init(){setAllowRespawn(false);setAllowRetaliate(false);}
            public void follow(){if(follows&&live())follow(info.attack_style!=null&&info.attack_style.isMelee()?1:8);}
            public boolean attack(){return false;}
            public boolean canAttack(Entity e){return e!=null&&owns(e.player);}
            public int getAttackBoundsRange(){return 64;}
            public void startDeath(Hit h){if(isDead()||closed)return;setDead(true);if(death!=null)death.run();remove(npc);}
        }
    }

    private static final class Crondis extends Puzzle {
        static final int[][] SPEAR={{3925,5293},{3939,5293},{3925,5258},{3939,5258}};
        static final int[][] DELAYS={{0,2,4,6,8,0,2,4,6,8},{0,3,6,9,2,0,3,6,9,2},{2,1,1,0,0,2,2,1,1,0},{0,1,2,3,4,4,3,2,1,0}};
        static final int[][] WATER={{3926,5250},{3940,5250},{3926,5306},{3940,5306}};
        static final int[][] CROC={{3925,5285},{3946,5285},{3925,5274},{3946,5274}};
        final List<NPC> crocs=new ArrayList<>();final List<Acid> acid=new ArrayList<>();
        final GameObject[][][] mouths=new GameObject[4][2][5],spears=new GameObject[4][2][5];
        final Map<GameObject,Integer> waterfallCooldown=new IdentityHashMap<>();NPC palm;int water,fillTick=-100,hitTick=-100;
        Crondis(TombsOfAmascutRaid.Session s){super(s);}
        public void spawn(){
            removeSourceObjects(id->id==45135);palm=actor(11700,3934,5278);palm.attackNpcListener=(p,n,m)->false;
            npcAction(palm,1,this::waterPalm);
            for(int[] point:WATER){GameObject fall=object(45398,point[0],point[1],10,point[1]>5280?2:0);action(fall,1,this::fill);}
            for(int[] statue:new int[][]{{3943,5255},{3929,5255},{3929,5304},{3943,5304}})object(45414,statue[0],statue[1],10,statue[1]>5280?2:0);
            for(int i=0;i<4;i++)for(int east=0;east<2;east++)for(int row=0;row<5;row++){int y=SPEAR[i][1]+row*2+east;mouths[i][east][row]=object(45415,SPEAR[i][0]+(east==1?7:0),y,10,east==1?0:2);spears[i][east][row]=object(45416,SPEAR[i][0]+(east==1?4:2),y,10,east==1?0:2);}
            message("Crondis: take water from a waterfall and water the palm. Acid and spears spill half your water.");
        }
        void fill(GameObject fall){if(waterfallCooldown.getOrDefault(fall,0)>cycle){message("This waterfall is empty for the moment.");return;}Item jar=loan(27295);if(jar==null)return;if(jar.getAttributeInt("toa.water",0)==100){message("Your container is already full.");return;}if(cycle-fillTick<4)return;fillTick=cycle;jar.putAttribute("toa.water",100);waterfallCooldown.put(fall,cycle+128);fall.setId(NearRealityRaidAssets.object(45399));s.player.animate(anim(827));message("You fill the container. Return it to the palm.");later(128,()->{if(fall.tile!=null)fall.setId(NearRealityRaidAssets.object(45398));});}
        void waterPalm(){Item jar=findLoan(27295);int charges=jar==null?0:jar.getAttributeInt("toa.water",0);if(charges<1){message("Fill your container at a waterfall first.");return;}water+=charges;jar.putAttribute("toa.water",0);s.player.animate(anim(827));int stage=Math.min(4,water/50);palm.transformVisual(NearRealityRaidAssets.npc(11700+stage));message("Water delivered: "+Math.min(water,200)+" / 200.");if(water>=200)complete();}
        void hazard(int min,int range){if(cycle-hitTick<2)return;hitTick=cycle;Item jar=findLoan(27295);if(jar!=null){int value=jar.getAttributeInt("toa.water",0);if(value>0){jar.putAttribute("toa.water",value/2);message("Water spills out of your container.");}}damage(min+Random.get(range));}
        final class Acid{final int x,y,start;final boolean north;Acid(int x,int y){this.x=x;this.y=y;start=cycle;north=y<5280;GameObject pool=object(45403,x,y,10,0);later(3,()->remove(pool));later(2,()->{for(int row=1;row<=10;row++)graphic(2129,x,y+(north?row:-row));});}boolean tick(){int age=cycle-start,delta=Math.abs(py()-y)-1,sub=1+delta/3;if(px()==x&&delta>=0&&delta<10&&(north?py()>y:py()<y)&&age>2+sub&&age<=4+sub)hazard(5,8);return age<8;}}
        void pulse(){
            for(int i=0;i<SPEAR.length;i++)for(int east=0;east<2;east++)for(int row=0;row<5;row++){
                int start=DELAYS[i][row+east*5],phase=Math.floorMod(cycle-start,10);int x=SPEAR[i][0]+(east==1?4:2),y=SPEAR[i][1]+row*2+east;
                if(phase==0)mouths[i][east][row].animate(anim(9563));if(phase==3)spears[i][east][row].animate(anim(9565));if(phase==6)spears[i][east][row].animate(anim(9562));if(phase==7)mouths[i][east][row].animate(anim(9562));if(phase>=4&&phase<=6&&px()==x&&py()==y)hazard(6,8);
            }
            if(cycle%5==0)for(int[] base:new int[][]{{3941,5257},{3927,5257},{3941,5303},{3927,5303}}){int[] columns=shuffle(5);int count=Random.get(2,3);for(int i=0;i<count;i++)acid.add(new Acid(base[0]+columns[i],base[1]));}
            acid.removeIf(a->!a.tick());
            if(cycle%50==0&&crocs.size()<8){int[] at=CROC[0];NPC n=fighter(11705,at[0],at[1],false,()->{});crocs.add(n);}
            crocs.removeIf(n->n.isRemoved()||n.getCombat().isDead());
            for(NPC n:new ArrayList<>(crocs)){int x=sx(n.getPosition()),y=sy(n.getPosition()),distance=Math.max(Math.abs(x-px()),Math.abs(y-py()));Item jar=findLoan(27295);boolean chasing=distance<=3&&jar!=null&&jar.getAttributeInt("toa.water",0)>0;int tx=chasing?px():3934,ty=chasing?py():5278;Position target=pos(tx,ty);if(Math.max(Math.abs(x-tx),Math.abs(y-ty))>1)n.getRouteFinder().routeAbsolute(target.getX(),target.getY());else if(cycle%7==0){int animation=n.getCombat().getInfo().attack_animation;if(animation>=0)n.animate(animation);if(chasing){boolean protection=s.player.getPrayer().isActive(Prayer.PROTECT_FROM_MELEE);int damage=protection?6:18;Hit h=new Hit(n,AttackStyle.CRUSH).fixedDamage(damage).ignorePrayer();s.player.hit(h);if(protection)s.player.getStats().get(StatType.Prayer).drain(12);}else if(water>0){water=Math.max(0,water-Random.get(2,5));palm.transformVisual(NearRealityRaidAssets.npc(11700+Math.min(3,water/50)));}}}
        }
    }

    /** Cardinal beam reflection used by the source's four diagonal mirror orientations. */
    static int[] reflect(int dx,int dy,int rotation){int mx=(rotation==0||rotation==1)?1:-1,my=(rotation==0||rotation==3)?1:-1;if(-dx==mx&&dy==0)return new int[]{0,my};if(-dy==my&&dx==0)return new int[]{mx,0};return null;}
    private static final class Het extends Puzzle {
        final Map<Long,Mirror> mirrors=new HashMap<>();final List<GameObject> layout=new ArrayList<>();final List<Orb> orbs=new ArrayList<>();
        NPC seal;int layoutIndex,downTicks,orbIndex;boolean mining;int mineX,mineY;
        Het(TombsOfAmascutRaid.Session s){super(s);layoutIndex=Random.get(RootToaPuzzleData.HET_ROOMS.length-1);}
        public void spawn(){removeSourceObjects(id->id==45135);seal=actor(11706,3679,5279);seal.attackNpcListener=(p,n,m)->false;npcAction(seal,1,this::mine);npc_combat.Info stats=NearRealityRaidAssets.combatInfo(11706);if(stats==null||stats.hitpoints<=0)throw new IllegalStateException("Missing authored Het seal hitpoints");seal.installCombat(new NPCCombat(){public void init(){setAllowRespawn(false);setAllowRetaliate(false);}public void follow(){}public boolean attack(){return false;}public void startDeath(Hit h){if(isDead()||closed)return;setDead(true);complete();}},stats);seal.hitListener=new HitListener().preDamage(h->{if(downTicks<=0||h.attacker==null||!owns(h.attacker.player))h.block().nullify();}).postDamage(h->s.recordDamage(h,2.5));object(45486,3676,5279,10,1);object(45485,3682,5279,10,3);buildLayout();message("Het: redirect the light with mirrors to strike the shield statue, then mine the weakened seal. Bring a pickaxe.");}
        long key(int x,int y){return ((long)x<<32)|(y&0xffffffffL);}
        void buildLayout(){for(int[] data:RootToaPuzzleData.HET_ROOMS[layoutIndex]){int id=data[0];if(id==45455||id==45456||id==45457){addMirror(id,data[3],data[4],data[2]);continue;}int active=id-1;GameObject o=object(active,data[3],data[4],data[1],data[2]);layout.add(o);if(id==45463||id==45465)action(o,1,obj->{s.player.animate(anim(827));obj.setId(NearRealityRaidAssets.object(45466));});}}
        void addMirror(int source,int x,int y,int rotation){Mirror m=new Mirror(source,x,y,rotation);mirrors.put(key(x,y),m);m.spawn();}
        final class Mirror {int source,x,y,rotation;GameObject object;Mirror(int id,int x,int y,int r){source=id;this.x=x;this.y=y;rotation=r;}
            void spawn(){object=Het.this.object(source,x,y,11,rotation);action(object,"Rotate-clockwise",o->rotate(1));action(object,"Rotate-anticlockwise",o->rotate(-1));action(object,"Push",o->push());action(object,"Pick-up",o->{if(source!=45455)return;if(loan(27296)!=null){remove(object);mirrors.remove(key(x,y));}});if(source==45457)action(object,1,o->{source=45456;remove(object);spawn();});}
            void rotate(int delta){rotation=Math.floorMod(rotation+delta,4);remove(object);spawn();}
            void push(){if(source!=45455)return;int dx=Integer.signum(x-px()),dy=Integer.signum(y-py());if(dx!=0&&dy!=0){message("Stand beside the mirror to push it.");return;}int nx=x+dx,ny=y+dy;if((dx==0&&dy==0)||occupied(nx,ny)||nx<3670||nx>3690||ny<5270||ny>5290)return;mirrors.remove(key(x,y));remove(object);x=nx;y=ny;mirrors.put(key(x,y),this);spawn();s.player.animate(anim(810));}
        }
        boolean occupied(int x,int y){if(mirrors.containsKey(key(x,y)))return true;for(GameObject o:layout)if(o.tile!=null&&sx(o.getPosition())==x&&sy(o.getPosition())==y&&o.id!=NearRealityRaidAssets.object(45466))return true;Tile t=Tile.get(pos(x,y));return t==null||!t.isFloorFreeCheckDecor();}
        void place(Item item){if(downTicks>0){message("Wait for the walls to rise again.");return;}if(occupied(px(),py())){message("You cannot place a mirror here.");return;}item.remove();addMirror(45455,px(),py(),0);s.player.animate(anim(827));}
        void mine(){if(downTicks<=0){message("A strange force protects the seal. Redirect the light first.");return;}Pickaxe pick=Pickaxe.find(s.player);if(pick==null){message("You need a pickaxe you have the Mining level to use.");return;}mining=true;mineX=px();mineY=py();s.player.animate(pick.regularAnimationID);}
        void shoot(){int x=3676,y=5280,dx=-1,dy=0;Set<String> seen=new HashSet<>();for(int step=0;step<200;step++){x+=dx;y+=dy;if(x<3670||x>3690||y<5270||y>5290)return;String state=x+":"+y+":"+dx+":"+dy;if(!seen.add(state))return;if(px()==x&&py()==y)damage(5+Random.get(3));Mirror mirror=mirrors.get(key(x,y));if(mirror!=null){int[] out=mirror.source==45457?null:reflect(dx,dy,mirror.rotation);if(out==null){graphic(2120,x,y);return;}int g=mirror.rotation==0?2119:mirror.rotation==1?2116:mirror.rotation==2?2117:2118;graphic(g,x,y);dx=out[0];dy=out[1];continue;}
                if(x>=3682&&x<=3684&&y>=5279&&y<=5281){weaken();return;}
                if((x>=3679&&x<=3681&&y>=5279&&y<=5281)||(x>=3676&&x<=3678&&y>=5279&&y<=5281)){graphic(2120,x,y);return;}
                boolean wall=false;for(GameObject o:layout)if(o.tile!=null&&o.id!=-1&&o.id!=NearRealityRaidAssets.object(45466)&&sx(o.getPosition())==x&&sy(o.getPosition())==y){wall=true;break;}if(wall){graphic(2120,x,y);return;}graphic(dy==0?2114:2064,x,y);
            }}
        void weaken(){downTicks=24;mining=false;seal.transformVisual(NearRealityRaidAssets.npc(11707));message("The statue is struck! The seal weakens - mine it now!");for(GameObject o:new ArrayList<>(layout))remove(o);layout.clear();for(Mirror m:new ArrayList<>(mirrors.values()))remove(m.object);mirrors.clear();Item held=findLoan(27296);if(held!=null)held.remove();graphic(2123,3680,5280);}
        final class Orb {int x,y,dx,dy;NPC npc;Orb(int[] data){x=data[0];y=data[1];dx=data[2];dy=data[3];npc=actor(11708,x,y);npc.attackNpcListener=(p,n,m)->false;}boolean tick(){if(px()==x&&py()==y){damage(5+Random.get(3));return false;}int nx=x+dx,ny=y+dy;if(nx<3670||nx>3690||ny<5270||ny>5290||occupied(nx,ny))return false;x=nx;y=ny;npc.getMovement().teleport(pos(x,y));return true;}}
        void pulse(){if(cycle%7==0)for(int i=0;i<2;i++){int[] data=RootToaPuzzleData.HET_ORBS[orbIndex];orbIndex=(orbIndex+1+Random.get(RootToaPuzzleData.HET_ORBS.length-2))%RootToaPuzzleData.HET_ORBS.length;orbs.add(new Orb(data));}for(Iterator<Orb> it=orbs.iterator();it.hasNext();){Orb o=it.next();if(!o.tick()){remove(o.npc);it.remove();}}
            if(downTicks>0){downTicks--;if(mining){Pickaxe p=Pickaxe.find(s.player);if(p==null||px()!=mineX||py()!=mineY)mining=false;else if(cycle%2==0){int level=s.player.getStats().get(StatType.Mining).currentLevel;int base=p.levelReq>=61?(level>=100?19:level>=85?17:10):p.levelReq>=41?(level>=100?17:level>=85?15:8):(level>=100?14:level>=85?13:5);seal.hit(new Hit(s.player).fixedDamage(base+Random.get(2)).ignoreDefence().ignorePrayer());s.player.getStats().addXp(StatType.Mining,35,true);s.player.animate(p.regularAnimationID);}}if(downTicks==0){mining=false;seal.transformVisual(NearRealityRaidAssets.npc(11706));layoutIndex=(layoutIndex+1)%RootToaPuzzleData.HET_ROOMS.length;buildLayout();}}
            else if(cycle%9==0)shoot();
        }
    }

    static int toggleLights(int mask,int index){return mask^(1<<index)^(1<<((index+7)%8))^(1<<((index+1)%8));}
    private static final class Scabaras extends Puzzle {
        static final int[][] BASE={{3539,5283},{3556,5283},{3539,5271},{3556,5271}};
        static final int[][] GATES={{3549,5286},{3564,5286},{3549,5274},{3564,5274}};
        // 0=lights, 1=sum, 2=pillars, 3=memory. Each is assigned to an actual donor quadrant.
        final int[] slots=new int[4],pillarOrder=new int[5],north=new int[9],south=new int[9],memory=new int[5];
        final boolean[] done=new boolean[4],matched=new boolean[9];
        final NPC[] pillars=new NPC[6];final GameObject[] gates=new GameObject[4],lightSigns=new GameObject[8];
        final GameObject[][] matchTiles=new GameObject[2][9];
        final List<NPC> scarabs=new ArrayList<>();final List<GameObject> sumSigns=new ArrayList<>();
        int lights,sum,sumGoal,pillarIndex,pillarDeadline,sequenceIndex,display=-1,displayTick,selectedNorth=-1,selectedSouth=-1;boolean sequenceReady,matchPending;
        long lastPillarLaunchTick=Long.MIN_VALUE;
        Hit lastPillarHit;NPC lastPillarTarget;
        Scabaras(TombsOfAmascutRaid.Session s){super(s);int[] permutation=shuffle(4);for(int i=0;i<4;i++)slots[permutation[i]]=i;System.arraycopy(shuffle(5),0,pillarOrder,0,5);System.arraycopy(shuffle(9),0,north,0,9);System.arraycopy(shuffle(9),0,south,0,9);sumGoal=Random.get(20,45);lights=Random.get(254);}
        int bx(int type){return BASE[slots[type]][0];}int by(int type){return BASE[slots[type]][1];}
        boolean usable(int type){if(done[type])return false;int slot=slots[type];if((slot==1&&!done[typeAt(2)])||(slot==3&&!done[typeAt(0)])){message("This device will not function until the opposite outer puzzle is solved.");return false;}return true;}
        int typeAt(int slot){for(int type=0;type<4;type++)if(slots[type]==slot)return type;throw new IllegalStateException();}
        public void spawn(){removeSourceObjects(id->id==45335);bindTraversal();for(int i=0;i<4;i++)gates[i]=object(45335,GATES[i][0],GATES[i][1],10,3);
            for(int i=0;i<8;i++){int[] d=RootToaPuzzleData.LIGHT_OFFSETS[i];GameObject plate=object(45344,bx(0)+d[0],by(0)+d[1],22,0);final int index=i;action(plate,"Flip",o->{if(usable(0)){lights^=1<<index;damage(20);updateLights();checkLights();}});}updateLights();
            for(int i=0;i<9;i++)for(int[] d:RootToaPuzzleData.SUM_OFFSETS[i])object(45345+i,bx(1)+d[0],by(1)+d[1],22,1);
            GameObject tablet=object(45339,bx(1)-1,by(1)+5,10,0);action(tablet,1,o->message("The tablet shows "+sumGoal+". Current total: "+sum+"."));
            for(int i=0;i<6;i++){int[] d=RootToaPuzzleData.OBELISK_OFFSETS[i];final int index=i;NPC n=actor(i==5?11699:11698,bx(2)+d[0],by(2)+d[1]);pillars[i]=n;
                // Interactive indestructible pillar: NR uses a non-combat NPC with hit callbacks.
                npc_combat.Info info=new npc_combat.Info();info.hitpoints=1;info.attack_style=AttackStyle.CRUSH;info.attack_ticks=4;info.spawn_animation=info.attack_animation=info.defend_animation=info.death_animation=-1;
                n.installCombat(new NPCCombat(){public void init(){setAllowRespawn(false);setAllowRetaliate(false);}public void follow(){}public boolean attack(){return false;}public void startDeath(Hit h){}},info);
                bindPillar(n,index);
            }
            for(int[] d:RootToaPuzzleData.MEMORY_OFFSETS)object(45340,bx(3)+d[0],by(3)+d[1],22,0);
            GameObject button=object(45338,bx(3)-1,by(3)+5,10,0);action(button,1,o->beginMemory());
            for(int side=0;side<2;side++)for(int i=0;i<9;i++){int[] d=RootToaPuzzleData.MATCH_OFFSETS[i];int[] base=side==0?new int[]{3568,5271}:new int[]{3568,5283};final int row=side,index=i;GameObject plate=object(45360,base[0]+d[0],base[1]+d[1],22,1);matchTiles[side][i]=plate;action(plate,1,o->match(row,index));}
            // NR's solo puzzle exposes these three pairs immediately.
            for(int value:new int[]{0,4,5}){matched[value]=true;showPair(value);}
            message("Scabaras: solve the four ancient puzzles, then match the symbols on the two final grids.");
        }
        void bindPillar(NPC n,int index){
            // NR's Hit option dispatches directly to normal combat, whose own
            // weapon range and ownership checks still apply. Do not walk adjacent first.
            n.skipMovementCheck=true;
            n.attackNpcListener=(p,npc,m)->owns(p)&&index<5&&npc.getId()==NearRealityRaidAssets.npc(11698)&&usableQuiet(2);
            NPCAction.register(n,"Hit",(p,npc)->{if(owns(p)&&index<5&&npc.getId()==NearRealityRaidAssets.npc(11698)&&usableQuiet(2))p.getCombat().setTarget(npc);});
            n.hitListener=new HitListener().preDefend(hit->{
                if(hit.attacker==null||!owns(hit.attacker.player)||index>=5||n.getId()!=NearRealityRaidAssets.npc(11698)||!usableQuiet(2)){hit.block().nullify();return;}
                lastPillarLaunchTick=io.ruin.Server.currentTick();
                lastPillarHit=hit;lastPillarTarget=n;
                // Native melee/ranged establish their delay before Entity.hit;
                // mirror NR's forceStop/setActionDelay(0) at this actual launch.
                s.player.getCombat().updateLastAttack(0);pillar(index);
                // Retain the real projectile/delay. The indestructible pillar
                // completes only when a scheduled hit actually arrives.
                hit.block();
            }).preDamage(hit->{
                if(hit.attacker!=null&&owns(hit.attacker.player)&&pillarIndex>4)finish(2);
                hit.block().nullify();
            });
        }
        /** The donor's west-side entrances and authored N/S shortcuts, scoped to this lease. */
        void bindTraversal(){
            int barriers=0,passages=0,platforms=0;
            for(Region r:s.map.getRegions())if(r!=null)for(int x=r.baseX;x<r.baseX+64;x++)for(int y=r.baseY;y<r.baseY+64;y++){
                Tile tile=Tile.get(x,y,s.room.plane,false);if(tile==null||tile.gameObjects==null)continue;
                for(GameObject o:new ArrayList<>(tile.gameObjects)){
                    int sx=sx(o.getPosition()),sy=sy(o.getPosition());
                    if(o.id==NearRealityRaidAssets.object(45135)&&sx==3532&&(sy==5274||sy==5286)){
                        action(o,1,obj->passBarrier(obj));action(o,2,obj->passBarrier(obj));barriers++;
                    }else if(o.id==NearRealityRaidAssets.object(45343)&&sx==3548&&(sy==5277||sy==5283)){
                        o.walkTo=pos(3548,sy>5280?5284:5276);action(o,1,obj->crossShortcut(obj,false));passages++;
                    }else if(o.id==NearRealityRaidAssets.object(45396)&&sx==3560&&sy==5280){
                        // The platform sits over unwalkable water. Native routing must
                        // reach a real bank; the action then routes to the exact endpoint.
                        o.skipReachCheck=p->nearEndpoint(s.sourceX(p),s.sourceY(p),3560,5283,3560,5277);
                        action(o,1,obj->crossShortcut(obj,true));platforms++;
                    }
                }
            }
            if(barriers!=2||passages!=2||platforms!=1)throw new IllegalStateException("Authored Scabaras traversal objects missing");
        }
        static boolean nearEndpoint(int x,int y,int nx,int ny,int sx,int sy){return Math.max(Math.abs(x-nx),Math.abs(y-ny))<=2||Math.max(Math.abs(x-sx),Math.abs(y-sy))<=2;}
        void passBarrier(GameObject o){
            int ox=sx(o.getPosition()),oy=sy(o.getPosition());if(!live()||ox!=3532||(oy!=5274&&oy!=5286)||Math.max(Math.abs(px()-ox),Math.abs(py()-oy))>1)return;
            final int startX=px()<=ox?ox-1:ox+1,dx=startX<ox?2:-2;
            s.player.startEvent(event->{
                Position at=pos(startX,oy);s.player.getRouteFinder().routeAbsolute(at.getX(),at.getY());
                int remaining=20;while(!s.player.getMovement().isAtDestination()&&remaining-->0&&live())event.delay(1);
                if(!live())return;if(py()!=oy||px()!=startX){s.player.getMovement().outOfReach();return;}
                s.player.lock();s.player.face(o);
                try{s.player.step(dx,0,StepType.FORCE_RUN);event.delay(1);}finally{s.player.unlock();}
            });
        }
        void crossShortcut(GameObject o,boolean stones){
            if(!live())return;
            final int x=stones?3560:3548,north=stones?5283:5284,south=stones?5277:5276;
            final boolean fromSouth=py()<5280;final int start=fromSouth?south:north,dest=fromSouth?north:south;
            s.player.startEvent(event->{
                Position at=pos(x,start);s.player.getRouteFinder().routeAbsolute(at.getX(),at.getY());
                int remaining=20;while(!s.player.getMovement().isAtDestination()&&remaining-->0&&live())event.delay(1);
                if(!live()||px()!=x||py()!=start)return;
                s.player.lock();
                try{
                    if(stones){
                        s.player.animate(anim(741));s.player.getMovement().force(0,5280-start,0,0,15,35,fromSouth?Direction.NORTH:Direction.SOUTH);
                        event.delay(2);if(!live())return;
                        s.player.animate(anim(741));s.player.getMovement().force(0,dest-5280,0,0,15,35,fromSouth?Direction.NORTH:Direction.SOUTH);
                        event.delay(1);if(live())s.player.getStats().addXp(StatType.Agility,5,true);
                    }else{
                        s.player.animate(anim(2796));event.delay(1);if(!live())return;
                        s.player.resetAnimation();s.player.getMovement().teleport(pos(x,dest));event.delay(1);
                    }
                }finally{s.player.unlock();}
            });
        }
        void updateLights(){for(int i=0;i<8;i++){remove(lightSigns[i]);lightSigns[i]=null;if((lights&(1<<i))!=0){int[] d=RootToaPuzzleData.LIGHT_OFFSETS[i];lightSigns[i]=object(45384,bx(0)+d[0],by(0)+d[1],10,1);}}}
        void checkLights(){if(lights==255)finish(0);}
        void finish(int type){if(done[type])return;done[type]=true;remove(gates[slots[type]]);message("Ancient puzzle completed ("+countDone()+" / 4).");}
        int countDone(){int n=0;for(boolean b:done)if(b)n++;return n;}
        void pillar(int index){if(!usable(2)||pillarIndex>=pillarOrder.length)return;if(index==pillarOrder[pillarIndex]){pillars[index].transformVisual(NearRealityRaidAssets.npc(11699));pillarIndex++;pillarDeadline=cycle+5;}else{resetPillars();graphic(317,px(),py());final int x=px(),y=py();later(5,()->{if(px()==x&&py()==y)damage(7+Random.get(6));});message("The obelisks reset. That was the wrong order.");}}
        void resetPillars(){pillarIndex=0;pillarDeadline=0;for(int i=0;i<5;i++)pillars[i].transformVisual(NearRealityRaidAssets.npc(11698));}
        void beginMemory(){if(!usable(3)||display>=0)return;int[] p=shuffle(9);System.arraycopy(p,0,memory,0,5);sequenceIndex=0;sequenceReady=false;display=0;displayTick=cycle+1;s.player.animate(anim(832));message("Watch the five flashing tiles, then repeat their order.");}
        void memoryStep(int index){if(!sequenceReady||display>=0)return;if(memory[sequenceIndex]==index){int[] d=RootToaPuzzleData.MEMORY_OFFSETS[index];GameObject glow=object(45341,bx(3)+d[0],by(3)+d[1],10,0);later(1,()->remove(glow));if(++sequenceIndex==5){sequenceReady=false;finish(3);}}else{sequenceReady=false;sequenceIndex=0;damage(6);graphic(302,px(),py());message("Wrong tile. Press the ancient button to try again.");}}
        void moved(int x,int y){
            if(!done[0])for(int i=0;i<8;i++){int[] d=RootToaPuzzleData.LIGHT_OFFSETS[i];if(x==bx(0)+d[0]&&y==by(0)+d[1]&&usable(0)){lights=toggleLights(lights,i);updateLights();checkLights();return;}}
            if(!done[1])for(int value=0;value<9;value++)for(int[] d:RootToaPuzzleData.SUM_OFFSETS[value])if(x==bx(1)+d[0]&&y==by(1)+d[1]&&usable(1)){sum+=value+1;if(sum==sumGoal)finish(1);else if(sum>sumGoal){sum=0;for(GameObject sign:new ArrayList<>(sumSigns))remove(sign);sumSigns.clear();damage(6);graphic(302,x,y);message("The total was too high. Start again at zero.");}else sumSigns.add(object(RootToaPuzzleData.SUM_PRESSURE_LIGHT_IDS[value],x,y,10,1));return;}
            if(!done[3]&&usableQuiet(3))for(int i=0;i<9;i++){int[] d=RootToaPuzzleData.MEMORY_OFFSETS[i];if(x==bx(3)+d[0]&&y==by(3)+d[1]){memoryStep(i);return;}}
        }
        boolean usableQuiet(int type){int slot=slots[type];return !done[type]&&!(slot==1&&!done[typeAt(2)])&&!(slot==3&&!done[typeAt(0)]);}
        int indexOf(int[] values,int value){for(int i=0;i<values.length;i++)if(values[i]==value)return i;return -1;}
        void showPair(int value){for(int side=0;side<2;side++){int index=indexOf(side==0?south:north,value);GameObject o=matchTiles[side][index];o.setId(NearRealityRaidAssets.object(RootToaPuzzleData.SUM_PRESSURE_LIGHT_IDS[value]));}}
        void match(int side,int index){if(countDone()!=4){message("Solve the four ancient puzzles first.");return;}if(matchPending)return;int value=(side==0?south:north)[index];if(matched[value])return;
            int previous=side==0?selectedSouth:selectedNorth;if(previous!=-1)matchTiles[side][indexOf(side==0?south:north,previous)].setId(NearRealityRaidAssets.object(45360));
            matchTiles[side][index].setId(NearRealityRaidAssets.object(45345+value));if(side==0)selectedSouth=value;else selectedNorth=value;
            if(selectedSouth>=0&&selectedNorth>=0){matchPending=true;final int southValue=selectedSouth,northValue=selectedNorth;later(1,()->{if(southValue==northValue){matched[southValue]=true;showPair(southValue);boolean all=true;for(boolean b:matched)all&=b;if(all){complete();return;}}else{matchTiles[0][indexOf(south,southValue)].setId(NearRealityRaidAssets.object(45360));matchTiles[1][indexOf(north,northValue)].setId(NearRealityRaidAssets.object(45360));}selectedNorth=selectedSouth=-1;matchPending=false;});}
        }
        void pulse(){if(!done[2]&&pillarIndex>0&&pillarIndex<pillarOrder.length&&cycle>=pillarDeadline)resetPillars();if(display>=0&&cycle>=displayTick){int[] d=RootToaPuzzleData.MEMORY_OFFSETS[memory[display]];GameObject glow=object(45341,bx(3)+d[0],by(3)+d[1],10,0);later(1,()->remove(glow));displayTick=cycle+2;if(++display==5){display=-1;sequenceReady=true;}}
            scarabs.removeIf(n->n.isRemoved()||n.getCombat().isDead());if(cycle%30==0&&scarabs.size()<2){int x=3535,y=Random.get(1)==0?5274:5286;NPC n=fighter(11697,x,y,true,()->{});scarabs.add(n);n.getCombat().setTarget(s.player);n.animate(anim(9589));}if(cycle%5==0)for(NPC n:new ArrayList<>(scarabs)){n.animate(anim(9587));s.player.hit(new Hit(n,AttackStyle.RANGED).randDamage(6).delay(2));}
        }
    }
    private static int[] shuffle(int size){List<Integer> list=new ArrayList<>();for(int i=0;i<size;i++)list.add(i);Collections.shuffle(list);return list.stream().mapToInt(i->i).toArray();}

    private static final class Apmeken extends Puzzle {
        static final int[][] SPAWNS={{3800,5288},{3808,5288},{3816,5288},{3816,5272},{3808,5272},{3800,5272}};
        static final int[][] VENTS={{3800,5284},{3816,5284},{3816,5276},{3800,5276}};
        static final int[][] ROOFS={{3803,5289},{3811,5289},{3811,5269},{3803,5269}};
        final List<Baboon> monkeys=new ArrayList<>();final Map<Long,GameObject> venom=new HashMap<>();final boolean[] actions=new boolean[4];
        int wave,waveAt=6,specialAt=50,special=-1,specialEnd;boolean waiting;
        Apmeken(TombsOfAmascutRaid.Session s){super(s);}
        public void spawn(){removeSourceObjects(id->id==45135);int hammers=bindExisting(45497,o->{loan(2347);}),potions=bindExisting(45498,o->{Item potion=loan(27297);if(potion!=null)potion.setAmount(100);});if(hammers<1||potions<1)throw new IllegalStateException("Authored Apmeken supply crates missing from room");for(int i=0;i<4;i++){final int index=i;GameObject roof=object(45494,ROOFS[i][0],ROOFS[i][1],10,3);action(roof,1,o->repair(index));}s.player.graphics(gfx(2132));message("Apmeken: defeat all 11 waves. Take a hammer and neutralising potions from the crates, then watch for vent and roof warnings.");}
        void repair(int index){if(findLoan(2347)==null&&!s.player.getInventory().contains(2347,1)){message("You need a hammer from the supply crate.");return;}s.player.animate(anim(3676));if(special!=1){damage(22);graphic(2139,px(),py());message("That roof support did not need repairing.");}else if(!actions[index]){actions[index]=true;message("You repair the roof support.");}}
        void pour(Item potion){int index=-1;for(int i=0;i<4;i++)if(px()==VENTS[i][0]&&py()==VENTS[i][1])index=i;s.player.animate(anim(2295));if(potion.getAmount()>1)potion.setAmount(potion.getAmount()-1);else potion.remove();if(index<0){message("You pour the potion on the floor. Stand directly on a vent to neutralise it.");return;}if(special!=0){damage(22);graphic(2138,px(),py());message("That vent did not need neutralising.");}else if(!actions[index]){actions[index]=true;message("You neutralise the fumes.");}}
        void startSpecial(){special=Random.get(1);specialEnd=cycle+30;Arrays.fill(actions,false);message(special==0?"Apmeken's Sight: fumes are rising from the vents! Pour a potion onto one vent.":"Apmeken's Sight: the roof supports are weakening! Repair one support.");for(int i=0;i<4;i++)graphic(i<2?2134:2135,special==0?VENTS[i][0]:ROOFS[i][0],special==0?VENTS[i][1]:ROOFS[i][1]);}
        void resolveSpecial(){boolean success=false;for(boolean done:actions)success|=done;if(!success){damage(22);graphic(special==0?2138:2139,px(),py());message("The room hazard was not dealt with in time.");}else message("You dealt with the room hazard in time.");special=-1;specialAt=cycle+50;}
        void spawnWave(){if(wave>=RootToaPuzzleData.BABOON_WAVES.length){if(monkeys.isEmpty())complete();return;}int[] order=shuffle(6),data=RootToaPuzzleData.BABOON_WAVES[wave];for(int i=0;i<data.length;i++){int source=data[i]<0?11715+Random.get(2):data[i];int[] at=SPAWNS[order[i]];monkeys.add(new Baboon(source,at[0],at[1],true));}wave++;waiting=false;message("Apmeken wave "+wave+" / 11.");}
        long key(int x,int y){return ((long)x<<32)|(y&0xffffffffL);}
        void pool(int x,int y){long key=key(x,y);if(venom.containsKey(key)||venom.size()>=80)return;for(int[] at:VENTS)if(x==at[0]&&y==at[1])return;venom.put(key,object(45493,x,y,10,0));}
        final class Baboon {
            final int source;final boolean critical;final NPC npc;int attackAt,explodeAt,thralls;boolean dying;
            Baboon(int source,int x,int y,boolean critical){this.source=source;this.critical=critical;thralls=source==11715?Random.get(6,8):0;npc=fighter(source,x,y,source!=11717,()->{dying=true;});npc.getCombat().setTarget(s.player);attackAt=cycle+4;}
            boolean alive(){return !dying&&!npc.isRemoved()&&!npc.getCombat().isDead();}
            void pulse(){if(!alive())return;int x=sx(npc.getPosition()),y=sy(npc.getPosition()),distance=Math.max(Math.abs(x-px()),Math.abs(y-py()));
                if(source==11717){if(cycle%3==0){pool(x,y);int nx=Random.get(3799,3817),ny=Random.get(5273,5287);npc.getRouteFinder().routeAbsolute(pos(nx,ny).getX(),pos(nx,ny).getY());}return;}
                if(explodeAt>0){if(cycle==explodeAt-3)npc.animate(anim(9756));if(cycle>=explodeAt){graphic(2249,x,y);graphic(131,x,y);if(distance<=1)damage(14+Random.get(2));dying=true;remove(npc);}return;}
                boolean melee=source==11709||source==11712||source==11718;
                if(cycle<attackAt||distance>(melee||source==11716?1:8))return;
                npc_combat.Info stats=npc.getCombat().getInfo();attackAt=cycle+Math.max(4,stats.attack_ticks);
                if(source==11716){explodeAt=cycle+4;npc.getCombat().reset();return;}
                if(source==11715&&thralls>0){thralls--;npc.animate(anim(9747));later(1,()->{if(alive()&&monkeys.size()<40){int nx=x+Random.get(-2,2),ny=y+Random.get(-2,2);Tile tile=Tile.get(pos(nx,ny));if(tile!=null&&tile.isFloorFreeCheckDecor())monkeys.add(new Baboon(11718,nx,ny,false));}});return;}
                AttackStyle style=melee?AttackStyle.CRUSH:(source==11710||source==11713?AttackStyle.RANGED:AttackStyle.MAGIC);npc.animate(anim(melee?9742:style==AttackStyle.RANGED?9745:9746));Hit hit=new Hit(npc,style).randDamage(Math.max(1,stats.max_damage)).delay(melee?0:2);hit.preDamage(e->{if(!owns(e.player)||!alive())hit.block().nullify();});if(source==11718||source==11715)hit.postDamage(e->{if(owns(e.player)&&hit.damage>0)s.player.getStats().get(StatType.Prayer).drain(source==11718?2:hit.damage);});s.player.hit(hit);if(!melee){int projectile=gfx(style==AttackStyle.RANGED?2242:2247);new Projectile(projectile,20,36,20,60,0,0,0).send(npc.getPosition(),s.player.getPosition());}
            }
        }
        void pulse(){monkeys.removeIf(b->!b.alive());if(waveAt>0&&cycle>=waveAt){waveAt=0;spawnWave();if(solved)return;}for(Baboon baboon:new ArrayList<>(monkeys))baboon.pulse();
            int critical=0;for(Baboon b:monkeys)if(b.alive()&&b.critical)critical++;
            int threshold=wave<=3?0:wave<=9?1:2;if(!waiting&&wave>0&&((wave<11&&critical<=threshold)||(wave==11&&monkeys.isEmpty()))){waiting=true;waveAt=cycle+4;}
            if(special<0&&cycle>=specialAt)startSpecial();else if(special>=0&&cycle>=specialEnd)resolveSpecial();if(cycle%3==0&&venom.containsKey(key(px(),py())))damage(6);
        }
    }
}
