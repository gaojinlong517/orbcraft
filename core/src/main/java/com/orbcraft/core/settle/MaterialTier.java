package com.orbcraft.core.settle;

import java.util.LinkedHashMap;
import java.util.Map;

import com.orbcraft.core.draft.DraftConfig;

/**
 * 材料投入档 —— 决定抽卡预算，也就决定了这件产物「最好能有多好」。
 *
 * <p><b>这个枚举是在修一个数值结构问题，不只是加个配置项。</b>
 * 固定预算 28 时，贪心玩家抽完四层平均 18.8 点，实测传说占 57%、普通占 0%：
 * 稀有度变成了「玩家有多克制」，而不是「他投入了多少」。与此同时材料只决定
 * 「能不能造」，不影响「造得多好」，袭城掉稀有材料的价值感就立不起来。
 *
 * <p>改成投入分级后，两个问题一起解决 —— 预算就是材料投入的镜像：
 * <pre>
 *   试作 ×1   预算  6   封顶 稀有      （4 层全白 = 4 点 → 普通；混两张蓝 = 6 点 → 稀有）
 *   标准 ×2   预算 12   封顶 史诗
 *   精制 ×4   预算 20   封顶 传说
 *   大师 ×8   预算 28   全开          （4 层全红正好 28 点）
 * </pre>
 *
 * <p>随机性并没有消失，只是换了位置：预算决定<b>上限</b>，抽牌决定<b>实际落在哪</b>。
 * 花 8 倍材料也可能只抽出一件平庸的传说，但那件传说的值日时间仍然是 10 分钟起 ——
 * 「这个传说好不好用」仍然是运气，「我配不配有一个传说」则由投入决定。
 *
 * <p>阈值 5 / 11 / 18 沿用 {@link DraftConfig} 已校准的档位，没有跟着预算等比缩放，
 * 因为它们是「点数 → 稀有度」的全局标尺。如果每档预算配一套阈值，跨档比较就失去了意义。
 */
public enum MaterialTier {

    /** 试作：日常试手，几乎不可能出货。 */
    TRIAL(1, 6, "试作"),
    /** 标准：主力档位，稳定出稀有、偶尔史诗。 */
    STANDARD(2, 12, "标准"),
    /** 精制：能摸到传说下沿。 */
    REFINED(4, 20, "精制"),
    /** 大师：只有全红才配得上，也才到得了 28 点。 */
    MASTERWORK(8, 28, "大师");

    private final int materialMultiplier;
    private final int budget;
    private final String zh;

    MaterialTier(int materialMultiplier, int budget, String zh) {
        this.materialMultiplier = materialMultiplier;
        this.budget = budget;
        this.zh = zh;
    }

    public int materialMultiplier() {
        return materialMultiplier;
    }

    public int budget() {
        return budget;
    }

    public String zh() {
        return zh;
    }

    /** 该档对应的抽卡配置。除预算外全部沿用默认值。 */
    public DraftConfig configFor() {
        return DraftConfig.defaults().withBudget(budget);
    }

    /**
     * 把「一份基础配方」放大成该档的实际材料消耗。
     *
     * @param baseMaterials 基础配方，例如 {@code {"iron": 4, "crystal": 1}}
     * @return 每项乘以 {@link #materialMultiplier()} 后的新表；入参为空则返回空表
     */
    public Map<String, Integer> costOf(Map<String, Integer> baseMaterials) {
        if (baseMaterials == null || baseMaterials.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : baseMaterials.entrySet()) {
            int n = e.getValue() == null ? 0 : e.getValue();
            if (n > 0) {
                out.put(e.getKey(), n * materialMultiplier);
            }
        }
        return Map.copyOf(out);
    }

    /** 由预算反查档位，用于旧存档或 UI 回填。 */
    public static MaterialTier fromBudget(int budget) {
        for (MaterialTier t : values()) {
            if (t.budget == budget) {
                return t;
            }
        }
        throw new SettleException("未知预算档：" + budget + "（合法值 6 / 12 / 20 / 28）");
    }
}
