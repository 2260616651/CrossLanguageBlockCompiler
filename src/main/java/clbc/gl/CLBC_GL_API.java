package clbc.gl;

import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWImage;
import org.lwjgl.glfw.GLFWScrollCallback;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GL;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import javax.imageio.ImageIO;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import clbc.util.CLBC_Util;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * CLBC 自研 OpenGL 渲染引擎（OpenGL 3.3 / LWJGL + GLFW）。
 *
 * 职责分层：
 *  · 通用渲染原语（fillRect / strokeRect / fillCircle / drawImage / drawText / drawLine 等）放在本类——所有 UI 共用。
 *  · 控件级逻辑（按钮 / 文本框 / 下拉 / 列表等命中测试与状态机）放在 {@link CLBC_GL_Widgets}，属内部渲染方法。
 *
 * 本类同时持有即时模式 GUI 所需的输入状态（鼠标 / 键盘 / 滚轮 / 字符队列），
 * 通过 GLFW 回调采集，每帧在 {@link #beginFrame(float, float, float)} 里把"边沿事件"搬进当前帧供控件读取。
 */
public class CLBC_GL_API {

    // ==========================================================
    // 引擎状态（窗口 / 着色器 / 顶点缓冲）
    // ==========================================================
    private static long window;
    private static int shaderProgram;
    private static int vao, vbo;
    private static final float[] projMat = new float[16];
    private static int uProjLoc, uTexLoc;

    private static int whiteTex;
    private static int currentTex = 0;

    /** 遮罩法掩盖色（= 画布实底色 #202020），用于积木内凹圆角"咬"出负空间（设计文档 3.1） */
    public static final long MASK_COLOR = 0xFF202020L;

    /** 主题网格点色（CLBC_Theme.apply 切换主题时刷新；默认浅色 #ECECEC） */
    private static long gridDotColor = 0xFFECECECL;
    public static void setGridDotColor(long color) {
        if (gridDotColor == color) return;
        gridDotColor = color;
        gridTex = 0;   // 丢弃旧网格纹理，下一帧按新色重新烘焙
        CLBC_Util.logFine("[Theme] 网格点色已更新，网格纹理将在下一帧重建");
    }

    /** 圆角描边线宽（由 strokeRoundRect 临时设置，drawRoundRect 描边分支读取） */
    private static float strokeWidth = 1f;

    // 形状顶点缓存：尺寸+半径不变则复用 float[]，避免每帧重算三角函数（设计文档 3. 节性能红线）
    private static final java.util.Map<String, float[]> shapeCache = new java.util.HashMap<>();
    private static float[] unitCircle = null;   // 单位圆（半径1，seg 段）顶点，缓存

    // ===== 渲染层优化（2026-11-08）：复用缓冲 + 平铺网格 + 文字宽度缓存 =====
    // 目标：核显 UHD620 + 8GB 内存，避免每帧短命数组造成的 GC 抖动。
    private static final float[] FILL_CIRC_X = new float[32];   // fillCircle 复用 x 缓冲（seg=28 封顶）
    private static final float[] FILL_CIRC_Y = new float[32];   // fillCircle 复用 y 缓冲
    private static final float[] RR_X = new float[64];           // drawRoundRect 复用（seg=8 → n=36 封顶）
    private static final float[] RR_Y = new float[64];           // drawRoundRect 复用 y 缓冲

    // 网格平铺纹理（一次性烘焙为 240×240 的小图，含 10×10 个 24px 间距圆点；drawGrid 改为平铺该纹理）
    private static int gridTex = 0;

    // 文字宽度缓存：相同字符串不重复累加 glyph advance（measureText 是渲染热路径，积木文本大量重复）。
    // 用 LinkedHashMap 限制上限 4096，超出自动淘汰最旧项，防止长期运行内存膨胀。
    private static final LinkedHashMap<String, Float> TEXT_W_CACHE = new LinkedHashMap<String, Float>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Float> e) { return size() > 4096; }
    };
    private static CLBC_GL_Font TEXT_W_FONT = null;   // 缓存仅对当前 UI 字体有效，换字体即失效并清空

    /** 顶点格式: x, y, u, v, r, g, b, a (共 8 个 float) */
    /** 单帧最大顶点数：JParser 那类 685 积木 + 每块数十 band/字形，20 万会被 addVertex 静默丢弃（缺字/缺边），
     *  提到 60 万（约 19.2MB 顶点 + 19.2MB VBO）。VBO 与 buffer 必须同容量，否则 glBufferSubData 越界=native 崩溃。 */
    private static final int VERTEX_CAP = 600_000;
    private static final FloatBuffer vertexBuffer = MemoryUtil.memAllocFloat(VERTEX_CAP * 8);
    private static boolean vertexOverflowLogged = false;   // 溢出只记一次日志，避免每帧刷屏

    private static int windowWidth, windowHeight;       // UI 虚拟坐标尺寸（= 物理帧缓冲 / 有效缩放），布局与命中统一用这套坐标
    private static int physWidth, physHeight;           // 物理帧缓冲尺寸：glViewport / glScissor / 截图读回专用

    // ===== 三档缩放之①屏幕分辨率/DPI + 之②全局缩放：统一在绘制层落地 =====
    // 有效缩放 = 屏幕 DPI 基础缩放 × 用户全局缩放(ui.scale)。两者任一变化都只走 setProjection 一处，
    // 同时更新虚拟视口(windowWidth/Height)、投影矩阵、鼠标逻辑坐标换算与裁剪框，避免坐标错乱。
    private static float screenScale = 1.0f;            // 显示器 content scale（随 DPI / 跨屏自适应）
    private static float userUiScale = 1.0f;            // 用户 ui.scale（设置面板滑块，0.5~2.0）
    private static final float SCALE_LIMIT_LO = 0.4f, SCALE_LIMIT_HI = 3.0f;

    private static boolean isFullscreen = false;        // 当前是否全屏（F11 切换）
    private static int savedX, savedY, savedW, savedH;  // 进入全屏前的窗口位置/尺寸，退出时还原
    // ===== T4 无边框窗口自拖动（无系统标题栏时，鼠标按顶部条拖动窗口）=====
    private static boolean borderlessDrag = false;      // 当前窗口是否无边框（随 setWindowDecorated 同步）
    private static boolean dragWindow = false;          // 正在拖动窗口
    private static double mouseXPhys, mouseYPhys;       // 原始物理鼠标坐标（窗口拖动用，不经缩放）
    private static double dragStartMouseX, dragStartMouseY; // 按下时鼠标物理坐标
    private static int dragStartWinX, dragStartWinY;    // 按下时窗口位置
    /** UI 字体（由应用加载微软雅黑后注入，控件默认使用） */
    public static CLBC_GL_Font uiFont = null;

    // ===== 渲染层崩溃修复（2026-11-17）=====
    // 纯色几何（填充/描边/线/圆角）的采样纹理与 UV。
    // 字体图集可用时统一复用其末行纯白块 → 几何与字形共用一张纹理，
    // 积木绘制全程无纹理切换，glBufferSubData 调用从每帧上万次降到个位数。
    private static int solidTex = 0;                 // 0 表示回退到独立 whiteTex
    private static float solidU = 0.5f, solidV = 0.5f;
    /** 绑定纯色几何纹理：优先字体图集白点，无字体时回退独立白纹理 */
    private static void bindSolidTexture() {
        if (uiFont != null) {
            solidTex = uiFont.getTextureId();
            solidU = CLBC_GL_Font.SOLID_U;
            solidV = CLBC_GL_Font.SOLID_V;
            setTexture(solidTex);
        } else {
            solidTex = 0; solidU = 0.5f; solidV = 0.5f;
            setTexture(whiteTex);
        }
    }

    // ==========================================================
    // 输入状态（即时模式 GUI 用，全部 public 供控件读取）
    // ==========================================================
    public static int mouseX = 0, mouseY = 0;          // 鼠标位置（窗口像素，y 向下）
    public static boolean mouseDown = false;            // 左键是否按住
    public static boolean mouseJustPressed = false;     // 本帧刚按下（边沿）
    public static boolean mouseJustReleased = false;    // 本帧刚松开（边沿）
    public static boolean mouseRightJustPressed = false;// 本帧右键刚按下（右键菜单触发）
    public static float scrollDelta = 0;                // 本帧滚轮增量（向上为正）
    public static int focusedTextField = -1;            // 当前获得焦点的文本框 id（-1 无）
    public static List<Integer> typedChars = new ArrayList<>(); // 本帧输入的 Unicode 码点（含中文）
    public static boolean keyBackspace = false, keyDelete = false;
    public static boolean keyLeft = false, keyRight = false, keyHome = false, keyEnd = false, keyEnter = false;
    public static boolean keyZ = false, keyY = false, keyS = false;   // Ctrl+Z/Y/S 快捷键边沿
    public static boolean ctrlDown = false;             // Ctrl 是否按住
    // ===== 段2 新增：按键边沿 / 组合状态（供代码编辑器与画布读取，见段2报告接口清单）=====
    public static boolean keyV = false, keyA = false, keyC = false;   // V / A / C 键边沿（配合 ctrlDown 判定 Ctrl+V/A/C）
    public static boolean keyUp = false, keyDown = false;             // 上 / 下方向键边沿
    public static boolean shiftDown = false;                          // Shift 修饰是否按住（多选 / 加速微调）
    // 滚轮：scrollDelta 本就是每帧公开的原始增量（向上为正），缩放与列表滚动按各自区域焦点消费，无需另设字段。

    // 回调采集到的"挂起"累积量，在 beginFrame 中搬入上面的"当前帧"字段
    private static int pressEvents = 0, releaseEvents = 0, rightPressEvents = 0;
    private static float scrollAccum = 0;
    private static final List<Integer> typedAccum = new ArrayList<>();
    private static boolean bkspEdge = false, delEdge = false, leftEdge = false,
            rightEdge = false, homeEdge = false, endEdge = false, enterEdge = false,
            zEdge = false, yEdge = false, sEdge = false,
            vEdge = false, aEdge = false, cEdge = false, upEdge = false, downEdge = false;

    // ==========================================================
    // 段2 新增：画布缩放变换（B-3）。仅画布 draw / drawPopup 绘制期间由 CanvasHost 激活。
    // 逻辑坐标 → 屏幕：screen = ox + (logical - ox) * s；鼠标逻辑化（逆变换）：logical = ox + (screen - ox) / s。
    // addVertex 在 zoomActive 时对顶点应用正变换；CLBC_GL_Widgets 的命中走 mouseLogicalX/Y 保证与绘制一致。
    // zoomActive 每帧由 beginFrame 强制复位，杜绝某一帧漏 pop 后污染到下一帧的工具箱 / 代码面板。
    // ==========================================================
    private static boolean zoomActive = false;
    private static float zoomOx = 0, zoomOy = 0, zoomS = 1f;

    /** 开启画布缩放绘制变换（ox,oy 为锚点、通常与画布区左上重合；s 为缩放比例） */
    public static void pushCanvasZoom(float ox, float oy, float s) {
        zoomActive = true; zoomOx = ox; zoomOy = oy; zoomS = (s > 0.0001f ? s : 1f);
    }
    /** 关闭画布缩放绘制变换（画布绘制结束即调用；命中状态由调用方自行用逻辑坐标判断） */
    public static void popCanvasZoom() { zoomActive = false; }
    /** 逻辑化鼠标 X：缩放激活时把物理鼠标坐标逆变换回画布逻辑坐标，否则原样返回 */
    public static float mouseLogicalX() { return zoomActive ? zoomOx + (mouseX - zoomOx) / zoomS : (float) mouseX; }
    /** 逻辑化鼠标 Y：同 mouseLogicalX */
    public static float mouseLogicalY() { return zoomActive ? zoomOy + (mouseY - zoomOy) / zoomS : (float) mouseY; }

    // ==========================================================
    // 段2 新增：GL 资源异常退出兜底（dispose / destroy 幂等 + JVM 关闭钩子）
    // ==========================================================
    // native 资源只能释放一次：destroy() 会由主循环退出路径(CLBC.main)与 JVM shutdown hook 两条线程调用。
    // 这两个标志必须 volatile + 同步，否则可能出现两个线程同时进入 dispose()、对同一 native 指针
    // （vertexBuffer / GL 纹理 / VAO/VBO）双重释放或删除，触发 native 层 0xC0000005 访问违规。
    private static volatile boolean disposed = false, destroyed = false;

    // ==========================================================
    // 窗口与引擎初始化
    // ==========================================================
    public static void init(String title, int width, int height) {
        GLFWErrorCallback.create((error, description) -> {
            String msg = "GLFW 错误 code=" + error + ": " + MemoryUtil.memUTF8(description);
            System.err.println(msg);
            CLBC_Util.log("!!!![GLFW] " + msg);
        }).set();
        if (!glfwInit()) throw new IllegalStateException("GLFW 初始化失败");

        // 异常退出兜底：主渲染循环 / init 中途抛异常时，CLBC.main 末尾的 destroy() 不会被执行，
        // 由 JVM 关闭钩子保证窗口与 GLFW 一定被终止（GL 对象在进程退出时由驱动回收，尽力调 dispose）。
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { destroy(); } catch (Throwable ignored) { }
        }, "CLBC-GL-cleanup"));

        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        // 黑边（系统边框）开关：读 ui.borderless 设置，缺省 false ⇒ GLFW_DECORATED=TRUE，
        // 保持用户当前看到的带边框外观不变；仅显式关闭时才无边框。
        glfwWindowHint(GLFW_DECORATED, readBorderlessSetting() ? GLFW_FALSE : GLFW_TRUE);
        // 窗口先隐藏：无论自检还是正常启动，都等初始化 + 首帧渲染完成后再由 showMainWindow() 显示。
        // 关键：窗口一旦可见却没进主循环调用 glfwPollEvents，Windows 会判定"未响应"。
        // 隐藏期间做完字体/主题/积木登记/首帧，显示瞬间即可交互，消除"闪屏关了窗口卡住"的空档。
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);

        window = glfwCreateWindow(width, height, title, NULL, NULL);
        if (window == NULL) throw new RuntimeException("窗口创建失败");

        setWindowIcon(window);   // 用 /icons/logo.png 替换 GLFW 默认占位图标

        glfwMakeContextCurrent(window);
        glfwSwapInterval(1);
        GL.createCapabilities();

        // 三档缩放之①：屏幕分辨率/DPI 基础缩放。init 内即取，随后 setProjection 按「屏幕×用户」合成缩放重算虚拟视口。
        screenScale = clamp(readDisplayScale(), 0.5f, 3.0f);
        // 视口/投影统一用帧缓冲物理尺寸（与 resize 回调一致），HiDPI 下才不会被缩到屏幕一角。
        int fbW = width, fbH = height;
        try (MemoryStack fb = MemoryStack.stackPush()) {
            IntBuffer fwb = fb.mallocInt(1), fhb = fb.mallocInt(1);
            glfwGetFramebufferSize(window, fwb, fhb);
            fbW = fwb.get(0); fbH = fhb.get(0);
        }
        glViewport(0, 0, fbW, fbH);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        initShaders();

        vao = glGenVertexArrays();
        vbo = glGenBuffers();
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, (long) VERTEX_CAP * 8 * Float.BYTES, GL_DYNAMIC_DRAW);

        // 步长变成 8 个 float (32 bytes)
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 8 * Float.BYTES, 0); // 位置
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 2, GL_FLOAT, false, 8 * Float.BYTES, 2 * Float.BYTES); // UV
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(2, 4, GL_FLOAT, false, 8 * Float.BYTES, 4 * Float.BYTES); // 颜色
        glEnableVertexAttribArray(2);
        glBindVertexArray(0);

        setProjection(fbW, fbH);

        // 创建 1x1 白色纹理（给纯色图形用）
        whiteTex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, whiteTex);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer whitePixel = stack.ints(0xFFFFFFFF);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, whitePixel);
        }
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glBindTexture(GL_TEXTURE_2D, 0);

        setupInputCallbacks();
        setupResizeCallback();   // 注册窗口尺寸变化回调（最大化 / 拖拽边框 / 全屏均自适应）
        // DPI 变化 / 窗口被拖到另一块不同缩放的显示器时，GLFW 回调实时刷新屏幕基础缩放并自适应重算投影。
        glfwSetWindowContentScaleCallback(window, (w, sx, sy) -> applyScreenScale(Math.max(sx, sy)));
        CLBC_Util.log(String.format("屏幕分辨率/DPI 基础缩放：×%.2f（显示器 content scale）", screenScale));

        System.out.println("✅ OpenGL 3.3 引擎初始化成功！");
        // 渲染层就绪日志（写入统一日志文件，受 logFile 就绪状态保护，未就绪时静默）
        CLBC_Util.log(String.format("渲染引擎就绪：OpenGL 3.3 / LWJGL，顶点缓冲 %.1fMB（上限 %d 顶点），几何布局指纹缓存+形状缓存+单位圆缓存已启用",
                (VERTEX_CAP * 8 * 4f) / (1024f * 1024f), VERTEX_CAP));
    }

    /** 读「无边框」设置（缺省 false = 带系统边框）。configFile 未就绪 / 读失败一律按带边框处理，绝不改变现有外观。 */
    private static boolean readBorderlessSetting() {
        try {
            if (clbc.core.CLBC_AppContext.configFile == null) return false;
            java.util.Properties p = CLBC_Util.loadProperties(clbc.core.CLBC_AppContext.configFile);
            return "true".equalsIgnoreCase(p.getProperty("ui.borderless", "false"));
        } catch (Throwable t) { return false; }
    }

    /** 运行期切换系统边框（设置面板调用）。GLFW 3.3 支持 glfwSetWindowAttrib 动态改 DECORATED，无需重建窗口。
     *  注意：切到无边框后系统标题栏消失，本类不提供自绘标题栏（见段2报告「做不到」项）。 */
    public static void setWindowDecorated(boolean decorated) {
        try {
            if (window == NULL || window == 0L) return;
            glfwSetWindowAttrib(window, GLFW_DECORATED, decorated ? GLFW_TRUE : GLFW_FALSE);
            borderlessDrag = !decorated;   // T4：无边框时启用自绘顶部条拖动
            if (decorated) dragWindow = false;
            CLBC_Util.logFine("系统边框已" + (decorated ? "开启（带边框，系统拖动）" : "关闭（无边框，按住顶部条拖动）"));
        } catch (Throwable t) { CLBC_Util.log("!!!! 切换系统边框失败: " + t.getMessage()); }
    }

    /**
     * 设置窗口标题栏图标：从 classpath 资源 /icons/logo.png 解码为 RGBA 像素并调用 glfwSetWindowIcon。
     * 失败仅记录日志、不影响窗口创建（图标缺失时 GLFW 回退默认占位图标）。
     */
    private static void setWindowIcon(long window) {
        try (InputStream in = CLBC_GL_API.class.getResourceAsStream("/icons/logo.png")) {
            if (in == null) {
                CLBC_Util.logFine("窗口图标资源缺失：/icons/logo.png，沿用 GLFW 默认图标");
                return;
            }
            byte[] png = in.readAllBytes();
            ByteBuffer pngBuf = MemoryUtil.memAlloc(png.length).put(png).flip();
            // 防御：flip 是 stb 进程级全局状态，确保图标按自顶向下解码（GLFW 期望），不依赖此前闪屏/壁纸是否复位过。
            STBImage.stbi_set_flip_vertically_on_load(false);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer w = stack.mallocInt(1);
                IntBuffer h = stack.mallocInt(1);
                IntBuffer ch = stack.mallocInt(1);
                ByteBuffer pixels = STBImage.stbi_load_from_memory(pngBuf, w, h, ch, 4);
                if (pixels == null) {
                    CLBC_Util.logFine("窗口图标解码失败: " + STBImage.stbi_failure_reason());
                    return;
                }
                // 【图标倒置修复】stb_image 默认 stbi_set_flip_vertically_on_load(false)，
                // 解码出的像素第 0 行就是图像顶部（自顶向下），正是 GLFW 期望的顺序。
                // 旧代码在此又做了一次「行首尾对调」垂直翻转 → 把本已正立的图标翻成倒立
                // （用户截图：皮卡丘头朝下）。故删除手动翻转，直接交给 glfwSetWindowIcon。
                // 注意：闪屏 T0 修复后全局 flip 恒为 false，本方法不再改动该全局状态，壁纸方向不受影响。
                int W = w.get(0), H = h.get(0);
                GLFWImage image = GLFWImage.malloc(stack);
                image.set(W, H, pixels);
                GLFWImage.Buffer images = GLFWImage.malloc(1, stack);
                images.put(0, image);
                glfwSetWindowIcon(window, images);
                STBImage.stbi_image_free(pixels);
                CLBC_Util.logFine(String.format("窗口图标已设置：/icons/logo.png (%dx%d)", W, H));
            } finally {
                MemoryUtil.memFree(pngBuf);
            }
        } catch (Exception e) {
            CLBC_Util.logFine("设置窗口图标异常: " + e.getMessage());
        }
    }

    /** 注册 GLFW 输入回调（鼠标 / 滚轮 / 字符 / 特殊键）。边沿事件先累积，beginFrame 再搬入当前帧。 */
    private static void setupInputCallbacks() {
        // T4：以窗口实际 DECORATED 属性初始化（无边框启动也正确）
        borderlessDrag = glfwGetWindowAttrib(window, GLFW_DECORATED) == GLFW_FALSE;
        glfwSetMouseButtonCallback(window, (w, button, action, mods) -> {
            // 鼠标事件也刷新修饰状态：Shift 多选 / Ctrl 框选依赖"点击那一刻"的修饰位，
            // 只靠按键回调（须先有按键动作）会漏掉"先按住修饰键再点鼠标"的情形。
            ctrlDown = (mods & GLFW_MOD_CONTROL) != 0;
            shiftDown = (mods & GLFW_MOD_SHIFT) != 0;
            if (button == GLFW_MOUSE_BUTTON_LEFT) {
                if (action == GLFW_PRESS) {
                    mouseDown = true; pressEvents++;
                    // T4：无边框 + 点在顶部条(mouseY<44) → 准备拖动窗口（真正移动要等鼠标位移超阈值，
                    // 这样点顶栏按钮不会误移窗口）。全屏不拖。
                    if (borderlessDrag && !isFullscreen && mouseY >= 0 && mouseY < 44) {
                        dragWindow = true;
                        dragStartMouseX = mouseXPhys; dragStartMouseY = mouseYPhys;
                        try (MemoryStack st = MemoryStack.stackPush()) {
                            IntBuffer px = st.mallocInt(1), py = st.mallocInt(1);
                            glfwGetWindowPos(window, px, py);
                            dragStartWinX = px.get(0); dragStartWinY = py.get(0);
                        }
                    }
                }
                else if (action == GLFW_RELEASE) { mouseDown = false; releaseEvents++; dragWindow = false; }
            } else if (button == GLFW_MOUSE_BUTTON_RIGHT) {
                if (action == GLFW_PRESS) rightPressEvents++;
            }
        });
        glfwSetCursorPosCallback(window, (w, xpos, ypos) -> {
            // 物理鼠标坐标（T4 窗口拖动用原始物理坐标，避免缩放换算误差）
            mouseXPhys = xpos; mouseYPhys = ypos;
            // 物理鼠标 → UI 虚拟坐标：与投影/绘制共用同一个有效缩放，保证命中与画面永远对齐。
            float s = getEffectiveUiScale();
            mouseX = (int) (xpos / s);
            mouseY = (int) (ypos / s);
            // T4：按住顶部条拖动 → 移动窗口。用物理坐标位移，1:1 跟手。
            if (dragWindow && !isFullscreen) {
                double dx = xpos - dragStartMouseX, dy = ypos - dragStartMouseY;
                if (Math.abs(dx) > 2 || Math.abs(dy) > 2) {
                    glfwSetWindowPos(window,
                            dragStartWinX + (int) Math.round(dx),
                            dragStartWinY + (int) Math.round(dy));
                }
            }
        });
        glfwSetScrollCallback(window, (w, xoff, yoff) -> {
            scrollAccum += (float) yoff;
        });
        glfwSetCharCallback(window, (w, codepoint) -> {
            typedAccum.add((int) codepoint);   // 👈 中文输入法的组字结果也会以 Unicode 码点送达，原生支持中文
        });
        glfwSetKeyCallback(window, (w, key, scancode, action, mods) -> {
            ctrlDown = (mods & GLFW_MOD_CONTROL) != 0;
            shiftDown = (mods & GLFW_MOD_SHIFT) != 0;
            if (action == GLFW_PRESS && key == GLFW_KEY_F11) {
                toggleFullscreen();
            }
            if (action == GLFW_PRESS || action == GLFW_REPEAT) {
                if (key == GLFW_KEY_BACKSPACE) bkspEdge = true;
                else if (key == GLFW_KEY_DELETE) delEdge = true;
                else if (key == GLFW_KEY_LEFT) leftEdge = true;
                else if (key == GLFW_KEY_RIGHT) rightEdge = true;
                else if (key == GLFW_KEY_HOME) homeEdge = true;
                else if (key == GLFW_KEY_END) endEdge = true;
                else if (key == GLFW_KEY_ENTER || key == GLFW_KEY_KP_ENTER) enterEdge = true;
                else if (key == GLFW_KEY_Z) zEdge = true;
                else if (key == GLFW_KEY_Y) yEdge = true;
                else if (key == GLFW_KEY_S) sEdge = true;
                else if (key == GLFW_KEY_V) vEdge = true;
                else if (key == GLFW_KEY_A) aEdge = true;
                else if (key == GLFW_KEY_C) cEdge = true;
                else if (key == GLFW_KEY_UP) upEdge = true;
                else if (key == GLFW_KEY_DOWN) downEdge = true;
            }
        });
    }

    /** 注册窗口尺寸变化回调：最大化 / 拖拽边框 / 全屏切换时，重设视口与投影矩阵，保证内容始终铺满 */
    private static void setupResizeCallback() {
        glfwSetFramebufferSizeCallback(window, (w, fw, fh) -> {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer ww = stack.mallocInt(1), wh = stack.mallocInt(1);
                glfwGetWindowSize(w, ww, wh);
                int lw = ww.get(0), lh = wh.get(0);
                if (lw <= 0 || lh <= 0) { lw = fw; lh = fh; }   // 极端情况下退回帧缓冲尺寸
                glViewport(0, 0, fw, fh);
                setProjection(fw, fh);   // 物理帧缓冲 → setProjection 内按合成缩放换算虚拟视口，随分辨率自适应
                CLBC_Util.logFine(String.format("窗口尺寸变化：窗口 %dx%d，帧缓冲 %dx%d → 虚拟视口 %dx%d（合成缩放 ×%.2f）",
                        lw, lh, fw, fh, windowWidth, windowHeight, getEffectiveUiScale()));
            }
        });
        CLBC_Util.log("已注册窗口尺寸变化回调（最大化 / 全屏 / 拖拽边框均自适应）");
    }

    /** F11 / 顶栏按钮 全屏切换：进入时用主显示器原生分辨率，退出还原进入前的窗口位置与尺寸 */
    public static void toggleFullscreen() {
        if (!isFullscreen) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer px = stack.mallocInt(1), py = stack.mallocInt(1);
                IntBuffer pw = stack.mallocInt(1), ph = stack.mallocInt(1);
                glfwGetWindowPos(window, px, py);
                glfwGetWindowSize(window, pw, ph);
                savedX = px.get(0); savedY = py.get(0); savedW = pw.get(0); savedH = ph.get(0);
            }
            GLFWVidMode mode = glfwGetVideoMode(glfwGetPrimaryMonitor());
            glfwSetWindowMonitor(window, glfwGetPrimaryMonitor(), 0, 0, mode.width(), mode.height(), mode.refreshRate());
            isFullscreen = true;
            CLBC_Util.log(String.format("进入全屏：%dx%d @%dHz（F11 退出）", mode.width(), mode.height(), mode.refreshRate()));
        } else {
            glfwSetWindowMonitor(window, NULL, savedX, savedY, savedW, savedH, 0);
            isFullscreen = false;
            CLBC_Util.log(String.format("退出全屏，还原窗口 %dx%d @(%d,%d)", savedW, savedH, savedX, savedY));
        }
    }

    /** 有效 UI 缩放：屏幕分辨率/DPI 自适应 × 用户全局缩放(ui.scale)。这是三档缩放里唯一作用于绘制的合成因子。 */
    public static float getEffectiveUiScale() {
        return clamp(screenScale * userUiScale, SCALE_LIMIT_LO, SCALE_LIMIT_HI);
    }
    public static float getScreenUiScale() { return screenScale; }
    public static float getUserUiScale() { return userUiScale; }

    private static float clamp(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }

    /**
     * 设置用户全局缩放（ui.scale）：设置面板拖动滑块、启动时读 config 都走这个入口。
     * 立即重算虚拟视口 + 投影矩阵，所以拖动后当帧即生效，无需重启；落盘由调用方负责。
     */
    public static void setUserUiScale(double value) {
        float nv = clamp((float) value, 0.5f, 2.0f);
        if (Math.abs(nv - userUiScale) < 1e-4f) return;
        userUiScale = nv;
        refreshProjection();
        CLBC_Util.log(String.format("全局缩放已应用：用户 ×%.2f × 屏幕/DPI %.2f = 合成 ×%.2f（虚拟视口 %dx%d）",
                userUiScale, screenScale, getEffectiveUiScale(), windowWidth, windowHeight));
    }

    /** DPI / 显示器变化时刷新屏幕基础缩放，再重算投影（自适应入口，无需重启） */
    private static void applyScreenScale(float raw) {
        float nv = clamp(raw > 0f ? raw : 1f, 0.5f, 3.0f);
        if (Math.abs(nv - screenScale) < 1e-3f) return;
        float old = screenScale;
        screenScale = nv;
        refreshProjection();
        CLBC_Util.log(String.format("屏幕分辨率/DPI 缩放自适应：×%.2f → ×%.2f（合成 ×%.2f）",
                old, screenScale, getEffectiveUiScale()));
    }

    /** 用当前物理帧缓冲尺寸重算投影（缩放变化 / DPI 变化时调用） */
    private static void refreshProjection() {
        if (shaderProgram == 0) return;   // init 前尚无 GL 程序，init 末尾会显式 setProjection
        setProjection(physWidth > 0 ? physWidth : 1280, physHeight > 0 ? physHeight : 800);
    }

    /** 读取窗口所在显示器的 DPI 内容缩放（100%=1.0，150%=1.5 ...），失败回退 1.0 */
    private static float readDisplayScale() {
        try (MemoryStack st = MemoryStack.stackPush()) {
            FloatBuffer sx = st.mallocFloat(1), sy = st.mallocFloat(1);
            glfwGetWindowContentScale(window, sx, sy);
            float v = Math.max(sx.get(0), sy.get(0));
            return v > 0f ? v : 1f;
        } catch (Throwable t) { return 1f; }
    }

    /**
     * 重设投影：width/height 为物理帧缓冲尺寸。
     * UI 采用虚拟坐标系（虚拟 = 物理 / 有效缩放）：布局、命中、鼠标、裁剪全在虚拟坐标，
     * 投影矩阵把虚拟坐标映射到整个物理视口；因此放大是"整体等比放大"，不会出现半屏/越界。
     */
    public static void setProjection(int width, int height) {
        physWidth = width; physHeight = height;
        float s = getEffectiveUiScale();
        int vw = Math.max(1, Math.round(width / s)), vh = Math.max(1, Math.round(height / s));
        windowWidth = vw; windowHeight = vh;
        projMat[0] =  2f / vw;  projMat[5] = -2f / vh;
        projMat[10]= -1f;       projMat[12]= -1f;
        projMat[13]= 1f;        projMat[15]= 1f;
        glUseProgram(shaderProgram);
        glUniformMatrix4fv(uProjLoc, false, projMat);
    }

    /** 每帧开头：清屏 + 把"挂起"的输入边沿搬入当前帧字段，供控件读取 */
    public static void beginFrame(float r, float g, float b) {
        // 每帧强制复位画布缩放绘制态：即便上一帧 CanvasHost 漏调 popCanvasZoom，也不把变换带进本帧的工具箱/代码面板。
        zoomActive = false;
        // 裁剪栈同样每帧复位：上一帧若有未配对的 setClip，不许把 scissor 残留带进本帧的其它面板。
        clipStack.clear();
        glDisable(GL_SCISSOR_TEST);
        // 👈 输入边沿搬运（在清屏前先完成，保证本帧控件读到的是"上一轮 poll 后"的事件）
        mouseJustPressed = pressEvents > 0;  pressEvents = 0;
        mouseJustReleased = releaseEvents > 0; releaseEvents = 0;
        mouseRightJustPressed = rightPressEvents > 0; rightPressEvents = 0;
        scrollDelta = scrollAccum;           scrollAccum = 0;
        typedChars.clear(); typedChars.addAll(typedAccum); typedAccum.clear();
        keyBackspace = bkspEdge; bkspEdge = false;
        keyDelete = delEdge; delEdge = false;
        keyLeft = leftEdge; leftEdge = false;
        keyRight = rightEdge; rightEdge = false;
        keyHome = homeEdge; homeEdge = false;
        keyEnd = endEdge; endEdge = false;
        keyEnter = enterEdge; enterEdge = false;
        keyZ = zEdge; zEdge = false;
        keyY = yEdge; yEdge = false;
        keyS = sEdge; sEdge = false;
        keyV = vEdge; vEdge = false;
        keyA = aEdge; aEdge = false;
        keyC = cEdge; cEdge = false;
        keyUp = upEdge; upEdge = false;
        keyDown = downEdge; downEdge = false;

        glClearColor(r, g, b, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT);
        glUseProgram(shaderProgram);
        glUniformMatrix4fv(uProjLoc, false, projMat);
        vertexBuffer.clear();
        currentTex = whiteTex;
        glBindTexture(GL_TEXTURE_2D, currentTex);
    }

    public static void endFrame() {
        if (vertexBuffer.position() != 0) {
            vertexBuffer.flip();
            glBindVertexArray(vao);
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            glBufferSubData(GL_ARRAY_BUFFER, 0, vertexBuffer);
            glUseProgram(shaderProgram);
            glBindTexture(GL_TEXTURE_2D, currentTex);
            glDrawArrays(GL_TRIANGLES, 0, vertexBuffer.limit() / 8);
            glBindVertexArray(0);
        }
        // 自检截图：必须在 swap 之前读 back buffer，否则内容已交换/未定义。
        if (capturePath != null) {
            String p = capturePath;
            capturePath = null;
            captureFrame(p);
        }
        glfwSwapBuffers(window);
        glfwPollEvents();
    }

    /** 首帧渲染完成后显示主窗口：此刻画面已就绪、紧接着进主循环持续 poll，
     *  避免"窗口可见但未 poll"被 Windows 判定未响应。headless 自检不显示。 */
    public static void showMainWindow() {
        if (window == NULL || window == 0L) return;
        if (Boolean.getBoolean("clbc.headless")) return;
        glfwShowWindow(window);
        glfwPollEvents();
        CLBC_Util.log("主窗口已显示（首帧就绪，可交互）");
    }

    /** 请求在下一帧 endFrame() 时把完整帧缓冲写成 PNG（一次性，不影响正常渲染）。 */
    public static void captureNextFrame(String pngPath) { capturePath = pngPath; }
    private static String capturePath = null;

    /** 读回默认 framebuffer（GL 原点左下 → PNG 原点左上需垂直翻转），纯 JDK 写盘。 */
    private static void captureFrame(String path) {
        ByteBuffer pixels = null;
        try {
            flushPending();
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glReadBuffer(GL_BACK);
            glDisable(GL_SCISSOR_TEST);          // glReadPixels 受 scissor 影响，先关掉
            int w = physWidth > 0 ? physWidth : windowWidth, h = physHeight > 0 ? physHeight : windowHeight;
            pixels = MemoryUtil.memAlloc(w * h * 4);
            glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < h; y++) {
                int row = (h - 1 - y) * w * 4;
                for (int x = 0; x < w; x++) {
                    int i = row + x * 4;
                    int a = pixels.get(i + 3) & 0xFF;
                    int r = pixels.get(i) & 0xFF;
                    int g = pixels.get(i + 1) & 0xFF;
                    int b = pixels.get(i + 2) & 0xFF;
                    img.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
                }
            }
            File out = new File(path);
            File parent = out.getAbsoluteFile().getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            ImageIO.write(img, "png", out);
            CLBC_Util.logFine("无窗口自检截图: " + out.getAbsolutePath() + " (" + w + "x" + h + ")");
        } catch (Throwable t) {
            CLBC_Util.log("!!!! 无窗口自检截图失败: " + t);
        } finally {
            if (pixels != null) MemoryUtil.memFree(pixels);
        }
    }

    public static boolean shouldClose() {
        return glfwWindowShouldClose(window);
    }

    /** 撤销关闭请求：退出确认弹窗选「取消」时调用，让主循环继续渲染。
     *  成对语义：GLFW 的 should-close 标志一旦被点 X 置位，只能显式写回 false 才能取消退出。 */
    public static void cancelClose() {
        glfwSetWindowShouldClose(window, false);
    }

    public static int getWindowWidth() { return windowWidth; }
    public static int getWindowHeight() { return windowHeight; }

    /** 释放全部 GL 资源：shader、纹理、VAO/VBO、顶点缓冲、字体图集、形状缓存。
     *  杜绝内存泄漏（设计文档 6. 红线）。退出时由 {@link #destroy()} 调用。 */
    public static synchronized void dispose() {
        if (disposed) return;      // 幂等：正常退出与 shutdown hook 只会真正释放一次，防 vertexBuffer 双重 memFree
        disposed = true;
        // 关键：glDeleteXxx / memFree 要求「当前线程持有 GL context」且不能与渲染线程并发。
        // 正常退出由渲染主线程调用（有 context）；但 JVM shutdown hook 线程没有 context，
        // 若在那里执行这些 native 调用会解引用线程本地空函数表 → native 访问违规 0xC0000005。
        // 无 context 时一律跳过 native 释放（进程即将退出，GL 对象/堆外内存由 OS 全部回收），只清 Java 引用。
        boolean hasContext = false;
        try { hasContext = glfwGetCurrentContext() != NULL; } catch (Throwable ignored) { }
        if (hasContext) {
            if (shaderProgram != 0) { glDeleteProgram(shaderProgram); shaderProgram = 0; }
            if (whiteTex != 0)      { glDeleteTextures(whiteTex); whiteTex = 0; }
            if (gridTex != 0)       { glDeleteTextures(gridTex); gridTex = 0; }   // 释放网格平铺纹理，杜绝泄漏
            for (int t : FILE_TEX_CACHE.values()) if (t != 0) glDeleteTextures(t); // 释放缩略图纹理缓存
            if (vao != 0)           { glDeleteVertexArrays(vao); vao = 0; }
            if (vbo != 0)           { glDeleteBuffers(vbo); vbo = 0; }
            if (vertexBuffer != null) { MemoryUtil.memFree(vertexBuffer); }
            if (uiFont != null)     { uiFont.dispose(); uiFont = null; }
        }
        FILE_TEX_CACHE.clear(); FILE_TEX_STAMP.clear();
        shapeCache.clear();
        unitCircle = null;
        TEXT_W_CACHE.clear();
        TEXT_W_FONT = null;
    }

    /** 幂等销毁：正常退出（CLBC.main 末尾）与 JVM 关闭钩子都会调用，只真正执行一次。
     *  各段 try/catch 兜底：即便 hook 线程没有 GL 上下文（GL 调用可能失败），也要保证 GLFW 窗口被销毁、glfwTerminate 执行。 */
    public static synchronized void destroy() {
        if (destroyed) return;
        destroyed = true;
        try { dispose(); }
        catch (Throwable t) { CLBC_Util.log("!!!! GL 资源释放异常（忽略，进程即将退出）: " + t.getMessage()); }
        try {
            if (window != NULL && window != 0L) { glfwDestroyWindow(window); window = NULL; }
            glfwTerminate();
            CLBC_Util.log("GL 资源已释放（窗口 / GLFW 已终止）");
        } catch (Throwable t) { CLBC_Util.log("!!!! GLFW 退出异常: " + t.getMessage()); }
    }

    // ==========================================================
    // 颜色工具（0xAARRGGBB 布局，与原 JavaFX #RRGGBB 解析一致）
    // ==========================================================
    public static long rgba(int r, int g, int b, int a) {
        return ((long) (a & 0xFF) << 24) | ((long) (r & 0xFF) << 16)
                | ((long) (g & 0xFF) << 8) | ((long) (b & 0xFF));
    }
    public static long rgb(int r, int g, int b) { return rgba(r, g, b, 255); }
    public static long gray(int v) { return rgba(v, v, v, 255); }

    /** 颜色变亮（amt∈[0,1]，0=不变，1=白）。用于顶高光 / 悬停态 */
    public static long lighten(long color, float amt) {
        float a = ((color >> 24) & 0xFF) / 255f;
        float r = ((color >> 16) & 0xFF) / 255f;
        float g = ((color >> 8) & 0xFF) / 255f;
        float b = (color & 0xFF) / 255f;
        r += (1 - r) * amt; g += (1 - g) * amt; b += (1 - b) * amt;
        return rgba((int) (r * 255), (int) (g * 255), (int) (b * 255), (int) (a * 255));
    }

    /** 颜色变暗（amt∈[0,1]，0=不变，1=黑）。用于积木描边 / 阴影区 */
    public static long darken(long color, float amt) {
        float a = ((color >> 24) & 0xFF) / 255f;
        float r = ((color >> 16) & 0xFF) / 255f;
        float g = ((color >> 8) & 0xFF) / 255f;
        float b = (color & 0xFF) / 255f;
        r *= (1 - amt); g *= (1 - amt); b *= (1 - amt);
        return rgba((int) (r * 255), (int) (g * 255), (int) (b * 255), (int) (a * 255));
    }
    /** 解析 "#RRGGBB" 或 "#AARRGGBB" 为 0xAARRGGBB 的 long；非法返回白色 */
    public static long hex(String s) {
        if (s == null) return rgb(255, 255, 255);
        String t = s.trim().replace("#", "").replace("0x", "");
        if (t.length() == 6) t = "FF" + t;
        if (t.length() == 8) {
            try { return Long.parseLong(t, 16) & 0xFFFFFFFFL; }
            catch (NumberFormatException e) { return rgb(255, 255, 255); }
        }
        return rgb(255, 255, 255);
    }

    // ==========================================================
    // 通用渲染原语（所有 UI 共用，属本类的核心职责）
    // ==========================================================

    /** 实心矩形（面板 / 背景） */
    public static void fillRect(long color, float x, float y, float w, float h) {
        drawPolygon(true, color,
                new float[]{x, x + w, x + w, x},
                new float[]{y, y, y + h, y + h});
    }

    /** 矩形描边（thickness 为线宽） */
    public static void strokeRect(long color, float x, float y, float w, float h, float thickness) {
        drawLine(x, y, x + w, y, color, thickness);
        drawLine(x + w, y, x + w, y + h, color, thickness);
        drawLine(x + w, y + h, x, y + h, color, thickness);
        drawLine(x, y + h, x, y, color, thickness);
    }

    /** 实心圆（工具箱分类圆点 / 网格点等）。
     *  优化：单位圆顶点已缓存（getUnitCircle），本方法只做线性缩放，零三角函数；
     *  缩放结果写入复用的静态缓冲 FILL_CIRC_X/Y，避免每帧 new float[] 造成 GC 抖动（设计文档 3. 节性能红线）。 */
    public static void fillCircle(long color, float cx, float cy, float r) {
        int seg = 28;
        float[] uc = getUnitCircle(seg);
        for (int i = 0; i < seg; i++) {
            FILL_CIRC_X[i] = cx + uc[i * 2] * r;
            FILL_CIRC_Y[i] = cy + uc[i * 2 + 1] * r;
        }
        drawPolygon(true, color, FILL_CIRC_X, FILL_CIRC_Y, seg);
    }

    /** 实心圆角矩形（沿用 drawRoundRect 的轮廓算法，但填充） */
    public static void fillRoundRect(long color, float x, float y, float w, float h, float radius) {
        drawRoundRect(true, color, x, y, w, h, radius);
    }

    /** 圆角矩形描边。线宽真正生效（修复原版重复调用且 thickness 失效的问题，设计文档 2.2/3.2） */
    public static void strokeRoundRect(long color, float x, float y, float w, float h, float radius, float thickness) {
        strokeWidth = Math.max(1f, thickness);
        drawRoundRect(false, color, x, y, w, h, radius);
        strokeWidth = 1f;
    }

    /** 纹理绘制（图标 / 图片）。texId<=0 时跳过。UV 取整张图 (0,0)-(1,1) */
    public static void drawImage(int texId, float x, float y, float w, float h) {
        if (texId <= 0) return;
        setTexture(texId);
        addVertex(x,     y,     0, 0, 1, 1, 1, 1);
        addVertex(x + w, y,     1, 0, 1, 1, 1, 1);
        addVertex(x + w, y + h, 1, 1, 1, 1, 1, 1);
        addVertex(x,     y,     0, 0, 1, 1, 1, 1);
        addVertex(x + w, y + h, 1, 1, 1, 1, 1, 1);
        addVertex(x,     y + h, 0, 1, 1, 1, 1, 1);
    }

    // 裁剪栈：嵌套 setClip（例如 textField 内部）不得把外层裁剪整个关掉。
    // clearClip 只弹栈顶、恢复上一层；栈空才真正关闭 scissor。
    private static final java.util.ArrayDeque<float[]> clipStack = new java.util.ArrayDeque<>();

    /** 立即提交已攒顶点。GL 是批量顶点缓冲，而 glScissor 是「立即状态」：
     *  若改裁剪前不 flush，先前攒的顶点会在「后来的裁剪」下被刷出，
     *  表现为网格点阵溢出覆盖工具箱／代码面板（画布内无积木、没有切纹理触发 flush 时尤其明显）。 */
    private static void flushPending() {
        if (vertexBuffer.position() == 0) return;
        vertexBuffer.flip();
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0, vertexBuffer);
        glUseProgram(shaderProgram);
        glBindTexture(GL_TEXTURE_2D, currentTex);
        glDrawArrays(GL_TRIANGLES, 0, vertexBuffer.limit() / 8);
        glBindVertexArray(0);
        vertexBuffer.clear();
    }

    /** 逻辑裁剪矩形 → glScissor 物理像素：乘合成缩放并翻转 y，保证缩放后裁剪框与画面同位置。
     *  glScissor 不经过顶点变换，必须在这里手动做与投影一致的换算。 */
    private static void applyScissor(float x, float y, float w, float h) {
        float s = getEffectiveUiScale();
        int px = Math.max(0, (int) (x * s));
        int py = Math.max(0, (int) (physHeight - (y + h) * s));
        int pw = Math.max(1, (int) (w * s));
        int ph = Math.max(1, (int) (h * s));
        glScissor(px, py, pw, ph);
    }

    /** 设置裁剪区域（对应 JavaFX ScrollPane 的内容裁剪）。坐标采用左上原点、y 向下，与 UI 一致 */
    public static void setClip(float x, float y, float w, float h) {
        flushPending();                       // 先把攒的顶点按「旧裁剪」刷出去
        clipStack.push(new float[]{x, y, w, h});
        glEnable(GL_SCISSOR_TEST);
        applyScissor(x, y, w, h);
    }
    /** 逻辑矩形裁剪：与 addVertex 同套 zoom 正变换。
     *  glScissor 用的是物理屏幕坐标、不会经过顶点变换；画布缩放期间直接拿逻辑坐标裁，
     *  裁剪框会整体偏到别处（下拉候选被切、注释文字裁错）都源于此。
     *  非缩放态（工具箱 / 代码面板 / 各屏幕）自动退化为原样。 */
    public static void setClipLogical(float x, float y, float w, float h) {
        if (!zoomActive) { setClip(x, y, w, h); return; }
        float sx = zoomOx + (x - zoomOx) * zoomS;
        float sy = zoomOy + (y - zoomOy) * zoomS;
        setClip(sx, sy, w * zoomS, h * zoomS);
    }
    public static void clearClip() {
        flushPending();                       // 先把攒的顶点按「当前裁剪」刷出去
        if (!clipStack.isEmpty()) clipStack.pop();
        if (clipStack.isEmpty()) {
            glDisable(GL_SCISSOR_TEST);
        } else {
            float[] p = clipStack.peek();
            glEnable(GL_SCISSOR_TEST);
            applyScissor(p[0], p[1], p[2], p[3]);
        }
    }

    /** 测量文本像素宽度（基于字体图集的 advance）。
     *  优化：结果按文本缓存（TEXT_W_CACHE），相同字符串（积木文本大量重复）不重复累加 glyph advance；
     *  缓存绑定当前 UI 字体，字体切换时自动清空以防串味。上限 4096 项防膨胀。 */
    public static float measureText(CLBC_GL_Font font, String text) {
        if (font == null || text == null) return 0;
        if (font != TEXT_W_FONT) { TEXT_W_CACHE.clear(); TEXT_W_FONT = font; }  // 字体变了，旧缓存失效
        Float cached = TEXT_W_CACHE.get(text);
        if (cached != null) return cached;
        float w = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            w += font.getGlyph(cp).adv;
        }
        TEXT_W_CACHE.put(text, w);
        return w;
    }

    // ==========================================================
    // 纹理加载（STBImage，支持 PNG 等）
    // ==========================================================
    public static int loadTexture(ByteBuffer png) {
        // 关键修复：stbi 的垂直翻转是【全局状态】。闪屏 CLBC_Splash.uploadTexture 为配套它的
        // UV 设过 stbi_set_flip_vertically_on_load(true) 却没复位，导致这里加载的壁纸/图标
        // 全部被上下翻转（截图里皮卡丘倒立）。本类 drawImage 的 UV 约定是「左上=(0,0)、
        // STB 第0行=图像顶部」，必须不翻转。故每次加载前显式复位，杜绝被别处污染。
        STBImage.stbi_set_flip_vertically_on_load(false);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1), h = stack.mallocInt(1), ch = stack.mallocInt(1);
            ByteBuffer data = STBImage.stbi_load_from_memory(png, w, h, ch, 4);
            if (data == null) return 0;
            int tex = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, tex);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w.get(0), h.get(0), 0, GL_RGBA, GL_UNSIGNED_BYTE, data);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            STBImage.stbi_image_free(data);
            glBindTexture(GL_TEXTURE_2D, 0);
            return tex;
        }
    }

    /** 从 classpath 资源（如 /icons/xxx.png）加载纹理；失败返回 0 */
    public static int loadTextureFromResource(String resPath) {
        try (InputStream in = CLBC_GL_API.class.getResourceAsStream(resPath)) {
            if (in == null) return 0;
            byte[] bytes = in.readAllBytes();
            ByteBuffer buf = MemoryUtil.memAlloc(bytes.length).put(bytes).flip();
            int tex = loadTexture(buf);
            MemoryUtil.memFree(buf);
            return tex;
        } catch (Exception e) {
            return 0;
        }
    }

    // 磁盘 PNG → 纹理缓存（按 路径 + 修改时间 + 大小 失效，避免每帧重复上传造成 GL 纹理泄漏）
    private static final java.util.Map<String, Integer> FILE_TEX_CACHE = new java.util.HashMap<>();
    private static final java.util.Map<String, String> FILE_TEX_STAMP = new java.util.HashMap<>();

    /**
     * 从磁盘 PNG 加载纹理（带缓存）：文件内容未变则复用旧纹理，变化则删除旧纹理并重新上传。
     * 供工程缩略图预览等场景每帧调用，失败返回 0（绘制方按空纹理跳过）。
     */
    public static int loadTextureFromFileCached(String path) {
        if (path == null) return 0;
        try {
            java.io.File f = new java.io.File(path);
            if (!f.isFile()) return 0;
            String stamp = f.lastModified() + ":" + f.length();
            Integer cached = FILE_TEX_CACHE.get(path);
            if (cached != null && stamp.equals(FILE_TEX_STAMP.get(path))) return cached;
            byte[] bytes = java.nio.file.Files.readAllBytes(f.toPath());
            ByteBuffer buf = MemoryUtil.memAlloc(bytes.length).put(bytes);
            buf.flip();
            int tex = loadTexture(buf);
            MemoryUtil.memFree(buf);
            if (cached != null && cached != 0) glDeleteTextures(cached);
            FILE_TEX_CACHE.put(path, tex);
            FILE_TEX_STAMP.put(path, stamp);
            return tex;
        } catch (Exception e) {
            CLBC_Util.log("缩略图纹理加载失败: " + e.getMessage());
            return 0;
        }
    }

    /** 由原始 RGBA 字节（w*h*4）直接创建纹理；供程序化生成的小图（如网格平铺）使用。失败返回 0 */
    public static int createTexture(int w, int h, ByteBuffer rgba) {
        if (rgba == null || w <= 0 || h <= 0) return 0;
        int tex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, tex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, rgba);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glBindTexture(GL_TEXTURE_2D, 0);
        return tex;
    }

    /** 懒初始化网格平铺纹理：一次性烘焙 240×240（含 10×10 个 24px 间距圆点）的小图。
     *  配合 drawGrid 的平铺调用，替代原先"每帧逐点调用 fillCircle（约数千次/帧）"的实现，
     *  绘制调用从数千骤降至数十，且仅一次纹理切换。幂等，重复调用直接返回已有纹理。 */
    public static void ensureGridTile() {
        if (gridTex != 0) return;
        final int S = 240, step = 24, r = 1;
        ByteBuffer px = MemoryUtil.memAlloc(S * S * 4);
        for (int i = 0; i < S * S; i++) px.putInt(0);   // 全透明底
        // 网格点色随主题切换（浅色 #ECECEC / 深色 #3A3A3A），取 CLBC_Theme 下发值
        byte gc = (byte) (gridDotColor & 0xFF);
        for (int gx = 0; gx <= S - step; gx += step)
            for (int gy = 0; gy <= S - step; gy += step)
                for (int dy = -r; dy <= r; dy++)
                    for (int dx = -r; dx <= r; dx++) {
                        int X = gx + dx, Y = gy + dy;
                        if (X >= 0 && X < S && Y >= 0 && Y < S) {
                            int idx = (Y * S + X) * 4;
                            px.put(idx, gc); px.put(idx + 1, gc); px.put(idx + 2, gc); px.put(idx + 3, (byte) 0xFF);
                        }
                    }
        px.flip();
        gridTex = createTexture(S, S, px);
        MemoryUtil.memFree(px);
        CLBC_Util.logFine("渲染层优化：网格平铺纹理已生成 (" + S + "x" + S + ", 间距 " + step
                + "px) —— 由逐点 fillCircle 改为单次平铺，绘制调用从数千降至数十");
    }

    /** 取网格平铺纹理 id（drawGrid 使用；未初始化时返回 0，调用方应先 ensureGridTile） */
    public static int getGridTile() { return gridTex; }

    // ==========================================================
    // 以下为原引擎保留的绘制方法（多边形 / 线 / 圆角 / 文字 / 顶点）
    // ==========================================================

    // 切换纹理（切换时需要把之前的画完）
    private static void setTexture(int tex) {
        if (tex != currentTex) {
            if (vertexBuffer.position() > 0) {
                vertexBuffer.flip();
                glBindVertexArray(vao);
                glBindBuffer(GL_ARRAY_BUFFER, vbo);
                glBufferSubData(GL_ARRAY_BUFFER, 0, vertexBuffer);
                glBindTexture(GL_TEXTURE_2D, currentTex);
                glDrawArrays(GL_TRIANGLES, 0, vertexBuffer.limit() / 8);
                glBindVertexArray(0);
                vertexBuffer.clear();
            }
            currentTex = tex;
            glBindTexture(GL_TEXTURE_2D, currentTex);
        }
    }

    /** 多边形绘制（兼容旧调用：以数组长度作为顶点数） */
    public static void drawPolygon(boolean filled, long color, float[] xList, float[] yList) {
        drawPolygon(filled, color, xList, yList, xList.length);
    }

    /** 多边形绘制（count 版本）：允许传入比数组更短的"有效顶点数"，
     *  从而复用固定大小的静态缓冲（如 RR_X/RR_Y）而无需为每次调用 new 子数组。 */
    public static void drawPolygon(boolean filled, long color, float[] xList, float[] yList, int count) {
        if (xList.length != yList.length || count < 3 || count > xList.length) return;
        bindSolidTexture();

        float a = ((color >> 24) & 0xFF) / 255.0f;
        float r = ((color >> 16) & 0xFF) / 255.0f;
        float g = ((color >> 8) & 0xFF) / 255.0f;
        float b = (color & 0xFF) / 255.0f;

        if (!filled) {
            for (int i = 0; i < count; i++) {
                int next = (i + 1) % count;
                drawLine(xList[i], yList[i], xList[next], yList[next], r, g, b, a);
            }
        } else {
            for (int i = 1; i < count - 1; i++) {
                addVertex(xList[0], yList[0], solidU, solidV, r, g, b, a);
                addVertex(xList[i], yList[i], solidU, solidV, r, g, b, a);
                addVertex(xList[i+1], yList[i+1], solidU, solidV, r, g, b, a);
            }
        }
    }

    /** 线段（公开，供描边 / 连线使用） */
    public static void drawLine(float x1, float y1, float x2, float y2, float r, float g, float b, float a) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len == 0) return;
        // 半宽 0.5px → 视觉线宽 1px（原实现法线未减半，实际画出 2px 粗边）
        float nx = -dy / len * 0.5f;
        float ny =  dx / len * 0.5f;

        addVertex(x1 + nx, y1 + ny, solidU, solidV, r, g, b, a);
        addVertex(x2 + nx, y2 + ny, solidU, solidV, r, g, b, a);
        addVertex(x2 - nx, y2 - ny, solidU, solidV, r, g, b, a);

        addVertex(x1 + nx, y1 + ny, solidU, solidV, r, g, b, a);
        addVertex(x2 - nx, y2 - ny, solidU, solidV, r, g, b, a);
        addVertex(x1 - nx, y1 - ny, solidU, solidV, r, g, b, a);
    }

    /** 公开重载：以颜色 long 形式画线段。
     *  优化/修复：先 setTexture(whiteTex) 确保线使用纯色白纹理。
     *  此前若上一次绘制是 drawText（绑定字体图集纹理），线段会错误取样字体纹理导致显示异常；
     *  此处统一绑定白纹理，消除状态依赖。 */
    public static void drawLine(float x1, float y1, float x2, float y2, long color, float thickness) {
        bindSolidTexture();   // 👈 强制白纹理，避免沿用上一次 drawText 的字体图集纹理
        float r = ((color >> 16) & 0xFF) / 255.0f;
        float g = ((color >> 8) & 0xFF) / 255.0f;
        float b = (color & 0xFF) / 255.0f;
        float a = ((color >> 24) & 0xFF) / 255.0f;
        // 👈 简单处理线宽：以 thickness 为偏移放大法线方向（保持 1px 视觉为默认）
        if (thickness <= 1f) { drawLine(x1, y1, x2, y2, r, g, b, a); return; }
        float dx = x2 - x1, dy = y2 - y1;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len == 0) return;
        float nx = -dy / len * thickness, ny = dx / len * thickness;
        addVertex(x1 + nx, y1 + ny, solidU, solidV, r, g, b, a);
        addVertex(x2 + nx, y2 + ny, solidU, solidV, r, g, b, a);
        addVertex(x2 - nx, y2 - ny, solidU, solidV, r, g, b, a);
        addVertex(x1 + nx, y1 + ny, solidU, solidV, r, g, b, a);
        addVertex(x2 - nx, y2 - ny, solidU, solidV, r, g, b, a);
        addVertex(x1 - nx, y1 - ny, solidU, solidV, r, g, b, a);
    }

    /** 圆角矩形（填充或描边）。轮廓顶点按 尺寸+半径 缓存（getRoundRectVerts），形状不变则复用，避免每帧重算三角函数（设计文档 3. 节）。
     *  优化：将轮廓顶点偏移后写入复用的静态缓冲 RR_X/RR_Y（seg=8 → n=36 ≤ 64），无需每帧 new 数组。 */
    public static void drawRoundRect(boolean filled, long color, float x, float y, float w, float h, float radius) {
        float[] vl = getRoundRectVerts(w, h, radius, 8);
        int n = vl.length / 2;
        for (int i = 0; i < n; i++) { RR_X[i] = vl[i * 2] + x; RR_Y[i] = vl[i * 2 + 1] + y; }
        if (filled) {
            drawPolygon(true, color, RR_X, RR_Y, n);
        } else {
            bindSolidTexture();
            for (int i = 0; i < n; i++) {
                int nx = (i + 1) % n;
                drawLine(RR_X[i], RR_Y[i], RR_X[nx], RR_Y[nx], color, strokeWidth);
            }
        }
    }

    /** 遮罩法掩盖：用指定颜色（通常=背景色）的圆角矩形覆盖，从而"咬"出内凹区域。供积木内凹圆角使用（设计文档 3.1） */
    public static void maskRoundRect(long maskColor, float x, float y, float w, float h, float radius) {
        drawRoundRect(true, maskColor, x, y, w, h, radius);
    }

    /**
     * 积木外壳：圆角矩形主体 + 深色描边 + 顶部高光 + 可选顶部帽(hat) / 顶部插头(topNotch) / 底部缺口(notched)。
     *  · 堆叠块互锁：topNotch 在顶部中央凸出一小圆插头，notched 在底部中央用遮罩法"咬"出缺口，
     *    上一块的底部缺口恰好容纳下一块的顶部插头，形成咬合堆叠（设计文档 3.1）。
     *  · hat 帽块（类/方法）：顶部中央大圆凸起，表示"程序/代码段入口"。
     *  · 描边 + 顶高光赋予经典塑料质感。
     */
    public static void drawBlockBody(long color, float x, float y, float w, float h,
                                     boolean hat, boolean topNotch, boolean bottomNotch) {
        drawBlockBody(color, x, y, w, h, hat, topNotch, bottomNotch, MASK_COLOR);
    }

    /**
     * 带 backdrop 版：缺口用"背后实际颜色"咬出。顶层块传画布底色；嵌套块传父块内腔色，
     * 避免在内腔里挖出一块黑（原遮罩法假定背景恒为画布色，嵌套时观感失真）。
     */
    public static void drawBlockBody(long color, float x, float y, float w, float h,
                                     boolean hat, boolean topNotch, boolean bottomNotch, long backdrop) {
        float r = 12;
        fillRoundRect(color, x, y, w, h, r);
        // 深色描边（积木边缘）
        strokeRoundRect(darken(color, 0.32f), x, y, w, h, r, 1.5f);
        // 顶部高光（塑料质感）：沿上缘一条浅色细线
        drawLine(x + 12, y + 4, x + w - 12, y + 4, lighten(color, 0.45f), 2f);
        if (hat) {
            fillCircle(color, x + w / 2f, y, 8);          // 顶部中央凸起（帽子）
            strokeRoundRect(darken(color, 0.32f), x + w / 2f - 8, y - 8, 16, 16, 8, 1.5f);
        }
        if (topNotch) {
            fillCircle(color, x + w / 2f, y - 1, 7);      // 顶部中央插头（与上块底部缺口咬合）
            strokeRoundRect(darken(color, 0.32f), x + w / 2f - 7, y - 8, 14, 14, 7, 1.5f);
        }
        if (bottomNotch) {
            maskRoundRect(backdrop, x + w / 2f - 7, y + h - 5, 14, 14, 7);  // 底部中央缺口
        }
    }

    /** 按 尺寸+半径 取缓存的圆角矩形局部顶点（左上角原点），仅在尺寸变化时重算 */
    private static float[] getRoundRectVerts(float w, float h, float radius, int segments) {
        radius = Math.min(radius, Math.min(w / 2, h / 2));
        String key = w + "x" + h + "x" + radius + "x" + segments;
        float[] cached = shapeCache.get(key);
        if (cached != null) return cached;
        cached = buildRoundRectVerts(0, 0, w, h, radius, segments);
        shapeCache.put(key, cached);
        return cached;
    }

    /** 生成圆角矩形轮廓顶点（局部坐标，左上角原点），仅在首次/尺寸变化时调用（含三角函数） */
    private static float[] buildRoundRectVerts(float x, float y, float w, float h, float radius, int segments) {
        float[] v = new float[4 * (segments + 1) * 2];
        int index = 0;
        for (int i = 0; i <= segments; i++) {
            float angle = (float) (Math.PI + i * (Math.PI / 2) / segments);
            v[index++] = x + radius + (float) Math.cos(angle) * radius;
            v[index++] = y + radius + (float) Math.sin(angle) * radius;
        }
        for (int i = 0; i <= segments; i++) {
            float angle = (float) (Math.PI * 1.5 + i * (Math.PI / 2) / segments);
            v[index++] = x + w - radius + (float) Math.cos(angle) * radius;
            v[index++] = y + radius + (float) Math.sin(angle) * radius;
        }
        for (int i = 0; i <= segments; i++) {
            float angle = (float) (0 + i * (Math.PI / 2) / segments);
            v[index++] = x + w - radius + (float) Math.cos(angle) * radius;
            v[index++] = y + h - radius + (float) Math.sin(angle) * radius;
        }
        for (int i = 0; i <= segments; i++) {
            float angle = (float) (Math.PI * 0.5 + i * (Math.PI / 2) / segments);
            v[index++] = x + radius + (float) Math.cos(angle) * radius;
            v[index++] = y + h - radius + (float) Math.sin(angle) * radius;
        }
        return v;
    }

    /** 取单位圆顶点（半径1，seg 段），缓存避免每帧重算三角函数 */
    private static float[] getUnitCircle(int seg) {
        if (unitCircle != null && unitCircle.length == seg * 2) return unitCircle;
        float[] v = new float[seg * 2];
        for (int i = 0; i < seg; i++) {
            double a = i * 2 * Math.PI / seg;
            v[i * 2] = (float) Math.cos(a);
            v[i * 2 + 1] = (float) Math.sin(a);
        }
        unitCircle = v;
        return v;
    }

    /** 文字渲染核心（公开，供控件与积木复用） */
    public static void drawText(CLBC_GL_Font font, String text, float x, float y, long color) {
        if (font == null || text == null) return;
        setTexture(font.getTextureId());

        float a = ((color >> 24) & 0xFF) / 255.0f;
        float r = ((color >> 16) & 0xFF) / 255.0f;
        float g = ((color >> 8) & 0xFF) / 255.0f;
        float b = (color & 0xFF) / 255.0f;

        float penX = x;
        float penY = y;

        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            CLBC_GL_Font.Glyph gl = font.getGlyph(cp);
            if (gl.w > 0 && gl.h > 0) {
                // 字形四边对齐到整数像素：图集超采样纹理与屏幕像素一一对应，
                // 不再产生亚像素双线性插值，中文小字笔画清晰不发虚。
                float x0 = Math.round(penX + gl.bx);
                float y0 = Math.round(penY + gl.by);
                float x1 = Math.round(x0 + gl.w);
                float y1 = Math.round(y0 + gl.h);

                addVertex(x0, y0, gl.u0, gl.v0, r, g, b, a);
                addVertex(x1, y0, gl.u1, gl.v0, r, g, b, a);
                addVertex(x1, y1, gl.u1, gl.v1, r, g, b, a);

                addVertex(x0, y0, gl.u0, gl.v0, r, g, b, a);
                addVertex(x1, y1, gl.u1, gl.v1, r, g, b, a);
                addVertex(x0, y1, gl.u0, gl.v1, r, g, b, a);
            }
            penX += gl.adv;
        }
    }

    /** 居中文字（控件常用）：在 (cx,cy) 指定的矩形内水平垂直居中绘制 */
    public static void drawTextCentered(CLBC_GL_Font font, String text, float x, float y, float w, float h, long color) {
        if (font == null || text == null) return;
        float tw = measureText(font, text);
        float th = font.getPixelHeight();
        float tx = x + (w - tw) / 2;
        float ty = y + (h - th) / 2 + th * 0.75f; // 基线近似
        drawText(font, text, tx, ty, color);
    }

    private static void addVertex(float x, float y, float u, float v, float r, float g, float b, float a) {
        if (vertexBuffer.remaining() < 8) {
            // 缓冲满：绝不能写越界（那是 native 崩溃源）。静默丢弃曾导致「卡+缺字」难排查，
            // 现在记一次显著日志，提示把 VERTEX_CAP 再调大或该工程积木数超出设计上限。
            if (!vertexOverflowLogged) {
                vertexOverflowLogged = true;
                CLBC_Util.log("⚠ 单帧顶点缓冲耗尽（上限 " + VERTEX_CAP
                        + " 顶点）：本帧后续几何/文字被丢弃，可能出现缺字或缺边。请调大 VERTEX_CAP。");
            }
            return;
        }
        // 画布缩放绘制态：把逻辑坐标正变换到屏幕。仅 pushCanvasZoom/popCanvasZoom 包裹的画布绘制会命中此分支。
        if (zoomActive) {
            x = zoomOx + (x - zoomOx) * zoomS;
            y = zoomOy + (y - zoomOy) * zoomS;
        }
        vertexBuffer.put(x).put(y).put(u).put(v).put(r).put(g).put(b).put(a);
    }

    private static void initShaders() {
        String vs = """
            #version 330 core
            layout (location = 0) in vec2 aPos;
            layout (location = 1) in vec2 aUV;
            layout (location = 2) in vec4 aColor;
            uniform mat4 uProj;
            out vec2 vUV;
            out vec4 vColor;
            void main() {
                gl_Position = uProj * vec4(aPos, 0.0, 1.0);
                vUV = aUV;
                vColor = aColor;
            }
            """;
        String fs = """
            #version 330 core
            in vec2 vUV;
            in vec4 vColor;
            uniform sampler2D uTex;
            out vec4 FragColor;
            void main() {
                FragColor = texture(uTex, vUV) * vColor;
            }
            """;

        int v = glCreateShader(GL_VERTEX_SHADER);
        glShaderSource(v, vs); glCompileShader(v);
        if (glGetShaderi(v, GL_COMPILE_STATUS) == GL_FALSE) {
            String msg = "❌ 顶点 Shader 编译失败: " + glGetShaderInfoLog(v);
            System.err.println(msg);
            clbc.util.CLBC_Util.log(msg);
        }

        int f = glCreateShader(GL_FRAGMENT_SHADER);
        glShaderSource(f, fs); glCompileShader(f);
        if (glGetShaderi(f, GL_COMPILE_STATUS) == GL_FALSE) {
            String msg = "❌ 片段 Shader 编译失败: " + glGetShaderInfoLog(f);
            System.err.println(msg);
            clbc.util.CLBC_Util.log(msg);
        }

        shaderProgram = glCreateProgram();
        glAttachShader(shaderProgram, v);
        glAttachShader(shaderProgram, f);
        glLinkProgram(shaderProgram);
        if (glGetProgrami(shaderProgram, GL_LINK_STATUS) == GL_FALSE) {
            String msg = "❌ Shader 链接失败: " + glGetProgramInfoLog(shaderProgram);
            System.err.println(msg);
            clbc.util.CLBC_Util.log(msg);
        }
        glDeleteShader(v); glDeleteShader(f);
        uProjLoc = glGetUniformLocation(shaderProgram, "uProj");
        uTexLoc = glGetUniformLocation(shaderProgram, "uTex");
        glUseProgram(shaderProgram);
        glUniform1i(uTexLoc, 0);
    }
}
