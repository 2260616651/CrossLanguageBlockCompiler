package clbc.blocks;

import java.util.List;

/**
 * 库成员定义（方法 / 字段 / 构造器），供代码解析器着色与工具箱查询。
 * 保留旧 5 参构造以兼容既有调用（{@code CLBC_JavaAST} / {@code CodeToBlocks}）。
 */
public class CLBC_BlockDefinition {
    public final String category;     // 分类（决定颜色）
    public final String className;    // 简单类名（显示 / 匹配）
    public final String ownerFqn;     // 全限定类名
    public final String methodName;   // 成员名（方法 / 字段 / 构造器名）
    public final String displayName;
    public final List<String> params;
    public final boolean isField;     // 是否为字段（属性块）
    public final boolean isStatic;
    public final String returnType;   // 返回类型（方法 / 构造器 / 字段类型；已擦除泛型、去包名）
    public final String signature;    // 可读签名（已擦除泛型、去包名）

    public CLBC_BlockDefinition(String category, String className, String ownerFqn, String methodName,
                                String displayName, List<String> params, boolean isField, boolean isStatic,
                                String returnType, String signature) {
        this.category = category; this.className = className; this.ownerFqn = ownerFqn;
        this.methodName = methodName; this.displayName = displayName; this.params = params;
        this.isField = isField; this.isStatic = isStatic; this.returnType = returnType; this.signature = signature;
    }

    /** 兼容旧调用（默认视为静态方法块） */
    public CLBC_BlockDefinition(String category, String className, String methodName, String displayName, List<String> params) {
        this(category, className, className, methodName, displayName, params, false, true, "", methodName);
    }
}
