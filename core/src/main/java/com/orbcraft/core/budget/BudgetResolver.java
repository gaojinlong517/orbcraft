package com.orbcraft.core.budget;

import com.orbcraft.core.spec.Rarity;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 战力预算裁决器 —— 整个系统的"数值最高法院"。
 *
 * 生成侧（规则引擎或大模型）永远只能提出"我想要什么数值"，不能决定最终数值。
 * 超预算时按等比缩放削减，并给出 trimmed 标记，让上层可以提示玩家
 * "能量不足以承载此描述" —— 这个提示本身就是玩法的一部分。
 */
public final class BudgetResolver {

    /**
     * @param attrs   最终数值（已量化）
     * @param spent   实际消耗点数
     * @param budget  可用预算
     * @param scale   等比缩放因子（1.0 表示未削减）
     * @param trimmed 是否发生了削减
     */
    public record Result(Map<String, Double> attrs, double spent, double budget, double scale, boolean trimmed) {
    }

    private static final double EPS = 1e-9;

    private final AttrCost cost;

    public BudgetResolver(AttrCost cost) {
        this.cost = cost;
    }

    public static BudgetResolver standard() {
        return new BudgetResolver(AttrCost.standard());
    }

    public Result resolve(Map<String, Double> wanted, Rarity rarity) {
        return resolve(wanted, rarity.budget());
    }

    public Result resolve(Map<String, Double> wanted, double budget) {
        Map<String, Double> clean = new LinkedHashMap<>();
        wanted.forEach((k, v) -> {
            if (cost.known(k) && v != null && v > 0) {
                clean.put(k, v);
            }
        });
        if (clean.isEmpty()) {
            return new Result(Map.of(), 0, budget, 1.0, false);
        }

        double full = totalCost(clean);
        if (full <= budget + EPS) {
            Map<String, Double> q = quantize(clean);
            return new Result(q, totalCost(q), budget, 1.0, false);
        }

        // 二分搜索等比缩放因子：cost(k * wanted) ≈ budget
        double lo = 0;
        double hi = 1;
        for (int i = 0; i < 50; i++) {
            double mid = (lo + hi) / 2;
            if (totalCost(scale(clean, mid)) <= budget) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        Map<String, Double> scaled = quantize(scale(clean, lo));
        // 量化后仍可能因浮点误差略超，做一次安全收缩
        while (totalCost(scaled) > budget + EPS && lo > 0) {
            lo *= 0.99;
            scaled = quantize(scale(clean, lo));
        }
        return new Result(scaled, totalCost(scaled), budget, lo, true);
    }

    public double totalCost(Map<String, Double> attrs) {
        double total = 0;
        for (Map.Entry<String, Double> e : attrs.entrySet()) {
            total += cost.costOf(e.getKey(), e.getValue());
        }
        return total;
    }

    private static Map<String, Double> scale(Map<String, Double> attrs, double k) {
        Map<String, Double> out = new LinkedHashMap<>();
        attrs.forEach((key, v) -> out.put(key, v * k));
        return out;
    }

    private static Map<String, Double> quantize(Map<String, Double> attrs) {
        Map<String, Double> out = new LinkedHashMap<>();
        attrs.forEach((key, v) -> {
            double p = AttrCost.precisionOf(key);
            double q = Math.floor(v / p + EPS) * p;
            if (q > 0) {
                out.put(key, Math.round(q * 1000d) / 1000d);
            }
        });
        return out;
    }
}
