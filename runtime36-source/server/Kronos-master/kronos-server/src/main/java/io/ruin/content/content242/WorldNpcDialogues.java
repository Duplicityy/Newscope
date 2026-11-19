package io.ruin.content.content242;

import io.ruin.Server;
import io.ruin.api.utils.TimeUtils;
import io.ruin.cache.NPCDef;
import io.ruin.model.World;
import io.ruin.model.entity.npc.NPC;
import io.ruin.model.entity.npc.NPCAction;
import io.ruin.model.entity.player.Player;
import io.ruin.model.inter.dialogue.NPCDialogue;
import io.ruin.model.inter.dialogue.OptionsDialogue;
import io.ruin.model.inter.utils.Option;
import io.ruin.model.map.Position;

/** Original training and direction advice for visible, verified world NPCs. */
final class WorldNpcDialogues {
    private WorldNpcDialogues() {}

    static int initialize() {
        int bound = 0;
        bound += tutor(3216, "Melee combat tutor", "What kind of fighter are you training to be?",
                "Choose a combat style", "Check the combat tab with your weapon equipped. Its styles show which skill you train. "
                        + "Controlled training spreads experience across Attack, Strength, and Defence.",
                "Prepare for a fight", "Bring food, use armour that suits your opponent, and retreat before your food runs out.");
        bound += tutor(3217, "Ranged combat tutor", "Pick your ammunition before you pick your fight.",
                "Match a bow and arrows", "Equip a bow your Ranged level allows and arrows that the bow supports. "
                        + "Read the equipment screen if the combination fails.",
                "Fight from a distance", "Find a clear line of sight. A long-range style, when your weapon offers one, "
                        + "can help you keep a little more space.");
        bound += tutor(3218, "Magic combat tutor", "The spellbook will tell you exactly what each spell needs.",
                "Prepare a spell", "Check the Magic level and runes in the spell description. An equipped elemental staff "
                        + "can supply its own element for a spell.",
                "Cast or autocast", "Select a combat spell and then the target, or use a compatible weapon's "
                        + "autocast controls to keep using that spell.");
        bound += tutor(3219, "Cooking tutor", "A cooked meal can save your life. Start with something simple.",
                "Cook your catch", "Use raw fish on a fire or range. Some ranges also let you choose Cook and pick a "
                        + "recipe from the menu.",
                "Avoid burnt food", "Recipes have Cooking requirements, and food you can attempt may still burn. "
                        + "Keep a few fish back if you need a meal for the road.");
        bound += tutor(3220, "Crafting tutor", "A small pot is a good way to learn both halves of pottery.",
                "Prepare soft clay", "Combine clay and water. Select a recipe at a pottery wheel to shape the soft clay.",
                "Finish your pottery", "Use the shaped piece on a pottery oven to finish it. Your Crafting level "
                        + "determines which shapes you can make.");
        bound += tutor(3221, "Fishing tutor", "Read the fishing spot's options before you unpack your gear.",
                "Choose a fishing method", "Choose a spot that offers the method you want. Check whether you need "
                        + "a net, rod, or another tool. Some methods also use bait or feathers.",
                "After a catch", "Keep room in your pack, and look for a nearby spot if the fish move on. Take your raw catch "
                        + "to a cooking fire or range when you are ready.");
        bound += tutor(3222, "Mining tutor", "Start by checking which ore is in the rock.",
                "Mine your first ore", "Use a pickaxe you can handle. Copper and tin are useful first ores and go "
                        + "together when you smelt bronze.",
                "When a rock runs out", "Move to another rock or wait for the vein to replenish. Higher-level rocks "
                        + "need a higher Mining level.");
        bound += tutor(3223, "Prayer tutor", "Prayer helps most when you choose it for the fight ahead.",
                "Train Prayer", "Bury bones to gain Prayer experience. New prayers unlock as your Prayer level increases.",
                "Restore your points", "Prayers can drain points while active. Switch them off when you no longer need "
                        + "them and visit an altar to recharge.");
        bound += tutor(3224, "Smithing apprentice", "The furnace makes the bar and the anvil works it. They're two different jobs.",
                "Smelt bronze", "Bring copper and tin to a furnace and select bronze. Keep space in your inventory for the bars.",
                "Work a bar", "Bring a hammer and bars to an anvil. The smithing menu lists the level and bars needed for each item.");
        bound += tutor(3225, "Master smithing tutor", "Decide what you want to make before you fill your pack.",
                "Plan your materials", "Check the required bar type and quantity at an anvil. For alloys, check the inputs "
                        + "at the furnace too.",
                "Use an anvil", "Carry a hammer. Select a product in the smithing menu and confirm your quantity. "
                        + "Keep enough space for the finished items.");
        bound += tutor(3226, "Woodsman tutor", "Choose a tree your skill and axe can handle.",
                "Gather logs", "Bring an axe and choose Chop down on a tree. Start on ordinary trees and move to "
                        + "other kinds as your Woodcutting level rises.",
                "Use the logs", "Logs can fuel a fire or be used for Fletching. Look for another tree if the one you "
                        + "are cutting falls before your pack is full.");
        bound += tutor(815, "Duke Horacio", "Welcome to Lumbridge. What are you looking for?",
                "Find the town", "The river bridge is east of the castle. The church is on the west bank, "
                        + "just south of the bridge.",
                "Find training advice", "You can speak to the combat tutors north of the castle and to the "
                        + "Prayer tutor near the churchyard.");
        bound += tutor(4287, "Border Guard", "The gate separates the Lumbridge road from the desert.",
                "Find Lumbridge", "Follow the road west and cross the river bridge to reach Lumbridge Castle.",
                "Find Al Kharid", "Al Kharid is on the other side of the gate. Approach it to see which "
                        + "crossing options are open to you.");
        bound += tutor(4288, "Border Guard", "Keep the crossing clear. Which side are you headed for?",
                "Find Lumbridge Castle", "After passing west through the gate, follow the road to the river bridge.",
                "Find the city", "Head east from the gate into Al Kharid.");
        bound += tutor(276, "Town crier", "Looking for a landmark in Varrock?",
                "Find the museum", "The museum is northeast of the square. Look for its entrance east of the palace grounds.",
                "Find the square", "Follow the street west toward the middle of the city. The square is between the shops "
                        + "and the palace approach.");
        bound += bindHans();
        // The indexed iterator is first populated by the world worker after startup.
        for (NPC npc : World.npcs.entityList) {
            if (npc == null || !npc.defaultSpawn) continue;
            switch (npc.getId()) {
                case 502:
                    bound += bindMerchant(npc, "Vanessa", 2820, 3461,
                            "If you are planning to work the patches around Catherby, take a look at my supplies.");
                    break;
                case 5423:
                    bound += bindMerchant(npc, "Garden supplier", 3012, 3373,
                            "I stock garden supplies here in the park. What would you like for your next planting?");
                    break;
                case 1328:
                    bound += bindMerchant(npc, "Trader Stan", 3036, 3193,
                            "You can browse my supplies before you head beyond the docks.");
                    break;
                default:
                    break;
            }
        }
        return bound;
    }

    private static int bindHans() {
        NPCDef def = NPCDef.get(3077);
        if (def == null || !"Hans".equals(def.name)) return 0;
        int age = def.getOption("Age"), talk = def.getOption("Talk-to");
        if (age < 1 || talk < 1 || action(def.defaultActions, age) != null || action(def.defaultActions, talk) != null) {
            return 0;
        }
        NPCAction.register(def.id, age, WorldNpcDialogues::showPlayTime);
        NPCAction.register(def.id, talk, (player, npc) -> player.dialogue(
                new NPCDialogue(npc, "I keep track of how much adventuring you've done. Want me to check?"),
                new OptionsDialogue("Time played",
                        new Option("How much time have I played?", p -> showPlayTime(p, npc)),
                        new Option("Leave"))));
        return 1;
    }

    private static void showPlayTime(Player player, NPC npc) {
        String played = TimeUtils.fromMs(player.playTime * Server.tickMs(), false);
        player.dialogue(new NPCDialogue(npc, "You've spent " + played + " playing in Newscape."));
    }

    private static int bindMerchant(NPC npc, String name, int x, int y, String greeting) {
        Position spawn = npc.getSpawnPosition();
        NPCDef def = npc.getDef();
        if (!name.equals(def.name) || spawn == null || spawn.getX() != x || spawn.getY() != y || spawn.getZ() != 0) return 0;
        int trade = def.getOption("Trade"), talk = def.getOption("Talk-to");
        if (trade < 1 || talk < 1 || action(npc.actions, trade) == null || action(npc.actions, talk) != null
                || action(def.defaultActions, talk) != null) return 0;
        NPCAction.register(npc, talk, (player, trader) -> offerStock(player, trader, trade, greeting));
        return 1;
    }

    static void offerStock(Player player, NPC trader, int tradeOption, String greeting) {
        int originalId = trader.getId();
        player.dialogue(new NPCDialogue(trader, greeting), new OptionsDialogue("Shop",
                new Option("Show me your stock", p -> {
                    if (p.isLocked() || trader.isRemoved() || trader.getId() != originalId) return;
                    if (!trader.getPosition().isWithinDistance(p.getPosition(), true, 3)) {
                        p.sendMessage("Speak to the trader again when you're nearby.");
                        return;
                    }
                    NPCAction trade = action(trader.actions, tradeOption);
                    if (trade == null) trade = action(trader.getDef().defaultActions, tradeOption);
                    if (trade != null) trade.handle(p, trader);
                }),
                new Option("Leave")));
    }

    private static NPCAction action(NPCAction[] actions, int option) {
        return actions == null || option < 1 || option > actions.length ? null : actions[option - 1];
    }

    private static int tutor(int id, String name, String greeting, String firstTopic, String firstReply,
                             String secondTopic, String secondReply) {
        NPCDef def = NPCDef.get(id);
        if (def == null || !name.equalsIgnoreCase(def.name)) return 0;
        int talk = def.getOption("Talk-to");
        if (talk < 1 || action(def.defaultActions, talk) != null) return 0;
        NPCAction.register(id, talk, (player, npc) -> player.dialogue(new NPCDialogue(npc, greeting),
                new OptionsDialogue(name,
                        new Option(firstTopic, p -> p.dialogue(new NPCDialogue(npc, firstReply))),
                        new Option(secondTopic, p -> p.dialogue(new NPCDialogue(npc, secondReply))),
                        new Option("Leave"))));
        return 1;
    }
}
