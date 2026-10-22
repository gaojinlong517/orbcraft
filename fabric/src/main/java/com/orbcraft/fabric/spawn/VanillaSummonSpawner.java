package com.orbcraft.fabric.spawn;

import java.util.ArrayList;
import java.util.List;
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
 * 「让调用方决定要不要提示」—— 提示所有权在调用方，当前唯一发送方是 S1 的投掷落地路径
 * （它才知道「谁扔的、落在哪」）。S3 只做两件事：<b>如实报等级</b>
 * （模型没挂上时报 {@link ModelCapability#NAMED_VANILLA}，而不是转述后端自报的 FULL），
 * 以及把「为什么降级」写进 debug 日志。
 * 任何新增的 spawn 调用方都必须自己按 {@code shouldWarnPlayer()} 提示，
 * 否则玩家看到一只长得不对的召唤物时没有任何解释。
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
        // 限时召唤的到期表：同一处注册，避免「spawner 知道 lifespan、lifespans 知道 despawn」
        // 这条依赖被拆到两个 init 点后漏接一边
        SummonLifespans.init();
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

        // ---- 外观计划（整批共用一份：同一个 Spec 的 N 只当然长一个样）
        SummonAppearance.Plan plan = look.planFor(spec.tags());
        if (!plan.registered()) {
            OrbcraftMod.LOG.warn("[orbcraft] Spec {} 的 tags {} 里没有已登记的载体，外观走占位兜底",
                    spec.id(), spec.tags());
        }
        CarrierModelPlan modelPlan = plan.plan();

        // ---- 数值在落地前解析一次，整批共用：召唤数量与存续时间都是「这一次召唤」的参数，
        //      不是单只实体自己的属性
        boolean hasElement = SpecTags.hasElement(spec.tags());
        SummonAttrMap.Resolution resolution = SummonAttrMap.resolve(spec.attrs(), hasElement);
        int count = resolution.summonCount();
        ServerPlayer owner = ownerPlayer(level, ownerKey);

        // ---- 逐只落地。第一只失败才算整次失败（一个都没召出来，要把原因还给玩家）；
        //      后面的失败降级成「少召了一只」，已经落地的不能陪葬。
        List<UUID> spawnedIds = new ArrayList<>();
        SummonStats.ApplyReport firstReport = null;
        ModelCapability effective = backendCap;
        for (int i = 0; i < count; i++) {
            Vec3 spot = count == 1 ? pos : ringSpot(pos, i, count);
            OneSpawn one = spawnOne(level, spot, spec, ownerKey, owner, ledger, modelPlan, resolution);
            if (one.failure() != null) {
                if (spawnedIds.isEmpty()) {
                    return SpawnResult.failed(one.failure(), backendCap);
                }
                OrbcraftMod.LOG.warn("[orbcraft] {} 要召 {} 只，第 {}/{} 只没落地（{}），其余保持不变",
                        spec.displayName(), count, i + 1, count, one.failure());
                break;
            }
            spawnedIds.add(one.entityId());
            if (firstReport == null) {
                firstReport = one.report();
                effective = one.capability();
            }
        }

        logMapping(spec, resolution, firstReport);

        // ---- 限时召唤：到期由 SummonLifespans 在服务器 tick 末尾走 despawnByRuntime 清场。
        //      只在实际堆了 summonLifespan 时登记（语义裁决见 SummonAttrMap 的注释）
        if (resolution.lifespanTicks() > 0) {
            long expireAt = level.getGameTime() + resolution.lifespanTicks();
            for (UUID id : spawnedIds) {
                SummonLifespans.register(id, expireAt);
            }
            OrbcraftMod.LOG.info("[orbcraft] {} 为限时召唤，{} 秒后消散", spec.displayName(),
                    resolution.lifespanTicks() / 20.0);
        }

        OrbcraftMod.LOG.info("[orbcraft] 召唤 {} · {} · {} 只 · 实体 {} · 血量 {} · 攻击 {} · 外观 {}",
                spec.displayName(), spec.behavior().zh(), spawnedIds.size(),
                spawnedIds.isEmpty() ? "-" : spawnedIds.get(0),
                firstReport == null ? -1 : firstReport.health(),
                firstReport == null ? -1 : firstReport.damage(),
                effective);
        return SpawnResult.ok(spawnedIds.isEmpty() ? null : spawnedIds.get(0), effective);
    }

    /**
     * 一只召唤物的落地结果。{@code failure} 非空即失败，其余字段只在成功时有意义。
     * 用 record 而不是异常：本类承诺不抛异常，失败原因要走值返回。
     */
    private record OneSpawn(String failure,
                            UUID entityId,
                            ModelCapability capability,
                            SummonStats.ApplyReport report,
                            boolean proxySubstituted) {

        static OneSpawn fail(String reason) {
            return new OneSpawn(reason, null, null, null, false);
        }

        static OneSpawn ok(UUID id, ModelCapability cap, SummonStats.ApplyReport report, boolean proxy) {
            return new OneSpawn(null, id, cap, report, proxy);
        }
    }

    /**
     * 落地一只。原来这段内联在 spawn 里；接 summonCount 后同一段逻辑要跑 N 次，
     * 拆出来让「第一只失败=整次失败、后续失败=少一只」的分级语义留在调用方。
     */
    private static OneSpawn spawnOne(ServerLevel level, Vec3 pos, Spec spec, String ownerKey,
                                     ServerPlayer owner, SummonLedger ledger,
                                     CarrierModelPlan modelPlan, SummonAttrMap.Resolution resolution) {
        ModelCapability backendCap = appearance().capability();

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
            return OneSpawn.fail("底层没能创建出可以战斗的实体，召唤失败");
        }

        mob.setPos(pos.x, pos.y, pos.z);
        mob.setCustomName(Component.literal(spec.displayName()));
        mob.setCustomNameVisible(true);

        SummonStats.ApplyReport applied = resolution.stats().applyTo(mob);

        String rejection = SummonBehaviorBinder.bind(mob, spec, pos, owner, level);
        if (rejection != null) {
            mob.discard();
            return OneSpawn.fail(rejection);
        }

        if (!level.addFreshEntity(mob)) {
            mob.discard();
            return OneSpawn.fail("世界没有接受这个实体（落点所在区块可能未加载），请稍后重试");
        }

        UUID entityId = mob.getUUID();
        ledger.remember(spec.id(), entityId.toString(), ownerKey);
        // 战斗数值在这里登记：召唤物一旦生成就带自己的暴击/吸血，
        // 否则这三个键永远只是数据里的死数字。
        com.orbcraft.fabric.combat.CombatStats.remember(
                entityId, com.orbcraft.fabric.combat.CombatStats.of(spec.attrs()));

        // ---- 外观
        Integer tint = SpecTags.tintOf(SpecTags.elementOf(spec.tags()));
        SummonAppearance.Attached attached = appearance().attach(entityId, modelPlan, spec.scalePct(), tint);
        ModelCapability effective = proxySubstituted ? ModelCapability.NAMED_VANILLA : attached.capability();
        if (effective.shouldWarnPlayer()) {
            // 提示所有权在调用方（当前唯一发送方是 S1 的投掷落地路径）。
            // 这里只留一条 debug：联调时「为什么这只召唤物长得不对」只能从这条查。
            OrbcraftMod.LOG.debug("[orbcraft] 召唤 {} 外观降级 · spec={} · 载体={} · 等级={} · 原因={}",
                    spec.displayName(), spec.id(), modelPlan.carrierId(), effective,
                    proxySubstituted ? "代理实体不能战斗，已改用 " + COMBAT_FALLBACK_PROXY : attached.note());
        }

        return OneSpawn.ok(entityId, effective, applied, proxySubstituted);
    }

    /**
     * 多只召唤时把落点摊开成一个小环，避免 N 只叠在同一坐标里互相挤成一团。
     * 半径 1 格：够散开，又不至于把召唤物放到玩家够不着的地方。
     */
    private static Vec3 ringSpot(Vec3 center, int index, int total) {
        double angle = (Math.PI * 2 * index) / total;
        return new Vec3(center.x + Math.cos(angle), center.y, center.z + Math.sin(angle));
    }

    // ------------------------------------------------------------------ despawn

    /**
     * 限时召唤到点的清理入口。包内可见：只有 {@link SummonLifespans} 在 tick 里用，
     * 契约层之外不允许有第二条「让召唤物消失」的路。
     */
    static boolean despawnByRuntime(ServerLevel level, UUID entityId) {
        return INSTANCE.despawn(level, entityId);
    }

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
