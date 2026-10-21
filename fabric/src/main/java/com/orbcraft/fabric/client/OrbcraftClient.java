package com.orbcraft.fabric.client;

import com.orbcraft.fabric.OrbcraftMod;
import com.orbcraft.fabric.net.BuildScreenS2C;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import com.orbcraft.fabric.entity.orb.OrbEntities;
import com.orbcraft.fabric.entity.orb.OrbProjectile;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.client.renderer.entity.ThrownItemRenderer;
import org.lwjgl.glfw.GLFW;

/**
 * 客户端入口。
 *
 * <p>单独一个 entrypoint，是因为 S1 要在客户端画耐久条、S3 要注册实体渲染器 ——
 * 那些类在专用服务端上不能加载（会 {@code NoClassDefFoundError}）。把它们放进
 * {@code client} 入口，专用服务端就不会碰。
 *
 * <p>没有用 {@code splitEnvironmentSourceSets()}：那样要多一个 {@code client} sourceSet
 * 和一套 run 配置，收益只有一个「编译期拦住客户端代码混进服务端」——
 * 而这一条用入口划分已经拿到了大半。等真的出现误用再拆不迟。
 */
public final class OrbcraftClient implements ClientModInitializer {

    /**
     * 打开构筑菜单的快捷键。默认 O，玩家可以在「选项 → 控制」里改。
     *
     * <p>入门槛只有一个按键：要求玩家先记住 {@code /orb build} 这种命令，
     * 等于把功能藏起来。菜单打开后全是点击，命令仍然保留给熟悉的人。
     */
    private static KeyMapping openMenu;

    @Override
    public void onInitializeClient() {
        openMenu = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.orbcraft.build_menu",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_O,
                KeyMapping.Category.GAMEPLAY));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openMenu.consumeClick()) {
                if (client.player != null) {
                    // 发的是 status 而不是 build：menu 只是「打开当前这一屏」，
                    // 没有进行中的构筑时服务端回的就是落地页（上面有「开始构筑」按钮）。
                    // 这样按 O 永远不会误开一局新的构筑。
                    client.player.connection.sendCommand("orb status");
                }
            }
        });
        // 投掷物必须有客户端渲染器。少了这一行不是「看不见」，是整个世界渲染循环 NPE：
        // EntityRenderDispatcher.shouldRender 直接对 null 调用 —— 表现是「丢球必崩服」。
        // 用原版「飞行的物品」渲染器：球长什么样，飞出去就是什么样。
        EntityRenderers.register(OrbEntities.PROJECTILE,
                context -> new ThrownItemRenderer<OrbProjectile>(context));

        ClientPlayNetworking.registerGlobalReceiver(BuildScreenS2C.ID, (payload, context) ->
                // 必须切回客户端主线程再开界面：网络回调跑在 netty 线程上，
                // 在那里碰 Screen / 世界状态会偶发崩溃，而且崩得没有规律。
                context.client().execute(() ->
                        context.client().setScreen(new BuildScreenGui(payload.json()))));
        OrbcraftMod.LOG.info("[orbcraft] 客户端初始化完成");
    }
}
