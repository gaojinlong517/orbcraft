package com.orbcraft.core.build;

import com.orbcraft.core.draft.Tier;
import com.orbcraft.core.spec.Rarity;

import java.util.Map;

/**
 * 构筑流程的全部可调参数。
 *
 * <h2>四层，每层一对「候选数 / 选取数」</h2>
 * <pre>
 * 类型层  3 选 1（固定，不可配）
 * 形态层  shapeDealCount    选 SHAPE_PICK
 * 修饰层  modifierDealCount 选 modifierPickCount
 * 效果层  effectDealCount   选 effectPickCount
 * </pre>
 *
 * <p><b>候选数与选取数是两个独立的量，不能合并。</b>
 * 需求里「共 5 张卡牌」说的是<b>候选</b>张数（玩家看到 5 张），
 * 而玩家最终拿走几张是另一件事。合成一个 {@code drawCount} 会直接锁死
 * 「5 张里挑 1 张」这种最基本的手感，也无法做「困难模式发 3 张」。
 *
 * <p><b>没有预算，也没有点数扣减。</b> 这是与 {@code draft.DraftConfig}
 * 最根本的区别：那边 {@code budget} 是硬上限，选牌要够点数才买得起；
 * 这里玩家能选什么只由<b>抽到了什么</b>决定，选哪张不影响任何资源。
 * 强度控制因此从「你投入多少点数」转成「你抽到多好的品质」，
 * 也就是需求里那条「非交易型随机构筑」。
 *
 * @param shapeDealCount     形态层候选张数（默认 5）
 * @param modifierDealCount  修饰层候选张数（默认 5）
 * @param modifierPickCount  修饰层必须选取张数（默认 1）
 * @param effectDealCount    效果层候选张数（默认 5）
 * @param effectPickCount    效果层必须选取张数（默认 1）
 * @param qualityWeights     品质抽取权重
 * @param shapeFloor         形态层保底品质（默认白 = 不保底）
 * @param modifierFloor      修饰层保底品质
 * @param effectFloor        效果层保底品质
 * @param commonAvgMax       平均点数不超过它 → 普通
 * @param rareAvgMax         平均点数不超过它 → 稀有
 * @param epicAvgMax         平均点数不超过它 → 史诗，超过即传说
 * @param extraCardHatchPct  每多带 1 张卡，孵化时长额外增加的百分比（默认 0.15）
 */
public record BuildConfig(
        int shapeDealCount,
        int modifierDealCount,
        int modifierPickCount,
        int effectDealCount,
        int effectPickCount,
        Map<Tier, Integer> qualityWeights,
        Tier shapeFloor,
        Tier modifierFloor,
        Tier effectFloor,
        double commonAvgMax,
        double rareAvgMax,
        double epicAvgMax,
        double extraCardHatchPct
) {

    /** 判断候选张数的合理区间：少于 3 张不构成选择，多于 9 张界面放不下。 */
    public static final int MIN_DEAL = 3;
    public static final int MAX_DEAL = 9;

    /**
     * 形态层固定只选 1 张 —— <b>不是懒得做成可配，是语义上不能多选。</b>
     *
     * <p>一件产物只有一个形体：一张外观 key、一套动画、一个碰撞箱。
     * 允许选 2 张形态，{@code Composition} 就得回答「枪 + 法杖到底是哪个模型」，
     * 而这个问题在渲染层没有正确答案。所以这里不给开关，只给常量。
     *
     * <p>要「一次造两件」是另一个需求（两件独立产物），不该靠放宽这里实现。
     */
    public static final int SHAPE_PICK = 1;

    public BuildConfig {
        checkDealRange("shapeDealCount", shapeDealCount);
        checkDealRange("modifierDealCount", modifierDealCount);
        checkDealRange("effectDealCount", effectDealCount);

        if (modifierPickCount < 1 || modifierPickCount > modifierDealCount) {
            throw new BuildException(BuildException.Code.BAD_CONFIG,
                    "modifierPickCount 应在 1~" + modifierDealCount + " 之间：" + modifierPickCount);
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
        shapeFloor = shapeFloor == null ? Tier.WHITE : shapeFloor;
        modifierFloor = modifierFloor == null ? Tier.WHITE : modifierFloor;
        effectFloor = effectFloor == null ? Tier.WHITE : effectFloor;
    }

    private static void checkDealRange(String name, int value) {
        if (value < MIN_DEAL || value > MAX_DEAL) {
            throw new BuildException(BuildException.Code.BAD_CONFIG,
                    name + " 应在 " + MIN_DEAL + "~" + MAX_DEAL + " 之间：" + value);
        }
    }

    /**
     * 默认配置：三层各发 5 张，每层选 1 张。
     *
     * <p>形态与修饰拆成两层而不是「5 张里选 2 张」，是因为后者的两个选取位
     * 有隐含依赖：修饰池必须<b>先知道</b>形态才能过滤。混在一层发牌时，
     * 玩家看到的 4 张修饰是按「候选里那张载体」过滤的 —— 如果他最终选了另一张，
     * 那 4 张里可能有一半根本不适用于他选的形态。拆层之后，
     * 修饰牌一定是在形态确认后才生成的，永远不会出现读完才发现对不上的情况。
     *
     * <p>稀有度阈值 1.6 / 2.8 / 4.5 的来历：品质点数只有 1/2/4/7 四档，
     * 全白均值 1.0、全蓝 2.0、全金 4.0、全红 7.0。阈值取在相邻两档之间偏上的位置，
     * 让「全同品质」稳定落在对应稀有度上，而混搭按实际均值滑档。
     *
     * <p>史诗上限定在 4.5 而不是 5.0：5.0 时「一红两金」（均值正好 5.0）会掉回史诗，
     * 于是传说只由「两红以上」产生，实测 900 局仅 0.7% —— 玩家一个月都见不到一次。
     * 4.5 让「一红两金」进入传说，实测约 4%，仍是稀有事件但不至于灭绝。
     */
    public static BuildConfig defaults() {
        return new BuildConfig(
                5, 5, 1,
                5, 1,
                Map.of(Tier.WHITE, 38, Tier.BLUE, 32, Tier.GOLD, 22, Tier.RED, 8),
                Tier.WHITE, Tier.WHITE, Tier.WHITE,
                1.6, 2.8, 4.5,
                0.15
        );
    }

    /** 形态层固定选取数。与其它层保持同一访问方式，UI 不必为它写特例。 */
    public int shapePickCount() {
        return SHAPE_PICK;
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

    public BuildConfig withModifierPickCount(int newPick) {
        return new BuildConfig(shapeDealCount, modifierDealCount, newPick, effectDealCount, effectPickCount,
                qualityWeights, shapeFloor, modifierFloor, effectFloor,
                commonAvgMax, rareAvgMax, epicAvgMax, extraCardHatchPct);
    }

    public BuildConfig withEffectPickCount(int newPick) {
        return new BuildConfig(shapeDealCount, modifierDealCount, modifierPickCount, effectDealCount, newPick,
                qualityWeights, shapeFloor, modifierFloor, effectFloor,
                commonAvgMax, rareAvgMax, epicAvgMax, extraCardHatchPct);
    }

    public BuildConfig withDealCounts(int shape, int modifier, int effect) {
        return new BuildConfig(shape, modifier, modifierPickCount, effect, effectPickCount,
                qualityWeights, shapeFloor, modifierFloor, effectFloor,
                commonAvgMax, rareAvgMax, epicAvgMax, extraCardHatchPct);
    }

    public BuildConfig withFloors(Tier shape, Tier modifier, Tier effect) {
        return new BuildConfig(shapeDealCount, modifierDealCount, modifierPickCount, effectDealCount, effectPickCount,
                qualityWeights, shape, modifier, effect,
                commonAvgMax, rareAvgMax, epicAvgMax, extraCardHatchPct);
    }
}
