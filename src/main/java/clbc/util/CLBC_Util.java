package clbc.util;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;
import javax.imageio.ImageIO;

import clbc.core.CLBC_AppContext;

/**
 * 通用工具集（静态方法为主）。
 * 覆盖：日志输出、错误弹窗、UI 缩放档位、图片文字渲染、文件/文件夹选择、
 * 配置读写（Properties，UTF-8）、项目历史、Java 项目创建、隐藏目录等。
 * 几乎所有模块都依赖本类的 {@link #log(String)} 与配置读写方法。
 */
public class CLBC_Util {

    public static boolean logDisabled = false;
    private static boolean logWarned = false;//设置俩布尔变量存是否进入了无变量模式
    /** 常驻日志写入句柄：启动 openLog 时打开并一直持有，占用文件防止被移动/删除导致写失败 */
    private static Writer logWriter = null;
    /** 日志文件路径：写失败后据此重建常驻句柄，保持占用设计不变 */
    private static Path logPath = null;
    /** 日志批量刷盘：写入先攒在 BufferedWriter（内存），每 24 行或 WARN+ 才落盘一次，
     *  避免启动期每行 flush 造成数十次同步磁盘写（"日志先缓存着"）。退出时 closeLog 强制刷盘。 */
    private static int logFlushCounter = 0;
    private static final int LOG_FLUSH_EVERY = 24;

    // ===== 单一日志：全部写入主 .log =====
    // log 与 logFine 共用同一主日志句柄；logFine 作为兼容出口保留 54 处调用点，
    // 内容以 DEBUG 级别归入主日志（受 log.level 过滤），默认不回显 stdout，避免刷屏。
    // 发布版不再产生独立 .fine.log 文件。

    // ===== D 组：日志级别 / 时间戳 / 来源 / 可配置过滤（既有 log(String) 调用点全部不改）=====
    public static final int LOG_DEBUG = 0, LOG_INFO = 1, LOG_WARN = 2, LOG_ERROR = 3;
    /** 可配置过滤阈值：低于该级别的日志不落盘/不打印。默认 DEBUG=0，保证既有调用点内容、顺序全部保留。
     *  由 CLBC_Bootstrap 依据 config.properties 的 log.level 调用 setLogLevel 设定。 */
    public static volatile int logMinLevel = LOG_DEBUG;
    private static final DateTimeFormatter LOG_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // ===== C 组：输入规模上限（8GB 机器防 OOM）=====
    public static final int MAX_PASTE_CHARS = 200_000;                    // 单次粘贴字符上限
    public static final long MAX_INPUT_FILE_BYTES = 256L * 1024 * 1024;  // 输入文件（jar/class/配置/语言）字节上限

    /** saveConfig 失败弹窗节流：只弹一次，后续仅记日志（addProjectHistory 为高频路径） */
    private static boolean configSaveWarned = false;
    public static double uiScale = 1.0;
    private static final float[] ZOOM_TABLE = {0, 0.25f, 0.5f, 0.75f, 1.0f, 1.5f, 2.0f, 3.0f, 4.0f, 6.0f};
    public static int getZoomIndex() {return CLBC_AppContext.zoomIndex;}
    // 拿当前档位
    public static void zoomIn() {
        if (CLBC_AppContext.zoomIndex < ZOOM_TABLE.length - 1) {
            CLBC_AppContext.zoomIndex++;
            log("代码区缩放倍率已加大");
        }else {log("代码区缩放倍率已最大");}
    }// 放大
    public static void zoomOut() {
        if (CLBC_AppContext.zoomIndex > 1) {
            CLBC_AppContext.zoomIndex--;
            log("代码区缩放倍率已减小");
        }else {log("代码区缩放倍率已最小");}
    }// 缩小

    /** 错误弹窗；exit=true 时弹完直接退出进程 */
    public static void Error_Dialog(String message,boolean exit){
        TinyFileDialogs.tinyfd_messageBox(
                "程序错误/崩溃",     // 标题，中文也行
                message,   // 内容，你传进来的
                "ok",      // 一个确定按钮
                "error",   // 错误图标
                1          // 默认选第一个
        );
        if (exit) {
            System.exit(1);
        }
    }

    /** 退出确认三态弹窗（tinyfd 的 yesnocancel 原生支持，无需降级到 yesno）。
     *  @return 0=保存并退出（Yes） 1=不保存直接退出（No） 2=取消退出（Cancel / 弹窗异常）
     *  tinyfd 官方头文件：messageBox 返回 0=cancel/no，1=ok/yes，2=no（yesnocancel 下的第二个按钮）。
     *  故 yesnocancel 三态为 0=Cancel、1=Yes、2=No；其它/异常值一律按 Cancel 处理（保守，不误丢数据）。 */
    public static int confirmSaveOnExit() {
        int r;
        try {
            r = TinyFileDialogs.tinyfd_messageBox(
                    "退出 CLBC",
                    "当前工程有未保存的改动。\n\n保存后退出？\n「否」将丢弃改动，「取消」返回继续编辑。",
                    "yesnocancel", "question", 1);
        } catch (Throwable t) {
            log("退出确认弹窗失败，按「取消」处理: " + t);
            return 2;
        }
        if (r == 1) return 0;   // Yes → 保存并退出
        if (r == 2) return 1;   // No  → 不保存直接退出
        return 2;               // Cancel(0) / 未知值 / 异常 → 取消退出（保守，绝不误丢数据）
    }

    /** 打开并常驻持有日志文件句柄（占用文件，防止运行期被移动/删除导致写失败）。失败则进入无日志模式。 */
    public static void openLog(Path p) {
        logPath = p;
        try {
            // 追加模式打开，句柄全程持有；Windows 下文件被标记为"占用"，无法被移动/删除
            logWriter = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(p.toFile(), true), StandardCharsets.UTF_8));
            Runtime.getRuntime().addShutdownHook(new Thread(CLBC_Util::closeLog));
        } catch (IOException e) {
            logWriter = null;
            logDisabled = true;
            System.err.println("[log] 日志文件打开失败，进入无日志模式: " + e.getMessage());
        }
    }

    /** 关闭常驻日志句柄（JVM 退出时由 shutdown hook 调用，亦可由显式 shutdown 调用） */
    public static void closeLog() {
        if (logWriter != null) {
            try { logWriter.flush(); logWriter.close(); } catch (IOException ignored) {}
            logWriter = null;
        }
    }

    /** 写失败后按原路径重建常驻句柄（不重复注册 shutdown hook），供磁盘恢复后自动重试 */
    private static boolean reopenLogWriter() {
        if (logPath == null) return false;
        try {
            logWriter = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(logPath.toFile(), true), StandardCharsets.UTF_8));
            return true;
        } catch (IOException e) {
            logWriter = null;
            return false;
        }
    }

    /** 兼容出口：既有 185+ 处 log(String) 调用点一律不改。
     *  在新出口里做兼容——自动从消息里收敛 "!!!!"（严重→ERROR）与 "[模块]"（分类→来源），
     *  补上级别、时间戳、来源，并按 logMinLevel 过滤；原消息文本与调用顺序保持不变。 */
    public static void log(String msg) {
        String m = msg == null ? "" : msg;
        int level = LOG_INFO;
        String source = callerSource();
        if (m.startsWith("!!!!")) { level = LOG_ERROR; m = m.substring(4); }
        m = m.stripLeading();
        if (m.startsWith("[")) {
            int close = m.indexOf(']');
            if (close > 1) { source = m.substring(1, close); m = m.substring(close + 1).stripLeading(); }
        }
        log(level, source, m);
    }

    /** 新出口（指定级别）：来源自动取调用方类简名 */
    public static void log(int level, String msg) {
        log(level, callerSource(), msg);
    }

    /** 新出口（指定级别 + 来源）：结构化落盘/打印的唯一出口，常驻句柄 + 写失败重建 + 弹窗只弹一次 */
    public static synchronized void log(int level, String source, String msg) {
        if (level < logMinLevel) return;   // 可配置过滤（默认不过滤）
        String body = msg == null ? "" : msg;
        String line = buildLogLine(level, source, body);
        if (!logDisabled) {
            if (logWriter == null) reopenLogWriter();
            if (logWriter != null) {
                try {
                    logWriter.write(line);
                    logWriter.write("\n");
                    // 批量刷盘：攒够 24 行或 WARN/ERROR 立即落盘；stdout 每行即时可见。
                    // 启动期 60+ 行日志由此从 60+ 次同步写降到 2~3 次。
                    if (level >= LOG_WARN || ++logFlushCounter >= LOG_FLUSH_EVERY) {
                        logWriter.flush();
                        logFlushCounter = 0;
                    }
                } catch (IOException e) {
                    if (!logWarned) {
                        logWarned = true;
                        Error_Dialog("日志写入失败，将进入无日志模式", false);
                    }
                    // 关闭坏句柄但不永久禁用：后续 log 会重建句柄重试，磁盘恢复后自动续写；弹窗仍只弹一次
                    try { logWriter.close(); } catch (IOException ignored) {}
                    logWriter = null;
                }
            }
        }
        System.out.println(line);
    }

    /** 构造一条带时间戳/级别/来源前缀的日志行；多行消息每行都带相同前缀（含 \n 的后续行不"裸奔"） */
    private static String buildLogLine(int level, String source, String body) {
        String prefix = LocalDateTime.now().format(LOG_TS)
                + " [" + levelName(level) + "] ["
                + (source == null || source.isEmpty() ? "main" : source) + "] ";
        String[] bodyLines = body.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bodyLines.length; i++) {
            if (i > 0) sb.append('\n');
            sb.append(prefix).append(bodyLines[i]);
        }
        return sb.toString();
    }

    // ==================== logFine 兼容出口：内容并入主日志 ====================
    /** 兼容出口：原有 54 处 logFine 调用点一律不改。
     *  自动收敛 "!!!!"（ERROR）与 "[模块]"（来源），随后以 DEBUG 级别写入主 .log。
     *  受 log.level 统一过滤；发布版不再产生独立 .fine.log。 */
    public static void logFine(String msg) {
        String m = msg == null ? "" : msg;
        int level = LOG_DEBUG;
        String source = callerSource();
        if (m.startsWith("!!!!")) { level = LOG_ERROR; m = m.substring(4); }
        m = m.stripLeading();
        if (m.startsWith("[")) {
            int close = m.indexOf(']');
            if (close > 1) { source = m.substring(1, close); m = m.substring(close + 1).stripLeading(); }
        }
        logFine(level, source, m);
    }

    /** logFine（指定级别）：来源自动取调用方类简名 */
    public static void logFine(int level, String msg) {
        logFine(level, callerSource(), msg);
    }

    /** 唯一结构化出口：与主日志共用一个句柄；不打 stdout（避免排障细节刷屏），写失败按主日志同一策略重建句柄。 */
    public static synchronized void logFine(int level, String source, String msg) {
        if (level < logMinLevel) return;
        String line = buildLogLine(level, source, msg == null ? "" : msg);
        if (!logDisabled) {
            if (logWriter == null) reopenLogWriter();
            if (logWriter != null) {
                try {
                    logWriter.write(line);
                    logWriter.write("\n");
                    // 与主日志同一批量刷盘策略（logFine 多为 DEBUG，攒批落盘）
                    if (level >= LOG_WARN || ++logFlushCounter >= LOG_FLUSH_EVERY) {
                        logWriter.flush();
                        logFlushCounter = 0;
                    }
                } catch (IOException e) {
                    if (!logWarned) {
                        logWarned = true;
                        Error_Dialog("日志写入失败，将进入无日志模式", false);
                    }
                    try { logWriter.close(); } catch (IOException ignored) {}
                    logWriter = null;
                }
            }
        }
    }

    /** 可配置过滤：DEBUG/INFO/WARN/ERROR；未知值保持默认，不猜 */
    public static void setLogLevel(String name) {
        if (name == null) return;
        switch (name.trim().toUpperCase()) {
            case "DEBUG" -> logMinLevel = LOG_DEBUG;
            case "INFO"  -> logMinLevel = LOG_INFO;
            case "WARN"  -> logMinLevel = LOG_WARN;
            case "ERROR" -> logMinLevel = LOG_ERROR;
            default -> { }
        }
    }

    private static String levelName(int level) {
        return switch (level) {
            case LOG_DEBUG -> "DEBUG";
            case LOG_WARN  -> "WARN";
            case LOG_ERROR -> "ERROR";
            default        -> "INFO";
        };
    }

    /** 来源：取调用栈里第一个非本类的类简名（既有调用点无需改动即可获得来源） */
    private static String callerSource() {
        for (StackTraceElement e : new Throwable().getStackTrace()) {
            String cn = e.getClassName();
            if (cn.equals(CLBC_Util.class.getName())) continue;
            String s = cn.substring(cn.lastIndexOf('.') + 1);
            int d = s.indexOf('$'); if (d > 0) s = s.substring(0, d);
            int slash = s.indexOf('/'); if (slash > 0) s = s.substring(0, slash);
            return s.isEmpty() ? "main" : s;
        }
        return "main";
    }

    /** 离屏渲染一段文字为 BufferedImage（白字透明底），供需要位图的场景使用 */
    public static BufferedImage renderText(int size, String text) {
        // 1. 临时 1×1 图，拿 FontMetrics
        BufferedImage tmp = new BufferedImage(1, 1, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g0 = tmp.createGraphics();
        Font font = new Font("Microsoft YaHei", Font.PLAIN, size);
        FontMetrics fm = g0.getFontMetrics(font);
        g0.dispose();

        // 2. 算宽高
        int w = fm.stringWidth(text);
        int h = fm.getHeight();

        // 3. 正式图
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();

        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        g.setFont(font);
        g.setColor(Color.WHITE);
        g.drawString(text, 0, fm.getAscent());

        g.dispose();

        return img;
    }

    // CLBC_AppContext
    public static float getZoom() {
        return ZOOM_TABLE[CLBC_AppContext.zoomIndex];
    }

    /** 弹文件选择框；取消时按 exitOnCancel 决定退出或返回 null */
    public static String file_picker(String title, String filterName, String filterDesc, boolean exitOnCancel) {
        String selected;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer filters = stack.mallocPointer(1);
            filters.put(stack.UTF8(filterName));
            filters.flip();
            selected = TinyFileDialogs.tinyfd_openFileDialog(title, null, filters, filterDesc, false);
        }

        if (selected == null) {
            return handleUserCancel(title, exitOnCancel);
        }
        log("选择文件: " + selected);
        return selected;
    }

    /** 弹文件选择框（多个扩展名过滤，如 {"*.class","*.jar"}）；取消时返回 null */
    public static String file_picker_multi(String title, String[] patterns, String filterDesc, boolean exitOnCancel) {
        String selected;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer filters = stack.mallocPointer(patterns.length);
            for (String p : patterns) filters.put(stack.UTF8(p));
            filters.flip();
            selected = TinyFileDialogs.tinyfd_openFileDialog(title, null, filters, filterDesc, false);
        }
        if (selected == null) return handleUserCancel(title, exitOnCancel);
        log("选择文件: " + selected);
        return selected;
    }

    /** 弹文件夹选择框；取消时按 exitOnCancel 决定退出或返回 null */
    public static String folder_picker(String title, boolean exitOnCancel) {
        String selected = TinyFileDialogs.tinyfd_selectFolderDialog(title, null);
        if (selected == null) {
            return handleUserCancel(title, exitOnCancel);
        }
        log("选择文件夹: " + selected);
        return selected;
    }

    /** 统一的"用户取消"处理：记日志、按需退出、返回 null（原 file_picker/folder_picker 重复块收口） */
    private static String handleUserCancel(String title, boolean exitOnCancel) {
        log("用户取消选择: " + title);
        if (exitOnCancel) {
            log("程序正常退出");
            System.exit(0);
        }
        return null;
    }

    /** 读取项目历史（config.properties 中 project.history.N 形式的键） */
    public static List<String> getProjectHistory() {
        Properties props = loadConfig();
        List<String> list = new ArrayList<>();
        int i = 1;
        while (true) {
            String p = props.getProperty("project.history." + i);
            if (p == null) break;
            list.add(p);
            i++;
        }
        return list;
    }

    /** 把路径加入项目历史（去重 + 置顶 + 持久化） */
    public static void addProjectHistory(String path) {
        CLBC_Util.log("添加项目历史: " + path);
        java.util.List<String> list = getProjectHistory();

        list.remove(path);
        list.add(0, path);

        java.util.Properties props = loadConfig();

        java.util.List<String> toRemove = new java.util.ArrayList<>();
        for (String key : props.stringPropertyNames()) {
            if (key.startsWith("project.history.")) {
                toRemove.add(key);
            }
        }
        for (String key : toRemove) {
            props.remove(key);
        }

        for (int i = 0; i < list.size(); i++) {
            props.setProperty("project.history." + (i + 1), list.get(i));
        }

        saveConfig(props);
    }

    /** 从 UTF-8 文件载入 Properties；失败记日志并返回空对象（file_picker 等处的重复读盘块收口于此） */
    public static Properties loadProperties(Path file) {
        Properties props = new Properties();
        // C 组：超长配置/工程属性文件读入前做 size 检查，超限记日志并安全拒绝，别 OOM
        try {
            if (file != null && Files.size(file) > MAX_INPUT_FILE_BYTES) {
                log("!!!![Util] 配置文件过大，已拒绝读取: " + file + " (" + Files.size(file) + " 字节)");
                return props;
            }
        } catch (IOException e) {
            log("!!!![Util] 无法读取配置文件大小: " + e.getMessage());
            return props;
        }
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            props.load(r);
        } catch (IOException e) {
            log("!!!!读配置失败: " + e.getMessage());
        }
        return props;
    }

    /** 把 Properties 以 UTF-8 存入文件；成功返回 true，失败记日志并返回 false */
    public static boolean saveProperties(Path file, Properties props) {
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            props.store(w, "CLBC config");
            return true;
        } catch (IOException e) {
            log("!!!!存配置失败: " + e.getMessage());
            return false;
        }
    }

    /** 读全局配置（config.properties） */
    private static Properties loadConfig() {
        return loadProperties(CLBC_AppContext.configFile);
    }

    /** 写全局配置，成功记日志；失败补日志并给界面可感知反馈（弹窗只弹一次，避免高频路径反复打扰） */
    private static void saveConfig(Properties props) {
        if (saveProperties(CLBC_AppContext.configFile, props)) {
            log("配置写入成功");
        } else {
            log("!!!!配置写入失败，设置或项目历史未能落盘");
            if (!configSaveWarned) {
                configSaveWarned = true;
                Error_Dialog("配置保存失败：设置或项目历史可能未生效（后续仅记日志，不再弹框）", false);
            }
        }
    }

    /** 创建 Java 项目，成功返回项目路径，失败返回 null */
    public static String createJavaProject(String dir, String name) {
        CLBC_Util.log("创建 Java 项目: dir=" + dir + ", name=" + name);
        // 1. 校验项目名合法
        if (name.contains("\\") || name.contains("/") ||
                name.contains(":")  || name.contains("*") ||
                name.contains("?")  || name.contains("\"") ||
                name.contains("<")  || name.contains(">") ||
                name.contains("|")) {
            log("!!!!项目名含非法字符: " + name);
            return null;
        }

        Path projectDir = Path.of(dir, name);

        // 2. 防覆盖
        if (Files.exists(projectDir)) {
            log("!!!!项目已存在: " + projectDir);
            return null;
        }

        try {
            // 3. 建目录
            Files.createDirectories(projectDir);
            Files.createDirectories(projectDir.resolve("src"));
            ensureClbcDir(projectDir.toString());

            // 4. 写 project.clbc
            Properties props = new Properties();
            props.setProperty("project.name", name);
            props.setProperty("project.language", "java");
            props.setProperty("project.created", java.time.LocalDateTime.now().toString());

            try (Writer w = Files.newBufferedWriter(
                    projectDir.resolve("project.clbc"), StandardCharsets.UTF_8)) {
                props.store(w, "CLBC project");
            }

            // 5. 写 src/Main.java
            String mainCode =
                    "public class Main {\n" +
                            "    public static void main(String[] args) {\n" +
                            "        System.out.println(\"Hello, CLBC!\");\n" +
                            "    }\n" +
                            "}\n";
            Files.writeString(projectDir.resolve("src").resolve("Main.java"),
                    mainCode, StandardCharsets.UTF_8);

            log("项目创建成功: " + projectDir);
            return projectDir.toString();
        } catch (IOException e) {
            log("!!!!项目创建失败: " + e.getMessage());
            return null;
        }
    }

    /** 保存单个设置项到 config.properties */
    /** 写入系统剪贴板（AWT 实现）；无显示环境降级为写临时文件并记日志，绝不抛异常中断 UI */
    public static void copyToClipboard(String text) {
        if (text == null) return;
        if (text.length() > MAX_PASTE_CHARS) {
            log("!!!![Util] 复制内容过长，已拒绝（" + text.length() + " 字符）");
            return;
        }
        try {
            java.awt.Toolkit tk = java.awt.Toolkit.getDefaultToolkit();
            java.awt.datatransfer.Clipboard cb = tk.getSystemClipboard();
            java.awt.datatransfer.StringSelection sel = new java.awt.datatransfer.StringSelection(text);
            cb.setContents(sel, null);
            log("已复制到剪贴板（" + text.length() + " 字符）");
        } catch (Throwable t) {
            try {
                Path dir = clipboardFallbackDir();
                Files.createDirectories(dir);
                Path f = dir.resolve("clipboard.txt");
                Files.writeString(f, text, StandardCharsets.UTF_8);
                log("剪贴板不可用，已写入: " + f);
            } catch (Exception e2) {
                log("!!!! 复制失败: " + e2.getMessage());
            }
        }
    }

    /** 剪贴板降级落盘目录：可配置（config.properties 的 clipboard.dir）→ 相对程序目录 run_tmp
     *  → 系统临时目录兜底。取代写死的 F:/workbuddy/run_tmp，换机器或无 F 盘都不失败。 */
    private static Path clipboardFallbackDir() {
        try {
            Path cfg = CLBC_AppContext.configFile;
            if (cfg != null && Files.isRegularFile(cfg)) {
                String d = loadProperties(cfg).getProperty("clipboard.dir");
                if (d != null && !d.isBlank()) return Path.of(d);
            }
        } catch (Throwable t) {
            log("[Util] 读取 clipboard.dir 配置失败，改用相对/临时目录: " + t.getMessage());
        }
        Path app = CLBC_AppContext.appDir;
        if (app != null) return app.resolve("run_tmp");
        return Path.of(System.getProperty("java.io.tmpdir", "."), "clbc");
    }

    public static void saveSetting(String key, String value) {
        Properties props = loadConfig();
        props.setProperty(key, value);
        saveConfig(props);
    }

    /** 创建 .clbc 隐藏目录（如果不存在） */
    public static Path ensureClbcDir(String projectPath) {
        Path dir = Path.of(projectPath, ".clbc");
        try {
            Files.createDirectories(dir);
            hideDir(dir);
        } catch (IOException e) {
            log("!!!!创建 .clbc 目录失败: " + e.getMessage());
        }
        return dir;
    }

    /** Windows 上加隐藏属性 */
    public static void hideDir(Path dir) {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("win")) return;
        try {
            Files.setAttribute(dir, "dos:hidden", true);
        } catch (Exception e) {
            log("!!!!隐藏目录失败: " + e.getMessage());
        }
    }

    /** 计算并更新全局的 UI 缩放倍率 */
    public static void updateUiScale(double screenHeight) {
        // 以 1080P 高度为基准 1.0
        CLBC_AppContext.uiScale = screenHeight / 1080.0;
    }
}
