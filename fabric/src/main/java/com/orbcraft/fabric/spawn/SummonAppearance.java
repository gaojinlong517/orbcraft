package com.orbcraft.fabric.spawn;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.orbcraft.core.render.CarrierModelPlan;
import com.orbcraft.core.render.CarrierModelRegistry;
import com.orbcraft.core.render.ModelBackend;
import com.orbcraft.core.render.ModelBackends;
import com.orbcraft.core.render.ModelCapability;
import com.orbcraft.core.render.ModelRequest;
import com.orbcraft.core.render.ModelSpawnResult;
import com.orbcraft.core.render.NullModelBackend;
import com.orbcraft.fabric.OrbcraftMod;

/**
 * 外观装配 —— 载体 id → {@link CarrierModelPlan} → 挂到宿主实体上。
 *
 * <p><b>在链路里的位置</b>：{@code VanillaSummonSpawner} 决定「召出什么实体、多少血」，
 * 本类只决定「它长什么样」。两者故意分开：玩法侧永远不该因为模型缺失而失败。
 *
 * <p><b>本类不判断模型能不能加载。</b> 那件事已经在 {@code ModelBackend} 里做了三遍
 * （{@code CatalogGuardBackend} 拦未打包模型、{@code NullModelBackend} 兜底、
 * {@code ModelBackends} 选后端）。在这里再写一遍就会有两套能力等级，
 * 玩家会收到自相矛盾的提示。这里只做一件事：把后端上报的等级<b>转述</b>出去。
 *
 * <p><b>数据来源</b>：{@code /data/carrier_models.json}（core 的 resources，随 mod 打包）。
 * 读失败不抛异常 —— 外观是附加层，一个资源文件缺失不该让召唤失败；
 * 退化成空注册表后所有载体都走 {@code item_display} 兜底并如实上报降级。
 */
public final class SummonAppearance {

    private static final String DATA_RESOURCE = "/data/carrier_models.json";

    private final CarrierModelRegistry registry;
    private final ModelBackend backend;

    private SummonAppearance(CarrierModelRegistry registry, ModelBackend backend) {
        this.registry = registry;
        this.backend = backend;
    }

    /**
     * 加载注册表并选定后端。**只在初始化时调一次**：
     * 后端选择是启动期决定，每次召唤重选会让「同一只宠物两次长得不一样」。
     */
    public static SummonAppearance load() {
        CarrierModelRegistry registry = loadRegistry();
        ModelBackend backend = ModelBackends.select(
                registry.toCatalog(),
                // 目前仓库里只有兜底后端；BetterModel 适配器就位后在这里追加一个候选，
                // 选择器会自动按能力等级挑更高的那个，本类不用改。
                new NullModelBackend("未安装 BetterModel 外观后端"));
        OrbcraftMod.LOG.info("[orbcraft] 外观后端 {} · 能力 {} · 已烘焙模型 {} 个（数据里共 {} 个载体）",
                backend.backendId(), backend.capability(), registry.toCatalog().size(), registry.size());
        return new SummonAppearance(registry, backend);
    }

    private static CarrierModelRegistry loadRegistry() {
        try (InputStream in = SummonAppearance.class.getResourceAsStream(DATA_RESOURCE)) {
            if (in == null) {
                OrbcraftMod.LOG.warn("[orbcraft] 找不到 {}，外观全部走兜底代理实体", DATA_RESOURCE);
                return CarrierModelRegistry.empty();
            }
            return CarrierModelRegistry.load(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            OrbcraftMod.LOG.warn("[orbcraft] 载体模型数据解析失败，外观全部走兜底代理实体：{}", e.toString());
            return CarrierModelRegistry.empty();
        }
    }

    /** 载体解析结果。{@code registered=false} 表示走了 {@code fallbackFor}，值得打一条日志。 */
    public record Plan(CarrierModelPlan plan, boolean registered) {
    }

    /**
     * 从 tags 里找载体 id。找法是「第一个在注册表里登记过的短 id」而不是取第一个 tag：
     * tags 里同时有元素 id 和属性键，取第一个会随卡牌顺序漂移。
     */
    public Plan planFor(List<String> tags) {
        if (tags != null) {
            for (String tag : tags) {
                Optional<CarrierModelPlan> found = registry.planFor(tag);
                if (found.isPresent()) {
                    return new Plan(found.get(), true);
                }
            }
        }
        return new Plan(registry.fallbackFor("unknown"), false);
    }

    public ModelCapability capability() {
        return backend.capability();
    }

    /**
     * 一次挂载的结果。{@code note} 只用于给玩家的降级说明与日志，
     * 不参与任何玩法判定。
     */
    public record Attached(ModelCapability capability, String note) {
    }

    /**
     * 把外观挂上去，返回<b>玩家实际看到</b>的等级。
     *
     * <p>未挂上时返回 {@link ModelCapability#NAMED_VANILLA} 而不是后端的等级：
     * 后端可能是 FULL 但模型没打包，此时玩家眼前的确实是原版代理实体。
     * 把这个差别如实上报，上层才不会对玩家说「外观正常」。
     */
    public Attached attach(UUID hostId, CarrierModelPlan plan, int scalePct, Integer tint) {
        if (plan == null) {
            return new Attached(ModelCapability.NAMED_VANILLA, "载体未知");
        }
        // 不可染色的载体忽略 tint：传进去只会得到一个「数据说支持、实际没效果」的假象
        Integer effectiveTint = plan.tintable() ? tint : null;
        ModelRequest request = new ModelRequest(hostId, plan.modelId(), clampScale(scalePct), effectiveTint, true);
        ModelSpawnResult result = backend.spawn(request);
        if (!result.isOk()) {
            OrbcraftMod.LOG.info("[orbcraft] 外观未挂上（{} / {}）：{} → 使用代理实体 {}",
                    result.status(), result.reason(), result.detail(), plan.proxyEntity());
            return new Attached(ModelCapability.NAMED_VANILLA,
                    "模型 " + plan.modelId() + " 没能挂上（" + result.reason() + "）");
        }
        return new Attached(backend.capability(), "");
    }

    public void detach(UUID hostId) {
        if (hostId != null) {
            backend.despawn(hostId);
        }
    }

    /** 与 {@code Spec.scalePct} 的边界一致；数据层已经校验过，这里防的是别的调用方。 */
    private static int clampScale(int scalePct) {
        if (scalePct <= 0) {
            return 100;
        }
        return Math.min(400, Math.max(25, scalePct));
    }
}
