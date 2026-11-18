package clbc.ui;

import java.util.LinkedHashMap;
import java.util.Map;

import clbc.blocks.CLBC_BlockLibrary;
import clbc.core.CLBC_JdkHistory;

/**
 * 积木文字中文化（显示层，零副作用）。
 *
 * 只改「画给用户看的字」，绝不改 Input.value / args / 生成代码——
 * 下拉里显示「数组列表 ▸ 取值」，背后仍是 ArrayList.get，生成的 Java 一字不变。
 *
 * 数据源分工（严格按约定）：
 *  ① JDK 自带库 → 自识别：类名走 {@link CLBC_JdkHistory#chineseClass}（全限定名→中文），
 *     方法名走 {@link CLBC_JdkHistory#chineseMethod}（与 lang/jdk-*.json 同源的 275 词词典）。
 *     本类**不硬编码任何 JDK 库类名/方法名**，它们随 JDK 识别数据自动更新。
 *  ② 不依赖任何库的纯 Java 语法标识符（关键字 / 字面量 / 基本类型 / 修饰符）→ 硬编码下表。
 *     这些是语言本身的一部分，不属于任何库，没有可自识别的数据源。
 * 未收录的一律原样返回（宁可留英文，也不臆造翻译）。
 */
public final class CLBC_I18n {

    private CLBC_I18n() {}

    /** 正则只编译一次：原 display() 每次都 String.matches + Pattern.compile，
     *  installBuiltins 登记近万条定义时每条至少编译两次，是渲染主线程卡死数秒的直接热点。 */
    private static final java.util.regex.Pattern ASCII_WORD =
            java.util.regex.Pattern.compile("[A-Za-z][A-Za-z0-9_]*");

    /** display 结果缓存：积木标签大量重复，缓存后每个不同字符串只 translate 一次，
     *  避免反复走 simpleClassFromLibraries / libraryMethodCn 的全库遍历。上限防膨胀。 */
    private static final Map<String, String> DISPLAY_CACHE = new LinkedHashMap<String, String>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, String> e) { return size() > 8192; }
    };

    /**
     * 纯 Java 语法标识符硬编码中文表（无库依赖：关键字 / 基本类型 / 字面量 / 修饰符）。
     * 不含任何 JDK 库类名或方法名——那些走自识别。
     */
    private static final Map<String, String> JAVA_SYNTAX = new LinkedHashMap<>();
    static {
        // 基本类型（Java 语言关键字，非 java.lang.Integer 等库类）
        String[][] types = {
            {"byte","8位整数"},{"short","16位整数"},{"int","32位整数"},{"long","64位整数"},
            {"float","32位小数"},{"double","64位小数"},{"char","字符"},{"boolean","布尔"},
            {"void","无返回值"},{"var","自动类型"}
        };
        // 字面量 / 关键字
        String[][] literals = {
            {"true","真"},{"false","假"},{"null","空"},{"this","本对象"},{"super","父类"}
        };
        // 修饰符 / 其他关键字
        String[][] mods = {
            {"public","公开"},{"protected","受保护"},{"private","私有"},
            {"static","静态"},{"final","最终"},{"abstract","抽象"},
            {"synchronized","同步"},{"volatile","易变"},{"transient","瞬态"},
            {"native","本地"},{"default","默认"}
        };
        for (String[] p : types)    JAVA_SYNTAX.putIfAbsent(p[0], p[1]);
        for (String[] p : literals) JAVA_SYNTAX.putIfAbsent(p[0], p[1]);
        for (String[] p : mods)     JAVA_SYNTAX.putIfAbsent(p[0], p[1]);
    }

    /**
     * 把任意积木显示文字（下拉值、标签、中英混排整句）翻译成中文。
     * 中文与空值原样返回；其中夹带的 ASCII 词条逐词查表，未收录的原样保留。
     * 类名（首字母大写）与成员名（方法/字段）按词典分层查，不会互串。
     */
    public static String display(String v) {
        if (v == null || v.isEmpty()) return v;
        String cached = DISPLAY_CACHE.get(v);
        if (cached != null) return cached;
        String result = displayUncached(v);
        DISPLAY_CACHE.put(v, result);
        return result;
    }

    private static String displayUncached(String v) {
        // 整串（单个方法名 / 简单类名 / 关键字）
        String whole = translate(v);
        if (!whole.equals(v)) return whole;
        // 复合显示（如 "A ▸ B"、"A.B()"）：逐词翻译，保留分隔符
        // 用预编译的 ASCII_WORD，杜绝每次调用重复编译正则（原 v.matches / Pattern.compile 是卡死热点）
        if (!ASCII_WORD.matcher(v).matches()) {
            StringBuilder sb = new StringBuilder();
            java.util.regex.Matcher m = ASCII_WORD.matcher(v);
            int last = 0;
            while (m.find()) {
                sb.append(v, last, m.start()).append(translate(m.group()));
                last = m.end();
            }
            sb.append(v.substring(last));
            return sb.toString();
        }
        return v;
    }

    private static String translate(String word) {
        if (word == null || word.isEmpty()) return word;
        // ① 纯 Java 语法标识符（硬编码，且仅当确认是语法词，避免误伤同名库方法）
        String syn = JAVA_SYNTAX.get(word);
        if (syn != null) return syn;
        // ② JDK 自带库类名：首字母大写 → resolveFqn 拿全限定名 → 自识别中文
        if (Character.isUpperCase(word.charAt(0))) {
            String fqn = CLBC_BlockLibrary.resolveFqn(word);
            if (fqn != null) {
                String cn = CLBC_JdkHistory.chineseClass(fqn);
                if (cn != null && !cn.equals(word)) return cn;
                // 内置词典未收录 → 按需加载该类的库语言文件（lang/jdk-<包名>.json）
                cn = libraryClassCn(fqn);
                if (cn != null) return cn;
            }
        }
        // ②b 简单类名（无全限定名）也按需查一次库文件
        if (Character.isUpperCase(word.charAt(0))) {
            String cn = simpleClassFromLibraries(word);
            if (cn != null) return cn;
        }
        // ③ 简单类名兜底（resolveFqn 遇到同名类无法消歧时）
        if (Character.isUpperCase(word.charAt(0))) {
            String cn = CLBC_JdkHistory.chineseSimpleClass(word);
            if (cn != null && !cn.equals(word)) return cn;
        }
        // ④ JDK 库方法名 / 字段名：同一套自识别词典
        String cn = CLBC_JdkHistory.chineseMethod(word);
        if (cn != null && !cn.equals(word)) return cn;
        cn = CLBC_JdkHistory.chineseField(word);
        if (cn != null && !cn.equals(word)) return cn;
        // ⑤ 按需库文件的方法名（内置词典未收录时）
        cn = libraryMethodCn(word);
        return cn == null ? word : cn;
    }

    // ===== 按需库语言文件查询（lang/jdk-<包名>.json，一个库一个文件，文件名=库名） =====

    /** 全限定类名 → 从所属包的库文件取中文；未加载/未收录返回 null。 */
    private static String libraryClassCn(String fqn) {
        int i = fqn.lastIndexOf('.');
        if (i <= 0) return null;
        clbc.blocks.CLBC_LangFiles.LibData d = clbc.blocks.CLBC_LangFiles.load(fqn.substring(0, i));
        return d == null ? null : d.classCn.get(fqn);
    }

    /** 简单类名 → 遍历已识别库文件找同名类（仅在内置词典失败时走，开销可控）。 */
    private static String simpleClassFromLibraries(String simple) {
        for (String lib : clbc.blocks.CLBC_LangFiles.availableLibraries()) {
            clbc.blocks.CLBC_LangFiles.LibData d = clbc.blocks.CLBC_LangFiles.load(lib);
            if (d == null) continue;
            for (java.util.Map.Entry<String, String> e : d.classCn.entrySet()) {
                if (e.getKey().endsWith("." + simple)) return e.getValue();
            }
        }
        return null;
    }

    /** 方法名 → 在已加载（或当前启用）的库文件里查中文；为避免全量加载，只查已缓存的库。 */
    private static String libraryMethodCn(String method) {
        for (clbc.blocks.CLBC_LangFiles.LibData d : clbc.blocks.CLBC_LangFiles.loaded()) {
            String cn = d.methodCn.get(method);
            if (cn != null) return cn;
        }
        return null;
    }
}
