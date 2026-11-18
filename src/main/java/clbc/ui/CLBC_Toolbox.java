package clbc.ui;

import java.util.ArrayList;
import java.util.List;

import clbc.blocks.CLBC_Lang;
import clbc.blocks.CLBC_JdkExtensions;
import clbc.blocks.CLBC_BlockLibrary;
import clbc.blocks.CLBC_BlockDefinition;
import clbc.blocks.CLBC_BlockRegistry;
import clbc.gl.CLBC_GL_API;
import clbc.gl.CLBC_GL_Widgets;
import clbc.util.CLBC_Util;

/**
 * 左侧积木工具箱（OpenGL 即时模式，无 JavaFX）。
 * 左侧分类栏 + 右侧积木列表（可滚轮滚动）。
 * 分类配色统一来自语言文件 {@link CLBC_Lang}（需求②-4：分类配色统一来源）。
 * 点击积木即"拖入画布"——对应原 JavaFX 的 DragDetected→CanvasHost drop，
 * 这里简化为点击即添加到画布中心（功能等价，交互更省心）。
 */
public class CLBC_Toolbox {

    /** 一个工具箱积木条目。#164：specId 非空时整块由登记器按定义生成；
     *  coreKey / callRef 仅作旧入口兼容保留，工具箱本身已不再手写任何条目。 */
    private record TBBlock(String text, long color, String kind, String coreKey, String callRef,
                           String specId, String search) {
        TBBlock(String text, long color, String kind) { this(text, color, kind, null, null, null, null); }
        TBBlock(String text, long color, String kind, String coreKey) { this(text, color, kind, coreKey, null, null, null); }
    }

    private final CLBC_CanvasHost canvas;
    private String currentCategory = "系统";
    private float scroll = 0;
    /** 搜索框当前输入的草稿（边打边显示，不立即过滤） */
    private String searchQuery = "";
    /** 按回车后真正生效的过滤串；空 = 不过滤 */
    private String activeQuery = "";

    /** 可见分类缓存：只在 JDK 扩展开关变化（revision++）时重算，避免每帧遍历 1808 条定义 */
    private java.util.List<String> cachedVisibleCats = null;
    private int cachedCatRevision = -1;

    /** 最近一次绘制的工具箱屏幕矩形（B-10：画布释放积木时据此判断"拖回工具箱＝删除"）。
     *  静态持有，供同包 CLBC_CanvasHost 在 handleInput 释放阶段读取；坐标为物理窗口像素，不受画布缩放影响。 */
    public static float lastX = 0, lastY = 0, lastW = 0, lastH = 0;

    /** 扩展库弹层开关（Scratch/TurboWarp 风格：工具箱左下角「扩展」按钮打开）。
     *  扩展入口属于代码编辑区，不在主菜单/设置页。 */
    public boolean extensionsOpen = false;
    private boolean extIgnoreOutsideClick = false;
    /** 扩展库弹层滚动（窗口较小时卡片超出可视高度） */
    private float extScroll = 0;
    /** 【#256】左侧分类栏滚动：分类多/工具箱区矮时圆点列超出可视高度，滚轮上下滚动（不画滑动条）。
     *  扩展按钮固定在底部，不随分类滚动，避免被下方源码文件面板覆盖。 */
    private float catScroll = 0;
    /** 【T10】扩展库分组筛选：0=全部分组，>0 对应 modules 列表下标+1 */
    private int extGroupSel = 0;

    public CLBC_Toolbox(CLBC_CanvasHost canvas) {
        this.canvas = canvas;
    }

    /** 分类→配色（统一取自语言文件 CLBC_Lang；无则默认橙） */
    public static long categoryColor(String cat) {
        return CLBC_Lang.categoryColor(cat);
    }

    /** 登记色的深档：在 CLBC_Lang 分类配色上压暗一档（分类圆点阴影 / 选中竖条）。 */
    public static long categoryColorDeep(String cat) {
        return CLBC_GL_API.darken(categoryColor(cat), 0.22f);
    }

    /** 登记色的浅档：在 CLBC_Lang 分类配色上提亮一档（圆点高光 / 相邻条目底色带）。
     *  深浅两档都从同一登记色派生，不另起配色体系；积木本体仍用登记色，
     *  因此预选区看到的积木与拖到画布上的积木外观一致。 */
    public static long categoryColorLight(String cat, float amount) {
        return CLBC_GL_API.lighten(categoryColor(cat), amount);
    }

    /** 【#218】按主题取色：浅色沿用原硬编码值（保持 TurboWarp 观感逐像素一致），
     *  深色改用 CLBC_Theme 下发的界面壳配色。工具箱此前全部写死浅色，深色主题下白底不跟随。 */
    private static long tc(long light, long dark) {
        return CLBC_Theme.isDark() ? dark : light;
    }

    /** 绘制整个工具箱（区域 x,y,w,h） */
    public void draw(float x, float y, float w, float h) {
        lastX = x; lastY = y; lastW = w; lastH = h;   // 记录区域，供拖回删除命中
        CLBC_Theme.Colors C = CLBC_Theme.current();
        // 右侧积木区底色（浅色=白色工作区 / 深色=主题面板色）
        CLBC_GL_API.fillRect(tc(CLBC_GL_API.hex("#FFFFFF"), C.PANEL), x, y, w, h);
        // Scratch 风格：左侧窄彩条，每类一个圆形彩色图标；选中时向右弹出浅色标签。
        float catW = 52;
        CLBC_GL_API.fillRect(tc(CLBC_GL_API.hex("#F2F5FA"), C.PANEL2), x, y, catW, h);
        CLBC_GL_API.fillRect(tc(CLBC_GL_API.hex("#DCE3ED"), C.SEP), x + catW - 1, y, 1, h);

        // ---- 分类栏（顺序由 CLBC_Lang.categories() 给出；15 类时自适应间距，不溢出） ----
        // Scratch 原版分类栏就是一列彩色圆点 + 选中竖条，不常驻文字标签：
        // 旧版把选中分类名向右弹出压在列表边缘，结果被后画的积木区白底盖住/截断（截图实证），
        // 且 15 类时文字挤成一团。这里回归 Scratch 风格：只画圆点，选中加左侧竖条 + 外圈光晕。
        java.util.List<String> cats = visibleCategories();
        if (!cats.contains(currentCategory)) {
            currentCategory = cats.isEmpty() ? "语法" : cats.get(0);
            scroll = 0;
            CLBC_Util.log("JDK 扩展变化，工具箱分类切到: " + currentCategory);
        }
        int n = cats.size();
        // 底部给「扩展」按钮留 46px（Scratch/TurboWarp：分类栏最下方的扩展入口）
        float catBottom = y + h - 46f;
        // 【#256】间距：7 类保持原 54px；更多类用 36px 固定行高，超出可视区靠滚轮滚动，
        // 不再强行把 step 压缩到 28px 下限还塞不下→圆点溢出压到扩展按钮/下方文件面板。
        float step = n <= 7 ? 54f : 36f;
        float dotR = n <= 7 ? 10f : 9f;
        // 分类圆点可视区：顶栏下方到扩展按钮之上
        float catViewTop = y + 4f, catViewH = Math.max(0f, catBottom - catViewTop - 4f);
        float catContentH = n * step;
        float catMaxScroll = Math.max(0f, catContentH - catViewH);
        if (CLBC_GL_Widgets.hit(x, catViewTop, catW, catViewH))
            catScroll = Math.max(0f, Math.min(catMaxScroll, catScroll - CLBC_GL_API.scrollDelta * 40f));
        if (catScroll > catMaxScroll) catScroll = catMaxScroll;
        CLBC_GL_API.setClip(x, catViewTop, catW, catViewH);
        float cy = y + 6 - catScroll;
        for (String cat : cats) {
            long c = categoryColor(cat);
            long deep = categoryColorDeep(cat);
            long pale = categoryColorLight(cat, 0.45f);
            boolean sel = cat.equals(currentCategory);
            if (cy + step < catViewTop || cy > catViewTop + catViewH) { cy += step; continue; }
            float iconX = x + catW / 2f, iconY = cy + step / 2f;
            if (sel) {
                // 【#218】选中光晕：浅色=白、深色=主题次级面板色的半透明提亮，避免深底上突兀白圈
                CLBC_GL_API.fillCircle(tc(CLBC_GL_API.rgba(255, 255, 255, 220),
                        CLBC_GL_API.rgba(90, 95, 105, 200)), iconX, iconY, dotR + 7f);
                CLBC_GL_API.fillRect(deep, x, cy + 1, 4, step - 4);
            }
            // 深档外圈 + 登记色主体 + 浅档高光：同一色相的深浅两档让每个分类色块都能看出层次
            CLBC_GL_API.fillCircle(deep, iconX, iconY + 1f, dotR + 2f);
            CLBC_GL_API.fillCircle(c, iconX, iconY, dotR);
            CLBC_GL_API.fillCircle(pale,
                    iconX - dotR * 0.28f, iconY - dotR * 0.28f, Math.max(2f, dotR * 0.34f));
            if (!extensionsOpen && CLBC_GL_Widgets.hit(x, cy, catW, step) && CLBC_GL_API.mouseJustPressed) {
                currentCategory = cat;
                scroll = 0;
                CLBC_Util.log("左侧分类切换: " + cat);
            }
            cy += step;
        }
        CLBC_GL_API.clearClip();   // 分类栏裁剪到此结束，扩展按钮固定画在底部不受裁

        // ---- 扩展按钮（分类栏最下方，Scratch/TurboWarp 风格） ----
        float exX = x + 6, exY = catBottom + 6, exW = catW - 12, exH = 34;
        boolean exHover = !extensionsOpen && CLBC_GL_Widgets.hit(exX, exY, exW, exH);
        CLBC_GL_API.fillRoundRect(exHover ? CLBC_GL_API.rgba(76, 151, 255, 235) : CLBC_GL_API.rgba(76, 151, 255, 190),
                exX, exY, exW, exH, 8);
        CLBC_GL_API.drawTextCentered(CLBC_GL_API.uiFont, "扩展", exX, exY, exW, exH,
                CLBC_GL_API.hex("#FFFFFF"));
        if (exHover && CLBC_GL_API.mouseJustPressed) {
            extensionsOpen = true;
            extIgnoreOutsideClick = true;
            extScroll = 0;
            CLBC_Util.log("打开 JDK 扩展库（代码编辑区入口）");
        }

        // ---- 搜索框 ----
        float sbX = x + catW + 12, sbY = y + 8, sbW = w - catW - 24, sbH = 28;
        long sbBorder = tc(CLBC_GL_API.hex("#DDE3EC"), C.SEP);
        long sbBg     = tc(CLBC_GL_API.hex("#F7F9FC"), C.PANEL2);
        long sbText   = tc(CLBC_GL_API.hex("#2E3440"), C.TEXT);
        long sbHint   = tc(CLBC_GL_API.hex("#8090A0"), C.SUBTEXT);
        CLBC_GL_API.strokeRoundRect(sbBorder, sbX - 1, sbY - 1, sbW + 2, sbH + 2, 7, 1);
        if (!extensionsOpen) {
            String typed = CLBC_GL_Widgets.textField(9100, sbX, sbY, sbW - 58, sbH, searchQuery,
                    sbBg, sbText, C.ACCENT);
            if (!typed.equals(searchQuery)) {
                searchQuery = typed;
                // 输入变化即撤销上一次过滤，等回车重新生效；清空输入直接恢复全量
                if (!typed.equals(activeQuery)) activeQuery = "";
                scroll = 0;
            }
            String hint = activeQuery.isEmpty() ? "回车搜索" : ("已搜 " + activeQuery);
            CLBC_GL_API.drawText(CLBC_GL_API.uiFont, hint, sbX + sbW - 54, sbY + 7, sbHint);
            if (CLBC_GL_API.keyEnter && CLBC_GL_API.focusedTextField == 9100) {
                activeQuery = searchQuery == null ? "" : searchQuery.trim();
                scroll = 0;
                CLBC_Util.log("工具箱搜索: " + (activeQuery.isEmpty() ? "清空" : activeQuery));
                CLBC_GL_API.keyEnter = false;   // 吞掉，避免同帧触发别的回车逻辑
            }
        } else {
            CLBC_GL_API.fillRoundRect(sbBg, sbX, sbY, sbW, sbH, 7);
            CLBC_GL_API.drawText(CLBC_GL_API.uiFont, searchQuery == null ? "" : searchQuery,
                    sbX + 8, sbY + 6, sbText);
        }

        // ---- 积木列表 ----
        float listX = x + catW + 12, listY = y + 44, listW = w - catW - 24, listH = h - 56;
        // P1-2：搜索串非空时跨全部分类检索（Scratch 行为），不再局限当前分类页；
        // 无查询时保持只显示当前分类，避免每帧遍历全库（近万条）拖慢渲染。
        List<TBBlock> blocks;
        if (activeQuery.isEmpty()) {
            blocks = blocksFor(currentCategory);
        } else {
            List<TBBlock> all = new ArrayList<>();
            for (String cat : visibleCategories()) all.addAll(blocksFor(cat));
            blocks = filterBlocks(all, activeQuery);
        }
        // 实测修复（堆叠根因）：旧实现把行高写死 bh=44、游标每轮固定 +=54，
        // 但积木真实高度由 CLBC_BlockShape 决定：语句块 STACK_H=52、报告块 ROUND_H=44、
        // 容器块=首栏 52 + 口 28 + 底条 16 ≈ 96，类/方法更高。44px 行高配 48px 裁剪窗
        // 把高块拦腰截断，块内不同栏的文字/榫口被压进同一条带里，相邻条目看起来堆成一团。
        // 现在按每块 measureBlock 的真实高度算行高，游标逐行累加，整块完整落在自己的行内。
        // 【#218】放宽条目间距：原 10px 在深色主题下条目贴得更紧，改 16px 让每积木行更透气。
        final float rowGap = 16f, bottomPad = 8f;
        final int count = blocks.size();
        float[] rowH = new float[count];
        float[] topPad = new float[count];
        CLBC_Block[] previews = new CLBC_Block[count];
        float contentH = 0f;
        for (int i = 0; i < count; i++) {
            TBBlock b = blocks.get(i);
            // 预选区与画布同源：同一登记器 create(specId) + 同一 drawBlock，所见即所得
            CLBC_Block pv = b.specId == null || b.specId.isEmpty()
                    ? null : CLBC_BlockRegistry.create(b.specId);
            if (pv != null) CLBC_BlockRenderer.measureBlock(pv);
            previews[i] = pv;
            // 帽块拱顶在 b.h 之上再鼓起约 17px（CLBC_BlockShape 贝塞尔峰值），单留顶部空隙
            topPad[i] = pv != null && CLBC_BlockShape.isHat(pv.kind) ? 18f : 4f;
            rowH[i] = pv == null ? 64f : Math.max(64f, pv.h + topPad[i] + bottomPad);
            contentH += rowH[i] + rowGap;
        }
        CLBC_GL_API.setClip(listX - 4, listY - 4, listW + 8, listH + 8);
        if (!extensionsOpen && CLBC_GL_Widgets.hit(listX - 4, listY - 4, listW + 8, listH + 8)) {
            scroll -= CLBC_GL_API.scrollDelta * 30;
            scroll = Math.max(0, Math.min(scroll, Math.max(0, contentH - listH)));
        }
        float by = listY - scroll;
        for (int i = 0; i < count; i++) {
            TBBlock b = blocks.get(i);
            float rh = rowH[i];
            if (by + rh < listY || by > listY + listH) { by += rh + rowGap; continue; }
            boolean hovered = CLBC_GL_Widgets.hit(listX, by, listW, rh);
            // 深浅层次：相邻条目交替淡档/白色底色带（淡档派生自当前分类登记色），
            // 只垫在积木背后、不改积木本体颜色——拖出后仍与预选区看到的一模一样。
            // 【#218】浅色主题维持分类淡档/白交替；深色主题改用主题面板色交替，避免白条刺眼。
            long rowBg = CLBC_Theme.isDark()
                    ? ((i & 1) == 0 ? C.PANEL2 : C.PANEL)
                    : ((i & 1) == 0 ? categoryColorLight(currentCategory, 0.88f)
                                    : CLBC_GL_API.hex("#FFFFFF"));
            CLBC_GL_API.fillRoundRect(rowBg, listX, by, listW, rh, 6);
            CLBC_Block preview = previews[i];
            if (preview == null) {
                // 兜底（未登记的历史条目）：保留原简化外观，不吞交互
                long body = hovered ? CLBC_GL_Widgets.lighten(b.color, 0.12f) : b.color;
                CLBC_GL_API.fillRoundRect(body, listX, by, listW, rh, 8);
                CLBC_GL_API.drawText(CLBC_GL_API.uiFont, ellipsis(b.text, listW - 26),
                        listX + 14f, by + rh / 2f + 5f, CLBC_GL_API.hex("#FFFFFF"));
            } else {
                if (hovered) {
                    CLBC_GL_API.fillRoundRect(CLBC_GL_API.rgba(76, 151, 255, 46),
                            listX - 2, by - 2, listW + 4, rh + 4, 9);
                }
                // 裁剪框跟着真实行高走：整块（含帽顶 HAT_H / 底榫）完整可见，
                // 超高积木只截左右（与 Scratch 工具箱一致），不再上下拦腰截断。
                CLBC_GL_API.setClip(listX, by, listW, rh);
                try {
                    float py = by + topPad[i];
                    CLBC_BlockRenderer.drawBlock(preview, listX + 2f, py);
                } finally {
                    CLBC_GL_API.clearClip();
                }
            }
            // #130：按下即开始「拖出到目标位置再插入」；松手落点由画布 handleInput 计算，点击不再直接生成
            if (!extensionsOpen && hovered && CLBC_GL_API.mouseJustPressed && !canvas.isToolboxDragging()) {
                CLBC_Util.log("工具箱开始拖拽积木: " + b.text + "（" + b.specId + "）");
                canvas.beginToolboxDrag(b.specId, b.coreKey, b.callRef, b.text, b.kind, b.color);
            }
            by += rh + rowGap;
        }
        CLBC_GL_API.clearClip();
    }

    /**
     * 扩展库弹层（Scratch/TurboWarp 风格）：覆盖在编辑器上层的一张扩展卡片画廊。
     * 只在 {@link #extensionsOpen} 为真时由渲染器于帧末调用；底层输入由渲染器临时屏蔽。
     */
    public void drawExtensionPopup(int winW, int winH) {
        if (!extensionsOpen) return;
        long dim = CLBC_GL_API.rgba(20, 28, 42, 150);
        CLBC_GL_API.fillRect(dim, 0, 0, winW, winH);

        CLBC_Theme.Colors C = CLBC_Theme.current();
        float pw = Math.min(760f, winW - 60f), ph = Math.min(590f, winH - 60f);
        float px = (winW - pw) / 2f, py = (winH - ph) / 2f;
        long panelBg = tc(CLBC_GL_API.hex("#FFFFFF"), C.PANEL);
        long border = tc(CLBC_GL_API.hex("#D9E2EC"), C.SEP);
        long titleC = tc(CLBC_GL_API.hex("#2E3440"), C.TEXT);
        long subC = tc(CLBC_GL_API.hex("#718096"), C.SUBTEXT);
        long chipBg = tc(CLBC_GL_API.hex("#F2F5FA"), C.PANEL2);
        long cardBg = tc(CLBC_GL_API.hex("#F7F9FC"), C.PANEL2);
        CLBC_GL_API.fillRoundRect(panelBg, px, py, pw, ph, 14);
        CLBC_GL_API.strokeRoundRect(border, px, py, pw, ph, 14, 2);

        CLBC_GL_API.drawText(CLBC_GL_API.uiFont, "扩展库", px + 24, py + 34, titleC);
        CLBC_GL_API.drawText(CLBC_GL_API.uiFont,
                "自动识别当前 JDK " + clbc.blocks.CLBC_JdkExtensions.jdkVersion()
                        + " · " + clbc.blocks.CLBC_JdkExtensions.ALL.size() + " 个库（运行时识别）",
                px + 300, py + 34, subC);
        if (CLBC_GL_Widgets.button(9601, px + pw - 44, py + 14, 30, 28, "×",
                chipBg, titleC)) {
            extensionsOpen = false;
            return;
        }
        CLBC_GL_API.fillRect(border, px + 20, py + 56, pw - 40, 1);

        // 【T10】分组下拉：按 JDK 模块分组筛选（"全部分组" + 各模块），选组后只显该组卡片。
        java.util.List<String> modules = new java.util.ArrayList<>();
        modules.add("全部分组");
        for (clbc.blocks.CLBC_JdkExtensions.Ext e : CLBC_JdkExtensions.ALL)
            if (e.module != null && !e.module.isEmpty() && !modules.contains(e.module)) modules.add(e.module);
        if (extGroupSel < 0 || extGroupSel >= modules.size()) extGroupSel = 0;
        CLBC_GL_API.drawText(CLBC_GL_API.uiFont, "分组:", px + 24, py + 74, subC);
        int groupSel = CLBC_GL_Widgets.comboBox(9630, px + 70, py + 62, 220, 28,
                modules.toArray(new String[0]), extGroupSel, chipBg, titleC,
                chipBg, titleC);
        if (groupSel != extGroupSel) { extGroupSel = groupSel; extScroll = 0; }

        // 扩展卡片，两列排布（Scratch 扩展画廊风格）；按分组筛选
        java.util.List<clbc.blocks.CLBC_JdkExtensions.Ext> exts = new java.util.ArrayList<>();
        String groupName = modules.get(extGroupSel);
        for (clbc.blocks.CLBC_JdkExtensions.Ext e : CLBC_JdkExtensions.ALL) {
            if (extGroupSel == 0) exts.add(e);
            else if (e.module != null && e.module.equals(groupName)) exts.add(e);
        }
        // 整组启停：选中具体分组时显示，一键把该组全部开/关
        if (extGroupSel > 0) {
            boolean allOn = true;
            java.util.Map<String, Boolean> gs = CLBC_JdkExtensions.snapshot();
            for (clbc.blocks.CLBC_JdkExtensions.Ext e : exts)
                if (!Boolean.TRUE.equals(gs.get(e.id))) { allOn = false; break; }
            String gt = allOn ? "停用本组" : "启用本组";
            if (CLBC_GL_Widgets.button(9631, px + 312, py + 62, 92, 28, gt,
                    chipBg, titleC)) {
                for (clbc.blocks.CLBC_JdkExtensions.Ext e : exts)
                    CLBC_JdkExtensions.setEnabled(e.id, !allOn);
            }
        }
        int cols = 2;
        float gap = 10f;
        float cardW = (pw - 40f - gap) / cols;
        float cardH = 66f;
        float gridY = py + 100f;
        float gridH = ph - 100f - 58f;
        java.util.Map<String, Boolean> state = CLBC_JdkExtensions.snapshot();
        int rows = (exts.size() + cols - 1) / cols;
        float maxScroll = Math.max(0f, rows * (cardH + 8f) - gridH);
        if (CLBC_GL_Widgets.hit(px + 20, gridY, pw - 40, gridH))
            extScroll = Math.max(0f, Math.min(maxScroll, extScroll - CLBC_GL_API.scrollDelta * 60f));
        CLBC_GL_API.setClip(px + 16, gridY - 2, pw - 32, gridH + 4);
        for (int i = 0; i < exts.size(); i++) {
            clbc.blocks.CLBC_JdkExtensions.Ext e = exts.get(i);
            int col = i % cols, row = i / cols;
            float cx = px + 20f + col * (cardW + gap);
            float cy = gridY + row * (cardH + 8f) - extScroll;
            if (cy + cardH < gridY || cy > gridY + gridH) continue;
            boolean on = Boolean.TRUE.equals(state.get(e.id));
            boolean hover = CLBC_GL_Widgets.hit(cx, cy, cardW, cardH);
            CLBC_GL_API.fillRoundRect(hover ? CLBC_GL_API.lighten(e.color, 0.90f) : cardBg,
                    cx, cy, cardW, cardH, 9);
            CLBC_GL_API.strokeRoundRect(on ? e.color : border, cx, cy, cardW, cardH, 9, on ? 2 : 1);
            CLBC_GL_API.fillCircle(e.color, cx + 22, cy + 22, 9);
            CLBC_GL_API.drawText(CLBC_GL_API.uiFont, e.name, cx + 40, cy + 27, titleC);
            // 【T11】去掉省略号截断：显示精简描述（模块短名 + 类数），完整不截断。
            String shortDesc = (e.module == null || e.module.isEmpty() ? e.desc
                    : e.module.replace("java.", "").replace("jdk.", "") + " · " + e.sample);
            CLBC_GL_API.drawText(CLBC_GL_API.uiFont, shortDesc, cx + 40, cy + 50, subC);
            String toggle = on ? "移除" : "添加";
            if (CLBC_GL_Widgets.button(9610 + i, cx + cardW - 72, cy + 20, 60, 28, toggle,
                    on ? chipBg : e.color, on ? titleC : CLBC_GL_API.hex("#FFFFFF"))) {
                CLBC_JdkExtensions.setEnabled(e.id, !on);
                CLBC_Util.log("扩展库开关：" + e.name + " → " + (!on ? "启用" : "停用"));
            }
        }

        CLBC_GL_API.clearClip();
        // 底部：统计 + 全部启停 + 完成
        float fy = py + ph - 46f;
        CLBC_GL_API.fillRect(border, px + 20, fy - 8, pw - 40, 1);
        int enabledCount = 0;
        for (Boolean b : state.values()) if (Boolean.TRUE.equals(b)) enabledCount++;
        CLBC_GL_API.drawText(CLBC_GL_API.uiFont, "已启用 " + enabledCount + " / " + exts.size(),
                px + 24, fy + 14, subC);
        if (CLBC_GL_Widgets.button(9620, px + 180, fy, 88, 30, "全部启用",
                chipBg, titleC)) {
            for (clbc.blocks.CLBC_JdkExtensions.Ext e : exts)
                if (!Boolean.TRUE.equals(state.get(e.id))) CLBC_JdkExtensions.setEnabled(e.id, true);
        }
        if (CLBC_GL_Widgets.button(9621, px + 278, fy, 88, 30, "全部停用",
                chipBg, titleC)) {
            for (clbc.blocks.CLBC_JdkExtensions.Ext e : exts)
                if (Boolean.TRUE.equals(state.get(e.id))) CLBC_JdkExtensions.setEnabled(e.id, false);
        }
        if (CLBC_GL_Widgets.button(9622, px + pw - 112, fy, 88, 30, "完成",
                CLBC_GL_API.hex("#4C97FF"), CLBC_GL_API.hex("#FFFFFF"))) {
            extensionsOpen = false;
            return;
        }

        // 点击面板外关闭（打开当帧忽略，避免同一按下立刻关闭）
        if (extIgnoreOutsideClick) {
            extIgnoreOutsideClick = false;
        } else if (CLBC_GL_API.mouseJustPressed
                && !CLBC_GL_Widgets.hit(px, py, pw, ph)) {
            extensionsOpen = false;
        }
    }

    /** 按已锁定的查询串过滤积木：中文显示文本 + 对应 Java 代码线索（类名/成员/键/模板）双向命中。 */
    static List<TBBlock> filterBlocks(List<TBBlock> src, String query) {
        if (query == null || query.isEmpty()) return src;
        String q = query.toLowerCase();
        List<TBBlock> out = new ArrayList<>();
        for (TBBlock b : src) {
            String hay = ((b.text == null ? "" : b.text) + " " + (b.search == null ? "" : b.search)).toLowerCase();
            if (hay.contains(q)) out.add(b);
        }
        return out;
    }

    /** 当前应显示的分类：纯语法分类恒在；JDK 分类下还有可见积木时才保留。
     *  这样停用「网络」扩展后，网络圆点与整页积木一起消失，和 Scratch 移除扩展一致。 */
    private java.util.List<String> visibleCategories() {
        int rev = CLBC_JdkExtensions.revision();
        if (cachedVisibleCats != null && cachedCatRevision == rev) return cachedVisibleCats;
        java.util.List<String> r = new ArrayList<>();
        for (String c : CLBC_Lang.categories()) {
            if (!CLBC_BlockRegistry.byCategory(c).isEmpty()) r.add(c);
        }
        cachedVisibleCats = r;
        cachedCatRevision = rev;
        return r;
    }

    /** 条目文字过长时按框宽截断（工具箱条目不换行），避免文字溢出到画布区。 */
    private static String ellipsis(String t, float maxW) {
        if (t == null) return "";
        if (CLBC_GL_API.uiFont == null) return t.length() > 24 ? t.substring(0, 24) + "…" : t;
        if (CLBC_GL_API.measureText(CLBC_GL_API.uiFont, t) <= maxW) return t;
        int lo = 0, hi = t.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (CLBC_GL_API.measureText(CLBC_GL_API.uiFont, t.substring(0, mid) + "…") <= maxW) lo = mid;
            else hi = mid - 1;
        }
        return t.substring(0, lo) + "…";
    }

    /** #164：工具箱条目全部来自程序化登记器，零写死。
     *  旧版这里手写了「打印输出 / 数学运算 / 拼接字符串 / 类定义」等占位块，
     *  它们没有 key / args / inputs，积木→代码只能吐出 displayName 文本，根本不是 Java。
     *  现在每条条目都对应一条 Spec（语法定义表 / 反射库成员），create() 产出的块必带合法 key，
     *  BlocksToCode 一定能还原成对应 Java（含按需增删 import、函数式参数 C 口回调体）。 */
    private List<TBBlock> blocksFor(String cat) {
        List<TBBlock> list = new ArrayList<>();
        final int cap = 60;
        // #实测修复：旧实现按登记顺序平铺前 60 条，反射入库时 java.lang.Boolean 排在最前，
        // 结果整屏都是「Boolean · new() / equa... / toStr... / hash... / com... / getB...」。
        // 两条通用规则（不写死任何类名 / 条目）：
        //   ① 过滤所有类从 java.lang.Object 继承的通用成员（方法名由反射生成，非手写名单）；
        //   ② 按声明类分组轮转取，保证一屏覆盖多个类，而不是同一类的几十个成员。
        java.util.Map<String, List<clbc.blocks.CLBC_BlockRegistry.Spec>> groups = new java.util.LinkedHashMap<>();
        java.util.Map<String, List<clbc.blocks.CLBC_BlockRegistry.Spec>> ctors = new java.util.LinkedHashMap<>();
        for (clbc.blocks.CLBC_BlockRegistry.Spec s : clbc.blocks.CLBC_BlockRegistry.byCategory(cat)) {
            if (!toolboxWorthy(s)) continue;
            String g = s.ownerFqn != null ? s.ownerFqn : "core:" + cat;
            java.util.Map<String, List<clbc.blocks.CLBC_BlockRegistry.Spec>> dst = isCtor(s) ? ctors : groups;
            dst.computeIfAbsent(g, k -> new ArrayList<>()).add(s);
        }
        // 构造器统一排到各组末尾：第一圈轮转先出真正有语义的方法/字段，
        // 不再被「Boolean · new() / Byte · new() / Short · new()」占满首屏。
        for (java.util.Map.Entry<String, List<clbc.blocks.CLBC_BlockRegistry.Spec>> e : ctors.entrySet())
            groups.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).addAll(e.getValue());
        List<List<clbc.blocks.CLBC_BlockRegistry.Spec>> gl = new ArrayList<>(groups.values());
        int[] idx = new int[gl.size()];
        java.util.Set<String> seenMembers = new java.util.HashSet<>();
        boolean added = true;
        while (list.size() < cap && added) {
            added = false;
            for (int i = 0; i < gl.size() && list.size() < cap; i++) {
                List<clbc.blocks.CLBC_BlockRegistry.Spec> g = gl.get(i);
                // 跨类同名成员首屏只留一条（Boolean/Byte/Short 都有 compare，
                // 不去重就会连着三行「· compare...」，仍是刷屏）。纯语法块按 label 去重。
                clbc.blocks.CLBC_BlockRegistry.Spec pick = null;
                while (idx[i] < g.size()) {
                    clbc.blocks.CLBC_BlockRegistry.Spec s = g.get(idx[i]++);
                    String sig = s.member != null ? s.member : s.label;
                    if (sig != null && !seenMembers.add(sig)) continue;
                    pick = s; break;
                }
                if (pick == null) continue;
                added = true;
                // 调用积木在工具箱里显示为 stack；实际 kind 由 makeCallBlock 按函数式参数决定（可能是 cblock）
                String kind = "ops.call".equals(pick.key) ? "stack" : pick.kind;
                // 搜索索引：中文标签 + 对应 Java 代码线索（类全名/成员/键/模板）
                StringBuilder sbCode = new StringBuilder();
                if (pick.ownerFqn != null) sbCode.append(pick.ownerFqn).append(' ');
                if (pick.member != null) sbCode.append(pick.member).append(' ');
                if (pick.callRef != null) sbCode.append(pick.callRef).append(' ');
                if (pick.key != null) sbCode.append(pick.key).append(' ');
                if (pick.codeTemplate != null) sbCode.append(pick.codeTemplate).append(' ');
                list.add(new TBBlock(pick.label, pick.color, kind, null, null, pick.id, sbCode.toString()));
            }
        }
        return list;
    }

    /** java.lang.Object 声明的通用成员名（反射生成，类无关），继承来的一律不进工具箱。 */
    private static final java.util.Set<String> OBJECT_MEMBERS = objectMemberNames();
    private static java.util.Set<String> objectMemberNames() {
        java.util.Set<String> s = new java.util.HashSet<>();
        for (java.lang.reflect.Method m : Object.class.getDeclaredMethods()) s.add(m.getName());
        return s;
    }

    private static boolean toolboxWorthy(clbc.blocks.CLBC_BlockRegistry.Spec s) {
        // 纯语法定义表（无库元数据）全留；库块只滤掉 Object 继承来的无意义成员
        return s.ownerFqn == null || s.member == null || !OBJECT_MEMBERS.contains(s.member);
    }

    /** 构造器判定（反射产物的通用命名，不针对任何具体类） */
    private static boolean isCtor(clbc.blocks.CLBC_BlockRegistry.Spec s) {
        if (s.member == null) return false;
        if ("<init>".equals(s.member) || "new".equals(s.member)) return true;
        int i = s.ownerFqn == null ? -1 : s.ownerFqn.lastIndexOf('.');
        return i >= 0 && s.member.equals(s.ownerFqn.substring(i + 1));
    }
}
