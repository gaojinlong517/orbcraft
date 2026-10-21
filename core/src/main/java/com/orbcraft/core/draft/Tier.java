package com.orbcraft.core.draft;

/**
 * 词条品质。点数即预算成本 —— 这是「越强越久」在抽卡里的落点。
 *
 * <p>点数不是随手定的：四种「全同品质」正好落在四档稀有度上
 * （全白 4→普通 / 全蓝 8→稀有 / 全金 16→史诗 / 全红 28→传说），
 * 这套自洽关系是整张预算表的锚点，改任何一个都会让稀有度分布失衡。
 */
public enum Tier {

    WHITE("白", 1),
    BLUE("蓝", 2),
    GOLD("金", 4),
    RED("红", 7);

    private final String zh;
    private final int designPoints;

    Tier(String zh, int designPoints) {
        this.zh = zh;
        this.designPoints = designPoints;
    }

    public String zh() {
        return zh;
    }

    /** 设计基准点数。数据里的 points 必须与它一致，否则预算表就是坏的。 */
    public int designPoints() {
        return designPoints;
    }

    public int rank() {
        return ordinal();
    }

    /** 降一档；已是最低则返回自身。 */
    public Tier downgrade() {
        return this == WHITE ? WHITE : values()[ordinal() - 1];
    }

    /** 升一档；已是最高则返回自身。 */
    public Tier upgrade() {
        return this == RED ? RED : values()[ordinal() + 1];
    }

    public static Tier parse(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("品质不能为 null");
        }
        return switch (raw.trim().toUpperCase()) {
            case "WHITE", "COMMON" -> WHITE;
            case "BLUE", "RARE" -> BLUE;
            case "GOLD", "EPIC" -> GOLD;
            case "RED", "LEGENDARY" -> RED;
            default -> throw new IllegalArgumentException("未知品质：" + raw);
        };
    }
}
