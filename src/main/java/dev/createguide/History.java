package dev.createguide;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** История диалога, хранится в config/createguide-history.json */
public final class History {
    public record Entry(String role, String text) {}

    private static final List<Entry> ENTRIES = new ArrayList<>();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private History() {}

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("createguide-history.json");
    }

    public static synchronized void load() {
        ENTRIES.clear();
        Path f = file();
        if (!Files.exists(f)) return;
        try {
            JsonArray arr = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonArray();
            for (JsonElement e : arr) {
                JsonObject o = e.getAsJsonObject();
                ENTRIES.add(new Entry(o.get("role").getAsString(), o.get("text").getAsString()));
            }
        } catch (Exception ex) {
            CreateGuide.LOGGER.warn("Не удалось прочитать историю диалога", ex);
        }
    }

    private static void save() {
        try {
            JsonArray arr = new JsonArray();
            for (Entry e : ENTRIES) {
                JsonObject o = new JsonObject();
                o.addProperty("role", e.role());
                o.addProperty("text", e.text());
                arr.add(o);
            }
            Files.writeString(file(), GSON.toJson(arr), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            CreateGuide.LOGGER.warn("Не удалось сохранить историю диалога", ex);
        }
    }

    public static synchronized List<Entry> all() {
        return new ArrayList<>(ENTRIES);
    }

    /** Последние n сообщений для отправки модели (начинаются с сообщения пользователя). */
    public static synchronized List<Entry> last(int n) {
        List<Entry> out = new ArrayList<>();
        if (n <= 0) return out;
        int from = Math.max(0, ENTRIES.size() - n);
        out.addAll(ENTRIES.subList(from, ENTRIES.size()));
        while (!out.isEmpty() && !out.get(0).role().equals("user")) out.remove(0);
        return out;
    }

    public static synchronized void add(String role, String text) {
        ENTRIES.add(new Entry(role, text));
        int keep = Config.HISTORY_KEEP.get();
        while (ENTRIES.size() > keep) ENTRIES.remove(0);
        save();
    }

    public static synchronized void clear() {
        ENTRIES.clear();
        save();
    }
}
