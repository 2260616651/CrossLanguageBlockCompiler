package clbc.blocks.jparser;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.HashMap;
import java.util.Map;

import clbc.blocks.jparser.JAst.JExpr;
import clbc.blocks.jparser.JAst.JField;
import clbc.blocks.jparser.JAst.JImport;
import clbc.blocks.jparser.JAst.JMethod;
import clbc.blocks.jparser.JAst.JRoot;
import clbc.blocks.jparser.JAst.JStmt;
import clbc.blocks.jparser.JAst.JType;
import clbc.blocks.jparser.JLexer.T;
import clbc.blocks.jparser.JLexer.Token;
import clbc.util.CLBC_Util;

/**
 * 自研 Java 解析器（纯 JDK，零第三方依赖）—— 替代 javaparser。
 *
 * 覆盖 CLBC 代码→积木所需的 Java 核心子集：包 / import / 类 / 字段 / 方法 /
 * 控制流(if/while/do/for/foreach) / 方法调用 / 变量声明 / 赋值 / 返回。
 *
 * 设计原则（与旧 javaparser 版行为一致、且更保真）：
 *  1. **语句级原文抓取**：每条语句先按分号/块边界切出完整 token 区间，用源码区间回取原文，
 *     不做全表达式递归下降 —— 任意复杂语法都不会解析失败或死循环。
 *  2. **轻量模式识别**：对语句原文做"方法调用 / 变量声明 / 赋值"三类识别，
 *     其余一律降级为 {@link JStmt.K#RAW}（原文完整保留）—— 即"不在声明内的代码"。
 *  3. **错误定位只走日志**：解析异常/降级统一记 {@link CLBC_Util#log(String)}。
 */
public final class JParser {

    private static final Set<String> MODS = new HashSet<>(Arrays.asList(
            "public", "private", "protected", "abstract", "static", "final",
            "volatile", "transient", "synchronized", "native", "strictfp", "default",
            "sealed", "non-sealed"));

    /** 需按原文降级的控制流/跳转关键字（不生成可执行积木，只保留原文）。
     *  try 已改为结构化 TRY（见 parseTry），不再走原文降级。 */
    private static final Set<String> RAW_KEYWORDS = new HashSet<>(Arrays.asList(
            "break", "continue", "throw", "assert", "switch", "synchronized", "yield"));

    /** A-4：顶层类型修饰符原文（含 @注解）旁路表。
     *  JAst.JType 无 modifiers 字段、且 JAst.java 不在段 1 修改范围（红线禁改），
     *  故按类名在 JParser 内暂存，供 CodeToBlocks 生成 unit.class 时取回；每次 parse 开头清空。 */
    private static final Map<String, String> CLASS_MODIFIERS = new HashMap<>();

    /** 取某顶层类型的修饰符原文（可含 @注解 / public / final / abstract 等）；缺失退回 "public" */
    public static String classModifiers(String typeName) {
        String m = typeName == null ? null : CLASS_MODIFIERS.get(typeName);
        return (m == null || m.isEmpty()) ? "public" : m;
    }

    private final String src;
    private final List<Token> tokens;
    private int i;

    private JParser(String src) {
        this.src = src;
        this.tokens = JLexer.tokenize(src);
        this.i = 0;
    }

    /** 解析入口：源码 → 编译单元 AST。内部异常不外抛，返回部分结果 + 记日志。
     *  A-4：深层嵌套语句递归可能抛 StackOverflowError（Error 非 RuntimeException），一并兜底，不许穿透。 */
    public static JRoot parse(String code) {
        CLASS_MODIFIERS.clear();
        try {
            return new JParser(code).parseRoot();
        } catch (RuntimeException | StackOverflowError ex) {
            String msg = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            CLBC_Util.log("!!!! 自研解析器异常（已降级为部分结果）: " + msg);
            return new JRoot();
        }
    }

    // ===================== 词法辅助 =====================
    private Token peek() { return tokens.get(Math.min(i, tokens.size() - 1)); }
    private boolean at(T t) { return peek().type == t; }
    private boolean at(T t, String s) { Token p = peek(); return p.type == t && p.text.equals(s); }
    private boolean atWord(String s) { return at(T.IDENT) && peek().text.equals(s); }
    private void consume() { if (i < tokens.size() - 1) i++; }
    private String ident() { if (at(T.IDENT)) { String s = peek().text; consume(); return s; } return ""; }
    private boolean isMod(String s) { return MODS.contains(s); }

    /** 源码区间：[startTok, endTok] 对应的原始子串（保真，含空格/换行）。
     *  跳过 COMMENT token 覆盖的源码区间：行注释不应渗入语句原文 / 轻量识别结果
     *  （否则会污染 classify 与往返代码）。其余空白、换行按原样保留。 */
    private String srcRange(int startTok, int endTok) {
        if (startTok < 0 || endTok < 0 || startTok > endTok || startTok >= tokens.size()) return "";
        // 区间内首个 / 末个非 COMMENT token 决定整体起止，从而保留 token 间空白
        int lo = -1, hi = -1;
        for (int k = startTok; k <= endTok && k < tokens.size(); k++) {
            T kt = tokens.get(k).type;
            if (kt == T.COMMENT || kt == T.BLOCK_COMMENT) continue;
            if (lo < 0) lo = k;
            hi = k;
        }
        if (lo < 0) return "";   // 区间里全是注释
        int s = tokens.get(lo).pos;
        Token last = tokens.get(hi);
        int e = last.pos + last.text.length();
        if (e > src.length()) e = src.length();
        if (s < 0 || s >= e) return "";
        // 收集区间内 COMMENT 的源码覆盖区间，逐段拷贝并跳过它们
        List<int[]> cuts = new ArrayList<>();
        for (int k = startTok; k <= endTok && k < tokens.size(); k++) {
            Token tk = tokens.get(k);
            if (tk.type == T.COMMENT) {
                // COMMENT token 的 pos 指向 "//" 之后、text 不含 "//"。
                // 旧实现只切注释正文，残留的 "//" 会与下一行代码粘成一行，
                // 把整段后续代码吞进注释（实测 CLBC.java 往返后 lambda 的 "});" 消失，javac 报文件结尾）。
                // 现在连 "//" 一起切，并停在行末换行之前（保留换行 → 下一行代码另起一行，语法合法）。
                int cs = tk.pos - 2;
                if (cs < 0) cs = 0;
                int ce = tk.pos + tk.text.length();
                while (ce < src.length() && src.charAt(ce) != '\n' && src.charAt(ce) != '\r') ce++;
                if (ce > src.length()) ce = src.length();
                if (cs < e && ce > s) cuts.add(new int[]{Math.max(cs, s), Math.min(ce, e)});
            } else if (tk.type == T.BLOCK_COMMENT) {
                // BLOCK_COMMENT 的 pos 指向起始 '/'，text 是含 /* */ 的完整原文。
                // 整段切除即可，不留首尾分隔符残渣；不延伸到行末，因为块注释本身就带闭合符。
                int cs = tk.pos;
                int ce = cs + tk.text.length();
                if (ce > src.length()) ce = src.length();
                if (cs < e && ce > s) cuts.add(new int[]{Math.max(cs, s), Math.min(ce, e)});
            }
        }
        StringBuilder sb = new StringBuilder();
        int p = s;
        for (int[] c : cuts) {
            if (c[0] > p) sb.append(src, p, c[0]);
            p = Math.max(p, c[1]);
        }
        if (p < e) sb.append(src, p, e);
        return sb.toString();
    }

    /** 跳过多层配对符号（从 open 消费到匹配的 close） */
    private void skipBalanced(T open, T close) {
        if (!at(open)) return;
        int depth = 0;
        while (i < tokens.size()) {
            T t = peek().type;
            if (t == open) depth++;
            else if (t == close) { depth--; consume(); if (depth == 0) return; continue; }
            consume();
        }
    }

    /** 从当前 '(' 起返回括号内原文，并消费掉匹配的 ')' */
    private String parenInner() {
        int open = i;
        if (!at(T.LPAREN)) return "";
        consume();
        int depth = 0, j = i;
        while (j < tokens.size()) {
            T t = tokens.get(j).type;
            if (t == T.LPAREN) depth++;
            else if (t == T.RPAREN) {
                if (depth == 0) { String r = srcRange(open + 1, j - 1); i = j + 1; return r; }
                depth--;
            }
            j++;
        }
        // 找不到匹配的 ')'：别静默吞掉条件/参数原文，降级为保留 '(' 之后到末尾的原文并记日志
        CLBC_Util.log("!!!!自研解析器：括号未闭合，已保留括号内原文（降级）");
        i = j;
        return srcRange(open + 1, j - 1);
    }

    /** 找 '{' 的匹配 '}' 下标（不移动游标） */
    private int matchBrace(int openIdx) {
        int depth = 0;
        for (int j = openIdx; j < tokens.size(); j++) {
            T t = tokens.get(j).type;
            if (t == T.LBRACE) depth++;
            else if (t == T.RBRACE) { depth--; if (depth == 0) return j; }
        }
        return -1;
    }

    /** A-4：修饰符与 @注解可能交错出现（如 "@Override public static" 或 "public @Deprecated"），
     *  必须循环消费到两者都不再出现，否则注解后的修饰符会被挤进返回类型、往返语义被破坏。 */
    /** 返回被消费掉的修饰符原文（token 级拼接，不用 srcRange 取源码区间）。
     *  实测：srcRange(guard, start-1) 会把区间里的注释/上一个成员的残留文本带进来，
     *  往返生成 "public public void run()" 这种重复修饰符的非法 Java。 */
    private java.util.List<String> skipModifiers() {
        java.util.List<String> out = new java.util.ArrayList<>();
        boolean progress = true;
        while (progress) {
            progress = false;
            while (at(T.IDENT) && isMod(peek().text)) { out.add(peek().text); consume(); progress = true; }
            while (at(T.AT)) {
                consume(); progress = true;
                String an = "@" + ident();
                while (at(T.DOT)) { consume(); an = an + "." + ident(); }
                if (at(T.LPAREN)) skipBalanced(T.LPAREN, T.RPAREN);
                out.add(an);
            }
        }
        return out;
    }

    // ===================== 顶层 =====================
    private JRoot parseRoot() {
        JRoot root = new JRoot();
        if (atWord("package")) { consume(); root.pkg = readUntilSemi(); }
        while (atWord("import")) {
            consume();
            JImport imp = new JImport();
            StringBuilder sb = new StringBuilder(ident());
            while (at(T.DOT)) {
                consume();
                if (at(T.OP, "*")) { imp.wildcard = true; sb.append(".*"); consume(); }
                else sb.append('.').append(ident());
            }
            imp.name = sb.toString();
            if (at(T.SEMI)) consume();
            root.imports.add(imp);
        }
        while (!at(T.EOF)) {
            int guard = i;
            // #已知限制实修：顶层注释（类级 Javadoc / 许可证头 / 文件尾注释）不参与语法判定。
            // 块注释改为 token 后若不跳过，parseRoot 的 else 分支会用 collectStmtRaw 从注释处
            // 一路扫到下一个 ; 或 {，把紧随其后的 class 声明一起吞成「顶层游离代码」，
            // 整份文件的方法/字段都会大面积降级 raw.code（实测真实源文件降级 1→24 条）。
            // 注释文本由 CodeToBlocks 的 JLexer 差集兜底补成独立便签，信息不丢。
            if (at(T.COMMENT) || at(T.BLOCK_COMMENT)) { consume(); continue; }
            skipModifiers();
            // A-4：捕获被消费掉的修饰符 + @注解 原文（无修饰符时 srcRange 返回空串）
            String clsMods = srcRange(guard, i - 1).trim();
            if (atWord("class") || atWord("interface") || atWord("enum")) {
                JType t = parseType();
                if (t != null) {
                    CLASS_MODIFIERS.put(t.name, clsMods);   // 按类名暂存，CodeToBlocks 取回后不再写死 public
                    root.types.add(t);
                }
            } else {
                // 顶层"不在声明内"的代码：抓原文存入 orphans（⑨ 往返保真），保证推进
                String raw = collectStmtRaw().trim();
                if (!raw.isEmpty()) {
                    root.orphans.add(raw);
                    CLBC_Util.logFine("自研解析器：顶层游离代码已保留: " + raw.replace("\n", " "));
                }
            }
            if (i == guard) consume();
        }
        return root;
    }

    private String readUntilSemi() {
        int start = i;
        while (!at(T.SEMI) && !at(T.EOF)) consume();
        String r = srcRange(start, i - 1).trim();
        if (at(T.SEMI)) consume();
        return r;
    }

    private JType parseType() {
        // 收口4：记录顶层类型关键字（class／interface／enum），供 CodeToBlocks 还原 unit.class／interface／enum
        String kw = at(T.IDENT) ? peek().text : "class";
        consume();  // class / interface / enum
        String name = at(T.IDENT) ? ident() : "Anonymous";
        JType t = new JType();
        t.name = name;
        t.kind = kw;
        while (!at(T.LBRACE) && !at(T.EOF)) consume();   // 跳过 generics/extends/implements
        if (at(T.LBRACE)) consume(); else return t;
        parseMembers(t);
        return t;
    }

    private void parseMembers(JType t) {
        // 类体里的 // 注释（含字段/方法声明后的行尾注释）按源码顺序暂存，
        // 挂到下一个方法体开头 → 转成独立注释框。若不消费，skipModifiers 会从注释 token 起手，
        // 把下一个成员的修饰符 / 返回类型解析错（实测生成 "public public void run()"）。
        java.util.List<String> pending = new java.util.ArrayList<>();
        while (!at(T.EOF)) {
            if (at(T.RBRACE)) { consume(); return; }
            // 类体里的行注释与块注释都要按源码顺序暂存，挂到下一个方法体开头转成独立便签。
            // 不消费的话 skipModifiers 会从注释 token 起手，把下一个成员的修饰符 / 返回类型解析错。
            if (at(T.COMMENT) || at(T.BLOCK_COMMENT)) { pending.add(peek().text); consume(); continue; }
            int guard = i;
            String modifiers = String.join(" ", skipModifiers());
            int start = i;
            // 试探扫描：判断成员是方法、字段，还是无法识别（降级）
            int j = start, paren = 0, brace = -1, semi = -1, firstParen = -1, topAssign = -1;
            while (j < tokens.size() && tokens.get(j).type != T.EOF) {
                T ty = tokens.get(j).type;
                String tx = tokens.get(j).text;
                if (ty == T.OP && tx.equals("=") && paren == 0 && topAssign < 0) topAssign = j;
                if (ty == T.LPAREN) { if (paren == 0 && firstParen < 0) firstParen = j; paren++; }
                else if (ty == T.RPAREN) { if (paren > 0) paren--; }
                else if (ty == T.LBRACE && paren == 0) {
                    if (topAssign >= 0) { int close = matchBrace(j); j = (close >= 0 ? close : j); }
                    else { brace = j; break; }
                } else if (ty == T.SEMI && paren == 0) { semi = j; break; }
                else if (ty == T.RBRACE && paren == 0) { semi = j; break; }
                j++;
            }
            if (firstParen >= 0 && topAssign < 0 && (brace >= 0 || semi >= 0)) {
                JMethod m = parseMethod(start, firstParen, modifiers);
                if (m != null) {
                    // 类体级注释（字段行尾 / 方法声明前）挂到方法体最前面，按源码顺序
                    for (int k = 0; k < pending.size(); k++) {
                        JStmt cs = new JStmt();
                        cs.kind = JStmt.K.COMMENT;
                        cs.comment = pending.get(k);
                        m.body.add(k, cs);
                    }
                    pending.clear();
                    t.methods.add(m);
                }
            } else if (semi >= 0) {
                JField f = parseField(start, semi, modifiers);
                if (f != null) t.fields.add(f);
                // semi 可能指向类型结束的 '}'，此时不消费，交给循环收尾
                i = (tokens.get(semi).type == T.RBRACE) ? semi : semi + 1;
            } else {
                CLBC_Util.logFine("自研解析器：类成员降级为原文（"
                        + srcRange(guard, Math.min(guard + 6, tokens.size() - 1)).replace("\n", " ") + " ...）");
                if (brace >= 0) { int close = matchBrace(brace); i = (close >= 0 ? close : brace) + 1; }
                else consume();
            }
            if (i == guard) consume();   // 死循环保险
        }
    }

    /** 解析方法：firstParen 之前的最后一个 IDENT 是方法名，之前是返回类型 */
    private JMethod parseMethod(int start, int firstParen, String modifiers) {
        int save = i;
        i = firstParen;
        String params = parenInner();      // 消费 '(' ... ')'
        int nameTok = -1;
        for (int k = start; k < firstParen; k++) if (tokens.get(k).type == T.IDENT) nameTok = k;
        if (nameTok < 0) { i = save; return null; }
        JMethod m = new JMethod();
        m.name = tokens.get(nameTok).text;
        m.retType = nameTok > start ? srcRange(start, nameTok - 1).trim() : "";
        m.params = params;
        m.modifiers = modifiers;   // 保留修饰符原文，往返后不丢 static/private/final
        // 跳过 throws 等，直到 '{' 或 ';'
        while (!at(T.LBRACE) && !at(T.SEMI) && !at(T.EOF) && !at(T.RBRACE)) {
            if (at(T.LPAREN)) skipBalanced(T.LPAREN, T.RPAREN); else consume();
        }
        if (at(T.LBRACE)) {
            m.hasBody = true;
            consume();
            while (!at(T.RBRACE) && !at(T.EOF)) m.body.add(parseStatement());
            if (at(T.RBRACE)) consume();
        } else {
            m.hasBody = false;
            if (at(T.SEMI)) consume();
        }
        return m;
    }

    /** 解析字段：原文 "[modifiers] type name [= init]"；修饰符独立拼回类型前，否则往返丢 public/final */
    private JField parseField(int start, int semi, String modifiers) {
        int eq = -1;
        for (int k = start; k < semi; k++)
            if (tokens.get(k).type == T.OP && tokens.get(k).text.equals("=")) { eq = k; break; }
        int declEnd = (eq >= 0 ? eq : semi) - 1;
        int nameTok = -1;
        for (int k = start; k <= declEnd; k++) if (tokens.get(k).type == T.IDENT) nameTok = k;
        if (nameTok < 0) return null;
        JField f = new JField();
        String rawType = nameTok > start ? srcRange(start, nameTok - 1).trim() : "";
        f.type = (modifiers == null || modifiers.isEmpty()) ? rawType : (modifiers + " " + rawType).trim();
        f.name = tokens.get(nameTok).text;
        f.init = (eq >= 0 && eq + 1 <= semi - 1) ? srcRange(eq + 1, semi - 1).trim() : null;
        return f;
    }

    // ===================== 语句 =====================
    private JStmt parseStatement() {
        // #131：行注释 → COMMENT 语句（后续 CodeToBlocks 转成独立注释框）；
        // #已知限制实修：块注释同样进链路，comment 存含分隔符的完整原文，回写时保留块形式。
        if (at(T.COMMENT) || at(T.BLOCK_COMMENT)) {
            JStmt s = new JStmt(); s.kind = JStmt.K.COMMENT; s.comment = peek().text;
            s.blockComment = at(T.BLOCK_COMMENT);
            consume();
            return s;
        }
        // #已知限制实修：try/catch/finally 是 Java 语言本身的语法，硬编码为结构化 TRY 语句。
        // 绝不拆成独立语句（那样会生成孤立的 "catch(X e);" 这种非法 Java），
        // 也不再整段降级 raw.code —— 整组解析成一条语句，再摊成多口容器积木。
        if (atWord("try")) return parseTry();
        if (at(T.LBRACE)) {
            JStmt s = new JStmt(); s.kind = JStmt.K.BLOCK;
            consume();
            while (!at(T.RBRACE) && !at(T.EOF)) s.body.add(parseStatement());
            if (at(T.RBRACE)) consume();
            return s;
        }
        if (atWord("if")) {
            consume(); skipToParen();
            JStmt s = new JStmt(); s.kind = JStmt.K.IF; s.cond = parenInner();
            s.thenBody.add(parseStatement());
            if (atWord("else")) { consume(); s.elseBody.add(parseStatement()); }
            return s;
        }
        if (atWord("while")) {
            consume(); skipToParen();
            JStmt s = new JStmt(); s.kind = JStmt.K.WHILE; s.cond = parenInner();
            s.body.add(parseStatement()); return s;
        }
        if (atWord("do")) {
            consume();
            JStmt s = new JStmt(); s.kind = JStmt.K.DO;
            s.body.add(parseStatement());
            if (atWord("while")) { consume(); skipToParen(); s.cond = parenInner(); }
            if (at(T.SEMI)) consume();
            return s;
        }
        if (atWord("for")) return parseFor();
        if (atWord("return")) {
            consume();
            JStmt s = new JStmt(); s.kind = JStmt.K.RETURN; s.retExpr = collectStmtRaw().trim();
            return s;
        }
        if (at(T.IDENT) && RAW_KEYWORDS.contains(peek().text)) {
            JStmt s = new JStmt(); s.kind = JStmt.K.RAW; s.raw = collectStmtRaw().trim(); return s;
        }
        // 通用语句：抓原文 → 轻量识别 → EXPR（可识别）或 RAW（不在声明内）
        String raw = collectStmtRaw().trim();
        JExpr e = classify(raw);
        JStmt s = new JStmt();
        s.kind = (e.kind == JExpr.K.OTHER) ? JStmt.K.RAW : JStmt.K.EXPR;
        s.raw = raw;
        s.expr = e;
        return s;
    }

    /**
     * 硬编码实现 Java 异常处理语法：
     * {@code try (resources) {..} catch (Type e) {..} catch (..) {..} finally {..}}。
     *
     * 库方法可以由反射程序化登记，但 try/catch/finally 是语言级固定语法，只能按语法写：
     * catch / finally 必须紧接 try，拆成独立语句积木会生成非法 Java，因此整组解析成
     * 【一条 TRY 语句】，再由 CodeToBlocks 摊成 control.try 的一个多口容器积木
     * （口 0 = 尝试体，中间各口 = 捕获，末口 = 最终执行），BlocksToCode 按口拼回完整结构。
     *
     * 支持：普通 try / try-with-resources / 多个 catch / 多异常联合捕获 / 空体 finally。
     */
    private JStmt parseTry() {
        JStmt s = new JStmt();
        s.kind = JStmt.K.TRY;
        consume();                                          // try
        if (at(T.LPAREN)) s.tryResources = parenInner().trim();   // try (R r = ...) 的资源原文
        while (!at(T.EOF) && !at(T.LBRACE) && !at(T.RBRACE)) consume();
        if (at(T.LBRACE)) s.body.addAll(parseBraceBody());
        for (int g = 0; g < 32; g++) {
            // 组间（} 与 catch 之间）的注释不并入任何一条语句，由 CodeToBlocks 的
            // 「源码注释差集兜底」按字符位置补建独立便签，信息不丢。
            while (at(T.COMMENT) || at(T.BLOCK_COMMENT)) consume();
            if (atWord("catch")) {
                consume();
                JAst.JCatch cc = new JAst.JCatch();
                cc.param = at(T.LPAREN) ? parenInner().trim() : "";
                while (!at(T.EOF) && !at(T.LBRACE) && !at(T.RBRACE)) consume();
                if (at(T.LBRACE)) cc.body.addAll(parseBraceBody());
                s.catches.add(cc);
            } else if (atWord("finally")) {
                consume();
                while (!at(T.EOF) && !at(T.LBRACE) && !at(T.RBRACE)) consume();
                if (at(T.LBRACE)) s.finallyBody.addAll(parseBraceBody());
                s.hasFinally = true;
            } else break;
        }
        return s;
    }

    /** 从当前 '{' 起解析到匹配 '}'，返回块内语句列表并消费掉 '}'（try 体 / catch 体 / finally 体共用） */
    private java.util.List<JStmt> parseBraceBody() {
        java.util.List<JStmt> out = new java.util.ArrayList<>();
        if (!at(T.LBRACE)) return out;
        consume();
        while (!at(T.RBRACE) && !at(T.EOF)) out.add(parseStatement());
        if (at(T.RBRACE)) consume();
        return out;
    }

    private void skipToParen() { while (!at(T.LPAREN) && !at(T.EOF) && !at(T.SEMI)) consume(); }

    /**
     * 抓取一条语句的完整原文：追踪 ()/[] 深度，深度 0 遇 ';' 消费并停止；
     * 遇 '{'/'}' 停止但不消费（交给 BLOCK 分支），保证每次调用至少推进一格。
     */
    private String collectStmtRaw() {
        int start = i, guard = i;
        int paren = 0, brack = 0;
        while (!at(T.EOF)) {
            T ty = peek().type;
            if (ty == T.LPAREN) paren++;
            else if (ty == T.RPAREN) { if (paren > 0) paren--; }
            else if (ty == T.LBRACK) brack++;
            else if (ty == T.RBRACK) { if (brack > 0) brack--; }
            else if (paren == 0 && brack == 0 && (ty == T.SEMI || ty == T.LBRACE || ty == T.RBRACE)) {
                String r = srcRange(start, i - 1);
                if (ty == T.SEMI) consume();
                return r;
            }
            consume();
        }
        if (i == guard) consume();
        return srcRange(start, i - 1);
    }

    /** for / foreach：括号内按 ';' 或 ':' 切分，原文保真 */
    private JStmt parseFor() {
        consume();
        skipToParen();
        String inner = parenInner();
        JStmt s = new JStmt();
        int colon = colonIndexIn(inner);
        if (colon < inner.length()) {
            s.kind = JStmt.K.FOREACH;
            String left = inner.substring(0, colon).trim();
            s.iterable = inner.substring(colon + 1).trim();
            int sp = lastWordStart(left);
            s.var = left.substring(sp).trim();
            s.type = left.substring(0, sp).trim();
        } else {
            s.kind = JStmt.K.FOR;
            String[] parts = splitTopLevel(inner, ';');
            s.init = parts.length > 0 ? parts[0].trim() : "";
            s.cond = parts.length > 1 ? parts[1].trim() : "";
            s.update = parts.length > 2 ? parts[2].trim() : "";
        }
        s.body.add(parseStatement());
        return s;
    }

    /** inner 文本里第一个顶层 ':' 的下标；无冒号返回 inner.length() */
    private int colonIndexIn(String inner) {
        int depth = 0;
        boolean inStr = false, inChar = false;
        for (int k = 0; k < inner.length(); k++) {
            char c = inner.charAt(k);
            if (inStr) { if (c == '\\') k++; else if (c == '"') inStr = false; continue; }
            if (inChar) { if (c == '\\') k++; else if (c == '\'') inChar = false; continue; }
            if (c == '"') { inStr = true; continue; }
            if (c == '\'') { inChar = true; continue; }
            if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') depth--;
            else if (c == ':' && depth == 0) return k;
        }
        return inner.length();
    }

    private int lastWordStart(String s) {
        int k = s.length() - 1;
        while (k >= 0 && (Character.isLetterOrDigit(s.charAt(k)) || s.charAt(k) == '_' || s.charAt(k) == '$')) k--;
        return k + 1;
    }

    /** 按顶层分隔符切分原文（忽略括号/字符串内的分隔符） */
    private String[] splitTopLevel(String text, char sep) {
        List<String> out = new ArrayList<>();
        int depth = 0, seg = 0;
        boolean inStr = false, inChar = false;
        for (int k = 0; k < text.length(); k++) {
            char c = text.charAt(k);
            if (inStr) { if (c == '\\') k++; else if (c == '"') inStr = false; continue; }
            if (inChar) { if (c == '\\') k++; else if (c == '\'') inChar = false; continue; }
            if (c == '"') { inStr = true; continue; }
            if (c == '\'') { inChar = true; continue; }
            if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') depth--;
            else if (c == sep && depth == 0) { out.add(text.substring(seg, k)); seg = k + 1; }
        }
        out.add(text.substring(seg));
        return out.toArray(new String[0]);
    }

    // ===================== 语句轻量识别 =====================
    /**
     * 把一条语句原文识别为：方法调用 / 变量声明 / 赋值 / 其它（RAW 原文）。
     * 参数、条件、初始化一律保留源码原文，不重建表达式文本。
     */
    private JExpr classify(String raw) {
        JExpr e = new JExpr();
        e.kind = JExpr.K.OTHER;
        e.raw = raw;
        if (raw.isEmpty()) return e;

        // 1) 方法调用：形如 "scope.name(args);"
        int lp = raw.indexOf('(');
        if (lp > 0) {
            int rp = matchParenInText(raw, lp);
            if (rp >= 0 && raw.substring(rp + 1).trim().isEmpty()) {
                String head = raw.substring(0, lp).trim();
                if (isQualifiedName(head)) {
                    int dot = head.lastIndexOf('.');
                    JExpr call = new JExpr();
                    call.kind = JExpr.K.CALL;
                    call.scope = dot >= 0 ? head.substring(0, dot) : "";
                    call.name = dot >= 0 ? head.substring(dot + 1) : head;
                    call.args.addAll(splitArgs(raw.substring(lp + 1, rp)));
                    call.raw = raw;
                    return call;
                }
            }
        }

        // 2) 赋值 / 变量声明：只认顶层单个 '='
        int eq = topLevelAssign(raw);
        if (eq >= 0) {
            String lhs = raw.substring(0, eq).trim();
            String rhs = raw.substring(eq + 1).trim();
            String[] lhsWords = lhs.split("\\s+");
            String last = lhsWords[lhsWords.length - 1];
            if (lhsWords.length >= 2 && isIdent(last)
                    && looksLikeType(lhs.substring(0, lhs.lastIndexOf(last)))) {
                JExpr vd = new JExpr();
                vd.kind = JExpr.K.VARDECL;
                vd.varName = last;
                vd.type = lhs.substring(0, lhs.lastIndexOf(last)).trim();
                vd.init = rhs;
                vd.raw = raw;
                return vd;
            }
            JExpr as = new JExpr();
            as.kind = JExpr.K.ASSIGN;
            as.target = lhs;
            as.value = rhs;
            as.raw = raw;
            return as;
        }
        return e;   // OTHER → RAW，原文完整保留（"不在声明内的代码"）
    }

    private int matchParenInText(String s, int lp) {
        int depth = 0;
        boolean inStr = false, inChar = false;
        for (int k = lp; k < s.length(); k++) {
            char c = s.charAt(k);
            if (inStr) { if (c == '\\') k++; else if (c == '"') inStr = false; continue; }
            if (inChar) { if (c == '\\') k++; else if (c == '\'') inChar = false; continue; }
            if (c == '"') { inStr = true; continue; }
            if (c == '\'') { inChar = true; continue; }
            if (c == '(') depth++;
            else if (c == ')') { depth--; if (depth == 0) return k; }
        }
        return -1;
    }

    private List<String> splitArgs(String inner) {
        List<String> out = new ArrayList<>();
        if (inner.trim().isEmpty()) return out;
        for (String p : splitTopLevel(inner, ',')) {
            String t = p.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private boolean isQualifiedName(String s) {
        if (s.isEmpty()) return false;
        for (String part : s.split("\\.", -1)) if (!isIdent(part.trim())) return false;
        return true;
    }
    private boolean isIdent(String s) {
        if (s == null || s.isEmpty()) return false;
        if (!Character.isJavaIdentifierStart(s.charAt(0))) return false;
        for (int k = 1; k < s.length(); k++) if (!Character.isJavaIdentifierPart(s.charAt(k))) return false;
        return true;
    }
    /** 类型串启发式：只含标识符字符 / 泛型尖括号 / 数组方括号 / 点 / 逗号 / 空白 */
    private boolean looksLikeType(String s) {
        String t = s.trim();
        if (t.isEmpty()) return false;
        for (int k = 0; k < t.length(); k++) {
            char c = t.charAt(k);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '.' || c == '<' || c == '>'
                    || c == '[' || c == ']' || c == ',' || c == ' ' || c == '?') continue;
            return false;
        }
        return true;
    }
    /** 顶层赋值号下标（跳过 == != <= >= 与复合赋值） */
    private int topLevelAssign(String s) {
        int depth = 0;
        boolean inStr = false, inChar = false;
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            if (inStr) { if (c == '\\') k++; else if (c == '"') inStr = false; continue; }
            if (inChar) { if (c == '\\') k++; else if (c == '\'') inChar = false; continue; }
            if (c == '"') { inStr = true; continue; }
            if (c == '\'') { inChar = true; continue; }
            if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') depth--;
            else if (c == '=' && depth == 0) {
                char prev = k > 0 ? s.charAt(k - 1) : 0;
                char next = k < s.length() - 1 ? s.charAt(k + 1) : 0;
                if (prev == '=' || prev == '!' || prev == '<' || prev == '>' || prev == '+' || prev == '-'
                        || prev == '*' || prev == '/' || prev == '%' || prev == '&' || prev == '|' || prev == '^'
                        || next == '=') continue;
                return k;
            }
        }
        return -1;
    }

    // ===================== 调用收集（供 CLBC_JavaAST 识别） =====================
    /** 遍历所有方法体，收集方法调用（CALL 表达式），用于积木识别提示 */
    public static List<JExpr> collectCalls(JRoot root) {
        List<JExpr> out = new ArrayList<>();
        for (JType t : root.types)
            for (JMethod m : t.methods)
                if (m.hasBody) collectIn(out, m.body);
        return out;
    }

    private static void collectIn(List<JExpr> out, List<JStmt> stmts) {
        for (JStmt s : stmts) {
            if (s.kind == JStmt.K.EXPR && s.expr != null && s.expr.kind == JExpr.K.CALL) out.add(s.expr);
            collectIn(out, s.thenBody);
            collectIn(out, s.elseBody);
            collectIn(out, s.body);
            if (s.kind == JStmt.K.TRY) {
                for (JAst.JCatch cc : s.catches) collectIn(out, cc.body);
                if (s.hasFinally) collectIn(out, s.finallyBody);
            }
        }
    }
}
