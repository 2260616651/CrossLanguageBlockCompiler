package clbc.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Properties;

import clbc.util.CLBC_Util;
import clbc.core.CLBC_LanguageFiles;

/**
 * 首次启动的资源初始化器。
 * 依次确保：日志目录 / 日志文件 / 缓存目录 / 语言文件 / 配置文件 就绪。
 * 配置不存在时从 classpath 的 default-config.properties 拷贝；
 * 已存在则比对默认配置，补齐用户缺失的键（保持既有用户设置不被覆盖）。
 */
public class CLBC_Bootstrap {
    public void init() {
        CLBC_Util.log("[Bootstrap] 开始初始化运行环境");
        Path appDir = Path.of(".").toAbsolutePath().normalize();//获取程序所在根目录

        // ---- 日志目录 ----
        Path logs_a = appDir.resolve("logs");//得出地址
        try {
            Files.createDirectories(logs_a);
        } catch (IOException e) {
            CLBC_Util.Error_Dialog("日志文件夹创建失败:" + e.getMessage(),true);
        }//创建logs文件夹

        // ---- 带时间戳的日志文件 ----
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy_MM_dd_HH_mm_ss"));//得到时间
        Path logs_b = logs_a.resolve(timestamp + ".log");//得出地址
        try {
            // 同名日志文件（同秒内二次启动）直接复用：openLog 以追加模式打开，保持常驻占用，
            // 不再因 FileAlreadyExistsException 弹框后 System.exit 导致第二次启动打不开
            if (Files.notExists(logs_b)) {
                Files.createFile(logs_b);
            }
            CLBC_AppContext.logFile = logs_b;//将日志文件地址存入全局变量
            CLBC_Util.openLog(logs_b);//打开并常驻持有日志句柄（占用文件，防运行期被移动/删除）
            CLBC_Util.log("程序启动");
            CLBC_Util.log("日志文件创建成功：" + logs_b.getFileName());
        } catch (IOException e) {
            CLBC_Util.Error_Dialog("日志文件创建失败:" + e.getMessage(),true);
        }//创建.log文件

        // ---- 缓存目录 ----
        Path cache_a = appDir.resolve("cache");//得出地址
        ensureResource(() -> {
            Files.createDirectories(cache_a);
            CLBC_AppContext.cacheDir = cache_a;//将地址存入全局变量
        }, "缓存文件夹成功创建", "缓存文件夹创建失败", "缓存文件夹创建失败", true);//创建缓存文件夹

        // ---- 语言文件夹：启动时检查并生成（lang/ + mapping_file.json + 各 JDK 库文件）----
        // 必须放在最前面：它会触发 CLBC_BlockLibrary / CLBC_Lang 静态初始化，
        // 之后灌入的映射才能成为显示文本的最高优先级来源。
        CLBC_LanguageRegistry.init(appDir);

        // ---- 旧版单文件 lang.json（保留兼容；语言映射已改为 lang/ 文件夹）----
        Path lang = appDir.resolve("lang.json");//得出地址
        if (Files.notExists(lang)) {
            ensureResource(() -> Files.createFile(lang),
                    "语言文件创建成功", "语言文件创建失败", "语言文件创建失败", true);
        }//创建lang.json文件
        CLBC_AppContext.langFile = lang;//将地址存入全局变量

        // ⑦ 语言文件（每种目标语言一份本地对应表；缺失则生成默认模板）
        CLBC_LanguageFiles.ensureAll(appDir.resolve("lang"));

        // 加载完整标准库对应表（lang/java.json 的 libraries[]）供扩展展示库名/介绍/方法描述
        try {
            clbc.blocks.CLBC_Lang.loadLibraryFile(appDir.resolve("lang").resolve("java.json"));
        } catch (Throwable t) {
            CLBC_Util.log("加载标准库对应表失败: " + t.getMessage());
        }

        // ---- 配置文件（缺失则拷贝默认；存在则补齐键） ----
        Path config = appDir.resolve("config.properties");//得出地址
        if (Files.notExists(config)) {
            ensureResource(() -> {
                try (InputStream in = CLBC_Bootstrap.class.getResourceAsStream("/default-config.properties")) {
                    Files.copy(in, config);
                }
            }, "配置文件创建成功", "配置文件创建失败，设置将无法保存", "配置文件创建失败，设置将无法保存", false);
        }
        else {
            try {
                CLBC_Util.log("配置文件已存在，开始核对");

                // 1. 读默认配置（从 classpath）
                Properties defaults = new Properties();
                InputStream in = getClass().getResourceAsStream("/default-config.properties");
                if (in == null) {
                    CLBC_Util.Error_Dialog("默认配置资源缺失", true);
                }
                try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                    defaults.load(r);
                }

                // 2. 读用户配置（从磁盘文件）
                Properties user = new Properties();
                if (Files.exists(config)) {
                    try (Reader r = Files.newBufferedReader(config, StandardCharsets.UTF_8)) {
                        user.load(r);
                    }
                }

                // 3. 核对：默认里有的键，用户没有就补
                boolean needSave = false;
                for (String key : defaults.stringPropertyNames()) {
                    if (!user.containsKey(key)) {
                        user.setProperty(key, defaults.getProperty(key));
                        needSave = true;
                    }
                }

                // 4. 有补全就存回（UTF-8）
                if (needSave) {
                    try (Writer w = Files.newBufferedWriter(config, StandardCharsets.UTF_8)) {
                        user.store(w, "CLBC config");
                    }
                    CLBC_Util.log("配置已补齐");
                }
                else {
                    CLBC_Util.log("配置文件完整");
                }

            } catch (IOException e) {
                CLBC_Util.log("!!!!配置文件写入失败，设置将无法保存: " + e.getMessage());
                CLBC_Util.Error_Dialog("配置文件写入失败，设置将无法保存", false);
            }
        }
        CLBC_AppContext.configFile = config;//将地址存入全局变量
        // 日志配置：log.level 控制日志级别（DEBUG/INFO/WARN/ERROR）。
        // 发布版单一日志，logFine 内容以 DEBUG 级并入主日志；未配置或未知值保持默认。
        try {
            java.util.Properties logCfg = CLBC_Util.loadProperties(config);
            CLBC_Util.setLogLevel(logCfg.getProperty("log.level"));
        } catch (Throwable t) {
            CLBC_Util.log("!!!![Bootstrap] 读取日志配置失败，沿用默认设置: " + t.getMessage());
        }
        CLBC_Util.log("[Bootstrap] 运行环境初始化完成");
    }

    /** 建资源动作（允许抛出 IOException） */
    @FunctionalInterface
    private interface IoAction {
        void run() throws IOException;
    }

    /** 统一"建资源→记成功日志→失败记日志并弹窗"逻辑；建资源动作由调用方以 lambda 传入 */
    private static void ensureResource(IoAction createAction, String okMsg, String logFail, String dialogFail, boolean exitOnFail) {
        try {
            createAction.run();
            CLBC_Util.log(okMsg);
        } catch (IOException e) {
            CLBC_Util.log("!!!!" + logFail);
            CLBC_Util.Error_Dialog(dialogFail + ":" + e.getMessage(), exitOnFail);
        }
    }
}
