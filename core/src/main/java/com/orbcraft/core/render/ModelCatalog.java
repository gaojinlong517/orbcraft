package com.orbcraft.core.render;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 已预打包的模型清单 —— **运行时资源包变更的唯一闸门**。
 *
 * <p>为什么必须有这张表：BetterModel 会自动生成资源包下发给客户端。
 * 「生成资源包」这件事本身是廉价的，**昂贵的是客户端要重新加载它** ——
 * 1.20.2+ 支持会话中热更新资源包，但每次都会让客户端卡 1~2 秒，
 * 而且是全服同时卡。开服高峰每生成一只新宠物就卡一下，玩家会直接判定服务器卡。
 *
 * <p>所以架构上把「美术资源」和「运行时生成」彻底切开：
 * <ul>
 *   <li>模型库在**发版时**一次性烘焙进资源包 → 这里登记</li>
 *   <li>AI 产出的新模型先进入「待烘焙队列」，下次维护窗口统一打包</li>
 *   <li>运行时 {@link ModelBackend#spawn} 只允许使用本清单内的 modelId，
 *       不在清单内直接返回 {@link ModelSpawnResult.Reason#MODEL_NOT_PACKED}</li>
 * </ul>
 *
 * <p>这个约束同时保护了两件事：玩家不会因为生成新宠而卡顿，AI 建模管线也不会
 * 变成线上服务的强依赖。
 *
 * <p>线程安全：构造后不可变，可并发读。
 */
public final class ModelCatalog {

    /** 模型 → 可用动画名。动画为空集表示该模型是静态的。 */
    private final Map<String, Set<String>> models;

    private ModelCatalog(Map<String, Set<String>> models) {
        this.models = Collections.unmodifiableMap(models);
    }

    public static ModelCatalog empty() {
        return new ModelCatalog(Map.of());
    }

    /**
     * @param models modelId → 动画名集合
     */
    public static ModelCatalog of(Map<String, Set<String>> models) {
        Map<String, Set<String>> copy = new LinkedHashMap<>();
        models.forEach((k, v) -> copy.put(k, Set.copyOf(v)));
        return new ModelCatalog(copy);
    }

    /** 便捷构造：无动画的静态模型。 */
    public static ModelCatalog ofStatic(Set<String> modelIds) {
        Map<String, Set<String>> m = new LinkedHashMap<>();
        for (String id : modelIds) {
            m.put(id, Set.of());
        }
        return new ModelCatalog(m);
    }

    public boolean isPacked(String modelId) {
        return modelId != null && models.containsKey(modelId);
    }

    public boolean hasAnimation(String modelId, String animation) {
        if (modelId == null || animation == null) {
            return false;
        }
        Set<String> anims = models.get(modelId);
        return anims != null && anims.contains(animation);
    }

    public Optional<Set<String>> animationsOf(String modelId) {
        return Optional.ofNullable(models.get(modelId));
    }

    public int size() {
        return models.size();
    }

    public Set<String> modelIds() {
        return models.keySet();
    }

    /**
     * 合并另一份清单（用于多个资源包来源 / 版本补丁叠加）。
     * 同 id 时动画取并集，不覆盖 —— 避免后加载的补丁把基础模型的动画删掉。
     */
    public ModelCatalog merge(ModelCatalog other) {
        Map<String, Set<String>> m = new LinkedHashMap<>();
        models.forEach((k, v) -> m.put(k, new HashSet<>(v)));
        other.models.forEach((k, v) -> m.computeIfAbsent(k, x -> new HashSet<>()).addAll(v));
        return new ModelCatalog(m);
    }
}
