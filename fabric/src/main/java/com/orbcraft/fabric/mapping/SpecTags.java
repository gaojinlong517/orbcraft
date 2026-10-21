package com.orbcraft.fabric.mapping;

import java.util.List;
import java.util.Set;

/**
 * {@code Spec.tags()} 的读法 —— 载体 id 与元素 id 的唯一来源。
 *
 * <p>为什么单独一个类：{@code SpecSettler} 往 tags 里塞了三种东西
 * （载体短 id、元素短 id、属性键），它们长得一样，都是小写单词。
 * 如果每个消费点各自 {@code tags.get(0)} 或者 {@code tags.contains("fire")}，
 * 就会出现「装备的剑被当成召唤载体」这类错配。这里把「哪些是元素」
 * 钉成一张表，载体则必须去 {@code CarrierModelRegistry} 里查有没有登记。
 *
 * <p>注意：元素 id 在 {@code SpecSettler} 里是去前缀的短名（{@code elem.fire} → {@code fire}），
 * 因为 {@code Affix.elementId()} 做了 {@code substring}。这里按短名匹配。
 */
public final class SpecTags {

    /** 与 {@code affixes.element.json} 的 {@code elem.<id>} 一一对应。 */
    private static final Set<String> ELEMENTS =
            Set.of("lightning", "fire", "ice", "poison", "light", "dark", "rock", "wind", "physical");

    private SpecTags() {
    }

    /** 第一个元素标签；没有元素时返回 {@code null}（不是空串，调用方用 null 判断更清楚）。 */
    public static String elementOf(List<String> tags) {
        if (tags == null) {
            return null;
        }
        for (String tag : tags) {
            if (ELEMENTS.contains(tag)) {
                return tag;
            }
        }
        return null;
    }

    public static boolean hasElement(List<String> tags) {
        return elementOf(tags) != null;
    }

    /**
     * 元素 → 染色值。只对 {@code tintable} 的载体使用；
     * 给「火」一个橙红、「冰」一个青蓝，是玩家分辨元素的最省力手段
     * （外观后勤以后可以覆盖，这里只是不至于九种元素全一个颜色）。
     */
    public static Integer tintOf(String element) {
        if (element == null) {
            return null;
        }
        return switch (element) {
            case "fire" -> 0xFFFF6A2B;
            case "ice" -> 0xFF7FD8FF;
            case "lightning" -> 0xFFFFE45C;
            case "poison" -> 0xFF8CE05A;
            case "light" -> 0xFFFFF6D0;
            case "dark" -> 0xFF7A4BD0;
            case "rock" -> 0xFF9A8060;
            case "wind" -> 0xFFB8F0C8;
            case "physical" -> 0xFFC8C8C8;
            default -> null;
        };
    }
}
