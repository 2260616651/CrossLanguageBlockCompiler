package clbc.multilang;

import java.util.ArrayList;
import java.util.List;

/**
 * 语言无关 AST（Universal AST）。
 *
 * 设计原则：
 *  · 只保留四种目标语言（Java / Python / C++ / C#）都有的公共语法骨架；
 *  · 各语言独有写法（import/include/using、缩进 vs 花括号、类型标注有无）放进
 *    {@link LangSpec} 和源码原文字段，不污染公共节点；
 *  · 任何无法归入公共骨架的语句都用 {@link UStmt.K#RAW} 保留原文，保证往返不丢信息
 *    （与现有 Java 链路 raw.code 的口径一致）。
 *
 * 本文件不依赖 clbc.blocks / clbc.ui / clbc.gl，纯数据，便于单独编译与实测。
 */
public final class UAST {

    private UAST() {}

    /** 顶层翻译单元：文件 = 一个模块 + 一个主类型（与 CLBC「文件即类、声明帽子隐式」对齐） */
    public static final class UModule {
        public String fileName = "";
        public String packageName = "";
        /** 依赖声明原文：Java import / Python import / C++ #include / C# using，各语言自行解释 */
        public final List<String> imports = new ArrayList<>();
        public final List<UType> types = new ArrayList<>();
        /** 不在任何类型内的游离语句原文 */
        public final List<String> orphans = new ArrayList<>();
    }

    /** 类型定义：class / struct / interface / enum 统一成 kind 字符串，由 LangSpec 决定怎么写回 */
    public static final class UType {
        public String name = "Main";
        public String kind = "class";
        public String modifiers = "";
        public final List<UField> fields = new ArrayList<>();
        public final List<UFunc> methods = new ArrayList<>();
        public String rawHeader = "";
    }

    public static final class UField {
        public String name = "";
        public String type = "";
        public String modifiers = "";
        public String init = "";
        public String comment = "";
    }

    public static final class UFunc {
        public String name = "run";
        public String retType = "";
        public String params = "";
        public String modifiers = "";
        public final List<UStmt> body = new ArrayList<>();
        public String comment = "";
    }

    /** 语句节点。公共 K 集合刻意对齐现有 CLBC_Block key，桥接层可一一映射 */
    public static final class UStmt {
        public enum K {
            BLOCK, IF, WHILE, FOR, FOREACH, DO, RETURN, EXPR, ASSIGN, VARDECL,
            CALL, TRY, THROW, BREAK, CONTINUE, COMMENT, RAW, FUNCDEF
        }
        public K kind = K.RAW;
        /** 语句原文（RAW / EXPR / CALL 的参数也放这里，按 lang 的表达式规则原样保留） */
        public String raw = "";
        /** 条件（IF / WHILE / DO） */
        public String cond = "";
        /** FOR：init / cond / update；FOREACH：var + type + iterable */
        public String init = "", update = "", var = "", type = "", iterable = "";
        /** RETURN 的返回表达式 */
        public String value = "";
        /** ASSIGN / VARDECL 的左值与初始化 */
        public String target = "", name = "", expr = "";
        /** COMMENT 正文（不含注释符；块注释保留完整分隔符由 flag 标记） */
        public String comment = "";
        public boolean blockComment = false;
        /** 语句在源码中的起始行，回写注释锚点与排序用 */
        public int line = -1;
        public final List<UStmt> body = new ArrayList<>();
        public final List<UStmt> elseBody = new ArrayList<>();
        /** TRY：资源头、catch 列表、finally 体 */
        public String tryResources = "";
        public final List<UCatch> catches = new ArrayList<>();
        public final List<UStmt> finallyBody = new ArrayList<>();
        public boolean hasFinally = false;
    }

    public static final class UCatch {
        public String param = "";
        public final List<UStmt> body = new ArrayList<>();
    }

    /** 通用表达式：调用接收者/方法名/实参，足够支撑「类下拉+方法下拉+动态参数口」积木 */
    public static final class UExpr {
        public enum K { CALL, LITERAL, IDENT, OTHER }
        public K kind = K.OTHER;
        public String scope = "";
        public String name = "";
        public final List<String> args = new ArrayList<>();
        public String raw = "";
    }

    /** 新建节点的小工具，避免各语言 Parser 重复样板 */
    public static UStmt stmt(UStmt.K k) { UStmt s = new UStmt(); s.kind = k; return s; }
    public static UStmt raw(String text) { UStmt s = stmt(UStmt.K.RAW); s.raw = text; return s; }
    public static UStmt comment(String text, boolean block) {
        UStmt s = stmt(UStmt.K.COMMENT); s.comment = text; s.blockComment = block; return s;
    }
    public static UExpr call(String scope, String name) {
        UExpr e = new UExpr(); e.kind = UExpr.K.CALL; e.scope = scope == null ? "" : scope; e.name = name; return e;
    }
}
