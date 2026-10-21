package com.orbcraft.core.budget;

import java.util.List;
import java.util.Map;

/**
 * 属性单价表。核心设计：**边际成本递增**。
 *
 * 如果所有属性都是线性定价，玩家会把全部预算堆到攻击力上，得到一把"其他什么都没有但伤害离谱"的刀。
 * 分段定价让堆单一属性的代价急剧上升，自然引导玩家去做混合搭配。
 */
public final class AttrCost {

    public record Tier(double upTo, double unitCost) {
    }

    public static final String ATTACK_DAMAGE = "attack_damage";
    public static final String ATTACK_SPEED = "attack_speed";
    public static final String ARMOR = "armor";
    public static final String MAX_HEALTH = "max_health";
    public static final String MOVE_SPEED = "move_speed";
    public static final String KNOCKBACK_RESIST = "knockback_resist";

    private static final double INF = 1_000_000d;

    private final Map<String, List<Tier>> table;

    private AttrCost(Map<String, List<Tier>> table) {
        this.table = Map.copyOf(table);
    }

    public static AttrCost standard() {
        return new AttrCost(Map.of(
                ATTACK_DAMAGE, List.of(new Tier(3, 3), new Tier(6, 6), new Tier(INF, 12)),
                ATTACK_SPEED, List.of(new Tier(0.20, 4), new Tier(0.50, 8), new Tier(INF, 16)),
                ARMOR, List.of(new Tier(4, 2), new Tier(10, 4), new Tier(INF, 8)),
                MAX_HEALTH, List.of(new Tier(4, 2.5), new Tier(10, 5), new Tier(INF, 10)),
                MOVE_SPEED, List.of(new Tier(0.05, 3), new Tier(0.15, 6), new Tier(INF, 12)),
                KNOCKBACK_RESIST, List.of(new Tier(0.30, 5), new Tier(0.60, 10), new Tier(INF, 20))
        ));
    }

    public boolean known(String attr) {
        return table.containsKey(attr);
    }

    /** 累积成本。未知属性视为无穷大（调用方必须先用 known 过滤）。 */
    public double costOf(String attr, double value) {
        List<Tier> tiers = table.get(attr);
        if (tiers == null) {
            return Double.POSITIVE_INFINITY;
        }
        if (value <= 0) {
            return 0;
        }
        double total = 0;
        double prev = 0;
        for (Tier t : tiers) {
            if (value <= prev) {
                break;
            }
            double seg = Math.min(value, t.upTo()) - prev;
            total += seg * t.unitCost();
            prev = t.upTo();
        }
        if (value > prev) {
            total += (value - prev) * tiers.get(tiers.size() - 1).unitCost();
        }
        return total;
    }

    /** 逆函数：给定预算能买到多少属性值。 */
    public double maxValue(String attr, double budget) {
        List<Tier> tiers = table.get(attr);
        if (tiers == null || budget <= 0) {
            return 0;
        }
        double prev = 0;
        double spent = 0;
        for (Tier t : tiers) {
            double segLen = t.upTo() - prev;
            double segCost = segLen * t.unitCost();
            if (spent + segCost <= budget) {
                spent += segCost;
                prev = t.upTo();
            } else {
                return prev + (budget - spent) / t.unitCost();
            }
        }
        Tier last = tiers.get(tiers.size() - 1);
        return prev + (budget - spent) / last.unitCost();
    }

    /** 该属性允许的最小量化步长，保证落地时数值好看且可预期。 */
    public static double precisionOf(String attr) {
        return switch (attr) {
            case ATTACK_DAMAGE -> 0.5;
            case ARMOR, MAX_HEALTH -> 1.0;
            case ATTACK_SPEED -> 0.05;
            case MOVE_SPEED -> 0.01;
            case KNOCKBACK_RESIST -> 0.05;
            default -> 0.1;
        };
    }
}
