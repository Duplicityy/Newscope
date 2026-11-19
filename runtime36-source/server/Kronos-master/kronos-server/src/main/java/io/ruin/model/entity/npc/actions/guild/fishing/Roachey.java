package io.ruin.model.entity.npc.actions.guild.fishing;

import io.ruin.cache.NpcID;
import io.ruin.model.entity.npc.NPCAction;
import io.ruin.model.inter.dialogue.NPCDialogue;
import io.ruin.model.inter.dialogue.OptionsDialogue;
import io.ruin.model.inter.dialogue.PlayerDialogue;
import io.ruin.model.inter.utils.Option;
import io.ruin.model.shop.ShopManager;

public class Roachey {

    private static final String SHOP_ID = "4ea4cb73-305a-445c-a7ab-131a90a1d8b5";

    static {
        NPCAction.register(NpcID.ROACHEY, "talk-to", (player, npc) -> player.dialogue(
                new NPCDialogue(npc, "Would you like to buy some fishing equipment?"),
                new OptionsDialogue(
                        new Option("Yes please.", () -> ShopManager.openIfExists(player, SHOP_ID)),
                        new Option("No thank you.", () -> player.dialogue(new PlayerDialogue("No thank you.")))
                )
        ));
    }
}
