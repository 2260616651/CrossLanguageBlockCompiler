package clbc.decompile;

import java.util.Locale;

/**
 * 内置反编译引擎清单。
 *
 * <p>三个引擎全部作为标准 Maven 依赖声明在 pom.xml，由 Maven 自动下载，
 * 不再把第三方 jar 内置在发布包资源目录（授权与体积考虑）。主程序不 import 引擎类，
 * 一律通过反射调用，运行时用探测类判断依赖是否已就位。
 *
 * <p>三引擎包名互不重叠，可共存于同一 classpath：
 * CFR={@code org.benf.*}、Vineflower={@code org.jetbrains.java.decompiler.*}、
 * Procyon={@code com.strobel.*}。
 *
 * <p>Procyon 在 Maven Central 上没有 {@code procyon-decompiler} CLI 模块，
 * 只有 {@code procyon-compilertools} + {@code procyon-core}，因此它没有 mainClass，
 * 由 {@link CLBC_Decompiler} 反射调用 {@code Decompiler.decompile(...)} API。
 */
public enum CLBC_DecompileEngine {

    CFR("CFR", "0.152",
            "org.benf.cfr.reader.Main",
            "org.benf.cfr.reader.Main"),

    VINEFLOWER("Vineflower", "1.11.1",
            "org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler",
            "org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler"),

    PROCYON("Procyon", "0.6.0",
            "com.strobel.decompiler.Decompiler",
            null);

    public final String displayName;
    public final String version;
    /** 探测类：Class.forName 成功即视为该引擎依赖已就位 */
    public final String probeClass;
    /** CLI 入口类；null 表示走 API（Procyon） */
    public final String mainClass;

    CLBC_DecompileEngine(String displayName, String version, String probeClass, String mainClass) {
        this.displayName = displayName;
        this.version = version;
        this.probeClass = probeClass;
        this.mainClass = mainClass;
    }

    /** 资源目录 / 外部覆盖目录的子目录名 */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String label() {
        return displayName + " " + version;
    }

    public static String[] labels() {
        CLBC_DecompileEngine[] all = values();
        String[] out = new String[all.length];
        for (int i = 0; i < all.length; i++) out[i] = all[i].label();
        return out;
    }
}
