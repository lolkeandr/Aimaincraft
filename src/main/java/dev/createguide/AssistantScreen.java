package dev.createguide;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

public class AssistantScreen extends Screen {
    private static final String WELCOME = "Привет! Спроси что угодно про Create и Create Aeronautics. "
            + "При открытии окна я автоматически вижу твой экран, предмет в руке и инвентарь, "
            + "а рецепты беру прямо из твоей сборки.";
    private static final String KEY_HELP = "Нужен API-ключ нейросети. Вставь ключ в поле ниже и нажми Enter — "
            + "я сохраню его в конфиге мода. Получить ключ можно на сайте разработчиков Anthropic (Claude Platform).";

    private final List<Block> blocks = new ArrayList<>();
    private EditBox input;
    private int px, py, pw, ph;
    private double scroll = 0;
    private int maxScroll = 0;
    private boolean stickBottom = true;
    private volatile String status = "";
    private volatile boolean busy = false;

    // состояние текущего кадра (для подсказок над предметами)
    private int curMx, curMy;
    private boolean hoverOk;
    private ItemStack hovered = ItemStack.EMPTY;

    public AssistantScreen() {
        super(Component.literal("Create Guide"));
        for (History.Entry e : History.all()) blocks.add(new TextBlock(e.role(), e.text()));
        if (blocks.isEmpty()) blocks.add(new TextBlock("system", WELCOME));
        if (Config.API_KEY.get().isBlank()) blocks.add(new TextBlock("system", KEY_HELP));
    }

    @Override
    protected void init() {
        String old = input != null ? input.getValue() : "";
        pw = Math.min(width - 24, 560);
        ph = height - 24;
        px = (width - pw) / 2;
        py = 12;

        int inputY = py + ph - 26;
        input = new EditBox(font, px + 8, inputY, pw - 16 - 72, 20, Component.literal(""));
        input.setMaxLength(2000);
        input.setHint(Component.literal("Спроси про Create или Aeronautics..."));
        input.setValue(old);
        addRenderableWidget(input);
        addRenderableWidget(Button.builder(Component.literal("Отправить"), b -> send())
                .bounds(px + pw - 8 - 68, inputY, 68, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Очистить"), b -> clearHistory())
                .bounds(px + pw - 8 - 64, py + 4, 64, 16).build());
        setInitialFocus(input);
    }

    @Override
    public boolean isPauseScreen() {
        return Config.PAUSE_GAME.get();
    }

    // ---------------- Отправка ----------------

    private void send() {
        String text = input.getValue().trim();
        if (text.isEmpty() || busy) return;

        if (Config.API_KEY.get().isBlank()) {
            input.setValue("");
            if (text.length() < 20 || text.contains(" ")) {
                addBlock(new TextBlock("error", "Это не похоже на API-ключ. Вставь ключ целиком, без пробелов."));
                return;
            }
            Config.API_KEY.set(text);
            try {
                Config.SPEC.save();
            } catch (Throwable t) {
                CreateGuide.LOGGER.warn("Не удалось сохранить конфиг", t);
            }
            addBlock(new TextBlock("system", "Ключ сохранён. Теперь можно задавать вопросы."));
            return;
        }

        input.setValue("");
        addBlock(new TextBlock("user", text));
        busy = true;
        status = "Думаю";

        List<History.Entry> past = History.last(Config.HISTORY_IN_REQUEST.get());
        CompletableFuture<String> shot = CreateGuide.lastScreenshot;
        String ctx = CreateGuide.lastContext;
        Minecraft mc = Minecraft.getInstance();
        List<RecipeCard> cards = new CopyOnWriteArrayList<>();

        CreateGuide.EXEC.submit(() -> {
            try {
                String img = null;
                if (shot != null) {
                    try {
                        img = shot.get(20, TimeUnit.SECONDS);
                    } catch (Exception ignored) {
                        // отправим без скриншота
                    }
                }
                String answer = LlmClient.ask(past, text, ctx, img, s -> status = s, cards);
                mc.execute(() -> {
                    addBlock(new TextBlock("assistant", clean(answer)));
                    for (RecipeCard c : cards) addBlock(new RecipeBlock(c));
                    History.add("user", text);
                    History.add("assistant", answer);
                    busy = false;
                });
            } catch (Throwable t) {
                CreateGuide.LOGGER.warn("Ошибка запроса к нейросети", t);
                String msg = t.getMessage() != null ? t.getMessage() : t.toString();
                mc.execute(() -> {
                    addBlock(new TextBlock("error", msg));
                    busy = false;
                });
            }
        });
    }

    private void clearHistory() {
        if (busy) return;
        History.clear();
        blocks.clear();
        blocks.add(new TextBlock("system", "История очищена."));
        stickBottom = true;
    }

    private void addBlock(Block b) {
        blocks.add(b);
        stickBottom = true;
    }

    private static String clean(String s) {
        return s.replace("**", "").replace("`", "")
                .replaceAll("(?m)^#{1,6}\\s*", "")
                .replaceAll("(?m)^\\s*[-*]\\s+", "• ");
    }

    // ---------------- Ввод ----------------

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) && input.isFocused()) {
            send();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scroll -= scrollY * 18;
        if (scroll < 0) scroll = 0;
        if (scroll > maxScroll) scroll = maxScroll;
        stickBottom = scroll >= maxScroll - 1;
        return true;
    }

    // ---------------- Отрисовка ----------------

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        curMx = mx;
        curMy = my;
        hovered = ItemStack.EMPTY;

        g.fill(0, 0, width, height, 0xB0000000);
        g.fill(px, py, px + pw, py + ph, 0xF0181818);
        g.drawString(font, "Create Guide", px + 8, py + 8, 0xFFFFFF55, false);

        int ax = px + 8;
        int ay = py + 24;
        int aw = pw - 16;
        int ah = ph - 24 - 42;
        hoverOk = mx >= ax && mx < ax + aw && my >= ay && my < ay + ah;

        g.enableScissor(ax, ay, ax + aw, ay + ah);
        int total = 0;
        for (Block b : blocks) {
            int h = b.render(g, ax, ay + total - (int) scroll, aw);
            total += h + 4;
        }
        g.disableScissor();

        maxScroll = Math.max(0, total - ah);
        if (stickBottom) scroll = maxScroll;
        if (scroll > maxScroll) scroll = maxScroll;
        if (scroll < 0) scroll = 0;

        if (busy) {
            String dots = ".".repeat((int) ((Util.getMillis() / 400) % 4));
            g.drawString(font, status + dots, px + 8, py + ph - 38, 0xFFAAAAAA, false);
        }

        super.render(g, mx, my, partialTick);

        if (!hovered.isEmpty()) g.renderTooltip(font, hovered, mx, my);
    }

    private void drawSlot(GuiGraphics g, int x, int y, RecipeCard.Slot s) {
        if (g == null) return;
        g.fill(x, y, x + 18, y + 18, 0xFF555555);
        g.fill(x + 1, y + 1, x + 17, y + 17, 0xFF2B2B2B);
        if (s == null || s.options().isEmpty()) return;
        List<ItemStack> o = s.options();
        ItemStack st = o.get((int) ((Util.getMillis() / 1000) % o.size())).copy();
        st.setCount(s.count());
        g.renderItem(st, x + 1, y + 1);
        g.renderItemDecorations(font, st, x + 1, y + 1);
        if (hoverOk && curMx >= x && curMx < x + 18 && curMy >= y && curMy < y + 18) hovered = st;
    }

    // ---------------- Блоки сообщений ----------------

    private abstract class Block {
        /** Рисует блок и возвращает его высоту. */
        abstract int render(GuiGraphics g, int x, int y, int w);
    }

    private final class TextBlock extends Block {
        private final String role;
        private final String text;
        private List<FormattedCharSequence> lines = List.of();
        private int lastW = -1;

        TextBlock(String role, String text) {
            this.role = role;
            this.text = text;
        }

        @Override
        int render(GuiGraphics g, int x, int y, int w) {
            if (lastW != w) {
                String prefix = switch (role) {
                    case "user" -> "Ты: ";
                    case "error" -> "Ошибка: ";
                    default -> "";
                };
                lines = font.split(FormattedText.of(prefix + text), w - 12);
                lastW = w;
            }
            int h = lines.size() * 10 + 8;
            int color = switch (role) {
                case "user" -> 0xFFFFE9A8;
                case "error" -> 0xFFFF7777;
                case "system" -> 0xFFB0B0B0;
                default -> 0xFFFFFFFF;
            };
            g.fill(x, y, x + w, y + h, role.equals("user") ? 0x30FFFFFF : 0x18FFFFFF);
            int ty = y + 4;
            for (FormattedCharSequence line : lines) {
                g.drawString(font, line, x + 6, ty, color, false);
                ty += 10;
            }
            return h;
        }
    }

    private final class RecipeBlock extends Block {
        private final RecipeCard card;

        RecipeBlock(RecipeCard card) {
            this.card = card;
        }

        @Override
        int render(GuiGraphics g, int x, int y, int w) {
            int h = layout(null, x, y, w);
            g.fill(x, y, x + w, y + h, 0x30FFAA00);
            layout(g, x, y, w);
            return h;
        }

        /** Если g == null — только считает высоту. */
        private int layout(GuiGraphics g, int x, int y, int w) {
            int cy = y + 4;
            if (g != null) g.drawString(font, card.typeLabel, x + 6, cy, 0xFFFFAA00, false);
            cy += 12;

            if (card.gridW > 0) {
                int gh = card.gridH * 18;
                for (int i = 0; i < card.inputs.size(); i++) {
                    int col = i % card.gridW;
                    int row = i / card.gridW;
                    drawSlot(g, x + 6 + col * 18, cy + row * 18, card.inputs.get(i));
                }
                int ax = x + 6 + card.gridW * 18 + 8;
                if (g != null) g.drawString(font, "→", ax, cy + gh / 2 - 4, 0xFFFFFFFF, false);
                int ox = ax + 16;
                for (int i = 0; i < card.outputs.size(); i++) {
                    drawSlot(g, ox + i * 18, cy + (gh - 18) / 2, card.outputs.get(i));
                }
                cy += gh;
            } else {
                int perRow = Math.max(1, (w - 12) / 18);
                int n = card.inputs.size();
                for (int i = 0; i < n; i++) {
                    drawSlot(g, x + 6 + (i % perRow) * 18, cy + (i / perRow) * 18, card.inputs.get(i));
                }
                cy += Math.max(1, (n + perRow - 1) / perRow) * 18 + 4;
                String label = "Получится:";
                if (g != null) g.drawString(font, label, x + 6, cy + 5, 0xFFFFFFFF, false);
                int ox = x + 6 + font.width(label) + 6;
                for (int i = 0; i < card.outputs.size(); i++) {
                    drawSlot(g, ox + i * 18, cy, card.outputs.get(i));
                }
                cy += 18;
            }

            for (String extra : card.extras) {
                if (g != null) g.drawString(font, extra, x + 6, cy + 2, 0xFFBBBBBB, false);
                cy += 10;
            }
            return cy - y + 4;
        }
    }
}
