package clbc.gl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import clbc.util.CLBC_Util;

/**
 * 即时模式（IMGUI）控件层 —— 属"内部渲染方法"，供各 UI 屏幕调用。
 *
 * 设计要点：
 *  · 无保留节点树：每帧重画，控件返回"本帧是否发生某事件"（如按钮 clicked、下拉 selected）。
 *  · 输入来自 {@link CLBC_GL_API} 的全局状态（鼠标 / 键盘 / 滚轮 / 中文码点队列）。
 *  · 控件自身状态（光标位置、下拉展开、列表滚动、右键菜单）以 id 为键存于本类的静态 Map。
 *  · 公用的底层绘制（fillRect / drawText / drawImage …）一律走 {@link CLBC_GL_API}，本类只做控件编排与命中测试。
 *
 * 命名全部自起，避免与 JavaFX 控件同名冲突。
 */
public final class CLBC_GL_Widgets {

    private CLBC_GL_Widgets() {}

    // 控件状态
    private static final Map<Integer, Integer> caretPos = new HashMap<>();     // 文本框光标位置
    private static final Map<Integer, Float> textScrollX = new HashMap<>();    // 文本框水平滚动偏移（Shift+滚轮 / 光标跟随）
    private static final Map<Integer, Boolean> comboOpen = new HashMap<>();    // 下拉是否展开
    private static final Map<Integer, Float> listScroll = new HashMap<>();     // 列表滚动偏移
    // ===== 滑块拖动状态机（T15 按住圆点才拖 + T3 松手才提交）=====
    private static final Map<Integer, Boolean> sliderDragging = new HashMap<>();   // 该滑块本帧是否在拖动
    private static final Map<Integer, Double> sliderDraft = new HashMap<>();       // 拖动中的草稿值
    private static final java.util.Set<Integer> sliderReleased = new java.util.HashSet<>(); // 本帧刚松手的滑块 id
    private static final Map<Integer, float[]> sliderKnob = new HashMap<>();        // 上一帧圆点屏幕位置 [x,y]，用于命中
    private static Popup activePopup = null;                                  // 当前激活的右键菜单
    /** 展开中的下拉面板：延迟到帧末统一绘制，避免被其后绘制的控件文字盖住（截图实证）。 */
    private static final java.util.List<ComboDraw> pendingCombos = new java.util.ArrayList<>();
    private record ComboDraw(int id, float x, float y, float w, float h, String[] options, int selected,
                             long bg, long fg, long panelBg, long panelFg) {}

    private static final int ROW_H = 22;     // 列表 / 下拉行高
    private static final int PAD = 8;        // 文本框左右留白

    // 取 UI 字体（缺省回退为 API 注入字体）
    private static CLBC_GL_Font font() { return CLBC_GL_API.uiFont; }

    /** 判断点 (mx,my) 是否落在矩形内 */
    public static boolean hit(float x, float y, float w, float h) {
        // 画布缩放绘制期间（zoomActive）用逻辑化鼠标坐标，保证浮层命中与被缩放的绘制位置一致；
        // 其余屏幕（工具箱 / 代码面板 / 设置 / 顶栏）zoomActive 恒为 false，等价于原来的物理 mouseX/mouseY。
        float mx = CLBC_GL_API.mouseLogicalX(), my = CLBC_GL_API.mouseLogicalY();
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    // ==========================================================
    // 按钮（对应 JavaFX Button）
    // ==========================================================
    public static boolean button(int id, float x, float y, float w, float h, String label, long bg, long fg) {
        boolean hovered = hit(x, y, w, h);
        boolean clicked = hovered && CLBC_GL_API.mouseJustPressed;
        if (clicked) {
            CLBC_GL_API.focusedTextField = -1;   // 👈 点按钮时清空文本焦点，避免误输入
            CLBC_Util.logFine("控件点击: button#" + id + " " + label);
        }
        // 悬停时轻微提亮
        long drawBg = hovered ? lighten(bg, 0.12f) : bg;
        CLBC_GL_API.fillRoundRect(drawBg, x, y, w, h, Math.min(8, h / 2));
        CLBC_GL_API.drawTextCentered(font(), label, x, y, w, h, fg);
        return clicked;
    }

    // ==========================================================
    // 文本框（对应 JavaFX TextField / TextArea 单行）
    // 返回"修改后的文本"——即时模式天然每帧重绘，满足"修改后重绘"需求。
    // ==========================================================
    public static String textField(int id, float x, float y, float w, float h, String text,
                                   long bg, long fg, long caret) {
        boolean inside = hit(x, y, w, h);
        float scrollX = textScrollX.getOrDefault(id, 0f);
        if (CLBC_GL_API.mouseJustPressed && inside) {
            CLBC_GL_API.focusedTextField = id;
            // 👈 点击位置估算光标：按字符宽度累加，找最近处（考虑水平滚动偏移）
            caretPos.put(id, caretFromX(id, text, x, w, CLBC_GL_API.mouseLogicalX(), scrollX));
        }
        // Shift+滚轮：水平滚动文本框（滚轮向上为正 → 文本右移即 scrollX 减小，符合直觉）
        if (inside && CLBC_GL_API.shiftDown && CLBC_GL_API.scrollDelta != 0) {
            float maxScroll = Math.max(0f, CLBC_GL_API.measureText(font(), text == null ? "" : text) - (w - PAD * 2));
            scrollX -= CLBC_GL_API.scrollDelta * 40f;
            scrollX = Math.max(0f, Math.min(maxScroll, scrollX));
            textScrollX.put(id, scrollX);
        }

        // 背景
        CLBC_GL_API.fillRect(bg, x, y, w, h);
        // 焦点边框
        if (CLBC_GL_API.focusedTextField == id) {
            CLBC_GL_API.strokeRect(caret, x, y, w, h, 2);
        }

        // 处理键盘输入（仅焦点在此框时）
        String result = text;
        if (CLBC_GL_API.focusedTextField == id) {
            int cp = caretPos.getOrDefault(id, text.length());
            StringBuilder sb = new StringBuilder(text);

            for (int c : CLBC_GL_API.typedChars) {
                char[] ch = Character.toChars(c); // 支持 >0xFFFF 的码点（emoji 等）；(char)c 会截断代理对
                sb.insert(cp, ch);
                cp += ch.length;
            }
            if (CLBC_GL_API.keyBackspace && cp > 0) { sb.deleteCharAt(cp - 1); cp--; }
            if (CLBC_GL_API.keyDelete && cp < sb.length()) { sb.deleteCharAt(cp); }
            if (CLBC_GL_API.keyLeft) cp = Math.max(0, cp - 1);
            if (CLBC_GL_API.keyRight) cp = Math.min(sb.length(), cp + 1);
            if (CLBC_GL_API.keyHome) cp = 0;
            if (CLBC_GL_API.keyEnd) cp = sb.length();

            caretPos.put(id, cp);
            result = sb.toString();
            // 光标跟随：光标像素位置超出可视区时主动滚动，让光标回到框内（正负号不能反，否则死循环）。
            // 光标像素 = measure(前缀)；可视区 = [scrollX, scrollX + (w - 2PAD)]。
            float caretPx = CLBC_GL_API.measureText(font(), result.substring(0, Math.min(cp, result.length())));
            float viewW = w - PAD * 2;
            float maxScroll = Math.max(0f, CLBC_GL_API.measureText(font(), result) - viewW);
            if (caretPx < scrollX) {
                scrollX = Math.max(0f, caretPx);                       // 光标跑到左边 → 向左滚
            } else if (caretPx > scrollX + viewW) {
                scrollX = Math.min(maxScroll, caretPx - viewW);        // 光标跑到右边 → 向右滚
            }
            textScrollX.put(id, scrollX);
        }

        // 绘制文本（裁掉超出宽度的部分）
        // #163：裁剪栈必须 try/finally 配对。控件里任何异常都会让这一层 scissor 残留到本帧后续绘制，
        // 表现为「注释框的线 / 后半帧的积木边框随机不显示」。
        CLBC_GL_API.setClipLogical(x, y, w, h);
        try {
            float textX = x + PAD - scrollX;   // 水平滚动：文本整体左移 scrollX
            float textY = y + h / 2f + font().getPixelHeight() * 0.35f;
            CLBC_GL_API.drawText(font(), result, textX, textY, fg);
            // 光标
            if (CLBC_GL_API.focusedTextField == id) {
                int cp = caretPos.getOrDefault(id, result.length());
                float cx = textX + CLBC_GL_API.measureText(font(), result.substring(0, Math.min(cp, result.length())));
                CLBC_GL_API.drawLine(cx, y + 4, cx, y + h - 4, caret, 1.5f);
            }
        } finally {
            CLBC_GL_API.clearClip();
        }

        return result;
    }

    /**
     * 自扩展长度文本框（对应原 createInputField：宽度随文字增长）。
     * 返回修改后的文本；实际绘制宽度 = max(minW, 文字宽 + 留白)。
     */
    public static String textFieldAuto(int id, float x, float y, float minW, float h, String text,
                                       long bg, long fg, long caret) {
        float w = inputWidth(text, minW);
        return textField(id, x, y, w, h, text, bg, fg, caret);
    }

    /** 计算自扩展输入框宽度（与 textFieldAuto 同步，供积木布局复用） */
    public static float inputWidth(String text, float minW) {
        float tw = CLBC_GL_API.measureText(font(), text == null ? "" : text);
        return Math.max(minW, tw + PAD * 2);
    }

    /** 根据点击 x 估算光标位置（scrollX = 当前水平滚动偏移） */
    private static int caretFromX(int id, String text, float x, float w, float mouseX, float scrollX) {
        if (text == null || text.isEmpty()) return 0;
        float tx = x + PAD - scrollX;
        for (int i = 0; i < text.length(); i++) {
            float cw = CLBC_GL_API.measureText(font(), text.substring(0, i + 1));
            if (tx + cw >= mouseX) return i;
        }
        return text.length();
    }

    // ==========================================================
    // #162 软换行多行文本框（注释便利贴专用）
    // 超出框宽 → 折行继续打印，不再一刀裁掉；折行只是「显示」，不往字符串里塞 '\n'，
    // 因此 BlocksToCode 导出回源码时仍是单行 // 注释。用户按 Enter 才是真换行（导出时拆成多行 //）。
    // ==========================================================

    /** 折行结果：每行文本 + 该行首字符在原串中的下标（供光标定位） */
    public static final class Wrapped {
        public final String[] lines;
        public final int[] starts;
        Wrapped(String[] l, int[] s) { lines = l; starts = s; }
        public int count() { return lines.length; }
        /** 光标位置 → [行号, 行内偏移] */
        public int[] caretLineCol(int cp) {
            for (int i = lines.length - 1; i >= 0; i--)
                if (cp >= starts[i]) return new int[]{i, Math.min(lines[i].length(), cp - starts[i])};
            return new int[]{0, 0};
        }
    }

    /** 按可用宽度折行：CJK 逐字可断、ASCII 优先在空格处断，超长单词强制逐字断。 */
    public static Wrapped wrapText(String text, float maxW) {
        if (text == null) text = "";
        if (maxW < 12f) maxW = 12f;
        ArrayList<String> lines = new ArrayList<>();
        ArrayList<Integer> starts = new ArrayList<>();
        int n = text.length(), p = 0;
        while (p < n) {
            int eol = text.indexOf('\n', p);
            if (eol < 0) eol = n;
            int seg = p;
            while (seg < eol) {
                float w = 0f;
                int j = seg, lastBreak = -1;
                while (j < eol) {
                    int cp = text.codePointAt(j);
                    int cc = Character.charCount(cp);
                    float cw = CLBC_GL_API.measureText(font(), text.substring(j, j + cc));
                    if (w + cw > maxW && j > seg) break;
                    w += cw; j += cc;
                    if (Character.isWhitespace(cp) || cp >= 0x2E80) lastBreak = j;
                }
                int cut;
                if (j >= eol) cut = eol;
                else if (lastBreak > seg && (lastBreak - seg) * 2 >= (j - seg)) cut = lastBreak;
                else cut = j;
                if (cut <= seg) cut = Math.min(eol, seg + 1);
                lines.add(text.substring(seg, cut));
                starts.add(seg);
                seg = cut;
                while (seg < eol && text.charAt(seg) == ' ') seg++;   // 行首空格不顶格
            }
            if (seg == p) { lines.add(""); starts.add(p); }            // 空行
            if (eol >= n) break;
            p = eol + 1;
            if (p >= n) { lines.add(""); starts.add(n); break; }        // 末尾换行
        }
        if (lines.isEmpty()) { lines.add(""); starts.add(0); }
        String[] L = lines.toArray(new String[0]);
        int[] S = new int[starts.size()];
        for (int i = 0; i < S.length; i++) S[i] = starts.get(i);
        return new Wrapped(L, S);
    }

    /** 多行点击 → 光标字符下标（按折行结果定位行列，再按字符宽度累加） */
    private static int caretFromXY(String text, float x, float y, float w, float lh, float mouseX, float mouseY) {
        Wrapped wp = wrapText(text, w - 2f * PAD);
        int line = (int) Math.floor((mouseY - y - PAD * 0.5f) / lh);
        line = Math.max(0, Math.min(wp.count() - 1, line));
        String ln = wp.lines[line];
        int col = 0;
        for (int i = 0; i < ln.length();) {
            int cc = Character.charCount(ln.codePointAt(i));
            if (x + PAD + CLBC_GL_API.measureText(font(), ln.substring(0, i + cc)) > mouseX) break;
            col = i + cc; i += cc;
        }
        return wp.starts[line] + col;
    }

    /** 行高（与 textArea 的绘制步长一致，供调用方按行数加高容器） */
    public static float wrappedLineHeight() { return font().getPixelHeight() + 4f; }

    /** 返回所需的显示行数（含硬换行） */
    public static int wrappedLineCount(String text, float w) {
        return wrapText(text, w - 2f * PAD).count();
    }

    /** 多行软换行文本框：编辑逻辑与 textField 一致，绘制按行折返、逐行打印。 */
    public static String textArea(int id, float x, float y, float w, float h, String text,
                                  long bg, long fg, long caret) {
        if (text == null) text = "";
        float lh = wrappedLineHeight();
        boolean inside = hit(x, y, w, h);
        if (CLBC_GL_API.mouseJustPressed && inside) {
            CLBC_GL_API.focusedTextField = id;
            caretPos.put(id, caretFromXY(text, x, y, w, lh,
                    CLBC_GL_API.mouseLogicalX(), CLBC_GL_API.mouseLogicalY()));
        }
        CLBC_GL_API.fillRect(bg, x, y, w, h);
        if (CLBC_GL_API.focusedTextField == id)
            CLBC_GL_API.strokeRect(caret, x, y, w, h, 2);

        String result = text;
        if (CLBC_GL_API.focusedTextField == id) {
            int cp = Math.max(0, Math.min(caretPos.getOrDefault(id, text.length()), text.length()));
            StringBuilder sb = new StringBuilder(text);
            for (int c : CLBC_GL_API.typedChars) {
                char[] ch = Character.toChars(c);
                sb.insert(cp, ch); cp += ch.length;
            }
            if (CLBC_GL_API.keyBackspace && cp > 0) { sb.deleteCharAt(--cp); }
            if (CLBC_GL_API.keyDelete && cp < sb.length()) sb.deleteCharAt(cp);
            if (CLBC_GL_API.keyLeft) cp = Math.max(0, cp - 1);
            if (CLBC_GL_API.keyRight) cp = Math.min(sb.length(), cp + 1);
            if (CLBC_GL_API.keyHome) cp = 0;
            if (CLBC_GL_API.keyEnd) cp = sb.length();
            if (CLBC_GL_API.keyUp || CLBC_GL_API.keyDown) {
                Wrapped mid = wrapText(sb.toString(), w - 2f * PAD);
                int[] lc = mid.caretLineCol(cp);
                int nl = Math.max(0, Math.min(mid.count() - 1, lc[0] + (CLBC_GL_API.keyDown ? 1 : -1)));
                cp = mid.starts[nl] + Math.min(mid.lines[nl].length(), lc[1]);
            }
            if (CLBC_GL_API.keyEnter) { sb.insert(cp, '\n'); cp++; }
            cp = Math.max(0, Math.min(sb.length(), cp));
            caretPos.put(id, cp);
            result = sb.toString();
        }

        Wrapped wp = wrapText(result, w - 2f * PAD);
        // #163：同 textField，裁剪栈 try/finally 配对，杜绝 scissor 残留吞掉后续描边。
        CLBC_GL_API.setClipLogical(x, y, w, h);
        try {
            for (int i = 0; i < wp.count(); i++) {
                float ly = y + PAD * 0.5f + i * lh + font().getPixelHeight() * 0.85f;
                if (ly - font().getPixelHeight() > y + h) break;
                CLBC_GL_API.drawText(font(), wp.lines[i], x + PAD, ly, fg);
            }
            if (CLBC_GL_API.focusedTextField == id) {
                int[] lc = wp.caretLineCol(caretPos.getOrDefault(id, result.length()));
                String ln = wp.lines[lc[0]];
                float cx = x + PAD + CLBC_GL_API.measureText(font(),
                        ln.substring(0, Math.max(0, Math.min(lc[1], ln.length()))));
                float cy = y + PAD * 0.5f + lc[0] * lh;
                CLBC_GL_API.drawLine(cx, cy, cx, cy + lh - 2f, caret, 1.5f);
            }
        } finally {
            CLBC_GL_API.clearClip();
        }
        return result;
    }

    // ==========================================================
    // 下拉框（对应 JavaFX ComboBox）
    // ==========================================================
    public static int comboBox(int id, float x, float y, float w, float h, String[] options, int selected,
                               long bg, long fg, long panelBg, long panelFg) {
        boolean open = comboOpen.getOrDefault(id, false);
        // 主按钮
        CLBC_GL_API.fillRoundRect(bg, x, y, w, h, Math.min(5, h / 2));
        String cur = (selected >= 0 && selected < options.length) ? options[selected] : "";
        // 【实测修复·图层覆盖】旧代码直接 drawText(cur) 无裁剪：长路径（F:\java项目\...）
        // 远超框宽，文字向左/右溢出盖住相邻的「返回」按钮和别的下拉（截图红框实证）。
        // 给主按钮文字套裁剪框（右侧留出箭头空间），溢出只在本控件内消失，绝不越界压别人。
        float textW = Math.max(1f, w - PAD - 16f);   // 16 = 右侧箭头区
        CLBC_GL_API.setClipLogical(x + 2f, y, textW, h);
        try {
            CLBC_GL_API.drawText(font(), cur, x + PAD, y + h / 2f + font().getPixelHeight() * 0.35f, fg);
        } finally {
            CLBC_GL_API.clearClip();
        }
        // 右侧箭头：改用几何三角形绘制，不依赖字体（msyhbd 缺 ▾ 字形 → 空方框 tofu）
        float ax = x + w - PAD - 10, ay = y + h / 2f - 2f;
        CLBC_GL_API.drawPolygon(true, fg,
                new float[]{ax - 4f, ax + 4f, ax},
                new float[]{ay, ay, ay + 5f}, 3);

        float px = x, py = y + h + 2, pw = w, ph = options.length * ROW_H;

        boolean clicked = hit(x, y, w, h) && CLBC_GL_API.mouseJustPressed;
        if (clicked) {
            CLBC_GL_API.focusedTextField = -1;
            open = !open;
            comboOpen.put(id, open);   // 👈 点击切换展开
            CLBC_GL_API.mouseJustPressed = false;   // 吞掉本次点击，避免同帧后面的控件也响应
            if (!open) return selected;
        }

        if (!open) return selected;

        // 先处理选项点击（命中判定用真实矩形，绘制交给帧末 drawComboPopups）
        for (int i = 0; i < options.length; i++) {
            float ry = py + i * ROW_H;
            if (hit(px, ry, pw, ROW_H) && CLBC_GL_API.mouseJustPressed) {
                comboOpen.put(id, false);
                CLBC_GL_API.mouseJustPressed = false;
                CLBC_Util.logFine("下拉选择: combo#" + id + " -> " + options[i]);
                return i;
            }
        }
        // 点击面板外关闭（不吞事件：点的是别的控件，让那个控件照常响应）
        if (CLBC_GL_API.mouseJustPressed && !hit(px, py, pw, ph)) comboOpen.put(id, false);
        else pendingCombos.add(new ComboDraw(id, x, y, w, h, options, selected, bg, fg, panelBg, panelFg));
        return selected;
    }

    /** 帧末统一绘制所有展开的下拉面板：保证盖在同帧后续控件之上，不再出现"下面那串字透出来"。 */
    public static void drawComboPopups() {
        if (pendingCombos.isEmpty()) return;
        for (ComboDraw d : pendingCombos) {
            if (!comboOpen.getOrDefault(d.id(), false)) continue;
            float px = d.x(), py = d.y() + d.h() + 2, pw = d.w(), ph = d.options().length * ROW_H;
            CLBC_GL_API.fillRect(d.panelBg(), px, py, pw, ph);
            CLBC_GL_API.strokeRect(lighten(d.panelBg(), 0.2f), px, py, pw, ph, 1);
            CLBC_GL_API.setClipLogical(px, py, pw, ph);
            for (int i = 0; i < d.options().length; i++) {
                float ry = py + i * ROW_H;
                if (hit(px, ry, pw, ROW_H)) CLBC_GL_API.fillRect(lighten(d.panelBg(), 0.15f), px, ry, pw, ROW_H);
                CLBC_GL_API.drawText(font(), d.options()[i], px + PAD,
                        ry + ROW_H / 2f + font().getPixelHeight() * 0.35f, d.panelFg());
            }
            CLBC_GL_API.clearClip();
        }
        pendingCombos.clear();
    }

    // ==========================================================
    // 列表（对应 JavaFX ListView）—— 支持滚轮滚动、点击选择
    // ==========================================================
    public static int list(int id, float x, float y, float w, float h, String[] items, int selected,
                           long bg, long fg, long selBg, long selFg) {
        CLBC_GL_API.fillRect(bg, x, y, w, h);
        CLBC_GL_API.strokeRect(lighten(bg, 0.2f), x, y, w, h, 1);

        float scroll = listScroll.getOrDefault(id, 0f);
        if (hit(x, y, w, h)) {
            scroll -= CLBC_GL_API.scrollDelta * ROW_H;     // 👈 滚轮滚动（向上为正）
            scroll = Math.max(0, Math.min(scroll, Math.max(0, items.length * ROW_H - h)));
            listScroll.put(id, scroll);
        }

        CLBC_GL_API.setClipLogical(x, y, w, h);
        for (int i = 0; i < items.length; i++) {
            float ry = y - scroll + i * ROW_H;
            if (ry + ROW_H < y || ry > y + h) continue;
            boolean rh = hit(x, ry, w, ROW_H);
            if (i == selected || rh) {
                CLBC_GL_API.fillRect(i == selected ? selBg : lighten(bg, 0.12f), x, ry, w, ROW_H);
            }
            CLBC_GL_API.drawText(font(), items[i], x + PAD, ry + ROW_H / 2f + font().getPixelHeight() * 0.35f,
                    i == selected ? selFg : fg);
            if (rh && CLBC_GL_API.mouseJustPressed) {
                CLBC_GL_API.focusedTextField = -1;
                CLBC_Util.logFine("列表选择: list#" + id + " -> " + items[i]);
                return i;
            }
        }
        CLBC_GL_API.clearClip();
        return selected;
    }

    /** 列表双击检测（对应 JavaFX setOnMouseClicked 双击打开项目） */
    public static boolean listDoubleClicked(int id, float x, float y, float w, float h, String[] items, int selected) {
        if (selected < 0 || selected >= items.length) return false;
        float scroll = listScroll.getOrDefault(id, 0f);
        float ry = y - scroll + selected * ROW_H;
        return hit(x, ry, w, ROW_H) && CLBC_GL_API.mouseJustPressed;
    }

    // ==========================================================
    // 勾选框（对应 JavaFX CheckBox）
    // ==========================================================
    public static boolean checkBox(int id, float x, float y, String label, boolean checked, long fg, long boxBg) {
        float box = 18;
        CLBC_GL_API.fillRect(boxBg, x, y, box, box);
        CLBC_GL_API.strokeRect(fg, x, y, box, box, 1.5f);
        if (checked) {
            CLBC_GL_API.drawLine(x + 4, y + 9, x + 8, y + 14, fg, 2.5f);
            CLBC_GL_API.drawLine(x + 8, y + 14, x + 15, y + 4, fg, 2.5f);
        }
        CLBC_GL_API.drawText(font(), label, x + box + 8, y + box / 2f + font().getPixelHeight() * 0.35f, fg);
        boolean clicked = hit(x, y, box + 8 + CLBC_GL_API.measureText(font(), label), box + 4) && CLBC_GL_API.mouseJustPressed;
        if (clicked) { CLBC_GL_API.focusedTextField = -1; CLBC_Util.logFine("勾选: check#" + id + " -> " + !checked); }
        return clicked ? !checked : checked;
    }

    // ==========================================================
    // 滑块（对应 JavaFX Slider）
    // ==========================================================
    public static double slider(int id, float x, float y, float w, float h, double min, double max, double val,
                                long track, long knob, long fg, Function<Double, String> fmt) {
        float ky = y + h / 2f;
        CLBC_GL_API.drawLine(x, ky, x + w, ky, track, 4f);

        boolean dragging = sliderDragging.getOrDefault(id, false);
        float mx = CLBC_GL_API.mouseLogicalX(), my = CLBC_GL_API.mouseLogicalY();

        // 上一帧圆点位置（首帧用 val 推算）：命中判定必须基于圆点，不能基于整条轨道。
        float[] lastKnob = sliderKnob.get(id);
        float kx0, ky0;
        if (lastKnob != null) { kx0 = lastKnob[0]; ky0 = lastKnob[1]; }
        else {
            double t0 = Math.max(0, Math.min(1, (val - min) / (max - min)));
            kx0 = x + (float) t0 * w; ky0 = ky;
        }

        if (dragging) {
            // 拖动中：草稿值跟随鼠标 X；松手（mouseJustReleased）才结束并标记 released。
            float nt = (mx - x) / w;
            nt = Math.max(0, Math.min(1, nt));
            sliderDraft.put(id, min + nt * (max - min));
            CLBC_GL_API.focusedTextField = -1;
            if (CLBC_GL_API.mouseJustReleased) {
                dragging = false;
                sliderDragging.put(id, false);
                sliderReleased.add(id);          // 通知调用方：本帧提交草稿
            }
        } else {
            // 【T15】只有「按下边沿」且命中圆点（半径 9 + 5 容差）才开始拖动。
            // 旧实现 hit(x-10, y, w+20, h+10)&&mouseDown 让鼠标落在轨道任意处长按就拖——误触根源。
            boolean onKnob = Math.abs(mx - kx0) <= 14f && Math.abs(my - ky0) <= 14f;
            if (CLBC_GL_API.mouseJustPressed && onKnob) {
                dragging = true;
                sliderDragging.put(id, true);
                sliderDraft.put(id, val);
                CLBC_GL_API.focusedTextField = -1;
            }
        }

        // 绘制用值：拖动期间用草稿（圆点跟手），否则用调用方传入的（已提交的）值。
        double shown = dragging ? sliderDraft.getOrDefault(id, val) : val;
        double t = Math.max(0, Math.min(1, (shown - min) / (max - min)));
        float kx = x + (float) t * w;
        sliderKnob.put(id, new float[]{kx, ky});
        CLBC_GL_API.fillCircle(knob, kx, ky, 9);
        String txt = fmt != null ? fmt.apply(shown) : String.format("%.2f", shown);
        CLBC_GL_API.drawText(font(), txt, x + w + 8, ky + font().getPixelHeight() * 0.35f, fg);
        return shown;
    }

    /** 该滑块是否在本帧刚松手（调用方据此提交草稿值、写盘一次）。读取后不清，由 endFrame 统一清。 */
    public static boolean sliderJustReleased(int id) { return sliderReleased.contains(id); }

    /** 取滑块当前草稿值（拖动中的值）；未拖动返回 defaultValue。 */
    public static double sliderDraftValue(int id, double defaultValue) {
        return sliderDraft.getOrDefault(id, defaultValue);
    }

    /** 每帧末清理「刚松手」边沿，由 CLBC_GL_API.endFrame 调用。 */
    public static void endFrameWidgets() { sliderReleased.clear(); }

    // ==========================================================
    // 右键菜单（对应 JavaFX ContextMenu）—— 两阶段：open + draw
    // 在每帧末尾调用 drawContextMenu 叠加绘制在最上层。
    // ==========================================================
    private static class Popup {
        int id;
        String[] items;
        float x, y;
    }

    public static void openContextMenu(int id, float x, float y, String[] items) {
        Popup p = new Popup();
        p.id = id; p.x = x; p.y = y; p.items = items;
        activePopup = p;
    }

    public static boolean contextMenuActive() { return activePopup != null; }

    /** 当前激活的右键菜单 id（对应 openContextMenu 传入的 id）；无菜单时返回 -1。
     *  用于调用方区分"这是不是我打开的菜单"，避免顶栏菜单与画布菜单共用全局状态时互相消费。 */
    public static int activePopupId() { return activePopup != null ? activePopup.id : -1; }

    /** 【T6】是否有下拉面板展开。展开期间调用方可屏蔽底层面板输入，
     *  防止“下拉盖住工具箱/画布，但点击却穿透到被盖住的积木”的图层/交互不一致。 */
    public static boolean anyComboOpen() {
        if (comboOpen.isEmpty()) return false;
        for (Boolean b : comboOpen.values()) if (Boolean.TRUE.equals(b)) return true;
        return false;
    }

    /** 绘制并检测右键菜单，返回被点击项索引；-1 表示无点击 / 点击外部取消 */
    public static int drawContextMenu(long bg, long fg, long sel) {
        if (activePopup == null) return -1;
        float px = activePopup.x, py = activePopup.y, pw = 160, ph = activePopup.items.length * ROW_H;
        // 点击外部取消
        if (CLBC_GL_API.mouseJustPressed && !hit(px, py, pw, ph)) {
            activePopup = null;
            return -1;
        }
        CLBC_GL_API.fillRect(bg, px, py, pw, ph);
        CLBC_GL_API.strokeRect(lighten(bg, 0.25f), px, py, pw, ph, 1);
        CLBC_GL_API.setClipLogical(px, py, pw, ph);
        for (int i = 0; i < activePopup.items.length; i++) {
            float ry = py + i * ROW_H;
            boolean rh = hit(px, ry, pw, ROW_H);
            if (rh) CLBC_GL_API.fillRect(sel, px, ry, pw, ROW_H);
            CLBC_GL_API.drawText(font(), activePopup.items[i], px + PAD, ry + ROW_H / 2f + font().getPixelHeight() * 0.35f, fg);
            if (rh && CLBC_GL_API.mouseJustPressed) {
                int idx = i;
                int pid = activePopup.id;
                String item = activePopup.items[i];
                activePopup = null;
                CLBC_Util.logFine("右键菜单: #" + pid + " 项=" + item);
                return idx;
            }
        }
        CLBC_GL_API.clearClip();
        return -1;
    }

    // ==========================================================
    // 颜色工具
    // ==========================================================
    /** 在给定颜色上叠加明度（+amt 提亮，-amt 压暗） */
    public static long lighten(long color, float amt) {
        int a = (int) ((color >> 24) & 0xFF);
        int r = (int) ((color >> 16) & 0xFF);
        int g = (int) ((color >> 8) & 0xFF);
        int b = (int) (color & 0xFF);
        r = clamp(r + (int) (amt * 255));
        g = clamp(g + (int) (amt * 255));
        b = clamp(b + (int) (amt * 255));
        return CLBC_GL_API.rgba(r, g, b, a);
    }
    public static long darken(long color, float amt) { return lighten(color, -amt); }
    private static int clamp(int v) { return Math.max(0, Math.min(255, v)); }
}
