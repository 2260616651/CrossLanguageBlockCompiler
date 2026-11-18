package clbc.core;

import java.nio.file.Path;

/**
 * 全局静态态（运行期上下文）。
 * 集中存放跨模块共享的路径与界面参数，避免各模块各自持有副本。
 * 由各生命周期阶段（Bootstrap / JavaLocator / Renderer）写入，供全局读取。
 */
public class CLBC_AppContext {
    public static Path appDir;       // 程序根目录
    public static Path logFile;      // 当前主日志文件
    public static Path configFile;   // config.properties
    public static Path langFile;     // lang.json（旧版单文件，保留兼容）
    public static Path langDir;      // 语言文件夹 lang/
    public static Path mappingFile;  // lang/mapping_file.json（最高优先级）
    public static Path cacheDir;     // 缓存目录
    public static String javaHome;   // 解析到的 JDK 根目录
    public static int windowWidth;   // 窗口宽
    public static int windowHeight;  // 窗口高
    public static int zoomIndex = 4; // 代码区缩放档位（查 ZOOM_TABLE）
    public static double uiScale = 1.0; // UI 整体缩放倍率（按屏幕高度换算）
}
