package clbc.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import clbc.core.CLBC_AppContext;
import clbc.blocks.BlocksToCode;
import clbc.blocks.CodeToBlocks;
import clbc.gl.CLBC_GL_API;
import clbc.gl.CLBC_GL_Font;
import clbc.gl.CLBC_GL_Widgets;
import clbc.ui.CLBC_Block;
import clbc.util.CLBC_Util;

/**
 * 自绘主界面（OpenGL 即时模式，无 JavaFX）。
 * 以状态机方式切换屏幕：主菜单 / 新建项目 / 历史项目 / 设置 / 项目视图。
 * 每个屏幕在 {@link #frame()} 中按当前 {@link #screen} 自绘，并把点击等事件交给 {@link CLBC_GL_Widgets}。
 */
public class CLBC_Renderer {

    // ---- 颜色常量（对应原 JavaFX 的 CSS 色值）----
    // 主题色系统：界面壳配色由 CLBC_Theme 统一供给；字段非常量，主题切换时经 syncTheme 就地刷新。
    // 代码编辑器刻意恒深色（CODE_BG/CODE_NUM + 语法色），与主题无关——语法高亮在深底上最可读。
    private long BG, PANEL, PANEL2, TEXT, SUBTEXT, ACCENT, GREEN, RED;
    private long WHITE, YELLOW, PURPLE, SEP, BRAND;
    private static final long CODE_BG  = CLBC_GL_API.hex("#282C34");   // 代码编辑器保留深色（语法高亮可读）
    private static final long CODE_NUM = CLBC_GL_API.hex("#7C8698");

    /** 壁纸纹理缓存（路径 / 内容变化才重新上传，避免每帧 GL 纹理泄漏） */
    private int wallpaperTex = 0;
    private String wallpaperStamp = "";

    /** 把当前主题配色刷入本实例的颜色字段（构造 + 主题变更后调用） */
    private void syncTheme() {
        CLBC_Theme.Colors c = CLBC_Theme.current();
        PANEL = c.PANEL; PANEL2 = c.PANEL2; TEXT = c.TEXT; SUBTEXT = c.SUBTEXT;
        ACCENT = c.ACCENT; GREEN = c.GREEN; RED = c.RED; WHITE = c.WHITE;
        YELLOW = c.YELLOW; PURPLE = c.PURPLE; SEP = c.SEP; BRAND = c.BRAND;
        BG = c.PANEL2;   // BG 原为未使用的死常量，跟随次级面板色保持语义一致
    }

    /** 绘制用户自定义背景壁纸：拉伸铺满画布区；路径/文件戳变化才重载纹理，失败静默回退纯色底。 */
    private void drawWallpaper(float x, float y, float w, float h) {
        String path = CLBC_Theme.wallpaper;
        if (path == null || path.isEmpty()) { wallpaperStamp = ""; return; }
        java.io.File f = new java.io.File(path);
        if (!f.isFile()) {
            if (!path.equals(wallpaperStamp)) {
                CLBC_Util.log("[Theme] 壁纸文件不存在，暂用纯色底: " + path);
                wallpaperStamp = path;   // 记一次，避免每帧刷日志
            }
            return;
        }
        String stamp = path + "|" + f.lastModified() + "|" + f.length();
        if (!stamp.equals(wallpaperStamp)) {
            // 变化才重载：loadTextureFromFileCached 内部按 lastModified:length 缓存并按路径复用纹理 id
            wallpaperTex = CLBC_GL_API.loadTextureFromFileCached(path);
            wallpaperStamp = stamp;
            CLBC_Util.log("[Theme] 壁纸已加载: " + path + (wallpaperTex == 0 ? "（加载失败，用纯色底）" : ""));
        }
        if (wallpaperTex != 0) CLBC_GL_API.drawImage(wallpaperTex, x, y, w, h);
    }

    /** Java 关键字（代码面板轻量语法着色用，仅作视觉区分，不影响编译） */
    private static final java.util.Set<String> JAVA_KW = new java.util.HashSet<>(java.util.Arrays.asList(
        "public","private","protected","static","final","class","interface","void","int","long","double",
        "float","boolean","char","byte","short","String","if","else","for","while","do","switch","case",
        "return","new","import","package","extends","implements","this","super","try","catch","finally",
        "throw","throws","abstract","synchronized","enum","var","null","true","false","break","continue"));

    /** 屏幕枚举 */
    public enum Screen { MENU, NEW_PROJECT, HISTORY, SETTINGS, PROJECT, DECOMPILE }

    private Screen screen = Screen.MENU;

    // ---- 项目视图共享状态 ----
    private final List<String> loadedProjects = new ArrayList<>();
    private String currentProject = null;
    private final Map<String, List<CLBC_RunInstance>> runHistory = new HashMap<>();
    private CLBC_RunInstance currentRun = null;
    private final CLBC_CanvasHost canvas = new CLBC_CanvasHost();   // 画布（积木数据模型）
    private String codeText = "";                                    // 右侧代码面板内容
    private String codeFileName = "Main.java";                      // 右侧代码面板当前显示的文件名（动态）

    // ===== A-2：代码面板可编辑 + 停顿回灌防回环 =====
    /** 代码编辑器专用焦点 id：取负数，避开所有 textField / 画布输入窗口（editId() 均为非负）的正数 id */
    private static final int CODE_EDITOR_ID = -2;
    private static final float CODE_LINE_H = 22f;
    private static final float CODE_GUTTER = 44f;
    private static final long CODE_DEBOUNCE_MS = 350;      // 输入停顿后回灌积木，避免每帧重建画布
    private int codeCaret = 0;                             // 全局字符偏移（0..codeText.length()）
    private long lastCodeEditMs = 0;                       // 上次用户改动时间（debounce 基准）
    private boolean codeDirty = false;                     // 有未回灌的用户改动
    private boolean suppressCanvasSync = false;            // 回灌积木期间抑制 syncCodeFromCanvas，防回环

    // ===== 收口4：代码编辑器轻量文本撤销／重做栈（每次编辑操作快照，限帧数／限总字节防吃内存）=====
    private static final int MAX_CODE_UNDO_FRAMES = 64;
    private static final long MAX_CODE_UNDO_BYTES = 2L * 1024 * 1024;   // 快照总字节粗估上限（UTF-16 每 char 2 字节）
    private final java.util.Deque<String> codeUndo = new java.util.ArrayDeque<>();
    private final java.util.Deque<String> codeRedo = new java.util.ArrayDeque<>();

    // ---- 新建项目字段 ----
    private String newProjName = "untitled";
    private String newProjPath = System.getProperty("user.home", ".");
    /** 【T7】新建项目时选择的语言：0=Java 1=Python。语言入口从顶栏移到创建流程。 */
    private int newProjLang = 0;

    // ---- 历史选择 ----
    private int historySel = -1;

    // ---- 项目视图顶栏下拉选择 ----
    private int projectComboSel = 0;
    private int runComboSel = -1;
    private int langComboSel = 0;
    private int bottomTab = 1; // 0=属性 1=输出 2=问题

    // ===== C：分区分隔条（可拖拽拉升，初值沿用原固定布局）=====
    private float leftPanelW = 230f;     // 左侧栏（工具箱 + 源码文件列表）宽度
    private float rightPanelW = 380f;    // 右侧代码面板宽度
    private float bottomPanelH = 170f;   // 底部分页高度
    private static final float LEFT_MIN = 140f, LEFT_MAX = 460f;
    private static final float RIGHT_MIN = 220f, RIGHT_MAX = 640f;
    private static final float BOTTOM_MIN = 90f, BOTTOM_MAX = 360f;
    private static final float MIN_CANVAS_W = 200f;   // 中间画布保底宽度，防止左右分隔条把画布挤没
    private static final float DIV_HALF = 4f;         // 分隔条命中半宽（像素）
    /** 拖拽中的分隔条：0 无；1 左(左侧栏↔画布)；2 右(画布↔代码)；3 下(画布↔底栏)；
     *  4 左栏内部(积木预选区↔源码文件选择区) */
    private int resizeMode = 0;
    /** 左栏里积木预选区（工具箱）占可用高度的比例：可拖内部横向分隔条调整 */
    private float toolboxRatio = 0.6f;
    private static final float TOOLBOX_MIN_RATIO = 0.2f, TOOLBOX_MAX_RATIO = 0.85f;

    // ===== B：当前工程源码文件列表（双击 .jav 在当前界面反编译）=====
    private final List<String> srcFiles = new ArrayList<>();    // 绝对路径（双击打开用）
    private final List<String> srcLabels = new ArrayList<>();   // 相对路径短名（列表显示用，与 srcFiles 一一对应）
    private String srcFilesProject = null;   // 缓存对应工程路径，切换工程时刷新清单
    private int srcListSel = -1;

    private final SettingsPanel settings = new SettingsPanel();
    private final CLBC_Toolbox toolbox = new CLBC_Toolbox(canvas);
    /** 反编译面板：源码转积木直接复用同一个画布实例 */
    private final CLBC_DecompilePanel decompilePanel = new CLBC_DecompilePanel(canvas, src -> {
        codeText = src;
        // A-2：程序化设置文本不算用户编辑，清掉待回灌状态，避免覆盖刚送入的源码
        codeDirty = false;
        codeCaret = 0;
        screen = Screen.PROJECT;
        CLBC_Util.log("反编译源码已送入项目视图代码面板");
    });

    private int themeVersion = -1;   // 上次已刷入的主题版本

    /** 构造：挂接画布结构变更回调，积木变动时反向生成代码刷新右侧面板 */
    public CLBC_Renderer() {
        if (CLBC_Theme.version() == 0) CLBC_Theme.init();   // 主题尚未初始化则读取配置
        syncTheme();
        themeVersion = CLBC_Theme.version();
        canvas.onStructureChanged = this::syncCodeFromCanvas;
    }

    /** 画布结构变更 → 用 BlocksToCode 反向生成代码刷新右侧面板（双向同步） */
    private void syncCodeFromCanvas() {
        // A-2 防回环：① 回灌积木触发的结构变更不覆盖用户文本；② 用户正在编辑代码面板期间一律不反向覆盖，
        // 避免"代码→积木→再生成代码"覆盖正在编辑的文本、吞输入、跳光标。
        if (suppressCanvasSync) return;
        if (CLBC_GL_API.focusedTextField == CODE_EDITOR_ID) return;
        try {
            String gen = BlocksToCode.fromCanvas(canvas.getBlocks(), canvas.getCommentData());
            codeText = gen;
            CLBC_Util.logFine("画布变更 → 代码面板已重新生成（" + gen.split("\n", -1).length + " 行）");
        } catch (Exception e) {
            CLBC_Util.log("!!!! 代码面板重新生成失败: " + e.getMessage());
        }
    }

    /** 每帧入口：清背景已由 CLBC.java 的 beginFrame 完成，这里只画当前屏幕 */
    public void frame() {
        if (themeVersion != CLBC_Theme.version()) {   // 设置面板切换主题后，下一帧刷新配色
            syncTheme();
            themeVersion = CLBC_Theme.version();
        }
        switch (screen) {
            case MENU -> drawMainMenu();
            case NEW_PROJECT -> drawNewProject();
            case HISTORY -> drawHistory();
            case SETTINGS -> drawSettings();
            case PROJECT -> {
                if (toolbox.extensionsOpen) {
                    // 扩展库弹层打开期间，底层编辑器只读：临时屏蔽鼠标边沿与滚轮，
                    // 让所有点击只落到弹层上，避免同时触发画布拖拽 / 代码面板输入。
                    boolean jp = CLBC_GL_API.mouseJustPressed, md = CLBC_GL_API.mouseDown,
                            jr = CLBC_GL_API.mouseJustReleased;
                    float sd = CLBC_GL_API.scrollDelta;
                    int ff = CLBC_GL_API.focusedTextField;
                    CLBC_GL_API.mouseJustPressed = false;
                    CLBC_GL_API.mouseDown = false;
                    CLBC_GL_API.mouseJustReleased = false;
                    CLBC_GL_API.scrollDelta = 0;
                    drawProjectView();
                    CLBC_GL_API.mouseJustPressed = jp;
                    CLBC_GL_API.mouseDown = md;
                    CLBC_GL_API.mouseJustReleased = jr;
                    CLBC_GL_API.scrollDelta = sd;
                    CLBC_GL_API.focusedTextField = ff;
                    toolbox.drawExtensionPopup(CLBC_GL_API.getWindowWidth(), CLBC_GL_API.getWindowHeight());
                } else {
                    drawProjectView();
                }
            }
            case DECOMPILE -> drawDecompile();
        }
        CLBC_GL_Widgets.drawComboPopups();   // 下拉面板最后画，盖住同帧后续控件文字
        CLBC_GL_Widgets.endFrameWidgets();   // 清理本帧滑块「刚松手」边沿（T3 提交信号）
    }

    // ==========================================================
    // 屏幕切换（对应原 showXxx）
    // ==========================================================
    public void showMainMenu() { screen = Screen.MENU; }
    public void showNewProject() { screen = Screen.NEW_PROJECT; }
    public void showHistory() { screen = Screen.HISTORY; historySel = -1; }
    public void showSettings() { screen = Screen.SETTINGS; }
    public void showDecompile() { screen = Screen.DECOMPILE; }
    public void showProject(String path) {
        if (!loadedProjects.contains(path)) loadedProjects.add(path);
        currentProject = path;
        canvas.setAutosavePath(Path.of(path, "project.clbc").toString());   // 设好保存路径，Ctrl+S / 保存按钮即生效
        canvas.clearDirty();                        // 退出询问：打开/切换工程＝干净基线
        runHistory.computeIfAbsent(path, k -> new ArrayList<>());
        refreshCodeText();
        loadProjectSource();
        langComboSel = 0; // 默认 Java
        screen = Screen.PROJECT;
        CLBC_Util.log("显示项目: " + path);
    }
    private void refreshCodeText() {
        codeText = "";
        codeFileName = "Main.java";
        // A-2：切换工程 = 结束编辑会话，重置光标与待回灌状态，避免旧编辑回灌覆盖新工程
        codeDirty = false;
        codeCaret = 0;
        // 收口4：切换工程结束编辑会话，撤销／重做栈一并清空，避免跨工程撤销
        codeUndo.clear();
        codeRedo.clear();
        try {
            Path mainJava = Path.of(currentProject, "src", "Main.java");
            if (Files.exists(mainJava)) {
                // 收口4：读入前做 size 检查，超 256MB 记日志并安全拒绝，别 OOM
                long sz = Files.size(mainJava);
                if (sz > CLBC_Util.MAX_INPUT_FILE_BYTES) {
                    CLBC_Util.log("!!!! 源 Main.java 过大，已拒绝读取: " + mainJava + " (" + sz + " 字节)");
                    return;
                }
                codeText = Files.readString(mainJava, StandardCharsets.UTF_8);
                codeFileName = "Main.java";
                CLBC_Util.log("代码面板已读取 src/Main.java");
            } else {
                CLBC_Util.log("src/Main.java 不存在，将交由 loadProjectSource 定位入口源");
            }
        } catch (Exception e) {
            CLBC_Util.log("!!!!读 Main.java 失败: " + e.getMessage());
        }
    }

    /** 定位工程入口源文件：优先 src/Main.java；否则扫描首个含 main 方法的 .java；再否则首个 .java */
    private String findEntrySource(String projectPath) {
        if (projectPath == null) return null;
        Path base = Path.of(projectPath);
        List<Path> roots = new ArrayList<>();
        for (String d : new String[]{"src", "src/main/java"}) {
            Path p = base.resolve(d);
            if (Files.isDirectory(p)) roots.add(p);
        }
        List<Path> all = new ArrayList<>();
        try {
            if (!roots.isEmpty()) {
                for (Path r : roots) Files.walk(r).filter(f -> f.toString().endsWith(".java")).forEach(all::add);
            } else {
                try (var s = Files.walk(base)) { s.filter(f -> f.toString().endsWith(".java")).limit(200).forEach(all::add); }
            }
        } catch (Exception e) {
            CLBC_Util.log("!!!! 扫描工程源文件失败: " + e.getMessage());
            return null;
        }
        if (all.isEmpty()) { CLBC_Util.log("工程内未发现任何 .java 源文件"); return null; }
        for (Path f : all) {
            try {
                // 收口4：读入前做 size 检查，超 256MB 记日志并跳过该文件，别 OOM
                long sz = Files.size(f);
                if (sz > CLBC_Util.MAX_INPUT_FILE_BYTES) {
                    CLBC_Util.log("!!!! 源文件过大，已跳过: " + f + " (" + sz + " 字节)");
                    continue;
                }
                if (Files.readString(f, StandardCharsets.UTF_8).contains("public static void main")) return f.toString();
            } catch (Exception ignored) { }
        }
        return all.get(0).toString();
    }

    /** 加载入口源文件：写入代码面板，并用 CodeToBlocks 反向解析为积木导入画布 */
    private void loadProjectSource() {
        String src = findEntrySource(currentProject);
        if (src == null) {
            CLBC_Util.log("未找到入口源文件，画布保持空（可手动从工具箱添加积木）");
            return;
        }
        try {
            // 收口4：读入前做 size 检查，超 256MB 记日志并安全拒绝，别 OOM
            Path srcPath = Path.of(src);
            long sz = Files.size(srcPath);
            if (sz > CLBC_Util.MAX_INPUT_FILE_BYTES) {
                CLBC_Util.log("!!!! 入口源文件过大，已拒绝读取: " + srcPath + " (" + sz + " 字节)");
                return;
            }
            String code = Files.readString(srcPath, StandardCharsets.UTF_8);
            codeText = code;
            // A-2：加载入口源属程序化设置，不触发反向回灌；下面的 importBlocks 仍按既有路径刷新代码面板
            codeDirty = false;
            codeCaret = 0;
            codeFileName = Path.of(src).getFileName().toString();
            CodeToBlocks.ConvertResult res = CodeToBlocks.convert(code);
            List<CLBC_Block> blocks = res.blocks;
            // 打开／切换工程＝替换：先清空画布再导入，避免 A、B 两套积木叠加；
            // 反编译面板「导入到积木画布」与代码解析导入仍走默认追加。
            canvas.importBlocks(blocks, true, res.comments);
            // 修"又反了"：importBlocks 会触发 syncCodeFromCanvas 把面板回灌成积木生成的代码，
            // 覆盖掉用户真正打开的源文件。这里把原始源码写回面板——面板应显示你打开的文件，
            // 积木才是解析版；后续编辑代码→实时更新积木、编辑积木→实时更新代码，方向才不反。
            codeText = code;
            codeDirty = false;
            codeCaret = 0;
            CLBC_Util.log(String.format("已加载入口源 %s → 代码面板（原始源码）+ %d 个积木导入画布", codeFileName, blocks.size()));
        } catch (Exception e) {
            CLBC_Util.log("!!!! 加载入口源文件失败: " + e);
            e.printStackTrace(System.out);   // 自检需要完整堆栈定位真实错误行
        }
    }

    /** 运行当前代码：写出源文件 → javac 编译 → java 运行，输出流式写入运行实例（支持停止） */
    private void runCurrent() {
        if (currentRun == null) { CLBC_Util.log("无活动运行实例"); return; }
        String src = codeText;
        if (src == null || src.isBlank()) { CLBC_Util.log("没有可运行的代码"); return; }
        try {
            Path dir = Path.of("F:/workbuddy/run_tmp");
            Files.createDirectories(dir);
            Path srcFile = dir.resolve("Main.java");
            Files.writeString(srcFile, src, StandardCharsets.UTF_8);
            CLBC_Util.log("已写出运行源文件: " + srcFile);
            String javac = "D:/Program Files (x86)/java25/bin/javac.exe";
            Process compile = new ProcessBuilder(javac, srcFile.toString())
                    .directory(dir.toFile()).redirectErrorStream(true).start();
            String cerr = new String(compile.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int crc = compile.waitFor();
            if (crc != 0) {
                currentRun.status = "编译失败";
                currentRun.output.append("【编译错误】\n").append(cerr);
                CLBC_Util.log("运行前编译失败:\n" + cerr);
                return;
            }
            CLBC_Util.log("编译成功，启动运行…");
            String java = "D:/Program Files (x86)/java25/bin/java.exe";
            Process p = new ProcessBuilder(java, "-cp", dir.toString(), "Main")
                    .directory(dir.toFile()).redirectErrorStream(true).start();
            currentRun.process = p;
            currentRun.status = "运行中";
            currentRun.output.setLength(0);
            Thread t = new Thread(() -> {
                try (var br = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) currentRun.output.append(line).append("\n");
                } catch (java.io.IOException e) {
                    currentRun.output.append("读取输出失败: ").append(e.getMessage());
                }
                try {
                    int rc = p.waitFor();
                    currentRun.status = "已结束（退出码 " + rc + "）";
                } catch (InterruptedException e) {
                    currentRun.status = "已中断";
                }
                CLBC_Util.log("运行结束: " + currentRun.status);
            });
            t.setDaemon(true);
            t.start();
        } catch (Exception e) {
            currentRun.status = "启动异常";
            currentRun.output.append("运行启动失败: ").append(e.getMessage());
            CLBC_Util.log("!!!! 运行启动失败: " + e.getMessage());
        }
    }

    /** 顶栏：返回按钮 + 标题（历史 / 设置页共用）。先画底，再画控件，保证可读。 */
    private void headerBar(String title, float w) {
        CLBC_GL_API.fillRect(PANEL, 0, 0, w, 52);                 // 顶栏底色
        CLBC_GL_API.fillRect(ACCENT, 0, 0, 4, 52);                // 左侧强调条
        CLBC_GL_API.fillRect(SEP, 0, 51, w, 1);                   // 底部分隔线
        if (CLBC_GL_Widgets.button(9000, 16, 12, 72, 28, "返回", PANEL2, TEXT)) showMainMenu();
        CLBC_GL_API.drawText(CLBC_GL_API.uiFont, title, 100, 12 + 18, TEXT);
    }

    // ==========================================================
    // 主菜单
    // ==========================================================
    private void drawMainMenu() {
        int W = CLBC_GL_API.getWindowWidth(), H = CLBC_GL_API.getWindowHeight();
        // #162：反编译＝左侧源码文件列表里双击 .jav（在当前界面 code→积木），
        // 不是启动页的独立屏幕。故这里不再放「反编译」卡片，避免两套入口语义打架。
        String[] labels = {"导入项目", "新建项目", "历史项目", "设置"};
        String[] subs = {"打开已有 Java 工程", "从模板创建新工程", "打开最近编辑的工程",
                "语言 / 主题 / 编辑器"};
        long[] colors = {ACCENT, GREEN, PURPLE, CLBC_GL_API.hex("#888888")};
        int n = labels.length;
        // 【T1】4 卡由一行横排改为 2×2 网格（用户实测觉得一行太挤/太小）。
        final int cols = 2, rows = 2;
        float gap = 20;
        float ch = 150;
        float cw = Math.max(180f, Math.min(300f, (W - 120f - gap * (cols - 1)) / (float) cols));
        float totalW = cols * cw + (cols - 1) * gap;
        float totalH = rows * ch + (rows - 1) * gap;
        float startX = (W - totalW) / 2f;
        float startY = (H - totalH) / 2f + 10;
        for (int i = 0; i < n; i++) {
            float x = startX + (i % cols) * (cw + gap);
            float y = startY + (i / cols) * (ch + gap);
            boolean hovered = CLBC_GL_Widgets.hit(x, y, cw, ch);
            long cardBg = hovered ? CLBC_GL_Widgets.lighten(PANEL2, 0.10f) : PANEL2;
            CLBC_GL_API.fillRoundRect(cardBg, x, y, cw, ch, 12);
            CLBC_GL_API.strokeRect(SEP, x, y, cw, ch, 1);
            CLBC_GL_API.fillRoundRect(colors[i], x, y, cw, 6, 3);   // 顶部强调条
            CLBC_GL_API.fillCircle(colors[i], x + cw / 2f, y + 46, 22);   // 图标圆点
            CLBC_GL_API.drawTextCentered(CLBC_GL_API.uiFont, labels[i], x, y + 80, cw, 24, TEXT);
            CLBC_GL_API.drawTextCentered(CLBC_GL_API.uiFont, subs[i], x, y + 108, cw, 30, SUBTEXT);
            if (hovered && CLBC_GL_API.mouseJustPressed) {
                switch (i) {
                    case 0 -> {
                        String p = CLBC_Util.folder_picker("选择项目文件夹", false);
                        if (p != null) { CLBC_Util.addProjectHistory(p); showProject(p); }
                    }
                    case 1 -> showNewProject();
                    case 2 -> showHistory();
                    case 3 -> showSettings();
                    default -> { }
                }
            }
        }
    }

    // ==========================================================
    // 新建项目
    // ==========================================================
    private void drawNewProject() {
        int W = CLBC_GL_API.getWindowWidth(), H = CLBC_GL_API.getWindowHeight();
        headerBar("新建项目", W);

        float cx = W / 2f, rowY = H / 2f - 60;
        CLBC_GL_API.drawText(CLBC_GL_API.uiFont, "项目名:", cx - 220, rowY + 14, TEXT);
        newProjName = CLBC_GL_Widgets.textField(2001, cx - 140, rowY, 300, 30, newProjName, WHITE, CLBC_GL_API.hex("#333333"), ACCENT);

        CLBC_GL_API.drawText(CLBC_GL_API.uiFont, "位置:", cx - 220, rowY + 54, TEXT);
        newProjPath = CLBC_GL_Widgets.textField(2002, cx - 140, rowY + 40, 240, 30, newProjPath, WHITE, CLBC_GL_API.hex("#333333"), ACCENT);
        if (CLBC_GL_Widgets.button(2003, cx + 110, rowY + 40, 80, 30, "浏览", PANEL2, TEXT)) {
            String d = CLBC_Util.folder_picker("选择项目位置", false);
            if (d != null) newProjPath = d;
        }

        // 【T7】语言在创建项目时选择（用户诉求），不再只靠顶栏临时切换
        CLBC_GL_API.drawText(CLBC_GL_API.uiFont, "语言:", cx - 220, rowY + 94, TEXT);
        String[] newLangs = {"Java", "Python"};
        newProjLang = CLBC_GL_Widgets.comboBox(2006, cx - 140, rowY + 80, 160, 30,
                newLangs, newProjLang, PANEL2, TEXT, PANEL2, TEXT);

        boolean cancel = CLBC_GL_Widgets.button(2004, cx - 90, rowY + 130, 80, 32, "取消", PANEL2, TEXT);
        boolean create = CLBC_GL_Widgets.button(2005, cx + 10, rowY + 130, 80, 32, "创建", GREEN, WHITE);
        if (cancel) showMainMenu();
        if (create) {
            String name = newProjName.trim();
            String dir = newProjPath.trim();
            if (name.isEmpty() || dir.isEmpty()) {
                CLBC_Util.log("!!!!项目名或位置为空");
                return;
            }
            String projectPath = CLBC_Util.createJavaProject(dir, name);
            if (projectPath != null) {
                // 【T7】把创建时选的语言应用到当前工程会话（顶栏语言下拉同步显示），并记入工程配置。
                langComboSel = newProjLang;
                String lang = newProjLang == 1 ? "Python" : "Java";
                try {
                    Path pcfg = Path.of(projectPath, "project.properties");
                    Files.writeString(pcfg, "project.language=" + lang + "\n", StandardCharsets.UTF_8);
                } catch (IOException ioe) {
                    CLBC_Util.log("写入工程语言配置失败（不影响创建）: " + ioe.getMessage());
                }
                CLBC_Util.log("新建项目 '" + name + "' 语言=" + lang);
                showProject(projectPath);
            }
        }
    }

    // ==========================================================
    // 历史项目
    // ==========================================================
    private void drawHistory() {
        int W = CLBC_GL_API.getWindowWidth(), H = CLBC_GL_API.getWindowHeight();
        headerBar("历史项目", W);
        List<String> history = CLBC_Util.getProjectHistory();
        String[] items = history.toArray(new String[0]);

        // 左侧列表 + 右侧缩略图预览卡片（UI 优化：打开前即可看到工程积木布局）
        float lx = 40, ly = 80, lh = H - 140;
        float lw = Math.max(240, (W - 120) * 0.55f);
        historySel = CLBC_GL_Widgets.list(3001, lx, ly, lw, lh, items, historySel, PANEL2, TEXT, ACCENT, WHITE);

        float px = lx + lw + 24, pw = W - px - 40, py = ly, ph = lh;
        CLBC_GL_API.fillRect(PANEL, px, py, pw, ph);
        CLBC_GL_API.strokeRect(SEP, px, py, pw, ph, 1);
        CLBC_GL_API.drawText(CLBC_GL_API.uiFont, "工程缩略图", px + 14, py + 26, SUBTEXT);
        if (historySel >= 0 && historySel < items.length) {
            String path = items[historySel];
            String png = CLBC_Thumbnail.thumbnailPath(path);
            int tex = CLBC_GL_API.loadTextureFromFileCached(png);
            float iw = pw - 28, ih = Math.min(ph - 74, iw * 0.625f);
            float ix = px + 14, iy = py + 40;
            if (tex != 0) {
                CLBC_GL_API.drawImage(tex, ix, iy, iw, ih);
            } else {
                CLBC_GL_API.fillRect(PANEL2, ix, iy, iw, ih);
                CLBC_GL_API.drawText(CLBC_GL_API.uiFont, "暂无缩略图（打开或保存工程后自动生成）",
                        ix + 12, iy + ih / 2f, SUBTEXT);
            }
            if (CLBC_GL_Widgets.button(3020, ix, iy + ih + 10, 120, 28, "生成缩略图", PANEL2, TEXT)) {
                canvas.generateThumbnail(png);
            }
        } else {
            CLBC_GL_API.drawText(CLBC_GL_API.uiFont, "选择左侧项目查看缩略图", px + 14, py + 60, SUBTEXT);
        }

        if (CLBC_GL_Widgets.listDoubleClicked(3001, lx, ly, lw, lh, items, historySel)) {
            if (historySel >= 0) showProject(items[historySel]);
        }
    }

    // ==========================================================
    // 设置
    // ==========================================================
    private void drawSettings() {
        int W = CLBC_GL_API.getWindowWidth(), H = CLBC_GL_API.getWindowHeight();
        headerBar("设置", W);
        settings.draw(0, 52, W, H - 52);
    }

    /** 内置反编译屏幕（CFR / Vineflower / Procyon / Fernflower） */
    private void drawDecompile() {
        int W = CLBC_GL_API.getWindowWidth(), H = CLBC_GL_API.getWindowHeight();
        headerBar("字节码反编译", W);
        decompilePanel.draw(0, 52, W, H - 52);
    }

    // ==========================================================
    // 项目视图（顶栏 + 左工具箱 + 中画布 + 右代码 + 底输出）
    // ==========================================================
    private void drawProjectView() {
        int W = CLBC_GL_API.getWindowWidth(), H = CLBC_GL_API.getWindowHeight();
        float TOP_H = 52;

        // C：先处理分隔条拖拽（可能改变下面三个尺寸变量），本帧即用新值
        handleRegionResize(W, H, TOP_H);
        float LEFT_W = leftPanelW, RIGHT_W = rightPanelW, BOTTOM_H = bottomPanelH;

        float cx0 = LEFT_W, cy0 = TOP_H;
        float cw = W - LEFT_W - RIGHT_W, ch = H - TOP_H - BOTTOM_H;
        float rx0 = W - RIGHT_W, ry0 = TOP_H;
        float rw = RIGHT_W, rh = H - TOP_H - BOTTOM_H;
        float bx0 = 0, by0 = H - BOTTOM_H;
        float bw = W, bh = BOTTOM_H;

        // 【#257】顶栏移到本函数最后画（见末尾），确保图层高于画布/工具箱/代码面板/底栏。

        // 用户要求：积木区（画布）所有内容的图层一律低于其它区。
        // 因此画布必须最先画、严格 clip 在自己的矩形内；工具箱 / 文件树 / 代码面板随后画，
        // 天然覆盖在画布之上——即便注释便签或积木超出画布边界，也绝不会盖住别的区。
        // 中间画布（裁剪到中心区域）
        CLBC_GL_API.fillRect(WHITE, cx0, cy0, cw, ch);
        CLBC_GL_API.setClip(cx0, cy0, cw, ch);
        drawWallpaper(cx0, cy0, cw, ch);   // 用户自定义背景壁纸（在画布底之上、网格/积木之下）
        canvas.draw(cx0, cy0, cw, ch);
        // C：拖分隔条期间不把鼠标事件交给画布，避免与积木拖拽 / 平移冲突
        if (resizeMode == 0) canvas.handleInput(cx0, cy0, cw, ch);
        CLBC_GL_API.clearClip();

        // 左侧栏：工具箱（上）+ 源码文件列表（下）——画布之后画，图层高于画布
        float leftH = H - TOP_H - BOTTOM_H;
        float toolboxH = leftH * toolboxRatio;
        float fileTop = TOP_H + toolboxH + 4;
        float fileH = leftH - toolboxH - 4;
        toolbox.draw(0, TOP_H, LEFT_W, toolboxH);
        drawSourceFileList(0, fileTop, LEFT_W, fileH);

        // 右侧代码面板
        CLBC_GL_API.fillRect(CODE_BG, rx0, ry0, rw, rh);
        // 面板头（标题 + 复制按钮）
        CLBC_GL_API.fillRect(PANEL, rx0, ry0, rw, 34);
        CLBC_GL_API.fillRect(SEP, rx0, ry0 + 33, rw, 1);
        CLBC_GL_API.drawText(CLBC_GL_API.uiFont, "代码 · " + codeFileName, rx0 + 12, ry0 + 12, TEXT);
        // A-2：GL 层没有 V 键专用边沿，Ctrl+V 粘贴可能收不到 char 回调；补一个可靠按钮通道
        if (CLBC_GL_Widgets.button(7002, rx0 + rw - 148, ry0 + 4, 64, 26, "粘贴", PANEL2, TEXT)) {
            String paste = readClipboard();
            if (paste != null && !paste.isEmpty()) {
                // 收口4：按钮粘贴也计入文本撤销栈（编辑操作快照）
                pushCodeUndo(codeText == null ? "" : codeText);
                StringBuilder sb = new StringBuilder(codeText == null ? "" : codeText);
                int cp = clampCaret(codeCaret, sb.length());
                sb.insert(cp, paste);
                codeText = sb.toString();
                codeCaret = cp + paste.length();
                codeDirty = true;
                lastCodeEditMs = System.currentTimeMillis();
                CLBC_GL_API.focusedTextField = CODE_EDITOR_ID;   // 粘贴后回到编辑器，便于继续输入
                CLBC_Util.log("代码面板：按钮粘贴 " + paste.length() + " 字符");
            }
        }
        if (CLBC_GL_Widgets.button(7001, rx0 + rw - 76, ry0 + 4, 64, 26, "复制", PANEL2, TEXT)) {
            CLBC_Util.copyToClipboard(codeText);
        }
        drawCodeEditor(rx0, ry0, rw, rh);

        // 底部分页（属性 / 输出 / 问题）
        drawBottomTabs(bx0, by0, bw, bh);
        // C：绘制可拖拽分隔条（拖拽中 / 悬停高亮）
        drawDividers(W, H, TOP_H, LEFT_W, RIGHT_W, BOTTOM_H);
        // 画布右键菜单（删除 / 添加注释）：所有区画完后再叠加，保证它在最上层不被面板盖住
        canvas.drawPopup();
        // 【#257】顶栏最后画，图层高于画布/工具箱/代码面板/底栏/右键菜单，
        // 杜绝任何区域内容越界压住顶栏控件（popup 仍由 frame 末尾统一画，正常盖住顶栏）。
        drawProjectTopBar(cx0, W);
        // A-2：输入停顿后把代码反向回灌画布（每帧检查 debounce，不每帧重建）
        maybePushCodeToCanvas();
    }

    // ==========================================================
    // C：分区间可拖拽拉升
    // ==========================================================
    /** 判定是否落在某条分隔条上，拖拽中实时改对应尺寸；松开复位。命中分隔条时 resizeMode≠0，
     *  调用方据此跳过画布 handleInput，避免和积木拖拽 / 平移抢事件（分隔条只在像素带内抢占）。 */
    private void handleRegionResize(int W, int H, float TOP_H) {
        // 左栏内部「积木预选区 ↔ 源码文件选择区」的横向分隔条 y
        float leftH = H - TOP_H - bottomPanelH;
        float innerDivY = TOP_H + leftH * toolboxRatio + 4f;
        boolean inLeftColumn = CLBC_GL_API.mouseX >= 0 && CLBC_GL_API.mouseX <= leftPanelW;
        if (CLBC_GL_API.mouseJustPressed) {
            boolean onInner = inLeftColumn && Math.abs(CLBC_GL_API.mouseY - innerDivY) <= DIV_HALF + 2f;
            boolean onLeft = !onInner && Math.abs(CLBC_GL_API.mouseX - leftPanelW) <= DIV_HALF;
            boolean onRight = Math.abs(CLBC_GL_API.mouseX - (W - rightPanelW)) <= DIV_HALF;
            boolean onBottom = Math.abs(CLBC_GL_API.mouseY - (H - bottomPanelH)) <= DIV_HALF;
            resizeMode = onInner ? 4 : onLeft ? 1 : onRight ? 2 : onBottom ? 3 : 0;
        }
        if (resizeMode == 1) {
            float maxL = W - rightPanelW - MIN_CANVAS_W;
            leftPanelW = clamp(CLBC_GL_API.mouseX, LEFT_MIN, Math.max(LEFT_MIN, maxL));
        } else if (resizeMode == 2) {
            float maxR = W - leftPanelW - MIN_CANVAS_W;
            rightPanelW = clamp(W - CLBC_GL_API.mouseX, RIGHT_MIN, Math.max(RIGHT_MIN, maxR));
        } else if (resizeMode == 3) {
            bottomPanelH = clamp(H - CLBC_GL_API.mouseY, BOTTOM_MIN, BOTTOM_MAX);
        } else if (resizeMode == 4) {
            // 预选区高度 = 鼠标 y - 顶栏；夹在最小/最大比例，保证文件列表仍可见
            toolboxRatio = clamp((CLBC_GL_API.mouseY - TOP_H - 4f) / leftH,
                    TOOLBOX_MIN_RATIO, TOOLBOX_MAX_RATIO);
        }
        if (resizeMode != 0 && CLBC_GL_API.mouseJustReleased) resizeMode = 0;
    }

    private void drawDividers(int W, int H, float TOP_H, float LEFT_W, float RIGHT_W, float BOTTOM_H) {
        boolean hoverL = Math.abs(CLBC_GL_API.mouseX - LEFT_W) <= DIV_HALF;
        boolean hoverR = Math.abs(CLBC_GL_API.mouseX - (W - RIGHT_W)) <= DIV_HALF;
        boolean hoverB = Math.abs(CLBC_GL_API.mouseY - (H - BOTTOM_H)) <= DIV_HALF;
        float innerY = TOP_H + (H - TOP_H - BOTTOM_H) * toolboxRatio + 4f;
        boolean hoverInner = CLBC_GL_API.mouseX >= 0 && CLBC_GL_API.mouseX <= LEFT_W
                && Math.abs(CLBC_GL_API.mouseY - innerY) <= DIV_HALF + 2f;
        long col = (resizeMode != 0 || hoverL || hoverR || hoverB || hoverInner) ? ACCENT : SEP;
        float ySpan = H - TOP_H - BOTTOM_H;
        CLBC_GL_API.fillRect(col, LEFT_W - 1, TOP_H, 2, ySpan);          // 左分隔：左侧栏 ↔ 画布
        CLBC_GL_API.fillRect(col, W - RIGHT_W - 1, TOP_H, 2, ySpan);     // 右分隔：画布 ↔ 代码
        CLBC_GL_API.fillRect(col, 0, H - BOTTOM_H - 1, W, 2);            // 下分隔：画布 ↔ 底栏
        // 左栏内部：积木预选区 ↔ 源码文件选择区（可拖）
        CLBC_GL_API.fillRect(hoverInner || resizeMode == 4 ? ACCENT : SEP,
                0, innerY - 3f, LEFT_W, 4);
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // ==========================================================
    // B：当前工程源码文件列表（双击 .jav 在当前界面反编译，不新开分页）
    // ==========================================================
    /** 左侧栏底部面板：列出当前工程所有 .java；双击即在「项目视图」内把源文件解析成积木，
     *  复用 loadProjectSource 同源逻辑（importBlocks 替换 + 原始源码写回代码面板），停在 PROJECT 屏幕。 */
    private void drawSourceFileList(float x, float y, float w, float h) {
        CLBC_GL_API.fillRect(PANEL, x, y, w, h);
        CLBC_GL_API.strokeRect(SEP, x, y, w, h, 1);
        CLBC_GL_API.drawText(CLBC_GL_API.uiFont, "源码文件（双击反编译）", x + 10, y + 6, SUBTEXT);
        // 切换工程或首次进入时刷新清单
        if (srcFilesProject == null || !srcFilesProject.equals(currentProject) || srcFiles.isEmpty()) {
            refreshSourceFiles();
        }
        if (srcFiles.isEmpty()) {
            CLBC_GL_API.drawText(CLBC_GL_API.uiFont, "（工程内未发现 .java）", x + 10, y + 30, SUBTEXT);
            return;
        }
        float ly = y + 26, lh = h - 30;
        String[] items = srcLabels.toArray(new String[0]);
        // IDEA 项目树式选中：整行浅蓝高亮条 + 【黑字】（用户明确「高亮让字变黑」）。
        // 旧代码 selFg 传 TEXT，深色主题下 TEXT 是浅色 → 浅蓝底上白字看不清。
        // 高亮底色恒为浅蓝 #D6E8FF，文字恒为黑色，深浅主题下对比都清晰。
        long selBg = CLBC_GL_API.hex("#D6E8FF");
        long selFg = CLBC_GL_API.hex("#000000");
        srcListSel = CLBC_GL_Widgets.list(8001, x + 6, ly, w - 12, lh, items, srcListSel,
                PANEL, TEXT, selBg, selFg);
        if (CLBC_GL_Widgets.listDoubleClicked(8001, x + 6, ly, w - 12, lh, items, srcListSel)) {
            loadSourceFile(srcFiles.get(srcListSel));
        }
    }

    /** 扫描当前工程 .java（超大文件跳过）。
     *  #157：原先同时把 src 与 src/main/java 当根扫，后者是前者的子目录 → 每个文件重复两遍；
     *  且列表直接显示绝对路径 → 满屏重复的 F:\...\src\main\java\...。
     *  现只取最深的那个根、按规范化路径去重，显示改成「相对 src/main/java 的短名 + 包层级缩进」。 */
    private void refreshSourceFiles() {
        srcFiles.clear();
        srcLabels.clear();
        srcListSel = -1;
        srcFilesProject = currentProject;
        if (currentProject == null) return;
        try {
            Path base = Path.of(currentProject).toAbsolutePath().normalize();
            Path root = base.resolve("src/main/java");
            if (!Files.isDirectory(root)) root = base.resolve("src");
            if (!Files.isDirectory(root)) root = base;
            Path finalRoot = root;
            java.util.LinkedHashSet<Path> seen = new java.util.LinkedHashSet<>();
            try (var s = Files.walk(finalRoot)) {
                s.filter(f -> Files.isRegularFile(f) && f.toString().endsWith(".java"))
                 .forEach(f -> seen.add(f.toAbsolutePath().normalize()));
            }
            for (Path f : seen) {
                if (Files.size(f) > CLBC_Util.MAX_INPUT_FILE_BYTES) continue;   // 超大跳过，防 OOM
                srcFiles.add(f.toString());
                // 相对短名：优先相对 src/main/java（或 src），最后兜底文件名
                String rel;
                try { rel = finalRoot.relativize(f).toString().replace('\\', '/'); }
                catch (Exception ignore) { rel = f.getFileName().toString(); }
                int depth = 0;
                for (int i = 0; i < rel.length(); i++) if (rel.charAt(i) == '/') depth++;
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < Math.min(depth, 6); i++) sb.append("  ");   // 包层级缩进（IDEA 树风格）
                sb.append("· ").append(f.getFileName().toString());
                srcLabels.add(sb.toString());
            }
        } catch (Exception e) {
            CLBC_Util.log("!!!! 扫描工程 .java 失败: " + e.getMessage());
        }
    }

    /** 双击源文件：在当前项目界面内反编译（code→blocks），替换画布、原始源码留在代码面板，不切换屏幕。 */
    private void loadSourceFile(String absPath) {
        if (absPath == null) return;
        try {
            Path p = Path.of(absPath);
            if (Files.size(p) > CLBC_Util.MAX_INPUT_FILE_BYTES) {
                CLBC_Util.log("!!!! 源文件过大，已拒绝: " + p + " (" + Files.size(p) + " 字节)");
                return;
            }
            String code = Files.readString(p, StandardCharsets.UTF_8);
            codeText = code;                                  // 先把原始源码送代码面板
            codeFileName = p.getFileName().toString();
            codeDirty = false; codeCaret = 0;
            codeUndo.clear(); codeRedo.clear();
            CodeToBlocks.ConvertResult res = CodeToBlocks.convert(code);
            // 替换画布（含 // 注释还原成注释框），与打开工程同语义
            canvas.importBlocks(res.blocks, true, res.comments);
            // 修"又反了"：importBlocks 会触发 syncCodeFromCanvas 把面板回灌成积木生成的代码，
            // 覆盖掉用户真正双击打开的源文件；这里把原始源码写回面板——面板显示你打开的文件，
            // 积木才是解析版（与 loadProjectSource 一致）。
            codeText = code;
            codeDirty = false; codeCaret = 0;
            CLBC_Util.log(String.format("双击源码 %s → 当前界面反编译：代码面板(原始源码) + %d 积木",
                    codeFileName, res.blocks.size()));
        } catch (Exception e) {
            CLBC_Util.log("!!!! 双击源码反编译失败: " + e.getMessage());
        }
    }

    private float scrollOffsetCode = 0;

    /** A-2：右侧代码面板可编辑多行文本。
     *  焦点复用 CLBC_GL_API.focusedTextField（专用负数 id，避开积木输入窗口 / 其它文本框）；
     *  用户改动只写 codeText 并置 codeDirty，真正回灌由 maybePushCodeToCanvas 在输入停顿后统一做。 */
    private void drawCodeEditor(float rx0, float ry0, float rw, float rh) {
        float x = rx0 + 12, y = ry0 + 50, availH = rh - 50;
        boolean inside = CLBC_GL_Widgets.hit(rx0, ry0 + 40, rw, rh - 40);

        // 点击代码区 → 取得焦点并按鼠标位置定位光标
        if (inside && CLBC_GL_API.mouseJustPressed) {
            CLBC_GL_API.focusedTextField = CODE_EDITOR_ID;
            codeCaret = caretFromPoint(x + CODE_GUTTER, y - scrollOffsetCode);
        }
        boolean focused = (CLBC_GL_API.focusedTextField == CODE_EDITOR_ID);

        // 滚轮滚动（悬停面板内即生效，沿用原有手感）
        String before = codeText == null ? "" : codeText;
        if (inside && CLBC_GL_API.scrollDelta != 0) {
            scrollOffsetCode -= CLBC_GL_API.scrollDelta * 18;
            float maxS = Math.max(0, before.split("\n", -1).length * CODE_LINE_H - availH);
            scrollOffsetCode = Math.max(0, Math.min(scrollOffsetCode, maxS));
        }
        // 键盘输入（仅当焦点在代码编辑器）
        if (focused) applyCodeInput();
        // 焦点边框（画在 clip 之外，避免被裁切）
        if (focused) CLBC_GL_API.strokeRect(ACCENT, rx0, ry0 + 40, rw, rh - 40, 2);

        // 绘制：用输入处理后的最新文本，光标与行号都基于它
        String text = codeText == null ? "" : codeText;
        String[] lines = text.split("\n", -1);
        CLBC_GL_API.setClip(rx0, ry0 + 40, rw, rh - 40);
        float ty = y - scrollOffsetCode;
        long numCol = CODE_NUM, base = CLBC_GL_API.hex("#D4D4D4");
        long kwCol = ACCENT, strCol = CLBC_GL_API.hex("#9CDCFE"), comCol = CLBC_GL_API.hex("#6A9955");
        // 收口4：Ctrl+A 全选态（codeCaret<0）每行画可见选区底色；仅加绘制，不改绘制签名／既有逻辑
        boolean selectAll = codeCaret < 0;
        long selBg = CLBC_GL_API.rgba(76, 151, 255, 90);
        for (int i = 0; i < lines.length; i++) {
            float ly = ty + i * CODE_LINE_H;
            if (ly + CODE_LINE_H < y - 10 || ly > y + availH) continue;
            if (selectAll) {
                float selW = Math.max(8f, CLBC_GL_API.measureText(CLBC_GL_API.uiFont, lines[i]));
                CLBC_GL_API.fillRect(selBg, x + CODE_GUTTER, ly, selW, CODE_LINE_H);
            }
            String num = String.valueOf(i + 1);
            CLBC_GL_API.drawText(CLBC_GL_API.uiFont, num,
                    x + CODE_GUTTER - 8 - CLBC_GL_API.measureText(CLBC_GL_API.uiFont, num), ly + 4, numCol);
            drawCodeLine(lines[i], x + CODE_GUTTER, ly + 4, kwCol, strCol, comCol, base);
        }
        if (focused) drawCodeCaret(text, lines, x + CODE_GUTTER, ty);
        CLBC_GL_API.clearClip();
    }

    /** A-2：按鼠标位置把光标定位到 (行, 列)，再换算成 codeText 的全局字符偏移 */
    private int caretFromPoint(float textX, float firstLineY) {
        String text = codeText == null ? "" : codeText;
        String[] lines = text.split("\n", -1);
        int line = (int) Math.floor((CLBC_GL_API.mouseY - firstLineY) / CODE_LINE_H);
        line = Math.max(0, Math.min(line, lines.length - 1));
        String ln = lines[line];
        float cx = textX;
        int col = 0;
        for (; col < ln.length(); col++) {
            float cw = CLBC_GL_API.measureText(CLBC_GL_API.uiFont, String.valueOf(ln.charAt(col)));
            if (cx + cw / 2f >= CLBC_GL_API.mouseX) break;
            cx += cw;
        }
        int off = 0;
        for (int i = 0; i < line; i++) off += lines[i].length() + 1;   // +1 = 换行符
        return clampCaret(off + col, text.length());
    }

    private int clampCaret(int cp, int len) { return Math.max(0, Math.min(cp, len)); }

    private int codeCaretLine(String text) {
        int cp = clampCaret(codeCaret, text.length());
        int line = 0;
        for (int k = 0; k < cp; k++) if (text.charAt(k) == '\n') line++;
        return line;
    }

    /** A-2：绘制竖直光标（编辑期间常亮；光标位置由 codeCaret 决定，不受画布回灌影响 → 不跳光标） */
    private void drawCodeCaret(String text, String[] lines, float textX, float firstLineY) {
        int cp = clampCaret(codeCaret, text.length());
        int line = codeCaretLine(text);
        int start = 0;
        for (int k = 0; k < line; k++) start += lines[k].length() + 1;
        String ln = line < lines.length ? lines[line] : "";
        int col = Math.max(0, Math.min(cp - start, ln.length()));
        float cx = textX + CLBC_GL_API.measureText(CLBC_GL_API.uiFont, ln.substring(0, col));
        float cy = firstLineY + line * CODE_LINE_H;
        // 实测修复：代码文本绘制在基线 ly+4（见上方 drawCodeLine），旧光标却从 ly 画到 ly+CODE_LINE_H，
        // 起点比文本低、整体下偏约半个字符高，且撑满整行显得又长又粗。
        // 现在按基线对齐：顶部 = 基线 - 0.8 字高（字身 ascent），底部 = 基线 + 0.12 字高（略含降部），
        // 高度≈一个字高而非整行；线宽 1.5 → 1.0。
        float fontH = CLBC_GL_API.uiFont == null ? 16f : CLBC_GL_API.uiFont.getPixelHeight();
        float baseline = cy + 4f;                 // 与 drawCodeLine(..., ly + 4, ...) 同一基线
        float top = baseline - fontH * 0.8f;
        float bottom = baseline + fontH * 0.12f;
        // 代码面板恒深色底（CODE_BG #282C34），光标必须恒白；
        // 旧代码用 WHITE，而深色主题下 WHITE 被设成 #1E1E1E → 光标与背景同色看不见。
        CLBC_GL_API.drawLine(cx, top, cx, bottom, CLBC_GL_API.hex("#FFFFFF"), 1.2f);
    }

    /** A-2 / 收口2：处理键盘输入。仅焦点在代码编辑器时调用（与画布快捷键靠 focusedTextField 隔离）；
     *  改动只写 codeText + 置 dirty，真正回灌由 maybePushCodeToCanvas 在输入停顿后统一做。
     *  接入段2 GL 按键状态：Ctrl+V 粘贴（keyV+ctrlDown，含超长拒绝）/ Ctrl+A 全选 / Ctrl+C 复制 /
     *  上下方向键移动光标 / 左右 / 行首行尾 / 回车 / Tab / 退格 / 删除。
     *  全选用 codeCaret<0 作标记（不新增字段、不改绘制）：全选后输入或粘贴即替换全文，
     *  方向键、Home/End、鼠标定位（caretFromPoint 写入非负 caret）即收起，不与画布撤销/全选/删除冲突。
     *  Ctrl+Z / Ctrl+Y 由独立文本撤销／重做栈处理（每次编辑操作快照，限帧数／限总字节），
     *  画布快捷键仅在 focusedTextField==-1 时触发，故焦点隔离下互不影响。 */
    private void applyCodeInput() {
        if (CLBC_GL_API.focusedTextField != CODE_EDITOR_ID) return;   // 焦点隔离：不与画布撤销/全选/删除冲突
        // 收口4：Ctrl+Z／Ctrl+Y 文本撤销／重做；本帧只做撤销／重做，不再混叠其它编辑。
        // 画布撤销快捷键仅在 focusedTextField==-1 触发（CODE_EDITOR_ID=-2），天然隔离、无副作用。
        if (CLBC_GL_API.ctrlDown && CLBC_GL_API.keyZ) { undoCodeText(); return; }
        if (CLBC_GL_API.ctrlDown && CLBC_GL_API.keyY) { redoCodeText(); return; }
        String editBefore = codeText == null ? "" : codeText;   // 本帧编辑前快照（撤销用）
        StringBuilder sb = new StringBuilder(editBefore);
        boolean selectAll = codeCaret < 0;                            // 负值 = 全选标记
        int cp = selectAll ? 0 : clampCaret(codeCaret, sb.length());
        boolean changed = false;

        // 收集本帧真正可插入的字符；Ctrl 组合键残留在 char 回调里的字母不当文本插入
        List<Integer> chars = new ArrayList<>();
        for (int c : CLBC_GL_API.typedChars) {
            if (CLBC_GL_API.ctrlDown && (c == 'v' || c == 'V' || c == 'a' || c == 'A'
                    || c == 'c' || c == 'C' || c == 'x' || c == 'X' || c == 'z' || c == 'Z'
                    || c == 'y' || c == 'Y' || c == 's' || c == 'S' || c == 0x16)) continue;
            chars.add(c);
        }
        String paste = (CLBC_GL_API.keyV && CLBC_GL_API.ctrlDown) ? readClipboard() : null;
        boolean pasteOk = false;
        if (paste != null && paste.length() > CLBC_Util.MAX_PASTE_CHARS) {
            CLBC_Util.log("!!!! 代码面板粘贴内容过长，已安全拒绝（" + paste.length() + " 字符）");
        } else if (paste != null && !paste.isEmpty()) {
            pasteOk = true;
        }

        // 全选后本帧会产生文本改动 → 先清空全文（替换选区）
        boolean willEdit = pasteOk || CLBC_GL_API.keyEnter || CLBC_GL_API.keyBackspace
                || CLBC_GL_API.keyDelete || !chars.isEmpty();
        if (selectAll && willEdit) { sb.setLength(0); cp = 0; selectAll = false; }

        // Ctrl+A 全选 / Ctrl+C 复制（复制全文，与代码面板「复制」按钮一致）
        if (CLBC_GL_API.keyA && CLBC_GL_API.ctrlDown) selectAll = true;
        if (CLBC_GL_API.keyC && CLBC_GL_API.ctrlDown) CLBC_Util.copyToClipboard(codeText);

        if (pasteOk) {
            sb.insert(cp, paste); cp += paste.length(); changed = true;
            CLBC_Util.log("代码面板：粘贴 " + paste.length() + " 字符");
        }
        for (int c : chars) {
            if (c == '\t') { sb.insert(cp, "    "); cp += 4; changed = true; continue; }
            if (c < 32) continue;   // 其余控制字符忽略；换行走 keyEnter，避免与 char 回调重复插入
            char[] ch = Character.toChars(c);
            sb.insert(cp, ch); cp += ch.length; changed = true;
        }
        if (CLBC_GL_API.keyEnter) { sb.insert(cp, '\n'); cp++; changed = true; }
        if (CLBC_GL_API.keyBackspace && cp > 0) { sb.deleteCharAt(cp - 1); cp--; changed = true; }
        if (CLBC_GL_API.keyDelete && cp < sb.length()) { sb.deleteCharAt(cp); changed = true; }
        if (CLBC_GL_API.keyLeft) { cp = Math.max(0, cp - 1); selectAll = false; }
        if (CLBC_GL_API.keyRight) { cp = Math.min(sb.length(), cp + 1); selectAll = false; }
        if (CLBC_GL_API.keyHome) { cp = lineStart(sb, cp); selectAll = false; }
        if (CLBC_GL_API.keyEnd) { cp = lineEnd(sb, cp); selectAll = false; }
        if (CLBC_GL_API.keyUp || CLBC_GL_API.keyDown) {
            selectAll = false;
            String[] lines = sb.toString().split("\n", -1);
            int start = 0, cur = 0;
            for (int i = 0; i < lines.length; i++) {
                int lineEnd = start + lines[i].length();
                if (cp <= lineEnd || i == lines.length - 1) { cur = i; break; }
                start = lineEnd + 1;
            }
            int col = cp - start;
            int tgt = cur + (CLBC_GL_API.keyUp ? -1 : 1);
            if (tgt < 0) cp = 0;
            else if (tgt >= lines.length) cp = sb.length();
            else {
                int ts = 0;
                for (int i = 0; i < tgt; i++) ts += lines[i].length() + 1;
                cp = Math.min(sb.length(), ts + Math.min(col, lines[tgt].length()));
            }
        }

        codeCaret = selectAll ? -1 : clampCaret(cp, sb.length());
        if (changed) {
            pushCodeUndo(editBefore);          // 收口4：编辑前文本入撤销栈，并清空重做分支
            codeText = sb.toString();
            codeDirty = true;
            lastCodeEditMs = System.currentTimeMillis();
        }
        // 鼠标点击定位由 drawCodeEditor 的 caretFromPoint 写入非负 codeCaret，自动收起全选，无需在此处理。
        // Ctrl+Z / Ctrl+Y 已由本方法开头的独立文本撤销／重做栈处理（焦点隔离，不影响画布撤销）。
    }

    private int lineStart(StringBuilder sb, int cp) {
        int k = cp - 1;
        while (k >= 0 && sb.charAt(k) != '\n') k--;
        return k + 1;
    }
    private int lineEnd(StringBuilder sb, int cp) {
        int k = cp;
        while (k < sb.length() && sb.charAt(k) != '\n') k++;
        return k;
    }

    /** 收口4：压入编辑前快照；新编辑清空重做分支，并裁剪到限帧数／限总字节 */
    private void pushCodeUndo(String before) {
        codeUndo.addLast(before == null ? "" : before);
        codeRedo.clear();
        trimCodeStack(codeUndo);
    }

    /** 收口4：文本撤销；恢复上一份快照，走统一的 codeDirty／debounce 回灌，不与 350ms 回环打架 */
    private void undoCodeText() {
        if (codeUndo.isEmpty()) return;
        String cur = codeText == null ? "" : codeText;
        codeRedo.addLast(cur);
        trimCodeStack(codeRedo);
        codeText = codeUndo.removeLast();
        codeCaret = clampCaret(codeCaret < 0 ? codeText.length() : codeCaret, codeText.length());
        codeDirty = true;
        lastCodeEditMs = System.currentTimeMillis();
    }

    /** 收口4：文本重做，与撤销对称 */
    private void redoCodeText() {
        if (codeRedo.isEmpty()) return;
        String cur = codeText == null ? "" : codeText;
        codeUndo.addLast(cur);
        trimCodeStack(codeUndo);
        codeText = codeRedo.removeLast();
        codeCaret = clampCaret(codeCaret < 0 ? codeText.length() : codeCaret, codeText.length());
        codeDirty = true;
        lastCodeEditMs = System.currentTimeMillis();
    }

    /** 收口4：限帧数 + 限总字节（UTF-16 粗估），超限丢弃最旧快照，防吃内存 */
    private void trimCodeStack(java.util.Deque<String> st) {
        long bytes = 0;
        for (String s : st) bytes += (long) s.length() * 2;
        while (st.size() > MAX_CODE_UNDO_FRAMES || bytes > MAX_CODE_UNDO_BYTES) {
            String old = st.pollFirst();
            if (old == null) break;
            bytes -= (long) old.length() * 2;
        }
    }

    /** A-2：读系统剪贴板（java.awt 为 JDK 自带，与 CLBC_Util.copyToClipboard 同源，不引入新依赖） */
    private String readClipboard() {
        try {
            Object data = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                    .getData(java.awt.datatransfer.DataFlavor.stringFlavor);
            return data == null ? null : data.toString();
        } catch (Throwable t) {
            CLBC_Util.log("!!!! 代码面板粘贴失败（剪贴板不可用）: " + t.getClass().getSimpleName());
            return null;
        }
    }

    /** A-2：输入停顿后把代码反向转换成积木回灌画布（每帧检查 debounce，不每帧重建）。 */
    private void maybePushCodeToCanvas() {
        if (!codeDirty || suppressCanvasSync) return;
        if (System.currentTimeMillis() - lastCodeEditMs < CODE_DEBOUNCE_MS) return;
        codeDirty = false;
        pushCodeToCanvas();
    }

    /** A-2：代码→积木→画布 回灌；失败时保留当前画布并记日志，绝不覆盖用户正在编辑的文本。 */
    private void pushCodeToCanvas() {
        String code = codeText == null ? "" : codeText;
        List<CLBC_Block> blocks;
        List<CodeToBlocks.CommentData> comments = new ArrayList<>();
        try {
            CodeToBlocks.ConvertResult conv = CodeToBlocks.convert(code);       // 纯解析：先拿到结果，确认可用再动画布
            blocks = conv.blocks;
            comments = conv.comments;
        } catch (Throwable t) {
            CLBC_Util.log("!!!! 代码→积木反解析失败，保留当前画布: " + t.getClass().getSimpleName());
            return;
        }
        if (!code.isEmpty() && blocks.isEmpty()) {
            CLBC_Util.log("!!!! 代码→积木反解析结果为空，保留当前画布（未清空积木）");
            return;
        }
        boolean wasFocused = (CLBC_GL_API.focusedTextField == CODE_EDITOR_ID);
        suppressCanvasSync = true;                    // 抑制回灌期间任何 syncCodeFromCanvas 回环
        try {
            // 收口1：编辑期回灌走专用入口——替换画布但保留撤销历史、不写盘、不触发代码面板回环。
            // loadProjectSource /「打开工程」仍用 importBlocks(blocks,true)，不在此改动。
            canvas.replaceBlocksLive(blocks, comments);
            CLBC_Util.log("代码编辑 → 积木回灌画布完成（" + blocks.size() + " 个积木）");
        } catch (Throwable t) {
            CLBC_Util.log("!!!! 代码→积木回灌画布失败，保留当前画布: " + t.getClass().getSimpleName());
        } finally {
            suppressCanvasSync = false;
            // 回灌若清掉了焦点，用户仍在编辑则恢复，不跳光标、不吞输入
            if (wasFocused) CLBC_GL_API.focusedTextField = CODE_EDITOR_ID;
        }
    }

    /** 单行代码轻量语法着色：关键字=ACCENT、字符串/字符=浅蓝、//注释=绿，其余=默认。逐 run 绘制（无换行）。 */
    private void drawCodeLine(String line, float x, float y, long kwCol, long strCol, long comCol, long base) {
        if (line == null) return;
        int ci = line.indexOf("//");
        String code = ci >= 0 ? line.substring(0, ci) : line;
        String comment = ci >= 0 ? line.substring(ci) : null;
        float cx = x;
        CLBC_GL_Font f = CLBC_GL_API.uiFont;
        int n = code.length(), i = 0;
        while (i < n) {
            char c = code.charAt(i);
            if (Character.isWhitespace(c)) { cx += CLBC_GL_API.measureText(f, " "); i++; continue; }
            if (c == '"' || c == '\'') {
                int end = code.indexOf(c, i + 1);
                if (end < 0) end = n - 1;
                String s = code.substring(i, end + 1);
                CLBC_GL_API.drawText(f, s, cx, y, strCol);
                cx += CLBC_GL_API.measureText(f, s);
                i = end + 1; continue;
            }
            int j = i;
            while (j < n && (Character.isLetterOrDigit(code.charAt(j)) || code.charAt(j) == '_' || code.charAt(j) == '.')) j++;
            if (j == i) j = i + 1;   // 标点/运算符等非单词字符：单字符前进，避免 i 原地踏步导致死循环
            String tok = code.substring(i, j);
            long col = JAVA_KW.contains(tok) ? kwCol : base;
            CLBC_GL_API.drawText(f, tok, cx, y, col);
            cx += CLBC_GL_API.measureText(f, tok);
            i = j;
        }
        if (comment != null) CLBC_GL_API.drawText(f, comment, cx, y, comCol);
    }

    /** 【#258】当前语言只读标签：优先项目创建/导入时写入的 project.language，
     *  否则按当前打开源文件扩展名推断（.py→Python，.java/其它→Java）。 */
    private String currentLangLabel() {
        try {
            if (currentProject != null && !currentProject.isEmpty()) {
                Path dir = Path.of(currentProject);
                if (!Files.isDirectory(dir)) dir = dir.getParent();
                if (dir != null) {
                    Path pcfg = dir.resolve("project.properties");
                    if (Files.isRegularFile(pcfg)) {
                        for (String ln : Files.readAllLines(pcfg, StandardCharsets.UTF_8)) {
                            String s = ln.trim();
                            if (s.startsWith("project.language=")) {
                                String v = s.substring("project.language=".length()).trim();
                                if (!v.isEmpty()) return v;
                            }
                        }
                    }
                }
            }
        } catch (Exception ignore) { /* 读不到就按文件扩展名推断 */ }
        String fn = codeFileName == null ? "" : codeFileName.toLowerCase();
        return fn.endsWith(".py") ? "Python" : "Java";
    }

    private void drawProjectTopBar(float regionX, int W) {
        CLBC_GL_API.fillRect(PANEL, 0, 0, W, 52);
        CLBC_GL_API.fillRect(BRAND, 0, 0, W, 3);                   // 红色标题细条
        CLBC_GL_API.fillRect(BRAND, 0, 0, 4, 52);                  // 左侧强调条
        CLBC_GL_API.fillRect(SEP, 0, 51, W, 1);                    // 底部分隔线
        float y = 12;
        if (CLBC_GL_Widgets.button(4001, 12, y, 48, 28, "返回", PANEL2, TEXT)) showMainMenu();

        String[] projItems = loadedProjects.toArray(new String[0]);
        if (loadedProjects.isEmpty()) projItems = new String[]{ currentProject == null ? "(无)" : currentProject };
        projectComboSel = CLBC_GL_Widgets.comboBox(4002, 68, y, 212, 28, projItems, projectComboSel,
                PANEL2, TEXT, PANEL2, TEXT);
        if (projItems.length > 0 && projectComboSel >= 0 && projectComboSel < projItems.length) {
            String sel = projItems[projectComboSel];
            if (!sel.equals(currentProject)) { currentProject = sel; refreshCodeText(); loadProjectSource(); currentRun = null; }
        }

        List<CLBC_RunInstance> runs = runHistory.getOrDefault(currentProject, new ArrayList<>());
        String[] runItems = new String[runs.size()];
        for (int i = 0; i < runs.size(); i++) runItems[i] = "运行 #" + runs.get(i).id + " (" + runs.get(i).status + ")";
        runComboSel = CLBC_GL_Widgets.comboBox(4003, 290, y, 160, 28, runItems, runComboSel, PANEL2, TEXT, PANEL2, TEXT);

        // 【#258】语言不在此处改：由项目创建/导入时决定（project.language）或当前源文件格式推出。
        // 这里只读展示，避免用户误以为切换下拉会改变解析语言（旧下拉是纯装饰，切了也不生效）。
        String langLabel = currentLangLabel();
        CLBC_GL_API.fillRoundRect(PANEL2, 460, y, 100, 28, 5);
        CLBC_GL_API.drawText(CLBC_GL_API.uiFont, langLabel, 470, y + 18, TEXT);
        CLBC_GL_API.drawText(CLBC_GL_API.uiFont, "▾", 542, y + 18, SUBTEXT);   // 保留下拉外观，但不可点

        // 内置字体缺 ▶/⏹ 字形会渲染成空方框，改用纯文字标签（语义不变）
        if (CLBC_GL_Widgets.button(4005, W - 220, y, 90, 28, "运行", GREEN, WHITE)) {
            List<CLBC_RunInstance> list = runHistory.computeIfAbsent(currentProject, k -> new ArrayList<>());
            int nextId = list.stream().mapToInt(i -> i.id).max().orElse(0) + 1;
            CLBC_RunInstance inst = new CLBC_RunInstance(nextId, currentProject);
            list.add(inst);
            currentRun = inst;
            CLBC_Util.log("启动运行 #" + nextId + " (项目: " + currentProject + ")");
            runCurrent();
        }
        // 规则06：次要操作弱化（面板底 + 红字），让主要操作"▶ 运行"保持唯一强填充
        if (CLBC_GL_Widgets.button(4006, W - 125, y, 90, 28, "停止", PANEL2, RED)) {
            if (!runs.isEmpty()) {
                CLBC_GL_Widgets.openContextMenu(4006, W - 125, y + 30,
                        runs.stream().map(r -> "运行 #" + r.id).toArray(String[]::new));
            } else CLBC_Util.log("没有可停止的运行");
        }
        // 顶栏"停止运行"右键菜单（id=4006）：仅当它是当前激活菜单时才消费，避免与画布菜单共用全局状态冲突
        if (CLBC_GL_Widgets.activePopupId() == 4006) {
            int stopPick = CLBC_GL_Widgets.drawContextMenu(CLBC_GL_API.rgba(255, 255, 255, 250), TEXT, ACCENT);
            if (stopPick >= 0 && stopPick < runs.size()) {
                CLBC_RunInstance inst = runs.get(stopPick);
                CLBC_Util.log("停止运行 #" + inst.id);
                if (inst.process != null) { inst.process.destroy(); inst.status = "已停止"; }
                runs.remove(inst);
                if (currentRun == inst) currentRun = runs.isEmpty() ? null : runs.get(0);
            }
        }
    }

    private void drawBottomTabs(float x, float y, float w, float h) {
        CLBC_GL_API.fillRect(PANEL, x, y, w, h);
        CLBC_GL_API.fillRect(SEP, x, y, w, 1);
        String[] tabs = {"属性", "输出", "问题"};
        // 【#259】底部标签文字偏上：自画 tab，baseline 比通用 drawTextCentered 再下沉几像素，
        // 并整体下移 y+8、加大高度到 32，让字在按钮里垂直居中偏下一点、看得清。
        float tx = x + 12, tw = 80, th = 32, ty = y + 8;
        for (int i = 0; i < tabs.length; i++) {
            float bxTab = tx + i * (tw + 8);
            long bg = (i == bottomTab) ? ACCENT : PANEL2;
            CLBC_GL_API.fillRoundRect(bg, bxTab, ty, tw, th, 6);
            String label = tabs[i];
            float labelW = CLBC_GL_API.measureText(CLBC_GL_API.uiFont, label);
            float labelH = CLBC_GL_API.uiFont.getPixelHeight();
            CLBC_GL_API.drawText(CLBC_GL_API.uiFont, label,
                    bxTab + (tw - labelW) / 2f,
                    ty + (th - labelH) / 2f + labelH * 0.92f,   // 0.92 比通用 0.75 下沉，文字不再贴顶
                    i == bottomTab ? WHITE : TEXT);
            if (CLBC_GL_Widgets.hit(bxTab, ty, tw, th) && CLBC_GL_API.mouseJustPressed) bottomTab = i;
        }
        // 右侧操作工具栏：撤销 / 重做 / 保存 / 全屏
        float by = y + 6;
        float bx = x + w - 12 - 80;
        if (CLBC_GL_Widgets.button(4154, bx, by, 80, 28, "全屏", PANEL2, TEXT)) CLBC_GL_API.toggleFullscreen();
        bx -= 88;
        if (CLBC_GL_Widgets.button(4153, bx, by, 80, 28, "保存", GREEN, WHITE)) saveProjectAction();
        bx -= 88;
        if (CLBC_GL_Widgets.button(4152, bx, by, 80, 28, "重做", PANEL2, TEXT)) canvas.redo();
        bx -= 88;
        if (CLBC_GL_Widgets.button(4151, bx, by, 80, 28, "撤销", PANEL2, TEXT)) canvas.undo();
        // 内容区
        float cy = y + 40, chh = h - 46;
        if (bottomTab == 1) {
            String out = (currentRun != null) ? currentRun.output.toString()
                    : "暂无运行。点右上角「运行」启动。  快捷键: Ctrl+Z 撤销 · Ctrl+Y 重做 · Ctrl+S 保存 · F11 全屏";
            CLBC_GL_API.setClip(x + 12, cy, w - 24, chh);
            CLBC_GL_API.drawText(CLBC_GL_API.uiFont, out, x + 12, cy, TEXT);
            CLBC_GL_API.clearClip();
        } else {
            CLBC_GL_API.drawText(CLBC_GL_API.uiFont, bottomTab == 0 ? "选中积木的属性" : "错误列表（空）", x + 12, cy, SUBTEXT);
        }
    }

    /** 保存工程：写入 <工程>/project.clbc（含缩略图），并记录为当前工程路径供 Ctrl+S 复用 */
    private void saveProjectAction() {
        if (currentProject == null) { CLBC_Util.log("没有可保存的工程"); return; }
        // 退出询问的「保存」也走这里：先把手上还没到回灌时机的代码编辑压进画布，
        // 否则最新一次输入不会进 project.clbc，退出后重开就丢了。
        if (codeDirty) { codeDirty = false; pushCodeToCanvas(); }
        String path = Path.of(currentProject, "project.clbc").toString();
        canvas.setAutosavePath(path);
        canvas.saveProject(path);
        codeDirty = false;                           // 防御：回灌期间若又置位，保存成功后一律清掉
    }

    /** 退出询问用：积木与代码两侧的合并未保存状态 */
    public boolean isDirty() { return codeDirty || canvas.isDirty(); }

    /** 退出询问选「保存」时的入口；返回是否真的保存了（无工程路径时 false，调用方应取消退出） */
    public boolean saveAllForExit() {
        if (currentProject == null) return false;
        saveProjectAction();
        return true;
    }

    /** 校验合法 Java 类名（支持中文；与原逻辑一致） */
    public static boolean isValidJavaClassName(String name) {
        if (name == null || name.isEmpty()) return false;
        return name.matches("^[a-zA-Z_\\u4e00-\\u9fa5][a-zA-Z0-9_\\u4e00-\\u9fa5]*$");
    }
}
