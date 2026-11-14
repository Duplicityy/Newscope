package io.ruin.content;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.ruin.Server;
import io.ruin.model.entity.player.Player;
import io.ruin.model.map.Position;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Optional content access, enabled only by the active data folder's manifest. */
public final class Content242 {
    private static final long MAX_MANIFEST_BYTES = 32 * 1024 * 1024;
    private static final int MAX_RESULTS = 100;

    private Content242() {
    }

    /** The command handler must pass this destination through its normal teleport checks. */
    public static Position destination() {
        JsonObject manifest = load();
        if (manifest == null)
            return null;
        JsonArray landing = manifest.getAsJsonArray("landing");
        return new Position(integer(landing.get(0)), integer(landing.get(1)), integer(landing.get(2)));
    }

    public static void showEquipment(Player player, String search) {
        JsonObject manifest = load();
        if (manifest == null) {
            player.sendMessage("The new equipment catalogue is not available on this world.");
            return;
        }
        String filter = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        List<String> lines = new ArrayList<>();
        lines.add("Equipment with supported appearance and basic stats.");
        lines.add("Special effects vary by item; see the release notes for supported effects.");
        lines.add("Search by name or item ID: ::newitems torva");
        lines.add("");
        int matches = 0;
        Set<Integer> seen = new HashSet<>();
        JsonArray equipment = manifest.getAsJsonArray("equipment");
        for (JsonElement element : equipment) {
            try {
                JsonObject entry = element.getAsJsonObject();
                int id = integer(entry.get("id"));
                String status = string(entry.get("status"));
                String name = displayName(string(entry.get("name")));
                if (id < 0 || id > 65534 || name.isEmpty()
                        || !("basic-equipment".equals(status) || "basic-equipment-rounded-magic".equals(status)))
                    continue;
                if (!(filter.isEmpty() || name.toLowerCase(Locale.ROOT).contains(filter)
                        || Integer.toString(id).equals(filter)) || !seen.add(id))
                    continue;
                if (++matches <= MAX_RESULTS)
                    lines.add(name + " (" + id + ")");
            } catch (RuntimeException ignored) {
                // A malformed or unsupported catalogue entry grants no access.
            }
        }
        if (matches == 0)
            lines.add("No supported equipment matches that search.");
        else if (matches > MAX_RESULTS)
            lines.add("Showing " + MAX_RESULTS + " of " + matches + ". Use a more specific search.");
        else
            lines.add(matches + " matching equipment item" + (matches == 1 ? "." : "s."));
        player.sendScroll("New equipment", lines.toArray(new String[0]));
    }

    private static JsonObject load() {
        try {
            if (Server.dataFolder == null)
                return null;
            File file = new File(Server.dataFolder, "content242.json");
            if (!file.isFile() || file.length() == 0 || file.length() > MAX_MANIFEST_BYTES)
                return null;
            JsonObject manifest = new JsonParser().parse(
                    new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)).getAsJsonObject();
            JsonElement enabled = manifest.get("enabled");
            if (enabled == null || !enabled.isJsonPrimitive() || !enabled.getAsJsonPrimitive().isBoolean()
                    || !enabled.getAsBoolean())
                return null;
            JsonArray landing = manifest.getAsJsonArray("landing");
            if (landing == null || landing.size() != 3)
                return null;
            int x = integer(landing.get(0)), y = integer(landing.get(1)), plane = integer(landing.get(2));
            if (x < 0 || x > 16383 || y < 0 || y > 16383 || plane < 0 || plane > 3)
                return null;
            if (!manifest.has("equipment") || !manifest.get("equipment").isJsonArray())
                return null;
            return manifest;
        } catch (Exception ignored) {
            // Missing, disabled, or invalid manifests leave the feature unavailable.
            return null;
        }
    }

    private static int integer(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("Expected integer");
        return value.getAsBigDecimal().intValueExact();
    }

    private static String string(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException("Expected string");
        return value.getAsString();
    }

    private static String displayName(String name) {
        String text = name.replaceAll("[<>\\p{Cntrl}]", "").trim();
        return text.length() > 120 ? text.substring(0, 120) : text;
    }
}
