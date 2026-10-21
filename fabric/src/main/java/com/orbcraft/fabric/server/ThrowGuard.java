package com.orbcraft.fabric.server;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.orbcraft.fabric.OrbcraftMod;
import com.orbcraft.fabric.contract.OrbItemContract;
import com.orbcraft.fabric.contract.PlayerStore;
import com.orbcraft.fabric.runtime.OrbcraftRuntime;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * 「A 的球 B 扔不了」的拦截点。
 *
 * <p><b>为什么拦在 {@code ItemEvents.USE}。</b> Fabric 的这个事件是从
 * {@code ItemStack.use} 里包住 {@code Item.use} 调用的，也就是说它是
 * <b>服务端上任何一次右键使用物品的最早可介入点</b> —— 早于 S1 的
 * {@code Item.use}、早于实体生成、早于任何状态迁移。放在更靠后的位置
 * （例如投出后的实体生成回调）会留下「票已经是 THROWN，但球没飞出去」的中间态，
 * 而那个状态没有任何回滚路径。
 *
 * <p><b>判定本身不在这里。</b> 归属、票据状态、跨存档旧球这三件事统一由
 * {@code OrbItemContract.checkThrow} 回答。本类只做三件事：问一次、拒绝时
 * 把中文原因送到玩家眼前、然后吃掉这次使用。判定写在两处必然分叉 ——
 * 一处认为能扔、另一处认为不能，于是球能飞出去但服务端不认账。
 *
 * <p><b>1.21.11 的坑（这一条会让 S1 的投掷完全失效）。</b> 这个事件的返回值是
 * {@code InteractionResult}，语义和旧版 {@code TypedActionResult} 不同：
 * <b>返回 {@code null} 才表示「我不管，交给物品自己的 use」</b>；
 * 返回 {@code InteractionResult.PASS} 是<b>非 null</b>，会直接顶掉 {@code Item.use}
 * 的调用并把它当成最终结果 —— 表现为球右键没反应、也不报错。
 * 所以放行路径必须 {@code return null}，只有拒绝才 {@code return FAIL}。
 *
 * <p>客户端也会收到这个事件。客户端的一侧<b>一律放行</b>（{@code null}）：
 * 客户端只负责预测动画，拦截与提示都由服务端做，否则会出现「客户端拦了、服务端也拦」
 * 的双份提示，或者更糟 —— 客户端放行、服务端没拦住。
 *
 * <p><b>排障判据（S7 会用）：看提示出现在哪个通道就能知道是谁拒的。</b>
 * 聊天栏（{@code [宝珠] …}）= 本类拦下的（归属 / 未就绪 / 跨存档旧球）；
 * 动作栏 = 本类放行后由 S1 的 {@code Item.use} 拒的（坏球、{@code throwBall} 抛出、
 * 生成投掷物失败）。两条路径互斥，同一句话不会两处都出现。
 */
final class ThrowGuard {

    /**
     * 同一条拒绝原因的重发间隔（tick）。按住右键时原版每 4 tick 会重发一次使用，
     * 20 tick 阈值把「一秒最多一条中文提示」钉住：既不会让玩家漏看，
     * 也不会让聊天栏被自己的手速刷满。
     */
    private static final long DENY_COOLDOWN_TICKS = 20L;

    /** 上次提示玩家的 tick，按玩家 UUID。断线时清掉，避免 UUID 复用后静默吞掉第一次提示。 */
    private static final Map<UUID, Long> LAST_DENY = new ConcurrentHashMap<>();

    private ThrowGuard() {
    }

    /**
     * {@code ItemEvents.USE} 的回调。
     *
     * @return {@code null} = 放行给物品自己的 {@code use}；{@code FAIL} = 吃掉这次使用
     */
    static InteractionResult onUse(Level level, Player player, InteractionHand hand) {
        // 客户端只做预测：真正的裁决在服务端，客户端拦截会造成「本地不显示动作、服务端却执行了」。
        if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
            return null;
        }
        return guard(serverPlayer, serverPlayer.getItemInHand(hand));
    }

    /**
     * {@code ItemEvents.USE_ON} 的回调 —— 对着<b>方块</b>右键时走的是这条路径。
     *
     * <p>不挂这一条会留下一个绕过口：玩家瞄着地面/墙右键时原版先走 {@code useOn}，
     * 我们把拦截只放在 {@code USE} 上，B 就能通过「对着方块扔」跳过归属判定。
     * 两条路径共用同一个 {@link #guard}，判定规则不会分叉。
     */
    static InteractionResult onUseOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
            return null;
        }
        return guard(serverPlayer, context.getItemInHand());
    }

    /**
     * 两条右键路径的共同判定。
     *
     * @return {@code null} = 放行；{@code FAIL} = 拒绝
     */
    private static InteractionResult guard(ServerPlayer serverPlayer, ItemStack stack) {
        OrbItemContract orbItem = ServiceLookup.orbItem();
        if (orbItem == null) {
            return null;
        }
        if (stack.isEmpty()) {
            return null;
        }

        try {
            // isOrb 是最常见的分支（空手/方块/别的物品全都走这里），必须先判它再谈归属。
            if (!orbItem.isOrb(stack)) {
                return null;
            }

            OrbItemContract.ThrowCheck check = orbItem.checkThrow(serverPlayer, stack);
            if (check.allowed()) {
                // 允许：什么都不做，交给 S1 的 Item.use 去做真正的投掷（状态迁移在服务端完成）。
                return null;
            }
            deny(serverPlayer, check.reason());
            return InteractionResult.FAIL;
        } catch (RuntimeException e) {
            // 一个坏物品不该让玩家的交互整体失效，更不该把异常抛回原版的使用链路。
            OrbcraftMod.LOG.error("[orbcraft] 投掷校验异常，本次使用放行（{}）：{}",
                    serverPlayer.getName().getString(), e.toString(), e);
            return null;
        }
    }

    /**
     * 拒绝：中文原因给玩家 + 一行日志给运维。
     *
     * <p>日志必须独立于聊天提示存在：玩家只会说「扔不出去」，而日志里的
     * {@code ownerKey} 才能回答「这是谁的球、他是不是换过号」。
     */
    private static void deny(ServerPlayer player, String reason) {
        long now = OrbcraftRuntime.nowTick(player.level().getServer());
        Long last = LAST_DENY.get(player.getUUID());
        if (last != null && now - last < DENY_COOLDOWN_TICKS && now >= last) {
            return;
        }
        LAST_DENY.put(player.getUUID(), now);

        String text = reason == null || reason.isBlank() ? "现在不能投掷" : reason;
        player.sendSystemMessage(Component.literal("[宝珠] " + text));
        OrbcraftMod.LOG.info("[orbcraft] 拒绝投掷 · player={} · ownerKey={} · 原因={}",
                player.getName().getString(),
                PlayerStore.ownerKeyOf(player.getUUID()),
                text);
    }

    /**
     * 断线：只清缓存，<b>不碰票据</b>。
     *
     * <p>「掉线时在孵的球不复位」的实现方式就是「什么都不做」——
     * 票据活在 {@code OrbcraftRuntime.hatch()} 里，与玩家连接无关；
     * 提前结算或重置只会让重连后的进度跳变。这里打一行日志，
     * 是为了让验收时能从服务端日志证明「掉线前后在途数量一致」。
     */
    static void onDisconnect(ServerGamePacketListenerImpl handler, MinecraftServer server) {
        ServerPlayer player = handler.player;
        if (player == null) {
            return;
        }
        LAST_DENY.remove(player.getUUID());

        String ownerKey = PlayerStore.ownerKeyOf(player.getUUID());
        int inflight = HatchRuntime.inFlight(ownerKey).size();
        OrbcraftMod.LOG.info("[orbcraft] 离线 · player={} · 在途票据 {} 张保持原状态（不重置、不重播）",
                player.getName().getString(), inflight);
    }
}
