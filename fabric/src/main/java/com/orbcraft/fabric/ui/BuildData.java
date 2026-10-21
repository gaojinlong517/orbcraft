package com.orbcraft.fabric.ui;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.orbcraft.core.draft.AffixData;
import com.orbcraft.core.draft.AffixTable;
import com.orbcraft.core.target.TargetDeck;

/**
 * 卡表与定向卡池的进程级缓存 —— 构筑入口读数据的唯一位置。
 *
 * <p>为什么放在平台层而不是 {@code core}：{@code core} 是零依赖的纯算法包，
 * 它提供的是 {@code AffixData.loadTable(json...)} 这种「给我字符串我还你表」的入口，
 * 而「去哪里找字符串」是平台层的事（开发期是 classpath 资源，将来可能是数据包）。
 *
 * <p>为什么两样都要缓存：{@link AffixTable} 加载一次要解析 1124 条词条，
 * 而 {@code BuildSession} 构造期会扫一遍全表算池子容量。每次敲命令重建一张表，
 * 就是每次构筑都多几百毫秒的服务端卡顿。
 *
 * <p>加载失败<b>直接抛</b>，不做「空表兜底」：卡表读不出来时，构筑能开但发不出牌，
 * 玩家看到的是一屏空白而不是「服务器有问题」。异常在命令入口被接住并转成中文提示。
 */
public final class BuildData {

    private static final Logger LOG = LoggerFactory.getLogger("orbcraft/build");

    private static AffixTable table;
    private static TargetDeck targetDeck;

    private BuildData() {
    }

    public static synchronized AffixTable table() {
        if (table == null) {
            table = AffixData.loadTable(
                    read("affixes.carrier.json"),
                    read("affixes.element.json"),
                    read("affixes.attribute.json"),
                    read("affixes.effect.json"));
            LOG.info("[orbcraft/build] 卡表加载完成：{} 条词条", table.size());
        }
        return table;
    }

    public static synchronized TargetDeck targetDeck() {
        if (targetDeck == null) {
            targetDeck = TargetDeck.of(AffixData.readTargetCards(read("target_cards.json")));
            LOG.info("[orbcraft/build] 定向卡池加载完成：{} 张", targetDeck.size());
        }
        return targetDeck;
    }

    /**
     * 从 classpath 读一个数据文件。
     *
     * <p>路径写死 {@code /data/} 是因为 {@code core/src/main/resources/data} 已经并进
     * 同一个 jar（见 build.gradle 的 sourceSets）。资源缺失时抛出的消息里带上完整路径，
     * 因为「打包漏了资源」和「文件名写错」在运行期长得一模一样。
     */
    private static String read(String file) {
        String path = "/data/" + file;
        try (InputStream in = BuildData.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("卡表资源不存在：" + path
                        + "（core 的 resources 没有打进 classpath）");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取卡表资源失败：" + path, e);
        }
    }
}
