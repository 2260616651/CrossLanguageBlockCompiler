package clbc.core;

import clbc.gl.CLBC_GL_API;
import clbc.gl.CLBC_GL_Font;
import clbc.ui.CLBC_Renderer;
import clbc.ui.CLBC_Theme;
import clbc.ui.CLBC_Audio;
import clbc.blocks.CLBC_JavaAST;
import clbc.blocks.CLBC_BlockRegistry;
import clbc.core.CLBC_Environment;
import clbc.util.CLBC_Util;

/**
 * CLBC 程序入口（main）。
 * 生命周期：弹 OpenGL 闪屏 → 闪屏后台线程跑引导初始化（日志/缓存/配置）
 * → 解析 JDK 位置 → 初始化 OpenGL 窗口与字体 → 进入自绘主渲染循环（{@link CLBC_Renderer}）。
 *
 * 与旧版的区别：不再依赖 JavaFX 的 Application / Stage / Scene，
 * 整套界面由 {@link CLBC_GL_API} + {@link CLBC_GL_Widgets} 即时模式自绘。
 */
public class CLBC {

    /** 微软雅黑字体路径（Windows） */
    private static final String YAHEI_FONT = "C:\\Windows\\Fonts\\msyhbd.ttc";

    public static void main(String[] args) {
        CLBC_Util.log("====== CLBC 启动（OpenGL 版） ======");

        // 1. OpenGL 闪屏（自含 GLFW + GL，后台线程跑引导初始化）
        CLBC_Splash.show("/icons/logo.png", ignored -> {
            CLBC_Util.log("闪屏回调：开始引导初始化");
            new CLBC_Bootstrap().init();
            // ⑥ 加载并识别编程环境（探测本机语言运行时；仅记日志，不阻断）
            CLBC_Environment.detect();
            // 积木登记器预热（近万条反射定义+中文化，约 4s）一并放进闪屏后台线程：
            // 此前它在闪屏销毁后、主窗口创建前于主线程执行，造成「闪屏关了但窗口未响应」的数秒真空。
            // installBuiltins 是纯数据登记、不碰 GL，且幂等+synchronized，放此安全；
            // 主流程里的同名调用保留作兜底（幂等，正常路径下这里已装好，那里一次 volatile 读即返回）。
            CLBC_BlockRegistry.installBuiltins();
        });

        // 2. 解析 / 选择 JDK 位置
        new CLBC_JavaLocator().resolve();

        // 3. 初始化 OpenGL 窗口（800P 比例 0.8）
        int w = 1280, h = 800;
        CLBC_GL_API.init("CLBC", w, h);
        CLBC_AppContext.windowWidth = w;
        CLBC_AppContext.windowHeight = h;
        // 三档缩放之①屏幕分辨率/DPI：CLBC_GL_API.init 内已读显示器 content scale，并注册 DPI 变化自适应回调。
        // 三档缩放之②全局缩放：把 config 的 ui.scale 真正注入绘制层（旧代码只让 CLBC_Util.updateUiScale 算了一个
        // 从未被绘制层消费的 CLBC_AppContext.uiScale，等于没生效）；设置面板滑块改动也走同一入口，即时生效。
        double cfgUiScale = 1.30;
        try {
            if (CLBC_AppContext.configFile != null) {
                String v = CLBC_Util.loadProperties(CLBC_AppContext.configFile).getProperty("ui.scale");
                if (v != null) cfgUiScale = Double.parseDouble(v);
            }
        } catch (Throwable t) {
            CLBC_Util.log("读取 ui.scale 失败，使用默认 1.30: " + t.getMessage());
        }
        CLBC_GL_API.setUserUiScale(cfgUiScale);
        CLBC_Util.log(String.format("UI 缩放已生效：屏幕/DPI ×%.2f × 用户全局 ×%.2f = 合成 ×%.2f（虚拟视口 %dx%d）",
                CLBC_GL_API.getScreenUiScale(), CLBC_GL_API.getUserUiScale(),
                CLBC_GL_API.getEffectiveUiScale(), CLBC_GL_API.getWindowWidth(), CLBC_GL_API.getWindowHeight()));

        // 4. 加载字体（微软雅黑）—— 必须在 GL 上下文就绪之后
        // 【T5】字号读配置 editor.fontSize（设置面板滑块写的值），不再写死 18f。
        // 这样"字体大小"设置真正生效：改字号→设置页写盘→重启按新字号建字体。
        float cfgFontSize = 26f;
        try {
            if (CLBC_AppContext.configFile != null) {
                String fv = CLBC_Util.loadProperties(CLBC_AppContext.configFile).getProperty("editor.fontSize");
                if (fv != null) cfgFontSize = (float) Math.round(Double.parseDouble(fv));
            }
        } catch (Throwable t) {
            CLBC_Util.log("读取 editor.fontSize 失败，使用默认 26: " + t.getMessage());
        }
        try {
            CLBC_GL_API.uiFont = new CLBC_GL_Font(YAHEI_FONT, cfgFontSize);
            CLBC_Util.log("字体加载成功: " + YAHEI_FONT + "，字号 " + String.format("%.0f", cfgFontSize));
        } catch (RuntimeException e) {
            CLBC_Util.Error_Dialog("字体加载失败（请确认 " + YAHEI_FONT + " 存在）: " + e.getMessage(), false);
        }

        // 5. 启动前先跑一次 Java 积木 AST 解析（原测试代码保留）
        String testCode = "public class Main {\n" +
                "    public static void main(String[] args) {\n" +
                "        System.out.println(\"测试\");\n" +
                "        int a = Math.max(10, 20);\n" +
                "        Thread.sleep(1000);\n" +
                "    }\n" +
                "}\n";
        CLBC_JavaAST.analyze(testCode);

        // 6. 进入自绘主循环
        CLBC_Util.log("引导完成，进入 OpenGL 主界面");
        CLBC_Theme.init();                                  // 主题 / 壁纸 / 音乐配置读入（配置单一真源）

        // 启动阶段预热积木登记器：把「反射登记近万条定义 + 逐条中文化」的一次性重活放在进入渲染循环之前，
        // 绝不让首次 templateOf/byCategory 触发的 installBuiltins 落在某一渲染帧里阻塞主线程。
        // 此调用幂等，渲染线程后续再问只是一次 volatile 读。
        CLBC_BlockRegistry.installBuiltins();

        CLBC_Renderer renderer = new CLBC_Renderer();
        // 首帧预渲染：窗口此时仍隐藏（init 里设 VISIBLE=false），先把完整一帧画好再显示。
        // 这样窗口一出现就是完整画面，紧接着进主循环持续 poll，从根上杜绝"未响应"空档。
        long ws0 = CLBC_Theme.current().WORKSPACE;
        CLBC_GL_API.beginFrame(((ws0 >> 16) & 0xFF) / 255f, ((ws0 >> 8) & 0xFF) / 255f, (ws0 & 0xFF) / 255f);
        renderer.frame();
        CLBC_GL_API.endFrame();
        CLBC_GL_API.showMainWindow();          // 首帧就绪后才显示窗口（可交互）
        // 启动背景音乐：优先从「已添加文件夹提取的曲目」里随机选一首；
        // 没有曲目时兼容旧的单曲配置 ui.bgm。两者都为空则不播放。
        // 放到后台线程：ffplay 探测/拉起可能耗时数秒（外部进程 -version + 启动），
        // 同步执行会让主窗口创建后数秒内点不动（Windows 显示"未响应"）。音乐晚几秒开始无感。
        final String startBgm0 = CLBC_Theme.randomTrack().isEmpty() ? CLBC_Theme.bgm : CLBC_Theme.randomTrack();
        final float startVol0 = CLBC_Theme.bgmVolume;
        if (!startBgm0.isEmpty()) {
            Thread bgmThread = new Thread(() -> {
                try { CLBC_Audio.play(startBgm0, startVol0); }
                catch (Throwable t) { CLBC_Util.log("!!!![BGM] 后台启动失败: " + t.getMessage()); }
            }, "clbc-bgm-start");
            bgmThread.setDaemon(true);
            bgmThread.start();
        }

        // 唯一退出拦截点：GLFW 点 X → shouldClose() 置位 → 这里先询问未保存改动，
        // 选「取消」则 cancelClose() 写回 false，主循环继续；否则才离开循环走资源清理。
        while (true) {
            if (CLBC_GL_API.shouldClose()) {
                if (renderer.isDirty()) {
                    CLBC_Util.log("检测到未保存改动，弹出退出确认");
                    int choice = CLBC_Util.confirmSaveOnExit();   // 0=保存并退出 1=不保存退出 2=取消
                    if (choice == 2) {
                        CLBC_GL_API.cancelClose();          // 取消退出：撤销 GLFW 关闭请求
                        CLBC_Util.log("退出已取消，返回编辑");
                    } else if (choice == 0 && !renderer.saveAllForExit()) {
                        CLBC_GL_API.cancelClose();          // 选保存但没有工程路径：保守起见不退出
                        CLBC_Util.log("无工程路径可保存，退出已取消");
                    }
                }
                if (CLBC_GL_API.shouldClose()) break;       // 仍要求关闭 → 退出主循环
            }
            long ws = CLBC_Theme.current().WORKSPACE;      // 工作区底色随主题（浅 #F9F9F9 / 深 #171717）
            CLBC_GL_API.beginFrame(((ws >> 16) & 0xFF) / 255f, ((ws >> 8) & 0xFF) / 255f, (ws & 0xFF) / 255f);
            renderer.frame();                              // 绘制当前屏幕（右键菜单由各屏幕自行在最上层叠加）
            CLBC_GL_API.endFrame();
        }

        CLBC_Audio.stop();                                 // 退出前释放背景音乐线路
        CLBC_GL_API.destroy();
        System.exit(0);
    }
}
