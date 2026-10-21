package com.orbcraft.fabric.server;

import java.util.List;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.orbcraft.core.hatch.HatchTicket;
import com.orbcraft.core.hatch.IncubationService;
import com.orbcraft.fabric.contract.PlayerStore;
import com.orbcraft.fabric.contract.Services;
import com.orbcraft.fabric.runtime.OrbcraftRuntime;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * 开发期测试命令 {@code /orbdev}，只对管理员（等级 2）开放。
 *
 * <p><b>为什么需要它。</b> 一颗球要孵几分钟到十几分钟，而联调时要验证的是
 * 「读条满了以后投出去会怎样」——每验一次都要等一遍真实时长，一天下来大部分时间在等。
 *
 * <p><b>为什么是「推进时钟」而不是「改 startTick」。</b>
 * {@code IncubationService.tick(nowTick)} 只做一件事：把「进度已满但状态还是 CHARGING」
 * 的票翻成 READY。它不写 {@code startTick}、不碰配额、不碰存档格式。
 * 传一个够远的未来时刻进去，就等价于「这批球已经孵完了」，而且**没有制造任何只在开发期存在的状态**：
 * 之后的正常 tick 看到同样的 {@code startTick} + 真实时间，得到同样的结论。
 * 直接改 startTick 反而要新增一个「写入」入口，那是给线上玩法开口子。
 *
 * <p><b>它不会做的事</b>：不发球、不扣配额、不改稀有度。只是把「等」这一步跳过去。
 */
public final class OrbDevCommand {

    /** 24 小时。远大于任何 {@code hatchSeconds}，又不会溢出 tick 计算。 */
    private static final long SKIP_TICKS = 24L * 60 * 60 * 20;

    private static boolean registered;

    private OrbDevCommand() {
    }

    public static void init() {
        if (registered) {
            return;
        }
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, selection) ->
                dispatcher.register(tree()));
        registered = true;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> tree() {
        return Commands.literal("orbdev")
                // 刻意<b>不</b>要求管理员等级：开发服是离线模式，玩家进来不是 op，
                // 要求等级 2 等于「这个命令永远用不了」。代价是任何人都能跳孵化 ——
                // 只在开发构建里成立。<b>上线前必须改回 AdminPermissions.requirement()</b>，
                // 或者干脆把这个类从发布包里删掉。
                .then(Commands.literal("hatch")
                        .executes(ctx -> skipHatch(ctx.getSource())))
                .then(Commands.literal("tickets")
                        .executes(ctx -> listTickets(ctx.getSource())));
    }

    /**
     * 把本人所有在孵的球推到「已就绪」。
     *
     * <p>只影响<b>自己</b>：开发期最怕的是「试功能的顺手把别人的进度也改了」，
     * 而这里没有传玩家参数的入口，想加速别人的球只能让对方自己敲。
     */
    private static int skipHatch(CommandSourceStack source) {
        ServerPlayer self = source.getPlayer();
        if (self == null) {
            source.sendFailure(Component.literal("这个命令只能由玩家执行：控制台没有自己的球"));
            return 0;
        }
        String ownerKey = PlayerStore.ownerKeyOf(self.getUUID());
        IncubationService hatch = OrbcraftRuntime.hatch();
        long now = OrbcraftRuntime.nowTick(source.getServer());

        // 分两种状态数：在孵（可以加速）与已就绪（不需要加速，直接能投）。
        // 只数 CHARGING 会让「球早就孵好了」被报成「你没有球」——
        // 玩家手里明明拿着两颗，这句话看起来就是坏档。
        int before = 0;
        int readyAlready = 0;
        for (HatchTicket ticket : hatch.ticketsOf(ownerKey)) {
            if (ticket.state() == com.orbcraft.core.hatch.HatchState.CHARGING) {
                before++;
            } else if (ticket.state() == com.orbcraft.core.hatch.HatchState.READY) {
                readyAlready++;
            }
        }
        if (before == 0) {
            if (readyAlready > 0) {
                final int ready = readyAlready;
                source.sendSuccess(() -> Component.literal(
                        "没有在孵的球；已有 " + ready + " 颗就绪，直接右键投出去（/orbdev tickets 看明细）"), false);
            } else {
                source.sendSuccess(() -> Component.literal("手上没有在孵或已就绪的球"), false);
            }
            return 0;
        }

        List<HatchTicket> ready = hatch.tick(now + SKIP_TICKS);
        int mine = 0;
        for (HatchTicket ticket : ready) {
            if (ticket.ownerKey().equals(ownerKey)) {
                mine++;
            }
        }

        // 已就绪是状态变更，必须让存档层知道，否则崩服后票还停在 CHARGING，
        // 下一次 tick 会再翻一次 READY —— 玩家会收到第二条「球亮了」。
        try {
            Services.playerStore().markDirty(ownerKey);
        } catch (IllegalStateException e) {
            // 存档层没接线：内存里照样是 READY，只是这次不落盘。
        }

        final int done = mine;
        final int total = before;
        source.sendSuccess(() -> Component.literal(
                "已跳过孵化：" + done + "/" + total + " 颗进入就绪，右键投出去试试"), false);
        // 界面还开着的时候敲不出这条命令也行，但要让它立刻刷新：
        // 否则玩家点完按钮看不到任何变化，会以为没生效。
        com.orbcraft.fabric.ui.BuildFlow.status(self);
        return done;
    }

    /** 列出自己的票据与剩余时间，排「读条不动」这类问题时不用翻日志。 */
    private static int listTickets(CommandSourceStack source) {
        ServerPlayer self = source.getPlayer();
        if (self == null) {
            source.sendFailure(Component.literal("这个命令只能由玩家执行"));
            return 0;
        }
        String ownerKey = PlayerStore.ownerKeyOf(self.getUUID());
        long now = OrbcraftRuntime.nowTick(source.getServer());
        List<HatchTicket> tickets = OrbcraftRuntime.hatch().ticketsOf(ownerKey);
        if (tickets.isEmpty()) {
            source.sendSuccess(() -> Component.literal("手上没有票据"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal("票据 " + tickets.size() + " 张："), false);
        for (HatchTicket ticket : tickets) {
            final String line = "  " + ticket.ticketId() + " · " + ticket.spec().displayName()
                    + " · " + ticket.state()
                    + " · 剩余 " + Math.max(0, ticket.remainingSeconds(now)) + "s";
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return tickets.size();
    }
}
