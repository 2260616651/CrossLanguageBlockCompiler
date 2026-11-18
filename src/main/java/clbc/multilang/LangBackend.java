package clbc.multilang;

import clbc.blocks.CodeToBlocks;
import clbc.ui.CLBC_Block;
import java.util.List;

/**
 * 语言后端接口：把「源码 ↔ 积木」的双向转换抽象成一层，UI 与画布只认这个接口，
 * 不再关心源码是 Java / Python / C++ / C#。
 */
public interface LangBackend {

    LangSpec spec();

    /** 源码 → 积木 + 注释便签 */
    CodeToBlocks.ConvertResult toBlocks(String code);

    /** 积木 + 注释便签 → 源码 */
    String toCode(List<CLBC_Block> flat, List<CodeToBlocks.CommentData> comments);

    /** 一步往返（源码 → 积木 → 源码），自检与「翻译」按钮走这里 */
    default String roundTrip(String code) {
        CodeToBlocks.ConvertResult r = toBlocks(code);
        return toCode(r.blocks, r.comments);
    }
}
