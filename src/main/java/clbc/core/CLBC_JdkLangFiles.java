package clbc.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import clbc.blocks.CLBC_BlockDefinition;
import clbc.blocks.CLBC_BlockLibrary;
import clbc.util.CLBC_Util;

/**
 * JDK 自带库的语言文件生成器：一个库（包）一个 json，java1 ~ java27 共 27 个版本共用同一份文件。
 *
 * 规则（对应用户口径）：
 *  · 「一个库一个文件」：按包拆分，文件名 jdk-&lt;包名&gt;.json，例如 jdk-java.lang.json。
 *  · 「必须包括 java1 到 java27」：每个文件都带 javaVersions [1..27]，
 *    且每个类 / 方法都带 since(起始版本) 与 until(移除版本)；另有 jdk-versions.json 版本总表。
 *  · 「27 个版本里的相同库共用一个语言文件」：绝不按版本拆文件，版本差异只体现在 since/until 字段。
 *  · 「方法改名两个都写」：{@link CLBC_JdkHistory#RENAMES} 里的旧名与新名各生成一条独立方法条目，
 *    chinese 字段完全相同，并互记 aliases。
 *
 * 只在文件缺失时生成（已存在一律不覆盖，用户可直接改）；生成结果只记日志。
 */
public final class CLBC_JdkLangFiles {

    public static final String PREFIX = "jdk-";
    public static final String VERSIONS_FILE = "jdk-versions.json";

    private CLBC_JdkLangFiles() {}

    /** 确保版本总表 + 每个库一份语言文件存在；返回新生成的文件数 */
    public static int ensureAll(Path langDir) {
        int made = 0;
        try {
            Files.createDirectories(langDir);
        } catch (IOException e) {
            CLBC_Util.log("!!!![JdkLang] 语言文件夹创建失败: " + e.getMessage());
            return 0;
        }

        Path verFile = langDir.resolve(VERSIONS_FILE);
        if (!Files.exists(verFile)) {
            if (writeIfAbsent(verFile, buildVersionsJson())) made++;
        } else {
            CLBC_Util.log("[JdkLang] 已有版本总表: " + verFile.getFileName());
        }

        Map<String, List<String>> byPkg = groupByPackage();
        // 改名表里出现、但当前 JDK 反射扫不到的包也要有文件（如 java.awt）
        for (String p : CLBC_JdkHistory.extraPackages()) byPkg.putIfAbsent(p, new ArrayList<>());
        // 已知 JDK 库全集也补齐：一个库一个文件，不因当前 JDK 反射不到就缺项
        for (String p : CLBC_JdkHistory.knownPackages()) byPkg.putIfAbsent(p, new ArrayList<>());

        for (Map.Entry<String, List<String>> e : byPkg.entrySet()) {
            String pkg = e.getKey();
            Path f = langDir.resolve(PREFIX + pkg + ".json");
            if (Files.exists(f)) {
                CLBC_Util.log("[JdkLang] 已有库语言文件: " + f.getFileName());
                continue;
            }
            if (writeIfAbsent(f, buildLibraryJson(pkg, e.getValue()))) made++;
        }
        CLBC_Util.log("[JdkLang] 库语言文件就绪：包 " + byPkg.size() + " 个，本次新生成 " + made
                + " 个，版本覆盖 java1~java27");
        return made;
    }

    private static boolean writeIfAbsent(Path f, String json) {
        if (Files.exists(f)) return false;
        try {
            Files.writeString(f, json, StandardCharsets.UTF_8);
            CLBC_Util.log("[JdkLang] 生成: " + f.getFileName() + " (" + json.length() + " 字符)");
            return true;
        } catch (IOException ex) {
            CLBC_Util.log("!!!![JdkLang] 生成失败 " + f.getFileName() + ": " + ex.getMessage());
            return false;
        }
    }

    /** 按包分组反射识别集 */
    private static Map<String, List<String>> groupByPackage() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (String fqn : CLBC_BlockLibrary.library.keySet()) {
            int i = fqn.lastIndexOf('.');
            String pkg = i >= 0 ? fqn.substring(0, i) : "";
            if (pkg.isEmpty()) pkg = "default";
            out.computeIfAbsent(pkg, k -> new ArrayList<>()).add(fqn);
        }
        return out;
    }

    // ===================== 版本总表 =====================

    private static String buildVersionsJson() {
        StringBuilder s = new StringBuilder();
        s.append("{\n");
        s.append("  \"_comment\": \"CLBC 内置 JDK 版本总表：java1 ~ java27 共 27 个版本。序号与真实 JDK 版本号的映射见 jdk 字段。每个库的具体语言文件为 jdk-<包名>.json，27 个版本共用同一份，差异由 since/until 表达。\",\n");
        s.append("  \"minVersion\": 1,\n");
        s.append("  \"maxVersion\": 27,\n");
        s.append("  \"versionCount\": 27,\n");
        s.append("  \"javaVersions\": [");
        int[] vs = CLBC_JdkHistory.allVersions();
        for (int i = 0; i < vs.length; i++) { if (i > 0) s.append(", "); s.append(vs[i]); }
        s.append("],\n");
        s.append("  \"versions\": [\n");
        for (int i = 0; i < CLBC_JdkHistory.VERSIONS.length; i++) {
            CLBC_JdkHistory.Ver v = CLBC_JdkHistory.VERSIONS[i];
            if (i > 0) s.append(",\n");
            s.append("    {\n");
            s.append("      \"v\": ").append(v.v).append(",\n");
            s.append("      \"jdk\": ").append(jstr(v.jdk)).append(",\n");
            s.append("      \"year\": ").append(v.year).append(",\n");
            s.append("      \"note\": ").append(jstr(v.note)).append(",\n");
            s.append("      \"added\": ").append(strArray(v.added)).append(",\n");
            s.append("      \"removed\": ").append(strArray(v.removed)).append("\n");
            s.append("    }");
        }
        s.append("\n  ],\n");
        s.append("  \"_note\": \"added/removed 为该版本代表类或能力；实际识别以运行时 JDK 反射结果为准，本表保证 java1~java27 每个版本都有可用类记录。\"\n");
        s.append("}\n");
        return s.toString();
    }

    private static String strArray(String[] a) {
        StringBuilder s = new StringBuilder("[");
        for (int i = 0; i < a.length; i++) { if (i > 0) s.append(", "); s.append(jstr(a[i])); }
        return s.append("]").toString();
    }

    // ===================== 单库语言文件 =====================

    private static String buildLibraryJson(String pkg, List<String> fqns) {
        StringBuilder s = new StringBuilder();
        s.append("{\n");
        s.append("  \"_comment\": \"CLBC 自动生成的 JDK 库语言文件。一个库一个文件；java1~java27 共 27 个版本共用本文件，不按版本拆分。since=起始版本，until=移除版本(27 表示仍可用)。方法改名时旧名与新名各写一条，chinese 相同。\",\n");
        s.append("  \"library\": ").append(jstr(pkg)).append(",\n");
        s.append("  \"libraryChinese\": ").append(jstr(CLBC_JdkHistory.chinesePackage(pkg))).append(",\n");
        s.append("  \"minVersion\": 1,\n");
        s.append("  \"maxVersion\": 27,\n");
        s.append("  \"versionRange\": \"java1~java27\",\n");
        s.append("  \"javaVersions\": [");
        int[] vs = CLBC_JdkHistory.allVersions();
        for (int i = 0; i < vs.length; i++) { if (i > 0) s.append(", "); s.append(vs[i]); }
        s.append("],\n");

        // 反射扫到的类 + 改名表里的类 + 版本总表记录的历史类（反射扫不到的老类也留档）
        Set<String> classSet = new LinkedHashSet<>(fqns);
        List<CLBC_JdkHistory.Rename> renames = CLBC_JdkHistory.renamesOf(pkg);
        for (CLBC_JdkHistory.Rename r : renames) classSet.add(r.fqn);
        for (String h : CLBC_JdkHistory.historicalClasses(pkg)) classSet.add(h);

        s.append("  \"classes\": [\n");
        boolean firstClass = true;
        for (String fqn : classSet) {
            if (!firstClass) s.append(",\n");
            firstClass = false;
            String cn = CLBC_JdkHistory.chineseClass(fqn);
            int classSince = CLBC_JdkHistory.sinceOfClass(fqn);
            int classUntil = CLBC_JdkHistory.untilOf(fqn, null);
            s.append("    {\n");
            s.append("      \"fqn\": ").append(jstr(fqn)).append(",\n");
            s.append("      \"chinese\": ").append(jstr(cn)).append(",\n");
            s.append("      \"displayName\": ").append(jstr(simpleName(fqn))).append(",\n");
            s.append("      \"since\": ").append(classSince).append(",\n");
            s.append("      \"until\": ").append(classUntil).append(",\n");
            s.append("      \"methods\": [\n");

            List<CLBC_BlockDefinition> defs = CLBC_BlockLibrary.library.get(fqn);
            List<String> lines = new ArrayList<>();
            Set<String> written = new LinkedHashSet<>();

            // 改名表先行：旧名与新名共用同一个中文，且各占一条独立方法条目。
            // 必须先建表再写反射结果，否则反射里已有的新名条目会用词典中文盖掉改名中文。
            Map<String, RenameEntry> renamed = new LinkedHashMap<>();
            for (CLBC_JdkHistory.Rename r : renames) {
                if (!r.fqn.equals(fqn)) continue;
                renamed.put(r.oldName, new RenameEntry(r.chinese, r.oldSince, r.oldUntil, r.newName));
                renamed.put(r.newName, new RenameEntry(r.chinese, r.newSince,
                        CLBC_JdkHistory.PRESENT, r.oldName));
            }

            if (defs != null) {
                for (CLBC_BlockDefinition d : defs) {
                    String name = d.methodName;
                    if (name == null || name.isEmpty()) continue;
                    if (renamed.containsKey(name)) continue;   // 交给改名表统一写，保证中文一致
                    // 同名重载只写一次（语言文件是名称对应表，不区分签名）
                    if (!written.add(name)) continue;
                    String chinese = methodChinese(cn, name, d.isField, fqn);
                    int since = CLBC_JdkHistory.sinceOfMethod(fqn, name);
                    int until = CLBC_JdkHistory.untilOf(fqn, name);
                    List<String> aliases = aliasList(renames, fqn, name);
                    lines.add(methodJson(name, chinese, since, until, aliases,
                            d.returnType, d.isStatic, d.isField, d.params));
                }
            }

            // 改名双写：两个名字都写出来，chinese 完全相同
            for (Map.Entry<String, RenameEntry> e : renamed.entrySet()) {
                RenameEntry r = e.getValue();
                List<String> al = new ArrayList<>();
                if (r.alias != null && !r.alias.isEmpty() && !r.alias.equals(e.getKey())) al.add(r.alias);
                lines.add(methodJson(e.getKey(), r.chinese, r.since, r.until, al,
                        "", false, false, null));
            }

            for (int i = 0; i < lines.size(); i++) {
                if (i > 0) s.append(",\n");
                s.append(lines.get(i));
            }
            s.append("\n      ]\n");
            s.append("    }");
        }
        s.append("\n  ]\n");
        s.append("}\n");
        return s.toString();
    }

    /** 改名表条目：旧名 / 新名共用同一中文，互记别名 */
    private static final class RenameEntry {
        final String chinese, alias;
        final int since, until;
        RenameEntry(String chinese, int since, int until, String alias) {
            this.chinese = chinese; this.since = since; this.until = until; this.alias = alias;
        }
    }

    private static List<String> aliasList(List<CLBC_JdkHistory.Rename> renames, String fqn, String name) {
        List<String> out = new ArrayList<>();
        for (CLBC_JdkHistory.Rename r : renames) {
            if (!r.fqn.equals(fqn)) continue;
            if (r.oldName.equals(name) && !out.contains(r.newName)) out.add(r.newName);
            if (r.newName.equals(name) && !out.contains(r.oldName)) out.add(r.oldName);
        }
        return out;
    }

    private static String methodJson(String name, String chinese, int since, int until,
                                     List<String> aliases, String returnType,
                                     boolean isStatic, boolean isField, List<String> params) {
        StringBuilder s = new StringBuilder();
        s.append("        {\n");
        s.append("          \"name\": ").append(jstr(name)).append(",\n");
        s.append("          \"chinese\": ").append(jstr(chinese)).append(",\n");
        s.append("          \"blockText\": ").append(jstr(chinese)).append(",\n");
        s.append("          \"since\": ").append(since).append(",\n");
        s.append("          \"until\": ").append(until).append(",\n");
        s.append("          \"aliases\": ").append(strArray(aliases.toArray(new String[0]))).append(",\n");
        s.append("          \"returnType\": ").append(jstr(returnType == null ? "" : returnType)).append(",\n");
        s.append("          \"isStatic\": ").append(isStatic).append(",\n");
        s.append("          \"isField\": ").append(isField).append(",\n");
        s.append("          \"params\": ").append(strArray(params == null ? new String[0] : params.toArray(new String[0]))).append("\n");
        s.append("        }");
        return s.toString();
    }

    private static String methodChinese(String classChinese, String name, boolean isField, String fqn) {
        String simple = simpleName(fqn);
        String base;
        if (name.equals(simple)) base = "新建";
        else if (isField) base = CLBC_JdkHistory.chineseMethod(name);
        else base = CLBC_JdkHistory.chineseMethod(name);
        return classChinese + " · " + base;
    }

    private static String simpleName(String fqn) {
        int i = fqn.lastIndexOf('.');
        return i >= 0 ? fqn.substring(i + 1) : fqn;
    }

    /** JSON 字符串转义（生成的全部文件都合法 UTF-8 JSON） */
    public static String jstr(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append("\"").toString();
    }
}
