package com.orbcraft.core.risk;

import java.util.Optional;
import java.util.Random;

/**
 * 失控事件表。权重决定出现频率，damageHearts 是单次事件的伤害上限（心）。
 *
 * 铁律：任何事件都不能致死。落地器在扣血前必须检查玩家剩余血量，
 * 低于 {@link #MIN_KEEP_HEARTS} 时强制归零。
 */
public enum ChaosEvent {

    FRIENDLY_FIRE(35, 2.0, "误伤附近生物"),
    BUMP_OWNER(20, 2.0, "顶撞主人"),
    FIREBALL(15, 3.0, "喷吐火球"),
    ROAR(15, 0.0, "原地咆哮"),
    BLINK(10, 0.0, "瞬移偏离"),
    RAMPAGE(5, 4.0, "短暂暴走");

    /** 玩家血量低于此值（心）时，失控伤害强制归零。 */
    public static final double MIN_KEEP_HEARTS = 2.0;

    private static final int TOTAL_WEIGHT;

    static {
        int sum = 0;
        for (ChaosEvent e : values()) {
            sum += e.weight;
        }
        TOTAL_WEIGHT = sum;
    }

    private final int weight;
    private final double damageHearts;
    private final String label;

    ChaosEvent(int weight, double damageHearts, String label) {
        this.weight = weight;
        this.damageHearts = damageHearts;
        this.label = label;
    }

    public int weight() {
        return weight;
    }

    public double damageHearts() {
        return damageHearts;
    }

    public String label() {
        return label;
    }

    public static ChaosEvent roll(Random random) {
        int r = random.nextInt(TOTAL_WEIGHT);
        for (ChaosEvent e : values()) {
            r -= e.weight;
            if (r < 0) {
                return e;
            }
        }
        return FRIENDLY_FIRE;
    }

    /** 根据玩家当前血量结算真实伤害（心），保证不致死。 */
    public double resolvedDamage(double ownerCurrentHearts, boolean targetIsOwner) {
        double dmg = targetIsOwner ? damageHearts / 2.0 : damageHearts;
        if (targetIsOwner && ownerCurrentHearts - dmg < MIN_KEEP_HEARTS) {
            dmg = Math.max(0, ownerCurrentHearts - MIN_KEEP_HEARTS);
        }
        return Math.min(4.0, dmg);
    }

    public static Optional<ChaosEvent> safeRoll(Random random) {
        return Optional.of(roll(random));
    }
}
