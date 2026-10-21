package com.orbcraft.fabric.spawn;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.orbcraft.core.render.CarrierModelPlan;
import com.orbcraft.core.render.ModelCapability;
import com.orbcraft.core.spec.BehaviorMode;
import com.orbcraft.core.spec.Spec;
import com.orbcraft.fabric.OrbcraftMod;
import com.orbcraft.fabric.contract.Services;
import com.orbcraft.fabric.contract.SummonSpawner;
import com.orbcraft.fabric.mapping.SpecTags;
import com.orbcraft.fabric.mapping.SummonAttrMap;
import com.orbcraft.fabric.mapping.SummonStats;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

/**
 * {@link SummonSpawner} 的实现 —— 「一个 Spec，变成世界里一个会打人的东西」。
 *
 * <p><b>在链路里的位置</b>：S1 在投掷物命中点调用 {@link #spawn}；
 * S2 用 {@link #capability} 决定提示语；S5 用 {@link #ownedBy} 判友伤与指挥权。
 * 本类是链路里唯一把 Spec 的三部分（数值 / 行为 / 外观）落到实体上的地方：
 * <ul>
 *   <li>数值：{@code SummonAttrMap} → {@code SummonStats}</li>
 *   <li>行为：{@code SummonBehaviorBinder}</li>
 *   <li>外观：{@code SummonAppearance}（只转述能力等级，不判断模型能不能加载）</li>
 * </ul>
 *
 * <p><b>为什么宿主实体是原版代理实体，而不是自注册的 EntityType。</b>
 * 自注册实体类型要多一条客户端渲染器注册，少一处就是客户端崩溃；
 * 而 {@code CarrierModelPlan} 本来就规定了 {@code proxyEntity}（原版实体 id）作为退化目标。
 * 首版外观后端只有兜底实现（不挂任何模型），此时如果宿主隐形，玩家<em>什么都看不到</em>——
 * 那比「长得像僵尸」糟得多。所以：玩法宿主 = 可见的原版代理实体，
 * 外观后端就位后可以把宿主变隐形、把模型挂上去，玩法侧一行不用改。
 * 这也解释了为什么 {@code treant}（{@code asset=NEW}，模型没烘进资源包）
 * 会直接以 {@code minecraft:warden} 的样子出现，并收到降级提示。
 *
 * <p><b>外观降级的提示不在这里发。</b> {@link SpawnResult#appearance()} 的用途就是
 * 「让调用方决定要不要提示」，S1 的落地路径已经按它发过中文提示；S3 再发一条，
 * 玩家就会收到两条一模一样的消息 —— 同一次召唤的提示只能有一个发送方。
 * 本类的责任是<b>如实报等级</b>：模型没挂上时报 {@link ModelCapability#NAMED_VANILLA}，
 * 而不是转述后端自报的 FULL。
 *
 * <p><b>本类不抛异常。</b> 它跑在投掷物命中的 tick 里，异常会打断整个 tick，
 * 玩家的观感是「服务器卡一下，然后这颗球消失了」。所有失败走
 * {@link SpawnResult#failed} 与中文原因。
 */
public final class VanillaSummonSpawner implements SummonSpawner {

    /** 单例：{@code Services.install} 对同一实例重复安装是允许的（双端各初始化一次）。 */
    private static final VanillaSummonSpawner INSTANCE = new VanillaSummonSpawner();

    /**
     * 代理实体不是生物 / 不是 Mob 时的替代品。
     *
     * <p>会真的走到这里：{@code totem} 的代理是 {@code armor_stand}（是生物但不会打架），
     * {@code crystal} 的代理是 {@code end_crystal}（根本不是生物）。两者的载体层
     * 都是召唤产物合法的选择，所以不能直接拒绝召唤，只能换一个能打的壳。
     */
    private static final String COMBAT_FALLBACK_PROXY = "minecraft:zombie";

    /** 未知属性键只告警一次：同一个键每召一次就刷一行，日志会被淹没到没人看。 */
    private static final Set<String> WARNED_UNKNOWN_KEYS = ConcurrentHashMap.newKeySet();

    private static volatile SummonAppearance appearance;
    private static volatile MinecraftServer currentServer;
    private static volatile SummonLedger currentLedger;

    private VanillaSummonSpawner() {
    }

    /**
     * 初始化：注册契约实现与服务器生命周期事件。
     *
     * <p>主线只需要在 {@code OrbcraftMod.onInitialize()} 里加一行
     * {@code VanillaSummonSpawner.init();}。这里注册事件而不是让主线注册，
     * 是因为事件与 {@code ownedBy} 的账本缓存是同一份状态 —— 拆到两处，
     * 就会出现「事件清空了缓存但实现还拿着旧服务器」的时序 bug。
     */
    public static void init() {
        Services.install(INSTANCE);
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            currentServer = server;
            currentLedger = null;
            // 提前加载：把「载体模型数据坏了」这件事暴露在启动日志里，
            // 而不是等玩家投出第一颗球时才发现外观全降级
            appearance();
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            currentServer = null;
            currentLedger = null;
        });
        OrbcraftMod.LOG.info(
                "[orbcraft] 召唤落地器就位：属性已接 {} 键 / 已知未接 {} 键 / 效果族 {} 个 · 外观能力 {}",
                SummonAttrMap.wiredKeyCount(), SummonAttrMap.knownUnwiredKeyCount(),
                SummonAttrMap.effectFamilyCount(), INSTANCE.capability());
    }

    private static SummonAppearance appearance() {
        SummonAppearance local = appearance;
        if (local == null) {
            synchronized (VanillaSummonSpawner.class) {
                local = appearance;
                if (local == null) {
                    local = SummonAppearance.load();
                    appearance = local;
                }
            }
        }
        return local;
    }

    private static SummonLedger ledger() {
        MinecraftServer server = currentServer;
        if (server == null) {
            return SummonLedger.forServer(null);
        }
        SummonLedger local = currentLedger;
        if (local == null) {
            local = SummonLedger.forServer(server);
            currentLedger = local;
        }
        return local;
    }

    // ------------------------------------------------------------------ spawn

    @Override
    public SpawnResult spawn(ServerLevel level, Vec3 pos, Spec spec, String ownerKey) {
        SummonAppearance look = appearance();
        ModelCapability backendCap = look.capability();

        if (level == null) {
            return SpawnResult.failed("世界还没加载好，召唤失败", backendCap);
        }
        if (spec == null) {
            return SpawnResult.failed("召唤规格是空的，没有东西可以召唤", backendCap);
        }
        if (pos == null) {
            return SpawnResult.failed("没有落点，召唤失败", backendCap);
        }
        if (ownerKey == null || ownerKey.isBlank()) {
            return SpawnResult.failed("查不到召唤者，无法确定这只召唤物归谁", backendCap);
        }
        if (!spec.isSummon()) {
            // 装备/消耗品走到这里说明上游状态机漏了判断，这里兜住但不抛异常
            return SpawnResult.failed("「" + spec.displayName() + "」是" + spec.type().zh()
                    + "，不能当召唤物落地", backendCap);
        }
        if (spec.behavior() == BehaviorMode.NONE) {
            return SpawnResult.failed("「" + spec.displayName() + "」没有行为模式（装备或消耗品），"
                    + "不能召唤成生物", backendCap);
        }

        SummonLedger ledger = SummonLedger.of(level);
        currentLedger = ledger;

        // ---- 幂等：同一个球被「实体命中」和「区块补偿」两条路径触发时只出一只
        String previous = ledger.entityOfSpec(spec.id());
        if (previous != null) {
            Entity existing = liveEntity(level, previous);
            if (existing != null && !existing.isRemoved()) {
                OrbcraftMod.LOG.info("[orbcraft] 召唤 {} 已存在（实体 {}），忽略重复落地", spec.id(), previous);
                return SpawnResult.failed("这颗球已经召出过了，不会再召一只", backendCap);
            }
            // 记录还在但实体已经没了：上一只死了或被清理，允许重新召一只
            OrbcraftMod.LOG.info("[orbcraft] 召唤 {} 的旧实体 {} 已不存在，按新召唤处理", spec.id(), previous);
        }

        // ---- 外观计划 + 宿主实体
        SummonAppearance.Plan plan = look.planFor(spec.tags());
        if (!plan.registered()) {
            OrbcraftMod.LOG.warn("[orbcraft] Spec {} 的 tags {} 里没有已登记的载体，外观走占位兜底",
                    spec.id(), spec.tags());
        }
        CarrierModelPlan modelPlan = plan.plan();

        Entity entity = createEntity(level, modelPlan.proxyEntity());
        boolean proxySubstituted = false;
        if (!(entity instanceof Mob)) {
            if (entity != null) {
                entity.discard();
            }
            OrbcraftMod.LOG.warn("[orbcraft] 载体 {} 的代理实体 {} 不能战斗，改用 {}",
                    modelPlan.carrierId(), modelPlan.proxyEntity(), COMBAT_FALLBACK_PROXY);
            entity = createEntity(level, COMBAT_FALLBACK_PROXY);
            proxySubstituted = true;
        }
        if (!(entity instanceof Mob mob)) {
            return SpawnResult.failed("底层没能创建出可以战斗的实体，召唤失败", backendCap);
        }

        mob.setPos(pos.x, pos.y, pos.z);
        mob.setCustomName(Component.literal(spec.displayName()));
        mob.setCustomNameVisible(true);

        // ---- 数值：maxHealth + summonHealth、damage + summonDamage 等
        boolean hasElement = SpecTags.hasElement(spec.tags());
        SummonAttrMap.Resolution resolution = SummonAttrMap.resolve(spec.attrs(), hasElement);
        SummonStats.ApplyReport applied = resolution.stats().applyTo(mob);
        logMapping(spec, resolution, applied);

        // ---- 行为
        ServerPlayer owner = ownerPlayer(level, ownerKey);
        String rejection = SummonBehaviorBinder.bind(mob, spec, pos, owner, level);
        if (rejection != null) {
            mob.discard();
            return SpawnResult.failed(rejection, backendCap);
        }

        if (!level.addFreshEntity(mob)) {
            mob.discard();
            return SpawnResult.failed("世界没有接受这个实体（落点所在区块可能未加载），请稍后重试", backendCap);
        }

        UUID entityId = mob.getUUID();
        ledger.remember(spec.id(), entityId.toString(), ownerKey);

        // ---- 外观
        Integer tint = SpecTags.tintOf(SpecTags.elementOf(spec.tags()));
        SummonAppearance.Attached attached = look.attach(entityId, modelPlan, spec.scalePct(), tint);
        ModelCapability effective = proxySubstituted ? ModelCapability.NAMED_VANILLA : attached.capability();
        if (effective.shouldWarnPlayer()) {
            // 提示由调用方发（S1 落地路径已按 appearance().shouldWarnPlayer() 发过）。
            // 本类只保证等级如实，并在日志里留下「为什么降级」——见 attach 的那行 INFO。
            OrbcraftMod.LOG.info("[orbcraft] 召唤 {} 外观降级为 {}：{}", spec.displayName(), effective,
                    proxySubstituted ? "代理实体不能战斗，已改用 " + COMBAT_FALLBACK_PROXY : attached.note());
        }

        OrbcraftMod.LOG.info("[orbcraft] 召唤 {} · {} · 实体 {} · 血量 {} · 攻击 {} · 外观 {}",
                spec.displayName(), spec.behavior().zh(), entityId,
                applied.health(), applied.damage(), effective);
        return SpawnResult.ok(entityId, effective);
    }

    // ------------------------------------------------------------------ despawn

    @Override
    public boolean despawn(ServerLevel level, UUID entityId) {
        if (entityId == null) {
            return false;
        }
        SummonLedger ledger = level == null ? ledger() : SummonLedger.of(level);
        ledger.forget(entityId.toString());
        appearance().detach(entityId);
        if (level == null) {
            return false;
        }
        Entity entity = level.getEntityInAnyDimension(entityId);
        if (entity == null || entity.isRemoved()) {
            // 被别的机制清掉是正常运行的一部分，契约要求返回 false 而不是异常
            return false;
        }
        entity.discard();
        return true;
    }

    // ------------------------------------------------------------------ 归属

    @Override
    public boolean ownedBy(UUID entityId, String ownerKey) {
        if (entityId == null || ownerKey == null || ownerKey.isBlank()) {
            return false;
        }
        String owner = ledger().ownerOf(entityId.toString());
        // 查不到按「不是你的」处理：反过来会让玩家凭空指挥别人的召唤物
        return ownerKey.equals(owner);
    }

    @Override
    public ModelCapability capability() {
        return appearance().capability();
    }

    // ------------------------------------------------------------------ 内部

    private static Entity createEntity(ServerLevel level, String typeId) {
        EntityType<?> type = EntityType.byString(typeId).orElse(null);
        if (type == null) {
            OrbcraftMod.LOG.warn("[orbcraft] 实体 id {} 不存在（载体模型数据写错了？）", typeId);
            return null;
        }
        try {
            return type.create(level, EntitySpawnReason.MOB_SUMMONED);
        } catch (RuntimeException e) {
            OrbcraftMod.LOG.warn("[orbcraft] 创建实体 {} 失败：{}", typeId, e.toString());
            return null;
        }
    }

    private static Entity liveEntity(ServerLevel level, String entityId) {
        try {
            return level.getEntityInAnyDimension(UUID.fromString(entityId));
        } catch (IllegalArgumentException e) {
            // 存档里被手改坏的 UUID：当成不存在，别让一条脏数据挡住整次召唤
            return null;
        }
    }

    /**
     * 玩家查找。<b>只用 UUID</b>：{@code PlayerStore.ownerKeyOf} 的唯一定义就是
     * {@code playerId.toString()}，在这里改成「按名字模糊匹配」等于引入第二套账本键。
     */
    private static ServerPlayer ownerPlayer(ServerLevel level, String ownerKey) {
        MinecraftServer server = level.getServer();
        if (server == null) {
            return null;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (ownerKey.equals(player.getUUID().toString())) {
                return player;
            }
        }
        return null;
    }

    /**
     * 属性映射的日志。分三档写，是为了让「玩家说堆了暴击没伤害」这类反馈
     * 能在日志里一眼定位：已接 / 已知未接 / 数据漂移。
     */
    private static void logMapping(Spec spec, SummonAttrMap.Resolution resolution, SummonStats.ApplyReport applied) {
        if (!resolution.unwired().isEmpty()) {
            OrbcraftMod.LOG.debug("[orbcraft] {} 有 {} 个键本轮不落地：{}",
                    spec.id(), resolution.unwired().size(), resolution.unwired());
        }
        if (!applied.skipped().isEmpty()) {
            // 代理实体天生没有某个属性（例如鹦鹉没有 attack_damage），不是数据错误
            OrbcraftMod.LOG.info("[orbcraft] {} 的以下属性在代理实体上不存在，已跳过：{}",
                    spec.id(), applied.skipped());
        }
        for (String key : resolution.unknown()) {
            if (WARNED_UNKNOWN_KEYS.add(key)) {
                OrbcraftMod.LOG.warn("[orbcraft] 属性键 «{}» 不在 SummonAttrMap 里，本次没有生效。"
                        + "要么补进映射表，要么是卡表改了名字。", key);
            }
        }
    }
}
