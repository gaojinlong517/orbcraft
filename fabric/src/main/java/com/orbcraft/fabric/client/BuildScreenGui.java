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
 * <p><b>文字不截断（2026-10-21 修正）。</b> 上一版为了「不撑破卡片」把超长文案截成
 * {@code …}，结果玩家看不到完整词条名。现在改成<b>先测量、再决定布局</b>：
 * 列数按最长文案反推（3 → 2 → 1），卡片高度随行数变化（1 行 24、2 行 34），
 * 标题最多两行，动作按钮放不下就换行。仍然放不下的个别长文案才截断，
 * 并把完整内容挂在 hover 提示里。截断是最后手段，不是默认行为。
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

    /** 间距阶梯只有 8/14/24/34，避免「差 1 像素」的随手值。 */
    private static final int PAD = 14;
    private static final int GAP = 8;
    private static final int LINE_H = 10;
    private static final int CARD_H_1 = 24;
    private static final int CARD_H_2 = 34;
    private static final int ACTION_H = 22;
    /** 文字区左侧留白（含选中色条）与右侧留白（状态点）。 */
    private static final int TEXT_LEFT = 9;
    private static final int TEXT_RIGHT = 14;

    private String heading = "";
    private final List<Entry> entries = new ArrayList<>();

    // 布局结果：init 算一次，render 直接用
    private final List<Placed> placed = new ArrayList<>();
    private final List<String> titleLines = new ArrayList<>();
    private int panelLeft;
    private int panelTop;
    private int panelWidth;
    private int panelHeight;
    private int dividerY = -1;
    private int hintY;

    /** 一条可点项。{@code action} 决定它进卡片网格还是底部动作行。 */
    private record Entry(String label, String command, ChatFormatting tone, boolean chosen, boolean action) {
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
        placed.clear();
        titleLines.clear();

        Font font = this.font;
        List<Entry> options = new ArrayList<>();
        List<Entry> actions = new ArrayList<>();
        for (Entry entry : entries) {
            (entry.action() ? actions : options).add(entry);
        }

        // 面板尽量宽：文字优先，窗边留 12。
        panelWidth = Math.min(this.width - 24, 620);

        // 列数按「最长文案能否一行放下」反推：3 → 2 → 1。
        // 都放不下就用 1 列 + 两行（宁可高一点，也不要截断）。
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
        int cardHeight = linesNeeded > 1 ? CARD_H_2 : CARD_H_1;

        // 标题最多两行，表头高度跟着走。
        titleLines.addAll(wrap(font, heading, panelWidth - PAD * 2, 2));
        int headerHeight = 8 + titleLines.size() * LINE_H + 4;

        int rows = options.isEmpty() ? 0 : (options.size() + columns - 1) / columns;
        List<List<Entry>> actionRows = packActions(font, actions, panelWidth - PAD * 2);
        int actionBlock = actionRows.isEmpty() ? 0
                : 12 + 1 + GAP + actionRows.size() * (ACTION_H + GAP) - GAP;

        int bodyTop = PAD + headerHeight + GAP;
        panelHeight = bodyTop + rows * (cardHeight + GAP) + (rows > 0 ? -GAP : 0)
                + actionBlock + GAP + LINE_H + PAD;
        panelLeft = (this.width - panelWidth) / 2;
        panelTop = Math.max(12, (this.height - panelHeight) / 2);

        int y = panelTop + bodyTop;
        for (int i = 0; i < options.size(); i++) {
            Entry entry = options.get(i);
            int x = panelLeft + PAD + (i % columns) * (cardWidth + GAP);
            int top = y + (i / columns) * (cardHeight + GAP);
            placed.add(new Placed(entry, x, top, cardWidth, cardHeight,
                    wrap(font, entry.label(), cardWidth - TEXT_LEFT - TEXT_RIGHT, 2)));
        }
        if (rows > 0) {
            y += rows * (cardHeight + GAP) - GAP;
        }

        if (!actionRows.isEmpty()) {
            dividerY = y + 12;
            int rowTop = dividerY + 1 + GAP;
            for (List<Entry> row : actionRows) {
                int cursorRight = panelLeft + panelWidth - PAD;
                for (int i = row.size() - 1; i >= 0; i--) {
                    Entry entry = row.get(i);
                    int w = Math.max(64, font.width(entry.label()) + 20);
                    cursorRight -= w;
                    placed.add(new Placed(entry, cursorRight, rowTop, w, ACTION_H,
                            wrap(font, entry.label(), w - 14, 1)));
                    cursorRight -= GAP;
                }
                rowTop += ACTION_H + GAP;
            }
            y = rowTop - GAP;
        }

        hintY = y + GAP + 2;

        for (Placed p : placed) {
            addRenderableWidget(new CardButton(p, ignored -> run(p.entry().command())));
        }
    }

    /** 每个选项在给定文本宽度下需要几行，取最大值。 */
    private static int maxLines(Font font, List<Entry> entries, int textWidth) {
        int worst = 1;
        for (Entry entry : entries) {
            worst = Math.max(worst, wrap(font, entry.label(), textWidth, 2).size());
        }
        return worst;
    }

    /** 动作按钮按可用宽度分行，行内保持原顺序。 */
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

    /**
     * 按像素宽度换行，最多 {@code maxLines} 行。
     *
     * <p>中文没有空格可断，所以逐字符累加。超出行数时只在最后一行补 {@code …}，
     * 完整文案由 hover 提示兜底 —— 先保证「看得到内容」，再保证「放得下」。
     */
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
        // 不主动关界面：服务端处理完会推新快照，界面原地刷新。
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 半透明底：保证任何地形上文字都可读；不开世界模糊，因为这是随手可关的选择框。
        graphics.fill(panelLeft, panelTop, panelLeft + panelWidth, panelTop + panelHeight, PANEL_BG);
        outline(graphics, panelLeft, panelTop, panelWidth, panelHeight, PANEL_BORDER);

        int headerBottom = panelTop + PAD + titleLines.size() * LINE_H + 4;
        graphics.fill(panelLeft + 1, panelTop + 1, panelLeft + panelWidth - 1, headerBottom, HEADER_BG);

        int ty = panelTop + PAD;
        for (String line : titleLines) {
            graphics.drawString(this.font, line, panelLeft + PAD, ty, ACCENT, false);
            ty += LINE_H;
        }
        // 标题下划线：强调色唯一强用途，把「这是哪一屏」和下面卡片分开。
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
        // 单人模式不暂停：否则界面一开世界就停，球也不孵了。
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
     * 卡牌按钮：外观全自定义，行为全部继承原版。
     *
     * <p>为什么不自己写命中检测：原版 {@code Button} 已经实现了 hover、焦点、
     * Tab 遍历、Enter/空格激活，以及给读屏工具的朗读文本。重写只会引入
     * 「鼠标能点、键盘不能」这类只有部分玩家会遇到的缺陷。
     */
    private static final class CardButton extends Button {

        private final Placed data;

        CardButton(Placed data, OnPress onPress) {
            super(data.x(), data.y(), data.w(), data.h(),
                    // 朗读用完整文案，不是屏幕上那两行
                    Component.literal(data.entry().label()), onPress, Button.DEFAULT_NARRATION);
            this.data = data;
        }

        @Override
        protected void renderContents(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            Font font = Minecraft.getInstance().font;
            Entry entry = data.entry();
            boolean hot = isHoveredOrFocused();
            int background = !this.active ? CARD_BG_DISABLED : (hot ? CARD_BG_HOVER : CARD_BG);
            graphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), background);
            outline(graphics, getX(), getY(), getWidth(), getHeight(),
                    hot && this.active ? CARD_BORDER_HOVER : CARD_BORDER);

            // 选中态：左侧 3px 色条 + 文字变色，双通道（缩略图下 ✓ 前缀看不清）
            if (entry.chosen()) {
                graphics.fill(getX() + 1, getY() + 1, getX() + 4, getY() + getHeight() - 1,
                        toneColor(entry.tone()));
            }

            int textColor = !this.active ? TEXT_DISABLED
                    : (entry.chosen() ? toneColor(entry.tone()) : TEXT);
            List<String> lines = data.lines();
            int blockHeight = lines.size() * LINE_H - 2;
            int textY = getY() + (getHeight() - blockHeight) / 2;

            if (entry.action()) {
                // 动作按钮居中：动作是「点一下就走」，不需要逐个比较
                for (String line : lines) {
                    graphics.drawString(font, line,
                            getX() + (getWidth() - font.width(line)) / 2, textY, textColor, false);
                    textY += LINE_H;
                }
            } else {
                int textX = getX() + (entry.chosen() ? TEXT_LEFT + 1 : TEXT_LEFT);
                for (String line : lines) {
                    graphics.drawString(font, line, textX, textY, textColor, false);
                    textY += LINE_H;
                }
                // 右侧状态点：可用（色调）/ 不可用（暗灰），余光可辨
                int dot = this.active ? toneColor(entry.tone()) : TEXT_DISABLED;
                graphics.fill(getX() + getWidth() - 10, getY() + getHeight() / 2 - 2,
                        getX() + getWidth() - 6, getY() + getHeight() / 2 + 2, dot);
            }

            // 真的放不下时（两行仍溢出）用 hover 给全文 —— 截断的兜底说明
            if (hot && truncated(font, entry)) {
                graphics.setTooltipForNextFrame(Component.literal(entry.label()), mouseX, mouseY);
            }
        }

        private boolean truncated(Font font, Entry entry) {
            int available = entry.action()
                    ? getWidth() - 14
                    : getWidth() - TEXT_LEFT - TEXT_RIGHT;
            return font.width(entry.label()) > available * data.lines().size();
        }
    }
}
