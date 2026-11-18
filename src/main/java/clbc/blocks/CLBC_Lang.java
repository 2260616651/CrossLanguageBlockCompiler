package clbc.blocks;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import clbc.util.CLBC_Util;

/**
 * 语言文件（lang.json）统一来源：积木键 → 显示模板 / 分类 / 库别名 / 分类配色。
 *
 * 双向转换、积木显示名、文本自定义、分类配色都以此为准（需求③ / ②-4）。
 *  · JSON 解析内嵌、零外部依赖（不依赖 org.json / jackson，避免 classpath 风险）。
 *  · 仅支持本工具使用的子集：对象与字符串（无数组），足够表达名称对应表。
 *  · 文件缺失 / 为空 / 解析失败时，回退到内置默认表，不阻塞运行。
 */
public final class CLBC_Lang {

    private static final String FILE = "lang.json";
    private static final String DEFAULT_LOCALE = "zh_CN";

    /** 积木键 → {模板文本(含 %0 %1 占位), 分类} */
    private static final Map<String, BlockDef> BLOCKS = new LinkedHashMap<>();
    /** 库全限定名 → 分类别名 */
    private static final Map<String, String> LIB_ALIASES = new LinkedHashMap<>();
    /** 库全限定名 → 显示短名（工具箱 / 调用块显示用；缺省用简单类名） */
    private static final Map<String, String> LIB_NAMES = new LinkedHashMap<>();
    /** 用户自定义黑名单（语言文件 unsafe 段；内置黑名单在 CLBC_BlockLibrary，二者取并集） */
    private static final Set<String> UNSAFE_CLASSES = new HashSet<>();
    private static final Set<String> UNSAFE_METHODS = new HashSet<>();
    /** 分类 → 配色（0xAARRGGBB）。语言文件 categories 段优先覆盖内置默认 */
    private static final Map<String, Long> CATEGORY_COLORS = new LinkedHashMap<>();
    private static String defaultLocale = DEFAULT_LOCALE;

    private CLBC_Lang() {}

    public static final class BlockDef {
        public final String text;       // 模板，含 %0 %1 ... 占位
        public final String category;   // 分类（颜色键）
        public BlockDef(String text, String category) { this.text = text; this.category = category; }
    }

    /** 库信息（供扩展展示「库名 + 介绍 + 方法描述」） */
    public static final class LibInfo {
        public final String fqn, displayName, category, description;
        public final List<MethodInfo> methods;
        public LibInfo(String fqn, String displayName, String category, String description, List<MethodInfo> methods) {
            this.fqn = fqn; this.displayName = displayName; this.category = category;
            this.description = description; this.methods = methods;
        }
    }

    /** 方法信息（积木文本语言 + 介绍 + 安全标记） */
    public static final class MethodInfo {
        public final String name, returnType, blockText, category, description;
        public final boolean isStatic, isField, safe;
        public final List<String> params;
        public MethodInfo(String name, String returnType, List<String> params, boolean isStatic, boolean isField,
                         String blockText, String category, boolean safe, String description) {
            this.name = name; this.returnType = returnType; this.params = params;
            this.isStatic = isStatic; this.isField = isField; this.blockText = blockText;
            this.category = category; this.safe = safe; this.description = description;
        }
    }

    /** 标准库 ↔ 积木 对应表（java.json 的 libraries[] 加载于此；扩展 UI / 工具箱从此读取介绍） */
    private static final Map<String, LibInfo> LIBRARIES = new LinkedHashMap<>();

    static {
        seedBuiltins();
        loadFile();
        // 尝试加载默认位置的完整标准库对应表（程序启动后生成的 lang/java.json）
        try { loadLibraryFile(Paths.get("lang", "java.json")); }
        catch (Throwable ignored) { CLBC_Util.log("[Lang] 标准库对应表加载降级（沿用内置表）: " + ignored.getClass().getSimpleName()); }
        // 语言文件夹注册中心：无论谁先被加载，都把 mapping_file.json 的映射灌进来（最高优先级）
        try { clbc.core.CLBC_LanguageRegistry.applyToLang(); }
        catch (Throwable ignored) { CLBC_Util.log("[Lang] 语言注册中心映射灌入降级: " + ignored.getClass().getSimpleName()); }
    }

    private static void seedBuiltins() {
        // 类名由 hat 块的可编辑名称框承载，标签只留前缀；写 "类 %0" 会画成「类 Demo Demo」
        put("unit.class",      "类", "结构");
        put("unit.method",     "%0 %1 %2( %3 )", "结构");
        put("control.if",      "如果 %0 那么", "结构");
        put("control.else",    "否则", "结构");
        put("control.while",   "当 %0", "结构");
        put("control.for",     "重复 直到 %0", "结构");
        put("control.forEach", "对于 %0 在 %1 中", "结构");
        put("control.repeat",  "重复 %0 次", "结构");
        put("data.var",        "变量格式:%0 变量名:%1 值:%2", "数据");
        put("data.const",      "常量格式:%0 常量名:%1 值:%2", "数据");
        // #253：new Xxx(lambda -> {…}) 的 C 口容器 + new 表达式，避免显示成原始 key（data.new...）
        put("data.new",        "新建 %0 ( %1 )", "数据");
        put("data.newCallback","新建 %2 ( %3 -> )", "结构");
        put("data.assign",     "将 %0 设为 %1", "数据");
        put("data.return",     "返回 %0", "数据");
        put("ops.call",        "调用 %0", "系统");
        put("expr.number",     "%0", "数学");
        put("expr.string",     "%0", "字符串");
        put("expr.bool",       "%0", "运算");
        put("raw.code",        "%0", "结构");
        // 库别名（全限定名 → 分类）
        LIB_ALIASES.put("java.lang.Math", "数学");
        LIB_ALIASES.put("java.lang.System", "系统");
        LIB_ALIASES.put("java.lang.String", "字符串");
        // 库显示短名（全限定名 → 显示名；缺省回退简单类名）
        LIB_NAMES.put("java.lang.Math", "Math");
        LIB_NAMES.put("java.lang.System", "System");
        LIB_NAMES.put("java.lang.String", "String");
        LIB_NAMES.put("java.util.List", "列表");
        LIB_NAMES.put("java.util.ArrayList", "列表");
        LIB_NAMES.put("java.util.Map", "映射");
        LIB_NAMES.put("java.util.HashMap", "映射");
        LIB_NAMES.put("java.util.Set", "集合");
        LIB_NAMES.put("java.util.HashSet", "集合");
        // 分类默认配色（语言文件 categories 段可覆盖）
        CATEGORY_COLORS.put("结构",   0xFFFFAB19L);
        CATEGORY_COLORS.put("数据",   0xFFFF661AL);
        CATEGORY_COLORS.put("系统",   0xFFFF8C1AL);
        CATEGORY_COLORS.put("数学",   0xFF59C059L);
        CATEGORY_COLORS.put("字符串", 0xFF9966FFL);
        CATEGORY_COLORS.put("运算",   0xFF5CB1D6L);
        // JDK 细分分类（硬编码配色，与 CLBC_CategoryMap 的分类名一一对应；Scratch 风格色系）
        CATEGORY_COLORS.put("日期时间", 0xFFE4578AL);
        CATEGORY_COLORS.put("文件IO",   0xFF7B68B5L);
        CATEGORY_COLORS.put("网络",     0xFF4CA5C7L);
        CATEGORY_COLORS.put("并发",     0xFFD97A2BL);
        CATEGORY_COLORS.put("正则",     0xFF8A6BBEL);
        CATEGORY_COLORS.put("文本格式", 0xFFB07AA1L);
        CATEGORY_COLORS.put("压缩",     0xFF6B9E5CL);
        CATEGORY_COLORS.put("函数式",   0xFF5AA9A0L);
        // 纯语法分类：不依赖任何库（第三方 / JDK 自带）的 Java 语言本身
        CATEGORY_COLORS.put("语法",   0xFF4C97FFL);
    }

    /** 纯语法积木表（与 lang/java-core.json 同源）；供工具箱 / 扩展面板显示与介绍 */
    public static List<clbc.blocks.CLBC_CoreSyntax.Def> coreBlocks() {
        return clbc.blocks.CLBC_CoreSyntax.all();
    }
    public static String coreText(String key, List<String> args) {
        return clbc.blocks.CLBC_CoreSyntax.text(key, args);
    }
    public static String coreDescription(String key) {
        clbc.blocks.CLBC_CoreSyntax.Def d = clbc.blocks.CLBC_CoreSyntax.def(key);
        return d == null ? "" : d.desc;
    }

    private static void put(String key, String text, String cat) {
        BLOCKS.put(key, new BlockDef(text, cat));
    }

    /**
     * 由语言文件夹注册中心灌入外部映射。
     * 优先级：内置写死表 &lt; lang/ 下所有导入 .json &lt; mapping_file.json
     * （注入顺序已由 CLBC_LanguageRegistry 保证，这里只负责落表）。
     * 只覆盖「显示模板」，不改积木定义；纯语法积木定义始终来自 CLBC_CoreSyntax 硬编码表。
     */
    public static void applyMappings(Map<String, String> m) {
        if (m == null) return;
        for (Map.Entry<String, String> e : m.entrySet()) {
            String k = e.getKey(), v = e.getValue();
            if (k == null || k.isEmpty() || v == null) continue;
            BlockDef old = BLOCKS.get(k);
            BLOCKS.put(k, new BlockDef(v, old == null ? "结构" : old.category));
        }
    }

    // ========== 对外查询 ==========

    /** 用参数填充模板：%0 %1 ... 依次替换为 args[i] */
    public static String text(String key, List<String> args) {
        BlockDef d = BLOCKS.get(key);
        if (d == null) return key;
        String t = d.text;
        if (args != null) {
            for (int i = 0; i < args.size(); i++) {
                t = t.replace("%" + i, args.get(i) == null ? "" : args.get(i));
            }
        }
        return t;
    }

    /** 积木键 → 分类（决定颜色） */
    public static String categoryOf(String key) {
        BlockDef d = BLOCKS.get(key);
        return d == null ? "结构" : d.category;
    }

    /** 分类 → 配色（语言文件 categories 段优先覆盖内置默认；兜底橙） */
    public static long categoryColor(String cat) {
        Long c = CATEGORY_COLORS.get(cat);
        return c != null ? c : 0xFFFFAB19L;
    }

    /** 全部分类名（供工具箱左侧分类栏遍历，顺序即插入顺序） */
    public static List<String> categories() {
        return new ArrayList<>(CATEGORY_COLORS.keySet());
    }

    /** 积木键 → 颜色（= 该键分类的配色） */
    public static long colorOf(String key) {
        return categoryColor(categoryOf(key));
    }

    /** 库全限定名 → 分类别名（用于调用块着色 / 库识别） */
    public static String libraryAlias(String fqn) {
        return LIB_ALIASES.getOrDefault(fqn, fqn);
    }

    /** 库全限定名 → 显示短名（缺省用简单类名） */
    public static String libraryDisplayName(String fqn) {
        String n = LIB_NAMES.get(fqn);
        if (n != null) return n;
        int i = fqn.lastIndexOf('.');
        return i >= 0 ? fqn.substring(i + 1) : fqn;
    }

    /** 用户是否将某类列入黑名单（语言文件 unsafe.classes）。
     *  支持精确匹配与包前缀匹配（如 "java.lang.reflect" / "sun" 视为前缀），
     *  与内置 {@link CLBC_BlockLibrary#isUnsafeClass} 语义一致。 */
    public static boolean isUnsafeClass(String fqn) {
        for (String u : UNSAFE_CLASSES)
            if (fqn.equals(u) || fqn.startsWith(u + ".")) return true;
        return false;
    }

    /** 用户是否将某方法列入黑名单（匹配 fqn#method 与 简单名#method） */
    public static boolean isUnsafeMethod(String fqn, String method) {
        if (UNSAFE_METHODS.contains(fqn + "#" + method)) return true;
        int i = fqn.lastIndexOf('.');
        String simple = i >= 0 ? fqn.substring(i + 1) : fqn;
        return UNSAFE_METHODS.contains(simple + "#" + method);
    }

    // ========== 标准库对应表（libraries[]）加载与查询 ==========

    private static String simpleNameOf(String fqn) {
        int i = fqn.lastIndexOf('.');
        return i >= 0 ? fqn.substring(i + 1) : fqn;
    }

    /** 加载完整标准库对应表（lang/java.json 的 libraries[]）到内存，供扩展展示库名/介绍/方法描述。 */
    @SuppressWarnings("unchecked")
    public static void loadLibraryFile(Path p) {
        if (p == null || !Files.exists(p)) return;
        try {
            String s = new String(Files.readAllBytes(p), StandardCharsets.UTF_8).trim();
            if (s.isEmpty()) return;
            Map<String, Object> root = (Map<String, Object>) parseTop(s);
            Object libsO = get(root, "libraries");
            if (!(libsO instanceof List)) return;
            for (Object lo : (List<Object>) libsO) {
                if (!(lo instanceof Map)) continue;
                Map<String, Object> lm = (Map<String, Object>) lo;
                String fqn = str(get(lm, "fqn"), "");
                if (fqn.isEmpty()) continue;
                String displayName = str(get(lm, "displayName"), simpleNameOf(fqn));
                String category = str(get(lm, "category"), "系统");
                String description = str(get(lm, "description"), "");
                List<MethodInfo> methods = new ArrayList<>();
                Object msO = get(lm, "methods");
                if (msO instanceof List) {
                    for (Object mo : (List<Object>) msO) {
                        if (!(mo instanceof Map)) continue;
                        Map<String, Object> mm = (Map<String, Object>) mo;
                        String name = str(get(mm, "name"), "");
                        if (name.isEmpty()) continue;
                        boolean isStatic = Boolean.parseBoolean(str(get(mm, "isStatic"), "false"));
                        boolean isField = Boolean.parseBoolean(str(get(mm, "isField"), "false"));
                        boolean safe = Boolean.parseBoolean(str(get(mm, "safe"), "true"));
                        String returnType = str(get(mm, "returnType"), "");
                        String blockText = str(get(mm, "blockText"), name);
                        String mcat = str(get(mm, "category"), category);
                        String mdesc = str(get(mm, "description"), "");
                        List<String> params = new ArrayList<>();
                        Object psO = get(mm, "params");
                        if (psO instanceof List) for (Object po : (List<Object>) psO) params.add(po.toString());
                        methods.add(new MethodInfo(name, returnType, params, isStatic, isField,
                                blockText, mcat, safe, mdesc));
                    }
                }
                LIBRARIES.put(fqn, new LibInfo(fqn, displayName, category, description, methods));
            }
            CLBC_Util.log("[Lang] 已加载标准库对应表: " + LIBRARIES.size() + " 个类，"
                    + LIBRARIES.values().stream().mapToInt(l -> l.methods.size()).sum() + " 条方法");
        } catch (Exception ex) {
            CLBC_Util.log("标准库对应表解析失败: " + ex.getMessage());
        }
    }

    /** 全部已加载的库信息（供扩展 UI 遍历展示） */
    public static List<LibInfo> libraries() { return new ArrayList<>(LIBRARIES.values()); }

    /** 按全限定名取库信息（null 表示不在对应表内） */
    public static LibInfo library(String fqn) { return LIBRARIES.get(fqn); }

    /** 按 库fqn + 方法名 取方法信息（用于展示积木文本/介绍/安全标记） */
    public static MethodInfo methodInfo(String fqn, String name) {
        LibInfo l = LIBRARIES.get(fqn);
        if (l == null) return null;
        for (MethodInfo m : l.methods) if (m.name.equals(name)) return m;
        return null;
    }

    /** 已加载库类数（调试/自测用） */
    public static int libraryCount() { return LIBRARIES.size(); }

    // ========== 文件加载（极简 JSON 解析） ==========

    @SuppressWarnings("unchecked")
    private static void loadFile() {
        Path p = Paths.get(FILE);
        if (!Files.exists(p)) return;
        try {
            String s = new String(Files.readAllBytes(p), StandardCharsets.UTF_8).trim();
            if (s.isEmpty()) return;
            Map<String, Object> root = (Map<String, Object>) parseTop(s);
            defaultLocale = str(get(root, "defaultLocale"), DEFAULT_LOCALE);

            Object locsO = get(root, "locales");
            if (locsO instanceof Map) {
                Map<String, Object> locs = (Map<String, Object>) locsO;
                Object locO = locs.get(defaultLocale);
                if (locO == null && !locs.isEmpty()) locO = firstValue(locs);
                if (locO instanceof Map) {
                    Map<String, Object> loc = (Map<String, Object>) locO;
                    Object blocksO = loc.get("blocks");
                    if (blocksO instanceof Map) {
                        Map<String, Object> blocks = (Map<String, Object>) blocksO;
                        for (Map.Entry<String, Object> e : blocks.entrySet()) {
                            // 纯语法积木（不依赖 JDK 内置库 / 第三方库）的默认中文写死在
                            // CLBC_CoreSyntax，导入的 lang.json 一律不许覆盖它的定义与默认文本；
                            // 用户改名只能经 mapping_file.json → applyMappings 生效。
                            if (CLBC_CoreSyntax.isCore(e.getKey())) continue;
                            if (e.getValue() instanceof Map) {
                                Map<String, Object> m = (Map<String, Object>) e.getValue();
                                String t = str(m.get("text"), e.getKey());
                                String cat = str(m.get("category"), "结构");
                                BLOCKS.put(e.getKey(), new BlockDef(t, cat));
                            }
                        }
                    }
                }
            }
            Object catsO = get(root, "categories");
            if (catsO instanceof Map) {
                Map<String, Object> cats = (Map<String, Object>) catsO;
                for (Map.Entry<String, Object> e : cats.entrySet()) {
                    CATEGORY_COLORS.put(e.getKey(), parseHex(str(e.getValue(), "#FFAB19")));
                }
            }
            Object laO = get(root, "libraryAliases");
            if (laO instanceof Map) {
                Map<String, Object> la = (Map<String, Object>) laO;
                for (Map.Entry<String, Object> e : la.entrySet()) {
                    LIB_ALIASES.put(e.getKey(), str(e.getValue(), e.getKey()));
                }
            }
            Object lnO = get(root, "libraryNames");
            if (lnO instanceof Map) {
                Map<String, Object> ln = (Map<String, Object>) lnO;
                for (Map.Entry<String, Object> e : ln.entrySet()) {
                    LIB_NAMES.put(e.getKey(), str(e.getValue(), e.getKey()));
                }
            }
            // 用户自定义黑名单（极简 JSON 仅支持对象，故用对象而非数组）
            Object unO = get(root, "unsafe");
            if (unO instanceof Map) {
                Map<String, Object> un = (Map<String, Object>) unO;
                Object ucO = un.get("classes");
                if (ucO instanceof Map) {
                    for (Object v : ((Map<String, Object>) ucO).values()) UNSAFE_CLASSES.add(v.toString());
                }
                Object umO = un.get("methods");
                if (umO instanceof Map) {
                    for (Object v : ((Map<String, Object>) umO).values()) UNSAFE_METHODS.add(v.toString());
                }
            }
        } catch (Exception ex) {
            CLBC_Util.log("语言文件解析失败，使用内置默认表: " + ex.getMessage());
        }
    }

    /** 解析 "#RRGGBB" / "#AARRGGBB" / "0x..." 为 0xAARRGGBB（非法兜底橙） */
    private static long parseHex(String s) {
        String t = s.trim().replace("#", "").replace("0x", "");
        if (t.length() == 6) t = "FF" + t;
        if (t.length() == 8) {
            try { return Long.parseLong(t, 16) & 0xFFFFFFFFL; }
            catch (NumberFormatException e) { return 0xFFFFAB19L; }
        }
        return 0xFFFFAB19L;
    }

    private static Object firstValue(Map<String, Object> m) {
        for (Object v : m.values()) return v;
        return null;
    }

    private static Object get(Map<String, Object> m, String k) {
        return m == null ? null : m.get(k);
    }

    private static String str(Object o, String dflt) {
        return o == null ? dflt : o.toString();
    }

    // ---- 极简 JSON：仅支持 对象{} 与 字符串""（本工具使用的子集） ----

    @SuppressWarnings("unchecked")
    /** 解析 JSON 顶层为 Map（零外部库）；供按需加载器 CLBC_LangFiles 复用。 */
    public static Map<String, Object> parseTop(String s) {
        return parseObject(s, new int[]{0});
    }

    private static Map<String, Object> parseObject(String s, int[] pos) {
        Map<String, Object> map = new LinkedHashMap<>();
        skip(s, pos);
        expect(s, pos, '{');
        skip(s, pos);
        if (peek(s, pos) == '}') { pos[0]++; return map; }
        while (true) {
            skip(s, pos);
            String key = readString(s, pos);
            skip(s, pos);
            expect(s, pos, ':');
            Object val = readValue(s, pos);
            map.put(key, val);
            skip(s, pos);
            char c = peek(s, pos);
            if (c == ',') { pos[0]++; continue; }
            if (c == '}') { pos[0]++; break; }
            break;
        }
        return map;
    }

    private static Object readValue(String s, int[] pos) {
        skip(s, pos);
        char c = peek(s, pos);
        if (c == '"') return readString(s, pos);
        if (c == '{') return parseObject(s, pos);
        if (c == '[') return parseArray(s, pos);
        int start = pos[0];
        while (pos[0] < s.length()) {
            char ch = s.charAt(pos[0]);
            if (ch == ',' || ch == '}' || ch == ']') break;
            pos[0]++;
        }
        return s.substring(start, pos[0]).trim();
    }

    private static List<Object> parseArray(String s, int[] pos) {
        List<Object> list = new ArrayList<>();
        skip(s, pos);
        expect(s, pos, '[');
        skip(s, pos);
        if (peek(s, pos) == ']') { pos[0]++; return list; }
        while (true) {
            Object v = readValue(s, pos);
            list.add(v);
            skip(s, pos);
            char c = peek(s, pos);
            if (c == ',') { pos[0]++; continue; }
            if (c == ']') { pos[0]++; break; }
            break;
        }
        return list;
    }

    private static String readString(String s, int[] pos) {
        expect(s, pos, '"');
        StringBuilder sb = new StringBuilder();
        while (pos[0] < s.length()) {
            char c = s.charAt(pos[0]++);
            if (c == '"') return sb.toString();
            if (c == '\\') {
                char e = s.charAt(pos[0]++);
                switch (e) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    default: sb.append(e);
                }
            } else sb.append(c);
        }
        return sb.toString();
    }

    private static char peek(String s, int[] pos) {
        return pos[0] < s.length() ? s.charAt(pos[0]) : 0;
    }

    private static void skip(String s, int[] pos) {
        while (pos[0] < s.length()) {
            char c = s.charAt(pos[0]);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos[0]++;
            else break;
        }
    }

    private static void expect(String s, int[] pos, char c) {
        if (peek(s, pos) == c) pos[0]++;
    }
}
