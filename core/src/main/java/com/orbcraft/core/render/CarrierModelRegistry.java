package com.orbcraft.core.render;

import com.orbcraft.core.util.Json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 载体模型注册表 —— 把 {@code carrier_models.json} 编译成「载体 id → {@link CarrierModelPlan}」。
 *
 * <p>核心职责只有一个，但它是整条外观链路的关键闸门：{@link #toCatalog()} 只登记
 * {@link CarrierModelPlan#baked()} 的模型。未烘焙的 {@code NEW} 模型不进清单，
 * 于是 {@link CatalogGuardBackend} 会返回 {@link ModelSpawnResult.Reason#MODEL_NOT_PACKED}，
 * 上层据此降级为 {@link ModelCapability#NAMED_VANILLA} 并向玩家提示。
 *
 * <p>反过来做会怎样：把 35 个 modelId 全登记进 catalog，再让 BetterModel 在运行时
 * 生成缺失资源 —— 每次召唤都可能触发全服客户端重载资源包（卡 1~2 秒）。
 * 而且资源缺失从「降级」变成「异常」，玩家观感是服务器坏了。所以宁可少登记。
 *
 * <p>线程安全：构造后不可变。
 */
public final class CarrierModelRegistry {

    /** 数据文件里承载数组的键名。 */
    private static final String ROOT_KEY = "carriers";

    private final Map<String, CarrierModelPlan> byCarrier;
    private final List<CarrierModelPlan> ordered;

    private CarrierModelRegistry(Map<String, CarrierModelPlan> byCarrier, List<CarrierModelPlan> ordered) {
        this.byCarrier = Collections.unmodifiableMap(byCarrier);
        this.ordered = Collections.unmodifiableList(ordered);
    }

    /**
     * 解析数据文件。校验失败一律抛 {@link IllegalArgumentException} 并指出是哪个载体 ——
     * 数据错误必须在启动期炸出来，而不是在玩家召唤时才表现为「外观没加载」。
     */
    public static CarrierModelRegistry load(String json) {
        Map<String, Object> root = Json.parseObject(json);
        List<Map<String, Object>> entries = Json.objectListOf(root, ROOT_KEY);
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("载体模型数据缺少非空的 " + ROOT_KEY + " 数组");
        }
        Map<String, CarrierModelPlan> byId = new LinkedHashMap<>();
        List<CarrierModelPlan> list = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            Map<String, Object> e = entries.get(i);
            String carrierId = Json.str(e, "carrier_id", "");
            if (carrierId.isBlank()) {
                throw new IllegalArgumentException("第 " + (i + 1) + " 条载体模型缺少 carrier_id");
            }
            String modelId = Json.str(e, "model_id", "");
            if (modelId.isBlank()) {
                throw new IllegalArgumentException("载体 " + carrierId + " 缺少 model_id");
            }
            String proxy = Json.str(e, "proxy_entity", "");
            if (proxy.isBlank()) {
                throw new IllegalArgumentException("载体 " + carrierId + " 缺少 proxy_entity");
            }
            String assetName = Json.str(e, "asset", "");
            CarrierModelPlan.AssetKind asset;
            try {
                asset = CarrierModelPlan.AssetKind.valueOf(assetName);
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException(
                        "载体 " + carrierId + " 的 asset 非法：" + assetName + "，只允许 REUSE/REPLACE/NEW");
            }
            CarrierModelPlan plan;
            try {
                plan = new CarrierModelPlan(
                        carrierId,
                        modelId,
                        proxy,
                        Json.strList(e, "animations"),
                        Json.boolOf(e, "tintable", false),
                        asset,
                        Json.str(e, "note", ""));
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("载体 " + carrierId + " 的模型计划非法：" + ex.getMessage(), ex);
            }
            if (byId.putIfAbsent(carrierId, plan) != null) {
                throw new IllegalArgumentException("载体 " + carrierId + " 重复登记");
            }
            list.add(plan);
        }
        return new CarrierModelRegistry(byId, list);
    }

    /** 空注册表。所有查询走兜底。 */
    public static CarrierModelRegistry empty() {
        return new CarrierModelRegistry(Map.of(), List.of());
    }

    public Optional<CarrierModelPlan> planFor(String carrierId) {
        return carrierId == null ? Optional.empty() : Optional.ofNullable(byCarrier.get(carrierId));
    }

    /** 按数据文件顺序返回全部计划。 */
    public List<CarrierModelPlan> plans() {
        return ordered;
    }

    /** 全部 modelId（含未烘焙的），用于诊断「数据里有但没有资源」。 */
    public Set<String> modelIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (CarrierModelPlan p : ordered) {
            ids.add(p.modelId());
        }
        return Collections.unmodifiableSet(ids);
    }

    public int size() {
        return ordered.size();
    }

    /**
     * 编译运行时清单：**只含已烘焙的模型**。
     *
     * <p>{@code New} 模型的缺席是刻意设计，不是遗漏 —— 见类注释。
     */
    public ModelCatalog toCatalog() {
        Map<String, Set<String>> models = new LinkedHashMap<>();
        for (CarrierModelPlan p : ordered) {
            if (p.baked()) {
                models.put(p.modelId(), p.animationSet());
            }
        }
        return ModelCatalog.of(models);
    }

    /**
     * 兜底计划 —— 未登记的载体也要有可用的外观约定，绝不返回 null。
     *
     * <p>用 {@code item_display} 而不是 {@code marker}：marker 完全不可见，
     * 玩家会以为召唤失败；item_display 至少能挂一个占位物品，让实体「看得见」。
     */
    public CarrierModelPlan fallbackFor(String carrierId) {
        String id = (carrierId == null || carrierId.isBlank()) ? "unknown" : carrierId;
        CarrierModelPlan plan = byCarrier.get(id);
        if (plan != null) {
            return plan;
        }
        return new CarrierModelPlan(
                id,
                CarrierModelPlan.MODEL_PREFIX + id,
                "minecraft:item_display",
                List.of(),
                false,
                CarrierModelPlan.AssetKind.NEW,
                "未登记的载体，静态兜底为物品展示实体，无动画");
    }
}
