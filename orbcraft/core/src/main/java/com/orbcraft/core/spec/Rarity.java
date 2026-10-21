package com.orbcraft.core.spec;

/**
 * 稀有度。决定三件事：
 * 1. 战力预算（budget）—— 生成侧只能在预算内分配数值
 * 2. 召唤物初始失控度（instabilityBase）
 * 3. 孵化时间上限（maxHatchSeconds）
 */
public enum Rarity {

    COMMON("普通", 20, 10, 30),
    RARE("稀有", 45, 25, 90),
    EPIC("史诗", 80, 45, 180),
    LEGENDARY("传说", 140, 65, 600);

    private final String zh;
    private final int budget;
    private final int instabilityBase;
    private final int maxHatchSeconds;

    Rarity(String zh, int budget, int instabilityBase, int maxHatchSeconds) {
        this.zh = zh;
        this.budget = budget;
        this.instabilityBase = instabilityBase;
        this.maxHatchSeconds = maxHatchSeconds;
    }

    public String zh() {
        return zh;
    }

    public int budget() {
        return budget;
    }

    public int instabilityBase() {
        return instabilityBase;
    }

    public int maxHatchSeconds() {
        return maxHatchSeconds;
    }

    /** 由程度词命中数与效果词数量推断稀有度。 */
    public static Rarity fromIntensity(int intensityHits, int effectCount) {
        int score = intensityHits * 2 + effectCount;
        if (score >= 6) {
            return LEGENDARY;
        }
        if (score >= 4) {
            return EPIC;
        }
        if (score >= 2) {
            return RARE;
        }
        return COMMON;
    }
}
