package io.ruin.content.content242;
import com.google.gson.*;
import io.ruin.model.item.Item;
import java.util.*;
import java.util.function.DoubleSupplier;

/** Detached native loot semantics; no server lifecycle, accounts or cache writes. */
public final class World242Checks {
    static int checks;
    static void check(boolean value,String reason){checks++;if(!value)throw new AssertionError(reason);}
    static JsonArray source(String text){return new JsonParser().parse(text).getAsJsonArray();}
    static World242.ModernLoot table(String text){return World242.ModernLoot.parse(source(text),id->id==995||id==30000);}
    static DoubleSupplier draws(double... values){return new DoubleSupplier(){int at;public double getAsDouble(){if(at>=values.length)throw new AssertionError("Unexpected random roll");return values[at++];}};}
    static void rejected(Runnable action){try{action.run();throw new AssertionError("Invalid input accepted");}catch(IllegalArgumentException expected){checks++;}}
    public static void main(String[]args){
        String row="[{\"denominator\":100,\"guaranteed\":[{\"id\":995,\"min\":2,\"max\":2}],\"main\":[{\"id\":30000,\"min\":1,\"max\":1,\"weight\":40}],\"independent\":[{\"id\":995,\"min\":5,\"max\":5,\"weight\":1,\"denominator\":128}]}]";
        World242.ModernLoot loot=table(row);
        List<Item> result=loot.roll(true,draws(0.39,0));
        check(result.size()==3,"guaranteed/main/independent rewards all present");
        check(result.get(0).getAmount()==2&&result.get(1).getId()==30000&&result.get(2).getAmount()==5,"native rewards correct");
        check(loot.roll(false,draws(0.4,0.5)).isEmpty(),"unmapped weight remains empty, guaranteed flag honoured");
        check(loot.roll(true,draws(0.99,0.5)).size()==1,"guaranteed drop survives empty main roll");
        int successes=0;
        for(int i=0;i<1000;i++)if(!loot.roll(false,draws(i/1000.0,0.5)).isEmpty())successes++;
        check(successes==400,"exact deterministic40percent main distribution");
        successes=0;
        for(int i=0;i<128;i++)if(!loot.roll(false,draws(0.99,i/128.0)).isEmpty())successes++;
        check(successes==1,"tertiary1/128 stays independent");
        check(loot.getLootItems().size()==3,"native drop catalogue exposes all entries");
        String range="[{\"denominator\":1,\"guaranteed\":[],\"main\":[{\"id\":995,\"min\":1,\"max\":2147483647,\"weight\":1}],\"independent\":[]}]";
        check(table(range).roll(false,draws(0,0)).get(0).getAmount()==1,"quantity lower bound");
        check(table(range).roll(false,draws(0,Math.nextDown(1.0))).get(0).getAmount()==Integer.MAX_VALUE,"quantity upper bound without integer overflow");
        rejected(()->table(row.replace("\"weight\":40","\"weight\":101")));
        rejected(()->table(row.replace("\"id\":30000","\"id\":12345")));
        rejected(()->table(row.replace("\"max\":2","\"max\":1")));
        rejected(()->table(row.replace("\"denominator\":128","\"denominator\":0")));
        rejected(()->loot.roll(false,draws(Double.NaN)));
        rejected(()->loot.roll(false,draws(1)));
        String nested="[{\"denominator\":2,\"guaranteed\":[],\"independent\":[],\"main\":[{\"weight\":1,\"rollCounts\":[{\"rolls\":1,\"weight\":1},{\"rolls\":3,\"weight\":1}],\"table\":{\"denominator\":4,\"guaranteed\":[],\"independent\":[],\"main\":[{\"id\":995,\"min\":7,\"max\":7,\"weight\":1}]}}]}]";
        World242.ModernLoot shared=table(nested);
        check(shared.roll(false,draws(0.5)).isEmpty(),"shared table only rolled after its parent wins");
        check(shared.roll(false,draws(0,0,0)).get(0).getAmount()==7,"one shared roll selects native item");
        check(shared.roll(false,draws(0,0.5,0,0.25,0)).size()==2,"weighted multiple shared rolls preserve empty child outcomes");
        check(shared.getLootItems().size()==1,"shared leaf appears in native catalogue");
        rejected(()->table(nested.replace("\"rolls\":3","\"rolls\":9")));
        rejected(()->table(nested.replace("\"rolls\":3,\"weight\":1","\"rolls\":3,\"weight\":2147483647")));
        System.out.println("PASS World242 native loot checks="+checks+"; no live server attached");
    }
}
