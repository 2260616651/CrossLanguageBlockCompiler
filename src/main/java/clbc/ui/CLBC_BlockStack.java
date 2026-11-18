package clbc.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 积木的命中判定与「整堆拖拽」关系推导。
 *
 * <p>本类只回答两个问题，不碰渲染、不碰数据（不写 parentId / x / y）：
 * <ol>
 *   <li>光标落在哪一块上（精确到子块、忽略 C 口空腔与缺口空隙）；</li>
 *   <li>拖动某一块时，哪些块要跟着一起走、其中哪些块的归属需要重挂。</li>
 * </ol>
 *
 * <p>本项目的数据模型是「扁平列表 + parentId + slotIndex + 纵向 y 序」：
 * <ul>
 *   <li>同一 (parentId, slotIndex) 视为一条连续的积木列，按 y 升序即其先后顺序；</li>
 *   <li>parentId 一律表示块被包进某个容器的某个口，因此最近的关系父就是该容器；</li>
 *   <li>从命中块沿列走到列尾，再越过外层容器的边界时列的标识必变，
 *       故"拖到 } 为止"是这套模型的自然结果，不会把 } 之外的块一起带走。</li>
 * </ul>
 * 结构变更（改 parentId / 坐标）统一由 {@link CLBC_CanvasHost} 负责。
 */
public final class CLBC_BlockStack {

    private CLBC_BlockStack() {}

    /** 命中容差：在积木实心边缘之外再放大的抓取范围 */
    public static final float DRAG_RADIUS = 6f;

    /** 递归深度保护：脏数据（parentId 成环 / 巨型工程）不许卡死主线程 */
    private static final int MAX_DEPTH = 64;

    /** 精确命中结果：带回被点中的块及其屏幕左上角，供拖拽换算世界坐标 */
    public static final class Hit {
        public final CLBC_Block block;
        public final float sx, sy;
        Hit(CLBC_Block block, float sx, float sy) {
            this.block = block; this.sx = sx; this.sy = sy;
        }
    }

    /** 一次拖拽要带走的整堆。
     *  all   = 命中块所在列的后半段 + 每块的全部嵌套后代，用于整体平移；
     *  roots = 需要重新指定归属的那条列（命中块 → 列尾），
     *          嵌套后代保持挂在原父块下，否则整棵树会被拍平。 */
    public static final class Group {
        public final List<CLBC_Block> all;
        public final List<CLBC_Block> roots;
        public final Set<Integer> ids;
        Group(List<CLBC_Block> all, List<CLBC_Block> roots) {
            this.all = all; this.roots = roots;
            Set<Integer> s = new HashSet<>();
            for (CLBC_Block b : all) s.add(b.id);
            this.ids = s;
        }
    }

    // ==========================================================
    // ① 命中：精确定位光标实际落在哪一块
    // ==========================================================

    /**
     * 按绘制顺序倒序（后画的在上层），逐棵子树递归命中。
     * 命中不了就返回 null，绝不"退而返回顶层根"——那个兜底会让点在空腔或缺口里时
     * 误抓到最外层容器，导致"拖的是哪一块都分析不清"。
     */
    public static Hit hit(List<CLBC_Block> flat, float mx, float my,
                          float regionX, float regionY, float panX, float panY) {
        for (int i = flat.size() - 1; i >= 0; i--) {
            CLBC_Block root = flat.get(i);
            if (root.parentId >= 0 || root.hidden) continue;
            // 用真实 GL 字体重算整棵树：粗糙粗估比实际绘制窄，
            // 拿它命中会"点积木右半边抓不到"。
            CLBC_BlockRenderer.measureBlock(root);
            float sx = regionX + root.x + panX, sy = regionY + root.y + panY;
            if (mx < sx - DRAG_RADIUS || mx > sx + root.w + DRAG_RADIUS
                    || my < sy - CLBC_BlockRenderer.HAT_BUMP - DRAG_RADIUS
                    || my > sy + root.h + DRAG_RADIUS) continue;
            Hit h = hitTree(root, sx, sy, mx, my, DRAG_RADIUS, 0);
            if (h != null) return h;
        }
        return null;
    }

    private static Hit hitTree(CLBC_Block b, float sx, float sy,
                               float mx, float my, float pad, int depth) {
        if (depth > MAX_DEPTH) return null;
        CLBC_BlockShape.Shape s = CLBC_BlockShape.shapeOf(b);
        if (CLBC_BlockShape.isContainer(s.kind) && !b.collapsed) {
            // 子块后画的在上层 → 命中要倒序；但坐标必须按正向堆积顺序预先算好。
            // 旧实现倒序循环里从第一块的 cy 开始累加，等于把「最后一块」拿到「第一块」的位置测，
            // 点输入框 / 点子块会命中同一列里另一块（用户所见「拖拽分不清是哪一块」）。
            for (int i = s.slotCount - 1; i >= 0; i--) {
                float[] o = s.childOffset(i);
                float cx = sx + o[0];
                List<CLBC_Block> kids = b.slotChildren(i);
                float[] kidY = new float[kids.size()];
                float cursor = sy + o[1];
                for (int k = 0; k < kids.size(); k++) {
                    kidY[k] = cursor;
                    cursor += kids.get(k).h + CLBC_BlockRenderer.CHILD_GAP;
                }
                for (int k = kids.size() - 1; k >= 0; k--) {
                    Hit h = hitTree(kids.get(k), cx, kidY[k], mx, my, pad, depth + 1);
                    if (h != null) return h;
                }
            }
        }
        // 内嵌输入窗口里的报告积木：drawSegments 把它们画在块本体之上，
        // 但它们的 child 不在 slotChildren 里，旧命中递归完全没覆盖 →
        // 点 < / + / 比较块的参数窗口会命中宿主语句块。窗口矩形直接取渲染层
        // 同源的 sockets()，child 绘制位置与 drawSegments 完全一致。
        for (CLBC_BlockRenderer.SocketHit h : CLBC_BlockRenderer.sockets(b, sx, sy)) {
            CLBC_Block child = h.input.child;
            if (child == null) continue;
            Hit hit = hitTree(child, h.x + 3f, h.y + (h.h - child.h) / 2f, mx, my, pad, depth + 1);
            if (hit != null) return hit;
        }
        return containsPadded(s, mx - sx, my - sy, pad) ? new Hit(b, sx, sy) : null;
    }

    /** 逐条带扫描命中，含 pad 容差：与绘制层的 contains 同源，
     *  凹口/圆角之外的空隙也算抓取范围。 */
    private static boolean containsPadded(CLBC_BlockShape.Shape s, float lx, float ly, float pad) {
        for (int i = 0; i < s.bandCount; i++) {
            int k = i * 6;
            float y0 = s.bands[k], y1 = s.bands[k + 3];
            if (ly < y0 - pad || ly > y1 + pad) continue;
            float cy = Math.max(y0, Math.min(y1, ly));
            float t = (y1 - y0) < 0.001f ? 0f : (cy - y0) / (y1 - y0);
            float l = s.bands[k + 1] + (s.bands[k + 4] - s.bands[k + 1]) * t;
            float r = s.bands[k + 2] + (s.bands[k + 5] - s.bands[k + 2]) * t;
            if (lx >= l - pad && lx <= r + pad) return true;
        }
        return false;
    }

    // ==========================================================
    // ② 列关系推导
    // ==========================================================

    /** 一条积木列的标识：跨过外层容器的边界时该值必变 */
    public static String stackKey(CLBC_Block b) {
        return b.parentId + ":" + (b.parentId >= 0 ? b.slotIndex : 0);
    }

    /** 同一列的全部块（同 scope + 同一列），按 y 升序（同 y 时保持扁平列表原序）。
     *  模型里没有显式的"下一块"字段，故用「x 同列」还原：
     *  并排摆放的两摞积木属于两条独立列，不能因为 y 都更大就被一起拖走。 */
    public static List<CLBC_Block> stackSiblings(List<CLBC_Block> flat, CLBC_Block b) {
        Map<CLBC_Block, Integer> order = new IdentityHashMap<>();
        for (int i = 0; i < flat.size(); i++) order.put(flat.get(i), i);
        String key = stackKey(b);
        List<CLBC_Block> r = new ArrayList<>();
        for (CLBC_Block o : flat)
            if (!o.hidden && stackKey(o).equals(key) && Math.abs(o.x - b.x) <= COLUMN_TOL) r.add(o);
        r.sort(Comparator.<CLBC_Block>comparingDouble(o -> o.y)
                .thenComparingInt(o -> order.getOrDefault(o, 0)));
        return r;
    }

    /** 同列容差：只需覆盖拖拽/吸附产生的几像素偏差 */
    private static final float COLUMN_TOL = 24f;
    /** 允许的堆叠断口：代码→积木的展平用粗估高度 +8，容器块实测高度含全部子块，
     *  两者不相等，故给一个宽松上限；超过它视为「两摞不相连的积木」。 */
    private static final float MAX_STACK_GAP = 160f;

    /** 同一列里紧跟在 b 后面的那块 */
    public static CLBC_Block getNextBlock(List<CLBC_Block> flat, CLBC_Block b) {
        List<CLBC_Block> s = stackSiblings(flat, b);
        int i = s.indexOf(b);
        return (i >= 0 && i + 1 < s.size()) ? s.get(i + 1) : null;
    }

    /** 同一列里排在 b 前面的那块 */
    public static CLBC_Block getPreviousBlock(List<CLBC_Block> flat, CLBC_Block b) {
        List<CLBC_Block> s = stackSiblings(flat, b);
        int i = s.indexOf(b);
        return i > 0 ? s.get(i - 1) : null;
    }

    /** 沿列走到最后一块 */
    public static CLBC_Block lastConnectionInStack(List<CLBC_Block> flat, CLBC_Block b) {
        CLBC_Block cur = b;
        Set<Integer> seen = new HashSet<>();
        CLBC_Block next;
        while (seen.add(cur.id) && (next = getNextBlock(flat, cur)) != null) cur = next;
        return cur;
    }

    /** 沿 parentId 找顶层祖先，带环保护 */
    public static CLBC_Block getRootBlock(Map<Integer, CLBC_Block> index, CLBC_Block b) {
        CLBC_Block cur = b;
        Set<Integer> seen = new HashSet<>();
        while (cur != null && cur.parentId >= 0 && seen.add(cur.id)) {
            cur = index.get(cur.parentId);
        }
        return cur == null ? b : cur;
    }

    /** 返回把当前块包在口里的那个容器，没有则 null。
     *  本项目 parentId 一律表示 C 口嵌套，故最近的关系父就是容器。 */
    public static CLBC_Block getSurroundParent(Map<Integer, CLBC_Block> index, CLBC_Block b) {
        CLBC_Block cur = b;
        Set<Integer> seen = new HashSet<>();
        while (cur != null && cur.parentId >= 0 && seen.add(cur.id)) {
            CLBC_Block p = index.get(cur.parentId);
            if (p == null) return null;
            if (CLBC_BlockShape.isContainer(p.kind == null ? "stack" : p.kind)) return p;
            cur = p;
        }
        return null;
    }

    // ==========================================================
    // ③ 整堆拖拽
    // ==========================================================

    /** 从命中块到列尾的那条链。
     *  链尾天然停在外层容器的边界之前：边界外的块列标识不同，不会被收进来。
     *  另外在同列内做一次"断口"检查：中间空了一大截的两摞积木不该被当成一条列。 */
    public static List<CLBC_Block> stackFrom(List<CLBC_Block> flat, CLBC_Block hit) {
        List<CLBC_Block> s = stackSiblings(flat, hit);
        int i = s.indexOf(hit);
        List<CLBC_Block> r = new ArrayList<>();
        if (i < 0) { r.add(hit); return r; }
        CLBC_Block cur = hit;
        r.add(cur);
        for (int j = i + 1; j < s.size(); j++) {
            CLBC_Block o = s.get(j);
            if (o.y < cur.y) continue;
            float expected = cur.y + (cur.h > 0 ? cur.h : CLBC_BlockShape.STACK_H);
            if (o.y - expected > MAX_STACK_GAP) break;
            r.add(o);
            cur = o;
        }
        return r;
    }

    /** 拖拽整堆：命中块之下同列全部 + 每一块的全部嵌套后代。
     *  不修改任何块字段，纯判定。 */
    public static Group dragGroup(List<CLBC_Block> flat, CLBC_Block hit) {
        List<CLBC_Block> roots = stackFrom(flat, hit);
        LinkedHashSet<CLBC_Block> all = new LinkedHashSet<>();
        for (CLBC_Block r : roots) {
            all.add(r);
            collectDescendants(flat, r, all, 0);
        }
        return new Group(new ArrayList<>(all), roots);
    }

    /** 按 parentId 链递归收集全部后代，带环 / 深度保护 */
    private static void collectDescendants(List<CLBC_Block> flat, CLBC_Block parent,
                                           Set<CLBC_Block> out, int depth) {
        if (depth > MAX_DEPTH) return;
        for (CLBC_Block c : flat) {
            if (c.parentId != parent.id || c.hidden || !out.add(c)) continue;
            collectDescendants(flat, c, out, depth + 1);
        }
    }

    /** 整堆的垂直跨度（从首块顶到末块底，含容器自身高度 = 已包含 C 口全部子块） */
    public static float stackHeight(List<CLBC_Block> roots) {
        if (roots == null || roots.isEmpty()) return CLBC_BlockShape.STACK_H;
        float top = Float.MAX_VALUE, bottom = -Float.MAX_VALUE;
        for (CLBC_Block b : roots) {
            float h = b.h > 0 ? b.h : CLBC_BlockShape.STACK_H;
            top = Math.min(top, b.y);
            bottom = Math.max(bottom, b.y + h);
        }
        return Math.max(CLBC_BlockShape.STACK_H, bottom - top);
    }
}
