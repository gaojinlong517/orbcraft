package com.orbcraft.core.hatch;

/**
 * 孵化进度计算 —— 纯函数，不持有任何状态，也不依赖游戏 tick。
 *
 * 平台层只需要保存 (specId, startTick, totalSeconds)，每次扫描时调用这里的静态方法即可。
 * 这样做的好处：进度条逻辑可以单元测试，且两个平台共用同一份实现。
 *
 * <p><b>进度条实现约定</b>：物品的 max_damage 固定为 {@link #BAR_MAX}，
 * damage 从 BAR_MAX 递减到 0，原版耐久条会自然从空填满，形成"充能"的观感，
 * 完全不需要资源包。
 */
public final class HatchSession {

    /** 耐久条总量。数值越大进度越细腻，1000 足够。 */
    public static final int BAR_MAX = 1000;

    private static final int TICKS_PER_SECOND = 20;
    private static final int BAR_CELLS = 10;

    private HatchSession() {
    }

    public static long totalTicks(int totalSeconds) {
        return (long) totalSeconds * TICKS_PER_SECOND;
    }

    /** 进度 0.0 ~ 1.0。 */
    public static double progress(long startTick, int totalSeconds, long nowTick) {
        long total = totalTicks(totalSeconds);
        if (total <= 0) {
            return 1.0;
        }
        long elapsed = Math.max(0, nowTick - startTick);
        return Math.min(1.0, (double) elapsed / total);
    }

    public static boolean ready(long startTick, int totalSeconds, long nowTick) {
        return nowTick - startTick >= totalTicks(totalSeconds);
    }

    /**
     * 反算耐久条 damage 值。
     * 充能中返回 1 ~ BAR_MAX（条从空到满），完成后返回 0（条消失，代表"已完成"）。
     */
    public static int durabilityDamage(long startTick, int totalSeconds, long nowTick) {
        double p = progress(startTick, totalSeconds, nowTick);
        if (p >= 1.0) {
            return 0;
        }
        int dmg = (int) Math.ceil(BAR_MAX * (1.0 - p));
        return Math.max(1, Math.min(BAR_MAX, dmg));
    }

    public static long remainingSeconds(long startTick, int totalSeconds, long nowTick) {
        long total = totalTicks(totalSeconds);
        long elapsed = Math.max(0, nowTick - startTick);
        long remainTicks = Math.max(0, total - elapsed);
        return (remainTicks + TICKS_PER_SECOND - 1) / TICKS_PER_SECOND;
    }

    /** 生成 lore 文字条，例如 {@code ▰▰▰▰▱▱▱▱▱▱ 43% · 2:41}。 */
    public static String loreBar(long startTick, int totalSeconds, long nowTick) {
        double p = progress(startTick, totalSeconds, nowTick);
        int filled = (int) Math.round(p * BAR_CELLS);
        StringBuilder sb = new StringBuilder(BAR_CELLS * 2);
        for (int i = 0; i < BAR_CELLS; i++) {
            sb.append(i < filled ? '▰' : '▱');
        }
        long remain = remainingSeconds(startTick, totalSeconds, nowTick);
        return sb + " " + (int) Math.round(p * 100) + "% · "
                + (remain / 60) + ":" + String.format("%02d", remain % 60);
    }

    public static String remainingClock(long startTick, int totalSeconds, long nowTick) {
        long remain = remainingSeconds(startTick, totalSeconds, nowTick);
        return (remain / 60) + ":" + String.format("%02d", remain % 60);
    }
}
