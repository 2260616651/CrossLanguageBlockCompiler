package clbc.blocks;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import clbc.util.CLBC_Util;

/**
 * JDK 库语言文件**按需加载器**。
 *
 * 约定（用户明确要求）：
 *  · 一个库一个语言文件，文件名必须和库名一致：lang/jdk-&lt;库名&gt;.json；
 *  · 只有当对应扩展被启用、且真的要展示该库积木时才加载；
 *  · 加载结果按库名缓存，重复调用零磁盘 IO；
 *  · 文件缺失 / 解析失败一律降级为空，绝不让工具箱崩掉。
 *
 * 数据格式沿用现有 jdk-*.json：{"library":"包名","classes":[{"fqn","chinese",
 *  "methods":[{"name","chinese",...}]}]}。
 */
public final class CLBC_LangFiles {

    private CLBC_LangFiles() {}

    /** 一个已加载库的类/方法中文数据 */
    public static final class LibData {
        public final String library;
        public final String chineseName;
        public final Map<String, String> classCn = new LinkedHashMap<>();   // fqn → 中文
        public final Map<String, String> methodCn = new LinkedHashMap<>();  // 方法名 → 中文（跨类同名以首次为准）

        LibData(String library, String chineseName) {
            this.library = library; this.chineseName = chineseName;
        }

        public int classCount() { return classCn.size(); }
        public int methodCount() { return methodCn.size(); }
    }

    /** 库名 → 已加载数据（null 值表示尝试过但文件不存在，避免反复读盘） */
    private static final Map<String, LibData> CACHE = new LinkedHashMap<>();
    private static int loadCount = 0;

    /** 运行时识别的库名清单：从 lang/ 目录扫描 jdk-*.json，文件名严格 = 库名。 */
    public static List<String> availableLibraries() {
        List<String> out = new ArrayList<>();
        Path dir = Paths.get("lang");
        if (!Files.isDirectory(dir)) return out;
        try (java.util.stream.Stream<Path> s = Files.list(dir)) {
            s.filter(p -> p.getFileName().toString().startsWith("jdk-")
                    && p.getFileName().toString().endsWith(".json"))
             .map(p -> p.getFileName().toString())
             .forEach(n -> {
                 String lib = n.substring(4, n.length() - 5);
                 if (!lib.equals("versions")) out.add(lib);   // versions.json 是元数据，不是库
             });
        } catch (IOException ignored) { /* 目录读不了就返回空 */ }
        Collections.sort(out);
        return out;
    }

    /** 按需加载一个库的语言文件。库名 → lang/jdk-&lt;库名&gt;.json。线程安全，命中缓存零 IO。 */
    public static synchronized LibData load(String library) {
        if (library == null || library.isEmpty()) return null;
        if (CACHE.containsKey(library)) return CACHE.get(library);
        Path p = Paths.get("lang", "jdk-" + library + ".json");
        if (!Files.isRegularFile(p)) {
            CACHE.put(library, null);   // 记下"不存在"，下次直接返回
            CLBC_Util.logFine("[LangFiles] 库语言文件不存在: " + p);
            return null;
        }
        LibData data = null;
        try {
            String s = new String(Files.readAllBytes(p), StandardCharsets.UTF_8).trim();
            if (!s.isEmpty()) data = parse(s);
        } catch (Throwable t) {
            CLBC_Util.log("[LangFiles] 解析失败 " + p + ": " + t.getClass().getSimpleName());
        }
        CACHE.put(library, data);
        if (data != null) loadCount++;
        return data;
    }

    @SuppressWarnings("unchecked")
    private static LibData parse(String json) {
        Map<String, Object> root = parseTop(json);
        String lib = str(root.get("library"), "");
        String cn = str(root.get("libraryChinese"), lib);
        LibData d = new LibData(lib, cn);
        Object classes = root.get("classes");
        if (!(classes instanceof List)) return d;
        for (Object co : (List<Object>) classes) {
            if (!(co instanceof Map)) continue;
            Map<String, Object> cm = (Map<String, Object>) co;
            String fqn = str(cm.get("fqn"), "");
            String fqnCn = str(cm.get("chinese"), "");
            if (!fqn.isEmpty() && !fqnCn.isEmpty()) d.classCn.put(fqn, fqnCn);
            Object methods = cm.get("methods");
            if (!(methods instanceof List)) continue;
            for (Object mo : (List<Object>) methods) {
                if (!(mo instanceof Map)) continue;
                Map<String, Object> mm = (Map<String, Object>) mo;
                String mn = str(mm.get("name"), "");
                String mc = str(mm.get("chinese"), "");
                if (!mn.isEmpty() && !mc.isEmpty()) d.methodCn.putIfAbsent(mn, mc);
            }
        }
        return d;
    }

    /** 极简 JSON 顶层解析（只取 library / libraryChinese / classes），零外部库。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseTop(String s) {
        Map<String, Object> m = CLBC_Lang.parseTop(s);
        return m == null ? new LinkedHashMap<>() : m;
    }

    private static String str(Object o, String def) {
        return o == null ? def : o.toString();
    }

    /** 已实际加载（命中缓存）的库数据快照；未加载的库不在这里，保证"按需"不被查方法名破坏。 */
    public static synchronized List<LibData> loaded() {
        List<LibData> out = new ArrayList<>();
        for (LibData d : CACHE.values()) if (d != null) out.add(d);
        return out;
    }

    /** 诊断 / 日志：实际触发磁盘加载的库文件数（验证"按需"而非"全量"）。 */
    public static int loadedFileCount() { return loadCount; }

    /** 清空缓存（诊断用） */
    public static synchronized void clearCache() { CACHE.clear(); loadCount = 0; }
}
