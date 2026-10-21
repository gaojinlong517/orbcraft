package com.orbcraft.fabric.client;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 构筑界面。
 *
 * <p><b>它只做两件事</b>：画出来、点下去发一条命令。所有判断都在服务端，
 * 界面不复制任何规则 —— 否则「界面显示可选、点了却报错」会永远跟着版本走。
 *
 * <p><b>视觉上刻意不用原版按钮外观</b>：原版灰按钮在深色半透明底上既没有层级也没有
 * 状态区分（选中/不可用/危险操作全是一个灰块）。这里继承 {@link Button} 只重写绘制，
 * 于是焦点、Tab 切换、Enter 激活、无障碍朗读全部白拿，不需要自己写命中检测。
 *
 * <p>信息层级：标题（金）→ 选项卡（主体）→ 分隔线 → 动作行（次要，右对齐）→ 快捷键提示。
 * 选项与动作按<b>命令</b>区分（{@code orb card N} / {@code orb target X} 是选项，其余是动作），
 * 不靠文案猜测 —— 文案会改，命令是契约。
 */
public final class BuildScreenGui extends Screen {

    // ---------------------------------------------------------------- 设计 token
    // 颜色集中在这里，改主题只动这一段。深色底 + 低饱和卡片 + 单一强调色，
    // 强调色只给标题与选中态，避免整屏发光。
    private static final int PANEL_BG = 0xF0121216;
    private static final int PANEL_BORDER = 0xFF3C3C48;
    private static final int HEADER_BG = 0xFF1B1B22;
    private static final int CARD_BG = 0xFF23232C;
    private static final int CARD_BG_HOVER = 0xFF2F2F3A;
    private static final int CARD_BG_DISABLED = 0xFF1A1A20;
    private static final int CARD_BORDER = 0xFF373742;
    private static final int CARD_BORDER_HOVER = 0xFF8A8AA0;
    private static final int TEXT = 0xFFE6E6EE;
    private static final int TEXT_MUTED = 0xFF8C8C9A;
    private static final int TEXT_DISABLED = 0xFF64646F;
    private static final int ACCENT = 0xFFFFC24B;
    private static final int DIVIDER = 0xFF2E2E38;

    /** 间距阶梯只有 8/14/24，避免出现「差 1 像素」的随手值。 */
    private static final int PAD = 14;
    private static final int GAP = 8;
    private static final int CARD_H = 24;
    private static final int ACTION_H = 22;

    private final List<Entry> entries = new ArrayList<>();
    private String heading = "";

    // 布局结果：init 里算一次，render 直接用，避免每帧重复测量
    private int panelLeft;
    private int panelTop;
    private int panelWidth;
    private int panelHeight;
    private int dividerY = -1;
    private int hintY;

    /** 一条可点项。{@code action} 决定它进卡片网格还是底部动作行。 */
    private record Entry(String label, String command, ChatFormatting tone, boolean chosen, boolean action) {
    }

    public BuildScreenGui(String json) {
        super(Component.literal("构筑"));
        parse(json);
    }

    private void parse(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (root.has("title")) {
                heading = root.get("title").getAsString();
            }
            JsonArray arr = root.getAsJsonArray("buttons");
            if (arr == null) {
                return;
            }
            for (JsonElement element : arr) {
                JsonObject one = element.getAsJsonObject();
                String label = one.get("label").getAsString();
                String cmd = one.get("cmd").getAsString();
                entries.add(new Entry(
                        label,
                        cmd,
                        formatOf(one.has("tone") ? one.get("tone").getAsString() : "white"),
                        label.startsWith("✓"),
                        isAction(cmd)));
            }
        } catch (RuntimeException e) {
            // 解析失败就给空界面：宁可什么都不显示，也不要发出一条错误的命令。
            entries.clear();
            heading = "构筑界面数据异常";
        }
    }

    /** 选项与动作的唯一判据。命令是两端契约，文案不是。 */
    private static boolean isAction(String command) {
        String cmd = command.startsWith("/") ? command.substring(1) : command;
        return !(cmd.startsWith("orb card ") || cmd.startsWith("orb target "));
    }

    @Override
    protected void init() {
        if (entries.isEmpty()) {
            panelWidth = Math.min(this.width - 40, 320);
            panelHeight = 64;
            panelLeft = (this.width - panelWidth) / 2;
            panelTop = (this.height - panelHeight) / 2;
            hintY = panelTop + 40;
            return;
        }
        List<Entry> options = new ArrayList<>();
        List<Entry> actions = new ArrayList<>();
        for (Entry entry : entries) {
            (entry.action() ? actions : options).add(entry);
        }

        panelWidth = Math.min(this.width - 40, 560);
        int columns = Math.max(1, Math.min(panelWidth >= 460 ? 3 : 2, Math.max(1, options.size())));
        int cardWidth = (panelWidth - PAD * 2 - GAP * (columns - 1)) / columns;
        int rows = options.isEmpty() ? 0 : (options.size() + columns - 1) / columns;

        int content = 20 + GAP
                + rows * (CARD_H + GAP)
                + (actions.isEmpty() ? 0 : 12 + 1 + GAP + ACTION_H)
                + GAP + 10;
        panelHeight = content + PAD * 2;
        panelLeft = (this.width - panelWidth) / 2;
        panelTop = Math.max(16, (this.height - panelHeight) / 2);

        int y = panelTop + PAD + 20 + GAP;
        for (int i = 0; i < options.size(); i++) {
            Entry entry = options.get(i);
            int x = panelLeft + PAD + (i % columns) * (cardWidth + GAP);
            int top = y + (i / columns) * (CARD_H + GAP);
            addRenderableWidget(new CardButton(x, top, cardWidth, CARD_H,
                    Component.literal(entry.label()), entry.tone(), entry.chosen(), false,
                    ignored -> run(entry.command())));
        }
        if (rows > 0) {
            y += rows * (CARD_H + GAP) - GAP;
        }

        if (!actions.isEmpty()) {
            dividerY = y + 12;
            y = dividerY + 1 + GAP;
            // 动作行右对齐：玩家视线自然落点是右下角，确认/放弃都在那一带。
            int cursorRight = panelLeft + panelWidth - PAD;
            for (int i = actions.size() - 1; i >= 0; i--) {
                Entry entry = actions.get(i);
                int w = Math.max(64, this.font.width(entry.label()) + 20);
                cursorRight -= w;
                addRenderableWidget(new CardButton(cursorRight, y, w, ACTION_H,
                        Component.literal(entry.label()), entry.tone(), entry.chosen(), true,
                        ignored -> run(entry.command())));
                cursorRight -= GAP;
            }
            y += ACTION_H;
        }

        hintY = y + GAP + 2;
    }

    private void run(String command) {
        if (this.minecraft == null || this.minecraft.player == null || command == null) {
            return;
        }
        String cmd = command.startsWith("/") ? command.substring(1) : command;
        this.minecraft.player.connection.sendCommand(cmd);
        // 不主动关界面：服务端处理完会推新快照，界面原地刷新。
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 半透明底：保证任何地形上文字都可读；不开世界模糊，因为这是随手可关的选择框。
        graphics.fill(panelLeft, panelTop, panelLeft + panelWidth, panelTop + panelHeight, PANEL_BG);
        outline(graphics, panelLeft, panelTop, panelWidth, panelHeight, PANEL_BORDER);
        graphics.fill(panelLeft + 1, panelTop + 1, panelLeft + panelWidth - 1,
                panelTop + PAD + 12, HEADER_BG);

        String title = fit(heading, panelWidth - PAD * 2);
        graphics.drawString(this.font, title, panelLeft + PAD, panelTop + PAD, ACCENT, false);
        // 标题下划线：强调色的唯一强用途，把「这是哪一屏」和下面卡片分开。
        graphics.fill(panelLeft + PAD, panelTop + PAD + 11,
                panelLeft + PAD + Math.min(panelWidth - PAD * 2, this.font.width(title)),
                panelTop + PAD + 13, ACCENT);

        if (dividerY >= 0) {
            graphics.fill(panelLeft + PAD, dividerY, panelLeft + panelWidth - PAD,
                    dividerY + 1, DIVIDER);
        }

        graphics.drawString(this.font, "Tab 切换 · Enter 确认 · Esc 关闭",
                panelLeft + PAD, hintY, TEXT_MUTED, false);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        // 单人模式不暂停：否则界面一开世界就停，球也不孵了。
        return false;
    }

    private static void outline(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y, x + 1, y + h, color);
        g.fill(x + w - 1, y, x + w, y + h, color);
    }

    private String fit(String text, int maxWidth) {
        return fit(this.font, text, maxWidth);
    }

    private static String fit(Font font, String text, int maxWidth) {
        if (maxWidth <= 0) {
            return "";
        }
        if (font.width(text) <= maxWidth) {
            return text;
        }
        return font.plainSubstrByWidth(text, Math.max(0, maxWidth - font.width("…")), true) + "…";
    }

    private static ChatFormatting formatOf(String tone) {
        return switch (tone) {
            case "green" -> ChatFormatting.GREEN;
            case "red" -> ChatFormatting.RED;
            case "aqua" -> ChatFormatting.AQUA;
            case "gold" -> ChatFormatting.GOLD;
            case "yellow" -> ChatFormatting.YELLOW;
            case "dark_gray" -> ChatFormatting.DARK_GRAY;
            case "gray" -> ChatFormatting.GRAY;
            default -> ChatFormatting.WHITE;
        };
    }

    private static int toneColor(ChatFormatting tone) {
        return switch (tone) {
            case GREEN -> 0xFF5BD37A;
            case RED -> 0xFFFF6B6B;
            case AQUA -> 0xFF4FD1E0;
            case GOLD -> ACCENT;
            case YELLOW -> 0xFFE8D06A;
            case DARK_GRAY, GRAY -> TEXT_MUTED;
            default -> TEXT;
        };
    }

    /**
     * 卡牌按钮：外观全自定义，行为全部继承原版。
     *
     * <p>为什么不自己写命中检测：原版 {@code Button} 已经实现了 hover、焦点、
     * Tab 遍历、Enter/空格激活，以及给读屏工具的朗读文本。重写这些只会引入
     * 「鼠标能点、键盘不能」这类只有部分玩家会遇到的缺陷。
     */
    private static final class CardButton extends Button {

        private final ChatFormatting tone;
        private final boolean chosen;
        private final boolean action;

        CardButton(int x, int y, int w, int h, Component label,
                   ChatFormatting tone, boolean chosen, boolean action, OnPress onPress) {
            super(x, y, w, h, label, onPress, Button.DEFAULT_NARRATION);
            this.tone = tone;
            this.chosen = chosen;
            this.action = action;
        }

        @Override
        protected void renderContents(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            Font font = Minecraft.getInstance().font;
            boolean hot = isHoveredOrFocused();
            int background = !this.active ? CARD_BG_DISABLED : (hot ? CARD_BG_HOVER : CARD_BG);
            graphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), background);
            outline(graphics, getX(), getY(), getWidth(), getHeight(),
                    hot && this.active ? CARD_BORDER_HOVER : CARD_BORDER);

            // 选中态：左侧 3px 色条 + 文字变色。只靠 ✓ 前缀太弱，缩略图下看不出差别。
            if (chosen) {
                graphics.fill(getX() + 1, getY() + 1, getX() + 4, getY() + getHeight() - 1,
                        toneColor(tone));
            }

            int textColor = !this.active ? TEXT_DISABLED : (chosen ? toneColor(tone) : TEXT);
            String label = getMessage().getString();
            int centerY = getY() + (getHeight() - 8) / 2;

            if (action) {
                // 动作按钮文字居中：动作是「扫一眼」的，卡片是「逐个比较」的。
                String fitted = fit(font, label, getWidth() - 14);
                graphics.drawString(font, fitted,
                        getX() + (getWidth() - font.width(fitted)) / 2, centerY, textColor, false);
            } else {
                int textX = getX() + (chosen ? 10 : 9);
                String fitted = fit(font, label, getWidth() - (textX - getX()) - 14);
                graphics.drawString(font, fitted, textX, centerY, textColor, false);
                // 右侧状态点：可用（色调）/ 不可用（暗灰），余光里就能分辨。
                int dot = this.active ? toneColor(tone) : TEXT_DISABLED;
                graphics.fill(getX() + getWidth() - 10, getY() + getHeight() / 2 - 2,
                        getX() + getWidth() - 6, getY() + getHeight() / 2 + 2, dot);
            }
        }
    }
}
