package com.orbcraft.core.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 极简 JSON 解析器 —— 存在的唯一原因是 <b>core 必须零依赖</b>。
 *
 * <p>为什么不用 Gson：core 的定位是「能脱离 Minecraft 独立编译、独立测试、独立跑批」。
 * 一旦引入 Gson，就跑不了纯 Java 环境（测试要额外拉 jar），
 * 而宿主环境里 MC 自带的 Gson 版本又会被 MC 的依赖管理牵着走。
 *
 * <p>代价是这个解析器只支持标准 JSON 子集（对象 / 数组 / 字符串 / 数字 / 布尔 / null），
 * 不支持注释、单引号、尾逗号。对我们自己的数据文件足够，也顺便逼数据保持标准。
 */
public final class Json {

    private final String src;
    private int pos;

    private Json(String src) {
        this.src = src;
    }

    public static Object parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("JSON 文本为 null");
        }
        Json p = new Json(text);
        p.skipWs();
        Object v = p.readValue();
        p.skipWs();
        if (p.pos < p.src.length()) {
            throw p.error("解析结束后仍有残留内容");
        }
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (!(v instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("顶层不是 JSON 对象");
        }
        return (Map<String, Object>) v;
    }

    // ---------- 解析 ----------

    private Object readValue() {
        skipWs();
        if (pos >= src.length()) {
            throw error("意外的输入结束");
        }
        char c = src.charAt(pos);
        switch (c) {
            case '{':
                return readObject();
            case '[':
                return readArray();
            case '"':
                return readString();
            case 't':
                return readLiteral("true", Boolean.TRUE);
            case 'f':
                return readLiteral("false", Boolean.FALSE);
            case 'n':
                return readLiteral("null", null);
            default:
                return readNumber();
        }
    }

    private Map<String, Object> readObject() {
        expect('{');
        Map<String, Object> m = new LinkedHashMap<>();
        skipWs();
        if (peek() == '}') {
            pos++;
            return m;
        }
        while (true) {
            skipWs();
            String key = readString();
            skipWs();
            expect(':');
            m.put(key, readValue());
            skipWs();
            char c = peek();
            if (c == ',') {
                pos++;
            } else if (c == '}') {
                pos++;
                return m;
            } else {
                throw error("对象内期望 , 或 }，实际 " + c);
            }
        }
    }

    private List<Object> readArray() {
        expect('[');
        List<Object> list = new ArrayList<>();
        skipWs();
        if (peek() == ']') {
            pos++;
            return list;
        }
        while (true) {
            list.add(readValue());
            skipWs();
            char c = peek();
            if (c == ',') {
                pos++;
            } else if (c == ']') {
                pos++;
                return list;
            } else {
                throw error("数组内期望 , 或 ]，实际 " + c);
            }
        }
    }

    private String readString() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= src.length()) {
                throw error("字符串未闭合");
            }
            char c = src.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (pos >= src.length()) {
                throw error("转义未完成");
            }
            char e = src.charAt(pos++);
            switch (e) {
                case '"' -> sb.append('"');
                case '\\' -> sb.append('\\');
                case '/' -> sb.append('/');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> {
                    if (pos + 4 > src.length()) {
                        throw error("\\u 转义长度不足");
                    }
                    sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                    pos += 4;
                }
                default -> throw error("未知转义 \\" + e);
            }
        }
    }

    private Object readLiteral(String literal, Object value) {
        if (!src.startsWith(literal, pos)) {
            throw error("期望字面量 " + literal);
        }
        pos += literal.length();
        return value;
    }

    private Double readNumber() {
        int start = pos;
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') {
                pos++;
            } else {
                break;
            }
        }
        if (start == pos) {
            throw error("无法识别的字符 " + src.charAt(pos));
        }
        try {
            return Double.valueOf(src.substring(start, pos));
        } catch (NumberFormatException ex) {
            throw error("数字格式错误：" + src.substring(start, pos));
        }
    }

    private void skipWs() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\uFEFF') {
                pos++;
            } else {
                break;
            }
        }
    }

    private char peek() {
        if (pos >= src.length()) {
            throw error("意外的输入结束");
        }
        return src.charAt(pos);
    }

    private void expect(char c) {
        if (pos >= src.length() || src.charAt(pos) != c) {
            throw error("期望 " + c);
        }
        pos++;
    }

    private IllegalArgumentException error(String msg) {
        // 截取附近上下文，数据文件出错时能直接定位
        int from = Math.max(0, pos - 30);
        int to = Math.min(src.length(), pos + 30);
        return new IllegalArgumentException(msg + "（位置 " + pos + "，附近：" + src.substring(from, to) + "）");
    }

    // ---------- 取值辅助（容错，缺失时返回默认值） ----------

    public static String str(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        return v instanceof String s ? s : def;
    }

    public static int intOf(Map<String, Object> m, String key, int def) {
        Object v = m.get(key);
        return v instanceof Number n ? n.intValue() : def;
    }

    public static double doubleOf(Map<String, Object> m, String key, double def) {
        Object v = m.get(key);
        return v instanceof Number n ? n.doubleValue() : def;
    }

    public static boolean boolOf(Map<String, Object> m, String key, boolean def) {
        Object v = m.get(key);
        return v instanceof Boolean b ? b : def;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> objectOf(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> listOf(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof List<?> l ? (List<Object>) l : List.of();
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> objectListOf(Map<String, Object> m, String key) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : listOf(m, key)) {
            if (o instanceof Map<?, ?> map) {
                out.add((Map<String, Object>) map);
            }
        }
        return out;
    }

    public static Set<String> strSet(Map<String, Object> m, String key) {
        Set<String> out = new LinkedHashSet<>();
        for (Object o : listOf(m, key)) {
            if (o instanceof String s) {
                out.add(s);
            }
        }
        return out;
    }

    public static List<String> strList(Map<String, Object> m, String key) {
        List<String> out = new ArrayList<>();
        for (Object o : listOf(m, key)) {
            if (o instanceof String s) {
                out.add(s);
            }
        }
        return out;
    }
}
