package clbc.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import clbc.gl.CLBC_GL_API;
import clbc.gl.CLBC_GL_Widgets;
import clbc.blocks.CLBC_Lang;
import clbc.blocks.CodeToBlocks;
import clbc.blocks.CLBC_ProjectIO;
import clbc.blocks.CLBC_ProjectIO.CommentState;
import clbc.util.CLBC_Util;

/**
 * 画布宿主（OpenGL 即时模式，无 JavaFX）。
 * 承载积木数据列表，提供：
 *  · 平移（左键空白拖拽） / Ctrl+空白框选
 *  · 从工具箱点击添加积木（{@link #addBlock}）
 *  · 积木拖动（整块含嵌套子树）/ 拖出可视区删除 / 邻近堆叠吸附
 *  · 嵌套拖入（释放时若落在容器 body 区则成为其子块）
 *  · 双击折叠 / 展开（{@link CLBC_Block#collapsed}）
 *  · 右键菜单（添加注释 / 折叠 / 删除）
 * 背景为无限延伸的点阵网格。
 */
public class CLBC_CanvasHost {

    /** 注释便利贴（可编辑，带连线） */
    private static final class Comment {
        float x, y, w = 210, h = 76;
        String text = "输入注释...";
        /** 便签的锚点 id：固定连到【源码里注释前那条语句】的积木，
         *  平移 / 缩放 / 拖块都不会换锚点；只有该块被删除才重新找。 */
        int anchorId = -1;
        /** 源码顺序位置（积木→代码回写用），与画布上的视觉位置解耦。 */
        float orderY = Float.NaN;
        /** true = 注释在开头无前驱，锚点是后继语句 */
        boolean anchorIsNext = false;
        /** true = 用户手动拖 / 缩放过：位置与尺寸由用户做主，自动布局不再覆盖。 */
        boolean userPlaced = false;
    }

    private final List<CLBC_Block> blocks = new ArrayList<>();
    private final List<Comment> comments = new ArrayList<>();
    /** #实测修复：导入 / 加载工程后便签需要按【当前画布尺寸】重排一次。
     *  旧逻辑在导入时就把便签摆到「最外层容器右边界 +36」，容器一宽就掉出画布、
     *  被右侧代码面板裁掉（用户截图里只露左半截、文字竖着挤）。只做一次，手动拖动不被覆盖。 */
    private boolean commentsNeedLayout = true;
    private float panX = 0, panY = 0;
    private int nextId = 1;

    // 平移状态
    private boolean panning = false;
    private float panStartMX, panStartMY, panStartPX, panStartPY;

    // 拖动状态（dragId 记录顶层祖先 id，拖动整块含子树）
    private int dragId = -1;
    private float dragOffX, dragOffY;

    // 右键菜单目标
    private int menuBlockId = -1;

    // 最近一次绘制区域（addBlock 用于定位到视野中心）
    private float lastRX, lastRY, lastRW, lastRH;

    // 嵌套树索引（每帧重建）
    private final Map<Integer, CLBC_Block> idIndex = new HashMap<>();

    // 双击折叠检测
    private int lastClickId = -1;
    private long lastClickTime = 0;

    // 框选
    private boolean selecting = false;
    private float selX0, selY0, selX1, selY1;
    private final List<Integer> selection = new ArrayList<>();

    // 撤销/重做历史（快照式：整份扁平积木列表的深拷贝）
    private final List<List<CLBC_Block>> history = new ArrayList<>();
    private int histIdx = -1;
    private static final int HIST_CAP = 100;

    // 工程持久化 / 自动保存
    private String currentProjectPath = null;
    private String autosavePath = null;
    /** 自上次成功保存到 project.clbc 以来，积木数据是否有未保存改动。
     *  仅记录「已落到画布模型」的改动：commit()（积木交互 / 导入）与 replaceBlocksLive()（代码编辑回灌）置位；
     *  saveProject() 成功、importBlocks(replace=true)（打开工程）、loadProject() 清零。
     *  打开工程不算脏：importBlocks 的 replace 分支只 initHistory，不调 commit。 */
    private boolean dirtySinceSave = false;

    // 拖动起点快照（用于判断释放时是否发生变更，决定是否提交历史）
    private float dragStartX = 0, dragStartY = 0;
    private int dragStartParent = -1;

    // 内嵌输入窗口：当前正在编辑的槽（即时模式文本框覆盖在窗口上）
    private CLBC_Block.Input editingInput = null;
    private CLBC_Block editOwner = null;
    private final float[] editRect = new float[4];
    private long lastEditCommit = 0;

    // 枚举下拉弹层：点中下拉槽 → 浮层列出候选 → 选中写回。
    // 绘制放在 drawPopup（clip 之外、最上层），命中/选择放在 handleInput，二者共用同一矩形算法。
    private CLBC_Block.Input dropdownInput = null;
    private CLBC_Block dropdownOwner = null;
    private final float[] dropdownRect = new float[4];   // 槽的屏幕矩形
    private float dropdownScroll = 0f;
    private static final float DD_ROW_H = 22f;
    private static final float DD_MAX_H = 360f;   // #132：抬高候选面板高度上限，方法多的类（如 Math）也能一次露出更多
    private static final float DD_MIN_W = 120f;

    // 渲染统计（节流日志用）：累计帧数，每 600 帧打印一次视口裁剪效果
    private int renderStatTick = 0;

    // ================= 段2 新增：缩放 / 选择组 / 剪贴板 / 注释交互 / 插入提示 =================
    /** B-3 画布缩放比例。世界→逻辑屏幕仍为 region + (b.x,b.y) + pan，绘制时由 GL 变换整体乘 zoom。 */
    private float zoom = 1f;
    private static final float ZOOM_MIN = 0.25f, ZOOM_MAX = 4f;

    /** B-2 / B-7：本轮拖动涉及的块集合（选中集合或堆叠子序列）及各自起始世界坐标、按下锚点。 */
    private final List<CLBC_Block> dragGroup = new ArrayList<>();
    private final List<float[]> dragGroupStart = new ArrayList<>();   // 每块起始 [x, y]
    /** #163：同一积木列上「命中块 → 列尾」的那条链。
     *  只有这些块需要改 parentId；dragGroup 里其余是嵌套后代，必须保持挂在原父块下。 */
    private final List<CLBC_Block> dragRoots = new ArrayList<>();
    private float dragAnchorX = 0, dragAnchorY = 0;                   // 按下时逻辑鼠标坐标

    /** B-1：复制 / 粘贴剪贴板（积木深拷贝 + 就近注释便利贴）。 */
    private final List<CLBC_Block> clipBlocks = new ArrayList<>();
    private final List<Comment> clipComments = new ArrayList<>();

    /** B-4：注释便利贴拖动 / 缩放状态。 */
    private int commentDragIdx = -1, commentResizeIdx = -1;
    private float cStartX, cStartY, cStartW, cStartH, cStartMX, cStartMY;

    /** B-6：拖动中"将要插入的位置"高亮线（逻辑坐标，hintY 为 NaN 时不显示）。 */
    private float hintX = Float.NaN, hintY = Float.NaN, hintW = 0;

    // ================= #130 工具箱拖拽插入：从工具箱「拖出」到目标位置，松手按落点插入 =================
    /** 工具箱拖拽进行中（与画布块拖动 dragGroup 互斥，独占一帧输入处理） */
    private boolean toolboxDragging = false;
    private String tbSpecId = null;    // #164：登记器 specId（优先，程序化生成可转回 Java 的积木）
    private String tbCoreKey = null;   // 纯语法块 key（非空走 buildCoreBlock）
    private String tbCallRef = null;   // 调用积木 "类#方法"（非空走 makeCallBlock）
    private String tbText = null;      // 普通块显示文本
    private String tbKind = "stack";
    private long tbColor = 0;
    /** 拖拽期间缓存的待生成积木（量过尺寸，绘制虚影 + 松手落盘复用同一实例） */
    private CLBC_Block tbGhostBlock = null;
    /** 当前推算落点（世界坐标），用于画「松开后将出现在此处」的虚影 */
    private float tbGhostX = 0, tbGhostY = 0;
    /** 落点是否在某个容器 C 口内（嵌套插入） */
    private boolean tbNest = false;
    /** tbNestChild：嵌进该口时插到第几个子块之前（0..n，n=末尾）。#实测：支持插进一列积木中间 */
    private int tbNestId = -1, tbNestSlot = 0, tbNestChild = 0;
    /** 抓取偏移：虚影块左上相对光标的偏移（按抓取点，非正中） */
    private float tbGrabX = 14, tbGrabY = 14;

    // ================= #162：拖拽判定（连接口配对，非光标猜测） =================
    //      松手时遍历所有可连接口，按距离取最近且合法的候选；
    //      搜索半径随更优候选动态收缩；只有松手那一刻才真正建立连接。
    /** 吸附半径（workspace 单位；不随 zoom 缩放，缩放时手感等比变化） */
    private static final float SNAP_RADIUS = 48f;
    /** 0 无候选 / 1 叠到目标下 / 2 置于目标上 / 3 嵌进容器 C 口 / 4 嵌进内嵌输入窗口 */
    private int snapKind = 0;
    private CLBC_Block snapTarget = null;
    private int snapSlot = 0;
    /** 嵌进 C 口时插到该口第几个子块之前（0..n，n=末尾）；配合 snapKind=3。 */
    private int snapChildAt = 0;
    private CLBC_BlockRenderer.SocketHit snapSocket = null;
    /** 高亮连接口的位置（逻辑屏幕坐标） */
    private float snapMarkX = 0, snapMarkY = 0;

    /** C：历史快照估算字节上限（8GB 机器上留 64MB，大工程超限丢最旧帧，防吃满内存）。 */
    private static final long HIST_BYTES_CAP = 64L * 1024 * 1024;

    /** 构造即初始化历史栈（空状态为历史第 0 帧） */
    /** 结构变更回调（拖放 / 编辑 / 增删后由 commit 触发），渲染层挂接以刷新代码面板 */
    public Runnable onStructureChanged = null;

    public CLBC_CanvasHost() { initHistory(); }

    /** 工具箱点击/拖拽落盘：按种类创建并落在当前视野中心（#130：复用 buildSpecBlock 保证产物一致） */
    public void addBlock(String kind, long color, String text) {
        CLBC_Block b = buildSpecBlock(null, null, text, kind, color);
        float wx = (lastRW / 2f) / zoom - panX;   // 视野中心换算回缩放后的世界坐标
        float wy = (lastRH / 2f) / zoom - panY;
        b.x = wx; b.y = wy;
        blocks.add(b);
        commit();
        CLBC_Util.log("画布新增积木: " + text);
    }

    /** 工具箱「语法」分类：按纯语法定义表创建带内嵌输入窗口的积木（复用 buildSpecBlock） */
    public void addCoreBlock(String key) {
        CLBC_Block b = buildSpecBlock(key, null, null, null, CLBC_Lang.categoryColor("语法"));
        float wx = (lastRW / 2f) / zoom - panX;
        float wy = (lastRH / 2f) / zoom - panY;
        b.x = wx; b.y = wy;
        blocks.add(b);
        commit();
        CLBC_Util.log("画布新增纯语法积木: " + key + "（输入窗口 " + b.inputs.size() + " 个）");
    }

    /** 工具箱库成员点击：新建一个带「类/方法下拉 + 参数内嵌口/C 口」的调用积木（默认无实参，复用 buildSpecBlock） */
    public void addCallBlock(String ref) {
        CLBC_Block b = buildSpecBlock(null, ref, null, null, CLBC_Lang.categoryColor("系统"));
        float wx = (lastRW / 2f) / zoom - panX;
        float wy = (lastRH / 2f) / zoom - panY;
        b.x = wx; b.y = wy;
        blocks.add(b);
        commit();
        CLBC_Util.log("画布新增调用积木: " + ref);
    }

    /** #130：按工具箱条目规格构造一块积木（不加入画布、不定位、不 commit）。
     *  addBlock/addCoreBlock/addCallBlock 与工具箱拖拽共用，保证点击与拖拽产物完全一致；
     *  调用积木的 id 由 makeCallBlock 给 -1，这里补成 nextId++，避免 id 冲突 / idIndex 互踩。 */
    private CLBC_Block buildSpecBlock(String coreKey, String callRef, String text, String kind, long color) {
        return buildSpecBlock(null, coreKey, callRef, text, kind, color);
    }

    /** #164：登记器优先。specId 非空时整块由 CLBC_BlockRegistry 按定义生成，
     *  保证带合法 key / args / inputs，BlocksToCode 一定能还原成对应 Java。 */
    private CLBC_Block buildSpecBlock(String specId, String coreKey, String callRef,
                                      String text, String kind, long color) {
        if (specId != null && !specId.isEmpty()) {
            CLBC_Block rb = clbc.blocks.CLBC_BlockRegistry.create(specId);
            if (rb != null) { rb.id = nextId++; return rb; }
            CLBC_Util.log("!!!! 登记器未产出积木: " + specId + "，回退旧构造");
        }
        CLBC_Block b;
        if (coreKey != null) {
            clbc.blocks.CLBC_CoreSyntax.Def d = clbc.blocks.CLBC_CoreSyntax.def(coreKey);
            if (d == null) {
                b = new CLBC_Block(nextId++, "stack", color, coreKey);
            } else {
                java.util.List<String> args = new ArrayList<>();
                for (int i = 0; i < d.ins.length; i++) {
                    // 枚举下拉槽默认值取候选首项，避免落成不在候选里的自由文本
                    String[] dd = clbc.blocks.CLBC_CoreSyntax.dropdownOptions(d.key, i);
                    if (dd != null && dd.length > 0) { args.add(dd[0]); continue; }
                    args.add(switch (d.ins[i]) {
                        case "num"  -> "0";
                        case "bool" -> "true";
                        case "str"  -> "名称";
                        default     -> "";
                    });
                }
                String t = clbc.blocks.CLBC_CoreSyntax.text(d.key, args);
                b = new CLBC_Block(nextId++, d.kind, CLBC_Lang.categoryColor("语法"), t);
                b.key = d.key;
                b.args = new ArrayList<>(args);
                for (int i = 0; i < args.size(); i++) {
                    CLBC_Block.Input in = new CLBC_Block.Input(args.get(i), "bool".equals(d.ins[i]));
                    in.row = 0; in.ordinal = i; in.accept = d.ins[i];
                    in.options = clbc.blocks.CLBC_CoreSyntax.dropdownOptions(d.key, i);
                    b.inputs.add(in);
                }
                if (CLBC_BlockShape.isContainer(d.kind)) {
                    b.ensureSlots(1);
                    b.slots.get(0).label = t;
                    b.slots.get(0).expr = (d.ins.length > 0 && "bool".equals(d.ins[0])) ? args.get(0) : "";
                }
            }
        } else if (callRef != null) {
            int h = callRef.indexOf('#');
            String cs = h > 0 ? callRef.substring(0, h) : callRef;
            String mn = h > 0 ? callRef.substring(h + 1) : "";
            b = CLBC_Block.makeCallBlock(cs, mn, new java.util.ArrayList<>());
            if (b == null) b = new CLBC_Block(nextId++, "stack", color, callRef + "()");
            else b.id = nextId++;   // makeCallBlock 给 -1，补真实 id
        } else {
            b = new CLBC_Block(nextId++, kind, color, text);
        }
        return b;
    }

    // ================= #130 工具箱拖拽插入：从工具箱「拖出」到目标位置，松手按落点插入 =================

    /** 工具箱条目按下即开始拖拽（由 CLBC_Toolbox.draw 在 mouseJustPressed && hovered 时触发；已拖拽则忽略） */
    public void beginToolboxDrag(String coreKey, String callRef, String text, String kind, long color) {
        beginToolboxDrag(null, coreKey, callRef, text, kind, color);
    }

    /** #164：带登记器 specId 的拖拽入口（工具箱现在一律走这个） */
    public void beginToolboxDrag(String specId, String coreKey, String callRef,
                                 String text, String kind, long color) {
        if (toolboxDragging) return;
        toolboxDragging = true;
        tbSpecId = specId;
        tbCoreKey = coreKey; tbCallRef = callRef; tbText = text;
        tbKind = (kind != null) ? kind : "stack";
        tbColor = color;
        tbGhostBlock = buildSpecBlock(specId, coreKey, callRef, text, tbKind, color);
        tbGhostX = lmx() - lastRX - panX - tbGrabX;
        tbGhostY = lmy() - lastRY - panY - tbGrabY;
        tbNest = false; tbNestId = -1; tbNestSlot = 0;
        hintY = Float.NaN;
        resetDragSnap();
        CLBC_Util.logFine("工具箱拖拽开始: " + (coreKey != null ? coreKey : (callRef != null ? callRef : text)));
    }
    public boolean isToolboxDragging() { return toolboxDragging; }

    /** 拖拽中：虚影跟随光标，并推演落点（顶层插入线 / 嵌套容器 C 口 / 无效）。 */
    private void updateToolboxDrag(float regionX, float regionY, float regionW, float regionH) {
        if (tbGhostBlock == null) return;
        CLBC_BlockRenderer.measureBlock(tbGhostBlock);
        tbGhostX = lmx() - lastRX - panX - tbGrabX;
        tbGhostY = lmy() - lastRY - panY - tbGrabY;

        boolean overTB = CLBC_GL_API.mouseX >= CLBC_Toolbox.lastX
                && CLBC_GL_API.mouseX <= CLBC_Toolbox.lastX + CLBC_Toolbox.lastW
                && CLBC_GL_API.mouseY >= CLBC_Toolbox.lastY
                && CLBC_GL_API.mouseY <= CLBC_Toolbox.lastY + CLBC_Toolbox.lastH;
        boolean inCanvas = CLBC_GL_API.mouseX >= regionX && CLBC_GL_API.mouseX <= regionX + regionW
                && CLBC_GL_API.mouseY >= regionY && CLBC_GL_API.mouseY <= regionY + regionH;
        if (overTB || !inCanvas) {
            tbNest = false; tbNestId = -1; hintY = Float.NaN; resetDragSnap(); return;
        }
        // #162：不再靠「光标落在哪个矩形里」猜落点，改用连接口最近配对
        updateToolboxConnections(tbGhostBlock);
    }

    /** 顶层插入落点：与 computeInsertHint 同算法，作用对象换成虚影块（不依赖 dragId/dragGroup）。 */
    private void toolboxComputeHint(CLBC_Block b) {
        hintY = Float.NaN;
        CLBC_Block best = null;
        for (CLBC_Block o : blocks) {
            if (o.parentId >= 0) continue;
            if (Math.abs(o.x - b.x) > 20) continue;
            if (b.y > o.y + o.h * 0.25f && b.y < o.y + o.h * 0.9f) {
                if (best == null || o.y < best.y) best = o;
            }
        }
        if (best != null) {
            hintX = lastRX + best.x + panX;
            hintY = lastRY + best.y + panY;
            hintW = Math.max(best.w, b.w);
        }
    }

    /** 松手：按落点插入（嵌套 / 顶层插入线），无效落点（工具箱或画布外）则取消。 */
    private void finalizeToolboxDrag(float regionX, float regionY, float regionW, float regionH) {
        boolean overTB = CLBC_GL_API.mouseX >= CLBC_Toolbox.lastX
                && CLBC_GL_API.mouseX <= CLBC_Toolbox.lastX + CLBC_Toolbox.lastW
                && CLBC_GL_API.mouseY >= CLBC_Toolbox.lastY
                && CLBC_GL_API.mouseY <= CLBC_Toolbox.lastY + CLBC_Toolbox.lastH;
        boolean inCanvas = CLBC_GL_API.mouseX >= regionX && CLBC_GL_API.mouseX <= regionX + regionW
                && CLBC_GL_API.mouseY >= regionY && CLBC_GL_API.mouseY <= regionY + regionH;
        // #162：拖到画布任意空白处都能放下，不该因为「没找到插入线」就整次取消。
        boolean valid = inCanvas && !overTB;
        if (valid) {
            CLBC_Block b = buildSpecBlock(tbSpecId, tbCoreKey, tbCallRef, tbText, tbKind, tbColor);
            CLBC_BlockRenderer.measureBlock(b);
            // #164：报告块（oval/hex）拖进内嵌输入窗口 → 成为该窗口的 child 子树，
            // 不进画布顶层列表；这样它才会真正参与生成 Java 表达式。
            if (snapKind == 4 && snapSocket != null && snapSocket.input.child == null) {
                snapSocket.input.child = b;
                commit();
                CLBC_Util.logFine("工具箱拖拽落盘：报告积木嵌入输入窗口（" + b.key + "）");
                resetToolboxDrag();
                return;
            }
            boolean added = false;
            if (tbNest) {
                CLBC_Block t = idIndex.get(tbNestId);
                if (t != null) {
                    // #实测：按落点算出的插入序号插到该口中间，helper 内部已完成 blocks.add
                    nestBlockIntoSlot(b, t, tbNestSlot, tbNestChild);
                    added = true;
                } else {
                    b.parentId = -1;
                }
            } else if (snapKind == 2 && snapTarget != null) {
                // 置于目标上方：直接贴住目标顶边，不做「下推」（下推会把目标又推远一层）
                b.x = snapTarget.x;
                b.y = snapTarget.y - b.h;
                b.parentId = -1;
            } else if (!Float.isNaN(hintY)) {
                applyInsertHint(b);   // 复用已算好的 hintX/hintY，下推同列后续块
            } else {
                b.x = tbGhostX; b.y = tbGhostY; b.parentId = -1;   // 自由摆放
            }
            if (!added) blocks.add(b);
            commit();
            CLBC_Util.logFine("工具箱拖拽落盘: " + (tbCoreKey != null ? tbCoreKey : (tbCallRef != null ? tbCallRef : tbText)));
        } else {
            CLBC_Util.logFine("工具箱拖拽取消（落在工具箱/画布外，未插入）");
        }
        resetToolboxDrag();
    }

    private void resetToolboxDrag() {
        toolboxDragging = false;
        tbSpecId = null;
        tbCoreKey = null; tbCallRef = null; tbText = null; tbKind = "stack"; tbColor = 0;
        tbGhostBlock = null; tbNest = false; tbNestId = -1; tbNestSlot = 0;
        hintY = Float.NaN;
        resetDragSnap();
    }

    /** 绘制拖拽中的「已拖出」虚影块（跟随光标）+ 落点预览。
     *  被拖块是半透明「拖影层」，绝不实心盖住底下积木；
     *  落点只画一条插入线或吸附目标的轮廓高亮，不套两层直角矩形框（旧实现糊成「一坨」的根因）。 */
    private void drawToolboxGhost(float regionX, float regionY) {
        if (!toolboxDragging || tbGhostBlock == null) return;
        CLBC_BlockRenderer.measureBlock(tbGhostBlock);
        float gx = regionX + tbGhostX + panX;
        float gy = regionY + tbGhostY + panY;

        // ① 落点预览（画在拖影之下，先画）
        if (tbNest) {
            // 吸附到 C 口：高亮目标容器自身轮廓
            CLBC_Block t = idIndex.get(tbNestId);
            if (t != null) {
                float[] p = blockScreenPos(t);
                CLBC_BlockRenderer.strokeOutline(t, p[0], p[1], CLBC_GL_API.hex("#2E9E5B"), 3f);
            }
        } else if (!Float.isNaN(hintY)) {
            // 顶层插入：一条水平插入线，不再画套住整块的矩形框
            float lineW = Math.max(hintW, tbGhostBlock.w);
            CLBC_GL_API.fillRect(CLBC_GL_API.hex("#4C97FF"), hintX, hintY - 2, lineW, 4);
        }
        if (snapKind != 0) {
            // #162：高亮「将要接上的那个连接口」+ 在落点画同形半透明插入标记，
            // 这就是「松开鼠标后会出现的变更的虚影」（落位标记）。
            CLBC_GL_API.fillRect(CLBC_GL_API.hex("#FFCC33"), snapMarkX - 18, snapMarkY - 2, 36, 4);
            float[] mark = null;
            if (snapKind == 3 && snapTarget != null) {
                float[] p = blockScreenPos(snapTarget);
                float[] off = CLBC_BlockShape.shapeOf(snapTarget).childOffset(snapSlot);
                mark = new float[]{p[0] + off[0], p[1] + off[1]};
            } else if (!Float.isNaN(hintY) && (snapKind == 1 || snapKind == 2)) {
                mark = new float[]{hintX, hintY};
            }
            if (mark != null) {
                long o = tbGhostBlock.color;
                tbGhostBlock.color = (o & 0x00FFFFFFL) | 0x40000000L;
                try {
                    CLBC_BlockRenderer.drawBlock(tbGhostBlock, mark[0], mark[1]);
                } finally {
                    tbGhostBlock.color = o;
                }
                CLBC_BlockRenderer.strokeOutline(tbGhostBlock, mark[0], mark[1],
                        CLBC_GL_API.hex("#FFCC33"), 2f);
            }
        }

        // ② 半透明拖影：临时把 ghost 的颜色 alpha 降到 ~55%，画完立刻还原，不改数据
        long origColor = tbGhostBlock.color;
        long ghostColor = (origColor & 0x00FFFFFFL) | 0x90000000L;
        tbGhostBlock.color = ghostColor;
        try {
            CLBC_BlockRenderer.drawBlock(tbGhostBlock, gx, gy);
        } finally {
            tbGhostBlock.color = origColor;
        }
        // ③ 拖影轮廓：有效落点用蓝、无效用红，均沿块自身 path 描边（贴合圆角/C 口）
        long edge = (tbNest || !Float.isNaN(hintY) || snapKind == 4)
                ? CLBC_GL_API.hex("#4C97FF") : CLBC_GL_API.rgba(220, 80, 80, 255);
        CLBC_BlockRenderer.strokeOutline(tbGhostBlock, gx, gy, edge, 2f);
    }

    // ================= #162 拖拽：连接口配对 / 高亮 / 松手落位 =================

    /** 收集被拖整堆的 id（含 C 口子块），避免自己吸到自己。 */
    private void collectDragIds(Set<Integer> out) {
        for (CLBC_Block g : dragGroup) {
            out.add(g.id);
            if (g.children != null) for (CLBC_Block c : g.children) collectSubtree(c, out);
            if (g.hasSlots()) for (CLBC_Block.Slot s : g.slots)
                for (CLBC_Block c : s.children) collectSubtree(c, out);
        }
    }

    /** 被拖「整堆」从首块顶到末块底的总高。
     *  #163：只按 dragRoots（同栈链）算；嵌套后代的高度已经含在容器块自己的 h 里，
     *  再拿它们算一遍会把跨度重复放大。 */
    private float dragStackHeight(CLBC_Block b) {
        List<CLBC_Block> roots = dragRoots.isEmpty() ? dragGroup : dragRoots;
        if (roots.isEmpty()) return b.h > 0 ? b.h : CLBC_BlockShape.STACK_H;
        return CLBC_BlockStack.stackHeight(roots);
    }

    /**
     * 计算「待放置块」的最近连接口：
     * 待放置块的可连接口 = 顶凹槽 / 底凸起 / 报告块输出口；
     * 画布上的候选口     = 其它块的顶凹槽、底凸起、容器各 C 口入口、内嵌输入窗口。
     * 半径随更优候选收缩，只保留一个最优解。ghost 既可是画布上正在拖的块，
     * 也可是工具箱里还没落盘的虚影，故位置单独用 gx/gy 传（不依赖 b.x/b.y）。
     */
    private void computeConnections(CLBC_Block ghost, float gx, float gy, float bottomY,
                                    Set<Integer> excl, boolean allowSocket) {
        snapKind = 0; snapTarget = null; snapSocket = null; snapSlot = 0; snapChildAt = 0;
        CLBC_BlockRenderer.measureBlock(ghost);
        final float notch = CLBC_BlockShape.NOTCH_X + CLBC_BlockShape.NOTCH_W / 2f;
        float myTopX = gx + notch, myTopY = gy;
        float best = SNAP_RADIUS;

        // ① 语句堆叠：待放置块顶凹槽 ↔ 目标底凸起；待放置块底凸起 ↔ 目标顶凹槽
        for (CLBC_Block o : blocks) {
            if (o.parentId >= 0 || o.hidden || excl.contains(o.id)) continue;
            CLBC_BlockRenderer.measureBlock(o);
            float tx = o.x + notch;
            float dBelow = (float) Math.hypot(myTopX - tx, myTopY - (o.y + o.h));
            if (dBelow < best) { best = dBelow; snapKind = 1; snapTarget = o; snapSocket = null; }
            float dAbove = (float) Math.hypot(myTopX - tx, bottomY - o.y);
            if (dAbove < best) { best = dAbove; snapKind = 2; snapTarget = o; snapSocket = null; }
        }

        // ② C 口嵌套：待放置块顶凹槽 ↔ 容器每个口的 N+1 个插入点。
        // #实测修复：旧版只算「最后一个子块之后」一个点，所以永远只能追加到末尾，
        // 插不进一列积木中间（Scratch 可以）。现在把「每个子块之前」和「末尾」都作为候选，
        // 取离虚影顶凹槽最近的一个，记录 snapChildAt=插入序号（0=最前，kids.size()=末尾）。
        for (CLBC_Block o : blocks) {
            if (o.hidden || o.collapsed || excl.contains(o.id)) continue;
            CLBC_BlockShape.Shape sh = CLBC_BlockShape.shapeOf(o);
            if (!CLBC_BlockShape.isContainer(sh.kind)) continue;
            float[] pp = blockScreenPos(o);
            float wx = pp[0] - lastRX - panX, wy = pp[1] - lastRY - panY;
            for (int i = 0; i < sh.slotCount; i++) {
                float[] off = sh.childOffset(i);
                float ex = wx + off[0] + notch;
                float ey = wy + off[1];
                List<CLBC_Block> kids = o.slotChildren(i);
                for (int at = 0; at <= kids.size(); at++) {
                    float d = (float) Math.hypot(myTopX - ex, myTopY - ey);
                    if (d < best) {
                        best = d; snapKind = 3; snapTarget = o; snapSlot = i;
                        snapChildAt = at; snapSocket = null;
                    }
                    if (at < kids.size()) {
                        CLBC_Block k = kids.get(at);
                        ey += (k.h > 0 ? k.h : CLBC_BlockShape.STACK_H) + CLBC_BlockRenderer.CHILD_GAP;
                    }
                }
            }
        }

        // ③ 报告块输出口 ↔ 内嵌输入窗口（表达式槽）
        if (allowSocket && ("round".equals(ghost.kind) || "hex".equals(ghost.kind) || ghost.isReporter)) {
            float outX = gx, outY = gy + ghost.h / 2f;
            for (CLBC_Block root : blocks) {
                if (root.parentId >= 0 || root.hidden || excl.contains(root.id)) continue;
                float sx = lastRX + root.x + panX, sy = lastRY + root.y + panY;
                for (CLBC_BlockRenderer.SocketHit hit : CLBC_BlockRenderer.sockets(root, sx, sy)) {
                    if (hit.input.child != null || hit.input.isDropdown() || excl.contains(hit.owner.id)) continue;
                    float cx = hit.x + hit.w / 2f - lastRX - panX;
                    float cy = hit.y + hit.h / 2f - lastRY - panY;
                    float d = (float) Math.hypot(outX - cx, outY - cy);
                    if (d < best) { best = d; snapKind = 4; snapSocket = hit; snapTarget = null; }
                }
            }
        }
    }

    /** 把最优连接口换算成高亮锚点（逻辑屏幕坐标，与 draw 同一套坐标系）。 */
    private void updateSnapMark() {
        final float notch = CLBC_BlockShape.NOTCH_X + CLBC_BlockShape.NOTCH_W / 2f;
        if (snapKind == 1 && snapTarget != null) {
            snapMarkX = lastRX + snapTarget.x + panX + notch;
            snapMarkY = lastRY + snapTarget.y + panY + snapTarget.h;
        } else if (snapKind == 2 && snapTarget != null) {
            snapMarkX = lastRX + snapTarget.x + panX + notch;
            snapMarkY = lastRY + snapTarget.y + panY;
        } else if (snapKind == 3 && snapTarget != null) {
            float[] p = blockScreenPos(snapTarget);
            float[] off = CLBC_BlockShape.shapeOf(snapTarget).childOffset(snapSlot);
            snapMarkX = p[0] + off[0] + notch;
            snapMarkY = p[1] + off[1];
            // #实测：高亮线落到实际插入点（第 snapChildAt 个子块之前），不是口起点
            List<CLBC_Block> kids = snapTarget.slotChildren(snapSlot);
            for (int k = 0; k < snapChildAt && k < kids.size(); k++) {
                CLBC_Block kid = kids.get(k);
                snapMarkY += (kid.h > 0 ? kid.h : CLBC_BlockShape.STACK_H) + CLBC_BlockRenderer.CHILD_GAP;
            }
        } else if (snapKind == 4 && snapSocket != null) {
            snapMarkX = snapSocket.x + snapSocket.w / 2f;
            snapMarkY = snapSocket.y + snapSocket.h / 2f;
        }
    }

    /** 画布内积木拖动：每帧重算连接候选，无候选才退回同列插入线。 */
    private void updateDragConnections() {
        hintY = Float.NaN;
        CLBC_Block b = idIndex.get(dragId);
        if (b == null) { resetDragSnap(); return; }
        Set<Integer> excl = new HashSet<>();
        collectDragIds(excl);
        boolean reporter = dragGroup.size() == 1
                && ("round".equals(b.kind) || "hex".equals(b.kind) || b.isReporter);
        computeConnections(b, b.x, b.y, b.y + dragStackHeight(b), excl, reporter);
        updateSnapMark();
        if (snapKind == 0) computeInsertHint(lastRX, lastRY);
    }

    /** 工具箱虚影拖动：与画布内拖动共用同一套连接口配对，映射回 tbNest / 插入线落点。 */
    private void updateToolboxConnections(CLBC_Block ghost) {
        Set<Integer> excl = new HashSet<>();   // 虚影尚未进 blocks，自身无子树需排除
        // #164：从工具箱拖出的 oval/hex 报告块必须能吸附到内嵌表达式窗口，
        // 否则「程序化生成的报告积木」永远只能悬空、转不进 Java 表达式。
        boolean reporter = "round".equals(ghost.kind) || "hex".equals(ghost.kind) || ghost.isReporter;
        computeConnections(ghost, tbGhostX, tbGhostY, tbGhostY + ghost.h, excl, reporter);
        updateSnapMark();
        if (snapKind == 4 && snapSocket != null) {
            tbNest = false; tbNestId = -1; hintY = Float.NaN;
        } else if (snapKind == 3 && snapTarget != null) {
            tbNest = true; tbNestId = snapTarget.id; tbNestSlot = snapSlot;
            tbNestChild = snapChildAt;   // 插到该口第几个子块之前
            hintY = Float.NaN;
        } else if ((snapKind == 1 || snapKind == 2) && snapTarget != null) {
            tbNest = false; tbNestId = -1;
            float px = snapTarget.x;
            float py = snapKind == 1 ? snapTarget.y + snapTarget.h : snapTarget.y - ghost.h;
            hintX = lastRX + px + panX; hintY = lastRY + py + panY;
            hintW = Math.max(snapTarget.w, ghost.w);
        } else {
            tbNest = false; tbNestId = -1;
            toolboxComputeHint(ghost);
        }
    }

    /** 吸附后首块的 world 坐标；C 口/输入窗口由宿主布局决定，返回 null。 */
    private float[] snappedWorldPos(CLBC_Block b) {
        if (snapKind == 1 && snapTarget != null)
            return new float[]{snapTarget.x, snapTarget.y + snapTarget.h};
        if (snapKind == 2 && snapTarget != null)
            return new float[]{snapTarget.x, snapTarget.y - dragStackHeight(b)};
        return null;
    }

    /** 拖动中绘制：高亮目标连接口 + 在「松手后会落在这里」画半透明同形插入标记。 */
    private void drawDragSnapPreview(float regionX, float regionY) {
        if (dragId < 0 || !CLBC_GL_API.mouseDown || snapKind == 0) return;
        long hi = CLBC_GL_API.hex("#FFCC33");   // 高亮连接口色
        if (snapKind == 3 && snapTarget != null) {
            float[] p = blockScreenPos(snapTarget);
            CLBC_BlockRenderer.strokeOutline(snapTarget, p[0], p[1], hi, 3f);
        } else if (snapKind == 4 && snapSocket != null) {
            CLBC_GL_API.strokeRect(hi, snapSocket.x - 2, snapSocket.y - 2,
                    snapSocket.w + 4, snapSocket.h + 4, 2f);
        } else if (snapTarget != null) {
            float[] p = blockScreenPos(snapTarget);
            CLBC_BlockRenderer.strokeOutline(snapTarget, p[0], p[1], hi, 2f);
        }
        // 连接口短横线
        CLBC_GL_API.fillRect(hi, snapMarkX - 18, snapMarkY - 2, 36, 4);

        CLBC_Block b = idIndex.get(dragId);
        if (b == null) return;
        float[] wp = snappedWorldPos(b);
        if (wp == null) return;
        float gx = regionX + wp[0] + panX, gy = regionY + wp[1] + panY;
        long orig = b.color;
        b.color = (orig & 0x00FFFFFFL) | 0x50000000L;   // 半透明插入标记，不实心盖住底下积木
        try {
            CLBC_BlockRenderer.drawBlock(b, gx, gy);
        } finally {
            b.color = orig;
        }
        CLBC_BlockRenderer.strokeOutline(b, gx, gy, CLBC_GL_API.hex("#4C97FF"), 2f);
    }

    /** 松手真正建立连接。 */
    private boolean applyDragConnection(CLBC_Block b, float regionX, float regionY, float regionW, float regionH) {
        // 删除区（工具箱）优先于连接
        boolean overToolbox = CLBC_GL_API.mouseX >= CLBC_Toolbox.lastX
                && CLBC_GL_API.mouseX <= CLBC_Toolbox.lastX + CLBC_Toolbox.lastW
                && CLBC_GL_API.mouseY >= CLBC_Toolbox.lastY
                && CLBC_GL_API.mouseY <= CLBC_Toolbox.lastY + CLBC_Toolbox.lastH;
        if (overToolbox) return false;   // 交给调用方按删除处理

        if (snapKind == 4 && snapSocket != null && !inSubtree(b, snapSocket.owner.id)) {
            snapSocket.input.child = b;
            b.parentId = -1;
            blocks.remove(b);
            editingInput = null;
            CLBC_Util.logFine("拖拽吸附：报告积木嵌入输入窗口");
            return true;
        }
        if (snapKind == 3 && snapTarget != null && !inSubtree(b, snapTarget.id)) {
            // #163：只有同栈链的根改 parentId；嵌套后代继续挂在原父块下，整棵树不被拍平
            // #实测：按 snapChildAt 插到该口指定位置（不再是只能追加末尾），后续兄弟整体下推
            List<CLBC_Block> roots = dragRoots.isEmpty() ? java.util.Collections.singletonList(b) : dragRoots;
            nestGroupIntoSlot(roots, dragStackHeight(b), snapTarget, snapSlot, snapChildAt);
            CLBC_Util.logFine("拖拽吸附：整堆（链 " + roots.size() + " 块 / 共 " + dragGroup.size()
                    + " 块）嵌进 " + snapTarget.kind + " #" + snapTarget.id
                    + " 第 " + (snapSlot + 1) + " 口第 " + (snapChildAt + 1) + " 位");
            return true;
        }
        float[] wp = snappedWorldPos(b);
        if (wp != null) {
            float dx = wp[0] - b.x, dy = wp[1] - b.y;
            if (snapKind == 1) {
                // 让位：给新块腾位置，同列后续块整体下推
                float delta = dragStackHeight(b) + CLBC_BlockRenderer.CHILD_GAP;
                for (CLBC_Block o : blocks) {
                    if (o.parentId >= 0 || dragGroup.contains(o) || o.hidden) continue;
                    if (Math.abs(o.x - wp[0]) <= 20f && o.y + 1e-3f >= wp[1]) o.y += delta;
                }
            }
            for (CLBC_Block g : dragGroup) { g.x += dx; g.y += dy; }
            // #163：只把同栈链恢复为顶层；嵌套后代的 parentId 保持指向链中父块
            for (CLBC_Block g : dragRoots) g.parentId = -1;
            CLBC_Util.logFine("拖拽吸附：" + (snapKind == 1 ? "叠到目标下方" : "置于目标上方")
                    + " #" + snapTarget.id + "（携带 " + dragGroup.size() + " 块）");
            return true;
        }
        return false;
    }

    private void resetDragSnap() {
        snapKind = 0; snapTarget = null; snapSocket = null; snapSlot = 0; snapChildAt = 0;
    }

    /** 代码→积木 的批量导入入口：接收已布局好的扁平积木（带 kind/color/text/name/x/y/parentId）。
     *  重分配 id 并同步修正 parentId（保证 积木→代码 重建树时父子关系不丢）。
     *  默认追加；打开／切换工程请走带 replace 的重载以「先清空再导入」。 */
    public void importBlocks(List<CLBC_Block> imported) {
        importBlocks(imported, false, new ArrayList<>());
    }

    /** replace=true：打开／切换工程用，先清空画布再导入（替换语义）；
     *  replace=false：反编译面板「导入到积木画布」、代码解析导入用，追加不变。 */
    public void importBlocks(List<CLBC_Block> imported, boolean replace) {
        importBlocks(imported, replace, new ArrayList<>());
    }

    /** #131 扩展：导入积木的同时把代码里的 // 注释还原为独立的注释便利贴框（复用已有 Comment 模型）。
     *  replace=true 时先清空旧注释再建；replace=false 时在原注释基础上追加。 */
    public void importBlocks(List<CLBC_Block> imported, boolean replace,
                             List<CodeToBlocks.CommentData> importedComments) {
        if (replace) {
            blocks.clear();
            comments.clear();
            selection.clear();
            editingInput = null;
            editOwner = null;
            closeDropdown();
            menuBlockId = -1;
            dragId = -1;
            dragGroup.clear();
            dragGroupStart.clear();
            dragRoots.clear();
            selecting = false;
            panning = false;
            CLBC_GL_API.focusedTextField = -1;
            // 不再在此 initHistory()：否则历史第 0 帧是空画布，撤销会把整个工程撤没。
        }
        Map<Integer, Integer> remap = new HashMap<>();
        for (CLBC_Block b : imported) {
            int newId = nextId++;
            remap.put(b.id, newId);
            b.id = newId;
        }
        for (CLBC_Block b : imported) {
            if (b.parentId >= 0) b.parentId = remap.getOrDefault(b.parentId, -1);
            clampCoords(b);                       // C：导入坐标非有限值拦住，不许污染绘图
            blocks.add(b);
        }
        // #165：// 注释 → 独立便签，按源码锚点定位到「注释前那条积木」右侧的空白处
        addImportedComments(importedComments, remap);
        recomputeNextId();                        // C：重算到 max(existing id)+1，新增块不撞已有 id
        if (replace) {
            // C：切换工程后撤销应回到「刚打开的工程状态」而非空画布——以导入结果为历史第 0 帧基线。
            // 不调用 commit：避免 autosave 写盘（打开工程本就从磁盘读入，无需立刻回写）、也不压重复帧。
            dirtySinceSave = false;                 // 打开/切换工程＝干净基线，刚打开不算未保存
            initHistory();
            fireStructureChanged();               // 维持段1既有行为：打开工程后由 BlocksToCode 刷新右侧代码面板
        } else {
            commit();
        }
        CLBC_Util.log("代码→积木：导入 " + imported.size() + " 个积木"
                + (replace ? "（替换，历史基线=刚打开的工程）" : "")
                + (importedComments != null && !importedComments.isEmpty()
                    ? "，" + importedComments.size() + " 个注释框" : ""));
    }

    /** #165：源码注释数据 → 画布便签。
     *  ① 锚点 id 按导入时的重映射表换算（CodeToBlocks 的 id 属于源码侧，画布会重新分配）；
     *  ② 便签摆到锚点积木右侧空白处，共用锚点的多条注释纵向错开，不再压在积木／C 口内腔上；
     *  ③ orderY 保留源码顺序，回写代码时不受便签位置影响。 */
    private void addImportedComments(List<CodeToBlocks.CommentData> data, Map<Integer, Integer> remap) {
        if (data == null) return;
        Map<Integer, Integer> used = new HashMap<>();
        for (CodeToBlocks.CommentData cd : data) {
            Comment c = new Comment();
            c.x = cd.x; c.y = cd.y; c.w = cd.w; c.h = cd.h;
            c.text = cd.text == null ? "" : cd.text;
            c.orderY = cd.sortY();
            c.anchorIsNext = cd.anchorIsNext;
            int aid = cd.anchorId >= 0 ? remap.getOrDefault(cd.anchorId, -1) : -1;
            c.anchorId = aid;
            CLBC_Block a = null;
            if (aid >= 0) for (CLBC_Block b : blocks) if (b.id == aid) { a = b; break; }
            if (a != null && !a.hidden) {
                CLBC_BlockRenderer.measureBlock(a);
                // 嵌套在 C 口里的子块用屏幕位置反推世界坐标；顶层块直接用世界坐标
                float ax, ay;
                if (a.parentId >= 0) {
                    float[] sp = blockScreenPos(a);
                    ax = sp[0] - lastRX - panX;
                    ay = sp[1] - lastRY - panY;
                } else { ax = a.x; ay = a.y; }
                // 锚点在 C 口内时，便签放到最外层容器右边界之外，绝不压在积木 / 内腔上
                float right = ax + (a.w > 0 ? a.w : 140f);
                if (a.parentId >= 0) {
                    CLBC_Block outer = a;
                    for (int g = 0; g < 64 && outer.parentId >= 0; g++) {
                        CLBC_Block p = null;
                        for (CLBC_Block q : blocks) if (q.id == outer.parentId) { p = q; break; }
                        if (p == null) break;
                        outer = p;
                    }
                    if (outer != a) {
                        CLBC_BlockRenderer.measureBlock(outer);
                        float[] op = blockScreenPos(outer);
                        right = Math.max(right, op[0] - lastRX - panX + (outer.w > 0 ? outer.w : 200f));
                    }
                }
                int n = used.getOrDefault(aid, 0);
                c.x = right + 36f;
                c.y = ay + n * (c.h + 12f);
                used.put(aid, n + 1);
            }
            clampComment(c);
            comments.add(c);
        }
        commentsNeedLayout = true;
    }

    /** 段2 新增（供主 agent / 段3 接）：代码编辑器「编辑期实时回灌」专用入口。
     *  与 importBlocks(replace=true) 的区别：不重置撤销历史、不写盘、不触发代码面板回环刷新。
     *  段1 的 CLBC_Renderer.pushCodeToCanvas 目前仍调 importBlocks(blocks,true)，会把每次输入停顿的回灌
     *  当成「打开工程」从而重置撤销历史；把该调用改到本方法即可消除该交互（详见段2报告「需裁决」项）。 */
    public void replaceBlocksLive(List<CLBC_Block> imported) {
        replaceBlocksLive(imported, new ArrayList<>());
    }

    /** 段2 新增（供主 agent / 段3 接）：代码编辑器「编辑期实时回灌」专用入口。
     *  与 importBlocks(replace=true) 的区别：不重置撤销历史、不写盘、不触发代码面板回环刷新。
     *  #131：回灌时一并把代码里的 // 注释还原为独立注释框。
     *  段1 的 CLBC_Renderer.pushCodeToCanvas 目前仍调 replaceBlocksLive(blocks,comments)，会把每次输入停顿的回灌
     *  当成「打开工程」从而重置撤销历史；把该调用改到本方法即可消除该交互（详见段2报告「需裁决」项）。 */
    public void replaceBlocksLive(List<CLBC_Block> imported, List<CodeToBlocks.CommentData> importedComments) {
        dirtySinceSave = true;                      // 退出询问：代码编辑回灌的积木改动也算未保存
        blocks.clear();
        comments.clear();
        selection.clear();
        editingInput = null;
        editOwner = null;
        closeDropdown();
        menuBlockId = -1;
        dragId = -1;
        dragGroup.clear();
        dragGroupStart.clear();
        dragRoots.clear();
        selecting = false;
        panning = false;
        Map<Integer, Integer> remap = new HashMap<>();
        for (CLBC_Block b : imported) { int nid = nextId++; remap.put(b.id, nid); b.id = nid; }
        for (CLBC_Block b : imported) {
            if (b.parentId >= 0) b.parentId = remap.getOrDefault(b.parentId, -1);
            clampCoords(b);
            blocks.add(b);
        }
        // #165：// 注释 → 独立便签（按源码锚点定位）
        addImportedComments(importedComments, remap);
        recomputeNextId();
        // 视为一次可撤销编辑：保留此前历史，截断 redo 尾巴后压入新帧（帧数 / 字节双上限兜底防膨胀）。
        if (histIdx < history.size() - 1) history.subList(histIdx + 1, history.size()).clear();
        history.add(snapshot());
        histIdx = history.size() - 1;
        while (history.size() > HIST_CAP) { history.remove(0); histIdx--; }
        enforceHistoryBytes();
        // 刻意不 autosave、不 fireStructureChanged：代码面板文本由 Renderer 自己持有，避免回环覆盖用户输入。
    }

    /** 每帧重建嵌套树（children 并集 + 各口 children + idIndex），保证绘制与命中测试一致 */
    private void rebuildTree() {
        for (CLBC_Block b : blocks) {
            b.children.clear();
            if (b.hasSlots()) for (CLBC_Block.Slot s : b.slots) s.children.clear();
        }
        idIndex.clear();
        for (CLBC_Block b : blocks) idIndex.put(b.id, b);
        for (CLBC_Block b : blocks) {
            if (b.parentId >= 0) {
                CLBC_Block p = idIndex.get(b.parentId);
                if (p != null) {
                    // 多口分派：子块按 slotIndex 落进对应分支；children 恒为并集供旧逻辑复用
                    int si = Math.max(0, Math.min(p.slotCount() - 1, b.slotIndex));
                    if (p.hasSlots()) p.slots.get(si).children.add(b);
                    p.children.add(b);
                }
            }
        }
    }

    /** ④-3 连接合法性检查：重算每块 invalid 标记（瞬态），非法者由渲染器标红描边 */
    private void validateConnections() {
        for (CLBC_Block b : blocks) b.invalid = false;
        for (CLBC_Block b : blocks) {
            if (b.parentId >= 0) continue;   // 仅从顶层起遍历整树
            validateNode(b);
        }
    }

    private void validateNode(CLBC_Block b) {
        if (b.parentId >= 0) {
            CLBC_Block p = idIndex.get(b.parentId);
            // reporter/round 被当作语句子块 → 非法（应置于表达式槽，而非独立语句）
            if ((b.kind.equals("round") || b.isReporter) && p != null
                    && (p.kind.equals("class") || p.kind.equals("method")
                        || p.kind.equals("cblock") || p.kind.equals("stack"))) {
                b.invalid = true;
            }
            // 椭圆 reporter 落入 C 形命令口（容器的 slot 子块）→ 非法：C 口只容纳命令栈，
            // 椭圆只能进 hex/圆 表达式槽。落错标红拒绝（对应图3 椭圆块进 C 口的问题）。
            if ((b.kind.equals("round") || b.isReporter) && p != null && p.hasSlots()) {
                boolean inSlot = false;
                for (CLBC_Block.Slot s : p.slots) if (s.children.contains(b)) { inSlot = true; break; }
                if (inSlot) {
                    b.invalid = true;
                    p.invalid = true;
                }
            }
            // 类块必须顶层（不能嵌套进其它块）
            if (b.kind.equals("class")) b.invalid = true;
            // 方法块：类声明已隐式化，顶层方法（parentId<0）合法；仅当父不是类块时才非法
            if (b.kind.equals("method") && p != null && !p.kind.equals("class")) b.invalid = true;
        }
        if (b.children != null) for (CLBC_Block c : b.children) validateNode(c);
    }

    /**
     * 视口剔除判定（真实屏幕坐标，已含 zoom 正变换）。
     *
     * 左侧出界必须用「最右边缘」screenX + screenW 去比可视区左界；用 screenX 比会把
     * 右半截还在屏幕里的积木误判成「已出左侧」而整棵不画。右侧判定同理用最左边缘。
     * margin 随 zoom 等比，并额外为底部燕尾凸起 NOTCH_D 留位置。
     *
     * 抽成静态方法供无窗口自检直接打边界断言，避免「公式看着对、实际没人验」。
     */
    public static boolean isOffscreen(float screenX, float screenY, float screenW, float screenH,
                                      float regionX, float regionY, float regionW, float regionH,
                                      float zoom) {
        float margin = 80f * zoom;
        return screenX + screenW + margin < regionX
                || screenX - margin > regionX + regionW
                || screenY + screenH + CLBC_BlockShape.NOTCH_D * zoom + margin < regionY
                || screenY - margin > regionY + regionH;
    }

    /** 绘制画布（区域 regionX/Y/W/H，调用方已 setClip）。坐标以左上原点、y 向下。
     *  扁平列表每帧重建为树，仅绘制顶层块，子块由渲染器递归绘制。 */
    public void draw(float regionX, float regionY, float regionW, float regionH) {
        lastRX = regionX; lastRY = regionY; lastRW = regionW; lastRH = regionH;
        // B-3：画布绘制期间开启 GL 顶点缩放变换；try/finally 保证任何路径都复位，避免污染其它面板的绘制。
        CLBC_GL_API.pushCanvasZoom(lastRX, lastRY, zoom);
        try {
        rebuildTree();
        validateConnections();   // ④-3 连接合法性标红（每帧重算 invalid）
        if (commentsNeedLayout) layoutComments();   // 首帧按真实画布尺寸摆便签

        // 用户要求：设置了背景壁纸后不要再画网格点阵（壁纸本身已是背景）。
        // 判定必须与 Renderer.drawWallpaper 的真实加载结果一致：不仅路径要有效，
        // 纹理还要真的加载成功（loadTextureFromFileCached 返回非 0）。否则文件损坏/
        // 解码失败时会出现「既没壁纸、又没网格」的空白画布。清除壁纸后网格自动恢复。
        String wp = CLBC_Theme.wallpaper;
        boolean wallpaperOn = wp != null && !wp.isEmpty()
                && CLBC_GL_API.loadTextureFromFileCached(wp) != 0;
        if (!wallpaperOn) drawGrid(regionX, regionY, regionW, regionH);

        // #163：注释连线改到积木之后画（连线层在积木层之上），
        // 否则连线会被后画的积木盖住，看起来像「线动不动就没」。
        // 顶层块遍历 + 视口裁剪：measureBlock 已算出整棵树屏幕包围盒（容器块含全部子块高度），
        // 包围盒完全落在可视区外则跳过其绘制（含递归子块），避免无谓形变与顶点上传。
        int topCount = 0, drawn = 0, culled = 0;
        for (CLBC_Block b : blocks) {
            if (b.parentId >= 0) continue;   // 只画顶层，子块由渲染器递归
            if (b.hidden) continue;          // 隐式结构块（unit.file 头）不绘制
            topCount++;
            CLBC_BlockRenderer.measureBlock(b);
            // 逻辑坐标（直接喂给 zoom 变换）：drawBlock 接收逻辑坐标，由 addVertex 的 zoom 正变换得到屏幕位置。
            float lx = regionX + b.x + panX;
            float ly = regionY + b.y + panY;
            // 真实屏幕坐标：必须与 CLBC_GL_API.addVertex 的 zoom 正变换同一套公式
            //   screen = region + (logical - region) * zoom = region + (b.x + panX) * zoom
            // 之前剔除直接用逻辑 lx/ly（未 ×zoom）当物理像素比，导致非 1 缩放时积木被误剔
            // （「往右滚十几像素消失 / 下滑只剩凸起主体就整块没了」）。
            float screenX = regionX + (b.x + panX) * zoom;
            float screenY = regionY + (b.y + panY) * zoom;
            float screenW = b.w * zoom, screenH = b.h * zoom;
            if (isOffscreen(screenX, screenY, screenW, screenH,
                    regionX, regionY, regionW, regionH, zoom)) {
                culled++;                     // 整树在可视区外，跳过绘制
                continue;
            }
            CLBC_BlockRenderer.drawBlock(b, lx, ly);
            drawn++;
        }

        // 渲染统计：每 600 帧（约 10s @60fps）打印一次，验证视口裁剪收益，避免每帧刷屏
        if (++renderStatTick % 600 == 0 && (drawn > 0 || culled > 0)) {
            CLBC_Util.logFine(String.format("渲染统计：顶层块=%d 绘出=%d 视口裁剪=%d", topCount, drawn, culled));
        }

        // 规则20 优先设计空状态：说明画布将承载什么 + 给出明确下一步（而非只留一片空白网格）
        if (topCount == 0) {
            long hint = CLBC_GL_API.hex("#888888");
            String line1 = "画布是空的", line2 = "点击左侧工具箱里的积木，从这里开始搭建程序";
            float f1 = CLBC_GL_API.measureText(CLBC_GL_API.uiFont, line1);
            float f2 = CLBC_GL_API.measureText(CLBC_GL_API.uiFont, line2);
            CLBC_GL_API.drawText(CLBC_GL_API.uiFont, line1,
                    regionX + (regionW - f1) / 2f, regionY + regionH / 2f - 18, CLBC_GL_API.hex("#575E75"));
            CLBC_GL_API.drawText(CLBC_GL_API.uiFont, line2,
                    regionX + (regionW - f2) / 2f, regionY + regionH / 2f + 8, hint);
        }

        // 内嵌窗口文本编辑：默认只画白色窗口，点击后才覆盖一层即时模式输入框
        if (editingInput != null && editOwner != null) {
            String nv = CLBC_GL_Widgets.textField(editId(), editRect[0], editRect[1],
                    editRect[2], editRect[3], editingInput.value,
                    CLBC_GL_API.hex("#FFFFFF"), CLBC_GL_API.hex("#333333"), editOwner.color);
            if (!nv.equals(editingInput.value)) {
                editingInput.value = nv;
                syncInputToArgs(editOwner, editingInput);   // 窗口内容 ↔ 转译字段同源
                long now = System.currentTimeMillis();
                if (now - lastEditCommit > 800) { commit(); lastEditCommit = now; }
            }
        }

        // 选中高亮：沿块自身轮廓描边，
        // 圆角与 C 口自动贴合；不选「套在块外的直角矩形框」，也不画实心矩形条。
        long selEdge = CLBC_GL_API.hex("#FFCC33");
        for (int id : selection) {
            CLBC_Block b = idIndex.get(id);
            if (b != null && b.parentId < 0) {
                float sx = regionX + b.x + panX, sy = regionY + b.y + panY;
                CLBC_BlockRenderer.strokeOutline(b, sx, sy, selEdge, 3f);
            }
        }

        // #163：连线先画、便利贴后画，二者都在积木之上，且都不再受「最近块 200px」阈值影响
        for (Comment c : comments) drawCommentLink(c);
        for (Comment c : comments) drawComment(c);

        // 框选矩形
        if (selecting) {
            float x0 = Math.min(selX0, selX1), y0 = Math.min(selY0, selY1);
            float w = Math.abs(selX1 - selX0), h = Math.abs(selY1 - selY0);
            CLBC_GL_API.strokeRect(CLBC_GL_API.rgba(80, 140, 255, 200), x0, y0, w, h, 1.5f);
        }

        // B-6：拖动过程中显示「将要插入的位置」高亮线
        drawInsertHint();
        // #162：连接口高亮 + 「松手后会落在这里」的半透明插入标记
        drawDragSnapPreview(regionX, regionY);
        // #130：工具箱拖拽中的「已拖出」虚影块 + 落点预览
        drawToolboxGhost(regionX, regionY);
        } finally {
            CLBC_GL_API.popCanvasZoom();
        }
    }

    /** 绘制无限点阵网格背景。
     *  优化（2026-11-08）：原实现每帧对每个 24px 点调用 fillCircle（1920×1080 下约 3600 次/帧，且每次分配短命数组）。
     *  现改为：一次性烘焙 240×240 的网格平铺纹理（ensureGridTile），本方法仅以该纹理平铺覆盖可视区，
     *  绘制调用降至数十、仅一次纹理切换，且零逐点分配。平铺起点按 pan 偏移对齐保持视觉一致。 */
    private void drawGrid(float x, float y, float w, float h) {
        // 网格永远最底层：先画网格、再画块（draw() 里 drawGrid 在积木循环之前）。
        // setClip 与 clearClip 必须成对：GL 层是批量顶点缓冲 + 立即 scissor 状态，
        // clearClip 内部会先 flushPending，把攒着的网格顶点按「当前画布裁剪」提交，
        // 否则这批顶点会拖到帧末、在后续面板改过的（或已关闭的）裁剪下刷出 → 点阵溢出覆盖工具箱/代码面板。
        CLBC_GL_API.setClip(x, y, w, h);
        try {
            CLBC_GL_API.ensureGridTile();
            float offX = ((panX % 24) + 24) % 24;
            float offY = ((panY % 24) + 24) % 24;
            final float S = 240;   // 平铺纹理尺寸（含 10×10 个 24px 间距圆点）
            // B-3：GL 缩放变换把逻辑区间 [x, x+w/zoom] 映射到物理画布 [x, x+w]，
            // 故平铺必须按逻辑尺寸 w/zoom、h/zoom 覆盖，放大/缩小时才不会露出空白边。
            float lw = w / zoom, lh = h / zoom;
            // 向左/上各多铺一块以消除平移时的边缘缝隙，超界部分由 setClip 裁剪
            for (float ty = y + offY - S; ty < y + lh + S; ty += S)
                for (float tx = x + offX - S; tx < x + lw + S; tx += S)
                    CLBC_GL_API.drawImage(CLBC_GL_API.getGridTile(), tx, ty, S, S);
        } finally {
            // 弹掉自己这层，恢复到 Renderer 在 canvas.draw() 前设的画布裁剪（积木仍受裁剪）
            CLBC_GL_API.clearClip();
        }
    }

    private void drawComment(Comment c) {
        float sx = lastRX + c.x + panX, sy = lastRY + c.y + panY;
        // 便签尺寸由文本决定（长度定宽、行数定高），软换行不写回 c.text。
        // 用户手动缩放过（userPlaced）就尊重其尺寸，只按行数兜底加高避免文字被切。
        boolean editing = CLBC_GL_API.focusedTextField == c.hashCode();
        fitCommentToText(c, editing);
        // 阴影：偏移 3px 的半透明圆角底，制造立体感
        CLBC_GL_API.fillRoundRect(CLBC_GL_API.rgba(70, 70, 40, 36), sx + 3, sy + 4, c.w, c.h, 7);
        CLBC_GL_API.fillRoundRect(CLBC_GL_API.hex("#FFFFCC"), sx, sy, c.w, c.h, 7);
        float barH = 12f;
        CLBC_GL_API.fillRoundRect(CLBC_GL_API.hex("#F2E7A0"), sx + 2, sy + 2, c.w - 4, barH, 5);
        CLBC_GL_API.fillRect(CLBC_GL_API.hex("#F2E7A0"), sx + 2, sy + barH - 4, c.w - 4, 6);
        c.text = CLBC_GL_Widgets.textArea(c.hashCode(), sx + 7, sy + barH + 3, c.w - 14,
                Math.max(16f, c.h - barH - 7),
                c.text, CLBC_GL_API.hex("#FFFFCC"), CLBC_GL_API.hex("#3A3A3A"),
                CLBC_GL_API.hex("#D8C85A"));
        // 边框必须放在文本控件之后（textArea 会铺底色 + 临时裁剪）
        CLBC_GL_API.strokeRoundRect(CLBC_GL_API.hex("#D8C85A"), sx, sy, c.w, c.h, 7, 1.5f);
        CLBC_GL_API.fillRect(CLBC_GL_API.hex("#C7B43F"), sx + c.w - 10, sy + c.h - 10, 9, 9);
    }

    /** 按文本计算便签应占的宽高：宽随最长一行，高随换行后的行数；不做省略号。
     *  只在自动摆放（!userPlaced）或未编辑时兜底，编辑中用户改的文字实时撑开。 */
    private void fitCommentToText(Comment c, boolean editing) {
        String t = c.text == null ? "" : c.text;
        // 【实测修复·挤扁】旧代码 maxW = lastRW / z * 0.34 拿【画布屏幕宽】当便签宽度上限，
        // 而便签 w 是【世界坐标】。移动屏幕/拖分隔条/改窗口时 lastRW 一变，maxW 跟着变，
        // 便签宽度被反复重算压缩 → 右侧不断被向左挤、文字越折越窄、看起来被挤扁。
        // 正解：便签宽度只由文本自然宽度决定，封顶用固定世界常量，绝不随画布区尺寸浮动。
        final float maxW = 360f;   // 世界坐标硬上限，与 lastRW/zoom 完全无关
        final float minW = 96f;
        // 自然宽 = 所有行里最长一行的像素宽 + 内边距；硬换行按 \n 拆
        float natural = 0f;
        String[] hard = t.split("\n", -1);
        for (String ln : hard) natural = Math.max(natural, CLBC_GL_API.measureText(CLBC_GL_API.uiFont, ln));
        float targetW = Math.max(minW, Math.min(maxW, natural + 24f));
        if (!c.userPlaced || editing) c.w = targetW;
        float innerW = c.w - 14f;
        int lines = CLBC_GL_Widgets.wrappedLineCount(t, innerW);
        lines = Math.max(1, lines);
        float needH = 12f + 3f + lines * CLBC_GL_Widgets.wrappedLineHeight() + 8f;
        // 直接赋值（不累积）：自动摆放的便签高度恒等于文本所需，编辑时随输入增减。
        // userPlaced 的便签保留用户拖出的高度，但至少能容纳文本。
        // 用户要求「不要截断行」：便签高度完全按文本所需行数，不再设 420px 上限，
        // 否则超长注释的最后几行会被 textArea 的框高裁剪掉。
        c.h = c.userPlaced ? Math.max(c.h, needH) : needH;
    }

    /** 命中注释便利贴区域（屏幕坐标，与 drawComment 同一套 lastRX/lastRY+pan 算法） */
    private boolean commentAt(float mx, float my) {
        for (Comment c : comments) {
            float sx = lastRX + c.x + panX, sy = lastRY + c.y + panY;
            if (mx >= sx && mx <= sx + c.w && my >= sy && my <= sy + c.h) return true;
        }
        return false;
    }

    /** #实测修复：便签跟随【锚点积木】定位，不再塞进当前可视区。
     *  用户要的是「贴在被注释积木正右侧」——便签是画布内容的一部分，超出可视区时
     *  画布可平移查看（clampPan 已把便签纳入内容包围盒），而不是硬夹回屏幕造成重叠。
     *  实测旧实现 15 条便签 66 对互相重叠、13 条压在积木上：根因是所有便签被
     *  clamp 到同一个 right-c.w 列 + 同一个 bottom 行。新策略：
     *   ① x = 锚点世界右边界 + GAP（每条按自己的锚点，不强求同列）；
     *   ② y = 锚点世界顶；同一锚点多便签纵向错开；
     *   ③ 按 y 排序后逐条做冲突消解：与已摆便签/积木相交就沿 y 下移到空位；
     *   ④ 只做一次，用户手动拖过的便签不动。 */
    private void layoutComments() {
        if (lastRW <= 0 || lastRH <= 0) return;   // 首帧还没拿到画布尺寸，下一帧再摆
        commentsNeedLayout = false;
        final float GAP = 14f;                    // 便签与锚点积木的间距
        final float STACK_GAP = 8f;               // 便签彼此的纵向间距

        // 先把所有便签尺寸按文本算准（含软换行行数），后面避让要用真实宽高
        for (Comment c : comments) {
            if (c.userPlaced) continue;
            fitCommentToText(c, false);
        }
        // 按锚点 y 排序：便签纵向顺序与积木一致
        List<Comment> ordered = new ArrayList<>(comments);
        ordered.sort(java.util.Comparator.comparingDouble(c -> {
            CLBC_Block a = commentAnchor(c);
            if (a != null) {
                float[] p = blockScreenPos(a);
                return p[1] - lastRY - panY;
            }
            return Float.isFinite(c.orderY) ? c.orderY : c.y;
        }));

        // 积木世界包围盒：便签不得压在任何积木上
        List<float[]> boxes = new ArrayList<>();
        for (CLBC_Block b : blocks) {
            if (b.hidden) continue;
            CLBC_BlockRenderer.measureBlock(b);
            float[] p = blockScreenPos(b);
            boxes.add(new float[]{p[0] - lastRX - panX, p[1] - lastRY - panY,
                    b.w > 0 ? b.w : 140f, b.h > 0 ? b.h : CLBC_BlockShape.STACK_H});
        }

        List<float[]> placed = new ArrayList<>();   // 已摆便签 [x,y,w,h]
        Map<Integer, Integer> sameAnchor = new HashMap<>();
        for (Comment c : ordered) {
            if (c.userPlaced) {
                placed.add(new float[]{c.x, c.y, c.w, c.h});
                continue;
            }
            CLBC_Block anchor = commentAnchor(c);
            float x, y, preferredX;
            if (anchor != null) {
                float[] p = blockScreenPos(anchor);
                float ax = p[0] - lastRX - panX;
                float ay = p[1] - lastRY - panY;
                float aw = anchor.w > 0 ? anchor.w : 140f;
                x = ax + aw + GAP;                     // 正右侧，不夹到可视区
                int n = sameAnchor.getOrDefault(anchor.id, 0);
                y = ay + n * (c.h + STACK_GAP);        // 同锚点多便签纵向错开
                sameAnchor.put(anchor.id, n + 1);
            } else {
                // 【实测修复·图层覆盖】锚点丢失的便签旧代码摆到 x=40（画布左上角），
                // 正好压在积木堆上（截图实证：一排黄便签糊在橙积木上），这才是用户反复说的
                // 「图层覆盖」。改为先放到所有积木世界右界之外，再走统一的右移避让，
                // 保证无锚点便签也不压任何积木；y 保留源码顺序。
                float worldMax = 0f;
                for (float[] bx : boxes) worldMax = Math.max(worldMax, bx[0] + bx[2]);
                x = worldMax + GAP;
                // 无锚点便签彼此也纵向错开（用 -1 作公共 key），避免多条同 y 只能靠右移排成一长排
                int k = sameAnchor.getOrDefault(-1, 0);
                float baseY = Float.isFinite(c.orderY) ? c.orderY : c.y;
                y = baseY + k * (c.h + STACK_GAP);
                sameAnchor.put(-1, k + 1);
            }
            preferredX = x;   // 锚点右侧首选列，右移到底后回列用
            // 冲突消解：用户明确要求「注释盖住别的东西时往右侧移，不要往下挤」。
            // 旧实现沿 y 每步 +8 往下挤，结果便签被推到画布底部、压住文件树/溢出可视区。
            // 新策略：优先沿 x 往右找空位（保持锚点 y 对齐，语义上仍贴着被注释积木右侧）；
            // 右移到画布右缘仍被占，才回到首选列并向下换行，避免无限右移越界。
            int guard = 0;
            // 【第三遍修复·真凶】旧代码 rightLimit = lastRW - c.w - 8f 拿【画布屏幕宽度】当右界，
            // 而 x / boxes 全是【世界坐标】（上面已减 lastRX/panX）。世界 x 常远大于画布宽度，
            // 于是 x+8<=rightLimit 永远 false → 每次冲突都走「回列+下移」→ 便签被挤到画布底部压住
            // 文件树，用户看到的「一直在往下挤、就是往右挤」正是这个坐标系错配。
            // 正确做法：便签是画布内容，超出可视区靠平移查看（clampPan 已纳入便签包围盒），
            // 右移不该有画布宽度上限；一路往右找空位，直到越过所有已摆内容右界 + 一屏的兜底余量。
            float worldMaxRight = 0f;
            for (float[] bx : boxes) worldMaxRight = Math.max(worldMaxRight, bx[0] + bx[2]);
            for (float[] pn : placed) worldMaxRight = Math.max(worldMaxRight, pn[0] + pn[2]);
            float rightLimit = worldMaxRight + Math.max(lastRW, 800f);
            while (blocked(x, y, c.w, c.h, boxes, placed) && guard++ < 400) {
                if (x + 8f <= rightLimit) {
                    x += 8f;                        // 首选：一直往右移（坐标系已统一为世界坐标）
                } else {
                    x = preferredX;                 // 极端兜底：越过所有内容+一屏仍冲突才回列
                    y += c.h + STACK_GAP;           // 换到下一行
                }
            }
            c.x = x; c.y = y;
            placed.add(new float[]{x, y, c.w, c.h});
        }
    }

    /** 从首选位置出发找不压积木、不压其他便签的空位：先向下 → 向上 → 横向错列。 */
    private static float[] findFreeSpot(float x, float y, float w, float h,
                                        float minX, float minY, float right, float bottom,
                                        List<float[]> boxes, List<float[]> placed) {
        y = Math.max(minY, Math.min(bottom - h, y));
        x = Math.max(minX, Math.min(right - w, x));
        // 三个候选 x：首选、右移一列、左移（仅当首选被占）
        float[] xs = {x, Math.max(minX, Math.min(right - w, x + w + 10f)), x};
        for (float cx : xs) {
            // 向下
            for (float cy = y; cy + h <= bottom + 0.1f; cy += 10f)
                if (!blocked(cx, cy, w, h, boxes, placed)) return new float[]{cx, cy};
            // 向上
            for (float cy = y - 10f; cy >= minY - 0.1f; cy -= 10f)
                if (!blocked(cx, cy, w, h, boxes, placed)) return new float[]{cx, Math.max(minY, cy)};
        }
        // 兜底：贴右边界、底部内侧（极端密集时至少不越界）
        return new float[]{x, Math.max(minY, bottom - h)};
    }
    private static boolean blocked(float x, float y, float w, float h,
                                   List<float[]> boxes, List<float[]> placed) {
        for (float[] bx : boxes) if (overlap(x, y, w, h, bx[0], bx[1], bx[2], bx[3])) return true;
        for (float[] pn : placed) if (overlap(x, y, w, h, pn[0], pn[1], pn[2], pn[3])) return true;
        return false;
    }
    /** 两个矩形是否相交（带 2px 容差） */
    private static boolean overlap(float ax, float ay, float aw, float ah,
                                   float bx, float by, float bw, float bh) {
        return ax < bx + bw + 2f && ax + aw + 2f > bx && ay < by + bh + 2f && ay + ah + 2f > by;
    }

    /** #实测修复 + #已知限制实修：连线始终可见，但绝不斜穿整片积木。
     *  · 每张便签恒有 14px 短尾巴 + 锚点小圆点（不会动不动就没）；
     *  · 近距（≤110px）：一段正交实线细折线；
     *  · 远距：改走【便签列左侧的竖直通道】——出锚点后水平段画成极淡虚线（不形成连续横线
     *    压住积木文字），竖线落在右侧空白带实线可见，两端带转向小点，最后接回便签尾巴。
     *    这样便签离锚点再远也有可见连线，同时不回到「斜穿全屏的长线」。 */
    private void drawCommentLink(Comment c) {
        CLBC_Block anchor = commentAnchor(c);
        if (anchor == null) return;
        CLBC_BlockRenderer.measureBlock(anchor);
        float[] pos = blockScreenPos(anchor);
        float x1 = pos[0] + anchor.w;
        float y1 = pos[1] + Math.min(anchor.h, CLBC_BlockShape.STACK_H) / 2f;
        float x2 = lastRX + c.x + panX, y2 = lastRY + c.y + panY + 10f;
        long line = CLBC_GL_API.hex("#CDD6E2");
        long dot  = CLBC_GL_API.hex("#A9B5C6");
        // 便签端：恒定的短尾巴 + 小方块（锚标记）
        CLBC_GL_API.drawLine(x2 - 14f, y2, x2, y2, line, 1.2f);
        CLBC_GL_API.fillRect(CLBC_GL_API.hex("#C7B43F"), x2 - 3, y2 - 3, 6, 6);
        CLBC_GL_API.fillCircle(dot, x1, y1, 2.5f);
        if (x2 < x1) return;
        boolean near = x2 - x1 <= 110f && Math.abs(y2 - y1) <= 110f;
        if (near) {
            // 正交折线：水平出块 → 垂直对齐 → 水平接便签尾巴
            float elbow = x1 + Math.min(16f, (x2 - x1) / 2f);
            CLBC_GL_API.drawLine(x1, y1, elbow, y1, line, 1.2f);
            CLBC_GL_API.drawLine(elbow, y1, elbow, y2, line, 1.2f);
            CLBC_GL_API.drawLine(elbow, y2, x2 - 14f, y2, line, 1.2f);
        } else {
            long faint = CLBC_GL_API.rgba(169, 181, 198, 110);
            float channel = Math.max(x1 + 20f, x2 - 26f);
            CLBC_GL_API.drawLine(x1, y1, Math.min(x1 + 18f, channel), y1, faint, 1f);
            drawDashedH(Math.min(x1 + 18f, channel), channel, y1, faint, 1f);
            CLBC_GL_API.drawLine(channel, y1, channel, y2, faint, 1f);
            CLBC_GL_API.drawLine(channel, y2, x2 - 14f, y2, line, 1.2f);
            CLBC_GL_API.fillCircle(dot, channel, y1, 2f);
            CLBC_GL_API.fillCircle(dot, channel, y2, 2f);
        }
    }

    /** 水平虚线：远距注释连线的「横向引导」用它，避免一条连续实线横穿积木文字 */
    private static void drawDashedH(float x1, float x2, float y, long color, float width) {
        if (x2 <= x1) return;
        final float DASH = 6f, GAP = 7f;
        for (float x = x1; x < x2; x += DASH + GAP)
            CLBC_GL_API.drawLine(x, y, Math.min(x + DASH, x2), y, color, width);
    }

    /** 取便签的固定锚点；只有从未绑定、或锚点块被删除时才重找一次。 */
    private CLBC_Block commentAnchor(Comment c) {
        CLBC_Block b = c.anchorId >= 0 ? idIndex.get(c.anchorId) : null;
        if (b != null && !b.hidden) return b;
        b = precedingBlock(c);
        if (b != null) c.anchorId = b.id;
        return b;
    }

    /** #165：用户手建便签、或锚点被删时的兜底——选【注释前（y 不大于便签）最近的一条语句积木】，
     *  与几何中心距离无关，语义与「注释要连这个注释前的那个积木」一致。 */
    private CLBC_Block precedingBlock(Comment c) {
        CLBC_Block best = null;
        float bestCost = Float.MAX_VALUE;
        for (CLBC_Block b : blocks) {
            if (b.hidden || b.parentId >= 0) continue;
            CLBC_BlockRenderer.measureBlock(b);
            float[] p = blockScreenPos(b);
            float bx = p[0] - lastRX - panX, by = p[1] - lastRY - panY;
            float gap = c.y - by;                        // >0：块在便签上方（即注释之前）
            float cost = gap >= 0 ? gap : -gap * 4f;     // 没有前置块时才退化到下方最近块
            cost += Math.abs(bx - c.x) * 0.05f;          // 同列优先，只作微调
            if (cost < bestCost) { bestCost = cost; best = b; }
        }
        return best;
    }

    /** #132：平移边界——禁止把画布平移到离所有积木/注释太远（避免「滚一下积木就没了 / 找不到内容」）。
     *  内容包围盒始终保持在「可视区 ± 漫游余量 M」之内；内容大于可视区时退化为「两端不越过视口」，可整体滚动浏览。 */
    private void clampPan(float regionX, float regionY, float regionW, float regionH) {
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        boolean have = false;
        for (CLBC_Block b : blocks) {
            if (b.parentId >= 0 || b.hidden) continue;
            // #162：先量整棵树，b.w/b.h 才是「含全部子块/C 口」的真实宽高。
            // 之前直接用未测算的 b.w（0 → 兜底 200），把近百像素宽的块当成 200px 点，边界判歪。
            CLBC_BlockRenderer.measureBlock(b);
            float w = b.w > 0 ? b.w : 120f, h = b.h > 0 ? b.h : 52f;
            minX = Math.min(minX, b.x); minY = Math.min(minY, b.y);
            maxX = Math.max(maxX, b.x + w); maxY = Math.max(maxY, b.y + h);   // 用右/下边缘，不用左上角
            have = true;
        }
        for (Comment c : comments) {
            minX = Math.min(minX, c.x); minY = Math.min(minY, c.y);
            maxX = Math.max(maxX, c.x + c.w); maxY = Math.max(maxY, c.y + c.h);
            have = true;
        }
        float vw = regionW / zoom, vh = regionH / zoom;   // 视口在 world 坐标下的尺寸
        if (!have) {
            float d = Math.max(vw, vh) * 1.5f;
            panX = Math.max(-d, Math.min(d, panX));
            panY = Math.max(-d, Math.min(d, panY));
            return;
        }
        // 绘制公式：screen = region + (world + pan) * zoom（pan 是 world 单位，不是屏幕像素）。
        // 令内容包围盒始终至少保留 keep 屏幕像素在可视区内：
        //   内容右边缘最左只能到 region + keep      → pan_min = keep/zoom - maxX
        //   内容左边缘最右只能到 region + W - keep  → pan_max = (W-keep)/zoom - minX
        // 这样「往右看」能一直滚到看见最右侧那条边（含积木自身宽度），不会被提前拦；
        // 也不会让画布飞到离积木太远（始终留一条可见边）。
        float keep = Math.min(160f, Math.min(regionW, regionH) * 0.25f);   // 屏幕像素
        float loX = keep / zoom - maxX, hiX = (regionW - keep) / zoom - minX;
        float loY = keep / zoom - maxY, hiY = (regionH - keep) / zoom - minY;
        // 内容比视口小很多时上下界会倒挂，给半屏漫游余量（不要锁死成只能看一块）
        if (loX > hiX) { float mid = (loX + hiX) / 2f, half = Math.max(vw, vh) * 0.5f; loX = mid - half; hiX = mid + half; }
        if (loY > hiY) { float mid = (loY + hiY) / 2f, half = Math.max(vw, vh) * 0.5f; loY = mid - half; hiY = mid + half; }
        panX = Math.max(loX, Math.min(hiX, panX));
        panY = Math.max(loY, Math.min(hiY, panY));
    }

    /** 处理画布输入（窗口坐标命中）。
     *  B-3 缩放：画布内容的命中 / 坐标一律走逻辑鼠标 lmx()/lmy()（缩放逆变换）；
     *  仅「物理画布区」「工具箱区」「平移物理增量」「滚轮缩放锚点」用物理鼠标 CLBC_GL_API.mouseX/mouseY。 */
    public void handleInput(float regionX, float regionY, float regionW, float regionH) {
        rebuildTree();
        // 画布区是物理矩形，用物理鼠标判断（GL 缩放变换只作用于画布内容绘制，不作用于区域本身）
        boolean inRegion = CLBC_GL_API.mouseX >= regionX && CLBC_GL_API.mouseX <= regionX + regionW
                && CLBC_GL_API.mouseY >= regionY && CLBC_GL_API.mouseY <= regionY + regionH;
        float mx = lmx(), my = lmy();   // 逻辑鼠标坐标

        // ===== #130 工具箱拖拽：独占本帧输入，拖拽中只更新虚影/落点，松手按落点插入 =====
        if (toolboxDragging) {
            if (CLBC_GL_API.mouseJustReleased) finalizeToolboxDrag(regionX, regionY, regionW, regionH);
            else updateToolboxDrag(regionX, regionY, regionW, regionH);
            return;
        }

        // ===== B-3 滚轮缩放：指针在画布区内、下拉未展开、未按 Ctrl 时才消费滚轮（按焦点区分）=====
        if (inRegion && dropdownInput == null && !CLBC_GL_API.ctrlDown && CLBC_GL_API.scrollDelta != 0) {
            float old = zoom;
            float factor = CLBC_GL_API.scrollDelta > 0 ? 1.1f : (1f / 1.1f);
            float nz = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, old * factor));
            if (nz != old) {
                // 以鼠标为锚保持其下世界点不动：pan += (物理鼠标 - region) * (1/新zoom - 1/旧zoom)
                panX += (CLBC_GL_API.mouseX - regionX) * (1f / nz - 1f / old);
                panY += (CLBC_GL_API.mouseY - regionY) * (1f / nz - 1f / old);
                zoom = nz;
                clampPan(regionX, regionY, regionW, regionH);   // #132：缩放后重新夹紧，避免内容被推离可视区
                CLBC_Util.logFine(String.format("画布缩放: %.2f → %.2f", old, nz));
            }
        }

        // 右键菜单（在积木上按下右键 → 打开）
        if (inRegion && CLBC_GL_API.mouseRightJustPressed) {
            CLBC_Block hit = blockAt(mx, my);
            if (hit != null) {
                menuBlockId = hit.id;
                // 菜单在 drawPopup 的缩放变换内绘制，锚点用逻辑坐标
                CLBC_GL_Widgets.openContextMenu(hit.id, mx, my,
                        new String[]{"添加注释", "折叠/展开", "复制", "整理积木", "删除", "重命名积木"});
            }
        }

        // ---- 枚举下拉弹层优先：已展开时，本帧点击由下拉消费（选中 / 关闭），不触发拖动 ----
        boolean dropdownConsumed = false;
        if (dropdownInput != null && CLBC_GL_API.mouseJustPressed) {
            dropdownConsumed = true;
            float[] p = dropdownPanelRect();
            boolean inside = p != null && hitL(p[0], p[1], p[2], p[3]);
            int idx = inside ? dropdownHitIndex(mx, my) : -1;
            if (idx >= 0) {
                applyDropdownValue(dropdownOwner, dropdownInput, dropdownInput.options[idx]);
                closeDropdown();
            } else if (!inside) {
                closeDropdown();   // 点面板外即收起
            }
        }
        // 下拉展开时滚轮滚动候选列表（滚轮此刻归下拉消费，上面的缩放条件已用 dropdownInput==null 排除）
        if (dropdownInput != null) {
            float[] p = dropdownPanelRect();
            if (p != null && hitL(p[0], p[1], p[2], p[3])) {
                dropdownScroll -= CLBC_GL_API.scrollDelta * DD_ROW_H;
                float maxS = Math.max(0f, dropdownInput.options.length * DD_ROW_H - p[3]);
                dropdownScroll = Math.max(0f, Math.min(dropdownScroll, maxS));
            }
        }

        // 内嵌输入窗口优先：点中「空窗口」→ 进入文本编辑；点中下拉槽 → 弹出候选，二者都不触发折叠 / 拖动
        CLBC_BlockRenderer.SocketHit sock = (inRegion && CLBC_GL_API.mouseJustPressed && !dropdownConsumed)
                ? socketAt(mx, my) : null;
        boolean dropdownHit = sock != null && sock.input.isDropdown() && sock.input.child == null;
        boolean socketEditing = sock != null && sock.input.child == null && !dropdownHit;
        if (inRegion && CLBC_GL_API.mouseJustPressed && !dropdownConsumed) {
            if (dropdownHit) {
                dropdownInput = sock.input;
                dropdownOwner = sock.owner;
                dropdownRect[0] = sock.x; dropdownRect[1] = sock.y;
                dropdownRect[2] = sock.w; dropdownRect[3] = sock.h;
                dropdownScroll = 0f;
                editingInput = null;               // 下拉槽绝不进自由文本框
                CLBC_GL_API.focusedTextField = -1;
                lastClickId = -1;
                CLBC_Util.logFine("下拉展开: " + sock.owner.key + " #" + sock.input.ordinal
                        + " 候选 " + sock.input.options.length + " 项");
            } else if (socketEditing) {
                editingInput = sock.input;
                editOwner = sock.owner;
                editRect[0] = sock.x; editRect[1] = sock.y;
                editRect[2] = sock.w; editRect[3] = sock.h;
                CLBC_GL_API.focusedTextField = editId();
                lastClickId = -1;
            } else {
                editingInput = null;   // 点别处即退出编辑
            }
        }

        // 双击折叠 / 展开
        if (inRegion && CLBC_GL_API.mouseJustPressed && !socketEditing && !dropdownConsumed && !dropdownHit) {
            CLBC_Block hit = blockAt(mx, my);
            long now = System.currentTimeMillis();
            if (hit != null && hit.id == lastClickId && now - lastClickTime < 350) {
                hit.collapsed = !hit.collapsed;
                commit();
                lastClickId = -1;
            } else {
                lastClickId = (hit != null) ? hit.id : -1;
                lastClickTime = now;
            }
        }

        // ===== B-4 注释拖动条 / 缩放手柄命中（优先于积木拖动 / 平移 / 框选）=====
        int ch = commentHandleAt(mx, my);   // -1 无；偶数 2k = 注释 k 拖动条；奇数 2k+1 = 注释 k 缩放手柄
        boolean commentGrabbed = false;
        if (inRegion && CLBC_GL_API.mouseJustPressed && ch >= 0) {
            int ci = ch / 2;
            if (ch % 2 == 0) { commentDragIdx = ci; commentResizeIdx = -1; }
            else { commentResizeIdx = ci; commentDragIdx = -1; }
            Comment c = comments.get(ci);
            cStartX = c.x; cStartY = c.y; cStartW = c.w; cStartH = c.h;
            cStartMX = mx; cStartMY = my;
            commentGrabbed = true;
        }

        // 左键按下：积木拖动（B-2 组 / B-7 子序列）/ Shift 多选 / 平移 / 框选
        if (inRegion && CLBC_GL_API.mouseJustPressed && !socketEditing && !dropdownConsumed && !dropdownHit
                && !commentGrabbed && ch < 0) {
            CLBC_Block hit = blockAt(mx, my);
            if (hit != null && !overNameField(hit)) {
                if (CLBC_GL_API.shiftDown) {   // B-9 Shift 多选：进 / 出选中集合，不启动拖动
                    int topId = topAncestor(hit).id;
                    if (!selection.remove(Integer.valueOf(topId))) selection.add(topId);
                } else {
                    // 内嵌在输入窗口里的报告积木（input.child）x/y 是创建时的旧值，
                    // 并不是它在屏幕上的位置（它的位置由窗口 socket 决定）。若直接拿它
                    // 当拖拽目标，dragGroupStart 记的是旧坐标 → 一拖就跳到画布原点。
                    // 这类块统一改为拖承载它的宿主块：宿主在 blocks 顶层、坐标正确，
                    // 报告积木随宿主绘制，视觉上仍是从光标处跟手移动。
                    CLBC_Block drag = dragTargetFor(hit);
                    beginDragGroup(drag, mx, my, regionX, regionY);
                }
            } else if (hit == null) {
                if (commentAt(mx, my)) {
                    // 命中注释文本区：交给 drawComment 的即时文本框编辑文字（不平移 / 框选）
                } else if (CLBC_GL_API.ctrlDown) {    // Ctrl+空白 = 框选（存逻辑坐标）
                    selecting = true;
                    selX0 = selX1 = mx; selY0 = selY1 = my;
                } else {                              // 平移（锚点记物理鼠标）
                    panning = true;
                    panStartMX = CLBC_GL_API.mouseX; panStartMY = CLBC_GL_API.mouseY;
                    panStartPX = panX; panStartPY = panY;
                }
            }
        }

        // 拖动中（B-2 / B-7 组：按各自起始坐标整体平移；单块时等价原逻辑）
        if (dragId >= 0 && CLBC_GL_API.mouseDown) {
            float dx = mx - dragAnchorX, dy = my - dragAnchorY;
            for (int i = 0; i < dragGroup.size(); i++) {
                CLBC_Block g = dragGroup.get(i);
                float[] st = dragGroupStart.get(i);
                float nx = st[0] + dx, ny = st[1] + dy;
                if (finite(nx) && finite(ny)) { g.x = nx; g.y = ny; }   // C：拖动坐标非有限拦住
                else CLBC_Util.log("!!!! 拖动坐标非有限，已拦住 #" + g.id);
            }
            updateDragConnections();   // #162：连接口最近配对（无候选才退回插入线）
        }
        // 平移中（屏幕像素位移 / zoom = 逻辑位移）
        if (panning && CLBC_GL_API.mouseDown) {
            float nx = panStartPX + (CLBC_GL_API.mouseX - panStartMX) / zoom;
            float ny = panStartPY + (CLBC_GL_API.mouseY - panStartMY) / zoom;
            if (finite(nx) && finite(ny)) { panX = nx; panY = ny; }
            else CLBC_Util.log("!!!! 平移坐标非有限，已拦住");
            clampPan(regionX, regionY, regionW, regionH);   // #132：平移后夹紧，积木再也不会被滚出可视区太远
        }
        // 框选中（逻辑坐标）
        if (selecting && CLBC_GL_API.mouseDown) {
            selX1 = mx; selY1 = my;
        }
        // B-4 注释拖动 / 缩放中
        if (CLBC_GL_API.mouseDown && (commentDragIdx >= 0 || commentResizeIdx >= 0)) {
            applyCommentDrag(mx, my);
        }

        // 释放
        if (CLBC_GL_API.mouseJustReleased) {
            // B-4 注释交互提交
            if (commentDragIdx >= 0 || commentResizeIdx >= 0) {
                commit();
                commentDragIdx = -1; commentResizeIdx = -1;
            }
            if (dragId >= 0) {
                CLBC_Block b = idIndex.get(dragId);
                boolean changed = false;
                if (b != null) {
                    CLBC_BlockRenderer.measureBlock(b);
                    boolean overToolbox = CLBC_GL_API.mouseX >= CLBC_Toolbox.lastX
                            && CLBC_GL_API.mouseX <= CLBC_Toolbox.lastX + CLBC_Toolbox.lastW
                            && CLBC_GL_API.mouseY >= CLBC_Toolbox.lastY
                            && CLBC_GL_API.mouseY <= CLBC_Toolbox.lastY + CLBC_Toolbox.lastH;
                    // #162：删除判定改用「整块包围盒完全离开画布」，不再拿光标那一个点判。
                    // 近百像素宽的积木，光标刚越过右边界但块体还在画布里 → 不该被删、也不该被拦。
                    float bsx = regionX + (b.x + panX) * zoom, bsy = regionY + (b.y + panY) * zoom;
                    float bsw = b.w * zoom, bsh = b.h * zoom;
                    boolean outside = bsx + bsw < regionX || bsx > regionX + regionW
                            || bsy + bsh < regionY || bsy > regionY + regionH;
                    if (overToolbox) {
                        for (CLBC_Block g : dragGroup) blocks.remove(g);
                        changed = true; selection.clear();
                        CLBC_Util.log("积木拖回工具箱区域，已删除");
                    } else if (outside) {
                        for (CLBC_Block g : dragGroup) blocks.remove(g);
                        changed = true; selection.clear();
                        CLBC_Util.log("积木整块被拖出可视区域，已删除");
                    } else {
                        // 只有存在连接候选才建立连接，
                        // 否则保持自由摆放（不再 snap 把块硬拉回某一列）。
                        boolean connected = applyDragConnection(b, regionX, regionY, regionW, regionH);
                        if (!connected) {
                            b.parentId = -1;
                            if (!Float.isNaN(hintY)) applyInsertHint(b);
                        }
                        changed = connected || b.x != dragStartX || b.y != dragStartY
                                || b.parentId != dragStartParent;
                    }
                }
                if (changed) commit();
                dragId = -1;
                dragGroup.clear();
                dragGroupStart.clear();
                dragRoots.clear();
                hintY = Float.NaN;
                resetDragSnap();
            }
            if (selecting) {
                computeSelection();
                selecting = false;
            }
            panning = false;
        }

        // ===== B-9 快捷键：仅在无文本框聚焦时生效（代码编辑器 / 内嵌输入框 / 搜索框聚焦时让位，避免冲突）=====
        if (CLBC_GL_API.focusedTextField == -1) {
            if (CLBC_GL_API.ctrlDown) {
                if (CLBC_GL_API.keyZ) undo();
                else if (CLBC_GL_API.keyY) redo();
                else if (CLBC_GL_API.keyA) selectAll();
                else if (CLBC_GL_API.keyC) copySelection();
                else if (CLBC_GL_API.keyV) pasteClipboard();
                else if (CLBC_GL_API.keyS) {
                    // A：新打开工程时 currentProjectPath 尚未设（仅 autosavePath 已设），
                    // 若只按 Ctrl+S 会落到"未设置保存路径"而静默不写盘；这里回退到 autosavePath
                    // （showProject 已将其指向 <工程>/project.clbc），保证 Ctrl+S 始终能真正落盘。
                    if (currentProjectPath != null) saveProject(currentProjectPath);
                    else if (autosavePath != null) saveProject(autosavePath);
                    else CLBC_Util.log("未设置保存路径：请先调用 saveProject(\"路径.clbc\") 指定位置");
                }
            } else {
                if (CLBC_GL_API.keyDelete) deleteSelection();
                float ddx = 0, ddy = 0, step = CLBC_GL_API.shiftDown ? 10f : 1f;
                if (CLBC_GL_API.keyLeft) ddx -= step;
                if (CLBC_GL_API.keyRight) ddx += step;
                if (CLBC_GL_API.keyUp) ddy -= step;
                if (CLBC_GL_API.keyDown) ddy += step;
                if (ddx != 0 || ddy != 0) nudgeSelection(ddx, ddy);
            }
        }
    }

    // ================= 枚举下拉弹层 =================

    /** 候选面板矩形（clip 之外绘制，锚定在被点中的字段槽正下方） */
    private float[] dropdownPanelRect() {
        if (dropdownInput == null || !dropdownInput.isDropdown()) return null;
        String[] opts = dropdownInput.options;
        float w = Math.max(DD_MIN_W, dropdownRect[2]);
        if (CLBC_GL_API.uiFont != null) {
            for (String o : opts) {
                String t = CLBC_BlockShape.dropdownDisplay(o);
                w = Math.max(w, CLBC_GL_API.measureText(CLBC_GL_API.uiFont, t) + 20f);
            }
        }
        float h = Math.min(DD_MAX_H, opts.length * DD_ROW_H);
        // #132：面板默认显示在字段下方；若会超出可视区域底部，则翻转到字段上方，
        // 否则下半部分会被 GL 视口裁切且无法悬停滚动到（用户报告的「列表底部被切/显示不全」根因）。
        // 所有调用方都实时读取本方法，翻转对命中/绘制/滚动透明。
        float belowY = dropdownRect[1] + dropdownRect[3] + 2f;
        float aboveY = dropdownRect[1] - h - 2f;
        float regionBottom = lastRY + lastRH / zoom;   // 可视区域底部的逻辑 Y（与 pushCanvasZoom 同套变换）
        float y = (belowY + h <= regionBottom) ? belowY : Math.max(aboveY, lastRY);
        return new float[]{dropdownRect[0], y, w, h};
    }

    /** 光标落在第几个候选；-1 = 未命中选项 */
    private int dropdownHitIndex(float mx, float my) {
        if (dropdownInput == null) return -1;
        float[] p = dropdownPanelRect();
        if (p == null || !hitL(p[0], p[1], p[2], p[3])) return -1;
        int i = (int) ((my - p[1] + dropdownScroll) / DD_ROW_H);
        return (i >= 0 && i < dropdownInput.options.length) ? i : -1;
    }

    private void closeDropdown() {
        dropdownInput = null;
        dropdownOwner = null;
        dropdownScroll = 0f;
    }

    /** 在显示文本中按 ordinal 定位并替换该参数（与 segments() 的 indexOf 切槽顺序一致） */
    private static String replaceArgInText(String text, List<String> args, int ordinal,
                                           String oldV, String newV) {
        if (text == null || oldV == null || oldV.isEmpty()) return text;
        int cursor = 0;
        for (int i = 0; i < ordinal && i < args.size(); i++) {
            String a = args.get(i);
            if (a != null && !a.isEmpty()) {
                int p = text.indexOf(a, cursor);
                if (p >= 0) cursor = p + a.length();
            }
        }
        int p = text.indexOf(oldV, cursor);
        if (p < 0) p = text.indexOf(oldV);
        if (p < 0) return text;
        return text.substring(0, p) + newV + text.substring(p + oldV.length());
    }

    /**
     * 下拉选中后的值落地：窗口值 / args / 显示文本 / 容器分支标签 / 方法返回类型 / 修饰符
     * 一次同步，保证积木→代码、显示、撤销快照三者不漂移。
     */
    private void applyDropdownValue(CLBC_Block owner, CLBC_Block.Input in, String v) {
        if (owner == null || in == null || v == null) return;
        String old = in.value == null ? "" : in.value;
        in.value = v;
        in.child = null;                       // 枚举字段不接受报告积木

        owner.text = replaceArgInText(owner.text, owner.args, in.ordinal, old, v);
        if (owner.hasSlots() && in.row >= 0 && in.row < owner.slots.size()) {
            CLBC_Block.Slot sl = owner.slots.get(in.row);
            sl.label = replaceArgInText(sl.label, owner.args, in.ordinal, old, v);
            // 容器第一个槽同时也是该口条件表达式（旧单槽约定），保持同源
            if (in.ordinal == 0 && !sl.expr.isEmpty())
                sl.expr = replaceArgInText(sl.expr, owner.args, 0, old, v);
        }
        if (in.ordinal >= 0 && in.ordinal < owner.args.size()) owner.args.set(in.ordinal, v);

        String key = owner.key == null ? "" : owner.key;
        // #280：变量/常量「类型」下拉联动「值」槽 —— 切到 boolean 时值槽变 true/false 下拉，
        // 切回其它类型时退回自由文本框。仅当原值是 true/false 字面量（或空）时切换，
        // 复杂布尔表达式保持为文本框+子块，绝不丢信息。照 ops.call 联动范式。
        if (("data.var".equals(key) || "data.const".equals(key)) && in.ordinal == 0) {
            CLBC_Block.Input valIn = owner.inputFor(0, 2);
            if (valIn != null) {
                String cv = valIn.value == null ? "" : valIn.value.trim();
                boolean literalBool = "true".equals(cv) || "false".equals(cv) || cv.isEmpty();
                if ("boolean".equals(v)) {
                    if (literalBool && valIn.child == null) {
                        String nv = cv.isEmpty() ? "true" : cv;
                        valIn.options = clbc.blocks.CLBC_CoreSyntax.BOOL_LITERALS;
                        valIn.value = nv;
                        if (owner.args.size() > 2) owner.args.set(2, nv);
                        owner.text = clbc.blocks.CLBC_Lang.text(key, owner.args);
                        clbc.ui.CLBC_BlockShape.invalidateAllLayout();
                    }
                } else if (valIn.options == clbc.blocks.CLBC_CoreSyntax.BOOL_LITERALS) {
                    // 从 boolean 切回其它类型 → 值槽退回文本框
                    valIn.options = null;
                    clbc.ui.CLBC_BlockShape.invalidateAllLayout();
                    CLBC_Util.logFine("变量类型切离 boolean: 值槽退回文本框, value=" + valIn.value);
                }
            }
        }
        if ("unit.method".equals(key)) {
            // 代码导入布局 [修饰符, 返回类型, 名称, 参数表] vs 定义表布局 [返回类型, 名称, 参数表]
            boolean importLayout = owner.args.size() >= 4;
            int retOrdinal = importLayout ? 1 : 0;
            if (in.ordinal == retOrdinal) owner.retType = v;   // BlocksToCode 读 retType，不读 args
            if (importLayout && in.ordinal == 0) owner.modifiers = v;
        }
        // 调用积木（ops.call）：类/方法下拉联动 —— 改类刷新方法候选，改类或方法都重建参数口/C 口
        if ("ops.call".equals(key)) {
            if (in.ordinal == 0) {
                String fqn = clbc.blocks.CLBC_BlockLibrary.resolveFqn(v);
                CLBC_Block.Input min = owner.inputFor(0, 1);
                if (min != null) {
                    min.options = CLBC_Block.toArr(clbc.blocks.CLBC_BlockLibrary.methodNamesOf(fqn));
                    min.value = (min.options != null && min.options.length > 0) ? min.options[0] : "";
                }
                owner.rebuildCallParams();
                commit();
                CLBC_Util.logFine("调用积木·类下拉: " + v + " → 方法候选已刷新、参数口已重建");
                return;
            } else if (in.ordinal == 1) {
                owner.rebuildCallParams();
                commit();
                CLBC_Util.logFine("调用积木·方法下拉: " + v + " → 参数口已重建");
                return;
            }
        }
        commit();
        CLBC_Util.logFine("下拉选择: " + key + " #" + in.ordinal + " -> " + v);
    }

    /** 绘制下拉候选面板（在 clip 之外的最上层，由 Renderer 调用 drawPopup 触发） */
    private void drawDropdownPopup() {
        float[] p = dropdownPanelRect();
        if (p == null) return;
        String[] opts = dropdownInput.options;
        long panelBg = CLBC_GL_API.rgb(250, 250, 252);
        long border  = CLBC_GL_API.hex("#4C97FF");
        long text    = CLBC_GL_API.hex("#333333");
        long hover   = CLBC_GL_API.hex("#E3EEFF");
        // 「选择框」对齐 IDEA 项目树：选中项是整行蓝灰高亮条 + 文字反白，
        // 不再是左侧一根 3px 竖条；hover 仍用浅蓝区分。
        long selRow  = CLBC_GL_API.rgb(60, 92, 140);
        long selText = CLBC_GL_API.hex("#FFFFFF");
        // #132：候选面板改圆角（与积木/内嵌窗口风格统一），不再用直角矩形
        CLBC_GL_API.fillRoundRect(panelBg, p[0], p[1], p[2], p[3], 8f);
        CLBC_GL_API.strokeRoundRect(border, p[0], p[1], p[2], p[3], 8f, 1f);
        CLBC_GL_API.setClipLogical(p[0], p[1], p[2], p[3]);
        int cur = dropdownInput.optionIndex();
        for (int i = 0; i < opts.length; i++) {
            float ry = p[1] + i * DD_ROW_H - dropdownScroll;
            if (ry + DD_ROW_H < p[1] || ry > p[1] + p[3]) continue;
            boolean selectedRow = (i == cur);
            if (selectedRow) CLBC_GL_API.fillRect(selRow, p[0], ry, p[2], DD_ROW_H);
            else if (CLBC_GL_Widgets.hit(p[0], ry, p[2], DD_ROW_H))
                CLBC_GL_API.fillRect(hover, p[0], ry, p[2], DD_ROW_H);
            String label = CLBC_BlockShape.dropdownDisplay(opts[i]);
            CLBC_GL_API.drawText(CLBC_GL_API.uiFont, label, p[0] + 8,
                    ry + DD_ROW_H / 2f + CLBC_GL_API.uiFont.getPixelHeight() * 0.35f,
                    selectedRow ? selText : text);
        }
        CLBC_GL_API.clearClip();
    }

    /** 右键菜单结果分发（由 Renderer 在 clearClip 之后调用） */
    public void drawPopup() {
        CLBC_GL_API.pushCanvasZoom(lastRX, lastRY, zoom);   // 下拉 / 菜单浮层与画布同缩放，命中走逻辑鼠标
        try {
        if (dropdownInput != null) drawDropdownPopup();
        if (!CLBC_GL_Widgets.contextMenuActive()) return;
        if (CLBC_GL_Widgets.activePopupId() != menuBlockId) return;
        int idx = CLBC_GL_Widgets.drawContextMenu(
                CLBC_GL_API.rgba(255, 255, 255, 250),
                CLBC_GL_API.hex("#2E3440"),
                CLBC_GL_API.hex("#E3EEFF"));
        if (idx < 0) return;
        CLBC_Block b = idIndex.get(menuBlockId);
        if (b == null) return;
        if (idx == 0) { // 添加注释：固定连到右键点中的这块，并摆到它右侧空白处
            Comment c = new Comment();
            float[] pos = blockScreenPos(b);
            c.x = (pos[0] - lastRX - panX) + b.w + 36; c.y = (pos[1] - lastRY - panY);
            c.anchorId = b.id;
            c.orderY = b.y + 0.5f;
            comments.add(c);
            commit();
            CLBC_Util.log("已生成带连线的内联注释");
        } else if (idx == 1) { // 折叠/展开
            b.collapsed = !b.collapsed;
            commit();
        } else if (idx == 2) { // B-1 复制（深拷贝含子树 / 内嵌输入窗口 / 就近注释连线）
            duplicateBlocks(java.util.Collections.singletonList(b));
        } else if (idx == 3) { // B-8 整理积木
            tidyBlocks();
        } else if (idx == 4) { // 删除
            blocks.remove(b);
            commit();
            CLBC_Util.log("已删除积木");
        } else if (idx == 5) { // 重命名积木 → 立即写入 lang/mapping_file.json（最高优先级）
            String nv = org.lwjgl.util.tinyfd.TinyFileDialogs.tinyfd_inputBox(
                    "重命名积木", "输入新的积木名称（会存入 lang/mapping_file.json）：", b.text);
            if (nv != null && !nv.isEmpty() && !nv.equals(b.text)) {
                String key = (b.key == null || b.key.isEmpty()) ? "block#" + b.id : b.key;
                b.text = nv;
                clbc.core.CLBC_LanguageRegistry.renameBlock(key, nv);
                commit();
                CLBC_Util.log("已重命名积木: " + key + " → " + nv);
            }
        }
        } finally {
            CLBC_GL_API.popCanvasZoom();
        }
    }

    /** 邻近堆叠吸附：在阈值内寻找最近顶层块，将拖拽块对齐到其下方或上方。
     *  燕尾凸起/凹槽在 y=o.y+o.h 处天然互补，故间距严格为 0（不再人为重叠）。 */
    private void snap(CLBC_Block b) {
        final float TH = 30;          // 吸附触发阈值：当前位置到"吸附后位置"的像素距离上限
        CLBC_Block best = null;
        float bestX = b.x, bestY = b.y, bestDist = Float.MAX_VALUE, bestMode = 0; // -1 置上, +1 叠下
        for (CLBC_Block o : blocks) {
            if (o.id == b.id || o.parentId >= 0) continue;
            if (o.h <= 0 || b.h <= 0) continue;               // 尺寸未测算过则不吸附，避免错位
            // 叠在下方：b 顶 对齐 o 底（o 的燕尾凸起正好填进 b 的顶凹槽）
            float yBelow = o.y + o.h;
            float dBelow = (float) Math.hypot(b.x - o.x, b.y - yBelow);
            if (dBelow <= TH && dBelow < bestDist) { bestDist = dBelow; best = o; bestX = o.x; bestY = yBelow; bestMode = 1; }
            // 置于上方：b 底 对齐 o 顶
            float yAbove = o.y - b.h;
            float dAbove = (float) Math.hypot(b.x - o.x, b.y - yAbove);
            if (dAbove <= TH && dAbove < bestDist) { bestDist = dAbove; best = o; bestX = o.x; bestY = yAbove; bestMode = -1; }
        }
        if (best != null) {
            b.x = bestX; b.y = bestY;
            CLBC_Util.logFine("积木吸附：" + (bestMode > 0 ? "堆叠到 " : "置于 ") + best.kind + " #" + best.id
                    + " 之" + (bestMode > 0 ? "下" : "上") + "（互锁）");
        }
    }

    /** 测试/编程用：直接加入已构建好的积木（不重映射 id，不定位视野） */
    public void addBlockDirect(CLBC_Block b) { blocks.add(b); }
    /** 导出当前画布扁平列表的浅拷贝，供 BlocksToCode.fromCanvas 重新生成代码 */
    public List<CLBC_Block> getBlocks() { return new ArrayList<>(blocks); }

    /** #131：导出当前画布全部注释框为 CommentData，供 BlocksToCode.fromCanvas 以 // 形式写回代码（往返闭环） */
    public List<CodeToBlocks.CommentData> getCommentData() {
        rebuildTree();   // 导出可能在输入事件里发生，先确保锚点索引是本帧最新的
        List<CodeToBlocks.CommentData> out = new ArrayList<>();
        for (Comment c : comments) {
            CLBC_Block a = commentAnchor(c);
            CodeToBlocks.CommentData d = new CodeToBlocks.CommentData(
                    c.x, c.y, c.w, c.h, c.text,
                    a == null ? -1 : a.id,
                    Float.isFinite(c.orderY) ? c.orderY : c.y);
            d.anchorIsNext = c.anchorIsNext;
            out.add(d);
        }
        return out;
    }

    /** 测试/编程用：对给定积木执行吸附对齐（同释放时的 snap 逻辑） */
    public void applySnap(CLBC_Block b) { snap(b); }

    private boolean overNameField(CLBC_Block b) {
        if (!(b.kind.equals("class") || b.kind.equals("method"))) return false;
        float[] pos = blockScreenPos(b);
        float sx = pos[0], sy = pos[1];
        CLBC_BlockShape.TextMeasurer tm = CLBC_GL_API.uiFont == null
                ? CLBC_BlockShape.ROUGH
                : t -> CLBC_GL_API.measureText(CLBC_GL_API.uiFont, t);
        float nx = sx + 12 + CLBC_BlockShape.segmentsWidth(CLBC_BlockShape.segments(b, 0, tm)) + 8f;
        float ny = sy + 5;
        float nw = CLBC_GL_Widgets.inputWidth(b.name, b.kind.equals("class") ? 80 : 60);
        return hitL(nx, ny, nw, 26);   // 名称框坐标是逻辑坐标，命中走逻辑鼠标（缩放一致）
    }

    // ================= 内嵌输入窗口 =================

    /** 收集所有顶层积木（含子树）的内嵌窗口，返回光标命中的那个 */
    private CLBC_BlockRenderer.SocketHit socketAt(float mx, float my) {
        for (CLBC_Block b : blocks) {
            if (b.parentId >= 0) continue;
            float sx = lastRX + b.x + panX, sy = lastRY + b.y + panY;
            for (CLBC_BlockRenderer.SocketHit h : CLBC_BlockRenderer.sockets(b, sx, sy))
                if (h.contains(mx, my)) return h;
        }
        return null;
    }

    private int editId() {
        return (editOwner == null ? 0 : editOwner.id) * 10000
                + (editingInput == null ? 0 : editingInput.row) * 100
                + (editingInput == null ? 0 : editingInput.ordinal);
    }

    /** 窗口文本编辑后同步回 args / Slot.expr，保证积木→代码与窗口内容一致 */
    private void syncInputToArgs(CLBC_Block owner, CLBC_Block.Input in) {
        if (owner == null || in == null) return;
        String v = in.code();
        if (CLBC_BlockShape.isContainer(owner.kind == null ? "stack" : owner.kind)) {
            if (owner.hasSlots() && in.row >= 0 && in.row < owner.slots.size())
                owner.slots.get(in.row).expr = v;
        }
        // 容器首栏的多参数窗口（方法定义）也必须落回 args：旧实现只写 Slot.expr，
        // 导致改了方法名 / 参数表后 积木→代码 仍用旧值。
        if (in.ordinal >= 0 && owner.args != null && in.ordinal < owner.args.size())
            owner.args.set(in.ordinal, v);
        if ("unit.method".equals(owner.key)) {
            int nameOrdinal = owner.args != null && owner.args.size() >= 4 ? 2 : 1;
            if (in.ordinal == nameOrdinal) owner.name = v;
        }
    }

    /** 防成环：目标窗口的宿主不能是被拖积木自身或其后代 */
    private boolean inSubtree(CLBC_Block root, int id) {
        if (root == null) return false;
        if (root.id == id) return true;
        for (CLBC_Block c : root.children) if (inSubtree(c, id)) return true;
        return false;
    }

    /** #163：命中测试统一走 CLBC_BlockStack.hit。
     *  旧实现有「hitTest 落空就返回顶层根」的兜底：点在 C 口空腔 / 凹口缝隙里会误抓最外层容器，
     *  于是「鼠标拖的是积木群里的哪一块」永远判不准。现在只认真正点中的那一块，落空就是空白。 */
    private CLBC_Block blockAt(float mx, float my) {
        CLBC_BlockStack.Hit h = CLBC_BlockStack.hit(blocks, mx, my, lastRX, lastRY, panX, panY);
        return h == null ? null : h.block;
    }

    /** 命中到输入窗口内的报告积木时，改成拖它的宿主块。
     *  报告积木是 input.child，不在 blocks 顶层，x/y 是创建时的旧值而非屏幕位置；
     *  直接拖它会让 dragGroupStart 记下旧坐标，按下即跳飞。宿主块坐标正确且报告块随其绘制。
     *  带环保护：只沿 parentId 向上找最近的、真实存在于 blocks 里的祖先。 */
    private CLBC_Block dragTargetFor(CLBC_Block hit) {
        if (hit == null) return null;
        // hit 本身就在顶层列表里 → 坐标可信，直接拖
        if (hit.parentId < 0 && idIndex.get(hit.id) == hit) return hit;
        Set<Integer> seen = new HashSet<>();
        CLBC_Block cur = hit;
        while (cur != null && seen.add(cur.id)) {
            if (idIndex.get(cur.id) == cur) return cur;   // 顶层宿主
            if (cur.parentId < 0) break;
            CLBC_Block p = idIndex.get(cur.parentId);
            if (p == null) break;
            cur = p;
        }
        // 兜底：找把 hit（或其祖先报告块）当作 input.child 的宿主。
        // 递归进所有 input.child，覆盖「报告块套报告块」的多层嵌套。
        for (CLBC_Block b : blocks) {
            CLBC_Block host = socketHostOf(b, hit, new HashSet<>());
            if (host != null) return host;
        }
        return hit;
    }

    /** 在 b 的子树（含 input.child 里嵌套的报告块）中找持有 target 的那个顶层块 */
    private CLBC_Block socketHostOf(CLBC_Block b, CLBC_Block target, Set<Integer> seen) {
        if (b == null || !seen.add(b.id)) return null;
        for (CLBC_Block.Input in : b.inputs) {
            if (in == null) continue;
            if (in.child == target) return b;
            if (in.child != null && socketHostOf(in.child, target, seen) != null) return b;
        }
        return null;
    }

    /** 沿 parentId 链找顶层祖先。带"已访问"集合：若 parentId 自环/成环（脏数据），
     *  见过的 id 二次出现即停止，绝不无限循环（此前无保护，坏数据会卡死主线程）。 */
    private CLBC_Block topAncestor(CLBC_Block b) {
        CLBC_Block cur = b;
        Set<Integer> seen = new HashSet<>();
        while (cur.parentId >= 0 && seen.add(cur.id)) {
            CLBC_Block p = idIndex.get(cur.parentId);
            if (p == null) break;
            cur = p;
        }
        return cur;
    }

    /** 返回块 b 的屏幕左上坐标（沿父链累加 childOffset）。带"已访问"集合：parentId 成环时安全终止。 */
    private float[] blockScreenPos(CLBC_Block b) {
        List<CLBC_Block> chain = new ArrayList<>();
        CLBC_Block t = b;
        Set<Integer> seen = new HashSet<>();
        while (t != null && seen.add(t.id)) { chain.add(t); t = (t.parentId >= 0) ? idIndex.get(t.parentId) : null; }
        for (int i = 0, j = chain.size() - 1; i < j; i++, j--) {
            CLBC_Block tmp = chain.get(i); chain.set(i, chain.get(j)); chain.set(j, tmp);
        }
        CLBC_Block root = chain.get(0);
        float sx = lastRX + root.x + panX;
        float sy = lastRY + root.y + panY;
        for (int i = 0; i < chain.size() - 1; i++) {
            CLBC_Block parent = chain.get(i), child = chain.get(i + 1);
            // 子块可能落在父块的任意一口，按 slotIndex 取对应口的偏移
            float[] o = CLBC_BlockRenderer.childOffset(parent, child.slotIndex);
            sx += o[0]; sy += o[1];
            // 关键：同一口里第 k 块的 y 不等于口起点，还要累加它前面所有兄弟的「身高 + 间距」。
            // 绘制(draw)、命中(hitTree)、测量(measureBlock) 三处都是这么摆的；这里漏掉，
            // 拔出拖拽时整条语句列的每块都会被算到口起点 → 飞到方法头 + 下方挤成一坨。
            List<CLBC_Block> sibs = parent.slotChildren(child.slotIndex);
            for (CLBC_Block sib : sibs) {
                if (sib == child) break;
                sy += (sib.h > 0 ? sib.h : CLBC_BlockShape.STACK_H) + CLBC_BlockRenderer.CHILD_GAP;
            }
        }
        return new float[]{sx, sy};
    }

    /** 光标落在容器的第几个口（用于拖入时设置子块 slotIndex） */
    private int slotIndexAt(CLBC_Block b, float sy, float my) {
        CLBC_BlockShape.Shape s = CLBC_BlockShape.shapeOf(b);
        if (s.slotCount <= 1) return 0;
        float local = my - sy;
        int idx = 0;
        for (int i = 0; i < s.slotCount - 1; i++) {
            if (local >= s.slotY[i] + s.slotH[i] + CLBC_BlockShape.BAR_H / 2f) idx = i + 1;
        }
        return idx;
    }

    /** 容器命中：返回光标所在容器块（排除 dragged 子树，防成环） */
    private CLBC_Block containerAt(CLBC_Block dragged, float mx, float my) {
        Set<Integer> excl = new HashSet<>();
        collectSubtree(dragged, excl);
        for (CLBC_Block b : blocks) {
            if (b.parentId >= 0) continue;
            float sx = lastRX + b.x + panX, sy = lastRY + b.y + panY;
            CLBC_Block r = containerInTree(b, sx, sy, mx, my, excl);
            if (r != null) return r;
        }
        return null;
    }

    private CLBC_Block containerInTree(CLBC_Block b, float sx, float sy, float mx, float my, Set<Integer> excl) {
        if (excl.contains(b.id)) return null;
        CLBC_BlockShape.Shape s = CLBC_BlockShape.shapeOf(b);
        if (CLBC_BlockShape.isContainer(s.kind)
                && mx >= sx && mx <= sx + s.w && my >= sy + CLBC_BlockShape.TOP_H && my <= sy + s.h) {
            return b;
        }
        if (!b.collapsed) {
            for (int i = s.slotCount - 1; i >= 0; i--) {
                float[] o = s.childOffset(i);
                float cx = sx + o[0], cy = sy + o[1];
                List<CLBC_Block> kids = b.slotChildren(i);
                for (int k = kids.size() - 1; k >= 0; k--) {
                    CLBC_Block r = containerInTree(kids.get(k), cx, cy, mx, my, excl);
                    if (r != null) return r;
                    cy += kids.get(k).h + CLBC_BlockRenderer.CHILD_GAP;
                }
            }
        }
        return null;
    }

    private void collectSubtree(CLBC_Block b, Set<Integer> set) {
        set.add(b.id);
        if (b.children != null) for (CLBC_Block c : b.children) collectSubtree(c, set);
    }

    private void computeSelection() {
        selection.clear();
        float x0 = Math.min(selX0, selX1), x1 = Math.max(selX0, selX1);
        float y0 = Math.min(selY0, selY1), y1 = Math.max(selY0, selY1);
        for (CLBC_Block b : blocks) {
            if (b.parentId >= 0) continue;
            float[] pos = blockScreenPos(b);
            float cx = pos[0] + b.w / 2f, cy = pos[1] + b.h / 2f;
            if (cx >= x0 && cx <= x1 && cy >= y0 && cy <= y1) selection.add(b.id);
        }
    }

    /** 打印画布积木结构（日志） */
    public void printCanvasStructure() {
        CLBC_Util.logFine("====== 开始扫描画布积木 ======");
        for (CLBC_Block b : blocks) {
            CLBC_Util.logFine(String.format("积木: 类型=%s, 坐标=(%.1f, %.1f)", b.kind, b.x, b.y));
        }
        CLBC_Util.logFine("===============================");
    }

    /** 重构类名：遍历类积木，把 name 由 oldName 改为 newName */
    public void refactorClassName(String oldName, String newName) {
        CLBC_Util.log("触发重构：将类名 [" + oldName + "] 修改为 [" + newName + "]");
        for (CLBC_Block b : blocks) {
            if ("class".equals(b.kind) && b.name != null && b.name.equals(oldName)) {
                b.name = newName;
            }
        }
    }

    // ==========================================================
    // ④-1 撤销 / 重做（快照式历史栈）
    // ==========================================================

    private void initHistory() {
        history.clear();
        history.add(snapshot());
        histIdx = 0;
    }

    /** 整份扁平积木列表的深拷贝（children 为每帧重建的瞬态字段，无需复制） */
    private List<CLBC_Block> snapshot() {
        List<CLBC_Block> copy = new ArrayList<>();
        for (CLBC_Block b : blocks) copy.add(cloneBlock(b));
        return copy;
    }

    private CLBC_Block cloneBlock(CLBC_Block b) {
        CLBC_Block c = new CLBC_Block(b.id, b.kind, b.color, b.text);
        c.name = b.name;
        c.x = b.x; c.y = b.y; c.w = b.w; c.h = b.h;
        c.key = b.key;
        c.args = new ArrayList<>(b.args);
        c.parentId = b.parentId;
        c.nestLevel = b.nestLevel;
        c.isReporter = b.isReporter;
        c.collapsed = b.collapsed;
        c.hidden = b.hidden;   // 隐式文件头撤销后仍必须保持不可见，否则会冒出「类声明帽子」
        c.retType = b.retType;
        c.modifiers = b.modifiers;
        c.expr = b.expr;
        c.slotIndex = b.slotIndex;
        if (b.hasSlots()) {
            c.slots = new ArrayList<>();
            for (CLBC_Block.Slot s : b.slots) c.slots.add(new CLBC_Block.Slot(s.label, s.expr));
        }
        for (CLBC_Block.Input in : b.inputs) c.inputs.add(cloneInput(in));
        c.children = new ArrayList<>();
        return c;
    }

    /** 内嵌窗口深拷贝（含窗口里那块报告积木及它自己的窗口），撤销/重做不丢 */
    private CLBC_Block.Input cloneInput(CLBC_Block.Input in) {
        CLBC_Block.Input o = new CLBC_Block.Input(in.value, in.hex, in.accept);
        o.row = in.row; o.ordinal = in.ordinal;
        o.options = in.options == null ? null : in.options.clone();   // 下拉候选随撤销/重做一起深拷贝
        if (in.child != null) o.child = cloneBlock(in.child);
        return o;
    }

    /** 提交一次变更：截断重做尾巴、压入新快照、触发自动保存 */
    private void commit() {
        dirtySinceSave = true;                      // 退出询问：任何已提交的结构改动都算未保存
        if (histIdx < history.size() - 1) history.subList(histIdx + 1, history.size()).clear();
        history.add(snapshot());
        histIdx = history.size() - 1;
        while (history.size() > HIST_CAP) { history.remove(0); histIdx--; }
        enforceHistoryBytes();                // C：单帧/累计深拷贝体积上限，超限丢最旧帧
        autosave();
        fireStructureChanged();
    }

    /** 结构变更回调（拖放 / 编辑 / 增删后触发），渲染层挂接以刷新代码面板。
     *  抽成方法，供 importBlocks(replace=true) 在不 commit（不写盘）的前提下复用，维持段1既有刷新行为。 */
    private void fireStructureChanged() {
        if (onStructureChanged != null) {
            try { onStructureChanged.run(); }
            catch (Exception e) { CLBC_Util.log("!!!! 结构变更回调异常: " + e.getMessage()); }
        }
    }

    private void restore(List<CLBC_Block> snap) {
        // 快照换回全新克隆对象，旧的编辑态（editingInput/editOwner）指向被丢弃的孤儿对象。
        // 撤销／重做后安全清空编辑态（关闭输入／下拉／菜单／拖动／框选），选更保守的方案，
        // 避免后续输入写进孤儿对象并持续压历史、清空 redo 栈。
        editingInput = null;
        editOwner = null;
        closeDropdown();
        menuBlockId = -1;
        dragId = -1;
        selecting = false;
        panning = false;
        selection.clear();
        CLBC_GL_API.focusedTextField = -1;
        blocks.clear();
        int maxId = 0;
        for (CLBC_Block b : snap) {
            CLBC_Block c = cloneBlock(b);
            blocks.add(c);
            if (c.id > maxId) maxId = c.id;
        }
        nextId = maxId + 1;
    }

    public void undo() {
        if (histIdx > 0) {
            histIdx--;
            restore(history.get(histIdx));
            CLBC_Util.log("撤销 (栈深 " + histIdx + "/" + (history.size() - 1) + ")");
        }
    }

    public void redo() {
        if (histIdx < history.size() - 1) {
            histIdx++;
            restore(history.get(histIdx));
            CLBC_Util.log("重做 (栈深 " + histIdx + "/" + (history.size() - 1) + ")");
        }
    }

    // ==========================================================
    // ④-2 工程持久化（.clbc JSON） + 自动保存
    // ==========================================================

    /** 设置自动保存落盘路径（建议指向 F 盘工程目录，避免写入 C 盘） */
    public void setAutosavePath(String path) { this.autosavePath = path; }

    /** 积木模型自上次成功保存以来是否有未保存改动（供渲染层合并代码编辑状态做退出询问） */
    public boolean isDirty() { return dirtySinceSave; }

    /** 显式清脏：打开/新建工程时由渲染层调用，保证"刚打开不算未保存"（即使入口源文件缺失） */
    public void clearDirty() { dirtySinceSave = false; }

    /** 保存到指定路径，并记录为当前工程路径（Ctrl+S 复用） */
    public void saveProject(String path) {
        if (path != null && !path.isEmpty()) currentProjectPath = path;
        String target = currentProjectPath != null ? currentProjectPath : autosavePath;
        if (target == null) { CLBC_Util.log("未设置保存路径，跳过保存"); return; }
        try {
            List<CommentState> cs = collectComments();
            String json = CLBC_ProjectIO.toJson(blocks, cs);
            java.nio.file.Files.writeString(java.nio.file.Path.of(target), json,
                    java.nio.charset.StandardCharsets.UTF_8);
            dirtySinceSave = false;                 // 退出询问：成功写入 project.clbc 后清脏
            CLBC_Util.log("工程已保存: " + target);
            generateThumbnail(target + ".png");
        } catch (Exception e) {
            CLBC_Util.log("保存失败: " + e.getMessage());
        }
    }

    /** 生成工程缩略图（软件渲染，无需 GL 上下文）：写入 <pngPath>；失败静默（不干扰用户操作） */
    public void generateThumbnail(String pngPath) {
        try {
            CLBC_Thumbnail.write(blocks, pngPath, 320, 200);
        } catch (Exception e) {
            CLBC_Util.log("缩略图生成失败（非致命）: " + e.getMessage());
        }
    }

    /** 从 .clbc 文件加载工程（重建积木列表 + 注释 + nextId + 历史栈） */
    public void loadProject(String path) {
        try {
            String json = java.nio.file.Files.readString(java.nio.file.Path.of(path),
                    java.nio.charset.StandardCharsets.UTF_8);
            CLBC_ProjectIO.ProjectData d = CLBC_ProjectIO.fromJson(json);
            blocks.clear();
            comments.clear();
            int maxId = 0;
            for (CLBC_Block b : d.blocks) {
                b.children = new ArrayList<>();
                clampCoords(b);               // C：工程文件里的非有限坐标拦住，不许污染绘图
                blocks.add(b);
                if (b.id > maxId) maxId = b.id;
            }
            for (CommentState c : d.comments) {
                Comment cm = new Comment();
                cm.x = c.x; cm.y = c.y; cm.w = c.w; cm.h = c.h; cm.text = c.text;
                cm.anchorId = c.anchorId; cm.orderY = c.orderY; cm.anchorIsNext = c.anchorIsNext;
                clampComment(cm);
                comments.add(cm);
            }
            nextId = maxId + 1;              // C：导入工程后重算到 max(existing id)+1
            commentsNeedLayout = true;
            currentProjectPath = path;
            dirtySinceSave = false;           // 从 .clbc 载入＝干净状态
            initHistory();
            CLBC_Util.log("工程已加载: " + path + " (" + d.blocks.size() + " 积木, " + d.comments.size() + " 注释)");
            generateThumbnail(CLBC_Thumbnail.thumbnailPath(path));   // 打开工程即刷新缩略图
        } catch (Exception e) {
            CLBC_Util.log("加载失败: " + e.getMessage());
        }
    }

    private List<CommentState> collectComments() {
        List<CommentState> cs = new ArrayList<>();
        for (Comment c : comments) cs.add(new CommentState(c.x, c.y, c.w, c.h, c.text,
                c.anchorId, Float.isFinite(c.orderY) ? c.orderY : c.y, c.anchorIsNext));
        return cs;
    }

    /** 自动保存：仅当已设置保存路径（或自动保存路径）时，写入 <路径>.autosave 旁挂文件；静默失败 */
    private void autosave() {
        if (currentProjectPath == null && autosavePath == null) return;
        String target = (currentProjectPath != null ? currentProjectPath : autosavePath) + ".autosave";
        try {
            List<CommentState> cs = collectComments();
            String json = CLBC_ProjectIO.toJson(blocks, cs);
            java.nio.file.Files.writeString(java.nio.file.Path.of(target), json,
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ignored) { /* 自动保存静默失败，不干扰用户操作 */ }
    }

    // ==========================================================
    // 段2：B-3 缩放坐标换算 + C 坐标 / ID / 快照健壮性
    // ==========================================================

    /** 物理鼠标 → 画布逻辑 X（缩放逆变换，锚点 = 画布区左上 lastRX） */
    private float lmx() { return lastRX + (CLBC_GL_API.mouseX - lastRX) / zoom; }
    /** 物理鼠标 → 画布逻辑 Y */
    private float lmy() { return lastRY + (CLBC_GL_API.mouseY - lastRY) / zoom; }
    /** 逻辑矩形命中（供下拉面板 / 名称框等逻辑坐标区域在 handleInput 阶段使用） */
    private boolean hitL(float x, float y, float w, float h) {
        float mx = lmx(), my = lmy();
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    private static boolean finite(float v) { return Float.isFinite(v) && Math.abs(v) < 1e7f; }

    /** C：导入 / 加载时拦住非有限或极大坐标并归零，记日志，不许污染绘图。 */
    private void clampCoords(CLBC_Block b) {
        if (!finite(b.x) || !finite(b.y)) {
            CLBC_Util.log("!!!! 积木 #" + b.id + " 坐标非有限或极大（" + b.x + "," + b.y + "），已归零");
            b.x = 0; b.y = 0;
        }
    }
    private void clampComment(Comment c) {
        if (!finite(c.x)) c.x = 0;
        if (!finite(c.y)) c.y = 0;
        if (!finite(c.w) || c.w < 20) c.w = 210;
        if (!finite(c.h) || c.h < 20) c.h = 76;
    }
    /** C：重算 nextId 到 max(existing id)+1，新增块不撞已有 id。 */
    private void recomputeNextId() {
        int m = 0;
        for (CLBC_Block b : blocks) if (b.id > m) m = b.id;
        nextId = m + 1;
    }

    /** C：粗估单帧快照字节数（按字符串长度量级，不做精确对象图遍历，避免热路径开销）。 */
    private long estimateFrameBytes(List<CLBC_Block> snap) {
        long n = 64;
        for (CLBC_Block b : snap) {
            n += 128;
            if (b.text != null) n += (long) b.text.length() * 2;
            if (b.name != null) n += (long) b.name.length() * 2;
            if (b.expr != null) n += (long) b.expr.length() * 2;
            if (b.args != null) for (String s : b.args) if (s != null) n += 32L + (long) s.length() * 2;
            if (b.slots != null) for (CLBC_Block.Slot s : b.slots) {
                n += 64;
                if (s.label != null) n += (long) s.label.length() * 2;
                if (s.expr != null) n += (long) s.expr.length() * 2;
            }
            for (CLBC_Block.Input in : b.inputs) {
                n += 128;
                if (in.value != null) n += (long) in.value.length() * 2;
                if (in.options != null) for (String o : in.options) if (o != null) n += (long) o.length() * 2;
                if (in.child != null) n += 64;
            }
        }
        return n;
    }
    /** C：历史累计体积超上限时循环丢最旧帧并记日志（至少保留 1 帧），大工程不吃满 8GB。 */
    private void enforceHistoryBytes() {
        long total = 0;
        for (List<CLBC_Block> f : history) total += estimateFrameBytes(f);
        while (history.size() > 1 && total > HIST_BYTES_CAP) {
            total -= estimateFrameBytes(history.get(0));
            history.remove(0);
            histIdx--;
            CLBC_Util.log("历史快照超过内存上限，丢弃最旧帧（剩 " + history.size() + " 帧）");
        }
        if (histIdx < 0) histIdx = 0;
    }

    // ==========================================================
    // 段2：B-4 注释拖动 / 缩放
    // ==========================================================

    /** 命中注释顶部拖动条 / 右下角缩放手柄。
     *  返回 -1 无命中；偶数 2k = 注释 k 拖动条；奇数 2k+1 = 注释 k 缩放手柄。 */
    private int commentHandleAt(float mx, float my) {
        for (int i = comments.size() - 1; i >= 0; i--) {
            Comment c = comments.get(i);
            float sx = lastRX + c.x + panX, sy = lastRY + c.y + panY;
            if (mx >= sx + c.w - 10 && mx <= sx + c.w && my >= sy + c.h - 10 && my <= sy + c.h)
                return i * 2 + 1;
            if (mx >= sx && mx <= sx + c.w && my >= sy && my <= sy + 12)
                return i * 2;
        }
        return -1;
    }
    private void applyCommentDrag(float mx, float my) {
        float dx = mx - cStartMX, dy = my - cStartMY;
        int i = commentDragIdx >= 0 ? commentDragIdx : commentResizeIdx;
        if (i < 0 || i >= comments.size()) return;
        Comment c = comments.get(i);
        c.userPlaced = true;   // 用户亲自摆过：位置和尺寸不再被自动布局覆盖
        if (commentDragIdx >= 0) {
            float nx = cStartX + dx, ny = cStartY + dy;
            if (finite(nx) && finite(ny)) { c.x = nx; c.y = ny; }
        } else {
            c.w = Math.max(80f, finite(cStartW + dx) ? cStartW + dx : 80f);
            c.h = Math.max(40f, finite(cStartH + dy) ? cStartH + dy : 40f);
        }
    }

    // ==========================================================
    // 段2：B-2 / B-5 / B-6 / B-7 组拖动 · 堆叠子序列 · 中间插入
    // ==========================================================

    /** #163：开始一次拖动。
     *  · 未选中 → 命中块 + 同一积木列上它下面的全部块 + 所有嵌套后代；
     *    链尾停在外层 C 块的 } 之前（"到 } 或到最底"），不会把 } 外面的块一起拽走。
     *  · 有选中 → 每个选中根各取一次整堆，合并去重。
     *  · unplug：所有「链根」一起脱离父容器，先按屏幕位置换算成世界坐标再改 parentId，
     *    避免脱离瞬间整堆跳变，也避免只脱一块、其余仍留在 C 口里。 */
    private void beginDragGroup(CLBC_Block hit, float mx, float my, float regionX, float regionY) {
        dragGroup.clear();
        dragGroupStart.clear();
        dragRoots.clear();
        resetDragSnap();
        int origParent = hit.parentId;

        List<CLBC_Block> roots = new ArrayList<>();
        java.util.LinkedHashSet<CLBC_Block> all = new java.util.LinkedHashSet<>();
        if (!selection.isEmpty() && selection.contains(CLBC_BlockStack.getRootBlock(idIndex, hit).id)) {
            for (int id : selection) {
                CLBC_Block r = idIndex.get(id);
                if (r == null) continue;
                roots.add(r);
                all.addAll(CLBC_BlockStack.dragGroup(blocks, r).all);
            }
        } else {
            CLBC_BlockStack.Group g = CLBC_BlockStack.dragGroup(blocks, hit);
            roots.addAll(g.roots);
            all.addAll(g.all);
        }
        // 显式硬边界：链根必须和命中块属于同一条 statement stack；
        // 命中块若被包在某个 C 口里，这条链就停在它的 } 之前，绝不越出去。
        // 关系父用于日志定位边界。
        String scope = CLBC_BlockStack.stackKey(hit);
        roots.removeIf(r -> !CLBC_BlockStack.stackKey(r).equals(scope));
        CLBC_Block enclosing = CLBC_BlockStack.getSurroundParent(idIndex, hit);
        // unplug 前先批量取屏幕位置（取完再改 parentId，否则父链断了算不出偏移）
        java.util.Map<CLBC_Block, float[]> detach = new java.util.IdentityHashMap<>();
        for (CLBC_Block r : roots) if (r.parentId >= 0) detach.put(r, blockScreenPos(r));
        for (java.util.Map.Entry<CLBC_Block, float[]> e : detach.entrySet()) {
            CLBC_Block r = e.getKey();
            float[] sp = e.getValue();
            r.x = sp[0] - lastRX - panX;
            r.y = sp[1] - lastRY - panY;
            r.parentId = -1;
        }

        dragRoots.addAll(roots);
        dragGroup.addAll(all);
        for (CLBC_Block g : dragGroup) dragGroupStart.add(new float[]{g.x, g.y});
        dragAnchorX = mx; dragAnchorY = my;
        dragId = hit.id;
        dragStartX = hit.x; dragStartY = hit.y; dragStartParent = origParent;
        dragOffX = mx - regionX - panX - hit.x;
        dragOffY = my - regionY - panY - hit.y;
        hintY = Float.NaN;
        CLBC_Block tail = CLBC_BlockStack.lastConnectionInStack(blocks, hit);
        CLBC_Util.logFine("开始拖动积木 #" + hit.id + "（链 " + roots.size() + " 块 / 整堆 "
                + dragGroup.size() + " 块，栈尾 #" + (tail == null ? -1 : tail.id)
                + "，外层 C #" + (enclosing == null ? -1 : enclosing.id) + "，到 } 或栈底为止）");
    }

    /** 与 b 同一 statement stack、自 b 起到栈尾的块序列（含 b）。
     *  #163：不再靠 x 对齐 + y 紧邻做几何猜测，改由 parentId/slotIndex 结构判定。 */
    private List<CLBC_Block> stackChainFrom(CLBC_Block b) {
        return CLBC_BlockStack.stackFrom(blocks, b);
    }

    /** B-6：拖动中计算"将要插入的位置"（同列某块的垂直中部），写入 hintX/hintY/hintW（逻辑屏幕坐标）。 */
    private void computeInsertHint(float regionX, float regionY) {
        hintY = Float.NaN;
        if (dragGroup.size() != 1) return;   // 仅单块拖动给插入提示
        CLBC_Block b = idIndex.get(dragId);
        if (b == null) return;
        CLBC_Block best = null;
        for (CLBC_Block o : blocks) {
            if (o.parentId >= 0 || o.id == b.id || dragGroup.contains(o)) continue;
            if (Math.abs(o.x - b.x) > 20) continue;
            if (b.y > o.y + o.h * 0.25f && b.y < o.y + o.h * 0.9f) {
                if (best == null || o.y < best.y) best = o;
            }
        }
        if (best != null) {
            hintX = regionX + best.x + panX;
            hintY = regionY + best.y + panY;
            hintW = Math.max(best.w, b.w);
        }
    }
    /** B-5：把 b 插到提示线位置，并把该位置起的同列后续块整体下推 b.h+gap。 */
    /** #实测：把单块 moving 嵌进 target 的第 slot 口、插到第 at 个子块之前。
     *  渲染按 blocks 数组顺序、代码生成按 y 排序，两处必须同步，否则会出现「画面在中间、生成跑到末尾」。
     *  做法：算插入点 y → 该口 at 及其后的兄弟（连同子树）整体下移 → 设 moving 父子/slotIndex →
     *  在 blocks 数组里插到对应兄弟之前（保持 slotChildren 顺序与 y 顺序一致）。 */
    private void nestBlockIntoSlot(CLBC_Block moving, CLBC_Block target, int slot, int at) {
        nestGroupIntoSlot(java.util.Collections.singletonList(moving), moving.h, target, slot, at);
    }

    /** 整堆版本：movingRoots 依次挂到 target 的第 slot 口、从 at 位开始，保持队形；后续兄弟整体下推。 */
    private void nestGroupIntoSlot(List<CLBC_Block> movingRoots, float movingHeight,
                                   CLBC_Block target, int slot, int at) {
        if (movingRoots == null || movingRoots.isEmpty()) return;
        for (CLBC_Block m : movingRoots) CLBC_BlockRenderer.measureBlock(m);
        List<CLBC_Block> kids = new ArrayList<>(target.slotChildren(slot));
        at = Math.max(0, Math.min(at, kids.size()));
        float[] off = CLBC_BlockShape.shapeOf(target).childOffset(slot);
        float insertY = target.y + off[1];
        for (int i = 0; i < at; i++) {
            CLBC_Block k = kids.get(i);
            insertY += (k.h > 0 ? k.h : CLBC_BlockShape.STACK_H) + CLBC_BlockRenderer.CHILD_GAP;
        }
        float delta = (movingHeight > 0 ? movingHeight : CLBC_BlockShape.STACK_H)
                + CLBC_BlockRenderer.CHILD_GAP;
        Set<Integer> seen = new HashSet<>();
        for (int i = at; i < kids.size(); i++) {
            List<CLBC_Block> flat = new ArrayList<>();
            collectSubtreeFlat(kids.get(i), flat, seen);
            for (CLBC_Block f : flat) f.y += delta;
        }
        float cy = insertY;
        for (CLBC_Block m : movingRoots) {
            m.x = target.x + off[0];
            m.y = cy;
            m.parentId = target.id;
            m.slotIndex = slot;
            cy += (m.h > 0 ? m.h : CLBC_BlockShape.STACK_H) + CLBC_BlockRenderer.CHILD_GAP;
        }
        // 数组顺序：把整堆插到 kids[at] 之前，保持 slotChildren 顺序与 y 排序一致
        blocks.removeAll(movingRoots);
        int arrAt = at < kids.size() ? blocks.indexOf(kids.get(at)) : -1;
        if (arrAt < 0 && !kids.isEmpty()) {
            int last = blocks.indexOf(kids.get(kids.size() - 1));
            arrAt = last >= 0 ? last + 1 : -1;
        }
        if (arrAt < 0) arrAt = blocks.size();
        blocks.addAll(arrAt, movingRoots);
        // 立即重建父子树，让 slotChildren 顺序与 blocks 数组/y 一致（不依赖下一帧 handleInput）
        rebuildTree();
        CLBC_Util.logFine("C 口插入：整堆 " + movingRoots.size() + " 块嵌进 " + target.kind + " #"
                + target.id + " 第 " + (slot + 1) + " 口第 " + (at + 1) + " 位（该口原 " + kids.size() + " 块）");
    }

    private void applyInsertHint(CLBC_Block b) {
        float colX = hintX - lastRX - panX;
        float targetY = hintY - lastRY - panY;
        float delta = b.h + CLBC_BlockRenderer.CHILD_GAP;
        for (CLBC_Block o : blocks) {
            if (o.parentId >= 0 || o.id == b.id) continue;
            if (Math.abs(o.x - colX) > 20) continue;
            if (o.y + 1e-3f >= targetY) o.y += delta;
        }
        b.x = colX; b.y = targetY;
        CLBC_Util.logFine("积木插入堆叠中间并下推后续 " + delta + "px");
    }
    /** 绘制插入位置高亮线（在画布 draw 的缩放变换内，坐标已是逻辑屏幕坐标）。 */
    private void drawInsertHint() {
        if (Float.isNaN(hintY)) return;
        CLBC_GL_API.fillRect(CLBC_GL_API.hex("#FFAB19"), hintX, hintY - 3, hintW, 6);
    }

    // ==========================================================
    // 段2：B-1 / B-2 / B-8 / B-9 复制粘贴 · 选择组 · 整理 · 快捷键
    // ==========================================================

    private List<CLBC_Block> selectionOrMenu() {
        List<CLBC_Block> out = new ArrayList<>();
        if (!selection.isEmpty()) {
            for (int id : selection) { CLBC_Block t = idIndex.get(id); if (t != null) out.add(t); }
        } else if (menuBlockId >= 0) {
            CLBC_Block t = idIndex.get(menuBlockId);
            if (t != null) out.add(t);
        }
        return out;
    }
    private void collectSubtreeFlat(CLBC_Block root, List<CLBC_Block> out, Set<Integer> seen) {
        if (root == null || !seen.add(root.id)) return;
        out.add(root);
        if (root.children != null) for (CLBC_Block c : root.children) collectSubtreeFlat(c, out, seen);
    }
    private void collectIds(CLBC_Block b, Set<Integer> set) {
        if (!set.add(b.id)) return;
        if (b.children != null) for (CLBC_Block c : b.children) collectIds(c, set);
    }
    /** 注释是否就近锚定在这批源块上（世界坐标距离，避开 nearestBlock 的屏幕/世界混用）。 */
    private boolean commentNearSources(Comment c, List<CLBC_Block> sources) {
        float ccx = c.x + c.w / 2f, ccy = c.y + c.h / 2f;
        for (CLBC_Block s : sources) {
            if (s.parentId >= 0) continue;
            float bcx = s.x + s.w / 2f, bcy = s.y + s.h / 2f;
            if (Math.hypot(ccx - bcx, ccy - bcy) < 240f) return true;
        }
        return false;
    }

    /** 深拷贝一组顶层块（各含子树 + 内嵌输入窗口 + 就近注释连线），重映射 id / parentId，整体偏移后加入画布。 */
    private List<CLBC_Block> cloneGroupInto(List<CLBC_Block> roots, float offX, float offY) {
        rebuildTree();
        List<CLBC_Block> sources = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (CLBC_Block r : roots) collectSubtreeFlat(r, sources, seen);
        List<Comment> nearComments = new ArrayList<>();
        for (Comment c : comments) if (commentNearSources(c, sources)) nearComments.add(c);
        Map<Integer, Integer> remap = new HashMap<>();
        for (CLBC_Block s : sources) remap.put(s.id, nextId++);
        List<CLBC_Block> made = new ArrayList<>();
        for (CLBC_Block s : sources) {
            CLBC_Block c = cloneBlock(s);       // 含 inputs / cloneInput(child 递归) / slots 深拷贝
            c.id = remap.get(s.id);
            c.parentId = s.parentId >= 0 ? remap.getOrDefault(s.parentId, -1) : -1;
            if (s.parentId < 0) { c.x = s.x + offX; c.y = s.y + offY; }   // 偏移只加在顶层，子块随父布局
            made.add(c);
        }
        blocks.addAll(made);
        for (Comment c : nearComments) {
            Comment nc = new Comment();
            nc.x = c.x + offX; nc.y = c.y + offY; nc.w = c.w; nc.h = c.h; nc.text = c.text;
            comments.add(nc);
        }
        recomputeNextId();
        return made;
    }

    /** B-1 右键 duplicate */
    private void duplicateBlocks(List<CLBC_Block> roots) {
        if (roots == null || roots.isEmpty()) return;
        List<CLBC_Block> made = cloneGroupInto(roots, 24, 24);
        selection.clear();
        for (CLBC_Block c : made) if (c.parentId < 0) selection.add(c.id);
        commit();
        CLBC_Util.log("已复制积木（含子树 / 内嵌窗口 / 注释连线）: " + roots.size() + " 组");
    }
    /** B-1 Ctrl+C */
    private void copySelection() {
        rebuildTree();
        List<CLBC_Block> roots = selectionOrMenu();
        List<CLBC_Block> sources = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (CLBC_Block r : roots) collectSubtreeFlat(r, sources, seen);
        clipBlocks.clear(); clipComments.clear();
        for (CLBC_Block s : sources) clipBlocks.add(cloneBlock(s));
        for (Comment c : comments) if (commentNearSources(c, sources)) {
            Comment nc = new Comment();
            nc.x = c.x; nc.y = c.y; nc.w = c.w; nc.h = c.h; nc.text = c.text;
            clipComments.add(nc);
        }
        CLBC_Util.log("已复制 " + clipBlocks.size() + " 个积木到剪贴板");
    }
    /** B-1 Ctrl+V */
    private void pasteClipboard() {
        if (clipBlocks.isEmpty()) return;
        Map<Integer, Integer> remap = new HashMap<>();
        for (CLBC_Block s : clipBlocks) remap.put(s.id, nextId++);
        List<CLBC_Block> made = new ArrayList<>();
        for (CLBC_Block s : clipBlocks) {
            CLBC_Block c = cloneBlock(s);
            c.id = remap.get(s.id);
            c.parentId = s.parentId >= 0 ? remap.getOrDefault(s.parentId, -1) : -1;
            if (s.parentId < 0) { c.x = s.x + 24; c.y = s.y + 24; }
            made.add(c);
        }
        blocks.addAll(made);
        for (Comment c : clipComments) {
            Comment nc = new Comment();
            nc.x = c.x + 24; nc.y = c.y + 24; nc.w = c.w; nc.h = c.h; nc.text = c.text;
            comments.add(nc);
        }
        recomputeNextId();
        selection.clear();
        for (CLBC_Block c : made) if (c.parentId < 0) selection.add(c.id);
        commit();
        CLBC_Util.log("已粘贴 " + made.size() + " 个积木");
    }
    /** B-9 Delete：删选中集合（各含子树） */
    private void deleteSelection() {
        if (selection.isEmpty()) return;
        rebuildTree();
        Set<Integer> kill = new HashSet<>();
        for (CLBC_Block r : selectionOrMenu()) collectIds(r, kill);
        if (kill.isEmpty()) return;
        blocks.removeIf(b -> kill.contains(b.id));
        selection.clear();
        commit();
        CLBC_Util.log("已删除选中积木 " + kill.size() + " 个（含子树）");
    }
    /** B-9 Ctrl+A */
    private void selectAll() {
        selection.clear();
        for (CLBC_Block b : blocks) if (b.parentId < 0) selection.add(b.id);
        CLBC_Util.log("全选 " + selection.size() + " 个顶层积木");
    }
    /** B-9 方向键微调：只改内存坐标，刻意不 commit，避免按键 REPEAT 每帧入历史 / 频繁写盘。 */
    private void nudgeSelection(float dx, float dy) {
        List<CLBC_Block> targets = new ArrayList<>();
        for (int id : selection) { CLBC_Block t = idIndex.get(id); if (t != null) targets.add(t); }
        if (targets.isEmpty()) return;
        for (CLBC_Block t : targets) {
            if (finite(t.x + dx)) t.x += dx;
            if (finite(t.y + dy)) t.y += dy;
        }
    }
    /** B-8 整理积木：把游离顶层块按列规整、每列紧凑下排（嵌套子块由容器布局管理，不动）。 */
    private void tidyBlocks() {
        List<CLBC_Block> tops = new ArrayList<>();
        for (CLBC_Block b : blocks) if (b.parentId < 0) tops.add(b);
        tops.sort((a, b) -> {
            int col = Float.compare(a.x, b.x);
            return col != 0 ? col : Float.compare(a.y, b.y);
        });
        List<List<CLBC_Block>> cols = new ArrayList<>();
        for (CLBC_Block b : tops) {
            List<CLBC_Block> col = null;
            for (List<CLBC_Block> c : cols) if (Math.abs(c.get(0).x - b.x) < 24) { col = c; break; }
            if (col == null) { col = new ArrayList<>(); cols.add(col); }
            col.add(b);
        }
        float colX = 40;
        for (List<CLBC_Block> col : cols) {
            col.sort((a, b) -> Float.compare(a.y, b.y));
            float y = 40;
            for (CLBC_Block b : col) {
                b.x = colX; b.y = y;
                CLBC_BlockRenderer.measureBlock(b);
                y += b.h + 12f;
            }
            colX += 220;
        }
        commit();
        CLBC_Util.log("整理积木：规整 " + tops.size() + " 个游离块为 " + cols.size() + " 列");
    }
}
