package io.ruin.model.activities.raids.tob.port;

import io.ruin.model.World;
import io.ruin.model.activities.raids.NearRealityRaidAssets;
import io.ruin.data.impl.npcs.npc_combat;
import io.ruin.model.combat.*;
import io.ruin.model.entity.Entity;
import io.ruin.model.entity.npc.*;
import io.ruin.model.entity.player.Player;
import io.ruin.model.entity.shared.listeners.HitListener;
import io.ruin.model.map.*;
import io.ruin.model.map.object.GameObject;
import io.ruin.model.skills.prayer.Prayer;
import java.util.*;

/** Instance-scoped adaptation of Near Reality's normal-mode encounter scripts. */
final class TheatreCombat {
    final TheatreOfBloodRaid.Run run;
    final TheatreOfBloodRaid.Room room;
    final Map<NPC,Actor> actors=new IdentityHashMap<>();
    NPC boss;int ticks;boolean closed;
    TheatreCombat(TheatreOfBloodRaid.Run r,TheatreOfBloodRaid.Room room){run=r;this.room=room;}
    void begin(){Behavior mechanic;switch(room){case MAIDEN:mechanic=new Maiden();break;case BLOAT:mechanic=new Bloat();break;case NYLOCAS:mechanic=new Nylo();break;case SOTETSEG:mechanic=new Sotetseg();break;case XARPUS:mechanic=new Xarpus();break;case VERZIK:mechanic=new Verzik();break;default:throw new IllegalStateException();}boss=run.spawn(room.npc,room.npcName,room.bx,room.by,0,mechanic);}
    static void attach(TheatreOfBloodRaid.Run r,NPC npc,npc_combat.Info info,int hp,Behavior b){
        TheatreCombat encounter=r.encounter;Actor a=new Actor(encounter,npc,b);encounter.actors.put(npc,a);
        npc.installCombat(a,info);npc.getCombat().setAllowRespawn(false);npc.getCombat().setAllowRetaliate(false);npc.sixSystemsRewardless=true;
        int max=hp>0?hp:(int)Math.ceil(info.hitpoints*healthScale(r.party.members.size()));npc.setMaxHp(Math.max(1,max));npc.setHp(Math.max(1,max));
        npc.attackNpcListener=(p,n,m)->a.live()&&r.valid(p)&&b.attackable(a,p);
        npc.hitListener=new HitListener().preDamage(hit->{if(hit.attacker!=null&&hit.attacker.player!=null&&!r.valid(hit.attacker.player)){reject(hit);return;}b.incoming(a,hit);}).postDamage(hit->{if(hit.attacker!=null&&hit.attacker.player!=null&&hit.type!=HitType.HEAL&&hit.damage>0)r.recordDamage(hit.attacker.player,npc,hit.damage);});
        int petSource=npc.getId();
        npc.deathEndListener=(entity,killer,hit)->{
            if(encounter.closed||r.closed)return;
            if(killer!=null&&r.valid(killer.player))
                io.ruin.content.pets.UniversalPets.encounterKilled(npc,killer.player,petSource);
            b.died(a);if(npc==encounter.boss)r.completeRoom();
        };
        b.begin(a);
    }
    static double healthScale(int size){return size<=3?.75:size==4?.875:1.;}
    void pulse(){if(closed)return;ticks++;for(Actor a:new ArrayList<>(actors.values()))if(a.live())a.behavior.tick(a);}
    void close(){closed=true;for(Actor a:new ArrayList<>(actors.values()))a.behavior.close(a);actors.clear();}
    static void reject(Hit h){h.damage=0;h.nullify().setResetActions(false);}
    abstract static class Behavior {
        void begin(Actor a){}void tick(Actor a){}void incoming(Actor a,Hit hit){}void died(Actor a){}void close(Actor a){}
        boolean attackable(Actor a,Player p){return true;}boolean interceptDeath(Actor a){return false;}
    }
    static final class Actor extends NPCCombat {
        final TheatreCombat encounter;final TheatreOfBloodRaid.Run run;final Behavior behavior;final NPC entity;int ticks;
        Actor(TheatreCombat e,NPC n,Behavior b){encounter=e;run=e.run;entity=n;behavior=b;}
        boolean live(){return !encounter.closed&&!run.closed&&!entity.isRemoved()&&!isDead();}
        @Override public void init(){setAllowRespawn(false);setAllowRetaliate(false);}
        @Override public void follow(){}
        @Override public boolean attack(){return false;}
        @Override public boolean canAttack(Entity victim){return live()&&victim!=null&&run.valid(victim.player);}
        @Override public void dropItems(Killer killer){}
        @Override public void startDeath(Hit hit){if(behavior.interceptDeath(this))return;super.startDeath(hit);}
        void animate(int source){int id=NearRealityRaidAssets.animation(source);if(id>=0)entity.animate(id);}
        void transform(int source,String name){int id=NearRealityRaidAssets.npc(source,name);if(id<0)throw new IllegalStateException("Missing Theatre form "+source);entity.transformVisual(id);}
        void gfx(Player p,int source){int id=NearRealityRaidAssets.graphic(source);if(id>=0)p.graphics(id);}
        void floorGfx(int source,Position tile){int id=NearRealityRaidAssets.graphic(source);if(id>=0)World.sendGraphics(id,0,0,tile);}
        void later(int delay,Runnable action){run.later(delay,()->{if(live())action.run();});}
        List<Player> players(){return run.targets();}
        Player target(){List<Player> p=players();return p.isEmpty()?null:p.get(TheatreOfBloodRaid.RANDOM.nextInt(p.size()));}
        int maximumHit(){return info.max_damage;}
        int random(int min,int max){return min+TheatreOfBloodRaid.RANDOM.nextInt(Math.max(1,max-min+1));}
        Position tile(int x,int y){return run.tile(x,y);}
        int x(){return roomX(entity.getPosition());}int y(){return roomY(entity.getPosition());}
        int roomX(Position p){return run.room().cx*8+p.getX()-run.map.swRegion.baseX;}int roomY(Position p){return run.room().cy*8+p.getY()-run.map.swRegion.baseY;}
        void projectile(Entity target,int gfx,int start,int end,int delay){int id=NearRealityRaidAssets.graphic(gfx);if(id>=0)new Projectile(id,start,end,0,delay*30,0,0,0).send(entity,target);}
        void projectile(Position to,int gfx,int start,int end,int delay){int id=NearRealityRaidAssets.graphic(gfx);if(id>=0)new Projectile(id,start,end,0,delay*30,0,0,0).send(entity.getPosition(),to);}
        void damage(Player p,AttackStyle style,int min,int max,int delay,double protectedMultiplier){if(!run.valid(p))return;Hit h=style==null?new Hit(entity):new Hit(entity,style);h.randDamage(min,max).delay(delay);if(style!=null)h.ignorePrayer();h.preDamage(victim->{if(!live()||!run.valid(p)){reject(h);return;}if(style!=null&&p.getPrayer().isActive(style.isMelee()?Prayer.PROTECT_FROM_MELEE:style.isRanged()?Prayer.PROTECT_FROM_MISSILES:Prayer.PROTECT_FROM_MAGIC))h.damage=(int)(h.damage*protectedMultiplier);});p.hit(h);}
        void damage(Player p,int min,int max){damage(p,null,min,max,1,1.);}
        NPC child(int id,String name,int x,int y,int hp,Behavior behavior){return run.spawn(id,name,x,y,hp,behavior);}
        GameObject object(int source,String name,int x,int y,int shape){int id=NearRealityRaidAssets.object(source,name);if(id<0)throw new IllegalStateException("Missing Theatre object "+source);GameObject obj=new GameObject(id,tile(x,y),shape,0).spawn();run.objects.add(obj);return obj;}
        void move(NPC n,int x,int y){n.getRouteFinder().routeAbsolute(tile(x,y).getX(),tile(x,y).getY());}
    }
    static final class Maiden extends Behavior {
        int stage,storms;double max=35,bloodMax=10;final Map<Position,Integer> splats=new HashMap<>();
        @Override void tick(Actor a){a.ticks++;splats.entrySet().removeIf(e->e.getValue()<a.ticks);for(Player p:a.players())if(splats.containsKey(p.getPosition())){int hit=a.random(5,(int)bloodMax);a.damage(p,hit,hit);a.entity.incrementHp(hit);}
            double hp=a.entity.getHp()/(double)a.entity.getMaxHp();int required=hp<=.3?3:hp<=.5?2:hp<=.7?1:0;while(stage<required){stage++;a.transform(8360+stage,"The Maiden of Sugadinti");spawnCrabs(a);}
            if(a.ticks%10!=0)return;Player target=a.players().stream().min(Comparator.comparingInt(p->Math.max(Math.abs(a.x()-a.run.sourceX(p)),Math.abs(a.y()-a.run.sourceY(p))))).orElse(null);if(target==null)return;
            if(storms>=2&&a.random(1,4)==1){storms=0;a.animate(8091);for(Player p:a.players()){Position pos=p.getPosition().copy();a.projectile(pos,1578,135,0,3);a.later(3,()->{splats.put(pos,a.ticks+10);a.floorGfx(1579,pos);});}}
            else{storms++;a.animate(8092);a.entity.face(target);a.projectile(target,1577,0,10,3);a.damage(target,AttackStyle.MAGIC,0,(int)max,3,.5);}}
        void spawnCrabs(Actor a){int[][] spots={{3174,4457},{3174,4435},{3179,4436},{3178,4457},{3182,4457},{3182,4435},{3187,4436},{3186,4457},{3186,4455},{3186,4437}};int count=Math.min(spots.length,a.run.party.members.size()*2);for(int i=0;i<count;i++){int[] pos=spots[i];a.child(8366,"Nylocas Matomenos",pos[0],pos[1],0,new Behavior(){@Override void tick(Actor c){c.move(c.entity,3167,4446);if(Math.max(Math.abs(c.x()-3167),Math.abs(c.y()-4446))<=2){max+=3.5;bloodMax+=1.8;a.entity.incrementHp(Math.min(200,c.entity.getHp()*2));c.entity.remove();}}});}}
    }
    static final class Bloat extends Behavior {
        final int[][] corners={{3299,4451},{3288,4451},{3288,4440},{3299,4440}};int corner,cycle,cycles,downs,direction=1,nextReverse=28;boolean down;final Map<Position,Integer> falling=new HashMap<>();
        @Override void begin(Actor a){cycles=a.random(7,12);}
        @Override void incoming(Actor a,Hit hit){if(!down)hit.damage/=2;}
        @Override void tick(Actor a){a.ticks++;if(down){if(++cycle==31)for(Player p:a.players())if(io.ruin.model.map.route.routes.ProjectileRoute.allow(a.entity,p))a.damage(p,40,80);if(cycle>=33){down=false;cycle=0;downs++;cycles=a.random(7,12);nextReverse=a.ticks+25;}return;}
            if(a.ticks>=nextReverse){direction=-direction;nextReverse=Integer.MAX_VALUE;corner=(corner+direction+4)%4;}
            int[] dest=corners[corner];if(a.x()==dest[0]&&a.y()==dest[1])corner=(corner+direction+4)%4;dest=corners[corner];a.move(a.entity,dest[0],dest[1]);
            for(Player p:a.players())if(io.ruin.model.map.route.routes.ProjectileRoute.allow(a.entity,p)){a.projectile(p,1568,60,10,1);a.damage(p,AttackStyle.RANGED,10,20,1,.5);a.gfx(p,1569);for(Player other:a.players())if(other!=p&&p.getPosition().isWithinDistance(other.getPosition(),1))a.damage(other,AttackStyle.RANGED,10,20,2,.5);}
            if(downs==0){if(a.ticks>=35){down=true;cycle=0;a.entity.getMovement().reset();a.animate(8082);}return;}
            if(++cycle%5==0){if(--cycles<=0){down=true;cycle=0;a.entity.getMovement().reset();a.animate(8082);return;}for(int i=0;i<16;i++){Position pos=a.tile(a.random(3287,3303),a.random(4439,4454));if(falling.put(pos,a.ticks+3)!=null)continue;a.floorGfx(a.random(1570,1573),pos);}}
            for(Iterator<Map.Entry<Position,Integer>> it=falling.entrySet().iterator();it.hasNext();){Map.Entry<Position,Integer> entry=it.next();if(entry.getValue()<=a.ticks){for(Player p:a.players())if(p.getPosition().equals(entry.getKey())){a.damage(p,30,50);a.gfx(p,254);}it.remove();}}
        }
    }
    static final class Nylo extends Behavior {
        int style;final int[] forms={8355,8357,8356};
        @Override void begin(Actor a){a.animate(8075);}
        @Override void incoming(Actor a,Hit h){if(h.type==HitType.HEAL||h.attacker==null||h.attacker.player==null)return;boolean correct=style==0?h.attackStyle!=null&&h.attackStyle.isMelee():style==1?h.attackStyle!=null&&h.attackStyle.isRanged():h.attackStyle==AttackStyle.MAGIC;if(!correct){int damage=h.damage;reject(h);a.entity.incrementHp(damage);a.damage(h.attacker.player,damage,damage);}}
        @Override void tick(Actor a){a.ticks++;if(a.ticks%10==0){style=(style+a.random(1,2))%3;a.transform(forms[style],"Nylocas Vasilias");for(Player p:a.players())p.getCombat().reset();}if(a.ticks%4!=0)return;Player p=a.target();if(p==null)return;AttackStyle attack=style==0?AttackStyle.STAB:style==1?AttackStyle.RANGED:AttackStyle.MAGIC;a.animate(style==0?8004:style==1?8001:7990);if(style>0)a.projectile(p,style==1?1561:1610,40,20,2);a.damage(p,attack,0,a.maximumHit(),style==0?1:2,style==0?0.:.25);}
    }
    static final class Sotetseg extends Behavior {
        int magic,phase;Maze maze;
        @Override void incoming(Actor a,Hit h){if(maze!=null)reject(h);}
        @Override void tick(Actor a){a.ticks++;if(maze!=null){if(maze.tick(a))maze=null;return;}double ratio=a.entity.getHp()/(double)a.entity.getMaxHp();if(phase<2&&ratio<=(phase==0?.66:.33)){phase++;maze=new Maze(a);return;}if(a.ticks%6!=0)return;Player p=a.target();if(p==null)return;a.entity.face(p);
            if(++magic==11){magic=0;a.animate(8139);p.sendMessage("A large ball of energy is shot your way. Stand together!");a.projectile(p,1604,40,30,15);List<Player> alive=a.players();a.later(15,()->{if(!a.run.valid(p))return;List<Player> near=new ArrayList<>();for(Player q:a.players())if(q.getPosition().isWithinDistance(p.getPosition(),1))near.add(q);if(near.isEmpty())return;int each=(70+Math.max(0,alive.size()-near.size())*12)/near.size();for(Player q:near){a.damage(q,each,each);a.gfx(q,1605);}});}
            else if(a.entity.getPosition().isWithinDistance(p.getPosition(),4)&&a.random(0,1)==0){a.animate(8138);a.damage(p,AttackStyle.CRUSH,0,50,1,0.);}
            else{a.animate(8139);a.projectile(p,1606,20,30,4);a.damage(p,AttackStyle.MAGIC,0,50,4,0.);if(a.players().size()>1){List<Player> next=a.players();next.remove(p);Collections.shuffle(next);for(int i=0;i<Math.min(2,next.size());i++){Player q=next.get(i);AttackStyle style=i==0&&a.random(0,1)==0?AttackStyle.MAGIC:AttackStyle.RANGED;a.projectile(q,style==AttackStyle.MAGIC?1606:1607,20,30,6);a.damage(q,style,0,50,6,0.);}}}}
        @Override void close(Actor a){if(maze!=null)maze.close();}
    }
    /** Donor's bounded monotone maze path; the native adapter also waits for the team. */
    static final class Maze {
        final Set<Long> path=new LinkedHashSet<>();final List<GameObject> tiles=new ArrayList<>();Player runner;boolean runnerFinished;int ticks;
        Maze(Actor a){List<int[]> generated=mazePath(TheatreOfBloodRaid.RANDOM);for(int[] t:generated)path.add(key(t[0],t[1]));List<Player> players=a.players();if(players.isEmpty())return;runner=players.get(a.random(0,players.size()-1));for(Player p:players){p.resetActions(true,true,true);p.getMovement().teleport(a.tile(3279,4309));}for(long tile:path){int x=(int)(tile>>32),y=(int)tile;tiles.add(a.object(33035,"",x,y,22));}runner.sendMessage("Follow the red path north. Your team must cross the maze before the fight resumes.");}
        boolean tick(Actor a){ticks++;boolean crossed=true;for(Player p:a.players()){int x=a.run.sourceX(p),y=a.run.sourceY(p);if(y<=4325)crossed=false;if(x>=3274&&x<=3287&&y>=4311&&y<=4325&&!path.contains(key(x,y))){a.damage(p,10,25);for(Player q:a.players())if(q!=p&&p.getPosition().isWithinDistance(q.getPosition(),1))a.damage(q,10,25);}}if(crossed&&!a.players().isEmpty()){close();for(Player p:a.players())p.getMovement().teleport(a.tile(3275,4327));return true;}return false;}
        void close(){for(GameObject tile:tiles)if(tile.id!=-1)tile.remove();tiles.clear();}
    }
    static List<int[]> mazePath(Random random){List<int[]> path=new ArrayList<>();int x=random.nextInt(14),y=0;path.add(new int[]{3274+x,4311});path.add(new int[]{3274+x,4312});y=1;while(y<14){int nextX=Math.max(0,x-5)+random.nextInt(Math.min(13,x+5)-Math.max(0,x-5)+1);for(int px=Math.min(x,nextX);px<=Math.max(x,nextX);px++)path.add(new int[]{3274+px,4311+y});int nextY=Math.min(14,y+(random.nextBoolean()?2:4));for(int py=y;py<=nextY;py++)path.add(new int[]{3274+nextX,4311+py});x=nextX;y=nextY;}return path;}
    static long key(int x,int y){return ((long)x<<32)|(y&0xffffffffL);}
    static final class Xarpus extends Behavior {
        int phase,spawned,quadrant,turn,counterAt,number,heal,interval;final Map<Position,Integer> exhumed=new HashMap<>();final Map<Position,GameObject> exhumedObjects=new HashMap<>();final Set<Position> poison=new HashSet<>();
        @Override void begin(Actor a){int[][] data={{7,20,13},{8,16,8},{12,12,8},{15,9,4},{18,6,4}};int[] row=data[Math.max(0,Math.min(4,a.run.party.members.size()-1))];number=row[0];heal=row[1];interval=row[2];a.entity.setHp(Math.max(1,a.entity.getMaxHp()-heal*11*number));}
        @Override boolean attackable(Actor a,Player p){return phase>0;}
        @Override void incoming(Actor a,Hit hit){if(phase==0)reject(hit);else if(phase==2&&a.ticks>counterAt&&hit.attacker!=null&&hit.attacker.player!=null&&quadrant(a,hit.attacker.player)==quadrant)a.damage(hit.attacker.player,50+hit.damage/2,50+hit.damage/2);}
        int quadrant(Actor a,Player p){return (a.run.sourceX(p)>=a.x()+2?1:0)+(a.run.sourceY(p)>=a.y()+2?2:0);}
        @Override void tick(Actor a){a.ticks++;if(phase==0){if(a.ticks>7&&a.ticks%interval==0&&spawned<number){Position tile=a.tile(a.x()+a.random(-7,7),a.y()+a.random(-7,7));exhumed.put(tile,a.ticks);exhumedObjects.put(tile,a.object(32743,"Exhumed",a.roomX(tile),a.roomY(tile),22));spawned++;}for(Iterator<Map.Entry<Position,Integer>> it=exhumed.entrySet().iterator();it.hasNext();){Map.Entry<Position,Integer> entry=it.next();if(a.ticks>entry.getValue()+11){GameObject obj=exhumedObjects.remove(entry.getKey());if(obj!=null)obj.remove();a.floorGfx(1549,entry.getKey());it.remove();continue;}boolean blocked=false;for(Player p:a.players())if(p.getPosition().equals(entry.getKey()))blocked=true;if(!blocked&&a.ticks>=entry.getValue()+3)a.entity.incrementHp(heal);}if(a.ticks>=number*interval+25){for(GameObject obj:exhumedObjects.values())obj.remove();exhumedObjects.clear();phase=1;a.animate(8061);a.transform(8340,"Xarpus");a.entity.getMovement().teleport(a.tile(a.x()-1,a.y()-1));}return;}
            for(Player p:a.players())if(poison.contains(p.getPosition()))a.damage(p,6,8);
            if(phase==1){if(a.entity.getHp()<=a.entity.getMaxHp()/4){phase=2;turn=a.ticks;counterAt=a.ticks+8;a.entity.forceText("Screeeeech!");return;}if(a.ticks%5==0){Player p=a.target();if(p==null)return;Position tile=p.getPosition().copy();a.animate(8059);a.projectile(tile,1555,80,0,2);a.later(2,()->{if(poison.add(tile))a.object(32744,"Acidic miasma",a.roomX(tile),a.roomY(tile),22);a.floorGfx(1556,tile);for(Player q:a.players())if(q.getPosition().isWithinDistance(tile,1))a.damage(q,6,8);});}}
            else if(a.ticks%8==0){quadrant=(quadrant+a.random(1,3))%4;turn=a.ticks;a.entity.face(a.tile(a.x()+((quadrant&1)==0?-6:6),a.y()+((quadrant&2)==0?-6:6)).getX(),a.tile(a.x(),a.y()+((quadrant&2)==0?-6:6)).getY());}}
    }
    static final class Verzik extends TheatreVerzik { }
}
