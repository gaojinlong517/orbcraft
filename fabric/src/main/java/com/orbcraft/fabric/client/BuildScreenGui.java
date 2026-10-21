package com.orbcraft.fabric.client;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 构筑界面：把服务端发来的按钮铺成可点的卡牌。
 *
 * <p><b>它只做两件事</b>：画出来、点下去发一条命令。所有判断（能不能选、选了会怎样、
 * 配额够不够）都在服务端，界面不复制任何规则 —— 否则「界面显示可选、点了却报错」
 * 这种不一致会永远跟着版本走。
 *
 * <p><b>为什么点按钮是发命令而不是走自定义包</b>：命令通道本来就在，玩家手敲也能走同一条路。
 * 界面只是输入方式之一，它挂了玩法不受影响；反过来，如果界面自己维护一套动作，
 * 就变成了两个入口两套状态。
 *
 * <p>数据来自 {@code BuildScreenS2C}（JSON 字符串），形状见那边的注释。
 */
public final class BuildScreenGui extends Screen {

    private record Entry(String label, String command, ChatFormatting color) {
    }

    private final List<Entry> entries = new ArrayList<>();
    private String heading = "";

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
                entries.add(new Entry(
                        one.get("label").getAsString(),
                        one.get("cmd").getAsString(),
                        formatOf(one.has("tone") ? one.get("tone").getAsString() : "white")));
            }
        } catch (RuntimeException e) {
            // 解析失败就当空界面：宁可什么都不显示，也不要让点击发出一条错误的命令。
            entries.clear();
            heading = "构筑界面数据异常";
        }
    }

    @Override
    protected void init() {
        if (entries.isEmpty()) {
            return;
        }
        boolean twoColumns = entries.size() > 6;
        int columns = twoColumns ? 2 : 1;
        int buttonWidth = twoColumns ? 150 : 300;
        int gap = 8;
        int rowHeight = 24;
        int rows = (entries.size() + columns - 1) / columns;
        int panelWidth = columns * buttonWidth + (columns - 1) * gap + 24;
        int left = (this.width - (columns * buttonWidth + (columns - 1) * gap)) / 2;
        int top = Math.max(40, (this.height - rows * rowHeight) / 2 - 10);

        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            int col = i % columns;
            int row = i / columns;
            int x = left + col * (buttonWidth + gap);
            int y = top + row * rowHeight;
            Button button = Button.builder(Component.literal(entry.label())
                            .withStyle(entry.color()), b -> run(entry.command()))
                    .bounds(x, y, buttonWidth, 20)
                    .build();
            addRenderableWidget(button);
        }
        this.panelWidth = panelWidth;
        this.panelTop = top - 26;
        this.panelHeight = rows * rowHeight + 16;
    }

    private int panelWidth = 300;
    private int panelTop = 20;
    private int panelHeight = 120;

    private void run(String command) {
        if (this.minecraft == null || this.minecraft.player == null || command == null) {
            return;
        }
        String cmd = command.startsWith("/") ? command.substring(1) : command;
        this.minecraft.player.connection.sendCommand(cmd);
        // 不主动关界面：服务端处理完会推一份新的快照过来，界面原地刷新。
        // 如果这条命令让构筑结束，服务端推的就是「没有进行中的构筑」，玩家自己按 ESC 退出。
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 半透明底：避免在明亮地形上白字看不清。不调用 super 的背景渲染，
        // 那样会在单人模式下触发世界模糊，和「这是个随手可关的选择框」的定位不符。
        int left = (this.width - this.panelWidth) / 2;
        graphics.fill(left, this.panelTop, left + this.panelWidth,
                this.panelTop + this.panelHeight, 0xC0101010);
        graphics.drawCenteredString(this.font, this.heading, this.width / 2, this.panelTop + 8, 0xFFFFAA00);
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, "点卡片选择 · ESC 关闭（构筑状态不会丢）",
                this.width / 2, this.panelTop + this.panelHeight - 12, 0xFF808080);
    }

    @Override
    public boolean isPauseScreen() {
        // 单人模式下关掉暂停：否则界面一开世界就停，玩家的球读条也跟着停。
        return false;
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
}
