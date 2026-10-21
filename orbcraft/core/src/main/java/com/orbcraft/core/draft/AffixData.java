package com.orbcraft.core.draft;

import com.orbcraft.core.util.Json;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 卡表加载器：JSON 文本 → {@link AffixTable} / {@link TargetCard}。
 *
 * <p>校验只做「不校验就会在运行时静默出错」的那几条：
 * <ul>
 *   <li>id 前缀必须与 layer 一致 —— 否则 family 去重和定向卡注入会错位</li>
 *   <li>四档品质必须齐全 —— 否则 roll 到缺失档会掉回白卡，且没有任何提示</li>
 *   <li>points 必须等于设计值 —— 否则预算表与稀有度阈值同时失真</li>
 * </ul>
 * 更重的检查（引用悬空、池子抽空、协同重复）交给 `tools/validate-cards.mjs`，
 * 那个脚本能跑几万次模拟，比启动期校验更彻底。
 */
public final class AffixData {

    private AffixData() {
    }

    public static AffixTable loadTable(String carrierJson, String elementJson,
                                       String attributeJson, String effectJson) {
        Map<Layer, List<Affix>> map = new EnumMap<>(Layer.class);
        map.put(Layer.CARRIER, readAffixes(carrierJson, Layer.CARRIER));
        map.put(Layer.ELEMENT, readAffixes(elementJson, Layer.ELEMENT));
        map.put(Layer.ATTRIBUTE, readAffixes(attributeJson, Layer.ATTRIBUTE));
        map.put(Layer.EFFECT, readAffixes(effectJson, Layer.EFFECT));
        return AffixTable.of(map);
    }

    public static List<Affix> readAffixes(String json, Layer expected) {
        Map<String, Object> root = Json.parseObject(json);
        List<Affix> out = new ArrayList<>();
        for (Map<String, Object> m : Json.objectListOf(root, "affixes")) {
            Affix a = toAffix(m);
            if (a.layer() != expected) {
                throw new IllegalArgumentException(
                        "词条 " + a.id() + " 的 layer 是 " + a.layer() + "，但出现在 " + expected + " 文件里");
            }
            out.add(a);
        }
        return out;
    }

    public static List<TargetCard> readTargetCards(String json) {
        Map<String, Object> root = Json.parseObject(json);
        List<TargetCard> out = new ArrayList<>();
        for (Map<String, Object> m : Json.objectListOf(root, "cards")) {
            out.add(new TargetCard(
                    Json.str(m, "id", ""),
                    Layer.valueOf(Json.str(m, "target_layer", "CARRIER")),
                    Json.str(m, "target_affix", ""),
                    Json.str(m, "quality_floor", "NONE"),
                    Json.str(m, "name", ""),
                    Json.str(m, "base_type", "ANY")
            ));
        }
        return out;
    }

    private static Affix toAffix(Map<String, Object> m) {
        String id = Json.str(m, "id", "");
        Layer layer = Layer.valueOf(Json.str(m, "layer", "ATTRIBUTE"));

        Map<Tier, TierData> tiers = new LinkedHashMap<>();
        for (Map<String, Object> t : Json.objectListOf(m, "tiers")) {
            Tier tier = Tier.parse(Json.str(t, "tier", "WHITE"));
            int points = Json.intOf(t, "points", tier.designPoints());
            if (points != tier.designPoints()) {
                throw new IllegalArgumentException(
                        "词条 " + id + " 的 " + tier + " 档点数 " + points
                                + " 与设计值 " + tier.designPoints() + " 不一致，预算表会失真");
            }
            TierData.Drawback drawback = null;
            Map<String, Object> d = Json.objectOf(t, "drawback");
            if (!d.isEmpty()) {
                drawback = new TierData.Drawback(
                        Json.str(d, "type", ""),
                        Json.doubleOf(d, "value", 0));
            }
            tiers.put(tier, new TierData(
                    tier,
                    points,
                    Json.doubleOf(t, "magnitude", 1),
                    Json.doubleOf(t, "value", 0),
                    drawback,
                    Json.str(t, "desc", "")
            ));
        }

        // 四档必须齐全：缺一档时 roll 到那一档会静默退回白卡，是最难发现的数据错误
        for (Tier tier : Tier.values()) {
            if (!tiers.containsKey(tier)) {
                throw new IllegalArgumentException(
                        "词条 " + id + " 缺少 " + tier + " 档品质数据");
            }
        }

        return new Affix(
                id,
                Json.str(m, "family", ""),
                layer,
                Json.str(m, "name", ""),
                Json.str(m, "base_type", "ANY"),
                Json.strSet(m, "carrier_allow"),
                Json.strSet(m, "carrier_deny"),
                Json.strSet(m, "element_allow"),
                Json.strSet(m, "excludes"),
                Json.intOf(m, "weight", 100),
                Json.str(m, "appearance_key", ""),
                tiers
        );
    }
}
