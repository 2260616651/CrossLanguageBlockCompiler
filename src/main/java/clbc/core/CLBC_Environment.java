package clbc.core;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import clbc.util.CLBC_Util;

/**
 * ⑥ 加载并识别编程环境。
 * 探测本机可用的语言运行时（JDK / Python / C++ 编译器），判定当前可用语言集合，
 * 并选出默认语言（CLBC 当前以 Java 为主，跨语言按蓝图后续接入）。
 *
 * 所有探测均为“超时保护的一次性外部进程”，失败一律静默降级 + 仅记日志
 * （错误定位只许用日志），绝不抛异常阻断启动；未检测到任何外部运行时也能离线工作
 * （CLBC 自带纯 JDK 自研解析器）。
 */
public final class CLBC_Environment {

    /** 目标语言枚举（兼顾之后 Python / C++ 跨语言扩展） */
    public enum Lang { JAVA, PYTHON, CPP }

    /** 单语言运行时探测结果 */
    public static final class RuntimeInfo {
        public final Lang lang;
        public final boolean available;
        public final String version;
        public final String path;
        public RuntimeInfo(Lang lang, boolean available, String version, String path) {
            this.lang = lang; this.available = available;
            this.version = version; this.path = path;
        }
        @Override public String toString() {
            return lang + (available ? " ✅ " + version : " ❌ 未检测到") + (path == null ? "" : " (" + path + ")");
        }
    }

    private static final List<RuntimeInfo> detected = new ArrayList<>();
    private static Lang current = Lang.JAVA;

    private CLBC_Environment() {}

    /** 探测全部语言运行时；结果缓存于本类，供 UI / 适配器查询 */
    public static List<RuntimeInfo> detect() {
        detected.clear();
        detected.add(probe(Lang.JAVA, "java", "-version"));
        detected.add(probe(Lang.PYTHON, "python", "--version"));
        detected.add(probe(Lang.CPP, "g++", "--version"));
        if (detected.stream().noneMatch(r -> r.available)) {
            // 补充探测别名：python3 / clang++ / cl
            RuntimeInfo py = probe(Lang.PYTHON, "python3", "--version");
            RuntimeInfo cpp = probe(Lang.CPP, "clang++", "--version");
            if (py.available) replace(py); else if (!detected.get(1).available) detected.set(1, py);
            if (cpp.available) replace(cpp); else if (!detected.get(2).available) detected.set(2, cpp);
        }
        boolean any = detected.stream().anyMatch(r -> r.available);
        current = any ? firstAvailable() : Lang.JAVA;
        StringBuilder sb = new StringBuilder("[Environment] 编程环境识别：");
        for (RuntimeInfo r : detected) sb.append("\n  - ").append(r);
        CLBC_Util.log(sb.toString());
        if (!any) CLBC_Util.log("[Environment] 未检测到外部语言运行时，CLBC 将以内置 Java 解析器离线工作");
        return detected;
    }

    private static void replace(RuntimeInfo info) {
        for (int i = 0; i < detected.size(); i++)
            if (detected.get(i).lang == info.lang) { detected.set(i, info); return; }
    }

    private static Lang firstAvailable() {
        for (RuntimeInfo r : detected) if (r.available) return r.lang;
        return Lang.JAVA;
    }

    public static List<RuntimeInfo> getDetected() { return detected; }
    public static Lang getCurrentLanguage() { return current; }
    public static void setCurrentLanguage(Lang l) {
        current = l;
        CLBC_Util.log("[Environment] 当前语言切换为: " + l);
    }

    // ---- 探测实现（超时保护，失败静默降级） ----

    private static RuntimeInfo probe(Lang lang, String cmd, String arg) {
        try {
            Process p = new ProcessBuilder(cmd, arg).redirectErrorStream(true).start();
            String out = drain(p, 3);
            boolean ok = !out.isEmpty();
            return new RuntimeInfo(lang, ok, parseVersion(out), ok ? which(cmd) : null);
        } catch (Throwable e) {
            CLBC_Util.log("[Environment] " + lang + " 探测失败: " + e.getMessage());
            return new RuntimeInfo(lang, false, "", null);
        }
    }

    /** 读取进程输出（带超时与上限，避免子进程挂死拖垮主程序） */
    private static String drain(Process p, int secs) {
        StringBuilder sb = new StringBuilder();
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                char[] buf = new char[256]; int n;
                while ((n = r.read(buf)) != -1) { sb.append(buf, 0, n); if (sb.length() > 2000) break; }
            } catch (Exception e) {
                CLBC_Util.log("[Environment] 读取子进程输出异常（已忽略）: " + e.getMessage());
            }
        });
        t.setDaemon(true); t.start();
        try {
            if (!p.waitFor(secs, TimeUnit.SECONDS)) p.destroyForcibly();
        } catch (Throwable e) {
            CLBC_Util.log("[Environment] 等待子进程超时/中断（已忽略）: " + e.getMessage());
        }
        try { t.join(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return sb.toString().trim();
    }

    private static String which(String cmd) {
        String whichCmd = System.getProperty("os.name", "").toLowerCase().contains("win") ? "where" : "which";
        try {
            Process p = new ProcessBuilder(whichCmd, cmd).redirectErrorStream(true).start();
            String out = drain(p, 2);
            return out.isEmpty() ? null : out.split("\n")[0].trim();
        } catch (Throwable e) {
            CLBC_Util.log("[Environment] 定位可执行文件失败（" + whichCmd + " " + cmd + "）: " + e.getMessage());
            return null;
        }
    }

    private static String parseVersion(String out) {
        if (out == null || out.isEmpty()) return "";
        for (String line : out.split("\n")) {
            if (line.matches(".*\\d+\\.\\d+.*")) return line.trim();
        }
        return out.split("\n")[0].trim();
    }
}
