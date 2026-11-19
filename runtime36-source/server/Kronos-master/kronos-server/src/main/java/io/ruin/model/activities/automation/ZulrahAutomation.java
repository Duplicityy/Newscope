package io.ruin.model.activities.automation;

import io.ruin.cache.ItemDef;
import io.ruin.content.content242.ModernChargedWeapons;
import io.ruin.model.World;
import io.ruin.model.activities.bosses.zulrah.Zulrah;
import io.ruin.model.combat.AttackStyle;
import io.ruin.model.combat.AttackType;
import io.ruin.model.entity.Entity;
import io.ruin.model.entity.npc.NPC;
import io.ruin.model.entity.player.Player;
import io.ruin.model.entity.player.PlayerCombat;
import io.ruin.model.item.Item;
import io.ruin.model.item.actions.ItemAction;
import io.ruin.model.map.Position;
import io.ruin.model.map.Region;
import io.ruin.model.map.Tile;
import io.ruin.model.map.ground.GroundItem;
import io.ruin.model.map.route.RouteType;
import io.ruin.model.map.route.routes.DumbRoute;
import io.ruin.model.map.route.routes.ProjectileRoute;
import io.ruin.model.map.route.routes.TargetRoute;
import io.ruin.model.skills.prayer.Prayer;
import io.ruin.model.stat.StatType;
import io.ruin.network.incoming.handlers.NPCActionHandler;
import io.ruin.process.event.Event;

import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Private owner controller for one already entered native Zulrah shrine. */
public final class ZulrahAutomation implements AutomationDriver {
    /** Read-only accessors on the actual active native encounter. */
    public interface Observation {
        int ownerZulrahPhaseIndex();
        String ownerZulrahForm();
        boolean ownerZulrahTransitioning();
        boolean ownerZulrahNextJadRanged();
        Position ownerZulrahMeleeTarget();
    }

    private static final int GRID_RADIUS = 12, GRID_WIDTH = GRID_RADIUS * 2 + 1;
    private static final int GRID_SIZE = GRID_WIDTH * GRID_WIDTH, MAX_VISITS = 240;
    private static final int FUME_ID = 11700, MAX_SITE_DISTANCE = 26, NO_DAMAGE_TICKS = 150;
    private static final int[][] FUMES = {{-4,3},{-4,0},{-4,-3},{-1,-4},{2,-4},{5,-4},{6,-1},{6,2},{6,5}};
    private static final int[] STEP_X = {1,-1,0,0,1,1,-1,-1}, STEP_Y = {0,0,1,-1,1,-1,1,-1};
    private static final int[] FOOD = {13441, 391, 11936, 385, 3144, 7946, 397, 379};
    private static final int[] PRAYER_RESTORE = {2434, 139, 141, 143, 3024, 3026, 3028, 3030};
    private static final int[] ANTI_VENOM = {12913, 12915, 12917, 12919, 12905, 12907, 12909, 12911};
    private static final int[] ESCAPE_TAB = {8008, 8007, 8009, 8010, 8011};
    private static final Prayer[] OVERHEADS = {Prayer.PROTECT_FROM_MAGIC, Prayer.PROTECT_FROM_MISSILES,
            Prayer.PROTECT_FROM_MELEE, Prayer.RETRIBUTION, Prayer.REDEMPTION, Prayer.SMITE};

    private final Player player;
    private final PlayerCombat combat;
    private final AutomationActionSource events;
    private final int goal, startingKills, instance;
    private final int[] queue = new int[GRID_SIZE], first = new int[GRID_SIZE];
    private final boolean[] activeFumes = new boolean[FUMES.length];
    private final Set<GroundItem> attempted = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Prayer startingOverhead;
    private NPC boss;
    private Observation observed;
    private int bossHp, phase = -1;
    private long lastDamage, retreatDeadline, lootReady, lootDeadline;
    private Position drop, melee;
    private Event retreatEvent, lootEvent;
    private Prayer ownedPrayer;
    private boolean moving, stopped, changedPrayer, preparednessConfirmed;
    private String retreatReason, activity = "Find a live Zulrah in your solo shrine";

    ZulrahAutomation(Player player, int goal, long tick) {
        this.player = player;
        this.goal = goal;
        this.combat = player.getCombat();
        this.events = (AutomationActionSource) (Object) player;
        this.instance = player.getPosition().getRegion().dynamicIndex;
        this.startingKills = player.zulrahKills.getKills();
        this.lastDamage = tick;
        this.startingOverhead = overhead();
    }

    @Override public String activity() { return activity; }
    @Override public int remainingBossKills() { return Math.max(0, goal - kills()); }
    @Override public boolean ownsNativeTravel() { return retreatEvent != null; }
    @Override public boolean ownsNativeLock() {
        return retreatEvent != null && events.ownerAutomationAction() == retreatEvent;
    }

    @Override
    public String pulse(long tick) {
        if (retreatEvent != null) {
            if (!inInstance()) return "Retreated safely: " + retreatReason;
            return tick >= retreatDeadline || events.ownerAutomationAction() != retreatEvent
                    ? "Retreat did not complete. Take manual control." : null;
        }
        if (!inInstance()) return "Enter your own Zulrah shrine before starting";
        if (drop != null) return collectDrops(tick);
        if (boss == null) {
            boss = findBoss();
            if (boss == null) return "Start inside a shrine containing a live, unclaimed Zulrah";
            observed = (Observation) boss.getCombat();
            bossHp = boss.getHp();
        }
        if (!alive(boss)) {
            clearAttack();
            if (!boss.getCombat().isDead() && boss.getHp() > 0) return "Zulrah left the encounter";
            drop = boss.getCombat().getDropPosition().copy();
            lootReady = tick + Math.min(10, Math.max(0, boss.getCombat().getInfo().death_ticks)) + 2;
            lootDeadline = lootReady + 16;
            activity = "Waiting for Zulrah's drops";
            return null;
        }
        if (combat.getTarget() != null && combat.getTarget() != boss) return "Another fight took control";
        if (!claimable(boss)) return "Zulrah belongs to another player";
        if (player.hasActiveAction()) return "Another native action took control";
        if (bossHp > boss.getHp()) lastDamage = tick;
        bossHp = boss.getHp();
        if (tick - lastDamage > NO_DAMAGE_TICKS) return retreat(tick, "No progress damaging Zulrah");

        int range = rangedDistance();
        if (range < 2) return preparednessConfirmed ? retreat(tick, "No usable ranged weapon or spell")
                : "Equip a ranged weapon with ammo or configure autocast";
        if (!preparednessConfirmed) {
            String requirement = preflight();
            if (requirement != null) return requirement;
            preparednessConfirmed = true;
        }
        String recovery = supplies(tick);
        if (recovery != null || retreatEvent != null) return recovery;

        String form = observed.ownerZulrahForm();
        int nextPhase = observed.ownerZulrahPhaseIndex();
        if (phase != nextPhase) {
            phase = nextPhase;
            clearAttack();
        }
        Prayer wanted = protection(form, observed.ownerZulrahNextJadRanged());
        useProtection(wanted);
        melee = observed.ownerZulrahMeleeTarget();
        updateFumes();
        if (moving && !player.getMovement().isAtDestination()) {
            int next = player.getMovement().readOffset;
            if (danger(player.getMovement().getStepsX()[next], player.getMovement().getStepsY()[next])) {
                player.getMovement().reset();
                moving = false;
            }
        }
        boolean unsafe = danger(player.getAbsX(), player.getAbsY());
        boolean positioned = canShoot(player.getAbsX(), player.getAbsY(), range);
        if (player.isStunned()) {
            activity = "Waiting for Zulrah's stun to end";
            return null;
        }
        if (unsafe || !positioned && !observed.ownerZulrahTransitioning()) {
            clearAttack();
            if (!moveSafely(range, unsafe))
                return retreat(tick, "No safe reachable firing position");
            activity = unsafe ? "Moving out of a fume or tail-sweep tile" : "Repositioning for Zulrah";
            return null;
        }
        if (moving && !player.getMovement().isAtDestination()) return null;
        moving = false;
        if (observed.ownerZulrahTransitioning()) {
            clearAttack();
            activity = "Waiting for Zulrah to surface";
            return null;
        }
        if (!combat.canAttack(boss, false)) return retreat(tick, "Native combat checks refused the attack");
        if (combat.getTarget() != boss)
            NPCActionHandler.handleAction(player, boss.getDef().attackOption, boss.getIndex(), 0);
        activity = "Zulrah phase " + (phase + 1) + " / " + form;
        return null;
    }

    private String preflight() {
        if (player.getHp() < 48 || supply(FOOD,"eat") == null || supply(ESCAPE_TAB,"break") == null)
            return "Bring food and a safe teleport tablet, and restore your health before starting";
        if (player.getStats().get(StatType.Prayer).fixedLevel < Prayer.PROTECT_FROM_MAGIC.level
                || player.getStats().get(StatType.Prayer).currentLevel < 5)
            return "Restore Prayer and unlock both protection prayers before starting Zulrah";
        if (!player.isVenomImmune() && supply(ANTI_VENOM,"drink") == null)
            return "Bring anti-venom or wear working venom protection";
        return null;
    }

    private String supplies(long tick) {
        int hp = player.getHp();
        int healAt = Math.max(45, (int) (player.getMaxHp() * .62));
        if (hp <= healAt) {
            Item food = supply(FOOD,"eat");
            if (food != null && !player.eatDelay.isDelayed() && !player.karamDelay.isDelayed()
                    && !player.potDelay.isDelayed()) {
                clearAttack();
                itemAction(food,"eat").handle(player, food);
                activity = "Eating to recover health";
            }
            if (player.getHp() <= 42) return retreat(tick, "Health too low to continue safely");
        }
        if (!player.potDelay.isDelayed() && !player.eatDelay.isDelayed() && !player.karamDelay.isDelayed()) {
            if (player.isPoisoned()) {
                Item venom = supply(ANTI_VENOM,"drink");
                if (venom != null) itemAction(venom,"drink").handle(player, venom);
                else return retreat(tick, "Venom protection ran out");
            } else if (player.getStats().get(StatType.Prayer).currentLevel < 5) {
                Item restore = supply(PRAYER_RESTORE,"drink");
                if (restore != null) itemAction(restore,"drink").handle(player, restore);
                else return retreat(tick, "Prayer supplies ran out");
            }
        }
        return null;
    }

    private String retreat(long tick, String reason) {
        clearAttack();
        Item tab = supply(ESCAPE_TAB,"break");
        if (tab == null) return reason + ". No usable retreat tablet; take manual control.";
        Event before = events.ownerAutomationAction();
        itemAction(tab,"break").handle(player, tab);
        Event after = events.ownerAutomationAction();
        if (after == null || after == before || !after.persistent())
            return reason + ". The native teleport was refused; take manual control.";
        retreatEvent = after;
        retreatReason = reason;
        retreatDeadline = tick + 15;
        activity = "Retreating with a teleport tablet";
        return null;
    }

    private String collectDrops(long tick) {
        if (lootEvent != null && events.ownerAutomationAction() == lootEvent) {
            if (tick < lootDeadline) return null;
            if (events.cancelOwnerAutomationAction(lootEvent)) player.getMovement().reset();
        }
        lootEvent = null;
        if (tick < lootReady) return null;
        Tile tile = Tile.get(drop.getX(), drop.getY(), drop.getZ());
        if (tick < lootDeadline && attempted.size() < 8 && !player.getInventory().isFull()
                && player.getHeight() == drop.getZ() && tile != null && tile.groundItems != null) {
            for (GroundItem item : tile.groundItems) {
                if (!ownedDrop(item) || !attempted.add(item)) continue;
                player.getRouteFinder().routeGroundItem(item, distance -> {
                    if (!stopped && inInstance() && ownedDrop(item)) item.pickup(player, distance);
                });
                lootEvent = events.ownerAutomationAction();
                activity = "Collecting your Zulrah drops";
                return null;
            }
        }
        if (kills() <= 0) return "The shrine ended without a recorded Zulrah kill";
        return remainingBossKills() == 0 ? "Zulrah kill goal reached. Use the native shrine exit."
                : "Zulrah defeated. Each shrine has one encounter; enter a fresh shrine for another kill.";
    }

    private boolean ownedDrop(GroundItem item) {
        return !item.isRemoved() && item.z == player.getHeight() && item.getTimeDropped() == 0
                && item.originalOwner == player.getUserId()
                && (item.activeOwner == -1 || item.activeOwner == player.getUserId());
    }

    private NPC findBoss() {
        if (player.zulrahTimer == null) return null;
        for (NPC npc : player.localNpcs()) {
            if (!alive(npc) || npc.getHeight() != player.getHeight() || !(npc.getCombat() instanceof Zulrah)
                    || !(npc.getCombat() instanceof Observation) || !claimable(npc)) continue;
            if (npc.getPosition().getRegion().dynamicIndex == instance) return npc;
        }
        return null;
    }

    private static boolean alive(NPC npc) {
        return !npc.isRemoved() && npc.getCombat() != null && !npc.getCombat().isDead() && npc.getHp() > 0
                && World.getNpc(npc.getIndex()) == npc;
    }

    private boolean claimable(NPC target) {
        Entity fighting = target.getCombat().getTarget();
        return fighting == null || fighting == player;
    }

    private boolean inInstance() {
        return instance >= 0 && player.getPosition().getRegion().dynamicIndex == instance;
    }

    private int kills() { return player.zulrahKills.getKills() - startingKills; }

    private int rangedDistance() {
        if (combat.useSpell()) return 10;
        AttackStyle style = combat.getAttackStyle();
        if (style == null || !(style.isRanged() || style.isMagic()) || combat.weaponType == null) return 0;
        return ModernChargedWeapons.range(player,
                Math.min(10, combat.weaponType.maxDistance + (combat.getAttackType() == AttackType.LONG_RANGED ? 2 : 0)));
    }

    private boolean canShoot(int x, int y, int range) {
        int bx = boss.getAbsX(), by = boss.getAbsY(), size = boss.getSize();
        if (x >= bx && x < bx + size && y >= by && y < by + size) return false;
        return TargetRoute.inRange(x,y,player.getSize(),bx,by,size,range)
                && ProjectileRoute.allow(x,y,player.getHeight(),player.getSize(),bx,by,size);
    }

    private void updateFumes() {
        Position spawn = boss.getSpawnPosition();
        for (int i = 0; i < FUMES.length; i++)
            activeFumes[i] = Tile.getObject(FUME_ID, spawn.getX() + FUMES[i][0], spawn.getY() + FUMES[i][1], spawn.getZ()) != null;
    }

    private boolean danger(int x, int y) {
        if (melee != null && Math.max(Math.abs(x - melee.getX()),Math.abs(y - melee.getY())) <= 1)
            return true;
        Position spawn = boss.getSpawnPosition();
        for (int i = 0; i < FUMES.length; i++) {
            if (activeFumes[i] && insideFume(x,y,spawn.getX()+FUMES[i][0],spawn.getY()+FUMES[i][1])) return true;
        }
        return false;
    }

    static boolean insideFume(int x, int y, int baseX, int baseY) {
        return x >= baseX && x <= baseX + 2 && y >= baseY && y <= baseY + 2;
    }

    private boolean moveSafely(int range, boolean escapeHazard) {
        Arrays.fill(first, -1);
        int baseX = player.getAbsX() - GRID_RADIUS, baseY = player.getAbsY() - GRID_RADIUS;
        int origin = GRID_RADIUS * GRID_WIDTH + GRID_RADIUS, read = 0, written = 1;
        int z = player.getHeight();
        queue[0] = origin;
        first[origin] = origin;
        while (read < written && read < MAX_VISITS) {
            int current = queue[read++];
            int row = current / GRID_WIDTH, column = current % GRID_WIDTH;
            int x = baseX + row, y = baseY + column;
            if (current != origin && !danger(x,y) && (escapeHazard || canShoot(x,y,range))) {
                int move = first[current];
                int destX = baseX + move / GRID_WIDTH, destY = baseY + move % GRID_WIDTH;
                RouteType route = player.getRouteFinder().routeAbsolute(destX,destY);
                moving = route.reachable && route.finishX == destX && route.finishY == destY;
                if (!moving) player.getMovement().reset();
                return moving;
            }
            for (int i = 0; i < STEP_X.length; i++) {
                int r = row + STEP_X[i], c = column + STEP_Y[i];
                if (r < 0 || r >= GRID_WIDTH || c < 0 || c >= GRID_WIDTH) continue;
                int index = r * GRID_WIDTH + c, tx = baseX + r, ty = baseY + c;
                if (first[index] != -1 || tx <= 0 || tx >= 8192 || ty <= 0 || ty >= 16383
                        || !escapeHazard && danger(tx,ty)
                        || Region.get(tx,ty).dynamicIndex != instance
                        || Math.max(Math.abs(tx - boss.getSpawnPosition().getX()), Math.abs(ty - boss.getSpawnPosition().getY())) > MAX_SITE_DISTANCE
                        || DumbRoute.getDirection(player.getRouteFinder().getClipUtils(),x,y,z,player.getSize(),tx,ty) == null) continue;
                first[index] = current == origin ? index : first[current];
                queue[written++] = index;
            }
        }
        return false;
    }

    static Prayer protection(String form, boolean jadRanged) {
        if ("NORMAL".equals(form)) return Prayer.PROTECT_FROM_MISSILES;
        if ("TANZANITE".equals(form)) return Prayer.PROTECT_FROM_MAGIC;
        if ("JAD".equals(form)) return jadRanged ? Prayer.PROTECT_FROM_MISSILES : Prayer.PROTECT_FROM_MAGIC;
        return null;
    }

    private Prayer overhead() {
        for (Prayer prayer : OVERHEADS) if (player.getPrayer().isActive(prayer)) return prayer;
        return null;
    }

    private void useProtection(Prayer wanted) {
        Prayer active = overhead();
        if (wanted == active) return;
        if (wanted == null) {
            if (ownedPrayer != null && active == ownedPrayer) player.getPrayer().deactivate(ownedPrayer);
            return;
        }
        if (player.getStats().get(StatType.Prayer).currentLevel == 0) return;
        player.getPrayer().toggle(wanted);
        if (player.getPrayer().isActive(wanted)) {
            ownedPrayer = wanted;
            changedPrayer = true;
        }
    }

    private Item supply(int[] ids, String option) {
        for (int id : ids) {
            Item item = player.getInventory().findItem(id);
            if (item != null && itemAction(item,option) != null) return item;
        }
        return null;
    }

    private static ItemAction itemAction(Item item, String option) {
        ItemDef def = item.getDef();
        if (def == null || def.isNote() || def.inventoryActions == null) return null;
        int choice = def.getOption(option) - 1;
        return choice < 0 || choice >= def.inventoryActions.length ? null : def.inventoryActions[choice];
    }

    private void clearAttack() {
        if (boss != null && combat.getTarget() == boss) {
            combat.reset();
            player.getMovement().reset();
        }
    }

    @Override
    public void stop() {
        stopped = true;
        clearAttack();
        if (moving || lootEvent != null && events.cancelOwnerAutomationAction(lootEvent)) player.getMovement().reset();
        if (changedPrayer) {
            Prayer active = overhead();
            if (active == ownedPrayer && ownedPrayer != null) player.getPrayer().deactivate(ownedPrayer);
            if (startingOverhead != null && overhead() == null && player.getStats().get(StatType.Prayer).currentLevel > 0)
                player.getPrayer().toggle(startingOverhead);
        }
    }
}
