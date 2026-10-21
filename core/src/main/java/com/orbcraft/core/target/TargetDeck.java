package com.orbcraft.core.target;

import com.orbcraft.core.draft.BaseKind;
import com.orbcraft.core.draft.Layer;
import com.orbcraft.core.draft.TargetCard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 按类型切分好的定向卡池。
 *
 * <p>背景：卡表里 44 张定向卡混在一起，CARRIER 层的 35 张各自绑定一种 {@code base_type}
 * （12 装备 / 10 消耗品 / 13 召唤物），ELEMENT 层的 9 张 {@code base_type="ANY"} 对三类通用。
 * 直接把它们摊平给玩家看，会出现「想抽定向·猎鹰却点了定向·剑」的错配 —— 配额一旦按
 * 笼统计数（旧的「每日 3 次」），浪费就是不可撤销的。所以卡池必须先分类型。
 *
 * <p>两条排序约定，UI 依赖它们做固定展示位：
 * <ol>
 *   <li>{@link #forKind} 先出 CARRIER 层，后出 ELEMENT 层；</li>
 *   <li>同一组内保持 {@link #of} 的输入顺序（即数据文件顺序）。</li>
 * </ol>
 * 顺序稳定意味着同一份卡表在任何设备上渲染出同一个列表，不需要前端再排一次。
 *
 * <p>不可变：构造后所有集合都是只读视图。卡池是全局单例量，一旦有人能改它，
 * 「玩家 A 的操作影响玩家 B 的按钮」这种 bug 会极难复现。
 */
public final class TargetDeck {

    private final List<TargetCard> all;
    private final Map<String, TargetCard> byId;
    private final Map<BaseKind, List<TargetCard>> byKind;
    private final Map<Layer, Integer> byLayer;

    private TargetDeck(List<TargetCard> all,
                       Map<String, TargetCard> byId,
                       Map<BaseKind, List<TargetCard>> byKind,
                       Map<Layer, Integer> byLayer) {
        this.all = all;
        this.byId = byId;
        this.byKind = byKind;
        this.byLayer = byLayer;
    }

    /**
     * 从扁平卡表建池。
     *
     * @throws TargetException {@code CARD_DUPLICATED} id 重复；{@code BAD_ARGUMENT} 参数为 null
     */
    public static TargetDeck of(List<TargetCard> cards) {
        if (cards == null) {
            throw new TargetException(TargetException.Code.BAD_ARGUMENT, "卡表不能为 null");
        }
        List<TargetCard> snapshot = new ArrayList<>(cards.size());
        Map<String, TargetCard> byId = new LinkedHashMap<>();
        Map<Layer, Integer> byLayer = new EnumMap<>(Layer.class);
        for (Layer layer : Layer.values()) {
            byLayer.put(layer, 0);
        }
        for (TargetCard card : cards) {
            if (card == null) {
                throw new TargetException(TargetException.Code.BAD_ARGUMENT, "卡表中存在 null 元素");
            }
            TargetCard previous = byId.putIfAbsent(card.id(), card);
            if (previous != null) {
                throw new TargetException(TargetException.Code.CARD_DUPLICATED,
                        "定向卡 id 重复：" + card.id());
            }
            snapshot.add(card);
            byLayer.merge(card.targetLayer(), 1, Integer::sum);
        }
        snapshot = List.copyOf(snapshot);

        Map<BaseKind, List<TargetCard>> byKind = new EnumMap<>(BaseKind.class);
        for (BaseKind kind : BaseKind.values()) {
            byKind.put(kind, poolOf(snapshot, kind));
        }
        return new TargetDeck(
                snapshot,
                Collections.unmodifiableMap(new LinkedHashMap<>(byId)),
                Collections.unmodifiableMap(byKind),
                Collections.unmodifiableMap(byLayer));
    }

    /**
     * 某一类型的可用卡池：CARRIER 层按 {@code base_type} 配对，ELEMENT 层对三类都通用。
     *
     * <p>判断 ELEMENT 层时只看 {@link Layer}，不看 {@code base_type}：层是语义，
     * {@code base_type="ANY"} 是数据侧对这件事实的重复表述。以层为准，
     * 数据里漏写 ANY 也不会让 9 张元素卡突然从某个池子里消失。
     *
     * <p>ATTRIBUTE / EFFECT 层的定向卡不进入任何类型池（当前数据没有），
     * 但仍能通过 {@link #find} 命中，方便上层给出「这张卡不该按类型发放」的提示。
     */
    private static List<TargetCard> poolOf(List<TargetCard> cards, BaseKind kind) {
        List<TargetCard> out = new ArrayList<>();
        for (TargetCard card : cards) {
            if (card.targetLayer() == Layer.CARRIER && matchesBaseType(card, kind)) {
                out.add(card);
            }
        }
        for (TargetCard card : cards) {
            if (card.targetLayer() == Layer.ELEMENT) {
                out.add(card);
            }
        }
        return List.copyOf(out);
    }

    private static boolean matchesBaseType(TargetCard card, BaseKind kind) {
        String baseType = card.baseType();
        return baseType != null && kind.wire().equalsIgnoreCase(baseType.trim());
    }

    /**
     * 某一类型的定向卡池，先 CARRIER 后 ELEMENT，各自保持卡表顺序。
     *
     * @return 只读列表
     */
    public List<TargetCard> forKind(BaseKind kind) {
        return byKind.get(requireKind(kind));
    }

    /** 这张卡是否属于该类型的池子。{@link TargetPlan} 用它做 WRONG_KIND 判定。 */
    public boolean offers(BaseKind kind, TargetCard card) {
        if (card == null) {
            throw new TargetException(TargetException.Code.BAD_ARGUMENT, "定向卡不能为 null");
        }
        return byKind.get(requireKind(kind)).contains(card);
    }

    /** 按 id 找卡；null 或未命中都返回空，不做异常。 */
    public Optional<TargetCard> find(String cardId) {
        if (cardId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byId.get(cardId));
    }

    /** 卡表总张数。 */
    public int size() {
        return all.size();
    }

    /**
     * 各层张数。四个层都出现在结果里（没有卡片的层给 0），
     * 这样 UI 可以直接遍历 {@link Layer#values()} 取值而不用判空。
     */
    public Map<Layer, Integer> sizeByLayer() {
        return byLayer;
    }

    /** 全部卡片，卡表顺序，只读。 */
    public List<TargetCard> cards() {
        return all;
    }

    private static BaseKind requireKind(BaseKind kind) {
        if (kind == null) {
            throw new TargetException(TargetException.Code.BAD_ARGUMENT, "基调不能为 null");
        }
        return kind;
    }
}
