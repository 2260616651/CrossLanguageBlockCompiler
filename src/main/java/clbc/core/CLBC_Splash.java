package clbc.core;

import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL12;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.system.MemoryUtil.NULL;

import clbc.util.CLBC_Util;

/**
 * 启动闪屏：用 GLFW 开一个透明、置顶、无装饰的窗口显示 logo，
 * 同时在后台线程跑 {@code initWork}（引导初始化），完成后再销毁闪屏。
 * 即使图片缺失/解码失败也保证 initWork 被执行，避免卡死在启动阶段。
 */
public class CLBC_Splash {

    public static void show(String imageResource, Consumer<Void> initWork) {
        CLBC_Util.log("闪屏开始: " + imageResource);
        int imgW, imgH;
        try (var in = CLBC_Splash.class.getResourceAsStream(imageResource)) {
            if (in == null) {
                CLBC_Util.log("!!!!闪屏图片缺失: " + imageResource);
                initWork.accept(null);
                return;
            }
            byte[] bytes = in.readAllBytes();
            var buf = BufferUtils.createByteBuffer(bytes.length).put(bytes).flip();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer w = stack.mallocInt(1);
                IntBuffer h = stack.mallocInt(1);
                IntBuffer c = stack.mallocInt(1);
                STBImage.stbi_set_flip_vertically_on_load(false);
                var pixels = STBImage.stbi_load_from_memory(buf, w, h, c, 4);
                if (pixels == null) {
                    CLBC_Util.log("!!!!PNG 解码失败");
                    initWork.accept(null);
                    return;
                }
                imgW = w.get(0);
                imgH = h.get(0);
                STBImage.stbi_image_free(pixels);
            }
        } catch (Exception e) {
            CLBC_Util.log("!!!!闪屏读图失败: " + e.getMessage());
            initWork.accept(null);
            return;
        }

        // 用户要求：闪屏长宽各放大一倍（上一版缩小 4 倍 = 128×128，现缩小 2 倍 = 256×256）。
        // 只调整窗口尺寸，logo 纹理仍按原始像素上传，绘制时等比缩放到窗口内。
        final int SPLASH_SHRINK = 2;
        int winW = Math.max(32, imgW / SPLASH_SHRINK);
        int winH = Math.max(32, imgH / SPLASH_SHRINK);

        if (!glfwInit()) {
            CLBC_Util.Error_Dialog("GLFW 初始化失败", true);
            return;
        }

        // 透明 + 无装饰 + 置顶 + 不可调整，做出"浮在上面的 logo"效果
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_DECORATED, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_FALSE);
        glfwWindowHint(GLFW_FLOATING, GLFW_TRUE);
        glfwWindowHint(GLFW_FOCUS_ON_SHOW, GLFW_FALSE);
        glfwWindowHint(GLFW_TRANSPARENT_FRAMEBUFFER, GLFW_TRUE);
        glfwWindowHint(GLFW_ALPHA_BITS, 8);

        long window = glfwCreateWindow(winW, winH, "CLBC", NULL, NULL);
        if (window == NULL) {
            CLBC_Util.log("!!!!闪屏窗口创建失败");
            glfwTerminate();
            initWork.accept(null);
            return;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer pw = stack.mallocInt(1);
            IntBuffer ph = stack.mallocInt(1);
            glfwGetWindowSize(window, pw, ph);
            GLFWVidMode mode = glfwGetVideoMode(glfwGetPrimaryMonitor());
            if (mode != null) {
                glfwSetWindowPos(window,
                        (mode.width()  - pw.get(0)) / 2,
                        (mode.height() - ph.get(0)) / 2);
            }
        }

        glfwMakeContextCurrent(window);
        GL.createCapabilities();
        glfwSwapInterval(1);

        int tex = uploadTexture(imageResource);

        glfwShowWindow(window);
        CLBC_Util.log("闪屏窗口创建成功: " + winW + "x" + winH + "（原图 " + imgW + "x" + imgH + "，缩小2倍）");

        glClearColor(0f, 0f, 0f, 0f);
        glClear(GL_COLOR_BUFFER_BIT);
        begin2D(winW, winH);
        drawTexture(tex, 0, 0, winW, winH);   // logo 等比缩放到缩小后的窗口
        glfwSwapBuffers(window);

        // 后台线程执行初始化，主线程用事件循环等它结束或窗口被关
        AtomicBoolean done = new AtomicBoolean(false);
        Thread worker = new Thread(() -> {
            try { initWork.accept(null); }
            catch (Throwable t) { CLBC_Util.log("!!!!初始化异常: " + t.getMessage()); }
            finally { done.set(true); }
        }, "clbc-init");
        worker.setDaemon(true);
        worker.start();
        CLBC_Util.log("后台初始化线程启动");

        while (!done.get() && !glfwWindowShouldClose(window)) {
            glfwWaitEventsTimeout(0.1);
        }

        glDeleteTextures(tex);
        glfwDestroyWindow(window);
        glfwTerminate();
    }

    /** 从 classpath 资源解码 PNG 并上传为 GL 纹理；失败返回 0（绘制时按空纹理跳过） */
    private static int uploadTexture(String resourcePath) {
        try (var in = CLBC_Splash.class.getResourceAsStream(resourcePath)) {
            if (in == null) return 0;
            byte[] bytes = in.readAllBytes();
            ByteBuffer fileBuf = BufferUtils.createByteBuffer(bytes.length).put(bytes).flip();

            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer w = stack.mallocInt(1);
                IntBuffer h = stack.mallocInt(1);
                IntBuffer ch = stack.mallocInt(1);

                STBImage.stbi_set_flip_vertically_on_load(true);
                ByteBuffer pixels = STBImage.stbi_load_from_memory(fileBuf, w, h, ch, 4);
                // 关键：stbi 的 flip 是【进程级全局状态】。闪屏 logo 用 true 是为了配套本类
                // drawTexture 的 UV 约定（左上=(0,1)）；加载完必须复位为 false，否则会把
                // true 状态遗留给我们程序后续所有图片加载——壁纸倒置的根因之一。
                // GL_API.loadTexture 也会加载前显式复位，这里从源头清理，双保险。
                STBImage.stbi_set_flip_vertically_on_load(false);
                if (pixels == null) {
                    CLBC_Util.log("!!!!PNG 解码失败: " + STBImage.stbi_failure_reason());
                    return 0;
                }

                int tex = glGenTextures();
                glBindTexture(GL_TEXTURE_2D, tex);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA,
                        w.get(0), h.get(0), 0,
                        GL_RGBA, GL_UNSIGNED_BYTE, pixels);
                glBindTexture(GL_TEXTURE_2D, 0);

                STBImage.stbi_image_free(pixels);
                return tex;
            }
        } catch (Exception e) {
            CLBC_Util.log("!!!!读图失败 " + resourcePath + ": " + e.getMessage());
            return 0;
        }
    }

    /** 切到正交投影（左上原点、Y 向下），供闪屏这种一次性 2D 绘制使用 */
    private static void begin2D(int w, int h) {
        glMatrixMode(GL_PROJECTION);
        glLoadIdentity();
        glOrtho(0, w, h, 0, -1, 1);
        glMatrixMode(GL_MODELVIEW);
        glLoadIdentity();
    }

    /** 用当前纹理贴一张四边形（闪屏 logo） */
    private static void drawTexture(int tex, float x, float y, float w, float h) {
        if (tex == 0) return;
        glEnable(GL_TEXTURE_2D);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glBindTexture(GL_TEXTURE_2D, tex);
        glColor4f(1f, 1f, 1f, 1f);
        glBegin(GL_QUADS);
        glTexCoord2f(0,1); glVertex2f(x,     y);
        glTexCoord2f(1,1); glVertex2f(x + w, y);
        glTexCoord2f(1,0); glVertex2f(x + w, y + h);
        glTexCoord2f(0,0); glVertex2f(x,     y + h);
        glEnd();
        glBindTexture(GL_TEXTURE_2D, 0);
    }
}
