package com.orbcraft.core.draft;

/**
 * 可复现随机源（mulberry32）。
 *
 * <p>为什么自己写而不直接用 {@code java.util.Random}：抽卡必须**可按 seed 回放**。
 * 玩家投诉「我抽了十次都没出红卡」时，只有能拿 seed 重跑出同一串结果，
 * 才能证明这是概率而不是 bug。这条在以随机性为核心的玩法里几乎是必需品。
 *
 * <p>与 `tools/simulate-draft.mjs` 里的同名实现算法一致，但两端的 seed 派生方式不同，
 * 所以**不保证跨语言逐位相同**，只保证各自内部可复现。
 */
public final class SeedRandom {

    private int state;
    private final long origin;

    public SeedRandom(long seed) {
        this.origin = seed;
        this.state = (int) (seed ^ (seed >>> 32));
        if (this.state == 0) {
            this.state = 0x9E3779B9;
        }
    }

    public long seed() {
        return origin;
    }

    /** [0, 1) 均匀分布。 */
    public double nextDouble() {
        state += 0x6D2B79F5;
        int t = state;
        t = (t ^ (t >>> 15)) * (t | 1);
        t ^= t + (t ^ (t >>> 7)) * (t | 61);
        return Integer.toUnsignedLong(t ^ (t >>> 14)) / 4294967296.0;
    }

    /** [0, bound) 整数。bound <= 0 时返回 0。 */
    public int nextInt(int bound) {
        if (bound <= 0) {
            return 0;
        }
        return (int) (nextDouble() * bound);
    }

    /**
     * 按权重抽取。
     *
     * @param weights 各档权重，索引与 {@link Tier#values()} 对齐
     */
    public Tier pickTier(int[] weights) {
        int total = 0;
        for (int w : weights) {
            total += w;
        }
        if (total <= 0) {
            return Tier.WHITE;
        }
        double r = nextDouble() * total;
        for (int i = 0; i < weights.length; i++) {
            r -= weights[i];
            if (r < 0) {
                return Tier.values()[i];
            }
        }
        return Tier.WHITE;
    }
}
