package clbc.blocks;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.HashMap;

import clbc.ui.CLBC_Block;
import clbc.util.CLBC_Util;

/**
 * 积木库（通用化）：扫描 import / classpath / jar，反射 public 类的方法 / 字段 / 构造器，
 * 自动生成调用块 + 属性块定义；分类由语言文件 libraryAliases 决定（兜底按类名启发式）。
 * 已扫描类缓存于内存（scanned 记录），重复扫描自动跳过（离线友好、版本无关）。
 *
 * 设计文档需求④：机械化、通用化识别——不再硬编码具体类，而是按"全限定名 + 反射"递推。
 */
public class CLBC_BlockLibrary {

    /** 全限定类名 → 该类的可调用成员定义 */
    public static final Map<String, List<CLBC_BlockDefinition>> library = new LinkedHashMap<>();
    /** 已扫描类记录（去重，避免重复反射 / 重复入表） */
    private static final Set<String> scanned = new HashSet<>();

    /** A-1：简单类名 → 拥有该简单名的【已加载入库】类的全限定名列表。
     *  findBlock / findBlockRaw 用它把简单名直接定位到确切 fqn，不再遍历全库、不再 endsWith 猜包。
     *  索引只由 library 派生，内容始终只覆盖已 scanClass 入库的类。 */
    private static final Map<String, List<String>> bySimpleName = new HashMap<>();
    /** 上次建索引时 library 的规模；library 是 public，若被外部直接增删，按规模变化自愈重建索引 */
    private static int indexedSize = -1;

    /** 支持识别的 Java 标准库类（统一来源，见 {@link CLBC_BuiltinCatalog}）。
     *  取代原 17 个 BASE_CLASSES，覆盖日常积木编程最常用的 java.* 标准库；
     *  识别集与 {@code lang/java.json} 生成集同源 → 永远一致。 */
    private static final List<String> BASE_CLASSES = CLBC_BuiltinCatalog.CURATED_CLASSES;

    /**
     * 安全（无毒）策略：被下列类 / 方法生成的调用块可能被用于执行任意代码、启动外部进程、
     * 反射 / 类加载、删除文件等危险操作，因此禁止作为积木块出现。
     * 内置黑名单保证开箱即安全；lang.json 的 unsafe 段可追加用户自定义项（二者取并集）。
     */
    // 整类禁用：精确类名 或 包前缀（以 "." 结尾视为前缀匹配）
    private static final String[] UNSAFE_CLASSES = {
        "java.lang.Runtime", "java.lang.ProcessBuilder", "java.lang.Process",
        "java.lang.Class", "java.lang.ClassLoader",
        "java.lang.SecurityManager", "java.lang.RuntimePermission",
        "java.lang.reflect", "java.lang.invoke", "java.lang.module",
        "javax.script", "java.security", "com.sun", "sun", "jdk.internal"
    };
    // 方法级黑名单：格式 "全限定类#方法" 或 "简单类名#方法"
    private static final Set<String> UNSAFE_METHODS = new HashSet<>();
    static {
        String[] m = {
            "java.lang.System#exit", "java.lang.System#halt",
            "java.lang.System#load", "java.lang.System#loadLibrary",
            "java.lang.System#setSecurityManager", "java.lang.System#setProperties",
            "java.lang.Runtime#exec", "java.lang.Runtime#load", "java.lang.Runtime#loadLibrary",
            "java.lang.Runtime#getRuntime",
            "java.lang.Class#forName",
            "java.lang.ClassLoader#loadClass", "java.lang.ClassLoader#defineClass",
            "java.lang.ClassLoader#loadLibrary", "java.lang.ClassLoader#findLibrary",
            "java.lang.Thread#stop", "java.lang.Thread#suspend", "java.lang.Thread#resume",
            "java.lang.Thread#destroy", "java.lang.Thread#destroy0",
            "java.io.File#delete", "java.io.File#deleteOnExit",
            "java.lang.ProcessBuilder#start",
            "java.lang.ProcessHandle#destroy", "java.lang.ProcessHandle#destroyForcibly", "java.lang.ProcessHandle#onExit"
        };
        for (String s : m) UNSAFE_METHODS.add(s);
    }
    // java.lang.System 采用白名单：仅下列成员允许入库，其余（含 exit/halt）一律禁止
    private static final Set<String> SYSTEM_SAFE = new HashSet<>();
    static {
        SYSTEM_SAFE.add("out"); SYSTEM_SAFE.add("err"); SYSTEM_SAFE.add("in");
        SYSTEM_SAFE.add("currentTimeMillis"); SYSTEM_SAFE.add("nanoTime");
        SYSTEM_SAFE.add("arraycopy"); SYSTEM_SAFE.add("getProperty");
        SYSTEM_SAFE.add("getProperties"); SYSTEM_SAFE.add("getenv");
        SYSTEM_SAFE.add("lineSeparator"); SYSTEM_SAFE.add("identityHashCode");
        SYSTEM_SAFE.add("gc"); SYSTEM_SAFE.add("runFinalization");
        SYSTEM_SAFE.add("console"); SYSTEM_SAFE.add("inheritedChannel");
        SYSTEM_SAFE.add("setOut"); SYSTEM_SAFE.add("setErr");
    }

    /** 整类是否禁用（精确 / 前缀 / 语言文件自定义） */
    public static boolean isUnsafeClass(String fqn) {
        for (String u : UNSAFE_CLASSES)
            if (fqn.equals(u) || fqn.startsWith(u + ".")) return true;
        return CLBC_Lang.isUnsafeClass(fqn);
    }

    /** 安全（无毒）审查：该类.成员 是否允许作为积木调用块 */
    public static boolean isSafe(String fqn, String member) {
        if (isUnsafeClass(fqn)) return false;
        if (CLBC_Lang.isUnsafeMethod(fqn, member)) return false;
        if (UNSAFE_METHODS.contains(fqn + "#" + member)) return false;
        int i = fqn.lastIndexOf('.');
        String simple = i >= 0 ? fqn.substring(i + 1) : fqn;
        if (UNSAFE_METHODS.contains(simple + "#" + member)) return false;
        if (fqn.equals("java.lang.System") && !SYSTEM_SAFE.contains(member)) return false;
        return true;
    }

    static {
        for (String c : BASE_CLASSES) scanClass(c);
        // 汇总：Class.forName 失败的类不会进 scanned，低版本 JDK 启动时会静默缺类。
        // 这里一次性列出未加载的基础类，便于排查，别完全静默（catch 里仍保留逐条日志）。
        List<String> missing = new ArrayList<>();
        for (String c : BASE_CLASSES) if (!scanned.contains(c)) missing.add(c);
        if (!missing.isEmpty())
            CLBC_Util.log("!!!!库扫描汇总：本次启动有 " + missing.size()
                    + " 个基础类未加载（低版本 JDK 或不在 classpath），库可能不完整: " + missing);
    }

    /** 通用化入口：按全限定类名反射扫描（可重复调用，已扫描则跳过）。
     *  注意：去重由 scanClass(Class) 依据 fqn 统一管理，本方法【不得】提前把
     *  className 写入 scanned——否则 scanClass(Class) 会因 fqn==className 命中
     *  “已扫描”而直接 return，导致 library 永不填充（历史 bug，已修）。 */
    public static void scanClass(String className) {
        try {
            Class<?> clazz = Class.forName(className);
            scanClass(clazz);
        } catch (Throwable e) {
            CLBC_Util.log("扫描类失败（可能未编译/不在 classpath）: " + className);
        }
    }

    /** 直接反射一个 Class 对象（如运行时已知实例类型） */
    public static void scanClass(Class<?> clazz) {
        String fqn = clazz.getName();
        if (scanned.contains(fqn)) return;
        scanned.add(fqn);
        if (isUnsafeClass(fqn)) {
            CLBC_Util.log("安全策略：跳过禁用类 " + fqn);
            return;
        }
        List<CLBC_BlockDefinition> members = new ArrayList<>();
        String cat = resolveCategory(clazz);

        for (Constructor<?> c : clazz.getConstructors()) {
            if (!Modifier.isPublic(c.getModifiers()) || c.isSynthetic()) continue;
            members.add(new CLBC_BlockDefinition(cat, clazz.getSimpleName(), fqn, clazz.getSimpleName(),
                    clazz.getSimpleName(), paramsOf(c.getParameterTypes()), false, false,
                    strip(clazz.getName()), sig(clazz.getSimpleName(), c.getParameterTypes())));
        }
        for (Method m : clazz.getMethods()) {
            if (!Modifier.isPublic(m.getModifiers()) || m.isSynthetic()) continue;
            boolean st = Modifier.isStatic(m.getModifiers());
            members.add(new CLBC_BlockDefinition(cat, clazz.getSimpleName(), fqn, m.getName(),
                    m.getName(), paramsOf(m.getParameterTypes()), false, st,
                    strip(m.getReturnType().toString()), sig(m.getName(), m.getParameterTypes())));
        }
        for (Field f : clazz.getFields()) {
            if (!Modifier.isPublic(f.getModifiers())) continue;
            boolean st = Modifier.isStatic(f.getModifiers());
            members.add(new CLBC_BlockDefinition(cat, clazz.getSimpleName(), fqn, f.getName(),
                    f.getName(), new ArrayList<>(), true, st,
                    strip(f.getType().toString()), f.getType().getSimpleName() + " " + f.getName()));
        }
        library.put(fqn, members);
        addToIndex(fqn);
    }

    /** A-1：取全限定名的简单名（含数组/内部类的兜底：只截最后一个 '.' 之后） */
    private static String simpleOf(String fqn) {
        int i = fqn.lastIndexOf('.');
        return i >= 0 ? fqn.substring(i + 1) : fqn;
    }

    /** A-1：类入库时同步把简单名登记进索引（scanned 保证同一 fqn 只登记一次） */
    private static void addToIndex(String fqn) {
        bySimpleName.computeIfAbsent(simpleOf(fqn), k -> new ArrayList<>()).add(fqn);
        indexedSize = library.size();
    }

    /** A-1：library 是 public，可能被外部直接增删；按规模变化自愈重建，保证索引始终与库一致 */
    private static void ensureIndex() {
        if (indexedSize != library.size()) rebuildIndex();
    }

    private static void rebuildIndex() {
        bySimpleName.clear();
        for (String fqn : library.keySet()) {
            bySimpleName.computeIfAbsent(simpleOf(fqn), k -> new ArrayList<>()).add(fqn);
        }
        indexedSize = library.size();
        CLBC_Util.log("积木库索引重建：已收录 " + library.size() + " 个已加载类");
    }

    private static List<String> paramsOf(Class<?>[] pts) {
        List<String> ps = new ArrayList<>();
        for (Class<?> p : pts) ps.add(strip(p.toString()));
        return ps;
    }

    private static String sig(String name, Class<?>[] pts) {
        List<String> ps = paramsOf(pts);
        return name + "(" + String.join(", ", ps) + ")";
    }

    /** 类型名 → 简单名（去包名、擦除泛型、数组格式化为 int[] / String[] 等可读形式） */
    private static String strip(String t) {
        String s = t;
        if (s.startsWith("class ")) s = s.substring(6);
        else if (s.startsWith("interface ")) s = s.substring(10);
        // 数组：Class.toString() 形如 "class [B" / "class [Ljava.lang.String;"
        int dim = 0;
        while (s.startsWith("[")) { dim++; s = s.substring(1); }
        if (dim > 0) {
            String base;
            switch (s.charAt(0)) {
                case 'Z' -> base = "boolean";
                case 'B' -> base = "byte";
                case 'C' -> base = "char";
                case 'D' -> base = "double";
                case 'F' -> base = "float";
                case 'I' -> base = "int";
                case 'J' -> base = "long";
                case 'S' -> base = "short";
                case 'L' -> {
                    String fqn = s.endsWith(";") ? s.substring(1, s.length() - 1) : s.substring(1);
                    int di = fqn.lastIndexOf('.');
                    base = di >= 0 ? fqn.substring(di + 1) : fqn;
                }
                default -> base = s;
            }
            StringBuilder sb = new StringBuilder(base);
            for (int i = 0; i < dim; i++) sb.append("[]");
            return sb.toString();
        }
        // 非数组：去包名 + 擦除泛型
        int idx = s.lastIndexOf('.');
        String name = (idx >= 0 ? s.substring(idx + 1) : s);
        return name.replaceAll("<.*>", "");
    }

    /** 分类解析：硬编码表优先（人逐条审定、纯 JDK、零猜测），语言文件别名兜底。
     *  不再按类名 contains() 启发式——那会把 194 个类全丢进"系统"且随 JDK 升级漂移。 */
    private static String resolveCategory(Class<?> clazz) {
        String fqn = clazz.getName();
        String hard = CLBC_CategoryMap.categoryOf(fqn);
        if (hard != null) return hard;                      // ① 硬编码表（权威）
        String alias = CLBC_Lang.libraryAlias(fqn);
        if (!alias.equals(fqn)) return alias;                // ② 语言文件别名（历史兼容）
        return "系统";                                       // ③ 未收录：安全兜底
    }

    /** 供代码解析器查询：该类.方法 是否已知（用于调用块着色）。className 可为简单名或全限定。
     *  仅返回安全（无毒）成员——工具箱 / 补全不应展示被安全策略禁用的调用。 */
    public static CLBC_BlockDefinition findBlock(String className, String methodName) {
        CLBC_BlockDefinition def = lookup(className, methodName);
        if (def == null) return null;
        // 安全门控作用在【确切命中的 fqn】上：同名不同包不许借道绕过 System.exit / Runtime 等黑名单
        if (!isSafe(def.ownerFqn, def.methodName)) return null;
        return def;
    }

    /** 安全无关的原始查询：返回该成员定义（含被禁用成员），供代码→积木转换器判断
     *  "命中库但被安全策略禁用"并生成标注块。工具箱 / 补全请使用 {@link #findBlock}。 */
    public static CLBC_BlockDefinition findBlockRaw(String className, String methodName) {
        return lookup(className, methodName);
    }

    /** A-1：O(1) 查表核心。className 为全限定名时精确命中 library；为简单名时按 bySimpleName 定位。
     *  不再遍历全库、不再 endsWith 猜包；同名不同包且无法唯一消歧 → 返回 null（降级路径与原来一致），
     *  绝不误命中到错误的包。查不到一律返回 null。 */
    private static CLBC_BlockDefinition lookup(String className, String methodName) {
        if (className == null || className.isEmpty() || methodName == null) return null;
        ensureIndex();
        List<CLBC_BlockDefinition> members = library.get(className);   // 全限定名精确
        if (members == null) {
            List<String> fqns = bySimpleName.get(className);           // 简单名索引
            if (fqns == null || fqns.isEmpty()) return null;
            if (fqns.size() == 1) {
                members = library.get(fqns.get(0));                    // 唯一同名类：等价于原来 endsWith 唯一命中
            } else {
                // 同名不同包：按 methodName 精确消歧，只认"恰好一个已加载类含该成员"；
                // 多个都含 / 都不含 → 判歧义，返回 null，不误命中、不绕过黑名单
                List<CLBC_BlockDefinition> unique = null;
                int hits = 0;
                for (String fqn : fqns) {
                    List<CLBC_BlockDefinition> ms = library.get(fqn);
                    if (ms == null) continue;
                    for (CLBC_BlockDefinition m : ms) {
                        if (!m.isField && m.methodName.equals(methodName)) { unique = ms; hits++; break; }
                    }
                }
                if (hits != 1) {
                    CLBC_Util.log("积木库查表：简单名 " + className + " 命中 " + fqns.size()
                            + " 个同名类，成员 " + methodName + " 无法唯一消歧，按未识别降级");
                    return null;
                }
                members = unique;
            }
        }
        if (members == null) return null;
        for (CLBC_BlockDefinition m : members)
            if (!m.isField && m.methodName.equals(methodName)) return m;   // 与原一致：取第一个匹配重载
        return null;
    }

    /** 调用点存在性校验：该类.方法 是否在已识别库中存在（已含安全门控） */
    public static boolean methodExists(String className, String methodName) {
        return findBlock(className, methodName) != null;
    }

    /** 供工具箱 / 补全：列出某分类下所有“安全可用”的库调用块（已过滤被禁用成员） */
    public static List<CLBC_BlockDefinition> membersOfCategory(String cat) {
        List<CLBC_BlockDefinition> r = new ArrayList<>();
        for (List<CLBC_BlockDefinition> ms : library.values())
            for (CLBC_BlockDefinition m : ms)
                if (m.category.equals(cat) && isSafe(m.ownerFqn, m.methodName)) r.add(m);
        return r;
    }

    // ================= 调用积木辅助（下拉类 / 方法 + 回调 C 口判定） =================

    /** 简单类名 → 已入库的全限定名（O(1) 索引）。同名多类（不同包）返回 null，不猜包避免误命中。 */
    public static String resolveFqn(String simpleName) {
        if (simpleName == null || simpleName.isEmpty()) return null;
        ensureIndex();
        List<String> fqns = bySimpleName.get(simpleName);
        if (fqns == null || fqns.isEmpty()) return null;
        return fqns.size() == 1 ? fqns.get(0) : null;
    }

    /** 列出某已入库类的所有安全方法名（供调用积木「类下拉 → 方法下拉」二级联动）。 */
    public static List<String> methodNamesOf(String fqn) {
        List<String> r = new ArrayList<>();
        List<CLBC_BlockDefinition> ms = library.get(fqn);
        if (ms == null) return r;
        for (CLBC_BlockDefinition m : ms)
            if (!m.isField && isSafe(m.ownerFqn, m.methodName) && !r.contains(m.methodName)) r.add(m.methodName);
        return r;
    }

    /** 列出某已入库类的所有安全字段名（供属性积木下拉）。 */
    public static List<String> fieldNamesOf(String fqn) {
        List<String> r = new ArrayList<>();
        List<CLBC_BlockDefinition> ms = library.get(fqn);
        if (ms == null) return r;
        for (CLBC_BlockDefinition m : ms)
            if (m.isField && isSafe(m.ownerFqn, m.methodName) && !r.contains(m.methodName)) r.add(m.methodName);
        return r;
    }

    /** 全库已扫描类的简单名列表（供调用积木「类下拉」候选）。同名多类时各自都列出，由 resolveFqn 消歧。 */
    public static List<String> scannedSimpleNames() {
        ensureIndex();
        return new ArrayList<>(bySimpleName.keySet());
    }

    /** 全限定名 → 简单名（最后一节） */
    public static String simpleNameOf(String fqn) {
        if (fqn == null) return "";
        int i = fqn.lastIndexOf('.');
        return i >= 0 ? fqn.substring(i + 1) : fqn;
    }

    /** 判定某类型是否为「函数式接口」（恰好一个抽象方法，排除 default/static 与 Object 三件套）。
     *  命中即调用积木应把该参数渲染为 C 口（回调体包进调用内）。结果缓存避免重复反射。 */
    private static final Map<String, Boolean> FUNC_CACHE = new java.util.concurrent.ConcurrentHashMap<>();
    public static boolean isFunctionalInterface(String fqn) {
        if (fqn == null || fqn.isEmpty()) return false;
        Boolean cached = FUNC_CACHE.get(fqn);
        if (cached != null) return cached;
        boolean result = false;
        try {
            Class<?> c = Class.forName(fqn);
            if (c.isInterface() && !c.isAnnotationPresent(java.lang.Deprecated.class)) {
                int abs = 0;
                for (java.lang.reflect.Method m : c.getMethods()) {
                    if (!java.lang.reflect.Modifier.isAbstract(m.getModifiers())) continue;
                    String n = m.getName();
                    // 跳过 Object 自带的 equals/hashCode/toString（函数式接口可合法持有）
                    if (n.equals("equals") && m.getParameterCount() == 1
                            && m.getParameterTypes()[0] == Object.class) continue;
                    if (n.equals("hashCode") && m.getParameterCount() == 0) continue;
                    if (n.equals("toString") && m.getParameterCount() == 0) continue;
                    abs++;
                }
                result = abs == 1;
            }
        } catch (Throwable e) {
            // 类不在 classpath（如用户未编译的类）→ 无法判定，按非函数式处理，不缓存错误
            return false;
        }
        FUNC_CACHE.put(fqn, result);
        return result;
    }

    // ================= 调用积木参数布局（下拉/内嵌口/C 口 三方共用） =================

    /** ops.call 参数布局单元：渲染 / 生成 / 交互三处共用，确保下拉、内嵌口、C 口一致。 */
    public static final class CallParam {
        public final boolean functional;   // true=回调 C 口；false=内嵌输入窗口
        public final String typeSimple;    // 参数类型简单名（仅展示/调试用）
        public final int inlineOrdinal;    // functional=false 时：顶层 input 的 ordinal（= 2 + 之前非函数参数数）
        public final int slotIndex;        // functional=true 时：对应第几个 C 口（= 之前函数参数数）
        public CallParam(boolean functional, String typeSimple, int inlineOrdinal, int slotIndex) {
            this.functional = functional; this.typeSimple = typeSimple;
            this.inlineOrdinal = inlineOrdinal; this.slotIndex = slotIndex;
        }
    }

    /** 计算 ops.call 的参数布局（类/方法下拉已选定后）。
     *  · 类+方法能在已扫描库安全命中 → 以库定义为准（含函数式接口判定），最权威。
     *  · 命中不了（用户手选了未扫描/被禁用类）→ 退回积木当前 inputs/slots，保证绘制/生成不崩。 */
    public static List<CallParam> callParamPlan(String classSimple, String methodName, CLBC_Block b) {
        CLBC_BlockDefinition def = (classSimple == null || methodName == null) ? null
                : findBlock(classSimple, methodName);
        List<CallParam> out = new ArrayList<>();
        if (def != null) {
            int inline = 0, slot = 0;
            for (String p : def.params) {
                String ts = stripSimpleName(p);
                boolean func = isFunctionalInterface(resolveFqn(ts));
                if (func) out.add(new CallParam(true, ts, -1, slot++));
                else      out.add(new CallParam(false, ts, 2 + inline++, -1));
            }
            return out;
        }
        // 降级：未知类/方法 → 从当前输入/槽反推（顺序即参数顺序）
        for (CLBC_Block.Input in : (b == null ? java.util.Collections.<CLBC_Block.Input>emptyList() : b.inputs))
            if (in.row == 0 && in.ordinal >= 2) out.add(new CallParam(false, "", in.ordinal, -1));
        if (b != null && b.hasSlots())
            for (int i = 0; i < b.slots.size(); i++) out.add(new CallParam(true, "", -1, i));
        return out;
    }

    /** 取类型名最后一节（如 "java.util.List" → "List"，"int[]" → "int[]"） */
    private static String stripSimpleName(String t) {
        if (t == null) return "";
        int i = t.lastIndexOf('.');
        return i >= 0 ? t.substring(i + 1) : t;
    }
}
