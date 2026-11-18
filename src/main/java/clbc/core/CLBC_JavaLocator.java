package clbc.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import clbc.util.CLBC_Util;

/**
 * JDK 定位器（发布版：可移植自动探测）。
 *
 * 优先级：
 *   1. 当前运行 CLBC 的 JVM 根目录（System.getProperty("java.home")）——最可靠，随程序自带 JRE 也能用；
 *   2. 配置文件里记录的 java.home（仅当路径仍有效才采用，避免旧机器绝对路径失效）；
 *   3. 弹窗让用户选 java.exe（前两种都失败时的兜底）。
 * 探测结果写回配置，供下次启动与反编译模块复用；写入失败不阻断运行，只记日志。
 */
public class CLBC_JavaLocator {
    public void resolve() {
        CLBC_Util.log("[JavaLocator] 开始解析 JDK 位置");
        Properties props = CLBC_Util.loadProperties(CLBC_AppContext.configFile);
        String javaHome = null;

        // 1) 当前 JVM 根目录：程序用哪个 java 启动，就认定哪个，天然可移植、零配置
        String runtimeHome = System.getProperty("java.home");
        if (runtimeHome != null && !runtimeHome.isBlank() && looksLikeJdk(Path.of(runtimeHome))) {
            javaHome = Path.of(runtimeHome).toAbsolutePath().normalize().toString();
            CLBC_Util.log("[JavaLocator] 采用当前运行时 JDK: " + javaHome);
        }

        // 2) 配置里的旧记录：仅当路径仍存在才用（避免拷贝配置到别的机器后指向不存在的盘符）
        if (javaHome == null) {
            String saved = props.getProperty("java.home");
            if (saved != null && !saved.isBlank() && looksLikeJdk(Path.of(saved))) {
                javaHome = Path.of(saved).toAbsolutePath().normalize().toString();
                CLBC_Util.log("[JavaLocator] 采用配置中已记录且有效的 JDK: " + javaHome);
            }
        }

        // 3) 兜底：让用户选 java.exe，反推 JDK 根目录
        if (javaHome == null) {
            String selected = CLBC_Util.file_picker(
                    "选择 java.exe",
                    "java.exe",
                    "Java 可执行文件",
                    true
            );
            if (selected == null) return;   // exitOnCancel=true 已 System.exit
            Path javaExe = Path.of(selected);
            javaHome = javaExe.getParent().getParent().toString();
        }

        // 探测结果写回配置（失败不阻断：内存中的 javaHome 已可用）
        if (!javaHome.equals(props.getProperty("java.home"))) {
            props.setProperty("java.home", javaHome);
            if (!CLBC_Util.saveProperties(CLBC_AppContext.configFile, props)) {
                CLBC_Util.Error_Dialog("JDK 位置保存失败：下次启动可能需要重新选择 java.exe", false);
            }
        }
        CLBC_AppContext.javaHome = javaHome;
        CLBC_Util.log("使用 Java: " + javaHome);
    }

    /** 判定一个目录是否像 JDK/JRE 根目录：含 bin/java(.exe) 或 bin/javac(.exe) 即可。 */
    private static boolean looksLikeJdk(Path root) {
        try {
            if (root == null || !Files.isDirectory(root)) return false;
            Path bin = root.resolve("bin");
            String exe = System.getProperty("os.name", "").toLowerCase().contains("win") ? ".exe" : "";
            return Files.isRegularFile(bin.resolve("java" + exe))
                    || Files.isRegularFile(bin.resolve("javac" + exe));
        } catch (Throwable t) {
            return false;
        }
    }
}
