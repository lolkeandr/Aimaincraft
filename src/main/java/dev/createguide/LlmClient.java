package dev.createguide;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** HTTP-клиент нейросети: Anthropic (Claude) и OpenAI-совместимые API, с циклом вызова инструментов. */
public final class LlmClient {
    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private static final int MAX_STEPS = 8;

    // имя, описание, JSON-схема параметров
    private static final String[][] TOOL_DEFS = {
            {"find_items",
                    "Найти предметы по части названия (на языке игры) или по части id. Возвращает пары 'id — название'. Используй, если не знаешь точный id предмета.",
                    """
                    {"type":"object","properties":{"query":{"type":"string","description":"Часть названия или id, например 'шестерня' или 'andesite'"}},"required":["query"]}
                    """},
            {"get_recipes",
                    "Найти реальные рецепты ЭТОЙ сборки (включая Create и Aeronautics): как получить предмет (mode=make) или где предмет используется как ингредиент (mode=use). Возвращает JSON рецептов с recipe_id.",
                    """
                    {"type":"object","properties":{"item_id":{"type":"string","description":"Точный id предмета, например create:cogwheel"},"mode":{"type":"string","enum":["make","use"],"description":"make — как получить предмет; use — где предмет используется"}},"required":["item_id"]}
                    """},
            {"show_recipe",
                    "Показать игроку рецепт наглядно (с иконками предметов) в окне помощника. Вызывай для самых важных рецептов (1-3 штуки).",
                    """
                    {"type":"object","properties":{"recipe_id":{"type":"string","description":"recipe_id из результата get_recipes"}},"required":["recipe_id"]}
                    """}
    };

    private LlmClient() {}

    public static String ask(List<History.Entry> past, String question, String context, String imageB64,
                             Consumer<String> status, List<RecipeCard> cards) throws Exception {
        String userMsg = context + "\n\nВопрос игрока: " + question;
        boolean anthropic = !"openai".equalsIgnoreCase(Config.PROVIDER.get().trim());
        return anthropic
                ? askAnthropic(past, userMsg, imageB64, status, cards)
                : askOpenAi(past, userMsg, imageB64, status, cards);
    }

    // ================= Anthropic =================

    private static String askAnthropic(List<History.Entry> past, String userMsg, String imageB64,
                                       Consumer<String> status, List<RecipeCard> cards) throws Exception {
        JsonArray messages = new JsonArray();
        for (History.Entry e : past) {
            if (messages.isEmpty() && !e.role().equals("user")) continue;
            JsonObject m = new JsonObject();
            m.addProperty("role", e.role());
            m.addProperty("content", e.text());
            messages.add(m);
        }

        JsonArray content = new JsonArray();
        if (imageB64 != null) {
            JsonObject src = new JsonObject();
            src.addProperty("type", "base64");
            src.addProperty("media_type", "image/jpeg");
            src.addProperty("data", imageB64);
            JsonObject img = new JsonObject();
            img.addProperty("type", "image");
            img.add("source", src);
            content.add(img);
        }
        JsonObject text = new JsonObject();
        text.addProperty("type", "text");
        text.addProperty("text", userMsg);
        content.add(text);
        JsonObject um = new JsonObject();
        um.addProperty("role", "user");
        um.add("content", content);
        messages.add(um);

        JsonArray tools = new JsonArray();
        for (String[] d : TOOL_DEFS) {
            JsonObject t = new JsonObject();
            t.addProperty("name", d[0]);
            t.addProperty("description", d[1]);
            t.add("input_schema", JsonParser.parseString(d[2]));
            tools.add(t);
        }

        JsonObject body = new JsonObject();
        body.addProperty("model", Config.MODEL.get().trim());
        body.addProperty("max_tokens", Config.MAX_TOKENS.get());
        body.addProperty("system", systemPrompt());
        body.add("messages", messages);
        body.add("tools", tools);

        String url = endpoint(true);
        Map<String, String> headers = Map.of(
                "x-api-key", Config.API_KEY.get().trim(),
                "anthropic-version", "2023-06-01");

        for (int step = 0; step < MAX_STEPS; step++) {
            JsonObject resp = post(url, headers, body);
            JsonArray blocks = resp.getAsJsonArray("content");
            StringBuilder answer = new StringBuilder();
            List<JsonObject> toolUses = new ArrayList<>();
            if (blocks != null) {
                for (JsonElement el : blocks) {
                    JsonObject b = el.getAsJsonObject();
                    String type = b.get("type").getAsString();
                    if (type.equals("text")) answer.append(b.get("text").getAsString());
                    else if (type.equals("tool_use")) toolUses.add(b);
                }
            }
            if (toolUses.isEmpty()) {
                String s = answer.toString().trim();
                if (s.isEmpty()) throw new IOException("Нейросеть вернула пустой ответ.");
                return s;
            }

            JsonObject am = new JsonObject();
            am.addProperty("role", "assistant");
            am.add("content", blocks);
            messages.add(am);

            JsonArray results = new JsonArray();
            for (JsonObject tu : toolUses) {
                String name = tu.get("name").getAsString();
                status.accept(statusFor(name));
                JsonObject args = tu.has("input") && tu.get("input").isJsonObject() ? tu.getAsJsonObject("input") : new JsonObject();
                String out = Tools.execute(name, args, cards);
                JsonObject tr = new JsonObject();
                tr.addProperty("type", "tool_result");
                tr.addProperty("tool_use_id", tu.get("id").getAsString());
                tr.addProperty("content", out);
                results.add(tr);
            }
            JsonObject rm = new JsonObject();
            rm.addProperty("role", "user");
            rm.add("content", results);
            messages.add(rm);
            status.accept("Думаю");
        }
        throw new IOException("Нейросеть слишком долго вызывала инструменты. Попробуй переформулировать вопрос.");
    }

    // ================= OpenAI-совместимые =================

    private static String askOpenAi(List<History.Entry> past, String userMsg, String imageB64,
                                    Consumer<String> status, List<RecipeCard> cards) throws Exception {
        JsonArray messages = new JsonArray();
        JsonObject sys = new JsonObject();
        sys.addProperty("role", "system");
        sys.addProperty("content", systemPrompt());
        messages.add(sys);

        for (History.Entry e : past) {
            JsonObject m = new JsonObject();
            m.addProperty("role", e.role());
            m.addProperty("content", e.text());
            messages.add(m);
        }

        JsonObject um = new JsonObject();
        um.addProperty("role", "user");
        if (imageB64 != null) {
            JsonArray parts = new JsonArray();
            JsonObject t = new JsonObject();
            t.addProperty("type", "text");
            t.addProperty("text", userMsg);
            parts.add(t);
            JsonObject urlObj = new JsonObject();
            urlObj.addProperty("url", "data:image/jpeg;base64," + imageB64);
            JsonObject ip = new JsonObject();
            ip.addProperty("type", "image_url");
            ip.add("image_url", urlObj);
            parts.add(ip);
            um.add("content", parts);
        } else {
            um.addProperty("content", userMsg);
        }
        messages.add(um);

        JsonArray tools = new JsonArray();
        for (String[] d : TOOL_DEFS) {
            JsonObject fn = new JsonObject();
            fn.addProperty("name", d[0]);
            fn.addProperty("description", d[1]);
            fn.add("parameters", JsonParser.parseString(d[2]));
            JsonObject t = new JsonObject();
            t.addProperty("type", "function");
            t.add("function", fn);
            tools.add(t);
        }

        String url = endpoint(false);
        JsonObject body = new JsonObject();
        body.addProperty("model", Config.MODEL.get().trim());
        body.addProperty(url.contains("api.openai.com") ? "max_completion_tokens" : "max_tokens", Config.MAX_TOKENS.get());
        body.add("messages", messages);
        body.add("tools", tools);

        Map<String, String> headers = Map.of("Authorization", "Bearer " + Config.API_KEY.get().trim());

        for (int step = 0; step < MAX_STEPS; step++) {
            JsonObject resp = post(url, headers, body);
            JsonArray choices = resp.getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) throw new IOException("Нейросеть вернула пустой ответ.");
            JsonObject m = choices.get(0).getAsJsonObject().getAsJsonObject("message");
            String content = m.has("content") && !m.get("content").isJsonNull() ? m.get("content").getAsString() : "";
            JsonArray calls = m.has("tool_calls") && m.get("tool_calls").isJsonArray() ? m.getAsJsonArray("tool_calls") : null;

            if (calls == null || calls.isEmpty()) {
                if (content.isBlank()) throw new IOException("Нейросеть вернула пустой ответ.");
                return content.trim();
            }

            JsonObject am = new JsonObject();
            am.addProperty("role", "assistant");
            am.addProperty("content", content);
            am.add("tool_calls", calls);
            messages.add(am);

            for (JsonElement ce : calls) {
                JsonObject call = ce.getAsJsonObject();
                JsonObject fn = call.getAsJsonObject("function");
                String name = fn.get("name").getAsString();
                status.accept(statusFor(name));
                JsonObject args = parseArgs(fn.has("arguments") ? fn.get("arguments").getAsString() : "{}");
                String out = Tools.execute(name, args, cards);
                JsonObject tm = new JsonObject();
                tm.addProperty("role", "tool");
                tm.addProperty("tool_call_id", call.get("id").getAsString());
                tm.addProperty("content", out);
                messages.add(tm);
            }
            status.accept("Думаю");
        }
        throw new IOException("Нейросеть слишком долго вызывала инструменты. Попробуй переформулировать вопрос.");
    }

    // ================= Общее =================

    private static JsonObject parseArgs(String json) {
        try {
            if (json == null || json.isBlank()) return new JsonObject();
            return JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception e) {
            return new JsonObject();
        }
    }

    private static String statusFor(String tool) {
        return switch (tool) {
            case "find_items" -> "Ищу предметы";
            case "get_recipes" -> "Смотрю рецепты сборки";
            case "show_recipe" -> "Готовлю карточку рецепта";
            default -> "Думаю";
        };
    }

    private static String endpoint(boolean anthropic) {
        String custom = Config.API_URL.get().trim();
        if (!custom.isEmpty()) return custom;
        return anthropic ? "https://api.anthropic.com/v1/messages" : "https://api.openai.com/v1/chat/completions";
    }

    private static JsonObject post(String url, Map<String, String> headers, JsonObject body) throws Exception {
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(120))
                .header("content-type", "application/json");
        headers.forEach(rb::header);
        HttpRequest req = rb.POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8)).build();
        HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (r.statusCode() / 100 != 2) throw new IOException(describeError(r.statusCode(), r.body()));
        return JsonParser.parseString(r.body()).getAsJsonObject();
    }

    private static String describeError(int status, String body) {
        String msg = body == null ? "" : body;
        try {
            JsonObject o = JsonParser.parseString(msg).getAsJsonObject();
            if (o.has("error")) {
                JsonElement e = o.get("error");
                if (e.isJsonObject() && e.getAsJsonObject().has("message")) msg = e.getAsJsonObject().get("message").getAsString();
                else if (e.isJsonPrimitive()) msg = e.getAsString();
            }
        } catch (Exception ignored) {
            // оставляем тело как есть
        }
        if (msg.length() > 300) msg = msg.substring(0, 300) + "…";
        String hint = switch (status) {
            case 401, 403 -> "Проверь API-ключ. ";
            case 402 -> "Похоже, на балансе нет средств. ";
            case 429 -> "Слишком много запросов или исчерпан лимит. ";
            default -> "";
        };
        return hint + "Ошибка API " + status + ": " + msg;
    }

    private static String systemPrompt() {
        String base = """
                Ты — ИИ-помощник игрока внутри Minecraft 1.21.1 (NeoForge). Сборка построена на модах Create и Create Aeronautics. \
                Игрок плохо знаком с этими модами, поэтому объясняй просто, по шагам и по-русски, коротко (обычно до 8-10 строк).

                Правила:
                - Не придумывай рецепты и id предметов. Если не знаешь точный id — вызови find_items. Рецепты бери через get_recipes: в этой сборке они могут отличаться от стандартных.
                - Когда объясняешь, как сделать предмет или механизм, вызови show_recipe для 1-3 самых важных рецептов, чтобы игрок увидел их с иконками. Не пересказывай рецепт по ячейкам — достаточно сути.
                - К вопросу приложен контекст (предмет в руке, инвентарь, блок под прицелом) и, возможно, скриншот. Опирайся на них: смотри, какая конструкция на скриншоте и какие блоки видны, и подсказывай по ситуации. Если на скриншоте что-то не разобрать, скажи об этом.
                - Если не уверен в механике (особенно в Create Aeronautics: мод новый, твои знания могут быть неполными или устаревшими), честно скажи об этом и предложи, что проверить в игре, вместо того чтобы выдумывать.
                - Пиши обычным текстом без markdown-разметки (без звёздочек и заголовков). Списки — через дефис.
                """;
        String extra = Config.EXTRA_PROMPT.get().trim();
        return extra.isEmpty() ? base : base + "\nДополнительные инструкции игрока: " + extra;
    }
}
