import io.ruin.model.activities.automation.AutomationActionSource;
import io.ruin.model.entity.Entity;
import io.ruin.model.entity.player.Player;
import io.ruin.process.event.Event;
import io.ruin.process.event.EventType;

import java.lang.reflect.Field;
import sun.misc.Unsafe;

/** Tests the selected Entity overlay without starting a server or using an account. */
public final class OwnerAutomationLeaseChecks {
    public static void main(String[] args) throws Exception {
        Class.forName("io.ruin.network.incoming.IncomingDecoder", false, OwnerAutomationLeaseChecks.class.getClassLoader());
        Class.forName("io.ruin.network.incoming.handlers.CommandHandler", false, OwnerAutomationLeaseChecks.class.getClassLoader());
        Class.forName("io.ruin.model.activities.dungeoneering.IDungeon", false, OwnerAutomationLeaseChecks.class.getClassLoader());
        Class.forName("io.ruin.model.activities.raids.auto.RaidAuto", false, OwnerAutomationLeaseChecks.class.getClassLoader());
        Field allocator = Unsafe.class.getDeclaredField("theUnsafe");
        allocator.setAccessible(true);
        Player player = (Player) ((Unsafe) allocator.get(null)).allocateInstance(Player.class);
        AutomationActionSource owner = (AutomationActionSource) (Object) player;
        Field active = Entity.class.getDeclaredField("activeEvent");
        Field next = Entity.class.getDeclaredField("nextEvent");
        active.setAccessible(true);
        next.setAccessible(true);
        Event mining = new Event(event -> {});
        Event manual = new Event(event -> {});
        Event pending = new Event(event -> {});
        Event locked = new Event(event -> {});
        locked.eventType = EventType.PERSISTENT;
        active.set(player, mining);
        next.set(player, pending);
        check(owner.ownerAutomationAction() == pending, "Queued action takes precedence");
        check(!owner.cancelOwnerAutomationAction(manual), "Foreign reference preserved");
        check(owner.cancelOwnerAutomationAction(pending), "Cancel one queued action");
        check(owner.ownerAutomationAction() == mining, "Active action remains");
        check(owner.cancelOwnerAutomationAction(mining), "Cancel owned active action");
        check(owner.ownerAutomationAction() == null, "No remaining action");
        check(!owner.cancelOwnerAutomationAction(null), "Empty token rejected");
        active.set(player, locked);
        check(!owner.cancelOwnerAutomationAction(locked), "Persistent native action preserved");
        check(owner.ownerAutomationAction() == locked, "Persistent action remains untouched");
        com.google.gson.Gson gson = new com.google.gson.GsonBuilder().excludeFieldsWithoutExposeAnnotation().create();
        io.ruin.model.activities.automation.AutomationPreferences.Source stored =
                (io.ruin.model.activities.automation.AutomationPreferences.Source) (Object) player;
        Field field = io.ruin.model.entity.player.PlayerAttributes.class.getDeclaredField("ownerAutomationPreferences");
        field.setAccessible(true);
        check(field.isAnnotationPresent(com.google.gson.annotations.Expose.class), "Visible in ordinary native player saves");
        Class<?> preferenceType = io.ruin.model.activities.automation.AutomationPreferences.class;
        Object custom = gson.fromJson("{\"enabled\":false,\"goal\":61,\"selected\":\"Thieving\",\"disabled\":[\"Mining\"]}", preferenceType);
        field.set(player, custom);
        check(stored.automationPreferences() == custom, "Saved preference instance returned");
        String record = gson.toJson(stored.automationPreferences());
        check(record.contains("\"goal\":61") && record.contains("Thieving") && record.contains("Mining")
                && record.contains("\"enabled\":false"), "Selected settings round-trip");
        check(!record.contains("active") && !record.contains("Session"), "Action state never serialized");
        field.set(player, null);
        check(gson.toJson(stored.automationPreferences()).contains("\"goal\":99"), "Legacy account initializes defaults");
        System.out.println("PASS Entity action ownership, preferences round-trip and selected patched classes verified");
    }

    private static void check(boolean value, String failure) {
        if (!value) throw new AssertionError(failure);
    }
}
