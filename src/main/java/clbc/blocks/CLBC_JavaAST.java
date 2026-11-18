package clbc.blocks;

import java.util.List;

import clbc.blocks.jparser.JAst;
import clbc.blocks.jparser.JParser;
import clbc.ui.CLBC_Block;
import clbc.ui.CLBC_CanvasHost;
import clbc.util.CLBC_Util;

/**
 * 代码 AST 解析器（自研纯 JDK，零第三方依赖，替代 javaparser）：把用户代码解析成语法树，
 * 识别其中可调用的积木。仅做“识别/提示”，不修改代码。识别结果统一走 {@link CLBC_Util#log(String)}
 * （错误定位只许用日志）。
 */
public class CLBC_JavaAST {

    /** 尝试解析代码，并识别积木（仅日志，不影响后续导入） */
    public static void analyze(String code) {
        try {
            JAst.JRoot root = JParser.parse(code);
            List<JAst.JExpr> calls = JParser.collectCalls(root);
            if (calls.isEmpty()) {
                CLBC_Util.log("代码识别：未检测到方法调用");
                return;
            }
            for (JAst.JExpr call : calls) {
                String scope = call.scope == null ? "" : call.scope;
                String method = call.name == null ? "" : call.name;
                String owner = scope.isEmpty() ? "" : scope.split("\\.")[0];
                // 优先查询已识别库（含安全门控），未识别再按“特殊已知调用”兜底
                CLBC_BlockDefinition def = owner.isEmpty() ? null : CLBC_BlockLibrary.findBlockRaw(owner, method);
                if (def != null) {
                    if (CLBC_BlockLibrary.isSafe(def.ownerFqn, def.methodName)) {
                        CLBC_Util.log("✅ 识别到可用积木: [" + def.category + "] "
                                + CLBC_Lang.libraryDisplayName(def.ownerFqn) + "." + def.methodName);
                    } else {
                        CLBC_Util.log("⚠️ 调用被安全策略禁用: " + def.ownerFqn + "#" + def.methodName);
                    }
                } else if (scope.equals("System.out") && method.equals("println")) {
                    CLBC_Util.log("✅ 识别到可用积木: [系统] 打印输出");
                } else {
                    CLBC_Util.log("❌ 不支持的方法调用: " + scope + "." + method);
                }
            }
        } catch (Exception e) {
            CLBC_Util.log("❌ 代码语法错误，无法解析: " + e.getMessage());
        }
    }

    /** 代码→积木：把源码解析并导入到画布（真正生成积木树，区别于仅识别的 {@link #analyze}） */
    public static void importCode(CLBC_CanvasHost canvas, String code) {
        CodeToBlocks.ConvertResult res = CodeToBlocks.convert(code);
        List<CLBC_Block> blocks = res.blocks;
        // A-4：转译链路关键路径留痕（解析结果为空也要能发现，避免静默导入空画布）
        CLBC_Util.log("代码→积木：importCode 解析出 " + blocks.size() + " 个积木、" + res.comments.size() + " 个注释框，准备导入画布");
        canvas.importBlocks(blocks, false, res.comments);
    }
}
