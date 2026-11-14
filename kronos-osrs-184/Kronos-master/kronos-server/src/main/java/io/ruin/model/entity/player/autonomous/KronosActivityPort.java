package io.ruin.model.entity.player.autonomous;

import com.google.gson.*;
import io.ruin.Server;
import io.ruin.model.World;
import io.ruin.model.entity.npc.NPC;
import io.ruin.model.inter.Interface;
import io.ruin.model.item.Item;
import io.ruin.model.item.containers.Equipment;
import io.ruin.model.map.*;
import io.ruin.model.map.object.GameObject;
import io.ruin.model.shop.Shop;
import io.ruin.model.skills.BotPrevention;
import io.ruin.model.skills.fletching.LogCutting;
import io.ruin.model.skills.thieving.Stall;
import io.ruin.model.stat.*;
import io.ruin.network.incoming.handlers.*;
import rsps.sim.ProgressionBrain.*;
import java.util.*;

/** Revision-184 adapter. No XP, coin or item creation; only existing game actions. */
public final class KronosActivityPort implements Port {
    private static final List<GameObject> objects = new ArrayList<>();
    private static final List<NPC> npcs = new ArrayList<>();
    private static final Set<Integer> TREES = new HashSet<>(Arrays.asList(1276,1278,1286,1282,1383,1289,2091));
    private static final Set<String> SUPPORTED = new HashSet<>(Arrays.asList("prayer","thieving","woodcutting","fletching","mining","firemaking","fishing","cooking","attack","strength","defence","hitpoints"));
    private static final String SHOP = "e522b145-fbaa-4746-8d71-6ad9808d2338";
    private final Adventurer p;
    private Activity active;
    private GameObject lastResource;
    private NPC combatTarget;
    private String lastRouteKey;
    private final FishingCookingActivities fishingCooking;
    private static final Set<String> MELEE = new HashSet<>(Arrays.asList("attack","strength","defence","hitpoints"));
    private static final Set<Integer> ORES = new HashSet<>(Arrays.asList(11161,10943,11360,11361,11362,11363));
    private long nextAction;
    private String detail = "initializing";
    public long routes, slowestRouteNanos, bankTrips;

    KronosActivityPort(Adventurer p) { this.p = p; fishingCooking = new FishingCookingActivities(p, this); }

    /** Once at startup, bounded region catalog; decisions never scan the world. */
    static void prepareCatalogue() {
        for (TrainingSites.Site site : TrainingSites.sites) for (int x = site.x-site.radius; x <= site.x+site.radius; x++) for (int y = site.y-site.radius; y <= site.y+site.radius; y++) {
            Tile t = Tile.get(x, y, 0);
            if (t != null && t.gameObjects != null) for (GameObject o : t.gameObjects) if (TREES.contains(o.id) || option(o.getDef().options, "steal-from", "steal from", "bank", "mine") > 0) if (!objects.contains(o)) objects.add(o);
        }
        for (NPC n : World.npcs.entityList) if (n != null && inArea(n.getAbsX(), n.getAbsY(), n.getHeight())) npcs.add(n);
        FishingCookingActivities.prepareCatalogue();
    }
    static boolean inArea(int x, int y, int z) { return TrainingSites.at(x,y,z) != null; }
    private boolean legalPresence() {
        return p.wildernessLevel == 0 && !p.pvpAttackZone
                && TrainingSites.allowsPresence(p.getAbsX(),p.getAbsY(),p.getHeight());
    }

    public View observe() {
        if (p.quarantineReason != null) throw new IllegalStateException(p.quarantineReason);
        Map<String,Skill> skills = new LinkedHashMap<>();
        long marker = ((long)p.getAbsX() << 32) ^ p.getAbsY();
        for (StatType type : StatType.values()) {
            Stat s = p.getStats().get(type);
            skills.put(type.name().toLowerCase(Locale.ROOT), new Skill(s.fixedLevel, s.experience));
            marker += (long)s.experience;
        }
        boolean safe = !p.isLocked() && !p.hasActiveAction() && p.getMovement().isAtDestination()
                && !p.getCombat().isDead() && !p.getCombat().isDefending(17) && p.getCombat().getTarget() == null
                && p.wildernessLevel == 0 && !p.pvpAttackZone && !BotPrevention.isBlocked(p) && !p.safety.isBusy();
        return new View(skills, marker, safe, p.getCombat().isDead() || p.safety.needsRecovery() || !legalPresence());
    }

    public List<Activity> candidates(View view) {
        List<Activity> list = new ArrayList<>();
        if (p.policy.archetype != rsps.sim.BuildPolicy.Archetype.SKILLER && owned(526)) list.add(activity("prayer",1));
        if (findStall() != null) list.add(activity("thieving", 1));
        if (canObtainTool(1349) && findTree() != null) list.add(activity("woodcutting", 1));
        if (canObtainTool(1267) && findOre() != null) list.add(activity("mining", 1));
        if (owned(1511)) {
            if (canObtainTool(590)) list.add(activity("firemaking", 1));
            if (canObtainTool(946)) list.add(activity("fletching", 1));
        }
        if (fishingCooking.canCook() && (fishingCooking.rawCount(false)>0 || canReachBank())) list.add(activity("cooking", 2));
        if (fishingCooking.canFish() && canObtainTool(303,2891,Math.max(1,io.ruin.cache.ItemDef.get(303).highAlchValue))) list.add(activity("fishing", 1));
        if (p.policy.archetype != rsps.sim.BuildPolicy.Archetype.SKILLER && hasOwnedCombatSupplies() && findCombatTarget() != null)
            for (String skill : MELEE) list.add(new Activity("basic_"+skill, skill, MELEE, 1, 500));
        return list;
    }
    private Activity activity(String skill, double weight) { return new Activity("home_" + skill, skill, Collections.singleton(skill), weight, 400); }
    public boolean start(Activity activity, long tick) {
        if (!SUPPORTED.contains(activity.primarySkill) || !p.policy.permits(activity.possibleXpSkills) || p.isLocked()) return false;
        p.resetActions(true, true, true);
        active = activity; combatTarget = null; lastResource = null; lastRouteKey = null; nextAction = tick;
        return true;
    }

    public ActivityStatus poll(long tick) {
        if (active == null) return ActivityStatus.BLOCKED;
        if (p.safety.isBusy()) { detail = p.safety.description(); return ActivityStatus.RUNNING; }
        if (tick < nextAction || p.isLocked()) return ActivityStatus.RUNNING;
        if (BotPrevention.isBlocked(p)) {
            NPC guard = p.botPreventionNPC;
            int option = option(guard.getDef().options, "dismiss");
            if (option < 1) return ActivityStatus.BLOCKED;
            if (!p.hasActiveAction() && p.getMovement().isAtDestination()) interact(guard, option, tick);
            detail = "responding to guard";
            return ActivityStatus.RUNNING;
        }
        if (MELEE.contains(active.primarySkill)) return combat(tick);
        if (active.primarySkill.equals("cooking") && fishingCooking.handleCookingDialogue(tick)) return ActivityStatus.RUNNING;
        if (p.hasActiveAction() || !p.getMovement().isAtDestination()) return ActivityStatus.RUNNING;
        if (active.primarySkill.equals("fishing")) return fishingCooking.fish(tick);
        if (active.primarySkill.equals("cooking")) return fishingCooking.cook(tick);
        // Processing frees inventory space. Never bank the very inputs it needs.
        if (p.getInventory().getFreeSlots() < 2 && !isProcessing(active.primarySkill)) {
            bankSurplus(tick, -1);
            return ActivityStatus.RUNNING;
        }
        switch (active.primarySkill) {
            case "prayer": {
                if (!has(526)) {
                    if(p.getBank().getAmount(526)==0)return ActivityStatus.COMPLETE;
                    withdrawOwned(526, 3, tick); return ActivityStatus.RUNNING;
                }
                Item bones=p.getInventory().findItem(526);
                int opt=option(bones.getDef().inventoryOptions,"bury");
                if(opt<1 || bones.getDef().inventoryActions==null || bones.getDef().inventoryActions[opt-1]==null)return ActivityStatus.BLOCKED;
                bones.getDef().inventoryActions[opt-1].handle(p,bones);
                detail="burying earned bones";nextAction=tick+4;break;
            }
            case "thieving": {
                GameObject stall = findStall();
                if (stall == null) return ActivityStatus.BLOCKED;
                int option = option(stall.getDef().options, "steal-from", "steal from");
                if (option < 1) return ActivityStatus.BLOCKED;
                if (!TrainingSites.claim(p,stall)) return ActivityStatus.BLOCKED;
                detail = "stealing from " + stall.getDef().name;
                interact(stall, option, tick); break;
            }
            case "woodcutting": {
                if (!ensureTool(1349, tick)) return ActivityStatus.RUNNING;
                GameObject tree = findTree();
                if (tree == null) return ActivityStatus.BLOCKED;
                if (!TrainingSites.claim(p,tree)) return ActivityStatus.BLOCKED;
                detail = "cutting " + tree.getDef().name;
                interact(tree, option(tree.getDef().options, "chop down"), tick); break;
            }
                        case "mining": {
                if (!ensureTool(1267,tick)) return ActivityStatus.RUNNING;
                GameObject ore = findOre();
                if (ore == null || !TrainingSites.claim(p,ore)) return ActivityStatus.BLOCKED;
                detail = "mining " + ore.getDef().name;
                interact(ore, option(ore.getDef().options,"mine"),tick); break;
            }
            case "firemaking": {
                if (!ensureTool(590,tick)) return ActivityStatus.RUNNING;
                if (!has(1511)) {
                    if (!owned(1511)) return ActivityStatus.COMPLETE;
                    withdrawOwned(1511, 3, tick); return ActivityStatus.RUNNING;
                }
                if (!Tile.allowObjectPlacement(p.getPosition())) {
                    return moveToFireTile(tick) ? ActivityStatus.RUNNING : ActivityStatus.BLOCKED;
                }
                detail="lighting earned logs";
                io.ruin.model.item.actions.ItemItemAction.handleAction(p,p.getInventory().findItem(590),p.getInventory().findItem(1511));
                nextAction=tick+5;break;
            }
            case "fletching": {
                if (!ensureTool(946,tick)) return ActivityStatus.RUNNING;
                if (!has(1511)) {
                    if (p.getBank().getAmount(1511) == 0) return ActivityStatus.COMPLETE;
                    withdrawOwned(1511, 3, tick);
                    return ActivityStatus.RUNNING;
                }
                detail = "fletching earned logs";
                p.resetActions(true, true, true);
                LogCutting.cut(p, LogCutting.ARROW_SHAFT, 1);
                nextAction = tick + 4; break;
            }
            default: return ActivityStatus.BLOCKED;
        }
        return ActivityStatus.RUNNING;
    }

    private boolean has(int id) { return p.getInventory().contains(id, 1); }
    private boolean owned(int id) { return has(id) || (p.getBank().getAmount(id) > 0 && canReachBank()); }
    private boolean canObtainTool(int id) {
        return canObtainTool(id,2820,1000);
    }
    private boolean canObtainTool(int id,int supplierId,int minimumCoins) {
        return owned(id) || (canReachSupplier(supplierId)
                && (p.getInventory().getAmount(995)>=minimumCoins || (p.getBank().getAmount(995)>=minimumCoins && canReachBank())));
    }
    private boolean canReachSupplier(int id) {
        for(NPC npc:npcs) if(npc.getId()==id && !npc.isRemoved()
                && TrainingSites.safeToWork(p,npc.getAbsX(),npc.getAbsY(),npc.getHeight())
                && TrainingSites.canTravelTo(p,npc.getAbsX(),npc.getAbsY(),npc.getHeight()))return true;
        return false;
    }
    private boolean canReachBank() {
        if(p.isVisibleInterface(Interface.BANK))return true;
        for(GameObject object:objects) if(object.id>=0 && option(object.getDef().options,"bank")>0
                && TrainingSites.safeToWork(p,object.x,object.y,object.z) && TrainingSites.canTravelTo(p,object.x,object.y,object.z))return true;
        for(NPC npc:npcs) if(!npc.isRemoved() && option(npc.getDef().options,"bank")>0
                && TrainingSites.safeToWork(p,npc.getAbsX(),npc.getAbsY(),npc.getHeight())
                && TrainingSites.canTravelTo(p,npc.getAbsX(),npc.getAbsY(),npc.getHeight()))return true;
        return false;
    }
    static boolean isProcessing(String skill) { return skill.equals("prayer") || skill.equals("fletching") || skill.equals("firemaking"); }

    /** Existing bank UI actions; explicitly request items, never banknotes. */
    boolean withdrawOwned(int id, int option, long tick) {
        if (p.getBank().getAmount(id) <= 0) return false;
        if (p.getInventory().getFreeSlots() < 1) { bankSurplus(tick, processingInput()); return false; }
        if (!bank(tick)) return false;
        Item item = p.getBank().findItem(id);
        int before = p.getInventory().getAmount(id);
        ActionButtonHandler.handleAction(p, 1, (Interface.BANK << 16) | 22, -1, -1, false);
        ActionButtonHandler.handleAction(p, option, (Interface.BANK << 16) | 13, item.getSlot(), id, false);
        p.closeInterfaces(); defer(tick + 3, "withdrawing owned supplies");
        return p.getInventory().getAmount(id) > before;
    }
    private int processingInput() {
        if (active == null) return -1;
        return active.primarySkill.equals("prayer") ? 526 : isProcessing(active.primarySkill) ? 1511 : -1;
    }
    private boolean ensureTool(int id, long tick) {
        if (has(id)) return true;
        if (p.getBank().getAmount(id) > 0) { withdrawOwned(id, 2, tick); return false; }
        if (p.getInventory().getFreeSlots() < 1) { bankSurplus(tick, processingInput()); return false; }
        if (p.getInventory().getAmount(995) < 1000 && p.getBank().getAmount(995) > 0) {
            withdrawOwned(995, 7, tick); return false;
        }
        return buy(id, tick);
    }
    /** Bank gathered output, retaining a small real recovery reserve and owned tools. */
    boolean bankSurplus(long tick, int inputId) {
        if (!bank(tick)) return false;
        int foodKept = 0, inputsKept = 0;
        Set<Integer> retainedTools = new HashSet<>();
        for (Item item : p.getInventory().getItems().clone()) {
            if (item == null) continue;
            int id = item.getId(), keep = 0;
            if (id == 995) continue;
            if (id == 1349 || id == 1267 || id == 590 || id == 946 || id == 1323 || id == 303) {
                keep = retainedTools.add(id) ? 1 : 0;
            } else if (AdventurerSafety.isRecoveryItem(item)) {
                keep = Math.min(item.getAmount(), Math.max(0, 4 - foodKept)); foodKept += keep;
            } else if (id == inputId) {
                keep = Math.min(item.getAmount(), Math.max(0, 10 - inputsKept)); inputsKept += keep;
            }
            if (item.getAmount() > keep) p.getBank().deposit(item, item.getAmount() - keep, false);
        }
        bankTrips++; p.closeInterfaces(); defer(tick + 4, "banked surplus; retained tools and recovery supplies");
        return true;
    }
    private Item bankedRecovery() {
        for (Item item : p.getBank().getItems()) if (item != null && item.getAmount() > 0 && AdventurerSafety.isRecoveryItem(item)) return item;
        return null;
    }
    private boolean hasOwnedCombatSupplies() {
        return (owned(1323) || p.getEquipment().getId(Equipment.SLOT_WEAPON) == 1323)
                && (AdventurerSafety.recoveryCount(p) > 0 || (bankedRecovery() != null && canReachBank()));
    }
    private boolean prepareCombatSupplies(long tick) {
        if (p.getEquipment().getId(Equipment.SLOT_WEAPON) != 1323) {
            if (has(1323)) p.getEquipment().equip(p.getInventory().findItem(1323));
            else { withdrawOwned(1323, 2, tick); return false; }
        }
        Item food = canReachBank() ? bankedRecovery() : null;
        if (AdventurerSafety.recoveryCount(p) < 3 && food != null) { withdrawOwned(food.getId(), 3, tick); return false; }
        return p.getEquipment().getId(Equipment.SLOT_WEAPON) == 1323 && AdventurerSafety.recoveryCount(p) > 0;
    }
    private boolean moveToFireTile(long tick) {
        int attempted = 0, z = p.getHeight();
        for (int radius = 1; radius <= 12; radius++) for (int dx = -radius; dx <= radius; dx++) for (int dy = -radius; dy <= radius; dy++) {
            if (Math.max(Math.abs(dx), Math.abs(dy)) != radius) continue;
            int x = p.getAbsX() + dx, y = p.getAbsY() + dy;
            Tile tile = Tile.get(x, y, z);
            if (!inArea(x,y,z) || !TrainingSites.safeToWork(p,x,y,z) || (tile != null && !tile.isFloorFree()) || !Tile.allowObjectPlacement(x,y,z)) continue;
            if (++attempted > 8) return false;
            io.ruin.model.map.route.RouteType route = p.getRouteFinder().routeAbsolute(x,y);
            routes++;
            if (route.reachable && route.finishX == x && route.finishY == y) { defer(tick + 4, "walking to clear firemaking tile"); return true; }
            p.getMovement().reset();
        }
        detail = "blocked: no reachable clear firemaking tile";
        return false;
    }
    void defer(long tick, String description) { nextAction = tick; detail = description; }
    boolean intentionalCombat(NPC enemy) { return active != null && MELEE.contains(active.primarySkill) && combatTarget == enemy; }
    private boolean buy(int id, long tick) {
        NPC merchant = null;
        for (NPC n : npcs) if (n.getId() == 2820) { merchant = n; break; }
        if (merchant == null) { detail = "blocked: supply merchant missing"; return false; }
        if(!TrainingSites.canTravelTo(p,merchant.getAbsX(),merchant.getAbsY(),merchant.getHeight())) {
            defer(tick+10,"blocked: unsafe supply-shop arrival"); return false;
        }
                // Use an accessible adjacent tile around custom map walls.
        // Dispatch its existing action only after a physical adjacency/line-of-sight check.
        if (p.viewingShop == null && p.getPosition().isWithinDistance(merchant.getPosition(),1)
                && io.ruin.model.map.route.routes.ProjectileRoute.allow(p,merchant)) {
            int opt=option(merchant.getDef().options,"trade");
            if (opt>0 && merchant.actions!=null && merchant.actions[opt-1]!=null) merchant.actions[opt-1].handle(p,merchant);
        }
        Shop shop = p.viewingShop;
        if (shop != null && SHOP.equals(shop.identifier) && p.getPosition().isWithinDistance(merchant.getPosition(), 2)) {
            int coins = p.getInventory().getAmount(995);
            shop.buyFromShop(p, new Item(id, 1));
            p.closeInterfaces(); nextAction = tick + 4;
            detail = has(id) ? "purchased tool through normal shop" : "blocked: insufficient coins or stock (" + coins + ")";
            return has(id);
        }
        // The custom home's shop has a solid east wall. Use its real exterior
        // entrance, not the nearby but unreachable interior door.
        if (TrainingSites.travel(p,merchant.getAbsX(),merchant.getAbsY(),merchant.getHeight())) {nextAction=tick+10;return false;}
        Tile entrance=Tile.get(2021,3569,0);
        if(entrance!=null && entrance.gameObjects!=null)for(GameObject door:entrance.gameObjects) {
            int open=door.id<0?-1:option(door.getDef().options,"open");
            if(open<1 || !door.getDef().name.toLowerCase(Locale.ROOT).contains("door"))continue;
            if(Math.max(Math.abs(p.getAbsX()-2021),Math.abs(p.getAbsY()-3570))>1) {
                p.resetActions(true,true,true);p.getRouteFinder().routeAbsolute(2021,3570);nextAction=tick+8;detail="walking to shop entrance";return false;
            }
            interact(door,open,tick);detail="opening exterior shop door";return false;
        }
        detail = "walking to supply shop";
        interact(merchant, option(merchant.getDef().options, "trade"), tick);
        return false;
    }

    boolean bank(long tick) {
        detail = "banking earned supplies";
        if (p.isVisibleInterface(Interface.BANK)) return true;
        GameObject bank = nearest(o -> option(o.getDef().options, "bank") > 0 && TrainingSites.safeToWork(p,o.x,o.y,o.z)
                && TrainingSites.canTravelTo(p,o.x,o.y,o.z));
        if (bank != null) { interact(bank, option(bank.getDef().options, "bank"), tick); return false; }
        NPC banker = null; int distance = Integer.MAX_VALUE;
        for (NPC n : npcs) {
            int opt = option(n.getDef().options, "bank");
            if (opt > 0 && !n.isRemoved() && TrainingSites.safeToWork(p,n.getAbsX(),n.getAbsY(),n.getHeight())
                    && TrainingSites.canTravelTo(p,n.getAbsX(),n.getAbsY(),n.getHeight())) {
                int score = Math.max(Math.abs(n.getAbsX()-p.getAbsX()), Math.abs(n.getAbsY()-p.getAbsY()))
                        + p.memory.failedRoutes.getOrDefault(npcRouteKey(n), 0) * 100000;
                if (score < distance) { banker = n; distance = score; }
            }
        }
        if (banker != null) { interact(banker, option(banker.getDef().options,"bank"), tick); return false; }
        detail = "blocked: no bank in activity area";
        return false;
    }

    void markRoute(GameObject object) {
        lastResource = object;
        lastRouteKey = object.originalId + "@" + object.x + "," + object.y;
    }
    void interact(GameObject object, int option, long tick) {
        markRoute(object);
        if(!TrainingSites.canTravelTo(p,object.x,object.y,object.z)) {defer(tick+10,"blocked: unsafe city arrival");return;}
        if (TrainingSites.travel(p, object.x, object.y, object.z)) { nextAction = tick + 10; return; }
        if (option < 1) throw new IllegalStateException("Missing object option " + object.id);
        p.resetActions(true, true, true);
        long start = System.nanoTime();
        ObjectActionHandler.handleAction(p, option, object.id, object.x, object.y, 0);
        routes++; slowestRouteNanos = Math.max(slowestRouteNanos, System.nanoTime() - start);
        nextAction = tick + 8;
    }
    void interact(NPC npc, int option, long tick) {
        lastRouteKey = npcRouteKey(npc);
        if(!TrainingSites.canTravelTo(p,npc.getAbsX(),npc.getAbsY(),npc.getHeight())) {defer(tick+10,"blocked: unsafe city arrival");return;}
        if (TrainingSites.travel(p, npc.getAbsX(), npc.getAbsY(), npc.getHeight())) { nextAction = tick + 10; return; }
        if (option < 1) throw new IllegalStateException("Missing NPC option " + npc.getId());
        p.resetActions(true, true, true);
        long start = System.nanoTime();
        NPCActionHandler.handleAction(p, option, npc.getIndex(), 0);
        routes++; slowestRouteNanos = Math.max(slowestRouteNanos, System.nanoTime() - start);
        nextAction = tick + 8;
    }

    private GameObject findOre() { return nearest(o -> ORES.contains(o.id) && TrainingSites.available(p,o), true); }
    private GameObject findTree() { return nearest(o -> TREES.contains(o.id) && TrainingSites.available(p,o), true); }
    private GameObject findStall() {
        int level = p.getStats().get(StatType.Thieving).fixedLevel;
        return nearest(o -> { if (!TrainingSites.available(p,o)) return false;
            for (Stall s : Stall.values()) if (s.levelReq <= level) for (int[] ids : s.objIDs) if (o.id == ids[0]) return true;
            return false;
        }, true);
    }
    private GameObject nearest(java.util.function.Predicate<GameObject> match) { return nearest(match, false); }
    private GameObject nearest(java.util.function.Predicate<GameObject> match, boolean training) {
        GameObject result = null; int score = Integer.MAX_VALUE;
        for (GameObject o : objects) {
            if (o.id < 0 || !match.test(o)) continue;
            int distance = Math.max(Math.abs(o.x - p.getAbsX()), Math.abs(o.y - p.getAbsY()));
            // Stable tiny per-actor offset avoids everyone selecting the exact same tree.
            int s = (training ? TrainingSites.preference(p,o) : 0) + Math.min(distance,5000) * 8 + Math.floorMod(o.x * 31 + o.y + (int)p.seed, 8);
            s += p.memory.failedRoutes.getOrDefault(o.originalId + "@" + o.x + "," + o.y,0) * 100000;
            if (s < score) { result = o; score = s; }
        }
        return result;
    }
    static int option(String[] options, String... names) {
        if (options != null) for (int i = 0; i < options.length; i++) for (String name : names) if (name.equalsIgnoreCase(options[i])) return i + 1;
        return -1;
    }

    public PauseStatus pauseSafely(long tick) {
        if (p.isLocked() || p.getCombat().isDead()) { detail = "waiting for safe stop"; return PauseStatus.PENDING; }
        if (p.safety.isBusy() || p.getCombat().isDefending(17)) {
            detail = p.safety.description(); return PauseStatus.PENDING;
        }
        // Stop the old XP job once. Subsequent settling passes must allow a
        // recovery bank route to finish instead of repeatedly canceling it.
        if (active != null) {
            p.resetActions(true,true,true); active = null; combatTarget = null;
            TrainingSites.release(p);
        }
        if (p.safety.needsRecovery()) {
            if (!p.safety.heal() && !p.hasActiveAction() && p.getMovement().isAtDestination() && tick >= nextAction) {
                Item food = bankedRecovery();
                if (food != null) withdrawOwned(food.getId(), 3, tick);
            }
            detail = "recovering health before resting"; return PauseStatus.PENDING;
        }
        if (BotPrevention.isBlocked(p)) {
            if (!p.hasActiveAction() && tick >= nextAction) interact(p.botPreventionNPC, option(p.botPreventionNPC.getDef().options, "dismiss"), tick);
            return PauseStatus.PENDING;
        }
        p.resetActions(true, true, true); active = null; combatTarget = null; ProtectionPrayerPolicy.apply(p,null,false); TrainingSites.release(p);
        if (!legalPresence()) { p.quarantine("Recovery outside supported movement area requires review"); return PauseStatus.PENDING; }
        detail = "safe online pause";
        return observe().safeToIdle ? PauseStatus.SAFE : PauseStatus.PENDING;
    }
    public void idle(IdleStyle style, long tick) { p.faceNone(false); }
    public void onEvent(String id, String event) { if (event.contains("activity stalled") && lastRouteKey != null) p.memory.routeFailed(lastRouteKey); }
    void onSafetyRetreat() { if (lastRouteKey != null) p.memory.routeFailed(lastRouteKey); }
    public void onFault(String id, RuntimeException failure) { TrainingSites.release(p); p.quarantine(failure.toString()); Server.logError("Autonomous actor " + p.displayName, failure); }
    private NPC findCombatTarget() {
        NPC best=null; int score=Integer.MAX_VALUE;
        for(NPC n:npcs) {
            if (!eligibleCombatTarget(n)) continue;
            if(n.getCombat().getTarget()!=null && n.getCombat().getTarget()!=p)continue;
            GameObject spot=new GameObject(n.getId(),n.getAbsX(),n.getAbsY(),n.getHeight(),0,0);
            if(!TrainingSites.available(p,spot))continue;
            int s=TrainingSites.preference(p,spot)+Math.min(5000,Math.max(Math.abs(p.getAbsX()-n.getAbsX()),Math.abs(p.getAbsY()-n.getAbsY())))*8
                    + p.memory.failedRoutes.getOrDefault(npcRouteKey(n),0)*100000;
            if(s<score){score=s;best=n;}
        }
        return best;
    }
    boolean eligibleCombatTarget(NPC n) {
        if (n == null || n.isRemoved() || n.isHidden() || n.getCombat() == null || n.getCombat().isDead()) return false;
        String name = n.getDef().name;
        if (!("Chicken".equals(name) || "Cow".equals(name) || "Goblin".equals(name) || "Rat".equals(name))) return false;
        return n.getDef().combatLevel >= 1 && n.getDef().combatLevel <= 5
                && option(n.getDef().options,"attack") > 0
                && n.getCombat().getClass() == io.ruin.model.activities.miscpvm.BasicCombat.class
                && n.getCombat().getInfo().max_damage <= 2
                && p.memory.mayAttempt("npc:" + n.getId())
                && p.getCombat().canAttack(n,false);
    }
    static String npcRouteKey(NPC n) {
        Position spawn = n.spawnPosition;
        return "npc:" + n.getId() + "@" + (spawn == null ? n.getAbsX() : spawn.getX()) + "," + (spawn == null ? n.getAbsY() : spawn.getY());
    }
    private ActivityStatus combat(long tick) {
        if(p.getCombat().getTarget()!=null) return ActivityStatus.RUNNING;
        if(p.hasActiveAction() || !p.getMovement().isAtDestination())return ActivityStatus.RUNNING;
        // Eating clears the ordinary combat target. Resume that same valid enemy,
        // not a bank trip or a new victim while still defending.
        if (p.getCombat().isDefending(17) && combatTarget != null && eligibleCombatTarget(combatTarget)) {
            if (AdventurerSafety.recoveryCount(p) == 0) return ActivityStatus.BLOCKED;
            p.getCombat().setTarget(combatTarget); return ActivityStatus.RUNNING;
        }
        if (!hasOwnedCombatSupplies()) { detail = "blocked: obtain real weapon and recovery supplies"; return ActivityStatus.BLOCKED; }
        if (!prepareCombatSupplies(tick)) return ActivityStatus.RUNNING;
        if (p.getInventory().getFreeSlots() < 1) { bankSurplus(tick,-1); return ActivityStatus.RUNNING; }
        // Collect only this actor's actual drops using the normal route/pickup rules.
        if(p.getInventory().getFreeSlots()>0) for(int x=p.getAbsX()-3;x<=p.getAbsX()+3;x++)for(int y=p.getAbsY()-3;y<=p.getAbsY()+3;y++) {
            Tile tile=Tile.get(x,y,p.getHeight());if(tile==null || tile.groundItems==null)continue;
            for(io.ruin.model.map.ground.GroundItem drop:tile.groundItems)if(drop.id==526 && drop.originalOwner==p.getUserId()) {
                p.getRouteFinder().routeGroundItem(drop,distance->drop.pickup(p,distance));nextAction=tick+4;detail="collecting earned bones";return ActivityStatus.RUNNING;
            }
        }
        io.ruin.model.combat.AttackType wanted=active.primarySkill.equals("strength")?io.ruin.model.combat.AttackType.AGGRESSIVE:
            active.primarySkill.equals("defence")?io.ruin.model.combat.AttackType.DEFENSIVE:io.ruin.model.combat.AttackType.ACCURATE;
        io.ruin.model.combat.AttackSet[] sets=p.getCombat().weaponType.attackSets;
        for(int i=0;i<sets.length;i++)if(sets[i]!=null && sets[i].type==wanted){p.getCombat().changeAttackSet(i);break;}
        NPC target=findCombatTarget();
        if(target==null)return ActivityStatus.BLOCKED;
        GameObject spot=new GameObject(target.getId(),target.getAbsX(),target.getAbsY(),target.getHeight(),0,0);
        if(!TrainingSites.claim(p,spot))return ActivityStatus.BLOCKED;
        lastRouteKey=npcRouteKey(target);
        if(TrainingSites.travel(p,target.getAbsX(),target.getAbsY(),target.getHeight())) {nextAction=tick+10;return ActivityStatus.RUNNING;}
        // Re-check normal restrictions after travel/equipment, including ownership and combat locks.
        if (!eligibleCombatTarget(target)) return ActivityStatus.BLOCKED;
        combatTarget=target;lastRouteKey=npcRouteKey(target);detail="training "+active.primarySkill+" on "+target.getDef().name;
        p.face(target);p.getCombat().setTarget(target);nextAction=tick+4;
        return ActivityStatus.RUNNING;
    }
    String description() { return detail; }
    JsonArray blockedGoals() {
        JsonArray blocked = new JsonArray();
        for (String skill : p.policy.targets.keySet()) if (!SUPPORTED.contains(skill)) blocked.add(skill + ": activity adapter not yet verified");
        return blocked;
    }
    static JsonObject catalogueStatus() {
        JsonObject result = new JsonObject();
        result.addProperty("objects", objects.size()); result.addProperty("npcs", npcs.size());
        JsonArray entries = new JsonArray();
        for (GameObject o : objects) if (TREES.contains(o.originalId) || option(o.getDef().options, "steal-from", "steal from", "bank") > 0) entries.add(o.originalId + " " + o.getDef().name + " " + o.x + "," + o.y);
        result.add("activityObjects", entries); return result;
    }
}
