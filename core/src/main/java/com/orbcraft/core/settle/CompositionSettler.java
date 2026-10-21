package com.orbcraft.core.settle;

import com.orbcraft.core.build.Composition;
import com.orbcraft.core.draft.DraftCard;
import com.orbcraft.core.draft.Layer;
import com.orbcraft.core.draft.PickedSlot;
import com.orbcraft.core.spec.BehaviorMode;
import com.orbcraft.core.spec.ProductType;
import com.orbcraft.core.spec.Spec;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 构筑结果结算器 —— 把 {@link Composition} 翻译成可孵化、可占地、可落地的 {@link Spec}。
 *
 * <p>与 {@link SpecSettler} 的分工：
 * <ul>
 *   <li>{@code SpecSettler} 吃旧的 {@code DraftResult}（带预算点数、带残损球消耗）</li>
 *   <li>本类吃新的 {@code Composition}（无任何资源字段）</li>
 * </ul>
 * 两者共用同一套「词条 id → attrs / tags」的映射规则（{@code SpecSettler.collect}），
 * 因为那套规则属于卡表命名法，与抽卡形态无关。复制一份的话，
 * 将来改 id 规则必然漏掉一处。
 *
 * <p><b>id 的确定性</b>：同一玩家对同一个 Composition 重复结算必须得到同一个 id
 * （否则重复点「定稿」会造出两件），不同玩家用相同种子抽出相同配方必须得到不同 id
 * （否则第二件永远无法兑现）。所以 id = FNV-1a(ownerKey + seed + 类型 + 每张卡)。
 */
public final class CompositionSettler {

    private CompositionSettler() {
    }

    /** 结算结果：Spec 本体 + 时长明细。 */
    public record Outcome(Spec spec, HatchTime.Plan hatch) {
    }

    /** 用默认名（元素·载体）结算。 */
    public static Spec settle(Composition composition, SettleRequest request) {
        return settle(composition, request, defaultName(composition));
    }

    public static Spec settle(Composition composition, SettleRequest request, String displayName) {
        return settleDetailed(composition, request, displayName).spec();
    }

    public static Outcome settleDetailed(Composition composition, SettleRequest request) {
        return settleDetailed(composition, request, defaultName(composition));
    }

    public static Outcome settleDetailed(Composition composition, SettleRequest request, String displayName) {
        if (composition == null) {
            throw new SettleException("Composition 不能为空");
        }
        if (request == null) {
            throw new SettleException("SettleRequest 不能为空");
        }

        ProductType type = composition.kind().toProductType();
        BehaviorMode behavior = SpecSettler.resolveBehavior(type, request.behavior());
        HatchTime.Plan hatch = HatchTime.plan(composition.rarity(), composition.hatchMultiplier());

        Map<String, Double> attrs = new LinkedHashMap<>();
        Set<String> tags = new LinkedHashSet<>();
        List<String> fragments = new ArrayList<>();

        for (PickedSlot slot : composition.slots()) {
            SpecSettler.collect(slot, attrs, tags, fragments);
        }

        Map<String, Integer> cost = request.material().costOf(request.baseMaterials());
        String finalName = normalizeName(displayName, composition);

        return new Outcome(
                new Spec(
                        specId(composition, request.ownerKey()),
                        finalName,
                        type,
                        composition.rarity(),
                        behavior,
                        hatch.seconds(),
                        cost,
                        attrs,
                        List.copyOf(tags),
                        flavor(composition, fragments),
                        0,
                        100
                ),
                hatch);
    }

    /** 只要时长，供构筑完成前预览「大概要等多久」。 */
    public static HatchTime.Plan previewHatch(Composition composition) {
        return HatchTime.plan(composition.rarity(), composition.hatchMultiplier());
    }

    // ------------------------------------------------------------------ 内部

    /** 默认名：元素·载体（如「雷·枪」）；没有元素时就是载体名。 */
    public static String defaultName(Composition composition) {
        String element = composition.cards().stream()
                .filter(c -> c.affix().layer() == Layer.ELEMENT)
                .map(c -> c.affix().name())
                .findFirst().orElse("");
        String carrier = composition.carrierCard()
                .map(c -> c.affix().name())
                .orElse("产物");
        return element.isEmpty() ? carrier : element + "·" + carrier;
    }

    private static String normalizeName(String name, Composition composition) {
        String n = name == null || name.isBlank() ? defaultName(composition) : name.trim();
        return n.length() > 24 ? n.substring(0, 24) : n;
    }

    private static String flavor(Composition composition, List<String> fragments) {
        return composition.rarity().zh() + "·" + String.format("%.2f", composition.avgPoints())
                + "pt — " + String.join("，", fragments);
    }

    /**
     * FNV-1a 64 位。不用 {@code hashCode()}（32 位，几千件就有可观碰撞），
     * 也不用随机 UUID（会破坏「同一结果重复结算得同一 id」）。
     */
    static String specId(Composition composition, String ownerKey) {
        StringBuilder sb = new StringBuilder(128);
        sb.append(ownerKey).append('|')
                .append(composition.seed()).append('|')
                .append(composition.kind().wire()).append('|')
                .append(composition.rarity().name());
        for (DraftCard c : composition.cards()) {
            sb.append('|').append(c.affix().id()).append('#').append(c.tier().tier().name());
        }
        long hash = 0xcbf29ce484222325L;
        for (byte b : sb.toString().getBytes(StandardCharsets.UTF_8)) {
            hash ^= (b & 0xff);
            hash *= 0x100000001b3L;
        }
        return "spec-" + Long.toHexString(hash);
    }
}
