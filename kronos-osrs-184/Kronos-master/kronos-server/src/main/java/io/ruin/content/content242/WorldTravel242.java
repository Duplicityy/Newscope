package io.ruin.content.content242;

import com.google.gson.*;
import io.ruin.Server;
import io.ruin.cache.AnimDef;
import io.ruin.model.entity.player.Player;
import io.ruin.model.entity.shared.LockType;
import io.ruin.model.inter.dialogue.OptionsDialogue;
import io.ruin.model.inter.utils.Option;
import io.ruin.model.map.Position;
import io.ruin.model.map.Region;
import io.ruin.model.map.Tile;
import io.ruin.model.map.object.GameObject;
import io.ruin.model.map.object.actions.ObjectAction;
import java.util.*;

/** Native portal menus and captured floor transitions in additive242 regions. */
public final class WorldTravel242 {
    private static List<Destination> destinations=Collections.emptyList();
    private WorldTravel242(){}

    static void initialize(JsonObject root){
        List<Destination> places=new ArrayList<>();
        List<Runnable> registrations=new ArrayList<>();
        int rejected=0;
        if(root.has("destinations"))for(JsonElement element:World242.array(root,"destinations",1000)){
            JsonObject row=element.getAsJsonObject();
            String name=row.get("name").getAsString();
            if(name.isEmpty()||name.length()>80||name.matches(".*[<>\\p{Cntrl}].*"))
                throw new IllegalArgumentException("Invalid242 destination name");
            Position tile=point(row.getAsJsonArray("tile"));
            if(!walkable(tile)){rejected++;continue;}
            places.add(new Destination(name,tile));
        }
        if(root.has("objectLinks"))for(JsonElement element:World242.array(root,"objectLinks",10000)){
            JsonObject row=element.getAsJsonObject();
            Position origin=point(row.getAsJsonArray("origin"));
            int id=World242.integer(row.get("object"),0,65534),option=World242.integer(row.get("option"),1,5);
            int animation=World242.integer(row.get("animation"),-1,65534);
            int ticks=World242.integer(row.get("ticks"),0,20),animationTick=World242.integer(row.get("animationTick"),0,ticks);
            if(animation!=-1&&AnimDef.get(animation)==null)throw new IllegalArgumentException("Unknown242 traversal animation");
            List<Route> routes=new ArrayList<>();
            for(JsonElement routeElement:World242.array(row,"routes",16)){
                JsonObject route=routeElement.getAsJsonObject();
                Position from=route.has("from")?point(route.getAsJsonArray("from")):null;
                Position to=point(route.getAsJsonArray("to"));
                if(walkable(to))routes.add(new Route(from,to));
            }
            GameObject object=Tile.getObject(id,origin.getX(),origin.getY(),origin.getZ());
            if(object==null||object.getDef().getOption(row.get("optionName").getAsString())!=option||routes.isEmpty()){
                rejected++;continue;
            }
            // A position-specific registration cannot replace another region's handlers.
            registrations.add(()->ObjectAction.register(object,option,
                (player,clicked)->traverse(player,routes,animation,animationTick,ticks)));
        }
        registrations.forEach(Runnable::run);
        destinations=Collections.unmodifiableList(places);
        Server.println("Content242 travel: "+places.size()+" destinations, "+registrations.size()+
            " object transitions; "+rejected+" unavailable/blocked entries excluded.");
    }

    public static boolean available(){return Content242Registry.current().feature("world")&&(!destinations.isEmpty()
        ||io.ruin.content.content242.bosses.Scurrius242.available()||io.ruin.content.content242.bosses.Amoxliatl242.available()
        ||io.ruin.content.content242.bosses.PhantomMuspah242.available());}
    public static void open(Player player){
        if(!available()){player.sendMessage("The new lands are not available on this world.");return;}
        List<Option> options=new ArrayList<>();
        if(!destinations.isEmpty())options.add(new Option("Explore towns and islands",p->openPlaces(p,0)));
        if(io.ruin.content.content242.bosses.Scurrius242.available())
            options.add(new Option("Scurrius (private encounter)",io.ruin.content.content242.bosses.Scurrius242::open));
        if(io.ruin.content.content242.bosses.Amoxliatl242.available())
            options.add(new Option("Amoxliatl (private encounter)",io.ruin.content.content242.bosses.Amoxliatl242::open));
        if(io.ruin.content.content242.bosses.PhantomMuspah242.available())
            options.add(new Option("Phantom Muspah (private encounter)",io.ruin.content.content242.bosses.PhantomMuspah242::open));
        player.dialogue(new OptionsDialogue("New lands",options));
    }
    private static void openPlaces(Player player,int offset){
        if(!available()){player.sendMessage("The new lands are not available on this world.");return;}
        List<Option> options=new ArrayList<>();
        for(int i=offset;i<Math.min(offset+3,destinations.size());i++){
            Destination place=destinations.get(i);
            options.add(new Option(place.name,p->teleport(p,place.tile)));
        }
        if(offset+3<destinations.size())options.add(new Option("Next places",p->openPlaces(p,offset+3)));
        if(offset>0)options.add(new Option("Previous places",p->openPlaces(p,Math.max(0,offset-3))));
        player.dialogue(new OptionsDialogue("Explore the new lands",options));
    }
    private static void teleport(Player player,Position point){
        if(player.wildernessLevel!=0||player.pvpAttackZone){
            player.sendMessage("You cannot use this teleport from a PvP area.");return;
        }
        if(!walkable(point)){player.sendMessage("This destination is currently blocked.");return;}
        // The same checks as ordinary command teleports: activity restrictions, locks and teleblock.
        player.getMovement().startTeleport(event->{
            player.animate(3864);player.graphics(1039);player.privateSound(200,0,10);
            event.delay(2);
            player.getMovement().teleport(point.getX(),point.getY(),point.getZ());
        });
    }
    private static void traverse(Player player,List<Route> routes,int animation,int animationTick,int ticks){
        if(player.isLocked())return;
        Route closest=null;int distance=Integer.MAX_VALUE;
        for(Route route:routes){
            if(route.from==null){closest=route;break;}
            if(route.from.getZ()!=player.getHeight())continue;
            int d=Math.max(Math.abs(player.getAbsX()-route.from.getX()),Math.abs(player.getAbsY()-route.from.getY()));
            if(d<distance){closest=route;distance=d;}
        }
        if(closest==null||(closest.from!=null&&distance>4)||!walkable(closest.to)){
            player.sendMessage("You cannot use this passage from here.");return;
        }
        Position destination=closest.to;
        player.startEvent(event->{
            player.lock(LockType.FULL_DELAY_DAMAGE);
            try{
                if(animationTick>0)event.delay(animationTick);
                if(animation!=-1)player.animate(animation);
                if(ticks>animationTick)event.delay(ticks-animationTick);
                player.getMovement().teleport(destination.getX(),destination.getY(),destination.getZ());
            }finally{player.resetAnimation();player.unlock();}
        });
    }
    static Position point(JsonArray value){
        if(value==null||value.size()!=3)throw new IllegalArgumentException("Invalid242 position");
        return new Position(World242.integer(value.get(0),0,6399),World242.integer(value.get(1),0,16383),
            World242.integer(value.get(2),0,3));
    }
    private static boolean walkable(Position point){
        Region region=Region.get(point.getX(),point.getY());
        if(region==null||region.empty||Server.fileStore.get(5).getArchiveId(
                "m"+(point.getX()>>6)+"_"+(point.getY()>>6))==-1)return false;
        Tile tile=Tile.get(point);
        return tile==null||tile.isFloorFreeCheckDecor();
    }
    private static final class Destination{
        final String name;final Position tile;
        Destination(String name,Position tile){this.name=name;this.tile=tile;}
    }
    private static final class Route{
        final Position from,to;
        Route(Position from,Position to){this.from=from;this.to=to;}
    }
}
