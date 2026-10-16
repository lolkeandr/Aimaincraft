package dev.createguide;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.Minecraft;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Инструменты, которые нейросеть может вызывать. Выполняются в потоке клиента Minecraft. */
public final class Tools {
    private static RecipeManager cachedManager;
    private static final Map<ResourceLocation, JsonElement> CACHE = new HashMap<>();

    private Tools() {}

    /** Вызывается из рабочего потока: переносит выполнение в поток клиента и ждёт результат. */
    public static String execute(String name, JsonObject args, List<RecipeCard> cards) {
        Minecraft mc = Minecraft.getInstance();
        CompletableFuture<String> f = new CompletableFuture<>();
        mc.execute(() -> {
            try {
                f.complete(run(name, args, cards));
            } catch (Throwable t) {
                CreateGuide.LOGGER.warn("Ошибка инструмента " + name, t);
                f.complete("Ошибка инструмента: " + t);
            }
        });
        try {
            return f.get(20, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "Ошибка: инструмент не ответил вовремя (" + e + ")";
        }
    }

    private static String run(String name, JsonObject args, List<RecipeCard> cards) {
        Level level = Minecraft.getInstance().level;
        if (level == null) return "Мир не загружен.";
        return switch (name) {
            case "find_items" -> findItems(str(args, "query"));
            case "get_recipes" -> getRecipes(level, str(args, "item_id"), str(args, "mode"));
            case "show_recipe" -> showRecipe(level, str(args, "recipe_id"), cards);
            default -> "Неизвестный инструмент: " + name;
        };
    }

    private static String str(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
    }

    // ---------- find_items ----------

    private static String findItems(String query) {
        String q = query.toLowerCase(Locale.ROOT).trim();
        if (q.isEmpty()) return "Пустой запрос.";
        String[] tokens = q.split("\\s+");
        List<String> out = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
            String name = item.getDescription().getString();
            String hay = (key + " " + name).toLowerCase(Locale.ROOT);
            boolean ok = true;
            for (String t : tokens) {
                if (!hay.contains(t)) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                out.add(key + " — " + name);
                if (out.size() >= 30) break;
            }
        }
        if (out.isEmpty()) return "Ничего не найдено. Попробуй другое слово (название на языке игры или часть английского id).";
        return String.join("\n", out);
    }

    // ---------- get_recipes ----------

    private static String getRecipes(Level level, String itemId, String mode) {
        ResourceLocation rl = ResourceLocation.tryParse(itemId);
        if (rl == null || BuiltInRegistries.ITEM.getOptional(rl).isEmpty()) {
            return "Нет предмета с id '" + itemId + "'. Сначала найди точный id через find_items.";
        }
        boolean make = !"use".equals(mode);
        RecipeManager rm = level.getRecipeManager();
        RegistryAccess ra = level.registryAccess();
        String id = rl.toString();

        List<String> lines = new ArrayList<>();
        for (RecipeHolder<?> h : rm.getRecipes()) {
            JsonElement json = jsonFor(rm, h, ra);
            if (json == null || !mentions(json, id, make)) continue;
            String s = json.toString();
            if (s.length() > 1500) s = s.substring(0, 1500) + "…";
            lines.add("recipe_id: " + h.id() + "\n" + s);
            if (lines.size() >= 10) break;
        }
        if (lines.isEmpty()) {
            return make ? "Рецептов, дающих " + id + ", не найдено (возможно, предмет добывается в мире, выпадает с мобов или из лута)."
                    : "Рецептов, где " + id + " нужен как ингредиент, не найдено (возможно, он используется через тег).";
        }
        return String.join("\n\n", lines);
    }

    private static boolean mentions(JsonElement json, String id, boolean asOutput) {
        if (!json.isJsonObject()) return false;
        boolean inOut = false;
        boolean inIn = false;
        for (Map.Entry<String, JsonElement> e : json.getAsJsonObject().entrySet()) {
            String k = e.getKey();
            if (k.equals("type")) continue;
            boolean outKey = k.equals("result") || k.equals("results") || k.equals("output") || k.equals("outputs");
            if (containsString(e.getValue(), id)) {
                if (outKey) inOut = true;
                else inIn = true;
            }
        }
        return asOutput ? inOut : inIn;
    }

    private static boolean containsString(JsonElement e, String s) {
        if (e == null) return false;
        if (e.isJsonPrimitive()) return e.getAsString().equals(s);
        if (e.isJsonArray()) {
            for (JsonElement x : e.getAsJsonArray()) if (containsString(x, s)) return true;
            return false;
        }
        if (e.isJsonObject()) {
            for (Map.Entry<String, JsonElement> x : e.getAsJsonObject().entrySet()) {
                if (containsString(x.getValue(), s)) return true;
            }
        }
        return false;
    }

    // ---------- show_recipe ----------

    private static String showRecipe(Level level, String recipeId, List<RecipeCard> cards) {
        ResourceLocation rl = ResourceLocation.tryParse(recipeId);
        if (rl == null) return "Некорректный recipe_id.";
        RecipeManager rm = level.getRecipeManager();
        Optional<RecipeHolder<?>> opt = rm.byKey(rl);
        if (opt.isEmpty()) return "Рецепт " + recipeId + " не найден. Используй recipe_id из результата get_recipes.";
        RecipeHolder<?> holder = opt.get();
        JsonElement json = jsonFor(rm, holder, level.registryAccess());
        cards.add(RecipeCard.from(holder, level, json));
        return "Рецепт показан игроку в окне.";
    }

    // ---------- JSON рецептов ----------

    private static JsonElement jsonFor(RecipeManager rm, RecipeHolder<?> h, RegistryAccess ra) {
        if (cachedManager != rm) {
            CACHE.clear();
            cachedManager = rm;
        }
        return CACHE.computeIfAbsent(h.id(), k -> encode(h.value(), ra));
    }

    private static JsonElement encode(Recipe<?> r, RegistryAccess ra) {
        try {
            RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, ra);
            return Recipe.CODEC.encodeStart(ops, r).result().orElse(null);
        } catch (Throwable t) {
            return null;
        }
    }
}
