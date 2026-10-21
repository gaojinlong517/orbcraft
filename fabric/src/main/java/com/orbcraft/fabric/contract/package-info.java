/**
 * 三路并行契约 —— 六个人共用的唯一接口面。
 *
 * <p><b>这个包只读。</b> 除主线外任何人不得修改本包内文件的签名。
 * 需要加方法时找主线，由主线评估后再改，并同步更新
 * {@code tools/contracts.lock}（否则 {@code tools/verify-all.sh} 会红）。
 * 派单细节见 {@code docs/并行派单-S1-S5.md}。
 *
 * <p>为什么这么严：上一轮并行开发里 {@code Composition} 差点被改签名，
 * 引用它的三个模块同时编译中断。契约漂移的代价不是「改一行」，
 * 而是所有人一起停下来等一个人。
 *
 * <h2>三个契约与责任边界</h2>
 * <table border="1">
 *   <caption>谁实现、谁调用</caption>
 *   <tr><th>契约</th><th>实现方</th><th>调用方</th><th>管什么</th></tr>
 *   <tr>
 *     <td>{@link com.orbcraft.fabric.contract.OrbItemContract}</td>
 *     <td>S1 orb-item</td><td>S2 / S5 / S1</td>
 *     <td>票据 ↔ 球物品；能否投掷（含归属）</td>
 *   </tr>
 *   <tr>
 *     <td>{@link com.orbcraft.fabric.contract.SummonSpawner}</td>
 *     <td>S3 spawner</td><td>S1 / S2</td>
 *     <td>Spec → 实体 / 属性 / AI；归属查询</td>
 *   </tr>
 *   <tr>
 *     <td>{@link com.orbcraft.fabric.contract.PlayerStore}</td>
 *     <td>S4 persistence</td><td>S1 / S2 / S3 / S5</td>
 *     <td>配额 + 在途票据 + 定向账本的持久化；票据 NBT 的唯一布局</td>
 *   </tr>
 * </table>
 *
 * <p>{@link com.orbcraft.fabric.contract.Services} 是运行期取用点，归主线所有。
 * 依赖接口而不是依赖实现类，才能让五路真正并行 —— 谁先写完都不会挡住别人编译。
 *
 * <h2>三条不成文但会踩的规矩</h2>
 * <ol>
 *   <li><b>不要在这里再写一遍 core 已有什么。</b> 外观能力等级来自
 *       {@code ModelBackend.capability()}，配额与时长来自 {@code IncubationService}。
 *       先 grep 再动手。</li>
 *   <li><b>失败用返回值表达，不要抛异常</b>（投掷判定、生成、读存档都在事件回调里）。
 *       异常只留给「参数写错了」这种真正的 bug。</li>
 *   <li><b>所有给玩家看的原因都必须是中文短句</b>，不能是状态码 ——
 *       它最终会出现在聊天栏里。</li>
 * </ol>
 */
package com.orbcraft.fabric.contract;
