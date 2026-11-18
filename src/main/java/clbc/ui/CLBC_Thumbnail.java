package clbc.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import clbc.ui.CLBC_BlockShape.Segment;
import clbc.ui.CLBC_BlockShape.Shape;
import clbc.ui.CLBC_BlockShape.TextMeasurer;
import clbc.util.CLBC_Util;

/**
 * 工程缩略图生成（纯 JDK Java2D 离屏渲染，**不依赖 OpenGL / GLFW / 字体图集**）。
 *
 * 与 {@link CLBC_BlockRenderer} 共用 {@link CLBC_BlockShape} 的同一份轮廓与扫描条带，
 * 因此缩略图与真实画布的积木形状（燕尾槽 / C 口 / 多口分隔栏）严格一致，而非各自近似。
 *
 *  · 独立重建嵌套树（依据 parentId + slotIndex），不改动原积木的瞬态 children 字段。
 *  · 纯色填充 + 同色轻微加深 1px 描边，与画布视觉规则相同；C 口内腔真正镂空。
 *  · 全程 try/catch 兜底：失败只记日志并返回 false，绝不影响保存主流程；空画布也产出合法 PNG。
 */
public final class CLBC_Thumbnail {

    private CLBC_Thumbnail() {}

    /** 与画布实底色一致（workspace #F9F9F9） */
    private static final Color BG = new Color(0xF9, 0xF9, 0xF9);
    private static final Color GRID = new Color(0xDD, 0xDD, 0xDD);
    private static final Color TEXT_WHITE = new Color(0xFF, 0xFF, 0xFF);
    private static final Color TEXT_DARK = new Color(0x33, 0x33, 0x33);
    private static final Color SLOT_EDGE = new Color(0xD9, 0xD9, 0xD9);
    private static final Color INVALID_RED = new Color(0xFF, 0x3B, 0x30);
    private static final Color EMPTY_TEXT = new Color(0x88, 0x88, 0x88);

    private static final float CHILD_GAP = CLBC_BlockShape.CHILD_GAP;
    private static final float BODY_PAD = CLBC_BlockShape.BODY_PAD;
    private static final float HAT_BUMP = CLBC_BlockShape.HAT_H;
    private static final int MAX_DEPTH = 64;      // 防御 parentId 成环 / 超深嵌套
    private static final Font FONT = new Font(Font.SANS_SERIF, Font.BOLD, 13);
    private static final float EDGE_DARK = 0.20f;   // 与 GL 画布一致，压暗一档作描边

    /** 便捷重载：默认 320×200 缩略图 */
    public static boolean write(List<CLBC_Block> blocks, String pngPath) {
        return write(blocks, pngPath, 320, 200);
    }

    /** 工程路径 → 缩略图路径（与 saveProject 的落盘约定一致） */
    public static String thumbnailPath(String projectPath) {
        return projectPath + ".png";
    }

    /**
     * 生成缩略图 PNG。
     * @param blocks  扁平积木列表（含 parentId / slotIndex；children 会被独立重建，不作为输入）
     * @param pngPath 输出路径（父目录不存在会自动创建）
     */
    public static boolean write(List<CLBC_Block> blocks, String pngPath, int maxW, int maxH) {
        if (pngPath == null || pngPath.isEmpty()) return false;
        final int W = Math.max(64, maxW), H = Math.max(64, maxH);
        Graphics2D g = null;
        try {
            BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
            g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setFont(FONT);

            g.setColor(BG);
            g.fillRect(0, 0, W, H);
            drawGrid(g, W, H);

            List<CLBC_Block> src = (blocks == null) ? new ArrayList<>() : blocks;
            List<Node> roots = buildTree(src);

            if (roots.isEmpty()) {
                g.setColor(EMPTY_TEXT);
                FontMetrics fm = g.getFontMetrics();
                String hint = "(空画布)";
                g.drawString(hint, (W - fm.stringWidth(hint)) / 2f, H / 2f + fm.getAscent() / 2f);
            } else {
                FontMetrics fm = g.getFontMetrics();
                TextMeasurer tm = fm::stringWidth;
                for (Node r : roots) { measureNode(r, tm, Float.MAX_VALUE, 0); layoutNode(r, r.b.x, r.b.y, 0); }
                float[] box = new float[]{Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
                for (Node r : roots) collectBounds(r, box, 0);
                float bw = Math.max(1f, box[2] - box[0]);
                float bh = Math.max(1f, box[3] - box[1]);
                float pad = 12f;
                float scale = Math.min((W - 2 * pad) / bw, (H - 2 * pad) / bh);
                scale = Math.max(0.02f, Math.min(1.0f, scale));
                float offX = (W - bw * scale) / 2f - box[0] * scale;
                float offY = (H - bh * scale) / 2f - box[1] * scale;
                for (Node r : roots) drawNode(g, r, offX, offY, scale, 0);
            }

            g.dispose();
            g = null;

            File out = new File(pngPath);
            File parent = out.getAbsoluteFile().getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            boolean ok = ImageIO.write(img, "png", out);
            CLBC_Util.log(String.format("缩略图生成: %s (%dx%d, 积木 %d, 结果 %s)",
                    pngPath, W, H, src.size(), ok ? "成功" : "失败"));
            return ok && out.isFile() && out.length() > 0;
        } catch (Throwable t) {
            CLBC_Util.log("!!!!缩略图生成失败: " + t);
            return false;
        } finally {
            if (g != null) g.dispose();
        }
    }

    // ==========================================================
    // 嵌套树重建（parentId + slotIndex，不触碰原对象 children）
    // ==========================================================
    private static final class Node {
        final CLBC_Block b;
        final List<Node> kids = new ArrayList<>();            // 所有口并集（包围盒用）
        final List<List<Node>> slotKids = new ArrayList<>();  // 按口分派（布局/绘制用）
        Shape shape;
        float x, y, w, h;
        Node(CLBC_Block b) { this.b = b; }
    }

    private static List<Node> buildTree(List<CLBC_Block> src) {
        Map<Integer, Node> idx = new HashMap<>();
        List<Node> all = new ArrayList<>();
        for (CLBC_Block b : src) {
            if (b == null) continue;
            Node n = new Node(b);
            all.add(n);
            idx.put(b.id, n);
        }
        List<Node> roots = new ArrayList<>();
        for (Node n : all) {
            Node p = (n.b.parentId >= 0) ? idx.get(n.b.parentId) : null;
            if (p != null && p != n) {
                int slots = Math.max(1, p.b.slotCount());
                while (p.slotKids.size() < slots) p.slotKids.add(new ArrayList<>());
                int si = Math.max(0, Math.min(slots - 1, n.b.slotIndex));
                p.slotKids.get(si).add(n);
                p.kids.add(n);
            } else roots.add(n);
        }
        return roots;
    }

    // ==========================================================
    // 布局（与 CLBC_BlockShape.layout 同一算法）
    // ==========================================================
    private static void measureNode(Node n, TextMeasurer tm, float maxW, int depth) {
        String kind = kind(n.b);
        boolean container = CLBC_BlockShape.isContainer(kind);
        float innerMax = 0f;
        float[] sh = null;
        int slots = container ? n.b.slotCount() : 0;
        if (container) {
            sh = new float[slots];
            for (int i = 0; i < slots; i++) {
                float acc = 0;
                if (!n.b.collapsed && depth < MAX_DEPTH) {
                    List<Node> group = i < n.slotKids.size() ? n.slotKids.get(i) : new ArrayList<>();
                    // 子块按自然宽度测量（Float.MAX_VALUE），不被父块宽度反压，否则内嵌块溢出
                    for (Node c : group) {
                        measureNode(c, tm, Float.MAX_VALUE, depth + 1);
                        acc += c.h + CHILD_GAP;
                        innerMax = Math.max(innerMax, c.w);
                    }
                }
                sh[i] = Math.max(CLBC_BlockShape.MIN_SLOT_H, acc + 2 * BODY_PAD);
            }
        } else {
            // 非容器块：先以自然宽度测算（含内嵌输入窗口子块），保证 preferredWidth 计入其宽度
            CLBC_BlockShape.layout(n.b, Float.MAX_VALUE, tm, true);
        }
        // 宽度 = 标签/内嵌宽度 与「容纳最宽语句子块所需宽度」取大（与 CLBC_BlockShape.layout 同口径）。
        // 注意：缩略图由扁平 parentId 重建树（slots 子块已被 flatten 清空），故最宽子块宽度取自 n.slotKids，
        // 而非 b.slotChildren，否则容器不会被撑开、嵌套块会溢出右边界。
        float labelW = CLBC_BlockShape.preferredWidth(n.b, kind, tm);
        float w = Math.max(labelW, innerMax + CLBC_BlockShape.ARM_W + CLBC_BlockShape.CHILD_INSET + 16f);
        w = Math.max(CLBC_BlockShape.MIN_W, Math.min(w, maxW));
        // 与 GL 同源：内嵌口被内部报告块撑大时，该行栏高同步增长
        n.shape = CLBC_BlockShape.build(kind, w, sh,
                CLBC_BlockShape.rowHeights(n.b, kind, tm), CLBC_BlockShape.isHat(kind));
        n.w = n.shape.w; n.h = n.shape.h;
    }

    private static void layoutNode(Node n, float x, float y, int depth) {
        n.x = x; n.y = y;
        if (depth >= MAX_DEPTH || n.shape == null) return;
        String kind = kind(n.b);
        if (!CLBC_BlockShape.isContainer(kind) || n.b.collapsed) return;
        for (int i = 0; i < n.shape.slotCount; i++) {
            float[] o = n.shape.childOffset(i);
            float cx = x + o[0], cy = y + o[1];
            List<Node> group = i < n.slotKids.size() ? n.slotKids.get(i) : new ArrayList<>();
            for (Node c : group) {
                layoutNode(c, cx, cy, depth + 1);
                cy += c.h + CHILD_GAP;
            }
        }
    }

    private static void collectBounds(Node n, float[] box, int depth) {
        if (depth >= MAX_DEPTH) return;
        float top = CLBC_BlockShape.isHat(kind(n.b)) ? n.y - HAT_BUMP : n.y;
        box[0] = Math.min(box[0], n.x);
        box[1] = Math.min(box[1], top);
        box[2] = Math.max(box[2], n.x + n.w);
        box[3] = Math.max(box[3], n.y + n.h + CLBC_BlockShape.NOTCH_D);
        for (Node c : n.kids) collectBounds(c, box, depth + 1);
    }

    // ==========================================================
    // 绘制：直接消费 Shape 的扫描条带 + 轮廓（与 GL 画布同源）
    // ==========================================================
    private static void drawNode(Graphics2D g, Node n, float ox, float oy, float s, int depth) {
        if (depth >= MAX_DEPTH || n.shape == null) return;
        Shape sh = n.shape;
        float x = n.x * s + ox, y = n.y * s + oy;
        Color base = awt(n.b.color);
        Color edge = n.b.invalid ? INVALID_RED : darken(base, EDGE_DARK);

        // 1) 逐条带填纯色（与 CLBC_BlockRenderer.fillShape 同源的抗接缝处理）
        // 轮廓 y 在 Path.p() 里被量化到 0.5 逻辑单位，乘非整数 scale 后相邻带的共享边
        // 落在像素中间：Java2D 对各带分别抗锯齿填充、每次只覆盖约 75%，叠加后仍漏出
        // #F9F9F9 背景 → 实测浅线 RGB=F6BB58（主色 F5A623 与背景约 25% 混合）。
        // 修法：共享 y 边界 round 到整数设备像素（相邻带取同一值）精确落在像素边界，
        // 再像 GL 那样下探 0.6px 重叠兜住亚像素残差；x 斜边保留抗锯齿平滑。
        g.setColor(base);
        for (int i = 0; i < sh.bandCount; i++) {
            int k = i * 6;
            float l0 = x + sh.bands[k + 1] * s, r0 = x + sh.bands[k + 2] * s;
            float l1 = x + sh.bands[k + 4] * s, r1 = x + sh.bands[k + 5] * s;
            float y0 = Math.round(y + sh.bands[k] * s);
            float y1 = Math.round(y + sh.bands[k + 3] * s) + 0.6f;
            if (r0 - l0 < 0.05f && r1 - l1 < 0.05f) continue;
            Path2D.Float q = new Path2D.Float();
            q.moveTo(l0, y0); q.lineTo(r0, y0); q.lineTo(r1, y1); q.lineTo(l1, y1); q.closePath();
            g.fill(q);
        }

        // 2) 沿真实轮廓描 1px 微深边（含 C 口内沿）
        g.setColor(edge);
        g.setStroke(new BasicStroke(Math.max(0.6f, s)));
        Path2D.Float outline = new Path2D.Float();
        outline.moveTo(x + sh.ox[0] * s, y + sh.oy[0] * s);
        for (int i = 1; i < sh.n; i++) outline.lineTo(x + sh.ox[i] * s, y + sh.oy[i] * s);
        outline.closePath();
        g.draw(outline);

        // 3) 标签栏片段（普通文字 / 白色数值槽 / 六边形布尔槽）；非容器块同样要画一行
        FontMetrics fm1 = g.getFontMetrics(FONT);
        TextMeasurer tm = fm1::stringWidth;
        if (!CLBC_BlockShape.isContainer(sh.kind)) {
            float barH = sh.barH != null && sh.barH.length > 0 ? sh.barH[0]
                    : (("round".equals(sh.kind) || "hex".equals(sh.kind)) ? CLBC_BlockShape.ROUND_H : CLBC_BlockShape.STACK_H);
            drawSegments(g, CLBC_BlockShape.segments(n.b, 0, tm),
                    x + 12f * s, y + (barH / 2f + 5f) * s, s, base);
        } else {
            for (int i = 0; i < sh.slotCount; i++) {
                float barH = sh.barH != null && i < sh.barH.length ? sh.barH[i]
                        : (i == 0 ? CLBC_BlockShape.TOP_H : CLBC_BlockShape.BAR_H);
                List<Segment> segs = new ArrayList<>(CLBC_BlockShape.segments(n.b, i, tm));
                // 方法定义首栏已含名称输入窗口，不追加名称文字（避免同一行出现两个 run）
                if (CLBC_BlockShape.isHat(sh.kind) && i == 0 && n.b.name != null
                        && !"unit.method".equals(n.b.key)) {
                    String nm = " " + n.b.name;
                    segs.add(new Segment(nm, false, fm1.stringWidth(nm)));
                }
                drawSegments(g, segs, x + 12f * s,
                        y + (sh.barTop[i] + barH / 2f + 5f) * s, s, base);
            }
        }
        if (n.b.collapsed) {
            // 折叠三角改用几何绘制，不依赖字体字形（msyhbd 缺 ▶ → 空方框）
            float tx = x + (sh.w - 26f) * s, ty = y + (CLBC_BlockShape.TOP_H / 2f + 5f) * s;
            g.setColor(TEXT_WHITE);
            g.fillPolygon(new int[]{(int) tx, (int) (tx + 8f * s), (int) tx},
                    new int[]{(int) (ty - 5f * s), (int) ty, (int) (ty + 5f * s)}, 3);
        }
        if (n.b.invalid) drawText(g, "!", x + (sh.w - 16f) * s, y + 16f * s, s);

        // 4) 递归各口子块
        if (CLBC_BlockShape.isContainer(sh.kind) && !n.b.collapsed) {
            for (int i = 0; i < sh.slotCount; i++) {
                List<Node> group = i < n.slotKids.size() ? n.slotKids.get(i) : new ArrayList<>();
                for (Node c : group) drawNode(g, c, ox, oy, s, depth + 1);
            }
        }
    }

    /** 与 GL 画布同源的片段绘制：普通白字 / 白色胶囊数值槽 / 六边形布尔条件槽 */
    private static void drawSegments(Graphics2D g, List<Segment> segs, float x, float baseline,
                                    float s, Color base) {
        if (segs == null) return;
        float cursor = x;
        for (Segment seg : segs) {
            float w = seg.width * s;
            float sh = Math.max(6f, seg.height * s);
            // 内嵌窗口在标签栏内「垂直居中」：baseline = 栏顶 + 栏高/2 + 5，
            // 故 sy = 栏顶 + (栏高 - sh)/2 = baseline - 5 - sh/2。
            // 旧公式 baseline - sh + 5 是给空胶囊写单行字用的基线对齐，一旦窗口里换成
            // 真实报告块（高 ROUND_H=32 > 标签栏需留白），会把整块上移并顶出栏顶。
            float sy = baseline - 5f * s - sh / 2f;
            if (seg.arg) {
                CLBC_Block child = seg.input == null ? null : seg.input.child;
                if (child != null) {
                    // 窗口真正承载一块报告积木：直接递归画真实的 oval/hexagon 块（含它自己的嵌套），
                    // 不画白底占位，避免白框套白框伪影（与 GL 画布一致）。
                    drawBlockInline(g, child, cursor, sy, sh, s);
                } else if (seg.dropdown) {
                    // 枚举下拉字段：深色实心圆角块 + 白字 + ▾ 三角（与 GL 画布同源）
                    g.setColor(darken(base, 0.28f));
                    int arc = Math.max(1, Math.round(Math.min(6f, sh / 2f)));
                    g.fillRoundRect(Math.round(cursor), Math.round(sy),
                            Math.max(1, Math.round(w)), Math.max(1, Math.round(sh)), arc, arc);
                    float cx = cursor + w - 10f * s, cy = sy + sh / 2f - 2f * s, half = 4f * s;
                    int[] tx = {Math.round(cx - half), Math.round(cx + half), Math.round(cx)};
                    int[] ty = {Math.round(cy), Math.round(cy), Math.round(cy + 5f * s)};
                    g.setColor(TEXT_WHITE);
                    g.fillPolygon(tx, ty, 3);
                    String t = CLBC_BlockShape.dropdownDisplay(
                            seg.input == null ? seg.text : seg.input.code());
                    drawTextColored(g, t, cursor + CLBC_BlockShape.SLOT_PAD * s, baseline, s, TEXT_WHITE);
                } else {
                    // 空窗口：白色胶囊 / 六边形占位 + 文本
                    float tip = CLBC_BlockShape.hexTip(sh);   // sh 已是设备像素高，六角恒 120°
                    if (seg.hex) {
                        int[] ex = {Math.round(cursor + tip), Math.round(cursor + w - tip),
                                Math.round(cursor + w + 1), Math.round(cursor + w - tip),
                                Math.round(cursor + tip), Math.round(cursor - 1)};
                        int[] ey = {Math.round(sy - 1), Math.round(sy - 1), Math.round(sy + sh / 2f),
                                Math.round(sy + sh + 1), Math.round(sy + sh + 1), Math.round(sy + sh / 2f)};
                        g.setColor(SLOT_EDGE); g.fillPolygon(ex, ey, 6);
                        int[] hx = {Math.round(cursor + tip), Math.round(cursor + w - tip),
                                Math.round(cursor + w), Math.round(cursor + w - tip),
                                Math.round(cursor + tip), Math.round(cursor)};
                        int[] hy = {Math.round(sy), Math.round(sy), Math.round(sy + sh / 2f),
                                Math.round(sy + sh), Math.round(sy + sh), Math.round(sy + sh / 2f)};
                        g.setColor(TEXT_WHITE); g.fillPolygon(hx, hy, 6);
                    } else {
                        int arc = Math.max(1, Math.round(sh / 2f));
                        g.setColor(SLOT_EDGE);
                        g.fillRoundRect(Math.round(cursor - 1), Math.round(sy - 1),
                                Math.max(1, Math.round(w + 2)), Math.max(1, Math.round(sh + 2)),
                                arc + 1, arc + 1);
                        g.setColor(TEXT_WHITE);
                        g.fillRoundRect(Math.round(cursor), Math.round(sy),
                                Math.max(1, Math.round(w)), Math.max(1, Math.round(sh)), arc, arc);
                    }
                    String t = seg.input == null ? seg.text : seg.input.code();
                    float tx = cursor + (seg.hex
                            ? CLBC_BlockShape.hexTip(sh) + 4f * s
                            : CLBC_BlockShape.SLOT_PAD * s);
                    drawTextColored(g, t, tx, baseline, s, TEXT_DARK);
                }
            } else if (!seg.text.isEmpty()) {
                drawTextColored(g, seg.text, cursor, baseline, s, TEXT_WHITE);
            }
            cursor += w + CLBC_BlockShape.SEG_GAP * s;
        }
    }

    /**
     * 把内嵌窗口承载的报告块画成真实形状（与 drawNode 同源：纯色填充 + 1px 微深描边）。
     * 用于缩略图中嵌套的 oval / hexagon 块——直接递归画其子块（含更深的嵌套），
     * 而不是画一个同色占位胶囊（那样会丢失真实形状与颜色）。
     */
    private static void drawBlockInline(Graphics2D g, CLBC_Block b, float x, float y, float sh, float s) {
        if (b == null) return;
        FontMetrics fm = g.getFontMetrics(FONT);
        TextMeasurer tm = fm::stringWidth;
        CLBC_BlockShape.layout(b, Float.MAX_VALUE, tm, true);
        Shape shp = b.shape;
        if (shp == null) return;
        float bx = x + 3f * s;                               // 与 GL 画布 cursor+3 对齐（x 已缩放）
        float by = y + (sh - shp.h * s) / 2f;               // 在父窗口内垂直居中（sh 已缩放，子块高也须缩放）
        Color base = awt(b.color);
        Color edge = b.invalid ? INVALID_RED : darken(base, EDGE_DARK);

        // 1) 逐条带填纯色：设备像素对齐 + 0.6px 重叠（同 drawNode / GL fillShape，消浅色接缝）
        g.setColor(base);
        for (int i = 0; i < shp.bandCount; i++) {
            int k = i * 6;
            float l0 = bx + shp.bands[k + 1] * s, r0 = bx + shp.bands[k + 2] * s;
            float l1 = bx + shp.bands[k + 4] * s, r1 = bx + shp.bands[k + 5] * s;
            float y0 = Math.round(by + shp.bands[k] * s);
            float y1 = Math.round(by + shp.bands[k + 3] * s) + 0.6f;
            if (r0 - l0 < 0.05f && r1 - l1 < 0.05f) continue;
            Path2D.Float q = new Path2D.Float();
            q.moveTo(l0, y0); q.lineTo(r0, y0); q.lineTo(r1, y1); q.lineTo(l1, y1); q.closePath();
            g.fill(q);
        }
        // 2) 沿真实轮廓描 1px 微深边
        g.setColor(edge);
        g.setStroke(new BasicStroke(Math.max(0.6f, s)));
        Path2D.Float outline = new Path2D.Float();
        outline.moveTo(bx + shp.ox[0] * s, by + shp.oy[0] * s);
        for (int i = 1; i < shp.n; i++) outline.lineTo(bx + shp.ox[i] * s, by + shp.oy[i] * s);
        outline.closePath();
        g.draw(outline);
        // 3) 报告块标签栏片段（文字 + 自身嵌套窗口）；嵌套块均为 reporter，不会是容器
        if (!CLBC_BlockShape.isContainer(shp.kind)) {
            float barH = shp.barH != null && shp.barH.length > 0 ? shp.barH[0]
                    : (("round".equals(shp.kind) || "hex".equals(shp.kind))
                        ? CLBC_BlockShape.ROUND_H : CLBC_BlockShape.STACK_H);
            drawSegments(g, CLBC_BlockShape.segments(b, 0, tm),
                    bx + 12f * s, by + (barH / 2f + 5f) * s, s, base);
        }
    }

    /** 0xAARRGGBB → java.awt.Color */
    private static Color awtColor(long c) {
        return new Color((int) ((c >> 16) & 0xFF), (int) ((c >> 8) & 0xFF), (int) (c & 0xFF));
    }

    private static void drawTextColored(Graphics2D g, String t, float x, float y, float s, Color c) {
        if (t == null || t.isEmpty()) return;
        g.setFont(FONT.deriveFont(Math.max(7f, 13f * s)));
        g.setColor(c);
        g.drawString(clip(t), x, y);
    }

    private static void drawText(Graphics2D g, String t, float x, float y, float s) {
        drawTextColored(g, t, x, y, s, TEXT_WHITE);
    }

    /** 超长文本截断，避免缩略图里文字溢出成黑块 */
    private static String clip(String t) { return t.length() > 48 ? t.substring(0, 45) + "..." : t; }

    private static void drawGrid(Graphics2D g, int W, int H) {
        g.setColor(GRID);
        for (int gy = 12; gy < H; gy += 24)
            for (int gx = 12; gx < W; gx += 24)
                g.fillRect(gx, gy, 1, 1);
    }

    // ==========================================================
    // 小工具
    // ==========================================================
    private static String kind(CLBC_Block b) { return b.kind == null ? "stack" : b.kind; }

    /** 0xAARRGGBB → java.awt.Color（alpha=0 视为不透明，避免全透明不可见） */
    private static Color awt(long c) {
        int a = (int) ((c >> 24) & 0xFF);
        int r = (int) ((c >> 16) & 0xFF);
        int gg = (int) ((c >> 8) & 0xFF);
        int b = (int) (c & 0xFF);
        if (a == 0) a = 0xFF;
        return new Color(r, gg, b, a);
    }

    private static Color darken(Color c, float amt) {
        return new Color((int) (c.getRed() * (1 - amt)), (int) (c.getGreen() * (1 - amt)),
                (int) (c.getBlue() * (1 - amt)), c.getAlpha());
    }
}
