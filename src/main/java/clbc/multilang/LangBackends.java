package clbc.multilang;

import clbc.blocks.BlocksToCode;
import clbc.blocks.CodeToBlocks;
import clbc.ui.CLBC_Block;
import java.util.List;

/**
 * 四语言后端实现集中在此文件：
 *  · Java 委托现有已验证链路（CodeToBlocks / BlocksToCode），保证 447 断言不回退；
 *  · Python / C++ / C# 走通用 UAST 链路（UParser → UastBridge → UEmitter）。
 */
public final class LangBackends {

    private LangBackends() {}

    /** 通用后端：由 LangSpec 驱动 U 链路 */
    public static final class Universal implements LangBackend {
        private final LangSpec spec;
        public Universal(LangSpec spec) { this.spec = spec; }
        @Override public LangSpec spec() { return spec; }
        @Override public CodeToBlocks.ConvertResult toBlocks(String code) {
            UAST.UModule m = UParser.parse(code, spec);
            return UastBridge.toBlocks(m, spec);
        }
        @Override public String toCode(List<CLBC_Block> flat, List<CodeToBlocks.CommentData> comments) {
            // 通用 AST ← 积木：走块 args 还原 UAST；这里保留从 flat 重建的能力（详见 UastBack）
            return UastBack.toSource(flat, comments, spec);
        }
    }

    /** Java：委托现有链路 */
    public static final class Java implements LangBackend {
        private final LangSpec spec = LangSpec.java();
        @Override public LangSpec spec() { return spec; }
        @Override public CodeToBlocks.ConvertResult toBlocks(String code) { return CodeToBlocks.convert(code); }
        @Override public String toCode(List<CLBC_Block> flat, List<CodeToBlocks.CommentData> comments) {
            return BlocksToCode.fromCanvas(flat, comments);
        }
    }

    public static LangBackend java() { return new Java(); }
    public static LangBackend python() { return new Universal(LangSpec.python()); }
    public static LangBackend cpp() { return new Universal(LangSpec.cpp()); }
    public static LangBackend csharp() { return new Universal(LangSpec.csharp()); }

    public static LangBackend of(String id) {
        LangSpec s = LangSpec.byId(id);
        return switch (s.id) {
            case "python" -> python();
            case "cpp" -> cpp();
            case "csharp" -> csharp();
            default -> java();
        };
    }
}
