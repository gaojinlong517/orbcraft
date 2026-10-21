package com.orbcraft.fabric.net;

import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.orbcraft.fabric.OrbcraftMod;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

/**
 * 网络层的装配与发送。
 *
 * <p>只注册一个 S2C 载荷。点击按钮不需要自定义 C2S：客户端直接发原版命令，
 * 服务端仍旧是唯一的状态机（见 {@link BuildScreenS2C} 的说明）。
 */
public final class OrbcraftNet {

    private static boolean registered;

    private OrbcraftNet() {
    }

    public static void init() {
        if (registered) {
            // 集成服务器双入口会各初始化一次；重复注册载荷会抛。
            return;
        }
        PayloadTypeRegistry.playS2C().register(BuildScreenS2C.ID, BuildScreenS2C.CODEC);
        registered = true;
    }

    /**
     * 推一次界面快照。
     *
     * <p>失败必须吞掉：界面是「额外的输入方式」，它坏了不能让命令跟着坏。
     * 玩家收不到界面时，聊天里那份可点击文本仍然在。
     */
    public static void sendBuildScreen(ServerPlayer player, String title, List<String[]> buttons) {
        if (player == null || title == null || title.isBlank()) {
            return;
        }
        try {
            JsonObject root = new JsonObject();
            root.addProperty("title", title);
            JsonArray arr = new JsonArray();
            for (String[] b : buttons) {
                // 约定：{标签, 命令, 色调, 图标, 副标题}，后两项可缺省
                JsonObject one = new JsonObject();
                one.addProperty("label", b[0]);
                one.addProperty("cmd", b[1]);
                one.addProperty("tone", b.length > 2 ? b[2] : "white");
                one.addProperty("icon", b.length > 3 ? b[3] : "action");
                one.addProperty("detail", b.length > 4 ? b[4] : "");
                arr.add(one);
            }
            root.add("buttons", arr);
            ServerPlayNetworking.send(player, new BuildScreenS2C(root.toString()));
        } catch (RuntimeException e) {
            // 客户端没装模组、协议没注册、连接正在断开……都走这里。
            OrbcraftMod.LOG.debug("[orbcraft] 界面快照发送失败（不影响命令）：{}", e.toString());
        }
    }
}
