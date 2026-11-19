package io.ruin.model.activities.raids.toa.port;

import io.ruin.model.activities.raids.NearRealityRaidAssets;
import io.ruin.model.item.Item;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/** RewardEncounter.java tables from Near Reality f577de7 (Savions), using native verified items. */
final class NativeToaRewards {
    private static final class Pair{
        final int source,divisor;final String name;
        Pair(int id,String name,int divisor){source=id;this.name=name;this.divisor=divisor;}
        int id(){int id=NearRealityRaidAssets.item(source,name);if(id<0)throw new IllegalStateException("Missing ToA reward "+source+" "+name);return id;}
    }
    private static final Pair[] COMMON={
        new Pair(995,"Coins",1),new Pair(560,"Death rune",20),new Pair(566,"Soul rune",40),new Pair(444,"Gold ore",90),
        new Pair(11232,"Dragon dart tip",100),new Pair(6332,"Mahogany logs",100),new Pair(1607,"Sapphire",200),
        new Pair(1605,"Emerald",250),new Pair(2357,"Gold bar",250),new Pair(3138,"Potato cactus",250),new Pair(383,"Raw shark",250),
        new Pair(1603,"Ruby",300),new Pair(1601,"Diamond",400),new Pair(389,"Raw manta ray",450),new Pair(6016,"Cactus spine",600),
        new Pair(1615,"Dragonstone",600),new Pair(1391,"Battlestaff",1100),new Pair(5935,"Coconut milk",1100),
        new Pair(27272,"Lily of the sands",1100),new Pair(5296,"Toadflax seed",1400),new Pair(5295,"Ranarr seed",1800),
        new Pair(5304,"Torstol seed",2200),new Pair(5300,"Snapdragon seed",2200),new Pair(1149,"Dragon med helm",4000),
        new Pair(5316,"Magic seed",6500),new Pair(26390,"Blood essence",7500)
    };
    private static final Pair FANG=new Pair(26219,"Osmumten's fang",1),LIGHTBEARER=new Pair(25975,"Lightbearer",1),
        WARD=new Pair(25985,"Elidinis' ward",1),MASK=new Pair(27226,"Masori mask",1),BODY=new Pair(27229,"Masori body",1),
        CHAPS=new Pair(27232,"Masori chaps",1),SHADOW=new Pair(27277,"Tumeken's shadow",1),
        THREAD=new Pair(27279,"Thread of Elidinis",1),SCARAB=new Pair(27283,"Breach of the scarab",1),
        SUN=new Pair(27289,"Jewel of the sun",1),CORRUPTOR=new Pair(27285,"Eye of the corruptor",1),
        CLUE=new Pair(12073,"Clue scroll",1),DUNG=new Pair(27216,"Fossilised dung",1);
    static List<String> missingAssets(){
        List<String> out=new ArrayList<>();List<Pair> all=new ArrayList<>(Arrays.asList(COMMON));
        all.addAll(Arrays.asList(FANG,LIGHTBEARER,WARD,MASK,BODY,CHAPS,SHADOW,THREAD,SCARAB,SUN,CORRUPTOR,CLUE,DUNG));
        for(Pair p:all)if(NearRealityRaidAssets.item(p.source,p.name)<0)out.add("reward item "+p.source+" "+p.name);
        return out;
    }
    static Item[] roll(int points,int raidLevel){return roll(points,raidLevel,ThreadLocalRandom.current());}
    static Item[] roll(int points,int raidLevel,java.util.Random random){
        Map<Integer,Integer> loot=new LinkedHashMap<>();
        int score=Math.max(0,Math.min(64000,points));
        if(score<1500){add(loot,DUNG,1);return items(loot);}
        double modifier=2;
        try{modifier=Double.parseDouble(System.getProperty("newscape.toa.uniqueMultiplier","2.0"));}catch(NumberFormatException ignored){}
        Pair unique=null;
        if(random.nextDouble()<ToaRewardMath.uniqueProbability(score,raidLevel,modifier)){
            if(random.nextInt(7)<=3){if(raidLevel>=50||random.nextInt(50)==0)unique=random.nextBoolean()?FANG:LIGHTBEARER;}
            else if(raidLevel>=150||random.nextInt(50)==0){int n=random.nextInt(11);unique=n<3?WARD:n<5?MASK:n<7?BODY:n<9?CHAPS:SHADOW;}
        }
        if(unique!=null)add(loot,unique,1);
        else{
            List<Pair> eligible=new ArrayList<>();for(Pair p:COMMON)if(score>=p.divisor)eligible.add(p);
            for(int i=0;i<3;i++){Pair p=eligible.get(random.nextInt(eligible.size()));add(loot,p,(int)(Math.floor((double)score/p.divisor)*ToaRewardMath.quantityFactor(raidLevel)));}
        }
        if(loot.size()<6&&random.nextInt(15)==0)add(loot,THREAD,1);
        if(loot.size()<6&&random.nextInt(20)==0)add(loot,new Pair[]{SCARAB,SUN,CORRUPTOR}[random.nextInt(3)],1);
        if(loot.size()<6&&random.nextInt(26)==0)add(loot,CLUE,1);
        return items(loot);
    }
    private static void add(Map<Integer,Integer> out,Pair p,int amount){int id=p.id();long total=(long)out.getOrDefault(id,0)+Math.max(1,amount);out.put(id,(int)Math.min(Integer.MAX_VALUE,total));}
    private static Item[] items(Map<Integer,Integer> out){List<Item> items=new ArrayList<>();for(Map.Entry<Integer,Integer> e:out.entrySet())items.add(new Item(e.getKey(),e.getValue()));return items.toArray(new Item[0]);}
}
