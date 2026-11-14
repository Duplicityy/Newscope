package io.ruin.content.content242.bosses;

import io.ruin.model.map.*;
import io.ruin.model.map.route.routes.ProjectileRoute;
import io.ruin.model.stat.Stat;
import java.nio.file.*;
import java.util.*;

/** Deterministic phase histories plus real native stat drains/tile clipping, without a server. */
public final class Muspah242Regression {
    static int checks;
    static void check(boolean condition,String label){checks++;if(!condition)throw new AssertionError(label);}
    public static void main(String[] args)throws Exception{int exit=1;try{
        for(boolean ranged:new boolean[]{true,false}){
            MuspahState242 state=new MuspahState242(ranged);int threshold=ranged?100:80;
            state.damage(850,threshold-1,false,false);check(!state.switchDue(),"No early form switch");state.damage(700,1,false,false);check(state.switchDue(),"First threshold accepts fewer than4hits");
            state.switched();threshold=ranged?80:100;state.damage(700,threshold,false,false);check(!state.switchDue(),"Subsequent form requires4damaging hits");
            state.damage(600,0,false,false);check(state.switchHits==1,"Misses excluded from switch count");for(int i=0;i<3;i++)state.damage(600,1,false,false);check(state.switchDue(),"Subsequent switch combines damage andhitcount");
            check(state.specialIsClouds()==ranged,"Initial form chooses special order");state.specials++;check(state.specialIsClouds()!=ranged,"Second special alternates");
        }
        MuspahState242 state=new MuspahState242(true);
        check(!state.specialDue(638)&&state.specialDue(637),"First special637threshold");state.specials=1;check(!state.specialDue(426)&&state.specialDue(425),"Second special425threshold");
        check(!state.waveDue(127)&&state.waveDue(126),"Shockwave below127threshold");
        MuspahState242.Damage lethal=state.damage(120,150,false,false);check(lethal.dealt==119&&state.skipRestore==120,"Pre-shield lethal leaves1HP");
        state.damage(1,0,false,false);check(state.skipRestore==120,"Zero hit does not cancel shield skip");MuspahState242.Damage extra=state.damage(1,3,false,false);
        check(extra.dealt==0&&extra.restore==120&&state.skipRestore==0,"Extra positive hit cancels skip and restores capturedHP");
        state.damage(120,150,false,false);state.raiseShield();check(state.form==MuspahState242.Form.FINAL&&state.shieldDone&&state.shield==0,"Clean pending skip reaches final with no shield");
        check(state.damage(1,20,false,false).dealt==20,"Final phase permits ordinary death");
        state=new MuspahState242(false);state.raiseShield();check(state.shield==75&&state.form==MuspahState242.Form.SHIELDED,"Normal shield has75points");
        check(state.damage(126,100,false,false).dealt==0&&state.shield==75,"Regular damage cannot hurtHP orshield");
        state.damage(126,19,true,false);check(state.shield==71,"Smite floors quarter damage");state.damage(126,0,false,true);check(state.shield==38,"Sapphire proc drains33 independently of accuracy");
        state.drain(2);check(state.shield==36,"Boss own attack drains2");state.damage(126,400,true,true);check(state.shield==0,"Shield drain caps atzero");state.breakShield();check(state.shieldDone&&state.form==MuspahState242.Form.FINAL,"Shield break activates final form");
        for(int hp=1;hp<=850;hp+=7)for(int hit=1;hit<=150;hit+=11){MuspahState242 s=new MuspahState242(true);MuspahState242.Damage d=s.damage(hp,hit,false,false);check(d.dealt<hp,"Any pre-shield lethal is capped");if(hit<hp)check(d.dealt==hit,"Nonlethal damage unchanged");}
        check(MuspahState242.freezeTicks(20)==13&&MuspahState242.freezeTicks(32)==21,"Freeze resistance rounded to native ticks");
        check(MuspahState242.corruptionDrain(3,1)+MuspahState242.corruptionDrain(3,2)+MuspahState242.corruptionDrain(3,3)==18,"Magic corruption total18prayer");
        check(MuspahState242.corruptionDrain(4,1)+MuspahState242.corruptionDrain(4,2)+MuspahState242.corruptionDrain(4,3)==24,"Shockwave corruption total24prayer");
        Stat ordinary=new Stat(200);MuspahDefence242 protectedStat=new MuspahDefence242(200,200);
        ordinary.drain(.30);protectedStat.drain(.30);check(ordinary.currentLevel==140&&protectedStat.currentLevel==140,"Initial native percentage drain");
        ordinary.drain(.30);protectedStat.drain(.30);check(ordinary.currentLevel==80&&protectedStat.currentLevel==125,"Only instance-owned stat has125floor");
        int remainder=150-protectedStat.drain(150);Stat strength=new Stat(250);if(remainder>0)strength.drain(remainder);check(strength.currentLevel==250&&protectedStat.currentLevel==125,"Native BGS drain loop cannot spill past floor");
        protectedStat.restore();check(protectedStat.currentLevel==200,"Shield restore resetsDefence");
        int animated=0;for(int[] p:MuspahArena242.tiles())if(MuspahArena242.kind(p[0],p[1])!=' ')animated++;
        check(animated==433,"All433captured shockwave tiles retained");check(MuspahArena242.floor(2847,4258)&&MuspahArena242.floor(2858,4265)&&MuspahArena242.floor(2838,4255),"Captured centre/edge floor samples");
        check(!MuspahArena242.sheltered(2857,4258,(x,y)->false),"Open shockwave ray unsheltered");
        check(MuspahArena242.sheltered(2857,4258,(x,y)->x==2852&&y==4258),"Intervening spike shelters player");
        check(!MuspahArena242.sheltered(2857,4258,(x,y)->x==2852&&y==4259),"Off-ray spike does not shelter");
        Region region=new Region(Region.getId(3201,3200));region.dynamicIndex=991;Region.LOADED[region.id]=region;
        PhantomMuspah242 combat=new PhantomMuspah242();Position spike=new Position(3201,3200,0);Tile tile=Tile.get(spike,true);tile.clipping=0x800000;tile.projectileClipping=0x800000;
        check(ProjectileRoute.allow(3200,3200,0,3202,3200),"Native initial projectile route open");combat.spikes.block(spike);combat.spikes.block(spike);
        check(!tile.isFloorFree()&&!ProjectileRoute.allow(3200,3200,0,3202,3200),"Actual movement andnativeprojectile masks blockspike");
        combat.spikes.clear();combat.spikes.clear();check(tile.clipping==0x800000&&tile.projectileClipping==0x800000&&ProjectileRoute.allow(3200,3200,0,3202,3200),"Idempotent cleanup restores exact originalmask andLOS");
        Boss242Regression.Actor owner=Boss242Regression.actor(),stranger=Boss242Regression.actor();Boss242Regression.Target target=new Boss242Regression.Target();
        java.lang.reflect.Field actor=io.ruin.model.entity.npc.NPCCombat.class.getDeclaredField("npc");actor.setAccessible(true);actor.set(combat,target);
        combat.state=new MuspahState242(true);combat.dx=3201-2847;combat.dy=3201-4258;combat.plane=0;combat.lease=991;combat.ready=true;combat.tick=10;combat.bindOwner(owner);
        check(combat.allowsBurn(owner)&&!combat.allowsBurn(stranger),"Actual Muspah StatusTarget allows only instance owner");
        combat.state.raiseShield();check(combat.beforeBurn(5)==0&&combat.state.shield==75&&target.hp==100,"Actual Muspah burn bridge cannot damageHP orprayer shield");
        combat.state=new MuspahState242(true);check(combat.beforeBurn(150)==99&&combat.state.skipRestore==100,"Actual Muspah burn bridge caps pre-shield lethal");
        target.hp=1;check(combat.beforeBurn(1)==0&&target.hp==100&&combat.state.skipRestore==0,"Further native burn cancels pending shield skip safely");
        owner.at=new Position(3220,3201,0);check(!combat.allowsBurn(owner)&&combat.beforeBurn(5)==0,"Owner leaving arena invalidates pending burn admission/completion");
        String report="PASS Muspah mechanics regression assertions="+checks+"; phase histories, shield/skip boundaries, native stat drain and exact native clipping/LOS cleanup. No live/staged process launched.";
        Files.write(Paths.get(args[0]),report.getBytes("UTF-8"));System.out.println(report);exit=0;
    }catch(Throwable failure){failure.printStackTrace();}finally{System.exit(exit);}}
}
