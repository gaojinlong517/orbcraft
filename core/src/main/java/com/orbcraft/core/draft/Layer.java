package com.orbcraft.core.draft;

/**
 * 抽卡层。顺序即 {@link #values()} 顺序，状态机按它推进。
 *
 * <p>注意：文档里常说的「6 层」是口语（含手动选基调 + 手动命名），
 * <b>真正可抽的只有 4 层</b>。这个区别决定了预算表：4 层全红 = 28 点 = 传说上限。
 */
public enum Layer {

    CARRIER("载体"),
    ELEMENT("元素"),
    ATTRIBUTE("属性"),
    EFFECT("效果");

    private final String zh;

    Layer(String zh) {
        this.zh = zh;
    }

    public String zh() {
        return zh;
    }

    public int index() {
        return ordinal();
    }

    public static Layer byIndex(int i) {
        Layer[] all = values();
        if (i < 0 || i >= all.length) {
            throw new IllegalArgumentException("层序号越界：" + i);
        }
        return all[i];
    }
}
