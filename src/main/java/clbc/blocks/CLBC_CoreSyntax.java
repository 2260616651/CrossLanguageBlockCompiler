package clbc.blocks;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import clbc.ui.CLBC_Block;
import clbc.ui.CLBC_Toolbox;
import clbc.util.CLBC_Util;

/**
 * 【纯语法积木集】不依赖任何库（第三方库 + JDK 自带类库都不依赖）时，Java 语言本身可用的全部语法。
 *
 * 与 {@link CLBC_BlockLibrary}（反射扫描 java.* 类库）严格分开：
 *  · 那里是「库方法」——需要 import / JDK 类；
 *  · 这里是「语言本身」——关键字、运算符、字面量、声明、控制流、数组、表达式，
 *    哪怕一个 import 都不写、一个库类都不调用也能编译通过。
 *
 * 之前 {@link CodeToBlocks} 对这类语法识别不了：数学运算、比较、逻辑、字面量、变量声明等
 * 一律降级成 raw.code。本类提供独立的积木定义表 + 自研表达式解析器，
 * 把 Java 表达式递归拆成「报告积木 + 内嵌输入窗口」的树，窗口里还能继续放报告积木。
 *
 * 语言文件 lang/java-core.json 由 {@link #toLanguageJson()} 导出，是这张表的可读对照表；
 * 程序运行时以本表为统一来源（避免再自研一遍 JSON 解析器造成字段漂移）。
 */
public final class CLBC_CoreSyntax {

    private CLBC_CoreSyntax() {}

    private static int counter = 2_000_000;

    /** 一条纯语法积木定义 */
    public static final class Def {
        public final String key;        // 规范键
        public final String cat;        // 工具箱分类
        public final String text;       // 中文模板，{0}{1}{2} 为内嵌输入窗口
        public final String code;       // 生成代码的模板（不含缩进）
        public final String kind;       // stack / round / cblock / class / method
        public final String[] ins;      // 每个输入窗口的接受类型：num/str/bool/any
        public final String desc;       // 介绍（扩展面板可见）
        Def(String key, String cat, String text, String code, String kind, String[] ins, String desc) {
            this.key = key; this.cat = cat; this.text = text; this.code = code;
            this.kind = kind; this.ins = ins == null ? new String[0] : ins;
            this.desc = desc;
        }
    }

    private static final List<Def> DEFS = new ArrayList<>();
    private static final Map<String, Def> BY_KEY = new LinkedHashMap<>();

    private static void d(String key, String cat, String text, String code, String kind,
                          String ins, String desc) {
        Def def = new Def(key, cat, text, code, kind,
                ins == null || ins.isEmpty() ? new String[0] : ins.split(","), desc);
        DEFS.add(def);
        BY_KEY.put(key, def);
    }

    static {
        // ===== 声明 / 结构（语言关键字，不依赖任何类库） =====
        d("unit.package", "语法·声明", "包 {0}", "package {0};\n", "stack", "str", "声明本文件所属包；纯语言语法，无需任何库");
        d("unit.import",  "语法·声明", "导入 {0}", "import {0};\n", "stack", "str", "导入类型；导入本身是语言语法，不消耗库能力");
        d("unit.class",   "语法·声明", "定义类 {0}", "class {0} {\n}\n", "class", "str", "声明一个类；类体由子积木构成");
        d("unit.interface","语法·声明","定义接口 {0}", "interface {0} {\n}\n", "class", "str", "声明接口");
        d("unit.enum",    "语法·声明", "定义枚举 {0}", "enum {0} {\n}\n", "class", "str", "声明枚举");
        d("unit.method",  "语法·声明", "定义方法 {0} {1} ( {2} )", "{0} {1}({2}) {\n}\n", "method", "str,str,str", "声明方法：返回类型 / 名称 / 参数表");
        d("unit.constructor","语法·声明","定义构造 {0} ( {1} )", "{0}({1}) {\n}\n", "method", "str,str", "声明构造方法");
        d("unit.field",   "语法·声明", "声明字段 {0} {1} = {2}", "{0} {1} = {2};\n", "stack", "str,str,any", "声明类字段");
        d("unit.block",   "语法·声明", "代码块", "{\n}\n", "cblock", "", "独立作用域代码块");
        d("data.var",     "语法·声明", "变量格式:{0} 变量名:{1} 值:{2}", "{0} {1} = {2};\n", "stack", "str,str,any", "声明局部变量（类型 + 名称 + 初值）");
        d("data.const",   "语法·声明", "常量格式:{0} 常量名:{1} 值:{2}", "final {0} {1} = {2};\n", "stack", "str,str,any", "final 常量声明");

        // ===== 数据 / 赋值 =====
        d("data.assign",  "语法·数据", "把 {0} 设为 {1}", "{0} = {1};\n", "stack", "str,any", "简单赋值 =");
        d("data.assignOp","语法·数据", "把 {0} {1}= {2}", "{0} {1}= {2};\n", "stack", "str,str,any", "复合赋值 += -= *= /= %= &= |= ^= <<= >>= >>>=");
        d("data.get",     "语法·数据", "变量 {0}", "{0}", "round", "str", "读取变量值（报告块）");
        d("data.return",  "语法·数据", "返回 {0}", "return {0};\n", "stack", "any", "从方法返回");
        d("data.this",    "语法·数据", "当前对象 this", "this", "round", "", "this 关键字");
        d("data.super",   "语法·数据", "父类 super", "super", "round", "", "super 关键字");
        d("data.new",     "语法·数据", "新建 {0} ( {1} )", "new {0}({1})", "round", "str,any", "new 自定义类型（不调用 JDK 库方法）");
        // #253：new Xxx(lambda -> {…}) 的 C 口容器积木（args=[声明类型,变量名,构造类型,lambda参数,尾部实参]，slot0=回调体）
        // 仅作注册/着色/命中用，代码生成走 BlocksToCode 专用分支，不走这里的 code 模板。
        d("data.newCallback", "语法·结构", "新建 {2} ( {3} -> )", "", "cblock", "str,str,str,str,str", "创建匿名回调对象并把回调体作为 C 口子积木");

        // ===== 字面量（语言级常量，不需要任何类） =====
        d("lit.int",    "语法·字面量", "整数 {0}", "{0}", "round", "num", "int 字面量，如 42 / 0xFF / 0b1010");
        d("lit.long",   "语法·字面量", "长整数 {0}", "{0}L", "round", "num", "long 字面量");
        d("lit.float",  "语法·字面量", "单精度 {0}", "{0}f", "round", "num", "float 字面量");
        d("lit.double", "语法·字面量", "小数 {0}", "{0}", "round", "num", "double 字面量");
        d("lit.char",   "语法·字面量", "字符 {0}", "{0}", "round", "str", "char 字面量，如 'A'");
        d("lit.string", "语法·字面量", "字符串 {0}", "{0}", "round", "str", "String 字面量，如 \"你好\"");
        d("lit.bool",   "语法·字面量", "布尔 {0}", "{0}", "hex", "bool", "true / false");
        d("lit.null",   "语法·字面量", "空值 null", "null", "round", "", "null 字面量");

        // ===== 数学运算 =====
        d("core.add", "语法·运算", "{0} + {1}", "{0} + {1}", "round", "any,any", "加法 / 字符串拼接");
        d("core.sub", "语法·运算", "{0} - {1}", "{0} - {1}", "round", "num,num", "减法");
        d("core.mul", "语法·运算", "{0} * {1}", "{0} * {1}", "round", "num,num", "乘法");
        d("core.div", "语法·运算", "{0} / {1}", "{0} / {1}", "round", "num,num", "除法");
        d("core.mod", "语法·运算", "{0} % {1}", "{0} % {1}", "round", "num,num", "取余");
        d("core.neg", "语法·运算", "- {0}", "-{0}", "round", "num", "一元取负");
        d("core.inc", "语法·运算", "{0} ++", "{0}++", "round", "str", "后置自增");
        d("core.dec", "语法·运算", "{0} --", "{0}--", "round", "str", "后置自减");
        d("core.concat", "语法·运算", "拼接 {0} 与 {1}", "{0} + {1}", "round", "str,str", "字符串 + 拼接（语言内置运算符）");

        // ===== 比较 =====
        d("core.eq", "语法·比较", "{0} = {1}", "{0} == {1}", "hex", "any,any", "等于 ==");
        d("core.ne", "语法·比较", "{0} ≠ {1}", "{0} != {1}", "hex", "any,any", "不等于 !=");
        d("core.lt", "语法·比较", "{0} < {1}", "{0} < {1}", "hex", "num,num", "小于");
        d("core.gt", "语法·比较", "{0} > {1}", "{0} > {1}", "hex", "num,num", "大于");
        d("core.le", "语法·比较", "{0} ≤ {1}", "{0} <= {1}", "hex", "num,num", "小于等于");
        d("core.ge", "语法·比较", "{0} ≥ {1}", "{0} >= {1}", "hex", "num,num", "大于等于");

        // ===== 逻辑（布尔窗口） =====
        d("core.and", "语法·逻辑", "{0} 且 {1}", "{0} && {1}", "hex", "bool,bool", "逻辑与 &&（短路）");
        d("core.or",  "语法·逻辑", "{0} 或 {1}", "{0} || {1}", "hex", "bool,bool", "逻辑或 ||（短路）");
        d("core.not", "语法·逻辑", "非 {0}", "!{0}", "hex", "bool", "逻辑非 !");

        // ===== 位运算 =====
        d("core.bitAnd","语法·位运算", "{0} & {1}", "{0} & {1}", "round", "num,num", "按位与");
        d("core.bitOr", "语法·位运算", "{0} | {1}", "{0} | {1}", "round", "num,num", "按位或");
        d("core.bitXor","语法·位运算", "{0} ^ {1}", "{0} ^ {1}", "round", "num,num", "按位异或");
        d("core.bitNot","语法·位运算", "~ {0}", "~{0}", "round", "num", "按位取反");
        d("core.shl",   "语法·位运算", "{0} << {1}", "{0} << {1}", "round", "num,num", "左移");
        d("core.shr",   "语法·位运算", "{0} >> {1}", "{0} >> {1}", "round", "num,num", "带符号右移");
        d("core.ushr",  "语法·位运算", "{0} >>> {1}", "{0} >>> {1}", "round", "num,num", "无符号右移");

        // ===== 其它表达式 =====
        d("core.ternary",    "语法·表达式", "{0} ? {1} : {2}", "{0} ? {1} : {2}", "round", "bool,any,any", "三元条件表达式");
        d("core.cast",       "语法·表达式", "把 {1} 转为 {0}", "({0}) {1}", "round", "str,any", "强制类型转换 (int)x");
        d("core.instanceof", "语法·表达式", "{0} 是 {1} 的实例", "{0} instanceof {1}", "hex", "any,str", "instanceof 类型判断");
        d("core.paren",      "语法·表达式", "( {0} )", "({0})", "round", "any", "括号提升优先级");
        d("core.call",       "语法·表达式", "调用 {0} ( {1} )", "{0}({1})", "round", "str,any", "调用本类/自定义方法（不依赖 JDK 库）");
        d("core.comment",    "语法·表达式", "注释 {0}", "// {0}\n", "stack", "str", "单行注释（语言级，无库）");
        d("core.literal",    "语法·表达式", "{0}", "{0}", "round", "any", "无法继续拆分的原始表达式片段（原样保留，不丢信息）");

        // ===== 数组（数组是语言内置结构，不是类库） =====
        d("core.arrayNew",  "语法·数组", "新建 {0} 数组，长度 {1}", "new {0}[{1}]", "round", "str,num", "创建数组");
        d("core.arrayInit", "语法·数组", "数组字面量 [ {0} ]", "{ {0} }", "round", "any", "数组初始化列表");
        d("core.arrayGet",  "语法·数组", "{0} [ {1} ]", "{0}[{1}]", "round", "any,num", "按下标取数组元素");
        d("core.arrayLen",  "语法·数组", "{0} 的长度", "{0}.length", "round", "any", "数组 length（语言内置属性）");

        // ===== 控制流 =====
        d("control.if",      "语法·控制", "如果 {0}", "if ({0}) {\n}\n", "cblock", "bool", "条件分支，可继续追加否则如果 / 否则多口");
        d("control.while",   "语法·控制", "当 {0} 重复", "while ({0}) {\n}\n", "cblock", "bool", "while 循环");
        d("control.do",      "语法·控制", "重复直到 {0}", "do {\n} while ({0});\n", "cblock", "bool", "do-while 循环");
        d("control.for",     "语法·控制", "循环 {0} ; {1} ; {2}", "for ({0}; {1}; {2}) {\n}\n", "cblock", "any,bool,any", "for 循环");
        d("control.forEach", "语法·控制", "遍历 {1} 中的 {0}", "for (var {0} : {1}) {\n}\n", "cblock", "str,any", "增强 for 循环");
        d("control.switch",  "语法·控制", "按 {0} 分支", "switch ({0}) {\n}\n", "cblock", "any", "switch 分支");
        d("control.case",    "语法·控制", "情况 {0}", "case {0}:\n", "stack", "any", "case 标签");
        d("control.break",   "语法·控制", "跳出循环", "break;\n", "stack", "", "break");
        d("control.continue","语法·控制", "继续下一轮", "continue;\n", "stack", "", "continue");
        d("control.try",     "语法·控制", "尝试", "try {\n}\n", "cblock", "", "try 块");
        d("control.catch",   "语法·控制", "捕获 {0} {1}", "catch ({0} {1}) {\n}\n", "cblock", "str,str", "catch 块");
        d("control.finally", "语法·控制", "最终执行", "finally {\n}\n", "cblock", "", "finally 块");
        d("control.throw",   "语法·控制", "抛出 {0}", "throw {0};\n", "stack", "any", "throw 语句");
        d("control.assert",  "语法·控制", "断言 {0}", "assert {0};\n", "stack", "bool", "assert 断言");
        d("control.synchronized","语法·控制","同步 {0}", "synchronized ({0}) {\n}\n", "cblock", "any", "synchronized 块");
        d("control.labeled", "语法·控制", "标签 {0}", "{0}:\n", "stack", "str", "带标签语句");
    }

    // ==========================================================
    // 下拉字段统一来源
    // ----------------------------------------------------------
    // 「类型」「修饰符」「运算符」「布尔值」这类有限枚举的槽，不能是自由文本框，
    // 必须点开列表选。这里登记 key + 参数序号 → 候选数组；未登记 = 普通可编辑窗口。
    // 注意两套 args 布局不同：
    //   · 定义表布局（工具箱新建 / 表达式识别）：与 d.text 的 {0}{1}{2} 顺序一致；
    //   · 代码导入布局（CodeToBlocks 走 CLBC_Lang 模板）：unit.method 是
    //     [修饰符, 返回类型, 名称, 参数表]，类型落在序号 1 而非 0。
    // ==========================================================

    /** Java 常用类型（变量 / 字段 / 返回 / 强转 / 数组元素 / instanceof）。当前值不在表内时仍原样保留，不丢信息 */
    public static final String[] JAVA_TYPES = {
            "int", "long", "short", "byte", "float", "double", "boolean", "char",
            "String", "Object", "void", "var", "Integer", "Long", "Double", "Boolean",
            "List", "Map", "Set", "ArrayList", "HashMap", "Exception", "RuntimeException"
    };
    /** 成员修饰符组合 */
    public static final String[] JAVA_MODIFIERS = {
            "public", "private", "protected", "public static", "private static",
            "public final", "static", "final", "abstract", "synchronized", ""
    };
    /** 复合赋值运算符（定义表里写作 {1}=） */
    public static final String[] ASSIGN_OPS = {
            "+", "-", "*", "/", "%", "&", "|", "^", "<<", ">>", ">>>"
    };
    /** 布尔字面量 */
    public static final String[] BOOL_LITERALS = {"true", "false"};

    private static final Map<String, String[]> DROPDOWNS = new LinkedHashMap<>();
    private static void dd(String key, int ordinal, String[] options) {
        DROPDOWNS.put(key + "#" + ordinal, options);
    }
    static {
        // 类型槽（定义表布局）
        dd("unit.method",   0, JAVA_TYPES);      // 定义方法 {0} {1} ( {2} ) → 返回类型
        dd("unit.field",    0, JAVA_TYPES);
        dd("data.var",      0, JAVA_TYPES);
        dd("data.const",    0, JAVA_TYPES);
        dd("data.new",      0, JAVA_TYPES);      // 新建 {0} ( {1} )
        dd("core.cast",     0, JAVA_TYPES);      // 把 {1} 转为 {0}
        dd("core.arrayNew", 0, JAVA_TYPES);      // 新建 {0} 数组
        dd("core.instanceof", 1, JAVA_TYPES);    // {0} 是 {1} 的实例
        dd("control.catch", 0, JAVA_TYPES);      // 捕获 {0} {1}
        // 其它枚举槽
        dd("data.assignOp", 1, ASSIGN_OPS);      // 把 {0} {1}= {2}
        dd("lit.bool",      0, BOOL_LITERALS);
    }

    /** 定义表布局：该积木第 ordinal 个输入窗口的下拉候选；null = 普通可编辑窗口 */
    public static String[] dropdownOptions(String key, int ordinal) {
        return key == null ? null : DROPDOWNS.get(key + "#" + ordinal);
    }

    /** 代码导入布局（CLBC_Lang 模板）：args 顺序与定义表不同者在此单独映射 */
    public static String[] importDropdownOptions(String key, int ordinal) {
        if (key == null) return null;
        if ("unit.method".equals(key)) {
            if (ordinal == 0) return JAVA_MODIFIERS;   // args[0] = 修饰符
            if (ordinal == 1) return JAVA_TYPES;       // args[1] = 返回类型
            return null;
        }
        if ("data.var".equals(key) && ordinal == 0) return JAVA_TYPES;
        if ("data.const".equals(key) && ordinal == 0) return JAVA_TYPES;
        if ("unit.field".equals(key) && ordinal == 0) return JAVA_TYPES;
        return null;
    }

    public static List<Def> all() { return DEFS; }
    public static Def def(String key) { return BY_KEY.get(key); }
    public static boolean isCore(String key) { return key != null && BY_KEY.containsKey(key); }
    public static List<String> categories() {
        List<String> out = new ArrayList<>();
        for (Def d : DEFS) if (!out.contains(d.cat)) out.add(d.cat);
        return out;
    }
    public static List<Def> byCategory(String cat) {
        List<Def> out = new ArrayList<>();
        for (Def d : DEFS) if (d.cat.equals(cat)) out.add(d);
        return out;
    }

    /** 语言文件 java-core.json：这张表的可读对照表（程序运行时仍以本表为准） */
    public static String toLanguageJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"language\": \"java-core\",\n")
          .append("  \"description\": \"不依赖任何库的 Java 纯语法积木集（关键字/运算符/字面量/声明/控制流/数组）\",\n")
          .append("  \"note\": \"与 java.json 的 JDK 类库方法表严格分离；本文件由 CLBC_CoreSyntax 生成，勿手改结构\",\n")
          .append("  \"blocks\": [\n");
        for (int i = 0; i < DEFS.size(); i++) {
            Def d = DEFS.get(i);
            sb.append("    {\"key\":").append(q(d.key))
              .append(",\"category\":").append(q(d.cat))
              .append(",\"text\":").append(q(d.text))
              .append(",\"code\":").append(q(d.code))
              .append(",\"kind\":").append(q(d.kind))
              .append(",\"inputs\":[");
            for (int j = 0; j < d.ins.length; j++) {
                if (j > 0) sb.append(',');
                sb.append(q(d.ins[j]));
            }
            sb.append(']');
            // 下拉候选也写进可读对照表，供外部查阅；运行时仍以 DROPDOWNS 为准
            if (d.ins.length > 0) {
                sb.append(",\"dropdown\":[");
                for (int j = 0; j < d.ins.length; j++) {
                    if (j > 0) sb.append(',');
                    String[] dd = dropdownOptions(d.key, j);
                    if (dd == null) { sb.append("null"); continue; }
                    sb.append('[');
                    for (int k = 0; k < dd.length; k++) {
                        if (k > 0) sb.append(',');
                        sb.append(q(dd[k]));
                    }
                    sb.append(']');
                }
                sb.append(']');
            }
            sb.append(",\"description\":").append(q(d.desc)).append('}');
            if (i < DEFS.size() - 1) sb.append(',');
            sb.append('\n');
        }
        sb.append("  ]\n}\n");
        return sb.toString();
    }

    private static String q(String s) {
        if (s == null) return "\"\"";
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default   -> b.append(c);
            }
        }
        return b.append('"').toString();
    }

    /** 用硬编码模板填出显示文本。
     *  定义与默认中文完全写死在本类（不依赖任何语言文件）；
     *  只有用户在 lang/mapping_file.json 里明确改过名时才覆盖显示模板。 */
    public static String text(String key, List<String> args) {
        Def d = BY_KEY.get(key);
        if (d == null) return args == null || args.isEmpty() ? key : String.join(" ", args);
        String t = null;
        try { t = clbc.core.CLBC_LanguageRegistry.getUser(key); }
        catch (Throwable ignored) { CLBC_Util.log("[CoreSyntax] 用户自定义显示名读取降级（沿用内置模板）: " + ignored.getClass().getSimpleName()); }
        if (t == null || t.isEmpty()) t = d.text;
        if (args != null) for (int i = 0; i < args.size(); i++)
            t = t.replace("{" + i + "}", args.get(i) == null ? "" : args.get(i));
        return t;
    }

    // ==========================================================
    // 表达式识别（自研，纯 JDK）：把 Java 表达式递归拆成报告积木 + 内嵌输入窗口
    // ==========================================================

    /** 识别一段 Java 表达式；返回 round 报告积木，其每个操作数是一个内嵌输入窗口。
     *  无法识别时返回 null（调用方保留原文，绝不丢信息）。 */
    public static CLBC_Block recognize(String expr) {
        if (expr == null) return null;
        String s = expr.trim();
        while (s.endsWith(";")) s = s.substring(0, s.length() - 1).trim();
        if (s.isEmpty()) return null;
        // lambda / 方法引用：自研表达式解析器会把 "->" 当成减号+大于号拆成 "- >"，
        // 往返生成非法 Java。整段保留原文（降级 raw.code）才是保真做法。
        if (s.contains("->") || s.contains("::")) return null;
        try { return new ExprParser(s).parse(); }
        catch (RuntimeException | StackOverflowError e) {
            // A-4：表达式递归深 / 非法时安全降级为"保留原文"，记日志，绝不把 SOE 穿透到转译链路
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            CLBC_Util.log("!!!! 表达式解析降级（嵌套过深或非法），保留原文: " + msg);
            return null;
        }
    }

    /** A-4：表达式解析递归调用总量上限（防 a+a+a+… 链式深层递归 StackOverflowError） */
    private static final int MAX_EXPR_CALLS = 4096;

    private static final String[][] BINOPS = {
            // 优先级从低到高；同组内从右往左取最外层（保证左结合）
            {"||"}, {"&&"}, {"|"}, {"^"}, {"&"},
            {"==", "!="}, {"<", ">", "<=", ">=", "instanceof"},
            {"<<", ">>", ">>>"}, {"+", "-"}, {"*", "/", "%"}
    };
    private static final String[][] BINKEYS = {
            {"core.or"}, {"core.and"}, {"core.bitOr"}, {"core.bitXor"}, {"core.bitAnd"},
            {"core.eq", "core.ne"}, {"core.lt", "core.gt", "core.le", "core.ge", "core.instanceof"},
            {"core.shl", "core.shr", "core.ushr"}, {"core.add", "core.sub"},
            {"core.mul", "core.div", "core.mod"}
    };

    private static final class ExprParser {
        final String s;
        private int calls = 0;
        ExprParser(String s) { this.s = s; }

        CLBC_Block parse() { return ternary(0, s.length()); }

        /** A-4：每个递归入口累计调用数，超限抛异常由 recognize 兜底降级，杜绝深层递归 SOE 穿透 */
        private void guardDepth() {
            if (++calls > MAX_EXPR_CALLS)
                throw new IllegalStateException("表达式解析递归深度超限(" + calls + ")");
        }

        /**
         * 保留区间原样：外层括号绝不自动剥掉。
         * (a+b)*c 若剥掉括号会退化成 a+b*c，语义就错了；括号统一由 atom 生成
         * core.paren 积木，inputCode 再原样还原，保证表达式往返不改变优先级。
         */
        private int[] unwrap(int a, int b) { return new int[]{a, b}; }

        private int matchingParen(int open) {
            int depth = 0;
            for (int i = open; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '\'' || c == '"') { i = skipLiteral(i); continue; }
                if (c == '(') depth++;
                else if (c == ')' && --depth == 0) return i;
            }
            return -1;
        }

        private int skipLiteral(int i) {
            char q = s.charAt(i);
            for (int j = i + 1; j < s.length(); j++) {
                if (s.charAt(j) == '\\') { j++; continue; }
                if (s.charAt(j) == q) return j;
            }
            return s.length() - 1;
        }

        /** 在 [a,b) 内跳过字符串 / 括号 / 数组下标，找顶层 depth=0 的字符 */
        private int topLevel(int a, int b, String token, boolean rightMost) {
            int depth = 0, found = -1;
            for (int i = a; i < b; i++) {
                char c = s.charAt(i);
                if (c == '\'' || c == '"') { i = skipLiteral(i); continue; }
                if (c == '(' || c == '[') { depth++; continue; }
                if (c == ')' || c == ']') { depth--; continue; }
                if (depth != 0) continue;
                if (s.startsWith(token, i)) {
                    // 避免把 <= 当成 <、把 == 当成 =、把 >> 当成 >
                    if (!tokenBoundary(token, i, b)) continue;
                    if (rightMost) found = i;
                    else return i;
                }
            }
            return found;
        }

        private boolean tokenBoundary(String token, int i, int b) {
            int e = i + token.length();
            // 长运算符优先：单字符运算符后面不能再跟同类字符
            char t0 = token.charAt(0);
            if (e < b) {
                char nx = s.charAt(e);
                if ((t0 == '<' || t0 == '>' || t0 == '=' || t0 == '!')
                        && (nx == '=' || nx == '<' || nx == '>')) return false;
                if (t0 == '&' && nx == '&') return false;
                if (t0 == '|' && nx == '|') return false;
            }
            if (i > 0) {
                char pv = s.charAt(i - 1);
                if (t0 == '=' && (pv == '=' || pv == '!' || pv == '<' || pv == '>')) return false;
                // 一元 + / - 不是二元：左边是运算符或左括号
                if ((t0 == '+' || t0 == '-') && "+-*/%=<>!&|^~?:([{,;".indexOf(pv) >= 0) return false;
            }
            return true;
        }

        private CLBC_Block ternary(int a0, int b0) {
            guardDepth();   // A-4：递归深度保护
            int[] u = unwrap(a0, b0);
            int a = u[0], b = u[1];
            int q = topLevel(a, b, "?", false);
            if (q > a) {
                int c = topLevel(q + 1, b, ":", false);
                if (c > q) {
                    return make("core.ternary",
                            new String[]{s.substring(a, q).trim(), s.substring(q + 1, c).trim(),
                                    s.substring(c + 1, b).trim()},
                            new CLBC_Block[]{ternary(a, q), ternary(q + 1, c), ternary(c + 1, b)},
                            new boolean[]{true, false, false});
                }
            }
            return binary(a, b, 0);
        }

        private CLBC_Block binary(int a, int b, int level) {
            guardDepth();   // A-4：递归深度保护
            if (level >= BINOPS.length) return unary(a, b);
            // 后置 ++/-- 必须先于二元 +/-：否则 "i++" 会被 core.add 拆成 "i + ()"，
            // for 更新段往返变成 "i + ()"（javac 直接报语法错）。unary 里有专门的后置分支。
            if (b - a > 2 && ((s.charAt(b - 2) == '+' && s.charAt(b - 1) == '+')
                    || (s.charAt(b - 2) == '-' && s.charAt(b - 1) == '-'))) return unary(a, b);
            // 前缀一元 ! / ~ 优先级高于一切二元运算符：必须先交给 unary 处理，
            // 否则会抢先把内部的 > / && 等先拆出来（如 !(a > b) 被错拆成 (a > b…）。
            if (a < b) {
                char fa = s.charAt(a);
                if (fa == '!' || fa == '~') return unary(a, b);
            }
            String[] ops = BINOPS[level];
            // 同级内取所有运算符里最右（位置最大）的出现拆分：左结合语义下，
            // 最右的运算符才是整组的「最外层」。例如 a + b - c 拆成 (a + b) - c，
            // a + b * c - d / e 拆成 ((a + b*c) - (d/e))，而非错误地先按 + 拆成 a + (...)。
            int bestPos = -1, bestOi = -1;
            for (int oi = 0; oi < ops.length; oi++) {
                int p = topLevel(a, b, ops[oi], true);
                if (p > a && p + ops[oi].length() < b && p > bestPos) {
                    bestPos = p; bestOi = oi;
                }
            }
            if (bestPos > a) {
                String key = BINKEYS[level][bestOi];
                int opLen = ops[bestOi].length();
                // 去掉操作数两端空白后再递归解析：否则 "(a + b) * c" 里括号后的空格会让
                // atom 的 matchingParen(a) == b-1 判定失败、core.paren 块丢失（只留下纯文本）。
                int ls = a;            while (ls < bestPos && Character.isWhitespace(s.charAt(ls))) ls++;
                int le = bestPos;      while (le > ls && Character.isWhitespace(s.charAt(le - 1))) le--;
                int rs = bestPos + opLen; while (rs < b && Character.isWhitespace(s.charAt(rs))) rs++;
                int re = b;            while (re > rs && Character.isWhitespace(s.charAt(re - 1))) re--;
                String left = s.substring(ls, le).trim();
                String right = s.substring(rs, re).trim();
                boolean bool = isBoolKey(key);
                return make(key, new String[]{left, right},
                        new CLBC_Block[]{binary(ls, le, level), binary(rs, re, level)},
                        new boolean[]{bool, bool});
            }
            return binary(a, b, level + 1);
        }

        private static boolean isBoolKey(String key) {
            return "core.and".equals(key) || "core.or".equals(key);
        }

        private CLBC_Block unary(int a0, int b0) {
            guardDepth();   // A-4：递归深度保护
            int[] u = unwrap(a0, b0);
            int a = u[0], b = u[1];
            if (a >= b) return null;
            char c = s.charAt(a);
            if (c == '!' || c == '~' || c == '-' || c == '+') {
                String key = c == '!' ? "core.not" : c == '~' ? "core.bitNot"
                        : c == '-' ? "core.neg" : "core.paren";
                String rest = s.substring(a + 1, b).trim();
                CLBC_Block sub = rest.isEmpty() ? null : unary(a + 1, b);
                return make(key, new String[]{rest},
                        new CLBC_Block[]{sub}, new boolean[]{c == '!'});
            }
            // 后置 ++ / --
            if (b - a > 2 && ((s.charAt(b - 2) == '+' && s.charAt(b - 1) == '+')
                    || (s.charAt(b - 2) == '-' && s.charAt(b - 1) == '-'))) {
                boolean inc = s.charAt(b - 1) == '+';
                String base = s.substring(a, b - 2).trim();
                return make(inc ? "core.inc" : "core.dec", new String[]{base},
                        new CLBC_Block[]{atom(a, b - 2)}, new boolean[]{false});
            }
            // 强制转换 (int)x / (String)y：左括号后是标识符、右括号后还有内容
            if (c == '(') {
                int close = matchingParen(a);
                if (close > a && close + 1 < b && isTypeName(s.substring(a + 1, close).trim())) {
                    String type = s.substring(a + 1, close).trim();
                    String rest = s.substring(close + 1, b).trim();
                    return make("core.cast", new String[]{type, rest},
                            new CLBC_Block[]{null, unary(close + 1, b)},
                            new boolean[]{false, false});
                }
            }
            // 后缀：数组下标 / .length / 方法调用
            int br = topLevel(a, b, "[", false);
            if (br > a && s.charAt(b - 1) == ']') {
                String base = s.substring(a, br).trim();
                String idx = s.substring(br + 1, b - 1).trim();
                return make("core.arrayGet", new String[]{base, idx},
                        new CLBC_Block[]{atom(a, br), unary(br + 1, b - 1)},
                        new boolean[]{false, false});
            }
            if (b - a > 7 && s.startsWith(".length", b - 7)) {
                String base = s.substring(a, b - 7).trim();
                return make("core.arrayLen", new String[]{base},
                        new CLBC_Block[]{atom(a, b - 7)}, new boolean[]{false});
            }
            if (c != '(' && s.charAt(b - 1) == ')') {
                int op = -1;
                for (int i = a; i < b; i++) if (s.charAt(i) == '(') { op = i; break; }
                if (op > a) {
                    String name = s.substring(a, op).trim();
                    String argv = s.substring(op + 1, b - 1).trim();
                    if (isIdentifier(name))
                        return make("core.call", new String[]{name, argv},
                                new CLBC_Block[]{null, argv.isEmpty() ? null : ternary(op + 1, b - 1)},
                                new boolean[]{false, false});
                }
            }
            return atom(a, b);
        }

        private static boolean isTypeName(String t) {
            return !t.isEmpty() && (Character.isJavaIdentifierStart(t.charAt(0)) || t.charAt(0) == '[')
                    && t.chars().allMatch(ch -> Character.isJavaIdentifierPart(ch) || ch == '.' || ch == '[' || ch == ']');
        }
        private static boolean isIdentifier(String t) {
            return !t.isEmpty() && Character.isJavaIdentifierStart(t.charAt(0))
                    && t.chars().allMatch(Character::isJavaIdentifierPart);
        }

        private CLBC_Block atom(int a0, int b0) {
            guardDepth();   // A-4：递归深度保护
            int[] u = unwrap(a0, b0);
            int a = u[0], b = u[1];
            // 括号包裹的表达式：保留括号积木，内部递归
            if (b - a >= 2 && s.charAt(a) == '(' && matchingParen(a) == b - 1) {
                String inner = s.substring(a + 1, b - 1).trim();
                return make("core.paren", new String[]{inner},
                        new CLBC_Block[]{inner.isEmpty() ? null : ternary(a + 1, b - 1)},
                        new boolean[]{false});
            }
            String t = s.substring(a, b).trim();
            if (t.isEmpty()) return null;
            String key;
            if (t.startsWith("\"") && t.endsWith("\"")) key = "lit.string";
            else if (t.startsWith("'") && t.endsWith("'")) key = "lit.char";
            else if (t.equals("true") || t.equals("false")) key = "lit.bool";
            else if (t.equals("null")) key = "lit.null";
            else if (t.matches("\\d+[lL]")) key = "lit.long";
            else if (t.matches("[0-9]*\\.?[0-9]+[fF]")) key = "lit.float";
            else if (t.matches("[0-9]*\\.?[0-9]+[dD]?")) key = "lit.double";
            else if (t.matches("0[xX][0-9a-fA-F]+|0[bB][01]+|\\d+")) key = "lit.int";
            else if (isIdentifier(t)) key = "data.get";
            else key = "core.literal";
            // 叶子字面量 / 变量不建块：让父窗口的 value 保留文本（数值直接是白数字框而非彩色胶囊），
            // 真正的嵌套块只留给运算符 / 比较 / 逻辑 / 调用等非叶子节点；避免彩色 oval 套彩色 oval 的伪影。
            return null;
        }

        private CLBC_Block make(String key, String[] vals, CLBC_Block[] subs, boolean[] hexes) {
            Def d = BY_KEY.get(key);
            List<String> args = new ArrayList<>();
            for (String v : vals) args.add(v == null ? "" : v);
            String text = (d == null ? key : d.text);
            for (int i = 0; i < args.size(); i++) text = text.replace("{" + i + "}", args.get(i));
            // 模板里未填的占位（参数少于模板）清掉，避免窗口文字错位
            for (int i = args.size(); i < 4; i++) text = text.replace("{" + i + "}", "");

            // 布尔结果的块必须是六边形（hex），否则会画成椭圆；非布尔结果才是椭圆（round）
            String k = isBoolResult(key) ? "hex" : "round";
            CLBC_Block r = new CLBC_Block(counter++, k,
                    CLBC_Toolbox.categoryColor("语法"), text);
            r.key = key;
            r.kind = k;
            r.isReporter = isBoolResult(key);
            r.args = new ArrayList<>(args);
            r.text = text;
            for (int i = 0; i < args.size(); i++) {
                CLBC_Block.Input in = new CLBC_Block.Input(args.get(i), hexes != null && i < hexes.length && hexes[i]);
                in.row = 0; in.ordinal = i;
                in.accept = d != null && i < d.ins.length ? d.ins[i] : "any";
                in.options = dropdownOptions(key, i);   // 定义表布局：类型 / 运算符 / 布尔字面量
                if (subs != null && i < subs.length) in.child = subs[i];
                r.inputs.add(in);
            }
            // 单个原子字面量/变量：窗口里就是它本身的值，无需再套一层报告块
            if (args.size() == 1 && subs != null && subs.length == 1 && subs[0] == null
                    && r.inputs.size() == 1) r.inputs.get(0).value = args.get(0);
            return r;
        }

        private static boolean isBoolResult(String key) {
            return switch (key) {
                case "core.eq", "core.ne", "core.lt", "core.gt", "core.le", "core.ge",
                     "core.and", "core.or", "core.not", "core.instanceof", "lit.bool" -> true;
                default -> false;
            };
        }
    }
}
