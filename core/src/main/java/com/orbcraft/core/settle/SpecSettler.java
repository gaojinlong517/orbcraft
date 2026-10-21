package com.orbcraft.core.settle;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.orbcraft.core.draft.Affix;
import com.orbcraft.core.draft.DraftResult;
import com.orbcraft.core.draft.Layer;
import com.orbcraft.core.draft.PickedSlot;
import com.orbcraft.core.spec.BehaviorMode;
import com.orbcraft.core.spec.ProductType;
import com.orbcraft.core.spec.Spec;

/**
 * 结算器 —— 把「抽卡定稿」翻译成「可孵化、可占配额、可落地」的 {@link Spec}。
 *
 * <p>这是整条链路上此前唯一缺失的一环：{@code DraftResult} 给不出
 * {@code QuotaManager.tryConsume} 要的 Spec，也给不出 {@code HatchSession} 要的总时长。
 *
 * <p><b>数值来源约定</b>（三条隐含规则，全部来自卡表 id 的命名法）：
 * <ul>
 *   <li>{@code attr.damage.gun} → 写入 {@code attrs["damage"]}，累加该层 tier 的 value。
 *       第二段就是属性名，第三段是载体限定，不参与计算。</li>
 *   <li>{@code effect.ignite.fire.gun} → 写入 {@code attrs["effect.ignite"]}。
 *       带 {@code effect.} 前缀是为了和普通属性分开 —— 它们是触发器而不是数值，
 *       将来由效果解析器消费，{@code QuotaManager} 与外观层都应当忽略。</li>
 *   <li>{@code elem.fire} / {@code carrier.gun} 只进 tags，不产生数值。</li>
 * </ul>
 *
 * <p><b>id 的确定性</b>：同一玩家对同一个 DraftResult 重复结算，必须得到同一个 id
 * （否则重复点「定稿」会造出两件东西，而落地幂等拦不住）；不同玩家用相同种子抽出
 * 相同配方，必须得到不同 id（否则第二件永远无法兑现）。所以 id = FNV-1a(ownerKey + seed + 各层选择)。
 */
public final class SpecSettler {

    /** {@code effect.*} 在 attrs 里的前缀，供效果解析器识别。 */
    public static final String EFFECT_PREFIX = "effect.";

    private SpecSettler() {
    }

    /** 结算结果：Spec 本体 + 时长明细，便于 UI 解释「为什么等这么久」。 */
    public record Outcome(Spec spec, HatchTime.Plan hatch) {
    }

    public static Spec settle(DraftResult result, SettleRequest request) {
        return settleDetailed(result, request).spec();
    }

    public static Outcome settleDetailed(DraftResult result, SettleRequest request) {
        if (result == null) {
            throw new SettleException("DraftResult 不能为空");
        }
        if (request == null) {
            throw new SettleException("SettleRequest 不能为空");
        }

        ProductType type = result.productType();
        BehaviorMode behavior = resolveBehavior(type, request.behavior());
        HatchTime.Plan hatch = HatchTime.plan(result.rarity(), result.hatchMultiplier());

        Affix carrier = slotOf(result, Layer.CARRIER)
                .map(PickedSlot::affix)
                .orElseThrow(() -> new SettleException(
                        "缺少载体层，无法结算外观：" + result.productName()));

        Map<String, Double> attrs = new LinkedHashMap<>();
        Set<String> tags = new LinkedHashSet<>();
        List<String> fragments = new ArrayList<>();

        for (PickedSlot slot : result.slots()) {
            collect(slot, attrs, tags, fragments);
        }

        Map<String, Integer> cost = request.material().costOf(request.baseMaterials());

        return new Outcome(
                new Spec(
                        specId(result, request.ownerKey()),
                        result.productName(),
                        type,
                        result.rarity(),
                        behavior,
                        hatch.seconds(),
                        cost,
                        attrs,
                        List.copyOf(tags),
                        flavor(result, fragments),
                        0,
                        100
                ),
                hatch);
    }

    /** 只要时长，供抽卡 UI 在定稿前预览「大概要等多久」。 */
    public static HatchTime.Plan previewHatch(DraftResult result) {
        return HatchTime.plan(result.rarity(), result.hatchMultiplier());
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 行为模式校验。
     *
     * <p>装备与消耗品在 Spec 层面强制 NONE，这既是 Spec 自己的约束，也是
     * QuotaManager 判断「占不占配额」的前提；召唤物则必须明确是攻击 / 守护 / 混乱，
     * 否则 tryConsume 拿不到类别，会静默按 CHAOS 处理。
     */
    private static BehaviorMode resolveBehavior(ProductType type, BehaviorMode requested) {
        boolean staticType = type == ProductType.EQUIPMENT || type == ProductType.CONSUMABLE;
        if (staticType) {
            if (requested != BehaviorMode.NONE) {
                throw new SettleException(
                        type.zh() + "不允许指定行为模式（收到 " + requested + "），必须为 NONE");
            }
            return BehaviorMode.NONE;
        }
        if (requested == BehaviorMode.NONE) {
            throw new SettleException(
                    type.zh() + "必须指定行为模式（攻击 / 守护 / 混乱），否则无法判定统御力配额");
        }
        return requested;
    }

    private static void collect(PickedSlot slot, Map<String, Double> attrs,
                                Set<String> tags, List<String> fragments) {
        Affix affix = slot.affix();
        Layer layer = slot.layer();
        double value = slot.tier().value();

        switch (layer) {
            case CARRIER -> {
                String id = affix.carrierId();
                if (id != null) {
                    tags.add(id);
                }
            }
            case ELEMENT -> {
                String id = affix.elementId();
                if (id != null) {
                    tags.add(id);
                }
            }
            case ATTRIBUTE -> {
                String key = segment(affix.id(), 1);
                attrs.merge(key, value, Double::sum);
                tags.add(key);
            }
            case EFFECT -> {
                String key = segment(affix.id(), 1);
                attrs.merge(EFFECT_PREFIX + key, value, Double::sum);
                tags.add(EFFECT_PREFIX + key);
            }
        }
        fragments.add(affix.name() + slot.tier().tier().zh());
    }

    /** 取 id 的第 index 段；不足时返回整串，避免数据短缺时抛空指针。 */
    private static String segment(String id, int index) {
        String[] parts = id.split("\\.");
        return parts.length > index ? parts[index] : id;
    }

    private static Optional<PickedSlot> slotOf(DraftResult result, Layer layer) {
        return result.slots().stream().filter(s -> s.layer() == layer).findFirst();
    }

    private static String flavor(DraftResult result, List<String> fragments) {
        return result.rarity().zh() + "·" + result.points() + "pt — " + String.join("，", fragments);
    }

    /**
     * FNV-1a 64 位哈希。用它而不是 {@code String.hashCode()} 是因为后者只有 32 位，
     * 几千件产物就有可观碰撞概率；也不用 {@code UUID.randomUUID()}，因为那会破坏
     * 「同一结果重复结算得同一 id」的幂等前提。
     */
    static String specId(DraftResult result, String ownerKey) {
        StringBuilder sb = new StringBuilder(128);
        sb.append(ownerKey).append('|')
                .append(result.seed()).append('|')
                .append(result.baseKind().wire()).append('|')
                .append(result.productName());
        for (PickedSlot slot : result.slots()) {
            sb.append('|').append(slot.affix().id()).append('#').append(slot.tier().tier().name());
        }
        long hash = 0xcbf29ce484222325L;
        for (byte b : sb.toString().getBytes(StandardCharsets.UTF_8)) {
            hash ^= (b & 0xff);
            hash *= 0x100000001b3L;
        }
        return "spec-" + Long.toHexString(hash);
    }
}
