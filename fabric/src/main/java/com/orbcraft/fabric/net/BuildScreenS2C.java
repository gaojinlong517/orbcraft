package com.orbcraft.fabric.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 服务端 → 客户端的「构建界面快照」。
 *
 * <p><b>为什么载荷是一个 JSON 字符串，而不是一堆并列字段。</b> 这份数据的形状会随玩法变
 * （这一层是 3 个类型、下一层是 5 张牌、最后一屏是 2 个动作），用固定字段的
 * {@code StreamCodec.composite} 表达，每次加一层就要改协议、且旧客户端会读错位。
 * 一个字符串 + 两端各自的解释，改动只发生在两端各自的解析里。
 *
 * <p><b>为什么不做双向协议。</b> 点击按钮只需要发一条玩家命令（{@code orb card 2}），
 * 走原版命令通道即可，不需要自定义 C2S 载荷。这样服务端仍然是唯一的状态机，
 * 界面只是「命令的另一种输入方式」—— 命令还能用，界面挂了也不影响玩法。
 *
 * @param json 形如 {@code {"title":"…","buttons":[{"label":"…","cmd":"…","tone":"…"}]}}
 */
public record BuildScreenS2C(String json) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<BuildScreenS2C> ID =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("orbcraft", "build_screen"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BuildScreenS2C> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, BuildScreenS2C::json,
                    BuildScreenS2C::new);

    @Override
    public CustomPacketPayload.Type<BuildScreenS2C> type() {
        return ID;
    }
}
