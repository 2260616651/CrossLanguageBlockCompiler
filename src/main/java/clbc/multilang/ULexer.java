package clbc.multilang;

import java.util.ArrayList;
import java.util.List;

/**
 * 通用词法器：按 {@link LangSpec} 配置切分四种语言共用的 token 类别。
 * · 注释统一成 COMMENT / BLOCK_COMMENT（含完整原文），供注释便签链路复用；
 * · 字符串/字符字面量整体成一个 STRING token，内部换行不打断；
 * · 缩进族记录行首空白深度（indent），花括号族记录 { } token；
 * · 关键字本身仍是 IDENT（由 Parser 用 spec.keywords 判断），与 Java 的 JLexer 口径一致。
 */
public final class ULexer {

    public enum T { IDENT, NUMBER, STRING, COMMENT, BLOCK_COMMENT, NEWLINE, INDENT,
                    LBRACE, RBRACE, LPAREN, RPAREN, LBRACK, RBRACK,
                    SEMI, COMMA, DOT, COLON, OP, HASH, EOF }

    public static final class Token {
        public final T type;
        public final String text;
        public final int pos, line, indent;
        public Token(T type, String text, int pos, int line, int indent) {
            this.type = type; this.text = text; this.pos = pos; this.line = line; this.indent = indent;
        }
        @Override public String toString() { return type + "(" + text.replace("\n", "\\n") + ")"; }
    }

    private ULexer() {}

    public static List<Token> tokenize(String src, LangSpec spec) {
        List<Token> out = new ArrayList<>();
        if (src == null) src = "";
        int n = src.length(), i = 0, line = 1;
        boolean lineStart = true;
        int lineIndent = 0;
        while (i < n) {
            char c = src.charAt(i);
            if (lineStart) {
                int j = i, spaces = 0;
                while (j < n && (src.charAt(j) == ' ' || src.charAt(j) == '\t')) { spaces++; j++; }
                if (j > i) { i = j; lineIndent = spaces; }
                else lineIndent = 0;
                if (i < n && src.charAt(i) != '\n' && src.charAt(i) != '\r') {
                    out.add(new Token(T.INDENT, String.valueOf(lineIndent), i, line, lineIndent));
                }
                lineStart = false;
                if (i >= n) break;
                c = src.charAt(i);
            }
            // 换行
            if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < n && src.charAt(i + 1) == '\n') i++;
                out.add(new Token(T.NEWLINE, "\n", i, line, lineIndent));
                i++; line++; lineStart = true; continue;
            }
            if (Character.isWhitespace(c)) { i++; continue; }
            // 行注释
            if (c == '/' && spec.lineComment.equals("//") && i + 1 < n && src.charAt(i + 1) == '/') {
                int st = i; while (i < n && src.charAt(i) != '\n' && src.charAt(i) != '\r') i++;
                out.add(new Token(T.COMMENT, src.substring(st, i), st, line, lineIndent)); continue;
            }
            if (c == '#') {
                // 只有 Python 的 # 才是行注释；C++ #include 必须切成 HASH，
                // 否则 '#' 会落到默认 OP 分支、整行被当游离语句塞进函数体。
                if (spec.family == LangSpec.Family.PYTHON) {
                    int st = i; while (i < n && src.charAt(i) != '\n' && src.charAt(i) != '\r') i++;
                    out.add(new Token(T.COMMENT, src.substring(st, i), st, line, lineIndent)); continue;
                }
                out.add(new Token(T.HASH, "#", i, line, lineIndent)); i++; continue;
            }
            // 块注释 / Python 三引号文档串
            if (!spec.blockCommentOpen.isEmpty() && src.startsWith(spec.blockCommentOpen, i)) {
                int st = i, len = spec.blockCommentOpen.length();
                i += len;
                while (i < n && !src.startsWith(spec.blockCommentClose, i)) {
                    if (src.charAt(i) == '\n') line++;
                    i++;
                }
                if (i < n) i += spec.blockCommentClose.length(); else i = n;
                out.add(new Token(T.BLOCK_COMMENT, src.substring(st, i), st, line, lineIndent)); continue;
            }
            // 字符串
            if (c == '"' || c == '\'') {
                int st = i; char q = c; i++;
                while (i < n) {
                    if (src.charAt(i) == '\\' && i + 1 < n) { i += 2; continue; }
                    if (src.charAt(i) == q) { i++; break; }
                    if (src.charAt(i) == '\n') line++;
                    i++;
                }
                out.add(new Token(T.STRING, src.substring(st, i), st, line, lineIndent)); continue;
            }
            // 数字
            if (Character.isDigit(c)) {
                int st = i;
                while (i < n && (Character.isLetterOrDigit(src.charAt(i)) || src.charAt(i) == '.')) i++;
                out.add(new Token(T.NUMBER, src.substring(st, i), st, line, lineIndent)); continue;
            }
            // 标识符
            if (Character.isLetter(c) || c == '_' || c == '$') {
                int st = i;
                while (i < n && (Character.isLetterOrDigit(src.charAt(i)) || src.charAt(i) == '_'
                        || src.charAt(i) == '$')) i++;
                out.add(new Token(T.IDENT, src.substring(st, i), st, line, lineIndent)); continue;
            }
            // 单字符 token
            T t = switch (c) {
                case '{' -> T.LBRACE; case '}' -> T.RBRACE;
                case '(' -> T.LPAREN; case ')' -> T.RPAREN;
                case '[' -> T.LBRACK; case ']' -> T.RBRACK;
                case ';' -> T.SEMI; case ',' -> T.COMMA; case '.' -> T.DOT;
                case ':' -> T.COLON;
                default -> T.OP;
            };
            out.add(new Token(t, String.valueOf(c), i, line, lineIndent)); i++;
        }
        out.add(new Token(T.EOF, "", n, line, 0));
        return out;
    }
}
