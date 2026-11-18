package clbc.ui;

import clbc.core.CLBC_AppContext;
import clbc.gl.CLBC_GL_API;
import clbc.util.CLBC_Util;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * 主题与个性化中心（单一真源）。
 *
 * 职责：
 *   1) 深/浅两套界面配色，读取配置 ui.theme 后由 {@link #apply()} 下发到各绘制类；
 *      刻意保持"代码编辑器恒深色"（语法高亮可读），主题只换界面壳与画布/网格。
 *   2) 自定义背景壁纸路径（ui.wallpaper）——仅存用户指定的本地路径，程序不携带任何图片。
 *   3) 自定义背景音乐路径（ui.bgm）——仅存用户指定的本地路径，程序不携带任何音频。
 *
 * 约定：所有取色方持有本类的实例字段拷贝（经 {@link #syncInto(Colors)} 刷新），
 * 避免把 CLBC_Renderer / SettingsPanel 的 static final 常量拆得七零八落。
 */
public final class CLBC_Theme {

    private CLBC_Theme() {}

    /** 界面配色组：绘制类持有此对象的一份实例，主题变更时字段被就地刷新 */
    public static final class Colors {
        public long PANEL, PANEL2, TEXT, SUBTEXT, ACCENT, GREEN, RED;
        public long WHITE, YELLOW, PURPLE, SEP, BRAND;
        public long CANVAS_BG, GRID_DOT, WORKSPACE;
        public long SET_PANEL, SET_PANEL2, SET_TEXT, SET_SUBTEXT;
    }

    /** 当前配色（全局共享实例，绘制类在主题变更后重新 syncInto） */
    private static final Colors CURRENT = new Colors();

    // 静态兜底：任何入口（含 headless 诊断）未调 init() 时也有有效浅色配色，不会取到全 0 黑值
    static { apply(); }

    public static Colors current() { return CURRENT; }

    /** null=尚未初始化；深色=true / 浅色=false */
    private static Boolean dark = null;
    /** 主题版本号：每次 apply() 自增，绘制类据此判断是否需要重新取色（避免每帧全量刷新） */
    private static int version = 0;
    public static int version() { return version; }
    /** true=深色主题（设置面板自身用深色壳），与 dark 一致 */
    public static boolean isDark() { return dark != null && dark; }

    /** 主题名（"深色"/"浅色"） */
    public static String themeName() { return isDark() ? "深色" : "浅色"; }

    /** 背景壁纸路径（可能为空串） */
    public static String wallpaper = "";
    /** 背景音乐路径（单曲，兼容旧配置；新方案优先用 bgmTracks） */
    public static String bgm = "";
    /** 用户添加的音乐文件夹列表（绝对路径，'|' 分隔存配置） */
    public static final List<String> bgmDirs = new ArrayList<>();
    /** 从上述文件夹提取出的可播放音频绝对路径列表（'|' 分隔存配置） */
    public static final List<String> bgmTracks = new ArrayList<>();
    /** 背景音乐音量 0.0~1.0 */
    public static float bgmVolume = 0.6f;

    /** 支持的音频/视频扩展名（视频用 ffplay -vn 只取音轨） */
    private static final String[] MEDIA_EXT = {
            "wav", "au", "aif", "aiff", "mp3", "ogg", "oga", "flac", "m4a", "aac",
            "wma", "opus", "wv", "mp2", "mka", "mp4", "mkv", "avi", "mov", "flv",
            "webm", "wmv", "mpeg", "mpg", "m4v", "3gp", "ts", "ogv", "vob"
    };

    private static List<String> splitPaths(String s) {
        List<String> out = new ArrayList<>();
        if (s == null || s.trim().isEmpty()) return out;
        for (String part : s.split("\\|")) {
            String t = part == null ? "" : part.trim();
            if (!t.isEmpty() && !out.contains(t)) out.add(t);
        }
        return out;
    }

    private static String joinPaths(List<String> list) { return String.join("|", list); }

    /** 从配置读取三项个性化设置并应用（启动时调一次） */
    public static void init() {
        try {
            Properties p = (CLBC_AppContext.configFile == null)
                    ? new Properties() : CLBC_Util.loadProperties(CLBC_AppContext.configFile);
            String t = p.getProperty("ui.theme", "深色");
            dark = !"浅色".equals(t);           // 默认深色，兼容历史配置
            wallpaper = nullToEmpty(p.getProperty("ui.wallpaper", ""));
            bgm = nullToEmpty(p.getProperty("ui.bgm", ""));
            bgmDirs.clear();   bgmDirs.addAll(splitPaths(p.getProperty("ui.bgm.dirs", "")));
            bgmTracks.clear(); bgmTracks.addAll(splitPaths(p.getProperty("ui.bgm.tracks", "")));
            try { bgmVolume = Float.parseFloat(p.getProperty("ui.bgm.volume", "0.6")); }
            catch (Exception ignore) { bgmVolume = 0.6f; }
            if (bgmVolume < 0f) bgmVolume = 0f;
            if (bgmVolume > 1f) bgmVolume = 1f;
        } catch (Throwable t) {
            dark = true; wallpaper = ""; bgm = ""; bgmDirs.clear(); bgmTracks.clear(); bgmVolume = 0.6f;
            CLBC_Util.log("[Theme] 读取个性化配置失败，使用默认深色主题: " + t.getMessage());
        }
        apply();
        CLBC_Util.log("[Theme] 主题=" + themeName() + " 壁纸=" + (wallpaper.isEmpty() ? "无" : wallpaper)
                + " 音乐文件夹=" + bgmDirs.size() + " 曲目=" + bgmTracks.size()
                + " 单曲=" + (bgm.isEmpty() ? "无" : bgm));
    }

    private static String nullToEmpty(String s) { return s == null ? "" : s.trim(); }

    /** 切换主题（设置面板调用）：落盘 + 重新下发配色 */
    public static void setTheme(boolean isDark) {
        dark = isDark;
        CLBC_Util.saveSetting("ui.theme", themeName());
        CLBC_Util.log("保存设置 ui.theme=" + themeName());
        apply();
    }

    /** 设置壁纸路径（空串=清除）；落盘 + 通知渲染层释放旧纹理 */
    public static void setWallpaper(String path) {
        wallpaper = nullToEmpty(path);
        CLBC_Util.saveSetting("ui.wallpaper", wallpaper);
        CLBC_Util.log("保存设置 ui.wallpaper=" + (wallpaper.isEmpty() ? "（清除）" : wallpaper));
    }

    /** 设置背景音乐路径（空串=停止）；落盘 */
    public static void setBgm(String path) {
        bgm = nullToEmpty(path);
        CLBC_Util.saveSetting("ui.bgm", bgm);
        CLBC_Util.log("保存设置 ui.bgm=" + (bgm.isEmpty() ? "（清除）" : bgm));
    }

    public static void setBgmVolume(float v) {
        bgmVolume = Math.max(0f, Math.min(1f, v));
        CLBC_Util.saveSetting("ui.bgm.volume", String.format("%.2f", bgmVolume));
        CLBC_Util.log("保存设置 ui.bgm.volume=" + bgmVolume);
    }

    /** 判断文件名是否为可播放媒体（按 MEDIA_EXT 白名单） */
    private static boolean isMediaFile(String name) {
        if (name == null) return false;
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return false;
        String ext = name.substring(dot + 1).toLowerCase();
        for (String e : MEDIA_EXT) if (e.equals(ext)) return true;
        return false;
    }

    /** 添加一个音乐文件夹：扫描其下可播放媒体的【绝对路径】存入 bgmTracks 并落盘，返回新增曲目数。
     *  同时记录文件夹到 bgmDirs（去重）。不递归子目录（用户要"添加的文件夹里"，保持直觉）。 */
    public static int addBgmDir(String folder) {
        if (folder == null || folder.trim().isEmpty()) return 0;
        File dir = new File(folder.trim());
        if (!dir.isDirectory()) {
            CLBC_Util.log("[Theme] 音乐文件夹无效: " + folder);
            return 0;
        }
        String absDir = dir.getAbsolutePath();
        if (!bgmDirs.contains(absDir)) bgmDirs.add(absDir);
        int added = 0;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f == null || !f.isFile() || !isMediaFile(f.getName())) continue;
                String abs = f.getAbsolutePath();
                if (!bgmTracks.contains(abs)) { bgmTracks.add(abs); added++; }
            }
        }
        saveBgmLists();
        CLBC_Util.log("[Theme] 添加音乐文件夹 " + absDir + "：新增 " + added
                + " 首，累计 " + bgmTracks.size() + " 首");
        return added;
    }

    /** 从已添加曲目里移除某个文件夹的曲目（保留其它文件夹），并落盘 */
    public static void removeBgmDir(String folder) {
        if (folder == null) return;
        String absDir = new File(folder.trim()).getAbsolutePath();
        bgmDirs.remove(absDir);
        bgmTracks.removeIf(t -> t != null && t.startsWith(absDir + File.separator));
        saveBgmLists();
        CLBC_Util.log("[Theme] 移除音乐文件夹 " + absDir + "，剩余 " + bgmTracks.size() + " 首");
    }

    /** 清空所有音乐文件夹与曲目（落盘） */
    public static void clearBgmDirs() {
        bgmDirs.clear(); bgmTracks.clear();
        saveBgmLists();
        CLBC_Util.log("[Theme] 已清空音乐文件夹与曲目");
    }

    private static void saveBgmLists() {
        CLBC_Util.saveSetting("ui.bgm.dirs", joinPaths(bgmDirs));
        CLBC_Util.saveSetting("ui.bgm.tracks", joinPaths(bgmTracks));
    }

    /** 从已添加曲目里随机取一首（无曲目返回空串） */
    public static String randomTrack() {
        if (bgmTracks.isEmpty()) return "";
        return bgmTracks.get((int) (Math.random() * bgmTracks.size()));
    }

    /** 把当前配色刷入目标 Colors 对象 */
    public static void syncInto(Colors c) {
        c.PANEL = CURRENT.PANEL; c.PANEL2 = CURRENT.PANEL2;
        c.TEXT = CURRENT.TEXT; c.SUBTEXT = CURRENT.SUBTEXT;
        c.ACCENT = CURRENT.ACCENT; c.GREEN = CURRENT.GREEN; c.RED = CURRENT.RED;
        c.WHITE = CURRENT.WHITE; c.YELLOW = CURRENT.YELLOW; c.PURPLE = CURRENT.PURPLE;
        c.SEP = CURRENT.SEP; c.BRAND = CURRENT.BRAND;
        c.CANVAS_BG = CURRENT.CANVAS_BG; c.GRID_DOT = CURRENT.GRID_DOT; c.WORKSPACE = CURRENT.WORKSPACE;
        c.SET_PANEL = CURRENT.SET_PANEL; c.SET_PANEL2 = CURRENT.SET_PANEL2;
        c.SET_TEXT = CURRENT.SET_TEXT; c.SET_SUBTEXT = CURRENT.SET_SUBTEXT;
    }

    /** 按当前主题重算 CURRENT（代码区/语法色不在此列，恒深色） */
    public static void apply() {
        boolean d = isDark();
        Colors c = CURRENT;
        if (d) {
            // 深色主题：界面壳用中性深灰，画布更深，网格点提亮到可见但不抢眼
            c.PANEL   = hex("#252526");  c.PANEL2  = hex("#2D2D30");
            c.TEXT    = hex("#E8E8E8");  c.SUBTEXT = hex("#9A9A9A");
            c.ACCENT  = hex("#4C97FF");  c.GREEN   = hex("#3FBF68"); c.RED = hex("#F05050");
            c.WHITE   = hex("#1E1E1E");  // 输入框底 / 反白文字位（深色下为深底）
            c.YELLOW  = hex("#FFAB19");  c.PURPLE  = hex("#9966FF");
            c.SEP     = hex("#3E3E42");  c.BRAND   = hex("#FF4C4C");
            c.CANVAS_BG = hex("#1E1E1E"); c.GRID_DOT = hex("#3A3A3A"); c.WORKSPACE = hex("#171717");
            c.SET_PANEL = hex("#252526"); c.SET_PANEL2 = hex("#2D2D30");
            c.SET_TEXT = hex("#E8E8E8");  c.SET_SUBTEXT = hex("#9A9A9A");
        } else {
            // 浅色主题：即原 #165 TurboWarp 观感（保持与现状逐像素一致）
            c.PANEL   = hex("#FFFFFF");  c.PANEL2  = hex("#EEF2F8");
            c.TEXT    = hex("#2E3440");  c.SUBTEXT = hex("#8A94A6");
            c.ACCENT  = hex("#4C97FF");  c.GREEN   = hex("#3FBF68"); c.RED = hex("#F05050");
            c.WHITE   = hex("#FFFFFF");  c.YELLOW  = hex("#FFAB19"); c.PURPLE = hex("#9966FF");
            c.SEP     = hex("#DCE3EC");  c.BRAND   = hex("#FF4C4C");
            c.CANVAS_BG = hex("#FFFFFF"); c.GRID_DOT = hex("#ECECEC"); c.WORKSPACE = hex("#F9F9F9");
            c.SET_PANEL = hex("#FFFFFF"); c.SET_PANEL2 = hex("#EEF2F8");
            c.SET_TEXT = hex("#2E3440");  c.SET_SUBTEXT = hex("#8A94A6");
        }
        CLBC_GL_API.setGridDotColor(c.GRID_DOT);
        version++;
        CLBC_Util.log("[Theme] 配色已应用: " + themeName());
    }

    private static long hex(String s) { return CLBC_GL_API.hex(s); }
}
