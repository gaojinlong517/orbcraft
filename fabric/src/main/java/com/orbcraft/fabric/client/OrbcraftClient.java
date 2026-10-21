package com.orbcraft.fabric.client;

import com.orbcraft.fabric.OrbcraftMod;
import com.orbcraft.fabric.net.BuildScreenS2C;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

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

    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(BuildScreenS2C.ID, (payload, context) ->
                // 必须切回客户端主线程再开界面：网络回调跑在 netty 线程上，
                // 在那里碰 Screen / 世界状态会偶发崩溃，而且崩得没有规律。
                context.client().execute(() ->
                        context.client().setScreen(new BuildScreenGui(payload.json()))));
        OrbcraftMod.LOG.info("[orbcraft] 客户端初始化完成");
    }
}
