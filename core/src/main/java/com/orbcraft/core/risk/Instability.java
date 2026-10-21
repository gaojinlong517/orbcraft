package com.orbcraft.core.risk;

import java.util.Optional;
import java.util.Random;

/**
 * 失控度（0~100）—— 把"随机攻击"变成可感知、可预期、可自证的概率系统。
 *
 * <p>设计要点：
 * <ul>
 *   <li>概率与"这只召唤物最近做了多少事、挨了多少打"绑定，玩家能感觉到它越来越不稳</li>
 *   <li>roll 间隔固定（默认 30 秒），不是每 tick 掷骰子，否则频率失控</li>
 *   <li>任何时候都可以查 {@link #zone()} 做视觉反馈，这是玩家理解系统的唯一入口</li>
 * </ul>
 */
public final class Instability {

    public static final double MAX = 100;
    /** 触发概率分母：P = value / DIVISOR，65 点 → 32.5%。 */
    public static final double DIVISOR = 200;

    private double value;

    private Instability(double value) {
        this.value = value;
    }

    public static Instability of(int base) {
        return new Instability(clamp(base));
    }

    public double value() {
        return value;
    }

    public void onAttackHit() {
        add(2);
    }

    /** @param damageFraction 本次受到伤害占总生命的比例（0~1） */
    public void onDamageTaken(double damageFraction) {
        add(damageFraction * 30);
    }

    /** 主人附近有村民 / 宠物等易干扰目标。 */
    public void onNearFriendly() {
        add(5);
    }

    /** 每次 roll 间隔调用一次，自然冷却会让它慢慢稳定下来。 */
    public void decay(double seconds) {
        add(-seconds / 5.0);
    }

    public void reset(int base) {
        this.value = clamp(base);
    }

    public boolean shouldTrigger(Random random) {
        return random.nextDouble() < value / DIVISOR;
    }

    /** 掷一次骰子，命中则返回具体事件。 */
    public Optional<ChaosEvent> maybeTrigger(Random random) {
        if (!shouldTrigger(random)) {
            return Optional.empty();
        }
        return Optional.of(ChaosEvent.roll(random));
    }

    /** 0 = 稳定（绿）/ 1 = 躁动（黄）/ 2 = 濒临失控（红）。 */
    public int zone() {
        if (value < 40) {
            return 0;
        }
        if (value < 70) {
            return 1;
        }
        return 2;
    }

    public String zoneLabel() {
        return switch (zone()) {
            case 0 -> "稳定";
            case 1 -> "躁动";
            default -> "濒临失控";
        };
    }

    private void add(double delta) {
        this.value = clamp(this.value + delta);
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(MAX, v));
    }
}
