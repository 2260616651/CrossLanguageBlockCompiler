package clbc.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import clbc.blocks.CLBC_CoreSyntax;
import clbc.util.CLBC_Util;

/**
 * 语言文件夹注册中心（启动时检查并生成；运行时改名的唯一落盘出口）。
 *
 * 目录：&lt;程序根目录&gt;/lang/
 *   · mapping_file.json —— 最高优先级用户映射表，运行时修改积木名称直接写入这里。
 *   · jdk-versions.json / jdk-&lt;包名&gt;.json —— 内置生成的 JDK 库语言文件（一个库一个文件，java1~java27）。
 *   · 其它任意名字的 .json —— 用户导入的语言文件，全部读取，不限定文件名。
 *
 * 优先级（低 → 高）：
 *   1. 程序内部写死的纯语法积木表 {@code CLBC_CoreSyntax}（定义与默认中文，不依赖任何文件）
 *   2. lang/ 下所有导入的 .json（含内置生成的 jdk-*.json）
 *   3. lang/mapping_file.json（覆盖一切导入语言文件）
 *
 * 「mapping_file.json 高于一切导入的语言文件」由加载顺序保证：扫描时先跳过它，最后单独加载。
 */
public final class CLBC_LanguageRegistry {

    public static final String LANG_DIR = "lang";
    public static final String MAPPING_FILE = "mapping_file.json";

    /** key（积木键 / fqn#方法）→ 中文显示模板：所有 .json 合并结果，供 CLBC_Lang(%N 模板) 使用 */
    private static final Map<String, String> MAPPINGS = new LinkedHashMap<>();
    /** 仅来自 mapping_file.json 的用户改名（{N} 模板体系），供纯语法积木 CLBC_CoreSyntax 使用。
     *  纯语法积木的默认中文写死在程序里，导入的任意语言文件一律不许改，只有用户改名能覆盖。 */
    private static final Map<String, String> USER_MAPPINGS = new LinkedHashMap<>();
    private static Path langDir;
    private static Path mappingFile;
    private static boolean ready = false;

    private CLBC_LanguageRegistry() {}

    /** 启动时调用一次：确保目录、mapping_file.json、JDK 库语言文件都在，然后扫描全部 .json */
    public static synchronized void init(Path appDir) {
        if (ready) return;
        Path dir = appDir.resolve(LANG_DIR);
        langDir = dir;
        mappingFile = dir.resolve(MAPPING_FILE);
        CLBC_AppContext.langDir = dir;
        CLBC_AppContext.mappingFile = mappingFile;

        try {
            Files.createDirectories(dir);
            CLBC_Util.log("[LanguageRegistry] 语言文件夹就绪: " + dir);
        } catch (IOException e) {
            CLBC_Util.log("!!!![LanguageRegistry] 语言文件夹创建失败: " + e.getMessage());
            return;
        }

        // 1) 内置 JDK 库语言文件（一个库一个文件，java1~java27），缺失才生成
        CLBC_JdkLangFiles.ensureAll(dir);

        // 2) mapping_file.json 必须在，且格式合法；已存在则保留用户内容
        if (!Files.exists(mappingFile)) {
            writeMappingFile();
            CLBC_Util.log("[LanguageRegistry] 已生成 " + MAPPING_FILE);
        } else {
            CLBC_Util.log("[LanguageRegistry] 已找到 " + MAPPING_FILE + "（最高优先级）");
        }

        // 3) 读取文件夹里【所有】.json，不限文件名
        reload();
        ready = true;
        CLBC_Util.log("[LanguageRegistry] 扫描完成：映射 " + MAPPINGS.size()
                + " 条，来源文件 " + countJsonFiles() + " 个；mapping_file.json 优先级最高");

        // 4) 灌入 CLBC_Lang（会触发其静态初始化；若已初始化则直接覆盖）
        clbc.blocks.CLBC_Lang.applyMappings(snapshot());
    }

    /** 供 CLBC_Lang 静态初始化时反向取数（无论谁先加载都保证一致） */
    public static void applyToLang() {
        if (!MAPPINGS.isEmpty()) clbc.blocks.CLBC_Lang.applyMappings(snapshot());
    }

    /** 重新扫描语言文件夹（用户导入新语言文件后可调用） */
    public static synchronized void reload() {
        MAPPINGS.clear();
        USER_MAPPINGS.clear();
        if (langDir == null || !Files.isDirectory(langDir)) return;
        List<Path> files = jsonFiles();
        for (Path f : files) {
            if (f.getFileName().toString().equalsIgnoreCase(MAPPING_FILE)) continue; // 最后单独加载
            try {
                Map<String, String> tmp = new LinkedHashMap<>();
                harvest(readJson(f), tmp);
                // 纯语法积木（不依赖 JDK 内置库 / 第三方库）的定义与默认中文写死在
                // CLBC_CoreSyntax：导入的任意语言文件一律不许改 core 键，
                // 只有 mapping_file.json 里的用户改名能覆盖（见 USER_MAPPINGS）。
                tmp.keySet().removeIf(CLBC_CoreSyntax::isCore);
                MAPPINGS.putAll(tmp);
                CLBC_Util.log("[LanguageRegistry] 导入语言文件: " + f.getFileName()
                        + "（" + tmp.size() + " 条映射）");
            } catch (Exception e) {
                CLBC_Util.log("!!!![LanguageRegistry] 语言文件解析失败 " + f.getFileName() + ": " + e.getMessage());
            }
        }
        // mapping_file.json 最后加载 → 覆盖一切导入文件
        if (mappingFile != null && Files.exists(mappingFile)) {
            try {
                Map<String, String> user = new LinkedHashMap<>();
                harvest(readJson(mappingFile), user);
                USER_MAPPINGS.putAll(user);
                MAPPINGS.putAll(user);   // 最后写入，保证优先级最高
                CLBC_Util.log("[LanguageRegistry] 应用最高优先级映射: " + MAPPING_FILE
                        + "（" + user.size() + " 条）");
            } catch (Exception e) {
                CLBC_Util.log("!!!![LanguageRegistry] " + MAPPING_FILE + " 解析失败: " + e.getMessage());
            }
        }
    }

    private static List<Path> jsonFiles() {
        List<Path> out = new ArrayList<>();
        try (var st = Files.list(langDir)) {
            st.filter(p -> Files.isRegularFile(p)
                    && p.getFileName().toString().toLowerCase().endsWith(".json"))
              // E 组：明确合并优先级。内置生成的 jdk-*.json 排最前（最低优先级，仅作基线）；
              // 其余 .json（java.json 及用户导入/可手改文件）排在其后，靠 reload() 的 putAll 覆盖基线，
              // mapping_file.json 仍在 reload() 里最后单独加载（最高优先级）。
              // 这样用户手改的 blockText 不会再被后加载的 jdk-*.json 覆盖；不改任何 lang 文件本身。
              .sorted((a, b) -> {
                  boolean aj = a.getFileName().toString().toLowerCase().startsWith("jdk-");
                  boolean bj = b.getFileName().toString().toLowerCase().startsWith("jdk-");
                  if (aj != bj) return aj ? -1 : 1;
                  return a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString());
              })
              .forEach(out::add);
        } catch (IOException e) {
            CLBC_Util.log("!!!![LanguageRegistry] 语言文件夹遍历失败: " + e.getMessage());
        }
        return out;
    }

    private static int countJsonFiles() { return jsonFiles().size(); }

    /** 运行时用户修改积木名称：立即写入 mapping_file.json，并同步到显示层 */
    public static synchronized void renameBlock(String key, String displayText) {
        if (key == null || key.isEmpty() || displayText == null) return;
        MAPPINGS.put(key, displayText);
        USER_MAPPINGS.put(key, displayText);
        writeMappingFile();
        clbc.blocks.CLBC_Lang.applyMappings(Collections.singletonMap(key, displayText));
        CLBC_Util.log("[LanguageRegistry] 积木改名已存入 " + MAPPING_FILE + ": " + key + " → " + displayText);
    }

    /** 删除某条用户自定义映射（恢复为导入文件 / 内置默认值） */
    public static synchronized void removeMapping(String key) {
        if (key == null || MAPPINGS.remove(key) == null) return;
        USER_MAPPINGS.remove(key);
        writeMappingFile();
        reload();
        clbc.blocks.CLBC_Lang.applyMappings(snapshot());
        CLBC_Util.log("[LanguageRegistry] 已移除映射: " + key);
    }

    /** 查询全量映射；无则 null */
    public static synchronized String get(String key) {
        return key == null ? null : MAPPINGS.get(key);
    }

    /** 只查 mapping_file.json 里的用户改名；纯语法积木只认这个，导入语言文件改不了它 */
    public static synchronized String getUser(String key) {
        return key == null ? null : USER_MAPPINGS.get(key);
    }

    public static synchronized Map<String, String> snapshot() {
        return new LinkedHashMap<>(MAPPINGS);
    }

    public static synchronized Map<String, String> userSnapshot() {
        return new LinkedHashMap<>(USER_MAPPINGS);
    }

    public static Path mappingFilePath() { return mappingFile; }
    public static Path langDirPath() { return langDir; }

    // ===================== mapping_file.json 读写 =====================

    private static void writeMappingFile() {
        if (mappingFile == null) return;
        StringBuilder s = new StringBuilder();
        s.append("{\n");
        s.append("  \"_comment\": \"CLBC 用户映射表。运行时修改积木名称会自动写入本文件。本文件优先级高于 lang/ 下所有其它 .json（含内置 jdk-*.json 与用户导入文件）。删除某条 key 即恢复默认名称。\",\n");
        s.append("  \"_priority\": \"highest\",\n");
        s.append("  \"mappings\": {\n");
        // 只写用户改名（USER_MAPPINGS）。绝不能把从导入 .json 合并来的全量映射倒进来，
        // 否则 mapping_file.json 会膨胀成所有库文件的副本，也失去「用户自定义」语义。
        Map<String, String> sorted = new TreeMap<>(USER_MAPPINGS);
        int i = 0;
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            if (i++ > 0) s.append(",\n");
            s.append("    ").append(CLBC_JdkLangFiles.jstr(e.getKey())).append(": ")
             .append(CLBC_JdkLangFiles.jstr(e.getValue()));
        }
        s.append("\n  }\n");
        s.append("}\n");
        try {
            Files.writeString(mappingFile, s.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            CLBC_Util.log("!!!![LanguageRegistry] " + MAPPING_FILE + " 写入失败: " + e.getMessage());
        }
    }

    // ===================== 从任意 .json 抽取「键 → 中文」 =====================

    private static Object readJson(Path f) throws IOException {
        // C 组：超大语言文件读入前做 size 检查，超限记日志并安全拒绝（reload() 的 catch 会跳过该文件）
        long sz = Files.size(f);
        if (sz > CLBC_Util.MAX_INPUT_FILE_BYTES) {
            throw new IOException("语言文件过大，已拒绝读取: " + f.getFileName() + " (" + sz + " 字节)");
        }
        String s = Files.readString(f, StandardCharsets.UTF_8).trim();
        if (s.isEmpty()) return null;
        int[] p = {0};
        Object v = new Json(s).value(p);
        return v;
    }

    @SuppressWarnings("unchecked")
    private static void harvest(Object root, Map<String, String> out) {
        if (!(root instanceof Map)) return;
        harvestObject((Map<String, Object>) root, out, 0);
    }

    @SuppressWarnings("unchecked")
    private static void harvestObject(Map<String, Object> obj, Map<String, String> out, int depth) {
        if (obj == null || depth > 12) return;

        // 1) mappings / texts：纯 键→字符串
        harvestFlat(get(obj, "mappings"), out);
        harvestFlat(get(obj, "texts"), out);

        // 2) blocks：值是字符串或 {text: "..."}
        Object blocks = get(obj, "blocks");
        if (blocks instanceof Map) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) blocks).entrySet()) {
                Object v = e.getValue();
                if (v instanceof String) out.put(e.getKey(), (String) v);
                else if (v instanceof Map) {
                    String t = str(((Map<String, Object>) v).get("text"));
                    if (t != null) out.put(e.getKey(), t);
                }
            }
        }

        // 3) locales.*.blocks
        Object locales = get(obj, "locales");
        if (locales instanceof Map) {
            for (Object loc : ((Map<String, Object>) locales).values()) {
                if (loc instanceof Map) harvestObject((Map<String, Object>) loc, out, depth + 1);
            }
        }

        // 4) classes[] / libraries[]：fqn + methods[].chinese|blockText → fqn#name
        harvestClasses(get(obj, "classes"), out);
        harvestClasses(get(obj, "libraries"), out);

        // 5) 顶层任意 "fqn#method" → 中文
        for (Map.Entry<String, Object> e : obj.entrySet()) {
            Object v = e.getValue();
            if (v instanceof String && e.getKey().contains("#")) out.put(e.getKey(), (String) v);
        }

        // 6) 递归其它容器（用户自定结构也能被扫到）
        for (Object v : obj.values()) {
            if (v instanceof Map) harvestObject((Map<String, Object>) v, out, depth + 1);
            else if (v instanceof List) harvestList((List<Object>) v, out, depth + 1);
        }
    }

    @SuppressWarnings("unchecked")
    private static void harvestList(List<Object> list, Map<String, String> out, int depth) {
        if (depth > 12) return;
        for (Object v : list) {
            if (v instanceof Map) harvestObject((Map<String, Object>) v, out, depth + 1);
            else if (v instanceof List) harvestList((List<Object>) v, out, depth + 1);
        }
    }

    @SuppressWarnings("unchecked")
    private static void harvestClasses(Object o, Map<String, String> out) {
        if (!(o instanceof List)) return;
        for (Object item : (List<Object>) o) {
            if (!(item instanceof Map)) continue;
            Map<String, Object> m = (Map<String, Object>) item;
            String fqn = str(get(m, "fqn"));
            String classCn = str(get(m, "chinese"));
            if (fqn != null && classCn != null) {
                out.put(fqn, classCn);
                out.put("library." + fqn, classCn);
            }
            Object ms = get(m, "methods");
            if (!(ms instanceof List) || fqn == null) continue;
            for (Object mo : (List<Object>) ms) {
                if (!(mo instanceof Map)) continue;
                Map<String, Object> mm = (Map<String, Object>) mo;
                String name = str(get(mm, "name"));
                String cn = first(get(mm, "chinese"), get(mm, "blockText"), get(mm, "text"));
                if (name == null || cn == null) continue;
                out.put(fqn + "#" + name, cn);
                out.put(fqn + "." + name, cn);
                Object al = get(mm, "aliases");
                if (al instanceof List) {
                    for (Object a : (List<Object>) al) {
                        if (a instanceof String) {
                            out.put(fqn + "#" + a, cn);
                            out.put(fqn + "." + a, cn);
                        }
                    }
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void harvestFlat(Object o, Map<String, String> out) {
        if (!(o instanceof Map)) return;
        for (Map.Entry<String, Object> e : ((Map<String, Object>) o).entrySet()) {
            if (e.getValue() instanceof String) out.put(e.getKey(), (String) e.getValue());
        }
    }

    private static Object get(Map<String, Object> m, String k) { return m == null ? null : m.get(k); }

    private static String str(Object o) { return o instanceof String ? (String) o : null; }

    private static String first(Object... os) {
        for (Object o : os) if (o instanceof String && !((String) o).isEmpty()) return (String) o;
        return null;
    }

    // ===================== 极简 JSON 解析（零外部依赖） =====================

    /** 支持 object / array / string / number / true / false / null 与四位十六进制 Unicode 转义 */
    private static final class Json {
        private final String s;
        Json(String s) { this.s = s; }

        Object value(int[] p) {
            skip(p);
            if (p[0] >= s.length()) return null;
            char c = s.charAt(p[0]);
            return switch (c) {
                case '{' -> object(p);
                case '[' -> array(p);
                case '"' -> string(p);
                case 't' -> { p[0] += 4; yield Boolean.TRUE; }
                case 'f' -> { p[0] += 5; yield Boolean.FALSE; }
                case 'n' -> { p[0] += 4; yield null; }
                default -> number(p);
            };
        }

        private Map<String, Object> object(int[] p) {
            Map<String, Object> m = new LinkedHashMap<>();
            p[0]++; // {
            while (p[0] < s.length()) {
                skip(p);
                if (p[0] >= s.length() || s.charAt(p[0]) == '}') { p[0]++; break; }
                String k = string(p);
                skip(p);
                if (p[0] < s.length() && s.charAt(p[0]) == ':') p[0]++;
                m.put(k, value(p));
                skip(p);
                if (p[0] < s.length() && s.charAt(p[0]) == ',') { p[0]++; continue; }
                if (p[0] < s.length() && s.charAt(p[0]) == '}') { p[0]++; break; }
                // 既不是 ',' 也不是 '}'：坏 JSON，指针会原地不动导致启动死循环；
                // 抛异常交由 reload() 的 catch 跳过该文件并记日志，其余文件照常加载
                throw new IllegalArgumentException("非法 object，位置 " + p[0]);
            }
            return m;
        }

        private List<Object> array(int[] p) {
            List<Object> a = new ArrayList<>();
            p[0]++; // [
            while (p[0] < s.length()) {
                skip(p);
                if (p[0] >= s.length() || s.charAt(p[0]) == ']') { p[0]++; break; }
                a.add(value(p));
                skip(p);
                if (p[0] < s.length() && s.charAt(p[0]) == ',') { p[0]++; continue; }
                if (p[0] < s.length() && s.charAt(p[0]) == ']') { p[0]++; break; }
                // 同 object：坏 JSON 必须抛异常，避免指针原地不动死循环
                throw new IllegalArgumentException("非法 array，位置 " + p[0]);
            }
            return a;
        }

        private String string(int[] p) {
            if (p[0] >= s.length() || s.charAt(p[0]) != '"')
                throw new IllegalArgumentException("期望字符串，位置 " + p[0]);
            p[0]++;
            StringBuilder b = new StringBuilder();
            while (p[0] < s.length()) {
                char c = s.charAt(p[0]++);
                if (c == '"') break;
                if (c == '\\' && p[0] < s.length()) {
                    char e = s.charAt(p[0]++);
                    switch (e) {
                        case 'n' -> b.append('\n');
                        case 'r' -> b.append('\r');
                        case 't' -> b.append('\t');
                        case 'b' -> b.append('\b');
                        case 'f' -> b.append('\f');
                        case 'u' -> {
                            if (p[0] + 4 <= s.length()) {
                                try { b.append((char) Integer.parseInt(s.substring(p[0], p[0] + 4), 16)); }
                                catch (NumberFormatException nfe) {
                                    CLBC_Util.log("[LanguageRegistry] 非法 \\u 转义，已忽略该字符: " + nfe.getMessage());
                                }
                                p[0] += 4;
                            }
                        }
                        default -> b.append(e);
                    }
                } else b.append(c);
            }
            return b.toString();
        }

        private String number(int[] p) {
            int st = p[0];
            while (p[0] < s.length() && "-+.0123456789eE".indexOf(s.charAt(p[0])) >= 0) p[0]++;
            // 无法识别的字符（裸词 / 中文逗号等）不能返回 "" 且不推进，否则上层循环死循环
            if (p[0] == st) throw new IllegalArgumentException("无法识别的值，位置 " + p[0]);
            return s.substring(st, p[0]);
        }

        private void skip(int[] p) {
            while (p[0] < s.length() && Character.isWhitespace(s.charAt(p[0]))) p[0]++;
        }
    }
}
