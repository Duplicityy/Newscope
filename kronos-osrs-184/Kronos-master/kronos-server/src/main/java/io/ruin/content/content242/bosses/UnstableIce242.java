package io.ruin.content.content242.bosses;
import io.ruin.model.combat.*;
import io.ruin.model.entity.npc.NPCCombat;
import io.ruin.model.entity.shared.listeners.HitListener;
public final class UnstableIce242 extends NPCCombat {
    Amoxliatl242 owner;
    private boolean owned(io.ruin.model.entity.player.Player player){return owner!=null&&owner.children.contains(npc)&&owner.valid(player);}
    private void guard(Hit hit){if(hit.attacker==null||!owned(hit.attacker.player))Boss242Combat.reject(hit);}
    @Override public void init(){setAllowRetaliate(false);npc.attackNpcListener=(player,actor,message)->owned(player);npc.hitListener=new HitListener().preDefend(hit->{
        guard(hit);if(hit.isNullified())return;
        if(hit.attackStyle==null||!hit.attackStyle.isMelee()||hit.attacker==null||hit.attacker.player==null){hit.block();return;}
        hit.ignoreDefence().fixedDamage(npc.getHp());
    }).preDamage(this::guard);}
    @Override public void follow(){}
    @Override public boolean attack(){return false;}
    @Override public void startDeath(Hit hit){if(owner!=null)owner.broken(npc);super.startDeath(hit);}
}
