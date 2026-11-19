package io.ruin.model.entity.npc.actions.traveling;

import io.ruin.model.entity.npc.NPC;
import io.ruin.model.entity.npc.NPCAction;
import io.ruin.model.entity.player.Player;
import io.ruin.model.inter.dialogue.NPCDialogue;
import io.ruin.model.inter.dialogue.OptionsDialogue;
import io.ruin.model.inter.utils.Option;

/**
 * @author ReverendDread on 7/19/2020
 * https://www.rune-server.ee/members/reverenddread/
 * @project Kronos
 */
public class LokarSearunner {

    private static final int LOKAR = 3855;

    static {
        NPCAction.register(LOKAR, "Talk-to", (player, npc) -> {
            String destination = atCove(npc) ? "Rellekka" : "Pirates' Cove";
            player.dialogue(new NPCDialogue(npc, "I sail between here and " + destination + ". Need a passage?"),
                    new OptionsDialogue("Sail with Lokar",
                            new Option("Sail to " + destination, p -> sail(p, npc)),
                            new Option("Stay here")));
        });
        NPCAction.register(LOKAR, "Travel", LokarSearunner::sail);
    }

    private static boolean atCove(NPC npc) {
        return npc.getPosition().equals(2214, 3794, 0);
    }

    private static void sail(Player player, NPC npc) {
        if (atCove(npc)) Traveling.fadeTravel(player, 2621, 3686, 0);
        else Traveling.fadeTravel(player, 2213, 3794, 0);
    }

}
