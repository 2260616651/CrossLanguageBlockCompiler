package clbc.ui;

import clbc.util.CLBC_Util;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 背景音乐播放器（零第三方 jar 依赖）。
 *
 * 两级播放链：
 *  ① JDK 原生 {@code javax.sound.sampled}：WAV / AU / AIFF 直接循环播放；
 *  ② 外部 ffplay（自动探测 PATH、程序自带目录、ffmpeg 常见安装路径；Windows 用 .exe）：
 *     JDK 不支持的 MP3 / OGG / FLAC / M4A / AAC / WMA 以及【视频文件里的音轨】
 *     一律交给 ffplay：{@code -vn} 只取声音、{@code -nodisp} 不开视频窗、{@code -loop 0} 无限循环。
 *
 * 为什么视频用 ffplay 而不是「ffmpeg 解码 → 管道喂 JDK Clip」：
 *  后者要自行协商采样率/声道/样本格式并持续写 SourceDataLine，缓冲、 seek、同步都很脆弱；
 *  ffplay 是 ffmpeg 官方自带的播放器，一行命令即可「只播声音、无窗口、可循环」，最稳。
 *  纯 JDK 没有 MP3/OGG 解码器，这类格式必须依赖外部播放器——这是客观限制，不假装支持。
 *
 * 只播放用户在设置里指定的本地路径——程序本身不携带、不分发任何媒体文件（避免侵权）。
 * 单例静态入口，同一时刻只有一条音轨，切换/停止会先关掉旧的（Clip 或子进程），且子进程必被回收。
 */
public final class CLBC_Audio {

    private CLBC_Audio() {}

    private static Clip current = null;                 // ① JDK 原生音轨
    private static Process externalProc = null;         // ② 外部 ffplay 进程
    private static String currentPath = "";
    private static boolean externalMode = false;        // 当前是否走外部 ffplay（音量需特殊处理）
    private static String lastPath = "";                // 暂停前曲目（供继续播放）
    private static float lastVolume = 0.6f;             // 暂停前音量
    private static boolean paused = false;              // 是否处于暂停态

    /** ffplay 路径探测缓存：null=未探测，""=确认没有，其他=可用路径（避免每次播放都 fork 探测进程） */
    private static String ffplayCached = null;

    static {
        // JVM 退出兜底：即使 CLBC.java 的正常清理路径没走到（异常/强制关闭），
        // 也尽量回收外部 ffplay 子进程，避免残留僵尸进程。与正常 stop() 幂等。
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { stop(); } catch (Throwable ignore) { /* 关机阶段日志组件可能已关闭，忽略 */ }
        }, "clbc-audio-cleanup"));
    }

    /** 播放（循环）；path 为空或加载失败则停止并记日志。volume 0~1。 */
    public static synchronized void play(String path, float volume) {
        stop();
        if (path == null || path.trim().isEmpty()) return;
        File f = new File(path.trim());
        if (!f.isFile()) {
            CLBC_Util.log("[BGM] 媒体文件不存在: " + path);
            return;
        }

        // ① 先试 JDK 原生（WAV / AU / AIFF）
        try (AudioInputStream in = AudioSystem.getAudioInputStream(f)) {
            Clip clip = AudioSystem.getClip();
            clip.open(in);
            setVolumeOn(clip, volume);
            clip.loop(Clip.LOOP_CONTINUOUSLY);
            current = clip;
            currentPath = path;
            externalMode = false;
            paused = false;
            lastPath = path;
            lastVolume = volume;
            CLBC_Util.log("[BGM] 原生播放（循环）: " + path + "（音量 " + Math.round(volume * 100) + "%）");
            return;
        } catch (Exception nativeFail) {
            CLBC_Util.log("[BGM] JDK 原生不支持该格式，尝试外部播放器: " + path);
        }

        // ② JDK 不支持 → 外部 ffplay（覆盖 mp3/ogg/flac/m4a/wma/视频音轨等）
        String ffplay = findFfplay();
        if (ffplay.isEmpty()) {
            CLBC_Util.log("[BGM] 未找到 ffplay/ffmpeg，无法播放该格式。纯 JDK 只能解码 WAV/AU/AIFF，"
                    + "MP3/OGG/FLAC/M4A/WMA 及视频音轨需要外部播放器 ffplay。"
                    + "请安装 ffmpeg（含 ffplay）并把 bin 目录加入 PATH，或改用 WAV。");
            return;
        }
        try {
            // -vn 只取音轨；-nodisp 不开视频窗口；-autoexit 播完退出；
            // -loop 0 无限循环（ffplay 文档明确：0 means forever）；-volume 0~100
            String[] cmd = {
                    ffplay, "-nodisp", "-autoexit", "-vn", "-loop", "0",
                    "-volume", String.valueOf(Math.max(0, Math.min(100, Math.round(volume * 100)))),
                    f.getAbsolutePath()
            };
            ProcessBuilder pb = new ProcessBuilder(cmd);
            // 隐藏子进程输出，避免污染日志；错误也一并丢弃
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            externalProc = pb.start();
            currentPath = path;
            externalMode = true;
            paused = false;
            lastPath = path;
            lastVolume = volume;
            CLBC_Util.log("[BGM] 外部播放（ffplay，循环）: " + path + "（音量 " + Math.round(volume * 100) + "%）");
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            CLBC_Util.log("[BGM] 外部播放失败: " + msg);
            externalProc = null;
        }
    }

    /**
     * 探测 ffplay：用户覆盖变量 → PATH → 程序自带目录/工作目录 → ffmpeg 常见安装路径 →
     * 由 PATH 中的 ffmpeg 推导同目录 ffplay（ffmpeg 官方包两者同放一个 bin）。带 3 秒超时。
     */
    private static String findFfplay() {
        if (ffplayCached != null) return ffplayCached;

        boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
        List<String> candidates = new ArrayList<String>();

        // 0) 用户显式覆盖（最高优先级，便于自定义/绿色版）
        String override = System.getenv("CLBC_FFPLAY");
        if (override != null && !override.trim().isEmpty()) candidates.add(override.trim());

        // 1) PATH（Windows 上 ProcessBuilder("ffplay") 会自动找 ffplay.exe）
        candidates.add("ffplay");

        // 2) 程序自带目录 / 当前工作目录（自带播放器时放这里即可）
        candidates.add(exeIn(safeCodeSourceDir(), "ffplay", win));
        candidates.add(exeIn(System.getProperty("user.dir"), "ffplay", win));

        // 3) 各平台常见安装路径
        candidates.add("/usr/bin/ffplay");
        candidates.add("/usr/local/bin/ffplay");
        if (win) {
            String[] roots = {
                    "C:\\ffmpeg", "D:\\ffmpeg", "E:\\ffmpeg", "F:\\ffmpeg",
                    "C:\\Program Files\\ffmpeg", "C:\\Program Files (x86)\\ffmpeg",
                    "D:\\Program Files\\ffmpeg", "D:\\Program Files (x86)\\ffmpeg",
                    System.getenv("LOCALAPPDATA") == null ? null
                            : System.getenv("LOCALAPPDATA") + "\\Programs\\ffmpeg",
                    System.getenv("USERPROFILE") == null ? null
                            : System.getenv("USERPROFILE") + "\\scoop\\apps\\ffmpeg\\current",
                    "C:\\ProgramData\\chocolatey\\bin"
            };
            for (String r : roots) {
                if (r == null || r.trim().isEmpty()) continue;
                // chocolatey 的 ffplay 直接在 bin 下，其余按 <root>\bin
                candidates.add(exeIn(r.endsWith("\\bin") ? r : r + "\\bin", "ffplay", win));
            }
        }

        // 4) 只装了 ffmpeg 没装 ffplay：从 ffmpeg 所在目录推导同目录 ffplay
        String ffmpeg = probeFirstTrue(win ? new String[]{"ffmpeg", "ffmpeg.exe"} : new String[]{"ffmpeg"});
        if (ffmpeg != null) {
            File parent = new File(ffmpeg).getParentFile();
            if (parent != null) candidates.add(exeIn(parent.getAbsolutePath(), "ffplay", win));
        }

        for (String c : candidates) {
            if (c == null || c.trim().isEmpty()) continue;
            String exe = c.trim();
            if (probe(exe)) {
                ffplayCached = exe;
                CLBC_Util.log("[BGM] 探测到外部播放器: " + exe);
                return exe;
            }
        }
        ffplayCached = "";   // 确认没有，缓存空串避免每次都 fork 探测进程
        CLBC_Util.log("[BGM] 未探测到 ffplay（PATH / 程序目录 / 常见安装路径均未找到）");
        return ffplayCached;
    }

    /** 目录 + 可执行文件名拼接，Windows 自动补 .exe；目录为空返回 null */
    private static String exeIn(String dir, String name, boolean win) {
        if (dir == null || dir.trim().isEmpty()) return null;
        return new File(dir, name + (win ? ".exe" : "")).getAbsolutePath();
    }

    /** 本类所在 jar/classes 的上级目录（程序自带目录） */
    private static String safeCodeSourceDir() {
        try {
            File loc = new File(CLBC_Audio.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            File dir = loc.isFile() ? loc.getParentFile() : loc;  // 若是 jar，取其所在目录
            return dir == null ? null : dir.getAbsolutePath();
        } catch (Exception ignore) {
            return null;
        }
    }

    /** 返回第一个探测成功的可执行文件名（用于找 PATH 里的 ffmpeg），没有则 null */
    private static String probeFirstTrue(String[] names) {
        for (String n : names) if (probe(n)) return n;
        return null;
    }

    /** 用 -version 探测可执行文件是否可用（3 秒超时，超时/非零退出都算失败） */
    private static boolean probe(String exe) {
        Process p = null;
        try {
            p = new ProcessBuilder(exe, "-version")
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!p.waitFor(3, TimeUnit.SECONDS)) return false;   // 超时
            return p.exitValue() == 0;
        } catch (Exception ignore) {
            return false;
        } finally {
            if (p != null && p.isAlive()) p.destroyForcibly();
        }
    }

    /** 停止并释放当前音轨（可重复调用；同时清理原生 Clip 与外部进程） */
    public static synchronized void stop() {
        if (current != null) {
            try { current.stop(); current.close(); } catch (Exception ignore) {}
            CLBC_Util.log("[BGM] 已停止原生音轨: " + currentPath);
            current = null;
        }
        if (externalProc != null) {
            Process p = externalProc;
            externalProc = null;   // 先摘引用，保证任何异常路径都不会留下僵尸状态
            // ffplay 是 SDL 程序：先礼貌 destroy，短时间未退出则强杀，绝不残留僵尸进程
            try { p.destroy(); } catch (Exception ignore) {}
            if (p.isAlive()) {
                try { p.waitFor(2, TimeUnit.SECONDS); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
            if (p.isAlive()) {
                try { p.destroyForcibly(); } catch (Exception ignore) {}
                try { p.waitFor(1, TimeUnit.SECONDS); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
            CLBC_Util.log("[BGM] 已停止外部播放并回收子进程: " + currentPath);
        }
        currentPath = "";
        paused = false;
    }

    /** 是否正在播放（原生 Clip 在跑 或 外部 ffplay 进程存活） */
    public static synchronized boolean isPlaying() {
        return (current != null && current.isRunning()) || (externalProc != null && externalProc.isAlive());
    }

    /** 是否处于暂停态 */
    public static synchronized boolean isPaused() { return paused; }

    /**
     * 暂停 / 继续切换（供右下角圆圈左键调用）。
     * ffplay 是独立进程、无运行时暂停协议，故"暂停"=停掉进程但记住曲目，"继续"=用记住的曲目/音量重新播放。
     * 没有曲目时不处理，由调用方决定是否随机。返回切换后是否在播放。
     */
    public static synchronized boolean togglePause() {
        if (isPlaying()) {
            String p = lastPath;
            float v = lastVolume;
            stop();
            lastPath = p; lastVolume = v;
            paused = true;
            CLBC_Util.log("[BGM] 暂停: " + p);
        } else if (paused && !lastPath.isEmpty()) {
            play(lastPath, lastVolume);
            paused = false;
            CLBC_Util.log("[BGM] 继续: " + lastPath);
        }
        return isPlaying();
    }

    /**
     * 调整音量（0~1）。原生音轨实时改增益；外部 ffplay 无法运行时改音量，
     * 故若正在外部播放，则用新音量重新起播当前曲目，让音量设置真正生效（SettingsPanel 已做"松手才调用"，不会拖动中反复重播）。
     */
    public static synchronized void setVolume(float volume) {
        volume = Math.max(0f, Math.min(1f, volume));
        lastVolume = volume;
        if (current != null) {
            setVolumeOn(current, volume);
            CLBC_Util.log("[BGM] 音量调整为 " + Math.round(volume * 100) + "%（原生实时生效）");
        } else if (externalMode && externalProc != null && externalProc.isAlive() && !lastPath.isEmpty()) {
            // ffplay 无运行时音量控制：用新音量重新播放当前曲目，真正生效
            CLBC_Util.log("[BGM] 音量调整为 " + Math.round(volume * 100) + "%，外部播放按新音量重新起播");
            play(lastPath, volume);
        } else {
            CLBC_Util.log("[BGM] 音量记忆为 " + Math.round(volume * 100) + "%（下次播放生效）");
        }
    }

    private static void setVolumeOn(Clip clip, float volume) {
        try {
            if (!clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) return;
            FloatControl g = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
            // 线性音量 → 分贝：0 → 静音(min)，1 → 0dB
            float min = g.getMinimum(), max = Math.min(0f, g.getMaximum());
            float db = min + (max - min) * Math.max(0f, Math.min(1f, volume));
            g.setValue(db);
        } catch (Exception ignore) {}
    }
}
