package io.ruin.model.entity.player.autonomous;

import io.ruin.data.impl.teleports;
import io.ruin.model.World;
import io.ruin.model.entity.npc.NPC;
import io.ruin.model.map.Position;
import io.ruin.model.map.object.GameObject;
import io.ruin.model.skills.magic.spells.HomeTeleport;
import java.util.*;

/** Shared physical reservations, not one occupancy counter per brain. */
final class TrainingSites {
    // Resource anchors stay inside their catalogued site. Actual object routes
    // can finish outside that anchor (object width + adjacent interaction tile),
    // or take a short detour around clipping. This is presence-only permission.
    static final int MOVEMENT_MARGIN = 8;
    static final class Site {
        final String name; final int x,y; final int radius;
        Site(String name,int x,int y,int radius) {this.name=name;this.x=x;this.y=y;this.radius=radius;}
        boolean contains(int px,int py,int pz) {return pz==0 && Math.abs(px-x)<=radius && Math.abs(py-y)<=radius;}
    }
    static final List<Site> sites = Arrays.asList(
        new Site("Home",2030,3577,55),new Site("Ardougne",2662,3307,55),
        new Site("Draynor Village",3103,3249,55),new Site("Varrock",3214,3424,75),
        new Site("Lumbridge",3225,3219,70),new Site("Seers' Village",2710,3485,55),
        new Site("Catherby",2804,3435,55),new Site("Falador",2965,3379,65),
        new Site("Al Kharid",3299,3197,75),new Site("Rellekka",2631,3676,55));
    private static final Map<String,GameObject> claims = new HashMap<>();
    private static final Map<Long,List<Hazard>> hazards = new HashMap<>();
    private static final Map<Site,Position> cityLandings = new HashMap<>();
    private static final class Hazard {
        final int x,y,z,radius,aggressionLevel;
        Hazard(NPC npc) {
            x=npc.spawnPosition.getX();y=npc.spawnPosition.getY();z=npc.spawnPosition.getZ();
            radius=Math.max(0,npc.walkRange)+npc.getCombat().getAggressionRange()+Math.max(1,npc.getSize())+2;
            int configured=npc.getCombat().getInfo().aggressive_level;
            aggressionLevel=npc.wildernessSpawnLevel>0 ? Integer.MAX_VALUE :
                    configured==-1 ? npc.getDef().combatLevel*2 : configured==0 ? Integer.MAX_VALUE : configured;
        }
        boolean threatens(int px,int py,int pz,int level) {
            return pz==z && level<=aggressionLevel && Math.max(Math.abs(px-x),Math.abs(py-y))<=radius;
        }
    }
    private static long cell(int x,int y) {return ((long)Math.floorDiv(x,16)<<32) ^ (Math.floorDiv(y,16)&0xffffffffL);}

    /** Startup-only spawn catalogue. Resource decisions inspect a tiny local bucket. */
    static void prepareSafetyCatalogue() {
        hazards.clear();
        cityLandings.clear();
        // Same first free city entry the existing menu would use, not an
        // approximate site centre. Menu discovery is done only at startup.
        for(Site site:sites) if(!site.name.equals("Home"))
            for(teleports.Category category:teleports.CATEGORIES)
                for(teleports.Subcategory sub:category.subcategories)
                    for(teleports.Teleport teleport:sub.teleports)
                        if(teleport.name.equals(site.name) && teleport.price==0 && teleport.z==0)
                            cityLandings.putIfAbsent(site,new Position(teleport.x,teleport.y,teleport.z));
        for(NPC npc:World.npcs.entityList) {
            if(npc==null || npc.spawnPosition==null || npc.getCombat()==null || npc.getCombat().getInfo()==null || !npc.getCombat().isAggressive())continue;
            Hazard hazard=new Hazard(npc);
            if(hazard.z!=0)continue;
            // Populate only cells covering the configured training areas, even if
            // an unusual NPC has an extremely broad movement/aggression radius.
            Set<Long> inserted=new HashSet<>();
            for(Site site:sites) {
                int left=Math.max(site.x-site.radius,hazard.x-hazard.radius);
                int right=Math.min(site.x+site.radius,hazard.x+hazard.radius);
                int bottom=Math.max(site.y-site.radius,hazard.y-hazard.radius);
                int top=Math.min(site.y+site.radius,hazard.y+hazard.radius);
                if(left>right || bottom>top)continue;
                for(int cx=Math.floorDiv(left,16);cx<=Math.floorDiv(right,16);cx++)
                    for(int cy=Math.floorDiv(bottom,16);cy<=Math.floorDiv(top,16);cy++) {
                        long key=cell(cx*16,cy*16);
                        if(inserted.add(key))hazards.computeIfAbsent(key,ignored->new ArrayList<>()).add(hazard);
                    }
            }
        }
    }
    static boolean safeToWork(Adventurer p,int x,int y,int z) {
        if(at(x,y,z)==null || p.memory.avoidsLocation(x,y,z))return false;
        List<Hazard> bucket=hazards.get(cell(x,y));
        if(bucket!=null)for(Hazard hazard:bucket)
            if(hazard.threatens(x,y,z,p.getCombat().getLevel()))return false;
        return true;
    }
    static Site at(int x,int y,int z) {for(Site s:sites)if(s.contains(x,y,z))return s;return null;}
    static boolean allowsPresence(int x,int y,int z) {
        if(z!=0)return false;
        for(Site site:sites)if(Math.abs(x-site.x)<=site.radius+MOVEMENT_MARGIN
                && Math.abs(y-site.y)<=site.radius+MOVEMENT_MARGIN)return true;
        return false;
    }
    static boolean available(Adventurer p,GameObject obj) {
        if(!safeToWork(p,obj.x,obj.y,obj.z) || !canTravelTo(p,obj.x,obj.y,obj.z))return false;
        for(Map.Entry<String,GameObject> e:claims.entrySet()) {
            if(e.getKey().equals(p.actorId))continue;
            GameObject other=e.getValue();
            if(obj.z==other.z && Math.max(Math.abs(obj.x-other.x),Math.abs(obj.y-other.y))<12)return false;
        }
        return true;
    }
    static boolean claim(Adventurer p,GameObject obj) {if(!available(p,obj))return false;claims.put(p.actorId,obj);return true;}
    static void release(Adventurer p) {claims.remove(p.actorId);}
    static int preference(Adventurer p,GameObject obj) {
        Site s=at(obj.x,obj.y,obj.z); if(s==null)return 100000;
        int preferred=Math.floorMod(Integer.parseInt(p.actorId)-1,sites.size());
        return Math.floorMod(sites.indexOf(s)-preferred,sites.size())*10000;
    }
    private static boolean withinWalkingRange(Adventurer p,int x,int y,int z) {
        return p.getHeight()==z && Math.max(Math.abs(p.getAbsX()-x),Math.abs(p.getAbsY()-y))<=70;
    }
    static Position landingFor(Adventurer p,Site site) {
        if(site==null)return null;
        if(site.name.equals("Home")) {
            Position override=HomeTeleport.getHomeTeleportOverride(p);
            return override!=null ? override : p.edgeHome ? World.EDGEHOME : World.HOME;
        }
        return cityLandings.get(site);
    }
    /** Arrival safety is separate from destination safety and physical route safety. */
    static boolean canTravelTo(Adventurer p,int x,int y,int z) {
        Site site=at(x,y,z);
        if(site==null)return false;
        if(withinWalkingRange(p,x,y,z))return true;
        Position landing=landingFor(p,site);
        return landing!=null && safeToWork(p,landing.getX(),landing.getY(),landing.getZ());
    }
    /** True means stop/wait, including refusal; false alone permits a local route. */
    static boolean travel(Adventurer p,int x,int y,int z) {
        if(!canTravelTo(p,x,y,z))return true;
        if(withinWalkingRange(p,x,y,z))return false;
        Site site=at(x,y,z);
        if(site.name.equals("Home")) {HomeTeleport.MODERN.clickAction.accept(p,0);return true;}
        Position landing=landingFor(p,site);
        teleports.teleport(p,landing.getX(),landing.getY(),landing.getZ());
        return true;
    }
}
