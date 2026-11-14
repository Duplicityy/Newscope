package io.ruin.content.content242.bosses;
import io.ruin.api.utils.Random;
import io.ruin.model.combat.*;
import io.ruin.model.entity.npc.NPCCombat;
import io.ruin.model.entity.shared.listeners.HitListener;
import io.ruin.model.skills.prayer.Prayer;
public final class GiantRat242 extends NPCCombat {
    Boss242Combat owner;
    private boolean owned(io.ruin.model.entity.player.Player player){return owner!=null&&owner.children.contains(npc)&&owner.valid(player);}
    private void guard(Hit hit){if(hit.attacker==null||!owned(hit.attacker.player))Boss242Combat.reject(hit);}
    @Override public void init(){npc.attackNpcListener=(player,actor,message)->owned(player);npc.hitListener=new HitListener().preDefend(hit->{guard(hit);if(!hit.isNullified())hit.ignoreDefence().fixedDamage(npc.getHp());})
        .preDamage(this::guard)
        .preTargetDamage((hit,victim)->{if(owner==null||!owner.children.contains(npc)||!owner.valid(victim.player))hit.damage=0;});}
    @Override public void follow(){follow(1);}
    @Override public boolean attack(){if(!withinDistance(1))return false;npc.animate(info.attack_animation);
        Hit hit=new Hit(npc,AttackStyle.STAB,AttackType.ACCURATE).randDamage(4).ignorePrayer().delay(0);
        hit.preDamage(victim->{if(victim.player!=null&&victim.player.getPrayer().isActive(Prayer.PROTECT_FROM_MELEE))hit.damage=Math.min(1,hit.damage);});target.hit(hit);return true;
    }
}
