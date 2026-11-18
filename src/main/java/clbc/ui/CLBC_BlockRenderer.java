package clbc.ui;

import clbc.gl.CLBC_GL_API;
import clbc.gl.CLBC_GL_Widgets;
import clbc.ui.CLBC_BlockShape.Segment;
import clbc.ui.CLBC_BlockShape.Shape;
import clbc.ui.CLBC_BlockShape.TextMeasurer;

import java.util.ArrayList;
import java.util.List;

/**
 * 积木绘制工厂（OpenGL 即时模式，纯色 + 1px 微深边框，几何由 {@link CLBC_BlockShape} 提供）。
 *
 * 造型约定：
 *  · 连接口在左侧，是梯形燕尾槽而非中央圆插头；上一块底部凸起与下一块顶部凹槽互补、无缝咬合。
 *  · 带口块是真正的 C 形（顶栏 + 左臂 + 底条围出右侧开口），多口时插入分隔栏形成
 *    C(1) / E(2) / 任意 N 口，支持 if → else if → else 无限串接。
 *  · 无高光、无渐变、无内腔填色：整体一个纯色，仅轮廓描 1px「轻微加深」的同相色。
 *
 * 布局（measureBlock / drawBlock）与命中（hitTest / childOffset）共用同一份 Shape，不漂移。
 */
public final class CLBC_BlockRenderer {

    private CLBC_BlockRenderer() {}

    private static final long TEXT_WHITE = CLBC_GL_API.hex("#FFFFFF");
    private static final long TEXT_DARK  = CLBC_GL_API.hex("#333333");
    private static final long SLOT_WHITE = CLBC_GL_API.hex("#FFFFFF");
    private static final long SLOT_EDGE  = CLBC_GL_API.hex("#C9D3E0"); // 内嵌窗口淡边框
    private static final long INVALID_RED = 0xFFFF3B30L;

    /** 边框加深比例：在主体色上压暗一档作描边，保持同色相 */
    private static final float EDGE_DARK = 0.24f;

    /** 帽块拱起高度（命中/包围盒需预留） */
    public static final float HAT_BUMP = CLBC_BlockShape.HAT_H;
    /** 堆叠间距：为 0，由燕尾口咬合 */
    public static final float CHILD_GAP = CLBC_BlockShape.CHILD_GAP;

    /** GL 字体宽度供给者；离线 / 字体未加载时用字符数粗估，几何仍可计算 */
    private static final TextMeasurer GL_TEXT = t ->
            CLBC_GL_API.uiFont == null
                    ? CLBC_BlockShape.ROUGH.width(t)
                    : CLBC_GL_API.measureText(CLBC_GL_API.uiFont, t);

    /** drawPolygon 复用缓冲，避免每帧为每条扫描带 new 数组 */
    private static final float[] BX = new float[4], BY = new float[4];

    // ============ 对外：测量 / 绘制 ============

    /** 纯测算：递归写回 b.w / b.h / b.shape，供布局与命中测试使用 */
    public static void measureBlock(CLBC_Block b) {
        CLBC_BlockShape.layout(b, Float.MAX_VALUE, GL_TEXT, true);
    }

    /** 布局 + 绘制一个积木（自动递归所有口的子块） */
    public static float[] drawBlock(CLBC_Block b, float x, float y) {
        CLBC_BlockShape.layout(b, Float.MAX_VALUE, GL_TEXT, true);
        return draw(b, x, y);
    }

    private static float[] draw(CLBC_Block b, float x, float y) {
        return draw(b, x, y, false);
    }

    private static float[] draw(CLBC_Block b, float x, float y, boolean nested) {
        Shape s = b.shape != null ? b.shape : CLBC_BlockShape.layout(b, Float.MAX_VALUE, GL_TEXT, false);
        long body = b.color;
        // #实测修复：invalid 不再把整块轮廓描成通红。反编译/导入时 validateConnections
        // 会把大量块标为非法，整屏红色直角感线框就是这么来的（用户截图里那一堆"红框"）。
        // 现在轮廓始终用同相深色，非法状态只留一个克制的小角标，信息不丢、界面不吵。
        long edge = CLBC_GL_API.darken(body, EDGE_DARK);

        // 投影只给最外层块：堆叠/嵌套的子块与父块是咬合的一体，
        // 子块再画投影会在接缝处透出一条灰边，看起来像积木之间没接上。
        if (!nested) fillShape(s, x + 2f, y + 3f, CLBC_GL_API.rgba(45, 60, 85, 34));
        fillShape(s, x, y, body);
        strokeShape(s, x, y, edge);
        drawLabels(b, s, x, y);

        if (CLBC_BlockShape.isContainer(s.kind) && !b.collapsed) {
            for (int i = 0; i < s.slotCount; i++) {
                float[] o = s.childOffset(i);
                float cx = x + o[0], cy = y + o[1];
                for (CLBC_Block c : b.slotChildren(i)) {
                    draw(c, cx, cy, true);
                    cy += c.h + CHILD_GAP;
                }
            }
        }
        return new float[]{s.w, s.h};
    }

    /** 逐扫描带填纯色（梯形是凸四边形，三角扇安全；C 口内腔自动镂空） */
    private static void fillShape(Shape s, float x, float y, long color) {
        for (int i = 0; i < s.bandCount; i++) {
            int k = i * 6;
            float l0 = x + s.bands[k + 1], r0 = x + s.bands[k + 2], y0 = y + s.bands[k];
            float l1 = x + s.bands[k + 4], r1 = x + s.bands[k + 5];
            // 相邻扫描条带故意重叠 0.6px：GL 光栅化在共享边上会各留半像素抗锯齿，
            // 否则横条内部出现一条「浅色条纹」（主色被 #F9F9F9 背景冲淡的 3 行像素）。
            float y1 = y + s.bands[k + 3] + 0.6f;
            if (r0 - l0 < 0.05f && r1 - l1 < 0.05f) continue;
            BX[0] = l0; BX[1] = r0; BX[2] = r1; BX[3] = l1;
            BY[0] = y0; BY[1] = y0; BY[2] = y1; BY[3] = y1;
            CLBC_GL_API.drawPolygon(true, color, BX, BY, 4);
        }
    }

    /** 沿真实轮廓描 1px 边（含 C 口内沿），颜色为同相色轻微加深 */
    private static void strokeShape(Shape s, float x, float y, long edge) {
        for (int i = 0; i < s.n; i++) {
            int j = (i + 1) % s.n;
            CLBC_GL_API.drawLine(x + s.ox[i], y + s.oy[i], x + s.ox[j], y + s.oy[j], edge, 1.2f);
        }
    }

    /** 沿积木自身轮廓描粗边（选中高亮 / 拖拽落点 / 吸附目标）。
     *  描的是块的实际轮廓路径，圆角与 C 口自动贴合，
     *  而不是套在块外面的直角矩形框。 */
    public static void strokeOutline(CLBC_Block b, float x, float y, long color, float width) {
        if (b == null) return;
        CLBC_BlockShape.Shape s = b.shape != null ? b.shape
                : CLBC_BlockShape.layout(b, Float.MAX_VALUE, GL_TEXT, false);
        if (s == null || s.n < 3) return;
        for (int i = 0; i < s.n; i++) {
            int j = (i + 1) % s.n;
            CLBC_GL_API.drawLine(x + s.ox[i], y + s.oy[i], x + s.ox[j], y + s.oy[j], color, width);
        }
    }

    /**
     * 每条标签栏画该口的分支文字 / 参数输入槽；语句块与报告块也终于有自己的文字
     * （旧实现只遍历 slotCount，非容器块恒为 0 → 整块无字）。
     * 类 / 方法首栏额外挂可编辑名称框，位置由同一份片段宽度推算，命中不漂移。
     */
    private static void drawLabels(CLBC_Block b, Shape s, float x, float y) {
        boolean hat = CLBC_BlockShape.isHat(s.kind);
        boolean container = CLBC_BlockShape.isContainer(s.kind);
        if (!container) {
            // 栏高读几何数据：内嵌口被内部报告块撑大时，基线随整块一起下移居中
            float barH = s.barH != null && s.barH.length > 0 ? s.barH[0]
                    : (("round".equals(s.kind) || "hex".equals(s.kind)) ? CLBC_BlockShape.ROUND_H : CLBC_BlockShape.STACK_H);
            drawSegments(CLBC_BlockShape.segments(b, 0, GL_TEXT), x + 12f,
                    y + barH / 2f + 5f, b, x, y, b.color);
        } else {
            for (int i = 0; i < s.slotCount; i++) {
                float barH = s.barH != null && i < s.barH.length ? s.barH[i]
                        : (i == 0 ? CLBC_BlockShape.TOP_H : CLBC_BlockShape.BAR_H);
                float cy = y + s.barTop[i] + barH / 2f + 5f;
                List<Segment> segs = CLBC_BlockShape.segments(b, i, GL_TEXT);
                float lx = x + 12f;
                drawSegments(segs, lx, cy, b, x, y, b.color);
                // 方法定义首栏已把名称做成参数窗口，不再叠加 hat 名称框（否则出现两个 run）
                if (i == 0 && hat && !"unit.method".equals(b.key)) {
                    float nx = lx + CLBC_BlockShape.segmentsWidth(segs) + 8f;
                    float nw = CLBC_BlockShape.nameFieldWidth(b, s.kind, GL_TEXT);
                    String nn = CLBC_GL_Widgets.textFieldAuto(b.id * 100 + 1, px(nx), px(y + 5),
                            px(nw), 26, b.name, TEXT_WHITE, TEXT_DARK, b.color);
                    if (!nn.equals(b.name)) b.name = nn;
                }
            }
        }
        if (b.collapsed) {
            // 折叠标记改用几何三角形绘制，不依赖字体（msyhbd 缺 ▶ 字形 → 空方框 tofu）
            float tx = px(x + s.w - 26), ty = px(y + CLBC_BlockShape.TOP_H / 2f + 5f);
            CLBC_GL_API.drawPolygon(true, TEXT_WHITE,
                    new float[]{tx, tx + 8f, tx},
                    new float[]{ty - 5f, ty, ty + 5f}, 3);
        }
        // 规则15：非法状态用左上角小圆点 + "!" 提示，不再整块描红（避免满屏红框）。
        if (b.invalid) {
            float cx = px(x + 13), cy = px(y + 13);
            CLBC_GL_API.fillCircle(INVALID_RED, cx, cy, 7f);
            CLBC_GL_API.drawText(CLBC_GL_API.uiFont, "!", px(cx - 3.5f), px(cy + 5), TEXT_WHITE);
        }
    }

    /**
     * 按片段逐段绘制：普通白字 / 白色胶囊数值槽（深字）/ 六边形布尔条件槽（白字）。
     * 与 {@link CLBC_BlockShape#segments} 共用宽度，测算、绘制、缩略图三者同源。
     */
    private static void drawSegments(List<Segment> segs, float x, float baseline,
                                     CLBC_Block b, float bx, float by, long body) {
        if (segs == null) return;
        float cursor = x;
        for (Segment seg : segs) {
            if (seg.plus) {
                // 圆圈加号：在参数窗口末尾画一个圆形按钮 + 号（Scratch 变长参数入口的视觉）。
                float pcx = px(cursor + seg.width / 2f), pcy = px(baseline - 2f);
                CLBC_GL_API.fillCircle(CLBC_GL_API.hex("#8A5A14"), pcx, pcy, 8.5f);
                CLBC_GL_API.fillCircle(CLBC_GL_API.rgba(255, 255, 255, 240), pcx, pcy, 7f);
                CLBC_GL_API.drawLine(pcx - 3.6f, pcy, pcx + 3.6f, pcy, CLBC_GL_API.hex("#8A5A14"), 1.6f);
                CLBC_GL_API.drawLine(pcx, pcy - 3.6f, pcx, pcy + 3.6f, CLBC_GL_API.hex("#8A5A14"), 1.6f);
                cursor += seg.width + CLBC_BlockShape.SEG_GAP;
                continue;
            }
            if (seg.arg) {
                float sh = seg.height;
                // 内嵌窗口在标签栏内垂直居中（推导见下）：sy = baseline - 5 - sh/2。
                // 旧式 baseline - sh + 5 会让装入真实报告块的窗口整体上移、顶出栏顶。
                float sy = baseline - 5f - sh / 2f;
                float sw = seg.width;
                // 内嵌窗口矩形写回数据模型（相对本块左上），供命中测试使用
                if (seg.input != null) {
                    seg.input.x = cursor - bx; seg.input.y = sy - by;
                    seg.input.w = sw;           seg.input.h = sh;
                }
                CLBC_Block child = seg.input == null ? null : seg.input.child;
                if (child != null) {
                    // 窗口真正承载一块报告积木（oval/hexagon）：直接把整块（含它自己的窗口）画进来，
                    // 不画白底占位，避免「白框套白框 / 彩色块边缘留白」伪影。
                    // 实测修复：超长报告块链（长字符串拼接等）宽度远超窗口上限，不裁会横贯画布、
                    // 把父块撑出屏幕。这里按窗口矩形裁剪；几何层 argSeg 已对同一窗口封顶，
                    // 绘制/测量/命中三处宽度同源，数据不变。
                    float cy = sy + (sh - child.h) / 2f;
                    CLBC_GL_API.setClipLogical(cursor, sy, sw, sh);
                    try {
                        draw(child, px(cursor + 3f), px(cy), true);
                    } finally {
                        CLBC_GL_API.clearClip();
                    }
                } else if (seg.dropdown) {
                    // 枚举下拉字段：不是白色胶囊，
                    // 而是比积木主色深一档的实心圆角块 + 白字 + 右侧 ▾ 三角。
                    drawDropdownWindow(cursor, sy, sw, sh, body);
                    String t = CLBC_BlockShape.dropdownDisplay(
                            seg.input == null ? seg.text : seg.input.code());
                    CLBC_GL_API.drawText(CLBC_GL_API.uiFont,
                            fit(t, sw - CLBC_BlockShape.DROPDOWN_ARROW_W - CLBC_BlockShape.SLOT_PAD),
                            px(cursor + CLBC_BlockShape.SLOT_PAD), px(baseline), TEXT_WHITE);
                } else {
                    // 空窗口：画白色胶囊 / 六边形占位 + 文本
                    if (seg.hex) drawHexWindow(cursor, sy, sw, sh);
                    else         drawPillWindow(cursor, sy, sw, sh);
                    String t = seg.input == null ? seg.text : seg.input.code();
                    float tx = cursor + (seg.hex
                            ? CLBC_BlockShape.hexTip(sh) + 4f
                            : CLBC_BlockShape.SLOT_PAD);
                    CLBC_GL_API.drawText(CLBC_GL_API.uiFont, fit(t, sw - 2f * CLBC_BlockShape.SLOT_PAD),
                            px(tx), px(baseline), TEXT_DARK);
                }
            } else if (!seg.text.isEmpty()) {
                CLBC_GL_API.drawText(CLBC_GL_API.uiFont, seg.text, px(cursor), px(baseline), TEXT_WHITE);
            }
            cursor += seg.width + CLBC_BlockShape.SEG_GAP;
        }
    }

    /** 输入窗口文字：完整显示，绝不省略号截断（用户明确「把截断删了」）。
     *  只把换行压成空格保持单行布局；宽度溢出靠画布缩放/平移解决，不牺牲文本完整性。 */
    private static String fit(String t, float maxW) {
        if (t == null) return "";
        return t.replace('\n', ' ');
    }

    /** 文本/数字内嵌窗口：两端半圆 + 中间矩形（stadium 胶囊），白底 + 1px 淡边框 */
    private static void drawPillWindow(float x, float y, float w, float h) {
        x = px(x); y = px(y); w = px(w); h = px(h);
        CLBC_GL_API.fillRoundRect(SLOT_EDGE, x - 1, y - 1, w + 2, h + 2, (h + 2) / 2f);
        CLBC_GL_API.fillRoundRect(SLOT_WHITE, x, y, w, h, h / 2f);
    }

    /**
     * 枚举下拉字段：比主色深一档的实心圆角块 + 右侧白色 ▾ 三角。
     * 与白色胶囊 / 六边形布尔槽视觉区分开：可自由输入的槽是白底深字，
     * 只能从列表选的字段是深色底白字带箭头。
     */
    private static void drawDropdownWindow(float x, float y, float w, float h, long body) {
        x = px(x); y = px(y); w = px(w); h = px(h);
        long bg = CLBC_GL_API.darken(body, 0.28f);
        CLBC_GL_API.fillRoundRect(bg, x, y, w, h, Math.min(6f, h / 2f));
        // ▾ 三角（右端留 4px，与 DROPDOWN_ARROW_W 预留宽度对齐）
        float cx = x + w - 10f, cy = y + h / 2f - 2f, half = 4f;
        float[] tx = {cx - half, cx + half, cx};
        float[] ty = {cy, cy, cy + 5f};
        CLBC_GL_API.drawPolygon(true, TEXT_WHITE, tx, ty, 3);
    }

    /** 布尔内嵌窗口：左右带尖角的六边形，白底 + 1px 淡边框；内可放比较/逻辑报告块 */
    private static void drawHexWindow(float x, float y, float w, float h) {
        x = px(x); y = px(y); w = px(w); h = px(h);
        float t = CLBC_BlockShape.hexTip(h);   // 六个内角恒 120°
        float[] ex = {x + t, x + w - t, x + w + 1, x + w - t, x + t, x - 1};
        float[] ey = {y - 1, y - 1, y + h / 2f, y + h + 1, y + h + 1, y + h / 2f};
        CLBC_GL_API.drawPolygon(true, SLOT_EDGE, ex, ey, 6);
        float[] hx = {x + t, x + w - t, x + w, x + w - t, x + t, x};
        float[] hy = {y, y, y + h / 2f, y + h, y + h, y + h / 2f};
        CLBC_GL_API.drawPolygon(true, SLOT_WHITE, hx, hy, 6);
    }

    /** 字形左边缘对齐整数像素，消除亚像素采样导致的发虚 */
    private static float px(float v) { return Math.round(v); }

    // ============ 命中测试（递归，与绘制共用 Shape） ============

    /** 返回点 (mx,my) 命中的最深块；用扫描带判定，点落在 C 口镂空区不会误选父块 */
    public static CLBC_Block hitTest(CLBC_Block b, float sx, float sy, float mx, float my) {
        Shape s = CLBC_BlockShape.shapeOf(b);
        if (CLBC_BlockShape.isContainer(s.kind) && !b.collapsed) {
            for (int i = 0; i < s.slotCount; i++) {
                float[] o = s.childOffset(i);
                float cx = sx + o[0];
                java.util.List<CLBC_Block> kids = b.slotChildren(i);
                // 与 BlockStack.hitTree 同源：正向堆积算坐标，倒序命中取上层块。
                // 不能在倒序循环里边测边累加，否则子块坐标与其绘制位置错位。
                float[] kidY = new float[kids.size()];
                float cursor = sy + o[1];
                for (int k = 0; k < kids.size(); k++) {
                    kidY[k] = cursor;
                    cursor += kids.get(k).h + CHILD_GAP;
                }
                for (int k = kids.size() - 1; k >= 0; k--) {
                    CLBC_Block hit = hitTest(kids.get(k), cx, kidY[k], mx, my);
                    if (hit != null) return hit;
                }
            }
        }

        // 输入窗口内的报告积木画在块本体之上，必须同样参与命中；
        // 位置取自与本类绘制同源的 sockets()，与 drawSegments 的居中公式一致。
        for (SocketHit h : sockets(b, sx, sy)) {
            CLBC_Block child = h.input.child;
            if (child == null) continue;
            CLBC_Block hit = hitTest(child, h.x + 3f, h.y + (h.h - child.h) / 2f, mx, my);
            if (hit != null) return hit;
        }
        return contains(s, mx - sx, my - sy) ? b : null;
    }

    /** 点是否落在积木实心区（按扫描带线性插值）；传局部坐标 */
    public static boolean contains(Shape s, float lx, float ly) {
        for (int i = 0; i < s.bandCount; i++) {
            int k = i * 6;
            float y0 = s.bands[k], y1 = s.bands[k + 3];
            if (ly < y0 || ly > y1) continue;
            float t = (y1 - y0) < 0.001f ? 0 : (ly - y0) / (y1 - y0);
            float l = s.bands[k + 1] + (s.bands[k + 4] - s.bands[k + 1]) * t;
            float r = s.bands[k + 2] + (s.bands[k + 5] - s.bands[k + 2]) * t;
            if (lx >= l - 0.5f && lx <= r + 0.5f) return true;
        }
        return false;
    }

    /** 父块中第 0 口子块的偏移（旧调用兼容） */
    public static float[] childOffset(CLBC_Block b) { return childOffset(b, 0); }

    /** 父块中第 slot 口子块的相对偏移（与 draw 完全一致） */
    public static float[] childOffset(CLBC_Block b, int slot) {
        return CLBC_BlockShape.shapeOf(b).childOffset(slot);
    }

    // ============ 内嵌输入窗口命中 ============

    /** 一个内嵌窗口的屏幕矩形（owner = 承载它的积木） */
    public static final class SocketHit {
        public final CLBC_Block owner;
        public final CLBC_Block.Input input;
        public final float x, y, w, h;
        SocketHit(CLBC_Block owner, CLBC_Block.Input input, float x, float y, float w, float h) {
            this.owner = owner; this.input = input;
            this.x = x; this.y = y; this.w = w; this.h = h;
        }
        public boolean contains(float mx, float my) {
            return mx >= x && mx <= x + w && my >= y && my <= y + h;
        }
    }

    /** 从一棵积木树收集全部内嵌窗口的屏幕矩形（不依赖绘制顺序，命中可随时调用） */
    public static List<SocketHit> sockets(CLBC_Block root, float sx, float sy) {
        List<SocketHit> out = new ArrayList<>();
        collectSockets(root, sx, sy, out);
        return out;
    }

    private static void collectSockets(CLBC_Block b, float sx, float sy, List<SocketHit> out) {
        if (b == null) return;
        Shape s = CLBC_BlockShape.shapeOf(b);
        boolean container = CLBC_BlockShape.isContainer(s.kind);
        int rows = container ? s.slotCount : 1;
        for (int r = 0; r < rows; r++) {
            List<Segment> segs = CLBC_BlockShape.segments(b, r, GL_TEXT);
            float cursor = sx + 12f;
            // 与 drawLabels 同源：栏高读 Shape.barH，命中矩形不随内嵌口撑大而漂移
            float barH = s.barH != null && r < s.barH.length ? s.barH[r]
                    : (container ? (r == 0 ? CLBC_BlockShape.TOP_H : CLBC_BlockShape.BAR_H)
                                 : ("round".equals(s.kind) || "hex".equals(s.kind)
                                    ? CLBC_BlockShape.ROUND_H : CLBC_BlockShape.STACK_H));
            float baseline = sy + (container ? s.barTop[r] + barH / 2f : barH / 2f) + 5f;
            for (Segment seg : segs) {
                // 与 drawSegments 同源的垂直居中公式，保证命中矩形与绘制位置严格重合
                float y = baseline - 5f - seg.height / 2f;
                if (seg.arg && seg.input != null) {
                    out.add(new SocketHit(b, seg.input, cursor, y, seg.width, seg.height));
                    if (seg.input.child != null)
                        collectSockets(seg.input.child, cursor + 3f,
                                y + (seg.height - seg.input.child.h) / 2f, out);
                }
                cursor += seg.width + CLBC_BlockShape.SEG_GAP;
            }
        }
        if (container && !b.collapsed) {
            for (int i = 0; i < s.slotCount; i++) {
                float[] o = s.childOffset(i);
                float cx = sx + o[0], cy = sy + o[1];
                for (CLBC_Block c : b.slotChildren(i)) {
                    collectSockets(c, cx, cy, out);
                    cy += c.h + CHILD_GAP;
                }
            }
        }
    }
}
