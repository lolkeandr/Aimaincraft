package dev.createguide;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Клиентский конфиг: файл config/createguide-client.toml */
public final class Config {
    private static final ModConfigSpec.Builder B = new ModConfigSpec.Builder();

    public static final ModConfigSpec.ConfigValue<String> PROVIDER = B
            .comment("Провайдер нейросети: \"anthropic\" (Claude) или \"openai\" (OpenAI и любые OpenAI-совместимые, например OpenRouter)")
            .define("provider", "anthropic");

    public static final ModConfigSpec.ConfigValue<String> API_KEY = B
            .comment("API-ключ. Можно оставить пустым и вставить ключ прямо в окне помощника.")
            .define("apiKey", "");

    public static final ModConfigSpec.ConfigValue<String> API_URL = B
            .comment("Полный адрес API. Пусто = адрес по умолчанию для выбранного провайдера.")
            .define("apiUrl", "");

    public static final ModConfigSpec.ConfigValue<String> MODEL = B
            .comment("Модель. Для Claude: claude-sonnet-5-5 (качество) или claude-haiku-5-5 (дешевле и быстрее). Модель должна понимать изображения.")
            .define("model", "claude-sonnet-5-5");

    public static final ModConfigSpec.IntValue MAX_TOKENS = B
            .comment("Максимальная длина ответа в токенах")
            .defineInRange("maxTokens", 1500, 200, 8000);

    public static final ModConfigSpec.BooleanValue SEND_SCREENSHOT = B
            .comment("Автоматически отправлять скриншот игры вместе с вопросом")
            .define("sendScreenshot", true);

    public static final ModConfigSpec.IntValue SCREENSHOT_WIDTH = B
            .comment("Ширина скриншота в пикселях (меньше = дешевле и быстрее)")
            .defineInRange("screenshotWidth", 1280, 320, 2560);

    public static final ModConfigSpec.BooleanValue PAUSE_GAME = B
            .comment("Ставить игру на паузу, пока открыто окно помощника")
            .define("pauseGame", true);

    public static final ModConfigSpec.IntValue HISTORY_KEEP = B
            .comment("Сколько последних сообщений хранить в файле истории")
            .defineInRange("historyKeep", 60, 2, 500);

    public static final ModConfigSpec.IntValue HISTORY_IN_REQUEST = B
            .comment("Сколько последних сообщений истории отправлять нейросети как контекст диалога")
            .defineInRange("historyInRequest", 16, 0, 100);

    public static final ModConfigSpec.ConfigValue<String> EXTRA_PROMPT = B
            .comment("Дополнительные инструкции для помощника (необязательно)")
            .define("extraPrompt", "");

    public static final ModConfigSpec SPEC = B.build();

    private Config() {}
}
