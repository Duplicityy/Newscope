package io.ruin.model.activities.hollowdepths;

import com.newscape.dungeons.ContentCatalog;
import com.newscape.dungeons.DungeonPorts.NpcTicket;
import io.ruin.api.utils.JsonUtils;
import io.ruin.data.impl.npcs.npc_combat;
import io.ruin.model.combat.AttackStyle;
import io.ruin.model.entity.npc.NPCCombat;
import io.ruin.model.map.Projectile;

/** Per-instance native combat, using audited184 skins and the run's frozen stats. */
public final class HollowDepthsCombat extends NPCCombat {
    // These native184 effects are already used by InfernalMage and DeathlyRanger.
    private static final Projectile MAGIC = new Projectile(130,43,31,51,56,10,16,64);
    private static final Projectile RANGED = Projectile.arrow(1384)[0];
    private final NpcTicket ticket;
    private final ContentCatalog.NpcDef profile;
    private final npc_combat.Info scaled;
    private int mixedCycle;

    public HollowDepthsCombat(NpcTicket ticket,ContentCatalog.NpcDef profile,npc_combat.Info actualAssetInfo){
        if(ticket==null||profile==null||actualAssetInfo==null||!ticket.archetypeKey.equals(profile.key))
            throw new IllegalArgumentException("Audited matching dungeon combat assets required");
        if(actualAssetInfo.attack_animation<0||actualAssetInfo.death_animation<0)
            throw new IllegalArgumentException("Audited native attack/death animations required");
        this.ticket=ticket;this.profile=profile;
        // Never edit NPCDef.combatInfo: multiple ordinary NPCs may share that object.
        this.scaled=JsonUtils.GSON_EXPOSE.fromJson(JsonUtils.GSON_EXPOSE.toJson(actualAssetInfo),npc_combat.Info.class);
        scaled.hitpoints=ticket.stats.hp;scaled.max_damage=ticket.stats.maxHit;scaled.attack_ticks=ticket.stats.attackTicks;
        scaled.aggressive_level=Integer.MAX_VALUE;scaled.handler=null;scaled.pet=null;
        scaled.slayer_level=0;scaled.slayer_xp=0;scaled.slayer_tasks=new String[0];scaled.slayerTasks=null;
        scaled.random_drop_count=0;scaled.local_loot=false;
        scaled.attack_style=profile.style==ContentCatalog.Style.MAGIC?AttackStyle.MAGIC:
            profile.style==ContentCatalog.Style.RANGED?AttackStyle.RANGED:
            actualAssetInfo.attack_style!=null&&actualAssetInfo.attack_style.isMelee()?actualAssetInfo.attack_style:AttackStyle.CRUSH;
    }

    public npc_combat.Info scaledInfo(){return scaled;}
    public NpcTicket ticket(){return ticket;}

    @Override public void init(){
        updateInfo(scaled);
        setAllowRespawn(false);
        setAllowRetaliate(true);
    }
    @Override public void follow(){follow(profile.attackRange);}
    @Override public int getAggressionRange(){return Math.max(4,profile.attackRange);}
    @Override public boolean attack(){
        if(!withinDistance(profile.attackRange))return false;
        AttackStyle style=scaled.attack_style;
        if(profile.style==ContentCatalog.Style.MIXED){
            int step=mixedCycle++%3;
            style=step==0&&withinDistance(1)?AttackStyle.CRUSH:step==1?AttackStyle.RANGED:AttackStyle.MAGIC;
        }
        if(style.isMelee()){
            if(!withinDistance(1))return false;
            basicAttack(scaled.attack_animation,style,scaled.max_damage);
        }else{
            projectileAttack(style.isMagic()?MAGIC:RANGED,scaled.attack_animation,style,scaled.max_damage);
        }
        return true;
    }
}
