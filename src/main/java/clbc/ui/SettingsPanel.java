package clbc.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import clbc.core.CLBC_AppContext;
import clbc.gl.CLBC_GL_API;
import clbc.gl.CLBC_GL_Font;
import clbc.gl.CLBC_GL_Widgets;
import clbc.util.CLBC_Util;

/**
 * 设置面板（OpenGL 即时模式，无 JavaFX）。
 *
 * 从 {@link CLBC_Renderer} 抽出的独立职责类，负责"通用 / 外观 / 编辑器 / 高级"四个设置页。
 * 整体布局为左侧分类列表 + 右侧内容区，分类切换由即时模式列表控件驱动。
 *
 * 所有底层绘制走 {@link CLBC_GL_API}（通用原语），控件编排走 {@link CLBC_GL_Widgets}（内部渲染层），
 * 不依赖任何 JavaFX 组件。各控件的选择 / 勾选 / 滑动变化都会即时通过 {@link CLBC_Util#saveSetting} 落盘配置。
 */
public class SettingsPanel {

    // 颜色字段（主题色系统：由 CLBC_Theme 供给，切换主题时 syncTheme 就地刷新）
    private long PANEL, PANEL2, TEXT, SUBTEXT, ACCENT, WHITE;
    private int themeVersion = -1;

    /** 从主题中心刷新本面板配色 */
    private void syncTheme() {
        CLBC_Theme.Colors c = CLBC_Theme.current();
        PANEL = c.SET_PANEL; PANEL2 = c.SET_PANEL2; TEXT = c.SET_TEXT;
        SUBTEXT = c.SET_SUBTEXT; ACCENT = c.ACCENT; WHITE = c.WHITE;
    }

    /** 【T5】设置页改字号后即时重建 UI 字体，让代码/积木文字立即变大变小，无需重启。
     *  旧字体 dispose 释放图集纹理；GL_API 的文字宽度缓存按字体引用比对，会自动失效。 */
    private void rebuildUiFont(float size) {
        try {
            CLBC_GL_Font old = CLBC_GL_API.uiFont;
            CLBC_GL_API.uiFont = null;
            if (old != null) old.dispose();
            CLBC_GL_API.uiFont = new CLBC_GL_Font("C:\\Windows\\Fonts\\msyhbd.ttc", size);
            // 字号变了 → measureText 结果变 → 让全部分指纹布局缓存失效，积木宽度按新字号重算
            CLBC_BlockShape.invalidateAllLayout();
            CLBC_Util.log("字体已即时重建，字号 " + String.format("%.0f", size));
        } catch (RuntimeException e) {
            // 重建失败不能让界面失去字体：尽力恢复默认字号
            try {
                CLBC_GL_API.uiFont = new CLBC_GL_Font("C:\\Windows\\Fonts\\msyhbd.ttc", 26f);
                CLBC_BlockShape.invalidateAllLayout();
            } catch (RuntimeException ignore) { }
            CLBC_Util.log("!!!! 即时重建字体失败，已回退默认字号: " + e.getMessage());
        }
    }

    private static final String[] CATEGORIES = {"通用", "外观", "编辑器", "高级"};

    // ---- 面板状态（即时模式每帧重绘，状态留在本实例字段）----
    private int catSel = 0;
    // 用户要求：设置项必须从 config.properties 读回初值，切换后重启不丢（持久化闭环）。
    private int langSel = readLangSel();
    private int themeSel = CLBC_Theme.isDark() ? 0 : 1;
    private boolean autoUpdate = readBool("app.autoUpdate", true);
    private boolean autoSave = readBool("editor.autoSave", true);
    private double uiScale = readDouble("ui.scale", 1.30);
    private double fontSize = readDouble("editor.fontSize", 26);
    // 黑边（系统边框）开关：true = 无边框。默认 false，保持现有带边框外观。
    private boolean borderless = readBorderless();
    private int bgmSelected = 0;   // 曲目清单当前选中项

    /** 从配置文件读布尔项（缺省给默认值） */
    private static boolean readBool(String key, boolean def) {
        try {
            if (CLBC_AppContext.configFile == null) return def;
            String v = CLBC_Util.loadProperties(CLBC_AppContext.configFile).getProperty(key);
            return v == null ? def : Boolean.parseBoolean(v);
        } catch (Throwable t) { return def; }
    }
    /** 从配置文件读数值项（缺省给默认值） */
    private static double readDouble(String key, double def) {
        try {
            if (CLBC_AppContext.configFile == null) return def;
            String v = CLBC_Util.loadProperties(CLBC_AppContext.configFile).getProperty(key);
            if (v == null) return def;
            return Double.parseDouble(v);
        } catch (Throwable t) { return def; }
    }
    /** 语言下拉初值：中文=0 / English=1 */
    private static int readLangSel() {
        try {
            if (CLBC_AppContext.configFile == null) return 1;
            String v = CLBC_Util.loadProperties(CLBC_AppContext.configFile).getProperty("ui.language", "English");
            return "中文".equals(v) ? 0 : 1;
        } catch (Throwable t) { return 1; }
    }

    private static boolean readBorderless() {
        try {
            if (CLBC_AppContext.configFile == null) return false;
            return "true".equalsIgnoreCase(
                    CLBC_Util.loadProperties(CLBC_AppContext.configFile).getProperty("ui.borderless", "false"));
        } catch (Throwable t) { return false; }
    }

    // ---- 日志收口（即时模式每帧重绘，避免日志刷屏）----
    private boolean loggedMain = false;
    private final boolean[] pageLogged = new boolean[CATEGORIES.length];

    /**
     * 每帧绘制入口：x/y/w/h 为内容区（已扣除顶栏）。
     * 左侧分类列表 + 右侧对应页内容。
     */
    public void draw(float x, float y, float w, float h) {
        if (!loggedMain) { CLBC_Util.log("渲染设置面板主体"); loggedMain = true; }
        if (themeVersion != CLBC_Theme.version()) {
            syncTheme();
            themeSel = CLBC_Theme.isDark() ? 0 : 1;
            themeVersion = CLBC_Theme.version();
        }

        // 左侧分类列表
        float catW = 160;
        catSel = CLBC_GL_Widgets.list(5001, x, y, catW, h, CATEGORIES, catSel, PANEL, TEXT, ACCENT, WHITE);

        // 右侧内容区
        float conX = x + catW + 24, conY = y + 20, conW = w - catW - 48;
        switch (catSel) {
            case 0 -> drawGeneral(conX, conY, conW);
            case 1 -> drawAppearance(conX, conY, conW);
            case 2 -> drawEditor(conX, conY, conW);
            case 3 -> drawAdvanced(conX, conY, conW);
        }

        // 右下角播放控制圆圈：左键=暂停/继续，右键=重新随机。
        drawBgmFab(x + w - 38, y + h - 38, 14);
    }

    /** 右下角背景音乐控制圆圈。左键暂停/继续，右键重新随机一首。 */
    private void drawBgmFab(float cx, float cy, float r) {
        boolean playing = CLBC_Audio.isPlaying();
        long ring = CLBC_Audio.isPaused() || !playing ? SUBTEXT : ACCENT;
        CLBC_GL_API.fillCircle(ring, cx, cy, r + 4);        // 外圈（描边色）
        CLBC_GL_API.fillCircle(PANEL2, cx, cy, r + 1.5f);   // 内圈盖掉，剩 2.5px 环
        // 图标：播放中画两条竖线（暂停符号），暂停/停止画三角（播放符号）
        if (playing && !CLBC_Audio.isPaused()) {
            CLBC_GL_API.fillRect(ring, cx - 5, cy - 6, 3.5f, 12);
            CLBC_GL_API.fillRect(ring, cx + 1.5f, cy - 6, 3.5f, 12);
        } else {
            CLBC_GL_API.fillCircle(ring, cx - 1, cy, 6);
        }
        // 命中：圆心半径内
        float mx = CLBC_GL_API.mouseLogicalX(), my = CLBC_GL_API.mouseLogicalY();
        float dx = mx - cx, dy = my - cy;
        boolean inside = dx * dx + dy * dy <= (r + 3) * (r + 3);
        if (inside && CLBC_GL_API.mouseJustPressed) {
            if (!playing && !CLBC_Audio.isPaused()) randomNextTrack();  // 没在播也没暂停 → 起一首
            else CLBC_Audio.togglePause();
        }
        if (inside && CLBC_GL_API.mouseRightJustPressed) {
            randomNextTrack();   // 右键：重新随机
        }
    }

    /** 随机播放下一首（无曲目记日志），供按钮与圆圈复用 */
    private void randomNextTrack() {
        String pick = CLBC_Theme.randomTrack();
        if (!pick.isEmpty()) {
            CLBC_Theme.setBgm(pick);
            CLBC_Audio.play(pick, CLBC_Theme.bgmVolume);
        } else {
            CLBC_Util.log("[BGM] 还没有曲目，请先添加音乐文件夹");
        }
    }

    /** 曲目清单显示名：文件名（去掉路径）；与 bgmTracks 同序 */
    private String[] bgmTrackNames() {
        List<String> tracks = CLBC_Theme.bgmTracks;
        String[] out = new String[tracks.size()];
        for (int i = 0; i < tracks.size(); i++) {
            String t = tracks.get(i);
            int slash = Math.max(t.lastIndexOf('/'), t.lastIndexOf('\\'));
            out[i] = slash >= 0 && slash + 1 < t.length() ? t.substring(slash + 1) : t;
        }
        return out;
    }

    // ==========================================================
    // 通用
    // ==========================================================
    private void drawGeneral(float x, float y, float w) {
        ensurePageLogged(0, "通用");
        float colW = 100;

        CLBC_GL_API.drawText(CLBC_GL_API.uiFont, "语言", x, y + 14, TEXT);
        int newLang = CLBC_GL_Widgets.comboBox(5101, x + colW, y, 200, 28,
                new String[]{"中文", "English"}, langSel, PANEL, TEXT, PANEL2, TEXT);
        if (newLang != langSel) {
            langSel = newLang;
            String v = langSel == 0 ? "中文" : "English";
            CLBC_Util.saveSetting("ui.language", v);
            CLBC_Util.log("保存设置 ui.language=" + v);
        }

        float cy = y + 50;
        boolean newAuto = CLBC_GL_Widgets.checkBox(5302, x, cy, "启动时自动检查更新", autoUpdate, TEXT, PANEL);
        if (newAuto != autoUpdate) {
            autoUpdate = newAuto;
            CLBC_Util.saveSetting("app.autoUpdate", String.valueOf(autoUpdate));
            CLBC_Util.log("保存设置 app.autoUpdate=" + autoUpdate);
        }
    }

    // ==========================================================
    // 外观
    // ==========================================================
    private void drawAppearance(float x, float y, float w) {
        ensurePageLogged(1, "外观");
        float colW = 100;

        CLBC_GL_API.drawText(CLBC_GL_API.uiFont, "主题", x, y + 14, TEXT);
        int newTheme = CLBC_GL_Widgets.comboBox(5201, x + colW, y, 200, 28,
                new String[]{"深色", "浅色"}, themeSel, PANEL, TEXT, PANEL2, TEXT);
        if (newTheme != themeSel) {
            themeSel = newTheme;
            CLBC_Theme.setTheme(themeSel == 0);   // 落盘 + 全局刷新配色（代码区恒深色不变）
        }

        // ---- 自定义背景壁纸（只读取用户指定路径，程序不携带任何图片）----
        float wy = y + 148;
        CLBC_GL_API.drawText(CLBC_GL_API.uiFont, "背景壁纸", x, wy + 8, TEXT);
        String wp = CLBC_Theme.wallpaper;
        String wpShow = (wp == null || wp.isEmpty()) ? "（无，纯色底）" : wp;
        CLBC_GL_API.drawText(CLBC_GL_API.uiFont, wpShow, x + colW, wy + 8, SUBTEXT);
        if (CLBC_GL_Widgets.button(5204, x + colW + 210, wy, 72, 28, "选择", PANEL2, TEXT)) {
            String picked = CLBC_Util.file_picker_multi("选择背景壁纸",
                    new String[]{"*.png", "*.jpg", "*.jpeg", "*.bmp", "*.gif"}, "图片文件 (*.png;*.jpg;*.jpeg;*.bmp;*.gif)", false);
            if (picked != null) CLBC_Theme.setWallpaper(picked);
        }
        if (CLBC_GL_Widgets.button(5205, x + colW + 288, wy, 56, 28, "清除", PANEL2, TEXT)) {
            CLBC_Theme.setWallpaper("");
        }

        // ---- 自定义背景音乐（添加文件夹 → 提取曲目绝对路径存配置 → 随机播放）----
        float my = y + 186;
        CLBC_GL_API.drawText(CLBC_GL_API.uiFont, "背景音乐", x, my + 8, TEXT);
        int nDir = CLBC_Theme.bgmDirs.size(), nTrack = CLBC_Theme.bgmTracks.size();
        String bgShow = nDir == 0 ? "（未添加文件夹）" : (nDir + " 个文件夹 · " + nTrack + " 首");
        CLBC_GL_API.drawText(CLBC_GL_API.uiFont, bgShow, x + colW, my + 8, SUBTEXT);
        if (CLBC_GL_Widgets.button(5206, x + colW + 210, my, 72, 28, "添加文件夹", PANEL2, TEXT)) {
            String dir = CLBC_Util.folder_picker("选择背景音乐文件夹", false);
            if (dir != null) {
                int added = CLBC_Theme.addBgmDir(dir);
                // 添加后若当前没在播放，随机挑一首试播
                if (added > 0 && CLBC_Theme.bgmTracks.size() > 0) {
                    String pick = CLBC_Theme.randomTrack();
                    CLBC_Theme.setBgm(pick);
                    CLBC_Audio.play(pick, CLBC_Theme.bgmVolume);
                }
            }
        }
        if (CLBC_GL_Widgets.button(5207, x + colW + 288, my, 56, 28, "停止", PANEL2, TEXT)) {
            CLBC_Audio.stop();
        }
        if (CLBC_GL_Widgets.button(5209, x + colW + 210, my + 36, 72, 24, "随机下一首", PANEL2, TEXT)) {
            randomNextTrack();
        }
        if (CLBC_GL_Widgets.button(5210, x + colW + 288, my + 36, 56, 24, "清空", PANEL2, TEXT)) {
            CLBC_Audio.stop();
            CLBC_Theme.clearBgmDirs();
            CLBC_Theme.setBgm("");
        }
        // 音量滑条：拖动中只走草稿（不重播），松手才一次性应用到 Theme 与播放器。
        // 否则外部 ffplay 每次 setVolume 都会重新起播，拖动中会连续重播卡死。
        double newVol = CLBC_GL_Widgets.slider(5208, x + colW, my + 36, 100, 24, 0.0, 1.0, CLBC_Theme.bgmVolume,
                SUBTEXT, ACCENT, TEXT, (java.util.function.Function<Double, String>) v -> "音量 " + Math.round(v * 100) + "%");
        if (CLBC_GL_Widgets.sliderJustReleased(5208)) {
            float vol = (float) CLBC_GL_Widgets.sliderDraftValue(5208, CLBC_Theme.bgmVolume);
            CLBC_Theme.setBgmVolume(vol);
            CLBC_Audio.setVolume(vol);
        }

        // 曲目清单：列出全部已添加的可播放文件（按文件名显示，鼠标悬停/选中可看完整绝对路径）
        float ly = my + 72;
        CLBC_GL_API.drawText(CLBC_GL_API.uiFont, "曲目清单（全部可播放文件）", x, ly, SUBTEXT);
        String[] names = bgmTrackNames();
        int picked = CLBC_GL_Widgets.list(5211, x, ly + 20, Math.max(colW + 344, 520), 132,
                names, bgmSelected < names.length ? bgmSelected : 0, PANEL2, TEXT, ACCENT, WHITE);
        if (picked >= 0 && picked < names.length && picked != bgmSelected) {
            bgmSelected = picked;
            CLBC_Util.log("[BGM] 曲目清单选中: " + (picked < CLBC_Theme.bgmTracks.size() ? CLBC_Theme.bgmTracks.get(picked) : ""));
        }
        // 双击清单当前项立即播放（用单击选中态 + 双击时间窗近似：这里用 list 选中即试听交"随机"按钮，保持简单）

        float sy = y + 50;
        CLBC_GL_API.drawText(CLBC_GL_API.uiFont, "UI 缩放", x, sy + 14, TEXT);
        double newScale = CLBC_GL_Widgets.slider(5202, x + colW, sy, 200, 28, 0.5, 2.0, uiScale,
                SUBTEXT, ACCENT, TEXT, (Function<Double, String>) v -> String.format("%.2f", v));
        // 【#254 真凶：缩放反馈回路】拖动中若实时 setUserUiScale → refreshProjection 改虚拟视口
        // windowWidth/Height → 设置面板（含本滑条自己）按虚拟坐标整页重排 → 滑条 x/w 一变，
        // 鼠标逻辑坐标对不上 → 圆点乱跳、拉不动，还每帧刷日志。
        // 正解：拖动中只记草稿值（slider 圆点+数字用草稿显示，跟手正常），绝不改全局缩放；
        // 松手那一帧才一次性 setUserUiScale + 写盘，布局只重排一次。
        if (Math.abs(newScale - uiScale) > 1e-6) uiScale = newScale;   // 草稿值，仅作下次滑条起点
        boolean scaleReleased = CLBC_GL_Widgets.sliderJustReleased(5202);
        if (scaleReleased) {
            uiScale = CLBC_GL_Widgets.sliderDraftValue(5202, uiScale);
            CLBC_GL_API.setUserUiScale(uiScale);
            CLBC_Util.saveSetting("ui.scale", String.format("%.2f", uiScale));
            CLBC_Util.log("保存设置 ui.scale=" + String.format("%.2f", uiScale) + "（松手后应用并写入）");
        }

        // 黑边开关：勾选框表示「带系统边框」（与 borderless 取反），切换时即时改窗口属性并落盘。
        float by = y + 100;
        boolean wantBorder = CLBC_GL_Widgets.checkBox(5203, x, by, "系统窗口边框（取消勾选为无边框）",
                !borderless, TEXT, PANEL);
        if (wantBorder != !borderless) {
            borderless = !wantBorder;
            CLBC_Util.saveSetting("ui.borderless", String.valueOf(borderless));
            CLBC_GL_API.setWindowDecorated(wantBorder);
            CLBC_Util.log("保存设置 ui.borderless=" + borderless);
        }
    }

    // ==========================================================
    // 编辑器
    // ==========================================================
    private void drawEditor(float x, float y, float w) {
        ensurePageLogged(2, "编辑器");
        float colW = 100;

        CLBC_GL_API.drawText(CLBC_GL_API.uiFont, "字体大小", x, y + 14, TEXT);
        double newFont = CLBC_GL_Widgets.slider(5301, x + colW, y, 200, 28, 8, 32, fontSize,
                SUBTEXT, ACCENT, TEXT, (Function<Double, String>) v -> String.format("%.0f", v));
        // 【T14】slider 返回连续浮点（如 12.919999957084656）。拖动中只更新内部值用于圆点/文字跟手，
        // 松手才取整写盘 + 重建字体（重建图集开销大，不能每帧做）。写盘/显示/日志统一为整数。
        if (Math.abs(newFont - fontSize) > 1e-6) fontSize = newFont;
        if (CLBC_GL_Widgets.sliderJustReleased(5301)) {
            fontSize = Math.round(fontSize);
            CLBC_Util.saveSetting("editor.fontSize", String.format("%.0f", fontSize));
            CLBC_Util.log("保存设置 editor.fontSize=" + String.format("%.0f", fontSize));
            rebuildUiFont((float) fontSize);
        }

        float cy = y + 50;
        boolean newAuto = CLBC_GL_Widgets.checkBox(5303, x, cy, "自动保存", autoSave, TEXT, PANEL);
        if (newAuto != autoSave) {
            autoSave = newAuto;
            CLBC_Util.saveSetting("editor.autoSave", String.valueOf(autoSave));
            CLBC_Util.log("保存设置 editor.autoSave=" + autoSave);
        }
    }

    // ==========================================================
    // 高级
    // ==========================================================
    private void drawAdvanced(float x, float y, float w) {
        ensurePageLogged(3, "高级");
        CLBC_GL_API.drawText(CLBC_GL_API.uiFont, "缓存目录: " + cacheDirLabel(), x, y - 2, SUBTEXT);
        // 规则：删除操作必须先二次确认（右键菜单），避免误清空
        if (CLBC_GL_Widgets.button(5401, x, y + 16, 140, 32, "清理缓存", PANEL, TEXT)) {
            CLBC_GL_Widgets.openContextMenu(5401, x, y + 50,
                    new String[]{"确认清理（删除 cache 目录内容）", "取消"});
        }
        if (CLBC_GL_Widgets.activePopupId() == 5401) {
            int pick = CLBC_GL_Widgets.drawContextMenu(CLBC_GL_API.rgba(40, 40, 40, 250), TEXT, ACCENT);
            if (pick == 0) clearCache();
        }
    }

    private String cacheDirLabel() {
        Path d = CLBC_AppContext.cacheDir;
        return d != null ? d.toString() : "(未初始化)";
    }

    /** 仅清理应用自有缓存目录（反编译解包临时产物）；保留根目录本身，删除前已由确认菜单二次确认。 */
    private void clearCache() {
        Path d = CLBC_AppContext.cacheDir;
        if (d == null || !Files.isDirectory(d)) {
            CLBC_Util.log("清理缓存：缓存目录不存在，无需清理");
            return;
        }
        int removed = 0;
        try (Stream<Path> s = Files.walk(d)) {
            List<Path> all = s.collect(Collectors.toList());
            // 倒序删除（先删最深文件/子目录），最后跳过根目录本身
            for (int i = all.size() - 1; i >= 0; i--) {
                Path p = all.get(i);
                if (p.equals(d)) continue;
                try {
                    Files.deleteIfExists(p);
                    removed++;
                } catch (IOException ex) {
                    CLBC_Util.log("清理缓存：删除失败 " + p + " : " + ex.getMessage());
                }
            }
        } catch (IOException ex) {
            CLBC_Util.log("清理缓存：遍历失败 " + ex.getMessage());
            return;
        }
        CLBC_Util.log("清理缓存完成，共移除 " + removed + " 项");
    }

    /** 分页首次进入时记录一次日志（即时模式每帧重绘，避免刷屏） */
    private void ensurePageLogged(int idx, String name) {
        if (!pageLogged[idx]) {
            CLBC_Util.log("渲染" + name + "设置面板");
            pageLogged[idx] = true;
        }
    }
}
