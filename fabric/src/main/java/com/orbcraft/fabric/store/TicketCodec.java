package com.orbcraft.fabric.store;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.orbcraft.core.hatch.HatchState;
import com.orbcraft.core.hatch.HatchTicket;
import com.orbcraft.core.spec.BehaviorMode;
import com.orbcraft.core.spec.ProductType;
import com.orbcraft.core.spec.Rarity;
import com.orbcraft.core.spec.Spec;
import com.orbcraft.fabric.OrbcraftMod;
import com.orbcraft.fabric.contract.PlayerStore;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;

/**
 * 票据的 NBT 布局 —— 全项目唯一的一份（契约 {@link PlayerStore.OrbCodec} 的实现）。
 *
 * <p><b>在链路里的位置。</b> 票据既是「玩家背包里那颗球」的载荷，也是
 * 「玩家存档里在孵的那几张票」的载荷。两条路必须用同一份布局：各自写一套的结果是
 * 球读得出、存档读不出（或反过来），症状是重启后球变成读不出来的坏物品且不报错。
 * 所以 S1 造球/读球时不要自己拼 NBT，一律
 * {@code Services.playerStore().codec().write(ticket)} / {@code .read(tag)}。
 *
 * <p>布局（所有键都在同一层，{@code v} 是布局版本）：
 * <pre>
 * v          int     布局版本，当前 1；读到更大的值直接放弃（不猜未知格式）
 * ticketId   string
 * specId     string
 * owner      string  ownerKey，必须来自 {@link PlayerStore#ownerKeyOf}
 * startTick  long    进度起点，绝对世界 tick（重启后仍然有效，见 {@code LevelData#getGameTime}）
 * seconds    int     实际需要的总秒数（已含祭坛加速）
 * state      string  {@link HatchState#name()}
 * spec       compound
 *     id/name/type/rarity/behavior/hatch/slot/scale/flavor  标量
 *     mats    compound  {材料名 -> int}
 *     attrs   compound  {属性键 -> double}
 *     tags    list<string>
 * </pre>
 *
 * <p><b>为什么 {@code state} 存名字而不是序号。</b> 枚举加成员时序号会移位，
 * 旧存档里的 {@code 2} 会静默变成另一个状态。名字错了只会读不出来（可观测），
 * 序号错了会静默改玩法（不可观测）。
 *
 * <p><b>为什么读失败返回 empty 而不是抛异常。</b> 载体是物品 NBT，创造模式、作弊器、
 * 别的模组都能写出一份看起来像球的东西；把异常抛到服务端 tick 里等于整局卡住。
 */
public final class TicketCodec implements PlayerStore.OrbCodec {

    /** 当前布局版本。改动任何键名/含义都要加一，并在 {@link #read} 里保留旧版本的读法。 */
    public static final int VERSION = 1;

    @Override
    public CompoundTag write(HatchTicket ticket) {
        if (ticket == null) {
            throw new IllegalArgumentException("ticket 不能为空");
        }
        CompoundTag tag = new CompoundTag();
        tag.putInt("v", VERSION);
        tag.putString("ticketId", ticket.ticketId());
        tag.putString("specId", ticket.specId());
        tag.putString("owner", ticket.ownerKey());
        tag.putLong("startTick", ticket.startTick());
        tag.putInt("seconds", ticket.totalSeconds());
        tag.putString("state", ticket.state().name());
        tag.put("spec", writeSpec(ticket.spec()));
        return tag;
    }

    @Override
    public Optional<HatchTicket> read(CompoundTag tag) {
        if (tag == null) {
            return Optional.empty();
        }
        try {
            int version = tag.getIntOr("v", 1);
            if (version > VERSION) {
                // 不猜未来格式：猜错会把玩家的球变成一个数值全错的球，而且没有报错点。
                OrbcraftMod.LOG.warn("票据布局版本 {} 高于本模组认识的 {}，已忽略这张票", version, VERSION);
                return Optional.empty();
            }
            return Optional.of(new HatchTicket(
                    text(tag, "ticketId"),
                    text(tag, "specId"),
                    text(tag, "owner"),
                    tag.getLongOr("startTick", 0L),
                    tag.getIntOr("seconds", 0),
                    enums(HatchState.class, text(tag, "state"), "state"),
                    readSpec(tag.getCompoundOrEmpty("spec"))));
        } catch (RuntimeException e) {
            OrbcraftMod.LOG.warn("票据 NBT 读不出来，已丢弃：{}", e.getMessage());
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------ spec

    private static CompoundTag writeSpec(Spec spec) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", spec.id());
        tag.putString("name", spec.displayName());
        tag.putString("type", spec.type().name());
        tag.putString("rarity", spec.rarity().name());
        tag.putString("behavior", spec.behavior().name());
        tag.putInt("hatch", spec.hatchSeconds());
        tag.putInt("slot", spec.slotId());
        tag.putInt("scale", spec.scalePct());
        tag.putString("flavor", spec.flavor() == null ? "" : spec.flavor());

        CompoundTag mats = new CompoundTag();
        spec.materialCost().forEach(mats::putInt);
        tag.put("mats", mats);

        CompoundTag attrs = new CompoundTag();
        spec.attrs().forEach(attrs::putDouble);
        tag.put("attrs", attrs);

        ListTag tags = new ListTag();
        for (String value : spec.tags()) {
            tags.add(StringTag.valueOf(value == null ? "" : value));
        }
        tag.put("tags", tags);
        return tag;
    }

    private static Spec readSpec(CompoundTag tag) {
        if (tag.isEmpty()) {
            throw new IllegalArgumentException("缺少 spec 段");
        }
        Map<String, Integer> mats = new LinkedHashMap<>();
        CompoundTag matsTag = tag.getCompoundOrEmpty("mats");
        for (String key : matsTag.keySet()) {
            mats.put(key, matsTag.getIntOr(key, 0));
        }

        Map<String, Double> attrs = new LinkedHashMap<>();
        CompoundTag attrsTag = tag.getCompoundOrEmpty("attrs");
        for (String key : attrsTag.keySet()) {
            attrs.put(key, attrsTag.getDoubleOr(key, 0.0));
        }

        List<String> tags = new ArrayList<>();
        ListTag tagList = tag.getListOrEmpty("tags");
        for (int i = 0; i < tagList.size(); i++) {
            tagList.getString(i).ifPresent(tags::add);
        }

        return new Spec(
                text(tag, "id"),
                text(tag, "name"),
                enums(ProductType.class, text(tag, "type"), "type"),
                enums(Rarity.class, text(tag, "rarity"), "rarity"),
                enums(BehaviorMode.class, text(tag, "behavior"), "behavior"),
                tag.getIntOr("hatch", 0),
                mats,
                attrs,
                tags,
                tag.getStringOr("flavor", ""),
                tag.getIntOr("slot", 0),
                tag.getIntOr("scale", 100));
    }

    // ------------------------------------------------------------------ 读工具

    /** 1.21.11 的 {@code getString} 返回 {@code Optional<String>}，缺键与空串都要当错误。 */
    private static String text(CompoundTag tag, String key) {
        String value = tag.getStringOr(key, null);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少字段 " + key);
        }
        return value;
    }

    private static <E extends Enum<E>> E enums(Class<E> type, String raw, String key) {
        try {
            return Enum.valueOf(type, raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("字段 " + key + " 的值 " + raw + " 不认识");
        }
    }
}
