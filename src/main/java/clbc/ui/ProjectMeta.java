package clbc.ui;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import clbc.util.CLBC_Util;

/**
 * 项目元数据：取自项目目录下的 project.clbc（名称 + 语言）。
 * 提供静态工厂 {@link #read(Path)}；读取失败时回退到"目录名 + java"默认值并记日志，
 * 保证调用方永远拿到可用的元数据。
 */
public record ProjectMeta(String name, String language) {
    public static ProjectMeta read(Path projectDir) {
        String name = projectDir.getFileName().toString();
        String language = "java";
        Path clbc = projectDir.resolve("project.clbc");
        if (Files.exists(clbc)) {
            Properties props = new Properties();
            try (Reader r = Files.newBufferedReader(clbc, StandardCharsets.UTF_8)) {
                props.load(r);
            } catch (IOException e) {
                CLBC_Util.log("!!!!读项目配置失败: " + e.getMessage());
                return new ProjectMeta(name, language);
            }
            String n = props.getProperty("project.name");
            if (n != null && !n.isEmpty()) name = n;
            String l = props.getProperty("project.language");
            if (l != null && !l.isEmpty()) language = l;
        }
        return new ProjectMeta(name, language);
    }
}
