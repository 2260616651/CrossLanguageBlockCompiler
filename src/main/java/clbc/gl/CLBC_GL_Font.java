package clbc.gl;

import org.lwjgl.stb.STBTTFontinfo;
import org.lwjgl.stb.STBTruetype;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.*;

public class CLBC_GL_Font {

    public static class Glyph {
        public float adv, bx, by, w, h;
        public float u0, v0, u1, v1;
    }

    // 图集扩容 2048→4096：旧版 2048 配 2x 超采样，中文按 ~36px 烘每字约 72px，
    // 只能容纳约 800 字；加载数万条 JDK 中文映射后图集迅速写满，新字渲染成空方框
    // （截图里注释/标签大面积缺字）。4096 容量翻 4 倍，显存约 64MB，核显可接受。
    private static final int ATLAS_SIZE = 4096;
    /** 图集超采样倍数：以 2x 分辨率烘字形、按 1x 逻辑尺寸绘制，
     *  配合 GL_LINEAR 相当于一次高质量下采样，中文小字笔画不再糊成一团。 */
    private static final int SS = 2;

    // ===== 渲染层崩溃修复（2026-11-17，hs_err_pid10604.log）=====
    // 图集最末一行预留一块纯白不透明像素：几何色块（描边/填充/线/圆角）也采样本图集，
    // 与字形共用同一张纹理。这样积木绘制不再 whiteTex↔fontTex 高频切换，
    // 每帧 glBufferSubData 从「上万次」降到个位数，根治 Intel 核显驱动
    // 在 glBufferSubData 高频调用下的 native 访问违规（ig9icd64.dll 读 0x0）。
    // shelf 从 (1,1) 起分配且 y+bh+1>=ATLAS_SIZE 即停，末行永不用于字形，白块不会被覆盖。
    private static final int SOLID_SIZE = 16;
    /** 几何纯色顶点采样坐标（白块中心，远离块边缘 8px，避免 GL_LINEAR 采到邻域） */
    public static final float SOLID_U = (SOLID_SIZE / 2f) / ATLAS_SIZE;
    public static final float SOLID_V = (ATLAS_SIZE - SOLID_SIZE / 2f) / ATLAS_SIZE;
    private final STBTTFontinfo info = STBTTFontinfo.create();
    private final ByteBuffer fontData;
    private final float pixelHeight;
    private final float scale;          // 2x 渲染用 scale
    private final Map<Integer, Glyph> cache = new HashMap<>();
    private boolean atlasFullLogged = false;

    private final int textureId;
    private int shelfX = 1, shelfY = 1, shelfH = 0;

    public CLBC_GL_Font(String fontFile, float pixelHeight) {
        Path p = Path.of(fontFile);
        try {
            byte[] bytes = Files.readAllBytes(p);
            fontData = MemoryUtil.memAlloc(bytes.length);
            fontData.put(bytes).flip();
        } catch (IOException e) {
            throw new RuntimeException("字体读取失败: " + fontFile, e);
        }

        int offset = STBTruetype.stbtt_GetFontOffsetForIndex(fontData, 0);
        if (offset < 0) offset = 0;
        if (!STBTruetype.stbtt_InitFont(info, fontData, offset)) {
            throw new RuntimeException("字体解析失败: " + fontFile);
        }

        this.pixelHeight = pixelHeight;
        this.scale = STBTruetype.stbtt_ScaleForPixelHeight(info, pixelHeight * SS);

        // 创建空白图集
        textureId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, textureId);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, ATLAS_SIZE, ATLAS_SIZE, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

        // 烘焙末行纯白块（几何复用）：一次性写入，之后字形 shelf 分配不会触达末行。
        ByteBuffer solid = MemoryUtil.memAlloc(SOLID_SIZE * SOLID_SIZE * 4);
        for (int i = 0; i < SOLID_SIZE * SOLID_SIZE; i++) {
            solid.put((byte) 0xFF).put((byte) 0xFF).put((byte) 0xFF).put((byte) 0xFF);
        }
        solid.flip();
        glBindTexture(GL_TEXTURE_2D, textureId);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, ATLAS_SIZE - SOLID_SIZE,
                SOLID_SIZE, SOLID_SIZE, GL_RGBA, GL_UNSIGNED_BYTE, solid);
        glBindTexture(GL_TEXTURE_2D, 0);
        MemoryUtil.memFree(solid);
    }

    public int getTextureId() { return textureId; }

    /** 字体像素高度（控件布局/文字基线计算用） */
    public float getPixelHeight() { return pixelHeight; }

    public Glyph getGlyph(int codepoint) {
        if (cache.containsKey(codepoint)) return cache.get(codepoint);

        Glyph g = new Glyph();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer adv = stack.mallocInt(1), lsb = stack.mallocInt(1);
            STBTruetype.stbtt_GetCodepointHMetrics(info, codepoint, adv, lsb);
            // advance / bearing / 尺寸全部归一回 1x 逻辑像素
            g.adv = adv.get(0) * scale / SS;

            IntBuffer ix0 = stack.mallocInt(1), iy0 = stack.mallocInt(1);
            IntBuffer ix1 = stack.mallocInt(1), iy1 = stack.mallocInt(1);
            STBTruetype.stbtt_GetCodepointBitmapBox(info, codepoint, scale, scale, ix0, iy0, ix1, iy1);

            int bw = ix1.get(0) - ix0.get(0);   // 2x 位图宽
            int bh = iy1.get(0) - iy0.get(0);   // 2x 位图高
            g.w = bw / (float) SS; g.h = bh / (float) SS;
            g.bx = ix0.get(0) / (float) SS; g.by = iy0.get(0) / (float) SS;

            if (bw > 0 && bh > 0) {
                // 图集溢出保护：只记一次日志并静默跳过该字形，绝不越界写 / 崩溃
                if (shelfY + bh + 1 >= ATLAS_SIZE) {
                    if (!atlasFullLogged) {
                        atlasFullLogged = true;
                        clbc.util.CLBC_Util.log("字体图集已满，后续新字形跳过（不影响已有字形）");
                    }
                    g.w = 0; g.h = 0;
                    // 图集满的失败字形不缓存成「已渲染」，允许后续重试，避免新字永久空白
                    return g;
                }
                ByteBuffer alpha = MemoryUtil.memAlloc(bw * bh);
                STBTruetype.stbtt_MakeCodepointBitmap(info, alpha, bw, bh, bw, scale, scale, codepoint);

                // 分配图集位置（按 2x 位图尺寸）
                if (shelfX + bw + 1 > ATLAS_SIZE) { shelfX = 1; shelfY += shelfH + 1; shelfH = 0; }
                if (shelfY + bh + 1 >= ATLAS_SIZE) {
                    if (!atlasFullLogged) {
                        atlasFullLogged = true;
                        clbc.util.CLBC_Util.log("字体图集已满，后续新字形跳过（不影响已有字形）");
                    }
                    g.w = 0; g.h = 0;
                    MemoryUtil.memFree(alpha);
                    // 同前：失败字形不缓存，允许后续重试
                    return g;
                }
                int px = shelfX, py = shelfY;
                shelfX += bw + 1;
                if (bh > shelfH) shelfH = bh;

                ByteBuffer rgba = MemoryUtil.memAlloc(bw * bh * 4);
                for (int i = 0; i < bw * bh; i++) {
                    byte a = alpha.get(i);
                    rgba.put(i * 4, (byte) 0xFF);
                    rgba.put(i * 4 + 1, (byte) 0xFF);
                    rgba.put(i * 4 + 2, (byte) 0xFF);
                    rgba.put(i * 4 + 3, a);
                }
                glBindTexture(GL_TEXTURE_2D, textureId);
                glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
                glTexSubImage2D(GL_TEXTURE_2D, 0, px, py, bw, bh, GL_RGBA, GL_UNSIGNED_BYTE, rgba);
                glBindTexture(GL_TEXTURE_2D, 0);

                g.u0 = (float) px / ATLAS_SIZE;
                g.v0 = (float) py / ATLAS_SIZE;
                g.u1 = (float) (px + bw) / ATLAS_SIZE;
                g.v1 = (float) (py + bh) / ATLAS_SIZE;

                MemoryUtil.memFree(alpha);
                MemoryUtil.memFree(rgba);
            }
        }
        cache.put(codepoint, g);
        return g;
    }

    /** 释放字体资源：删除图集纹理、memFree 字体数据（设计文档 6. 红线，杜绝内存泄漏） */
    public void dispose() {
        if (textureId != 0) { glDeleteTextures(textureId); }
        if (fontData != null) { MemoryUtil.memFree(fontData); }
    }
}