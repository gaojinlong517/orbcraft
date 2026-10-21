package com.orbcraft.fabric.item;

import java.util.Optional;

import com.orbcraft.core.hatch.HatchTicket;
import com.orbcraft.core.spec.ProductType;
import com.orbcraft.fabric.OrbcraftMod;
import com.orbcraft.fabric.contract.OrbItemContract;
import com.orbcraft.fabric.contract.PlayerStore;
import com.orbcraft.fabric.contract.Services;
import com.orbcraft.fabric.runtime.OrbcraftRuntime;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * {@link OrbItemContract} 的实现 —— 全项目「票据 ↔ 球物品」的唯一读写路径。
 *
 * <p><b>在整条链路里的位置</b>：S2 造完产物调 {@link #createOrb} 拿到球；
 * S5 在投掷事件最早的位置调 {@link #checkThrow} 拒绝「A 的球 B 扔」；
 * S1 自己的 {@code OrbItem} / {@code OrbProjectile} 用 {@link #readOrb} 取回票据。
 *
 * <p><b>NBT 布局不在本类。</b> 票据的编解码一律交给
 * {@link PlayerStore#codec()}（S4）：球与玩家存档是同一张票，两处各写一份布局，
 * 必然出现「球读得出、存档读不出」，而且没有任何报错。
 *
 * <p><b>本类只做三件事之间的翻译</b>：物品组件 ↔ 票据 ↔ 物品栏里的一颗球。
 * 进度、稀有度、时长、配额全部来自 {@code core}，这里一个都不算。
 */
public final class OrbItemContractImpl implements OrbItemContract {

    /**
     * 造球。票据状态原样带上（通常是刚 submit 出来的 CHARGING），不在这里改状态 ——
     * 状态机只有 {@code IncubationService} 能推进。
     *
     * <p>S4 还没接线时这里会抛 {@link IllegalStateException}，并<b>故意不吞</b>：
     * 造球失败是接线问题，不是玩法分支。调用方（S2 的命令）在外面接住并提示玩家即可，
     * 静默返回一个空白球反而更难查。
     */
    @Override
    public ItemStack createOrb(HatchTicket ticket) {
        if (ticket == null) {
            throw new IllegalArgumentException("ticket 不能为空：球的全部内容都来自票据");
        }
        PlayerStore.OrbCodec codec = Services.playerStore().codec();
        CompoundTag tag = codec.write(ticket);
        if (tag == null || tag.isEmpty()) {
            throw new IllegalStateException("票据写不出 NBT：" + ticket.ticketId()
                    + "。检查 PlayerStore.codec() 的实现是否漏了字段");
        }

        ItemStack stack = new ItemStack(OrbItems.ORB);
        stack.set(OrbItems.TICKET, tag);
        OrbDisplay.stamp(stack, ticket);
        // 起点用票据自己的 startTick：那一刻进度必然是 0，不需要向任何时钟要 nowTick。
        OrbDisplay.refresh(stack, ticket, ticket.startTick());
        return stack;
    }

    /**
     * 读球。任何异常都收敛成 {@code Optional.empty()}，绝不抛。
     *
     * <p>物品可以由创造模式、{@code /give}、别的模组甚至手改 NBT 产生，
     * 一个坏物品把服务端 tick 抛崩是这条链路上最差的结果 —— 它会让整个维度停一帧，
     * 而且玩家看不到任何原因。
     */
    @Override
    public Optional<HatchTicket> readOrb(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.is(OrbItems.ORB)) {
            return Optional.empty();
        }
        CompoundTag tag = stack.get(OrbItems.TICKET);
        if (tag == null || tag.isEmpty()) {
            return Optional.empty();
        }

        PlayerStore.OrbCodec codec;
        try {
            codec = Services.playerStore().codec();
        } catch (IllegalStateException e) {
            // S4 未接线。这里每 tick 都会被调用，只报一次，且如实说明是「存档层没接上」，
            // 免得被误读成「球坏了」。
            warnOnceDeclined("PlayerStore 未注册，球的票据读不出来（S4 还没接线）");
            return Optional.empty();
        }

        try {
            return codec.read(tag);
        } catch (RuntimeException e) {
            // codec 的约定是返回 empty；真抛了说明布局对不上（旧球 / 被改过）。
            OrbcraftMod.LOG.warn("[orbcraft] 票据解码失败，按坏物品处理：{}", e.toString());
            return Optional.empty();
        }
    }

    /**
     * 能否投掷 —— 「A 的球 B 扔不了」的唯一判定点。
     *
     * <p>覆盖四件事：是不是球、归属、票据还在不在这一局里、状态允不允许。
     * 顺序是刻意的：先判归属再报状态，否则「别人的球还没充满」这条提示会泄露别人在孵什么。
     *
     * <p>失败原因必须是玩家能直接读的中文短句（它会被直接放进动作栏）。
     */
    @Override
    public ThrowCheck checkThrow(ServerPlayer player, ItemStack stack) {
        if (player == null) {
            return ThrowCheck.deny("没有玩家，扔不出去");
        }
        // 「不是球」和「是球但记录坏了」必须分开说。混成一句会让手里那颗坏球的玩家
        // 以为自己拿错了东西，也分不清是「这本来就不是球」还是「我的球被人动了」。
        if (stack == null || stack.isEmpty() || !stack.is(OrbItems.ORB)) {
            return ThrowCheck.deny("这不是一颗能投出的球");
        }
        Optional<HatchTicket> read = readOrb(stack);
        if (read.isEmpty()) {
            return ThrowCheck.deny("这颗球已经损坏，记录读不出来，用不了了");
        }
        HatchTicket onStack = read.get();

        String ownerKey = PlayerStore.ownerKeyOf(player.getUUID());
        if (!onStack.ownerKey().equals(ownerKey)) {
            return ThrowCheck.deny("这是别人的球");
        }

        // 三种产物都有落地实现：召唤物（spawn 包）、装备（equip 包）、消耗品（consume 包）。
        // 仍然拦在手里的是「未知的新类型」—— 宁可扔不出去，也不让球飞一段后凭空蒸发。
        // 新增 ProductType 时这里必须同步放开，否则就是同样的静默蒸发。
        if (!onStack.spec().isSummon()
                && onStack.spec().type() != ProductType.EQUIPMENT
                && onStack.spec().type() != ProductType.CONSUMABLE) {
            return ThrowCheck.deny("「" + onStack.spec().displayName() + "」是"
                    + onStack.spec().type().zh() + "，这一版还不能落地，先留在手里");
        }

        Optional<HatchTicket> live = OrbcraftRuntime.hatch().ticket(onStack.ticketId());
        if (live.isEmpty()) {
            // 跨存档的旧球、或者票据被服务端丢掉了。查不到时按「不能用」处理，
            // 反过来放行会让一颗没有任何票据的球凭空召出东西。
            return ThrowCheck.deny("这颗球已经不在这一局里了（服务器可能重启过）");
        }
        HatchTicket ticket = live.get();
        if (!ticket.ownerKey().equals(ownerKey)) {
            // 极端情况：票据被换过。按归属拒绝，和上面同一条口径。
            return ThrowCheck.deny("这是别人的球");
        }

        long nowTick = OrbcraftRuntime.nowTick(player.level().getServer());
        return switch (ticket.state()) {
            // 条已满但状态还是 CHARGING：tick 一秒只扫一次，玩家在两次扫描之间右键是常态。
            // throwBall 会就地补迁移，所以这里必须放行，不能报「还差 0 秒」。
            case CHARGING -> ticket.isReady(nowTick)
                    ? ThrowCheck.ok()
                    : ThrowCheck.deny("还差 " + ticket.remainingSeconds(nowTick) + " 秒才充满");
            case READY -> ThrowCheck.ok();
            case THROWN -> ThrowCheck.deny("这颗球已经投出去了");
            case DEPLOYED -> ThrowCheck.deny("这颗球已经用掉了");
            case FAILED -> ThrowCheck.deny("这颗球已经失效了");
        };
    }

    /** 只打一次的告警。静态状态只有「打过没」一位，不持有任何业务数据。 */
    private static volatile boolean warnedNoStore;

    private static void warnOnceDeclined(String message) {
        if (warnedNoStore) {
            return;
        }
        warnedNoStore = true;
        OrbcraftMod.LOG.warn("[orbcraft] {}", message);
    }
}
