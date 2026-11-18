package clbc.blocks;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import clbc.ui.CLBC_Block;
import clbc.ui.CLBC_Toolbox;
import clbc.blocks.CLBC_BlockDefinition;
import clbc.blocks.jparser.JAst;
import clbc.blocks.jparser.JLexer;
import clbc.blocks.jparser.JParser;
import clbc.util.CLBC_Util;

/**
 * 代码→积木 转换器（P0② 双向之一）。
 * 走【自研纯 JDK 解析器 JParser】（零第三方依赖，替代 javaparser），生成结构化
 * {@link CLBC_Block} 树（带规范键 / 参数 / 子块 / 类型 / 修饰符）。
 * 显示文本取自语言文件 {@link CLBC_Lang}；最后扁平化（绝对坐标 + parentId）交给画布 importBlocks 落盘。
 *
 * 流程对应整改 ⑥→⑩：
 *   ⑦ 若源码尾部含“游离代码注释区”（{@link CLBC_OrphanTrailer}），先剥离主体、再单独还原为游离积木；
 *   ⑧ 主体走自研解析器识别代码、布置积木（类/方法/字段/控制流/调用/变量/赋值/返回）；
 *   ⑨ 不在声明内的代码（RAW）保留积木结构，导出时写回文件结尾注释区（往返闭环见 BlocksToCode）。
 *
 * 覆盖 Java 核心子集；不支持的语法降级为 raw.code 块，绝不丢信息（错误定位只走日志）。
 */
public final class CodeToBlocks {

    private static int counter = 1; // id 分配（import 时画布会重映射）

    /** A-4：语句 / 积木树嵌套深度上限，超限降级保留原文并记日志，防 StackOverflowError 穿透 */
    private static final int MAX_LEVEL = 64;
    /** A-3：本次 convert 里无法还原为语法积木、仍降级 raw.code 的语句数（末尾汇总记一条日志，避免刷屏） */
    private static int degradedCount = 0;

    private CodeToBlocks() {}

    /** #131/#165：代码里的 // 注释 → 画布上独立注释便利贴框所需的数据。
     *  · (x,y) 是 flatten 落点，只作导入时便签初始位置参考，导入后会摆到锚点右侧空白处；
     *  · anchorId 是【源码顺序上紧挨在注释之前的那条语句积木】的 id（导入时按 id 重映射表换算），
     *    注释框固定连它，不再靠几何最近块猜（用户明确要求「注释要连这个注释前的那个积木」）；
     *  · orderY 是注释在源码里的原始顺序位置，积木→代码按它穿插回写，
     *    用户把便签拖到别处也不会改写源码里注释的位置。 */
    public static final class CommentData {
        public final float x, y, w, h;
        public final String text;
        public int anchorId = -1;
        public float orderY = Float.NaN;
        /** true = 注释落在开头、没有前驱语句，锚点是紧随其后的那条语句（回写时注释要排在它前面） */
        public boolean anchorIsNext = false;
        public CommentData(float x, float y, String text) {
            this.x = x; this.y = y; this.w = 210; this.h = 76; this.text = text;
            this.orderY = y;
        }
        public CommentData(float x, float y, float w, float h, String text, int anchorId, float orderY) {
            this.x = x; this.y = y; this.w = w; this.h = h; this.text = text;
            this.anchorId = anchorId; this.orderY = orderY;
        }
        /** 回写排序用：优先源码顺序，未设时退回落点 y。 */
        public float sortY() { return Float.isFinite(orderY) ? orderY : y; }
    }

    /** #131：convert 的返回值——积木块 + 独立注释框分两套数据结构，互不污染。 */
    public static final class ConvertResult {
        public final List<CLBC_Block> blocks;
        public final List<CommentData> comments;
        public ConvertResult(List<CLBC_Block> blocks, List<CommentData> comments) {
            this.blocks = blocks;
            this.comments = comments;
        }
    }

    /** 入口：源码 → 扁平积木列表 + 独立注释框数据 */
    public static ConvertResult convert(String code) {
        if (code == null) code = "";
        degradedCount = 0;   // A-3：本次解析的降级计数归零
        // ⑦：先还原尾部游离代码注释区的积木（往返闭环），再剥离它得到纯主体
        List<CLBC_Block> trailer = CLBC_OrphanTrailer.read(code, 1_000_000);
        String body = CLBC_OrphanTrailer.strip(code);

        List<CLBC_Block> roots = new ArrayList<>();
        try {
            JAst.JRoot root = JParser.parse(body);
            scanLibraries(root);   // ③-1 通用化库识别：扫描 import 的类 / 通配包常见类
            // 类声明改为「隐式」：文件即类，画布不再画 unit.package / unit.class 帽子块；
            // 只放一个隐藏的 unit.file 头块携带 (类名, 修饰符, 包名)，导出时由 BlocksToCode 包回 class 包裹，
            // 用户画布上看不到任何声明帽子。import 也不再是积木——导出时按实际用到的类自动增删。
            boolean fileHeaderDone = false;
            for (JAst.JType type : root.types) {
                if (!fileHeaderDone) {
                    CLBC_Block file = block("unit.file", "结构",
                            new ArrayList<>(List.of(type.name, JParser.classModifiers(type.name),
                                    root.pkg == null ? "" : root.pkg)), 0);
                    file.hidden = true;
                    file.name = type.name;
                    roots.add(file);
                    fileHeaderDone = true;
                }
                roots.addAll(convertTypeFlat(type, 0));
            }
            if (!fileHeaderDone)
                CLBC_Util.logFine("代码→积木：未识别到任何类型定义（可能只有包级语句 / 纯表达式）");
            // 顶层游离原文（不在任何声明内的代码）→ orphan.code 积木，保留信息不丢
            for (String raw : root.orphans) {
                if (!raw.isEmpty())
                    roots.add(block("orphan.code", "结构", new ArrayList<>(List.of(raw)), 0));
            }
            CLBC_Util.log("代码→积木：解析出 " + roots.size() + " 个顶层结构" + (trailer.isEmpty() ? "" : "，并还原 " + trailer.size() + " 个游离积木"));
        } catch (Exception | StackOverflowError e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            CLBC_Util.log("!!!! 代码解析失败（含深层嵌套溢出），降级为原生代码块: " + msg);
            CLBC_Block raw = block("raw.code", "结构", new ArrayList<>(List.of(body.replace("\n", " ⏎ "))), 0);
            roots.add(raw);
        }

        // 扁平化主体
        List<CLBC_Block> flat = flatten(roots, 40, 40);
        // ⑨：合并尾部还原的游离积木（已带 parentId / nestLevel / x,y），排在主体之后
        flat.addAll(trailer);
        // A-3/A-4：降级汇总（稀疏日志，不逐条刷屏）
        if (degradedCount > 0)
            CLBC_Util.logFine("代码→积木：有 " + degradedCount + " 条语句无法还原为语法积木，已降级 raw.code 保留原文");
        // #131/#165：把 // 注释从内联 core.comment 积木拆成独立注释便利贴框。
        // 扁平列表顺序就是源码顺序，扫描时记住「注释前面最近的那条语句积木」，把 id 直接写进
        // CommentData 当连线锚点；画布不再用几何距离猜该连谁。
        List<CLBC_Block> outBlocks = new ArrayList<>(flat.size());
        List<CommentData> outComments = new ArrayList<>();
        int prevStatementId = -1;
        for (CLBC_Block b : flat) {
            if ("core.comment".equals(b.key)) {
                String t = (b.args != null && !b.args.isEmpty() && b.args.get(0) != null) ? b.args.get(0) : "";
                outComments.add(new CommentData(b.x, b.y, 210, 76, t, prevStatementId, b.y));
            } else {
                outBlocks.add(b);
                String k = b.key == null ? "" : b.key;
                if (!b.hidden && !k.startsWith("unit.") && !"orphan.code".equals(k)
                        && !"raw.code".equals(k)) prevStatementId = b.id;
            }
        }
        // 注释落在文件 / 方法体开头（前面没有语句）时，退而绑定紧随其后的第一条语句，
        // 并标记 anchorIsNext，回写时注释要排在这条语句之前。
        for (CommentData cd : outComments) {
            if (cd.anchorId >= 0) continue;
            for (CLBC_Block b : outBlocks) {
                String k = b.key == null ? "" : b.key;
                if (b.hidden || k.startsWith("unit.")) continue;
                if (b.y > cd.sortY()) { cd.anchorId = b.id; cd.anchorIsNext = true; break; }
            }
        }
        // #已知限制实修：兜底不再只用正则扫 //，改为调用 JLexer 程序化扫描源码里【全部】
        // 行注释与块注释 token（词法层已正确区分字符串字面量里的 //、跨行块注释、Javadoc）。
        // 与已生成的便签做差集，剩下的按源码字符位置补建，锚点取它前面最近的那条语句积木。
        // 这样「调用参数 / lambda / 方法体切分」里被解析器跳过的注释也不会丢。
        java.util.List<JLexer.Token> missing = new java.util.ArrayList<>();
        for (JLexer.Token tk : JLexer.tokenize(code))
            if (tk.type == JLexer.T.COMMENT || tk.type == JLexer.T.BLOCK_COMMENT) missing.add(tk);
        for (CommentData cd : outComments) {
            String want = normText(cd.text);
            for (int i = 0; i < missing.size(); i++) {
                if (normText(missing.get(i).text).equals(want)) { missing.remove(i); break; }
            }
        }
        for (JLexer.Token tk : missing) {
            String t = tk.text;
            // 已经被 raw.code 原文保真的注释（典型＝安全策略降级标注）不再补便签：
            // 否则积木→代码时「raw 原文 + 便签」各输出一次，往返凭空多一条注释。
            boolean alreadyInRaw = false;
            for (CLBC_Block b : outBlocks) {
                if (b.args == null || !"raw.code".equals(b.key)) continue;
                for (String a : b.args)
                    if (a != null && !t.trim().isEmpty() && a.contains(t.trim())) { alreadyInRaw = true; break; }
                if (alreadyInRaw) break;
            }
            if (alreadyInRaw) continue;
            // BLOCK_COMMENT 的 pos 指向 /* 起始；COMMENT 的 pos 指向 // 之后，回退 2 格取 // 起始
            int cp = tk.type == JLexer.T.BLOCK_COMMENT ? tk.pos : Math.max(0, tk.pos - 2);
            CLBC_Block anchor = null;
            int anchorPos = -1;
            for (CLBC_Block b : outBlocks) {
                String k = b.key == null ? "" : b.key;
                if (b.hidden || k.startsWith("unit.")) continue;
                int p = sourcePosOf(b, code);
                if (p >= 0 && p <= cp && p > anchorPos) { anchorPos = p; anchor = b; }
            }
            int aid = anchor == null ? -1 : anchor.id;
            float cy = anchor == null ? 40f : anchor.y;   // y 与锚点同源，避免「注释落在锚点之前」的假象
            boolean next = false;
            if (aid < 0) {
                for (CLBC_Block b : outBlocks) {
                    String k = b.key == null ? "" : b.key;
                    if (b.hidden || k.startsWith("unit.")) continue;
                    aid = b.id; cy = b.y; next = true; break;
                }
            }
            CommentData cd = new CommentData(40, cy, 210, 76, t, aid, Float.NaN);
            cd.anchorIsNext = next;
            outComments.add(cd);
        }
        return new ConvertResult(outBlocks, outComments);
    }

    private static String normText(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "");
    }

    /** 估算积木在源码中的字符位置：用于把「被解析器跳过的注释」绑到它前面最近的语句积木。 */
    private static int sourcePosOf(CLBC_Block b, String code) {
        int best = Integer.MAX_VALUE;
        if (b.args != null) for (String a : b.args) {
            if (a == null || a.length() < 3) continue;
            int p = code.indexOf(a);
            if (p >= 0 && p < best) best = p;
        }
        if (b.name != null && b.name.length() >= 3) {
            int p = code.indexOf(b.name);
            if (p >= 0 && p < best) best = p;
        }
        return best == Integer.MAX_VALUE ? -1 : best;
    }

    /** 把一个类型平铺成「字段 + 方法」积木列表（不再包 unit.class 帽子，类声明改为隐式）。
     *  返回的块都是顶层 roots，画布上直接呈现方法/字段，无需声明帽子。 */
    private static List<CLBC_Block> convertTypeFlat(JAst.JType type, int level) {
        // 收口4：JParser.parseType 记录了顶层类型关键字，按 kind 还原对应结构积木（class／interface／enum）
        List<CLBC_Block> out = new ArrayList<>();
        String typeKey = "interface".equals(type.kind) ? "unit.interface"
                : "enum".equals(type.kind) ? "unit.enum" : "unit.class";
        // interface/enum 仍需一个隐藏头块携带类型关键字（导出时恢复 interface/enum 写法）；class 用 unit.file 已带。
        if (!"unit.class".equals(typeKey)) {
            CLBC_Block hdr = block(typeKey, "结构", new ArrayList<>(List.of(type.name)), level);
            hdr.hidden = true;
            hdr.modifiers = JParser.classModifiers(type.name);
            hdr.name = type.name;
            out.add(hdr);
        }
        for (JAst.JField f : type.fields) {
            if (f.name == null || f.name.isEmpty()) continue;
            out.add(block("data.var", "数据",
                    new ArrayList<>(List.of(f.type, f.name, f.init == null ? "" : f.init)), level + 1));
        }
        for (JAst.JMethod m : type.methods) {
            String params = m.params == null ? "" : m.params;
            // 收口4：无返回类型 = 构造方法，还原 unit.constructor（此前被当 unit.method 生成 `void 名()`，语义错）
            boolean ctor = m.retType == null || m.retType.isEmpty();
            CLBC_Block mb;
            if (ctor) {
                mb = block("unit.constructor", "结构",
                        new ArrayList<>(List.of(m.name, params)), level + 1);
                mb.kind = "method";
            } else {
                mb = block("unit.method", "结构",
                        new ArrayList<>(List.of(m.modifiers, m.retType, m.name, params)), level + 1);
            }
            mb.modifiers = m.modifiers;
            mb.retType = m.retType;
            mb.name = m.name;
            if (m.hasBody) {
                for (JAst.JStmt s : m.body) convertStatement(s, mb, level + 2);
            }
            out.add(mb);
        }
        return out;
    }

    private static void convertStatement(JAst.JStmt s, CLBC_Block parent, int level) {
        // A-4：深层嵌套保护，超限降级为 raw.code 保留原文并记日志，不许 StackOverflowError 穿透
        if (level > MAX_LEVEL) {
            degradedCount++;
            CLBC_Util.log("!!!! 代码→积木：语句嵌套深度超限(" + level + ")，已降级保留原文");
            parent.children.add(block("raw.code", "结构",
                    new ArrayList<>(List.of(s.raw == null ? "" : s.raw)), level));
            return;
        }
        switch (s.kind) {
            case BLOCK -> {
                // A-4：BLOCK 链同样计入深度，避免 {{{{…}}}} 无限递归
                for (JAst.JStmt c : s.body) convertStatement(c, parent, level + 1);
            }
            case IF -> {
                CLBC_Block b = block("control.if", "结构",
                        new ArrayList<>(List.of(s.cond == null ? "" : s.cond)), level);
                for (JAst.JStmt c : s.thenBody) convertStatement(c, b, level + 1);
                // else / else-if 链 → 依次追加口（C=1, E=2, 任意 N 口）
                List<JAst.JStmt> cur = s.elseBody;
                List<CLBC_Block.Slot> extra = new ArrayList<>();
                while (cur != null && !cur.isEmpty()) {
                    JAst.JStmt only = cur.size() == 1 ? cur.get(0) : null;
                    boolean chain = only != null && only.kind == JAst.JStmt.K.IF;
                    CLBC_Block.Slot sl = chain
                            ? new CLBC_Block.Slot("否则如果", only.cond)
                            : new CLBC_Block.Slot("否则", "");
                    CLBC_Block holder = block("raw", "结构", new ArrayList<>(), level + 1);
                    holder.children = sl.children;
                    List<JAst.JStmt> body = chain ? only.thenBody : cur;
                    for (JAst.JStmt c : body) convertStatement(c, holder, level + 2);
                    extra.add(sl);
                    cur = chain ? only.elseBody : null;
                }
                b.ensureSlots(1);                     // then 体收进第 0 口
                b.slots.get(0).label = b.text;
                b.slots.get(0).expr = s.cond == null ? "" : s.cond;
                for (CLBC_Block.Slot sl : extra) b.slots.add(sl);
                bindSlotInputs(b);                    // 每口条件 → 六边形布尔内嵌窗口
                b.syncChildrenUnion();
                parent.children.add(b);
            }
            case WHILE -> {
                CLBC_Block b = block("control.while", "结构",
                        new ArrayList<>(List.of(s.cond == null ? "" : s.cond)), level);
                for (JAst.JStmt c : s.body) convertStatement(c, b, level + 1);
                b.ensureSlots(1);
                b.slots.get(0).label = b.text;
                b.slots.get(0).expr = s.cond == null ? "" : s.cond;
                bindSlotInputs(b);
                b.syncChildrenUnion();
                parent.children.add(b);
            }
            // 收口4：do-while 此前与 while 合并、误还原成 control.while（丢 do 语义）；
            // JParser 已把 DO 解析成独立 JStmt.K.DO（含 s.cond／s.body），这里还原为 control.do。
            case DO -> {
                CLBC_Block b = block("control.do", "结构",
                        new ArrayList<>(List.of(s.cond == null ? "" : s.cond)), level);
                for (JAst.JStmt c : s.body) convertStatement(c, b, level + 1);
                parent.children.add(b);
            }
            case FOR -> {
                String init = s.init == null ? "" : s.init;
                String cond = s.cond == null ? "" : s.cond;
                String upd = s.update == null ? "" : s.update;
                String header = init + " ; " + cond + " ; " + upd;
                CLBC_Block b = block("control.for", "结构",
                        new ArrayList<>(List.of(header)), level);
                // 语言模板把 {0} 拼进了标签文字，参数本身又由内嵌输入窗口承载：
                // 不改成空参数标签，同一段 for 头会在积木上「文字 + 窗口」重复显示两遍。
                b.text = CLBC_Lang.text("control.for", new ArrayList<>(List.of(""))).trim();
                // 三段式 for 头不是单个表达式，别让表达式识别器往窗口里塞报告积木
                CLBC_Block.Input fi = b.inputFor(0, 0);
                if (fi != null) fi.child = null;
                for (JAst.JStmt c : s.body) convertStatement(c, b, level + 1);
                b.ensureSlots(1);
                b.slots.get(0).label = b.text;
                b.slots.get(0).expr = header;
                bindSlotInputs(b);
                b.syncChildrenUnion();
                parent.children.add(b);
            }
            case FOREACH -> {
                CLBC_Block b = block("control.forEach", "结构",
                        new ArrayList<>(List.of(s.var, s.iterable == null ? "" : s.iterable)), level);
                b.text = CLBC_Lang.text("control.forEach",
                        new ArrayList<>(List.of("", ""))).trim();
                for (JAst.JStmt c : s.body) convertStatement(c, b, level + 1);
                b.ensureSlots(1);                 // 先收子块再建口，ensureSlots 会把 children 搬进 slot
                b.slots.get(0).label = b.text;
                b.slots.get(0).expr = s.iterable == null ? "" : s.iterable;
                bindSlotInputs(b);
                b.syncChildrenUnion();
                parent.children.add(b);
            }
            // #已知限制实修：try/catch/finally 硬编码为【一个多口容器积木】。
            // 口 0=尝试体，中间各口=捕获（expr 存 "Type e" 参数原文），末口=最终执行。
            // 之所以不拆成独立 control.catch / control.finally 语句块：Java 要求它们紧接 try，
            // 独立块被拖开就会生成孤立非法的 catch(...); 整组一个积木才能保证语义与往返。
            case TRY -> {
                CLBC_Block b = block("control.try", "结构", new ArrayList<>(), level);
                b.text = "尝试";
                b.slots = new ArrayList<>();
                b.slots.add(new CLBC_Block.Slot("尝试", s.tryResources == null ? "" : s.tryResources));
                for (JAst.JCatch cc : s.catches)
                    b.slots.add(new CLBC_Block.Slot("捕获", cc.param == null ? "" : cc.param));
                if (s.hasFinally) b.slots.add(new CLBC_Block.Slot("最终执行", ""));
                // 每个口单独收集子块（holder 不进树，只借用它的 children 列表）
                List<List<JAst.JStmt>> groups = new ArrayList<>();
                groups.add(s.body);
                for (JAst.JCatch cc : s.catches) groups.add(cc.body);
                if (s.hasFinally) groups.add(s.finallyBody);
                for (int i = 0; i < groups.size(); i++) {
                    CLBC_Block holder = new CLBC_Block(-1, "stack", 0L, "");
                    for (JAst.JStmt c : groups.get(i)) convertStatement(c, holder, level + 1);
                    b.slots.get(i).children.addAll(holder.children);
                    // 资源表达式 / 捕获参数不是布尔条件，用普通胶囊窗口而非六边形布尔槽
                    String e = b.slots.get(i).expr;
                    if (e != null && !e.isEmpty()) {
                        CLBC_Block.Input in = new CLBC_Block.Input(e, false, "str");
                        in.row = i; in.ordinal = 0;
                        b.inputs.add(in);
                    }
                }
                b.syncChildrenUnion();
                parent.children.add(b);
            }
            case RETURN -> parent.children.add(block("data.return", "数据",
                    new ArrayList<>(List.of(s.retExpr == null ? "" : s.retExpr)), level));
            case COMMENT -> parent.children.add(block("core.comment", "结构",
                    new ArrayList<>(List.of(s.comment == null ? "" : s.comment)), level));
            case EXPR -> {
                if (s.expr != null) convertExpr(s.expr, parent, level);
            }
            default -> {
                // A-3：降级前先尝试把"工具箱语法积木生成的代码"还原回对应积木类型，
                // 保住积木 key / 字面量类型 / 报告块嵌套树；还原不了才降级 raw.code 保留原文
                String raw = s.raw == null ? "" : s.raw;
                // try/catch/finally 已由 JParser 解析为 TRY 结构化语句，不再有整段原文降级；
                // restoreSyntax 对含花括号 / 换行的原文仍会自动返回 null，兜底不误拆。
                CLBC_Block restored = restoreSyntax(raw, level);
                if (restored != null) parent.children.add(restored);
                else {
                    degradedCount++;
                    parent.children.add(block("raw.code", "结构",
                            new ArrayList<>(List.of(raw)), level));
                }
            }
        }
    }

    private static void convertExpr(JAst.JExpr e, CLBC_Block parent, int level) {
        // A-4：与 convertStatement 一致的深层嵌套保护
        if (level > MAX_LEVEL) {
            degradedCount++;
            CLBC_Util.log("!!!! 代码→积木：表达式嵌套深度超限(" + level + ")，已降级保留原文");
            parent.children.add(block("raw.code", "结构",
                    new ArrayList<>(List.of(e.raw == null ? "" : e.raw)), level));
            return;
        }
        switch (e.kind) {
            case CALL -> {
                CLBC_Block b = convertMethodCall(e.scope, e.name, e.args, level);
                if (b != null) parent.children.add(b);
            }
            case VARDECL -> {
                String tp = e.type == null ? "" : e.type;
                String ini = e.init == null ? "" : e.init;
                // #253：new Thread(() -> { … }) 这类「匿名类/lambda 回调」初值，
                // 旧路径整段塞进 data.var 输入口 → 扁平一行、右侧截断、回调体不可编辑。
                // 改成专用 C 口积木：父积木承载声明头，回调体递归解析成真正的子积木。
                CLBC_Block lb = convertLambdaInit(tp, e.varName, ini, level);
                if (lb != null) { parent.children.add(lb); return; }
                // 收口4：纯 `final 类型 名 = 值;` 还原为 data.const（有初值时模板保真）；
                // 其它（含多修饰符／无初值）沿用 data.var，避免 final 前缀往返失真。
                if (tp.startsWith("final ") && !ini.isEmpty()) {
                    parent.children.add(block("data.const", "数据",
                            new ArrayList<>(List.of(tp.substring(6).trim(), e.varName, ini)), level));
                } else {
                    parent.children.add(block("data.var", "数据",
                            new ArrayList<>(List.of(tp, e.varName, ini)), level));
                }
            }
            case ASSIGN -> parent.children.add(block("data.assign", "数据",
                    new ArrayList<>(List.of(e.target, e.value)), level));
            default -> {
                // A-3：与 RAW 语句同一套语法积木还原，避免往返后全变纯文本
                CLBC_Block restored = restoreSyntax(e.raw, level);
                if (restored != null) parent.children.add(restored);
                else {
                    degradedCount++;
                    parent.children.add(block("raw.code", "结构",
                            new ArrayList<>(List.of(e.raw == null ? "" : e.raw)), level));
                }
            }
        }
    }

    /** #253：识别「变量声明 = new 类型(lambda 或 匿名类体)」的初值，生成带 C 口的容器积木。
     *  目的：让 new Thread(() -> { … }) 的回调体变成可内嵌积木，而不是塞进一行输入口被截断。
     *  支持两种形态：
     *    ① new Thread(() -> { …body… })       —— lambda 参数默认 ()
     *    ② new Thread(new Runnable() { …body… }) —— 匿名类体
     *  非这些形态返回 null，交回调用方走原 data.var / data.const。 */
    private static CLBC_Block convertLambdaInit(String declType, String varName, String init, int level) {
        if (init == null) return null;
        String s = init.trim();
        while (s.endsWith(";")) s = s.substring(0, s.length() - 1).trim();
        if (!s.startsWith("new ")) return null;
        int lp = s.indexOf('(');
        if (lp <= 4) return null;
        String ctorType = s.substring(4, lp).trim();           // Thread
        if (ctorType.isEmpty() || ctorType.contains(" ")) return null;
        String inside = s.substring(lp + 1).trim();
        if (!inside.endsWith(")")) return null;
        inside = inside.substring(0, inside.length() - 1).trim();  // 构造器实参列表

        // 找回调体 '{' 与其配对的 '}'（忽略字符串/字符内括号）
        int bodyOpen = findTopLevelChar(inside, '{');
        int bodyClose = matchBrace(inside, bodyOpen);
        if (bodyOpen < 0 || bodyClose < 0) return null;
        String beforeBody = inside.substring(0, bodyOpen).trim();
        String body = inside.substring(bodyOpen + 1, bodyClose);
        String afterBody = inside.substring(bodyClose + 1).trim();
        // beforeBody 必须看起来是 lambda/匿名类头：() -> / (x) -> / new Xxx() / Runnable()
        boolean lambdaHead = beforeBody.endsWith("->") || beforeBody.contains("->");
        boolean anonHead = beforeBody.startsWith("new ") || beforeBody.endsWith("()");
        if (!lambdaHead && !anonHead) return null;
        // afterBody 只可能是空或 ", 其它实参"，含则留作尾部实参文本
        if (!afterBody.isEmpty() && !afterBody.startsWith(",")) return null;

        String lambdaParams = "()";
        if (lambdaHead) {
            int arrow = beforeBody.lastIndexOf("->");
            String head = beforeBody.substring(0, arrow).trim();
            if (head.startsWith("(") && head.endsWith(")")) lambdaParams = head;
            else if (!head.isEmpty()) lambdaParams = "(" + head + ")";
        }

        // 递归解析回调体语句：包进一个假方法交给 JParser，得到 JStmt 列表再逐条转积木
        List<JAst.JStmt> bodyStmts = parseLambdaStatements(body);
        if (bodyStmts == null) return null;   // 解析失败 → 退回原 data.var，原文不丢

        CLBC_Block b = new CLBC_Block(counter++, "cblock",
                CLBC_Toolbox.categoryColor("结构"), "");
        b.key = "data.newCallback";
        // args: [声明类型, 变量名, 构造类型, lambda 参数, 回调体外剩余实参]
        b.args = new ArrayList<>(List.of(
                declType == null ? "" : declType,
                varName == null ? "" : varName,
                ctorType, lambdaParams, afterBody));
        b.nestLevel = level;
        b.slots = new ArrayList<>();
        CLBC_Block.Slot slot = new CLBC_Block.Slot("创建 " + ctorType + " · 回调体", "");
        b.slots.add(slot);
        CLBC_Block holder = new CLBC_Block(-1, "stack", 0L, "");
        for (JAst.JStmt st : bodyStmts) convertStatement(st, holder, level + 1);
        slot.children.addAll(holder.children);
        b.syncChildrenUnion();
        CLBC_Util.logFine("lambda 回调转 C 口积木: " + ctorType + " " + lambdaParams
                + "，回调体 " + bodyStmts.size() + " 条语句");
        return b;
    }

    /** 把 lambda/匿名类体源码包进假方法解析成语句列表；失败返回 null（调用方保真降级） */
    private static List<JAst.JStmt> parseLambdaStatements(String body) {
        if (body == null || body.trim().isEmpty()) return new ArrayList<>();
        String wrapped = "class __CLBCLambda { void __run() {\n" + body + "\n} }";
        try {
            JAst.JRoot root = JParser.parse(wrapped);
            if (root == null || root.types.isEmpty() || root.types.get(0).methods.isEmpty()) return null;
            return root.types.get(0).methods.get(0).body;
        } catch (RuntimeException | StackOverflowError ex) {
            CLBC_Util.log("!!!! lambda 回调体解析失败，保留原文: "
                    + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
            return null;
        }
    }

    /** 找 text 中第一个顶层（不在字符串/字符/括号内）的指定字符 */
    private static int findTopLevelChar(String text, char target) {
        int depth = 0;
        boolean inStr = false, inChar = false;
        for (int k = 0; k < text.length(); k++) {
            char c = text.charAt(k);
            if (inStr) { if (c == '\\') k++; else if (c == '"') inStr = false; continue; }
            if (inChar) { if (c == '\\') k++; else if (c == '\'') inChar = false; continue; }
            if (c == '"') { inStr = true; continue; }
            if (c == '\'') { inChar = true; continue; }
            if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') {
                if (target == c && depth == 0) return k;
                depth--;
            } else if (c == target && depth == 0) return k;
        }
        return -1;
    }

    /** 从 text[open] 的 '{' 起找配对 '}' */
    private static int matchBrace(String text, int open) {
        if (open < 0 || open >= text.length() || text.charAt(open) != '{') return -1;
        int depth = 0;
        boolean inStr = false, inChar = false;
        for (int k = open; k < text.length(); k++) {
            char c = text.charAt(k);
            if (inStr) { if (c == '\\') k++; else if (c == '"') inStr = false; continue; }
            if (inChar) { if (c == '\\') k++; else if (c == '\'') inChar = false; continue; }
            if (c == '"') { inStr = true; continue; }
            if (c == '\'') { inChar = true; continue; }
            if (c == '{') depth++;
            else if (c == '}') { depth--; if (depth == 0) return k; }
        }
        return -1;
    }

    /** A-3：创建语法积木并按定义表修正形状。
     *  block() 的粗映射会把所有 control.* 当 cblock、把 lit.* 与 core.* 留成 stack，
     *  这里按 CLBC_CoreSyntax.Def.kind 校正 round/hex/stack，保证还原的积木画对形状。 */
    private static CLBC_Block syntaxBlock(String key, List<String> args, int level) {
        CLBC_Block b = block(key, "语法", args, level);
        CLBC_CoreSyntax.Def d = CLBC_CoreSyntax.def(key);
        if (d != null) {
            if ("hex".equals(d.kind)) { b.kind = "hex"; b.isReporter = true; }
            else if ("round".equals(d.kind)) { b.kind = "round"; b.isReporter = false; }
            else b.kind = "stack";
        }
        return b;
    }

    /** A-3：raw.code 降级前的"语法积木还原"。
     *  把一行"工具箱语法积木生成的代码"还原回对应积木类型，保住 key / 字面量类型 / 报告块嵌套树。
     *  因 JAst.JExpr.K 只有 CALL/VARDECL/ASSIGN/OTHER、且 JAst.java 不在段 1 修改范围（红线禁改），
     *  classify 无法新增枚举传递积木类型，故在降级出口按原文反推；无法还原返回 null，由调用方降级 raw.code。
     *
     *  覆盖：core.* 运算符/比较/逻辑/位/三元/强转/括号/数组/自增（走 recognize 还原整棵树）、
     *        lit.* / data.get / data.this / data.super 叶子、data.new / core.arrayNew、
     *        data.assignOp 复合赋值、control.break/continue/throw/assert、core.comment。
     */
    private static CLBC_Block restoreSyntax(String raw, int level) {
        if (raw == null) return null;
        String s = raw.trim();
        while (s.endsWith(";")) s = s.substring(0, s.length() - 1).trim();
        // 多行 / 带块结构（switch / try / synchronized / 复杂方法链块等）不在此还原，保留原文降级
        if (s.isEmpty() || s.contains("\n") || s.contains("{") || s.contains("}")) return null;

        // 1) 单行注释
        if (s.startsWith("//"))
            return syntaxBlock("core.comment", new ArrayList<>(List.of(s.substring(2).trim())), level);

        // 2) 关键字语句（RAW_KEYWORDS 里能一一对应工具箱积木的）
        if (s.equals("break")) return syntaxBlock("control.break", new ArrayList<>(), level);
        if (s.equals("continue")) return syntaxBlock("control.continue", new ArrayList<>(), level);
        if (s.startsWith("throw ") && s.length() > 6)
            return syntaxBlock("control.throw", new ArrayList<>(List.of(s.substring(6).trim())), level);
        if (s.startsWith("assert ") && s.length() > 7)
            return syntaxBlock("control.assert", new ArrayList<>(List.of(s.substring(7).trim())), level);

        // 3) 复合赋值 → data.assignOp（recognize 会把 '+=' 的 '+' 误判成二元加号，必须先拦）
        for (String op : new String[]{">>>=", "<<=", ">>=", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^="}) {
            int k = s.indexOf(op);
            if (k > 0) {
                String target = s.substring(0, k).trim();
                String value = s.substring(k + op.length()).trim();
                if (!target.isEmpty() && !value.isEmpty())
                    return syntaxBlock("data.assignOp",
                            new ArrayList<>(List.of(target, op.substring(0, op.length() - 1), value)), level);
            }
        }

        // 4) new 自定义类型 / 数组创建（工具箱 data.new、core.arrayNew）
        if (s.startsWith("new ") && s.endsWith(")")) {
            int lp = s.indexOf('(');
            if (lp > 4) {
                String type = s.substring(4, lp).trim();
                String argv = s.substring(lp + 1, s.length() - 1).trim();
                if (!type.isEmpty())
                    return syntaxBlock("data.new", new ArrayList<>(List.of(type, argv)), level);
            }
        }
        if (s.startsWith("new ") && s.endsWith("]")) {
            int lb = s.indexOf('[');
            if (lb > 4) {
                String type = s.substring(4, lb).trim();
                String size = s.substring(lb + 1, s.length() - 1).trim();
                if (!type.isEmpty())
                    return syntaxBlock("core.arrayNew", new ArrayList<>(List.of(type, size)), level);
            }
        }

        // 5) 报告积木树：core.* 运算符 / 比较 / 逻辑 / 位运算 / 三元 / 强转 / 括号 / 数组 / 自增 等
        //    recognize 返回的块自带正确 round/hex 形状与内嵌子树，直接作为该语句的积木
        CLBC_Block tree = CLBC_CoreSyntax.recognize(s);
        if (tree != null) {
            tree.nestLevel = level;
            return tree;
        }

        // 6) 叶子字面量 / 变量：recognize 对叶子返回 null，这里按类型补回 lit.* / data.get
        String lit = literalKey(s);
        if (lit != null) return syntaxBlock(lit, new ArrayList<>(List.of(s)), level);
        return null;   // 仍降级 raw.code（调用方计数 + 末尾汇总日志）
    }

    /** A-3：单个原子文本 → 对应语法积木 key（判定与 CLBC_CoreSyntax.ExprParser.atom 一致） */
    private static String literalKey(String s) {
        if (s.equals("true") || s.equals("false")) return "lit.bool";
        if (s.equals("null")) return "lit.null";
        if (s.equals("this")) return "data.this";
        if (s.equals("super")) return "data.super";
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) return "lit.string";
        if (s.length() >= 2 && s.startsWith("'") && s.endsWith("'")) return "lit.char";
        if (s.matches("\\d+[lL]")) return "lit.long";
        if (s.matches("[0-9]*\\.?[0-9]+[fF]")) return "lit.float";
        if (s.matches("[0-9]*\\.?[0-9]+[dD]?")) return "lit.double";
        if (s.matches("0[xX][0-9a-fA-F]+|0[bB][01]+|\\d+")) return "lit.int";
        if (s.matches("[A-Za-z_$][A-Za-z0-9_$]*")) return "data.get";
        return null;
    }

    /**
     * 方法调用 → 积木：③-2 库机械化识别接入点。
     *  · 调用方类名匹配到已扫描库成员 → 用库分类着色 + lang.json 显示短名，落入 ops.call（互转闭环）。
     *  · 命中库但被安全策略禁用 → 降级注释块并标注，既不生成危险积木也不丢信息。
     *  · 未识别（局部变量 / 未导入类 / 字段链等） → 降级 raw.code，保留完整调用文本。
     */
    private static CLBC_Block convertMethodCall(String scope, String method, List<String> argStrs, int level) {
        if (method == null) method = "";
        if (argStrs == null) argStrs = new ArrayList<>();
        // 取调用方简单名（scope 形如 Math / list / System.out → 取首段）
        String owner = (scope == null || scope.isEmpty()) ? "" : scope.split("\\.")[0];
        // 用原始查询（不过滤安全）：命中库后再审 isSafe，从而区分“被策略禁用”与“未知调用”
        CLBC_BlockDefinition def = owner.isEmpty() ? null : CLBC_BlockLibrary.findBlockRaw(owner, method);

        if (def != null && !CLBC_BlockLibrary.isSafe(def.ownerFqn, def.methodName)) {
            // 命中库但被安全策略禁用 → 降级原生代码块并标注
            // 注意：未识别/被禁用的调用也必须保留完整调用文本（scope.method(args)），
            // 否则 name 丢失（如 System.exit → System(0)）会导致往返失真。
            String full = (scope == null || scope.isEmpty() ? method : scope + "." + method)
                    + "(" + String.join(", ", argStrs) + ");";
            String flagged = "// [安全策略已禁用该方法] " + full;
            CLBC_Block b = new CLBC_Block(counter++, "stack",
                    CLBC_Toolbox.categoryColor("结构"), flagged);
            b.key = "raw.code";
            b.args = new ArrayList<>(List.of(flagged));
            b.nestLevel = level;
            CLBC_Util.logFine("调用被安全策略禁用: " + def.ownerFqn + "#" + def.methodName);
            return b;
        }

        if (def != null) {
            // 命中库：构造「类下拉 → 方法下拉 → 参数内嵌口/回调 C 口」调用积木
            CLBC_Block b = CLBC_Block.makeCallBlock(def.className, def.methodName, argStrs);
            if (b != null) {
                b.id = counter++;
                b.nestLevel = level;
                return b;
            }
        }

        // #已知限制实修：未命中库的调用不再降级 raw.code（典型：System.out.println、list.add、foo()）。
        // 统一生成 ops.call，用 args[3]="recv:<接收者原文>" 保真还原整条调用链；
        // 积木仍是「类下拉 + 方法下拉 + 参数内嵌口」，BlocksToCode 按 recv 拼回源码文本。
        // 这样 java.lang 隐式导入的调用、局部变量调用、链式调用都能变成可编辑的结构化积木。
        String recv = scope == null ? "" : scope;
        String cls = recv.isEmpty() ? "" : (recv.contains(".") ? recv.substring(0, recv.indexOf('.')) : recv);
        CLBC_Block b = new CLBC_Block(counter++, "stack",
                CLBC_Toolbox.categoryColor("结构"), (recv.isEmpty() ? method : recv + "." + method) + "()");
        b.key = "ops.call";
        b.args = new ArrayList<>(List.of(cls, method, "lib:", "recv:" + recv));
        b.nestLevel = level;
        // 类下拉候选：当前接收者首段不在已扫描类里时补进候选，保证下拉能显示当前值
        java.util.List<String> clsNames = CLBC_BlockLibrary.scannedSimpleNames();
        if (!cls.isEmpty() && !clsNames.contains(cls)) {
            clsNames = new ArrayList<>(clsNames);
            clsNames.add(0, cls);
        }
        CLBC_Block.Input cin = new CLBC_Block.Input(cls, false);
        cin.row = 0; cin.ordinal = 0;
        cin.options = CLBC_Block.toArr(clsNames);
        b.inputs.add(cin);
        // 方法下拉候选：能解析到类就列它的安全方法名，当前方法名不在候选里时补进去
        CLBC_Block.Input min = new CLBC_Block.Input(method, false);
        min.row = 0; min.ordinal = 1;
        String fqn = cls.isEmpty() ? null : CLBC_BlockLibrary.resolveFqn(cls);
        if (fqn != null) {
            java.util.List<String> names = CLBC_BlockLibrary.methodNamesOf(fqn);
            if (!names.contains(method)) { names = new ArrayList<>(names); names.add(0, method); }
            min.options = CLBC_Block.toArr(names);
        }
        b.inputs.add(min);
        for (int j = 0; j < argStrs.size(); j++) {
            CLBC_Block.Input pin = new CLBC_Block.Input(argStrs.get(j) == null ? "" : argStrs.get(j), false);
            pin.row = 0; pin.ordinal = 2 + j;
            pin.accept = "any";
            b.inputs.add(pin);
        }
        return b;
    }

    // 通配 import 包 → 常见类（扫描用 Class.forName，需全限定名）
    private static final Map<String, List<String>> WILDCARD = new LinkedHashMap<>();
    static {
        WILDCARD.put("java.util", List.of(
            "java.util.List", "java.util.ArrayList", "java.util.Map", "java.util.HashMap",
            "java.util.Set", "java.util.HashSet", "java.util.Scanner", "java.util.Random"));
        WILDCARD.put("java.lang", List.of(
            "java.lang.Math", "java.lang.String", "java.lang.Integer", "java.lang.Double",
            "java.lang.System", "java.lang.Object", "java.lang.StringBuilder"));
        WILDCARD.put("java.io", List.of(
            "java.io.File", "java.io.PrintWriter", "java.io.BufferedReader", "java.io.FileReader"));
    }

    /** ③-1 通用化库识别：扫描 import 的显式类，通配包则扫一批常见类（失败静默跳过） */
    private static void scanLibraries(JAst.JRoot root) {
        for (JAst.JImport imp : root.imports) {
            if (imp.wildcard) {
                String pkg = imp.name.endsWith(".*") ? imp.name.substring(0, imp.name.length() - 2) : imp.name;
                for (String sub : WILDCARD.getOrDefault(pkg, List.of())) {
                    CLBC_BlockLibrary.scanClass(sub);
                    CLBC_BlockRegistry.registerClass(sub);   // 增量登记：启动预热后新增的类也要进登记器
                }
            } else {
                CLBC_BlockLibrary.scanClass(imp.name);
                CLBC_BlockRegistry.registerClass(imp.name);  // 增量登记（内部 REGISTERED_LIB 去重，重复调用安全）
            }
        }
        CLBC_Util.log("库识别完成：已注册类 " + CLBC_BlockLibrary.library.size() + " 个");
    }

    // 创建积木并填显示文本（取自语言文件）
    private static CLBC_Block block(String key, String fallbackCat, List<String> args, int level) {
        String cat = CLBC_Lang.categoryOf(key);
        if (cat == null || cat.isEmpty()) cat = fallbackCat;
        CLBC_Block b = new CLBC_Block(counter++, "stack",
                CLBC_Toolbox.categoryColor(cat), CLBC_Lang.text(key, args));
        b.key = key;
        b.args = new ArrayList<>(args);
        b.nestLevel = level;
        // 形状映射（供渲染层识别）
        if ("unit.class".equals(key)) b.kind = "class";
        else if ("unit.method".equals(key)) b.kind = "method";
        else if (key.startsWith("control.")) b.kind = "cblock";
        else if (key.startsWith("expr.")) { b.kind = "round"; b.isReporter = true; }

        // ⑧ 每个参数都成为真正的内嵌输入窗口（可点击编辑、可再放报告积木）；
        // 表达式位置额外自研识别成纯语法积木树，不再一律降级为文本 / raw.code。
        b.bindArgsAsInputs(0, null);
        for (int i = 0; i < b.inputs.size(); i++) {
            CLBC_Block.Input in = b.inputs.get(i);
            if (in.value.startsWith("lib:")) { in.value = ""; continue; }
            boolean exprSlot = isExprArg(key, i);
            in.hex = exprSlot && isCondition(key, i, in.value);
            in.accept = in.hex ? "bool" : (exprSlot ? "any" : "str");
            // 代码导入布局：unit.method 的 args[0]=修饰符、args[1]=返回类型；
            // data.var / data.const / unit.field 的 args[0]=类型。这些槽改成枚举下拉。
            in.options = CLBC_CoreSyntax.importDropdownOptions(key, i);
            // #280：boolean 变量/常量的 true/false 初值槽改「布尔下拉」，不再是可自由输入的文本框。
            // 仅当值确为 true/false 字面量时切换；复杂布尔表达式（含方法调用/比较）保留文本框+子块，
            // 保证 boolean flag = computeFlag() 之类往返零丢失。下拉槽不能有 child（否则弹不出候选）。
            if (("data.var".equals(key) || "data.const".equals(key)) && i == 2
                    && b.args.size() > 0 && "boolean".equals(b.args.get(0).trim())) {
                String raw = in.value == null ? "" : in.value.trim();
                if ("true".equals(raw) || "false".equals(raw)) {
                    in.value = raw;
                    in.options = CLBC_CoreSyntax.BOOL_LITERALS;
                    in.child = null;
                    if (b.args.size() > 2) b.args.set(2, raw);
                    b.text = CLBC_Lang.text(key, b.args);   // text 与 args 同源，防 segment 错位
                    continue;                                // 跳过 recognize，值留在文本里下拉才弹得出
                }
            }
            if (exprSlot) {
                CLBC_Block sub = CLBC_CoreSyntax.recognize(in.value);
                if (sub != null) in.child = sub;   // 窗口里嵌套表达式积木
            }
        }
        return b;
    }

    /** 哪些参数位置是「表达式」（可被自研表达式解析器拆成纯语法积木） */
    private static boolean isExprArg(String key, int i) {
        return switch (key) {
            case "data.var" -> i == 2;
            case "data.assign" -> i == 1;
            case "data.return" -> i == 0;
            case "control.if", "control.while", "control.for", "control.forEach",
                 "control.do", "control.switch", "control.assert" -> i == 0;
            case "raw.code", "expr.number", "expr.string", "expr.bool" -> i == 0;
            default -> false;
        };
    }

    /** 容器每个口的条件 → 该行第 0 个六边形布尔内嵌窗口（内可放比较/逻辑报告块） */
    private static void bindSlotInputs(CLBC_Block b) {
        if (!b.hasSlots()) return;
        for (int i = 0; i < b.slotCount(); i++) {
            String cond = b.slots.get(i).expr;
            if (cond == null || cond.isEmpty()) continue;
            if (b.inputFor(i, 0) != null) continue;
            CLBC_Block.Input in = new CLBC_Block.Input(cond, true, "bool");
            in.row = i; in.ordinal = 0;
            CLBC_Block sub = CLBC_CoreSyntax.recognize(cond);
            if (sub != null) in.child = sub;
            b.inputs.add(in);
        }
    }

    /** 条件位置 → 六边形布尔窗口；其余表达式 → 胶囊文本/数字窗口 */
    private static boolean isCondition(String key, int i, String v) {
        if (key.startsWith("control.") && i == 0) return true;
        return "expr.bool".equals(key) || v.equals("true") || v.equals("false")
                || v.contains("==") || v.contains("!=") || v.contains(">=") || v.contains("<=")
                || v.contains("&&") || v.contains("||") || v.contains(" > ") || v.contains(" < ");
    }

    // 把嵌套树展平：分配绝对坐标 + parentId，清空 children（重建靠 parentId）
    private static List<CLBC_Block> flatten(List<CLBC_Block> roots, float x0, float y0) {
        List<CLBC_Block> out = new ArrayList<>();
        float y = y0;
        for (CLBC_Block root : roots) {
            // 必须在 place 之前量：place 会把 children / slots.children 清空（扁平化），
            // 之后再量会漏掉全部子块 → 高度严重低估、根之间叠在一起。
            float rh = renderHeight(root);
            place(root, x0, y, -1, out, 0);
            // 顶层根之间按【真实渲染高度】推进，不再用 place 内部按粗估子块高度累加的返回值。
            // 旧值让方法子块越多、下一个方法被推得越远（实测间隔 46~130px 的空白带）。
            y += rh + TOP_ROOT_GAP;
        }
        return out;
    }
    /** 顶层根之间的固定间隔（与画布点阵对齐） */
    private static final float TOP_ROOT_GAP = 20f;

    /** 估算一块在画布上的真实绘制高度（容器 = 顶栏 + 各口子块累加 + 底栏）。
     *  只用于根间排布，故用几何常量估算即可，不依赖 GL 字体。 */
    private static float renderHeight(CLBC_Block b) {
        String k = b.kind == null ? "stack" : b.kind;
        if ("round".equals(k) || "hex".equals(k)) return 44f;
        boolean container = "class".equals(k) || "method".equals(k) || "cblock".equals(k);
        if (!container) return 52f;
        float h = 52f + 16f;   // TOP_H + BOTTOM_H
        if (b.hasSlots()) {
            boolean first = true;
            for (CLBC_Block.Slot sl : b.slots) {
                h += first ? 52f : 48f;   // 首口 TOP_H，其余 BAR_H
                first = false;
                float kids = 0f;
                for (CLBC_Block c : sl.children) kids += renderHeight(c);
                h += Math.max(28f, kids);
            }
        } else {
            for (CLBC_Block c : b.children) h += renderHeight(c);
        }
        return h;
    }

    private static float place(CLBC_Block b, float x, float y, int parentId, List<CLBC_Block> out, int depth) {
        b.x = x; b.y = y; b.parentId = parentId;
        out.add(b);
        // A-4：展平深度保护，超限停止展开子树并记日志，不许 StackOverflowError 穿透
        if (depth > MAX_LEVEL) {
            CLBC_Util.log("!!!! 代码→积木：积木树展平深度超限(" + depth + ")，已停止展开子树");
            b.children = new ArrayList<>();
            if (b.hasSlots()) for (CLBC_Block.Slot sl : b.slots) sl.children.clear();
            return y + blockHeight(b) + 8;
        }
        float childX = x + 36, cur = y + blockHeight(b) + 8;
        if (b.hasSlots()) {
            // 多口：按口顺序扁平化，并给每个子块写上 slotIndex（供画布重建各口分支）
            for (int i = 0; i < b.slotCount(); i++) {
                for (CLBC_Block c : new ArrayList<>(b.slotChildren(i))) {
                    c.slotIndex = i;
                    cur = place(c, childX, cur, b.id, out, depth + 1);
                }
                b.slots.get(i).children.clear();
            }
        } else {
            List<CLBC_Block> kids = new ArrayList<>(b.children);
            for (CLBC_Block c : kids) cur = place(c, childX, cur, b.id, out, depth + 1);
        }
        b.children = new ArrayList<>(); // 扁平化后清空，避免重复引用
        return cur + 8;
    }

    private static float blockHeight(CLBC_Block b) {
        return switch (b.kind) {
            case "class" -> 140;
            case "method" -> 106;
            case "cblock" -> 120;
            case "round" -> 36;
            default -> 40;
        };
    }
}
