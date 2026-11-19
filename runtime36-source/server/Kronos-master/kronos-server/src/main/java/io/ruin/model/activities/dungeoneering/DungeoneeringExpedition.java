package io.ruin.model.activities.dungeoneering;

import com.google.gson.Gson;
import io.ruin.Server;
import io.ruin.cache.NPCDef;
import io.ruin.model.entity.player.Player;
import io.ruin.model.inter.InterfaceAction;
import io.ruin.model.inter.InterfaceHandler;
import io.ruin.model.inter.InterfaceType;
import io.ruin.model.inter.dialogue.YesNoDialogue;
import io.ruin.model.inter.handlers.OptionScroll;
import io.ruin.model.inter.loot.LootCompendium;
import io.ruin.model.inter.utils.Option;
import io.ruin.model.item.Item;
import io.ruin.model.entity.player.PlayerFile;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** The ledger presents the native party, saves and instances on the world thread. */
final class DungeoneeringExpedition {
    private static final int HOST = 383;
    private static final int ROWS = 6;
    private static final Gson GSON = new Gson();
    private static final Map<Player, Boolean> ENHANCED = new WeakHashMap<>();
    private static final Map<Player, Page> PAGES = new WeakHashMap<>();
    private static final long[] WORLD_TIMES = new long[60];
    private static int nextToken = 1;
    private static boolean installed;

    private enum Tab {
        PARTY("Party"), FLOORS("Floors"), ATLAS("Atlas"), KIT("Loadout"),
        LORE("Journal"), SHOP("Rewards"), RECORDS("Records");
        final String title;
        Tab(String title) { this.title = title; }
    }

    private static final class Row {
        final String title, detail;
        final int item;
        int token;
        final transient Runnable action;
        Row(String title, String detail, int item, Runnable action) {
            this.title = plain(title);
            this.detail = plain(detail);
            this.item = item;
            this.action = action;
        }
    }

    private static final class Page {
        final int version = 1;
        final String title;
        String subtitle = "", pagination = "";
        final List<Row> tabs = new ArrayList<>(), rows = new ArrayList<>();
        final transient Map<Integer, Runnable> actions = new HashMap<>();
        int back, next, close, mapWidth, mapHeight;
        int[] map;
        Page(Tab tab) { title = tab.title; }
    }

    private DungeoneeringExpedition() {}

    static void logout(Player p) { PAGES.remove(p); ENHANCED.remove(p); }

    static void enable(Player p, boolean enhanced) {
        ENHANCED.put(p, enhanced);
        Dungeoneering.open(p);
    }

    static void open(Player p) {
        show(p, Dungeoneering.inRun(p) ? Tab.ATLAS : Tab.PARTY, 0);
    }

    private static void show(Player p, Tab tab, int offset) {
        if (!p.isOnline() || !Dungeoneering.available() || PlayerFile.isSaveQuarantined(p)) return;
        DungeoneeringProgress progress = Dungeoneering.progress(p);
        Dungeoneering.Party party = Dungeoneering.party(p);
        DungeoneeringFloor run = Dungeoneering.floor(p);
        List<Row> content = new ArrayList<>();
        Page page = new Page(tab);
        page.subtitle = "Level " + progress.level() + "  |  " + progress.tokens + " tokens  |  Prestige " + progress.prestige();
        switch (tab) {
            case PARTY:
                party(p, party, content);
                break;
            case FLOORS:
                floors(p, party, progress, content);
                break;
            case ATLAS:
                atlas(p, run, page, content);
                break;
            case KIT:
                kit(p, run, progress, content);
                break;
            case LORE:
                journal(progress, run, content);
                break;
            case SHOP:
                rewards(p, run, progress, content);
                break;
            case RECORDS:
                records(progress, content);
                break;
        }
        if (!Boolean.TRUE.equals(ENHANCED.get(p))) {
            List<Option> options = new ArrayList<>();
            for (Tab entry : Tab.values()) if (entry != tab)
                options.add(new Option("<col=68c6b6>" + entry.title, () -> show(p, entry, 0)));
            for (Row row : content) {
                String caption = row.title + (row.detail.isEmpty() ? "" : " - " + row.detail);
                options.add(new Option(caption, row.action == null ? () -> show(p, tab, 0) : row.action));
            }
            String heading = ENHANCED.containsKey(p) ? "Dungeoneering / " : "Expedition Ledger / ";
            OptionScroll.open(p, heading + tab.title, false, options);
            return;
        }
        install();
        int maxPage = Math.max(0, (content.size() - 1) / ROWS);
        int index = Math.max(0, Math.min(offset, maxPage));
        int first = index * ROWS;
        page.pagination = (index + 1) + " / " + (maxPage + 1);
        for (Tab entry : Tab.values()) {
            Row nav = new Row(entry.title, "", -1, () -> show(p, entry, 0));
            register(page, nav);
            page.tabs.add(nav);
        }
        for (int i = first; i < Math.min(first + ROWS, content.size()); i++) {
            Row row = content.get(i);
            register(page, row);
            page.rows.add(row);
        }
        if (index > 0) page.back = token(page, () -> show(p, tab, index - 1));
        if (index < maxPage) page.next = token(page, () -> show(p, tab, index + 1));
        page.close = token(page, () -> p.closeInterface(InterfaceType.MAIN));
        boolean alreadyOpen = PAGES.containsKey(p) && p.isVisibleInterface(HOST);
        if (!alreadyOpen) {
            if (p.isVisibleInterface(HOST)) p.closeInterface(InterfaceType.MAIN);
            p.openInterface(InterfaceType.MAIN, HOST);
        }
        PAGES.put(p, page);
        p.getPacketSender().sendDropTable("@DG1:" + Base64.getEncoder()
            .encodeToString(GSON.toJson(page).getBytes(StandardCharsets.UTF_8)), -1, 0, Collections.emptyList());
    }

    private static String plain(String value) {
        return value == null ? "" : value.replace('<', ' ').replace('>', ' ').replace('|', '/')
            .replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static void install() {
        if (installed) return;
        LootCompendium.init();
        InterfaceHandler previous = InterfaceHandler.HANDLERS[HOST];
        InterfaceHandler.register(HOST, handler -> {
            System.arraycopy(previous.actions, 0, handler.actions, 0,
                Math.min(previous.actions.length, handler.actions.length));
            handler.actions[0] = new LedgerAction(previous.actions[0]);
            handler.closedAction = (p, id) -> {
                PAGES.remove(p);
                if (previous.closedAction != null) previous.closedAction.accept(p, id);
            };
        });
        installed = true;
    }

    private static final class LedgerAction implements InterfaceAction {
        private final InterfaceAction legacy;
        LedgerAction(InterfaceAction legacy) { this.legacy = legacy; }
        @Override public void handleClick(Player p, int option, int slot, int itemId) {
            Page page = PAGES.get(p);
            if (page == null) {
                if (legacy != null) legacy.handleClick(p, option, slot, itemId);
                return;
            }
            if (option != 1 || !p.isVisibleInterface(HOST) || PlayerFile.isSaveQuarantined(p)) return;
            Runnable action = page.actions.remove(itemId);
            if (action != null) action.run();
        }
        @Override public void handleDrag(Player p, int fromSlot, int fromItem, int toInterface, int toChild, int toSlot, int toItem) {
            if (!PAGES.containsKey(p) && legacy != null) legacy.handleDrag(p, fromSlot, fromItem, toInterface, toChild, toSlot, toItem);
        }
        @Override public void handleOnInterface(Player p, int fromSlot, int fromItem, int toInterface, int toChild, int toSlot, int toItem) {
            if (!PAGES.containsKey(p) && legacy != null) legacy.handleOnInterface(p, fromSlot, fromItem, toInterface, toChild, toSlot, toItem);
        }
        @Override public void handleOnEntity(Player p, io.ruin.model.entity.Entity entity, int slot, int itemId) {
            if (!PAGES.containsKey(p) && legacy != null) legacy.handleOnEntity(p, entity, slot, itemId);
        }
        @Override public void handleOnGroundItem(Player p, int slot, int itemId, io.ruin.model.map.ground.GroundItem item, int distance) {
            if (!PAGES.containsKey(p) && legacy != null) legacy.handleOnGroundItem(p, slot, itemId, item, distance);
        }
        @Override public void handleOnObject(Player p, int slot, int itemId, io.ruin.model.map.object.GameObject object) {
            if (!PAGES.containsKey(p) && legacy != null) legacy.handleOnObject(p, slot, itemId, object);
        }
    }

    private static int token(Page page, Runnable action) {
        int id = nextToken++;
        if (nextToken == 65000) nextToken = 1;
        page.actions.put(id, action);
        return id;
    }

    private static void register(Page page, Row row) {
        if (row.action != null) row.token = token(page, row.action);
    }

    private static void add(List<Row> rows, String title, String detail, Runnable action) {
        rows.add(new Row(title, detail, -1, action));
    }

    private static void party(Player p, Dungeoneering.Party party, List<Row> rows) {
        add(rows, "Original party controls", "Open the basic in-game actions.", () -> Dungeoneering.legacyOpen(p));
        if (party == null) {
            add(rows, "Form party", "Play solo or invite up to four players.", () -> Dungeoneering.formParty(p));
            String leader = Dungeoneering.pendingLeader(p);
            if (leader != null) add(rows, "Accept invitation", leader, () -> Dungeoneering.accept(p));
            return;
        }
        if (Dungeoneering.canEditParty(p, party)) {
            add(rows, "Begin floor " + party.floor, DungeoneeringAssets.theme(party.floor) + " / Complexity " + party.complexity,
                () -> { p.closeInterface(InterfaceType.MAIN); Dungeoneering.start(p, party); });
            add(rows, "Invite player", "Only the party leader can invite.", () -> {
                p.closeInterface(InterfaceType.MAIN);
                p.nameInput("Player to invite:", name -> Dungeoneering.invite(p, name));
            });
        }
        for (Player member : party.members)
            add(rows, member.getName(), (member == party.leader ? "Leader / " : "") +
                "Level " + Dungeoneering.progress(member).level(), null);
        if (party.run == null) add(rows, "Leave party", "Your floor progress is saved separately.",
            () -> { if (Dungeoneering.party(p) == party) Dungeoneering.leaveParty(p); open(p); });
    }

    private static void floors(Player p, Dungeoneering.Party party, DungeoneeringProgress progress, List<Row> rows) {
        if (!Dungeoneering.canEditParty(p, party)) {
            add(rows, "Floor " + (party == null ? progress.selectedFloor : party.floor),
                party == null ? "Form a party to choose a floor." : "The idle party leader can change settings.", null);
            return;
        }
        add(rows, "Floor " + party.floor + " / Complexity " + party.complexity, Dungeoneering.sizeName(party.size),
            () -> Dungeoneering.settings(p, party));
        add(rows, "Boss selection", party.selectedBoss < 0 ? "Random encounter" : bossName(party.selectedBoss),
            () -> selectBoss(p, party));
        add(rows, "Full-clear challenge", party.fullClear ? "On / Explore every room to claim" : "Off / Standard floor",
            () -> { if (Dungeoneering.canEditParty(p, party)) party.fullClear = !party.fullClear; show(p, Tab.FLOORS, 0); });
        if (party.complexity >= 2)
            add(rows, "Start room supplies", party.supplyKit ? "50 feathers and 25 rune essence" : "Standard rusty coins",
                () -> { if (Dungeoneering.canEditParty(p, party)) party.supplyKit = !party.supplyKit; show(p, Tab.FLOORS, 0); });
        int maximum = Dungeoneering.maximumFloor(party);
        for (int floor = 1; floor <= maximum; floor++) {
            final int selected = floor;
            add(rows, "Floor " + floor, DungeoneeringAssets.theme(floor) +
                (progress.completedFloors.contains(floor) ? " / Complete" : ""),
                () -> { Dungeoneering.setFloor(p, party, selected); show(p, Tab.FLOORS, 0); });
        }
    }

    static String bossName(int source) {
        NPCDef def = NPCDef.get(Dungeoneering.assets.npc(source));
        return def == null ? "Dungeon boss" : def.name;
    }

    private static void selectBoss(Player p, Dungeoneering.Party party) {
        if (!Dungeoneering.canEditParty(p, party)) return;
        Map<Integer, String> options = new LinkedHashMap<>();
        for (DungeoneeringAssets.Room room : Dungeoneering.assets.select("BossRoom", party.floor, party.complexity))
            if (room.npcSpawns != null && room.npcSpawns.length > 0 && room.doors.length == 1 &&
                java.util.Arrays.stream(room.npcSpawns).allMatch(spawn -> DungeoneeringCombat.supportedBoss(spawn.id)))
                options.put(room.npcSpawns[0].id, bossName(room.npcSpawns[0].id));
        List<Option> list = new ArrayList<>();
        list.add(new Option("Random encounter", () -> chooseBoss(p, party, -1)));
        options.forEach((id, name) -> list.add(new Option(name, () -> chooseBoss(p, party, id))));
        OptionScroll.open(p, "Expedition boss", false, list);
    }

    private static void chooseBoss(Player p, Dungeoneering.Party party, int source) {
        if (Dungeoneering.canEditParty(p, party) && (source < 0 ||
            Dungeoneering.assets.select("BossRoom", party.floor, party.complexity).stream().anyMatch(room ->
                room.doors.length == 1 && room.npcSpawns != null && room.npcSpawns.length > 0 &&
                room.npcSpawns[0].id == source && java.util.Arrays.stream(room.npcSpawns).allMatch(spawn -> DungeoneeringCombat.supportedBoss(spawn.id)))))
            party.selectedBoss = source;
        show(p, Tab.FLOORS, 0);
    }

    private static void atlas(Player p, DungeoneeringFloor run, Page page, List<Row> rows) {
        if (run == null || !Dungeoneering.inRun(p)) {
            add(rows, "Dungeon atlas", "Enter a floor to see its rooms and shared keys.", null);
            return;
        }
        page.mapWidth = run.graph.width;
        page.mapHeight = run.graph.height;
        page.map = new int[page.mapWidth * page.mapHeight];
        DungeoneeringFloor.RoomState here = run.room(p.getPosition());
        for (DungeoneeringFloor.RoomState room : run.rooms) {
            int state = !room.opened ? 1 : room == here ? 5 : room.node.boss ? 4 :
                room.node.id == run.graph.startId ? 3 : 2;
            page.map[room.node.x + (page.mapHeight - 1 - room.node.y) * page.mapWidth] = state;
        }
        add(rows, "Floor " + run.party.floor, run.openedCount() + " / " + run.rooms.length + " rooms explored", () -> run.status(p));
        if (run.mayClaim()) add(rows, "Claim floor rewards", "Save rewards and restore outside equipment.", () -> Dungeoneering.exit(p, true));
        else if (run.complete) add(rows, "Explore the remaining rooms", "Required by the full-clear challenge.", null);
        List<Integer> keys = new ArrayList<>(run.keys);
        Collections.sort(keys);
        for (int key : keys) {
            int item = run.assets.item(18202 + key * 2);
            rows.add(new Row(io.ruin.cache.ItemDef.get(item).name, "Shared party key", item, null));
        }
        add(rows, "Abandon floor", "Your original gear is restored.", () -> p.dialogue(new YesNoDialogue(
            "Leave this floor?", "Unfinished floor progress is lost.", 995, 1, () -> Dungeoneering.exit(p, false))));
    }

    private static void kit(Player p, DungeoneeringFloor run, DungeoneeringProgress progress, List<Row> rows) {
        if (run != null && Dungeoneering.inRun(p)) {
            add(rows, "Bind equipment", "Use the existing saved binding slots.", () -> Dungeoneering.bindMenu(p));
            add(rows, "Dungeon spellbook", "Combat, gatestones and utility spells.", () -> DungeoneeringSpells.open(p));
            add(rows, "Tool belt", "Store a pickaxe or hatchet for later floors.", () -> DungeoneeringExpeditionSupplies.tools(p));
            add(rows, "Crafting and supplies", "Native dungeon recipes and skills.", () -> DungeoneeringSkills.menu(p));
            add(rows, "Imbue weapon", "Convert an eligible staff near a dungeon altar.", () -> DungeoneeringSkills.imbue(p));
            add(rows, "Summoning", "Manage dungeon familiars.", () -> DungeoneeringSummoning.menu(p));
            if (DungeoneeringExpeditionSupplies.atStart(p, run))
                add(rows, "Smuggler storage", "Save dungeon supplies for later floors.", () -> DungeoneeringExpeditionSupplies.storage(p, run));
            DungeoneeringFloor.RoomState room = run.room(p.getPosition());
            if (room != null && room.node.id == run.graph.startId)
                add(rows, "Smuggler supplies", "Shop and recipes in the starting room.", () -> {
                    if (Dungeoneering.floor(p) == run && Dungeoneering.inRun(p) &&
                        run.room(p.getPosition()) == run.rooms[run.graph.startId]) DungeoneeringSkills.smuggler(p, run);
                });
            for (Item item : p.getInventory().getItems()) if (item != null && run.assets.sourceItem(item.getId()) >= 0) {
                String name = item.getDef().name.toLowerCase(java.util.Locale.ROOT);
                if (name.contains("pickaxe") || name.contains("hatchet") || name.contains("hammer") ||
                    name.contains("tinderbox") || name.contains("knife") || name.contains("fishing rod"))
                    rows.add(new Row(item.getDef().name, "Carried dungeon tool", item.getId(), null));
            }
        }
        for (Item item : progress.boundItems)
            if (item != null) rows.add(new Row(item.getDef().name, "Bound for future floors", item.getId(), null));
        add(rows, "Ring of kinship", "Manage the native combat and support roles.", () -> DungeoneeringKinship.open(p));
        add(rows, "Renewal prayer", "Inspect or use the unlocked prayer.", () -> DungeoneeringCombatRewards.open(p));
    }

    private static void journal(DungeoneeringProgress progress, DungeoneeringFloor run, List<Row> rows) {
        add(rows, "Dungeoneering progress", progress.experience + " XP / " + progress.completedDungeons + " rewards claimed", null);
        add(rows, "Prestige cycle", progress.completedFloors.size() + " credited floors / " + progress.unlockedFloor() + " unlocked", null);
        if (progress.lastFloorResult != null) add(rows, "Last floor reward", progress.lastFloorResult, null);
        if (run != null) add(rows, "Current expedition", duration(Server.currentTick() - run.created) +
            " / " + run.openedCount() + " rooms explored", null);
        add(rows, "Scroll upgrades", (progress.scrollLife ? "Life / " : "") + (progress.scrollCleansing ? "Cleansing / " : "") +
            (progress.scrollEfficiency ? "Efficiency" : ""), null);
    }

    private static void rewards(Player p, DungeoneeringFloor run, DungeoneeringProgress progress, List<Row> rows) {
        if (run != null || Dungeoneering.inRun(p)) {
            add(rows, "Token balance", progress.tokens + " / Trade at the lobby after leaving this floor.", null);
            return;
        }
        add(rows, "Token shop", progress.tokens + " tokens", () -> DungeoneeringRewards.open(p));
        add(rows, "Equipment repairs", "Inspect or recharge reward equipment.", () -> DungeoneeringEquipmentRewards.open(p));
        add(rows, "Reset prestige cycle", "Record the best completed-floor count.", () -> Dungeoneering.resetPrestige(p));
        for (int f = 1; f <= progress.unlockedFloor(); f++) {
            int floor = f;
            if (!progress.completedFloors.contains(floor))
                add(rows, "Skip floor " + floor, skipCost(progress.level(), floor) + " tokens / Prestige credit only",
                    () -> confirmSkip(p, floor));
        }
    }

    static long skipCost(int level, int floor) {
        if (level < 1 || level > 120 || floor < 1 || floor > 60) throw new IllegalArgumentException("Invalid floor skip");
        return (level == 120 ? 720L : (level >= 100 ? 5L : 4L) * level) * floor;
    }

    private static void confirmSkip(Player p, int floor) {
        DungeoneeringProgress progress = Dungeoneering.progress(p);
        long cost = skipCost(progress.level(), floor);
        if (!canSkip(p, progress, floor, cost)) return;
        p.dialogue(new YesNoDialogue("Skip floor " + floor + "?", "Spend " + cost + " tokens for prestige credit.", 995, 1, () -> {
            if (!canSkip(p, progress, floor, cost) || cost != skipCost(progress.level(), floor)) return;
            progress.completedFloors.add(floor);
            progress.tokens -= cost;
            Dungeoneering.save(p, () -> { p.sendMessage("Floor " + floor + " credited for this prestige cycle."); show(p, Tab.SHOP, 0); });
        }));
    }

    private static boolean canSkip(Player p, DungeoneeringProgress progress, int floor, long cost) {
        Dungeoneering.Party party = Dungeoneering.party(p);
        if (PlayerFile.isSaveQuarantined(p) || p.isLocked() || Dungeoneering.inRun(p) ||
            party != null && party.run != null || floor > progress.unlockedFloor() || progress.completedFloors.contains(floor)) {
            p.sendMessage("That floor is unavailable to skip.");
            return false;
        }
        if (progress.tokens >= cost) return true;
        p.sendMessage("You need " + cost + " Dungeoneering tokens.");
        return false;
    }

    static long record(DungeoneeringProgress progress, DungeoneeringFloor run, String reward) {
        long ticks = Math.max(1, Server.currentTick() - run.created);
        int index = run.party.floor - 1;
        long previous = progress.bestFloorTicks[index];
        if (previous == 0 || ticks < previous) progress.bestFloorTicks[index] = ticks;
        progress.lastFloorResult = reward;
        return ticks;
    }

    static void worldRecord(int floor, long ticks) {
        int index = floor - 1;
        if (WORLD_TIMES[index] == 0 || ticks < WORLD_TIMES[index]) WORLD_TIMES[index] = ticks;
    }

    private static void records(DungeoneeringProgress progress, List<Row> rows) {
        for (int i = 0; i < 60; i++) {
            long personal = progress.bestFloorTicks[i], world = WORLD_TIMES[i];
            if (personal > 0 || world > 0)
                add(rows, "Floor " + (i + 1), "Personal " + duration(personal) + " / World this boot " + duration(world), null);
        }
        if (rows.isEmpty()) add(rows, "No timed floors yet", "Claim a completed floor to start your record.", null);
    }

    private static String duration(long ticks) {
        if (ticks <= 0) return "--";
        long seconds = ticks * Server.tickMs() / 1000;
        return (seconds / 60) + "m " + (seconds % 60) + "s";
    }
}