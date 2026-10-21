package com.orbcraft.fabric.ui;

import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.orbcraft.core.build.BuildConfig;
import com.orbcraft.core.build.BuildException;
import com.orbcraft.core.build.BuildPhase;
import com.orbcraft.core.build.BuildSession;
import com.orbcraft.core.build.Composition;
import com.orbcraft.core.build.TypeOption;
import com.orbcraft.core.draft.BaseKind;
import com.orbcraft.core.draft.TargetCard;
import com.orbcraft.core.hatch.HatchException;
import com.orbcraft.core.hatch.HatchSubmit;
import com.orbcraft.core.hatch.HatchTicket;
import com.orbcraft.core.hatch.IncubationService;
import com.orbcraft.core.settle.MaterialTier;
import com.orbcraft.core.spec.BehaviorMode;
import com.orbcraft.core.target.TargetAllowance;
import com.orbcraft.core.target.TargetDeck;
import com.orbcraft.core.target.TargetException;
import com.orbcraft.core.target.TargetPlan;
import com.orbcraft.fabric.contract.OrbItemContract;
import com.orbcraft.fabric.contract.Services;

import net.minecraft.ChatFormatting;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * 构筑入口的动作层 —— 玩家每点一下按钮，最终都落到这里的一个方法。
 *
 * <p>这一层的职责只有三件：<b>取状态、调 core、把失败翻译成中文</b>。
 * 它不持有状态（状态在 {@link BuildSeat}）、不拼文案细节（在 {@link BuildScreens}）、
 * 更不重新实现 core 的规则（发牌、配额、稀有度都在 {@code core}）。
 *
 * <p><b>所有方法都不向外抛异常。</b> 这些方法跑在命令回调里，异常会连累整个 tick，
 * 玩家的表现是「服务器卡一下，然后这次操作凭空消失」。玩法分支（没选够、额度用完、
 * 配额满）统一走返回值 → 中文提示。
 *
 * <p><b>「扣额度」的时机是这里唯一的硬约束</b>：{@code preview} 一个字段都不改，
 * {@code submit} 才扣。任何提前扣减都会让「点了预览就退出」变成一次真实的损失。
 */
public final class BuildFlow {

    private static final Logger LOG = LoggerFactory.getLogger("orbcraft/build");

    private BuildFlow() {
    }

    // ------------------------------------------------------------------ 开局

    public static void start(ServerPlayer player) {
        BuildSeat seat = BuildRuntime.seat(player);
        if (seat.session() != null) {
            // 已有构筑时不开新局：静默覆盖会让玩家上一步选的牌凭空消失，而他可能已经用掉定向卡
            BuildScreens.toast(player, seat.session().phase() == BuildPhase.FINISHED
                    ? "上一局已经定稿但还没造球，用 /orb status 处理掉，或 /orb cancel 放弃"
                    : "已经有一局在进行了，继续点牌或用 /orb cancel 放弃", ChatFormatting.YELLOW);
            BuildScreens.show(player, seat);
            return;
        }
        try {
            BuildSession session = new BuildSession(BuildData.table(), BuildConfig.defaults(),
                    // 种子来自系统随机源：BuildSession 只用它做一次可回放的抽卡，
                    // 不需要和世界种子有任何关系
                    java.util.concurrent.ThreadLocalRandom.current().nextLong());
            seat.session(session);
        } catch (RuntimeException e) {
            // 卡表加载失败 / 配置非法：这是故障，不是玩法分支，必须进日志
            LOG.error("[orbcraft/build] 构筑开局失败", e);
            BuildScreens.toast(player, "卡表没加载出来，这一局开不了；具体原因见服务端日志", ChatFormatting.RED);
            return;
        }
        BuildScreens.show(player, seat);
    }

    // ------------------------------------------------------------------ 选牌

    /** {@code oneBased} 是玩家看到的序号（从 1 开始），命令参数直接传它。 */
    public static void choose(ServerPlayer player, int oneBased) {
        BuildSeat seat = require(player);
        if (seat == null) {
            return;
        }
        BuildSession session = seat.session();
        int index = oneBased - 1;
        try {
            if (session.phase().layerIndex() == 0) {
                // 类型层按类型名选，不按下标：将来展示顺序一变，下标就会指向另一个类型
                List<TypeOption> options = session.typeOptions();
                if (index < 0 || index >= options.size()) {
                    BuildScreens.toast(player, "没有第 " + oneBased + " 个选项", ChatFormatting.RED);
                    return;
                }
                TypeOption option = options.get(index);
                if (!option.available()) {
                    BuildScreens.toast(player, "「" + option.title() + "」现在不能选：" + option.reason(),
                            ChatFormatting.RED);
                    return;
                }
                session.chooseType(option.kind());
            } else {
                session.choose(index);
            }
        } catch (BuildException e) {
            BuildScreens.toast(player, e.getMessage(), ChatFormatting.RED);
            BuildScreens.show(player, seat);
            return;
        }
        seat.clearPending();
        BuildScreens.show(player, seat);
    }

    public static void confirm(ServerPlayer player) {
        BuildSeat seat = require(player);
        if (seat == null) {
            return;
        }
        Optional<Composition> finished;
        try {
            finished = seat.session().confirm();
        } catch (BuildException e) {
            BuildScreens.toast(player, e.getMessage(), ChatFormatting.RED);
            BuildScreens.show(player, seat);
            return;
        }
        seat.clearPending();
        if (finished.isPresent()) {
            BuildScreens.toast(player, "四层走完，已定稿。先预览、再确认造球", ChatFormatting.GREEN);
        }
        BuildScreens.show(player, seat);
    }

    public static void clearLayer(ServerPlayer player) {
        BuildSeat seat = require(player);
        if (seat == null) {
            return;
        }
        try {
            seat.session().clearChoices();
        } catch (BuildException e) {
            BuildScreens.toast(player, e.getMessage(), ChatFormatting.RED);
            return;
        }
        seat.clearPending();
        BuildScreens.show(player, seat);
    }

    public static void reroll(ServerPlayer player) {
        BuildSeat seat = require(player);
        if (seat == null) {
            return;
        }
        try {
            seat.session().rerollShape();
        } catch (BuildException e) {
            BuildScreens.toast(player, e.getMessage(), ChatFormatting.RED);
            return;
        }
        seat.clearPending();
        BuildScreens.toast(player, "形态层已换一批（还剩 " + seat.session().shapeRerollsLeft() + " 次）",
                ChatFormatting.GRAY);
        BuildScreens.show(player, seat);
    }

    /**
     * 放弃整局。
     *
     * <p>定稿之后（{@code FINISHED}）也允许放弃，但那一步不再调 {@code session.cancel()} ——
     * core 的状态机拒绝从终态取消。这里直接把席位清空，效果是「这份定稿结果不要了」。
     */
    public static void cancel(ServerPlayer player) {
        BuildSeat seat = require(player);
        if (seat == null) {
            return;
        }
        BuildSession session = seat.session();
        if (!session.phase().isTerminal()) {
            session.cancel();
        }
        seat.endSession();
        BuildScreens.toast(player, "已放弃本次构筑，没有扣任何东西", ChatFormatting.GRAY);
        BuildScreens.showIdle(player);
    }

    public static void status(ServerPlayer player) {
        BuildScreens.show(player, BuildRuntime.seat(player));
    }

    // ------------------------------------------------------------------ 定向卡

    public static void listTargets(ServerPlayer player) {
        BuildScreens.showTargets(player, BuildRuntime.seat(player));
    }

    /**
     * 用一张定向卡。
     *
     * <p>顺序是刻意的：<b>先确认这张卡能生效，再扣额度</b>。反过来写会造出
     * 「额度扣了但定向被拒」的状态，而 core 没有退额度的入口，玩家当天就少一次机会。
     * 极端情况下（同一 tick 内两次请求）可能出现「生效了但没扣」，那是白送一次，
     * 比扣了不生效安全得多。
     */
    public static void useTarget(ServerPlayer player, String cardId) {
        BuildSeat seat = require(player);
        if (seat == null) {
            return;
        }
        BuildSession session = seat.session();
        BaseKind kind = session.kind().orElse(null);
        if (kind == null) {
            BuildScreens.toast(player, "定向卡绑定类型：先在类型层选好类型", ChatFormatting.YELLOW);
            BuildScreens.showTargets(player, seat);
            return;
        }
        if (session.phase().layerIndex() != 0) {
            BuildScreens.toast(player, "形态牌已经发了，定向卡来不及了", ChatFormatting.RED);
            return;
        }
        TargetDeck deck = BuildData.targetDeck();
        Optional<TargetCard> found = deck.find(cardId);
        if (found.isEmpty()) {
            BuildScreens.toast(player, "没有这张定向卡：" + cardId, ChatFormatting.RED);
            return;
        }
        TargetCard card = found.get();
        if (!deck.offers(kind, card)) {
            BuildScreens.toast(player, "「" + card + "」不适用于" + kind.zh(), ChatFormatting.RED);
            return;
        }
        long today = BuildRuntime.todayEpochDay();
        int left = BuildRuntime.allowanceOf(seat.ownerKey()).remaining(seat.ownerKey(), today, kind);
        if (left <= 0) {
            BuildScreens.toast(player, kind.zh() + "今天的定向额度已用完", ChatFormatting.RED);
            return;
        }
        TargetPlan plan;
        try {
            plan = TargetPlan.of(deck, kind, List.of(cardId));
        } catch (TargetException e) {
            BuildScreens.toast(player, e.getMessage(), ChatFormatting.RED);
            return;
        }
        try {
            session.applyTargetPlan(plan);
        } catch (BuildException e) {
            BuildScreens.toast(player, "定向卡没生效：" + e.getMessage(), ChatFormatting.RED);
            return;
        }
        Optional<TargetAllowance> after = BuildRuntime.spendTarget(seat.ownerKey(), today, kind);
        if (after.isEmpty()) {
            // 上面的 remaining 已经挡过一次，走到这里说明并发写坏了账本 —— 要报警
            LOG.warn("[orbcraft/build] 定向卡已生效但额度扣减失败：{} {}", seat.ownerKey(), card.id());
            BuildScreens.toast(player, "定向卡已生效，但额度记录异常，请把这个情况报给管理员", ChatFormatting.RED);
            return;
        }
        BuildScreens.toast(player, plan.describe() + " · 今日剩余 "
                + after.get().remaining(seat.ownerKey(), today, kind) + "/" + after.get().perKindPerDay(),
                ChatFormatting.GREEN);
        BuildScreens.show(player, seat);
    }

    // ------------------------------------------------------------------ 定稿参数

    public static void setBehavior(ServerPlayer player, String raw) {
        BuildSeat seat = require(player);
        if (seat == null) {
            return;
        }
        Composition comp = finished(seat);
        if (comp == null) {
            BuildScreens.toast(player, "先把四层走完再选行为", ChatFormatting.YELLOW);
            return;
        }
        if (comp.kind() != BaseKind.SUMMON) {
            BuildScreens.toast(player, comp.kind().zh() + "没有行为模式，不需要选", ChatFormatting.YELLOW);
            return;
        }
        BehaviorMode mode;
        try {
            mode = BehaviorMode.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            BuildScreens.toast(player, "未知行为：" + raw + "（攻击 attack / 守护 guard / 混乱 chaos）",
                    ChatFormatting.RED);
            return;
        }
        if (mode == BehaviorMode.NONE) {
            BuildScreens.toast(player, "召唤物必须有行为模式", ChatFormatting.RED);
            return;
        }
        seat.behavior(mode);
        BuildScreens.toast(player, "行为已设为「" + mode.zh() + "」", ChatFormatting.GRAY);
        BuildScreens.show(player, seat);
    }

    public static void setMaterial(ServerPlayer player, String raw) {
        BuildSeat seat = require(player);
        if (seat == null) {
            return;
        }
        if (finished(seat) == null) {
            BuildScreens.toast(player, "先把四层走完再选材料", ChatFormatting.YELLOW);
            return;
        }
        try {
            seat.material(MaterialTier.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            BuildScreens.toast(player, "未知材料档：" + raw + "（试作 / 标准 / 精制 / 大师）", ChatFormatting.RED);
            return;
        }
        BuildScreens.toast(player, "材料已设为「" + seat.material().zh() + "料」", ChatFormatting.GRAY);
        BuildScreens.show(player, seat);
    }

    public static void toggleAltar(ServerPlayer player) {
        BuildSeat seat = require(player);
        if (seat == null) {
            return;
        }
        if (finished(seat) == null) {
            BuildScreens.toast(player, "先把四层走完再决定要不要上祭坛", ChatFormatting.YELLOW);
            return;
        }
        seat.toggleAltar();
        BuildScreens.show(player, seat);
    }

    // ------------------------------------------------------------------ 预览与提交

    /** 预览 —— <b>只读</b>，一个字段都不改。玩家看到数字之后才决定要不要造。 */
    public static void preview(ServerPlayer player) {
        BuildSeat seat = require(player);
        if (seat == null) {
            return;
        }
        Composition comp = finished(seat);
        if (comp == null) {
            BuildScreens.toast(player, "先把四层走完，才有东西可以预览", ChatFormatting.YELLOW);
            return;
        }
        HatchSubmit submit = HatchSubmit
                .of(seat.ownerKey(), comp, effectiveBehavior(seat, comp), seat.material())
                .withAltar(seat.onAltar());
        IncubationService.Preview result;
        try {
            result = BuildRuntime.incubation().preview(submit);
        } catch (RuntimeException e) {
            LOG.error("[orbcraft/build] 预览失败", e);
            BuildScreens.toast(player, "预览算不出来：" + e.getMessage(), ChatFormatting.RED);
            return;
        }
        seat.awaitConfirm(submit, result);
        BuildScreens.showPreview(player, seat, orbItemReady());
    }

    /**
     * 确认造球 —— 整条链路上<b>唯一</b>扣配额的地方。
     *
     * <p>必须存在一份「玩家看过」的预览：不是形式主义，而是因为
     * {@code submit} 会按当前参数重算，没有预览做对账就无法保证
     * 「玩家看到扣 1 点、实际扣 1 点」。
     */
    public static void submit(ServerPlayer player) {
        BuildSeat seat = require(player);
        if (seat == null) {
            return;
        }
        Composition comp = finished(seat);
        if (comp == null) {
            BuildScreens.toast(player, "还没有定稿，先把四层走完", ChatFormatting.YELLOW);
            return;
        }
        HatchSubmit pending = seat.pending();
        if (pending == null || !pending.composition().equals(comp)) {
            // 改了参数却没重新预览，就走这条：旧预览描述的是另一件产物
            BuildScreens.toast(player, "先点「预览定稿」看一眼，再确认造球", ChatFormatting.YELLOW);
            BuildScreens.show(player, seat);
            return;
        }
        OrbItemContract orb;
        try {
            orb = Services.orbItem();
        } catch (IllegalStateException e) {
            // 球物品还没注册：此刻 submit 会扣掉配额却造不出球，所以必须在扣之前拦住
            LOG.warn("[orbcraft/build] 球物品契约尚未注册，已拦下这次提交", e);
            BuildScreens.toast(player, "球物品还没接上，这次没有扣任何配额", ChatFormatting.RED);
            return;
        }

        long now = player.level().getGameTime();
        // lastActiveTick 传 now：玩家此刻正在敲命令，他就是活跃的。
        // 离线封顶只对「重启后恢复的旧票据」有意义，不该拿它削减在线玩家刚造的球。
        HatchTicket ticket;
        try {
            ticket = BuildRuntime.incubation().submit(now, now, pending);
        } catch (HatchException e) {
            BuildScreens.toast(player, "造不出来：" + e.getMessage(), ChatFormatting.RED);
            seat.clearPending();
            BuildScreens.show(player, seat);
            return;
        }

        ItemStack orbStack;
        try {
            orbStack = orb.createOrb(ticket);
        } catch (RuntimeException e) {
            // 票据已经建好（配额已扣），这里失败只能留证：日志里带 ticketId，能人工补发
            LOG.error("[orbcraft/build] 票据 {} 已建但造球失败，需要人工补发", ticket.ticketId(), e);
            BuildScreens.toast(player, "球没造出来，但配额已扣。把这条消息发给管理员：" + ticket.ticketId(),
                    ChatFormatting.RED);
            seat.endSession();
            return;
        }

        boolean stored = player.getInventory().add(orbStack);
        if (!stored) {
            // 背包满不是错误分支：球必须到玩家手里，掉在脚下比凭空消失好
            player.drop(orbStack, false);
        }
        seat.endSession();
        BuildScreens.toast(player, "造好了：" + ticket.spec().displayName() + " · 充能 "
                + ticket.totalSeconds() + " 秒"
                + (stored ? " · 球已放进背包" : " · 背包满了，球掉在脚下"), ChatFormatting.GREEN);
        BuildScreens.showIdle(player);
    }

    // ------------------------------------------------------------------ 内部

    /** 取席位并要求有一局在进行；没有时给提示并返回 {@code null}。 */
    private static BuildSeat require(ServerPlayer player) {
        BuildSeat seat = BuildRuntime.seat(player);
        if (seat.session() == null) {
            BuildScreens.toast(player, "没有进行中的构筑，先点 /orb build 开一局", ChatFormatting.YELLOW);
            return null;
        }
        return seat;
    }

    /** 已定稿的组合；还没定稿返回 {@code null}。 */
    private static Composition finished(BuildSeat seat) {
        BuildSession session = seat.session();
        if (session == null || session.phase() != BuildPhase.FINISHED) {
            return null;
        }
        return session.composition().orElse(null);
    }

    private static BehaviorMode effectiveBehavior(BuildSeat seat, Composition comp) {
        if (comp.kind() != BaseKind.SUMMON) {
            // HatchSubmit 要求装备 / 消耗品显式传 NONE
            return BehaviorMode.NONE;
        }
        return seat.behavior() == null ? BehaviorMode.ATTACK : seat.behavior();
    }

    /**
     * 球物品契约是否已就位。
     *
     * <p>这里主动接住 {@code Services} 的 {@code IllegalStateException}，是因为
     * 「S1 还没接上」在联调期是常态，而它必须变成「先别确认」的提示，
     * 不能变成一次已经扣了配额的失败。
     */
    private static boolean orbItemReady() {
        try {
            Services.orbItem();
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }
}
