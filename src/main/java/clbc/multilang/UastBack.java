package clbc.multilang;

import clbc.blocks.CodeToBlocks;
import clbc.multilang.UAST.*;
import clbc.ui.CLBC_Block;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 积木 → UAST → 源码（通用语言的 toCode 实现）。
 * 从扁平积木列表重建树，再按 key 还原 UStmt，交给 UEmitter 按 LangSpec 吐源码。
 */
public final class UastBack {

    private UastBack() {}

    private static String arg(CLBC_Block b, int i) {
        if (b.inputs != null) {
            for (CLBC_Block.Input in : b.inputs)
                if (in.row == 0 && in.ordinal == i) return in.code();
        }
        if (b.args != null && i < b.args.size() && b.args.get(i) != null) return b.args.get(i);
        return "";
    }

    public static String toSource(List<CLBC_Block> flat, List<CodeToBlocks.CommentData> comments, LangSpec spec) {
        // 1) 建 id→block 索引，重建 parentId 树
        Map<Integer, CLBC_Block> byId = new LinkedHashMap<>();
        for (CLBC_Block b : flat) byId.put(b.id, b);
        Map<Integer, List<CLBC_Block>> children = new LinkedHashMap<>();
        List<CLBC_Block> roots = new ArrayList<>();
        for (CLBC_Block b : flat) {
            if (b.parentId >= 0 && byId.containsKey(b.parentId))
                children.computeIfAbsent(b.parentId, k -> new ArrayList<>()).add(b);
            else roots.add(b);
        }
        for (CLBC_Block b : flat) {
            List<CLBC_Block> cs = children.get(b.id);
            b.children = cs == null ? new ArrayList<>() : new ArrayList<>(cs);
        }

        // 2) 组装 UModule
        UModule m = new UModule();
        UType mainType = new UType();
        String className = "Main", mods = "", pkg = "";
        boolean gotType = false;
        for (CLBC_Block r : roots) {
            String k = r.key == null ? "" : r.key;
            if ("unit.file".equals(k)) {
                className = arg(r, 0).isEmpty() ? r.name : arg(r, 0);
                mods = arg(r, 1); pkg = arg(r, 2);
                if (r.name != null && !r.name.isEmpty()) className = r.name;
                m.packageName = pkg;
            }
        }
        // 3) 逐根生成语句，塞进主类型的 methods（无函数头的语句合成一个 run 函数）
        UFunc synthetic = new UFunc(); synthetic.name = "run"; synthetic.modifiers = "";
        synthetic.retType = spec.family == LangSpec.Family.PYTHON ? "" : spec.word(LangSpec.K_VOID, "void");
        boolean syntheticUsed = false;
        for (CLBC_Block r : roots) {
            String k = r.key == null ? "" : r.key;
            if ("unit.file".equals(k)) continue;
            if ("meta.import".equals(k)) { m.imports.add(arg(r, 0)); continue; }
            if ("unit.method".equals(k)) {
                UFunc fn = new UFunc();
                fn.modifiers = arg(r, 0); fn.retType = arg(r, 1);
                fn.name = arg(r, 2).isEmpty() ? r.name : arg(r, 2); fn.params = arg(r, 3);
                if (r.retType != null && !r.retType.isEmpty()) fn.retType = r.retType;
                fn.body.addAll(toStmts(r.children, spec));
                mainType.methods.add(fn); gotType = true;
            } else if ("data.var".equals(k)) {
                UField f = new UField(); f.type = arg(r, 0); f.name = arg(r, 1); f.init = arg(r, 2);
                f.modifiers = arg(r, 3);
                mainType.fields.add(f); gotType = true;
            } else {
                synthetic.body.addAll(toStmts(java.util.List.of(r), spec));
                syntheticUsed = true;
            }
        }
        if (syntheticUsed) mainType.methods.add(synthetic);
        if (!gotType && syntheticUsed) gotType = true;
        mainType.name = className;
        mainType.modifiers = mods;
        if (!gotType) { UFunc empty = new UFunc(); empty.name = "run"; mainType.methods.add(empty); }
        m.types.add(mainType);

        // 4) 注释便签按 orderY 并入对应位置（简单起见作为 module 尾部注释；锚点在桥接时已保序）
        if (comments != null) {
            List<CodeToBlocks.CommentData> sorted = new ArrayList<>(comments);
            sorted.sort((a, b) -> Float.compare(a.sortY(), b.sortY()));
            for (CodeToBlocks.CommentData cd : sorted) m.orphans.add(restoreComment(cd.text, spec));
        }
        return UEmitter.emit(m, spec);
    }

    /** 桥接层 stripCommentMarks 去掉了注释符；回写时按语言补回，否则裸文本会被当代码吐出、语法非法。
     *  块注释统一转成逐行行注释，Python 用 #，花括号族用 //。 */
    private static String restoreComment(String text, LangSpec spec) {
        if (text == null) return "";
        String t = text.strip();
        if (t.isEmpty()) return "";
        String prefix = spec.family == LangSpec.Family.PYTHON ? "# " : spec.lineComment + " ";
        StringBuilder sb = new StringBuilder();
        for (String ln : t.split("\\R", -1)) {
            String l = ln.strip();
            if (l.isEmpty()) continue;
            if (l.startsWith("#") || l.startsWith("//") || l.startsWith("/*")) sb.append(l);
            else sb.append(prefix).append(l);
            sb.append('\n');
        }
        return sb.toString().stripTrailing();
    }

    private static List<UStmt> toStmts(List<CLBC_Block> blocks, LangSpec spec) {
        List<UStmt> out = new ArrayList<>();
        for (CLBC_Block b : blocks) {
            UStmt s = toStmt(b, spec);
            if (s != null) out.add(s);
        }
        return out;
    }

    private static UStmt toStmt(CLBC_Block b, LangSpec spec) {
        String k = b.key == null ? "" : b.key;
        switch (k) {
            case "data.var" -> {
                UStmt s = UAST.stmt(UStmt.K.VARDECL);
                s.type = arg(b, 0); s.name = arg(b, 1); s.expr = arg(b, 2); return s;
            }
            case "data.assign" -> {
                UStmt s = UAST.stmt(UStmt.K.ASSIGN);
                s.target = arg(b, 0); s.expr = arg(b, 1); return s;
            }
            case "data.return" -> {
                UStmt s = UAST.stmt(UStmt.K.RETURN); s.value = arg(b, 0); return s;
            }
            case "core.comment" -> { return UAST.comment(restoreComment(arg(b, 0), spec), false); }
            case "control.funcdef" -> {
                UStmt s = UAST.stmt(UStmt.K.FUNCDEF);
                s.raw = arg(b, 0);
                s.body.addAll(toStmts(b.children, spec));
                return s;
            }
            case "ops.call" -> {
                UStmt s = UAST.stmt(UStmt.K.CALL);
                String cls = arg(b, 0), mn = arg(b, 1);
                String recv = null;
                if (b.args != null && b.args.size() > 3 && b.args.get(3) != null && b.args.get(3).startsWith("recv:"))
                    recv = b.args.get(3).substring(5);
                StringBuilder call = new StringBuilder();
                if (recv != null && !recv.isEmpty() && !recv.equals(cls)) call.append(recv).append('.');
                else if (!cls.isEmpty() && !"lib:".equals(cls)) call.append(cls).append('.');
                call.append(mn).append('(');
                List<String> params = new ArrayList<>();
                for (int i = 4; i < (b.args == null ? 4 : b.args.size()); i++) {
                    String a = b.args.get(i);
                    if (a != null && !a.isEmpty() && !"lib:".equals(a) && !a.startsWith("recv:")) params.add(a);
                }
                if (params.isEmpty()) {
                    for (CLBC_Block.Input in : b.inputs)
                        if (in.row == 0 && in.ordinal >= 4 && !in.code().isEmpty()) params.add(in.code());
                }
                call.append(String.join(", ", params)).append(')');
                s.raw = call.toString(); return s;
            }
            case "control.if" -> {
                UStmt s = UAST.stmt(UStmt.K.IF); s.cond = arg(b, 0);
                appendSlots(s.body, s.elseBody, b, spec); return s;
            }
            case "control.while" -> {
                UStmt s = UAST.stmt(UStmt.K.WHILE); s.cond = arg(b, 0);
                s.body.addAll(toStmts(slotChildren(b, 0), spec)); return s;
            }
            case "control.for", "control.forEach" -> {
                UStmt s = UAST.stmt("control.forEach".equals(k) ? UStmt.K.FOREACH : UStmt.K.FOR);
                if ("control.forEach".equals(k)) {
                    s.var = arg(b, 0); s.iterable = arg(b, 1); s.cond = arg(b, 2);
                } else {
                    s.init = arg(b, 0); s.cond = arg(b, 1); s.update = arg(b, 2);
                }
                s.body.addAll(toStmts(slotChildren(b, 0), spec)); return s;
            }
            case "control.try" -> {
                UStmt s = UAST.stmt(UStmt.K.TRY);
                if (b.hasSlots()) {
                    for (int i = 0; i < b.slots.size(); i++) {
                        CLBC_Block.Slot sl = b.slots.get(i);
                        List<CLBC_Block> cs = sl.children;
                        if (i == 0) { s.tryResources = sl.expr; s.body.addAll(toStmts(cs, spec)); }
                        else if (sl.label != null && sl.label.contains("终")) { s.hasFinally = true; s.finallyBody.addAll(toStmts(cs, spec)); }
                        else { UCatch cc = new UCatch(); cc.param = sl.expr; cc.body.addAll(toStmts(cs, spec)); s.catches.add(cc); }
                    }
                } else s.body.addAll(toStmts(b.children, spec));
                if (!s.catches.isEmpty() && spec.family == LangSpec.Family.PYTHON) {
                    // Python except 语法由 UEmitter 处理
                }
                return s;
            }
            case "raw.code", "orphan.code" -> { return UAST.raw(arg(b, 0)); }
            default -> { return UAST.raw(b.text == null ? k : b.text); }
        }
    }

    private static void appendSlots(List<UStmt> then, List<UStmt> els, CLBC_Block b, LangSpec spec) {
        if (b.hasSlots()) {
            then.addAll(toStmts(b.slots.get(0).children, spec));
            for (int i = 1; i < b.slots.size(); i++) {
                CLBC_Block.Slot sl = b.slots.get(i);
                if (sl.label != null && sl.label.contains("否则")) {
                    if (sl.expr != null && !sl.expr.isEmpty()) {
                        UStmt ei = UAST.stmt(UStmt.K.IF); ei.cond = sl.expr;
                        ei.body.addAll(toStmts(sl.children, spec)); els.add(ei);
                    } else els.addAll(toStmts(sl.children, spec));
                }
            }
        } else then.addAll(toStmts(b.children, spec));
    }

    private static List<CLBC_Block> slotChildren(CLBC_Block b, int i) {
        if (b.hasSlots() && i < b.slots.size()) return b.slots.get(i).children;
        return b.children;
    }
}
