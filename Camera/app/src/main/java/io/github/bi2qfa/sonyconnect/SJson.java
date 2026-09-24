package io.github.bi2qfa.sonyconnect;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简扁平 JSON（私有协议专用，纯 Java，桌面可测）。
 *
 * 只覆盖协议需要的子集：扁平对象 {key: value}，value ∈ string | long |
 * boolean | string 数组。不引第三方库（相机端 minSdk 10 也要能跑，且桌面
 * JDK8 上没有 org.json）。转义遵循 RFC 8259。
 */
public final class SJson {

    private SJson() {
    }

    // ===== 编码 =====

    /** 字符串字面量（含引号与转义） */
    public static String str(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        sb.append("\\u").append(String.format("%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    /** {"k":v,...} 成员拼接小件 */
    public static StringBuilder member(StringBuilder sb, String key, String value) {
        if (sb.length() > 1) sb.append(',');
        sb.append(str(key)).append(':').append(str(value));
        return sb;
    }

    public static StringBuilder member(StringBuilder sb, String key, long value) {
        if (sb.length() > 1) sb.append(',');
        sb.append(str(key)).append(':').append(value);
        return sb;
    }

    public static StringBuilder member(StringBuilder sb, String key, boolean value) {
        if (sb.length() > 1) sb.append(',');
        sb.append(str(key)).append(':').append(value);
        return sb;
    }

    public static StringBuilder startObj() {
        return new StringBuilder("{");
    }

    public static String endObj(StringBuilder sb) {
        return sb.append('}').toString();
    }


    // ===== 解析（扁平对象） =====

    /**
     * 解析扁平 JSON 对象。值类型：String / Long / Boolean / List<String>。
     * 解析失败抛 IllegalArgumentException；输入须为单个对象且无嵌套对象。
     */
    public static Map<String, Object> parse(String json) {
        Parser p = new Parser(json);
        p.skipWs();
        Map<String, Object> out = p.parseObject();
        p.skipWs();
        if (!p.eof()) throw p.err("trailing content");
        return out;
    }

    public static String asString(Map<String, Object> obj, String key) {
        Object v = obj.get(key);
        return v instanceof String ? (String) v : null;
    }

    public static long asLong(Map<String, Object> obj, String key, long dft) {
        Object v = obj.get(key);
        return v instanceof Long ? (Long) v : dft;
    }


    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            this.s = s;
        }

        IllegalArgumentException err(String msg) {
            return new IllegalArgumentException("JSON " + msg + " at " + i);
        }

        boolean eof() {
            return i >= s.length();
        }

        void skipWs() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\r' || c == '\n') i++;
                else break;
            }
        }

        Map<String, Object> parseObject() {
            expect('{');
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            skipWs();
            if (peek() == '}') {
                i++;
                return out;
            }
            while (true) {
                skipWs();
                String key = parseString();
                skipWs();
                expect(':');
                skipWs();
                out.put(key, parseValue());
                skipWs();
                char c = peek();
                if (c == ',') {
                    i++;
                } else if (c == '}') {
                    i++;
                    return out;
                } else {
                    throw err("expected , or }");
                }
            }
        }

        Object parseValue() {
            char c = peek();
            if (c == '"') return parseString();
            if (c == '[') return parseStringArray();
            if (c == 't' || c == 'f') return parseBool();
            return parseNumber();
        }

        String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (eof()) throw err("unterminated string");
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (eof()) throw err("bad escape");
                    char e = s.charAt(i++);
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
                            if (i + 4 > s.length()) throw err("bad \\u");
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                            break;
                        default:
                            throw err("bad escape char");
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        List<String> parseStringArray() {
            expect('[');
            List<String> out = new ArrayList<String>();
            skipWs();
            if (peek() == ']') {
                i++;
                return out;
            }
            while (true) {
                skipWs();
                out.add(parseString());
                skipWs();
                char c = peek();
                if (c == ',') {
                    i++;
                } else if (c == ']') {
                    i++;
                    return out;
                } else {
                    throw err("expected , or ] in array");
                }
            }
        }

        Boolean parseBool() {
            if (s.startsWith("true", i)) {
                i += 4;
                return Boolean.TRUE;
            }
            if (s.startsWith("false", i)) {
                i += 5;
                return Boolean.FALSE;
            }
            throw err("bad literal");
        }

        Long parseNumber() {
            int start = i;
            while (!eof()) {
                char c = s.charAt(i);
                if ((c >= '0' && c <= '9') || c == '-') i++;
                else break;
            }
            if (i == start) throw err("bad number");
            return Long.parseLong(s.substring(start, i));
        }

        char peek() {
            if (eof()) throw err("unexpected end");
            return s.charAt(i);
        }

        void expect(char c) {
            if (eof() || s.charAt(i) != c) throw err("expected '" + c + "'");
            i++;
        }
    }
}
