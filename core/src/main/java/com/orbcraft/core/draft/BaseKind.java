package com.orbcraft.core.draft;

import com.orbcraft.core.spec.ProductType;

/**
 * 抽卡层 0 的「基调」。
 *
 * <p>单独建这个枚举而不是直接用 {@link ProductType}，是因为两者语义不同层级：
 * {@code ProductType} 是**玩法语义**（ENTITY 占召唤配额、NPC 有行为模式），
 * 这里的字符串是**卡表语义**（数据文件里写的是 {@code SUMMON}）。
 *
 * <p>直接混用会踩一个很隐蔽的坑：数据侧的 {@code SUMMON} 与枚举侧的 {@code ENTITY}
 * 名字不一致，按名字匹配会让召唤物基调抽到空池，而报错信息只会说「没有可用词条」。
 */
public enum BaseKind {

    EQUIPMENT("EQUIPMENT", "装备", ProductType.EQUIPMENT),
    CONSUMABLE("CONSUMABLE", "消耗品", ProductType.CONSUMABLE),
    SUMMON("SUMMON", "召唤物", ProductType.ENTITY);

    private final String wire;
    private final String zh;
    private final ProductType productType;

    BaseKind(String wire, String zh, ProductType productType) {
        this.wire = wire;
        this.zh = zh;
        this.productType = productType;
    }

    /** 数据文件里的取值。 */
    public String wire() {
        return wire;
    }

    public String zh() {
        return zh;
    }

    public ProductType toProductType() {
        return productType;
    }

    public static BaseKind from(ProductType type) {
        return switch (type) {
            case EQUIPMENT -> EQUIPMENT;
            case CONSUMABLE -> CONSUMABLE;
            case ENTITY, NPC -> SUMMON;
        };
    }

    public static BaseKind parse(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("基调不能为 null");
        }
        String v = raw.trim().toUpperCase();
        for (BaseKind k : values()) {
            if (k.wire.equals(v)) {
                return k;
            }
        }
        throw new IllegalArgumentException("未知基调：" + raw + "（合法值 EQUIPMENT / CONSUMABLE / SUMMON）");
    }
}
