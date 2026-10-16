package dev.createguide;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Mod(value = CreateGuide.MODID, dist = Dist.CLIENT)
public class CreateGuide {
    public static final String MODID = "createguide";
    public static final Logger LOGGER = LogUtils.getLogger();

    public static final ExecutorService EXEC = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "createguide-worker");
        t.setDaemon(true);
        return t;
    });

    public static final KeyMapping OPEN_KEY = new KeyMapping(
            "key.createguide.open",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_K,
            "key.categories.createguide");

    /** Контекст и скриншот, снятые в момент нажатия горячей клавиши. */
    public static volatile String lastContext = "";
    public static volatile CompletableFuture<String> lastScreenshot = null;

    public CreateGuide(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, Config.SPEC);
        modBus.addListener(this::registerKeys);
        NeoForge.EVENT_BUS.addListener(this::onClientTick);
        History.load();
    }

    private void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(OPEN_KEY);
    }

    private void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        while (OPEN_KEY.consumeClick()) {
            if (mc.player == null || mc.level == null || mc.screen != null) continue;
            openAssistant(mc);
        }
    }

    private static void openAssistant(Minecraft mc) {
        lastScreenshot = null;
        lastContext = ContextBuilder.build(mc);

        if (Config.SEND_SCREENSHOT.get()) {
            try {
                // Скриншот делаем ДО открытия окна, чтобы в кадр попал мир, а не наше окно.
                byte[] png;
                try (NativeImage img = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
                    png = img.asByteArray();
                }
                int width = Config.SCREENSHOT_WIDTH.get();
                lastScreenshot = CompletableFuture.supplyAsync(() -> Screenshots.toJpegBase64(png, width), EXEC);
            } catch (Throwable t) {
                LOGGER.warn("Не удалось сделать скриншот", t);
            }
        }
        mc.setScreen(new AssistantScreen());
    }
}
