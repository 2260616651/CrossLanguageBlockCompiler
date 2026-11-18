package clbc.ui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 积木几何计算（纯数据：不依赖 OpenGL、不依赖 Java2D）。
 *
 * 造型约定：
 *  · 连接口在**左侧**：顶边一条「燕尾凹槽」，底边同位置一条互补的
 *    「燕尾凸起」，上一块的凸起正好填进下一块的凹槽，堆叠无缝。
 *  · 四角 4px 小圆角；帽块顶边为圆弧拱起。
 *  · 带口块是真正的 **C 形**：顶栏 / 左臂 / 底条围出右侧开口的内腔；
 *    多个口时在中间插入分隔栏 → C(1口) / E(2口) / 任意 N 口（if → else if → else 无限串接）。
 *  · 报告块为胶囊形，无连接口。
 *
 * 输出两类数据，供 GL 画布与 Java2D 缩略图共用，保证两边像素一致：
 *  · {@link Shape#ox}/{@link Shape#oy}：闭合轮廓点列 → 1px 描边
 *  · {@link Shape#bands}：水平扫描条带（每 6 个 float = y0,l0,r0,y1,l1,r1）→ 纯色填充
 *    用扫描条带而非三角扇，是因为 C 形是凹多边形，三角扇会溢出到内腔里。
 */
public final class CLBC_BlockShape {

    private CLBC_BlockShape() {}

    // ================= 几何常量 =================
    // 以 4px 栅格为单位推导的一组比例：圆角=1格，凹槽起点=3格、宽=8格、深=2格，
    // C 口左臂=4格、内腔留白=2格，帽高=16px。整数倍关系保证缩放时轮廓对齐、不糊边。
    // 凹槽不是尖角梯形，而是「两段圆弧 + 斜边 + 平底」的圆润 U 形。
    public static final float GRID_UNIT  = 4f;
    public static final float CORNER     = 4f;    // 1 * GRID_UNIT
    public static final float NOTCH_X    = 12f;   // 3 * GRID_UNIT（NOTCH_START_PADDING）
    public static final float NOTCH_W    = 32f;   // 8 * GRID_UNIT（连接口标称宽）
    public static final float NOTCH_PATH_W = 36f; // 凹槽路径实际跨度
    public static final float NOTCH_D    = 8f;    // 2 * GRID_UNIT（凹槽深 = 凸起高）
    public static final float ARM_W      = 16f;   // 4 * GRID_UNIT（C 口左臂宽）
    public static final float INNER_SPACE = 8f;   // 2 * GRID_UNIT（C 口内腔左留白）
    // #133：在 #132 基础上继续加高，解决「积木偏矮 / 内嵌口 notch 最低点侵入下拉面板」：
    //  · 栏高提高后，内嵌窗口与块底连接口之间留出明显间距，下拉弹层不再吞掉块底 notch；
    //  · ROUND_H 同步提到 44（与语句块同高），保证内嵌的报告块整块落入窗口、胶囊两端不外露成「半圆」；
    //  · 此处常量是几何的唯一数据源，GL 画布与 Java2D 缩略图共用，改这里即全局生效。
    public static final float TOP_H      = 52f;   // 首条标签栏高（类/方法/事件首栏 + 内嵌口留呼吸空间）
    public static final float BAR_H      = 48f;   // 中间分隔栏高（else if / else）
    public static final float BOTTOM_H   = 16f;   // 4 * GRID_UNIT，底条高（保持，避免 C 口底条过厚）
    public static final float MIN_SLOT_H = 28f;   // 空口最小高度（随整体加高微调）
    public static final float HAT_H      = 16f;   // 帽块拱高
    public static final float HAT_W      = 96f;   // 帽块拱顶的水平跨度
    public static final float STACK_H    = 52f;   // 语句块高（#133：继续加高）
    public static final float ROUND_H    = 44f;   // 报告块高（#133：提到与语句块同高，内嵌报告块不再溢出窗口）
    public static final float CHILD_GAP  = 0f;    // 堆叠无缝（由圆润接口咬合）
    public static final float BODY_PAD   = 4f;
    public static final float MIN_W      = 120f;
    public static final float CHILD_INSET = 0f;   // 子块左边缘紧贴 C 口左臂内沿（16px）
    /** C/E 形底口「嘴」：左下角矩形凹口，嵌套块的最后一块从其退出（if/else 外形） */
    public static final float MOUTH_D    = 18f;   // 嘴深
    public static final float MOUTH_W    = ARM_W + 12f; // 嘴宽 = 左臂 + 余量

    /** 内嵌输入窗口尺寸（胶囊形数值槽 / 六边形布尔槽） */
    public static final float SLOT_H     = 30f;   // 槽高（#133：微调，内嵌窗口/下拉字段不贴块顶底，下拉弹层不与 C 口重叠）
    public static final float SLOT_PAD   = 10f;
    public static final float SLOT_MIN_W = 44f;   // 最小宽度：保证是「胶囊」而非正圆
    /** 内嵌【报告子块】（child）窗口的最大显示宽度。
     *  【实测修复·蓝色积木截断】旧值 240 会把稍长的报告块（如 "CLBC_GL_API.setUserUiScale(...)"）
     *  压到 240px 再配合绘制层 setClipLogical 裁掉右侧文字——用户明确「蓝色积木截断没修」。
     *  正解与纯文本窗口一致：宽度随 child 真实内容扩展，不截断；这里只保留一个极大值
     *  防止 pathological 情形（数千字符拼接）无限撑爆顶点缓冲，正常代码永远碰不到。 */
    public static final float SLOT_MAX_W = 4096f;
    /** 方法/类定义参数窗口末尾的“添加参数”圆圈加号按钮尺寸 */
    public static final float PLUS_BTN_W = 22f;
    /**
     * 六边形六个内角均为 120° 时，左右尖角的水平跨度。
     * 推导：顶/底边水平，斜边与水平夹角 60° → t = (h/2)/tan60° = h/(2√3)。
     * 该式与宽度 w 无关，因此被撑得很长的布尔块同样满足「每个角 120°」。
     * 四个钝角 = 180° − 60° = 120°；左右尖角 = 2 × 60° = 120°。
     */
    public static float hexTip(float h) {
        return (float) (h / (2.0 * Math.sqrt(3.0)));
    }
    public static final float SLOT_HEX_TIP = hexTip(SLOT_H); // 空布尔槽默认尖角（120°）
    public static final float SEG_GAP    = 4f;
    /** 下拉字段右侧 ▾ 箭头预留宽度 */
    public static final float DROPDOWN_ARROW_W = 16f;
    /** 空值候选（如「无修饰符」）在字段与弹层中的显示占位；不写回数据，空串仍是合法候选 */
    public static final String DROPDOWN_EMPTY_LABEL = "(无)";

    /** 下拉字段/候选行的显示文本：空值显示占位符，避免下拉看起来像一块没有内容的色块。
     *  中文化只作用于显示（CLBC_I18n），内部 value / 生成代码仍是原始 Java 名，不受影响。 */
    public static String dropdownDisplay(String v) {
        if (v == null || v.isEmpty()) return DROPDOWN_EMPTY_LABEL;
        return CLBC_I18n.display(v);
    }

    /** 文本宽度供给者：GL 用字体图集，缩略图用 FontMetrics，几何算法本身与字体无关 */
    public interface TextMeasurer { float width(String s); }

    /** 文本宽度兜底（无字体环境 / 离线测试）：按字符数粗估 */
    public static final TextMeasurer ROUGH = s -> s == null ? 0 : s.length() * 9f;

    /**
     * 一行标签被切成「普通文字段 / 参数输入槽段」，GL 画布与缩略图共用，
     * 使宽度测算、绘制、命中三者不会各算一套。
     */
    public static final class Segment {
        public final String text;
        public final boolean arg;   // true = 内嵌输入窗口
        public final boolean hex;   // true = 六边形布尔窗口
        public final boolean dropdown;  // true = 枚举下拉字段（深色块 + ▾，不可自由输入）
        public final String[] options;  // 下拉候选（dropdown 为 false 时 null）
        public final float width;
        public final float height;  // 窗口高：嵌入报告块时随块高变化
        public final CLBC_Block.Input input;  // 绑定的内嵌窗口（旧工程为 null）
        /** true = 画一个圆圈加号按钮（方法/类定义参数窗口末尾的“添加参数”图标） */
        public final boolean plus;
        Segment(String text, boolean arg, boolean hex, float width, float height,
                CLBC_Block.Input input, boolean dropdown, String[] options) {
            this(text, arg, hex, width, height, input, dropdown, options, false);
        }
        Segment(String text, boolean arg, boolean hex, float width, float height,
                CLBC_Block.Input input, boolean dropdown, String[] options, boolean plus) {
            this.text = text == null ? "" : text;
            this.arg = arg;
            this.hex = hex;
            this.width = width;
            this.height = height;
            this.input = input;
            this.dropdown = dropdown;
            this.options = options;
            this.plus = plus;
        }
        /** 圆圈加号按钮段 */
        static Segment plus() {
            return new Segment("", false, false, PLUS_BTN_W, SLOT_H, null, false, null, true);
        }
        Segment(String text, boolean arg, boolean hex, float width, float height,
                CLBC_Block.Input input) {
            boolean dd = input != null && input.isDropdown();
            this(text, arg, hex, width, height, input, dd, dd ? input.options : null);
        }
        Segment(String text, boolean arg, boolean hex, float width) {
            this(text, arg, hex, width, SLOT_H, null);
        }
        Segment(String text, boolean arg, float width) { this(text, arg, false, width); }
    }

    /** 依内嵌窗口计算片段宽度/高度；旧工程无 Input 时退回纯文本测算 */
    private static Segment argSeg(CLBC_Block b, int row, int ordinal, String value,
                                  boolean hex, TextMeasurer m) {
        CLBC_Block.Input in = b == null ? null : b.inputFor(row, ordinal);
        boolean dd = in != null && in.isDropdown() && in.child == null;
        float w, h = SLOT_H;
        if (in != null && in.child != null) {
            // 窗口被一块报告积木占满：按该块真实尺寸撑开。
            // 实测修复：长字符串拼接（如源码里的 testCode）会被转成 core.add 报告块链，
            // 量出上千像素宽，把整个输入窗口乃至父容器撑出画布，注释便签随即无处安放、
            // 全挤在一起互相重叠。这里和纯文本分支一样封顶 SLOT_MAX_W：只约束显示宽度，
            // child 数据 / key / 转译结果一律不动（窗口内按裁剪绘制，拖动命中同源）。
            CLBC_BlockShape.layout(in.child, Float.MAX_VALUE, m, true);
            w = Math.min(SLOT_MAX_W, Math.max(SLOT_MIN_W, in.child.w + 8f));
            h = Math.max(SLOT_H, in.child.h);
        } else {
            String v = in == null ? value : in.code();
            w = Math.max(hex ? SLOT_MIN_W + SLOT_HEX_TIP : SLOT_MIN_W,
                         m.width(v) + (hex ? 2 * SLOT_HEX_TIP + 2 * SLOT_PAD : 2 * SLOT_PAD));
            // 下拉字段右侧要放 ▾ 箭头，测宽时先留出，保证绘制/命中/preferredWidth 同源
            if (dd) w += DROPDOWN_ARROW_W;
            // 用户要求「不要截断行」：文本窗口宽度随内容按需扩展，不再封顶，
            // 让整段文字完整显示（绘制层 fit 因窗口已足够宽，不会再出现省略号）。
        }
        return new Segment(value, true, in != null ? in.hex : hex, w, h, in,
                dd, dd ? in.options : null);
    }

    /**
     * 生成第 slot 行的显示片段。
     *  · 容器块：分支标签为文字段，该口条件（Slot.expr）为参数槽段；
     *  · 语句 / 报告块：以 b.text 为底本，按 b.args 在文本中出现的顺序切出参数槽。
     * 参数找不到时不硬切，整行退化为普通文字，保证旧工程永远画得出来。
     */
    public static List<Segment> segments(CLBC_Block b, int slot, TextMeasurer tm) {
        TextMeasurer m = tm == null ? ROUGH : tm;
        ArrayList<Segment> out = new ArrayList<>();
        if (b == null) return out;
        // 调用积木（ops.call）有专属片段布局：首栏 [类▾][方法▾]( 参数内嵌口… )，
        // 函数式参数不在此栏，作为 C 口由 draw() 在口腔内绘制子块。与下拉/内嵌口共用 argSeg。
        if ("ops.call".equals(b.key)) return callSegments(b, slot, m);
        String label;
        List<String> args = new ArrayList<>();
        boolean container = isContainer(b.kind == null ? "stack" : b.kind);
        // 带「多个显式输入窗口」的容器（方法 / 类定义）：按 args 切出多个内嵌窗口，
        // 让「返回类型」「修饰符」这类枚举槽有地方承载下拉；
        // 否则控制流容器（if / while）仍是「分支标签 + 单个布尔条件槽」。
        int inputCount = 0;
        for (CLBC_Block.Input in : b.inputs) if (in.row == slot) inputCount++;
        boolean multiArgs = container && inputCount >= 2;
        if (multiArgs) {
            label = b.slotLabel(slot);
            // 空值参数也必须保留占位：过滤掉它们会让后面的 ordinal 整体前移，
            // 下拉窗口就会错绑到别的参数上（详见 structuredArgs）。
            if (b.args != null) for (String a : b.args) args.add(a == null ? "" : a);
        } else if (container) {
            label = b.slotLabel(slot);
            if (b.hasSlots() && slot >= 0 && slot < b.slots.size()) {
                String e = b.slots.get(slot).expr;
                if (e != null && !e.isEmpty()) args.add(e);
            }
        } else {
            label = b.text == null ? "" : b.text;
            // 与容器分支一致：保留空串占位。过滤掉会令 ordinal 与 inputFor 错位
            // （如 data.var 的 name 为空时，init 窗口会错绑到 name 输入上）。
            if (b.args != null) for (String a : b.args) args.add(a == null ? "" : a);
        }
        // 容器行：分支标签 + 布尔条件六边形槽（如「如果 <条件> 那么」）
        if (container && !multiArgs) {
            String e = args.isEmpty() ? "" : args.get(0);
            CLBC_Block.Input condIn = b.inputFor(slot, 0);
            // 条件表达式为空但槽里塞了报告积木时，仍要生成参数槽段并让窗口被 child 撑大，
            // 否则「外面一层的内嵌口被内部积木撑大」在容器分支会整段丢失。
            if (e.isEmpty() && (condIn == null || condIn.child == null)) {
                if (!label.isEmpty()) out.add(new Segment(label, false, m.width(label)));
                return out;
            }
            int idx = e.isEmpty() ? -1 : label.indexOf(e);
            boolean hex = isBoolExpr(e);
            CLBC_Block.Input in = condIn;
            if (in != null) hex = in.hex;
            Segment seg = argSeg(b, slot, 0, e, hex, m);
            if (idx < 0) {
                if (!label.isEmpty()) out.add(new Segment(label, false, m.width(label)));
                out.add(seg);
                return out;
            }
            if (idx > 0) {
                String t = label.substring(0, idx);
                out.add(new Segment(t, false, m.width(t)));
            }
            out.add(seg);
            if (idx + e.length() < label.length()) {
                String t = label.substring(idx + e.length());
                out.add(new Segment(t, false, m.width(t)));
            }
            return out;
        }
        // 多参数容器（方法/类定义）不能靠「参数值在拼接文本里 indexOf」切槽：
        // 修饰符、参数表为空时模板只剩空格与括号，空串匹配会把后续 ordinal 整体前移，
        // 结果「返回类型下拉」套到方法名上、方法名窗口丢失。这里按 Input 的 ordinal
        // 结构化重建整行，保证每个窗口与数据模型一一对应。
        if (container && multiArgs) return structuredArgs(b, slot, label, m);
        if (args.isEmpty()) {
            if (!label.isEmpty()) out.add(new Segment(label, false, m.width(label)));
            return out;
        }
        int cursor = 0, matched = 0, ordinal = 0;
        for (String a : args) {
            if (a.isEmpty()) {
                // 空占位参数：保留 ordinal 与输入窗口一一对应（中间空名会让后续窗口整体前移，
                // 把 init 窗口错绑到 name 输入上）；但不做文本定位——indexOf("") 会原地死循环。
                out.add(argSeg(b, 0, ordinal, a, false, m));
                ordinal++;
                continue;
            }
            int idx = label.indexOf(a, cursor);
            if (idx < 0) { ordinal++; continue; }
            if (idx > cursor) {
                String t = label.substring(cursor, idx);
                out.add(new Segment(t, false, m.width(t)));
            }
            out.add(argSeg(b, 0, ordinal, a, false, m));
            cursor = idx + a.length();
            matched++;
            ordinal++;
        }
        if (matched == 0) {
            out.clear();
            if (!label.isEmpty()) out.add(new Segment(label, false, m.width(label)));
        } else if (cursor < label.length()) {
            String t = label.substring(cursor);
            out.add(new Segment(t, false, m.width(t)));
        }
        return out;
    }

    /**
     * ops.call 调用积木的专用片段：首栏 = [类下拉] [方法下拉] ( [参数内嵌口…] )，
     * 函数式参数不在此栏，而是作为 C 口（由 draw() 在口腔内绘制其子块）。
     * 与 dropdowns / 内嵌口共用 argSeg，绘制 / 命中 / 缩略图同源。
     */
    private static List<Segment> callSegments(CLBC_Block b, int slot, TextMeasurer m) {
        ArrayList<Segment> out = new ArrayList<>();
        if (slot != 0) return out;   // 回调口行：仅容纳子块，不画内嵌窗口
        List<clbc.blocks.CLBC_BlockLibrary.CallParam> plan = b.callParamPlan();
        // [类▾]
        out.add(new Segment(" ", false, m.width(" ")));
        out.add(argSeg(b, 0, 0, b.args != null && b.args.size() > 0 ? b.args.get(0) : "", false, m));
        out.add(new Segment(" ", false, m.width(" ")));
        // [方法▾]
        out.add(argSeg(b, 0, 1, b.args != null && b.args.size() > 1 ? b.args.get(1) : "", false, m));
        // 参数区
        out.add(new Segment(" ( ", false, m.width(" ( ")));
        if (plan != null) {
            for (clbc.blocks.CLBC_BlockLibrary.CallParam p : plan)
                if (!p.functional) out.add(argSeg(b, 0, p.inlineOrdinal, "", false, m));
        } else {
            for (CLBC_Block.Input in : b.inputs)
                if (in.row == 0 && in.ordinal >= 2) out.add(argSeg(b, 0, in.ordinal, in.value, false, m));
        }
        out.add(new Segment(" )", false, m.width(" )")));
        return out;
    }

    /**
     * 多参数容器首栏的结构化片段，兼容两套 args 布局：
     *  · 导入布局（4 参）：[修饰符▾] [返回类型▾] [名称] ( [参数表] )
     *  · 定义表布局（3 参）：[返回类型▾] [名称] ( [参数表] )
     * 参数表恒为最后一个 ordinal；空值参数照样生成窗口（空串是合法候选：
     * 包可见修饰符 / 无形参），因此窗口数恒等于 args 数，ordinal 不会漂移。
     */
    private static List<Segment> structuredArgs(CLBC_Block b, int slot, String label, TextMeasurer m) {
        ArrayList<Segment> out = new ArrayList<>();
        int n = b.args == null ? 0 : b.args.size();
        if (n == 0) {
            if (label != null && !label.isEmpty()) out.add(new Segment(label, false, m.width(label)));
            return out;
        }
        int paramsOrdinal = n - 1;
        for (int ordinal = 0; ordinal < n; ordinal++) {
            if (ordinal > 0) {
                String gap = ordinal == paramsOrdinal ? "( " : " ";
                out.add(new Segment(gap, false, m.width(gap)));
            }
            String a = b.args.get(ordinal);
            out.add(argSeg(b, slot, ordinal, a == null ? "" : a, false, m));
        }
        out.add(new Segment(" )", false, m.width(" )")));
        // 圆圈加号：所有内嵌参数窗口之后的“添加参数”图标（Scratch 风格变长参数入口）。
        // 纯视觉段：绘制层按 plus 画图标，几何层经 segmentsWidth 自动计入宽度，不溢出积木。
        out.add(Segment.plus());
        return out;
    }

    /** 条件表达式 → 六边形布尔槽；普通字面量 → 白色胶囊数值槽 */
    private static boolean isBoolExpr(String e) {
        if (e == null || e.isEmpty()) return false;
        for (int i = 0; i < e.length(); i++) {
            char ch = e.charAt(i);
            if (ch == '=' || ch == '<' || ch == '>' || ch == '!' || ch == '&' || ch == '|') return true;
        }
        return e.contains("且") || e.contains("或") || e.contains("不成立") || e.contains("包含");
    }

    /** 一行的总显示宽度（供 preferredWidth 与绘制共用） */
    public static float segmentsWidth(List<Segment> segs) {
        float w = 0;
        if (segs == null) return w;
        for (Segment s : segs) w += s.width + SEG_GAP;
        return Math.max(0f, w - SEG_GAP);
    }

    /** 帽块首栏可编辑名称框宽度：按 b.name 实测文本自适应，避免长类名画到积木外（悬浮重影）。
     *  绘制层(drawLabels) 与几何层(preferredWidth) 共用同一公式，永不漂移。 */
    public static float nameFieldWidth(CLBC_Block b, String kind, TextMeasurer tm) {
        float minW = "class".equals(kind) ? 80f : 60f;
        String name = b == null || b.name == null ? "" : b.name;
        TextMeasurer m = tm == null ? ROUGH : tm;
        return Math.max(minW, m.width(name) + 16f);   // 16 = 左右内边距 + ▾ 余量
    }

    // ================= 结果载体 =================
    public static final class Shape {
        public final String kind;
        public final float w, h;              // 逻辑宽 / 高（不含底部凸起 NOTCH_D）
        public final int slotCount;
        public final float[] slotY;           // 每口内腔顶 y
        public final float[] slotH;           // 每口内腔高
        public final float[] barTop;          // 每条标签栏顶 y（长度 = slotCount）
        /** 每条标签栏实际高度（非容器长度 1 = 块高）。内嵌口被内部报告块撑大时，
         *  该行栏高随之增长；GL 画布与缩略图都读这里定位文字/窗口基线，不再各用常量。 */
        public final float[] barH;
        public final float[] ox, oy;          // 闭合轮廓点列
        public final int n;
        public final float[] bands;           // 扫描条带：y0,l0,r0,y1,l1,r1
        public final int bandCount;

        Shape(String kind, float w, float h, int slotCount, float[] slotY, float[] slotH,
              float[] barTop, float[] barH, float[] ox, float[] oy, int n, float[] bands, int bandCount) {
            this.kind = kind; this.w = w; this.h = h;
            this.slotCount = slotCount; this.slotY = slotY; this.slotH = slotH; this.barTop = barTop;
            this.barH = barH;
            this.ox = ox; this.oy = oy; this.n = n;
            this.bands = bands; this.bandCount = bandCount;
        }

        /** 第 i 口内子块相对本块左上的偏移 */
        public float[] childOffset(int i) {
            int k = Math.max(0, Math.min(slotCount - 1, i));
            // y 不额外留白：子块顶凹槽正好套住内腔上沿的 U 形凸起（咬合）
            return new float[]{ ARM_W + CHILD_INSET, slotY[k] };
        }

        /** 父块内腔留给子块的可用宽度 */
        public float childWidth() { return Math.max(60f, w - ARM_W - CHILD_INSET - 8f); }
    }

    // ================= 布局（统一入口：GL 与缩略图都走这里） =================

    /**
     * 递归测算积木树并写回 b.w/b.h（writeBack=true 时还写 b.shape）。
     * 高度完全由几何常量 + 子块高度决定，与字体无关，因此缩略图与画布结构严格一致。
     */
    public static Shape layout(CLBC_Block b, float maxW, TextMeasurer tm, boolean writeBack) {
        if (b == null) return build("stack", MIN_W, null, false);
        String kind = b.kind == null ? "stack" : b.kind;
        boolean container = isContainer(kind);

        // ===== 布局缓存（性能红线）=====
        // 旧实现每帧对 685 块积木至少 2 次全树 layout，每次逐字 measureText + new Segment/band 数组，
        // 是「渲染某个类持续卡」的真凶。这里用内容指纹缓存：内容没变就直接复用上次的 shape，
        // O(1) 返回；指纹包含本块所有影响几何的字段与全部子块，任一变化自动失效，零漏点。
        // writeBack=false 的纯查询（shapeOf）不走缓存写，仅读已有 shape。
        int h = layoutHash(b);
        if (writeBack && h == b.layoutHash && b.shape != null && b.w > 0) return b.shape;

        // 先以自然宽度递归测量语句子块：既要算各口高度，也要得到最宽子块宽度，
        // 用于把容器自身宽度撑开到足以容纳子块（否则子块会溢出右边界）。
        // 关键：子块按 Float.MAX_VALUE 测量，绝不被父块当前（尚过小的）宽度反压。
        float innerMax = 0f;
        float[] sh = null;
        int slots = container ? b.slotCount() : 0;
        if (container) {
            sh = new float[slots];
            for (int i = 0; i < slots; i++) {
                float acc = 0;
                if (!b.collapsed) {
                    for (CLBC_Block c : b.slotChildren(i)) {
                        layout(c, Float.MAX_VALUE, tm, writeBack);
                        acc += c.h + CHILD_GAP;
                        innerMax = Math.max(innerMax, c.w);
                    }
                }
                // 内腔高度必须严格等于子块总高：子块底凸起占 [h, h+NOTCH_D]，
                // 内腔下沿凹槽从 slotY+sh[i] 起同样向下 NOTCH_D，只有 sh[i]==acc 时两者逐点重合。
                // 旧式 acc + 2*BODY_PAD 会把下沿凹槽整体下移 8px，与凸起错开 → 底部一条白缝。
                // 空口没有子块时才用最小高度留出可投放空间。
                sh[i] = acc > 0f ? Math.max(MIN_SLOT_H, acc) : MIN_SLOT_H;
            }
        }

        float w = Math.max(MIN_W, Math.min(preferredWidth(b, kind, tm, innerMax), maxW));
        // 内嵌口被内部报告块撑大 → 该行栏高、整块高度同步增长（外层被内层撑大）
        float[] rowH = rowHeights(b, kind, tm);
        Shape s = build(kind, w, sh, rowH, isHat(kind));
        if (writeBack) { b.w = s.w; b.h = s.h; b.shape = s; b.layoutHash = h; }
        return s;
    }

    /** 布局世代：字体重建（字号变化）后 measureText 结果变了，必须让全部分指纹缓存失效，
     *  否则积木宽度还停留在旧字号的测量结果。字体变化时调 {@link #invalidateAllLayout()} bump。 */
    private static int layoutGeneration = 0;
    public static void invalidateAllLayout() { layoutGeneration++; }

    /** 内容指纹：覆盖一切会影响本块几何的字段 + 全部子块指纹。
     *  只做 char 混合与引用比较，不调用 measureText/不分配数组，比 layout 主体便宜一个数量级。
     *  任一字段变化 → hash 变 → layout 自动重算，无需在各修改点手工置脏（杜绝漏点）。 */
    private static int layoutHash(CLBC_Block b) {
        int h = 17 + layoutGeneration;
        h = h * 31 + hash(b.kind);
        h = h * 31 + hash(b.text);
        h = h * 31 + hash(b.name);
        h = h * 31 + hash(b.expr);
        h = h * 31 + hash(b.retType);
        h = h * 31 + hash(b.modifiers);
        h = h * 31 + (b.collapsed ? 1 : 0);
        h = h * 31 + (b.isReporter ? 1 : 0);
        h = h * 31 + hash(b.key);
        if (b.args != null) for (String a : b.args) h = h * 31 + hash(a);
        if (b.inputs != null) for (CLBC_Block.Input in : b.inputs) {
            h = h * 31 + hash(in.value);
            h = h * 31 + (in.hex ? 1 : 0);
            h = h * 31 + (in.options == null ? 0 : in.options.length);
            h = h * 31 + (in.child == null ? 0 : layoutHash(in.child));
        }
        int slotN = b.slotCount();
        h = h * 31 + slotN;
        for (int i = 0; i < slotN; i++) {
            h = h * 31 + hash(b.slotLabel(i));
            h = h * 31 + hash(b.hasSlots() ? b.slots.get(i).expr : null);
            for (CLBC_Block c : b.slotChildren(i)) h = h * 31 + layoutHash(c);
        }
        return h;
    }

    private static int hash(String s) { return s == null ? 0 : s.hashCode(); }

    public static Shape layout(CLBC_Block b, float maxW, TextMeasurer tm) {
        return layout(b, maxW, tm, true);
    }

    /** 取得（或重建）某块的几何；用于 childOffset / hitTest 等不需要重新测量的场合 */
    public static Shape shapeOf(CLBC_Block b) {
        if (b == null) return build("stack", MIN_W, null, false);
        if (b.shape != null && Math.abs(b.shape.w - b.w) < 0.5f) return b.shape;
        return layout(b, Float.MAX_VALUE, ROUGH, false);
    }

    /** 期望宽度（供 GL 画布与 Java2D 缩略图共用，保证两者同宽）。
     * 内部会先以自然宽度测量语句子块，得到最宽子块驱动容器自身宽度。 */
    public static float preferredWidth(CLBC_Block b, String kind, TextMeasurer tm) {
        float innerMax = 0f;
        // 单口退化容器（unit.method / unit.constructor 的 slots==null，子块在 children 里）
        // 也必须参与测量：只看 hasSlots() 会让这类容器永远停在 method 基准宽 300，
        // 内嵌积木（含很长的 raw/code 块）右半截画到块外，且窗口中心命中不到。
        if (isContainer(kind) && !b.collapsed) {
            for (int i = 0; i < b.slotCount(); i++)
                for (CLBC_Block c : b.slotChildren(i)) {
                    CLBC_BlockShape.layout(c, Float.MAX_VALUE, tm, true);
                    innerMax = Math.max(innerMax, c.w);
                }
        }
        return preferredWidth(b, kind, tm, innerMax);
    }

    /** 期望宽度（已测得最宽子块 innerMax 时复用，避免重复递归测量）。 */
    public static float preferredWidth(CLBC_Block b, String kind, TextMeasurer tm, float innerMax) {
        TextMeasurer m = tm == null ? ROUGH : tm;
        float row = segmentsWidth(segments(b, 0, m));
        float base;
        switch (kind) {
            case "class"  -> base = 360f;
            case "method" -> base = 300f;
            case "cblock" -> base = 340f;
            case "round", "hex"  -> base = Math.max(80f, row + 32f);
            default       -> base = Math.max(MIN_W, row + 28f);
        }
        if (isContainer(kind)) {
            for (int i = 0; i < b.slotCount(); i++)
                base = Math.max(base, segmentsWidth(segments(b, i, m)) + 56f);
            // 最宽语句子块驱动容器宽度：容器必须张开到容纳其内嵌积木，
            // 否则嵌套的 if / 声明 / 调用块（及其自身的内嵌 oval·hexagon）会溢出右边界。
            base = Math.max(base, innerMax + ARM_W + CHILD_INSET + 16f);
        }
        // 帽块（class / method / event）首栏在段序列之后还会叠加一个可编辑名称框
        // （drawLabels 里的 textFieldAuto，宽 80/60 + 8 间距）。几何若不为它预留宽度，
        // 名称文本（如类名 CLBC_BlockDefinition）就会画到积木右边界之外，看着像悬浮重影。
        if (isHat(kind) && !"unit.method".equals(b.key)) {
            float nameW = nameFieldWidth(b, kind, m) + 8f;
            base = Math.max(base, row + nameW + 28f);
        }
        return base;
    }

    public static boolean isContainer(String kind) {
        return "class".equals(kind) || "method".equals(kind) || "cblock".equals(kind);
    }
    /** event = 事件帽块（如程序入口 / 事件触发）：
     *  有拱顶、有底部燕尾凸起，但**不是容器**，后面平铺堆叠语句块而非把它们吞进 C 口。 */
    public static boolean isHat(String kind) {
        return "class".equals(kind) || "method".equals(kind) || "event".equals(kind);
    }

    // ================= 几何构造 =================

    private static final LinkedHashMap<String, Shape> CACHE = new LinkedHashMap<String, Shape>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Shape> e) { return size() > 512; }
    };

    /** 按 kind / 宽 / 各口高度构造几何（带缓存，尺寸不变时零重算）。
     *  不传 rowH 时保持旧行为：栏高用常量 TOP_H / BAR_H / STACK_H / ROUND_H。 */
    public static Shape build(String kind, float w, float[] slotH, boolean hat) {
        return build(kind, w, slotH, null, hat);
    }

    /** 每行所需最小栏高（含内嵌口被内部报告块撑大的高度 + 上下留白）。
     *  GL 画布与缩略图共用，保证两边算出来的高度完全一致。 */
    public static float[] rowHeights(CLBC_Block b, String kind, TextMeasurer tm) {
        TextMeasurer m = tm == null ? ROUGH : tm;
        int n = isContainer(kind) ? Math.max(1, b.slotCount()) : 1;
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            float mx = 0f;
            for (Segment s : segments(b, i, m)) mx = Math.max(mx, s.height);
            out[i] = mx + 2 * BODY_PAD + 4f;
        }
        return out;
    }

    /** rowH[i] = 第 i 行内嵌口所需高度；栏高取 max(常量, rowH[i])，实现「外层被内层撑大」。 */
    public static Shape build(String kind, float w, float[] slotH, float[] rowH, boolean hat) {
        kind = kind == null ? "stack" : kind;
        w = Math.max(MIN_W, w);
        boolean container = isContainer(kind);
        int n = container ? Math.max(1, slotH == null ? 1 : slotH.length) : 0;

        float[] sh = new float[n], sy = new float[n], bt = new float[n];
        float[] barH = container ? new float[n] : new float[1];
        float h;
        if (container) {
            for (int i = 0; i < n; i++)
                barH[i] = Math.max(i == 0 ? TOP_H : BAR_H, rowH == null || i >= rowH.length ? 0f : rowH[i]);
            float cursor = barH[0];
            for (int i = 0; i < n; i++) {
                if (i > 0) cursor += barH[i];
                bt[i] = cursor - barH[i];
                sy[i] = cursor;
                sh[i] = Math.max(MIN_SLOT_H, slotH == null ? MIN_SLOT_H : slotH[i]);
                cursor += sh[i];
            }
            h = cursor + BOTTOM_H;
        } else {
            float base = ("round".equals(kind) || "hex".equals(kind)) ? ROUND_H : STACK_H;
            barH[0] = Math.max(base, rowH == null || rowH.length == 0 ? 0f : rowH[0]);
            h = barH[0];
        }

        StringBuilder kb = new StringBuilder().append(kind).append('|').append(Math.round(w * 10))
                .append('|').append(Math.round(h * 10)).append('|').append(hat ? 1 : 0);
        for (float v : sh) kb.append('|').append(Math.round(v * 10));
        for (float v : barH) kb.append('|').append(Math.round(v * 10));
        String key = kb.toString();
        Shape cached = CACHE.get(key);
        if (cached != null) return cached;

        Path p = new Path();
        float c = CORNER, nx = NOTCH_X, nw = NOTCH_PATH_W, nd = NOTCH_D;

        if ("round".equals(kind)) {
            float r = h / 2f;
            p.p(r, 0);
            p.p(w - r, 0);
            p.arc(w - r, r, r, -P2, P2, 12);          // 右半圆 → (w-r, h)
            p.p(r, h);
            p.arc(r, r, r, P2, 3 * P2, 12);           // 左半圆 → (r, 0)=起点
        } else if ("hex".equals(kind)) {
            // 布尔报告块：左右带尖角的六边形（与 drawHexWindow / 缩略图同顶点公式，保证画布一致）
            // t 由块高推导，六个内角恒为 120°；块被内嵌子块撑高时尖角随之同比放大。
            float t = hexTip(h);
            p.p(t, 0);
            p.p(w - t, 0);
            p.p(w, h / 2f);
            p.p(w - t, h);
            p.p(t, h);
            p.p(0, h / 2f);
        } else {
            if (hat) {
                // 帽顶三次贝塞尔：只有左端约 96px 鼓起（峰值约 -16.5），
                // 其后是平顶，再接右上角圆角。
                p.p(0, 0);
                float hw = Math.min(HAT_W, Math.max(48f, w - 2 * c));
                p.cubic(0, 0, hw * 25f / 96f, -22f, hw * 71f / 96f, -22f, hw, 0, 6);
                p.p(w - c, 0);
                p.arc(w - c, c, c, -P2, 0, 3);        // 右上角 → (w, c)
            } else {
                p.p(c, 0);
                p.p(nx, 0);
                p.notchDown(nx, 0, +1, nd);           // 顶凹槽：圆润 U 形，非尖角梯形
                p.p(w - c, 0);
                p.arc(w - c, c, c, -P2, 0, 3);        // 右上角 → (w, c)
            }

            if (container) {
                // ===== C / E / N 口形（内腔向右开口、由 scan() 镂空）=====
                // 轮廓（顺时针）：顶栏整宽 → 左臂(ARM_W) → [分隔栏整宽 → 左臂]×N → 底条整宽。
                // 内腔 y 区间的水平扫描线只与「左边界 x=0」和「左臂内沿 x=ARM_W」相交，
                // 因此只填出左臂，内腔保持镂空；分隔栏区间则与 x=0 / x=w 相交 → 整宽横条。
                // 参考截图像素实测：内腔行橙色右边界 = 16px，与 ARM_W=16 完全一致。
                // 每个内腔的上下沿各有一对圆润连接口，与 stack 块「顶凹槽/底凸起」严格互补：
                //   上沿 = 向下凸的 tab  （notchDown dir=-1，与块底凸起同形同向）
                //   下沿 = 向下凹的 notch（notchDown dir=+1，与块顶凹槽同形同向）
                // 关键是对齐：childOffset = ARM_W + CHILD_INSET = 16，子块自己的顶凹槽/底凸起
                // 在其局部 [NOTCH_X, NOTCH_X + NOTCH_PATH_W] = [12,48]，绝对区间即 [28,64]。
                // 故 C 口连接口取 tabX0 = ARM_W + nx = 28、tabX1 = tabX0 + nw = 64，
                // 与子块接口逐点重合；若从 ARM_W=16 起会错开 12px，咬合处露出白三角。
                // scan() 在这些 y 上拿到 4 个交点，配成 [0,ARM_W] 左臂 + 连接口实体两段。
                float tabX0 = ARM_W + nx, tabX1 = tabX0 + nw;
                p.p(w, barH[0] - c);
                p.arc(w - c, barH[0] - c, c, 0, P2, 3); // 顶栏右下角 → (w-c, barH[0])
                p.p(tabX1, barH[0]);                      // 顶栏底边 → tab 右端
                p.notchDown(tabX1, barH[0], -1, nd);      // 第 1 口上沿凸起，收在 (tabX0, barH[0])
                p.p(ARM_W, barH[0]);                      // 接回左臂内沿
                for (int i = 0; i < n; i++) {
                    float innerBottom = sy[i] + sh[i];
                    p.p(ARM_W, innerBottom);              // 左臂内沿下行到本口内腔底
                    p.p(tabX0, innerBottom);              // → 下沿凹槽左端
                    p.notchDown(tabX0, innerBottom, +1, nd); // 本口下沿凹槽，收在 (tabX1, innerBottom)
                    p.p(w, innerBottom);                  // 分隔栏 / 底条顶边 → 右
                    if (i < n - 1) {
                        p.p(w, sy[i + 1]);                // 分隔栏右端下行 → 下一口内腔顶
                        p.p(tabX1, sy[i + 1]);            // 分隔栏底边 → tab 右端
                        p.notchDown(tabX1, sy[i + 1], -1, nd); // 下一口上沿凸起
                        p.p(ARM_W, sy[i + 1]);            // 接回左臂内沿
                    } else {
                        p.p(w, h - c);                    // 底条右端下行
                    }
                }
            } else {
                // ---- 非容器：右边界纯色实心 ----
                p.p(w, h - c);
            }

            // ---- 底边：右侧圆角 + 中部圆润凸起（与下一块顶凹槽严格互补） ----
            p.arc(w - c, h - c, c, 0, P2, 3);
            p.p(nx + nw, h);
            p.notchDown(nx + nw, h, -1, nd);
            p.p(c, h);
            p.arc(c, h - c, c, P2, PI, 3);

            // ---- 左边界回到起点 ----
            p.p(0, hat ? 0 : c);
            if (!hat) p.arc(c, c, c, PI, 3 * P2, 3);  // 左上角 → (c,0)=起点
        }
        p.normalize();

        float[] ox = p.ax(), oy = p.ay();
        float[] bands = scan(ox, oy, p.size());
        Shape s = new Shape(kind, w, h, n, sy, sh, bt, barH, ox, oy, p.size(), bands, bands.length / 6);
        CACHE.put(key, s);
        return s;
    }

    private static final float PI = (float) Math.PI;
    private static final float P2 = (float) (Math.PI / 2);

    // ================= 轮廓点列小工具 =================
    private static final class Path {
        final ArrayList<Float> xs = new ArrayList<>(160), ys = new ArrayList<>(160);
        /** y 量化到 0.5px：贝塞尔采样点共用少量扫描断点，避免每块上百条带拖慢弱机 GPU */
        void p(float x, float y) { xs.add(x); ys.add(Math.round(y * 2f) / 2f); }
        void arc(float cx, float cy, float r, float a0, float a1, int seg) {
            for (int i = 0; i <= seg; i++) {
                float a = a0 + (a1 - a0) * i / seg;
                p(cx + (float) Math.cos(a) * r, cy + (float) Math.sin(a) * r);
            }
        }
        /** 三次贝塞尔折线近似 */
        void cubic(float x0, float y0, float x1, float y1,
                   float x2, float y2, float x3, float y3, int seg) {
            for (int i = 1; i <= seg; i++) {
                float t = i / (float) seg, u = 1 - t;
                p(u * u * u * x0 + 3 * u * u * t * x1 + 3 * u * t * t * x2 + t * t * t * x3,
                  u * u * u * y0 + 3 * u * u * t * y1 + 3 * u * t * t * y2 + t * t * t * y3);
            }
        }

        /**
         * 圆润 U 形连接口。
         * 从 (x,y) 沿 dir 方向横跨 36px，中途下沉 depth 再回到 y：
         * 三次贝塞尔圆角 → 斜边 → 平底 → 对称收回（而非尖角梯形）。
         * 顶边凹槽(dir=+1) 与底边凸起(dir=-1) 共用此形状，因此严格互补。
         */
        void notchDown(float x, float y, int dir, float depth) {
            float s = depth / 8f;
            cubic(x, y, x + dir * 2, y, x + dir * 3, y + s, x + dir * 4, y + 2 * s, 3);
            p(x + dir * 8, y + 6 * s);
            cubic(x + dir * 8, y + 6 * s, x + dir * 9, y + 7 * s,
                  x + dir * 10, y + 8 * s, x + dir * 12, y + 8 * s, 3);
            p(x + dir * 24, y + 8 * s);
            cubic(x + dir * 24, y + 8 * s, x + dir * 26, y + 8 * s,
                  x + dir * 27, y + 7 * s, x + dir * 28, y + 6 * s, 3);
            p(x + dir * 32, y + 2 * s);
            cubic(x + dir * 32, y + 2 * s, x + dir * 33, y + s,
                  x + dir * 34, y, x + dir * 36, y, 3);
        }

        /** 帽块拱顶：半椭圆弧 (0,y) → (w,y)，保留作兼容参考（新路径改用三次贝塞尔） */
        void hat(float w, float y, float bump, int seg) {
            for (int i = 1; i <= seg; i++) {
                double t = Math.PI * i / seg;
                p((float) (w / 2f - (w / 2f) * Math.cos(t)),
                  (float) (y - bump * Math.sin(t)));
            }
        }
        int size() { return xs.size(); }
        float[] ax() { float[] a = new float[xs.size()]; for (int i = 0; i < a.length; i++) a[i] = xs.get(i); return a; }
        float[] ay() { float[] a = new float[ys.size()]; for (int i = 0; i < a.length; i++) a[i] = ys.get(i); return a; }
        /** 去掉连续重复点 + 首尾重合点（闭合由 (n-1,0) 隐式表达） */
        void normalize() {
            for (int i = xs.size() - 1; i > 0; i--) {
                if (Math.abs(xs.get(i) - xs.get(i - 1)) < 0.001f && Math.abs(ys.get(i) - ys.get(i - 1)) < 0.001f) {
                    xs.remove(i); ys.remove(i);
                }
            }
            if (size() > 2 && Math.abs(xs.get(0) - xs.get(size() - 1)) < 0.001f
                    && Math.abs(ys.get(0) - ys.get(size() - 1)) < 0.001f) {
                xs.remove(size() - 1); ys.remove(size() - 1);
            }
        }
    }

    // ================= 水平扫描线填充 =================
    /**
     * 以所有顶点 y 为断点，逐带求轮廓边与水平线交点、排序配对，
     * 输出梯形条带（凸四边形，可直接用三角扇填充）。凹多边形（C 口）由此正确镂空。
     */
    private static float[] scan(float[] ox, float[] oy, int n) {
        TreeSet<Float> set = new TreeSet<>();
        for (int i = 0; i < n; i++) set.add(Math.round(oy[i] * 100f) / 100f);
        List<Float> yl = new ArrayList<>(set);
        ArrayList<Float> out = new ArrayList<>(yl.size() * 12);
        for (int k = 0; k + 1 < yl.size(); k++) {
            float ya = yl.get(k), yb = yl.get(k + 1);
            if (yb - ya < 0.02f) continue;
            float eps = Math.min(0.01f, (yb - ya) * 0.01f);
            float[] xa = crossings(ox, oy, n, ya + eps);
            float[] xb = crossings(ox, oy, n, yb - eps);
            if (xa.length != xb.length || xa.length < 2 || xa.length % 2 != 0) {
                // 顶点歧义兜底：用带中线的交点画等宽条带
                float[] xm = crossings(ox, oy, n, (ya + yb) / 2f);
                Arrays.sort(xm);
                for (int i = 0; i + 1 < xm.length; i += 2) {
                    out.add(ya); out.add(xm[i]); out.add(xm[i + 1]);
                    out.add(yb); out.add(xm[i]); out.add(xm[i + 1]);
                }
            } else {
                Arrays.sort(xa); Arrays.sort(xb);
                for (int i = 0; i + 1 < xa.length; i += 2) {
                    out.add(ya); out.add(xa[i]); out.add(xa[i + 1]);
                    out.add(yb); out.add(xb[i]); out.add(xb[i + 1]);
                }
            }
        }
        float[] r = new float[out.size()];
        for (int i = 0; i < r.length; i++) r[i] = out.get(i);
        return r;
    }

    /** 水平线 y 与轮廓所有边的交点（半开区间规则，水平边不计，避免顶点重复计数） */
    private static float[] crossings(float[] ox, float[] oy, int n, float y) {
        float[] tmp = new float[n];
        int cnt = 0;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            float y1 = oy[i], y2 = oy[j];
            float lo = Math.min(y1, y2), hi = Math.max(y1, y2);
            if (y >= lo && y < hi) {
                float t = (y - y1) / (y2 - y1);
                tmp[cnt++] = ox[i] + t * (ox[j] - ox[i]);
            }
        }
        return Arrays.copyOf(tmp, cnt);
    }

    /** 供自测：轮廓是否闭合、条带是否非空、每带左右是否有序 */
    public static boolean valid(Shape s) {
        if (s == null || s.n < 3 || s.bandCount < 1) return false;
        for (int i = 0; i < s.bandCount; i++) {
            int k = i * 6;
            if (s.bands[k + 1] > s.bands[k + 2] + 0.5f || s.bands[k + 4] > s.bands[k + 5] + 0.5f) return false;
            if (s.bands[k + 3] <= s.bands[k]) return false;
        }
        return true;
    }
}
