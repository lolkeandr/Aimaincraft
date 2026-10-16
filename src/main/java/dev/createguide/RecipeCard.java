package dev.createguide;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Данные для наглядного показа рецепта в окне помощника. */
public final class RecipeCard {
    public record Slot(List<ItemStack> options, int count) {}

    private static final Set<String> OUT_KEYS = Set.of("result", "results", "output", "outputs");

    public String typeLabel = "";
    /** Если gridW > 0 — рисуем сетку верстака, в inputs могут быть null (пустые ячейки). */
    public int gridW = 0;
    public int gridH = 0;
    public final List<Slot> inputs = new ArrayList<>();
    public final List<Slot> outputs = new ArrayList<>();
    public final List<String> extras = new ArrayList<>();

    public static RecipeCard from(RecipeHolder<?> holder, Level level, JsonElement json) {
        Recipe<?> r = holder.value();
        RecipeCard c = new RecipeCard();

        ResourceLocation type = BuiltInRegistries.RECIPE_TYPE.getKey(r.getType());
        c.typeLabel = type != null ? type.toString() : "recipe";

        // Входы
        if (r instanceof ShapedRecipe sr) {
            c.gridW = sr.getWidth();
            c.gridH = sr.getHeight();
            for (Ingredient ing : sr.getIngredients()) {
                List<ItemStack> opts = ing.isEmpty() ? List.of() : optionsOf(ing);
                c.inputs.add(opts.isEmpty() ? null : new Slot(opts, 1));
            }
        } else {
            Map<String, Slot> grouped = new LinkedHashMap<>();
            for (Ingredient ing : r.getIngredients()) {
                if (ing.isEmpty()) continue;
                List<ItemStack> opts = optionsOf(ing);
                if (opts.isEmpty()) continue;
                StringBuilder key = new StringBuilder();
                for (ItemStack s : opts) key.append(BuiltInRegistries.ITEM.getKey(s.getItem())).append(',');
                grouped.merge(key.toString(), new Slot(opts, 1), (a, b) -> new Slot(a.options(), a.count() + 1));
            }
            c.inputs.addAll(grouped.values());
        }

        // Выходы: сначала пробуем JSON (там все результаты, в том числе у Create), потом стандартный способ
        if (json != null && json.isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : json.getAsJsonObject().entrySet()) {
                if (OUT_KEYS.contains(e.getKey())) collectStacks(e.getValue(), c.outputs, c.extras);
            }
        }
        if (c.outputs.isEmpty()) {
            try {
                ItemStack res = r.getResultItem(level.registryAccess());
                if (!res.isEmpty()) c.outputs.add(new Slot(List.of(res.copy()), res.getCount()));
            } catch (Throwable ignored) {
                // не критично
            }
        }

        // Дополнительные условия: нагрев, жидкости, время
        if (json != null && json.isJsonObject()) {
            JsonObject o = json.getAsJsonObject();
            if (o.has("heat_requirement") && o.get("heat_requirement").isJsonPrimitive()) {
                c.extras.add("Нагрев: " + o.get("heat_requirement").getAsString());
            }
            if (o.has("processing_time") && o.get("processing_time").isJsonPrimitive()) {
                c.extras.add("Время обработки: " + o.get("processing_time").getAsString() + " тиков");
            }
            for (Map.Entry<String, JsonElement> e : o.entrySet()) {
                if (e.getKey().equals("type")) continue;
                String dir = OUT_KEYS.contains(e.getKey()) ? "на выходе" : "на входе";
                collectFluids(e.getValue(), dir, c.extras);
            }
        }
        return c;
    }

    private static List<ItemStack> optionsOf(Ingredient ing) {
        List<ItemStack> list = new ArrayList<>();
        for (ItemStack s : ing.getItems()) {
            if (s.isEmpty()) continue;
            list.add(s.copy());
            if (list.size() >= 12) break;
        }
        return list;
    }

    private static void collectStacks(JsonElement e, List<Slot> out, List<String> extras) {
        if (e == null) return;
        if (e.isJsonArray()) {
            for (JsonElement x : e.getAsJsonArray()) collectStacks(x, out, extras);
        } else if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            String id = null;
            if (o.has("id") && o.get("id").isJsonPrimitive()) id = o.get("id").getAsString();
            else if (o.has("item") && o.get("item").isJsonPrimitive()) id = o.get("item").getAsString();
            if (id == null) return;
            Item item = itemById(id);
            if (item == null) return;
            int count = o.has("count") && o.get("count").isJsonPrimitive() ? o.get("count").getAsInt() : 1;
            out.add(new Slot(List.of(new ItemStack(item, Math.max(1, count))), Math.max(1, count)));
            if (o.has("chance") && o.get("chance").isJsonPrimitive()) {
                int pct = (int) Math.round(o.get("chance").getAsDouble() * 100);
                extras.add("Шанс " + pct + "%: " + new ItemStack(item).getHoverName().getString());
            }
        } else if (e.isJsonPrimitive()) {
            Item item = itemById(e.getAsString());
            if (item != null) out.add(new Slot(List.of(new ItemStack(item)), 1));
        }
    }

    private static Item itemById(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) return null;
        return BuiltInRegistries.ITEM.getOptional(rl).orElse(null);
    }

    private static void collectFluids(JsonElement e, String dir, List<String> extras) {
        if (e == null) return;
        if (e.isJsonArray()) {
            JsonArray arr = e.getAsJsonArray();
            for (JsonElement x : arr) collectFluids(x, dir, extras);
        } else if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            if (o.has("fluid") && o.get("fluid").isJsonPrimitive()) {
                String fluid = o.get("fluid").getAsString();
                int sep = fluid.indexOf(':');
                String shown = sep >= 0 ? fluid.substring(sep + 1) : fluid;
                String amount = o.has("amount") && o.get("amount").isJsonPrimitive() ? " " + o.get("amount").getAsString() + " мБ" : "";
                extras.add("Жидкость " + dir + ": " + shown + amount);
            } else {
                for (Map.Entry<String, JsonElement> x : o.entrySet()) collectFluids(x.getValue(), dir, extras);
            }
        }
    }
}
