package com.orbcraft.core.build;

import com.orbcraft.core.draft.Tier;
import com.orbcraft.core.spec.Rarity;

import java.util.Map;

/**
 * 构筑流程的全部可调参数。
 *
 * <p><b>候选数与选取数是两个独立的量，不能合并。</b>
 * 需求里「共 5 张描述卡牌」说的是<b>候选</b>张数（玩家看到 5 张），
 * 而玩家最终拿走几张是另一件事。合成一个 {@code drawCount} 会直接锁死
 * 「5 张里挑 2 张」这种最基本的手感，也无法做「困难模式发 3 张」。
 *
 * <p><b>没有预算，也没有点数扣减。</b> 这是与 {@code draft.DraftConfig}
 * 最根本的区别：那边 {@code budget} 是硬上限，选牌要够点数才买得起；
 * 这里玩家能选什么只由<b>抽到了什么</b>决定，选哪张不影响任何资源。
 * 强度控制因此从「你投入多少点数」转成「你抽到多好的品质」，
 * 也就是需求里那条「非交易型随机构筑」。
 *
 * @param descDealCount      描述卡层候选张数（默认 5）
 * @param descPickCount      描述卡层必须选取张数（默认 2）
 * @param descCarrierSlots   描述卡候选里固定保留的载体张数（默认 1）
 * @param effectDealCount    效果层候选张数（默认 5）
 * @param effectPickCount    效果层必须选取张数（默认 1）
 * @param qualityWeights     品质抽取权重
 * @param descFloor          描述卡层保底品质（默认白 = 不保底）
 * @param effectFloor        效果层保底品质（默认白 = 不保底）
 * @param commonAvgMax       平均点数不超过它 → 普通
 * @param rareAvgMax         平均点数不超过它 → 稀有
 * @param epicAvgMax         平均点数不超过它 → 史诗，超过即传说
 * @param extraCardHatchPct  每多带 1 张卡，孵化时长额外增加的百分比（默认 0.15）
 */
public record BuildConfig(
        int descDealCount,
        int descPickCount,
        int descCarrierSlots,
        int effectDealCount,
        int effectPickCount,
        Map<Tier, Integer> qualityWeights,
        Tier descFloor,
        Tier effectFloor,
        double commonAvgMax,
        double rareAvgMax,
        double epicAvgMax,
        double extraCardHatchPct
) {

    /** 判断候选张数的合理区间：少于 3 张不构成选择，多于 9 张界面放不下。 */
    public static final int MIN_DEAL = 3;
    public static final int MAX_DEAL = 9;

    public BuildConfig {
        if (descDealCount < MIN_DEAL || descDealCount > MAX_DEAL) {
            throw new BuildException(BuildException.Code.BAD_CONFIG,
                    "descDealCount 应在 " + MIN_DEAL + "~" + MAX_DEAL + " 之间：" + descDealCount);
        }
        if (effectDealCount < MIN_DEAL || effectDealCount > MAX_DEAL) {
            throw new BuildException(BuildException.Code.BAD_CONFIG,
                    "effectDealCount 应在 " + MIN_DEAL + "~" + MAX_DEAL + " 之间：" + effectDealCount);
        }
        if (descCarrierSlots < 1) {
            throw new BuildException(BuildException.Code.BAD_CONFIG,
                    "descCarrierSlots 至少为 1：没有载体的产物无法生成外观");
        }
        // 载体位必须严格少于选取数，否则玩家只能选载体，描述层退化成「再点一次确认」
        if (descPickCount <= descCarrierSlots) {
            throw new BuildException(BuildException.Code.BAD_CONFIG,
                    "descPickCount(" + descPickCount + ") 必须大于 descCarrierSlots("
                            + descCarrierSlots + ")，否则描述层没有任何可搭配的属性/元素");
        }
        if (descPickCount > descDealCount) {
            throw new BuildException(BuildException.Code.BAD_CONFIG,
                    "descPickCount(" + descPickCount + ") 不能超过 descDealCount(" + descDealCount + ")");
        }
        if (effectPickCount < 1 || effectPickCount > effectDealCount) {
            throw new BuildException(BuildException.Code.BAD_CONFIG,
                    "effectPickCount 应在 1~" + effectDealCount + " 之间：" + effectPickCount);
        }
        if (!(commonAvgMax > 0 && commonAvgMax < rareAvgMax && rareAvgMax < epicAvgMax)) {
            throw new BuildException(BuildException.Code.BAD_CONFIG,
                    "稀有度阈值必须递增且为正：" + commonAvgMax + "/" + rareAvgMax + "/" + epicAvgMax);
        }
        if (extraCardHatchPct < 0 || extraCardHatchPct > 2) {
            throw new BuildException(BuildException.Code.BAD_CONFIG,
                    "extraCardHatchPct 应在 0~2 之间：" + extraCardHatchPct);
        }

        qualityWeights = qualityWeights == null
                ? Map.of(Tier.WHITE, 38, Tier.BLUE, 32, Tier.GOLD, 22, Tier.RED, 8)
                : Map.copyOf(qualityWeights);
        int total = 0;
        for (Tier t : Tier.values()) {
            int w = qualityWeights.getOrDefault(t, 0);
            if (w < 0) {
                throw new BuildException(BuildException.Code.BAD_CONFIG,
                        t + " 的品质权重不能为负：" + w);
            }
            total += w;
        }
        if (total <= 0) {
            throw new BuildException(BuildException.Code.BAD_CONFIG, "品质权重总和必须大于 0");
        }
        descFloor = descFloor == null ? Tier.WHITE : descFloor;
        effectFloor = effectFloor == null ? Tier.WHITE : effectFloor;
    }

    /**
     * 默认配置：描述层发 5 选 2（其中 1 张必是载体），效果层发 5 选 1。
     *
     * <p>为什么描述层是 2 而不是 1：只选 1 张时，玩家只能在「一把枪」和「伤害 +3」之间二选一，
     * 前者没有数值、后者没有形体，两种都不能单独成物。选 2 保证「形态 + 修饰」同时成立。
     *
     * <p>稀有度阈值 1.6 / 2.8 / 4.5 的来历：品质点数只有 1/2/4/7 四档，
     * 全白均值 1.0、全蓝 2.0、全金 4.0、全红 7.0。阈值取在相邻两档之间偏上的位置，
     * 让「全同品质」稳定落在对应稀有度上，而混搭按实际均值滑档。
     *
     * <p>史诗上限定在 4.5 而不是 5.0：5.0 时「一红两金」（均值正好 5.0）会掉回史诗，
     * 于是传说只由「两红以上」产生，实测 900 局仅 0.7% —— 玩家一个月都见不到一次。
     * 4.5 让「一红两金」进入传说，实测约 3%，仍是稀有事件但不至于灭绝。
     */
    public static BuildConfig defaults() {
        return new BuildConfig(
                5, 2, 1,
                5, 1,
                Map.of(Tier.WHITE, 38, Tier.BLUE, 32, Tier.GOLD, 22, Tier.RED, 8),
                Tier.WHITE, Tier.WHITE,
                1.6, 2.8, 4.5,
                0.15
        );
    }

    public int weightOf(Tier tier) {
        return qualityWeights.getOrDefault(tier, 0);
    }

    public int totalWeight() {
        int n = 0;
        for (Tier t : Tier.values()) {
            n += weightOf(t);
        }
        return n;
    }

    /**
     * 平均点数 → 稀有度。
     *
     * <p>用<b>平均</b>而不是总和，是因为选取张数可配：总和 14 在「选 2 张」时是全红传说，
     * 在「选 4 张」时只是全金史诗。总和不可比，均值才跨配置可比。
     */
    public Rarity rarityOf(double avgPoints) {
        if (avgPoints <= commonAvgMax) {
            return Rarity.COMMON;
        }
        if (avgPoints <= rareAvgMax) {
            return Rarity.RARE;
        }
        if (avgPoints <= epicAvgMax) {
            return Rarity.EPIC;
        }
        return Rarity.LEGENDARY;
    }

    /** 描述层总共要发几张非载体卡。 */
    public int descModifierDealCount() {
        return descDealCount - descCarrierSlots;
    }

    /** 描述层要选几张非载体卡（玩家在描述层真正做出的取舍）。 */
    public int descModifierPickCount() {
        return descPickCount - descCarrierSlots;
    }

    public BuildConfig withDescPickCount(int newPick) {
        return new BuildConfig(descDealCount, newPick, descCarrierSlots, effectDealCount, effectPickCount,
                qualityWeights, descFloor, effectFloor, commonAvgMax, rareAvgMax, epicAvgMax, extraCardHatchPct);
    }

    public BuildConfig withEffectPickCount(int newPick) {
        return new BuildConfig(descDealCount, descPickCount, descCarrierSlots, effectDealCount, newPick,
                qualityWeights, descFloor, effectFloor, commonAvgMax, rareAvgMax, epicAvgMax, extraCardHatchPct);
    }

    public BuildConfig withFloors(Tier desc, Tier effect) {
        return new BuildConfig(descDealCount, descPickCount, descCarrierSlots, effectDealCount, effectPickCount,
                qualityWeights, desc, effect, commonAvgMax, rareAvgMax, epicAvgMax, extraCardHatchPct);
    }
}
