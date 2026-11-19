package io.ruin.model.activities.dungeoneering;

import io.ruin.model.entity.player.Player;
import io.ruin.model.inter.InterfaceType;
import io.ruin.model.inter.dialogue.YesNoDialogue;
import io.ruin.model.inter.handlers.OptionScroll;
import io.ruin.model.inter.utils.Option;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/** Native configuration windows, shared by the Windows and Android clients. */
public final class IDungeonInterface {
    private IDungeonInterface() {}

    public static void open(Player p) {
        if (!IDungeon.owner(p)) { p.sendMessage("iDungeon is available only to the owner."); return; }
        IDungeon.stop(p,"Configuration opened; manual control restored",true);
        home(p);
    }

    private static void home(Player p) {
        if (!IDungeon.owner(p)) return;
        IDungeonSettings c=IDungeon.settings(p);
        List<Option> rows=new ArrayList<>();
        rows.add(new Option("Status: "+IDungeon.status(p),()->home(p)));
        rows.add(new Option("START iDungeon",()->{
            if (!valid(p,c)) return;
            p.closeInterface(InterfaceType.MAIN);
            IDungeon.start(p);
        }));
        rows.add(new Option("STOP iDungeon",()->{if(valid(p,c)){IDungeon.stop(p,"Stopped by owner",true);home(p);}}));
        rows.add(new Option("Stop and leave dungeon safely",()->{if(valid(p,c))IDungeon.leave(p);}));
        rows.add(new Option("Floors / complexity / size",()->floors(p,c)));
        rows.add(new Option("Combat and survival",()->combat(p,c)));
        rows.add(new Option("Loot and equipment",()->loot(p,c)));
        rows.add(new Option("Limits and stop conditions",()->limits(p,c)));
        rows.add(new Option("Save settings",()->{
            if(!valid(p,c))return;
            if(p.isLocked()){p.sendMessage("Wait for the current action before saving settings.");return;}
            Dungeoneering.save(p,()->{p.sendMessage("iDungeon preferences saved.");home(p);});
        }));
        rows.add(new Option("Restore default settings",()->{
            if(!valid(p,c))return;
            p.dialogue(new YesNoDialogue("Reset iDungeon preferences?","Your XP, tokens, bindings and equipment stay saved.",995,1,()->{
                if(!valid(p,c))return;
                IDungeon.stop(p,"Preferences reset",false);
                Dungeoneering.progress(p).idungeon=new IDungeonSettings();home(p);
            }));
        }));
        rows.add(new Option("Help / commands",()->p.sendScroll("Owner iDungeon",
                "::idungopen - configuration", "::idung start / stop / status / leave",
                "Starting uses normal solo dungeon entry and the selected floor range.",
                "Settings open returns manual control. Stop leaves the live dungeon to you.",
                "Leave uses the normal exit and original-equipment restoration.",
                "Carry or bind the equipment you prefer; the bot also collects dungeon supplies.",
                "Camera and chat can be used while observing. Manual gameplay stops automation.",
                "Settings are saved normally; automation never resumes automatically on login.")));
        show(p,"Owner iDungeon Pro - Configuration",rows);
    }

    private static void floors(Player p,IDungeonSettings c) {
        if(!valid(p,c))return;
        List<Option> r=new ArrayList<>();
        r.add(new Option("First floor: "+c.firstFloor,()->numbers(p,c,"First floor",1,60,n->{c.firstFloor=n;if(c.lastFloor<n)c.lastFloor=n;},()->floors(p,c))));
        r.add(new Option("Last floor: "+c.lastFloor,()->numbers(p,c,"Last floor",c.firstFloor,60,n->c.lastFloor=n,()->floors(p,c))));
        r.add(new Option("Complexity: "+c.complexity,()->numbers(p,c,"Complexity",1,6,n->c.complexity=n,()->floors(p,c))));
        r.add(new Option("Size: "+new String[]{"Small","Medium","Large"}[c.size],()->choices(p,c,"Dungeon size",new String[]{"Small","Medium","Large"},n->c.size=n,()->floors(p,c))));
        r.add(toggle(p,c,"Repeat floors",c.repeat,()->c.repeat=!c.repeat,()->floors(p,c)));
        r.add(toggle(p,c,"Reset prestige when range is complete",c.prestige,()->c.prestige=!c.prestige,()->floors(p,c)));
        r.add(toggle(p,c,"Explore bonus rooms",c.explore,()->c.explore=!c.explore,()->floors(p,c)));
        r.add(toggle(p,c,"Rush early floors",c.rush,()->c.rush=!c.rush,()->floors(p,c)));
        r.add(new Option("Rush through floor: "+c.rushThrough,()->numbers(p,c,"Rush through",1,60,n->c.rushThrough=n,()->floors(p,c))));
        r.add(new Option("Rush complexity: "+c.rushComplexity,()->numbers(p,c,"Rush complexity",1,6,n->c.rushComplexity=n,()->floors(p,c))));
        r.add(new Option("Native unlocked floor: "+Dungeoneering.progress(p).unlockedFloor(),()->floors(p,c)));
        back(p,r);show(p,"iDungeon - Floors",r);
    }

    private static void combat(Player p,IDungeonSettings c) {
        if(!valid(p,c))return;List<Option> r=new ArrayList<>();
        r.add(new Option("Preferred combat: "+c.combat,()->choices(p,c,"Preferred combat",new String[]{"AUTO","MELEE","RANGED","MAGIC"},n->c.combat=new String[]{"AUTO","MELEE","RANGED","MAGIC"}[n],()->combat(p,c))));
        r.add(toggle(p,c,"Switch styles for mechanics",c.switchStyles,()->c.switchStyles=!c.switchStyles,()->combat(p,c)));
        r.add(toggle(p,c,"Use protection prayers",c.prayer,()->c.prayer=!c.prayer,()->combat(p,c)));
        r.add(toggle(p,c,"Eat automatically",c.autoEat,()->c.autoEat=!c.autoEat,()->combat(p,c)));
        r.add(new Option("Eat below: "+c.eatPercent+"% HP",()->values(p,c,"Eat threshold",new int[]{25,35,45,55,65,75,85},n->c.eatPercent=n,()->combat(p,c))));
        r.add(toggle(p,c,"Catch / cook replacement food",c.cookFood,()->c.cookFood=!c.cookFood,()->combat(p,c)));
        back(p,r);show(p,"iDungeon - Combat and survival",r);
    }

    private static void loot(Player p,IDungeonSettings c) {
        if(!valid(p,c))return;List<Option> r=new ArrayList<>();
        r.add(toggle(p,c,"Collect food",c.lootFood,()->c.lootFood=!c.lootFood,()->loot(p,c)));
        r.add(toggle(p,c,"Collect runes",c.lootRunes,()->c.lootRunes=!c.lootRunes,()->loot(p,c)));
        r.add(toggle(p,c,"Collect rusty coins",c.lootCoins,()->c.lootCoins=!c.lootCoins,()->loot(p,c)));
        r.add(toggle(p,c,"Collect ammunition",c.lootAmmo,()->c.lootAmmo=!c.lootAmmo,()->loot(p,c)));
        r.add(toggle(p,c,"Collect / equip upgrades",c.upgradeGear,()->c.upgradeGear=!c.upgradeGear,()->loot(p,c)));
        r.add(toggle(p,c,"Bury collected bones",c.buryBones,()->c.buryBones=!c.buryBones,()->loot(p,c)));
        r.add(new Option("Shared keys and puzzle items are collected when required",()->loot(p,c)));
        back(p,r);show(p,"iDungeon - Loot and equipment",r);
    }

    private static void limits(Player p,IDungeonSettings c) {
        if(!valid(p,c))return;List<Option> r=new ArrayList<>();
        r.add(toggle(p,c,"Stop on a dungeon death",c.stopOnDeath,()->c.stopOnDeath=!c.stopOnDeath,()->limits(p,c)));
        r.add(new Option("Additional deaths allowed: "+c.maximumDeaths,()->numbers(p,c,"Death limit",0,5,n->c.maximumDeaths=n,()->limits(p,c))));
        r.add(new Option("Room progress timeout: "+c.roomTimeout+" ticks",()->values(p,c,"Room timeout",new int[]{200,400,600,1000,1600,2400},n->c.roomTimeout=n,()->limits(p,c))));
        r.add(new Option("Session limit: "+(c.maximumMinutes==0?"Off":c.maximumMinutes+" minutes"),()->values(p,c,"Session minutes (0 = off)",new int[]{0,15,30,60,120,240,480},n->c.maximumMinutes=n,()->limits(p,c))));
        r.add(new Option("Floor limit: "+(c.maximumFloors==0?"Off":c.maximumFloors),()->values(p,c,"Completed floors (0 = off)",new int[]{0,1,5,10,25,50,100,1000},n->c.maximumFloors=n,()->limits(p,c))));
        back(p,r);show(p,"iDungeon - Limits",r);
    }

    private static boolean valid(Player p,IDungeonSettings c) {return IDungeon.owner(p)&&p.isOnline()&&IDungeon.settings(p)==c;}
    private static Option toggle(Player p,IDungeonSettings c,String label,boolean on,Runnable change,Runnable back) {
        return new Option((on?"[ON] ":"[OFF] ")+label,()->{if(valid(p,c)){change.run();c.normalize();back.run();}});
    }
    private static void numbers(Player p,IDungeonSettings c,String title,int first,int last,IntConsumer set,Runnable back) {
        int[] values=new int[last-first+1];for(int i=0;i<values.length;i++)values[i]=first+i;
        values(p,c,title,values,set,back);
    }
    private static void values(Player p,IDungeonSettings c,String title,int[] values,IntConsumer set,Runnable back) {
        if(!valid(p,c))return;List<Option> r=new ArrayList<>();
        for(int value:values)r.add(new Option(Integer.toString(value),()->{if(valid(p,c)){set.accept(value);c.normalize();back.run();}}));
        r.add(new Option("Back",back));show(p,"iDungeon - "+title,r);
    }
    private static void choices(Player p,IDungeonSettings c,String title,String[] labels,IntConsumer set,Runnable back) {
        if(!valid(p,c))return;List<Option> r=new ArrayList<>();
        for(int i=0;i<labels.length;i++){final int n=i;r.add(new Option(labels[i],()->{if(valid(p,c)){set.accept(n);c.normalize();back.run();}}));}
        r.add(new Option("Back",back));show(p,"iDungeon - "+title,r);
    }
    private static void back(Player p,List<Option> rows) {rows.add(new Option("Back to configuration",()->home(p)));}
    private static void show(Player p,String title,List<Option> rows) {if(IDungeon.owner(p))OptionScroll.open(p,title,false,rows);}
}
