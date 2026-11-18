package clbc.multilang;

import clbc.multilang.UAST.*;
import clbc.multilang.ULexer.T;
import clbc.multilang.ULexer.Token;

import java.util.ArrayList;
import java.util.List;

/**
 * 语言无关解析器骨架：按 LangSpec + token 流切出 UAST。
 *
 * 统一处理缩进族（Python）与花括号族（C/Java/C#）：
 *  · 花括号族用 { } 定界，语句以 ; 结尾；
 *  · 缩进族用 INDENT 深度定界，语句到 NEWLINE 为止，块头以 : 结尾。
 * 无法归入公共骨架的语句一律 RAW 保留原文（含注释），保证往返信息不丢。
 */
public final class UParser {

    private final String src;
    private final LangSpec spec;
    private final List<Token> toks;
    private int p = 0;
    private int anonCounter = 1;

    private UParser(String src, LangSpec spec) {
        this.src = src == null ? "" : src;
        this.spec = spec;
        this.toks = ULexer.tokenize(this.src, spec);
    }

    public static UModule parse(String code, LangSpec spec) {
        return new UParser(code, spec).parseModule();
    }

    // ---------- token 工具 ----------
    private Token cur() { return toks.get(Math.min(p, toks.size() - 1)); }
    private Token la(int k) { return toks.get(Math.min(p + k, toks.size() - 1)); }
    private boolean at(T t) { return cur().type == t; }
    private boolean eof() { return at(T.EOF); }
    private void adv() { if (!eof()) p++; }
    private void skipNewlines() { while (at(T.NEWLINE) || at(T.INDENT)) adv(); }
    private String text() { return cur().text; }
    private boolean word(String w) { return at(T.IDENT) && text().equals(w); }
    private boolean anyWord(String... ws) { for (String w : ws) if (word(w)) return true; return false; }
    private String indentText() { return spec.indentUnit; }

    /** 从当前 token 起到行尾（或 ; / {）的原始源码切片 */
    private String lineRaw() {
        int st = cur().pos, end = st;
        while (p < toks.size()) {
            Token t = toks.get(p);
            if (t.type == T.NEWLINE || t.type == T.SEMI || t.type == T.LBRACE || t.type == T.EOF) break;
            end = t.pos + t.text.length();
            p++;
        }
        return src.substring(st, end).trim();
    }

    // ---------- 模块 ----------
    private UModule parseModule() {
        UModule m = new UModule();
        while (!eof()) {
            skipNewlines();
            if (eof()) break;
            Token t = cur();
            if (skipLeadingComments(m, t)) continue;
            if (t.type == T.COMMENT) { adv(); continue; }
            if (t.type == T.BLOCK_COMMENT) {
                m.orphans.add(t.text); adv(); continue;
            }
            // 类型收尾的 stray `}` / `;`（namespace 闭合、class 后的分号）不能当游离语句收进 run
            if (t.type == T.RBRACE || t.type == T.SEMI) { adv(); continue; }
            // namespace / package / import / using / include
            if (word(spec.namespaceKeyword) && !spec.namespaceKeyword.isEmpty()) {
                String ns = lineRaw(); m.packageName = ns.substring(spec.namespaceKeyword.length()).trim();
                skipNewlines();
                if (at(T.LBRACE)) adv();   // C# `namespace Demo\n{`：吃掉作用域起点，类型继续按模块级解析
                continue;
            }
            if (word(spec.importKeyword) && !spec.importKeyword.isEmpty()) { m.imports.add(lineRaw()); continue; }
            if (t.type == T.HASH && spec.id.equals("cpp")) {
                // 用原文切片到行尾，避免把下一行首 token 误拼进 include
                int st = t.pos;
                adv();
                while (!eof() && !at(T.NEWLINE)) adv();
                m.imports.add(src.substring(st, Math.min(cur().pos, src.length())).trim());
                continue;
            }
            // 类型声明
            if (isTypeDecl()) { m.types.add(parseType()); continue; }
            // Python 顶层 def：全部合并进同一个主类型（不能每个 def 新建一个 Main）
            if (word(spec.word(LangSpec.K_DEF, "def")) && spec.family == LangSpec.Family.PYTHON) {
                moduleType(m).methods.add(parseFunc(false)); continue;
            }
            // Python 顶层控制流（if __name__ == "__main__": 等）：解析成语句放进合成 run 方法，
            // 不能当游离原文，否则它的 body 会与头行平级、缩进全丢。
            if (spec.family == LangSpec.Family.PYTHON && isTopControl()) {
                UStmt st = parseStmt(cur().indent, false);
                if (st != null) syntheticRun(m).body.add(st);
                continue;
            }
            // 顶层游离语句：收到下一级块或 EOF
            String raw = collectTopRaw();
            if (!raw.isEmpty()) m.orphans.add(raw);
        }
        if (m.types.isEmpty()) moduleType(m);
        return m;
    }

    /** 取（或首次创建）模块级唯一主类型，承载顶层 def / 游离语句 */
    private UType moduleType(UModule m) {
        for (UType t : m.types) if ("Main".equals(t.name)) return t;
        UType ut = new UType(); ut.name = "Main"; ut.kind = "class"; m.types.add(ut); return ut;
    }

    /** 合成 run 方法，承载 Python 顶层控制流语句 */
    private UFunc syntheticRun(UModule m) {
        UType ut = moduleType(m);
        for (UFunc f : ut.methods) if ("run".equals(f.name)) return f;
        UFunc f = new UFunc(); f.name = "run"; f.retType = ""; ut.methods.add(f); return f;
    }

    /** 当前 token 是否为 Python 顶层控制流头 */
    private boolean isTopControl() {
        return word(spec.word(LangSpec.K_IF, "if")) || word(spec.word(LangSpec.K_WHILE, "while"))
                || word(spec.word(LangSpec.K_FOR, "for")) || word(spec.word(LangSpec.K_TRY, "try"));
    }

    /** 前置注释挂到模块 orphans（后续桥接层会转成 CommentData 便签） */
    private boolean skipLeadingComments(UModule m, Token t) {
        if (t.type == T.COMMENT || t.type == T.BLOCK_COMMENT) { m.orphans.add(t.text); adv(); return true; }
        return false;
    }

    private boolean isTypeDecl() {
        for (String kw : spec.typeKeywords) if (word(kw)) return true;
        // C# `public class Counter` / C++ `class X`：修饰符在前，la(1) 才是类型关键字
        if (spec.family == LangSpec.Family.C_STYLE && at(T.IDENT) && isModifier(text())
                && la(1).type == T.IDENT) {
            for (String kw : spec.typeKeywords) if (la(1).text.equals(kw)) return true;
        }
        return false;
    }

    private boolean isModifier(String w) {
        return w.equals("public") || w.equals("private") || w.equals("protected") || w.equals("internal")
                || w.equals("static") || w.equals("final") || w.equals("abstract") || w.equals("virtual")
                || w.equals("override") || w.equals("readonly") || w.equals("sealed") || w.equals("inline");
    }

    private String collectTopRaw() {
        int st = cur().pos, end = st, brace = 0;
        while (!eof()) {
            Token t = cur();
            if (t.type == T.NEWLINE && brace == 0) { adv(); break; }
            if (t.type == T.LBRACE) brace++;
            if (t.type == T.RBRACE) {
                brace--;
                // 顶层孤立的 }（如 C++ `};` 收尾）必须消费掉，否则 parseModule 反复读同一 token 死循环
                if (brace < 0) { end = t.pos + 1; adv(); break; }
            }
            end = t.pos + t.text.length();
            adv();
            if (brace == 0 && t.type == T.SEMI) break;
        }
        return src.substring(st, Math.min(end, src.length())).trim();
    }

    // ---------- 类型 ----------
    private UType parseType() {
        UType t = new UType();
        // 前置修饰符（C# public class / C++ 可选），否则 `public` 会被当类型 kind
        StringBuilder tmods = new StringBuilder();
        while (at(T.IDENT) && isModifier(text())) {
            if (tmods.length() > 0) tmods.append(' ');
            tmods.append(text()); adv();
        }
        t.modifiers = tmods.toString();
        t.kind = text(); adv();
        if (at(T.IDENT)) { t.name = text(); adv(); }
        else { t.name = "Anon" + (anonCounter++); }
        // 继承 / 泛型 / 接口列表：原文吞到 { 或 : / NEWLINE
        StringBuilder hdr = new StringBuilder();
        while (!eof() && !at(T.LBRACE) && !at(T.NEWLINE)) {
            hdr.append(text()); adv();
            if (!eof() && !at(T.LBRACE) && !at(T.NEWLINE)) hdr.append(' ');
        }
        t.rawHeader = hdr.toString().trim();
        skipNewlines();                     // C# `class X\n{` / Python `class X:` 都要先越过换行
        if (at(T.LBRACE)) adv();            // 花括号族
        int baseIndent = cur().indent;

        while (!eof()) {
            skipNewlines();
            if (eof()) break;
            if (at(T.RBRACE)) { adv(); if (at(T.SEMI)) adv(); break; }
            if (spec.family == LangSpec.Family.PYTHON && cur().indent < baseIndent) break;
            Token tk = cur();
            if (tk.type == T.COMMENT || tk.type == T.BLOCK_COMMENT) { t.fields.add(fieldFromComment(tk)); adv(); continue; }
            // C++ `public:` / C# 可见性标签不应变成 `var public:;` 字段
            if (spec.family == LangSpec.Family.C_STYLE && at(T.IDENT) && la(1).type == T.COLON
                    && (text().equals("public") || text().equals("private") || text().equals("protected"))) {
                adv(); adv(); continue;
            }
            if (isFuncDecl()) { t.methods.add(parseFunc(true)); continue; }
            // 字段声明（可能是无类型的 Python 类属性）
            UField f = parseField(baseIndent);
            if (f != null) t.fields.add(f);
        }
        return t;
    }

    private UField fieldFromComment(Token tk) {
        UField f = new UField(); f.comment = tk.text; f.name = ""; return f;
    }

    private boolean isFuncDecl() {
        if (word(spec.word(LangSpec.K_DEF, "def"))) return true;          // Python def
        if (spec.family != LangSpec.Family.C_STYLE || !at(T.IDENT)) return false;
        // 先越过 C# 可见性/修饰符（public static ...），再判断方法/构造函数签名。
        // 注意不能用 spec.keywords 排除：`void` 也是关键字，但 `void loop()` 是合法方法。
        int k = 0;
        while (k < 6 && la(k).type == T.IDENT && isModifier(la(k).text)) k++;
        Token a = la(k), b = la(k + 1), c = la(k + 2);
        if (a.type == T.IDENT && isControlWord(a.text)) return false;
        // IDENT IDENT ( → 带返回类型的方法；IDENT ( → 构造函数
        if (a.type == T.IDENT && b.type == T.IDENT && c.type == T.LPAREN) return true;
        if (a.type == T.IDENT && b.type == T.LPAREN) return true;
        return false;
    }

    private boolean isControlWord(String w) {
        return w.equals("if") || w.equals("while") || w.equals("for") || w.equals("return")
                || w.equals("else") || w.equals("try") || w.equals("catch") || w.equals("switch")
                || w.equals("do") || w.equals("throw") || w.equals("break") || w.equals("continue");
    }

    // ---------- 函数 ----------
    private UFunc parseFunc(boolean consumeIndent) {
        UFunc f = new UFunc();
        // 修饰符 / 返回类型
        StringBuilder mods = new StringBuilder();
        if (!word(spec.word(LangSpec.K_DEF, "def"))) {
            while (at(T.IDENT) && !isTypeWordOrFuncName()) { if (mods.length() > 0) mods.append(' '); mods.append(text()); adv(); }
        } else adv(); // 吃掉 def
        f.modifiers = mods.toString().trim();
        if (at(T.IDENT)) { f.name = text(); adv(); }
        // 参数
        if (at(T.LPAREN)) {
            // 参数可能跨行；跳过 NEWLINE/INDENT 并按源码间隙补空格，
            // 否则续行缩进 token（文本是缩进数，如 "13"）会粘到参数上变成 `13outname=...`。
            StringBuilder ps = new StringBuilder(); adv();
            int lastEnd = -1;
            while (!eof() && !at(T.RPAREN)) {
                Token pt = cur();
                if (pt.type == T.NEWLINE || pt.type == T.INDENT) { adv(); continue; }
                if (ps.length() > 0 && lastEnd >= 0 && pt.pos > lastEnd) ps.append(' ');
                ps.append(pt.text);
                lastEnd = pt.pos + pt.text.length();
                adv();
            }
            if (at(T.RPAREN)) adv();
            f.params = ps.toString().trim();
        }
        // Python 返回注解 `-> Type`；C/Java 类型已在 mods 倒数一段。
        // def f(...) -> T:  中 ')' 之后可能是 OP('>')（'->' 被切成 '-' '>'），
        // 必须先越过注解读到真正的 ':' 冒号，否则函数体解析不到、整段掉进 orphan。
        if (spec.family == LangSpec.Family.PYTHON) {
            StringBuilder rt = new StringBuilder();
            int guard = 0;
            while (!eof() && !at(T.COLON) && !at(T.NEWLINE) && guard++ < 200) {
                rt.append(text()); adv();
            }
            String ann = rt.toString().trim();
            if (ann.startsWith("->")) ann = ann.substring(2).trim();
            if (!ann.isEmpty()) f.retType = ann;
        } else {
            // 花括号族：mods 末段是返回类型（构造函数没有类型），前面是修饰符
            String[] ws = mods.toString().trim().split("\\s+");
            if (ws.length > 0 && !ws[0].isEmpty()) {
                String last = ws[ws.length - 1];
                if (spec.isTypeWord(last) || last.equals("void")) {
                    f.retType = last;
                    if (ws.length > 1) {
                        StringBuilder mb = new StringBuilder();
                        for (int i = 0; i < ws.length - 1; i++) { if (i > 0) mb.append(' '); mb.append(ws[i]); }
                        f.modifiers = mb.toString();
                    } else f.modifiers = "";
                }
            }
        }
        // 函数体
        skipNewlines(); if (at(T.LBRACE)) { adv();f.body.addAll(parseStmtList(0, true)); }
        else if (at(T.COLON)) {
            adv();
            if (at(T.NEWLINE)) adv();
            f.body.addAll(parseStmtList(cur().indent, false));
        }
        return f;
    }

    /** 缩进族：函数体内嵌套 def（def f(...): + 更深缩进的 body）。
     *  整段头行用原文切片保留（含可能的续行），body 递归按缩进解析。 */
    private UStmt parseNestedFunc(int baseIndent) {
        UStmt s = UAST.stmt(UStmt.K.FUNCDEF); s.line = cur().line;
        int st = cur().pos, end = st, depth = 0;
        while (!eof()) {
            Token t = cur();
            if (t.type == T.COLON && depth == 0) { end = t.pos + 1; adv(); break; }
            if (t.type == T.LPAREN || t.type == T.LBRACK || t.type == T.LBRACE) depth++;
            else if (t.type == T.RPAREN || t.type == T.RBRACK || t.type == T.RBRACE) depth--;
            end = t.pos + t.text.length();
            adv();
        }
        s.raw = src.substring(st, Math.min(end, src.length())).trim();
        if (at(T.NEWLINE)) adv();
        s.body.addAll(parseStmtList(cur().indent, false));
        return s;
    }

    private boolean isTypeWordOrFuncName() {
        // 在返回类型位置：IDENT ( 表示这是函数名
        return la(1).type == T.LPAREN;
    }

    // ---------- 字段 ----------
    private UField parseField(int baseIndent) {
        UField f = new UField();
        int st = cur().pos, end = st;
        while (!eof() && !at(T.NEWLINE) && !at(T.SEMI) && !at(T.RBRACE)) {
            end = cur().pos + cur().text.length(); adv();
        }
        if (at(T.SEMI)) adv();
        if (at(T.NEWLINE)) adv();
        String line = src.substring(st, Math.min(end, src.length())).trim();
        if (line.isEmpty()) return null;
        String noSemi = line.endsWith(";") ? line.substring(0, line.length() - 1) : line;
        int eq = noSemi.indexOf('=');
        String left = (eq >= 0 ? noSemi.substring(0, eq) : noSemi).trim();
        f.init = eq >= 0 ? noSemi.substring(eq + 1).trim() : "";
        String[] parts = left.split("\\s+");
        int idx = 0; StringBuilder mb = new StringBuilder();
        while (idx < parts.length && isModifier(parts[idx])) {
            if (mb.length() > 0) mb.append(' ');
            mb.append(parts[idx]); idx++;
        }
        f.modifiers = mb.toString();
        if (parts.length - idx >= 2) { f.type = parts[idx]; f.name = parts[parts.length - 1]; }
        else { f.name = left; }
        return f;
    }

    // ---------- 语句列表 ----------
    private List<UStmt> parseStmtList(int baseIndent, boolean braces) {
        List<UStmt> out = new ArrayList<>();
        while (!eof()) {
            skipNewlines();
            if (eof()) break;
            if (braces && at(T.RBRACE)) { adv(); break; }
            if (!braces && cur().indent < baseIndent) break;
            // 缩进族：elif / else / except / finally / elif 是与当前 if/try 同级的块 terminator，
            // 必须停在这里交回外层 parseIf/parseTry，否则会被当成 body 语句吞掉、缩进错乱。
            if (!braces && isBlockTerminator()) break;
            UStmt s = parseStmt(baseIndent, braces);
            if (s != null) out.add(s);
        }
        return out;
    }

    /** 缩进族块终止符：与外层 if/try 同级的 elif/else/except/finally */
    private boolean isBlockTerminator() {
        return word(spec.kw.getOrDefault("elif", "elif"))
                || word(spec.word(LangSpec.K_ELSE, "else"))
                || word(spec.word(LangSpec.K_CATCH, "catch"))   // python = except
                || word(spec.word(LangSpec.K_FINALLY, "finally"));
    }

    private UStmt parseStmt(int baseIndent, boolean braces) {
        Token t = cur();
        if (t.type == T.COMMENT || t.type == T.BLOCK_COMMENT) {
            UStmt s = UAST.comment(t.text, t.type == T.BLOCK_COMMENT); s.line = t.line; adv(); return s;
        }
        if (word(spec.word(LangSpec.K_IF, "if"))) return parseIf(baseIndent, braces);
        if (word(spec.word(LangSpec.K_WHILE, "while"))) return parseLoop(UStmt.K.WHILE, baseIndent, braces);
        if (word(spec.word(LangSpec.K_FOR, "for"))) return parseFor(baseIndent, braces);
        if (word(spec.word(LangSpec.K_RETURN, "return"))) {
            adv(); // 先吃掉 return 关键字，否则 readToStmtEnd 会把关键字本身也读进 value → `return return x`
            UStmt s = UAST.stmt(UStmt.K.RETURN); s.line = t.line; s.value = readToStmtEnd(); return s;
        }
        if (word(spec.word(LangSpec.K_TRY, "try"))) return parseTry(baseIndent, braces);
        if (word(spec.word(LangSpec.K_THROW, "throw")) || word("raise")) {
            adv(); // 同 RETURN：不能把关键字读进 raw，否则生成 `raise raise ...`
            UStmt s = UAST.stmt(UStmt.K.THROW); s.line = t.line; s.raw = readToStmtEnd(); return s;
        }
        if (word(spec.word(LangSpec.K_BREAK, "break"))) { adv(); return UAST.stmt(UStmt.K.BREAK); }
        if (word(spec.word(LangSpec.K_CONTINUE, "continue"))) { adv(); return UAST.stmt(UStmt.K.CONTINUE); }
        // Python 嵌套 def：函数内定义的子函数必须成块，否则头行变 CALL 丢冒号、body 被平级吞掉
        if (spec.family == LangSpec.Family.PYTHON && word(spec.word(LangSpec.K_DEF, "def")))
            return parseNestedFunc(baseIndent);
        // 赋值 / 变量声明 / 表达式语句
        String line = readToStmtEnd();
        UStmt s = classifySimple(line);
        s.line = t.line;
        return s;
    }

    private UStmt classifySimple(String line) {
        String s = line.trim();
        if (s.isEmpty()) return UAST.raw(s);
        int eq = findTopLevelAssign(s);
        if (eq > 0) {
            String left = s.substring(0, eq).trim(), right = s.substring(eq + 1).trim();
            // 类型 名 = 值 → VARDECL
            String[] parts = left.split("\\s+");
            if (parts.length >= 2 && spec.isTypeWord(parts[0])) {
                UStmt v = UAST.stmt(UStmt.K.VARDECL); v.type = parts[0]; v.name = parts[parts.length - 1]; v.expr = right; v.raw = s; return v;
            }
            UStmt a = UAST.stmt(UStmt.K.ASSIGN); a.target = left; a.expr = right; a.raw = s; return a;
        }
        // 纯调用
        UExpr e = parseCallExpr(s);
        if (e.kind == UExpr.K.CALL) {
            UStmt c = UAST.stmt(UStmt.K.CALL); c.raw = s; c.value = e.scope; c.target = e.name;
            for (String a : e.args) c.body.add(UAST.raw(a)); // 复用 body 存参数原文，桥接层按 CALL 解释
            return c;
        }
        return UAST.raw(s);
    }

    private int findTopLevelAssign(String s) {
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') depth--;
            else if (c == '=' && depth == 0) {
                // 排除 == != <= >= 以及 += -= *= /= %= //= **= &= |= ^= 等复合赋值：
                // 否则 `x += a` 会被拆成 target="x +" 再吐成 `x + = a`。
                if (i > 0 && i + 1 < s.length() && (s.charAt(i + 1) == '='
                        || "!<>+-*/%&|^:".indexOf(s.charAt(i - 1)) >= 0)) continue;
                return i;
            }
        }
        return -1;
    }

    private UExpr parseCallExpr(String s) {
        int lp = s.indexOf('('), rp = s.lastIndexOf(')');
        if (lp <= 0 || rp < lp) { UExpr e = new UExpr(); e.raw = s; return e; }
        String head = s.substring(0, lp).trim();
        UExpr e = UAST.call("", "");
        int dot = head.lastIndexOf('.');
        if (dot >= 0) { e.scope = head.substring(0, dot).trim(); e.name = head.substring(dot + 1).trim(); }
        else e.name = head;
        String inner = s.substring(lp + 1, rp);
        for (String a : splitTop(inner, ',')) if (!a.trim().isEmpty()) e.args.add(a.trim());
        return e;
    }

    private List<String> splitTop(String s, char sep) {
        List<String> out = new ArrayList<>();
        int depth = 0, st = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') depth--;
            else if (c == sep && depth == 0) { out.add(s.substring(st, i)); st = i + 1; }
        }
        out.add(s.substring(st));
        return out;
    }

    /** 读到语句结束：花括号族到 ;（含块内则到 }），缩进族到 NEWLINE；
     *  括号/方括号未闭合时不视为语句结束（Python 隐式续行），并保留原文换行与缩进。 */
    private String readToStmtEnd() {
        int st = cur().pos, end = st, depth = 0;
        while (!eof()) {
            Token t = cur();
            if (depth == 0 && t.type == T.NEWLINE) { adv(); break; }
            if (depth == 0 && t.type == T.SEMI) { end = t.pos; adv(); break; }
            if (t.type == T.LBRACE || t.type == T.LPAREN || t.type == T.LBRACK) depth++;
            else if (t.type == T.RBRACE || t.type == T.RPAREN || t.type == T.RBRACK) {
                if (depth == 0 && t.type == T.RBRACE) break;
                depth--;
            }
            end = t.pos + t.text.length();
            adv();
        }
        return src.substring(st, Math.min(end, src.length())).trim();
    }

    // ---------- 控制流 ----------
    private UStmt parseIf(int baseIndent, boolean braces) {
        UStmt s = UAST.stmt(UStmt.K.IF); s.line = cur().line;
        adv(); // if
        s.cond = readCondition();
        skipNewlines(); if (at(T.LBRACE)) { adv();s.body.addAll(parseStmtList(0, true)); }
        else if (at(T.COLON)) { adv(); if (at(T.NEWLINE)) adv(); s.body.addAll(parseStmtList(cur().indent, false)); }
        // else / elif
        while (true) {
            skipNewlines();
            String elifWord = spec.kw.getOrDefault("elif", "elif");
            boolean isElif = word(elifWord) || word(spec.word(LangSpec.K_ELSE, "else")) && la(1).type == T.COLON == false && word(elifWord);
            if (word(elifWord)) {
                adv(); UStmt ei = UAST.stmt(UStmt.K.IF); ei.cond = readCondition();
                skipNewlines(); if (at(T.LBRACE)) { adv();ei.body.addAll(parseStmtList(0, true)); }
                else if (at(T.COLON)) { adv(); if (at(T.NEWLINE)) adv(); ei.body.addAll(parseStmtList(cur().indent, false)); }
                s.elseBody.add(ei); continue;
            }
            if (word(spec.word(LangSpec.K_ELSE, "else"))) {
                // 排除 else if（花括号族写成 } else if (...)
                adv();
                if (word(spec.word(LangSpec.K_IF, "if"))) { p--; continue; }
                skipNewlines(); if (at(T.LBRACE)) { adv();s.elseBody.addAll(parseStmtList(0, true)); }
                else if (at(T.COLON)) { adv(); if (at(T.NEWLINE)) adv(); s.elseBody.addAll(parseStmtList(cur().indent, false)); }
            }
            break;
        }
        return s;
    }

    private String readCondition() {
        // 缩进族（Python）一律读到块头冒号为止：条件以 '(' 开头时，该括号可能只是
        // 元组/子表达式（如 `if (x, y) == goal:`），不能当成「整个条件被括号包裹」
        // 而在匹配的 ')' 处提前收尾，否则 `== goal` 会滞留为独立语句被吞掉、if 变空体。
        // 嵌套 () [] {} 内部的冒号不算块头结束；原文切片保留源码里的空格写法。
        if (!at(T.LPAREN) || spec.family == LangSpec.Family.PYTHON) {
            int st = cur().pos, end = st, depth = 0;
            while (!eof() && !at(T.NEWLINE)) {
                Token t = cur();
                if (depth == 0 && at(T.COLON)) break;
                if (t.type == T.LPAREN || t.type == T.LBRACK || t.type == T.LBRACE) depth++;
                else if (t.type == T.RPAREN || t.type == T.RBRACK || t.type == T.RBRACE) depth--;
                end = t.pos + t.text.length();
                adv();
            }
            return src.substring(st, Math.min(end, src.length())).trim();
        }
        adv(); // 吃掉 '('
        // 花括号族用原文切片，保留 token 间空格，否则 `int i = 0` 会粘成 `inti=0`
        int st = cur().pos, end = st, d = 1;
        while (!eof() && d > 0) {
            Token t = cur();
            if (t.type == T.LPAREN) d++;
            if (t.type == T.RPAREN) { d--; if (d == 0) { end = t.pos; adv(); break; } }
            end = t.pos + t.text.length();
            adv();
        }
        return src.substring(st, Math.min(end, src.length())).trim();
    }

    private UStmt parseLoop(UStmt.K k, int baseIndent, boolean braces) {
        UStmt s = UAST.stmt(k); s.line = cur().line; adv();
        s.cond = readCondition();
        skipNewlines(); if (at(T.LBRACE)) { adv();s.body.addAll(parseStmtList(0, true)); }
        else if (at(T.COLON)) { adv(); if (at(T.NEWLINE)) adv(); s.body.addAll(parseStmtList(cur().indent, false)); }
        return s;
    }

    private UStmt parseFor(int baseIndent, boolean braces) {
        UStmt s = UAST.stmt(UStmt.K.FOR); s.line = cur().line; adv();
        if (spec.family == LangSpec.Family.PYTHON) {
            // for x in xs:  读到块头冒号，忽略 [] () {} 内的冒号与换行（如 `circles[:m]`），
            // 用原文切片避免续行被误判为语句结束、把 for 头劈成两半。
            int st = cur().pos, end = st, depth = 0;
            while (!eof() && !at(T.NEWLINE)) {
                Token tk = cur();
                if (depth == 0 && at(T.COLON)) break;
                if (tk.type == T.LPAREN || tk.type == T.LBRACK || tk.type == T.LBRACE) depth++;
                else if (tk.type == T.RPAREN || tk.type == T.RBRACK || tk.type == T.RBRACE) depth--;
                end = tk.pos + tk.text.length();
                adv();
            }
            String head = src.substring(st, Math.min(end, src.length())).trim();
            String inW = spec.word(LangSpec.K_IN, "in");
            int k = head.indexOf(inW);
            if (k >= 0) { s.var = head.substring(0, k).trim(); s.iterable = head.substring(k + inW.length()).trim(); s.kind = UStmt.K.FOREACH; }
            else s.init = head;
            if (at(T.COLON)) adv();
            if (at(T.NEWLINE)) adv();
            s.body.addAll(parseStmtList(cur().indent, false));
        } else {
            String head = readCondition();
            String[] seg = head.split(";", -1);
            s.init = seg.length > 0 ? seg[0].trim() : "";
            s.cond = seg.length > 1 ? seg[1].trim() : "";
            s.update = seg.length > 2 ? seg[2].trim() : "";
            skipNewlines(); if (at(T.LBRACE)) adv();
            s.body.addAll(parseStmtList(0, true));
        }
        return s;
    }

    private UStmt parseTry(int baseIndent, boolean braces) {
        UStmt s = UAST.stmt(UStmt.K.TRY); s.line = cur().line; adv();
        if (at(T.LPAREN)) s.tryResources = readCondition();
        skipNewlines(); if (at(T.LBRACE)) { adv();s.body.addAll(parseStmtList(0, true)); }
        else if (at(T.COLON)) { adv(); if (at(T.NEWLINE)) adv(); s.body.addAll(parseStmtList(cur().indent, false)); }
        String catchWord = spec.word(LangSpec.K_CATCH, "catch");
        String finallyWord = spec.word(LangSpec.K_FINALLY, "finally");
        for (int g = 0; g < 32; g++) {
            skipNewlines();
            if (word(catchWord)) {
                adv(); UCatch cc = new UCatch();
                if (at(T.LPAREN)) cc.param = readCondition();
                else if (spec.family == LangSpec.Family.PYTHON) {
                    // Python: except Exception as e:  读到冒号前作为 param
                    StringBuilder pb = new StringBuilder(); int le = -1;
                    while (!eof() && !at(T.COLON) && !at(T.NEWLINE)) {
                        Token tk = cur();
                        if (pb.length() > 0 && le >= 0 && tk.pos > le) pb.append(' ');
                        pb.append(tk.text); le = tk.pos + tk.text.length(); adv();
                    }
                    cc.param = pb.toString().trim();
                }
                else if (at(T.COLON)) adv();
                skipNewlines(); if (at(T.LBRACE)) { adv();cc.body.addAll(parseStmtList(0, true)); }
                else if (at(T.COLON)) { adv(); if (at(T.NEWLINE)) adv(); cc.body.addAll(parseStmtList(cur().indent, false)); }
                s.catches.add(cc);
            } else if (word(finallyWord)) {
                adv(); s.hasFinally = true;
                skipNewlines(); if (at(T.LBRACE)) { adv();s.finallyBody.addAll(parseStmtList(0, true)); }
                else if (at(T.COLON)) { adv(); if (at(T.NEWLINE)) adv(); s.finallyBody.addAll(parseStmtList(cur().indent, false)); }
            } else break;
        }
        return s;
    }
}
