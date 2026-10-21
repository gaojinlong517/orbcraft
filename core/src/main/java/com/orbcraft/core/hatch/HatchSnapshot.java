package com.orbcraft.core.hatch;

import java.util.List;

import com.orbcraft.core.quota.QuotaManager;

/**
 * 一个玩家的孵化断面：配额占用 + 在途票据。
 *
 * <p>为什么两样必须<b>一起</b>存、一起恢复，而不是分成两个接口：
 * 配额与票据在 {@link IncubationService#submit} 里是在同一把锁内同时写入的
 * （扣额度 → 建票）。只存票据，重启后配额会从 0 开始 —— 玩家能凭空多造一件装备；
 * 只存配额，票据丢了 —— 玩家的球会「条走到一半消失」。这两种都是存档截断后
 * 才会出现的症状，而截断恰恰是最难复现的一类故障。所以这里把它们做成一个原子单位。
 *
 * <p><b>为什么 quota 要单独存，而不是从票据重算。</b>
 * {@link QuotaManager} 目前没有 release / refund：产物落地（{@code DEPLOYED}）
 * 之后<b>仍然占着</b>装备位与统御力，只有解契才会释放。已落地的票据不属于
 * 「在途」，不会出现在票据列表里，因此从票据重算只能算出在途那一部分，
 * 会把已经造出来的东西算没。存盘值是唯一能表达「历史上共占了什么」的东西。
 *
 * @param ownerKey 玩家标识，与 {@link HatchTicket#ownerKey()} 必须一致
 * @param quota    配额占用存盘值；{@code null} 视为空状态
 * @param tickets  在途票据（CHARGING / READY）；THROWN 之后的不必存，见 {@link HatchTicket#occupiesSlot()}
 */
public record HatchSnapshot(
        String ownerKey,
        QuotaManager.State quota,
        List<HatchTicket> tickets
) {

    public HatchSnapshot {
        if (ownerKey == null || ownerKey.isBlank()) {
            throw new IllegalArgumentException("断面里的 ownerKey 不能为空");
        }
        if (quota == null) {
            quota = QuotaManager.State.empty();
        }
        tickets = tickets == null ? List.of() : List.copyOf(tickets);
        for (HatchTicket t : tickets) {
            if (t == null) {
                throw new IllegalArgumentException("票据列表里含 null 元素：" + ownerKey);
            }
        }
    }

    /** 新玩家的空断面；读不到存档时用它，而不是 null。 */
    public static HatchSnapshot empty(String ownerKey) {
        return new HatchSnapshot(ownerKey, QuotaManager.State.empty(), List.of());
    }
}
