import com.google.gson.GsonBuilder;
import io.ruin.content.pets.UniversalPets.Save;
import io.ruin.model.entity.npc.NPCCombat;
import io.ruin.model.entity.player.Player;
import io.ruin.model.entity.player.PlayerAttributes;
import io.ruin.model.item.actions.impl.Pet;
import io.ruin.utility.LocalAccounts;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;

public final class VerifyPetCandidate {
    public static void main(String[] args) throws Exception {
        Path state = Paths.get(args[0]);
        LocalAccounts accounts = new LocalAccounts(state.resolve("accounts.properties"), false);
        if (accounts.authenticate("pet", "pet") == null || accounts.authenticate("pet", "wrong") != null
                || accounts.authenticate("other", "pet") != null) throw new AssertionError("Isolated account authentication");
        GsonBuilder gson = new GsonBuilder().excludeFieldsWithoutExposeAnnotation();
        Save probe = new Save();
        probe.owned.add(70);
        probe.pending.add(70);
        probe.active = 70;
        Save restored = gson.create().fromJson(gson.create().toJson(probe), Save.class);
        if (!restored.owned.equals(probe.owned) || !restored.pending.equals(probe.pending) || restored.active != 70)
            throw new AssertionError("Pet save round trip");
        for (Class<?> type : new Class<?>[]{PlayerAttributes.class, NPCCombat.class, Pet.class,
                Class.forName("io.ruin.model.activities.raids.toa.port.NativeToaEncounter", false, Player.class.getClassLoader()),
                Class.forName("io.ruin.model.inter.loot.LootCompendium", false, Player.class.getClassLoader())})
            if (type.getDeclaredMethods().length == 0) throw new AssertionError(type.getName());
        // Fixture shape uses the same exposed fields as the production save model.
        try (Reader in = Files.newBufferedReader(state.resolve("player-data/players/pet.json"))) {
            com.google.gson.JsonObject snapshot = new com.google.gson.JsonParser().parse(in).getAsJsonObject();
            Save pet = gson.create().fromJson(snapshot.get("universalPets"), Save.class);
            com.google.gson.JsonArray bank = snapshot.getAsJsonObject("bank").getAsJsonArray("items");
            if (bank.size() != 800 || pet.owned.size() != 2460 || pet.pending.size() != 1782
                    || !pet.owned.containsAll(pet.pending)) throw new AssertionError("Fixture coverage");
            HashSet<Integer> unique = new HashSet<>();
            for (com.google.gson.JsonElement slot : bank)
                if (!unique.add(slot.getAsJsonObject().get("id").getAsInt())) throw new AssertionError("Duplicate bank item");
        }
        System.out.println("PASS test-account authentication, exposed pet save round trip, bank/overflow counts and JVM linkage");
    }
}


