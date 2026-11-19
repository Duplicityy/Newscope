import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.ruin.Server;
import io.ruin.api.utils.TimeUtils;
import io.ruin.cache.NPCDef;
import io.ruin.model.entity.npc.NPC;
import io.ruin.model.entity.npc.NPCAction;
import io.ruin.model.entity.player.Player;
import io.ruin.model.inter.Interface;
import io.ruin.model.inter.InterfaceHandler;
import io.ruin.model.inter.dialogue.NPCDialogue;
import io.ruin.model.map.Position;
import io.ruin.model.map.Region;
import io.ruin.model.shop.Shop;
import io.ruin.model.shop.ShopManager;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

/** Exercise dialogue choices using the detached native world-access fixture. */
public final class WorldNpcDialogueChecks {
    private WorldNpcDialogueChecks() {}

    public static void run(Path stage, Player player) throws Exception {
        Position previous = player.getPosition().copy();
        long originalPlayTime = player.playTime;
        NPC artima = new NPC(13342).spawn(3230, 3214, 0);
        NPC prayerTutor = new NPC(3223).spawn(3230, 3215, 0);
        NPC hans = new NPC(3077).spawn(3219, 3218, 0);
        Region.get(Region.getId(2820, 3461)).init();
        NPC vanessa = new NPC(502).spawn(2820, 3461, 0);
        vanessa.defaultSpawn = true;
        try {
            Shop shop = loadShop(stage, "content242-shop-1023-0.yaml");
            Shop farming = loadShop(stage, "Farmer's_Shop.yaml");
            NPCAction.register(vanessa, "Trade", (p, npc) -> ShopManager.openIfExists(p, farming.identifier));
            Runnable bindArtima = () -> bindShop(artima.getId(), 3, shop.identifier);
            bindArtima.run();
            player.getPosition().set(3229, 3214, 0);
            chooseStock(player, artima);
            check(player.viewingShop == shop, "Merchant dialogue uses the installed shop");
            player.closeInterfaces();

            NPCAction nativeStock = artima.getDef().defaultActions[2];
            NPCAction nativeTalk = artima.getDef().defaultActions[0];
            bindArtima.run();
            check(artima.getDef().defaultActions[0] == nativeTalk
                    && artima.getDef().defaultActions[2] == nativeStock, "Existing registrations survive a second load");

            AtomicInteger instanceTrade = new AtomicInteger();
            NPCAction.register(artima, "Trade", (p, npc) -> instanceTrade.incrementAndGet());
            chooseStock(player, artima);
            check(instanceTrade.get() == 1 && player.viewingShop == null, "Per-spawn Trade takes priority");
            openStockDialogue(player, artima);
            player.getPosition().set(3230, 3214, 1);
            choose(player, 1);
            check(instanceTrade.get() == 1, "Moved player cannot use a stale conversation");

            Class<?> tutorDialogs = Class.forName("io.ruin.content.content242.WorldNpcDialogues");
            Method load = tutorDialogs.getDeclaredMethod("initialize");
            load.setAccessible(true);
            int added = (Integer) load.invoke(null);
            check(added >= 10, "Verified tutors and guides installed");
            int talk = prayerTutor.getDef().getOption("Talk-to");
            NPCAction prayerAction = prayerTutor.getDef().defaultActions[talk - 1];
            check(prayerAction != null && (Integer) load.invoke(null) == 0, "Tutor registration is idempotent");
            player.getPosition().set(3229, 3215, 0);
            prayerAction.handle(player, prayerTutor);
            check(player.lastDialogue instanceof NPCDialogue, "Tutor uses a native NPC conversation");
            player.continueDialogue();
            choose(player, 2);
            check(player.lastDialogue instanceof NPCDialogue, "Tutor topic returns its specific advice");

            player.closeInterfaces();
            player.getPosition().set(2820, 3460, 0);
            int farmingTalk = vanessa.getDef().getOption("Talk-to");
            check(vanessa.actions[farmingTalk - 1] != null, "Vanessa's verified spawn uses her farming shop dialogue");
            vanessa.actions[farmingTalk - 1].handle(player, vanessa);
            player.continueDialogue();
            choose(player, 1);
            check(player.viewingShop == farming, "Vanessa's Talk-to reaches her existing per-spawn shop");

            player.closeInterfaces();
            player.getPosition().set(3218, 3218, 0);
            player.playTime = 12345;
            int age = hans.getDef().getOption("Age");
            hans.getDef().defaultActions[age - 1].handle(player, hans);
            Field text = NPCDialogue.class.getDeclaredField("message");
            text.setAccessible(true);
            String expectedTime = TimeUtils.fromMs(player.playTime * Server.tickMs(), false);
            check(((String) text.get(player.lastDialogue)).contains(expectedTime), "Hans uses recorded Newscape play time");
            System.out.println("PASS tutor topic, merchant stock, native and per-spawn precedence, stale dialogue, and Hans's play time");
        } finally {
            player.closeInterfaces();
            player.playTime = originalPlayTime;
            player.getPosition().set(previous.getX(), previous.getY(), previous.getZ());
            artima.remove();
            prayerTutor.remove();
            hans.remove();
            vanessa.remove();
        }
    }

    private static Shop loadShop(Path stage, String fileName) throws Exception {
        Shop shop = new ObjectMapper(new YAMLFactory()).readValue(stage.resolve("data/shops").resolve(fileName).toFile(), Shop.class);
        shop.init();
        shop.populate();
        ShopManager.getShops().put(shop.identifier, shop);
        return shop;
    }

    private static void bindShop(int id, int trade, String identifier) {
        try {
            Class<?> world = Class.forName("io.ruin.content.content242.World242");
            Class<?> binding = Class.forName(world.getName() + "$ShopBinding");
            Constructor<?> constructor = binding.getDeclaredConstructor(int.class, int.class, java.util.List.class);
            constructor.setAccessible(true);
            Object entry = constructor.newInstance(id, trade, Collections.singletonList(identifier));
            Method bind = world.getDeclaredMethod("bindShop", binding);
            bind.setAccessible(true);
            bind.invoke(null, entry);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void chooseStock(Player player, NPC trader) {
        openStockDialogue(player, trader);
        choose(player, 1);
    }

    private static void openStockDialogue(Player player, NPC trader) {
        NPCDef def = trader.getDef();
        def.defaultActions[def.getOption("Talk-to") - 1].handle(player, trader);
        check(player.lastDialogue instanceof NPCDialogue, "Merchant uses a native NPC conversation");
        player.continueDialogue();
    }

    private static void choose(Player player, int index) {
        check(player.optionsDialogue != null, "Expected a native options menu");
        InterfaceHandler.getAction(player, Interface.OPTIONS_DIALOGUE, 1).handleClick(player, 1, index, -1);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
