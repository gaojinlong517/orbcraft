package com.orbcraft.core.settle;

import com.orbcraft.core.spec.Rarity;

/**
 * 孵化时长结算 —— 把「稀有度 + 红卡倍率」换算成一个 Spec 能接受的秒数。
 *
 * <p><b>这个类存在的唯一理由是一个数值冲突</b>：
 * <ul>
 *   <li>文档 §20.1 的基础时长是 8 / 30 / 120 / 600 秒</li>
 *   <li>{@link Rarity#maxHatchSeconds()} 的上限是 30 / 90 / 180 / 600 秒</li>
 *   <li>而红卡带 {@code HATCH_TIME_PCT +0.5}，且 {@code DraftResult.hatchMultiplier()}
 *       是**累加**的 —— 四层红卡就是 ×3.0</li>
 * </ul>
 *
 * <p>三者一乘就溢出：史诗 120 × 1.5 = 180 刚好触顶，再叠一张就 240 越界，
 * 传说 600 的任何倍率都越界。而 {@code Spec} 的构造器会直接抛
 * {@code IllegalArgumentException}。如果不在这里显式截断，玩家抽到第二张红卡时
 * 定稿就会崩 —— 这是那种「测试全绿、线上必炸」的缺口。
 *
 * <p>截断策略是<b>向上取到上限</b>而不是压缩整个曲线，因为上限本身有玩法含义：
 * 读条超过 10 分钟，玩家就不再把这件事当作「等」，而是当作「忘了」。
 * 溢出量通过 {@link Plan#overflowPct()} 暴露出来，供 UI 提示「已到该品质的等待上限」，
 * 而不是让玩家以为卡牌的代价没有生效。
 */
public final class HatchTime {

    /** 文档 §20.1：站着等，能忍。 */
    public static final int COMMON_SECONDS = 8;
    /** 开始想做点别的。 */
    public static final int RARE_SECONDS = 30;
    /** 一定去干别的。 */
    public static final int EPIC_SECONDS = 120;
    /** 当作一个任务来做。 */
    public static final int LEGENDARY_SECONDS = 600;

    /** Spec 要求 hatchSeconds >= 1，倍率再异常也不能低于这个值。 */
    private static final int MIN_SECONDS = 1;

    private HatchTime() {
    }

    public static int baseSeconds(Rarity rarity) {
        return switch (rarity) {
            case COMMON -> COMMON_SECONDS;
            case RARE -> RARE_SECONDS;
            case EPIC -> EPIC_SECONDS;
            case LEGENDARY -> LEGENDARY_SECONDS;
        };
    }

    /**
     * 一次时长结算的完整结果，保留中间量以便解释「为什么是这么多秒」。
     *
     * @param base       基础秒数（按稀有度查表）
     * @param multiplier 累加后的倍率，1.0 表示没有任何红卡
     * @param cap        该稀有度的上限
     * @param raw        未截断的秒数
     * @param seconds    实际生效秒数（已 clamp，可直接喂给 Spec）
     * @param clamped    是否被上限截断
     */
    public record Plan(int base, double multiplier, int cap, int raw, int seconds, boolean clamped) {

        /** 超出上限的比例，0 表示没溢出。用于 UI 提示，不参与数值计算。 */
        public double overflowPct() {
            return clamped ? (double) (raw - cap) / cap : 0.0;
        }

        /** 一行播报，例如 {@code 史诗 120s ×1.5 → 180s（上限 180s）}。 */
        public String brief() {
            String s = base + "s ×" + String.format("%.1f", multiplier) + " → " + seconds + "s";
            return clamped ? s + "（已到上限 " + cap + "s）" : s;
        }
    }

    /**
     * 结算时长。
     *
     * @param rarity     产物稀有度
     * @param multiplier {@code DraftResult.hatchMultiplier()} 的结果
     */
    public static Plan plan(Rarity rarity, double multiplier) {
        if (rarity == null) {
            throw new SettleException("稀有度不能为空");
        }
        int base = baseSeconds(rarity);
        int cap = rarity.maxHatchSeconds();

        // 倍率理论上 >= 1.0（drawback 都是正数），但负值代价随时可能被加进来，
        // 与其在这里信任上游，不如兜住一个下界。
        double safeMultiplier = Double.isFinite(multiplier) ? multiplier : 1.0;
        int raw = (int) Math.round(base * safeMultiplier);
        raw = Math.max(MIN_SECONDS, raw);

        int seconds = Math.min(raw, cap);
        seconds = Math.max(MIN_SECONDS, seconds);
        return new Plan(base, safeMultiplier, cap, raw, seconds, raw > cap);
    }
}
