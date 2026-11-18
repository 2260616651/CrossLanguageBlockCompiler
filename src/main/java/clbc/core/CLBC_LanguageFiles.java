package clbc.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import clbc.blocks.CLBC_BlockDefinition;
import clbc.blocks.CLBC_BlockLibrary;
import clbc.blocks.CLBC_CoreSyntax;
import clbc.util.CLBC_Util;

/**
 * ⑦ 语言文件（编程语言 ↔ 积木语言 对应表）本地化管理。
 *
 * 设计：每种目标语言一份 lang/&lt;id&gt;.json。Java 的 {@code lang/java.json} 由本类在「缺失时自动生成」，
 * 内容与运行时识别集（{@link CLBC_BlockLibrary}）完全一致——它是识别集的序列化镜像，
 * 即「程序启动后根据环境列出的代码与积木文本语言的对应表」：
 *   · libraries[]：每个标准库类的 fqn / 显示名 / 分类 / 介绍 / 方法表（方法含返回类型、参数、积木文本、安全标记、介绍）。
 *   · libraryNames / libraryAliases / unsafe：供调用块着色与黑名单。
 * 用户手动编辑 java.json 仅影响展示（介绍 / 分类），识别仍以 {@link CLBC_BlockLibrary} 反射结果为准，二者同源不漂移。
 *
 * 生成/导入结果仅记日志（错误定位只许用日志）。
 */
public final class CLBC_LanguageFiles {

    /** 已知语言 id（与 {@link clbc.core.CLBC_Environment.Lang} 对应）；顺序即优先级 */
    private static final List<String> KNOWN = Arrays.asList("java", "python", "cpp");

    /** 默认语言文件模板（Python / C++ 等未来语言用；Java 走完整自动生成） */
    private static final String TEMPLATE =
            "{\n"
          + "  \"defaultLocale\": \"zh_CN\",\n"
          + "  \"locales\": {\n"
          + "    \"zh_CN\": {\n"
          + "      \"blocks\": {\n"
          + "        \"unit.class\":      { \"text\": \"类 %0\", \"category\": \"结构\" },\n"
          + "        \"unit.method\":     { \"text\": \"%0 %1 %2( %3 )\", \"category\": \"结构\" },\n"
          + "        \"control.if\":      { \"text\": \"如果 %0 那么\", \"category\": \"结构\" },\n"
          + "        \"control.else\":    { \"text\": \"否则\", \"category\": \"结构\" },\n"
          + "        \"control.while\":   { \"text\": \"当 %0\", \"category\": \"结构\" },\n"
          + "        \"control.for\":     { \"text\": \"重复 直到 %0\", \"category\": \"结构\" },\n"
          + "        \"control.forEach\": { \"text\": \"对于 %0 在 %1 中\", \"category\": \"结构\" },\n"
          + "        \"control.repeat\":  { \"text\": \"重复 %0 次\", \"category\": \"结构\" },\n"
          + "        \"data.var\":        { \"text\": \"%0 %1 %2\", \"category\": \"数据\" },\n"
          + "        \"data.assign\":     { \"text\": \"将 %0 设为 %1\", \"category\": \"数据\" },\n"
          + "        \"data.return\":     { \"text\": \"返回 %0\", \"category\": \"数据\" },\n"
          + "        \"ops.call\":        { \"text\": \"调用 %0\", \"category\": \"系统\" },\n"
          + "        \"expr.number\":     { \"text\": \"%0\", \"category\": \"数学\" },\n"
          + "        \"expr.string\":     { \"text\": \"%0\", \"category\": \"字符串\" },\n"
          + "        \"expr.bool\":       { \"text\": \"%0\", \"category\": \"运算\" },\n"
          + "        \"raw.code\":        { \"text\": \"%0\", \"category\": \"结构\" }\n"
          + "      }\n"
          + "    }\n"
          + "  },\n"
          + "  \"libraryAliases\": { \"java.lang.Math\": \"数学\", \"java.lang.System\": \"系统\", \"java.lang.String\": \"字符串\" },\n"
          + "  \"categories\": {\n"
          + "    \"结构\": \"#FFAB19\", \"数据\": \"#FF661A\", \"系统\": \"#FF8C1A\",\n"
          + "    \"数学\": \"#59C059\", \"字符串\": \"#9966FF\", \"运算\": \"#5CB1D6\"\n"
          + "  }\n"
          + "}\n";

    private CLBC_LanguageFiles() {}

    /** 确保各语言文件存在（缺失则生成）：Java 生成完整库对应表，其余生成默认模板。返回成功确保的文件数 */
    public static int ensureAll(Path langDir) {
        int ok = 0;
        try {
            Files.createDirectories(langDir);
        } catch (IOException e) {
            CLBC_Util.log("!!!!语言文件目录创建失败: " + e.getMessage());
            return 0;
        }
        for (String id : KNOWN) {
            Path f = langDir.resolve(id + ".json");
            if (Files.exists(f)) {
                CLBC_Util.log("[LanguageFile] 导入语言文件: " + f);
                ok++;
            } else {
                try {
                    if ("java".equals(id)) {
                        generateJava(f);   // 完整 Java 标准库 ↔ 积木 对应表
                    } else {
                        Files.writeString(f, TEMPLATE, StandardCharsets.UTF_8);
                    }
                    CLBC_Util.log("[LanguageFile] 生成默认语言文件（编程语言↔积木）: " + f);
                    ok++;
                } catch (IOException e) {
                    CLBC_Util.log("!!!!语言文件生成失败: " + id + " -> " + e.getMessage());
                }
            }
        }
        ensureCore(langDir, false);
        return ok;
    }

    /**
     * ⑦ 独立的「纯语法」语言文件：与 java.json（JDK 类库方法表）严格分离，
     * 收录声明 / 字面量 / 数学运算 / 比较 / 逻辑 / 位运算 / 数组 / 控制流等
     * 不 import 任何类库也能用的 Java 语法。
     */
    private static void ensureCore(Path langDir, boolean overwrite) {
        Path f = langDir.resolve("java-core.json");
        try {
            if (overwrite || !Files.exists(f)) {
                Files.writeString(f, CLBC_CoreSyntax.toLanguageJson(), StandardCharsets.UTF_8);
                CLBC_Util.log("[LanguageFile] " + (overwrite ? "重新生成" : "生成")
                        + "纯语法积木对应表（不依赖任何库）: " + f);
            } else {
                CLBC_Util.log("[LanguageFile] 导入纯语法积木对应表: " + f);
            }
        } catch (IOException e) {
            CLBC_Util.log("!!!!纯语法语言文件写入失败: " + e.getMessage());
        }
    }

    /** 强制重新生成全部语言文件（覆盖已有；用于环境变更后刷新库对应表） */
    public static int regenerateAll(Path langDir) {
        int ok = 0;
        try {
            Files.createDirectories(langDir);
        } catch (IOException e) {
            CLBC_Util.log("!!!!语言文件目录创建失败: " + e.getMessage());
            return 0;
        }
        for (String id : KNOWN) {
            Path f = langDir.resolve(id + ".json");
            try {
                if ("java".equals(id)) generateJava(f);
                else Files.writeString(f, TEMPLATE, StandardCharsets.UTF_8);
                CLBC_Util.log("[LanguageFile] 重新生成语言文件: " + f);
                ok++;
            } catch (IOException e) {
                CLBC_Util.log("!!!!语言文件生成失败: " + id + " -> " + e.getMessage());
            }
        }
        ensureCore(langDir, true);
        return ok;
    }

    // ===================== Java 完整库对应表生成 =====================

    /** 读取运行时识别集（CLBC_BlockLibrary），生成完整的 lang/java.json。
     *  要求 CLBC_BlockLibrary 已完成静态扫描（首次引用本方法即触发）。 */
    public static void generateJava(Path f) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"_comment\": \"CLBC 自动生成的 Java 标准库 ↔ 积木 对应表。程序启动后根据检测到的 JDK 环境生成；libraries[] 与运行时识别集(CLBC_BlockLibrary)完全一致。改此文件仅影响展示，不影响识别（识别以 CLBC_BlockLibrary 反射结果为准）。\",\n");
        sb.append("  \"language\": \"java\",\n");

        // libraryNames: fqn -> 简单类名
        sb.append("  \"libraryNames\": {\n");
        boolean first = true;
        for (String fqn : CLBC_BlockLibrary.library.keySet()) {
            if (!first) sb.append(",\n");
            first = false;
            sb.append("    ").append(jstr(fqn)).append(": ").append(jstr(simpleName(fqn)));
        }
        sb.append("\n  },\n");

        // libraryAliases: fqn -> 分类（取该类首个成员的分类）
        sb.append("  \"libraryAliases\": {\n");
        first = true;
        for (Map.Entry<String, List<CLBC_BlockDefinition>> e : CLBC_BlockLibrary.library.entrySet()) {
            String cat = e.getValue().isEmpty() ? "系统" : e.getValue().get(0).category;
            if (!first) sb.append(",\n");
            first = false;
            sb.append("    ").append(jstr(e.getKey())).append(": ").append(jstr(cat));
        }
        sb.append("\n  },\n");

        // unsafe 段（留给用户追加自定义黑名单；内置黑名单在 CLBC_BlockLibrary）
        sb.append("  \"unsafe\": { \"classes\": {}, \"methods\": {} },\n");

        // libraries[]：每个标准库类的完整方法表
        sb.append("  \"libraries\": [\n");
        boolean firstLib = true;
        for (Map.Entry<String, List<CLBC_BlockDefinition>> e : CLBC_BlockLibrary.library.entrySet()) {
            String fqn = e.getKey();
            List<CLBC_BlockDefinition> defs = e.getValue();
            if (defs.isEmpty()) continue;
            String cat = defs.get(0).category;
            if (!firstLib) sb.append(",\n");
            firstLib = false;
            sb.append("    {\n");
            sb.append("      \"fqn\": ").append(jstr(fqn)).append(",\n");
            sb.append("      \"displayName\": ").append(jstr(simpleName(fqn))).append(",\n");
            sb.append("      \"category\": ").append(jstr(cat)).append(",\n");
            sb.append("      \"description\": ").append(jstr(classDescription(fqn, cat))).append(",\n");
            sb.append("      \"methods\": [\n");
            boolean firstM = true;
            for (CLBC_BlockDefinition d : defs) {
                if (!firstM) sb.append(",\n");
                firstM = false;
                sb.append("        {\n");
                sb.append("          \"name\": ").append(jstr(d.methodName)).append(",\n");
                sb.append("          \"isStatic\": ").append(d.isStatic).append(",\n");
                sb.append("          \"isField\": ").append(d.isField).append(",\n");
                sb.append("          \"returnType\": ").append(jstr(d.returnType)).append(",\n");
                sb.append("          \"params\": [").append(paramsJson(d.params)).append("],\n");
                sb.append("          \"blockText\": ").append(jstr(blockText(d))).append(",\n");
                sb.append("          \"category\": ").append(jstr(d.category)).append(",\n");
                sb.append("          \"safe\": ").append(CLBC_BlockLibrary.isSafe(d.ownerFqn, d.methodName)).append(",\n");
                sb.append("          \"description\": ").append(jstr(methodDescription(d))).append("\n");
                sb.append("        }");
            }
            sb.append("\n      ]\n");
            sb.append("    }");
        }
        sb.append("\n  ]\n");
        sb.append("}\n");

        Files.writeString(f, sb.toString(), StandardCharsets.UTF_8);
        CLBC_Util.log("[LanguageFile] 已生成 Java 标准库对应表: " + f
                + "（类 " + CLBC_BlockLibrary.library.size() + " 个，方法 "
                + CLBC_BlockLibrary.library.values().stream().mapToInt(List::size).sum() + " 条）");
    }

    // ===================== 文本工具 =====================

    private static String simpleName(String fqn) {
        int i = fqn.lastIndexOf('.');
        return i >= 0 ? fqn.substring(i + 1) : fqn;
    }

    private static String packageOf(String fqn) {
        int i = fqn.lastIndexOf('.');
        return i >= 0 ? fqn.substring(0, i) : "";
    }

    private static String paramsJson(List<String> params) {
        if (params == null || params.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(jstr(params.get(i)));
        }
        return sb.toString();
    }

    private static String blockText(CLBC_BlockDefinition d) {
        String owner = simpleName(d.ownerFqn);
        if (d.isField) return owner + "." + d.methodName;
        if (d.methodName.equals(owner)) return owner + " · new";   // 构造器
        return owner + " · " + d.methodName;
    }

    private static String classDescription(String fqn, String cat) {
        String pkg = packageOf(fqn);
        return "Java 标准库类 " + simpleName(fqn) + "（包 " + (pkg.isEmpty() ? "默认包" : pkg)
                + "）。分类：" + cat + "。可用方法见下方方法表。";
    }

    private static String methodDescription(CLBC_BlockDefinition d) {
        StringBuilder sb = new StringBuilder();
        sb.append(simpleName(d.ownerFqn)).append(".").append(d.signature);
        if (d.returnType != null && !d.returnType.isEmpty()) sb.append(" 返回 ").append(d.returnType);
        sb.append("（").append(d.category);
        if (d.isStatic) sb.append("，静态");
        if (d.isField) sb.append("，字段");
        if (!CLBC_BlockLibrary.isSafe(d.ownerFqn, d.methodName)) sb.append("，已被安全策略禁用");
        sb.append("）");
        return sb.toString();
    }

    /** JSON 字符串转义（键/值统一走这里，保证生成的 java.json 合法） */
    private static String jstr(String s) {
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
        sb.append("\"");
        return sb.toString();
    }

    public static List<String> knownLanguages() { return new ArrayList<>(KNOWN); }
}
