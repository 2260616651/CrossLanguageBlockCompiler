package clbc.decompile;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.UndeclaredThrowableException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import clbc.core.CLBC_AppContext;
import clbc.util.CLBC_Util;

/**
 * 内置反编译服务：CFR / Vineflower / Procyon（依赖由 Maven 管理）。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>零编译期 import</b>：三个引擎全部反射调用，主程序不 import 任何反编译器类；
 *       但 jar 以标准 Maven 依赖声明在 pom.xml，由 Maven 自动下载，不再内置发布包。</li>
 *   <li><b>共用应用 ClassLoader</b>：三引擎包名互不重叠
 *       （CFR=org.benf.* / Vineflower=org.jetbrains.java.decompiler.* / Procyon=com.strobel.*），
 *       无需隔离，直接用当前线程的上下文 ClassLoader 反射加载。</li>
 *   <li><b>依赖可用性探测</b>：{@link #isAvailable} 用 Class.forName 探测 probeClass，
 *       依赖未就位时该引擎显示"缺失"，不阻断其他引擎。</li>
 *   <li><b>输出只增不覆盖</b>：每次运行创建带时间戳的独立输出子目录，不删除任何已有文件。</li>
 *   <li><b>日志只走 CLBC_Util.log</b>。</li>
 * </ul>
 *
 * <p>Procyon 在 Maven Central 上没有 CLI 模块，走 {@code Decompiler.decompile(...)} API：
 * 用动态代理实现 {@code ITypeLoader} 把 .class / .jar 的字节喂给 Procyon，
 * 输出经 {@code PlainTextOutput} 落到 .java 文件。
 */
public final class CLBC_Decompiler {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    // ===== E/C 组：fat jar / 超大输入限额（8GB 机器不许 OOM）=====
    private static final long MAX_INPUT_FILE_BYTES   = 256L * 1024 * 1024;  // 输入 .jar/.class 文件本体上限
    private static final long MAX_SINGLE_CLASS_BYTES = 8L * 1024 * 1024;    // 单个 .class 解压后上限
    private static final long MAX_TOTAL_CLASS_BYTES  = 96L * 1024 * 1024;   // 一次反编译累计解压字节上限
    private static final int  MAX_CLASS_COUNT        = 20000;                // 类数量上限
    private static final int  JDK_TYPE_CACHE_MAX     = 256;                  // 运行时类型字节缓存条数上限（LRU 淘汰）

    /** 运行时 JDK 类型字节缓存（value 可为 null，表示查过但不存在）；LRU 限额，超限淘汰最旧，杜绝只存不删 */
    private static final Map<String, byte[]> JDK_TYPE_CACHE = new LinkedHashMap<String, byte[]>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
            return size() > JDK_TYPE_CACHE_MAX;
        }
    };
    /** 判断反编译结果是否真含类型声明，避免把只有 package/import 的空壳写成 .java */
    private static final java.util.regex.Pattern TYPE_DECL = java.util.regex.Pattern.compile(
            "(?m)\\b(class|interface|enum|record|@interface)\\s+[A-Za-z_$][\\w$]*");

    private CLBC_Decompiler() {}

    /** 一次反编译的结果；javaFiles 为本次生成的全部 .java（绝对路径） */
    public static final class Result {
        public final boolean ok;
        public final String message;
        public final CLBC_DecompileEngine engine;
        public final Path outputDir;
        public final List<Path> javaFiles;
        /** 本次没能产出源码的类 / 引擎报告的错误项；空列表才代表完整成功 */
        public final List<String> failures;

        Result(boolean ok, String message, CLBC_DecompileEngine engine, Path outputDir, List<Path> javaFiles) {
            this(ok, message, engine, outputDir, javaFiles, new ArrayList<>());
        }

        Result(boolean ok, String message, CLBC_DecompileEngine engine, Path outputDir,
               List<Path> javaFiles, List<String> failures) {
            this.ok = ok;
            this.message = message;
            this.engine = engine;
            this.outputDir = outputDir;
            this.javaFiles = javaFiles;
            this.failures = failures == null ? new ArrayList<>() : failures;
        }

        @Override public String toString() {
            return (ok ? "OK  " : "FAIL") + " " + (engine == null ? "-" : engine.displayName)
                    + "  输出 " + javaFiles.size() + " 个 .java"
                    + (failures.isEmpty() ? "" : "  未完成 " + failures.size() + " 项")
                    + "  " + message;
        }
    }

    // ==================================================================
    // 引擎可用性 / jar 定位
    // ==================================================================

    /** 当前线程上下文 ClassLoader：Maven 依赖之所以可见，全靠它 */
    private static ClassLoader engineLoader() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        return cl != null ? cl : CLBC_Decompiler.class.getClassLoader();
    }

    /** 某个引擎当前是否可用（Maven 依赖的探测类能否在 classpath 加载） */
    public static boolean isAvailable(CLBC_DecompileEngine e) {
        if (e == null) return false;
        try {
            Class.forName(e.probeClass, false, engineLoader());
            return true;
        } catch (Throwable ex) {
            return false;
        }
    }

    /** 三引擎可用状态，供设置/面板显示 */
    public static String availabilityText() {
        StringBuilder sb = new StringBuilder();
        for (CLBC_DecompileEngine e : CLBC_DecompileEngine.values()) {
            if (sb.length() > 0) sb.append(" | ");
            sb.append(e.displayName).append(isAvailable(e) ? " 可用" : " 缺失");
        }
        return sb.toString();
    }

    // ==================================================================
    // 对外主入口
    // ==================================================================

    /** 用指定引擎反编译 .class 或 .jar；输出落在 outputRoot 下的独立时间戳子目录 */
    public static Result decompile(CLBC_DecompileEngine e, Path input, Path outputRoot) {
        List<Path> empty = new ArrayList<>();
        if (e == null) return new Result(false, "未选择引擎", null, null, empty);
        if (input == null || !Files.isRegularFile(input)) {
            return new Result(false, "输入文件不存在: " + input, e, null, empty);
        }
        String lower = input.getFileName().toString().toLowerCase();
        if (!lower.endsWith(".class") && !lower.endsWith(".jar")) {
            return new Result(false, "只支持 .class / .jar: " + input, e, null, empty);
        }
        // C 组：超大 jar/class 读入前做 size 检查，超限记日志并安全拒绝（覆盖全部引擎，含 CLI 引擎）
        try {
            long size = Files.size(input);
            if (size > MAX_INPUT_FILE_BYTES) {
                String msg = "输入文件过大，已安全拒绝（" + size + " 字节，上限 " + MAX_INPUT_FILE_BYTES + "）";
                CLBC_Util.log("!!!![反编译] " + e.displayName + " " + msg + " <- " + input);
                return new Result(false, msg, e, null, empty);
            }
        } catch (IOException ex) {
            return new Result(false, "无法读取输入文件大小: " + ex.getMessage(), e, null, empty);
        }

        // Maven 依赖探测：jar 由 Maven 自动下载，不再内置；缺失时给出明确提示而非空指针
        if (!isAvailable(e)) {
            String msg = "引擎依赖未就位（" + e.probeClass + " 未找到）。请用 Maven 构建/运行以自动下载依赖";
            CLBC_Util.log("!!!![反编译] " + e.displayName + " " + msg);
            return new Result(false, msg, e, null, empty);
        }

        Path root = outputRoot == null ? defaultOutputRoot() : outputRoot;
        Path out = root.resolve(e.id() + "-" + LocalDateTime.now().format(STAMP));
        List<String> failures = new ArrayList<>();
        try {
            Files.createDirectories(out);
            ClassLoader cl = engineLoader();

            CLBC_Util.log("[反编译] 开始 " + e.label() + " <- " + input + "  输出 " + out);
            if (e.mainClass != null) {
                runMain(cl, e, input, out);
            } else {
                runProcyon(cl, input, out, failures);
            }

            // 部分 Fernflower 版本处理 .jar 输入时会把结果打成一个 .jar；统一展开后再收集
            expandNestedJava(out);
            List<Path> javaFiles = collectJava(out);
            if (javaFiles.isEmpty()) {
                String msg = "引擎执行完毕但没有生成 .java（查看日志里的引擎堆栈）";
                CLBC_Util.log("!!!![反编译] " + e.displayName + " " + msg);
                return new Result(false, msg, e, out, empty, failures);
            }
            String msg = "生成 " + javaFiles.size() + " 个 .java -> " + out;
            if (!failures.isEmpty()) msg += "；另有 " + failures.size() + " 个类未能反编译（详见日志）";
            CLBC_Util.log((failures.isEmpty() ? "[反编译] " : "!!!![反编译] ") + e.displayName + " " + msg);
            // 部分失败照样返回已产出的源码，但通过 failures 明确标注，不再静默当成完整成功
            return new Result(true, msg, e, out, javaFiles, failures);
        } catch (Throwable t) {
            Throwable cause = unwrap(t);   // 反射调用会把引擎真实异常包一层，不解包日志只剩 null
            String msg = cause.getClass().getName() + ": " + cause.getMessage();
            CLBC_Util.log("!!!![反编译] " + e.displayName + " 失败 " + msg);
            StringWriter sw = new StringWriter();
            cause.printStackTrace(new PrintWriter(sw));
            CLBC_Util.logFine(sw.toString());
            return new Result(false, msg, e, out, empty, failures);
        }
    }

    /** 四引擎依次跑同一输入，便于对比结果；单线程串行，符合低占用要求 */
    public static List<Result> decompileAll(Path input, Path outputRoot) {
        List<Result> all = new ArrayList<>();
        for (CLBC_DecompileEngine e : CLBC_DecompileEngine.values()) all.add(decompile(e, input, outputRoot));
        return all;
    }

    public static Path defaultOutputRoot() {
        Path app = CLBC_AppContext.appDir;
        Path base = app == null ? Path.of(System.getProperty("java.io.tmpdir"), "clbc") : app;
        return base.resolve("decompiled");
    }

    /**
     * 部分 Fernflower 版本（如 2.5.0.Final）处理 .jar 输入时，输出的是另一个装 .java 的 jar
     * 而不是散装源码。这里把它就地展开（保留原 jar，不删文件），兼容所有版本的结果格式。
     */
    private static void expandNestedJava(Path dir) throws IOException {
        List<Path> jars;
        try (var s = Files.list(dir)) {
            jars = s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .toList();
        }
        for (Path jar : jars) {
            try (JarFile jf = new JarFile(jar.toFile())) {
                Enumeration<JarEntry> it = jf.entries();
                while (it.hasMoreElements()) {
                    JarEntry je = it.nextElement();
                    String n = je.getName();
                    if (je.isDirectory() || !n.endsWith(".java")) continue;
                    Path target = dir.resolve(n).normalize();
                    if (!target.startsWith(dir.normalize())) continue;   // 防 zip slip
                    Files.createDirectories(target.getParent());
                    try (InputStream in = jf.getInputStream(je)) {
                        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            } catch (IOException ex) {
                CLBC_Util.log("!!!![反编译] 展开输出 jar 失败 " + jar + ": " + ex.getMessage());
            }
        }
    }

    // ==================================================================
    // CLI 引擎：CFR / Vineflower / Fernflower
    // ==================================================================

    private static void runMain(ClassLoader cl, CLBC_DecompileEngine e,
                               Path input, Path out) throws Exception {
        String[] args;
        if (e == CLBC_DecompileEngine.CFR) {
            args = new String[]{input.toString(), "--outputdir", out.toString()};
        } else {
            // Vineflower / Fernflower 共用老式 Fernflower CLI：<options> <source> <destinationDir>
            args = new String[]{input.toString(), out.toString()};
        }
        Class<?> main = Class.forName(e.mainClass, true, cl);
        Method m = main.getMethod("main", String[].class);
        m.invoke(null, (Object) args);
    }

    // ==================================================================
    // Procyon：无 CLI，反射调用 Decompiler.decompile API
    // ==================================================================

    private static void runProcyon(ClassLoader cl, Path input, Path out,
                                   List<String> failures) throws Exception {
        Map<String, byte[]> bytes = readClassBytes(input);
        if (bytes.isEmpty()) throw new IOException("输入里没有 .class 文件");

        Class<?> settingsCls = Class.forName("com.strobel.decompiler.DecompilerSettings", true, cl);
        Class<?> loaderIface = Class.forName("com.strobel.assembler.metadata.ITypeLoader", true, cl);
        Class<?> bufferCls = Class.forName("com.strobel.assembler.metadata.Buffer", true, cl);
        Class<?> outputIface = Class.forName("com.strobel.decompiler.ITextOutput", true, cl);
        Class<?> plainOutCls = Class.forName("com.strobel.decompiler.PlainTextOutput", true, cl);
        Class<?> decompilerCls = Class.forName("com.strobel.decompiler.Decompiler", true, cl);

        Method reset = bufferCls.getMethod("reset", int.class);
        Method array = bufferCls.getMethod("array");
        Method position = bufferCls.getMethod("position", int.class);

        // ITypeLoader 动态代理：输入里有的类型用输入字节，输入里没有的（JDK 运行时类型）
        // 由 runtimeTypeBytes 通用补齐。Procyon 解析 invokedynamic 的 bootstrap
        // （StringConcatFactory / ObjectMethods 等）时必须能解析其 owner 类型，
        // 否则 InvokeDynamicRewriter 拿到 null reference，整类反编译失败。
        InvocationHandler handler = (proxy, method, a) -> {
            String n = method.getName();
            if (n.equals("toString")) return "CLBC-ProcyonTypeLoader";
            if (n.equals("hashCode")) return System.identityHashCode(proxy);
            if (n.equals("equals")) return proxy == a[0];
            if (!n.equals("tryLoadType")) return null;
            String key = normalizeName((String) a[0]);
            byte[] data = bytes.get(key);
            if (data == null) data = runtimeTypeBytes(key);
            if (data == null) return false;
            Object buf = a[1];
            reset.invoke(buf, data.length);
            System.arraycopy(data, 0, array.invoke(buf), 0, data.length);
            position.invoke(buf, 0);
            return true;
        };
        Object typeLoader = Proxy.newProxyInstance(cl, new Class<?>[]{loaderIface}, handler);

        Object settings = settingsCls.getMethod("javaDefaults").invoke(null);
        settingsCls.getMethod("setTypeLoader", loaderIface).invoke(settings, typeLoader);

        Method decompile = decompilerCls.getMethod("decompile", String.class, outputIface, settingsCls);
        var plainCtor = plainOutCls.getConstructor(Writer.class);

        int written = 0;
        for (Map.Entry<String, byte[]> en : bytes.entrySet()) {
            String internal = en.getKey();
            // Procyon 各版本对 typeName 的写法不一致：先试内部名(斜杠)，空结果再试全限定名(点)。
            // 每次用全新的 output，避免一次失败留下的半截文本污染下一次尝试。
            String code = "";
            Throwable last = null;
            for (String candidate : new String[]{internal, internal.replace('/', '.')}) {
                StringWriter sw = new StringWriter();
                try {
                    decompile.invoke(null, candidate, plainCtor.newInstance(sw), settings);
                    if (hasTypeDeclaration(sw.toString())) { code = sw.toString(); break; }
                } catch (Throwable ex) {
                    last = unwrap(ex);
                }
            }
            if (code.isEmpty()) {
                String why = last == null
                        ? "引擎只输出 package/import，无类型声明（合成类常见）"
                        : last.getClass().getName() + ": " + last.getMessage();
                CLBC_Util.logFine("[反编译] Procyon 跳过 " + internal + ": " + why);
                failures.add(internal + " — " + why);
                continue;
            }
            Path f = out.resolve(internal + ".java");
            Files.createDirectories(f.getParent());
            Files.writeString(f, code, StandardCharsets.UTF_8);
            written++;
        }
        bytes.clear();   // 及时释放：Procyon 解析完毕立刻丢弃全部常驻类字节（typeLoader 仅本方法内使用）
        if (written == 0) throw new IOException("Procyon 未产出任何源码");
    }

    /** 把 .class / .jar 里的类字节读成 internalName -> bytes。
     *  E 组：Procyon 的 ITypeLoader 代理需要随机访问全部类字节，无法纯流式，
     *  故改为「逐条限额读取 + 累计/数量上限 + 用完及时清空」：fat jar 不会把全部解压字节常驻堆导致 OOM。 */
    private static Map<String, byte[]> readClassBytes(Path input) throws IOException {
        Map<String, byte[]> map = new LinkedHashMap<>();
        long total = 0;
        if (input.getFileName().toString().toLowerCase().endsWith(".jar")) {
            try (JarFile jar = new JarFile(input.toFile())) {
                Enumeration<JarEntry> it = jar.entries();
                while (it.hasMoreElements()) {
                    JarEntry je = it.nextElement();
                    String n = je.getName();
                    if (je.isDirectory() || !n.endsWith(".class") || n.equals("module-info.class")) continue;
                    if (map.size() >= MAX_CLASS_COUNT) {
                        CLBC_Util.log("!!!![反编译] 类数量超过上限 " + MAX_CLASS_COUNT + "，其余类已跳过");
                        break;
                    }
                    if (total >= MAX_TOTAL_CLASS_BYTES) {
                        CLBC_Util.log("!!!![反编译] 累计解压字节超过上限 " + MAX_TOTAL_CLASS_BYTES + "，其余类已跳过");
                        break;
                    }
                    if (je.getSize() > MAX_SINGLE_CLASS_BYTES) {
                        CLBC_Util.log("!!!![反编译] 单个类超过上限，已跳过: " + n + " (" + je.getSize() + " 字节)");
                        continue;
                    }
                    byte[] b;
                    try (InputStream in = jar.getInputStream(je)) {   // 逐条关闭，杜绝流句柄泄漏
                        b = readBounded(in, MAX_SINGLE_CLASS_BYTES);
                    }
                    if (b == null) {
                        CLBC_Util.log("!!!![反编译] 单个类实际解压超过上限，已跳过: " + n);
                        continue;
                    }
                    total += b.length;
                    String internal = internalNameOf(b);
                    if (internal == null) internal = n.substring(0, n.length() - 6);
                    map.put(internal, b);
                }
            }
        } else {
            long sz = Files.size(input);
            if (sz > MAX_SINGLE_CLASS_BYTES) {
                throw new IOException("单个 .class 超过上限 " + MAX_SINGLE_CLASS_BYTES + " 字节: " + sz);
            }
            byte[] b;
            try (InputStream in = Files.newInputStream(input)) {
                b = readBounded(in, MAX_SINGLE_CLASS_BYTES);
            }
            if (b == null) throw new IOException("单个 .class 实际读取超过上限: " + input);
            String internal = internalNameOf(b);
            if (internal == null) {
                String n = input.getFileName().toString();
                internal = n.substring(0, n.length() - 6);
            }
            map.put(internal, b);
        }
        return map;
    }

    /** 限额流式读取：最多读 cap 字节；超限返回 null（调用方记日志并跳过），避免 readAllBytes 一次性吃满内存 */
    private static byte[] readBounded(InputStream in, long cap) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > cap) return null;
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static String normalizeName(String n) {
        if (n == null) return "";
        String s = n.replace('.', '/');
        return s.endsWith(".class") ? s.substring(0, s.length() - 6) : s;
    }

    /** 反射 / 异步包装会把引擎真实异常藏起来；统一解包，避免日志只剩 "null" */
    private static Throwable unwrap(Throwable t) {
        Throwable x = t;
        for (int i = 0; i < 8 && x != null; i++) {
            Throwable c = x.getCause();
            boolean wrapper = x instanceof InvocationTargetException
                    || x instanceof UndeclaredThrowableException
                    || x instanceof ExecutionException;
            if (wrapper && c != null && c != x) { x = c; continue; }
            break;
        }
        return x == null ? t : x;
    }

    /**
     * 通用类型补齐：输入 .class / .jar 里没有的类型，从当前 JDK 运行时读字节。
     * 只走 platform loader 可见的类，不引入第三方依赖，也不碰主程序 classpath。
     */
    private static synchronized byte[] runtimeTypeBytes(String internal) {
        if (internal == null || internal.isEmpty()) return null;
        String key = internal.replace('.', '/');
        if (key.endsWith(".class")) key = key.substring(0, key.length() - 6);
        if (JDK_TYPE_CACHE.containsKey(key)) return JDK_TYPE_CACHE.get(key);
        byte[] b = null;
        try (InputStream in = ClassLoader.getPlatformClassLoader().getResourceAsStream(key + ".class")) {
            if (in != null) {
                b = readBounded(in, MAX_SINGLE_CLASS_BYTES);
                if (b == null) CLBC_Util.logFine("[反编译] JDK 类型字节超过上限，已忽略: " + key);
            }
        } catch (IOException e) {
            // 解析不到就返回 null 交给引擎降级，但至少留一条日志，不再完全静默
            CLBC_Util.logFine("[反编译] 读取 JDK 类型字节失败 " + key + ": " + e.getMessage());
        }
        JDK_TYPE_CACHE.put(key, b);   // LinkedHashMap LRU：超过 JDK_TYPE_CACHE_MAX 自动淘汰最旧，及时释放
        return b;
    }

    private static boolean hasTypeDeclaration(String code) {
        return code != null && TYPE_DECL.matcher(code).find();
    }

    /** 极简 class 常量池解析，只取 this_class 的内部名；不依赖任何第三方库 */
    private static String internalNameOf(byte[] b) {
        try {
            if (b.length < 12 || (b[0] & 0xff) != 0xCA || (b[1] & 0xff) != 0xFE
                    || (b[2] & 0xff) != 0xBA || (b[3] & 0xff) != 0xBE) return null;
            int cpCount = u2(b, 8);
            String[] utf = new String[cpCount];
            int[] ref = new int[cpCount];
            int p = 10;
            for (int i = 1; i < cpCount; i++) {
                int tag = b[p++] & 0xff;
                switch (tag) {
                    case 1 -> { int len = u2(b, p); p += 2; utf[i] = new String(b, p, len, StandardCharsets.UTF_8); p += len; }
                    case 7, 8, 16, 19, 20 -> { ref[i] = u2(b, p); p += 2; }
                    case 15 -> p += 3;
                    case 3, 4, 9, 10, 11, 12, 17, 18 -> p += 4;
                    case 5, 6 -> { p += 8; i++; }
                    default -> { return null; }
                }
            }
            int thisClass = u2(b, p + 2); // 跳过 access_flags(u2)
            int nameIdx = ref[thisClass];
            return (nameIdx > 0 && nameIdx < cpCount) ? utf[nameIdx] : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static int u2(byte[] b, int p) {
        return ((b[p] & 0xff) << 8) | (b[p + 1] & 0xff);
    }

    /** 递归收集输出目录里的 .java */
    public static List<Path> collectJava(Path dir) throws IOException {
        if (dir == null || !Files.isDirectory(dir)) return new ArrayList<>();
        try (var s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList();
        }
    }

    /** 读取某个反编译结果源码（供界面预览 / 导入积木） */
    public static String readSource(Path javaFile) {
        try {
            // C 组：超大源码读入前做 size 检查，超限记日志并安全拒绝
            long sz = Files.size(javaFile);
            if (sz > MAX_INPUT_FILE_BYTES) {
                CLBC_Util.log("!!!![反编译] 源码文件过大，已拒绝读取: " + javaFile + " (" + sz + " 字节)");
                return "";
            }
            return Files.readString(javaFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            CLBC_Util.log("!!!![反编译] 读取源码失败 " + javaFile + ": " + e.getMessage());
            return "";
        }
    }
}
