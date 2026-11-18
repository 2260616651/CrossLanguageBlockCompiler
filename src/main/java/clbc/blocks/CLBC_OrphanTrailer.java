package clbc.blocks;

import java.util.ArrayList;
import java.util.List;

import clbc.ui.CLBC_Block;
import clbc.util.CLBC_Util;

/**
 * ⑨「游离代码 / 未连接积木」尾部注释区协议（统一来源，纯 JDK，零第三方依赖）。
 *
 * 需求原文映射：
 *  · 「不在声明内的代码在文件结尾用注释的形式写出来」→ 生成目标源码时，把
 *    {@code orphan.code} / {@code raw.code} 积木以注释行追加到文件结尾。
 *  · 「保留积木结构」→ 每行带 {@code L<level>} 层级号，按原嵌套深度还原父子关系。
 *  · 「空行表示未连接的积木的切分处」→ 顶层未连接积木组之间写一个空行，
 *    读取时空行即切分新组（group++），组内按 level 用栈重建 parentId。
 *
 * 协议格式（全部为 Java 行注释，主体代码不受影响）：
 * <pre>
 * // ===== CLBC ORPHAN BEGIN =====
 * // CLBC|L0|G0|orphan.code|&lt;escapedText&gt;
 * // CLBC|L1|G0|orphan.code|&lt;escapedText&gt;
 *                              ← 空行：切分「未连接积木」组
 * // CLBC|L0|G1|orphan.code|&lt;escapedText&gt;
 * // ===== CLBC ORPHAN END =====
 * </pre>
 * text 转义：{@code \ → \\}、{@code | → \|}、{@code 换行 → \n}、{@code 回车 → \r}。
 *
 * 往返闭环：{@link CodeToBlocks#convert(String)} 先剥离并还原注释区积木，
 * {@link BlocksToCode#fromCanvas(List)} 再把 orphan 积木写回注释区，信息不丢失。
 */
public final class CLBC_OrphanTrailer {

    /** 注释区起止标记（改动需同步本类的写入/读取逻辑） */
    public static final String BEGIN = "// ===== CLBC ORPHAN BEGIN =====";
    public static final String END   = "// ===== CLBC ORPHAN END =====";
    /** 单行前缀：// CLBC|L<层级>|G<组号>|<key>|<转义文本> */
    private static final String ROW = "// CLBC|";

    /** 判定：该积木是否为"不在声明内 / 未连接"的游离积木 */
    public static boolean isOrphan(CLBC_Block b) {
        if (b == null) return false;
        return "orphan.code".equals(b.key) || "raw.code".equals(b.key);
    }

    private CLBC_OrphanTrailer() {}

    // ===================== 写入（积木 → 代码） =====================

    /**
     * 把游离积木序列化为尾部注释区。
     *
     * @param orphans       游离积木（按画布顺序；每项携带 nestLevel 表示积木结构层级）
     * @param connectedBase 已连接（在声明内）的顶层积木数，用于推算绝对层级，可传 0
     * @return 注释区字符串（含起止标记）；无游离积木时返回空串
     */
    public static String write(List<CLBC_Block> orphans, int connectedBase) {
        if (orphans == null || orphans.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append('\n').append(BEGIN).append('\n');

        int group = -1;
        int prevLevel = -1;
        for (CLBC_Block b : orphans) {
            // 顶层（level<=0）且非首项 → 视为新的「未连接积木」，用空行切分
            int level = Math.max(0, b.nestLevel - connectedBase);
            if (level == 0 && prevLevel >= 0) {
                sb.append('\n');       // ← 空行 = 未连接积木的切分处
                group++;
            }
            if (group < 0) group = 0;
            String key = (b.key == null || b.key.isEmpty()) ? "orphan.code" : b.key;
            // 游离积木的原文存放在 args[0]（convert 构造 orphan.code/raw.code 时以原文入参）；
            // 其 display 文本（block.text）对这两类可能是键名本身（如 "orphan.code"），
            // 故序列化注释区时优先取 args[0]，否则会把键名写进去而丢失真实代码。
            String text = (b.args != null && !b.args.isEmpty() && b.args.get(0) != null
                    && !b.args.get(0).isEmpty()) ? b.args.get(0)
                    : (b.text == null ? "" : b.text);
            sb.append(ROW).append('L').append(level)
                    .append("|G").append(group)
                    .append('|').append(key)
                    .append('|').append(escape(text)).append('\n');
            prevLevel = level;
        }
        sb.append(END).append('\n');
        CLBC_Util.log("游离代码注释区已写出: " + orphans.size() + " 个积木, "
                + (group + 1) + " 组未连接积木");
        return sb.toString();
    }

    /** 文本转义：保证单行注释不跨行、字段分隔符不冲突 */
    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("|", "\\|")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

    /** 反转义（按转义写入顺序逆序还原，逐字符扫描避免二次转义误伤） */
    private static String unescape(String s) {
        StringBuilder out = new StringBuilder();
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            if (c == '\\' && k + 1 < s.length()) {
                char n = s.charAt(++k);
                switch (n) {
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case '|' -> out.append('|');
                    case '\\' -> out.append('\\');
                    default -> { out.append('\\'); out.append(n); }
                }
            } else out.append(c);
        }
        return out.toString();
    }

    // ===================== 读取（代码 → 积木） =====================

    /** 源码中是否含游离代码注释区 */
    public static boolean hasTrailer(String code) {
        return code != null && code.contains(BEGIN) && code.contains(END);
    }

    /** 剥离尾部注释区，返回主体源码（供解析器使用，避免注释干扰） */
    public static String strip(String code) {
        if (code == null) return "";
        int[] be = trailerLines(code);
        if (be == null) return code;
        String[] lines = code.split("\n", -1);
        StringBuilder head = new StringBuilder();
        for (int i = 0; i < be[0]; i++) { if (head.length() > 0) head.append('\n'); head.append(lines[i]); }
        StringBuilder tail = new StringBuilder();
        for (int i = be[1] + 1; i < lines.length; i++) { if (tail.length() > 0) tail.append('\n'); tail.append(lines[i]); }
        return head.toString() + (head.length() > 0 && tail.length() > 0 ? "\n" : "") + tail.toString();
    }

    /** 按“独立整行”定位 BEGIN/END（避免游离文本中恰好含标记子串时误命中），返回 [beginLine, endLine] 或 null */
    private static int[] trailerLines(String code) {
        if (code == null) return null;
        String[] lines = code.split("\n", -1);
        int b = -1, e = -1;
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].trim();
            if (b < 0) { if (t.equals(BEGIN)) b = i; }
            else if (e < 0) { if (t.equals(END)) e = i; }
        }
        if (b < 0 || e < 0 || e <= b) return null;
        return new int[]{b, e};
    }

    /**
     * 从注释区还原积木（保留积木结构：按 L 层级用栈重建 parentId；空行切分未连接组）。
     *
     * @param code    原始源码（含注释区）
     * @param startId 起始积木 id，避免与主体积木冲突
     * @return 扁平积木列表（含 parentId / nestLevel），可直接并入画布
     */
    public static List<CLBC_Block> read(String code, int startId) {
        List<CLBC_Block> out = new ArrayList<>();
        if (!hasTrailer(code)) return out;
        int[] be = trailerLines(code);
        if (be == null) return out;
        String[] lines = code.split("\n", -1);
        StringBuilder region = new StringBuilder();
        for (int i = be[0] + 1; i < be[1]; i++) region.append(lines[i]).append('\n');
        String regionStr = region.toString();

        int id = startId;
        int group = 0;
        // 栈：每个层级当前最后一个积木 id，用于给子块挂 parentId
        List<Integer> stack = new ArrayList<>();
        // 布局缓冲：每个层级的 x/y（嵌套子块屏幕位置由 CanvasHost 按 parentId 推算，
        // 这里给顶层 + 子块一个合理绝对坐标，保证导入后不重叠、结构可见）
        float[] lx = new float[8];
        float[] ly = new float[8];
        for (String line : regionStr.split("\n", -1)) {
            String t = line.trim();
            if (t.isEmpty()) {          // ← 空行 = 未连接积木切分处
                group++;
                stack.clear();
                continue;
            }
            if (!t.startsWith(ROW)) continue;
            String[] p = splitRow(t.substring(ROW.length()));
            if (p.length < 4) continue;
            int level = parseLevel(p[0]);
            String key = p[2];
            String text = unescape(p[3]);

            CLBC_Block block = new CLBC_Block(id++, "stack", 0L, text);
            block.key = key;
            block.text = text;
            block.expr = text;
            block.nestLevel = level;
            block.args = new ArrayList<>(List.of(text));

            // 父子关系 + 坐标布局
            if (level <= 0 || stack.isEmpty()) {
                block.parentId = -1;
                block.x = 40f + group * 300f;
                block.y = 40f;
                lx[0] = block.x; ly[0] = block.y;
                for (int k = 1; k < lx.length; k++) { lx[k] = block.x; ly[k] = block.y; }
            } else {
                int parentIdx = Math.min(level - 1, stack.size() - 1);
                block.parentId = stack.get(parentIdx);
                float px = lx[Math.min(level - 1, lx.length - 1)];
                float py = ly[Math.min(level - 1, ly.length - 1)];
                block.x = px + 36f;
                block.y = py + 64f;
                if (level < lx.length) { lx[level] = block.x; ly[level] = block.y; }
            }
            while (stack.size() <= level) stack.add(block.id);
            stack.set(level, block.id);
            // 层级回退时截断更深的栈，避免错挂
            while (stack.size() > level + 1) stack.remove(stack.size() - 1);
            out.add(block);
        }
        if (!out.isEmpty()) CLBC_Util.log("游离代码注释区已还原: " + out.size() + " 个积木, "
                + (group + 1) + " 组未连接积木");
        return out;
    }

    /** 按未转义的 '|' 切分字段（转义的 \| 不切） */
    private static String[] splitRow(String s) {
        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            if (c == '\\' && k + 1 < s.length() && s.charAt(k + 1) == '|') {
                cur.append("\\|"); k++; continue;
            }
            if (c == '|') { parts.add(cur.toString()); cur.setLength(0); }
            else cur.append(c);
        }
        parts.add(cur.toString());
        return parts.toArray(new String[0]);
    }

    private static int parseLevel(String s) {
        if (s == null || s.length() < 2 || s.charAt(0) != 'L') return 0;
        try { return Integer.parseInt(s.substring(1)); } catch (NumberFormatException ex) { return 0; }
    }
}
