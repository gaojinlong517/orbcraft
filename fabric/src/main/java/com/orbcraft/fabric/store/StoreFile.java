package com.orbcraft.fabric.store;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;

/**
 * 玩家存档文件的读写 —— 「一个玩家的状态」怎么安全地落到磁盘上。
 *
 * <p><b>为什么不用一个大 {@code CompoundTag}。</b> 一次性写整棵树，任何一次写到一半
 * （断电、磁盘满、进程被杀）都会让整份存档不可解析，结果只能是「当作新玩家」——
 * 玩家的装备位被清空、球消失，而日志里只有一行读失败。这里改成
 * <b>按段（frame）追加</b>：文件是一个记录序列，末尾有一个提交标记；
 * 读到不完整的段就停在那儿，前面已经写完整的段照常生效。
 *
 * <p><b>为什么段序是 meta → 票据 → 配额 → 定向卡。</b> 截断只会切掉尾巴。
 * 把「原始事实」（票据）写在「结论」（配额）之前，截断后留下的就是
 * 「有票据、没有配额」——{@code IncubationService.restore} 恰好能识别这种不自洽，
 * 按票据重算并把 {@code quotaRepaired} 置真报警。反过来写（配额在前、票据在后），
 * 截断后变成「配额还在、票据没了」，那就是静默丢球，没有任何提示。
 *
 * <p><b>原子性靠两层</b>：先写 {@code .tmp} 再 {@code ATOMIC_MOVE} 替换，
 * 所以进程在任意时刻死掉，磁盘上的正式文件要么是旧的完整版本、要么是新的完整版本；
 * 段结构只是第二道保险，防的是文件被外部截断（磁盘损坏、手工编辑、备份工具写一半）。
 */
final class StoreFile {

    /** 文件头/尾魔数，用来区分「走错文件」与「文件被截断」。 */
    private static final int MAGIC = 0x4F524231;

    /**
     * 一段的类型。顺序有含义，见类注释。
     *
     * <p>落盘用的是 {@link #ordinal()}，所以<b>只能在末尾追加</b>，不能插入或重排：
     * 插在中间会让旧存档里的每一段被当成另一种段读，症状是配额被读成票据，而且不报错。
     */
    enum Kind {
        META,
        TICKET,
        QUOTA,
        TARGET;

        byte code() {
            return (byte) ordinal();
        }

        /** @return {@code null} 表示这段的标记不认识（不是本模组写的，或已损坏） */
        static Kind of(byte code) {
            Kind[] values = values();
            return code >= 0 && code < values.length ? values[code] : null;
        }
    }

    record Frame(Kind kind, CompoundTag tag) {
    }

    /**
     * 读出来的内容。
     *
     * @param complete 末尾提交标记存在 = 上次写到收尾；{@code false} 时 {@code frames}
     *                 里仍是可以用的部分，调用方应当打一条告警
     */
    record Content(List<Frame> frames, boolean complete) {
    }

    private StoreFile() {
    }

    static void write(Path target, List<Frame> frames) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try (OutputStream raw = Files.newOutputStream(tmp,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
             DataOutputStream out = new DataOutputStream(raw)) {
            out.writeInt(MAGIC);
            out.writeInt(frames.size());
            for (Frame frame : frames) {
                byte[] payload = encode(frame.tag());
                out.writeByte(frame.kind().code());
                out.writeInt(payload.length);
                out.write(payload);
            }
            out.writeInt(MAGIC);
            out.flush();
        }
        move(tmp, target);
    }

    static Content read(Path file) throws IOException {
        byte[] all = Files.readAllBytes(file);
        ByteBuffer buffer = ByteBuffer.wrap(all);
        if (buffer.remaining() < 8 || buffer.getInt() != MAGIC) {
            throw new IOException("文件头不匹配，不是本模组的玩家存档");
        }
        int declared = buffer.getInt();
        List<Frame> frames = new ArrayList<>(Math.max(0, Math.min(declared, 64)));
        boolean complete = false;
        for (int i = 0; i < declared; i++) {
            if (buffer.remaining() < 5) {
                break;
            }
            byte kindCode = buffer.get();
            int length = buffer.getInt();
            if (length < 0 || length > buffer.remaining()) {
                break;
            }
            byte[] payload = new byte[length];
            buffer.get(payload);
            Kind kind = Kind.of(kindCode);
            if (kind == null) {
                break;
            }
            frames.add(new Frame(kind, decode(payload)));
        }
        if (frames.size() == declared && buffer.remaining() >= 4 && buffer.getInt() == MAGIC) {
            complete = true;
        }
        return new Content(List.copyOf(frames), complete);
    }

    private static byte[] encode(CompoundTag tag) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(128);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            NbtIo.write(tag, out);
        }
        return bytes.toByteArray();
    }

    private static CompoundTag decode(byte[] payload) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            return NbtIo.read(in);
        }
    }

    private static void move(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // 跨卷/文件系统不支持原子替换时退化成普通替换。它不是原子的，但比不写强。
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
