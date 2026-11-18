package clbc.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * 积木数据模型（无 JavaFX 节点）。
 * 原 JavaFX 版本用 Node 承载积木，这里改为纯数据 + 自绘：位置、类型、颜色、文字、可编辑名称。
 * 绘制与命中测试由 {@link CLBC_BlockRenderer} 完成，几何由 {@link CLBC_BlockShape} 提供。
 *
 * 多口容器（if / else if / else 链）：
 *  · {@link #slots} 为每个"口"（C 形内腔）保存一条分支标签 + 该分支的子块列表；
 *    1 口 = C 型，2 口 = E 型（if-else），N 口 = 任意长 else-if 链，无上限。
 *  · {@link #slotIndex} 标记扁平列表里某个子块属于父块的第几个口。
 *  · slots 为空时自动退化为「单口 = children」，旧工程 / 旧代码零改动兼容。
 *  · {@link #children} 恒为所有口 children 的并集（由 CanvasHost 每帧重建），供校验/撤销/转译复用。
 */
public class CLBC_Block {
    public int id;
    public String kind;     // stack / round / class / method / cblock
    public long color;
    public String text;     // 显示标签（来自语言文件模板填充）
    public String name;     // 类 / 方法块的 editable 名称
    public float x, y;      // 世界坐标（画布平移前）
    public float w = 120, h = 40;

    // ===== 互转结构化字段（语言文件为统一来源） =====
    public String key = "";                 // 规范积木键（语言文件索引，如 control.if / ops.call）
    public List<String> args = new ArrayList<>();   // 有序参数（表达式/标识符/字面量）
    public List<CLBC_Block> children = new ArrayList<>(); // 所有口子块的并集（每帧重建）
    public String expr = "";                // 内联表达式（reporter / raw）
    public String retType = "";             // 方法返回类型 / 变量类型
    public String modifiers = "public";     // 修饰符（public static ...）
    public int parentId = -1;               // 父块 id（扁平化导入后用于重建树）
    public int nestLevel = 0;               // 嵌套层级（缩进用）
    public boolean isReporter = false;      // 布尔/报告块形状
    public boolean collapsed = false;       // 折叠状态（双击切换，隐藏子块）
    public boolean invalid = false;         // 连接合法性标红（每帧由 CanvasHost 重算，瞬态）
    /** 隐式结构块（如 unit.file 文件头）：参与互转但画布不绘制、不可命中，用户看不到帽子/import 类声明 */
    public boolean hidden = false;

    // ===== 多口容器（C / E / 任意多分支） =====
    /** 一条分支：标签（如「如果」「否则如果」「否则」）+ 该口的子块 */
    public static final class Slot {
        public String label = "";     // 分支显示标签：「如果」「否则如果」「否则」
        public String expr = "";      // 该分支条件（else 为空）
        public final List<CLBC_Block> children = new ArrayList<>();
        public Slot() {}
        public Slot(String label) { this.label = label == null ? "" : label; }
        public Slot(String label, String expr) { this(label); this.expr = expr == null ? "" : expr; }
    }

    /** 口列表；null / 空 = 退化为 children 单口。持久化由 CLBC_ProjectIO 负责 */
    public List<Slot> slots = null;
    /** 本块作为子块时，落在父块的第几个口（0 基）。旧工程默认 0 */
    public int slotIndex = 0;
    /** 几何缓存（瞬态，不入工程文件）；由 CLBC_BlockShape.layout 写入 */
    public transient CLBC_BlockShape.Shape shape = null;
    /** 布局内容指纹（瞬态）：与当前内容 hash 相同则复用 shape，跳过整棵子树的重复 measureText。
     *  内容（text/name/args/inputs/slots/子块）任一变化都会令 hash 改变，从而自动重算，无需手工置脏。 */
    public transient int layoutHash = Integer.MIN_VALUE;
    /** 第 slot 行的片段缓存（瞬态）：drawLabels 与 layout 共用，避免同一帧内重复 segments() 测量 */
    public transient List<List<CLBC_BlockShape.Segment>> segCache = null;

    public CLBC_Block(int id, String kind, long color, String text) {
        this.id = id;
        this.kind = kind;
        this.color = color;
        this.text = text;
        if ("class".equals(kind)) this.name = "Main";
        else if ("method".equals(kind)) this.name = "run";
    }

    // ---------- 兼容访问器：调用方永远不必关心是否启用了多口 ----------

    /** 口数：未启用多口时恒为 1 */
    /**
     * 内嵌输入窗口。
     *  · pill = 文本/数字输入口：两端半圆 + 中间矩形（即「圆被切两半、中间长方体」的胶囊）。
     *  · hex  = 布尔条件输入口：左右带尖角的六边形。
     * 两者都不是「画个白框」，而是能真正承载一块报告积木（{@link #child}）的窗口；
     * child 为 null 时窗口内是可直接编辑的字面量文本（{@link #value}）。
     * child 是**自包含子树**：不进画布扁平列表，因此不参与 parentId / rebuildTree / 吸附，
     * 但会被撤销快照、工程文件、双向转译完整递归处理。
     */
    public static final class Input {
        public String value = "";        // 直接输入的文本（字面量 / 表达式）
        public boolean hex = false;      // true=六边形布尔槽；false=胶囊文本/数字槽
        public String accept = "any";    // number / string / boolean / any
        public int row = 0;              // 所属标签栏（容器多口时对应第几个口）
        public int ordinal = 0;          // 该行内第几个槽（与 args 序号对齐）
        public CLBC_Block child = null;  // 嵌入的报告积木（内嵌窗口真正承载的块）
        /**
         * 下拉候选。非 null 且非空 = 该槽只能从枚举里选，
         * 不能自由输入，也不接受拖入报告积木；渲染为「深色块 + ▾ 箭头」。
         * 典型用途：变量类型 / 方法返回类型 / 修饰符 / 复合赋值运算符 / 布尔字面量。
         */
        public String[] options = null;
        // 布局后的局部矩形（相对本块左上），每帧由渲染器写入，供命中测试使用；瞬态
        public transient float x, y, w, h;
        public Input() {}
        public Input(String value, boolean hex) { this.value = value == null ? "" : value; this.hex = hex; }
        public Input(String value, boolean hex, String accept) { this(value, hex); this.accept = accept; }
        public boolean isDropdown() { return options != null && options.length > 0; }
        /** 当前值在候选中的下标；不在候选里（自定义类型）返回 -1 */
        public int optionIndex() {
            if (!isDropdown()) return -1;
            for (int i = 0; i < options.length; i++) if (options[i].equals(value)) return i;
            return -1;
        }
        /** 该槽当前表达的代码文本：嵌入块优先，否则用直接输入的文本 */
        public String code() {
            if (child != null) return CLBC_Block.inputCode(child);
            return value == null ? "" : value;
        }
    }

    /** 所有内嵌输入窗口；顺序即行内从左到右的槽序 */
    public final List<Input> inputs = new ArrayList<>();

    /** 取某行第 ordinal 个槽（无则 null，旧工程自动退回"只画框"兼容模式） */
    public Input inputFor(int row, int ordinal) {
        for (Input in : inputs) if (in.row == row && in.ordinal == ordinal) return in;
        return null;
    }

    /** 该行是否有显式输入窗口 */
    public boolean hasInputs(int row) {
        for (Input in : inputs) if (in.row == row) return true;
        return false;
    }

    /** 按现有 args 建立输入窗口（代码导入 / 工具箱新建时调用，row=0 为默认行） */
    public void bindArgsAsInputs(int row, boolean[] hexFlags) {
        inputs.removeIf(in -> in.row == row);
        for (int i = 0; i < args.size(); i++) {
            String v = args.get(i) == null ? "" : args.get(i);
            boolean hex = hexFlags != null && i < hexFlags.length ? hexFlags[i] : false;
            Input in = new Input(v, hex);
            in.row = row; in.ordinal = i;
            inputs.add(in);
        }
    }

    /** 报告积木递归序列化为表达式文本（供内嵌窗口参与生成代码） */
    public static String inputCode(CLBC_Block b) {
        if (b == null) return "";
        String key = b.key == null ? "" : b.key;
        java.util.List<String> a = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Input in = b.inputFor(0, i);
            if (in == null) break;
            a.add(in.code());
        }
        // 无显式窗口时退回 args
        if (a.isEmpty() && b.args != null) a.addAll(b.args);
        // 按运算符优先级给子表达式补括号：嵌套块（如直接把 core.add 拖进 core.mul 左口）
        // 必须生成 (a + b) * c 而非 a + b * c，保证积木→代码运算顺序正确。
        // core.paren 自身已带括号，跳过避免双重包裹。
        String A = a.size() > 0 ? wrap(a.get(0), childOf(b, 0), key, 0) : "";
        String B = a.size() > 1 ? wrap(a.get(1), childOf(b, 1), key, 1) : "";
        String C = a.size() > 2 ? wrap(a.get(2), childOf(b, 2), key, 2) : "";
        return switch (key) {
            case "expr.add", "core.add" -> A + " + " + B;
            case "expr.sub", "core.sub" -> A + " - " + B;
            case "expr.mul", "core.mul" -> A + " * " + B;
            case "expr.div", "core.div" -> A + " / " + B;
            case "expr.mod", "core.mod" -> A + " % " + B;
            case "expr.eq",  "core.eq"  -> A + " == " + B;
            case "expr.ne",  "core.ne"  -> A + " != " + B;
            case "expr.lt",  "core.lt"  -> A + " < " + B;
            case "expr.gt",  "core.gt"  -> A + " > " + B;
            case "expr.le",  "core.le"  -> A + " <= " + B;
            case "expr.ge",  "core.ge"  -> A + " >= " + B;
            case "expr.and", "core.and" -> A + " && " + B;
            case "expr.or",  "core.or"  -> A + " || " + B;
            case "expr.not", "core.not" -> "!" + A;
            case "expr.neg", "core.neg" -> "-" + A;
            case "expr.bitAnd", "core.bitAnd" -> A + " & " + B;
            case "expr.bitOr",  "core.bitOr"  -> A + " | " + B;
            case "expr.bitXor", "core.bitXor" -> A + " ^ " + B;
            case "expr.bitNot", "core.bitNot" -> "~" + A;
            case "expr.shl",  "core.shl"  -> A + " << " + B;
            case "expr.shr",  "core.shr"  -> A + " >> " + B;
            case "expr.ushr", "core.ushr" -> A + " >>> " + B;
            case "expr.concat", "core.concat" -> A + " + " + B;
            case "expr.ternary", "core.ternary" -> A + " ? " + B + " : " + C;
            case "expr.cast", "core.cast" -> "(" + A + ") " + B;
            case "expr.instanceof", "core.instanceof" -> A + " instanceof " + B;
            case "expr.arrayGet", "core.arrayGet" -> A + "[" + B + "]";
            case "expr.arrayLen", "core.arrayLen" -> A + ".length";
            case "expr.paren", "core.paren" -> "(" + A + ")";
            case "expr.get", "core.get", "data.get", "lit.int", "lit.long", "lit.float",
                 "lit.double", "lit.char", "lit.string", "lit.bool", "lit.null",
                 "core.literal" -> A;
            default -> {
                // 纯语法积木表兜底：模板 + 各输入窗口内容（含嵌套报告块）
                clbc.blocks.CLBC_CoreSyntax.Def d = clbc.blocks.CLBC_CoreSyntax.def(key);
                if (d != null) {
                    String t = d.code;
                    for (int i = 0; i < a.size(); i++) t = t.replace("{" + i + "}", a.get(i));
                    for (int i = a.size(); i < 4; i++) t = t.replace("{" + i + "}", "");
                    yield t.trim();
                }
                String e = b.expr == null ? "" : b.expr;
                yield !e.isEmpty() ? e : (!b.text.isEmpty() ? b.text : A);
            }
        };
    }

    /** 取某块第 ordinal 个内嵌口的子块（生成时判断是否需要补括号用） */
    private static CLBC_Block childOf(CLBC_Block b, int ordinal) {
        if (b == null) return null;
        CLBC_Block.Input in = b.inputFor(0, ordinal);
        return in == null ? null : in.child;
    }

    /**
     * 按运算符优先级决定子表达式生成时是否加括号：
     *  - 子运算优先级低于父运算（prec(child) < prec(parent)）→ 必须加括号
     *  - 同级且父为左结合、子为右侧操作数（如 a - (b - c)）→ 必须加括号
     *  - core.paren 自身已带括号，跳过避免双重包裹
     */
    private static String wrap(String code, CLBC_Block child, String parentKey, int operand) {
        if (child == null) return code;
        // core.paren 自身已提供括号，其子表达式不再按优先级二次包裹，否则 (a > b) 会变成 ((a > b))
        if ("core.paren".equals(parentKey) || "expr.paren".equals(parentKey)) return code;
        if ("core.paren".equals(child.key) || "expr.paren".equals(child.key)) return code;
        int pp = opPrec(parentKey), cp = opPrec(child.key);
        boolean rightSide = operand >= 1;
        if (cp < pp) return "(" + code + ")";
        if (cp == pp && rightSide && leftAssoc(parentKey)) return "(" + code + ")";
        return code;
    }

    /** 运算符优先级：数值越大绑定越紧；字面量/变量/调用等非运算符返回极大值，永不补括号 */
    private static int opPrec(String key) {
        return switch (key == null ? "" : key) {
            case "core.mul", "core.div", "core.mod",
                 "expr.mul", "expr.div", "expr.mod" -> 9;
            case "core.add", "core.sub", "core.concat",
                 "expr.add", "expr.sub", "expr.concat" -> 8;
            case "core.shl", "core.shr", "core.ushr",
                 "expr.shl", "expr.shr", "expr.ushr" -> 7;
            case "core.lt", "core.gt", "core.le", "core.ge", "core.eq", "core.ne", "core.instanceof",
                 "expr.lt", "expr.gt", "expr.le", "expr.ge", "expr.eq", "expr.ne", "expr.instanceof" -> 6;
            case "core.bitAnd", "expr.bitAnd" -> 4;
            case "core.bitXor", "expr.bitXor" -> 3;
            case "core.bitOr",  "expr.bitOr"  -> 2;
            case "core.and", "expr.and" -> 1;
            case "core.or",  "expr.or"  -> 0;
            case "core.neg", "core.not", "core.bitNot",
                 "expr.neg", "expr.not", "expr.bitNot" -> 11;   // 一元运算高于二元
            case "core.ternary", "expr.ternary" -> -1;          // 三元最低，且右结合
            default -> 100;
        };
    }

    /** 是否左结合（同级从左往右分组）；三元是右结合 */
    private static boolean leftAssoc(String key) {
        return !("core.ternary".equals(key) || "expr.ternary".equals(key));
    }

    public int slotCount() {
        return (slots == null || slots.isEmpty()) ? 1 : slots.size();
    }

    /** 是否显式启用了多口结构 */
    public boolean hasSlots() { return slots != null && !slots.isEmpty(); }

    /** 第 i 个口的子块；单口退化时返回 children */
    public List<CLBC_Block> slotChildren(int i) {
        if (!hasSlots()) return i == 0 ? children : java.util.Collections.emptyList();
        if (i < 0 || i >= slots.size()) return java.util.Collections.emptyList();
        return slots.get(i).children;
    }

    /** 第 i 个口上方的分支标签 */
    public String slotLabel(int i) {
        if (!hasSlots()) return i == 0 ? (text == null ? "" : text) : "";
        if (i < 0 || i >= slots.size()) return "";
        String s = slots.get(i).label;
        return s == null ? "" : s;
    }

    /** 确保有 n 个口（扩容时给新口默认标签；缩容只允许 n>=1） */
    public void ensureSlots(int n) {
        int count = Math.max(1, n);
        if (!hasSlots()) {
            slots = new ArrayList<>();
            Slot first = new Slot(text == null ? "" : text);
            first.children.addAll(children);
            slots.add(first);
        }
        while (slots.size() < count) slots.add(new Slot("否则如果"));
        if (slots.size() > count) slots = new ArrayList<>(slots.subList(0, count));
    }

    /** 把所有口的 children 汇总回并集字段（重建树 / 编辑后调用，保证旧逻辑读得到全部子块） */
    public void syncChildrenUnion() {
        if (!hasSlots()) return;
        children.clear();
        for (int i = 0; i < slots.size(); i++) {
            for (CLBC_Block c : slots.get(i).children) {
                children.add(c);
                c.slotIndex = i;
                c.parentId = this.id;
            }
        }
    }

    // ================= 调用积木（ops.call）专用构造 / 重排 =================

    /** List<String> → String[]（下拉候选用） */
    public static String[] toArr(java.util.List<String> l) {
        return l == null ? new String[0] : l.toArray(new String[0]);
    }

    /** ops.call 的参数布局（委托库统一计算，渲染 / 生成 / 交互三处共用） */
    public java.util.List<clbc.blocks.CLBC_BlockLibrary.CallParam> callParamPlan() {
        if (!"ops.call".equals(key)) return new java.util.ArrayList<>();
        String cs = (args != null && args.size() > 0) ? args.get(0) : "";
        String mn = (args != null && args.size() > 1) ? args.get(1) : "";
        return clbc.blocks.CLBC_BlockLibrary.callParamPlan(cs, mn, this);
    }

    /** 工厂：构造一个带「类下拉 → 方法下拉 → 内嵌参数口/回调 C 口」的调用积木。
     *  code→积木 与 工具箱新建 共用，保证两类入口产出的结构完全一致。
     *  @return 命中库则返回积木；未识别/被安全策略禁用则返回 null（调用方退化为 raw.code）。 */
    public static CLBC_Block makeCallBlock(String classSimple, String methodName, java.util.List<String> argStrs) {
        clbc.blocks.CLBC_BlockDefinition def = clbc.blocks.CLBC_BlockLibrary.findBlock(classSimple, methodName);
        if (def == null) return null;
        if (argStrs == null) argStrs = new java.util.ArrayList<>();
        CLBC_Block b = new CLBC_Block(-1, "stack",
                clbc.blocks.CLBC_Lang.categoryColor(def.category), classSimple + "." + methodName + "()");
        b.key = "ops.call";
        b.args = new java.util.ArrayList<>();
        b.args.add(def.className);
        b.args.add(def.methodName);
        b.args.add("lib:" + def.ownerFqn + "#" + def.methodName);

        // 类下拉（候选 = 全库已扫描简单名）
        CLBC_Block.Input cin = new CLBC_Block.Input(def.className, false);
        cin.row = 0; cin.ordinal = 0;
        cin.options = toArr(clbc.blocks.CLBC_BlockLibrary.scannedSimpleNames());
        b.inputs.add(cin);
        // 方法下拉（候选 = 该类的安全方法名）
        CLBC_Block.Input min = new CLBC_Block.Input(def.methodName, false);
        min.row = 0; min.ordinal = 1;
        min.options = toArr(clbc.blocks.CLBC_BlockLibrary.methodNamesOf(def.ownerFqn));
        b.inputs.add(min);

        // 参数：函数式 → C 口（回调体以 raw.code 暂存实参源码）；其余 → 内嵌输入窗口
        java.util.List<CLBC_Block> cbFills = new java.util.ArrayList<>();
        int inline = 0, func = 0;
        for (int j = 0; j < def.params.size(); j++) {
            String p = def.params.get(j);
            String ts = stripSimple(p);
            boolean isF = clbc.blocks.CLBC_BlockLibrary.isFunctionalInterface(
                    clbc.blocks.CLBC_BlockLibrary.resolveFqn(ts));
            if (isF) {
                String src = j < argStrs.size() ? argStrs.get(j) : "";
                CLBC_Block raw = new CLBC_Block(-1, "stack",
                        clbc.blocks.CLBC_Lang.categoryColor("结构"), src);
                raw.key = "raw.code";
                raw.args = new java.util.ArrayList<>(java.util.List.of(src));
                cbFills.add(raw);
                func++;
            } else {
                CLBC_Block.Input pin = new CLBC_Block.Input(
                        j < argStrs.size() ? argStrs.get(j) : "", false);
                pin.row = 0; pin.ordinal = 2 + inline++;
                b.inputs.add(pin);
            }
        }
        if (func > 0) {
            b.kind = "cblock";
            b.slots = new java.util.ArrayList<>();
            for (CLBC_Block r : cbFills) {
                CLBC_Block.Slot s = new CLBC_Block.Slot("");
                s.children.add(r);
                b.slots.add(s);
            }
        }
        return b;
    }

    /** 调用积木改选类/方法后：按新方法的签名重建参数口（清空旧内嵌窗与回调槽，重排） */
    public void rebuildCallParams() {
        this.inputs.removeIf(in -> in.row == 0 && in.ordinal >= 2);
        this.slots = null;
        String cs = (args != null && args.size() > 0) ? args.get(0) : "";
        String mn = (args != null && args.size() > 1) ? args.get(1) : "";
        clbc.blocks.CLBC_BlockDefinition def = clbc.blocks.CLBC_BlockLibrary.findBlock(cs, mn);
        if (def == null) { this.kind = "stack"; return; }
        int inline = 0, func = 0;
        for (String p : def.params) {
            boolean isF = clbc.blocks.CLBC_BlockLibrary.isFunctionalInterface(
                    clbc.blocks.CLBC_BlockLibrary.resolveFqn(stripSimple(p)));
            if (isF) func++;
            else {
                CLBC_Block.Input in = new CLBC_Block.Input("", false);
                in.row = 0; in.ordinal = 2 + inline++;
                this.inputs.add(in);
            }
        }
        if (func > 0) {
            this.kind = "cblock";
            this.slots = new java.util.ArrayList<>();
            for (int i = 0; i < func; i++) this.slots.add(new CLBC_Block.Slot(""));
        } else {
            this.kind = "stack";
        }
    }

    private static String stripSimple(String t) {
        if (t == null) return "";
        int i = t.lastIndexOf('.');
        return i >= 0 ? t.substring(i + 1) : t;
    }
}
