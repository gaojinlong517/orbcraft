package com.orbcraft.core.draft;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 四层词条表 —— 卡表的内存形态。
 *
 * <p>构造后不可变、可并发读。加载校验不通过会直接抛异常（**服务端起不来，
 * 好过线上抽到空池**）。
 */
public final class AffixTable {

    private final Map<Layer, List<Affix>> byLayer;
    private final Map<String, Affix> byId;

    private AffixTable(Map<Layer, List<Affix>> byLayer) {
        this.byLayer = Map.copyOf(byLayer);
        Map<String, Affix> ids = new LinkedHashMap<>();
        for (List<Affix> list : byLayer.values()) {
            for (Affix a : list) {
                Affix prev = ids.put(a.id(), a);
                if (prev != null) {
                    throw new IllegalArgumentException(
                            "affix id 重复：" + a.id() + "（数据生成器应按 id 去重）");
                }
            }
        }
        this.byId = Map.copyOf(ids);
    }

    public static AffixTable of(Map<Layer, List<Affix>> byLayer) {
        Map<Layer, List<Affix>> copy = new LinkedHashMap<>();
        for (Layer l : Layer.values()) {
            List<Affix> list = byLayer.getOrDefault(l, List.of());
            copy.put(l, List.copyOf(list));
        }
        return new AffixTable(copy);
    }

    public List<Affix> layer(Layer layer) {
        return byLayer.getOrDefault(layer, List.of());
    }

    public Optional<Affix> findById(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    public int size() {
        int n = 0;
        for (List<Affix> list : byLayer.values()) {
            n += list.size();
        }
        return n;
    }

    public int size(Layer layer) {
        return layer(layer).size();
    }

    /** 粗略的「单载体单层最窄池子」—— 版本验收看的应该是这个，不是总条数。 */
    public int narrowestPool(Layer layer, Collection<String> carrierIds) {
        int min = Integer.MAX_VALUE;
        for (String c : carrierIds) {
            int n = 0;
            for (Affix a : layer(layer)) {
                if (a.allowsCarrier(c)) {
                    n++;
                }
            }
            min = Math.min(min, n);
        }
        return min == Integer.MAX_VALUE ? 0 : min;
    }

    /** 各层的族数量 —— 抽 5 张不重复牌的理论上限。 */
    public int familyCount(Layer layer) {
        List<String> fams = new ArrayList<>();
        for (Affix a : layer(layer)) {
            if (!fams.contains(a.family())) {
                fams.add(a.family());
            }
        }
        return fams.size();
    }
}
