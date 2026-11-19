package io.ruin.model.activities.automation;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.ruin.Server;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import io.ruin.api.buffer.InBuffer;
import io.ruin.model.World;
import io.ruin.model.activities.dungeoneering.Dungeoneering;
import io.ruin.model.activities.dungeoneering.IDungeon;
import io.ruin.model.activities.dungeoneering.IDungeonInterface;
import io.ruin.model.activities.raids.auto.RaidAuto;
import io.ruin.model.activities.raids.toa.port.TombsOfAmascutRaid;
import io.ruin.model.activities.raids.tob.port.TheatreOfBloodRaid;
import io.ruin.model.entity.player.Player;
import io.ruin.model.entity.player.PlayerFile;
import io.ruin.model.entity.player.PlayerGroup;
import io.ruin.model.entity.npc.actions.edgeville.SlayerMaster;
import io.ruin.model.inter.Interface;
import io.ruin.model.inter.dialogue.OptionsDialogue;
import io.ruin.model.inter.utils.Option;
import io.ruin.model.skills.BotPrevention;
import io.ruin.model.skills.slayer.Slayer;
import io.ruin.model.skills.slayer.SlayerTask;
import io.ruin.model.item.Item;
import io.ruin.model.stat.StatType;
import io.ruin.network.incoming.Incoming;
import io.ruin.network.incoming.handlers.ActionButtonHandler;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Predicate;

/** Owner control and serial, world-thread activity scheduling. */
public final class OwnerAutomation {
    private static final String UNKNOWN = "Sorry, that command does not exist.";
    private static final Map<Player, Controls> CONTROLS = new WeakHashMap<>();
    private static final long MAX_TICKS = 12_000, STATUS_EVERY = 100;
    private static final List<StatType> TRAINING_ORDER = Arrays.asList(
            StatType.Mining, StatType.Woodcutting, StatType.Fishing,
            StatType.Smithing, StatType.Fletching, StatType.Cooking,
            StatType.Crafting, StatType.Herblore, StatType.Firemaking, StatType.Prayer,
            StatType.Runecrafting, StatType.Agility, StatType.Thieving, StatType.Hunter,
            StatType.Farming, StatType.Construction, StatType.Attack, StatType.Strength,
            StatType.Defence, StatType.Hitpoints, StatType.Ranged, StatType.Magic, StatType.Slayer);
    private static Session active;

    private OwnerAutomation() {}

    static boolean owner(boolean enabled, boolean publicWorld, boolean ownerRank) {
        return enabled && !publicWorld && ownerRank;
    }

    private static boolean authorized(Player p) {
        return owner(Boolean.getBoolean("newscape.ownerAutomation"), Boolean.getBoolean("kronos.public"),
                p.isGroup(PlayerGroup.OWNER));
    }

    private static boolean supported(StatType skill) {
        switch (skill) {
            case Mining: case Woodcutting: case Fishing: case Hunter: case Construction: case Farming: return true;
            default: return ProductionAutomation.supports(skill) || UtilitySkillAutomation.supports(skill)
                    || CombatAutomation.supports(skill);
        }
    }

    private static AutomationDriver driver(Player player, StatType skill, int level, long tick) {
        switch (skill) {
            case Mining: return new MiningAutomation(player, level, tick);
            case Woodcutting: return new WoodcuttingAutomation(player, level, tick);
            case Fishing: return new FishingAutomation(player, level, tick);
            case Hunter: return new HunterAutomation(player, level, tick);
            case Farming: return new FarmingAutomation(player, level, tick);
            case Construction: return new ConstructionAutomation(player, level, tick);
            default:
                if (ProductionAutomation.supports(skill)) return new ProductionAutomation(player, skill, level, tick);
                if (UtilitySkillAutomation.supports(skill)) return new UtilitySkillAutomation(player, skill, level, tick);
                if (CombatAutomation.supports(skill)) return new CombatAutomation(player, skill, level, tick);
                throw new IllegalArgumentException("Unsupported skill " + skill);
        }
    }

    public static boolean command(Player p, String query) {
        String trimmed = query.trim();
        if (!trimmed.regionMatches(true, 0, "bot", 0, 3)
                || trimmed.length() > 3 && !Character.isWhitespace(trimmed.charAt(3))) return false;
        String[] words = trimmed.toLowerCase(Locale.ROOT).split("\\s+");
        if (!authorized(p)) {
            p.sendMessage(UNKNOWN);
            return true;
        }
        if (words.length >= 4 && words[1].equals("mobile")) {
            mobile(p, words);
        } else if (words.length == 1 || (words.length == 2 && words[1].equals("help"))) {
            open(p);
        } else if (words.length == 2) {
            switch (words[1]) {
                case "status": tell(p, status(p)); break;
                case "stop": stop(p); break;
                case "pause": pause(p); break;
                case "resume": resume(p); break;
                case "slayer": slayerPage(p); break;
                default: usage(p);
            }
        } else if (words.length == 4 && words[1].equals("skill")) {
            StatType skill = parseSkill(words[2]);
            if (skill == null || !supported(skill)) {
                tell(p, "This skill does not have an available automation driver.");
            } else {
                int goal = parseGoal(words[3]);
                if (goal == 0) usage(p);
                else start(p, skill, goal);
            }
        } else if (words.length == 4 && words[1].equals("boss")) {
            Boss boss = Boss.parse(words[2]);
            int kills = parsePositiveCount(words[3], 100);
            if (boss == null || kills == 0) usage(p);
            else startBoss(p, boss, kills);
        } else if ((words.length == 3 || words.length == 4) && words[1].equals("train") && words[2].equals("all")) {
            int goal = words.length == 4 ? parseGoal(words[3]) : controls(p).goal;
            if (goal == 0) usage(p);
            else start(p, null, goal);
        } else {
            usage(p);
        }
        return true;
    }

    private static StatType parseSkill(String text) {
        if (text.equals("runecraft")) return StatType.Runecrafting;
        if (text.equals("defense")) return StatType.Defence;
        for (StatType skill : StatType.values())
            if (skill.name().equalsIgnoreCase(text)) return skill;
        return null;
    }

    private static int parsePositiveCount(String text, int maximum) {
        try {
            int count = Integer.parseInt(text);
            return count >= 1 && count <= maximum ? count : 0;
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static int parseGoal(String value) {
        try {
            int goal = Integer.parseInt(value);
            return AutomationPolicy.validGoal(goal) ? goal : 0;
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    public static boolean allowLegacyStart(Player p) {
        if (!authorized(p)) return true;
        Controls settings = controls(p);
        if (!settings.enabled) {
            tell(p, "Automation is disabled. Enable it in ::bot.");
            return false;
        }
        if (session(p) == null) return true;
        tell(p, "Stop the active training session before starting another controller.");
        return false;
    }

    private static void mobile(Player p, String[] words) {
        if (!authorized(p) || !words[2].matches("[a-f0-9]{24}")) return;
        Controls c = controls(p);
        long tick = Server.currentTick();
        String op = words[3];
        boolean emergency = op.equals("stop") || op.equals("raidstop") || op.equals("dungeonstop")
                || op.equals("master") && words.length == 5 && words[4].equals("false");
        if (!emergency && tick - c.lastMobileTick < 2) {
            mobileReply(p, words[2], "Please wait a moment");
            return;
        }
        c.lastMobileTick = tick;
        if (!op.equals("status")) c.notice = "";
        if (words.length == 4) {
            switch (op) {
                case "status": break;
                case "pause": pause(p); break;
                case "resume": resume(p); break;
                case "stop": stop(p); break;
                case "dungeonopen":
                    if (p.isLocked() || session(p) != null) {
                        mobileReply(p, words[2], "Finish your current action first");
                        return;
                    }
                    IDungeonInterface.open(p);
                    break;
                case "dungeonstop": IDungeon.stop(p, "Stopped by owner", true); break;
                case "raidstart": raid(p, "start"); break;
                case "raidstop": raid(p, "stop"); break;
                default: mobileReply(p, words[2], "Unsupported action"); return;
            }
        } else if (op.equals("master") && words.length == 5 && booleanWord(words[4])) {
            if (c.enabled != Boolean.parseBoolean(words[4])) setMaster(p, !c.enabled);
        } else if (op.equals("enable") && words.length == 6 && booleanWord(words[5])) {
            if (session(p) != null) {
                mobileReply(p, words[2], "Stop the current goal before changing enabled skills");
                return;
            }
            StatType skill = parseSkill(words[4]);
            if (skill == null || !supported(skill)) {
                mobileReply(p, words[2], "Unavailable activity");
                return;
            }
            if (Boolean.parseBoolean(words[5])) c.enabledSkills.add(skill);
            else c.enabledSkills.remove(skill);
            c.save();
        } else if (op.equals("start") && words.length == 6) {
            StatType skill = parseSkill(words[4]);
            int goal = parseGoal(words[5]);
            if (skill == null || !supported(skill) || goal == 0) {
                mobileReply(p, words[2], "Unavailable activity or invalid level");
                return;
            }
            c.selected = skill;
            c.goal = goal;
            c.save();
            if (skill == StatType.Slayer && c.preferences.slayer().master == 0)
                slayerPage(p);
            else start(p, skill, goal);
        } else if ((op.equals("slayerconfig") || op.equals("slayerstart")) && words.length == 6) {
            if (session(p) != null) {
                mobileReply(p, words[2], "Stop the active goal before changing Slayer settings");
                return;
            }
            int id = parsePositiveCount(words[4], 65534);
            SlayerTask.Type tier = parseSlayerTier(words[5]);
            if (id == 0 || tier == null || !isHomeSlayerMaster(id, tick)) {
                mobileReply(p, words[2], "Select an available Slayer master and task tier first");
                return;
            }
            SlayerSettings settings = c.preferences.slayer();
            settings.master = id;
            settings.tier = tier;
            c.selected = StatType.Slayer;
            c.save();
            if (op.equals("slayerstart")) start(p, StatType.Slayer, c.goal);
            else tell(p, "Slayer settings saved.");
        } else if (op.equals("progress") && words.length == 5 && parseGoal(words[4]) > 0) {
            start(p, null, parseGoal(words[4]));
        } else if (op.equals("boss") && words.length == 6 && words[5].matches("1|5|10") && Boss.parse(words[4]) != null) {
            startBoss(p, Boss.parse(words[4]), Integer.parseInt(words[5]));
        } else {
            mobileReply(p, words[2], "Invalid automation request");
            return;
        }
        mobileReply(p, words[2], "");
    }

    static SlayerTask.Type parseSlayerTier(String value) {
        switch (value) {
            case "easy": return SlayerTask.Type.EASY;
            case "medium": return SlayerTask.Type.MEDIUM;
            case "hard": return SlayerTask.Type.HARD;
            default: return null;
        }
    }

    private static boolean isHomeSlayerMaster(int id, long tick) {
        for (int master : SlayerMaster.IDS) {
            if (master == id) return !SlayerWorld.current(tick).masters(id).isEmpty();
        }
        return false;
    }

    private static boolean booleanWord(String value) {
        return "true".equals(value) || "false".equals(value);
    }

    private static void mobileReply(Player p, String nonce, String error) {
        Controls c = controls(p);
        Session run = session(p);
        JsonObject reply = new JsonObject();
        reply.addProperty("ok", error.isEmpty());
        reply.addProperty("error", error);
        reply.addProperty("enabled", c.enabled);
        reply.addProperty("running", run != null);
        reply.addProperty("paused", run != null && run.paused);
        reply.addProperty("goal", c.goal);
        reply.addProperty("selected", c.selected.name());
        SlayerSettings slayer = c.preferences.slayer();
        reply.addProperty("slayerMaster", slayer.master);
        reply.addProperty("slayerTier", slayer.tier == null ? "easy" : slayer.tier.name().toLowerCase(Locale.ROOT));
        JsonArray masters = new JsonArray();
        SlayerWorld slayerWorld = SlayerWorld.current(Server.currentTick());
        for (int id : SlayerMaster.IDS) {
            if (slayerWorld.masters(id).isEmpty()) continue;
            JsonObject master = new JsonObject();
            master.addProperty("id", id);
            master.addProperty("name", SlayerAutomation.masterName(id));
            masters.add(master);
        }
        reply.add("slayerMasters", masters);
        reply.addProperty("status", status(p));
        reply.addProperty("notice", c.notice);
        reply.addProperty("dungeon", IDungeon.status(p));
        reply.addProperty("dungeonActive", IDungeon.active(p));
        reply.addProperty("raidActive", RaidAuto.isActive(p));
        JsonArray skills = new JsonArray();
        JsonArray disabled = new JsonArray();
        for (StatType skill : TRAINING_ORDER) {
            if (!supported(skill)) continue;
            skills.add(skill.name());
            if (!c.enabledSkills.contains(skill)) disabled.add(skill.name());
        }
        reply.add("skills", skills);
        reply.add("disabled", disabled);
        JsonArray bosses = new JsonArray();
        for (Boss boss : Boss.values()) bosses.add(boss.label);
        reply.add("bosses", bosses);
        String text = Base64.getUrlEncoder().withoutPadding().encodeToString(reply.toString().getBytes(StandardCharsets.UTF_8));
        int chunkSize = 160; // Opcode 62 uses a one-byte length prefix.
        int count = (text.length() + chunkSize - 1) / chunkSize;
        for (int i = 0; i < count; i++) {
            int start = i * chunkSize;
            p.sendMessage("NewscapeAutomation " + nonce + " " + i + "/" + count + " "
                    + text.substring(start, Math.min(start + chunkSize, text.length())));
        }
    }

    private static void tell(Player p, String text) {
        controls(p).notice = text;
        p.sendMessage(text);
    }

    private static void usage(Player p) {
        tell(p, "::bot opens the panel. ::bot skill <skill> <2-99>, train all [level], boss <name> <kills>, status, pause, resume, or stop.");
    }

    private static Controls controls(Player p) {
        return CONTROLS.computeIfAbsent(p, ignored -> new Controls(
                ((AutomationPreferences.Source) (Object) p).automationPreferences()));
    }

    private static Session session(Player p) {
        return active != null && active.player == p ? active : null;
    }

    private static void open(Player p) {
        if (!authorized(p)) return;
        if ((p.isLocked() && session(p) == null) || (p.hasDialogue() && p.optionsDialogue != controls(p).panel)) {
            tell(p, "Close your current activity or dialogue before opening Automation.");
            return;
        }
        Session run = session(p);
        if (run != null) {
            boolean paused = run.paused;
            menu(p, status(p), new Option(paused ? "Resume" : "Pause", player -> {
                if (paused) resume(player); else pause(player);
                open(player);
            }), new Option("Stop", player -> { stop(player); open(player); }),
                    new Option("Refresh status", OwnerAutomation::open),
                    new Option("Master switch: On", OwnerAutomation::toggleMaster), new Option("Close"));
            return;
        }
        Controls c = controls(p);
        menu(p, "Owner automation", new Option("Master switch: " + (c.enabled ? "On" : "Off"), OwnerAutomation::toggleMaster),
                new Option("Start " + c.selected.name() + " to level " + c.goal,
                        player -> {
                            Controls settings = controls(player);
                            if (settings.selected == StatType.Slayer) slayerPage(player);
                            else start(player, settings.selected, settings.goal);
                        }),
                new Option("Choose skill or combat", OwnerAutomation::categories),
                new Option("Account progression", OwnerAutomation::progression),
                new Option("Dungeoneering and raids", OwnerAutomation::existingMenu));
    }

    private static void categories(Player p) {
        menu(p, "Choose an activity",
                new Option("Gathering", player -> browse(player, "Gathering", OwnerAutomation::gathering, 0)),
                new Option("Production and support", player -> browse(player, "Production and support", OwnerAutomation::production, 0)),
                new Option("Combat and Slayer", player -> browse(player, "Combat and Slayer", OwnerAutomation::combat, 0)),
                new Option("Back", OwnerAutomation::open));
    }

    private static boolean gathering(StatType s) {
        return s == StatType.Mining || s == StatType.Fishing || s == StatType.Woodcutting || s == StatType.Hunter || s == StatType.Farming;
    }

    private static boolean combat(StatType s) {
        return (s.isCombat() && s != StatType.Prayer) || s == StatType.Slayer;
    }

    private static boolean production(StatType s) {
        return !gathering(s) && !combat(s);
    }

    private static void browse(Player p, String title, Predicate<StatType> category, int page) {
        if (!authorized(p)) return;
        List<StatType> matches = new ArrayList<>();
        for (StatType skill : TRAINING_ORDER)
            if (supported(skill) && category.test(skill)) matches.add(skill);
        List<Option> options = new ArrayList<>();
        for (int i = page * 3; i < Math.min(matches.size(), page * 3 + 3); i++) {
            StatType skill = matches.get(i);
            options.add(new Option(skill.name() + (controls(p).enabledSkills.contains(skill) ? " (On)" : " (Off)"),
                    player -> select(player, skill)));
        }
        if (matches.size() > (page + 1) * 3)
            options.add(new Option("Next", player -> browse(player, title, category, page + 1)));
        options.add(new Option("Back", player -> {
            if (page > 0) browse(player, title, category, page - 1);
            else categories(player);
        }));
        menu(p, title, options.toArray(new Option[0]));
    }

    private static void select(Player p, StatType skill) {
        if (!authorized(p)) return;
        Controls c = controls(p);
        c.selected = skill;
        c.save();
        if (skill == StatType.Slayer) {
            slayerPage(p);
            return;
        }
        menu(p, skill.name() + " to level " + c.goal,
                new Option(c.enabledSkills.contains(skill) ? "Turn off" : "Turn on", player -> {
                    if (!authorized(player)) return;
                    EnumSet<StatType> enabled = controls(player).enabledSkills;
                    if (!enabled.remove(skill)) enabled.add(skill);
                    controls(player).save();
                    select(player, skill);
                }),
                new Option("Choose target level", player -> goals(player, skill)),
                new Option("Start " + skill.name(), player -> start(player, skill, controls(player).goal)),
                new Option("Back", OwnerAutomation::categories));
    }

    private static void slayerPage(Player p) {
        if (!authorized(p)) return;
        SlayerSettings settings = controls(p).preferences.slayer();
        Session run = session(p);
        SlayerTask task = Slayer.getTask(p);
        String title = "Slayer / " + SlayerAutomation.masterName(settings.master)
                + (task == null ? "" : " / " + task.name + " (" + p.slayerTaskRemaining + " left)");
        if (run != null && run.skill == StatType.Slayer) {
            menu(p, title, new Option(run.paused ? "Resume" : "Pause", player -> {
                if (run.paused) resume(player); else pause(player);
                if (run.paused) slayerPage(player); else if (player.hasDialogue()) player.closeDialogue();
            }), new Option("Stop", player -> { stop(player); slayerPage(player); }),
                    new Option("Choose master for future tasks", OwnerAutomation::slayerMasters),
                    new Option("Show status", player -> tell(player, status(player))),
                    new Option("Close"));
            return;
        }
        menu(p, title, new Option("Choose Slayer master", OwnerAutomation::slayerMasters),
                new Option("Settings", OwnerAutomation::slayerSettings),
                new Option("Start Slayer", player -> start(player, StatType.Slayer, controls(player).goal)),
                new Option(controls(p).enabledSkills.contains(StatType.Slayer) ? "Disable Slayer" : "Enable Slayer", player -> {
                    Controls c = controls(player);
                    if (!c.enabledSkills.remove(StatType.Slayer)) c.enabledSkills.add(StatType.Slayer);
                    c.save();
                    slayerPage(player);
                }),
                new Option("Back", OwnerAutomation::categories));
    }

    private static void slayerMasters(Player p) {
        slayerMasters(p, 0);
    }

    private static void slayerMasters(Player p, int page) {
        SlayerSettings settings = controls(p).preferences.slayer();
        SlayerWorld world = SlayerWorld.current(Server.currentTick());
        List<Integer> masters = new ArrayList<>();
        for (int id : SlayerMaster.IDS) if (!world.masters(id).isEmpty()) masters.add(id);
        List<Option> choices = new ArrayList<>();
        int pageSize = 3;
        int offset = Math.min(page, Math.max(0, (masters.size() - 1) / pageSize)) * pageSize;
        for (int id : masters.subList(offset, Math.min(masters.size(), offset + pageSize))) {
            String name = SlayerAutomation.masterName(id);
            String reason = SlayerAutomation.masterUnavailable(p, id, settings, Server.currentTick());
            choices.add(new Option(name + (id == settings.master ? " (Selected)" : reason == null ? "" : " (Unavailable)"), player -> {
                String currentReason = SlayerAutomation.masterUnavailable(player, id, settings, Server.currentTick());
                if (currentReason == null) settings.master = id;
                else tell(player, name + ". " + currentReason);
                slayerPage(player);
            }));
        }
        if (offset + pageSize < masters.size())
            choices.add(new Option("More masters", player -> slayerMasters(player, page + 1)));
        choices.add(new Option("Back", page == 0 ? OwnerAutomation::slayerPage : player -> slayerMasters(player, page - 1)));
        menu(p, "Select a Slayer master", choices.toArray(new Option[0]));
    }

    private static void slayerSettings(Player p) {
        SlayerSettings s = controls(p).preferences.slayer();
        menu(p, "Slayer settings",
                new Option("Assignment tier / " + s.tier, player -> menu(player, "Safe assignment tier",
                        slayerTier(SlayerTask.Type.EASY), slayerTier(SlayerTask.Type.MEDIUM),
                        slayerTier(SlayerTask.Type.HARD), new Option("Back", OwnerAutomation::slayerSettings))),
                new Option("Heal at " + s.healthPercent() + "% / carry " + s.foodTarget() + " food",
                        player -> menu(player, "Food and retreat",
                                slayerFood("Conservative", 70, 16, 4), slayerFood("Balanced", 60, 12, 2),
                                slayerFood("Fewer supplies", 55, 8, 1), new Option("Back", OwnerAutomation::slayerSettings))),
                new Option("Collect owned drops / " + (s.loot ? "On" : "Off"), player -> {
                    s.loot = !s.loot;
                    slayerSettings(player);
                }),
                new Option("Travel and combat", player -> menu(player, "Slayer travel and combat",
                        new Option("Free teleports / " + (s.useTeleports ? "On" : "Off"), selected -> {
                            s.useTeleports = !s.useTeleports;
                            slayerSettings(selected);
                        }),
                        new Option("Prefer " + (s.preferRanged ? "ranged" : "melee"), selected -> {
                            s.preferRanged = !s.preferRanged;
                            slayerSettings(selected);
                        }),
                        new Option("Bank preference / " + (s.preferHomeBank ? "Home" : "Nearest"), selected -> {
                            s.preferHomeBank = !s.preferHomeBank;
                            slayerSettings(selected);
                        }),
                        new Option("Back", OwnerAutomation::slayerSettings))),
                new Option("Back", OwnerAutomation::slayerPage));
    }

    private static Option slayerTier(SlayerTask.Type tier) {
        return new Option(tier.name(), p -> {
            controls(p).preferences.slayer().tier = tier;
            slayerSettings(p);
        });
    }

    private static Option slayerFood(String name, int health, int food, int minimum) {
        return new Option(name + " / " + health + "%, food " + food + "/" + minimum, p -> {
            SlayerSettings settings = controls(p).preferences.slayer();
            settings.healthPercent = health;
            settings.foodTarget = food;
            settings.foodMinimum = minimum;
            slayerSettings(p);
        });
    }

    private static void progression(Player p) {
        menu(p, "Train enabled skills to level " + controls(p).goal,
                new Option("Start progression", player -> start(player, null, controls(player).goal)),
                new Option("Choose target level", player -> goals(player, null)),
                new Option("Enable or disable skills", OwnerAutomation::categories),
                new Option("Back", OwnerAutomation::open));
    }

    private static void goals(Player p, StatType skill) {
        menu(p, "Choose target level", goal(15, skill), goal(41, skill), goal(61, skill), goal(99, skill),
                new Option("Back", player -> { if (skill == null) progression(player); else select(player, skill); }));
    }

    private static Option goal(int level, StatType skill) {
        return new Option("Level " + level, p -> {
            if (!authorized(p) || session(p) != null) return;
            controls(p).goal = level;
            controls(p).save();
            if (skill == null) progression(p); else select(p, skill);
        });
    }

    private static void existingMenu(Player p) {
        menu(p, "Existing automation",
                new Option("Open iDungeon", player -> { if (authorized(player)) IDungeonInterface.open(player); }),
                new Option("Start raid automation", player -> raid(player, "start")),
                new Option("Stop raid automation", player -> raid(player, "stop")),
                new Option("Bossing", OwnerAutomation::bosses),
                new Option("Back", OwnerAutomation::open));
    }

    private static void bosses(Player p) {
        menu(p, "Bossing",
                new Option(Boss.SMOKE_DEVIL.label, player -> bossMenu(player, Boss.SMOKE_DEVIL)),
                new Option(Boss.VORKATH.label, player -> bossMenu(player, Boss.VORKATH)),
                new Option(Boss.ZULRAH.label, player -> bossMenu(player, Boss.ZULRAH)),
                new Option("Back", OwnerAutomation::existingMenu));
    }

    private static void bossMenu(Player p, Boss boss) {
        menu(p, boss.label,
                new Option("Start 1 kill", player -> startBoss(player, boss, 1)),
                new Option("Start 5 kills", player -> startBoss(player, boss, 5)),
                new Option("Start 10 kills", player -> startBoss(player, boss, 10)),
                new Option("Back", OwnerAutomation::bosses));
    }

    private static void raid(Player p, String operation) {
        if (authorized(p)) RaidAuto.command(p, "raid", new String[]{"auto", operation});
    }

    private static void toggleMaster(Player p) {
        if (!authorized(p)) return;
        setMaster(p, !controls(p).enabled);
        open(p);
    }

    private static void setMaster(Player p, boolean enabled) {
        if (!authorized(p)) return;
        Controls settings = controls(p);
        settings.enabled = enabled;
        settings.save();
        if (!enabled) {
            Session run = session(p);
            if (run != null) finish(run, "Disabled with the master switch", true);
            IDungeon.stop(p, "Disabled with the master switch", true);
            RaidAuto.stop(p, "Disabled with the master switch", true);
        }
    }

    private static void menu(Player p, String title, Option... options) {
        if (!authorized(p)) return;
        Controls c = controls(p);
        c.panel = new OptionsDialogue(title.replace('|', '/'), options);
        p.dialogue(c.panel);
    }

    private static void start(Player p, StatType skill, int goal) {
        start(p, skill, goal, null, 0);
    }

    private static void startBoss(Player p, Boss boss, int kills) {
        start(p, StatType.Hitpoints, 99, boss, kills);
    }

    private static void start(Player p, StatType skill, int goal, Boss boss, int bossKills) {
        if (!authorized(p)) return;
        Controls c = controls(p);
        if (!c.enabled || (bossKills == 0 && skill != null && !c.enabledSkills.contains(skill))) {
            tell(p, "Enable automation and the activity in ::bot first.");
            return;
        }
        if (boss == null && skill == StatType.Slayer && c.preferences.slayer().master == 0) {
            tell(p, "Select a Slayer master in Slayer settings before starting.");
            return;
        }
        if (active != null || legacyActive(p)) {
            tell(p, "Stop other automation before starting a training goal.");
            return;
        }
        if (!safe(p, boss != null, false, false) || p.hasActiveAction() || !p.getMovement().isAtDestination()
                || (p.hasDialogue() && p.optionsDialogue != c.panel)
                || skill != StatType.Slayer && p.isVisibleInterface(Interface.BANK)) {
            tell(p, "Finish your current action and move to a safe training area.");
            return;
        }
        int levelLimit = AutomationPolicy.levelLimit(goal, skill == null ? 0 : p.getStats().get(skill).fixedLevel,
                boss == null && skill != null, skill == StatType.Slayer);
        boolean continuous = levelLimit == AutomationPolicy.NO_LEVEL_LIMIT;
        if (p.experienceLock && !continuous) {
            tell(p, "Unlock XP gain before starting a training goal.");
            return;
        }
        Deque<StatType> work = new ArrayDeque<>();
        if (skill == null) {
            for (StatType item : TRAINING_ORDER)
                if (c.enabledSkills.contains(item) && supported(item) && belowGoal(p, item, goal))
                    work.add(item);
        } else if (bossKills > 0 || supported(skill) && (continuous || belowGoal(p, skill, goal))) {
            work.add(skill);
        }
        if (work.isEmpty()) {
            tell(p, "No selected skill is below level " + goal + " with an available driver.");
            return;
        }
        if (boss == null) {
            if (skill != null) c.selected = skill;
            c.goal = goal;
            c.save();
        }
        if (p.optionsDialogue == c.panel && c.panel != null) p.closeDialogue();
        Session run = new Session(p, levelLimit, work, skill == null, boss, bossKills, Server.currentTick());
        active = run;
        tell(p, boss != null ? "Started " + boss.label + " goal." : skill == null
                ? "Started training enabled skills." : continuous ? "Started " + skill.name() + " in continuous mode."
                : "Started " + skill.name() + " to level " + goal + ".");
        World.startEvent(event -> {
            while (active == run) {
                evaluate(run, Server.currentTick());
                if (active == run) event.delay(run.boss != null ? 1 : 2);
            }
        });
    }

    private static void evaluate(Session run, long tick) {
        Player p = run.player;
        try {
            if (!authorized(p) || !connected(p)) {
                finish(run, "Disconnected or permission changed", false);
            } else if (legacyActive(p)) {
                finish(run, "Another controller took control", true);
            } else if (!run.paused && run.skill == StatType.Slayer && !run.progression && p.getCombat().isDead()) {
                completeActivity(run, "The character died. Recover, then Resume to continue the saved assignment.", false, tick);
            } else if (run.goal != AutomationPolicy.NO_LEVEL_LIMIT && tick - run.started >= MAX_TICKS) {
                finish(run, "Session reached its two-hour limit", true);
            } else if (!run.paused) {
                if (!safe(p, combat(run.skill), run.driver != null && run.driver.ownsNativeLock(),
                        run.driver != null && run.driver.ownsNativeTravel())
                        || p.experienceLock && run.goal != AutomationPolicy.NO_LEVEL_LIMIT) {
                    if (run.driver == null && tick < run.transitionDeadline && !p.experienceLock) return;
                    finish(run, "Stopped for an unsafe state or locked XP", true);
                    return;
                }
                if (run.driver == null && (run.boss != null || belowGoal(p, run.skill, run.goal))) run.begin(tick);
                boolean reached = run.boss == null && !belowGoal(p, run.skill, run.goal);
                String result = reached ? run.skill.name() + " goal reached" : run.driver.pulse(tick);
                if (result != null) {
                    completeActivity(run, result, reached, tick);
                } else if (tick >= run.nextStatus) {
                    run.nextStatus = tick + STATUS_EVERY;
                    p.sendFilteredMessage(status(p));
                }
            }
        } catch (RuntimeException failure) {
            Server.logError("Owner automation goal failed", failure);
            finish(run, "Training stopped after a server error. Check the server log.", true);
        }
    }

    private static void completeActivity(Session run, String result, boolean reached, long tick) {
        if (!run.progression) {
            if (run.skill == StatType.Slayer && !reached) {
                run.release();
                run.paused = true;
                run.pauseReason = result;
                controls(run.player).last = result;
                tell(run.player, "Slayer paused. " + result);
            } else {
                finish(run, result, true);
            }
            return;
        }
        run.release();
        if (reached) {
            run.completed++;
            run.blockedSkills.remove(run.skill);
        } else {
            run.blockedSkills.add(run.skill);
        }
        run.player.sendMessage(run.skill.name() + ": " + result);
        if (run.work.isEmpty() && !run.retried && run.earnedXp > 0 && !run.blockedSkills.isEmpty()) {
            run.retried = true;
            for (StatType skill : TRAINING_ORDER)
                if (run.blockedSkills.contains(skill)) run.work.addLast(skill);
        }
        run.skill = run.work.pollFirst();
        run.transitionDeadline = tick + 18;
        if (run.skill == null) {
            finish(run, "Progression ended. Goals met: " + run.completed + ", blocked: " + run.blockedSkills.size(), true);
        }
    }

    private static boolean belowGoal(Player p, StatType skill, int goal) {
        return p.getStats().get(skill).fixedLevel < goal;
    }

    private static boolean connected(Player p) {
        return p.isOnline() && p.logoutStage == 0 && p.getChannel() != null && p.getChannel().isActive();
    }

    private static boolean safe(Player p, boolean combatMode, boolean ownsNativeLock, boolean ownsNativeTravel) {
        return connected(p) && (combatMode || (!p.isLocked() || ownsNativeLock) && !p.isStunned()) && !p.getCombat().isDead()
                && (combatMode || p.getCombat().getTarget() == null
                    && !p.getCombat().isAttacking(8) && !p.getCombat().isDefending(8))
                && p.wildernessLevel <= 0 && !p.pvpAttackZone && (ownsNativeTravel || !p.getMovement().isTeleportQueued())
                && !BotPrevention.isBlocked(p) && !PlayerFile.isSaveQuarantined(p)
                && !Dungeoneering.inRun(p) && !TombsOfAmascutRaid.isInRaid(p) && !TheatreOfBloodRaid.isInRaid(p);
    }

    private static boolean legacyActive(Player p) { return IDungeon.active(p) || RaidAuto.isActive(p); }

    public static void manualInput(Player p, Incoming handler, InBuffer buffer) {
        Session run = session(p);
        if (run == null || run.paused || !RaidAuto.isGameplayInput(handler)) return;
        Controls c = CONTROLS.get(p);
        if (c != null && c.panel != null && p.optionsDialogue == c.panel
                && (selectsPanel(handler, buffer) || handler.getClass().getSimpleName().equals("CloseInterfaceHandler"))) return;
        finish(run, "Manual control", true);
    }

    private static boolean selectsPanel(Incoming handler, InBuffer buffer) {
        if (buffer.remaining() < 4) return false;
        int start = buffer.position();
        try {
            if (handler instanceof ActionButtonHandler.DefaultHandler)
                return buffer.readInt() == (Interface.OPTIONS_DIALOGUE << 16 | 1);
            if (handler instanceof ActionButtonHandler.DialogueHandler)
                return buffer.readLEInt() == (Interface.OPTIONS_DIALOGUE << 16 | 1);
            return false;
        } finally {
            buffer.position(start);
        }
    }

    private static void pause(Player p) {
        if (!authorized(p)) return;
        Session run = session(p);
        if (run == null || run.paused) return;
        run.release();
        if (run.boss != null && run.bossKills == 0) {
            finish(run, run.boss.label + " kill goal reached", true);
            return;
        }
        run.paused = true;
        run.pauseReason = "Paused by owner";
        tell(p, status(p));
    }

    private static void resume(Player p) {
        if (!authorized(p)) return;
        Session run = session(p);
        if (run == null || !run.paused) return;
        if (!safe(p, combat(run.skill), false, false) || legacyActive(p) || p.hasActiveAction()
                || !p.getMovement().isAtDestination()
                || run.skill != StatType.Slayer && p.isVisibleInterface(Interface.BANK)) {
            tell(p, "Finish your current action before resuming.");
            return;
        }
        run.paused = false;
        run.pauseReason = "";
        run.nextStatus = Server.currentTick() + STATUS_EVERY;
        tell(p, status(p));
    }

    private static void stop(Player p) {
        if (!authorized(p)) return;
        Session run = session(p);
        if (run != null) finish(run, "Stopped by owner", true);
        else tell(p, "Training is already stopped.");
    }

    private static void finish(Session run, String detail, boolean notify) {
        if (active != run) return;
        active = null;
        run.release();
        Controls c = CONTROLS.get(run.player);
        String message = detail + ". XP gained: " + (long) run.earnedXp;
        if (c != null) c.last = message;
        if (notify) run.player.sendMessage(message + ".");
    }

    private static String status(Player p) {
        Session run = session(p);
        if (run == null) return controls(p).last;
        int level = p.getStats().get(run.skill).fixedLevel;
        long seconds = Math.max(0, (Server.currentTick() - run.started) * 3 / 5);
        String step = run.paused ? "Paused. " + run.pauseReason : run.driver == null ? "Preparing" : run.driver.activity();
        String target = run.boss != null ? run.boss.label : run.skill.name() + " " + level
                + (run.goal == AutomationPolicy.NO_LEVEL_LIMIT ? " continuous" : "/" + run.goal);
        return target + " / " + step
                + " / +" + (long) run.xp() + " XP / " + seconds + "s";
    }

    private enum Boss {
        SMOKE_DEVIL("smokedevil", "Thermonuclear Smoke Devil"),
        VORKATH("vorkath", "Vorkath"),
        ZULRAH("zulrah", "Zulrah");

        final String key, label;
        Boss(String key, String label) { this.key = key; this.label = label; }

        static Boss parse(String key) {
            for (Boss boss : values()) if (boss.key.equals(key)) return boss;
            return null;
        }

        AutomationDriver driver(Player player, int kills, long tick) {
            switch (this) {
                case SMOKE_DEVIL: return CombatAutomation.smokeDevil(player, kills, tick);
                case VORKATH: return new VorkathAutomation(player, kills, tick);
                case ZULRAH: return new ZulrahAutomation(player, kills, tick);
                default: throw new IllegalStateException("Unexpected boss");
            }
        }
    }

    private static final class Controls {
        private final AutomationPreferences preferences;
        boolean enabled;
        int goal;
        StatType selected;
        final EnumSet<StatType> enabledSkills = EnumSet.allOf(StatType.class);
        String last = "No active training goal";
        String notice = "";
        OptionsDialogue panel;
        long lastMobileTick = -10;

        Controls(AutomationPreferences preferences) {
            this.preferences = preferences;
            enabled = preferences.enabled;
            goal = AutomationPolicy.validGoal(preferences.goal) ? preferences.goal : 99;
            StatType saved = preferences.selected == null ? null : parseSkill(preferences.selected);
            selected = saved == null || !supported(saved) ? StatType.Mining : saved;
            if (preferences.disabled != null) enabledSkills.removeAll(preferences.disabled);
        }

        void save() {
            preferences.enabled = enabled;
            preferences.goal = goal;
            preferences.selected = selected.name();
            EnumSet<StatType> disabled = EnumSet.allOf(StatType.class);
            disabled.removeAll(enabledSkills);
            preferences.disabled = disabled;
        }
    }

    private static final class Session {
        final Player player;
        final int goal;
        final long started;
        final Deque<StatType> work;
        final boolean progression;
        StatType skill;
        AutomationDriver driver;
        double segmentXp, earnedXp;
        boolean paused;
        String pauseReason = "";
        final Map<Item, Integer> slayerLoot = new IdentityHashMap<>();
        final int slayerCompletions;
        final Boss boss;
        int completed, bossKills;
        final EnumSet<StatType> blockedSkills = EnumSet.noneOf(StatType.class);
        boolean retried;
        long transitionDeadline;
        long nextStatus;

        Session(Player player, int goal, Deque<StatType> work, boolean progression, Boss boss, int bossKills, long tick) {
            this.player = player;
            this.goal = goal;
            this.work = work;
            this.progression = progression;
            this.boss = boss;
            this.bossKills = bossKills;
            this.slayerCompletions = SlayerAutomation.completions(player);
            skill = work.removeFirst();
            started = tick;
            nextStatus = tick + STATUS_EVERY;
        }

        void begin(long tick) {
            segmentXp = player.getStats().get(skill).experience;
            driver = boss != null ? boss.driver(player, bossKills, tick)
                    : skill == StatType.Slayer ? new SlayerAutomation(player, controls(player).preferences.slayer(),
                            tick, slayerLoot, slayerCompletions)
                    : OwnerAutomation.driver(player, skill, goal, tick);
        }

        double xp() {
            return earnedXp + (driver == null ? 0 : Math.max(0, player.getStats().get(skill).experience - segmentXp));
        }

        void release() {
            if (driver == null) return;
            earnedXp = xp();
            AutomationDriver current = driver;
            driver = null;
            if (boss != null) bossKills = current.remainingBossKills();
            current.stop();
        }
    }
}
