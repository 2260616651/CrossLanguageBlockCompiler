package clbc.blocks;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import clbc.ui.CLBC_Block;
import clbc.ui.CLBC_I18n;
import clbc.util.CLBC_Util;

/**
 * 【程序化积木输入登记器】—— 不写死积木，全部由「Java 定义」自动生成。
 *
 * 采用数据驱动的定义方式：
 *  · 积木只声明一条带 message / 参数表 / 分类的定义，
 *    工具箱由 make-toolbox-xml.js 按 block type 列表生成，渲染层零硬编码文本；
 *  · 这里用 {@link Spec} 承载那条定义：一条 Spec = 一个积木类型，
 *    携带 key / kind / 输入口类型 / 下拉候选 / 代码模板 / 分类 / 标签。
 *
 * 「输入程序」有三个入口，都是机械化的，不需要为新积木改任何 switch：
 *  ① {@link #registerCore(CLBC_CoreSyntax.Def, String, String)}  —— 纯语法定义表逐条灌入；
 *  ② {@link #registerClass(String)}                             —— 反射一个类，自动登记它的全部 public 成员；
 *  ③ {@link #register(Spec)}                                    —— 外部运行时直接塞一条自定义定义。
 *
 * 转译闭环（保证「积木能转回对应 Java」）：
 *  · 每个由 {@link #create(String)} 产出的积木都带合法 {@code key} + {@code args} + {@code inputs}；
 *  · 语法块的代码模板由 {@link #templateOf(String)} 提供给 BlocksToCode；
 *  · 库方法块统一是 {@code ops.call}，BlocksToCode 按签名拼参并自动增删 import；
 *  · 报告块（oval/hex）只有插入输入窗口才生成表达式，悬空时按规则忽略，
 *    绝不会吐出 {@code a + b;} 这种非法 Java 语句。
 *
 * 静态表只在第一次访问时惰性安装一次；之后 registerClass/register 可随时追加。
 */
public final class CLBC_BlockRegistry {

    private CLBC_BlockRegistry() {}

    /** 一条积木定义 */
    public static final class Spec {
        public final String id;                 // 唯一 id：core:<key> / lib:<fqn>#<member> / field:<fqn>#<member>
        public final String category;           // 工具箱分类
        public final String label;              // 工具箱显示文本（语言文件优先）
        public final String key;                // 积木规范键（BlocksToCode 的唯一依据）
        public final String kind;               // stack / round / hex / cblock / class / method
        public final String codeTemplate;       // 语法块代码模板，库块为 null
        public final String[] accepts;          // 每个输入口的接受类型
        public final String[][] options;        // 每个输入口的下拉候选（null = 自由输入）
        public final String callRef;            // 库块 "类#成员"，null = 非库块
        public final String ownerFqn, member;   // 库块元数据
        public final long color;
        public final boolean field;             // 库字段（报告块）
        public final boolean visible;           // 是否出现在工具箱（隐式结构块为 false）
        public final boolean statementUsable;   // false = 只能作表达式，悬空不生成语句

        /** 公开构造：外部运行时也能直接塞一条完整定义（程序化添加，无需改任何 switch） */
        public Spec(String id, String category, String label, String key, String kind,
                     String codeTemplate, String[] accepts, String[][] options, String callRef,
                     String ownerFqn, String member, long color, boolean field,
                     boolean visible, boolean statementUsable) {
            this.id = id; this.category = category; this.label = label; this.key = key;
            this.kind = kind; this.codeTemplate = codeTemplate; this.accepts = accepts;
            this.options = options; this.callRef = callRef; this.ownerFqn = ownerFqn;
            this.member = member; this.color = color; this.field = field;
            this.visible = visible; this.statementUsable = statementUsable;
        }
    }

    private static final Map<String, Spec> BY_ID = new LinkedHashMap<>();
    private static final Map<String, Spec> BY_KEY = new LinkedHashMap<>();
    private static final Set<String> REGISTERED_LIB = new LinkedHashSet<>();
    /** 幂等标志：installBuiltins 及所有查询入口都 synchronized，volatile 再保证跨线程可见，
     *  确保首次近万条登记只做一次，且不会被渲染线程重复触发。 */
    private static volatile boolean installed = false;

    /** 用户明确要求不要的帽子 / import 块：仍参与代码→积木互转，只是不进工具箱。
     *  control.catch / control.finally 也隐藏：Java 要求它们紧接 try，独立拖出会生成孤立非法语句，
     *  它们由 control.try 多口容器自带「捕获 / 最终执行」口表达，不需要单独的工具箱条目。 */
    private static final Set<String> HIDDEN_STRUCTURE = Set.of(
            "unit.package", "unit.import", "unit.class", "unit.interface", "unit.enum",
            "control.catch", "control.finally",
            "core.literal");   // 内部保真块（无法拆分的原始表达式）：仍参与互转，但不进工具箱，避免显示成空白积木

    /** 容器积木的默认口模板：{分支标签, 该口条件/参数原文}。
     *  登记器数据驱动，create() 通用读取，不需要为某个积木写死 if。 */
    private static final Map<String, String[][]> BUILTIN_SLOTS = Map.of(
            "control.try", new String[][]{{"尝试", ""}, {"捕获", "Exception e"}}
    );

    /** 报告块在语句位置是否合法：只有赋值 / ++-- / new / 方法调用能成为 Java 表达式语句 */
    public static boolean isJavaExpressionStatement(String e) {
        if (e == null) return false;
        String t = e.trim();
        if (t.isEmpty()) return false;
        if (t.matches("(?s).*(?<![=!<>])=(?!=).*")) return true;
        if (t.endsWith("++") || t.endsWith("--")) return true;
        if (t.startsWith("new ")) return true;
        return t.matches("(?s)^[A-Za-z_$][\\w$.]*\\s*\\(.*\\)\\s*$");
    }

    // ==========================================================
    // 登记入口（程序化添加，无写死条目）
    // ==========================================================

    /** 惰性安装内置定义：整张纯语法表 + 已扫描入库的全部类成员。可重复调用，幂等。 */
    public static synchronized void installBuiltins() {
        if (installed) return;
        installed = true;
        // ① 纯语法：直接遍历定义表，不手写任何一条积木。
        //    一份全量视图放「语法」，同时按定义表自带的子分类前缀映射进既有分类页
        //    （声明/控制→结构，数据/字面量/数组→数据，运算/比较/逻辑/表达式→运算），
        //    分类映射也是机械前缀匹配，没有逐条 if。
        for (CLBC_CoreSyntax.Def d : CLBC_CoreSyntax.all()) {
            registerCore(d, "语法", labelOfCore(d));
            String mapped = coreCategory(d);
            if (!"语法".equals(mapped)) registerCore("cat:" + d.key, d, mapped, labelOfCore(d));
        }
        // ② 库：反射产物已在 CLBC_BlockLibrary.library 里，按类全限定名逐个灌入
        for (String fqn : new ArrayList<>(CLBC_BlockLibrary.library.keySet())) registerClass(fqn);
        CLBC_Util.log("积木登记器：已程序化登记 " + BY_ID.size() + " 条定义（语法 "
                + CLBC_CoreSyntax.all().size() + " 条）");
    }

    /** 由一条纯语法定义生成 Spec（输入口类型/下拉候选/代码模板全部读定义表） */
    public static synchronized void registerCore(CLBC_CoreSyntax.Def d, String category, String label) {
        registerCore("core:" + d.key, d, category, label);
    }

    /** 可指定 id 的重载：同一条定义可以登记进多个分类页而互不覆盖 */
    public static synchronized void registerCore(String id, CLBC_CoreSyntax.Def d,
                                                  String category, String label) {
        if (d == null || d.key == null) return;
        String[] accepts = d.ins == null ? new String[0] : d.ins.clone();
        String[][] opts = new String[accepts.length][];
        for (int i = 0; i < accepts.length; i++) opts[i] = CLBC_CoreSyntax.dropdownOptions(d.key, i);
        boolean reporter = "round".equals(d.kind) || "hex".equals(d.kind);
        Spec s = new Spec(id, category,
                label == null ? d.text : label, d.key, d.kind, d.code,
                accepts, opts, null, null, null,
                CLBC_Lang.categoryColor(category), false,
                !HIDDEN_STRUCTURE.contains(d.key), !reporter);
        put(s);
    }

    /** 反射一个类并登记其全部安全成员；同名重载按「类#成员」归并成一条（下拉里选签名）。 */
    public static synchronized void registerClass(String fqn) {
        if (fqn == null || fqn.isEmpty()) return;
        CLBC_BlockLibrary.scanClass(fqn);            // 已扫描则内部直接跳过
        List<CLBC_BlockDefinition> members = CLBC_BlockLibrary.library.get(fqn);
        if (members == null) return;
        for (CLBC_BlockDefinition m : members) {
            if (!CLBC_BlockLibrary.isSafe(m.ownerFqn, m.methodName)) continue;
            String dedup = m.ownerFqn + "#" + m.methodName + (m.isField ? "#F" : "#M");
            if (!REGISTERED_LIB.add(dedup)) continue;
            String label = labelOfMember(m);
            // #165：java.lang.Object 的通用成员会被每个类继承，反射平铺会刷满一屏
            // 「Object ▸ xxx」。仍保留登记（create(specId) 照样能造合法积木），只是不进工具箱。
            boolean inheritedObject = "java.lang.Object".equals(m.ownerFqn);
            if (m.isField) {
                Spec s = new Spec("field:" + dedup, m.category, label, "ops.field", "round",
                        null, new String[0], new String[0][], null,
                        m.ownerFqn, m.methodName, CLBC_Lang.categoryColor(m.category),
                        true, !inheritedObject, false);   // 只作表达式：拖进输入窗口才参与生成 Java
                put(s);
            } else {
                Spec s = new Spec("lib:" + dedup, m.category, label, "ops.call", "stack",
                        null, new String[0], new String[0][], m.className + "#" + m.methodName,
                        m.ownerFqn, m.methodName, CLBC_Lang.categoryColor(m.category),
                        false, !inheritedObject, true);
                put(s);
            }
        }
    }

    /** 外部自定义定义入口：给个 key + 代码模板 + 输入口类型，就能得到一块能转回 Java 的积木。 */
    public static synchronized void register(String id, String category, String label,
                                             String key, String kind, String codeTemplate,
                                             String[] accepts, String[][] options) {
        if (id == null || key == null || kind == null) return;
        String[] a = accepts == null ? new String[0] : accepts.clone();
        String[][] o = options == null ? new String[a.length][] : options.clone();
        put(new Spec(id, category, label, key, kind, codeTemplate, a, o, null,
                null, null, CLBC_Lang.categoryColor(category), false, true,
                !("round".equals(kind) || "hex".equals(kind))));
    }

    public static synchronized void register(Spec s) { put(s); }

    private static void put(Spec s) {
        BY_ID.put(s.id, s);
        BY_KEY.putIfAbsent(s.key, s);
    }

    // ==========================================================
    // 查询
    // ==========================================================

    public static synchronized Spec get(String id) {
        installBuiltins();
        return id == null ? null : BY_ID.get(id);
    }

    /** 供 BlocksToCode 兜底：任何程序化登记的 key 都能取回自己的 Java 代码模板 */
    public static synchronized String templateOf(String key) {
        installBuiltins();
        Spec s = key == null ? null : BY_KEY.get(key);
        return s == null ? null : s.codeTemplate;
    }

    public static synchronized String kindOf(String key) {
        installBuiltins();
        Spec s = key == null ? null : BY_KEY.get(key);
        return s == null ? null : s.kind;
    }

    /** 某分类下可在工具箱出现的定义（保持登记顺序，供调用方自行截断数量）。
     *  Scratch 风格扩展：所属 JDK 库被停用时，该类成员不再出现在工具箱；
     *  纯语法块（ownerFqn == null）是基础积木，永远可见。
     *  注意只过滤「工具箱可见性」——登记本身不删，画布上已有积木与代码生成不受影响。 */
    public static synchronized List<Spec> byCategory(String cat) {
        installBuiltins();
        List<Spec> r = new ArrayList<>();
        for (Spec s : BY_ID.values()) {
            if (!s.visible || !s.category.equals(cat)) continue;
            if (s.ownerFqn != null && !CLBC_JdkExtensions.classEnabled(s.ownerFqn)) continue;
            r.add(s);
        }
        return r;
    }

    public static synchronized List<Spec> all() {
        installBuiltins();
        return new ArrayList<>(BY_ID.values());
    }

    // ==========================================================
    // 实例化：Spec → 积木（唯一入口，工具箱点击 / 拖拽共用）
    // ==========================================================

    /** 按登记 id 造一块积木。返回块 id = -1，由调用方分配真实 id。
     *  结构字段（key / args / inputs / slots）全部从 Spec 派生，保证 BlocksToCode 能还原 Java。 */
    public static CLBC_Block create(String specId) {
        Spec s = get(specId);
        if (s == null) return null;
        if ("ops.call".equals(s.key) && s.callRef != null) {
            int h = s.callRef.indexOf('#');
            String cs = h > 0 ? s.callRef.substring(0, h) : s.callRef;
            String mn = h > 0 ? s.callRef.substring(h + 1) : "";
            CLBC_Block made = CLBC_Block.makeCallBlock(cs, mn, new ArrayList<>());
            if (made != null) return made;
            // 同名多包 / 简单名无法唯一消歧时也不许退化成「无 key 的占位块」：
            // 直接以全限定名造一块 ops.call，BlocksToCode 走降级拼参仍能生成合法 Java。
            CLBC_Block b = new CLBC_Block(-1, "stack", s.color, s.label);
            b.key = "ops.call";
            b.args = new ArrayList<>(List.of(s.ownerFqn, mn, "lib:" + s.ownerFqn + "#" + mn));
            CLBC_Block.Input cin = new CLBC_Block.Input(s.ownerFqn, false);
            cin.row = 0; cin.ordinal = 0;
            cin.options = CLBC_Block.toArr(CLBC_BlockLibrary.scannedSimpleNames());
            b.inputs.add(cin);
            CLBC_Block.Input min = new CLBC_Block.Input(mn, false);
            min.row = 0; min.ordinal = 1;
            min.options = CLBC_Block.toArr(CLBC_BlockLibrary.methodNamesOf(s.ownerFqn));
            b.inputs.add(min);
            return b;
        }
        if ("ops.field".equals(s.key)) {
            String cls = CLBC_BlockLibrary.simpleNameOf(s.ownerFqn);
            CLBC_Block b = new CLBC_Block(-1, "round", s.color, s.label);
            b.key = "ops.field";
            b.isReporter = true;
            b.args = new ArrayList<>(List.of(cls, s.member, s.ownerFqn));
            b.expr = cls + "." + s.member;
            return b;
        }
        // 纯语法 / 自定义：输入口数量、类型、下拉候选、代码模板全由 Spec 决定
        List<String> args = new ArrayList<>();
        for (int i = 0; i < s.accepts.length; i++) {
            String[] dd = i < s.options.length ? s.options[i] : null;
            if (dd != null && dd.length > 0) { args.add(dd[0]); continue; }
            // 预选区/拖出的初始槽位留空：Scratch/TurboWarp 风格显示空白输入框，
            // 不再硬塞「名称 / 0」这类占位值到 label（此前会显示成「情况 0」「变量名:名称」）。
            // 有下拉候选（options）的槽上面已用 dd[0] 取真实默认值，不受影响。
            args.add("");
        }
        String t = CLBC_CoreSyntax.isCore(s.key) ? CLBC_CoreSyntax.text(s.key, args) : s.label;
        CLBC_Block b = new CLBC_Block(-1, s.kind, s.color, t == null ? s.label : t);
        b.key = s.key;
        b.args = new ArrayList<>(args);
        for (int i = 0; i < args.size(); i++) {
            CLBC_Block.Input in = new CLBC_Block.Input(args.get(i), "bool".equals(s.accepts[i]));
            in.row = 0; in.ordinal = i;
            in.accept = s.accepts[i] == null ? "any" : s.accepts[i];
            if (i < s.options.length) in.options = s.options[i];
            b.inputs.add(in);
        }
        if (clbc.ui.CLBC_BlockShape.isContainer(s.kind)) {
            String[][] tmpl = BUILTIN_SLOTS.get(s.key);
            if (tmpl != null) {
                // try 这类语言级固定语法：按登记的口模板造多口容器（尝试 + 捕获…），
                // 拖出来就能生成合法 Java，不会只剩一个孤零零的 try 头。
                b.slots = new ArrayList<>();
                for (String[] row : tmpl) {
                    CLBC_Block.Slot sl = new CLBC_Block.Slot(row[0], row[1]);
                    b.slots.add(sl);
                    if (!row[1].isEmpty()) {
                        CLBC_Block.Input in = new CLBC_Block.Input(row[1], false, "str");
                        in.row = b.slots.size() - 1; in.ordinal = 0;
                        b.inputs.add(in);
                    }
                }
                b.syncChildrenUnion();
            } else {
                b.ensureSlots(1);
                b.slots.get(0).label = b.text;
                if (s.accepts.length > 0 && "bool".equals(s.accepts[0]))
                    b.slots.get(0).expr = args.get(0);
            }
        }
        return b;
    }

    // ==========================================================
    // 标签（语言文件优先，回退短名；渲染层不自行拼字符串）
    // ==========================================================

    /** 定义表子分类 → 工具箱分类页（前缀匹配，纯机械化） */
    private static String coreCategory(CLBC_CoreSyntax.Def d) {
        String c = d.cat == null ? "" : d.cat;
        if (c.contains("声明") || c.contains("控制")) return "结构";
        if (c.contains("数据") || c.contains("字面量") || c.contains("数组")) return "数据";
        if (c.contains("运算") || c.contains("比较") || c.contains("逻辑")
                || c.contains("表达式") || c.contains("位运算")) return "运算";
        return "语法";
    }

    private static String labelOfCore(CLBC_CoreSyntax.Def d) {
        String label = CLBC_Lang.text(d.key, Collections.emptyList());
        if (label == null || label.equals(d.key) || label.isBlank()) {
            label = d.text.replace("{0}", "…").replace("{1}", "…")
                    .replace("{2}", "…").replace("{3}", "…");
        } else {
            label = label.replace("%0", "…").replace("%1", "…")
                    .replace("%2", "…").replace("%3", "…");
        }
        return label;
    }

    private static String labelOfMember(CLBC_BlockDefinition m) {
        String label = null;
        CLBC_Lang.MethodInfo mi = CLBC_Lang.methodInfo(m.ownerFqn, m.methodName);
        if (mi != null && mi.blockText != null && !mi.blockText.isBlank()) label = mi.blockText;
        if (label == null) label = CLBC_Lang.libraryDisplayName(m.ownerFqn) + "." + m.methodName;
        // 语言文件里的「▸」在内置字体里会渲染成缺字方框（截图里 Object ▯ new）。
        // 不动 lang/*.json，只在渲染层换成可显示的中点。
        label = label.replace('▸', '·');
        // 库成员标签与下拉显示共用同一中文化入口，预选区/拖出积木不会再出现英文标识符
        label = CLBC_I18n.display(label);
        return m.isField ? label : label + "()";
    }
}
