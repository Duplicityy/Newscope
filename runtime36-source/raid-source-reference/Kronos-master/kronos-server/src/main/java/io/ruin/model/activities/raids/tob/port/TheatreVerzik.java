package io.ruin.model.activities.raids.tob.port;

import io.ruin.data.impl.npcs.npc_combat;
import io.ruin.model.activities.raids.NearRealityRaidAssets;
import io.ruin.model.combat.*;
import io.ruin.model.entity.npc.NPC;
import io.ruin.model.entity.player.Player;
import io.ruin.model.map.*;
import io.ruin.model.map.object.GameObject;
import java.util.*;
import static io.ruin.model.activities.raids.tob.port.TheatreCombat.Actor;
import static io.ruin.model.activities.raids.tob.port.TheatreCombat.Behavior;

/** Normal mode phases adapted from donor VerzikConfigs and first/second/third scripts. */
class TheatreVerzik extends Behavior {
    int phase=1,attacks=-1,phaseTicks,electric,shieldUntil,pauseUntil,passive;
    boolean reds,tornado;
    final List<NPC> pillars=new ArrayList<>(),redCrabs=new ArrayList<>();
    @Override void begin(Actor a){
        applyStats(a,1);
        for(int y=18;y<=30;y+=6)for(int x:new int[]{25,37})
            pillars.add(a.child(8379,"Supporting pillar",3136+x,4288+y,350,new Behavior(){
                @Override boolean attackable(Actor c,Player p){return false;}
            }));
    }
    static void applyStats(Actor a,int phase){
        npc_combat.Info info=NearRealityRaidAssets.combatInfo(phase==1?8370:phase==2?8372:8374);
        if(info==null)throw new IllegalStateException("Missing original Verzik stats");
        // The donor base JSON has1HP placeholders; these are explicit VerzikConfigs overrides.
        info.hitpoints=phase==1?1500:3250;info.attack=phase==3?300:400;info.strength=400;
        info.defence=phase==1?20:phase==2?100:150;info.ranged=phase==3?300:400;info.magic=phase==3?300:400;
        info.stab_attack=phase==3?80:0;info.slash_attack=phase==3?30:0;info.crush_attack=phase==3?80:0;
        info.magic_attack=80;info.ranged_attack=80;
        int[] defence=phase==1?new int[]{20,20,20,20,20}:phase==2?new int[]{100,60,100,70,250}:new int[]{70,30,70,100,230};
        info.stab_defence=defence[0];info.slash_defence=defence[1];info.crush_defence=defence[2];info.magic_defence=defence[3];info.ranged_defence=defence[4];
        a.entity.installCombat(a,info);int hp=(int)Math.ceil(info.hitpoints*TheatreCombat.healthScale(a.run.party.members.size()));
        a.entity.setMaxHp(hp);a.entity.setHp(hp);
    }
    @Override boolean interceptDeath(Actor a){if(phase>=3)return false;switchPhase(a);return true;}
    void switchPhase(Actor a){
        a.run.phaseMvpQueued=true;phase++;phaseTicks=0;attacks=phase==2?-1:0;electric=0;reds=false;
        for(NPC pillar:pillars)if(!pillar.isRemoved())pillar.remove();pillars.clear();
        for(NPC crab:redCrabs)if(!crab.isRemoved())crab.remove();redCrabs.clear();
        if(phase==2)for(Player p:a.run.party.members)TheatreOfBloodRaid.removeLoans(p,a.run.id);
        a.animate(phase==2?8111:8118);a.transform(phase==2?8372:8374,"Verzik Vitur");applyStats(a,phase);
        a.entity.getMovement().teleport(a.tile(phase==2?3167:3165,phase==2?4313:4311));
        a.entity.forceText(phase==3?"Behold my true nature!":"You will pay for this!");pauseUntil=a.ticks+4;
    }
    @Override void incoming(Actor a,Hit hit){
        if(phase==1){if(TheatreDawnbringer.shieldHit(hit))return;hit.damage=Math.min(hit.damage,hit.attackStyle!=null&&hit.attackStyle.isMelee()?10:3);}
        else if(phase==2&&a.ticks<shieldUntil)hit.type=HitType.HEAL;
    }
    @Override void tick(Actor a){
        a.ticks++;phaseTicks++;if(a.ticks<pauseUntil)return;
        if(phase==1){if(phaseTicks%(attacks<0?19:15)==0){attacks++;first(a);}return;}
        if(phase==2){if(phaseTicks%4==0&&a.ticks>=shieldUntil)second(a);return;}
        if(!tornado&&a.entity.getHp()<=a.entity.getMaxHp()*.2){tornado=true;for(Player p:a.players())spawnTornado(a,p);}
        if(phaseTicks%7!=0)return;
        if(attacks==4){attacks=0;int spell=passive++%4;castPassive(a,spell);if(spell!=0)return;}
        Player p=a.target();if(p==null)return;
        if(a.entity.getPosition().isWithinDistance(p.getPosition(),4)&&a.random(0,1)==0){a.animate(8123);for(Player q:a.players())if(q.getPosition().isWithinDistance(p.getPosition(),1))a.damage(q,AttackStyle.CRUSH,0,60,1,0.);attacks++;return;}
        AttackStyle style=a.random(0,1)==0?AttackStyle.RANGED:AttackStyle.MAGIC;
        a.animate(style==AttackStyle.MAGIC?8124:8125);
        for(Player q:a.players()){a.projectile(q,style==AttackStyle.MAGIC?1594:1593,32,10,3);a.damage(q,style,0,32,3,0.);}
        attacks++;
    }
    void first(Actor a){a.animate(8109);Map<NPC,Integer> covered=new IdentityHashMap<>();
        for(Player p:a.players()){
            NPC cover=null;for(NPC pillar:pillars)if(!pillar.isRemoved()&&pillar.getHp()>0&&behind(a,p,pillar)){cover=pillar;break;}
            if(cover==null){a.projectile(p,1580,126,28,3);a.damage(p,AttackStyle.MAGIC,0,137,3,.5);}
            else covered.merge(cover,1,Integer::sum);
        }
        NPC target=covered.entrySet().stream().min(Comparator.comparingInt(Map.Entry::getValue)).map(Map.Entry::getKey).orElse(null);
        if(target!=null)target.hit(new Hit(a.entity).randDamage(40,60).delay(3));
    }
    boolean behind(Actor a,Player p,NPC pillar){int x=a.roomX(pillar.getPosition()),y=a.roomY(pillar.getPosition());int px=a.run.sourceX(p),py=a.run.sourceY(p);
        if(x==3161)return px>=x-3&&px<=x&&py>=y-3&&py<=y||px==x+1&&py==y-1||px==x-1&&py==y+1;
        return px>=x+2&&px<=x+4&&py>=y-2&&py<=y||px==x+1&&py==y-1||px==x+3&&py==y+1;
    }
    void second(Actor a){
        if(!reds&&a.entity.getHp()<=a.entity.getMaxHp()*.35){reds=true;attacks=0;spawnReds(a);return;}
        boolean bounce=false;for(Player p:a.players())if(under(a,p))bounce=true;
        if(bounce){a.animate(8116);for(Player p:a.players())if(under(a,p)){a.damage(p,0,50);a.gfx(p,80);}return;}
        if(reds){if(attacks>0&&attacks%7==0)spawnReds(a);else{int roll=a.random(0,100);if(roll>=25)blood(a);else if(roll>=15)electric(a);else lamp(a);}attacks++;return;}
        if(attacks==4){attacks=0;electric++;electric(a);}
        else if(attacks==-1||electric==5){attacks=0;electric=0;spawnPurple(a);spawnChasers(a);}
        else{attacks++;lamp(a);}
    }
    boolean under(Actor a,Player p){int x=a.run.sourceX(p),y=a.run.sourceY(p);return x>=3166&&x<=3170&&y>=4312&&y<=4316;}
    void lamp(Actor a){a.animate(8114);for(Player p:a.players()){Position tile=p.getPosition().copy();a.projectile(tile,1583,52,0,2);a.later(2,()->{a.floorGfx(1584,tile);if(a.run.valid(p)&&p.getPosition().equals(tile))a.damage(p,AttackStyle.RANGED,0,50,1,.5);});}}
    void blood(Actor a){Player p=a.target();if(p==null)return;a.animate(8114);a.projectile(p,1591,52,26,2);Hit hit=new Hit(a.entity,AttackStyle.MAGIC).randDamage(29).delay(2);hit.preDamage(v->{if(!a.live()||!a.run.valid(p)){hit.damage=0;hit.nullify();}});hit.postDamage(v->{if(a.live()&&a.run.valid(p)){a.entity.incrementHp(hit.damage/2);a.gfx(p,1592);}});p.hit(hit);}
    void electric(Actor a){List<Player> order=a.players();Collections.shuffle(order);if(order.isEmpty())return;a.animate(8114);for(int i=0;i<order.size();i++){Player p=order.get(i);a.projectile(p,1585,48,0,2+i);boolean last=i==order.size()-1;boolean insulated=p.getEquipment().getId(10)==7159||p.getEquipment().getId(10)==22951;a.damage(p,AttackStyle.MAGIC,last?(insulated?25:45):4,last?(insulated?28:48):8,2+i,1.);}}
    /** Donor VerzikConfigs200HP is scaled by this helper's TheatreNPC superclass. */
    static int redCrabHitpoints(int partySize){return partySize<=3?150:partySize==4?175:200;}
    static void applyRedCrabStats(npc_combat.Info info){
        // VerzikConfigs overrides the donor JSON's zero-level placeholders. Info is a detached copy.
        info.hitpoints=200;info.attack=info.strength=info.defence=info.ranged=info.magic=100;
        info.poison_immunity=info.venom_immunity=true;info.attack_ticks=6;
        int spawn=NearRealityRaidAssets.animation(8098),death=NearRealityRaidAssets.animation(8097);
        if(spawn>=0)info.spawn_animation=spawn;if(death>=0)info.death_animation=death;
    }
    void spawnReds(Actor a){shieldUntil=a.ticks+9;a.animate(8117);for(NPC crab:redCrabs)if(!crab.isRemoved()&&crab.getHp()>0){a.entity.incrementHp(crab.getHp());crab.remove();}redCrabs.clear();for(int x:new int[]{3174,3162})redCrabs.add(a.child(8385,"Nylocas Matomenos",x,4312,redCrabHitpoints(a.run.party.members.size()),new Behavior(){}));}
    void spawnPurple(Actor a){Player p=a.target();if(p==null)return;Position pos=p.getPosition().copy();a.projectile(pos,1586,54,26,3);a.later(3,()->{a.child(8384,"Nylocas Athanatos",a.roomX(pos),a.roomY(pos),100,new Behavior(){boolean poisoned;@Override void incoming(Actor c,Hit h){if(h.type==HitType.POISON||h.type==HitType.VENOM)poisoned=true;else if(h.attacker!=null&&h.attacker.player!=null){Player attacker=h.attacker.player;io.ruin.model.item.Item weapon=attacker.getEquipment().get(3);io.ruin.model.item.Item ammo=attacker.getEquipment().get(13);if(weapon!=null&&weapon.getDef().name.toLowerCase(Locale.ROOT).contains("(p")||ammo!=null&&ammo.getDef().name.toLowerCase(Locale.ROOT).contains("(p"))poisoned=true;else h.damage=0;}}@Override void tick(Actor c){if(phase!=2){c.entity.remove();return;}if(++c.ticks>=50){a.entity.incrementHp(c.entity.getHp());c.entity.remove();return;}if(c.ticks%4==0){if(poisoned){a.entity.hit(new Hit(HitType.POISON).fixedDamage(c.random(70,75)));c.entity.remove();}else a.entity.incrementHp(c.random(9,11));}}});if(a.run.valid(p)&&p.getPosition().equals(pos))a.damage(p,0,78);});}
    void spawnChasers(Actor a){int[] ids={8381,8382,8353};String[] names={"Nylocas Ischyros","Nylocas Toxobolos","Nylocas Hagios"};for(Player target:a.players()){final int i=a.random(0,2);int sx=a.run.sourceX(target)+(a.run.sourceX(target)<a.x()?-2:2),sy=a.run.sourceY(target);a.child(ids[i],names[i],sx,sy,50,new Behavior(){boolean exploded;@Override void incoming(Actor c,Hit h){boolean correct=i==0?h.attackStyle!=null&&h.attackStyle.isMelee():i==1?h.attackStyle!=null&&h.attackStyle.isRanged():h.attackStyle==AttackStyle.MAGIC;if(!correct)TheatreCombat.reject(h);}@Override void tick(Actor c){if(!a.run.valid(target)){c.entity.remove();return;}c.move(c.entity,a.run.sourceX(target),a.run.sourceY(target));if(++c.ticks>=25||c.entity.getPosition().isWithinDistance(target.getPosition(),2))explode(c);}@Override void died(Actor c){explode(c);}void explode(Actor c){if(exploded)return;exploded=true;for(Player q:a.players()){int distance=Math.max(Math.abs(q.getAbsX()-c.entity.getAbsX()),Math.abs(q.getAbsY()-c.entity.getAbsY()));if(distance<4){int damage=new int[]{63,26,8,0}[Math.max(0,distance-1)];a.damage(q,damage,damage);}}c.entity.remove();}});}}
    void spawnTornado(Actor a,Player target){a.child(8386,"Tornado",a.x(),a.y()-3,100,new Behavior(){@Override boolean attackable(Actor c,Player p){return false;}@Override void tick(Actor c){if(!a.run.valid(target)){c.entity.remove();return;}c.move(c.entity,a.run.sourceX(target),a.run.sourceY(target));if(c.entity.getPosition().equals(target.getPosition())){int damage=Math.min(99,target.getHp())/2;a.damage(target,damage,damage);a.entity.incrementHp(damage);a.gfx(target,1602);c.entity.remove();a.later(15,()->{if(a.run.valid(target))spawnTornado(a,target);});}}});}
    void castPassive(Actor a,int spell){
        if(spell==0){spawnChasers(a);return;}
        if(spell==1){a.animate(8127);pauseUntil=a.ticks+35;for(int i=0;i<25;i++){final int index=i;a.later(i+3,()->{List<Player> targets=a.players();if(targets.isEmpty())return;Player p=targets.get((index/3)%targets.size());Position tile=p.getPosition().copy();a.projectile(tile,1601,132,0,2);a.later(2,()->spawnWeb(a,tile));});}return;}
        if(spell==2){a.animate(8126);pauseUntil=a.ticks+13;Set<Position> dots=new HashSet<>();for(Player p:a.players()){Position tile;do{tile=a.tile(a.random(3155,3181),a.random(4303,4322));}while(!dots.add(tile));a.floorGfx(1595,tile);}a.later(13,()->{Set<Position> used=new HashSet<>();for(Player p:a.players()){if(dots.contains(p.getPosition())&&used.add(p.getPosition()))a.gfx(p,1597);else{a.gfx(p,1600);a.damage(p,80,80);}}});return;}
        a.animate(8125);Player first=a.players().isEmpty()?null:a.players().get(0);if(first!=null)meteor(a,first,0);
    }
    void meteor(Actor a,Player p,int bounce){if(!a.run.valid(p))return;a.projectile(p,1598,48,28,4);a.later(4,()->{if(!a.run.valid(p))return;Player next=a.players().stream().filter(q->q!=p&&q.getPosition().isWithinDistance(p.getPosition(),2)).findFirst().orElse(null);if(next==null){a.gfx(p,1600);a.damage(p,AttackStyle.MAGIC,30,30,1,1.);}else{a.damage(p,AttackStyle.MAGIC,0,15,1,1.);if(bounce<3)meteor(a,next,bounce+1);}});}
    void spawnWeb(Actor a,Position tile){GameObject obj=a.object(32734,"",a.roomX(tile),a.roomY(tile),10);a.child(8376,"Web",a.roomX(tile),a.roomY(tile),10,new Behavior(){int ticks;@Override void incoming(Actor c,Hit h){obj.remove();c.entity.remove();}@Override void tick(Actor c){if(++ticks==12){for(Player p:a.players())if(p.getPosition().equals(tile)){a.gfx(p,1600);a.damage(p,0,40);}obj.remove();c.entity.remove();}}});}
}
