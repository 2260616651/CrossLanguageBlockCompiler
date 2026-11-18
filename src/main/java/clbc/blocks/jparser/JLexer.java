package clbc.blocks.jparser;

import java.util.ArrayList;
import java.util.List;

/**
 * 轻量 Java 词法分析器（自研，零第三方依赖）。
 * 目的：把源码切分为带位置信息的 Token，供 {@link JParser} 做递归下降解析。
 * 仅覆盖 CLBC 代码→积木所需的词法：标识符、关键字（按标识符处理）、数字、字符串、字符、
 * 运算符、分隔符、注释（跳过）。行号仅用于日志定位，不影响解析。
 */
public final class JLexer {

    /** Token 类型（逻辑分类；关键字与标识符同为 IDENT，由文本区分） */
    public enum T {
        IDENT, INT, LONG, DOUBLE, STRING, CHAR, COMMENT, BLOCK_COMMENT,
        LPAREN, RPAREN, LBRACE, RBRACE, LBRACK, RBRACK,
        SEMI, COMMA, DOT, OP, AT, EOF
    }

    /** 词法单元：携带文本与源码起始位置 */
    public static final class Token {
        public final T type;
        public final String text;
        public final int pos;   // 在源码中的起始下标
        public final int line;  // 行号（1-based，用于日志）
        public Token(T type, String text, int pos, int line) {
            this.type = type; this.text = text; this.pos = pos; this.line = line;
        }
        @Override public String toString() { return type + "(" + text + ")"; }
    }

    private static final String[] MULTI_OPS = {
        "<<<", ">>>", "<<", ">>", "==", "!=", "<=", ">=", "&&", "||",
        "++", "--", "+=", "-=", "*=", "/=", "%=", "->", "::"
    };

    private static boolean isIdentStart(char c) {
        return Character.isLetter(c) || c == '_' || c == '$';
    }
    private static boolean isIdentPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }
    private static boolean isDigit(char c) { return c >= '0' && c <= '9'; }

    /** 词法分析入口：返回 Token 列表（末尾为 EOF） */
    public static List<Token> tokenize(String src) {
        List<Token> out = new ArrayList<>();
        int n = src.length();
        int line = 1;
        int i = 0;
        while (i < n) {
            char c = src.charAt(i);
            if (c == '\n') { line++; i++; continue; }
            if (c == ' ' || c == '\t' || c == '\r') { i++; continue; }

            // 注释：// 行注释 → COMMENT token（text=正文，pos 指向 // 之后）；
            // /* */ 块注释 → BLOCK_COMMENT token（text=含 /* */ 的完整原文，pos 指向起始 /）。
            // 两者都进积木链路：前者是「注释后的独立黄色便签」，后者同样生成便签、回写时保留块形式。
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                i += 2;
                int cs = i;
                while (i < n && src.charAt(i) != '\n') i++;
                String txt = src.substring(cs, i).trim();
                out.add(new Token(T.COMMENT, txt, cs, line));
                continue;
            }
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                int start = i;
                i += 2;
                while (i < n && !(src.charAt(i) == '*' && i + 1 < n && src.charAt(i + 1) == '/')) {
                    if (src.charAt(i) == '\n') line++;
                    i++;
                }
                if (i + 1 < n) i += 2; else i = n;   // 连 */ 一起收进原文；未闭合时不越界
                out.add(new Token(T.BLOCK_COMMENT, src.substring(start, i), start, line));
                continue;
            }

            // 字符串字面量
            if (c == '"') {
                int start = i;
                i++; // 跳过 "
                StringBuilder sb = new StringBuilder("\"");
                while (i < n && src.charAt(i) != '"') {
                    char ch = src.charAt(i);
                    if (ch == '\\' && i + 1 < n) { sb.append(ch).append(src.charAt(i + 1)); i += 2; }
                    else { if (ch == '\n') line++; sb.append(ch); i++; }
                }
                if (i < n) { sb.append('"'); i++; }
                out.add(new Token(T.STRING, sb.toString(), start, line));
                continue;
            }
            // 字符字面量
            if (c == '\'') {
                int start = i;
                i++;
                StringBuilder sb = new StringBuilder("'");
                while (i < n && src.charAt(i) != '\'') {
                    char ch = src.charAt(i);
                    if (ch == '\\' && i + 1 < n) { sb.append(ch).append(src.charAt(i + 1)); i += 2; }
                    else { if (ch == '\n') line++; sb.append(ch); i++; }
                }
                if (i < n) { sb.append('\''); i++; }
                out.add(new Token(T.CHAR, sb.toString(), start, line));
                continue;
            }

            // 数字（含 0x 十六进制、小数、指数、L/F/D 后缀）
            if (isDigit(c) || (c == '.' && i + 1 < n && isDigit(src.charAt(i + 1)))) {
                int start = i;
                if (c == '0' && i + 1 < n && (src.charAt(i + 1) == 'x' || src.charAt(i + 1) == 'X')) {
                    i += 2;
                    while (i < n && (isDigit(src.charAt(i)) || (src.charAt(i) >= 'a' && src.charAt(i) <= 'f')
                            || (src.charAt(i) >= 'A' && src.charAt(i) <= 'F') || src.charAt(i) == '.')) i++;
                    out.add(new Token(T.INT, src.substring(start, i), start, line));
                    continue;
                }
                while (i < n && isDigit(src.charAt(i))) i++;
                boolean isDouble = false;
                if (i < n && src.charAt(i) == '.') { isDouble = true; i++; while (i < n && isDigit(src.charAt(i))) i++; }
                if (i < n && (src.charAt(i) == 'e' || src.charAt(i) == 'E')) {
                    isDouble = true; i++;
                    if (i < n && (src.charAt(i) == '+' || src.charAt(i) == '-')) i++;
                    while (i < n && isDigit(src.charAt(i))) i++;
                }
                if (i < n && (src.charAt(i) == 'l' || src.charAt(i) == 'L')) { i++; out.add(new Token(T.LONG, src.substring(start, i), start, line)); continue; }
                if (i < n && (src.charAt(i) == 'f' || src.charAt(i) == 'F' || src.charAt(i) == 'd' || src.charAt(i) == 'D')) { i++; isDouble = true; }
                out.add(new Token(isDouble ? T.DOUBLE : T.INT, src.substring(start, i), start, line));
                continue;
            }

            // 标识符
            if (isIdentStart(c)) {
                int start = i;
                while (i < n && isIdentPart(src.charAt(i))) i++;
                out.add(new Token(T.IDENT, src.substring(start, i), start, line));
                continue;
            }

            // 多字符运算符
            boolean matched = false;
            for (String op : MULTI_OPS) {
                if (src.startsWith(op, i)) {
                    out.add(new Token(T.OP, op, i, line));
                    i += op.length();
                    matched = true;
                    break;
                }
            }
            if (matched) continue;

            // 单字符符号
            switch (c) {
                case '(' -> out.add(new Token(T.LPAREN, "(", i, line));
                case ')' -> out.add(new Token(T.RPAREN, ")", i, line));
                case '{' -> out.add(new Token(T.LBRACE, "{", i, line));
                case '}' -> out.add(new Token(T.RBRACE, "}", i, line));
                case '[' -> out.add(new Token(T.LBRACK, "[", i, line));
                case ']' -> out.add(new Token(T.RBRACK, "]", i, line));
                case ';' -> out.add(new Token(T.SEMI, ";", i, line));
                case ',' -> out.add(new Token(T.COMMA, ",", i, line));
                case '.' -> out.add(new Token(T.DOT, ".", i, line));
                case '@' -> out.add(new Token(T.AT, "@", i, line));
                case '=' -> out.add(new Token(T.OP, "=", i, line));
                case '<' -> out.add(new Token(T.OP, "<", i, line));
                case '>' -> out.add(new Token(T.OP, ">", i, line));
                case '!' -> out.add(new Token(T.OP, "!", i, line));
                case '&' -> out.add(new Token(T.OP, "&", i, line));
                case '|' -> out.add(new Token(T.OP, "|", i, line));
                case '+' -> out.add(new Token(T.OP, "+", i, line));
                case '-' -> out.add(new Token(T.OP, "-", i, line));
                case '*' -> out.add(new Token(T.OP, "*", i, line));
                case '/' -> out.add(new Token(T.OP, "/", i, line));
                case '%' -> out.add(new Token(T.OP, "%", i, line));
                case '^' -> out.add(new Token(T.OP, "^", i, line));
                case '~' -> out.add(new Token(T.OP, "~", i, line));
                case '?' -> out.add(new Token(T.OP, "?", i, line));
                case ':' -> out.add(new Token(T.OP, ":", i, line));
                default -> { /* 未知字符：不产 Token，仅推进 */ }
            }
            i++;   // 单字符统一推进一格（多字符运算符已在上方提前 continue）
        }
        out.add(new Token(T.EOF, "", n, line));
        return out;
    }
}
