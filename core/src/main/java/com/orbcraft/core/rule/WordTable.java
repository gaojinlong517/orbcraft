package com.orbcraft.core.rule;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 中文词表 + 最长优先匹配。
 *
 * 为什么不用分词库：描述都很短（十几个字），词表只有几十个词，
 * 直接 contains 扫描配合"长词优先"就足够准确，而且零依赖、零配置、可离线。
 */
public final class WordTable {

    public record Entry(String keyword, Tag tag) {
    }

    private final List<Entry> entries;

    private WordTable(List<Entry> raw) {
        this.entries = raw.stream()
                .sorted(Comparator.comparingInt((Entry e) -> e.keyword().length()).reversed())
                .toList();
    }

    /** 命中所有标签（按去重键去重，保持词表顺序）。 */
    public List<Tag> match(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        List<Tag> out = new ArrayList<>();
        for (Entry e : entries) {
            if (text.contains(e.keyword()) && seen.add(e.tag().dedupKey())) {
                out.add(e.tag());
            }
        }
        return out;
    }

    public Optional<Tag> firstOf(List<Tag> tags, Tag.Dimension dim) {
        return tags.stream().filter(t -> t.dimension() == dim).findFirst();
    }

    public List<Tag> allOf(List<Tag> tags, Tag.Dimension dim) {
        return tags.stream().filter(t -> t.dimension() == dim).toList();
    }

    public int size() {
        return entries.size();
    }

    public static WordTable standard() {
        Builder b = new Builder();

        // —— 元素 ——
        b.add(Tag.Dimension.ELEMENT, "fire", "火", "炎", "焰", "熔岩", "熔", "燃", "炽");
        b.add(Tag.Dimension.ELEMENT, "ice", "冰", "霜", "寒", "雪", "冻");
        b.add(Tag.Dimension.ELEMENT, "thunder", "雷", "电", "霆");
        b.add(Tag.Dimension.ELEMENT, "poison", "毒", "腐", "酸", "蚀");
        b.add(Tag.Dimension.ELEMENT, "light", "光", "圣", "辉");
        b.add(Tag.Dimension.ELEMENT, "dark", "暗", "影", "幽", "冥", "虚空");
        b.add(Tag.Dimension.ELEMENT, "rock", "岩", "石", "土", "山", "磐");
        b.add(Tag.Dimension.ELEMENT, "wind", "风", "飓");

        // —— 形态（决定产物类型）——
        b.add(Tag.Dimension.FORM, "sword", "剑", "刀", "刃", "长刀");
        b.add(Tag.Dimension.FORM, "bow", "弓", "弩", "箭");
        b.add(Tag.Dimension.FORM, "axe", "斧", "锤", "战锤");
        b.add(Tag.Dimension.FORM, "staff", "杖", "法杖", "魔杖");
        b.add(Tag.Dimension.FORM, "armor", "甲", "铠", "盔", "护甲");
        b.add(Tag.Dimension.FORM, "food", "苹果", "果实", "面包", "食物", "药水");
        b.add(Tag.Dimension.FORM, "dragon", "龙", "巨龙", "恶龙", "飞龙");
        b.add(Tag.Dimension.FORM, "beast", "兽", "狼", "虎", "熊", "巨兽");
        b.add(Tag.Dimension.FORM, "spirit", "精灵", "妖精", "浮灵", "元素");
        b.add(Tag.Dimension.FORM, "artisan", "铁匠", "铸造师", "铁工");
        b.add(Tag.Dimension.FORM, "merchant", "商人", "商贩", "店主");
        b.add(Tag.Dimension.FORM, "villager", "村民", "居民");
        b.add(Tag.Dimension.FORM, "traveler", "旅人", "游侠", "冒险者");

        // —— 效果 ——
        b.add(Tag.Dimension.EFFECT, "lifesteal", "吸血", "汲取", "吸魂");
        b.add(Tag.Dimension.EFFECT, "explode", "爆炸", "炸裂", "爆裂");
        b.add(Tag.Dimension.EFFECT, "haste", "加速", "疾行", "迅捷", "极速", "跑得很快");
        b.add(Tag.Dimension.EFFECT, "fly", "飞行", "翱翔", "腾空");
        b.add(Tag.Dimension.EFFECT, "invisibility", "隐身", "隐匿", "潜行");
        b.add(Tag.Dimension.EFFECT, "teleport", "传送", "闪现", "瞬移");
        b.add(Tag.Dimension.EFFECT, "heal", "治疗", "回血", "治愈", "回复");
        b.add(Tag.Dimension.EFFECT, "burn", "燃烧", "点燃", "灼烧");
        b.add(Tag.Dimension.EFFECT, "freeze", "冰冻", "冻结", "极寒");
        b.add(Tag.Dimension.EFFECT, "smite", "落雷", "召雷", "天罚");

        // —— 程度 ——
        b.add(Tag.Dimension.INTENSITY, "weak", "微", "弱", "简陋", "破旧");
        b.add(Tag.Dimension.INTENSITY, "strong", "强", "猛", "凶", "坚硬", "锐利");
        b.add(Tag.Dimension.INTENSITY, "extreme", "极", "绝", "至", "究极", "极致");
        b.add(Tag.Dimension.INTENSITY, "legend", "传说", "史诗", "神话", "毁天灭地", "无敌", "不朽");

        return new WordTable(b.build());
    }

    private static final class Builder {
        private final List<Entry> list = new ArrayList<>();

        void add(Tag.Dimension dim, String key, String... keywords) {
            for (String k : keywords) {
                list.add(new Entry(k, new Tag(dim, key)));
            }
        }

        List<Entry> build() {
            return list;
        }
    }
}
