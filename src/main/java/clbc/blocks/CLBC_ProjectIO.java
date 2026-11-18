package clbc.blocks;

import java.util.ArrayList;
import java.util.List;

import clbc.ui.CLBC_Block;

/**
 * 工程持久化（④-2）：把画布积木（扁平列表）+ 注释序列化为 .clbc JSON，并支持反向解析。
 *
 * 设计要点：
 *  · 零外部依赖——内嵌最小 JSON 写入器与解析器，仅覆盖本项目生成的子集（对象/数组/字符串/数字/布尔/null）。
 *  · 互转闭环：积木树由 parentId 重建，故只需保存扁平列表 + 各字段即可完整还原，无需递归嵌套。
 *  · color 以 "0xRRGGBB" / "0xAARRGGBB" 十六进制存盘，解析时还原为 0xAARRGGBB long。
 */
public final class CLBC_ProjectIO {

    /** 注释状态（与 CanvasHost.Comment 对应，独立以便序列化） */
    public static final class CommentState {
        public float x, y, w, h;
        public String text;
        /** #165：连线锚点 + 源码顺序，保存后重开工程仍能连回同一块、写回同一位置 */
        public int anchorId = -1;
        public float orderY = Float.NaN;
        public boolean anchorIsNext = false;
        public CommentState() {}
        public CommentState(float x, float y, float w, float h, String t) {
            this.x = x; this.y = y; this.w = w; this.h = h; this.text = t;
        }
        public CommentState(float x, float y, float w, float h, String t,
                            int anchorId, float orderY, boolean anchorIsNext) {
            this.x = x; this.y = y; this.w = w; this.h = h; this.text = t;
            this.anchorId = anchorId; this.orderY = orderY; this.anchorIsNext = anchorIsNext;
        }
    }

    /** 解析结果载体 */
    public static final class ProjectData {
        public List<CLBC_Block> blocks = new ArrayList<>();
        public List<CommentState> comments = new ArrayList<>();
    }

    private CLBC_ProjectIO() {}

    // ===================== 序列化 =====================

    public static String toJson(List<CLBC_Block> blocks, List<CommentState> comments) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"version\": 1,\n  \"blocks\": [\n");
        for (int i = 0; i < blocks.size(); i++) {
            sb.append("    ").append(blockToJson(blocks.get(i)));
            if (i < blocks.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ],\n  \"comments\": [\n");
        for (int i = 0; i < comments.size(); i++) {
            CommentState c = comments.get(i);
            sb.append("    {\"x\":").append(c.x).append(",\"y\":").append(c.y)
              .append(",\"w\":").append(c.w).append(",\"h\":").append(c.h)
              .append(",\"anchor\":").append(c.anchorId)
              .append(",\"order\":").append(Float.isFinite(c.orderY) ? c.orderY : c.y)
              .append(",\"next\":").append(c.anchorIsNext ? 1 : 0)
              .append(",\"text\":").append(esc(c.text)).append("}");
            if (i < comments.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ]\n}\n");
        return sb.toString();
    }

    private static String blockToJson(CLBC_Block b) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"id\":").append(b.id);
        sb.append(",\"kind\":").append(esc(b.kind));
        sb.append(",\"color\":").append(esc(colorHex(b.color)));
        sb.append(",\"text\":").append(esc(b.text));
        sb.append(",\"name\":").append(b.name == null ? "\"null\"" : esc(b.name));
        sb.append(",\"x\":").append(b.x).append(",\"y\":").append(b.y);
        sb.append(",\"w\":").append(b.w).append(",\"h\":").append(b.h);
        sb.append(",\"key\":").append(esc(b.key));
        sb.append(",\"args\":[");
        for (int i = 0; i < b.args.size(); i++) {
            sb.append(esc(b.args.get(i)));
            if (i < b.args.size() - 1) sb.append(",");
        }
        sb.append("]");
        sb.append(",\"parentId\":").append(b.parentId);
        sb.append(",\"slotIndex\":").append(b.slotIndex);
        // 多口容器：只存每条分支标签，子块归属由各自的 slotIndex 重建（与 parentId 同一套扁平约定）
        if (b.hasSlots()) {
            sb.append(",\"slots\":[");
            for (int i = 0; i < b.slots.size(); i++) {
                sb.append("{\"label\":").append(esc(b.slots.get(i).label))
                  .append(",\"expr\":").append(esc(b.slots.get(i).expr)).append("}");
                if (i < b.slots.size() - 1) sb.append(",");
            }
            sb.append("]");
        }
        // 内嵌输入窗口：含窗口里嵌入的报告积木（递归整块写出，往返不丢）
        if (!b.inputs.isEmpty()) {
            sb.append(",\"inputs\":[");
            for (int i = 0; i < b.inputs.size(); i++) {
                CLBC_Block.Input in = b.inputs.get(i);
                sb.append("{\"value\":").append(esc(in.value))
                  .append(",\"hex\":").append(in.hex)
                  .append(",\"accept\":").append(esc(in.accept))
                  .append(",\"row\":").append(in.row)
                  .append(",\"ordinal\":").append(in.ordinal);
                if (in.isDropdown()) {
                    sb.append(",\"options\":[");
                    for (int j = 0; j < in.options.length; j++) {
                        sb.append(esc(in.options[j]));
                        if (j < in.options.length - 1) sb.append(",");
                    }
                    sb.append("]");
                }
                if (in.child != null) sb.append(",\"child\":").append(blockToJson(in.child));
                sb.append("}");
                if (i < b.inputs.size() - 1) sb.append(",");
            }
            sb.append("]");
        }
        sb.append(",\"nestLevel\":").append(b.nestLevel);
        sb.append(",\"isReporter\":").append(b.isReporter);
        sb.append(",\"collapsed\":").append(b.collapsed);
        sb.append(",\"retType\":").append(esc(b.retType));
        sb.append(",\"modifiers\":").append(esc(b.modifiers));
        sb.append(",\"expr\":").append(esc(b.expr));
        sb.append("}");
        return sb.toString();
    }

    private static String colorHex(long c) {
        return String.format("0x%08X", c & 0xFFFFFFFFL);
    }

    private static String esc(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default   -> sb.append(ch);
            }
        }
        sb.append("\"");
        return sb.toString();
    }

    // ===================== 反序列化 =====================

    public static ProjectData fromJson(String json) {
        ProjectData data = new ProjectData();
        if (json == null || json.trim().isEmpty()) return data;
        Parser p = new Parser(json);
        p.expect('{');
        while (p.peek() != '}') {
            String key = p.readString();
            p.expect(':');
            if (key.equals("blocks")) {
                p.expect('[');
                while (p.peek() != ']') {
                    data.blocks.add(readBlock(p));
                    if (p.peek() == ',') p.next(); else break;
                }
                p.expect(']');
            } else if (key.equals("comments")) {
                p.expect('[');
                while (p.peek() != ']') {
                    data.comments.add(readComment(p));
                    if (p.peek() == ',') p.next(); else break;
                }
                p.expect(']');
            } else {
                p.skipValue(); // 忽略未知字段（如 version）
            }
            if (p.peek() == ',') p.next(); else break;
        }
        p.expect('}');
        return data;
    }

    private static CLBC_Block readBlock(Parser p) {
        CLBC_Block b = new CLBC_Block(0, "stack", 0, "");
        p.expect('{');
        while (p.peek() != '}') {
            String key = p.readString();
            p.expect(':');
            switch (key) {
                case "id"         -> b.id = p.readInt();
                case "kind"       -> b.kind = p.readString();
                case "color"      -> b.color = p.readColor();
                case "text"       -> b.text = p.readString();
                case "name"       -> { String v = p.readString(); b.name = "null".equals(v) ? null : v; }
                case "x"          -> b.x = p.readFloat();
                case "y"          -> b.y = p.readFloat();
                case "w"          -> b.w = p.readFloat();
                case "h"          -> b.h = p.readFloat();
                case "key"        -> b.key = p.readString();
                case "args" -> {
                    p.expect('[');
                    while (p.peek() != ']') {
                        b.args.add(p.readString());
                        if (p.peek() == ',') p.next(); else break;
                    }
                    p.expect(']');
                }
                case "parentId"   -> b.parentId = p.readInt();
                case "slotIndex"  -> b.slotIndex = p.readInt();
                case "slots" -> {
                    p.expect('[');
                    b.slots = new ArrayList<>();
                    while (p.peek() != ']') {
                        CLBC_Block.Slot s = new CLBC_Block.Slot();
                        p.expect('{');
                        while (p.peek() != '}') {
                            String sk = p.readString();
                            p.expect(':');
                            if (sk.equals("label")) s.label = p.readString();
                            else if (sk.equals("expr")) s.expr = p.readString();
                            else p.skipValue();
                            if (p.peek() == ',') p.next(); else break;
                        }
                        p.expect('}');
                        b.slots.add(s);
                        if (p.peek() == ',') p.next(); else break;
                    }
                    p.expect(']');
                }
                case "inputs" -> {
                    p.expect('[');
                    while (p.peek() != ']') {
                        CLBC_Block.Input in = new CLBC_Block.Input();
                        p.expect('{');
                        while (p.peek() != '}') {
                            String ik = p.readString();
                            p.expect(':');
                            switch (ik) {
                                case "value"   -> in.value = p.readString();
                                case "hex"     -> in.hex = p.readBool();
                                case "accept"  -> in.accept = p.readString();
                                case "row"     -> in.row = p.readInt();
                                case "ordinal" -> in.ordinal = p.readInt();
                                case "options" -> {
                                    p.expect('[');
                                    List<String> opts = new ArrayList<>();
                                    while (p.peek() != ']') {
                                        opts.add(p.readString());
                                        if (p.peek() == ',') p.next(); else break;
                                    }
                                    p.expect(']');
                                    if (!opts.isEmpty()) in.options = opts.toArray(new String[0]);
                                }
                                case "child"   -> in.child = readBlock(p);
                                default        -> p.skipValue();
                            }
                            if (p.peek() == ',') p.next(); else break;
                        }
                        p.expect('}');
                        b.inputs.add(in);
                        if (p.peek() == ',') p.next(); else break;
                    }
                    p.expect(']');
                }
                case "nestLevel"  -> b.nestLevel = p.readInt();
                case "isReporter" -> b.isReporter = p.readBool();
                case "collapsed"  -> b.collapsed = p.readBool();
                case "retType"    -> b.retType = p.readString();
                case "modifiers"  -> b.modifiers = p.readString();
                case "expr"       -> b.expr = p.readString();
                default           -> p.skipValue();
            }
            if (p.peek() == ',') p.next(); else break;
        }
        p.expect('}');
        restoreDropdowns(b);
        return b;
    }

    /**
     * 旧工程（无 options 字段）兜底：按积木键 + 参数序号从纯语法定义表重建下拉候选。
     * 两套 args 布局靠 unit.method 的参数个数区分（4 个 = 代码导入布局）。
     */
    private static void restoreDropdowns(CLBC_Block b) {
        if (b == null || b.inputs.isEmpty()) return;
        boolean importLayout = "unit.method".equals(b.key) && b.args != null && b.args.size() >= 4;
        for (CLBC_Block.Input in : b.inputs) {
            if (in.options != null) continue;   // 新工程已显式存过，原样保留
            String[] dd = importLayout
                    ? CLBC_CoreSyntax.importDropdownOptions(b.key, in.ordinal)
                    : CLBC_CoreSyntax.dropdownOptions(b.key, in.ordinal);
            if (dd != null) in.options = dd;
        }
    }

    private static CommentState readComment(Parser p) {
        CommentState c = new CommentState();
        p.expect('{');
        while (p.peek() != '}') {
            String key = p.readString();
            p.expect(':');
            switch (key) {
                case "x"    -> c.x = p.readFloat();
                case "y"    -> c.y = p.readFloat();
                case "w"    -> c.w = p.readFloat();
                case "h"    -> c.h = p.readFloat();
                case "text"  -> c.text = p.readString();
                case "anchor" -> c.anchorId = (int) p.readFloat();
                case "order" -> c.orderY = p.readFloat();
                case "next"  -> c.anchorIsNext = p.readFloat() > 0.5f;
                default     -> p.skipValue();
            }
            if (p.peek() == ',') p.next(); else break;
        }
        p.expect('}');
        return c;
    }

    // ===================== 最小 JSON 解析器 =====================

    private static final class Parser {
        private final String s;
        private int pos = 0;
        Parser(String s) { this.s = s; }

        char peek() { skip(); return pos < s.length() ? s.charAt(pos) : 0; }
        char next() { return s.charAt(pos++); }
        void skip() { while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++; }
        void expect(char c) { skip(); if (pos < s.length() && s.charAt(pos) == c) pos++; }

        String readString() {
            skip();
            if (pos < s.length() && s.charAt(pos) == '"') {
                pos++;
                StringBuilder sb = new StringBuilder();
                while (pos < s.length()) {
                    char ch = s.charAt(pos++);
                    if (ch == '"') return sb.toString();
                    if (ch == '\\') {
                        char e = s.charAt(pos++);
                        switch (e) {
                            case 'n' -> sb.append('\n');
                            case 't' -> sb.append('\t');
                            case 'r' -> sb.append('\r');
                            case '"' -> sb.append('"');
                            case '\\' -> sb.append('\\');
                            default   -> sb.append(e);
                        }
                    } else sb.append(ch);
                }
            }
            return "";
        }

        long readColor() {
            String v = readString();
            if (v.startsWith("0x") || v.startsWith("0X")) {
                try { return Long.parseLong(v.substring(2), 16) & 0xFFFFFFFFL; }
                catch (NumberFormatException e) { return 0xFF202020L; }
            }
            return 0xFF202020L;
        }

        int readInt() {
            skip();
            int start = pos;
            if (pos < s.length() && (s.charAt(pos) == '-' || s.charAt(pos) == '+')) pos++;
            while (pos < s.length() && Character.isDigit(s.charAt(pos))) pos++;
            try { return Integer.parseInt(s.substring(start, pos)); }
            catch (NumberFormatException e) { return 0; }
        }

        float readFloat() { return (float) readDouble(); }

        double readDouble() {
            skip();
            int start = pos;
            if (pos < s.length() && (s.charAt(pos) == '-' || s.charAt(pos) == '+')) pos++;
            while (pos < s.length()) {
                char ch = s.charAt(pos);
                if (Character.isDigit(ch) || ch == '.' || ch == 'e' || ch == 'E') { pos++; continue; }
                if ((ch == '+' || ch == '-') && pos > start
                        && (s.charAt(pos - 1) == 'e' || s.charAt(pos - 1) == 'E')) { pos++; continue; }
                break;
            }
            try { return Double.parseDouble(s.substring(start, pos)); }
            catch (NumberFormatException e) { return 0; }
        }

        boolean readBool() {
            skip();
            if (s.startsWith("true", pos))  { pos += 4; return true; }
            if (s.startsWith("false", pos)) { pos += 5; return false; }
            return false;
        }

        void skipValue() {
            skip();
            char c = peek();
            if (c == '{' || c == '[') {
                char open = c, close = (c == '{') ? '}' : ']';
                int depth = 0;
                while (pos < s.length()) {
                    char ch = s.charAt(pos);
                    if (ch == open) { depth++; pos++; }
                    else if (ch == close) { depth--; pos++; if (depth == 0) break; }
                    else if (ch == '"') { readString(); }
                    else pos++;
                }
            } else if (c == '"') {
                readString();
            } else {
                while (pos < s.length() && !Character.isWhitespace(s.charAt(pos))
                        && s.charAt(pos) != ',' && s.charAt(pos) != '}' && s.charAt(pos) != ']') pos++;
            }
        }
    }
}
