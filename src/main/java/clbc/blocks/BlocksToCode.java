package clbc.blocks;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import clbc.ui.CLBC_Block;
import clbc.util.CLBC_Util;

/**
 * 积木→代码 转换器（P0② 双向之二）：把画布上的积木还原为 Java 源码。
 *
 *  · 入参是画布扁平列表（含 parentId / 坐标）；先按 parentId 重建树，再递归序列化。
 *  · 序列化依据积木的规范键（{@code key}）+ 结构化字段（args / children / retType / modifiers / name），
 *    不依赖显示文本，保证与 代码→积木 使用同一套词汇表，实现稳定互转。
 *  · 不支持的块降级为 raw.code 原样输出。
 */
public final class BlocksToCode {

    /** A-4：生成 / 排序 / 展平的递归深度上限，超限记日志并安全截断，防 StackOverflowError 穿透 */
    private static final int MAX_GEN_DEPTH = 64;

    private BlocksToCode() {}

    /** 画布扁平列表 → Java 源码（不含注释框；需带注释框请用 {@link #fromCanvas(List, List)}） */
    public static String fromCanvas(List<CLBC_Block> flat) {
        return fromCanvas(flat, Collections.emptyList());
    }

    /** 画布扁平列表 + 注释框 → Java 源码。
     *  #131：注释框按 y 坐标穿插进对应方法体，导出为 // 注释，与代码→积木方向的注释框往返闭环。 */
    public static String fromCanvas(List<CLBC_Block> flat, List<CodeToBlocks.CommentData> comments) {
        Map<Integer, CLBC_Block> byId = new HashMap<>();
        for (CLBC_Block b : flat) byId.put(b.id, b);
        // #165：注释锚点已存进 CommentData，构造排序上下文时按锚点块当前 y 定位，
        // 积木被拖动后注释仍紧跟「它前面那条语句」写回源码。
        Gen ctx = new Gen(comments, byId);

        // 清空瞬态子块引用，避免同一份 flat 列表被多次调用时 children 重复累加
        // （rebuildTree 每帧也会清空，这里与之一致；顺序无关，父/子各自清空自己的 children）
        for (CLBC_Block b : flat) {
            b.children.clear();
            if (b.hasSlots()) for (CLBC_Block.Slot s : b.slots) s.children.clear();
        }

        // 检测 parentId 自指与环：环上的块都命中 byId 而不进 roots，但下面只对 roots 递归序列化，
        // 环上整块会静默消失。这里把环上块降级为根，且不把它们挂为彼此的子块，
        // 既保证源码不丢，也避免生成时因互相成 child 而无限递归。正常数据路径行为不变。
        Set<Integer> onCycle = new HashSet<>();
        for (CLBC_Block b : flat) {
            if (b.parentId == b.id) { onCycle.add(b.id); continue; }
            Set<Integer> seen = new HashSet<>();
            seen.add(b.id);
            int cur = b.parentId;
            while (cur >= 0 && byId.containsKey(cur)) {
                if (cur == b.id) { onCycle.add(b.id); break; }
                if (!seen.add(cur)) break;
                cur = byId.get(cur).parentId;
            }
        }
        if (!onCycle.isEmpty()) {
            CLBC_Util.log("!!!!积木 parentId 存在自指或环，已将 " + onCycle.size()
                    + " 个块降级为根以避免源码丢失");
        }

        List<CLBC_Block> roots = new ArrayList<>();
        for (CLBC_Block b : flat) {
            if (onCycle.contains(b.id)) { roots.add(b); continue; }   // 环上块降级为根，不建立环内子关系
            if (b.parentId >= 0 && byId.containsKey(b.parentId)) {
                CLBC_Block p = byId.get(b.parentId);
                p.children.add(b);
                // 多口容器：按 slotIndex 把子块分回对应分支，否则 if/else 分支体会在转译时丢失
                if (p.hasSlots()) {
                    int si = Math.max(0, Math.min(p.slotCount() - 1, b.slotIndex));
                    CLBC_Block.Slot sl = p.slots.get(si);
                    if (!sl.children.contains(b)) sl.children.add(b);
                }
            } else {
                roots.add(b);
            }
        }
        sortChildren(roots, 0);

        // ⑨：把游离（不在声明内）的顶层积木挑出来写进尾部注释区，主体只保留结构化的
        List<CLBC_Block> bodyRoots = new ArrayList<>();
        List<CLBC_Block> orphanRoots = new ArrayList<>();
        for (CLBC_Block r : roots) {
            if (CLBC_OrphanTrailer.isOrphan(r)) orphanRoots.add(r);
            else bodyRoots.add(r);
        }

        StringBuilder sb = new StringBuilder();
        // A-4：生成递归兜底，残留的深层递归溢出都不许穿透到 UI，只保留已生成部分并记日志
        try {
            // 类声明隐式化：文件头块（unit.file / 旧 unit.class）携带 类名/修饰符/包名，导出时包回 class 包裹。
            // 画布上不再有 unit.package / unit.class / unit.import 帽子块；import 按实际用到的类自动增删。
            CLBC_Block header = null;
            for (CLBC_Block r : bodyRoots)
                if ("unit.file".equals(r.key) || "unit.class".equals(r.key)) { header = r; break; }
            if (header != null && "unit.class".equals(header.key)) {
                // 旧格式：类块自带 children，由 generate 自行输出包裹
                generate(header, 0, sb, ctx);
            } else {
                String pkg = "", className = "Main", mods = "public";
                if (header != null) {
                    if (header.args != null && header.args.size() > 2 && header.args.get(2) != null) pkg = header.args.get(2);
                    if (header.args != null && !header.args.isEmpty() && header.args.get(0) != null && !header.args.get(0).isEmpty()) className = header.args.get(0);
                    if (header.args != null && header.args.size() > 1 && header.args.get(1) != null && !header.args.get(1).isEmpty()) mods = header.args.get(1);
                }
                if (pkg != null && !pkg.isEmpty()) sb.append("package ").append(pkg).append(";\n\n");
                // import 按需自动增删：扫描整棵积木树，收集真正用到的类全限定名
                List<String> imps = collectImports(flat, pkg);
                for (String imp : imps) sb.append("import ").append(imp).append(";\n");
                if (!imps.isEmpty()) sb.append("\n");
                String kw = (header != null && "unit.interface".equals(header.key)) ? "interface"
                        : (header != null && "unit.enum".equals(header.key)) ? "enum" : "class";
                sb.append(mods).append(' ').append(kw).append(' ').append(className).append(" {\n");
                List<CLBC_Block> classBody = new ArrayList<>(bodyRoots);
                if (header != null) classBody.remove(header);
                // 旧版工程可能残留 unit.package / unit.import 积木：import 现已自动增删，这里直接忽略，
                // 不把它们当源码塞进类体（否则会生成非法文本）。
                classBody.removeIf(r -> "unit.package".equals(r.key) || "unit.import".equals(r.key));
                genBody(classBody, 0, sb, ctx);
                ctx.emitBefore(Double.MAX_VALUE, 0, sb);   // 类体尾部兜底冲刷，任何注释都不丢
                sb.append("}\n");
            }
            // ⑨：游离积木写回文件结尾（注释形式），保留积木结构、空行切分未连接组
            if (!orphanRoots.isEmpty()) {
                List<CLBC_Block> orphanFlat = new ArrayList<>();
                for (CLBC_Block r : orphanRoots) flattenOrphan(r, 0, orphanFlat);
                sb.append(CLBC_OrphanTrailer.write(orphanFlat, 0));
            }
        } catch (StackOverflowError e) {
            CLBC_Util.log("!!!! 积木→代码：生成时递归溢出，已保留已生成部分: " + e.getClass().getSimpleName());
        }
        return sb.toString();
    }

    /** ⑨：把游离子树展平为带 nestLevel 的列表（供 OrphanTrailer 保留结构、空行切分） */
    private static void flattenOrphan(CLBC_Block b, int depth, List<CLBC_Block> out) {
        b.nestLevel = depth;
        out.add(b);
        // A-4：深度保护，超限停止展开子树并记日志
        if (depth > MAX_GEN_DEPTH) {
            CLBC_Util.log("!!!! 积木→代码：游离积木展平深度超限(" + depth + ")，已停止展开子树");
            return;
        }
        for (CLBC_Block c : b.children) flattenOrphan(c, depth + 1, out);
    }

    /** 便捷：源码 → 积木 → 源码，供无界面逻辑校验互转稳定性（含 // 注释框往返） */
    public static String roundTrip(String code) {
        CodeToBlocks.ConvertResult r = CodeToBlocks.convert(code);
        return fromCanvas(r.blocks, r.comments);
    }

    private static void sortChildren(List<CLBC_Block> list, int depth) {
        // A-4：深度保护，超限停止递归排序并记日志
        if (depth > MAX_GEN_DEPTH) {
            CLBC_Util.log("!!!! 积木→代码：积木树排序深度超限(" + depth + ")，已停止递归排序");
            return;
        }
        list.sort(Comparator.comparingDouble(b -> b.y));
        for (CLBC_Block b : list) {
            if (b.hasSlots()) for (CLBC_Block.Slot s : b.slots) s.children.sort(Comparator.comparingDouble(c -> c.y));
            sortChildren(b.children, depth + 1);
        }
    }

    /** #131：按 y 坐标把注释框穿插进方法体——在每条子块之前，先把 y 更小、尚未输出的注释写出。
     *  #已知限制实修：末尾只做【局部冲刷】到「本层最后一个子块 y + 0.6」，不再一次冲到 MAX_VALUE。
     *  旧版每个容器都冲刷全部注释，多口容器（if/else、try/catch/finally）会把所有注释
     *  挤进第一个口，后面的口全空；现在注释按锚点 y 分散到各自所属分支，类体末尾另做全局兜底。 */
    private static void genBody(List<CLBC_Block> children, int indent, StringBuilder sb, Gen ctx) {
        for (CLBC_Block c : children) {
            ctx.emitBefore(c.y, indent, sb);          // 在该子块之前插入 y 更小的注释
            generate(c, indent + 1, sb, ctx);
        }
        double tail = children.isEmpty() ? -1d
                : children.get(children.size() - 1).y + 0.6d;
        ctx.emitBefore(tail, indent, sb);            // 只冲刷落在【本层内部】的注释
    }

    /** #131：注释生成上下文——按 y 排序的注释列表 + 已输出游标（DFS 共享，避免重复写出）。 */
    private static final class Gen {
        final List<CodeToBlocks.CommentData> comments;
        int cursor = 0;
        Gen(List<CodeToBlocks.CommentData> c, Map<Integer, CLBC_Block> byId) {
            comments = new ArrayList<>(c == null ? Collections.emptyList() : c);
            // #165：有锚点时以【锚点当前 y】为准（+0.5 落在锚点语句之后、下一条之前；
            // 开头注释的锚点是后继语句，用 -0.5 排在它之前）；没锚点才退回源码落点。
            for (CodeToBlocks.CommentData d : comments) {
                CLBC_Block a = d.anchorId >= 0 ? byId.get(d.anchorId) : null;
                if (a != null) d.orderY = d.anchorIsNext ? a.y - 0.5f : a.y + 0.5f;
            }
            comments.sort(Comparator.comparingDouble(CodeToBlocks.CommentData::sortY));
        }
        void emitBefore(double untilY, int indent, StringBuilder sb) {
            String pad = "    ".repeat(indent);
            while (cursor < comments.size() && comments.get(cursor).sortY() < untilY) {
                String t = comments.get(cursor).text == null ? "" : comments.get(cursor).text;
                String trimmed = t.trim();
                if (trimmed.startsWith("/*") && trimmed.contains("*/")) {
                    // #已知限制实修：块注释按原文回写（含 /* */、Javadoc 星号与内部换行），
                    // 逐行补缩进但不动正文，保证 /** ... */ 不退化成一串行注释。
                    for (String ln : t.split("\n", -1))
                        sb.append(pad).append(ln.stripTrailing()).append("\n");
                } else {
                    // #162：用户在注释框里按 Enter 产生的硬换行，导出时拆成多行 // 注释，
                    // 避免把换行符直接塞进源码（会生成非法 Java）。软换行不进 c.text，天然不受影响。
                    // 便签里若手写了未闭合的 /* 也走这里，避免生成未闭合块注释把后续代码吞掉。
                    for (String ln : t.split("\n", -1)) sb.append(pad).append("// ").append(ln).append("\n");
                }
                cursor++;
            }
        }
    }

    private static void generate(CLBC_Block b, int indent, StringBuilder sb, Gen ctx) {
        // A-4：indent 即递归深度，超限安全截断并记日志，不许 StackOverflowError 穿透
        if (indent > MAX_GEN_DEPTH) {
            CLBC_Util.log("!!!! 积木→代码：生成嵌套深度超限(" + indent + ")，已截断子树");
            sb.append("    ".repeat(MAX_GEN_DEPTH)).append("// [递归深度超限，子树已截断]\n");
            return;
        }
        String pad = "    ".repeat(indent);
        String key = b.key == null || b.key.isEmpty() ? "raw" : b.key;

        switch (key) {
            case "unit.class" -> {
                sb.append(pad).append(b.modifiers.isEmpty() ? "public" : b.modifiers)
                        .append(" class ").append(b.name == null ? "Main" : b.name).append(" {\n");
                genBody(b.children, indent, sb, ctx);
                sb.append(pad).append("}\n");
            }
            case "unit.file" -> { /* 隐式类：包裹已在 fromCanvas 中输出，此处跳过 */ }
            case "unit.interface" -> generateType(b, indent, sb, "interface", ctx);
            case "unit.enum" -> generateType(b, indent, sb, "enum", ctx);
            case "unit.method" -> {
                String ret = b.retType.isEmpty() ? "void" : b.retType;
                // 导入布局 4 参 [修饰符, 返回类型, 名称, 参数表]；
                // 定义表布局 3 参 [返回类型, 名称, 参数表]，参数表恒为最后一项。
                int last = b.args.size() - 1;
                String params = last >= 0 && b.args.get(last) != null ? b.args.get(last) : "";
                sb.append(pad).append(b.modifiers.isEmpty() ? "public" : b.modifiers).append(" ")
                        .append(ret).append(" ").append(b.name == null ? "run" : b.name)
                        .append("(").append(params).append(") {\n");
                genBody(b.children, indent, sb, ctx);
                sb.append(pad).append("}\n");
            }
            case "control.if" -> {
                if (b.hasSlots() && b.slotCount() > 1) {
                    // 多口：if → else if* → else，口数无上限（C / E / 任意长链）
                    for (int i = 0; i < b.slotCount(); i++) {
                        CLBC_Block.Slot sl = b.slots.get(i);
                        String cond = (i == 0) ? arg(b, 0, "true") : sl.expr;
                        if (i == 0) sb.append(pad).append("if (").append(cond).append(") {\n");
                        else if (cond == null || cond.trim().isEmpty()) sb.append(pad).append("} else {\n");
                        else sb.append(pad).append("} else if (").append(cond).append(") {\n");
                        genBody(b.slotChildren(i), indent, sb, ctx);
                    }
                    sb.append(pad).append("}\n");
                } else {
                    // 旧工程 / 单口：保持 control.else 子块形式，往返不丢
                    sb.append(pad).append("if (").append(arg(b, 0, "true")).append(") {\n");
                    List<CLBC_Block> thenB = new ArrayList<>(), elseB = new ArrayList<>();
                    for (CLBC_Block c : b.children) {
                        if ("control.else".equals(c.key)) elseB.addAll(c.children);
                        else thenB.add(c);
                    }
                    genBody(thenB, indent, sb, ctx);
                    if (!elseB.isEmpty()) {
                        sb.append(pad).append("} else {\n");
                        genBody(elseB, indent, sb, ctx);
                    }
                    sb.append(pad).append("}\n");
                }
            }
            case "control.while" -> {
                sb.append(pad).append("while (").append(arg(b, 0, "true")).append(") {\n");
                genBody(b.children, indent, sb, ctx);
                sb.append(pad).append("}\n");
            }
            case "control.for" -> {
                String[] p = arg(b, 0, "").split(";");
                String init = p.length > 0 ? p[0].trim() : "";
                String cond = p.length > 1 ? p[1].trim() : "";
                String upd = p.length > 2 ? p[2].trim() : "";
                sb.append(pad).append("for (").append(init).append("; ").append(cond).append("; ")
                        .append(upd).append(") {\n");
                genBody(b.children, indent, sb, ctx);
                sb.append(pad).append("}\n");
            }
            case "control.forEach" -> {
                String var = arg(b, 0, "x"), iter = arg(b, 1, "");
                sb.append(pad).append("for (var ").append(var).append(" : ").append(iter).append(") {\n");
                genBody(b.children, indent, sb, ctx);
                sb.append(pad).append("}\n");
            }
            // #已知限制实修：Java 异常处理语法硬编码回写。
            // 一个 control.try 多口容器 = 口0 尝试体 + 中间各口 捕获 + 末口 最终执行，
            // 按口顺序拼成 try (...) {..} catch (..) {..} finally {..}，一次写完不散架。
            case "control.try" -> {
                int n = b.hasSlots() ? b.slotCount() : 1;
                String res = b.hasSlots() ? b.slots.get(0).expr : "";
                CLBC_Block.Input rin = b.inputFor(0, 0);
                if (rin != null && !rin.code().trim().isEmpty()) res = rin.code();
                boolean hasRes = res != null && !res.trim().isEmpty();
                sb.append(pad).append("try");
                if (hasRes) sb.append(" (").append(res.trim()).append(")");
                sb.append(" {\n");
                genBody(b.slotChildren(0), indent, sb, ctx);
                sb.append(pad).append("}");
                for (int i = 1; i < n; i++) {
                    CLBC_Block.Slot sl = b.hasSlots() ? b.slots.get(i) : null;
                    String label = sl == null || sl.label == null ? "" : sl.label;
                    boolean fin = label.contains("最终") || label.toLowerCase().contains("finally");
                    if (fin) {
                        sb.append(" finally {\n");
                        genBody(b.slotChildren(i), indent, sb, ctx);
                    } else {
                        String param = sl == null ? "" : sl.expr;
                        CLBC_Block.Input cin = b.inputFor(i, 0);
                        if (cin != null && !cin.code().trim().isEmpty()) param = cin.code();
                        if (param == null || param.trim().isEmpty()) param = "Exception e";
                        sb.append(" catch (").append(param.trim()).append(") {\n");
                        genBody(b.slotChildren(i), indent, sb, ctx);
                    }
                    sb.append(pad).append("}");
                }
                // 普通 try（无资源）必须至少跟一个 catch 或 finally，否则 Java 语法非法。
                // 用户从工具箱拖出只带 1 口的 try 时补一个空捕获，保证生成代码可编译。
                if (n < 2 && !hasRes) sb.append(" catch (Exception e) {\n").append(pad).append("}");
                sb.append("\n");
            }
            case "data.var" -> {
                String type = arg(b, 0, "Object"), name = arg(b, 1, "x"), init = arg(b, 2, "");
                sb.append(pad).append(type).append(" ").append(name);
                if (!init.isEmpty()) sb.append(" = ").append(init);
                sb.append(";\n");
            }
            // #253：new Thread(() -> { … }) 的 C 口容器积木 → 拼回完整构造器 + 回调体。
            // args: [声明类型, 变量名, 构造类型, lambda 参数, 回调体外剩余实参]；
            // slot0.children = 回调体语句。显式补 '}' + ')' + ';'，杜绝 lambda 的 "});" 丢失。
            case "data.newCallback" -> {
                String declType = arg(b, 0, "Thread"), varName = arg(b, 1, "");
                String ctorType = arg(b, 2, "Thread"), lambdaParams = arg(b, 3, "()");
                String tailArgs = arg(b, 4, "");
                if (lambdaParams.isEmpty()) lambdaParams = "()";
                if (!lambdaParams.startsWith("(")) lambdaParams = "(" + lambdaParams + ")";
                if (!declType.isEmpty() && !varName.isEmpty())
                    sb.append(pad).append(declType).append(" ").append(varName).append(" = ");
                else if (!varName.isEmpty())
                    sb.append(pad).append(varName).append(" = ");
                else sb.append(pad);
                sb.append("new ").append(ctorType).append("(").append(lambdaParams).append(" -> {\n");
                genBody(b.hasSlots() ? b.slotChildren(0) : b.children, indent + 1, sb, ctx);
                sb.append(pad).append("})");
                if (tailArgs != null && !tailArgs.trim().isEmpty()) {
                    String t = tailArgs.trim();
                    sb.append(t.startsWith(",") ? t : (", " + t));
                }
                sb.append(");\n");
            }
            case "data.assign" -> sb.append(pad).append(arg(b, 0, "")).append(" = ")
                    .append(arg(b, 1, "")).append(";\n");
            case "data.return" -> {
                String e = arg(b, 0, "");
                sb.append(pad).append("return").append(e.isEmpty() ? ";" : " " + e + ";").append("\n");
            }
            case "ops.call" -> {
                String cs = arg(b, 0, ""), mn = arg(b, 1, "");
                // 防御性双保险：若积木带 lib 元数据，复核方法是否仍存在且安全（无毒）
                String metaArg = b.args != null && b.args.size() > 2 ? b.args.get(2) : null;
                if (metaArg != null && metaArg.startsWith("lib:") && metaArg.length() > 4) {
                    String meta = metaArg.substring(4);   // fqn#method
                    int h = meta.indexOf('#');
                    if (h > 0) {
                        String f = meta.substring(0, h), m = meta.substring(h + 1);
                        if (!CLBC_BlockLibrary.isSafe(f, m)) {
                            b.invalid = true;   // 渲染层标红：该方法已被安全策略禁用
                            CLBC_Util.logFine("调用被安全策略拦截: " + f + "#" + m);
                            return;             // 不安全：跳过生成该语句，杜绝生成危险代码
                        }
                    }
                }
                // #已知限制实修：args[3]="recv:<接收者原文>" 让未显式 import 的 java.lang 调用
                // （System.out.println）、局部变量调用（list.add）、链式调用都能保真还原。
                // 用户在类下拉里改选了别的类时以新选值为准，不再硬套旧接收者。
                String recv = null;
                if (b.args != null && b.args.size() > 3 && b.args.get(3) != null
                        && b.args.get(3).startsWith("recv:")) recv = b.args.get(3).substring(5);
                String recvFirst = recv == null || recv.isEmpty() ? ""
                        : (recv.contains(".") ? recv.substring(0, recv.indexOf('.')) : recv);
                boolean recvEdited = recv != null && !recv.isEmpty() && !cs.equals(recvFirst);
                String callHead = (recv != null && !recv.isEmpty() && !recvEdited) ? recv + "." + mn
                        : (cs.isEmpty() ? mn : cs + "." + mn);
                CLBC_BlockDefinition def = CLBC_BlockLibrary.findBlock(cs, mn);
                if (def == null) {
                    // 降级：拼回 类名.方法(参数, 回调…)
                    sb.append(pad).append(callHead).append("(");
                    emitCallArgsFallback(b, sb, ctx);
                    sb.append(");\n");
                    return;
                }
                // 反射签名参数可能比源码实参多（变参 / 重载选中了不同签名）。
                // 旧实现硬按 def.params.size() 拼，空槽会吐出 ", )" → javac 报「非法的表达式开始」。
                // 现在只按积木实际输入口收集非空参数文本，再 join。
                List<clbc.blocks.CLBC_BlockLibrary.CallParam> plan = b.callParamPlan();
                List<String> parts = new ArrayList<>();
                for (int j = 0; j < plan.size(); j++) {
                    clbc.blocks.CLBC_BlockLibrary.CallParam p = plan.get(j);
                    String v = p.functional ? emitCallback(b, p.slotIndex, ctx)
                                            : inputCodeByOrdinal(b, p.inlineOrdinal);
                    if (v != null && !v.trim().isEmpty()) parts.add(v);
                }
                sb.append(pad).append(callHead).append("(")
                  .append(String.join(", ", parts)).append(");\n");
            }
            case "expr.number", "expr.string", "expr.bool" ->
                    sb.append(pad).append(arg(b, 0, "")).append("\n");
            // #164：库字段报告块。它只能作为表达式嵌进输入窗口（CLBC_Block.inputCode 走 b.expr），
            // 悬空放在语句位置按规则忽略，绝不生成 "Math.PI;" 这种非法 Java 语句。
            case "ops.field" -> CLBC_Util.logFine("积木→代码：字段报告块 " + key
                    + " 未插入输入窗口，按规则忽略（不生成非法表达式语句）");
            // 捕获 / 最终执行只作为 control.try 的口存在。旧工程里若被单独摆放，
            // 按注释保真写出，绝不生成孤立的 catch(...){ } 这种非法 Java 语句。
            case "control.catch", "control.finally" ->
                    sb.append(pad).append("// [").append(key).append(" 需挂在 try 容器内] ")
                      .append(b.args == null ? "" : String.join(" ", b.args).replace("\n", " "))
                      .append("\n");
            default -> {
                // #164：模板统一来源 = 程序化登记器；纯语法表是它的内置实现。
                // 任何运行时 register()/registerClass() 加进来的自定义块，都能在这里拿回 Java 模板。
                clbc.blocks.CLBC_CoreSyntax.Def d = clbc.blocks.CLBC_CoreSyntax.def(key);
                String tpl = d != null ? d.code : CLBC_BlockRegistry.templateOf(key);
                String kind = d != null ? d.kind : CLBC_BlockRegistry.kindOf(key);
                if (kind == null) kind = b.kind;
                if (tpl != null) {
                    for (int i = 0; i < 4; i++) tpl = tpl.replace("{" + i + "}", arg(b, i, ""));
                    int open = tpl.indexOf("{\n");
                    boolean container = "cblock".equals(kind) || "class".equals(kind)
                            || "method".equals(kind);
                    if (container && open >= 0) {
                        sb.append(pad).append(tpl, 0, open + 1).append("\n");
                        genBody(b.children, indent, sb, ctx);
                        sb.append(pad).append(tpl.substring(open + 2).replace("\n", "\n" + pad));
                    } else if ("round".equals(kind) || "hex".equals(kind)) {
                        String e = CLBC_Block.inputCode(b);
                        if (CLBC_BlockRegistry.isJavaExpressionStatement(e)) {
                            sb.append(pad).append(e).append(";\n");
                        } else {
                            // 悬空报告块不产生语句；插进输入窗口时才由 inputCode 生成表达式
                            CLBC_Util.logFine("积木→代码：报告块 " + key
                                    + " 未插入输入窗口，按规则忽略（避免生成非法表达式语句）");
                        }
                    } else {
                        sb.append(pad).append(tpl.replace("\n", "\n" + pad));
                    }
                } else if (!b.expr.isEmpty()) {
                    sb.append(pad).append(b.expr).append("\n");
                } else if (!b.text.isEmpty()) {
                    sb.append(pad).append(b.text.replace("\n", " ")).append("\n");
                } else {
                    // A-4：无任何可用内容的块（关键失败分支）留痕，避免静默丢源码
                    CLBC_Util.logFine("积木→代码：块 " + b.key + " 无模板/文本/表达式，已跳过（源码可能不完整）");
                }
            }
        }
    }

    private static String arg(CLBC_Block b, int i, String dflt) {
        // 内嵌输入窗口是统一来源：窗口里嵌入的报告块 / 直接输入的文本都参与生成代码
        CLBC_Block.Input in = b.inputFor(0, i);
        if (in != null) return in.code();
        if (b.args != null && b.args.size() > i && b.args.get(i) != null) return b.args.get(i);
        return dflt;
    }

    /** 按 ordinal 读取 ops.call 顶层内嵌口（非函数式参数）的代码文本 */
    private static String inputCodeByOrdinal(CLBC_Block b, int ordinal) {
        CLBC_Block.Input in = b.inputFor(0, ordinal);
        return in == null ? "" : in.code();
    }

    /** 还原一个函数式参数的回调体（C 口子块）：单条 raw.code → 直接输出其源码；
     *  多条语句 → 包成 { … }；空口 → 空 lambda。 */
    private static String emitCallback(CLBC_Block b, int slot, Gen ctx) {
        if (b.slots == null || slot < 0 || slot >= b.slots.size()) return "() -> {}";
        List<CLBC_Block> kids = b.slots.get(slot).children;
        if (kids.isEmpty()) return "() -> {}";
        if (kids.size() == 1 && "raw.code".equals(kids.get(0).key))
            return (kids.get(0).args != null && !kids.get(0).args.isEmpty())
                    ? kids.get(0).args.get(0) : "() -> {}";
        StringBuilder sb2 = new StringBuilder("{\n");
        genBody(kids, 1, sb2, ctx);
        sb2.append("}");
        return sb2.toString();
    }

    /** 未命中库的 ops.call 降级拼参（按当前输入框/槽顺序） */
    private static void emitCallArgsFallback(CLBC_Block b, StringBuilder sb, Gen ctx) {
        List<clbc.blocks.CLBC_BlockLibrary.CallParam> plan = b.callParamPlan();
        List<String> parts = new ArrayList<>();
        for (int j = 0; j < plan.size(); j++) {
            clbc.blocks.CLBC_BlockLibrary.CallParam p = plan.get(j);
            String v = p.functional ? emitCallback(b, p.slotIndex, ctx)
                                    : inputCodeByOrdinal(b, p.inlineOrdinal);
            if (v != null && !v.trim().isEmpty()) parts.add(v);
        }
        sb.append(String.join(", ", parts));
    }

    /** interface / enum 头块（隐式类场景下的嵌套类型）包裹输出 */
    private static void generateType(CLBC_Block b, int indent, StringBuilder sb, String kw, Gen ctx) {
        String pad = "    ".repeat(indent);
        String mods = b.modifiers == null || b.modifiers.isEmpty() ? "public" : b.modifiers;
        sb.append(pad).append(mods).append(' ').append(kw).append(' ')
                .append(b.name == null ? "" : b.name).append(" {\n");
        genBody(b.children, indent, sb, ctx);
        sb.append(pad).append("}\n");
    }

    /** 扫描整棵积木树，收集实际用到的类全限定名（用于按需自动写 import） */
    private static List<String> collectImports(List<CLBC_Block> flat, String pkg) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (CLBC_Block b : flat) collectImportsFrom(b, set);
        List<String> out = new ArrayList<>();
        for (String f : set) {
            if (f == null || f.isEmpty()) continue;
            if (f.startsWith("java.lang.")) continue;                 // java.lang 免 import
            if (pkg != null && !pkg.isEmpty() && f.startsWith(pkg + ".")) continue; // 同包免 import
            int last = f.lastIndexOf('.');
            String simple = last >= 0 ? f.substring(last + 1) : f;
            if (isPrimitive(simple)) continue;
            out.add(f);
        }
        Collections.sort(out);
        return out;
    }

    private static void collectImportsFrom(CLBC_Block b, LinkedHashSet<String> set) {
        if (b == null) return;
        // ops.call 的 lib 元数据里藏着调用方的全限定类名
        if ("ops.call".equals(b.key) && b.args != null && b.args.size() > 2) {
            String metaArg = b.args.get(2);
            // #实测修复：旧实现误取 args[1]（方法名）却按 lib: 元数据 substring(4)，
            // 方法名短于 4 字符时抛 Range [4,3)，导致整份积木→代码反向生成失败、代码面板永不刷新。
            String meta = (metaArg != null && metaArg.startsWith("lib:") && metaArg.length() > 4)
                    ? metaArg.substring(4) : null;
            if (meta != null) {
                int h = meta.indexOf('#');
                if (h > 0) set.add(meta.substring(0, h));
            }
        }
        // data.new / data.get / 字面量：若类型名（去泛型/参数）命中已入库类，补全全限定名
        if (b.key != null && (b.key.equals("data.new") || b.key.equals("data.get") || b.key.startsWith("lit."))) {
            if (b.args != null) for (String a : b.args) {
                if (a == null) continue;
                String f = CLBC_BlockLibrary.resolveFqn(stripSimple(a));
                if (f != null) set.add(f);
            }
        }
        if (b.children != null) for (CLBC_Block c : b.children) collectImportsFrom(c, set);
        if (b.hasSlots()) for (CLBC_Block.Slot s : b.slots) for (CLBC_Block c : s.children) collectImportsFrom(c, set);
        for (CLBC_Block.Input in : b.inputs) if (in.child != null) collectImportsFrom(in.child, set);
    }

    /** 从 "Type args" / "pkg.Type<Gen> x" 等文本里抠出最右的简单类名 */
    private static String stripSimple(String s) {
        if (s == null) return "";
        int p = s.indexOf('('); if (p >= 0) s = s.substring(0, p);
        int sp = s.indexOf(' '); if (sp >= 0) s = s.substring(0, sp);
        int lt = s.indexOf('<'); if (lt >= 0) s = s.substring(0, lt);
        int dot = s.lastIndexOf('.');
        return dot >= 0 ? s.substring(dot + 1) : s;
    }

    private static boolean isPrimitive(String s) {
        return s.equals("int") || s.equals("long") || s.equals("short") || s.equals("byte")
                || s.equals("char") || s.equals("boolean") || s.equals("float")
                || s.equals("double") || s.equals("void") || s.equals("String");
    }
}
