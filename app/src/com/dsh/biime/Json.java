package com.dsh.biime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 解析器，只为扩展包文件服务。
 * 不引任何依赖（Android 上虽然有 org.json，但那样就没法在 JVM 上跑测试了）。
 */
public final class Json {

    public static final class Error extends RuntimeException {
        public Error(String m) {
            super(m);
        }
    }

    private final String s;
    private int p;

    private Json(String s) {
        this.s = s;
    }

    public static Object parse(String text) {
        if (text == null) {
            throw new Error("内容为空");
        }
        Json j = new Json(text);
        j.ws();
        Object v = j.value();
        j.ws();
        if (j.p != j.s.length()) {
            throw new Error("JSON 结尾有多余内容（位置 " + j.p + "）");
        }
        return v;
    }

    // ------------------------------------------------------------- 解析

    private void ws() {
        while (p < s.length() && Character.isWhitespace(s.charAt(p))) {
            p++;
        }
    }

    private char peek() {
        if (p >= s.length()) {
            throw new Error("JSON 意外结束");
        }
        return s.charAt(p);
    }

    private void expect(char c) {
        if (peek() != c) {
            throw new Error("位置 " + p + " 期望 '" + c + "'，实际 '" + peek() + "'");
        }
        p++;
    }

    private void lit(String word) {
        if (!s.startsWith(word, p)) {
            throw new Error("位置 " + p + " 不是合法的 " + word);
        }
        p += word.length();
    }

    private Object value() {
        char c = peek();
        if (c == '{') {
            return object();
        }
        if (c == '[') {
            return array();
        }
        if (c == '"') {
            return string();
        }
        if (c == 't') {
            lit("true");
            return Boolean.TRUE;
        }
        if (c == 'f') {
            lit("false");
            return Boolean.FALSE;
        }
        if (c == 'n') {
            lit("null");
            return null;
        }
        return number();
    }

    private Map<String, Object> object() {
        expect('{');
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        ws();
        if (peek() == '}') {
            p++;
            return m;
        }
        while (true) {
            ws();
            String k = string();
            ws();
            expect(':');
            ws();
            m.put(k, value());
            ws();
            char c = peek();
            if (c == ',') {
                p++;
                continue;
            }
            expect('}');
            return m;
        }
    }

    private List<Object> array() {
        expect('[');
        List<Object> list = new ArrayList<Object>();
        ws();
        if (peek() == ']') {
            p++;
            return list;
        }
        while (true) {
            ws();
            list.add(value());
            ws();
            char c = peek();
            if (c == ',') {
                p++;
                continue;
            }
            expect(']');
            return list;
        }
    }

    private String string() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = peek();
            p++;
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            char e = peek();
            p++;
            switch (e) {
                case '"': sb.append('"'); break;
                case '\\': sb.append('\\'); break;
                case '/': sb.append('/'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case 'n': sb.append('\n'); break;
                case 'r': sb.append('\r'); break;
                case 't': sb.append('\t'); break;
                case 'u':
                    if (p + 4 > s.length()) {
                        throw new Error("\\u 转义不完整");
                    }
                    sb.append((char) Integer.parseInt(s.substring(p, p + 4), 16));
                    p += 4;
                    break;
                default:
                    throw new Error("未知转义 \\" + e);
            }
        }
    }

    private Object number() {
        int start = p;
        if (peek() == '-' || peek() == '+') {
            p++;
        }
        while (p < s.length()) {
            char c = s.charAt(p);
            if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '-' || c == '+') {
                p++;
            } else {
                break;
            }
        }
        String num = s.substring(start, p);
        if (num.length() == 0) {
            throw new Error("位置 " + start + " 不是合法的值");
        }
        try {
            return Double.valueOf(num);
        } catch (NumberFormatException e) {
            throw new Error("非法数字: " + num);
        }
    }

    // ------------------------------------------------------------- 取值

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object o) {
        return (o instanceof Map) ? (Map<String, Object>) o : null;
    }

    public static String str(Map<String, Object> m, String key) {
        if (m == null) {
            return null;
        }
        Object v = m.get(key);
        return (v instanceof String) ? (String) v : null;
    }

    public static Integer num(Map<String, Object> m, String key) {
        if (m == null) {
            return null;
        }
        Object v = m.get(key);
        if (v instanceof Double) {
            return Integer.valueOf((int) Math.round(((Double) v).doubleValue()));
        }
        return null;
    }

    public static Boolean flag(Map<String, Object> m, String key) {
        if (m == null) {
            return null;
        }
        Object v = m.get(key);
        return (v instanceof Boolean) ? (Boolean) v : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Map<String, Object> m, String key) {
        if (m == null) {
            return null;
        }
        Object v = m.get(key);
        return (v instanceof List) ? (List<Object>) v : null;
    }

    /** "#RGB" / "#RRGGBB" / "#AARRGGBB" → 颜色；解析失败返回 def。 */
    public static int color(String v, int def) {
        if (v == null) {
            return def;
        }
        String t = v.trim();
        if (t.startsWith("#")) {
            t = t.substring(1);
        }
        try {
            if (t.length() == 3) {
                int r = Integer.parseInt(t.substring(0, 1), 16);
                int g = Integer.parseInt(t.substring(1, 2), 16);
                int b = Integer.parseInt(t.substring(2, 3), 16);
                return 0xFF000000 | (r * 17 << 16) | (g * 17 << 8) | (b * 17);
            }
            if (t.length() == 6) {
                return 0xFF000000 | (int) Long.parseLong(t, 16);
            }
            if (t.length() == 8) {
                return (int) Long.parseLong(t, 16);
            }
        } catch (NumberFormatException ignored) {
        }
        return def;
    }
}
