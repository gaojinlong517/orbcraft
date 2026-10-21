package com.orbcraft.core.render;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 后端选择器：按能力等级从高到低取第一个可用的实现，失败则一路退化到 null 后端。
 *
 * <p>退化链是**启动期一次性决定**的，不是每次调用都试：
 * <pre>
 * BetterModel 3 层适配 → BetterModel 2 层适配 → NullModelBackend
 * </pre>
 *
 * <p>这条链的存在是为了让「BetterModel 版本不匹配」这类问题退化成外观降级，
 * 而不是 mod 加载失败。玩家的下限体验是「原版龙的形状，但有名字和粒子」，
 * 而不是「游戏打不开」。
 */
public final class ModelBackends {

    private ModelBackends() {
    }

    /**
     * @param catalog    预打包模型清单
     * @param candidates 候选实现，顺序不重要（按 {@link ModelCapability#rank()} 排序）
     * @return 必定非 null；必然是可用的实现
     */
    public static ModelBackend select(ModelCatalog catalog, ModelBackend... candidates) {
        List<ModelBackend> sorted = new ArrayList<>();
        if (candidates != null) {
            for (ModelBackend b : candidates) {
                if (b != null) {
                    sorted.add(b);
                }
            }
        }
        sorted.sort(Comparator.comparingInt((ModelBackend b) -> safeRank(b)).reversed());
        for (ModelBackend b : sorted) {
            if (safeAvailable(b)) {
                return new CatalogGuardBackend(b, catalog);
            }
        }
        return new CatalogGuardBackend(new NullModelBackend("无任何可用外观后端"), catalog);
    }

    private static int safeRank(ModelBackend b) {
        try {
            ModelCapability c = b.capability();
            return c == null ? 0 : c.rank();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static boolean safeAvailable(ModelBackend b) {
        try {
            return b.available();
        } catch (RuntimeException e) {
            return false;
        }
    }
}
