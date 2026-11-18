package clbc.blocks;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import clbc.core.CLBC_AppContext;
import clbc.core.CLBC_JdkHistory;
import clbc.util.CLBC_Util;

/**
 * Scratch 风格「JDK 库扩展」注册表——清单**运行时自动识别当前 JDK**。
 *
 * 语义：每个 JDK 标准库分包是一个可勾选的扩展（类似 Scratch 的画笔/音乐/视频侦测）。
 * 勾选后该库的类成员积木出现在工具箱；取消勾选则从工具箱移除，但**不影响**
 * 代码→积木识别与已有积木→代码生成（已放在画布上的积木继续有效）。
 *
 * 自动识别口径（用户明确要求「每一个 JDK 版本都不一样」）：
 *  · 扫描硬编码支持清单 {@link CLBC_BuiltinCatalog#CURATED_CLASSES}；
 *  · 逐类在**当前运行时** Class.forName 校验，加载不了的类（版本移除/缺失）自动不进扩展；
 *  · 按类所在包聚类，包即扩展；换 JDK 版本类集合变化 → 扩展清单自动跟着变；
 *  · 中文库名走 {@link CLBC_JdkHistory#chinesePackage} 自识别词典，配色走硬编码分类表；
 *  · 扩展 id 用包名（ext.java.util 等），配置缺省 true，绝不因配置异常清空工具箱。
 *
 * 注意与分类表的分工：分类映射（类→15 个工具箱分类）是逐条**硬编码**的；
 * 本类是「有哪些库开关」的运行时识别，两者口径不同、互不影响。
 */
public final class CLBC_JdkExtensions {

    private CLBC_JdkExtensions() {}

    /** 一个 JDK 库扩展（由运行时扫描生成，字段不再手写） */
    public static final class Ext {
        public final String id;           // 包名 = 配置键后缀 ext.java.util
        public final String name;         // 中文库名（自识别词典）
        public final String desc;         // 介绍（模块名 + 类数，运行时拼）
        public final String prefixes[];   // 覆盖的包前缀（这里就是该包本身）
        public final long color;          // 扩展标记色（取硬编码分类配色）
        public final String sample;       // 代表类
        public final String module;       // 【T10】所属 JDK 模块名，用于弹层分组（如 java.base）

        public Ext(String id, String name, String desc, long color, String sample, String module, String... prefixes) {
            this.id = id; this.name = name; this.desc = desc;
            this.color = color; this.sample = sample; this.module = module; this.prefixes = prefixes;
        }
    }

    /** 扩展清单：运行时扫描生成（静态初始化一次），换 JDK 版本自动不同。 */
    public static final List<Ext> ALL = build();

    /** 运行时识别：只收录当前 JDK 能加载、且属于 java／jdk 模块的支持类，按包聚类。 */
    private static List<Ext> build() {
        Map<String, List<String>> byPkg = new LinkedHashMap<>();
        Map<String, String> pkgModule = new LinkedHashMap<>();
        for (String fqn : CLBC_BuiltinCatalog.CURATED_CLASSES) {
            Class<?> k;
            try { k = Class.forName(fqn); }
            catch (Throwable t) { continue; }   // 当前 JDK 没有这个类 → 自动不出现在扩展里
            Module m = k.getModule();
            String mn = m == null ? "" : m.getName();
            if (!(mn.startsWith("java.") || mn.startsWith("jdk."))) continue;
            int dot = fqn.lastIndexOf('.');
            if (dot <= 0) continue;
            String pkg = fqn.substring(0, dot);
            byPkg.computeIfAbsent(pkg, x -> new ArrayList<>()).add(fqn);
            pkgModule.putIfAbsent(pkg, mn);
        }
        List<Ext> out = new ArrayList<>();
        Set<String> pkgs = new TreeSet<>(byPkg.keySet());
        for (String pkg : pkgs) {
            List<String> classes = byPkg.get(pkg);
            String cn = CLBC_JdkHistory.chinesePackage(pkg);
            String mod = pkgModule.get(pkg);
            String desc = "JDK 模块 " + (mod == null || mod.isEmpty() ? "（未命名）" : mod)
                    + " · 当前运行时识别 " + classes.size() + " 个支持类";
            long color = CLBC_Lang.categoryColor(CLBC_CategoryMap.categoryOf(classes.get(0)));
            out.add(new Ext(pkg, cn, desc, color, classes.get(0),
                    mod == null ? "" : mod, pkg));
        }
        return List.copyOf(out);
    }

    /** 当前启用状态：【T10】默认【全关】——用户明确「扩展默认一个也不启用」，要用哪个再手动开。 */
    private static final Map<String, Boolean> STATE = new LinkedHashMap<>();
    /** 开关变更版本号：工具箱据此判断是否要重算可见分类。 */
    private static int revision = 0;
    static { STATE.putAll(defaults()); reload(); }

    private static Map<String, Boolean> defaults() {
        Map<String, Boolean> m = new LinkedHashMap<>();
        for (Ext e : ALL) m.put(e.id, Boolean.FALSE);   // 默认全关
        return m;
    }

    public static synchronized int revision() { return revision; }

    /** 从 config.properties 重新读取全部扩展开关（【T10】缺省 false = 默认全关）。 */
    public static synchronized void reload() {
        Properties p = null;
        try {
            if (CLBC_AppContext.configFile != null) p = CLBC_Util.loadProperties(CLBC_AppContext.configFile);
        } catch (Throwable t) {
            CLBC_Util.log("[扩展] 读取配置失败，沿用当前开关: " + t.getClass().getSimpleName());
        }
        boolean changed = false;
        for (Ext e : ALL) {
            String v = p == null ? null : p.getProperty("ext." + e.id);
            // 只有显式 true 才启用；缺失/异常一律关（与 defaults 全关一致）。
            boolean on = v != null && "true".equalsIgnoreCase(v.trim());
            boolean old = Boolean.TRUE.equals(STATE.get(e.id));
            if (old != on) { STATE.put(e.id, on); changed = true; }
        }
        if (changed) revision++;
    }

    public static synchronized boolean enabled(String id) {
        return Boolean.TRUE.equals(STATE.get(id));
    }

    /** 切换扩展开关：立即生效（工具箱下次绘制按新状态过滤），并落盘配置。 */
    public static synchronized void setEnabled(String id, boolean value) {
        if (!STATE.containsKey(id)) return;
        boolean old = Boolean.TRUE.equals(STATE.get(id));
        STATE.put(id, value);
        if (old != value) revision++;
        CLBC_Util.saveSetting("ext." + id, String.valueOf(value));
        CLBC_Util.log("JDK 扩展 " + id + (value ? " 已启用" : " 已停用"));
    }

    /** 全部扩展id → 是否启用（绘制快照，避免每帧读磁盘） */
    public static synchronized Map<String, Boolean> snapshot() {
        return new LinkedHashMap<>(STATE);
    }

    /** 类全限定名 → 所属扩展（最长包前缀优先）；未覆盖的类返回 null。 */
    public static Ext ownerOf(String fqn) {
        if (fqn == null || fqn.isEmpty()) return null;
        Ext best = null;
        int bestLen = -1;
        for (Ext e : ALL) {
            for (String pre : e.prefixes) {
                if (!fqn.startsWith(pre)) continue;
                // 前缀边界：必须是完整包段，避免 java.utilRegex 之类误命中
                if (fqn.length() > pre.length() && fqn.charAt(pre.length()) != '.') continue;
                if (pre.length() > bestLen) { best = e; bestLen = pre.length(); }
            }
        }
        return best;
    }

    /** 类是否可用：未收录到任何扩展的类（如纯语法/用户自定义）永远可用。 */
    public static boolean classEnabled(String fqn) {
        Ext e = ownerOf(fqn);
        return e == null || enabled(e.id);
    }

    /** 扩展覆盖的支持类数量（读硬编码清单统计，不反射）。 */
    public static int classCount(Ext e) {
        if (e == null) return 0;
        int n = 0;
        for (String fqn : CLBC_BuiltinCatalog.CURATED_CLASSES) if (ownerOf(fqn) == e) n++;
        return n;
    }

    /** 当前 JDK 版本标识（诊断/日志用，来自运行时而非硬编码）。 */
    public static String jdkVersion() { return Runtime.version().toString(); }

    /** 扩展覆盖的包总数（当前 JDK 运行时识别出的 java／jdk 包）。 */
    public static int runtimePackageCount() {
        Set<String> pkgs = new TreeSet<>();
        for (Module m : ModuleLayer.boot().modules()) {
            String n = m.getName();
            if (n.startsWith("java.") || n.startsWith("jdk.")) pkgs.addAll(m.getPackages());
        }
        return pkgs.size();
    }

    /** 当前所有启用扩展的 id 列表（诊断/日志用） */
    public static synchronized List<String> enabledIds() {
        List<String> r = new ArrayList<>();
        for (Ext e : ALL) if (enabled(e.id)) r.add(e.id);
        return r;
    }
}
