package com.orbcraft.fabric.server;

import java.util.function.Predicate;

import me.lucko.fabric.api.permissions.v0.Permissions;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/**
 * 管理类操作的权限判定 —— <b>用原版权限等级，不用玩家名白名单</b>。
 *
 * <p>为什么不能是白名单：白名单在「服务器开了正版验证但号被盗」时形同虚设，
 * 在「玩家改名」时静默失效，而且还要求每个调用点各维护一份名单。
 * 原版等级由服务器自己维护（{@code ops.json}、服务器控制台、RCON），
 * 我们只需要说清「这个操作要多高的等级」。
 *
 * <p><b>权限桥接（2026-10 接入）</b>：判定现在经由
 * <a href="https://github.com/lucko/fabric-permissions-api">fabric-permissions-api</a>
 * （作者 lucko，MIT 许可，以 Jar-in-Jar 方式随本模组分发）：
 * <ul>
 *   <li>服务器装了 LuckPerms 等权限插件时，按节点 {@value #PERMISSION_NODE}
 *       细粒度授权——服主可以给「只能发球、不能改配额」这样的半管理员角色；</li>
 *   <li>没有任何权限插件时，API 自动回退到原版等级 {@value #FALLBACK_LEVEL}，
 *       行为与接入前完全一致，老服主无感。</li>
 * </ul>
 *
 * <p><b>1.21.11 的坑</b>：{@code CommandSourceStack.hasPermission(int)} 已经不存在了，
 * 权限集合被重做成 {@code PermissionSet} + {@code PermissionCheck}
 * （{@code net.minecraft.server.permissions}），等级用
 * {@code PermissionLevel} 枚举表达。继续照抄 1.20 教程的 {@code hasPermission(2)}
 * 会直接编译不过。正因如此，本类不再直接触碰这些原版内部结构，统一交给
 * fabric-permissions-api 处理版本间的权限模型迁移。
 *
 * <p>本类公开出来，是给 S2 的命令层复用的 —— 管理类命令的判定只该有一份，
 * 否则会出现「发球命令查了权限、改配额命令忘了查」这种只能靠审计发现的问题。
 */
public final class AdminPermissions {

    /**
     * 权限节点。LuckPerms 侧的用法示例：
     * {@code /lp group half-admin permission set orbcraft.admin true}
     */
    public static final String PERMISSION_NODE = "orbcraft.admin";

    /**
     * 无权限插件时的回退等级：2（GAMEMASTERS，与 {@code /give}、{@code /gamemode} 同级）。
     *
     * <p>不取 1（MODERATORS，只有 {@code /kick} 之类）：能凭空发球、能改配额，
     * 等于能绕过整条「定稿才扣额度」的经济设计，应该和创造模式同权。
     */
    public static final int FALLBACK_LEVEL = 2;

    /** 兼容保留：供需要展示「等级 2」文案的调用点使用。 */
    public static final net.minecraft.server.permissions.PermissionCheck REQUIRED = Commands.LEVEL_GAMEMASTERS;

    /** 权限不足时给玩家的原文。命令层直接用它，避免各处写出不同的措辞。 */
    public static final String DENY_MESSAGE = "权限不足：这个操作需要管理员权限（等级 2，或 orbcraft.admin 节点）";

    private AdminPermissions() {
    }

    /**
     * 给 Brigadier 的 {@code .requires(...)} 用：命令在补全阶段就直接对普通玩家不可见。
     *
     * <p>由 fabric-permissions-api 的 {@code Permissions.require(...)} 实现
     * （{@code .requires(Permissions.require("...", 2))} 是它的官方推荐用法）。
     */
    public static Predicate<CommandSourceStack> requirement() {
        return Permissions.require(PERMISSION_NODE, FALLBACK_LEVEL);
    }

    /**
     * 命令体内部的二次判定（防止有人把子节点挂到没有 requires 的父节点上）。
     *
     * <p>同样走 fabric-permissions-api：有权限插件时查节点，没有时回退等级 2。
     */
    public static boolean isAdmin(CommandSourceStack source) {
        return Permissions.check(source, PERMISSION_NODE, FALLBACK_LEVEL);
    }
}
