package clbc.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import clbc.blocks.CodeToBlocks;
import clbc.decompile.CLBC_DecompileEngine;
import clbc.decompile.CLBC_Decompiler;
import clbc.gl.CLBC_GL_API;
import clbc.gl.CLBC_GL_Widgets;
import clbc.util.CLBC_Util;

/**
 * 「反编译」屏幕面板（GL 自绘，无 JavaFX / Swing）。
 *
 * <p>流程：选 .class / .jar → 选引擎（或四引擎对比）→ 后台线程反编译 →
 * 结果列表里点开源码预览 → 一键把源码转成积木画布，或送到右侧代码面板。
 *
 * <p>反编译在单个后台线程串行执行（不并发起 JVM、不并发跑引擎），
 * 只通过 volatile 字段把状态回传主线程，GL 控件始终只在主线程调用。
 */
public class CLBC_DecompilePanel {

    private static final long BG     = CLBC_GL_API.hex("#333333");
    private static final long PANEL  = CLBC_GL_API.hex("#2A2A2A");
    private static final long PANEL2 = CLBC_GL_API.hex("#2E2E2E");
    private static final long TEXT   = CLBC_GL_API.hex("#E8E8E8");
    private static final long SUB    = CLBC_GL_API.hex("#888888");
    private static final long ACCENT = CLBC_GL_API.hex("#4C97FF");
    private static final long GREEN  = CLBC_GL_API.hex("#4A9E5C");
    private static final long RED    = CLBC_GL_API.hex("#C94A4A");
    private static final long WHITE  = CLBC_GL_API.hex("#FFFFFF");
    private static final long CODE   = CLBC_GL_API.hex("#D4D4D4");

    private final CLBC_CanvasHost canvas;
    private final Consumer<String> onSourceAccepted;

    private String inputPath = "";
    private int engineSel = 0;
    private int resultSel = -1;
    private int lastPreviewSel = -2;
    private String preview = "";

    private volatile boolean running = false;
    private volatile String status = "选择 .class 或 .jar，挑一个引擎开始。";
    private volatile boolean lastOk = true;
    private volatile List<Path> results = List.of();

    public CLBC_DecompilePanel(CLBC_CanvasHost canvas, Consumer<String> onSourceAccepted) {
        this.canvas = canvas;
        this.onSourceAccepted = onSourceAccepted;
    }

    public void draw(float x, float y, float w, float h) {
        CLBC_GL_API.fillRect(BG, x, y, w, h);
        var font = CLBC_GL_API.uiFont;
        CLBC_GL_API.drawText(font, "字节码反编译 · 内置 " + CLBC_Decompiler.availabilityText(),
                x + 24, y + 26, TEXT);

        // ---- 输入文件 ----
        float row1 = y + 56;
        CLBC_GL_API.drawText(font, "输入 .class / .jar", x + 24, row1 + 20, SUB);
        inputPath = CLBC_GL_Widgets.textField(6001, x + 24, row1 + 32, w - 250, 32,
                inputPath, WHITE, CLBC_GL_API.hex("#1E1E1E"), ACCENT);
        if (CLBC_GL_Widgets.button(6002, x + w - 210, row1 + 32, 186, 32, "选择文件", PANEL2, TEXT)) {
            String p = CLBC_Util.file_picker_multi("选择 .class / .jar",
                    new String[]{"*.class", "*.jar"}, "Java 字节码文件", false);
            if (p != null) inputPath = p;
        }

        // ---- 引擎选择与执行 ----
        float row2 = row1 + 84;
        CLBC_GL_API.drawText(font, "引擎", x + 24, row2 + 20, SUB);
        CLBC_DecompileEngine[] engines = CLBC_DecompileEngine.values();
        engineSel = CLBC_GL_Widgets.comboBox(6003, x + 24, row2 + 32, 230, 30,
                CLBC_DecompileEngine.labels(), engineSel, PANEL2, TEXT, PANEL2, TEXT);

        boolean canStart = !running && !inputPath.isBlank();
        if (CLBC_GL_Widgets.button(6004, x + 268, row2 + 32, 150, 30,
                running ? "反编译中…" : "开始反编译", canStart ? GREEN : PANEL2, WHITE) && canStart) {
            start(false);
        }
        if (CLBC_GL_Widgets.button(6005, x + 428, row2 + 32, 150, 30,
                "四引擎对比", canStart ? PANEL2 : PANEL, canStart ? TEXT : SUB) && canStart) {
            start(true);
        }
        CLBC_GL_API.drawText(font, running ? "正在后台执行，界面可继续操作" : "空闲",
                x + 596, row2 + 52, running ? RED : SUB);

        // ---- 状态 ----
        CLBC_GL_API.drawText(font, status, x + 24, row2 + 84, lastOk ? CODE : RED);

        // ---- 结果列表 + 源码预览 ----
        float listY = row2 + 104;
        float listW = Math.max(260f, Math.min(420f, w * 0.36f));
        float listH = Math.max(120f, y + h - listY - 58);
        String[] items = resultItems();
        resultSel = CLBC_GL_Widgets.list(6006, x + 24, listY, listW, listH, items, resultSel,
                PANEL2, TEXT, ACCENT, WHITE);
        if (resultSel != lastPreviewSel) {
            lastPreviewSel = resultSel;
            preview = (resultSel >= 0 && resultSel < results.size())
                    ? CLBC_Decompiler.readSource(results.get(resultSel)) : "";
        }

        float px = x + 24 + listW + 20;
        float pw = x + w - 24 - px;
        CLBC_GL_API.fillRect(PANEL, px, listY, pw, listH);
        CLBC_GL_API.drawText(font, "源码预览", px + 12, listY + 20, SUB);
        CLBC_GL_API.setClip(px + 12, listY + 30, pw - 24, listH - 38);
        float ty = listY + 36;
        if (preview.isEmpty()) {
            CLBC_GL_API.drawText(font, "（左侧选中一个结果查看源码）", px + 12, ty, SUB);
        } else {
            for (String line : preview.split("\n", -1)) {
                if (ty > listY + listH + 20) break;
                CLBC_GL_API.drawText(font, line, px + 12, ty, CODE);
                ty += 20;
            }
        }
        CLBC_GL_API.clearClip();

        // ---- 底部操作 ----
        float by = y + h - 44;
        if (CLBC_GL_Widgets.button(6007, x + 24, by, 210, 32, "导入到积木画布",
                preview.isEmpty() ? PANEL2 : ACCENT, WHITE) && !preview.isEmpty()) {
            try {
                CodeToBlocks.ConvertResult conv = CodeToBlocks.convert(preview);
                canvas.importBlocks(conv.blocks, false, conv.comments);
                status = "已导入 " + conv.blocks.size() + " 个积木、" + conv.comments.size() + " 个注释框到画布";
                lastOk = true;
            } catch (Exception e) {
                status = "导入失败: " + e;
                lastOk = false;
                CLBC_Util.log("!!!![反编译] 源码转积木失败: " + e);
            }
        }
        if (CLBC_GL_Widgets.button(6008, x + 246, by, 210, 32, "送到右侧代码面板",
                preview.isEmpty() ? PANEL2 : PANEL2, TEXT) && !preview.isEmpty()
                && onSourceAccepted != null) {
            onSourceAccepted.accept(preview);
            status = "源码已送到项目视图右侧代码面板";
            lastOk = true;
        }
    }

    private String[] resultItems() {
        List<String> out = new ArrayList<>();
        for (Path p : results) {
            String parent = p.getParent() == null ? "" : p.getParent().getFileName().toString();
            String engine = parent.contains("-") ? parent.substring(0, parent.indexOf('-')) : parent;
            out.add(engine + "  " + p.getFileName());
        }
        return out.toArray(new String[0]);
    }

    private void start(boolean all) {
        final String src = inputPath.trim();
        final CLBC_DecompileEngine engine = CLBC_DecompileEngine.values()[
                Math.max(0, Math.min(engineSel, CLBC_DecompileEngine.values().length - 1))];
        running = true;
        lastOk = true;
        status = all ? "四引擎依次反编译中…" : engine.label() + " 反编译中…";
        Thread t = new Thread(() -> {
            try {
                List<CLBC_Decompiler.Result> rs = all
                        ? CLBC_Decompiler.decompileAll(Path.of(src), CLBC_Decompiler.defaultOutputRoot())
                        : List.of(CLBC_Decompiler.decompile(engine, Path.of(src), CLBC_Decompiler.defaultOutputRoot()));
                List<Path> files = new ArrayList<>();
                int okCount = 0;
                StringBuilder sb = new StringBuilder();
                for (CLBC_Decompiler.Result r : rs) {
                    if (r.ok) { okCount++; files.addAll(r.javaFiles); }
                    if (sb.length() > 0) sb.append(" | ");
                    sb.append(r.engine.displayName).append(r.ok ? " OK" : " 失败(" + r.message + ")");
                }
                results = files;
                resultSel = files.isEmpty() ? -1 : 0;
                lastPreviewSel = -2;
                lastOk = okCount > 0;
                status = sb + "  共 " + files.size() + " 个 .java";
                CLBC_Util.log("[反编译] 批量完成: " + status);
            } catch (Throwable ex) {
                lastOk = false;
                status = "反编译异常: " + ex;
                CLBC_Util.log("!!!![反编译] " + status);
            } finally {
                running = false;
            }
        }, "CLBC-Decompile");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }
}
