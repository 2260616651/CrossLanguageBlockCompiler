package clbc.multilang;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 语言规格：把四种语言的差异收敛成一张数据表，Parser / Emitter / 积木文案都从它读。
 * 新增语言只需再写一个 LangSpec 实例，不必改 UAST / 桥接 / 渲染。
 */
public final class LangSpec {

    public enum Family { C_STYLE, PYTHON }   // 花括号族 / 缩进族

    public final String id;                  // java / python / cpp / csharp
    public final String displayName;
    public final String[] extensions;        // .java / .py / .cpp ...
    public final Family family;
    public final String lineComment;         // // 或 #
    public final String blockCommentOpen;    // /* 或 """；空串=不支持
    public final String blockCommentClose;

    /** 换行风格：缩进族用 \n 断句，花括号族用 ; */
    public final boolean semicolonStatements;
    public final String indentUnit;

    /** 关键字映射：语义 → 该语言关键字。语义键见 K_* 常量 */
    public final java.util.Map<String, String> kw = new java.util.LinkedHashMap<>();
    /** 该语言所有关键字（词法/解析判词用） */
    public final Set<String> keywords = new HashSet<>();
    /** 内置类型名（用于变量声明识别） */
    public final Set<String> builtinTypes = new HashSet<>();
    /** 包/模块/命名空间声明关键字：Java package / Python （无，用文件）/ C++ namespace / C# namespace */
    public final String namespaceKeyword;
    /** 依赖声明关键字：Java import / Python import/from / C++ #include / C# using */
    public final String importKeyword;
    /** 类声明关键字（C++ struct 另记） */
    public final String[] typeKeywords;

    public static final String K_IF = "if", K_ELSE = "else", K_WHILE = "while", K_FOR = "for";
    public static final String K_RETURN = "return", K_CLASS = "class", K_TRUE = "true";
    public static final String K_FALSE = "false", K_NULL = "null", K_NEW = "new";
    public static final String K_TRY = "try", K_CATCH = "catch", K_FINALLY = "finally";
    public static final String K_THROW = "throw", K_BREAK = "break", K_CONTINUE = "continue";
    public static final String K_DEF = "def", K_IN = "in", K_VOID = "void";

    public LangSpec(String id, String displayName, String[] extensions, Family family,
                    String lineComment, String blockCommentOpen, String blockCommentClose,
                    boolean semicolonStatements, String indentUnit,
                    String namespaceKeyword, String importKeyword, String[] typeKeywords) {
        this.id = id;
        this.displayName = displayName;
        this.extensions = extensions;
        this.family = family;
        this.lineComment = lineComment;
        this.blockCommentOpen = blockCommentOpen == null ? "" : blockCommentOpen;
        this.blockCommentClose = blockCommentClose == null ? "" : blockCommentClose;
        this.semicolonStatements = semicolonStatements;
        this.indentUnit = indentUnit;
        this.namespaceKeyword = namespaceKeyword == null ? "" : namespaceKeyword;
        this.importKeyword = importKeyword == null ? "" : importKeyword;
        this.typeKeywords = typeKeywords;
    }

    public LangSpec kw(String semantic, String word) {
        kw.put(semantic, word);
        keywords.add(word);
        return this;
    }

    public LangSpec types(String... names) { builtinTypes.addAll(Arrays.asList(names)); return this; }

    public String word(String semantic, String dflt) {
        String w = kw.get(semantic);
        return w == null || w.isEmpty() ? dflt : w;
    }

    public boolean isTypeWord(String s) {
        return builtinTypes.contains(s) || Arrays.asList(typeKeywords).contains(s);
    }

    public boolean supportsBlockComment() { return !blockCommentOpen.isEmpty(); }

    /** -------------------------------- 四语言规格 -------------------------------- */

    public static LangSpec java() {
        LangSpec s = new LangSpec("java", "Java", new String[]{".java"}, Family.C_STYLE,
                "//", "/*", "*/", true, "    ", "package", "import",
                new String[]{"class", "interface", "enum", "record"})
                .kw(K_IF, "if").kw(K_ELSE, "else").kw(K_WHILE, "while").kw(K_FOR, "for")
                .kw(K_RETURN, "return").kw(K_CLASS, "class").kw(K_TRUE, "true").kw(K_FALSE, "false")
                .kw(K_NULL, "null").kw(K_NEW, "new").kw(K_TRY, "try").kw(K_CATCH, "catch")
                .kw(K_FINALLY, "finally").kw(K_THROW, "throw").kw(K_BREAK, "break")
                .kw(K_CONTINUE, "continue").kw(K_VOID, "void")
                .types("int", "long", "double", "float", "boolean", "char", "byte", "short", "String", "var");
        return s;
    }

    public static LangSpec python() {
        LangSpec s = new LangSpec("python", "Python", new String[]{".py"}, Family.PYTHON,
                "#", "\"\"\"", "\"\"\"", false, "    ", "", "import",
                new String[]{"class"})
                .kw(K_IF, "if").kw(K_ELSE, "else").kw(K_WHILE, "while").kw(K_FOR, "for")
                .kw(K_RETURN, "return").kw(K_CLASS, "class").kw(K_TRUE, "True").kw(K_FALSE, "False")
                .kw(K_NULL, "None").kw(K_DEF, "def").kw(K_IN, "in").kw(K_TRY, "try")
                .kw(K_CATCH, "except").kw(K_FINALLY, "finally").kw(K_THROW, "raise")
                .kw(K_BREAK, "break").kw(K_CONTINUE, "continue")
                .types("int", "str", "float", "bool", "list", "dict", "tuple", "set", "bytes");
        // Python 没有 else-if 关键字，用 elif；单独登记但不列入主 kw 映射冲突
        s.kw.put("elif", "elif");
        s.keywords.add("elif");
        return s;
    }

    public static LangSpec cpp() {
        LangSpec s = new LangSpec("cpp", "C++", new String[]{".cpp", ".cc", ".cxx", ".h", ".hpp"}, Family.C_STYLE,
                "//", "/*", "*/", true, "    ", "namespace", "#include",
                new String[]{"class", "struct", "enum"})
                .kw(K_IF, "if").kw(K_ELSE, "else").kw(K_WHILE, "while").kw(K_FOR, "for")
                .kw(K_RETURN, "return").kw(K_CLASS, "class").kw(K_TRUE, "true").kw(K_FALSE, "false")
                .kw(K_NULL, "nullptr").kw(K_NEW, "new").kw(K_TRY, "try").kw(K_CATCH, "catch")
                .kw(K_FINALLY, "__finally").kw(K_THROW, "throw").kw(K_BREAK, "break")
                .kw(K_CONTINUE, "continue").kw(K_VOID, "void")
                .types("int", "long", "double", "float", "bool", "char", "unsigned", "auto", "std::string");
        return s;
    }

    public static LangSpec csharp() {
        LangSpec s = new LangSpec("csharp", "C#", new String[]{".cs"}, Family.C_STYLE,
                "//", "/*", "*/", true, "    ", "namespace", "using",
                new String[]{"class", "struct", "interface", "enum", "record"})
                .kw(K_IF, "if").kw(K_ELSE, "else").kw(K_WHILE, "while").kw(K_FOR, "for")
                .kw(K_RETURN, "return").kw(K_CLASS, "class").kw(K_TRUE, "true").kw(K_FALSE, "false")
                .kw(K_NULL, "null").kw(K_NEW, "new").kw(K_TRY, "try").kw(K_CATCH, "catch")
                .kw(K_FINALLY, "finally").kw(K_THROW, "throw").kw(K_BREAK, "break")
                .kw(K_CONTINUE, "continue").kw(K_VOID, "void")
                .types("int", "long", "double", "float", "bool", "char", "byte", "string", "object", "var", "decimal");
        return s;
    }

    public static LangSpec byId(String id) {
        if (id == null) return java();
        return switch (id.toLowerCase()) {
            case "python", "py" -> python();
            case "cpp", "c++", "cc" -> cpp();
            case "csharp", "cs", "c#" -> csharp();
            default -> java();
        };
    }

    public static LangSpec byExtension(String fileName) {
        if (fileName == null) return java();
        String n = fileName.toLowerCase();
        if (n.endsWith(".py")) return python();
        if (n.endsWith(".cpp") || n.endsWith(".cc") || n.endsWith(".cxx")
                || n.endsWith(".hpp") || n.endsWith(".h")) return cpp();
        if (n.endsWith(".cs")) return csharp();
        return java();
    }
}
