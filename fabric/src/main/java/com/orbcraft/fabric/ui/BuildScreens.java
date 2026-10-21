package com.orbcraft.fabric.ui;

import java.util.List;
import java.util.Set;

import com.orbcraft.core.build.BuildSnapshot;
import com.orbcraft.core.build.Composition;
import com.orbcraft.core.build.TypeOption;
import com.orbcraft.core.draft.BaseKind;
import com.orbcraft.core.draft.DraftCard;
import com.orbcraft.core.draft.TargetCard;
import com.orbcraft.core.draft.Tier;
import com.orbcraft.core.hatch.IncubationService;
import com.orbcraft.core.quota.QuotaManager;
import com.orbcraft.core.settle.MaterialTier;
import com.orbcraft.core.spec.BehaviorMode;
import com.orbcraft.core.target.TargetAllowance;
import com.orbcraft.fabric.runtime.OrbcraftRuntime;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

/**
 * 构筑界面的渲染层 —— 把 {@link BuildSnapshot} 画成一屏可点击的聊天文本。
 *
 * <p>这一轮明确不做图形界面（见派单书第 7 节），所以「界面」= 若干行聊天文本 +
 * 带 {@code ClickEvent.RunCommand} 的按钮。点击等同于玩家手敲同名命令，
 * 好处是同一份逻辑只有一条入口：图形界面将来接上时，命令仍然可用，不需要两套状态。
 *
 * <p><b>只读快照，不读 {@code BuildSession}</b>：快照是不可变的，
 * 一次渲染只看到一致的一帧；直接读状态机就可能画出「已选 2 张、上限 1 张」的中间态。
 *
 * <p><b>文案全部来自 core</b>：{@code statusLine()}、{@code Composition.brief()}、
 * {@code DraftCard.label()} 都已经写好了中文，这里再把字段拼一遍就会出现
 * 两处文案不一致，而玩家分不清哪个是对的。
 */
final class BuildScreens {

    private static final ChatFormatting ACCENT = ChatFormatting.GOLD;
    private static final ChatFormatting DIM = ChatFormatting.DARK_GRAY;
    private static final ChatFormatting OK = ChatFormatting.GREEN;
    private static final ChatFormatting BAD = ChatFormatting.RED;

    private BuildScreens() {
    }

    static void show(ServerPlayer player, BuildSeat seat) {
        if (seat.session() == null) {
            showIdle(player);
            return;
        }
        BuildSnapshot snap = seat.session().snapshot();
        switch (snap.phase()) {
            case TYPE_CHOOSING, TYPE_CONFIRMING -> showTypes(player, snap);
            case FINISHED -> showFinalize(player, seat, snap);
            case CANCELLED -> showIdle(player);
            default -> showCards(player, seat, snap);
        }
    }

    /** 没有进行中的构筑时的落地页。 */
    static void showIdle(ServerPlayer player) {
        send(player, Component.literal("[构筑] 还没有进行中的构筑").withStyle(ACCENT));
        send(player, Component.literal("  ")
                .append(button("开始构筑", OK, "/orb build", "四层确认：类型 → 形态 → 修饰 → 效果"))
                .append(Component.literal("  选完四层会出现球的预览，确认后才扣统御力").withStyle(DIM)));
    }

    // ------------------------------------------------------------------ 类型层

    private static void showTypes(ServerPlayer player, BuildSnapshot snap) {
        send(player, Component.literal("[构筑] " + snap.statusLine()).withStyle(ACCENT));
        List<TypeOption> options = snap.typeOptions();
        for (int i = 0; i < options.size(); i++) {
            TypeOption option = options.get(i);
            int index = i + 1;
            if (!option.available()) {
                // 不可用的选项不挂点击事件：让玩家点进去再被拒一次，等于多一次挫败
                send(player, Component.literal("  ✗ " + option.title() + " —— " + option.reason())
                        .withStyle(DIM));
                continue;
            }
            boolean chosen = snap.kind() == option.kind();
            send(player, Component.literal("  ")
                    .append(button((chosen ? "✓ " : "") + option.title(),
                            chosen ? OK : ChatFormatting.WHITE,
                            "/orb card " + index,
                            "词条 " + option.poolSize() + " 条，点一下选定（再点一次取消）")));
        }
        send(player, footer(
                button(snap.canConfirm() ? "确认" : "确认（" + snap.confirmHint() + "）",
                        snap.canConfirm() ? OK : DIM,
                        snap.canConfirm() ? "/orb confirm" : "/orb status",
                        "选定类型后进入形态层"),
                button("放弃", BAD, "/orb cancel", "本次构筑什么都没花，随时可以放弃")));
    }

    // ------------------------------------------------------------------ 发牌三层

    private static void showCards(ServerPlayer player, BuildSeat seat, BuildSnapshot snap) {
        send(player, Component.literal("[构筑] " + snap.statusLine()).withStyle(ACCENT));
        if (snap.targetMissed()) {
            send(player, Component.literal("  定向卡与本次形态不兼容，这一局没能兑现").withStyle(BAD));
        }
        if (!snap.confirmedCards().isEmpty()) {
            send(player, Component.literal("  已确定：" + names(snap.confirmedCards())).withStyle(DIM));
        }
        List<DraftCard> cards = snap.cards();
        Set<Integer> chosen = Set.copyOf(snap.chosen());
        for (int i = 0; i < cards.size(); i++) {
            DraftCard card = cards.get(i);
            boolean picked = chosen.contains(i);
            send(player, Component.literal("  ")
                    .append(button((picked ? "✓" : "") + "[" + (i + 1) + "] " + card.label(),
                            picked ? OK : colorOf(card.quality()),
                            "/orb card " + (i + 1),
                            "点一下" + (picked ? "取消选择" : "选定") + "这一张")));
        }
        MutableComponent footer = footer(
                button(snap.canConfirm() ? "确认" : "确认（" + snap.confirmHint() + "）",
                        snap.canConfirm() ? OK : DIM,
                        snap.canConfirm() ? "/orb confirm" : "/orb status",
                        "本层选够张数才能确认"));
        if (snap.canRerollShape()) {
            footer.append(button("换一批 ×" + snap.shapeRerollsLeft(), ChatFormatting.AQUA,
                    "/orb reroll", "只重发形态层候选，不消耗任何资源"));
        }
        if (!snap.chosen().isEmpty()) {
            footer.append(button("取消本层", ChatFormatting.YELLOW, "/orb clear", "清空本层已选，退到上一层不受影响"));
        }
        footer.append(button("放弃", BAD, "/orb cancel", "放弃整局，不扣任何资源"));
        send(player, footer);
    }

    // ------------------------------------------------------------------ 定稿

    private static void showFinalize(ServerPlayer player, BuildSeat seat, BuildSnapshot snap) {
        Composition comp = snap.composition();
        if (comp == null) {
            // FINISHED 但没有 composition 只可能是 core 被改坏了；别让玩家卡在一屏空白里
            send(player, Component.literal("[构筑] 定稿结果丢了，请重新 /orb build").withStyle(BAD));
            return;
        }
        send(player, Component.literal("[构筑] 已定稿 · " + comp.brief()).withStyle(ACCENT));

        if (comp.kind() == BaseKind.SUMMON) {
            MutableComponent line = Component.literal("  行为：");
            for (BehaviorMode mode : List.of(BehaviorMode.ATTACK, BehaviorMode.GUARD, BehaviorMode.CHAOS)) {
                boolean current = mode == effectiveBehavior(seat, comp);
                line.append(button((current ? "✓ " : "") + mode.zh(), current ? OK : ChatFormatting.WHITE,
                        "/orb behavior " + wire(mode), mode.zh() + "型召唤物"));
            }
            send(player, line);
        } else {
            send(player, Component.literal("  行为：无（" + comp.kind().zh() + "没有行为模式）").withStyle(DIM));
        }

        MutableComponent materials = Component.literal("  材料：");
        for (MaterialTier tier : MaterialTier.values()) {
            boolean current = tier == seat.material();
            materials.append(button((current ? "✓ " : "") + tier.zh() + "料", current ? OK : ChatFormatting.WHITE,
                    "/orb material " + tier.name().toLowerCase(java.util.Locale.ROOT),
                    "材料消耗 ×" + tier.materialMultiplier()));
        }
        send(player, materials);

        send(player, Component.literal("  祭坛加速：")
                .append(button(seat.onAltar() ? "已开启（孵化时长减半）" : "关闭", seat.onAltar() ? OK : DIM,
                        "/orb altar", "点一下切换"))
                .withStyle(ChatFormatting.GRAY));

        send(player, footer(
                button("预览定稿", OK, "/orb preview", "先看稀有度、孵化时长、要扣几点统御力"),
                button("放弃", BAD, "/orb cancel", "定稿结果直接丢弃，不扣任何资源")));
    }

    // ------------------------------------------------------------------ 预览

    static void showPreview(ServerPlayer player, BuildSeat seat, boolean orbReady) {
        IncubationService.Preview preview = seat.preview();
        Composition comp = seat.session() == null ? null : seat.session().composition().orElse(null);
        if (preview == null || comp == null) {
            send(player, Component.literal("[构筑] 预览已失效，请重新点「预览定稿」").withStyle(BAD));
            return;
        }
        var spec = preview.spec();
        send(player, Component.literal("[构筑] 预览 · " + spec.rarity().zh() + " · " + comp.kind().zh()
                + " · " + spec.displayName()).withStyle(ACCENT));

        QuotaManager.State state = OrbcraftRuntime.hatch().stateOf(seat.ownerKey());
        int before = state.usedPoints();
        int after = before + preview.cost();
        send(player, Component.literal("  孵化 " + clock(spec.hatchSeconds())
                + " · 统御力 " + before + "/" + QuotaManager.POOL_SIZE + " → " + after + "/"
                + QuotaManager.POOL_SIZE + "（扣 " + preview.cost() + " 点）"
                + " · " + categoryZh(preview.category())).withStyle(ChatFormatting.GRAY));

        boolean allowed = preview.allowed() && orbReady;
        if (allowed) {
            send(player, Component.literal("  现在可以造").withStyle(OK));
        } else if (!orbReady) {
            send(player, Component.literal("  造不出来：球物品还没接上，确认后会白扣配额，所以先别确认").withStyle(BAD));
        } else {
            send(player, Component.literal("  造不出来：" + preview.reason()).withStyle(BAD));
        }
        send(player, Component.literal("  确认之前什么都没扣；点了「确认造球」才扣").withStyle(DIM));

        send(player, footer(
                button(allowed ? "确认造球" : "确认造球（现在造不了）",
                        allowed ? OK : DIM,
                        allowed ? "/orb submit" : "/orb status",
                        "扣统御力并生成一颗球"),
                button("返回", ChatFormatting.YELLOW, "/orb status", "回到定稿界面改参数")));
    }

    // ------------------------------------------------------------------ 定向卡

    static void showTargets(ServerPlayer player, BuildSeat seat) {
        TargetAllowance book = OrbcraftRuntime.allowanceOf(seat.ownerKey());
        long today = BuildRuntime.todayEpochDay();
        BaseKind kind = seat.session() == null ? null : seat.session().kind().orElse(null);

        if (kind == null) {
            send(player, Component.literal("[构筑] 定向卡 · " + (seat.session() == null ? "还没有进行中的构筑" : "还没选类型"))
                    .withStyle(ACCENT));
            for (BaseKind k : List.of(BaseKind.EQUIPMENT, BaseKind.SUMMON, BaseKind.CONSUMABLE)) {
                send(player, Component.literal("  " + k.zh() + "：今日剩余 "
                        + book.remaining(seat.ownerKey(), today, k) + "/" + book.perKindPerDay())
                        .withStyle(ChatFormatting.GRAY));
            }
            send(player, Component.literal("  定向卡绑定类型：先在类型层选好类型，再回来挑").withStyle(DIM));
            send(player, footer(button("返回构筑", ChatFormatting.YELLOW, "/orb status", "回到当前一层")));
            return;
        }

        int left = book.remaining(seat.ownerKey(), today, kind);
        send(player, Component.literal("[构筑] 定向卡 · " + kind.zh() + " · 今日剩余 " + left + "/"
                + book.perKindPerDay()).withStyle(ACCENT));

        boolean usablePhase = seat.session().phase().layerIndex() == 0;
        if (!usablePhase) {
            send(player, Component.literal(seat.session().phase().isTerminal()
                    ? "  本局已经结束，定向卡要新开一局再用"
                    : "  已经发过形态牌了，定向卡必须在发牌前用").withStyle(BAD));
        } else if (left <= 0) {
            send(player, Component.literal("  " + kind.zh() + "今天的定向额度已用完").withStyle(BAD));
        }

        List<TargetCard> cards = BuildData.targetDeck().forKind(kind);
        for (TargetCard card : cards) {
            boolean clickable = usablePhase && left > 0;
            String label = card + "（" + card.targetLayer().zh() + "）";
            if (clickable) {
                send(player, Component.literal("  ").append(button(label, ChatFormatting.AQUA,
                        "/orb target " + card.id(),
                        "本局" + card.targetLayer().zh() + "层必定出现，品质仍然现掷")));
            } else {
                send(player, Component.literal("  " + label).withStyle(DIM));
            }
        }
        send(player, Component.literal("  一用就计入今天的额度，不能撤销").withStyle(DIM));
        send(player, footer(button("返回构筑", ChatFormatting.YELLOW, "/orb status", "回到当前一层")));
    }

    // ------------------------------------------------------------------ 工具

    private static BehaviorMode effectiveBehavior(BuildSeat seat, Composition comp) {
        // 装备 / 消耗品必须显式 NONE：HatchSubmit 的约定，不是这里临时决定的
        if (comp.kind() != BaseKind.SUMMON) {
            return BehaviorMode.NONE;
        }
        return seat.behavior() == null ? BehaviorMode.ATTACK : seat.behavior();
    }

    /** 命令参数里的行为名：与 {@code /orb behavior <name>} 的解析口径一致。 */
    private static String wire(BehaviorMode mode) {
        return mode.name().toLowerCase(java.util.Locale.ROOT);
    }

    private static MutableComponent footer(MutableComponent... buttons) {
        MutableComponent line = Component.literal("  ");
        for (MutableComponent b : buttons) {
            line.append(b).append(Component.literal(" "));
        }
        return line;
    }

    /**
     * 一个可点击按钮。
     *
     * <p>样式用 {@code withStyle(UnaryOperator)} 一次性写完：先 {@code withStyle(颜色)}
     * 再 {@code withStyle(styleWithClick)} 会把颜色覆盖掉，症状是按钮全部变成默认色 ——
     * 这个坑在 1.21 的 {@code Style} 上很容易踩。
     */
    private static MutableComponent button(String label, ChatFormatting color, String command, String hover) {
        return Component.literal("[" + label + "]").withStyle(style -> style
                .withColor(color)
                .withClickEvent(new ClickEvent.RunCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
    }

    private static ChatFormatting colorOf(Tier tier) {
        return switch (tier) {
            case WHITE -> ChatFormatting.WHITE;
            case BLUE -> ChatFormatting.BLUE;
            case GOLD -> ChatFormatting.GOLD;
            case RED -> ChatFormatting.RED;
        };
    }

    private static String names(List<DraftCard> cards) {
        StringBuilder sb = new StringBuilder();
        for (DraftCard card : cards) {
            if (sb.length() > 0) {
                sb.append(" / ");
            }
            sb.append(card.affix().name()).append('·').append(card.quality().zh());
        }
        return sb.toString();
    }

    private static String categoryZh(QuotaManager.Category category) {
        return switch (category) {
            case EQUIPMENT -> "占装备位";
            case PRIMARY -> "占主宠位";
            case CHAOS -> "混乱体";
            case FREE -> "不占配额";
        };
    }

    private static String clock(int seconds) {
        return String.format(java.util.Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    static void toast(ServerPlayer player, String message, ChatFormatting color) {
        player.displayClientMessage(Component.literal("[构筑] " + message).withStyle(color), false);
    }

    private static void send(ServerPlayer player, Component line) {
        player.displayClientMessage(line, false);
    }
}
