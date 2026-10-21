package com.orbcraft.fabric.server;

import java.util.List;
import java.util.Optional;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.orbcraft.core.hatch.HatchSnapshot;
import com.orbcraft.core.hatch.HatchState;
import com.orbcraft.core.hatch.HatchTicket;
import com.orbcraft.core.hatch.IncubationService;
import com.orbcraft.core.quota.QuotaManager;
import com.orbcraft.fabric.OrbcraftMod;
import com.orbcraft.fabric.contract.OrbItemContract;
import com.orbcraft.fabric.contract.PlayerStore;
import com.orbcraft.fabric.runtime.OrbcraftRuntime;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * 管理类命令 —— 直接发球、看/重算配额。
 *
 * <p>为什么这些命令不放在 S2 的构筑命令里：S2 的入口是玩法（四层确认制、发牌、确认后才扣配额），
 * 这些是<b>运维手段</b>（赔付、查账、清理脏配额）。两者权限等级不同、失败原因不同、
 * 面向的人也不同，混在一个根节点下会让「玩家敲错命令看到管理员提示」。
 * 所以这里用独立根 {@code /orbadmin}，与 S2 的 {@code /orbcraft} 互不覆盖 ——
 * 命令注册在启动期是独占的，两个包抢同一个根会在 {@code CommandDispatcher} 里报重复字面量。
 *
 * <p>三条命令都按<b>权限等级</b>放行（见 {@link AdminPermissions}），不做玩家名白名单。
 *
 * <p>这里有一个刻意的限制：<b>本类不制造票据</b>。发球只发「已经存在的票据」的球。
 * 凭空造球等于绕开「定稿才扣额度」，会让配额账本与物品栏脱钩 ——
 * 那正是 {@code HatchSnapshot} 那一节反复警告的「存档写过一半」状态。
 * 需要赔一张新球时，先让玩家正常走一遍构筑，或者由主线补一条正式的授权入口。
 */
final class AdminCommands {

    private AdminCommands() {
    }

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("orbadmin")
                .requires(AdminPermissions.requirement())
                .then(Commands.literal("ownerkey")
                        .executes(AdminCommands::ownerKey))
                .then(Commands.literal("orb")
                        .then(Commands.argument("target", EntityArgument.player())
                                .then(Commands.argument("ticket", StringArgumentType.greedyString())
                                        .executes(AdminCommands::giveOrb))))
                .then(Commands.literal("quota")
                        .then(Commands.argument("target", EntityArgument.player())
                                .executes(AdminCommands::showQuota)
                                .then(Commands.literal("recompute")
                                        .executes(AdminCommands::recomputeQuota)))));
    }

    /** 让管理员亲眼看到账本的键长什么样；验收「A 的球 B 扔不了」时靠它对日志。 */
    private static int ownerKey(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer self = source.getPlayer();
        if (self == null) {
            source.sendFailure(Component.literal("这个命令需要玩家执行：控制台没有 UUID"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("你的 ownerKey：" + PlayerStore.ownerKeyOf(self.getUUID())), false);
        return 1;
    }

    /**
     * 把某张已有票据的球直接放进目标背包。
     *
     * <p>用 {@code placeItemBackInInventory} 而不是 {@code add}：背包满时它会自己掉落，
     * 不会出现「提示发送成功但东西凭空消失」。
     */
    private static int giveOrb(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (!AdminPermissions.isAdmin(source)) {
            source.sendFailure(Component.literal(AdminPermissions.DENY_MESSAGE));
            return 0;
        }

        ServerPlayer target;
        String ticketId;
        try {
            target = EntityArgument.getPlayer(ctx, "target");
            ticketId = StringArgumentType.getString(ctx, "ticket");
        } catch (CommandSyntaxException e) {
            source.sendFailure(Component.literal("找不到目标玩家"));
            return 0;
        }

        Optional<HatchTicket> found = OrbcraftRuntime.hatch().ticket(ticketId);
        if (found.isEmpty()) {
            source.sendFailure(Component.literal("查不到票据「" + ticketId + "」，先用 /orbadmin quota 确认在途票据"));
            return 0;
        }
        HatchTicket ticket = found.get();
        if (ticket.state() == HatchState.DEPLOYED || ticket.state() == HatchState.FAILED) {
            source.sendFailure(Component.literal("这张票据已经结束了（" + stateZh(ticket.state()) + "），不能再发球"));
            return 0;
        }

        OrbItemContract orbItem = ServiceLookup.orbItem();
        if (orbItem == null) {
            source.sendFailure(Component.literal("球物品还没接线（S1 orb-item 未完成），现在发不出球"));
            return 0;
        }

        ItemStack stack;
        try {
            stack = orbItem.createOrb(ticket);
        } catch (RuntimeException e) {
            OrbcraftMod.LOG.error("[orbcraft] 造球失败 · ticket={}：{}", ticketId, e.toString(), e);
            source.sendFailure(Component.literal("造球失败，服务端日志里有原因"));
            return 0;
        }
        if (stack.isEmpty()) {
            source.sendFailure(Component.literal("造出来的球是空的，检查票据的 Spec 是否完整"));
            return 0;
        }

        target.getInventory().placeItemBackInInventory(stack);
        String specName = ticket.spec().displayName();
        String targetName = target.getName().getString();
        source.sendSuccess(() -> Component.literal("已把「" + specName + "」的球发给 " + targetName), true);
        OrbcraftMod.LOG.info("[orbcraft] 管理发球 · by={} · to={} · ticket={} · spec={}",
                source.getTextName(), targetName, ticketId, ticket.specId());
        return 1;
    }

    /** 只读：配额占用 + 在途票据。做验收「两人配额互不影响」时，两个人各敲一次就够。 */
    private static int showQuota(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer target;
        try {
            target = EntityArgument.getPlayer(ctx, "target");
        } catch (CommandSyntaxException e) {
            source.sendFailure(Component.literal("找不到目标玩家"));
            return 0;
        }

        String ownerKey = PlayerStore.ownerKeyOf(target.getUUID());
        QuotaManager.State state = OrbcraftRuntime.hatch().stateOf(ownerKey);
        int inflight = HatchRuntime.inFlight(ownerKey).size();
        String name = target.getName().getString();

        source.sendSuccess(() -> Component.literal(name + " · 装备 " + state.equipment() + "/" + QuotaManager.MAX_EQUIPMENT
                + " · 主宠 " + state.primary() + "/" + QuotaManager.MAX_PRIMARY
                + " · 混乱 " + state.chaos() + "/" + QuotaManager.MAX_CHAOS
                + " · 统御力 " + state.usedPoints() + "/" + QuotaManager.POOL_SIZE
                + " · 在途 " + inflight + "/" + OrbcraftRuntime.hatch().maxConcurrent()), false);
        source.sendSuccess(() -> Component.literal("ownerKey：" + ownerKey), false);
        return 1;
    }

    /**
     * 按在途票据重算配额 —— 释放「已落地产物仍占着的位子」。
     *
     * <p>为什么走 {@code IncubationService.restore} 而不是自己改配额：
     * {@code QuotaManager} 只有 {@code tryConsume}，没有 release/refund，
     * 平台层没有任何「合法的减配额」入口。{@code restore} 恰好提供了唯一一条：
     * 声明一个空的存盘配额，它发现「存盘值 < 在途票据所需」就会按票据重算并置
     * {@code quotaRepaired}。这里 {@code quotaRepaired=true} 是<b>预期</b>而不是损坏信号 ——
     * 那个标志在 S4 的加载路径里表示「存档写过一半」，在本命令里表示「管理员要求降配额」，
     * 所以日志里要写明是管理操作发出的，避免以后被误读成存档故障。
     */
    private static int recomputeQuota(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (!AdminPermissions.isAdmin(source)) {
            source.sendFailure(Component.literal(AdminPermissions.DENY_MESSAGE));
            return 0;
        }

        ServerPlayer target;
        try {
            target = EntityArgument.getPlayer(ctx, "target");
        } catch (CommandSyntaxException e) {
            source.sendFailure(Component.literal("找不到目标玩家"));
            return 0;
        }

        String ownerKey = PlayerStore.ownerKeyOf(target.getUUID());
        String name = target.getName().getString();
        QuotaManager.State before = OrbcraftRuntime.hatch().stateOf(ownerKey);

        IncubationService.RestoreResult result;
        try {
            // 传空票据列表：restore 只改配额，不动已在服务里的票据（同一 id 重复写入会抛异常，
            // 所以这里刻意不把当前票据再喂回去）。
            result = OrbcraftRuntime.hatch().restore(
                    new HatchSnapshot(ownerKey, QuotaManager.State.empty(), List.of()));
        } catch (RuntimeException e) {
            OrbcraftMod.LOG.error("[orbcraft] 重算配额失败 · {}：{}", ownerKey, e.toString(), e);
            source.sendFailure(Component.literal("重算失败，服务端日志里有原因"));
            return 0;
        }

        QuotaManager.State after = result.quota();
        source.sendSuccess(() -> Component.literal("已按在途票据重算 " + name + " 的配额：装备 " + after.equipment()
                + " · 主宠 " + after.primary()
                + " · 混乱 " + after.chaos()
                + " · 统御力 " + after.usedPoints()
                + "（已落地产物占用的位置被一并释放）"), true);
        OrbcraftMod.LOG.info("[orbcraft] 管理重算配额 · by={} · owner={} · {} -> {} · repaired={}",
                source.getTextName(), ownerKey, describe(before), describe(after), result.quotaRepaired());
        return 1;
    }

    private static String describe(QuotaManager.State s) {
        return "装备" + s.equipment() + "/主宠" + s.primary() + "/混乱" + s.chaos() + "/统御力" + s.usedPoints();
    }

    private static String stateZh(HatchState state) {
        return switch (state) {
            case CHARGING -> "充能中";
            case READY -> "已就绪";
            case THROWN -> "已投出";
            case DEPLOYED -> "已落地";
            case FAILED -> "已失效";
        };
    }
}
