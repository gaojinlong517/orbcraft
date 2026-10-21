package com.orbcraft.fabric.item;

import java.util.Optional;

import com.orbcraft.core.hatch.HatchException;
import com.orbcraft.core.hatch.HatchTicket;
import com.orbcraft.fabric.contract.OrbItemContract;
import com.orbcraft.fabric.contract.PlayerStore;
import com.orbcraft.fabric.entity.orb.OrbEntities;
import com.orbcraft.fabric.entity.orb.OrbProjectile;
import com.orbcraft.fabric.runtime.OrbcraftRuntime;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * 球物品 —— 玩家手里那个「读条 → 右键扔出去」的东西。
 *
 * <p><b>在整条链路里的位置</b>：S2 用 {@code OrbItemContract.createOrb} 造出它放进背包，
 * 本类负责两件事：每秒把票据的进度画到物品上（{@link #inventoryTick}），
 * 以及右键时做状态迁移 + 生成投掷物（{@link #use}）。落地的效果在
 * {@link OrbProjectile} 里，本类不碰召唤。
 *
 * <p><b>为什么判定全在服务端。</b> 客户端也跑同一份 {@code use}，但它只播挥手动画：
 * 客户端的物品栏、票据状态都是服务端同步下来的副本，在客户端推进状态会出现
 * 「客户端球没了、服务端还在」的分叉。{@code IncubationService} 也只有服务端那一个实例。
 */
public final class OrbItem extends Item {

    /** 刷新间隔（tick）。见 {@link #inventoryTick} 里关于带宽的说明。 */
    private static final long TICKS_PER_REFRESH = 20L;

    public OrbItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide()) {
            // 只播挥手；真正的判定在服务端。这里必须返回 SUCCESS 而不是 PASS ——
            // 返回 PASS 时客户端不会挥手臂，手感上像右键没反应。
            return InteractionResult.SUCCESS;
        }
        if (player instanceof ServerPlayer serverPlayer) {
            throwOrb(serverPlayer, player.getItemInHand(hand));
        }
        return InteractionResult.CONSUME;
    }

    /**
     * 投掷的完整流程：判定 → 生成投掷物 → 推进票据 → 扣除物品。
     *
     * <p><b>失败一律用中文短句回到动作栏</b>，不抛异常：这里在右键回调里，
     * 抛出去会打断整个 tick，玩家看到的是「服务器卡一下然后这个操作消失了」。
     */
    private static void throwOrb(ServerPlayer player, ItemStack stack) {
        OrbItemContract.ThrowCheck check = OrbItems.CONTRACT.checkThrow(player, stack);
        if (!check.allowed()) {
            player.displayClientMessage(Component.literal(check.reason()), true);
            return;
        }
        Optional<HatchTicket> read = OrbItems.CONTRACT.readOrb(stack);
        if (read.isEmpty()) {
            // checkThrow 已经保证读得出来，走到这里是契约实现内部不一致，如实告知而不是静默返回。
            player.displayClientMessage(Component.literal("这颗球的记录坏了，投不出去"), true);
            return;
        }
        HatchTicket ticket = read.get();
        ServerLevel level = player.level();
        long nowTick = OrbcraftRuntime.nowTick(level.getServer());

        OrbProjectile ball = new OrbProjectile(
                OrbEntities.PROJECTILE, player.getX(), player.getEyeY() - 0.1D, player.getZ(), level,
                // 让飞行中的球携带球物品本身：它同时是渲染素材与票据的落盘载体（重启后仍能取回）。
                stack.copy());
        ball.setOwner(player);
        ball.bind(ticket.ticketId(), PlayerStore.ownerKeyOf(player.getUUID()));
        ball.shootFromRotation(player, player.getXRot(), player.getYRot(), 0.0F, 1.2F, 0.5F);

        // 顺序是刻意的：先让实体进世界，再迁移票据。
        // 反过来先 throwBall 的话，一旦 addFreshEntity 失败（区块未加载等），票据已经 THROWN
        // 而世界里没有球，这个中间态没有任何 API 能回滚，玩家的产物就凭空没了。
        if (!level.addFreshEntity(ball)) {
            player.displayClientMessage(Component.literal("球没能出手，再试一次"), true);
            return;
        }

        try {
            OrbcraftRuntime.hatch().throwBall(ticket.ticketId(), nowTick);
        } catch (HatchException e) {
            ball.discard();
            player.displayClientMessage(Component.literal(throwReason(e, ticket, nowTick)), true);
            return;
        }

        // 无条件扣，创造模式也扣。原版投掷物在创造下不消耗，但那是因为它们没有状态；
        // 球的票据已经 THROWN，留在背包里就是一颗「看得见、扔不动」的死球，
        // 玩家与联调的人都会把它当成 bug。要再扔一颗就让 S2 再造一个。
        stack.shrink(1);
    }

    /**
     * 把 {@link HatchException.Code} 翻成玩家读得懂的一句话。
     *
     * <p>不直接打 {@code getMessage()}：那是给日志看的，里面带状态码和票据 id
     * （例如「还没充满（CHARGING），当前不能投掷：tk-1」），进聊天栏像报错。
     * 剩下几档在投掷路径上不可达，但 switch 必须穷尽，写实了也不会误导读日志的人。
     */
    private static String throwReason(HatchException e, HatchTicket ticket, long nowTick) {
        return switch (e.code()) {
            case NOT_READY -> "还差 " + ticket.remainingSeconds(nowTick) + " 秒才充满，先等等";
            case UNKNOWN_TICKET -> "这颗球已经不在这一局里了（服务器可能重启过）";
            case ALREADY_FINISHED -> "这颗球已经用掉了";
            case BAR_FULL -> "孵化栏满了，先把别的球投出去";
            case QUOTA_REJECTED -> "配额不足，先解契";
        };
    }

    /**
     * 每秒刷新一次进度条与 lore。
     *
     * <p><b>为什么用实时票据而不是球上那份。</b> 球上的 NBT 是造球那一刻的快照，
     * 状态由 {@code IncubationService} 推进（S5 的 tick 点每秒扫一次）。
     * 只读快照的话，球会永远显示 CHARGING，充满了也不提示 —— 而玩家恰恰要靠这一行决定能不能扔。
     * 服务里查不到（跨存档的旧球）时才退回快照。
     *
     * <p>只在服务端跑：{@code inventoryTick} 的这个重载签名带 {@code ServerLevel}，
     * 客户端本来就不会进来，进度靠组件同步过去。
     */
    @Override
    public void inventoryTick(ItemStack stack, ServerLevel level, Entity entity, EquipmentSlot slot) {
        long nowTick = OrbcraftRuntime.nowTick(level.getServer());
        // 一秒刷一次就够了。耐久条有 1000 级、进度每 tick 都在变，但每一次组件变化都要把
        // 整份物品重新发给客户端（票据里是完整的 Spec，带近百个属性键）。
        // 每 tick 刷等于给每个在读条的球白送几十 KB/s 的带宽，而玩家的眼睛只分得清秒。
        if (nowTick % TICKS_PER_REFRESH != 0L) {
            return;
        }
        Optional<HatchTicket> read = OrbItems.CONTRACT.readOrb(stack);
        if (read.isEmpty()) {
            return;
        }
        HatchTicket onStack = read.get();
        HatchTicket live = OrbcraftRuntime.hatch().ticket(onStack.ticketId()).orElse(onStack);
        OrbDisplay.refresh(stack, live, nowTick);
    }
}
