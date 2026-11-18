package clbc.blocks.jparser;

import java.util.ArrayList;
import java.util.List;

/**
 * 自研 Java 解析器的 AST 节点（纯 JDK，零第三方依赖）。
 * 设计要点：每个节点仅保留 CLBC 代码→积木所需的"结构化字段" +
 * 通过源码区间回取的"原文文本"，从而比 javaparser 的 toString 更保真地还原代码。
 */
public final class JAst {

    /** 编译单元：包名 / 导入 / 顶层类型 */
    public static final class JRoot {
        public String pkg = "";
        public final List<JImport> imports = new ArrayList<>();
        public final List<JType> types = new ArrayList<>();
        /** 顶层"不在声明内"的原文（如顶层杂项语句），导入时生成 orphan.code 积木，绝不丢信息 */
        public final List<String> orphans = new ArrayList<>();
    }

    public static final class JImport {
        public String name;      // 全限定名（通配时为包名 + ".*"）
        public boolean wildcard;
    }

    public static final class JField {
        public String type;      // 类型原文（如 int / List<String>）
        public String name;
        public String init;      // 初始化表达式原文（可为 null）
    }

    public static final class JMethod {
        public String modifiers;  // 修饰符原文（如 "public static"）
        public String retType;    // 返回类型原文
        public String name;
        public String params;     // 形参原文（如 "int a, int b"）
        public boolean hasBody;
        public final List<JStmt> body = new ArrayList<>();
    }

    public static final class JType {
        public String name;
        /** 收口4：顶层类型关键字（class／interface／enum），由 JParser.parseType 记录；默认 class 兼容旧路径 */
        public String kind = "class";
        public final List<JField> fields = new ArrayList<>();
        public final List<JMethod> methods = new ArrayList<>();
    }

    public static final class JStmt {
        public enum K { BLOCK, IF, WHILE, DO, FOR, FOREACH, RETURN, EXPR, RAW, COMMENT, TRY }
        public K kind;
        public String comment;       // COMMENT 语句：// 正文（不含 //），或 /* */ 完整原文
        public String cond;          // if / while / do / for 的条件原文
        public String init;          // for 的初始化
        public String update;        // for 的步进
        public String var;           // foreach 的循环变量名
        public String type;          // foreach 的循环变量类型原文（保留信息，供后续类型系统）
        public String iterable;      // foreach 的迭代对象原文
        public String retExpr;       // return 的表达式原文
        public String raw;           // RAW 语句原文
        public JExpr expr;           // EXPR 语句承载的表达式
        public final List<JStmt> thenBody = new ArrayList<>();  // if 的 then
        public final List<JStmt> elseBody = new ArrayList<>();  // if 的 else
        public final List<JStmt> body = new ArrayList<>();       // block / 循环体 / try 体
        /** #已知限制实修：COMMENT 承载块注释时为 true，回写必须保留块形式而非转成行注释 */
        public boolean blockComment = false;
        /** try-with-resources 括号内原文（普通 try 为空串） */
        public String tryResources = "";
        /** catch 子句（按源码顺序），每个含参数原文 + 体 */
        public final List<JCatch> catches = new ArrayList<>();
        /** finally 体；hasFinally 单独记录，因为 finally {} 空体也合法 */
        public final List<JStmt> finallyBody = new ArrayList<>();
        public boolean hasFinally = false;
    }

    /** 一个 catch 子句：括号内参数原文（如 "IOException e" / "A | B e"）+ 块体 */
    public static final class JCatch {
        public String param = "";
        public final List<JStmt> body = new ArrayList<>();
    }

    public static final class JExpr {
        public enum K { CALL, VARDECL, ASSIGN, OTHER }
        public K kind;
        public String scope;     // CALL：接收者原文（可为 ""）
        public String name;      // CALL：方法名
        public final List<String> args = new ArrayList<>(); // CALL：各参数原文
        public String type;      // VARDECL：类型原文
        public String varName;   // VARDECL：变量名
        public String init;      // VARDECL：初始化原文
        public String target;    // ASSIGN：左值原文
        public String value;     // ASSIGN：右值原文
        public String raw;       // OTHER / 任意节点的原文
    }

    private JAst() {}
}
