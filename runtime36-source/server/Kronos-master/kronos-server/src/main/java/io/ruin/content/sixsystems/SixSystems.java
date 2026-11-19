package io.ruin.content.sixsystems;

import io.ruin.model.entity.player.Player;
import io.ruin.model.stat.StatType;
import io.ruin.model.inter.handlers.OptionScroll;
import io.ruin.model.inter.utils.Option;
import java.util.*;

/** Entry points are world-thread only. Release flags are deliberately opt-in. */
public final class SixSystems {
    private SixSystems() {}
    private static final Set<String> KEYS = new HashSet<>(Arrays.asList("mastery","armor","legend","pets","athletics","athleticsSpeed","thanks"));
    public static boolean enabled(String key) {
        return KEYS.contains(key) && Boolean.parseBoolean(System.getProperty("kronos.six." + key, "false"));
    }
    public static String cosmetic(Player p) {
        StringBuilder label = new StringBuilder();
        if (enabled("legend") && p.sixBossState != null && p.sixBossState.active)
            label.append(Boolean.getBoolean("kronos.six.cosmeticIcons") ? "<img=80><col=e8c66a>[Legend] </col>" : "<col=e8c66a>[Legend] </col>");
        label.append(CommunityThanks.cosmetic(p));
        if (enabled("mastery")) {
            int highest = 0;
            for (StatType type : StatType.values()) highest = Math.max(highest, MasteryService.level(p.getStats().get(type)));
            if (enabled("athletics")) highest = Math.max(highest, Athletics.level(p));
            if (highest >= 100) label.append("<col=bb9bff>[").append(highest >= 120 ? "Transcendent" : highest >= 110 ? "Grandmaster" : highest >= 105 ? "Ascendant" : "Master").append("] </col>");
        }
        return label.toString();
    }
    public static void login(Player p) {
        io.ruin.content.pets.UniversalPets.initialize();
        MasteryService.migrate(p);
        if (enabled("thanks")) CommunityThanks.login(p);
        if (enabled("legend") || enabled("pets")) BossSystems.login(p);
        if (enabled("athletics")) Athletics.login(p);
    }
    public static void tick(Player p) {
        io.ruin.content.pets.UniversalPets.resume(p);
        if (enabled("thanks")) CommunityThanks.tick(p);
        if (enabled("legend") || enabled("pets")) BossSystems.tick(p);
        if (enabled("athletics")) Athletics.tick(p);
    }
    public static void logout(Player p) {
        io.ruin.content.pets.UniversalPets.logout(p);
        if (enabled("legend") || enabled("pets")) BossSystems.logout(p);
        if (enabled("athletics")) Athletics.logout(p);
    }
    public static void equipmentChanged(Player p) {
        if (enabled("legend")) BossSystems.equipmentChanged(p);
    }
    public static boolean command(Player p, String token, String full) {
        if (io.ruin.content.pets.UniversalPets.menu(p, token)) return true;
        switch (token) {
            case "progress": case "progression":
                OptionScroll.open(p, "Character progression", false,
                    new Option("Skill mastery", () -> mastery(p)),
                    new Option("Armour condition", () -> ArmorService.show(p)),
                    new Option("Legend", () -> SixText.show(p, "Legend", BossSystems.legendStatus(p).toArray(new String[0]))),
                    new Option("Boss pet progress", () -> SixText.show(p, "Boss pets", BossSystems.petStatus(p).toArray(new String[0]))),
                    new Option("Athletics", () -> SixText.show(p, "Athletics", Athletics.status(p))),
                    new Option("Community reputation", () -> CommunityThanks.status(p)));
                return true;
            case "mastery": mastery(p); return true;
            case "athletics": SixText.show(p, "Athletics", Athletics.status(p)); return true;
            case "legend": SixText.show(p, "Legend", BossSystems.legendStatus(p).toArray(new String[0])); return true;
            case "petprogress": SixText.show(p, "Boss pets", BossSystems.petStatus(p).toArray(new String[0])); return true;
            case "petclaim": BossSystems.claim(p); return true;
            case "condition": ArmorService.show(p); return true;
            case "reputation": CommunityThanks.status(p); return true;
            case "thank":
                if (!enabled("thanks")) p.sendMessage("Community thanks is not enabled on this world.");
                else CommunityThanks.thank(p, full.length() > token.length() ? full.substring(token.length()).trim() : "");
                return true;
            default: return false;
        }
    }
    private static void mastery(Player p) {
        List<Option> choices = new ArrayList<>();
        for (StatType type : StatType.values())
            choices.add(new Option(type.name(), () -> SixText.show(p, type.name() + " mastery", MasteryService.detail(p, type))));
        OptionScroll.open(p, "Skill mastery", false, choices);
    }
}
