package clbc.multilang;

import clbc.multilang.UAST.*;

import java.util.ArrayList;
import java.util.List;

/**
 * 通用代码生成器：UAST → 目标语言源码。语言差异全部由 LangSpec 驱动。
 * 与 UParser 互为逆过程；无法结构化的 RAW 原样吐回，保证往返信息不丢。
 */
public final class UEmitter {

    private final LangSpec spec;
    private UEmitter(LangSpec spec) { this.spec = spec; }

    public static String emit(UModule m, LangSpec spec) { return new UEmitter(spec).module(m); }

    private String module(UModule m) {
        StringBuilder sb = new StringBuilder();
        for (String imp : m.imports) {
            sb.append(imp);
            if (imp.startsWith("using")) sb.append(stmtEnd());   // C# using 需要分号，C++ #include 不需要
            sb.append('\n');
        }
        if (!m.imports.isEmpty()) sb.append('\n');
        boolean nsBlock = spec.family == LangSpec.Family.C_STYLE && "namespace".equals(spec.namespaceKeyword)
                && !m.packageName.isEmpty();
        if (nsBlock) sb.append(spec.namespaceKeyword).append(' ').append(m.packageName).append("\n{\n");
        else if (!m.packageName.isEmpty()) sb.append(spec.namespaceKeyword).append(' ')
                .append(m.packageName).append(stmtEnd()).append("\n\n");
        for (UType t : m.types) type(t, sb);
        for (String raw : m.orphans) {
            if (raw.trim().startsWith("//") || raw.trim().startsWith("#") || raw.trim().startsWith("/*"))
                sb.append(raw).append('\n');
            else sb.append(raw).append(stmtEnd()).append('\n');
        }
        if (nsBlock) sb.append("}\n");
        return sb.toString();
    }

    private String stmtEnd() { return spec.semicolonStatements ? ";" : ""; }

    private void type(UType t, StringBuilder sb) {
        String kw = t.kind == null || t.kind.isEmpty() ? "class" : t.kind;
        if (spec.family == LangSpec.Family.PYTHON) {
            sb.append(kw).append(' ').append(t.name);
            if (!t.rawHeader.isEmpty()) sb.append(t.rawHeader.startsWith(":") ? "" : ": ").append(t.rawHeader);
            else sb.append(':');
            sb.append('\n');
            for (UField f : t.fields) {
                if (!f.comment.isEmpty()) sb.append(indent(1)).append(f.comment).append('\n');
                else if (!f.name.isEmpty()) sb.append(indent(1)).append(f.name)
                        .append(f.init.isEmpty() ? "" : " = " + f.init).append('\n');
            }
            for (UFunc fn : t.methods) func(fn, sb, 1, t.name);
        } else {
            sb.append(t.modifiers.isEmpty() ? "" : t.modifiers + " ").append(kw).append(' ')
              .append(t.name).append(t.rawHeader.isEmpty() ? "" : " " + t.rawHeader).append(" {\n");
            for (UField f : t.fields) {
                if (!f.comment.isEmpty()) sb.append(indent(1)).append(f.comment).append('\n');
                else if (!f.name.isEmpty()) sb.append(indent(1))
                        .append(f.modifiers.isEmpty() ? "" : f.modifiers + " ")
                        .append(f.type.isEmpty() ? "var" : f.type).append(' ').append(f.name)
                        .append(f.init.isEmpty() ? "" : " = " + f.init).append(stmtEnd()).append('\n');
            }
            for (UFunc fn : t.methods) func(fn, sb, 1, t.name);
            sb.append("}\n");
        }
        sb.append('\n');
    }

    private void func(UFunc f, StringBuilder sb, int ind, String className) {
        if (spec.family == LangSpec.Family.PYTHON) {
            sb.append(indent(ind)).append(spec.word(LangSpec.K_DEF, "def")).append(' ').append(f.name)
              .append('(').append(f.params).append(')');
            // Python 没有 void 返回注解；桥接层对空返回类型补的 "void" 不能吐成 `-> void`
            boolean voidAnn = f.retType.equals("void") || f.retType.equals("Void");
            if (!f.retType.isEmpty() && !voidAnn) sb.append(" -> ").append(f.retType);
            sb.append(":\n");
            if (f.body.isEmpty()) sb.append(indent(ind + 1)).append("pass\n");
            else body(f.body, sb, ind + 1);
        } else {
            // 构造函数（名与类名相同）不写返回类型
            boolean ctor = className != null && className.equals(f.name);
            sb.append(indent(ind)).append(f.modifiers.isEmpty() ? "" : f.modifiers + " ");
            if (!ctor) sb.append(f.retType.isEmpty() ? spec.word(LangSpec.K_VOID, "void") : f.retType).append(' ');
            sb.append(f.name).append('(').append(f.params).append(") {\n");
            body(f.body, sb, ind + 1);
            sb.append(indent(ind)).append("}\n");
        }
    }

    private void body(List<UStmt> stmts, StringBuilder sb, int ind) {
        for (UStmt s : stmts) stmt(s, sb, ind);
    }

    private void stmt(UStmt s, StringBuilder sb, int ind) {
        String pad = indent(ind);
        switch (s.kind) {
            case COMMENT -> sb.append(pad).append(s.comment).append('\n');
            case VARDECL -> {
                if (spec.family == LangSpec.Family.PYTHON)
                    sb.append(pad).append(s.name).append(s.expr.isEmpty() ? "" : " = " + s.expr).append('\n');
                else sb.append(pad).append(s.type.isEmpty() ? "var" : s.type).append(' ')
                        .append(s.name).append(s.expr.isEmpty() ? "" : " = " + s.expr).append(stmtEnd()).append('\n');
            }
            case ASSIGN -> sb.append(pad).append(s.target).append(" = ").append(s.expr).append(stmtEnd()).append('\n');
            case RETURN -> {
                String kw = spec.word(LangSpec.K_RETURN, "return");
                sb.append(pad).append(kw).append(s.value.isEmpty() ? "" : " " + s.value).append(stmtEnd()).append('\n');
            }
            case CALL -> sb.append(pad).append(s.raw.isEmpty() ? s.target + "()" : s.raw).append(stmtEnd()).append('\n');
            case BREAK -> sb.append(pad).append(spec.word(LangSpec.K_BREAK, "break")).append(stmtEnd()).append('\n');
            case CONTINUE -> sb.append(pad).append(spec.word(LangSpec.K_CONTINUE, "continue")).append(stmtEnd()).append('\n');
            case THROW -> sb.append(pad).append(spec.word(LangSpec.K_THROW, "throw")).append(' ')
                    .append(s.raw).append(stmtEnd()).append('\n');
            case IF -> {
                String kw = spec.word(LangSpec.K_IF, "if");
                if (spec.family == LangSpec.Family.PYTHON) sb.append(pad).append(kw).append(' ').append(s.cond).append(":\n");
                else sb.append(pad).append(kw).append(" (").append(s.cond).append(") {\n");
                if (s.body.isEmpty()) sb.append(indent(ind + 1)).append(spec.family == LangSpec.Family.PYTHON ? "pass" : "").append('\n');
                else body(s.body, sb, ind + 1);
                if (spec.family != LangSpec.Family.PYTHON) sb.append(pad).append("}\n");
                // 约定：elseBody 中 IF 节点 = elif；其余连续普通语句合并成同一个 else 块。
                // 不能对每个普通语句吐一个 else:，否则会生成一串空 else。
                List<UStmt> elseStmts = new ArrayList<>();
                for (UStmt e : s.elseBody) {
                    if (e.kind == UStmt.K.IF) {
                        if (spec.family == LangSpec.Family.PYTHON) {
                            sb.append(pad).append(spec.kw.getOrDefault("elif", "elif")).append(' ').append(e.cond).append(":\n");
                            body(e.body, sb, ind + 1);
                        } else {
                            sb.append(pad).append("else if (").append(e.cond).append(") {\n");
                            body(e.body, sb, ind + 1); sb.append(pad).append("}\n");
                        }
                    } else elseStmts.add(e);
                }
                if (!elseStmts.isEmpty()) {
                    if (spec.family == LangSpec.Family.PYTHON) {
                        sb.append(pad).append(spec.word(LangSpec.K_ELSE, "else")).append(":\n");
                        body(elseStmts, sb, ind + 1);
                    } else {
                        sb.append(pad).append(spec.word(LangSpec.K_ELSE, "else")).append(" {\n");
                        body(elseStmts, sb, ind + 1); sb.append(pad).append("}\n");
                    }
                }
            }
            case WHILE, DO -> {
                String kw = spec.word(LangSpec.K_WHILE, "while");
                if (spec.family == LangSpec.Family.PYTHON) sb.append(pad).append(kw).append(' ').append(s.cond).append(":\n");
                else sb.append(pad).append(kw).append(" (").append(s.cond).append(") {\n");
                body(s.body, sb, ind + 1);
                if (spec.family != LangSpec.Family.PYTHON) sb.append(pad).append("}\n");
            }
            case FOR, FOREACH -> {
                if (spec.family == LangSpec.Family.PYTHON) {
                    sb.append(pad).append(spec.word(LangSpec.K_FOR, "for")).append(' ').append(s.var)
                      .append(' ').append(spec.word(LangSpec.K_IN, "in")).append(' ').append(s.iterable).append(":\n");
                    body(s.body, sb, ind + 1);
                } else {
                    sb.append(pad).append(spec.word(LangSpec.K_FOR, "for")).append(" (")
                      .append(s.kind == UStmt.K.FOREACH ? s.type + " " + s.var + " : " + s.iterable
                              : join3(s.init, s.cond, s.update)).append(") {\n");
                    body(s.body, sb, ind + 1); sb.append(pad).append("}\n");
                }
            }
            case TRY -> {
                sb.append(pad).append(spec.word(LangSpec.K_TRY, "try"));
                if (!s.tryResources.isEmpty() && spec.family != LangSpec.Family.PYTHON)
                    sb.append(" (").append(s.tryResources).append(')');
                if (spec.family == LangSpec.Family.PYTHON) sb.append(":\n");
                else sb.append(" {\n");
                body(s.body, sb, ind + 1);
                if (spec.family != LangSpec.Family.PYTHON) sb.append(pad).append("}\n");
                String cw = spec.word(LangSpec.K_CATCH, "catch");
                for (UCatch cc : s.catches) {
                    if (spec.family == LangSpec.Family.PYTHON)
                        sb.append(pad).append(cw).append(' ').append(cc.param).append(":\n");
                    else sb.append(pad).append(cw).append(" (").append(cc.param).append(") {\n");
                    body(cc.body, sb, ind + 1);
                    if (spec.family != LangSpec.Family.PYTHON) sb.append(pad).append("}\n");
                }
                if (s.hasFinally) {
                    if (spec.family == LangSpec.Family.PYTHON)
                        sb.append(pad).append(spec.word(LangSpec.K_FINALLY, "finally")).append(":\n");
                    else sb.append(pad).append(spec.word(LangSpec.K_FINALLY, "finally")).append(" {\n");
                    body(s.finallyBody, sb, ind + 1);
                    if (spec.family != LangSpec.Family.PYTHON) sb.append(pad).append("}\n");
                }
            }
            case BLOCK -> body(s.body, sb, ind);
            case FUNCDEF -> {
                // s.raw 是完整的 `def f(...):` 头行（原文切片，可能跨行）；body 更深一层缩进
                sb.append(pad).append(s.raw).append('\n');
                if (s.body.isEmpty()) sb.append(indent(ind + 1)).append("pass\n");
                else body(s.body, sb, ind + 1);
            }
            default -> sb.append(pad).append(s.raw).append(stmtEnd()).append('\n');
        }
    }

    private String join3(String a, String b, String c) { return a + "; " + b + "; " + c; }
    private String indent(int n) { return spec.indentUnit.repeat(Math.max(0, n)); }
}
