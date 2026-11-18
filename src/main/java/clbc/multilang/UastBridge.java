package clbc.multilang;

import clbc.blocks.CodeToBlocks;
import clbc.multilang.UAST.*;
import clbc.ui.CLBC_Block;

import java.util.ArrayList;
import java.util.List;

/**
 * UAST ↔ CLBC_Block 桥接。
 *
 * 关键：产物只用现有积木 key（unit.file / unit.method / data.var / control.if ...
 * 与 Java 链路完全一致），因此画布、渲染、吸附、工具箱、语言文件、注释便签
 * 全部零改动复用。语言差异只体现在 args 里的表达式原文与 spec 的文案。
 */
public final class UastBridge {

    private static int counter = 1;
    private UastBridge() {}

    private static CLBC_Block block(String key, String kind, List<String> args, int level) {
        CLBC_Block b = new CLBC_Block(counter++, kind, 0xFFCC66, key);
        b.key = key;
        b.kind = kind;
        b.nestLevel = level;
        if (args != null) b.args = new ArrayList<>(args);
        b.bindArgsAsInputs(0, null);
        return b;
    }

    public static CodeToBlocks.ConvertResult toBlocks(UModule m, LangSpec spec) {
        counter = 1;
        List<CLBC_Block> roots = new ArrayList<>();
        List<CodeToBlocks.CommentData> comments = new ArrayList<>();
        float y = 40;

        // import / using / #include 必须作为独立元块放到文件顶部，不能掉进合成 run
        for (String imp : m.imports)
            roots.add(block("meta.import", "stack", new ArrayList<>(List.of(imp)), 0));
        for (UType t : m.types) {
            CLBC_Block file = block("unit.file", "class",
                    new ArrayList<>(List.of(t.name, t.modifiers, m.packageName)), 0);
            file.hidden = true; file.name = t.name;
            roots.add(file);
            for (UField f : t.fields) {
                if (!f.comment.isEmpty()) {
                    comments.add(new CodeToBlocks.CommentData(500, y, 210, 76, stripCommentMarks(f.comment), -1, y));
                    y += 84;
                } else {
                    roots.add(block("data.var", "stack",
                            new ArrayList<>(List.of(f.type, f.name, f.init, f.modifiers)), 0));
                }
            }
            for (UFunc fn : t.methods) {
                CLBC_Block mb = block("unit.method", "method",
                        new ArrayList<>(List.of(fn.modifiers,
                                fn.retType.isEmpty() ? spec.word(LangSpec.K_VOID, "void") : fn.retType,
                                fn.name, fn.params)), 0);
                mb.name = fn.name; mb.modifiers = fn.modifiers; mb.retType = fn.retType;
                convertBody(fn.body, mb, 1, comments);
                roots.add(mb);
            }
        }
        for (String raw : m.orphans) {
            if (raw.trim().startsWith(spec.lineComment) || raw.trim().startsWith(spec.blockCommentOpen))
                comments.add(new CodeToBlocks.CommentData(500, y, 210, 76, stripCommentMarks(raw), -1, y));
            else
                roots.add(block("orphan.code", "stack", new ArrayList<>(List.of(raw)), 0));
            y += 84;
        }
        // 扁平化（绝对坐标 + parentId）
        List<CLBC_Block> flat = new ArrayList<>();
        float fy = 40;
        for (CLBC_Block r : roots) fy = place(r, 40, fy, -1, flat, 0) + 16;
        return new CodeToBlocks.ConvertResult(flat, comments);
    }

    private static float place(CLBC_Block b, float x, float y, int parentId, List<CLBC_Block> out, int depth) {
        b.x = x; b.y = y; b.parentId = parentId; b.nestLevel = depth;
        out.add(b);
        float cy = y + 42;
        for (CLBC_Block c : b.children) cy = place(c, x + 24, cy, b.id, out, depth + 1) + 6;
        return cy;
    }

    private static void convertBody(List<UStmt> body, CLBC_Block parent, int level,
                                    List<CodeToBlocks.CommentData> comments) {
        for (UStmt s : body) convertStmt(s, parent, level, comments);
    }

    private static void convertStmt(UStmt s, CLBC_Block parent, int level,
                                    List<CodeToBlocks.CommentData> comments) {
        if (level > 64) { parent.children.add(block("raw.code", "stack", new ArrayList<>(List.of(s.raw)), level)); return; }
        switch (s.kind) {
            case COMMENT -> parent.children.add(block("core.comment", "stack",
                    new ArrayList<>(List.of(stripCommentMarks(s.comment))), level));
            case VARDECL -> parent.children.add(block("data.var", "stack",
                    new ArrayList<>(List.of(s.type, s.name, s.expr)), level));
            case ASSIGN -> parent.children.add(block("data.assign", "stack",
                    new ArrayList<>(List.of(s.target, s.expr)), level));
            case RETURN -> parent.children.add(block("data.return", "stack",
                    new ArrayList<>(List.of(s.value)), level));
            case CALL -> {
                CLBC_Block c = block("ops.call", "stack",
                        new ArrayList<>(List.of(s.value, s.target, "lib:", "recv:" + s.value)), level);
                int i = 0;
                for (UStmt arg : s.body) c.args.add(arg.raw);
                c.bindArgsAsInputs(0, null);
                parent.children.add(c);
            }
            case IF -> {
                CLBC_Block b = block("control.if", "cblock",
                        new ArrayList<>(List.of(s.cond)), level);
                b.slots = new ArrayList<>();
                CLBC_Block.Slot thenSlot = new CLBC_Block.Slot("如果", s.cond);
                for (UStmt x : s.body) convertStmt(x, fake(b, thenSlot), level + 1, comments);
                b.slots.add(thenSlot);
                if (!s.elseBody.isEmpty()) {
                    CLBC_Block.Slot elseSlot = new CLBC_Block.Slot("否则", "");
                    for (UStmt x : s.elseBody) convertStmt(x, fake(b, elseSlot), level + 1, comments);
                    b.slots.add(elseSlot);
                }
                b.syncChildrenUnion();   // 把 slot 子块并入 b.children 并设置 parentId，扁平化才能保住嵌套
                parent.children.add(b);
            }
            case WHILE, DO -> {
                CLBC_Block b = block("control.while", "cblock", new ArrayList<>(List.of(s.cond)), level);
                b.slots = new ArrayList<>();
                CLBC_Block.Slot sl = new CLBC_Block.Slot("当", s.cond);
                for (UStmt x : s.body) convertStmt(x, fake(b, sl), level + 1, comments);
                b.slots.add(sl);
                b.syncChildrenUnion();
                parent.children.add(b);
            }
            case FOR, FOREACH -> {
                // args 统一 [a0, a1, a2]：
                //   foreach = [var, iterable, ""]；for = [init, cond, update]
                List<String> fa = s.kind == UStmt.K.FOREACH
                        ? new ArrayList<>(List.of(s.var, s.iterable, ""))
                        : new ArrayList<>(List.of(s.init, s.cond, s.update));
                CLBC_Block b = block(s.kind == UStmt.K.FOREACH ? "control.forEach" : "control.for", "cblock",
                        fa, level);
                b.slots = new ArrayList<>();
                CLBC_Block.Slot sl = new CLBC_Block.Slot(s.kind == UStmt.K.FOREACH ? "对于" : "重复", s.cond);
                for (UStmt x : s.body) convertStmt(x, fake(b, sl), level + 1, comments);
                b.slots.add(sl);
                b.syncChildrenUnion();
                parent.children.add(b);
            }
            case TRY -> {
                CLBC_Block b = block("control.try", "cblock", new ArrayList<>(), level);
                b.slots = new ArrayList<>();
                CLBC_Block.Slot t = new CLBC_Block.Slot("尝试", s.tryResources);
                for (UStmt x : s.body) convertStmt(x, fake(b, t), level + 1, comments);
                b.slots.add(t);
                for (UCatch cc : s.catches) {
                    CLBC_Block.Slot c = new CLBC_Block.Slot("捕获", cc.param);
                    for (UStmt x : cc.body) convertStmt(x, fake(b, c), level + 1, comments);
                    b.slots.add(c);
                }
                if (s.hasFinally) {
                    CLBC_Block.Slot f = new CLBC_Block.Slot("最终执行", "");
                    for (UStmt x : s.finallyBody) convertStmt(x, fake(b, f), level + 1, comments);
                    b.slots.add(f);
                }
                b.syncChildrenUnion();
                parent.children.add(b);
            }
            case BLOCK -> convertBody(s.body, parent, level, comments);
            case FUNCDEF -> {
                // 嵌套函数头（含 def 与冒号）原文放 a0，body 作为子块保住缩进层级
                CLBC_Block b = block("control.funcdef", "cblock",
                        new ArrayList<>(List.of(s.raw)), level);
                for (UStmt x : s.body) convertStmt(x, b, level + 1, comments);
                parent.children.add(b);
            }
            default -> parent.children.add(block("raw.code", "stack",
                    new ArrayList<>(List.of(s.raw.isEmpty() ? s.kind.name() : s.raw)), level));
        }
        parent.syncChildrenUnion();
    }

    /** 用一个轻量代理块承载「往某个 slot 里塞子块」，避免改动 convertStmt 签名传播 slot 参数 */
    private static CLBC_Block fake(CLBC_Block owner, CLBC_Block.Slot slot) {
        CLBC_Block proxy = new CLBC_Block(owner.id, "stack", 0, "");
        proxy.children = slot.children; // 直接引用 slot 的集合
        return proxy;
    }

    private static String stripCommentMarks(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.startsWith("//")) return t.substring(2).trim();
        if (t.startsWith("#")) return t.substring(1).trim();
        if (t.startsWith("/*") && t.endsWith("*/")) return t.substring(2, t.length() - 2).trim();
        if (t.startsWith("\"\"\"") && t.endsWith("\"\"\"")) return t.substring(3, t.length() - 3).trim();
        return t;
    }
}
