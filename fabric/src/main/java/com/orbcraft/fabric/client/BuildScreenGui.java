package com.orbcraft.fabric.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
 * <p><b>卡片结构</b>：图标（12×12 几何图形，不需要美术资源）+ 主标题（1–2 行）+
 * 副标题（1 行，暗色）。副标题只显示服务端传来的真实字段，没有就留空，不编。
 */
public final class BuildScreenGui extends Screen {

    // ---------------------------------------------------------------- 设计 token
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

    private static final int PAD = 14;
    private static final int GAP = 8;
    private static final int LINE_H = 10;
    private static final int ACTION_H = 22;
    /** 文字区左侧留白（放图标）与右侧留白（状态点）。 */
    private static final int TEXT_LEFT = 26;
    private static final int TEXT_RIGHT = 14;
    private static final int CARD_PAD_Y = 8;

    private String heading = "";
    private final List<Entry> entries = new ArrayList<>();

    private final List<Placed> placed = new ArrayList<>();
    private final List<String> titleLines = new ArrayList<>();
    private int panelLeft;
    private int panelTop;
    private int panelWidth;
    private int panelHeight;
    private int dividerY = -1;
    private int hintY;

    /** 列表区滚动状态：窗口装不下卡片时启用。 */
    private int scrollY;
    private int maxScroll;
    private int listTop;
    private int listBottom;
    private final List<CardButton> optionButtons = new ArrayList<>();

    /** 一条可点项。 */
    private record Entry(String label, String command, ChatFormatting tone, boolean chosen,
                         boolean action, String icon, String detail) {
    }

    /** 已排好位置的按钮：矩形 + 预先换行好的文字。 */
    private record Placed(Entry entry, int x, int y, int w, int h, List<String> lines) {
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
                        isAction(cmd),
                        one.has("icon") ? one.get("icon").getAsString() : "action",
                        one.has("detail") ? one.get("detail").getAsString() : ""));
            }
        } catch (RuntimeException e) {
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
        placed.clear();
        titleLines.clear();

        Font font = this.font;
        List<Entry> options = new ArrayList<>();
        List<Entry> actions = new ArrayList<>();
        for (Entry entry : entries) {
            (entry.action() ? actions : options).add(entry);
        }

        panelWidth = Math.min(this.width - 24, 620);

        // 列数按「最长文案能否一行放下」反推：3 → 2 → 1；都不行就 1 列 + 两行。
        int columns = 1;
        int cardWidth = panelWidth - PAD * 2;
        int linesNeeded = 1;
        for (int candidate : new int[] {3, 2, 1}) {
            int cw = (panelWidth - PAD * 2 - GAP * (candidate - 1)) / candidate;
            int worst = maxLines(font, options, cw - TEXT_LEFT - TEXT_RIGHT);
            columns = candidate;
            cardWidth = cw;
            linesNeeded = Math.min(2, worst);
            if (worst <= 1) {
                break;
            }
        }

        // 卡片高度 = 主标题行数 + 副标题行数（取全场最大），上下各留白。
        int maxOptionLines = linesNeeded;
        for (Entry entry : options) {
            if (!entry.detail().isEmpty()) {
                maxOptionLines = Math.max(maxOptionLines, linesNeeded + 1);
            }
        }
        int cardHeight = CARD_PAD_Y * 2 + maxOptionLines * LINE_H - 2;

        titleLines.addAll(wrap(font, heading, panelWidth - PAD * 2, 2));
        int headerHeight = 8 + titleLines.size() * LINE_H + 4;

        int rows = options.isEmpty() ? 0 : (options.size() + columns - 1) / columns;
        List<List<Entry>> actionRows = packActions(font, actions, panelWidth - PAD * 2);
        int actionBlockH = actionRows.isEmpty() ? 0
                : actionRows.size() * (ACTION_H + GAP) - GAP;

        // —— 高度分三块：头部(headBlock) + 可滚动卡片(listContent) + 尾部(tailBlock 分隔线+动作+提示)
        //     窗口装不下时不撑大屏幕，改为卡片区滚动；面板最小 140px 留点底。
        int bodyTop = PAD + headerHeight + GAP;
        int listContent = rows > 0 ? rows * (cardHeight + GAP) - GAP : 0;
        int dividerGap = actionRows.isEmpty() ? 0 : 12 + 1 + GAP;
        int tailBlock = (rows > 0 || !actionRows.isEmpty() ? GAP : 0)
                + LINE_H + PAD + dividerGap + actionBlockH;

        int natural = bodyTop + listContent + tailBlock;
        panelLeft = (this.width - panelWidth) / 2;
        panelHeight = Math.min(natural, Math.max(140, this.height - 24));
        panelTop = Math.max(12, (this.height - panelHeight) / 2);

        int listTop = panelTop + bodyTop;
        int listBottom = panelTop + panelHeight - tailBlock;
        maxScroll = Math.max(0, listContent - (listBottom - listTop));
        scrollY = 0;

        // 选项卡片：基准 Y 从 listTop 起，滚动时整体平移；动作按钮不参与滚动。
        optionButtons.clear();
        for (int i = 0; i < options.size(); i++) {
            Entry entry = options.get(i);
            int x = panelLeft + PAD + (i % columns) * (cardWidth + GAP);
            int baseTop = listTop + (i / columns) * (cardHeight + GAP);
            CardButton btn = new CardButton(
                    new Placed(entry, x, baseTop, cardWidth, cardHeight,
                            wrap(font, entry.label(), cardWidth - TEXT_LEFT - TEXT_RIGHT, 2)),
                    baseTop, ignored -> run(entry.command()));
            optionButtons.add(btn);
            addRenderableWidget(btn);
        }

        int rowTop = panelTop + panelHeight - PAD - LINE_H - GAP - actionBlockH;
        dividerY = actionRows.isEmpty() ? -1 : rowTop - GAP - 1;
        for (List<Entry> row : actionRows) {
            int cursorRight = panelLeft + panelWidth - PAD;
            for (int i = row.size() - 1; i >= 0; i--) {
                Entry entry = row.get(i);
                int w = Math.max(64, font.width(entry.label()) + 20);
                cursorRight -= w;
                Placed p = new Placed(entry, cursorRight, rowTop, w, ACTION_H,
                        wrap(font, entry.label(), w - 14, 1));
                addRenderableWidget(new CardButton(p, rowTop, ignored -> run(entry.command())));
                cursorRight -= GAP;
            }
            rowTop += ACTION_H + GAP;
        }

        hintY = panelTop + panelHeight - PAD - LINE_H;
        applyScroll();
    }

    /**
     * 把滚动偏移应用到选项卡片，并裁剪掉滚出列表区的卡片（避免它们画到标题/动作区上）。
     * 动作按钮不在 {@code optionButtons} 里，滚动不影响。
     */
    private void applyScroll() {
        int shift = maxScroll > 0 ? scrollY : 0;
        for (CardButton b : optionButtons) {
            int y = b.baseTop() - shift;
            b.setY(y);
            b.visible = y + b.getHeight() > listTop && y < listBottom;
        }
    }

    private void setScroll(int value) {
        int next = Math.max(0, Math.min(maxScroll, value));
        if (next == scrollY) {
            return;
        }
        scrollY = next;
        applyScroll();
        if (getFocused() instanceof CardButton focus && !focus.visible) {
            ensureVisible(focus);
        }
    }

    /** 焦点卡片被滚动出视野时，把它滚回视野内（键盘 Tab 导航用）。 */
    private void ensureVisible(CardButton b) {
        int h = b.getHeight();
        if (b.baseTop() - scrollY < listTop) {
            setScroll(b.baseTop() - listTop);
        } else if (b.baseTop() - scrollY + h > listBottom) {
            setScroll(b.baseTop() - (listBottom - h));
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (maxScroll > 0 && mouseX >= panelLeft && mouseX <= panelLeft + panelWidth
                && mouseY >= panelTop && mouseY <= panelTop + panelHeight) {
            setScroll(this.scrollY + (int) Math.signum(scrollY) * 28);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private static int maxLines(Font font, List<Entry> entries, int textWidth) {
        int worst = 1;
        for (Entry entry : entries) {
            worst = Math.max(worst, wrap(font, entry.label(), textWidth, 2).size());
        }
        return worst;
    }

    private static List<List<Entry>> packActions(Font font, List<Entry> actions, int maxWidth) {
        List<List<Entry>> rows = new ArrayList<>();
        List<Entry> current = new ArrayList<>();
        int used = 0;
        for (Entry entry : actions) {
            int w = Math.max(64, font.width(entry.label()) + 20);
            if (!current.isEmpty() && used + GAP + w > maxWidth) {
                rows.add(current);
                current = new ArrayList<>();
                used = 0;
            }
            used += (current.isEmpty() ? 0 : GAP) + w;
            current.add(entry);
        }
        if (!current.isEmpty()) {
            rows.add(current);
        }
        return rows;
    }

    /** 按像素宽度换行，最多 {@code maxLines} 行。中文逐字符累加。 */
    private static List<String> wrap(Font font, String text, int maxWidth, int maxLines) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty() || maxWidth <= 0) {
            lines.add(text == null ? "" : text);
            return lines;
        }
        StringBuilder current = new StringBuilder();
        int consumed = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            String next = current.toString() + c;
            if (font.width(next) > maxWidth && current.length() > 0) {
                lines.add(current.toString());
                consumed += current.length();
                current.setLength(0);
                if (lines.size() == maxLines) {
                    break;
                }
            }
            current.append(c);
        }
        if (lines.size() < maxLines && current.length() > 0) {
            lines.add(current.toString());
            consumed += current.length();
        }
        if (lines.size() == maxLines && consumed < text.length()) {
            String last = lines.get(maxLines - 1);
            String withDots = last + "…";
            while (font.width(withDots) > maxWidth && last.length() > 1) {
                last = last.substring(0, last.length() - 1);
                withDots = last + "…";
            }
            lines.set(maxLines - 1, withDots);
        }
        return lines;
    }

    private void run(String command) {
        if (this.minecraft == null || this.minecraft.player == null || command == null) {
            return;
        }
        String cmd = command.startsWith("/") ? command.substring(1) : command;
        this.minecraft.player.connection.sendCommand(cmd);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(panelLeft, panelTop, panelLeft + panelWidth, panelTop + panelHeight, PANEL_BG);
        outline(graphics, panelLeft, panelTop, panelWidth, panelHeight, PANEL_BORDER);

        int headerBottom = panelTop + PAD + titleLines.size() * LINE_H + 4;
        graphics.fill(panelLeft + 1, panelTop + 1, panelLeft + panelWidth - 1, headerBottom, HEADER_BG);

        int ty = panelTop + PAD;
        for (String line : titleLines) {
            graphics.drawString(this.font, line, panelLeft + PAD, ty, ACCENT, false);
            ty += LINE_H;
        }
        int underline = 0;
        for (String line : titleLines) {
            underline = Math.max(underline, this.font.width(line));
        }
        graphics.fill(panelLeft + PAD, ty - 1,
                panelLeft + PAD + Math.min(panelWidth - PAD * 2, underline), ty + 1, ACCENT);

        if (dividerY >= 0) {
            graphics.fill(panelLeft + PAD, dividerY, panelLeft + panelWidth - PAD, dividerY + 1, DIVIDER);
        }

        graphics.drawString(this.font, "Tab 切换 · Enter 确认 · Esc 关闭",
                panelLeft + PAD, hintY, TEXT_MUTED, false);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static void outline(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y, x + 1, y + h, color);
        g.fill(x + w - 1, y, x + w, y + h, color);
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
     * 画 12×12 的几何图标。
     *
     * <p>没有美术资源，所以用「形状语言」区分类型：方块=装备、菱形=召唤物、十字=消耗品、
     * 圆圈=定向卡、横条=普通卡。缩略图下要能辨，所以都做实心填充。
     */
    private static void drawIcon(GuiGraphics g, String icon, int x, int y, int color) {
        switch (icon) {
            case "equip" -> g.fill(x + 2, y + 2, x + 10, y + 10, color);
            case "summon" -> {
                g.fill(x + 6, y + 1, x + 8, y + 3, color);
                g.fill(x + 4, y + 3, x + 10, y + 9, color);
                g.fill(x + 2, y + 5, x + 12, y + 7, color);
                g.fill(x + 4, y + 9, x + 10, y + 11, color);
            }
            case "consume" -> {
                g.fill(x + 5, y + 1, x + 9, y + 11, color);
                g.fill(x + 1, y + 5, x + 13, y + 9, color);
            }
            case "target" -> {
                g.fill(x + 5, y + 1, x + 9, y + 5, color);
                g.fill(x + 1, y + 5, x + 13, y + 9, color);
                g.fill(x + 5, y + 9, x + 9, y + 13, color);
            }
            case "card" -> g.fill(x + 2, y + 4, x + 12, y + 8, color);
            default -> {
                // action：动作按钮是紧凑居中按钮，不画图标
            }
        }
    }

    private final class CardButton extends Button {

        private final Placed data;
        /** 选项卡片的基准顶部 Y（不含滚动偏移）；动作按钮恒等于自身 y。 */
        private final int baseTop;

        CardButton(Placed data, int baseTop, OnPress onPress) {
            super(data.x(), baseTop, data.w(), data.h(),
                    Component.literal(data.entry().label()), onPress, Button.DEFAULT_NARRATION);
            this.data = data;
            this.baseTop = baseTop;
        }

        int baseTop() {
            return baseTop;
        }

        @Override
        protected void renderContents(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            Font font = Minecraft.getInstance().font;
            Entry entry = data.entry();
            // 选项卡片滚出列表区时裁剪，避免画到标题/动作区上；动作按钮不参与滚动不裁剪。
            boolean clip = !entry.action() && maxScroll > 0;
            if (clip) {
                graphics.enableScissor(panelLeft + 1, listTop, panelLeft + panelWidth - 1, listBottom);
            }
            try {
                boolean hot = isHoveredOrFocused();
                int background = !this.active ? CARD_BG_DISABLED : (hot ? CARD_BG_HOVER : CARD_BG);
                graphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), background);
                outline(graphics, getX(), getY(), getWidth(), getHeight(),
                        hot && this.active ? CARD_BORDER_HOVER : CARD_BORDER);

                int baseColor = !this.active ? TEXT_DISABLED
                        : (entry.chosen() ? toneColor(entry.tone()) : TEXT);

                if (entry.action()) {
                    int centerY = getY() + (getHeight() - LINE_H) / 2;
                    for (String line : data.lines()) {
                        graphics.drawString(font, line,
                                getX() + (getWidth() - font.width(line)) / 2, centerY, baseColor, false);
                        centerY += LINE_H;
                    }
                    return;
                }

                // 选中态：左侧 3px 色条 + 文字变色
                if (entry.chosen()) {
                    graphics.fill(getX() + 1, getY() + 1, getX() + 4, getY() + getHeight() - 1, baseColor);
                }

                // 图标与主标题块垂直居中对齐
                drawIcon(graphics, entry.icon(), getX() + 8, getY() + CARD_PAD_Y - 1,
                        this.active ? toneColor(entry.tone()) : TEXT_DISABLED);

                List<String> lines = data.lines();
                int total = lines.size() + (entry.detail().isEmpty() ? 0 : 1);
                int blockHeight = total * LINE_H - 2;
                int textY = getY() + (getHeight() - blockHeight) / 2;
                int textX = getX() + TEXT_LEFT;

                for (String line : lines) {
                    graphics.drawString(font, line, textX, textY, baseColor, false);
                    textY += LINE_H;
                }
                if (!entry.detail().isEmpty()) {
                    graphics.drawString(font, entry.detail(), textX, textY, TEXT_MUTED, false);
                }

                // 右侧状态点：可用（色调）/ 不可用（暗灰）
                int dot = this.active ? toneColor(entry.tone()) : TEXT_DISABLED;
                graphics.fill(getX() + getWidth() - 10, getY() + getHeight() / 2 - 2,
                        getX() + getWidth() - 6, getY() + getHeight() / 2 + 2, dot);

                // 悬停兜底：只在主标题被省略号截断时才弹（wrap() 放不下才可能发生），
                // 不截断也弹会变成「鼠标移过就挡住半屏」。副标题固定跟着透传——
                // 「词条 N 条 / 品质 · 点数」正是玩家选卡时的判断依据，弹了就要弹全。
                if (hot && this.active
                        && font.width(entry.label()) > (getWidth() - TEXT_LEFT - TEXT_RIGHT) * lines.size()) {
                    List<Component> tip = new ArrayList<>();
                    tip.add(Component.literal(entry.label()));
                    if (!entry.detail().isEmpty()) {
                        tip.add(Component.literal(entry.detail()).withStyle(ChatFormatting.GRAY));
                    }
                    graphics.setTooltipForNextFrame(font, tip, Optional.empty(), mouseX, mouseY);
                }
            } finally {
                if (clip) {
                    graphics.disableScissor();
                }
            }
        }
    }
}
