package io.ruin.content.content242;

import io.ruin.Server;
import io.ruin.cache.*;
import io.ruin.model.combat.*;
import io.ruin.model.combat.special.melee.*;
import io.ruin.model.entity.Entity;
import io.ruin.model.entity.npc.NPC;
import io.ruin.model.entity.player.Player;
import io.ruin.model.entity.player.PlayerCombat;
import io.ruin.model.entity.player.PlayerPrayer;
import io.ruin.model.inter.handlers.EquipmentStats;
import io.ruin.model.skills.magic.spells.ancient.BloodBarrage;
import io.ruin.model.stat.StatType;
import io.ruin.model.entity.shared.listeners.HitListener;
import io.ruin.model.item.Item;
import io.ruin.model.item.ItemContainer;
import io.ruin.model.item.containers.Equipment;
import java.nio.file.*;
import java.util.*;

/** Detached actual Hit.defend + real item-listener checks; never boots a server or account. */
public final class ModernGearRegression {
    private static int checks;
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    private static final String MANIFEST="{\"enabled\":true,\"schemaVersion\":2,\"sourceRevision\":242,\"clientProtocol\":184,\"mechanics\":{\"enabled\":true,\"features\":{\"fang\":true,\"voidwaker\":true,\"inquisitor\":true,\"soulreaper\":true,\"virtus\":true,\"ancient_sceptre\":true}},\"mappings\":{\"items\":{\"26219\":40000,\"27246\":40001,\"27690\":40002,\"24419\":40003,\"24420\":40004,\"24421\":40005,\"28338\":40006,\"26241\":40007,\"26243\":40008,\"26245\":40009,\"27624\":40010}}}";
    private static final class Target extends NPC {
        private static final NPCDef DEF=new NPCDef();
        Target(){super(0);}
        public NPCDef getDef(){return DEF;}
        public int getHp(){return 10000;}
    }
    private static final class Wielder extends Player {
        Equipment equipment;
        PlayerCombat combat;
        PlayerPrayer prayer;
        int healed;
        public Equipment getEquipment(){return equipment;}
        public PlayerCombat getCombat(){return combat;}
        public PlayerPrayer getPrayer(){return prayer;}
        public int getHp(){return 100;}
        public int incrementHp(int value){healed+=value;return 100;}
    }
    private static final class RolledHit extends Hit {
        final double attack,defence;
        RolledHit(AttackStyle style,double attack,double defence){super(new Target(),style,AttackType.ACCURATE);this.attack=attack;this.defence=defence;}
        public double getAttackBonus(){return attack;}
        public double getDefenceBonus(Entity ignored){return defence;}
    }
    private static ItemDef definition(int id,String name,int slot){ItemDef d=new ItemDef();d.id=id;d.name=name;d.equipSlot=slot;ItemDef.cached.put(id,d);return d;}
    private static void cases()throws Exception{
        Content242Registry r=Content242Registry.parse(MANIFEST);
        check(r.feature("fang")&&r.resolve("items",26219)==40000,"explicit remapped target");
        check(r.resolve("animations",11222)==-1&&!r.matches("items",26219,26219),"no source-ID fallback");
        for(String bad:Arrays.asList("{}",MANIFEST.replace("242","241"),MANIFEST.replace("184","242"),MANIFEST.replace("40001","40000"),MANIFEST.replace("40000","65535"),MANIFEST.replace("\"fang\":true","\"fang\":\"true\"")))check(!Content242Registry.parse(bad).feature("fang"),"invalid manifest disabled");
        int[] calls={0};int[] rolls={6,5,7};
        check(ModernGearRules.fangAccurate(10,10,false,bound->{calls[0]++;return rolls[calls[0]-1];})&&calls[0]==3,"second attack faces same outside-ToA defence roll");
        calls[0]=0;int[] toa={6,5,7,8};
        check(!ModernGearRules.fangAccurate(10,10,true,bound->toa[calls[0]++])&&calls[0]==4,"ToA second defence is independently rolled");
        definition(40000,"Osmumten's fang",3);definition(40001,"Osmumten's fang (or)",3);definition(40002,"Voidwaker",3);
        definition(40003,"Inquisitor's great helm",0);definition(40004,"Inquisitor's hauberk",4);definition(40005,"Inquisitor's plateskirt",7);
        definition(40006,"Soulreaper axe",3);definition(40007,"Virtus mask",0);definition(40008,"Virtus robe top",4);definition(40009,"Virtus robe bottom",7);definition(40010,"Ancient sceptre",3);
        ModernGear.initialize();ModernGear.initialize();
        // Player's ordinary constructor boots unrelated interface registries. Detached fixture only:
        java.lang.reflect.Field unsafeField=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");unsafeField.setAccessible(true);
        Wielder p=(Wielder)((sun.misc.Unsafe)unsafeField.get(null)).allocateInstance(Wielder.class);p.equipment=new Equipment();p.equipment.init(14,false);
        p.player=p;p.prayer=(PlayerPrayer)((sun.misc.Unsafe)unsafeField.get(null)).allocateInstance(PlayerPrayer.class);p.combat=new PlayerCombat(){public double getLevel(StatType type){return 99;}public double getBonus(int type){return p.equipment.bonuses[type];}};
        p.equipment.set(3,new Item(40000));Target t=new Target();
        for(int i=0;i<256;i++){
            Hit h=new RolledHit(AttackStyle.STAB,100000000,0).randDamage(28);
            ModernGear.prepareFang(p,h,false);check(h.defend(t),"native defend accepted");
            check(!h.isBlocked()&&h.damage>=4&&h.damage<=24,"Fang normal native range");
            Hit special=new RolledHit(AttackStyle.STAB,100000000,0).randDamage(28);ModernGear.prepareFang(p,special,true);special.defend(t);
            check(special.damage>=4&&special.damage<=28,"Fang special native range");
        }
        Hit fixed=new RolledHit(AttackStyle.STAB,0,10000).fixedDamage(17).ignoreDefence();ModernGear.prepareFang(p,fixed,false);fixed.defend(t);
        check(fixed.hasFixedDamage()&&fixed.damage==17,"explicit fixed17 is preserved");
        Hit slash=new RolledHit(AttackStyle.SLASH,0,100000).randDamage(28);ModernGear.prepareFang(p,slash,false);slash.defend(t);
        check(slash.isBlocked()&&slash.damage==0&&!slash.defenceIgnored,"slash retains native accuracy");
        Hit blocked=new RolledHit(AttackStyle.STAB,100000,0).randDamage(28).block();ModernGear.prepareFang(p,blocked,false);blocked.defend(t);check(blocked.damage==0,"blocked damage not restored");
        Hit rejected=new RolledHit(AttackStyle.STAB,100000,0).randDamage(28).nullify();ModernGear.prepareFang(p,rejected,false);check(!rejected.defend(t),"nullified launch not restored");
        t.hitListener=new HitListener().postDefend(h->h.damage=0);
        Hit prayed=new RolledHit(AttackStyle.STAB,100000000,0).randDamage(28);ModernGear.prepareFang(p,prayed,false);prayed.defend(t);check(prayed.damage==0,"target post-defend protection remains last");t.hitListener=null;
        for(int mask=0;mask<8;mask++){
            p.equipment.set(0,(mask&1)!=0?new Item(40003):null);p.equipment.set(4,(mask&2)!=0?new Item(40004):null);p.equipment.set(7,(mask&4)!=0?new Item(40005):null);
            Hit h=new Hit().boostAttack(.2).boostDamage(.15);h.attackStyle=AttackStyle.CRUSH;
            for(Item item:p.equipment.getItems())if(item!=null)item.getDef().preTargetDefend(p,item,h,t);
            double bonus=ModernGearRules.inquisitorBonus((mask&1)!=0,(mask&2)!=0,(mask&4)!=0);
            check(Math.abs(h.getAttackBoost()-(.2+1.2*bonus))<1e-9,"Inquisitor aggregate accuracy subset"+mask);
            check(Math.abs(h.getDamageBoost()-(.15+1.15*bonus))<1e-9,"Inquisitor aggregate damage subset"+mask);
        }
        Hit nonCrush=new Hit();nonCrush.attackStyle=AttackStyle.STAB;for(Item item:p.equipment.getItems())if(item!=null)item.getDef().preTargetDefend(p,item,nonCrush,t);
        check(nonCrush.getDamageBoost()==0&&nonCrush.getAttackBoost()==0,"Inquisitor excludes noncrush");
        check(new Content242Fang().getDrainAmount()==25&&new Content242Voidwaker().getDrainAmount()==50,"native special energy costs");
        check(!new Content242Fang().accept(ItemDef.get(40000),"Osmumten's fang")&&!new Content242Voidwaker().accept(ItemDef.get(40002),"Voidwaker"),"missing mapped visuals suppress specials");
        for(int i=0;i<100;i++){
            Hit h=ModernGear.voidwakerHit(null,AttackStyle.SLASH,AttackType.AGGRESSIVE,41,ItemDef.get(40002));h.defend(t);
            check(h.damage>=20&&h.damage<=61&&!h.isBlocked()&&h.attackStyle==AttackStyle.MAGIC,"Voidwaker guaranteed native magic bounds");
        }
        Hit boosted=ModernGear.voidwakerHit(null,AttackStyle.SLASH,AttackType.AGGRESSIVE,40,ItemDef.get(40002)).boostDamage(.2);boosted.defend(t);
        check(boosted.maxDamage==48&&boosted.damage>=24&&boosted.damage<=72,"Voidwaker bounds include native melee max boosts");
        check(ModernChargedWeapons.shadowMax(99,10,false)==44&&ModernChargedWeapons.shadowMax(99,10,true)==47,"Shadow triple/quadruple equipment damage");
        check(ModernChargedWeapons.shadowMax(99,50,false)==68,"Shadow damage multiplier capped at100percent");
        definition(41000,"Soul rune",-1).stackable=true;definition(41001,"Chaos rune",-1).stackable=true;
        ItemContainer bag=new ItemContainer();bag.init(4,false);Item staff=new Item(40002);bag.set(0,staff);bag.set(1,new Item(41000,21));bag.set(2,new Item(41001,52));
        check(ModernCharges.pay(bag,staff,0,41000,2,41001,5)==10,"Shadow charges affordable whole batches");
        check(ModernCharges.get(staff,0)==10&&ModernCharges.plainAmount(bag,41000)==1&&ModernCharges.plainAmount(bag,41001)==2,"Exact rune payment");
        check(ModernCharges.refund(bag,staff,41000,2,41001,5)&&ModernCharges.get(staff,0)==0&&ModernCharges.plainAmount(bag,41000)==21,"Exact rune refund");
        ModernCharges.set(staff,19999);check(ModernCharges.pay(bag,staff,0,41000,2,41001,5)==1&&ModernCharges.get(staff,0)==20000,"Charge cap payment");
        Item attributed=new Item(41000,100);attributed.putAttribute("fixture",true);bag.set(1,attributed);check(ModernCharges.plainAmount(bag,41000)==0,"Attributed payment stacks excluded");
        bag.set(2,new Item(40003));bag.set(3,new Item(40004));ModernCharges.set(staff,5);
        check(!ModernCharges.refund(bag,staff,41000,2,41001,5)&&ModernCharges.get(staff,0)==5,"Full inventory refund leaves charges intact");
        Item reload=new Item(staff.getId(),staff.getAmount(),staff.copyOfAttributes());check(ModernCharges.get(reload,0)==5,"Charges survive native attribute copy used by banking and serialization");
        ModernSoulreaper.State state=new ModernSoulreaper.State();for(int i=0;i<8;i++)state.gain();check(state.count==5&&state.decay==50,"Soulreaper five-stack cap");
        for(int i=0;i<49;i++)state.pulse();check(state.count==5,"Soulreaper retains stacks for49ticks");state.pulse();check(state.count==4&&state.decay==50,"Soulreaper loses one at50ticks");
        state.gain();p.content242Soulreaper=state;p.equipment.set(3,new Item(40006));p.equipment.bonuses[EquipmentStats.MELEE_STRENGTH]=125;p.prayer.strengthBoost=.23;
        check(Math.abs(ModernSoulreaper.extraStrength(p)-.30)<1e-9,"Soulreaper additive Strength bonus");int boostedMaximum=CombatUtils.getMaxDamage(p,AttackStyle.SLASH,AttackType.AGGRESSIVE);
        p.equipment.set(3,new Item(40002));check(ModernSoulreaper.extraStrength(p)==0&&CombatUtils.getMaxDamage(p,AttackStyle.SLASH,AttackType.AGGRESSIVE)<boostedMaximum,"Switching weapon removes all axe Strength effects");
        p.equipment.set(3,new Item(40006));Hit behead=ModernSoulreaper.special(p,AttackType.AGGRESSIVE,40,ItemDef.get(40006)).ignoreDefence();behead.defend(t);
        check(behead.maxDamage==52&&behead.damage>=15&&behead.damage<=52&&Math.abs(behead.getAttackBoost()-.6)<1e-9,"Behead five-stack accuracy/max/min bounds");
        Item axe=p.equipment.get(3);state.consume();Hit miss=new Hit().block();miss.attackStyle=AttackStyle.SLASH;miss.setAttackWeapon(axe.getDef());axe.getDef().postTargetDefend(p,axe,miss,t);
        check(state.count==1,"Normal axe miss builds exactly one stack despite initialize twice");
        com.google.gson.Gson json=new com.google.gson.GsonBuilder().excludeFieldsWithoutExposeAnnotation().create();ModernSoulreaper.State saved=json.fromJson(json.toJson(state),ModernSoulreaper.State.class);check(saved.count==1&&saved.decay==50,"Soulreaper native exposed state survives serialization");
        p.equipment.set(0,new Item(40007));p.equipment.set(4,new Item(40008));p.equipment.set(7,new Item(40009));BloodBarrage barrage=new BloodBarrage();
        check(ModernAncients.extraMagicDamage(p,barrage)==9,"Virtus additional Ancient damage is3percent per piece");
        p.equipment.set(3,new Item(40010));Hit blood=new Hit(p).setAttackSpell(barrage);blood.damage=40;check(ModernAncients.afterHit(blood,t)&&p.healed==11,"Sceptre blood healing uses27.5percent");
        check(ModernAncients.iceTicks(0)==8&&ModernAncients.iceTicks(1)==17&&ModernAncients.iceTicks(2)==26&&ModernAncients.iceTicks(3)==35,"Sceptre exact ice tick rounding");
    }
    public static void main(String[] args)throws Exception{
        int code=1;try{Path folder=Paths.get(args[0]);Files.createDirectories(folder);Files.write(folder.resolve("content242.json"),MANIFEST.getBytes("UTF-8"));Server.dataFolder=folder.toFile();
            cases();String result="PASS modern gear native regression assertions="+checks+"; detached fixture metadata/actors, actual Hit.defend and ItemDef listeners; no server/client launched.";Files.write(folder.resolve("regression.txt"),Arrays.asList(result));System.out.println(result);code=0;
        }catch(Throwable failure){failure.printStackTrace();}finally{System.exit(code);}
    }
}
