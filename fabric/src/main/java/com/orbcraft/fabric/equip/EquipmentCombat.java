package com.orbcraft.fabric.equip;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.orbcraft.fabric.OrbcraftMod;
import com.orbcraft.fabric.item.OrbItems;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

/**
 * 装备受击特效 —— 「盾牌造出来却不会格挡」就是缺这一层。
 *
 * <p><b>在链路里的位置</b>：{@link EquipmentDeployer} 把词条写进物品的
 * {@code orbcraft:equipment} 组件，本类在玩家受击时现读现算。两层职责分离：
 * deployer 决定「数值长什么样」，本类决定「数值怎么生效」。
 *
 * <p><b>为什么数值挂在物品上而不是登记在玩家身上。</b> 装备会被捡起、丢出、进箱子、
 * 被别人捡走；登记在玩家身上意味着要在每个容器事件里追踪一件物品的去向，
 * 漏一条就是装备复制/蒸发的 bug。挂在物品上，物品在哪数值就在哪。
 *
 * <p><b>两条钩子的分工</b>（{@code ALLOW_DAMAGE} 只能否决不能改值，签名见 CombatInit 注释）：
 * <ul>
 *   <li>{@code ALLOW_DAMAGE} —— 格挡/招架这种「整段伤害归零」的判定，直接否决；</li>
 *   <li>{@code AFTER_DAMAGE} —— 护盾吸收/抗性减免/荆棘反弹这种「先结算再回补」的，
 *        用 heal 把已扣血还回去（与 CombatStats 暴击回补同源手法）。</li>
 * </ul>
 *
 * <p><b>递归必须挡。</b> 荆棘反弹会再次触发 {@code AFTER_DAMAGE}，不挡就是每帧互相伤害到死。
 * 用 ThreadLocal 标记本次补伤链路。
 */
public final class EquipmentCombat {

    /** 受击特效所需数值（所有装备位累加）。 */
    private record Stats(
            double blockChance,   // 自动格挡概率 %
            double blockPower,    // 举盾格挡时额外减免 %
            double parryChance,   // 自动招架概率 %
            double shieldAmount,  // 护盾吸收池（生命前的一道墙）
            double thorns,        // 荆棘反弹固定值
            double resist         // 全伤固定百分比减免 %
    ) {
        static Stats empty() {
            return new Stats(0, 0, 0, 0, 0, 0);
        }

        Stats merge(Stats o) {
            return new Stats(
                    Math.min(100, blockChance + o.blockChance),
                    blockPower + o.blockPower,
                    Math.min(100, parryChance + o.parryChance),
                    shieldAmount + o.shieldAmount,
                    thorns + o.thorns,
                    Math.min(90, resist + o.resist));
        }
    }

    /** 玩家护盾池：吸收量会随脱战回充。 */
    private static final class Pool {
        float amount;
        long lastDamageTick;

        Pool(float amount) {
            this.amount = amount;
        }
    }

    private static final Map<UUID, Pool> POOLS = new ConcurrentHashMap<>();
    private static final ThreadLocal<Boolean> IN_REFLECT = ThreadLocal.withInitial(() -> false);
    private static boolean initialized;

    private EquipmentCombat() {
    }

    /** 主线在 {@code OrbcraftMod.onInitialize()} 里调这一行。 */
    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        ServerLivingEntityEvents.ALLOW_DAMAGE.register(EquipmentCombat::allowDamage);
        ServerLivingEntityEvents.AFTER_DAMAGE.register(EquipmentCombat::afterDamage);
        ServerTickEvents.END_SERVER_TICK.register(EquipmentCombat::tickPools);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> POOLS.clear());
    }

    private static Stats readStats(LivingEntity entity) {
        if (!(entity instanceof Player)) {
            return Stats.empty();
        }
        Stats total = Stats.empty();
        for (EquipmentSlot slot : new EquipmentSlot[] {
                EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND,
                EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            total = total.merge(fromStack(entity.getItemBySlot(slot)));
        }
        return total;
    }

    private static Stats fromStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return Stats.empty();
        }
        var data = stack.get(OrbItems.EQUIPMENT);
        if (data == null) {
            return Stats.empty();
        }
        var attrs = data.getCompoundOrEmpty("attrs");
        if (attrs.isEmpty()) {
            return Stats.empty();
        }
        return new Stats(
                attrs.getDouble("blockChance").orElse(0.0),
                attrs.getDouble("blockPower").orElse(0.0),
                attrs.getDouble("parryChance").orElse(0.0),
                attrs.getDouble("shieldAmount").orElse(0.0),
                attrs.getDouble("thorns").orElse(0.0),
                attrs.getDouble("resist").orElse(0.0));
    }

    // ----------------------------------------------------------- ALLOW_DAMAGE

    /**
     * @return false = 否决本次伤害（格挡/招架成功）
     */
    private static boolean allowDamage(LivingEntity victim, DamageSource source, float amount) {
        if (!(victim instanceof ServerPlayer player) || amount <= 0f) {
            return true;
        }
        // 格挡/招架只对「有攻击者的伤害」生效：摔落、岩浆、虚空不算
        if (source.getEntity() == null) {
            return true;
        }
        Stats s = readStats(victim);
        if (s.blockChance <= 0 && s.parryChance <= 0) {
            return true;
        }

        double roll = victim.level().getRandom().nextDouble() * 100.0;
        if (roll < s.blockChance) {
            player.playSound(SoundEvents.SHIELD_BLOCK.value(), 1.0f, 1.0f);
            player.sendSystemMessage(
                    net.minecraft.network.chat.Component.literal("§a格挡！"), true);
            OrbcraftMod.LOG.debug("[orbcraft] 装备格挡 · 玩家 {} · 来源 {}", player.getName().getString(),
                    source.getMsgId());
            return false;
        }
        if (roll - s.blockChance < s.parryChance) {
            player.playSound(SoundEvents.SHIELD_BLOCK.value(), 1.0f, 0.8f);
            player.sendSystemMessage(
                    net.minecraft.network.chat.Component.literal("§e招架！"), true);
            if (source.getEntity() instanceof LivingEntity attacker) {
                // 招架成功：攻击者短暂虚弱，给玩家反打窗口
                attacker.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                        net.minecraft.world.effect.MobEffects.WEAKNESS, 40, 0, false, false));
            }
            return false;
        }
        return true;
    }

    // ----------------------------------------------------------- AFTER_DAMAGE

    /**
     * 先结算原版伤害，再用 heal 回补「护盾吸收 / 抗性减免」的部分。
     * 荆棘在这里反弹给攻击者。
     */
    private static void afterDamage(LivingEntity victim, DamageSource source,
                                    float original, float applied, boolean blocked) {
        if (Boolean.TRUE.equals(IN_REFLECT.get()) || applied <= 0f
                || !(victim instanceof ServerPlayer player)) {
            return;
        }
        Stats s = readStats(victim);
        if (s == Stats.empty()) {
            return;
        }

        float remaining = applied;

        // 护盾吸收：优先于生命的池子，脱战回充
        if (s.shieldAmount > 0) {
            Pool pool = POOLS.computeIfAbsent(player.getUUID(), k -> new Pool((float) s.shieldAmount));
            pool.lastDamageTick = player.level().getGameTime();
            if (pool.amount > 0) {
                float absorb = Math.min(pool.amount, remaining);
                pool.amount -= absorb;
                remaining -= absorb;
                player.heal(absorb); // heal 不触发回血动画，但把血还回去
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        "§b护盾吸收 " + (int) Math.ceil(absorb)), true);
            }
        }

        // 抗性减免：固定百分比，套在所有伤害上
        if (s.resist > 0 && remaining > 0) {
            float cut = remaining * (float) (s.resist / 100.0);
            remaining -= cut;
            player.heal(cut);
        }

        // 格挡强度：仅当玩家正举盾（原版右键）且手持本模组装备时，额外减免
        if (s.blockPower > 0 && player.isBlocking() && remaining > 0) {
            float cut = remaining * (float) (s.blockPower / 100.0);
            remaining -= cut;
            player.heal(cut);
        }

        // 荆棘反弹
        if (s.thorns > 0 && source.getEntity() instanceof LivingEntity attacker && attacker != victim) {
            IN_REFLECT.set(true);
            try {
                // 用魔法伤害反弹，不会被护甲二次削减
                DamageSource thornsSource = victim.damageSources().magic();
                attacker.hurtServer((ServerLevel) attacker.level(), thornsSource, (float) s.thorns);
            } finally {
                IN_REFLECT.set(false);
            }
        }
    }

    // ----------------------------------------------------------- 护盾回充

    private static void tickPools(MinecraftServer server) {
        if (POOLS.isEmpty()) {
            return;
        }
        long now = server.overworld().getGameTime();
        for (var it = POOLS.entrySet().iterator(); it.hasNext();) {
            var e = it.next();
            Pool pool = e.getValue();
            ServerPlayer player = server.getPlayerList().getPlayer(e.getKey());
            if (player == null) {
                // 玩家离线：池子留着，下次上线还在；但清掉避免无限增长
                it.remove();
                continue;
            }
            double max = readStats(player).shieldAmount;
            if (max <= 0) {
                it.remove();
                continue;
            }
            // 脱战 5 秒后开始回充，约 4 秒回满
            if (now - pool.lastDamageTick > 100 && pool.amount < max) {
                pool.amount = (float) Math.min(max, pool.amount + max / 80.0);
            }
        }
    }
}
