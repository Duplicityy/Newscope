package io.ruin.data.impl;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.ruin.data.DataFile;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Authored Newscape descriptions for named NPC and scenery families in the installed world. */
public final class world_examines extends DataFile {
    private static volatile Text current = new Text(Collections.emptyMap(), Collections.emptyMap());

    @Override
    public String path() {
        return "world-examines.json";
    }

    @Override
    public Object fromJson(String fileName, String json) {
        JsonObject root = new JsonParser().parse(json).getAsJsonObject();
        if (!"1".equals(root.get("schema").getAsString()))
            throw new IllegalArgumentException("Unsupported world examine schema");
        Text loaded = new Text(read(root.getAsJsonObject("npcs")), read(root.getAsJsonObject("objects")));
        current = loaded;
        return root;
    }

    public static String npc(String name) {
        return current.npcs.get(key(name));
    }

    public static String object(String name) {
        return current.objects.get(key(name));
    }

    private static Map<String, String> read(JsonObject entries) {
        Map<String, String> descriptions = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : entries.entrySet()) {
            String name = key(entry.getKey());
            JsonElement value = entry.getValue();
            if (name.isEmpty() || name.equals("null") || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
                throw new IllegalArgumentException("Invalid examine entry: " + entry.getKey());
            String description = value.getAsString().trim();
            if (description.isEmpty() || description.length() > 160 || description.indexOf('<') >= 0
                    || description.chars().anyMatch(Character::isISOControl) || name.equals(key(description)))
                throw new IllegalArgumentException("Invalid examine text for " + entry.getKey());
            if (descriptions.putIfAbsent(name, description) != null)
                throw new IllegalArgumentException("Duplicate examine name: " + entry.getKey());
        }
        return Collections.unmodifiableMap(descriptions);
    }

    private static String key(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    private static final class Text {
        final Map<String, String> npcs;
        final Map<String, String> objects;

        Text(Map<String, String> npcs, Map<String, String> objects) {
            this.npcs = npcs;
            this.objects = objects;
        }
    }
}
