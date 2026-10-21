/**
 * 服务端规则层 —— 「五个客户端各说各话时，以谁为准」。
 *
 * <p>这个包只做三件别处不该做的事：
 * <ol>
 *   <li><b>时间推进</b>：{@link com.orbcraft.fabric.server.HatchRuntime} 是
 *       {@code IncubationService.tick(nowTick)} 在全项目里<b>唯一</b>的调用点。
 *       推进点重复的后果不是性能，而是同一张票据被两次判定为「本次新就绪」，
 *       玩家聊天栏里出现两条一模一样的完成提示 —— 看起来像刷屏 bug，实际是 tick 重入。
 *       注意它<b>只推进、不持有</b>：孵化状态的唯一实例归主线
 *       {@code com.orbcraft.fabric.runtime.OrbcraftRuntime.hatch()}，
 *       S2 提交票、S1 投掷、S4 恢复、本包推进必须落在同一份状态上。</li>
 *   <li><b>越权拦截</b>：{@link com.orbcraft.fabric.server.ThrowGuard} 在
 *       {@code ItemEvents.USE}（{@code Item.use} 之前）问一次
 *       {@code OrbItemContract.checkThrow}，拒绝时给中文原因并吃掉这次使用。
 *       判定逻辑本身在 S1 的契约实现里，这里只负责「拦在最早的位置」。</li>
 *   <li><b>权限</b>：{@link com.orbcraft.fabric.server.AdminPermissions} 用原版权限等级，
 *       不用玩家名白名单 —— 白名单在改名的服务器上是「谁都能用」，在正版号被盗时是「谁都能用」。</li>
 * </ol>
 *
 * <p><b>为什么不在这里改票据状态。</b> {@code IncubationService} 的方法全部
 * {@code synchronized} 且自带状态机（CHARGING → READY → THROWN → DEPLOYED）。
 * 事件回调里绕过去直接改字段，必然制造「扣了配额但没有票」或「票已就绪但配额没算」这类
 * 无法回滚的中间态。本包的所有写入都只经由 service 的公有方法。
 *
 * <p><b>依赖方向</b>：本包依赖 {@code core}（纯算法）与 {@code contract}（三个契约），
 * 不依赖任何其他路的具体实现 —— 全部通过 {@link com.orbcraft.fabric.server.ServiceLookup}
 * 在运行期取。S1/S3/S4 谁先接上，功能就自动从「跳过」变成「生效」。
 */
package com.orbcraft.fabric.server;
