package clbc.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JDK 语言文件的历史数据源（程序内部写死，不依赖任何语言文件）。
 *
 * 覆盖「java1 ~ java27」共 27 个 Java 版本。这里的 1..27 是 CLBC 的版本序号，
 * 与真实 JDK 版本号的对应关系见 {@link #VERSIONS}：
 * 序号 1 = JDK 1.0，序号 9 = Java 8，序号 27 = Java 26（2026）。
 * 之所以不按真实版本号命名，是因为用户口径是「java1 到 java27 共 27 个版本」。
 *
 * 本表提供四类数据，供 {@link CLBC_JdkLangFiles} 生成每个库的语言文件：
 *   · 27 个版本的引入 / 移除清单（保证 1..27 每个版本在语言文件夹里都有内容）
 *   · 类 / 方法的 since（起始版本）与 until（移除版本），未收录者默认 1..27
 *   · 跨版本改名 / 弃用替代表：旧名与新名各写一条方法，映射到同一个中文积木名
 *   · 方法名 / 类名 / 包名的中文词典，保证每条方法都有对应中文
 */
public final class CLBC_JdkHistory {

    public static final int MIN_VERSION = 1;
    public static final int MAX_VERSION = 27;
    /** 当前未移除的版本标记 */
    public static final int PRESENT = MAX_VERSION;

    private CLBC_JdkHistory() {}

    /** 单个 Java 版本条目 */
    public static final class Ver {
        public final int v;                 // CLBC 版本序号 1..27
        public final String jdk;            // 真实 JDK 版本号
        public final int year;
        public final String note;
        public final String[] added;        // 该版本引入的代表类 / 能力
        public final String[] removed;      // 该版本移除 / 弃用的代表类 / 能力
        Ver(int v, String jdk, int year, String note, String added, String removed) {
            this.v = v; this.jdk = jdk; this.year = year; this.note = note;
            this.added = split(added); this.removed = split(removed);
        }
        private static String[] split(String s) {
            return s == null || s.isEmpty() ? new String[0] : s.split("\\|");
        }
    }

    /** 27 个版本，顺序即版本序号 */
    public static final Ver[] VERSIONS = {
        new Ver(1,  "1.0",   1996, "Java 1.0：语言基础、AWT、基础 I/O",
                "java.lang.Object|java.lang.String|java.lang.Math|java.lang.System|java.lang.Thread|java.util.Vector|java.util.Hashtable|java.util.Date|java.io.File|java.net.URL", ""),
        new Ver(2,  "1.1",   1997, "JDK 1.1：内部类、反射、JDBC、RMI、序列化",
                "java.lang.reflect|java.lang.Character|java.util.Calendar|java.util.Random|java.util.StringTokenizer|java.util.Properties", ""),
        new Ver(3,  "1.2",   1998, "J2SE 1.2：集合框架、Swing、strictfp",
                "java.util.ArrayList|java.util.HashMap|java.util.List|java.util.Map|java.util.Set|java.util.Iterator|java.util.Collections|java.util.Arrays|java.lang.StrictMath|java.lang.ThreadLocal", ""),
        new Ver(4,  "1.3",   2000, "J2SE 1.3：HotSpot、JNDI、RMI-IIOP",
                "java.util.Timer|java.util.jar.JarFile|java.lang.ClassLoader", ""),
        new Ver(5,  "1.4",   2002, "J2SE 1.4：正则、NIO、断言、日志、异常链",
                "java.util.regex.Pattern|java.util.regex.Matcher|java.nio.ByteBuffer|java.nio.channels|java.nio.charset.Charset|java.util.logging.Logger|java.lang.StackTraceElement", ""),
        new Ver(6,  "5",     2004, "Java 5：泛型、注解、枚举、自动装箱、for-each、并发包、StringBuilder",
                "java.lang.StringBuilder|java.lang.Enum|java.util.concurrent|java.util.concurrent.atomic|java.util.concurrent.locks|java.util.EnumSet|java.util.EnumMap|java.lang.annotation", "java.util.Vector（被 ArrayList 替代）|java.util.Hashtable（被 HashMap 替代）"),
        new Ver(7,  "6",     2006, "Java 6：脚本引擎、编译器 API、Desktop、JDBC 4",
                "java.util.ServiceLoader|java.util.Deque|java.util.ArrayDeque|java.util.NavigableMap|javax.script", ""),
        new Ver(8,  "7",     2011, "Java 7：try-with-resources、菱形语法、NIO.2、fork/join、Objects",
                "java.nio.file.Path|java.nio.file.Files|java.nio.file.Paths|java.util.Objects|java.lang.AutoCloseable|java.util.concurrent.ForkJoinPool|java.util.concurrent.RecursiveTask", ""),
        new Ver(9,  "8",     2014, "Java 8：Lambda、Stream、新日期时间、Optional、函数式接口、Base64",
                "java.util.function|java.util.stream|java.time|java.time.temporal|java.time.format|java.util.Optional|java.util.Base64|java.util.Spliterator|java.lang.FunctionalInterface", "java.lang.Compiler（移除）|java.util.jar.Pack200（弃用）"),
        new Ver(10, "9",     2017, "Java 9：模块系统、jshell、集合工厂方法、反应流",
                "java.util.List#of|java.util.Map#of|java.util.Set#of|java.lang.Module|java.lang.ProcessHandle|java.util.concurrent.Flow", "java.lang.Compiler|java.util.Observable（弃用）|java.util.Observer（弃用）|java.lang.Object#finalize（弃用）"),
        new Ver(11, "10",    2018, "Java 10：局部变量类型推断 var、G1 并行 Full GC",
                "java.util.List#copyOf|java.util.Map#copyOf|java.util.Set#copyOf|java.lang.invoke.VarHandle", "java.lang.Thread#stop（移除）|java.lang.Thread#destroy（移除）"),
        new Ver(12, "11",    2018, "Java 11（LTS）：HTTP Client、String 增强、单文件源码启动",
                "java.net.http.HttpClient|java.lang.String#isBlank|java.lang.String#strip|java.lang.String#lines|java.lang.String#repeat|java.util.Optional#isEmpty", "java.util.jar.Pack200（移除）|java.rmi.activation（移除）|java.xml.ws（移除）"),
        new Ver(13, "12",    2019, "Java 12：Switch 表达式预览、Shenandoah GC",
                "java.lang.String#indent|java.lang.String#transform|java.nio.file.Files#mismatch", ""),
        new Ver(14, "13",    2019, "Java 13：文本块预览、Socket API 重写",
                "java.lang.String#stripIndent|java.lang.String#formatted（预览）|java.nio.ByteBuffer#flip", ""),
        new Ver(15, "14",    2020, "Java 14：Switch 表达式正式、record 预览、instanceof 模式匹配预览",
                "java.lang.Record（预览）|java.lang.NullPointerException 增强", "java.rmi.activation（移除）"),
        new Ver(16, "15",    2020, "Java 15：文本块正式、sealed 预览、ZGC 正式",
                "java.lang.String#formatted|java.lang.Class#isHidden", "java.lang.Compiler（已移除）"),
        new Ver(17, "16",    2021, "Java 16（当前 LTS 之前的快版本）：record 正式、instanceof 模式匹配正式",
                "java.lang.Record|java.util.stream.Stream#toList|java.net.http.HttpClient 增强", ""),
        new Ver(18, "17",    2021, "Java 17（LTS）：sealed 正式、模式匹配 switch 预览、强封装 JDK 内部",
                "java.lang.constant|java.util.random.RandomGenerator|java.lang.MatchException（后续）", "java.lang.SecurityManager（弃用）|java.lang.Object#finalize（弃用）"),
        new Ver(19, "18",    2022, "Java 18：UTF-8 默认字符集、简单 Web 服务器、代码片段 API",
                "java.nio.charset.StandardCharsets 默认 UTF-8|java.net.spi", ""),
        new Ver(20, "19",    2022, "Java 19：虚拟线程预览、结构化并发预览",
                "java.lang.Thread#ofVirtual（预览）|java.util.concurrent.StructuredTaskScope（预览）", ""),
        new Ver(21, "20",    2023, "Java 20：虚拟线程二次预览、记录模式预览",
                "java.lang.Thread#ofVirtual（预览）|java.lang.Record 模式（预览）", ""),
        new Ver(22, "21",    2023, "Java 21（LTS）：虚拟线程正式、序列集合、模式匹配 switch 正式、记录模式",
                "java.lang.Thread#ofVirtual|java.util.SequencedCollection|java.util.SequencedMap|java.util.SequencedSet|java.util.concurrent.StructuredTaskScope（预览）", ""),
        new Ver(23, "22",    2024, "Java 22：未命名变量、外部函数与内存 API 正式、G1 区域固定",
                "java.lang.foreign|java.lang.invoke.MethodHandle 增强", ""),
        new Ver(24, "23",    2024, "Java 23：模式匹配原始类型预览、Markdown 文档注释",
                "java.util.stream.Gatherer（预览）|java.lang.Double 模式匹配（预览）", ""),
        new Ver(25, "24",    2025, "Java 24：虚拟线程同步器优化、流收集器二次预览、类文件 API",
                "java.lang.classfile|java.util.stream.Gatherer（预览）", "java.lang.SecurityManager（永久禁用）"),
        new Ver(26, "25",    2025, "Java 25（LTS）：稳定 API、紧凑源文件、模块导入声明",
                "java.lang.Module 导入|java.util.concurrent.ScopedValue", ""),
        new Ver(27, "26",    2026, "Java 26：后续演进版本（保留槽位，保证 1..27 全覆盖）",
                "java.lang.* 持续增强|java.util.* 持续增强", "")
    };

    /** 跨版本改名 / 弃用替代表：旧名与新名都要写进语言文件，且共用同一个中文积木名 */
    public static final class Rename {
        public final String pkg, fqn, oldName, newName, chinese;
        public final int oldSince, oldUntil, newSince;
        Rename(String fqn, String oldName, String newName, String chinese,
               int oldSince, int oldUntil, int newSince) {
            int i = fqn.lastIndexOf('.');
            this.pkg = i >= 0 ? fqn.substring(0, i) : "";
            this.fqn = fqn; this.oldName = oldName; this.newName = newName;
            this.chinese = chinese; this.oldSince = oldSince;
            this.oldUntil = oldUntil; this.newSince = newSince;
        }
    }

    /**
     * 旧名在 oldSince..oldUntil 可用，新名自 newSince 起可用。
     * 生成时两个名字各写一条独立方法条目，中文一致 → 老代码与新代码都能被同一个中文积木识别。
     */
    public static final Rename[] RENAMES = {
        new Rename("java.awt.Component", "show", "setVisible", "显示或隐藏组件", 1, 2, 2),
        new Rename("java.awt.Component", "hide", "setVisible", "显示或隐藏组件", 1, 2, 2),
        new Rename("java.lang.Character", "isJavaLetter", "isJavaIdentifierStart", "是标识符开头字符", 2, PRESENT, 2),
        new Rename("java.lang.Character", "isJavaDigitOrLetter", "isJavaIdentifierPart", "是标识符组成字符", 2, PRESENT, 2),
        new Rename("java.util.Properties", "save", "store", "保存属性到输出流", 1, 3, 3),
        new Rename("java.lang.String", "getBytes", "getBytes", "按字符集取字节", 1, PRESENT, 2),
        new Rename("java.net.URLEncoder", "encode", "encode", "URL 编码", 1, PRESENT, 5),
        new Rename("java.net.URLDecoder", "decode", "decode", "URL 解码", 1, PRESENT, 5),
        new Rename("java.util.Date", "toGMTString", "toInstant", "转为时间戳", 1, 9, 9),
        new Rename("java.util.Date", "getYear", "getYear", "取年份", 1, 9, 9),
        new Rename("java.lang.Integer", "Integer", "valueOf", "创建整数对象", 1, 10, 2),
        new Rename("java.lang.Boolean", "Boolean", "valueOf", "创建布尔对象", 1, 10, 2),
        new Rename("java.lang.Double", "Double", "valueOf", "创建小数对象", 1, 10, 2),
        new Rename("java.lang.Long", "Long", "valueOf", "创建长整数对象", 1, 10, 2),
        new Rename("java.lang.Thread", "stop", "interrupt", "中断线程", 1, 11, 2),
        new Rename("java.lang.Thread", "suspend", "wait", "暂停线程", 1, 11, 1),
        new Rename("java.lang.Thread", "resume", "notify", "恢复线程", 1, 11, 1),
        new Rename("java.lang.Runtime", "getLocalizedInputStream", "InputStreamReader", "读取本地化输入流", 1, 10, 2),
        new Rename("java.lang.Object", "finalize", "Cleaner", "对象清理", 1, 18, 10),
        new Rename("java.lang.StringBuffer", "StringBuffer", "StringBuilder", "可变字符串构造", 1, PRESENT, 6),
        new Rename("java.util.Vector", "Vector", "ArrayList", "数组列表构造", 1, PRESENT, 3),
        new Rename("java.util.Hashtable", "Hashtable", "HashMap", "哈希映射构造", 1, PRESENT, 3),
        new Rename("java.util.Enumeration", "hasMoreElements", "hasNext", "还有下一个元素", 1, PRESENT, 3),
        new Rename("java.util.Enumeration", "nextElement", "next", "取下一个元素", 1, PRESENT, 3),
        new Rename("java.lang.System", "getSecurityManager", "getSecurityManager", "取安全管理器", 1, 18, 1),
        new Rename("java.util.Observable", "notifyObservers", "PropertyChangeSupport", "通知观察者", 1, 10, 1)
    };

    // ===================== 版本元数据 =====================

    private static final Map<String, Integer> CLASS_SINCE = new LinkedHashMap<>();
    private static final Map<String, Integer> PACKAGE_SINCE = new LinkedHashMap<>();
    private static final Map<String, Integer> METHOD_SINCE = new LinkedHashMap<>();
    private static final Map<String, Integer> REMOVED = new LinkedHashMap<>();
    static {
        PACKAGE_SINCE.put("java.lang", 1);
        PACKAGE_SINCE.put("java.io", 1);
        PACKAGE_SINCE.put("java.net", 1);
        PACKAGE_SINCE.put("java.util", 1);
        PACKAGE_SINCE.put("java.text", 1);
        PACKAGE_SINCE.put("java.math", 1);
        PACKAGE_SINCE.put("java.util.regex", 5);
        PACKAGE_SINCE.put("java.nio", 5);
        PACKAGE_SINCE.put("java.nio.charset", 5);
        PACKAGE_SINCE.put("java.util.concurrent", 6);
        PACKAGE_SINCE.put("java.util.concurrent.atomic", 6);
        PACKAGE_SINCE.put("java.util.concurrent.locks", 6);
        PACKAGE_SINCE.put("java.nio.file", 8);
        PACKAGE_SINCE.put("java.time", 9);
        PACKAGE_SINCE.put("java.time.temporal", 9);
        PACKAGE_SINCE.put("java.time.format", 9);
        PACKAGE_SINCE.put("java.util.function", 9);
        PACKAGE_SINCE.put("java.util.stream", 9);
        PACKAGE_SINCE.put("java.net.http", 12);
        PACKAGE_SINCE.put("java.util.random", 17);

        CLASS_SINCE.put("java.util.ArrayList", 3);
        CLASS_SINCE.put("java.util.LinkedList", 1);
        CLASS_SINCE.put("java.util.HashMap", 3);
        CLASS_SINCE.put("java.util.TreeMap", 3);
        CLASS_SINCE.put("java.util.HashSet", 3);
        CLASS_SINCE.put("java.util.TreeSet", 3);
        CLASS_SINCE.put("java.util.List", 3);
        CLASS_SINCE.put("java.util.Map", 3);
        CLASS_SINCE.put("java.util.Set", 3);
        CLASS_SINCE.put("java.util.Iterator", 3);
        CLASS_SINCE.put("java.util.Collections", 3);
        CLASS_SINCE.put("java.util.Arrays", 3);
        CLASS_SINCE.put("java.util.Vector", 1);
        CLASS_SINCE.put("java.util.Hashtable", 1);
        CLASS_SINCE.put("java.lang.StringBuilder", 6);
        CLASS_SINCE.put("java.lang.StringBuffer", 1);
        CLASS_SINCE.put("java.lang.Enum", 6);
        CLASS_SINCE.put("java.lang.Record", 17);
        CLASS_SINCE.put("java.lang.AutoCloseable", 8);
        CLASS_SINCE.put("java.lang.ProcessHandle", 10);
        CLASS_SINCE.put("java.lang.Module", 10);
        CLASS_SINCE.put("java.util.Optional", 9);
        CLASS_SINCE.put("java.util.Base64", 9);
        CLASS_SINCE.put("java.util.Objects", 8);
        CLASS_SINCE.put("java.util.Spliterator", 9);
        CLASS_SINCE.put("java.util.SequencedCollection", 22);
        CLASS_SINCE.put("java.util.SequencedMap", 22);
        CLASS_SINCE.put("java.util.SequencedSet", 22);
        CLASS_SINCE.put("java.nio.file.Files", 8);
        CLASS_SINCE.put("java.nio.file.Path", 8);
        CLASS_SINCE.put("java.nio.file.Paths", 8);
        CLASS_SINCE.put("java.util.regex.Pattern", 5);
        CLASS_SINCE.put("java.util.regex.Matcher", 5);
        CLASS_SINCE.put("java.net.http.HttpClient", 12);
        CLASS_SINCE.put("java.lang.StackTraceElement", 5);
        CLASS_SINCE.put("java.lang.Character", 2);
        CLASS_SINCE.put("java.util.Random", 2);
        CLASS_SINCE.put("java.util.Calendar", 2);
        CLASS_SINCE.put("java.util.Properties", 1);
        CLASS_SINCE.put("java.util.StringTokenizer", 1);
        CLASS_SINCE.put("java.lang.StrictMath", 3);
        // 补齐类的起始版本（对照 JDK 17/21/25）
        CLASS_SINCE.put("java.lang.Number", 1);
        CLASS_SINCE.put("java.lang.Module", 10);   // Java 9 = 序号 10，修正后写覆盖
        CLASS_SINCE.put("java.lang.StackWalker", 9);
        CLASS_SINCE.put("java.lang.ScopedValue", 22);
        CLASS_SINCE.put("java.lang.ThreadGroup", 1);
        CLASS_SINCE.put("java.lang.MatchException", 22);
        CLASS_SINCE.put("java.lang.ReflectiveOperationException", 7);
        CLASS_SINCE.put("java.lang.ClassNotFoundException", 1);
        CLASS_SINCE.put("java.lang.InterruptedException", 1);
        CLASS_SINCE.put("java.lang.CloneNotSupportedException", 1);
        CLASS_SINCE.put("java.lang.IllegalAccessException", 1);
        CLASS_SINCE.put("java.lang.InstantiationException", 1);
        CLASS_SINCE.put("java.lang.NoSuchMethodException", 1);
        CLASS_SINCE.put("java.lang.NoSuchFieldException", 1);
        CLASS_SINCE.put("java.lang.ExceptionInInitializerError", 1);
        CLASS_SINCE.put("java.lang.Error", 1);
        CLASS_SINCE.put("java.lang.AssertionError", 1);
        CLASS_SINCE.put("java.lang.StringIndexOutOfBoundsException", 1);
        CLASS_SINCE.put("java.lang.NegativeArraySizeException", 1);
        CLASS_SINCE.put("java.lang.ArrayStoreException", 1);
        CLASS_SINCE.put("java.lang.IllegalMonitorStateException", 1);
        CLASS_SINCE.put("java.lang.IllegalThreadStateException", 1);
        CLASS_SINCE.put("java.lang.SecurityException", 1);
        CLASS_SINCE.put("java.lang.TypeNotPresentException", 5);
        CLASS_SINCE.put("java.lang.EnumConstantNotPresentException", 5);
        CLASS_SINCE.put("java.lang.IllegalCallerException", 16);
        CLASS_SINCE.put("java.lang.OutOfMemoryError", 1);
        CLASS_SINCE.put("java.lang.StackOverflowError", 1);
        CLASS_SINCE.put("java.lang.VirtualMachineError", 1);
        CLASS_SINCE.put("java.lang.LinkageError", 1);
        CLASS_SINCE.put("java.lang.NoClassDefFoundError", 1);
        CLASS_SINCE.put("java.lang.UnsupportedClassVersionError", 3);
        CLASS_SINCE.put("java.lang.AbstractMethodError", 1);
        CLASS_SINCE.put("java.lang.NoSuchMethodError", 1);
        CLASS_SINCE.put("java.lang.NoSuchFieldError", 1);
        CLASS_SINCE.put("java.lang.IncompatibleClassChangeError", 1);
        CLASS_SINCE.put("java.lang.VerifyError", 1);
        CLASS_SINCE.put("java.lang.InternalError", 1);
        CLASS_SINCE.put("java.lang.UnknownError", 1);
        CLASS_SINCE.put("java.lang.UnsatisfiedLinkError", 1);
        CLASS_SINCE.put("java.lang.BootstrapMethodError", 7);
        CLASS_SINCE.put("java.lang.ClassFormatError", 1);
        CLASS_SINCE.put("java.lang.ClassCircularityError", 1);
        CLASS_SINCE.put("java.lang.InstantiationError", 1);
        CLASS_SINCE.put("java.lang.IllegalAccessError", 1);
        CLASS_SINCE.put("java.util.Collection", 3);   // 集合框架 JDK 1.2 = 序号 3（原误写 1，已修正）
        // 集合框架实为 JDK 1.2（序号 3）：修正此处后写覆盖，避免把前面已定义的 3 冲成 1
        CLASS_SINCE.put("java.util.List", 3);
        CLASS_SINCE.put("java.util.Set", 3);
        CLASS_SINCE.put("java.util.Map", 3);
        CLASS_SINCE.put("java.util.Queue", 6);        // Queue 自 Java 5 = 序号 6 引入（原误写 1，已修正）
        CLASS_SINCE.put("java.util.Deque", 6);
        CLASS_SINCE.put("java.util.NavigableSet", 6);
        CLASS_SINCE.put("java.util.NavigableMap", 6);
        CLASS_SINCE.put("java.util.SortedSet", 2);
        CLASS_SINCE.put("java.util.SortedMap", 2);
        CLASS_SINCE.put("java.util.SequencedCollection", 22);
        CLASS_SINCE.put("java.util.SequencedMap", 22);
        CLASS_SINCE.put("java.util.SequencedSet", 22);
        CLASS_SINCE.put("java.util.random.RandomGenerator", 17);
        CLASS_SINCE.put("java.io.Console", 6);
        CLASS_SINCE.put("java.util.concurrent.Flow", 10);
        CLASS_SINCE.put("java.util.concurrent.RecursiveTask", 8);
        CLASS_SINCE.put("java.util.concurrent.RecursiveAction", 8);
        CLASS_SINCE.put("java.util.concurrent.locks.StampedLock", 8);
        CLASS_SINCE.put("java.util.concurrent.Executor", 5);
        CLASS_SINCE.put("java.util.concurrent.AbstractExecutorService", 5);
        CLASS_SINCE.put("java.util.concurrent.SubmissionPublisher", 9);
        CLASS_SINCE.put("java.util.concurrent.BlockingQueue", 5);

        METHOD_SINCE.put("java.lang.String#isBlank", 12);
        METHOD_SINCE.put("java.lang.String#strip", 12);
        METHOD_SINCE.put("java.lang.String#lines", 12);
        METHOD_SINCE.put("java.lang.String#repeat", 12);
        METHOD_SINCE.put("java.lang.String#indent", 13);
        METHOD_SINCE.put("java.lang.String#transform", 13);
        METHOD_SINCE.put("java.lang.String#formatted", 16);
        METHOD_SINCE.put("java.lang.String#isEmpty", 6);
        METHOD_SINCE.put("java.lang.String#join", 9);
        METHOD_SINCE.put("java.util.List#of", 10);
        METHOD_SINCE.put("java.util.Map#of", 10);
        METHOD_SINCE.put("java.util.Set#of", 10);
        METHOD_SINCE.put("java.util.List#copyOf", 11);
        METHOD_SINCE.put("java.util.Map#copyOf", 11);
        METHOD_SINCE.put("java.util.Set#copyOf", 11);
        METHOD_SINCE.put("java.util.stream.Stream#toList", 17);
        METHOD_SINCE.put("java.lang.Thread#ofVirtual", 22);
        METHOD_SINCE.put("java.util.Optional#isEmpty", 12);
        METHOD_SINCE.put("java.nio.ByteBuffer#flip", 14);
        // 补齐类的关键新方法起始版本
        METHOD_SINCE.put("java.util.SequencedCollection#addFirst", 22);
        METHOD_SINCE.put("java.util.SequencedCollection#addLast", 22);
        METHOD_SINCE.put("java.util.SequencedCollection#getFirst", 22);
        METHOD_SINCE.put("java.util.SequencedCollection#getLast", 22);
        METHOD_SINCE.put("java.util.SequencedCollection#removeFirst", 22);
        METHOD_SINCE.put("java.util.SequencedCollection#removeLast", 22);
        METHOD_SINCE.put("java.lang.ScopedValue#get", 22);
        METHOD_SINCE.put("java.lang.ScopedValue#callWhen", 22);
        METHOD_SINCE.put("java.lang.ScopedValue#runWhere", 22);
        METHOD_SINCE.put("java.lang.ScopedValue#where", 22);
        METHOD_SINCE.put("java.lang.Module#getName", 9);
        METHOD_SINCE.put("java.lang.Module#getPackages", 9);
        METHOD_SINCE.put("java.lang.Module#getClassLoader", 9);

        REMOVED.put("java.lang.Compiler", 10);
        REMOVED.put("java.rmi.activation", 15);
        REMOVED.put("java.util.jar.Pack200", 15);
        REMOVED.put("java.lang.Thread#stop", 11);
        REMOVED.put("java.lang.Thread#suspend", 11);
        REMOVED.put("java.lang.Thread#resume", 11);
        REMOVED.put("java.lang.Thread#destroy", 11);
        REMOVED.put("java.lang.SecurityManager", 25);
        REMOVED.put("java.lang.Object#finalize", 18);
        REMOVED.put("java.lang.Runtime#getLocalizedInputStream", 10);
    }

    /** 类的起始版本；未收录按包默认，包也未收录则 1 */
    public static int sinceOfClass(String fqn) {
        Integer v = CLASS_SINCE.get(fqn);
        if (v != null) return v;
        int i = fqn.lastIndexOf('.');
        String pkg = i >= 0 ? fqn.substring(0, i) : "";
        // 取最长匹配包前缀
        int best = 1, bestLen = -1;
        for (Map.Entry<String, Integer> e : PACKAGE_SINCE.entrySet()) {
            if ((fqn.startsWith(e.getKey() + ".") || pkg.equals(e.getKey())) && e.getKey().length() > bestLen) {
                bestLen = e.getKey().length(); best = e.getValue();
            }
        }
        return best;
    }

    /** 方法的起始版本；精确 fqn#method → 简单 #method → 类的起始版本 */
    public static int sinceOfMethod(String fqn, String method) {
        Integer v = METHOD_SINCE.get(fqn + "#" + method);
        if (v != null) return v;
        v = METHOD_SINCE.get("#" + method);
        if (v != null) return v;
        return sinceOfClass(fqn);
    }

    /** 类 / 方法的移除版本；未移除返回 PRESENT(27) */
    public static int untilOf(String fqn, String method) {
        Integer v = method == null ? null : REMOVED.get(fqn + "#" + method);
        if (v != null) return v;
        v = REMOVED.get(fqn);
        return v == null ? PRESENT : v;
    }

    public static int[] allVersions() {
        int[] out = new int[MAX_VERSION - MIN_VERSION + 1];
        for (int i = 0; i < out.length; i++) out[i] = MIN_VERSION + i;
        return out;
    }

    /** 取某个包下的改名条目 */
    public static List<Rename> renamesOf(String pkg) {
        List<Rename> out = new ArrayList<>();
        for (Rename r : RENAMES) if (r.pkg.equals(pkg)) out.add(r);
        return out;
    }

    /** 全部出现过的包（含改名表里但反射扫不到的包，保证语言文件夹覆盖所有已知库） */
    public static List<String> extraPackages() {
        List<String> out = new ArrayList<>();
        for (Rename r : RENAMES) if (!out.contains(r.pkg)) out.add(r.pkg);
        return out;
    }

    /** 已知 JDK 库（包）全集：即使当前 JDK 反射扫不到，也要为它生成一份语言文件，
     *  保证 java1~java27 里出现过的库「一个库一个文件」不缺项。 */
    public static List<String> knownPackages() {
        return new ArrayList<>(PACKAGE_CN.keySet());
    }

    /** 版本总表里记录过的历史类（含 fqn#method 形式，返回纯类名部分） */
    public static List<String> historicalClasses(String pkg) {
        List<String> out = new ArrayList<>();
        for (Ver v : VERSIONS) {
            for (String s : v.added) {
                int hash = s.indexOf('#');
                String cls = hash >= 0 ? s.substring(0, hash) : s;
                if (cls.startsWith(pkg + ".") && !out.contains(cls)) out.add(cls);
            }
        }
        return out;
    }

    // ===================== 中文词典 =====================

    private static final Map<String, String> METHOD_CN = new LinkedHashMap<>();
    static {
        String[][] pairs = {
            {"length","长度"},{"isEmpty","为空"},{"size","元素个数"},{"add","添加"},{"remove","移除"},
            {"get","取值"},{"set","设值"},{"put","放入"},{"contains","包含"},{"clear","清空"},
            {"substring","截取子串"},{"indexOf","查找位置"},{"lastIndexOf","反向查找"},{"charAt","取字符"},
            {"toUpperCase","转大写"},{"toLowerCase","转小写"},{"trim","去首尾空格"},{"strip","去首尾空白"},
            {"split","拆分"},{"replace","替换"},{"replaceAll","全部替换"},{"replaceFirst","替换首个"},
            {"equals","相等"},{"equalsIgnoreCase","忽略大小写相等"},{"compareTo","比较"},{"compare","比较"},
            {"concat","拼接"},{"append","追加"},{"insert","插入"},{"delete","删除片段"},
            {"deleteCharAt","删除指定字符"},{"reverse","反转"},{"toString","转为文本"},
            {"hashCode","哈希值"},{"valueOf","转为"},{"parseInt","解析整数"},
            {"parseLong","解析长整数"},{"parseDouble","解析小数"},{"parseFloat","解析单精度"},
            {"parseBoolean","解析布尔"},{"max","最大值"},{"min","最小值"},{"abs","绝对值"},
            {"sqrt","平方根"},{"cbrt","立方根"},{"pow","幂"},{"round","四舍五入"},
            {"floor","向下取整"},{"ceil","向上取整"},{"random","随机数"},{"sin","正弦"},
            {"cos","余弦"},{"tan","正切"},{"asin","反正弦"},{"acos","反余弦"},{"atan","反正切"},
            {"log","对数"},{"log10","常用对数"},{"exp","指数"},{"signum","取符号"},
            {"sort","排序"},{"binarySearch","二分查找"},{"fill","填充"},{"copyOf","复制"},
            {"copyOfRange","复制区间"},{"asList","转为列表"},{"of","创建"},{"copyOf","不可变副本"},
            {"shuffle","打乱"},{"rotate","轮转"},{"swap","交换"},{"frequency","出现次数"},
            {"max","最大值"},{"min","最小值"},{"reverseOrder","逆序比较"},{"emptyList","空列表"},
            {"read","读取"},{"readLine","读取一行"},{"write","写入"},{"close","关闭"},
            {"flush","刷新"},{"exists","是否存在"},{"createFile","创建文件"},
            {"mkdir","创建目录"},{"mkdirs","创建多级目录"},{"list","列出"},{"getName","取名称"},
            {"getPath","取路径"},{"getAbsolutePath","取绝对路径"},{"isFile","是文件"},
            {"isDirectory","是目录"},{"canRead","可读"},{"canWrite","可写"},{"renameTo","重命名"},
            {"open","打开"},{"connect","连接"},{"accept","接受"},{"send","发送"},
            {"receive","接收"},{"currentTimeMillis","当前毫秒"},{"nanoTime","纳秒计时"},
            {"getProperty","取系统属性"},{"getProperties","取全部系统属性"},{"getenv","取环境变量"},
            {"arraycopy","数组复制"},{"identityHashCode","对象哈希"},{"lineSeparator","换行符"},
            {"sleep","休眠"},{"start","启动"},{"join","等待结束"},{"interrupt","中断"},
            {"isAlive","是否存活"},{"setName","设名称"},{"getPriority","取优先级"},
            {"setPriority","设优先级"},{"yield","让出处理器"},{"ofVirtual","创建虚拟线程"},
            {"now","当前时间"},{"parse","解析"},{"format","格式化"},{"plus","加"},{"minus","减"},
            {"isBefore","早于"},{"isAfter","晚于"},{"toEpochMilli","转毫秒时间戳"},
            {"getYear","取年"},{"getMonth","取月"},{"getMonthValue","取月份数字"},
            {"getDayOfMonth","取日"},{"getHour","取小时"},{"getMinute","取分钟"},
            {"getSecond","取秒"},{"atZone","附加时区"},{"with","调整字段"},
            {"filter","筛选"},{"map","映射"},{"collect","收集"},{"forEach","逐个处理"},
            {"reduce","归约"},{"count","计数"},{"findFirst","取第一个"},{"findAny","取任意一个"},
            {"anyMatch","任一满足"},{"allMatch","全部满足"},{"noneMatch","全不满足"},
            {"distinct","去重"},{"limit","限制数量"},{"skip","跳过"},{"sorted","排序"},
            {"flatMap","扁平映射"},{"mapToInt","映射为整数"},{"boxed","装箱"},{"toArray","转为数组"},
            {"toList","转为列表"},{"peek","逐个查看"},{"iterate","迭代生成"},{"generate","生成"},
            {"apply","应用"},{"test","测试"},{"getAsInt","取整数值"},{"getAsDouble","取小数值"},
            {"andThen","接着执行"},{"compose","先执行"},{"negate","取反"},{"and","并且"},{"or","或者"},
            {"ofNullable","可空创建"},{"orElse","否则取"},{"orElseGet","否则计算"},
            {"orElseThrow","否则抛错"},{"ifPresent","有值则执行"},{"isPresent","有值"},
            {"getOrDefault","取值否则默认"},{"computeIfAbsent","无则计算"},{"computeIfPresent","有则计算"},
            {"merge","合并"},{"keySet","取所有键"},{"values","取所有值"},{"entrySet","取所有键值对"},
            {"containsKey","含键"},{"containsValue","含值"},{"putIfAbsent","无则放入"},
            {"matches","完全匹配"},{"find","查找"},{"lookingAt","从头匹配"},
            {"group","取分组"},{"pattern","取模式"},{"quote","转义"},{"reset","重置"},
            {"appendReplacement","追加替换"},{"appendTail","追加尾部"},
            {"encode","编码"},{"decode","解码"},{"compress","压缩"},{"decompress","解压"},
            {"intValue","取整数值"},{"longValue","取长整数值"},{"doubleValue","取小数值"},
            {"floatValue","取单精度值"},{"byteValue","取字节值"},{"shortValue","取短整数值"},
            {"booleanValue","取布尔值"},{"charValue","取字符值"},{"compareToIgnoreCase","忽略大小写比较"},
            {"startsWith","以…开头"},{"endsWith","以…结尾"},{"contains","包含"},{"join","连接"},
            {"intern","归入常量池"},{"repeat","重复"},{"lines","按行拆分"},{"indent","缩进"},
            {"isBlank","是空白"},{"formatted","格式化"},{"transform","变换"},{"chars","字符流"},
            {"codePoints","码点流"},{"clone","克隆"},{"wait","等待"},{"notify","通知"},
            {"notifyAll","通知全部"},{"getClass","取类型"},{"addAll","批量添加"},
            {"removeAll","批量移除"},{"retainAll","仅保留"},{"removeIf","按条件移除"},
            {"replaceAll","全部替换"},{"compute","计算"},{"forEach","逐个处理"},
            {"stream","转为流"},{"parallelStream","转为并行流"},{"iterator","取迭代器"},
            {"hasNext","还有下一个"},{"next","取下一个"},{"hasMoreElements","还有下一个元素"},
            {"nextElement","取下一个元素"},{"addFirst","头部添加"},{"addLast","尾部添加"},
            {"getFirst","取第一个"},{"getLast","取最后一个"},{"removeFirst","移除第一个"},
            {"removeLast","移除最后一个"},{"peekFirst","查看第一个"},{"poll","取出并移除"},
            {"offer","放入队列"},{"push","压入"},{"pop","弹出"},{"isEmpty","为空"},
            {"seed","随机种子"},{"nextInt","下一个整数"},{"nextDouble","下一个小数"},
            {"nextBoolean","下一个布尔"},{"nextLong","下一个长整数"},{"nextGaussian","高斯随机"},
            {"getBytes","取字节"},{"toCharArray","转为字符数组"},{"getChars","取字符到数组"},
            {"regionMatches","区域匹配"},{"contentEquals","内容相等"},{"stripIndent","去缩进"},
            {"translateEscapes","转义还原"},{"describeConstable","描述常量"},
            {"abs","绝对值"},{"add","加"},{"subtract","减"},{"multiply","乘"},
            {"divide","除"},{"remainder","取余"},{"negate","取负"},{"pow","幂"},
            {"scale","小数位数"},{"setScale","设小数位数"},{"stripTrailingZeros","去尾零"},
            {"toPlainString","转为普通文本"},{"signum","取符号"},{"gcd","最大公约数"},
            {"isProbablePrime","可能是质数"},{"mod","取模"},{"modInverse","模逆"},
            {"bitLength","位长度"},{"bitCount","置位数"},{"shiftLeft","左移"},{"shiftRight","右移"},
            {"and","按位与"},{"or","按位或"},{"xor","按位异或"},{"not","按位取反"},
            {"testBit","测试位"},{"setBit","置位"},{"flipBit","翻转位"},
            // ---- 补充：工具箱高频但原词典未收录的 JDK 库成员（自识别数据层，非渲染硬编码）----
            {"new","新建"},{"getStackTrace","取堆栈"},{"printStackTrace","打印堆栈"},
            {"fillInStackTrace","填充堆栈"},{"getCause","取成因"},{"initCause","初始化成因"},
            {"getMessage","取消息"},{"getSuppressed","取抑制异常"},
            {"getLocalizedMessage","取本地化消息"},{"setStackTrace","设堆栈"},
            {"addSuppressed","添加抑制异常"},{"spliterator","拆分迭代器"},
            {"mark","标记"},{"containsAll","包含全部"},{"reversed","逆序"},
            {"transferTo","转移到"},{"markSupported","支持标记"},{"range","范围"},
            {"from","从"},{"getLong","取长整数值"},{"isSupported","是否支持"},
            {"offset","偏移量"},{"getFirst","取首个"},{"getLast","取末个"},
            {"runFinalization","运行终结器"},{"parent","父级"},{"run","运行"},
            // ---- 补充：工具箱高频但原词典未收录的 JDK 库方法（自识别数据层）----
            {"absExact","精确绝对值"},
            {"accumulateAndGet","累加并取值"},
            {"activeCount","活动线程数"},
            {"adapt","适配"},
            {"addAndGet","加并取值"},
            {"addElement","添加元素"},
            {"addExact","精确加"},
            {"addRequestProperty","添加请求属性"},
            {"adjustInto","调整至"},
            {"after","晚于"},
            {"allOf","全部满足"},
            {"allocate","分配"},
            {"allocateDirect","直接分配"},
            {"andNot","与非"},
            {"appendCodePoint","追加码点"},
            {"applyLocalizedPattern","应用本地化模式"},
            {"applyPattern","应用模式"},
            {"array","数组"},
            {"arrayOffset","数组偏移"},
            {"asCharBuffer","转字符缓冲"},
            {"asDoubleBuffer","转小数缓冲"},
            {"asDoubleStream","转小数流"},
            {"asFloatBuffer","转单精度缓冲"},
            {"asIntBuffer","转整数缓冲"},
            {"asLongBuffer","转长整数缓冲"},
            {"asReadOnlyBuffer","转只读缓冲"},
            {"asShortBuffer","转短整数缓冲"},
            {"atDate","配日期"},
            {"atDay","配日"},
            {"atOffset","加偏移"},
            {"atan2","反正切2"},
            {"available","可用字节数"},
            {"average","平均值"},
            {"await","等待"},
            {"awaitTermination","等待终止"},
            {"before","早于"},
            {"between","介于"},
            {"bind","绑定"},
            {"builder","构建器"},
            {"byteValueExact","精确字节值"},
            {"cancel","取消"},
            {"capacity","容量"},
            {"ceilDiv","向上取除"},
            {"ceilDivExact","精确向上取除"},
            {"ceilMod","向上取模"},
            {"ceilingEntry","上界条目"},
            {"ceilingKey","上界键"},
            {"charset","字符集"},
            {"checkAccess","检查访问"},
            {"checkError","检查错误"},
            {"clamp","钳制"},
            {"codePointBefore","前一码点"},
            {"codePointCount","码点计数"},
            {"compact","压缩"},
            {"comparator","比较器"},
            {"compareAndExchange","比较并交换"},
            {"compareAndExchangeAcquire","比较并交换获取"},
            {"compareAndExchangeRelease","比较并交换释放"},
            {"compareAndSet","比较并设置"},
            {"compareAndSetForkJoinTaskTag","比较并设置任务标记"},
            {"complete","完成"},
            {"completeExceptionally","异常完成"},
            {"copySign","复制符号"},
            {"cosh","双曲余弦"},
            {"createTempFile","创建临时文件"},
            {"decrementAndGet","递减并取值"},
            {"decrementExact","精确递减"},
            {"deepEquals","深度相等"},
            {"descendingIterator","逆序迭代器"},
            {"descendingKeySet","逆序键集"},
            {"descendingMap","逆序映射"},
            {"descendingSet","逆序集合"},
            {"disconnect","断开"},
            {"divideAndRemainder","商和余数"},
            {"divideExact","精确除"},
            {"drainTo","抽取到"},
            {"duplicate","复制"},
            {"element","元素"},
            {"elementAt","取指定元素"},
            {"elements","全部元素"},
            {"empty","空"},
            {"execute","执行"},
            {"flip","翻转"},
            {"floorDiv","向下取除"},
            {"floorMod","向下取模"},
            {"getAndAccumulate","取旧值并累加"},
            {"getAndDecrement","取旧值并递减"},
            {"getAndIncrement","取旧值并递增"},
            {"getAndSet","取旧值并设新值"},
            {"getAndUpdate","取旧值并更新"},
            {"getAvailableLocales","取可用区域"},
            {"getDeclaringClass","取声明类"},
            {"getDisplayName","取显示名"},
            {"getNano","取纳秒"},
            {"hasArray","有数组"},
            {"hasRemaining","有剩余"},
            {"incrementAndGet","递增并取值"},
            {"incrementExact","精确递增"},
            {"invokeAll","调用全部"},
            {"invokeAny","调用任一"},
            {"isDaemon","是否守护"},
            {"isDirect","是否直接"},
            {"isEqual","是否相等"},
            {"isReadOnly","是否只读"},
            {"listIterator","列表迭代器"},
            {"lock","加锁"},
            {"minusDays","减天"},
            {"minusHours","减小时"},
            {"minusMillis","减毫秒"},
            {"minusMinutes","减分钟"},
            {"minusMonths","减月"},
            {"minusNanos","减纳秒"},
            {"minusSeconds","减秒"},
            {"minusWeeks","减周"},
            {"minusYears","减年"},
            {"multiplyExact","精确乘"},
            {"negateExact","精确取负"},
            {"newCondition","新建条件"},
            {"nullInputStream","空输入流"},
            {"nullOutputStream","空输出流"},
            {"nullReader","空读取器"},
            {"nullWriter","空写入器"},
            {"order","字节序"},
            {"plusDays","加天"},
            {"plusHours","加小时"},
            {"plusMillis","加毫秒"},
            {"plusMinutes","加分钟"},
            {"plusMonths","加月"},
            {"plusNanos","加纳秒"},
            {"plusSeconds","加秒"},
            {"plusWeeks","加周"},
            {"plusYears","加年"},
            {"position","位置"},
            {"putAll","放入全部"},
            {"readAllAsString","读取全部文本"},
            {"readAllBytes","读取全部字节"},
            {"readAllLines","读取全部行"},
            {"readNBytes","读取若干字节"},
            {"ready","就绪"},
            {"remaining","剩余"},
            {"rewind","倒回"},
            {"setDaemon","设守护"},
            {"shutdown","关闭"},
            {"shutdownNow","立即关闭"},
            {"sinh","双曲正弦"},
            {"skipNBytes","跳过若干字节"},
            {"slice","切片"},
            {"subList","取子列表"},
            {"submit","提交"},
            {"subtractExact","精确减"},
            {"sum","求和"},
            {"tanh","双曲正切"},
            {"toInstant","转时刻"},
            {"truncatedTo","截断至"},
            {"tryLock","尝试加锁"},
            {"unlock","解锁"},
            {"until","直到"},
            {"updateAndGet","更新并取值"},
            {"wrap","包装"},
            {"adaptInterruptible","可中断适配"},
            {"addTo","添加到"},
            {"alignedSlice","对齐切片"},
            {"alignmentOffset","对齐偏移"},
            {"applyAsDouble","作为小数应用"},
            {"applyAsInt","作为整数应用"},
            {"baseWireHandle","底层句柄"},
            {"canEncode","可编码"},
            {"ceiling","上界"},
            {"closeEntry","关闭条目"},
            {"codePointAt","指定位置码点"},
            {"compareUnsigned","无符号比较"},
            {"copy","复制"},
            {"copyInto","复制到"},
            {"current","当前"},
            {"divideUnsigned","无符号除"},
            {"doubles","小数流"},
            {"dropWhile","丢弃当"},
            {"ensureCapacity","确保容量"},
            {"enumerate","枚举"},
            {"equiDoubles","等价小数"},
            {"expand","扩展"},
            {"expm1","指数减一"},
            {"finish","完成"},
            {"finished","已完成"},
            {"first","首"},
            {"firstElement","首元素"},
            {"firstEntry","首条目"},
            {"firstKey","首键"},
            {"floorDivExact","精确向下取除"},
            {"floorEntry","下界条目"},
            {"floorKey","下界键"},
            {"fma","融合乘加"},
            {"fork","分叉"},
            {"formatToCharacterIterator","格式化为字符迭代器"},
            {"getAcquire","获取读取"},
            {"getOffset","取偏移"},
            {"getOpaque","取不透明值"},
            {"getPlain","取普通值"},
            {"getValue","取值"},
            {"higher","较高"},
            {"higherEntry","较高条目"},
            {"higherKey","较高键"},
            {"intValueExact","精确整数值"},
            {"ints","整数流"},
            {"isSet","是否设"},
            {"last","末"},
            {"lastElement","末元素"},
            {"lastEntry","末条目"},
            {"lastKey","末键"},
            {"log1p","对数加一"},
            {"longValueExact","精确长整数值"},
            {"longs","长整数流"},
            {"lower","较低"},
            {"lowerEntry","较低条目"},
            {"lowerKey","较低键"},
            {"name","名称"},
            {"nextDown","下近值"},
            {"nextUp","上近值"},
            {"normalize","规范化"},
            {"ordinal","序号"},
            {"peekLast","窥视末"},
            {"previous","上一"},
            {"setElementAt","设元素在"},
            {"setOpaque","设不透明值"},
            {"setPlain","设普通值"},
            {"setRelease","设释放"},
            {"shortValueExact","精确短整数值"},
            {"take","取"},
            {"takeWhile","取当"},
            {"ulp","最小精度单位"},
            {"getAddress","取地址"},
            {"getAdler","取阿德勒校验"},
            {"getAnnotation","取注解"},
            {"getAuthority","取权限"},
            {"getChannel","取通道"},
            {"getChar","取字符"},
            {"getChecksum","取校验和"},
            {"getChronology","取年表"},
            {"getComment","取注释"},
            {"getCurrency","取货币"},
            {"getCurrencyInstance","取货币实例"},
            {"getDate","取日期"},
            {"getDateInstance","取日期实例"},
            {"getDateTimeInstance","取日期时间实例"},
            {"getDefault","取默认"},
            {"getDefaultRequestProperty","取默认请求属性"},
            {"getDouble","取小数"},
            {"getEncoding","取编码"},
            {"getException","取异常"},
            {"getFloat","取单精度"},
            {"getHeaderField","取头字段"},
            {"getHeaderFieldDate","取头字段日期"},
            {"getHeaderFieldInt","取头字段整数"},
            {"getHeaderFieldKey","取头字段键"},
            {"getHeaderFieldLong","取头字段长整数"},
            {"getHost","取主机"},
            {"getHostAddress","取主机地址"},
            {"getHostName","取主机名称"},
            {"getId","取标识"},
            {"getIndex","取索引"},
            {"getInstance","取实例"},
            {"getInt","取整数"},
            {"getLocale","取区域设置"},
            {"getMaximum","取最大值"},
            {"getMinimum","取最小值"},
            {"getOption","取选项"},
            {"getParent","取父级"},
            {"getPermission","取权限"},
            {"getPort","取端口"},
            {"getRequestProperty","取请求属性"},
            {"getRoot","取根"},
            {"getShort","取短整数"},
            {"getTime","取时间"},
            {"getTimeInstance","取时间实例"},
            {"getTimeZone","取时间时区"},
            {"getZone","取时区"},
            {"nextBytes","下一字节"},
            {"nextFloat","下一单精度"},
            {"pollFirst","轮询首个"},
            {"pollLast","轮询末个"},
            {"putChar","放入字符"},
            {"putDouble","放入小数"},
            {"putFirst","放入首个"},
            {"putFloat","放入单精度"},
            {"putInt","放入整数"},
            {"putLast","放入末个"},
            {"putLong","放入长整数"},
            {"putShort","放入短整数"},
            {"readBoolean","读取布尔"},
            {"readByte","读取字节"},
            {"readChar","读取字符"},
            {"readDouble","读取小数"},
            {"readFloat","读取单精度"},
            {"readInt","读取整数"},
            {"readLock","读取锁"},
            {"readLong","读取长整数"},
            {"readShort","读取短整数"},
            {"removeElement","移除元素"},
            {"setComment","设注释"},
            {"setCurrency","设货币"},
            {"setDefaultRequestProperty","设默认请求属性"},
            {"setLength","设长度"},
            {"setLevel","设级别"},
            {"setMethod","设方法"},
            {"setOption","设选项"},
            {"setRequestProperty","设请求属性"},
            {"setSize","设大小"},
            {"setTime","设时间"},
            {"setTimeZone","设时间时区"},
            {"toPattern","转模式"},
            {"update","更新"},
            {"withZone","带时区"},
            {"writeBoolean","写入布尔"},
            {"writeByte","写入字节"},
            {"writeBytes","写入字节"},
            {"writeChar","写入字符"},
            {"writeDouble","写入小数"},
            {"writeFloat","写入单精度"},
            {"writeInt","写入整数"},
            {"writeLock","写入锁"},
            {"writeLong","写入长整数"},
            {"writeShort","写入短整数"},
        };
        for (String[] p : pairs) METHOD_CN.putIfAbsent(p[0], p[1]);
    }

    private static final Map<String, String> CLASS_CN = new LinkedHashMap<>();
    static {
        String[][] pairs = {
            {"java.lang.Object","对象"},{"java.lang.String","字符串"},
            {"java.lang.StringBuilder","可变字符串"},{"java.lang.StringBuffer","线程安全可变字符串"},
            {"java.lang.Math","数学"},{"java.lang.StrictMath","严格数学"},
            {"java.lang.System","系统"},{"java.lang.Integer","整数"},
            {"java.lang.Long","长整数"},{"java.lang.Double","小数"},
            {"java.lang.Float","单精度"},{"java.lang.Boolean","布尔"},
            {"java.lang.Byte","字节"},{"java.lang.Short","短整数"},
            {"java.lang.Character","字符"},{"java.lang.Number","数字"},
            {"java.lang.Thread","线程"},{"java.lang.Runnable","可运行"},
            {"java.lang.Throwable","可抛出"},{"java.lang.Exception","异常"},
            {"java.lang.RuntimeException","运行时异常"},{"java.lang.Enum","枚举"},
            {"java.lang.Record","记录"},{"java.lang.Class","类型"},
            {"java.lang.Comparable","可比较"},{"java.lang.Iterable","可迭代"},
            {"java.lang.CharSequence","字符序列"},{"java.lang.AutoCloseable","可自动关闭"},
            {"java.lang.ProcessHandle","进程句柄"},{"java.lang.StackTraceElement","栈帧"},
            {"java.util.ArrayList","数组列表"},{"java.util.LinkedList","链表"},
            {"java.util.Vector","向量"},{"java.util.HashMap","哈希映射"},
            {"java.util.TreeMap","树映射"},{"java.util.LinkedHashMap","有序映射"},
            {"java.util.HashSet","哈希集合"},{"java.util.TreeSet","树集合"},
            {"java.util.List","列表"},{"java.util.Map","映射"},{"java.util.Set","集合"},
            {"java.util.Collection","容器"},{"java.util.Collections","集合工具"},
            {"java.util.Arrays","数组工具"},{"java.util.Iterator","迭代器"},
            {"java.util.Optional","可空容器"},{"java.util.Random","随机数"},
            {"java.util.Scanner","扫描输入"},{"java.util.StringTokenizer","字符串分词"},
            {"java.util.Date","日期"},{"java.util.Calendar","日历"},
            {"java.util.Properties","属性表"},{"java.util.Timer","定时器"},
            {"java.util.Objects","对象工具"},{"java.util.Base64","六十四进制编解码"},
            {"java.util.UUID","唯一标识"},{"java.util.Deque","双端队列"},
            {"java.util.ArrayDeque","数组双端队列"},{"java.util.Queue","队列"},
            {"java.util.regex.Pattern","正则模式"},{"java.util.regex.Matcher","正则匹配器"},
            {"java.io.File","文件"},{"java.io.FileReader","文件读取"},
            {"java.io.FileWriter","文件写入"},{"java.io.BufferedReader","缓冲读取"},
            {"java.io.BufferedWriter","缓冲写入"},{"java.io.InputStream","输入流"},
            {"java.io.OutputStream","输出流"},{"java.io.PrintStream","打印流"},
            {"java.io.PrintWriter","打印写入"},{"java.io.ByteArrayOutputStream","字节数组输出流"},
            {"java.io.ByteArrayInputStream","字节数组输入流"},{"java.io.ObjectInputStream","对象输入流"},
            {"java.io.ObjectOutputStream","对象输出流"},{"java.io.RandomAccessFile","随机访问文件"},{"java.io.Console","控制台"},
            {"java.nio.file.Path","路径"},{"java.nio.file.Paths","路径工具"},
            {"java.nio.file.Files","文件工具"},{"java.nio.ByteBuffer","字节缓冲"},
            {"java.nio.charset.Charset","字符集"},{"java.nio.charset.StandardCharsets","标准字符集"},
            {"java.net.URL","网址"},{"java.net.URI","统一资源标识"},
            {"java.net.URLEncoder","网址编码器"},{"java.net.URLDecoder","网址解码器"},
            {"java.net.http.HttpClient","超文本传输客户端"},{"java.net.Socket","套接字"},
            {"java.time.LocalDate","日期"},{"java.time.LocalTime","时间"},
            {"java.time.LocalDateTime","日期时间"},{"java.time.Instant","时间戳"},
            {"java.time.Duration","时长"},{"java.time.Period","日期间隔"},
            {"java.time.ZonedDateTime","带时区日期时间"},{"java.time.ZoneId","时区"},
            {"java.time.format.DateTimeFormatter","日期格式"},
            {"java.text.SimpleDateFormat","简单日期格式"},{"java.text.DecimalFormat","数字格式"},
            {"java.math.BigDecimal","高精度小数"},{"java.math.BigInteger","大整数"},
            {"java.util.concurrent.Executors","线程池工具"},{"java.util.concurrent.Future","异步结果"},
            {"java.util.concurrent.atomic.AtomicInteger","原子整数"},
            {"java.util.concurrent.atomic.AtomicLong","原子长整数"},
            {"java.util.concurrent.locks.ReentrantLock","可重入锁"},
            {"java.util.stream.Stream","流"},{"java.util.stream.Collectors","流收集器"},
            {"java.util.function.Function","函数"},{"java.util.function.Consumer","消费者"},
            {"java.util.function.Supplier","供给者"},{"java.util.function.Predicate","判定"},
            {"java.util.function.BiFunction","二元函数"},
            // 补齐类的中文名（对照 JDK 17/21/25）
            {"java.lang.Number","数字(抽象)"},{"java.lang.Module","模块"},
            {"java.lang.StackWalker","栈遍历器"},{"java.lang.ScopedValue","作用域值"},
            {"java.lang.ThreadGroup","线程组"},{"java.lang.MatchException","匹配异常"},
            {"java.lang.ReflectiveOperationException","反射操作异常"},
            {"java.lang.ClassNotFoundException","类未找到异常"},
            {"java.lang.InterruptedException","中断异常"},
            {"java.lang.CloneNotSupportedException","不支持克隆异常"},
            {"java.lang.IllegalAccessException","非法访问异常"},
            {"java.lang.InstantiationException","实例化异常"},
            {"java.lang.NoSuchMethodException","无此方法异常"},
            {"java.lang.NoSuchFieldException","无此字段异常"},
            {"java.lang.ExceptionInInitializerError","初始化错误"},
            {"java.lang.Error","错误(根)"},{"java.lang.AssertionError","断言错误"},
            {"java.lang.StringIndexOutOfBoundsException","字符串越界异常"},
            {"java.lang.NegativeArraySizeException","负数组大小异常"},
            {"java.lang.ArrayStoreException","数组存储异常"},
            {"java.lang.IllegalMonitorStateException","非法监视器状态异常"},
            {"java.lang.IllegalThreadStateException","非法线程状态异常"},
            {"java.lang.SecurityException","安全异常"},
            {"java.lang.TypeNotPresentException","类型不存在异常"},
            {"java.lang.EnumConstantNotPresentException","枚举常量不存在异常"},
            {"java.lang.IllegalCallerException","非法调用者异常"},
            {"java.lang.OutOfMemoryError","内存溢出错误"},
            {"java.lang.StackOverflowError","栈溢出错误"},
            {"java.lang.VirtualMachineError","虚拟机错误"},
            {"java.lang.LinkageError","链接错误"},
            {"java.lang.NoClassDefFoundError","类定义未找到错误"},
            {"java.lang.UnsupportedClassVersionError","不支持的类版本错误"},
            {"java.lang.AbstractMethodError","抽象方法错误"},
            {"java.lang.NoSuchMethodError","无此方法错误"},
            {"java.lang.NoSuchFieldError","无此字段错误"},
            {"java.lang.IncompatibleClassChangeError","不兼容类变更错误"},
            {"java.lang.VerifyError","校验错误"},{"java.lang.InternalError","内部错误"},
            {"java.lang.UnknownError","未知错误"},
            {"java.lang.UnsatisfiedLinkError","链接未满足错误"},
            {"java.lang.BootstrapMethodError","引导方法错误"},
            {"java.lang.ClassFormatError","类格式错误"},
            {"java.lang.ClassCircularityError","类循环错误"},
            {"java.lang.InstantiationError","实例化错误"},
            {"java.lang.IllegalAccessError","非法访问错误"},
            {"java.util.Collection","容器(接口)"},{"java.util.List","列表(接口)"},
            {"java.util.Set","集合(接口)"},{"java.util.Map","映射(接口)"},
            {"java.util.Queue","队列(接口)"},{"java.util.Deque","双端队列(接口)"},
            {"java.util.NavigableSet","可导航集合"},{"java.util.NavigableMap","可导航映射"},
            {"java.util.SortedSet","排序集合"},{"java.util.SortedMap","排序映射"},
            {"java.util.SequencedCollection","序列集合"},
            {"java.util.SequencedMap","序列映射"},
            {"java.util.SequencedSet","序列集合"},
            {"java.util.random.RandomGenerator","随机生成器"},
            {"java.util.concurrent.Flow","反应流"},
            {"java.util.concurrent.RecursiveTask","可返回结果递归任务"},
            {"java.util.concurrent.RecursiveAction","递归动作"},
            {"java.util.concurrent.locks.StampedLock","戳锁"},
            {"java.util.concurrent.Executor","执行器(接口)"},
            {"java.util.concurrent.AbstractExecutorService","抽象执行器服务"},
            {"java.util.concurrent.SubmissionPublisher","提交发布器"},
            {"java.util.concurrent.BlockingQueue","阻塞队列(接口)"},
            // ---- 补充：工具箱支持但词典未收录的 JDK 库类（自识别数据层，非渲染硬编码）----
            {"java.io.BufferedInputStream","缓冲输入流"},
            {"java.io.BufferedOutputStream","缓冲输出流"},
            {"java.io.CharArrayReader","字符数组读取器"},
            {"java.io.CharArrayWriter","字符数组写入器"},
            {"java.io.DataInputStream","数据输入流"},
            {"java.io.DataOutputStream","数据输出流"},
            {"java.io.EOFException","文件结束异常"},
            {"java.io.FileFilter","文件过滤器"},
            {"java.io.FileInputStream","文件输入流"},
            {"java.io.FileNotFoundException","文件未找到异常"},
            {"java.io.FileOutputStream","文件输出流"},
            {"java.io.FilenameFilter","文件名过滤器"},
            {"java.io.FilterInputStream","过滤输入流"},
            {"java.io.FilterOutputStream","过滤输出流"},
            {"java.io.IOException","输入输出异常"},
            {"java.io.InputStreamReader","输入流读取器"},
            {"java.io.LineNumberReader","行号读取器"},
            {"java.io.OutputStreamWriter","输出流写入器"},
            {"java.io.PipedInputStream","管道输入流"},
            {"java.io.PipedOutputStream","管道输出流"},
            {"java.io.PushbackInputStream","回退输入流"},
            {"java.io.PushbackReader","回退读取器"},
            {"java.io.Reader","读取器"},
            {"java.io.SequenceInputStream","序列输入流"},
            {"java.io.StreamTokenizer","流分词器"},
            {"java.io.StringReader","字符串读取器"},
            {"java.io.StringWriter","字符串写入器"},
            {"java.io.Writer","写入器"},
            {"java.lang.Appendable","可追加"},
            {"java.lang.ArithmeticException","算术异常"},
            {"java.lang.ArrayIndexOutOfBoundsException","数组下标越界异常"},
            {"java.lang.ClassCastException","类型转换异常"},
            {"java.lang.Cloneable","可克隆"},
            {"java.lang.IllegalArgumentException","非法参数异常"},
            {"java.lang.IllegalStateException","非法状态异常"},
            {"java.lang.IndexOutOfBoundsException","下标越界异常"},
            {"java.lang.NullPointerException","空指针异常"},
            {"java.lang.NumberFormatException","数字格式异常"},
            {"java.lang.Package","包"},
            {"java.lang.Readable","可读"},
            {"java.lang.ThreadLocal","线程局部变量"},
            {"java.lang.UnsupportedOperationException","不支持的操作异常"},
            {"java.lang.Void","空类型"},
            {"java.math.MathContext","数学上下文"},
            {"java.math.RoundingMode","舍入模式"},
            {"java.net.DatagramPacket","数据报包"},
            {"java.net.DatagramSocket","数据报套接字"},
            {"java.net.HttpURLConnection","超文本传输连接"},
            {"java.net.Inet4Address","网际协议四地址"},
            {"java.net.Inet6Address","网际协议六地址"},
            {"java.net.InetAddress","网络地址"},
            {"java.net.MalformedURLException","网址格式异常"},
            {"java.net.NetworkInterface","网络接口"},
            {"java.net.Proxy","代理"},
            {"java.net.ServerSocket","服务端套接字"},
            {"java.net.SocketException","套接字异常"},
            {"java.net.URLConnection","网址连接"},
            {"java.net.UnknownHostException","未知主机异常"},
            {"java.nio.Buffer","缓冲区"},
            {"java.nio.CharBuffer","字符缓冲"},
            {"java.nio.DoubleBuffer","小数缓冲"},
            {"java.nio.FloatBuffer","单精度缓冲"},
            {"java.nio.IntBuffer","整数缓冲"},
            {"java.nio.LongBuffer","长整数缓冲"},
            {"java.nio.MappedByteBuffer","映射字节缓冲"},
            {"java.nio.ShortBuffer","短整数缓冲"},
            {"java.nio.charset.CharsetDecoder","字符集解码器"},
            {"java.nio.charset.CharsetEncoder","字符集编码器"},
            {"java.nio.file.DirectoryStream","目录流"},
            {"java.nio.file.FileSystem","文件系统"},
            {"java.nio.file.FileSystems","文件系统组"},
            {"java.nio.file.LinkOption","链接选项"},
            {"java.nio.file.PathMatcher","路径匹配器"},
            {"java.nio.file.StandardCopyOption","标准复制选项"},
            {"java.nio.file.StandardOpenOption","标准打开选项"},
            {"java.nio.file.WatchKey","监视键"},
            {"java.nio.file.WatchService","监视服务"},
            {"java.text.BreakIterator","断行迭代器"},
            {"java.text.ChoiceFormat","选择格式"},
            {"java.text.Collator","排序器"},
            {"java.text.DateFormat","日期格式"},
            {"java.text.DateFormatSymbols","日期格式符号"},
            {"java.text.FieldPosition","字段位置"},
            {"java.text.MessageFormat","消息格式"},
            {"java.text.NumberFormat","数字格式"},
            {"java.text.ParsePosition","解析位置"},
            {"java.time.Clock","时钟"},
            {"java.time.DayOfWeek","星期"},
            {"java.time.Month","月份"},
            {"java.time.MonthDay","月日"},
            {"java.time.OffsetDateTime","偏移日期时间"},
            {"java.time.OffsetTime","偏移时间"},
            {"java.time.Year","年"},
            {"java.time.YearMonth","年月"},
            {"java.time.ZoneOffset","时区偏移"},
            {"java.time.format.DateTimeFormatterBuilder","日期时间格式构建器"},
            {"java.time.format.DecimalStyle","小数样式"},
            {"java.time.format.ResolverStyle","解析样式"},
            {"java.time.format.SignStyle","符号样式"},
            {"java.time.temporal.ChronoUnit","时间单位"},
            {"java.time.temporal.Temporal","时间"},
            {"java.time.temporal.TemporalAccessor","时间访问器"},
            {"java.time.temporal.TemporalAdjuster","时间调整器"},
            {"java.time.temporal.TemporalAmount","时间量"},
            {"java.time.temporal.TemporalField","时间字段"},
            {"java.time.temporal.TemporalQuery","时间查询"},
            {"java.time.temporal.TemporalUnit","时间单位"},
            {"java.time.temporal.ValueRange","值范围"},
            {"java.time.temporal.WeekFields","周字段"},
            {"java.util.BitSet","位集"},
            {"java.util.Comparator","比较器"},
            {"java.util.EnumMap","枚举映射"},
            {"java.util.EnumSet","枚举集合"},
            {"java.util.Formatter","格式化器"},
            {"java.util.GregorianCalendar","公历"},
            {"java.util.Hashtable","哈希表"},
            {"java.util.IdentityHashMap","恒等映射"},
            {"java.util.LinkedHashSet","有序哈希集合"},
            {"java.util.ListIterator","列表迭代器"},
            {"java.util.Locale","区域设置"},
            {"java.util.OptionalDouble","可选小数"},
            {"java.util.OptionalInt","可选整数"},
            {"java.util.OptionalLong","可选长整数"},
            {"java.util.PriorityQueue","优先队列"},
            {"java.util.ServiceLoader","服务加载器"},
            {"java.util.SplittableRandom","可拆分随机数"},
            {"java.util.Stack","栈"},
            {"java.util.StringJoiner","字符串连接器"},
            {"java.util.WeakHashMap","弱引用映射"},
            {"java.util.concurrent.Callable","可调用"},
            {"java.util.concurrent.CompletableFuture","可完成未来"},
            {"java.util.concurrent.ConcurrentHashMap","并发哈希映射"},
            {"java.util.concurrent.ConcurrentLinkedQueue","并发链表队列"},
            {"java.util.concurrent.CopyOnWriteArrayList","写时复制列表"},
            {"java.util.concurrent.CountDownLatch","倒计时锁存器"},
            {"java.util.concurrent.CyclicBarrier","循环屏障"},
            {"java.util.concurrent.Exchanger","交换器"},
            {"java.util.concurrent.ExecutorService","执行器服务"},
            {"java.util.concurrent.ForkJoinPool","分合线程池"},
            {"java.util.concurrent.ForkJoinTask","分合任务"},
            {"java.util.concurrent.FutureTask","未来任务"},
            {"java.util.concurrent.LinkedBlockingQueue","链表阻塞队列"},
            {"java.util.concurrent.Phaser","阶段器"},
            {"java.util.concurrent.PriorityBlockingQueue","优先阻塞队列"},
            {"java.util.concurrent.ScheduledExecutorService","定时执行器服务"},
            {"java.util.concurrent.Semaphore","信号量"},
            {"java.util.concurrent.ThreadFactory","线程工厂"},
            {"java.util.concurrent.TimeUnit","时间单位"},
            {"java.util.concurrent.atomic.AtomicBoolean","原子布尔"},
            {"java.util.concurrent.atomic.AtomicReference","原子引用"},
            {"java.util.concurrent.locks.Lock","锁"},
            {"java.util.concurrent.locks.ReadWriteLock","读写锁"},
            {"java.util.function.BiConsumer","双参数消费者"},
            {"java.util.function.BiPredicate","双参数断言"},
            {"java.util.function.BinaryOperator","二元运算"},
            {"java.util.function.BooleanSupplier","布尔供应者"},
            {"java.util.function.DoubleBinaryOperator","小数二元运算"},
            {"java.util.function.DoubleUnaryOperator","小数一元运算"},
            {"java.util.function.IntBinaryOperator","整数二元运算"},
            {"java.util.function.IntConsumer","整数消费者"},
            {"java.util.function.IntFunction","整数函数"},
            {"java.util.function.IntSupplier","整数供应者"},
            {"java.util.function.IntUnaryOperator","整数一元运算"},
            {"java.util.function.LongConsumer","长整数消费者"},
            {"java.util.function.LongFunction","长整数函数"},
            {"java.util.function.LongSupplier","长整数供应者"},
            {"java.util.function.LongUnaryOperator","长整数一元运算"},
            {"java.util.function.ToDoubleFunction","转小数函数"},
            {"java.util.function.ToIntFunction","转整数函数"},
            {"java.util.function.UnaryOperator","一元运算"},
            {"java.util.stream.BaseStream","基础流"},
            {"java.util.stream.DoubleStream","小数流"},
            {"java.util.stream.IntStream","整数流"},
            {"java.util.stream.LongStream","长整数流"},
            {"java.util.stream.StreamSupport","流支持"},
            {"java.util.zip.Adler32","阿德勒校验"},
            {"java.util.zip.CRC32","循环冗余校验"},
            {"java.util.zip.CheckedInputStream","校验输入流"},
            {"java.util.zip.CheckedOutputStream","校验输出流"},
            {"java.util.zip.Deflater","压缩器"},
            {"java.util.zip.GZIPInputStream","压缩格式输入流"},
            {"java.util.zip.GZIPOutputStream","压缩格式输出流"},
            {"java.util.zip.Inflater","解压器"},
            {"java.util.zip.ZipEntry","压缩条目"},
            {"java.util.zip.ZipFile","压缩文件"},
            {"java.util.zip.ZipInputStream","压缩输入流"},
            {"java.util.zip.ZipOutputStream","压缩输出流"},
            {"java.util.concurrent.ArrayBlockingQueue","数组阻塞队列"},
        };
        for (String[] p : pairs) CLASS_CN.put(p[0], p[1]);
    }

    /** 全量 API 词条（方法 / 字段 / 缩写）：逐条核对写入的自识别数据，类名不在此表。 */
    private static final Map<String, String> EXTRA_CN = new LinkedHashMap<>();
    static {
        String[][] pairs = {
// ===== 生成的中文词条（写入 CLBC_JdkHistory 自识别词典） =====
            {"AD","公元"},{"ALL_STYLES","全部样式"},{"ALWAYS","始终"},{"AM","上午"},
            {"AM_PM","上午下午"},{"AM_PM_FIELD","上午下午字段"},{"APPEND","追加"},{"APRIL","四月"},
            {"ATOMIC_MOVE","原子移动"},{"AUGUST","八月"},{"Adler","阿德勒"},{"BASIC_ISO_DATE","基本国际标准日期"},
            {"BC","公元前"},{"BEST_COMPRESSION","最佳压缩"},{"BEST_SPEED","最佳速度"},{"BYTES","字节"},
            {"CANADA","加拿大"},{"CANADA_FRENCH","加拿大法语"},{"CANONICAL_DECOMPOSITION","规范分解"},{"CANON_EQ","规范等于"},
            {"CASE_INSENSITIVE","情况不敏感"},{"CASE_INSENSITIVE_ORDER","情况不敏感顺序"},{"CEILING","向上取整"},{"CENATT","中央目录属性"},
            {"CENATX","中央目录附加"},{"CENCOM","中央目录注释"},{"CENCRC","中央目录校验"},{"CENDSK","中央目录磁盘"},
            {"CENEXT","中央目录扩展"},{"CENFLG","中央目录标志"},{"CENHDR","中央目录头"},{"CENHOW","中央目录压缩方式"},
            {"CENLEN","中央目录长度"},{"CENNAM","中央目录名称"},{"CENOFF","中央目录偏移"},{"CENSIG","中央目录签名"},
            {"CENSIZ","中央目录尺寸"},{"CENTIM","中央目录时间"},{"CENTURIES","世纪"},{"CENVEM","中央目录版本"},
            {"CENVER","中央目录所需版本"},{"CHINA","中国"},{"CHINESE","中文"},{"COMBINING_SPACING_MARK","组合间隔标记"},
            {"COMMENTS","注释"},{"CONNECTOR_PUNCTUATION","连接器标点"},{"CONTROL","控制"},{"COPY_ATTRIBUTES","复制属性"},
            {"CREATE","创建"},{"CREATE_NEW","创建新建"},{"CURRENCY_SYMBOL","货币符号"},{"DASH_PUNCTUATION","短横线标点"},
            {"DATE","日期"},{"DATE_FIELD","日期字段"},{"DAYS","天"},{"DAY_OF_MONTH","日创建月"},
            {"DAY_OF_WEEK","日创建周"},{"DAY_OF_WEEK_FIELD","日创建周字段"},{"DAY_OF_WEEK_IN_MONTH","日创建周在月"},{"DAY_OF_WEEK_IN_MONTH_FIELD","日创建周在月字段"},
            {"DAY_OF_YEAR","日创建年"},{"DAY_OF_YEAR_FIELD","日创建年字段"},{"DECADES","十年"},{"DECEMBER","十二月"},
            {"DECIMAL128","十进制128"},{"DECIMAL32","十进制32"},{"DECIMAL64","十进制64"},{"DECIMAL_DIGIT_NUMBER","十进制数字数"},
            {"DEFAULT","默认"},{"DEFAULT_COMPRESSION","默认压缩"},{"DEFAULT_STRATEGY","默认策略"},{"DEFLATED","已压缩"},
            {"DELETE_ON_CLOSE","删除当关闭"},{"DIRECTIONALITY_ARABIC_NUMBER","方向性阿拉伯数"},{"DIRECTIONALITY_BOUNDARY_NEUTRAL","方向性边界中性"},{"DIRECTIONALITY_COMMON_NUMBER_SEPARATOR","方向性通用数分隔符"},
            {"DIRECTIONALITY_EUROPEAN_NUMBER","方向性欧洲数"},{"DIRECTIONALITY_EUROPEAN_NUMBER_SEPARATOR","方向性欧洲数分隔符"},{"DIRECTIONALITY_EUROPEAN_NUMBER_TERMINATOR","方向性欧洲数终止符"},{"DIRECTIONALITY_FIRST_STRONG_ISOLATE","方向性首个强隔离"},
            {"DIRECTIONALITY_LEFT_TO_RIGHT","方向性从左到右"},{"DIRECTIONALITY_LEFT_TO_RIGHT_EMBEDDING","方向性从左到右嵌入"},{"DIRECTIONALITY_LEFT_TO_RIGHT_ISOLATE","方向性从左到右隔离"},{"DIRECTIONALITY_LEFT_TO_RIGHT_OVERRIDE","方向性从左到右覆盖"},
            {"DIRECTIONALITY_NONSPACING_MARK","方向性不占位标记"},{"DIRECTIONALITY_OTHER_NEUTRALS","方向性其他中性"},{"DIRECTIONALITY_PARAGRAPH_SEPARATOR","方向性段落分隔符"},{"DIRECTIONALITY_POP_DIRECTIONAL_FORMAT","方向性弹出方向格式"},
            {"DIRECTIONALITY_POP_DIRECTIONAL_ISOLATE","方向性弹出方向隔离"},{"DIRECTIONALITY_RIGHT_TO_LEFT","方向性从右到左"},{"DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC","方向性右转左阿拉伯"},{"DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING","方向性从右到左嵌入"},
            {"DIRECTIONALITY_RIGHT_TO_LEFT_ISOLATE","方向性从右到左隔离"},{"DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE","方向性从右到左覆盖"},{"DIRECTIONALITY_SEGMENT_SEPARATOR","方向性片段分隔符"},{"DIRECTIONALITY_UNDEFINED","方向性未定义"},
            {"DIRECTIONALITY_WHITESPACE","方向性空白"},{"DONE","完成"},{"DOTALL","点匹配全部"},{"DOWN","向下"},
            {"DST_OFFSET","夏令时偏移"},{"DSYNC","数据同步"},{"E","自然常数"},{"EMPTY_LIST","空列表"},
            {"EMPTY_MAP","空映射"},{"EMPTY_SET","空设"},{"ENCLOSING_MARK","外围标记"},{"ENDCOM","结束注释"},
            {"ENDHDR","结束头"},{"ENDOFF","结束偏移"},{"ENDSIG","结束签名"},{"ENDSIZ","结束尺寸"},
            {"ENDSUB","结束子项"},{"ENDTOT","结束总数"},{"END_PUNCTUATION","结束标点"},{"ENGLISH","英语"},
            {"EPOCH","纪元"},{"ERA","纪元"},{"ERAS","纪元"},{"ERA_FIELD","纪元字段"},
            {"EXCEEDS_PAD","超出填充"},{"EXTCRC","扩展校验"},{"EXTHDR","扩展头"},{"EXTLEN","扩展长度"},
            {"EXTSIG","扩展签名"},{"EXTSIZ","扩展尺寸"},{"FALSE","假"},{"FEBRUARY","二月"},
            {"FIELD_COUNT","字段计数"},{"FILTERED","已过滤"},{"FINAL_QUOTE_PUNCTUATION","最终引号标点"},{"FLOOR","向下取整"},
            {"FOREVER","永久"},{"FORMAT","格式"},{"FRACTION_FIELD","小数部分字段"},{"FRANCE","法国"},
            {"FRENCH","法语"},{"FRIDAY","星期五"},{"FULL","完整"},{"FULL_DECOMPOSITION","完整分解"},
            {"FULL_FLUSH","完整刷新"},{"GERMAN","德语"},{"GERMANY","德国"},{"GZIP","压缩格式"},
            {"GZIP_MAGIC","压缩格式魔数"},{"HALF_DAYS","半天"},{"HALF_DOWN","半向下"},{"HALF_EVEN","半偶数"},
            {"HALF_UP","半向上"},{"HOUR","小时"},{"HOUR0_FIELD","零基小时字段"},{"HOUR1_FIELD","一基小时字段"},
            {"HOURS","小时"},{"HOUR_OF_DAY","小时创建日"},{"HOUR_OF_DAY0_FIELD","小时创建零基日字段"},{"HOUR_OF_DAY1_FIELD","小时创建一基日字段"},
            {"HTTP","超文本传输"},{"HTTP_ACCEPTED","超文本传输已接受"},{"HTTP_BAD_GATEWAY","超文本传输错误网关"},{"HTTP_BAD_METHOD","超文本传输错误方法"},
            {"HTTP_BAD_REQUEST","超文本传输错误请求"},{"HTTP_CLIENT_TIMEOUT","超文本传输客户端超时"},{"HTTP_CONFLICT","超文本传输冲突"},{"HTTP_CREATED","超文本传输已创建"},
            {"HTTP_ENTITY_TOO_LARGE","超文本传输实体过于大"},{"HTTP_FORBIDDEN","超文本传输禁止"},{"HTTP_GATEWAY_TIMEOUT","超文本传输网关超时"},{"HTTP_GONE","超文本传输已消失"},
            {"HTTP_INTERNAL_ERROR","超文本传输内部错误"},{"HTTP_LENGTH_REQUIRED","超文本传输长度必需"},{"HTTP_MOVED_PERM","超文本传输移动许可"},{"HTTP_MOVED_TEMP","超文本传输移动临时"},
            {"HTTP_MULT_CHOICE","超文本传输乘选择"},{"HTTP_NOT_ACCEPTABLE","超文本传输非可接受"},{"HTTP_NOT_AUTHORITATIVE","超文本传输非权威"},{"HTTP_NOT_FOUND","超文本传输非找到"},
            {"HTTP_NOT_IMPLEMENTED","超文本传输非已实现"},{"HTTP_NOT_MODIFIED","超文本传输非已修改"},{"HTTP_NO_CONTENT","超文本传输无内容"},{"HTTP_OK","超文本传输正常"},
            {"HTTP_PARTIAL","超文本传输部分"},{"HTTP_PAYMENT_REQUIRED","超文本传输支付必需"},{"HTTP_PRECON_FAILED","超文本传输预定义失败"},{"HTTP_PROXY_AUTH","超文本传输代理认证"},
            {"HTTP_REQ_TOO_LONG","超文本传输请求过于长"},{"HTTP_RESET","超文本传输重置"},{"HTTP_SEE_OTHER","超文本传输参见其他"},{"HTTP_SERVER_ERROR","超文本传输服务端错误"},
            {"HTTP_UNAUTHORIZED","超文本传输未授权"},{"HTTP_UNAVAILABLE","超文本传输不可用"},{"HTTP_UNSUPPORTED_TYPE","超文本传输不支持类型"},{"HTTP_USE_PROXY","超文本传输使用代理"},
            {"HTTP_VERSION","超文本传输版本"},{"HUFFMAN_ONLY","霍夫曼仅"},{"IDENTICAL","同一"},{"IEEEremainder","精确余数"},
            {"INITIAL_QUOTE_PUNCTUATION","初始引号标点"},{"INTEGER_FIELD","整数字段"},{"IO","输入输出"},{"IPv4","网际协议四"},
            {"IPv6","网际协议六"},{"ISO","国际标准"},{"ISO_8859_1","国际标准88591"},{"ISO_DATE","国际标准日期"},
            {"ISO_DATE_TIME","国际标准日期时间"},{"ISO_INSTANT","国际标准瞬时"},{"ISO_LOCAL_DATE","国际标准本地日期"},{"ISO_LOCAL_DATE_TIME","国际标准本地日期时间"},
            {"ISO_LOCAL_TIME","国际标准本地时间"},{"ISO_OFFSET_DATE","国际标准偏移日期"},{"ISO_OFFSET_DATE_TIME","国际标准偏移日期时间"},{"ISO_OFFSET_TIME","国际标准偏移时间"},
            {"ISO_ORDINAL_DATE","国际标准序数日期"},{"ISO_TIME","国际标准时间"},{"ISO_WEEK_DATE","国际标准周日期"},{"ISO_ZONED_DATE_TIME","国际标准带时区日期时间"},
            {"ITALIAN","意大利语"},{"ITALY","意大利"},{"JANUARY","一月"},{"JAPAN","日本"},
            {"JAPANESE","日语"},{"JULY","七月"},{"JUNE","六月"},{"KOREA","韩国"},
            {"KOREAN","韩语"},{"LENIENT","宽松"},{"LETTER_NUMBER","字母数"},{"LINE_SEPARATOR","行分隔符"},
            {"LITERAL","字面量"},{"LOCCRC","本地头校验"},{"LOCEXT","本地头扩展"},{"LOCFLG","本地头标志"},
            {"LOCHDR","本地头"},{"LOCHOW","本地头压缩方式"},{"LOCLEN","本地头长度"},{"LOCNAM","本地头名称"},
            {"LOCSIG","本地头签名"},{"LOCSIZ","本地头尺寸"},{"LOCTIM","本地头时间"},{"LOCVER","本地头版本"},
            {"LONG","长"},{"LONG_FORMAT","长格式"},{"LONG_STANDALONE","长独立"},{"LOWERCASE_LETTER","小写字母"},
            {"MARCH","三月"},{"MATH_SYMBOL","数学符号"},{"MAX","最大"},{"MAX_CODE_POINT","最大码点"},
            {"MAX_EXPONENT","最大指数"},{"MAX_HIGH_SURROGATE","最大高代理项"},{"MAX_LOW_SURROGATE","最大低代理项"},{"MAX_PRIORITY","最大优先级"},
            {"MAX_RADIX","最大基数"},{"MAX_SURROGATE","最大代理项"},{"MAX_VALUE","最大值"},{"MAY","五月"},
            {"MEDIUM","中"},{"MICROS","微秒"},{"MICROSECONDS","微秒"},{"MIDNIGHT","午夜"},
            {"MILLENNIA","千年"},{"MILLIS","毫秒"},{"MILLISECOND","毫秒"},{"MILLISECONDS","毫秒"},
            {"MILLISECOND_FIELD","毫秒字段"},{"MIN","最小"},{"MINUTE","分钟"},{"MINUTES","分钟"},
            {"MINUTE_FIELD","分钟字段"},{"MIN_CODE_POINT","最小码点"},{"MIN_EXPONENT","最小指数"},{"MIN_HIGH_SURROGATE","最小高代理项"},
            {"MIN_LOW_SURROGATE","最小低代理项"},{"MIN_NORMAL","最小标准"},{"MIN_PRIORITY","最小优先级"},{"MIN_RADIX","最小基数"},
            {"MIN_SUPPLEMENTARY_CODE_POINT","最小补充码点"},{"MIN_SURROGATE","最小代理项"},{"MIN_VALUE","最小值"},{"MODIFIER_LETTER","修饰符字母"},
            {"MODIFIER_SYMBOL","修饰符符号"},{"MONDAY","星期一"},{"MONTH","月"},{"MONTHS","月"},
            {"MONTH_FIELD","月字段"},{"MULTILINE","多行"},{"N","数量"},{"NANOS","纳秒"},
            {"NANOSECONDS","纳秒"},{"NARROW_FORMAT","窄格式"},{"NARROW_STANDALONE","窄独立"},{"NEGATIVE_INFINITY","负无穷"},
            {"NEVER","从不"},{"NOFOLLOW_LINKS","不跟随链接"},{"NON_SPACING_MARK","非间隔标记"},{"NOON","中午"},
            {"NORMAL","标准"},{"NORM_PRIORITY","规范优先级"},{"NOT_NEGATIVE","非负"},{"NOVEMBER","十一月"},
            {"NO_COMPRESSION","无压缩"},{"NO_DECOMPOSITION","无分解"},{"NO_FLUSH","无刷新"},{"NO_PROXY","无代理"},
            {"NaN","不适用个"},{"OCTOBER","十月"},{"ONE","一"},{"OPEN_DELETE","打开删除"},
            {"OPEN_READ","打开读"},{"OTHER_LETTER","其他字母"},{"OTHER_NUMBER","其他数"},{"OTHER_PUNCTUATION","其他标点"},
            {"OTHER_SYMBOL","其他符号"},{"PARAGRAPH_SEPARATOR","段落分隔符"},{"PI","圆周率"},{"PM","下午"},
            {"POSITIVE_INFINITY","正无穷"},{"PRC","中国"},{"PRECISION","精度"},{"PRIMARY","主要"},
            {"PRIVATE_USE","私有使用"},{"PRIVATE_USE_EXTENSION","私有使用扩展"},{"PROTOCOL_VERSION_1","协议版本1"},{"PROTOCOL_VERSION_2","协议版本2"},
            {"READ","读"},{"REPLACE_EXISTING","替换现有"},{"RFC_1123_DATE_TIME","征求意见规范1123日期时间"},{"ROOT","根"},
            {"ROUND_CEILING","舍入向上取整"},{"ROUND_DOWN","舍入向下"},{"ROUND_FLOOR","舍入向下取整"},{"ROUND_HALF_DOWN","舍入半向下"},
            {"ROUND_HALF_EVEN","舍入半偶数"},{"ROUND_HALF_UP","舍入半向上"},{"ROUND_UNNECESSARY","舍入不必要"},{"ROUND_UP","舍入向上"},
            {"SATURDAY","星期六"},{"SC_BLOCK_DATA","序列化标志块数据"},{"SC_ENUM","序列化标志枚举"},{"SC_EXTERNALIZABLE","序列化标志可外部化"},
            {"SC_SERIALIZABLE","序列化标志可序列化"},{"SC_WRITE_METHOD","序列化标志写方法"},{"SECOND","秒"},{"SECONDARY","次要"},
            {"SECONDS","秒"},{"SECOND_FIELD","秒字段"},{"SEPTEMBER","九月"},{"SERIAL_FILTER_PERMISSION","串行过滤权限"},
            {"SHORT","短整数"},{"SHORT_FORMAT","短整数格式"},{"SHORT_IDS","短整数标识"},{"SHORT_STANDALONE","短整数独立"},
            {"SIMPLIFIED_CHINESE","简体中文"},{"SIZE","大小"},{"SMART","智能"},{"SPACE_SEPARATOR","空格分隔符"},
            {"SPARSE","稀疏"},{"STANDARD","标准"},{"START_PUNCTUATION","开始标点"},{"STORED","已存储"},
            {"STREAM_MAGIC","流魔数"},{"STREAM_VERSION","流版本"},{"STRICT","严格"},{"SUBCLASS_IMPLEMENTATION_PERMISSION","子类实现权限"},
            {"SUBSTITUTION_PERMISSION","替换权限"},{"SUNDAY","星期日"},{"SUNDAY_START","星期日开始"},{"SURROGATE","代理项"},
            {"SYNC","同步"},{"SYNC_FLUSH","同步刷新"},{"TAIWAN","中国台湾"},{"TAU","圆周率两倍"},
            {"TC_ARRAY","类型码数组"},{"TC_BASE","类型码基"},{"TC_BLOCKDATA","类型码块数据"},{"TC_BLOCKDATALONG","类型码长块数据"},
            {"TC_CLASS","类型码类"},{"TC_CLASSDESC","类型码类描述符"},{"TC_ENDBLOCKDATA","类型码结束块数据"},{"TC_ENUM","类型码枚举"},
            {"TC_EXCEPTION","类型码异常"},{"TC_LONGSTRING","类型码长字符串"},{"TC_MAX","类型码最大"},{"TC_NULL","类型码空"},
            {"TC_OBJECT","类型码对象"},{"TC_PROXYCLASSDESC","类型码代理类描述符"},{"TC_REFERENCE","类型码引用"},{"TC_RESET","类型码重置"},
            {"TC_STRING","类型码字符串"},{"TEN","十"},{"TERTIARY","第三"},{"THURSDAY","星期四"},
            {"TIMEZONE_FIELD","时区字段"},{"TITLECASE_LETTER","首字母大写字母"},{"TRADITIONAL_CHINESE","繁体中文"},{"TRUE","真"},
            {"TRUNCATE_EXISTING","截断现有"},{"TT_EOF","类型文件结束"},{"TT_EOL","类型行尾"},{"TT_NUMBER","类型数"},
            {"TT_WORD","类型词"},{"TUESDAY","星期二"},{"TWO","二"},{"TYPE","类型"},
            {"UK","英国"},{"UNASSIGNED","未分配"},{"UNDECIMBER","第十三章"},{"UNICODE_CASE","统一码情况"},
            {"UNICODE_CHARACTER_CLASS","统一码字符类"},{"UNICODE_LOCALE_EXTENSION","统一码地区扩展"},{"UNIX_LINES","换行符"},{"UNLIMITED","无限"},
            {"UNNECESSARY","不必要"},{"UP","向上"},{"UPPERCASE_LETTER","大写字母"},{"US","美国"},
            {"US_ASCII","美国美标码"},{"UTC","协调世界时"},{"UTF_16","万国码16"},{"UTF_16BE","万国码16是"},
            {"UTF_16LE","万国码16小于"},{"UTF_32","万国码32"},{"UTF_32BE","万国码32是"},{"UTF_32LE","万国码32小于"},
            {"UTF_8","万国码8"},{"WEDNESDAY","星期三"},{"WEEKS","周"},{"WEEK_BASED_YEARS","周基于年"},
            {"WEEK_OF_MONTH","周创建月"},{"WEEK_OF_MONTH_FIELD","周创建月字段"},{"WEEK_OF_YEAR","周创建年"},{"WEEK_OF_YEAR_FIELD","周创建年字段"},
            {"WRITE","写"},{"YEAR","年"},{"YEARS","年"},{"YEAR_FIELD","年字段"},
            {"ZERO","零"},{"ZONE_OFFSET","时区偏移"},{"acceptEither","接受二者之一"},{"acceptEitherAsync","接受二者之一异步"},
            {"acquire","获取"},{"acquireUninterruptibly","获取不可中断"},{"activeGroupCount","活动组计数"},{"addAllAbsent","添加全部不存在"},
            {"addExports","添加导出项"},{"addIfAbsent","添加如果不存在"},{"addOpens","添加打开"},{"addReads","添加读"},
            {"addUses","添加使用"},{"address","地址"},{"aliases","别名"},{"allProcesses","全部进程"},
            {"anyOf","任意创建"},{"appendChronologyId","追加纪年标识"},{"appendChronologyText","追加纪年文本"},{"appendDayPeriodText","追加日周期文本"},
            {"appendFraction","追加小数部分"},{"appendGenericZoneText","追加通用时区文本"},{"appendInstant","追加瞬时"},{"appendLiteral","追加字面量"},
            {"appendLocalized","追加本地化"},{"appendLocalizedOffset","追加本地化偏移"},{"appendOffset","追加偏移"},{"appendOffsetId","追加偏移标识"},
            {"appendOptional","追加可选"},{"appendPattern","追加模式"},{"appendText","追加文本"},{"appendValue","追加值"},
            {"appendValueReduced","追加值已缩减"},{"appendZoneId","追加时区标识"},{"appendZoneOrOffsetId","追加时区或偏移标识"},{"appendZoneRegionId","追加时区区域标识"},
            {"appendZoneText","追加时区文本"},{"applyAsLong","应用作为长"},{"applyToEither","应用转二者之一"},{"applyToEitherAsync","应用转二者之一异步"},
            {"arrive","到达"},{"arriveAndAwaitAdvance","到达与等待推进"},{"arriveAndDeregister","到达与注销"},{"asIterator","作为迭代器"},
            {"asLifoQueue","作为后进先出队列"},{"asLongStream","作为长流"},{"asMatchPredicate","作为匹配谓词"},{"asPredicate","作为谓词"},
            {"asReadLock","作为读锁"},{"asReadWriteLock","作为读写锁"},{"asWriteLock","作为写锁"},{"atEndOfMonth","指定结束创建月"},
            {"atMonth","指定月"},{"atMonthDay","指定月日"},{"atStartOfDay","指定开始创建日"},{"atTime","指定时间"},
            {"atYear","指定年"},{"atZoneSameInstant","指定时区相同瞬时"},{"atZoneSimilarLocal","指定时区相似本地"},{"availableCharsets","可用字符集"},
            {"availableLocales","可用地区"},{"availablePermits","可用许可"},{"averageBytesPerChar","平均字节每字符"},{"averageCharsPerByte","平均字符每字节"},
            {"averagingDouble","平均双精度"},{"averagingInt","平均整数"},{"averagingLong","平均长"},{"awaitAdvance","等待推进"},
            {"awaitAdvanceInterruptibly","等待推进可中断"},{"awaitQuiescence","等待静止"},{"bulkRegister","批量注册"},{"call","调用"},
            {"callable","可调用"},{"canExecute","可执行"},{"canUse","可使用"},{"cancelDelayedTasksOnShutdown","取消延迟任务当关闭"},
            {"cardinality","基数"},{"caseFoldLanguageTag","情况折叠语言标签"},{"charCount","字符计数"},{"checkFromIndexSize","检查自索引大小"},
            {"checkFromToIndex","检查自转索引"},{"checkIndex","检查索引"},{"checkValidIntValue","检查有效整数值"},{"checkValidValue","检查有效值"},
            {"checkedCollection","受检容器"},{"checkedList","受检列表"},{"checkedMap","受检映射"},{"checkedNavigableMap","受检可导航映射"},
            {"checkedNavigableSet","受检可导航设"},{"checkedQueue","受检队列"},{"checkedSet","受检设"},{"checkedSortedMap","受检已排序映射"},
            {"checkedSortedSet","受检已排序设"},{"children","子项"},{"clearBit","清空位"},{"clockSequence","时钟序列"},
            {"closeExceptionally","关闭异常时"},{"codePointOf","码点创建"},{"collectingAndThen","收集与之后"},{"commentChar","注释字符"},
            {"commonPool","通用池"},{"comparing","比较"},{"comparingDouble","比较双精度"},{"comparingInt","比较整数"},
            {"comparingLong","比较长"},{"compile","编译"},{"complementOf","补码创建"},{"completeAsync","完成异步"},
            {"completeOnTimeout","完成当超时"},{"completedFuture","已完成未来"},{"completedStage","已完成阶段"},{"console","控制台"},
            {"constantName","常量名称"},{"consume","消耗"},{"convert","转换"},{"copyValueOf","复制值创建"},
            {"countDown","计数向下"},{"countTokens","计数标记"},{"counting","计数"},{"create","创建"},
            {"createDirectories","创建目录"},{"createDirectory","创建目录"},{"createLink","创建链接"},{"createNewFile","创建新建文件"},
            {"createSymbolicLink","创建符号链接"},{"createTempDirectory","创建临时目录"},{"currentThread","当前线程"},{"datesUntil","日期直到"},
            {"dayOfWeek","日创建周"},{"deepHashCode","深哈希码"},{"deepToString","深转字符串"},{"defaultBufferSize","默认缓冲大小"},
            {"defaultCharset","默认字符集"},{"defaultExecutor","默认执行器"},{"defaultForkJoinWorkerThreadFactory","默认分支连接工作线程线程工厂"},{"defaultReadObject","默认读对象"},
            {"defaultThreadFactory","默认线程工厂"},{"defaultWriteObject","默认写对象"},{"deflate","压缩"},{"delayedExecutor","延迟执行器"},
            {"deleteIfExists","删除如果存在"},{"delimiter","分隔符"},{"descendants","后代"},{"destroy","销毁"},
            {"detectedCharset","检测到的字符集"},{"digit","数字"},{"disjoint","不相交"},{"displayName","显示名称"},
            {"divideToIntegralValue","除转整数值"},{"dividedBy","已除按"},{"doubleStream","双精度流"},{"doubleToLongBits","双精度转长位"},
            {"doubleToRawLongBits","双精度转原始长位"},{"drainPermits","排空许可"},{"dumpStack","转储栈"},{"emptyEnumeration","空枚举"},
            {"emptyIterator","空迭代器"},{"emptyListIterator","空列表迭代器"},{"emptyMap","空映射"},{"emptyNavigableMap","空可导航映射"},
            {"emptyNavigableSet","空可导航设"},{"emptySet","空设"},{"emptySortedMap","空已排序映射"},{"emptySortedSet","空已排序设"},
            {"end","结束"},{"entries","条目"},{"entry","条目"},{"enumType","枚举类型"},
            {"enumeration","枚举"},{"eolIsSignificant","行尾是否有效"},{"err","错误"},{"estimateMaximumLag","估计最大延迟"},
            {"estimateMinimumDemand","估计最小需要"},{"exceptionNow","异常当前"},{"exceptionally","异常时"},{"exceptionallyAsync","异常时异步"},
            {"exceptionallyCompose","异常时组合"},{"exceptionallyComposeAsync","异常时组合异步"},{"exchange","交换"},{"externalSubmit","外部提交"},
            {"failedFuture","失败未来"},{"failedStage","失败阶段"},{"filterTags","过滤标签"},{"filtering","过滤"},
            {"findAll","寻找全部"},{"findInLine","寻找在行"},{"findWithinHorizon","寻找范围"},{"firstDayOfYear","首个日创建年"},
            {"firstMonthOfQuarter","首个月创建季度"},{"fixed","固定"},{"flags","标志"},{"flatMapToDouble","扁平映射转双精度"},
            {"flatMapToInt","扁平映射转整数"},{"flatMapToLong","扁平映射转长"},{"flatMapping","扁平映射"},{"float16ToFloat","半精度转单精度"},
            {"floatToFloat16","单精度转半精度"},{"floatToIntBits","单精度转整数位"},{"floatToRawIntBits","单精度转原始整数位"},{"following","之后"},
            {"forDigit","按数字"},{"forEachEntry","按逐个条目"},{"forEachKey","按逐个键"},{"forEachOrdered","按逐个有序"},
            {"forEachRemaining","按逐个剩余"},{"forEachValue","按逐个值"},{"forLanguageTag","按语言标签"},{"forName","按名称"},
            {"force","强制"},{"forceTermination","强制终止"},{"formatTo","格式转"},{"fromString","自字符串"},
            {"gather","收集"},{"gc","垃圾回收"},{"get2DigitYearStart","取2数字年开始"},{"getAbsoluteFile","取绝对文件"},
            {"getActiveThreadCount","取活动线程计数"},{"getActualMaximum","取实际最大"},{"getActualMinimum","取实际最小"},{"getAllByName","取全部按名称"},
            {"getAllStackTraces","取全部栈跟踪"},{"getAllowUserInteraction","取允许用户交互"},{"getAmPmStrings","取上午下午字符串"},{"getAndAdd","取与添加"},
            {"getAnnotations","取注解"},{"getAnnotationsByType","取注解按类型"},{"getArrivedParties","取已到达各方"},{"getAsBoolean","取为布尔"},
            {"getAsLong","取作为长"},{"getAsyncMode","取异步模式"},{"getAttribute","取属性"},{"getAvailableCalendarTypes","取可用日历类型"},
            {"getAvailableZoneIds","取可用时区标识"},{"getBaseUnit","取基单位"},{"getBeginIndex","取开始索引"},{"getBoolean","取布尔"},
            {"getBroadcast","取广播"},{"getBuffer","取缓冲"},{"getByAddress","取按地址"},{"getByIndex","取按索引"},
            {"getByInetAddress","取按网际地址"},{"getByName","取按名称"},{"getBytesRead","取字节读"},{"getBytesWritten","取字节已写"},
            {"getCalendar","取日历"},{"getCalendarType","取日历类型"},{"getCallerClass","取调用方类"},{"getCanonicalFile","取规范文件"},
            {"getCanonicalHostName","取规范主机名称"},{"getCanonicalPath","取规范路径"},{"getCharacterInstance","取字符实例"},{"getClassLoader","取类加载器"},
            {"getClassLoaderName","取类加载器名称"},{"getClassName","取类名称"},{"getClosedException","取已关闭异常"},{"getCollationKey","取排序规则键"},
            {"getCommonPoolParallelism","取通用池并行度"},{"getCompactNumberInstance","取紧凑数实例"},{"getCompressedSize","取已压缩大小"},{"getConnectTimeout","取连接超时"},
            {"getContent","取内容"},{"getContentEncoding","取内容编码"},{"getContentLength","取内容长度"},{"getContentLengthLong","取内容长度长"},
            {"getContentType","取内容类型"},{"getContextClassLoader","取上下文类加载器"},{"getCount","取计数"},{"getCountry","取国家"},
            {"getCrc","取循环冗余校验"},{"getCreationTime","取创建时间"},{"getData","取数据"},{"getDateFormatSymbols","取日期格式符号"},
            {"getDay","取日"},{"getDayOfWeek","取日创建周"},{"getDayOfYear","取日创建年"},{"getDays","取天"},
            {"getDecimalFormatSymbols","取十进制格式符号"},{"getDecimalSeparator","取十进制分隔符"},{"getDecimalStyle","取十进制样式"},{"getDeclaredAnnotation","取声明注解"},
            {"getDeclaredAnnotations","取声明注解"},{"getDeclaredAnnotationsByType","取声明注解按类型"},{"getDecomposition","取分解"},{"getDefaultAllowUserInteraction","取默认允许用户交互"},
            {"getDefaultPort","取默认端口"},{"getDefaultUncaughtExceptionHandler","取默认未捕获异常处理器"},{"getDefaultUseCaches","取默认使用缓存"},{"getDelayedTaskCount","取延迟任务计数"},
            {"getDescriptor","取描述符"},{"getDirectionality","取方向性"},{"getDisplayCountry","取显示国家"},{"getDisplayLanguage","取显示语言"},
            {"getDisplayNames","取显示名称"},{"getDisplayScript","取显示脚本"},{"getDisplayVariant","取显示变体"},{"getDoInput","取执行输入"},
            {"getDoOutput","取执行输出"},{"getDuration","取时长"},{"getEndIndex","取结束索引"},{"getEntry","取条目"},
            {"getEpochSecond","取纪元秒"},{"getEra","取纪元"},{"getEras","取纪元"},{"getErrorIndex","取错误索引"},
            {"getErrorStream","取错误流"},{"getExecutor","取执行器"},{"getExpiration","取过期"},{"getExponent","取指数"},
            {"getExtension","取扩展"},{"getExtensionKeys","取扩展键"},{"getExtra","取额外"},{"getFD","取文件描述符"},
            {"getFactory","取工厂"},{"getField","取字段"},{"getFieldAttribute","取字段属性"},{"getFile","取文件"},
            {"getFileAttributeView","取文件属性视图"},{"getFileName","取文件名称"},{"getFileNameMap","取文件名称映射"},{"getFilePointer","取文件指针"},
            {"getFileStore","取文件存储"},{"getFileStores","取文件存储"},{"getFileSystem","取文件系统"},{"getFirstDayOfWeek","取首个日创建周"},
            {"getFollowRedirects","取跟随重定向"},{"getForkJoinTaskTag","取分支连接任务标签"},{"getFormats","取格式"},{"getFormatsByArgumentIndex","取格式按参数索引"},
            {"getFragment","取片段"},{"getFreeSpace","取释放空格"},{"getFrom","取自"},{"getGreatestMinimum","取最大最小"},
            {"getGregorianChange","取公历改变"},{"getGroupingSize","取分组大小"},{"getHardwareAddress","取硬件地址"},{"getHeaderFields","取头字段"},
            {"getHoldCount","取持有计数"},{"getHours","取小时"},{"getISO3Country","取三字母标准国家"},{"getISO3Language","取三字母标准语言"},
            {"getISOCountries","取国际标准国家"},{"getISOLanguages","取国际标准语言"},{"getIfModifiedSince","取如果已修改自"},{"getImplementationTitle","取实现标题"},
            {"getImplementationVendor","取实现厂商"},{"getImplementationVersion","取实现版本"},{"getInetAddress","取网际地址"},{"getInetAddresses","取网际地址"},
            {"getInputStream","取输入流"},{"getInstanceFollowRedirects","取实例跟随重定向"},{"getInteger","取整数"},{"getIntegerInstance","取整数实例"},
            {"getInterfaceAddresses","取接口地址"},{"getKeepAlive","取保留存活"},{"getLanguage","取语言"},{"getLargestMinimum","取最大最小"},
            {"getLastAccessTime","取最后访问时间"},{"getLastModified","取最后已修改"},{"getLastModifiedTime","取最后已修改时间"},{"getLayer","取层"},
            {"getLeastMaximum","取最少最大"},{"getLeastSignificantBits","取最少有效位"},{"getLength","取长度"},{"getLimits","取限制"},
            {"getLineInstance","取行实例"},{"getLineNumber","取行数"},{"getLocalAddress","取本地地址"},{"getLocalHost","取本地主机"},
            {"getLocalPatternChars","取本地模式字符"},{"getLocalPort","取本地端口"},{"getLocalSocketAddress","取本地套接字地址"},{"getLocalizedDateTimePattern","取本地化日期时间模式"},
            {"getLoopbackAddress","取回环地址"},{"getLowestSetBit","取最低设位"},{"getMTU","取最大传输单元"},{"getMaxBufferCapacity","取最大缓冲容量"},
            {"getMaxPriority","取最大优先级"},{"getMaximumFractionDigits","取最大小数部分数字"},{"getMaximumIntegerDigits","取最大整数数字"},{"getMethod","取方法"},
            {"getMethodName","取方法名称"},{"getMinimalDaysInFirstWeek","取最小天在首个周"},{"getMinimumFractionDigits","取最小小数部分数字"},{"getMinimumIntegerDigits","取最小整数数字"},
            {"getMinutes","取分钟"},{"getModuleName","取模块名称"},{"getModuleVersion","取模块版本"},{"getMonths","取月"},
            {"getMostSignificantBits","取最多有效位"},{"getMultiplier","取倍数"},{"getNameCount","取名称计数"},{"getNegativePrefix","取负前缀"},
            {"getNegativeSign","取负符号"},{"getNegativeSuffix","取负后缀"},{"getNetworkInterfaces","取网络接口"},{"getNextEntry","取下一个条目"},
            {"getNow","取当前"},{"getNumberFormat","取数格式"},{"getNumberInstance","取数实例"},{"getNumberOfDependents","取数创建依赖项"},
            {"getNumberOfSubscribers","取数创建订阅者"},{"getNumberWaiting","取数等待"},{"getNumericValue","取数字值"},{"getOOBInline","取越界内联"},
            {"getObjectInputFilter","取对象输入过滤"},{"getOutputStream","取输出流"},{"getOwner","取所有者"},{"getPackage","取包"},
            {"getPackages","取包"},{"getParallelism","取并行度"},{"getParentFile","取父级文件"},{"getParties","取各方"},
            {"getPathMatcher","取路径匹配器"},{"getPercentInstance","取百分比实例"},{"getPhase","取阶段"},{"getPool","取池"},
            {"getPoolSize","取池大小"},{"getPositivePrefix","取正前缀"},{"getPositiveSign","取正符号"},{"getPositiveSuffix","取正后缀"},
            {"getPosixFilePermissions","取可移植系统文件权限"},{"getPrecision","取精度"},{"getProtocol","取协议"},{"getQuery","取查询"},
            {"getQueueLength","取队列长度"},{"getQueuedSubmissionCount","取排队提交计数"},{"getQueuedTaskCount","取排队任务计数"},{"getRangeUnit","取范围单位"},
            {"getRawAuthority","取原始权限"},{"getRawFragment","取原始片段"},{"getRawPath","取原始路径"},{"getRawQuery","取原始查询"},
            {"getRawResult","取原始结果"},{"getRawSchemeSpecificPart","取原始方案具体部分"},{"getRawUserInfo","取原始用户信息"},{"getReadLockCount","取读锁计数"},
            {"getReadTimeout","取读超时"},{"getReceiveBufferSize","取接收缓冲大小"},{"getRef","取引用"},{"getRegisteredParties","取已注册各方"},
            {"getRemaining","取剩余"},{"getRemoteSocketAddress","取远程套接字地址"},{"getRequestMethod","取请求方法"},{"getRequestProperties","取请求属性"},
            {"getResolverFields","取解析器字段"},{"getResolverStyle","取解析器样式"},{"getResourceAsStream","取资源作为流"},{"getResponseCode","取响应码"},
            {"getResponseMessage","取响应消息"},{"getReuseAddress","取复用地址"},{"getRootDirectories","取根目录"},{"getRoundingMode","取舍入模式"},
            {"getRules","取规则"},{"getRunningThreadCount","取运行中线程计数"},{"getScheme","取方案"},{"getSchemeSpecificPart","取方案具体部分"},
            {"getScopeId","取作用域标识"},{"getScopedInterface","取作用域内接口"},{"getScript","取脚本"},{"getSeconds","取秒"},
            {"getSendBufferSize","取发送缓冲大小"},{"getSentenceInstance","取句子实例"},{"getSeparator","取分隔符"},{"getShortMonths","取短整数月"},
            {"getShortWeekdays","取短整数工作日"},{"getSize","取大小"},{"getSmallestMaximum","取最小最大"},{"getSoLinger","取于是逗留"},
            {"getSoTimeout","取于是超时"},{"getSocketAddress","取套接字地址"},{"getSpecificationTitle","取规范标题"},{"getSpecificationVendor","取规范厂商"},
            {"getSpecificationVersion","取规范版本"},{"getState","取状态"},{"getStealCount","取窃取计数"},{"getStrength","取强度"},
            {"getSubInterfaces","取子接口"},{"getSubscribers","取订阅者"},{"getSurplusQueuedTaskCount","取剩余排队任务计数"},{"getTcpNoDelay","取传输控制无延迟"},
            {"getText","取文本"},{"getThreadGroup","取线程组"},{"getTimeInMillis","取时间在毫秒"},{"getTimeLocal","取时间本地"},
            {"getTimezoneOffset","取时区偏移"},{"getTotalIn","取总计在"},{"getTotalOut","取总计输出"},{"getTotalSeconds","取总计秒"},
            {"getTotalSpace","取总计空格"},{"getTrafficClass","取流量类"},{"getType","取类型"},{"getURL","取网址"},
            {"getUnarrivedParties","取未到达各方"},{"getUncaughtExceptionHandler","取未捕获异常处理器"},{"getUnicodeLocaleAttributes","取统一码地区属性"},{"getUnicodeLocaleKeys","取统一码地区键"},
            {"getUnicodeLocaleType","取统一码地区类型"},{"getUnits","取单位"},{"getUsableSpace","取可用空格"},{"getUseCaches","取使用缓存"},
            {"getUserInfo","取用户信息"},{"getUserPrincipalLookupService","取用户主体查找服务"},{"getVariant","取变体"},{"getWaitQueueLength","取等待队列长度"},
            {"getWeekYear","取周年"},{"getWeekdays","取工作日"},{"getWeeksInWeekYear","取周在周年"},{"getWordInstance","取词实例"},
            {"getYears","取年"},{"getZeroDigit","取零数字"},{"getZoneStrings","取时区字符串"},{"groupCount","组计数"},
            {"groupingBy","分组按"},{"groupingByConcurrent","分组按并发"},{"guessContentTypeFromName","推测内容类型自名称"},{"guessContentTypeFromStream","推测内容类型自流"},
            {"handle","处理"},{"handleAsync","处理异步"},{"hasAnchoringBounds","含定位边界"},{"hasExtensions","含扩展"},
            {"hasMatch","含匹配"},{"hasMoreTokens","含更多标记"},{"hasNextBigDecimal","含下一个大十进制"},{"hasNextBigInteger","含下一个大整数"},
            {"hasNextBoolean","含下一个布尔"},{"hasNextByte","含下一个字节"},{"hasNextDouble","含下一个双精度"},{"hasNextFloat","含下一个单精度"},
            {"hasNextInt","含下一个整数"},{"hasNextLine","含下一个行"},{"hasNextLong","含下一个长"},{"hasNextShort","含下一个短整数"},
            {"hasPrevious","含前一个"},{"hasQueuedSubmissions","含排队提交集合"},{"hasQueuedThread","含排队线程"},{"hasQueuedThreads","含排队线程"},
            {"hasSubscribers","含订阅者"},{"hasTransparentBounds","含透明边界"},{"hasWaiters","含等待者"},{"hash","哈希"},
            {"headMap","头映射"},{"headSet","头集合"},{"helpQuiesce","帮助静止"},{"highSurrogate","高代理项"},
            {"highestOneBit","最高一位"},{"hitEnd","命中结束"},{"holdsLock","持有锁"},{"hypot","直角斜边"},
            {"identity","同一性"},{"ifPresentOrElse","如果存在或否则"},{"in","在"},{"inForkJoinPool","在分支连接池"},
            {"indexOfSubList","索引创建子列表"},{"inetAddresses","网际地址"},{"inflate","解压"},{"info","信息"},
            {"inheritedChannel","继承通道"},{"insertElementAt","在指定位置插入元素"},{"instant","瞬时"},{"intBitsToFloat","整数位转单精度"},
            {"intStream","整数流"},{"interrupted","已中断"},{"intersects","相交"},{"invoke","调用"},
            {"invokeAllUninterruptibly","调用全部不可中断"},{"ioException","输入输出异常"},{"isAbsolute","是否绝对"},{"isAlphabetic","是否字母"},
            {"isAnnotationPresent","是否注解存在"},{"isAnyLocalAddress","是否任意本地地址"},{"isAutoDetecting","是否自动检测"},{"isBmpCodePoint","是否位图码点"},
            {"isBound","是否边界"},{"isBoundary","是否边界"},{"isBroken","是否损坏"},{"isCancelled","是否已取消"},
            {"isCharsetDetected","是否字符集检测到的"},{"isClosed","是否已关闭"},{"isCompatibleWith","是否兼容带"},{"isCompletedAbnormally","是否已完成异常"},
            {"isCompletedExceptionally","是否已完成异常时"},{"isCompletedNormally","是否已完成通常"},{"isConnected","是否已连接"},{"isDateBased","是否日期基于"},
            {"isDecimalSeparatorAlwaysShown","是否十进制分隔符始终显示"},{"isDefined","是否定义"},{"isDeprecated","是否已弃用"},{"isDestroyed","是否已销毁"},
            {"isDigit","是否数字"},{"isDone","是否完成"},{"isDurationEstimated","是否时长估计"},{"isEmoji","是否表情"},
            {"isEmojiComponent","是否表情组件"},{"isEmojiModifier","是否表情修饰符"},{"isEmojiModifierBase","是否表情修饰符基"},{"isEmojiPresentation","是否表情呈现"},
            {"isExecutable","是否可执行"},{"isExported","是否导出"},{"isExtendedPictographic","是否扩展图形"},{"isFair","是否公平"},
            {"isFinite","是否有限"},{"isFixed","是否固定"},{"isGroupingUsed","是否分组已使用"},{"isHeldByCurrentThread","是否被当前线程持有"},
            {"isHidden","是否隐藏"},{"isHighSurrogate","是否高代理项"},{"isIPv4CompatibleAddress","是否兼容网际协议四地址"},{"isISOControl","是否国际标准控制"},
            {"isIdentifierIgnorable","是否标识符可忽略"},{"isIdeographic","是否表意"},{"isInfinite","是否无限"},{"isInputShutdown","是否输入关闭"},
            {"isIntValue","是否整数值"},{"isInterrupted","是否已中断"},{"isJavaIdentifierPart","是否语言标识符部分"},{"isJavaIdentifierStart","是否语言标识符开始"},
            {"isJavaLetter","是否语言字母"},{"isJavaLetterOrDigit","是否语言字母或数字"},{"isLeap","是否闰"},{"isLeapYear","是否闰年"},
            {"isLegalReplacement","是否合法替换"},{"isLenient","是否宽松"},{"isLetter","是否字母"},{"isLetterOrDigit","是否字母或数字"},
            {"isLinkLocalAddress","是否链接本地地址"},{"isLoaded","是否已加载"},{"isLockStamp","是否锁时间戳"},{"isLocked","是否已锁定"},
            {"isLoopback","是否回环"},{"isLoopbackAddress","是否回环地址"},{"isLowSurrogate","是否低代理项"},{"isLowerCase","是否小写情况"},
            {"isMCGlobal","是否消息摘要全局"},{"isMCLinkLocal","是否消息摘要链接本地"},{"isMCNodeLocal","是否消息摘要节点本地"},{"isMCOrgLocal","是否消息摘要组织本地"},
            {"isMCSiteLocal","是否消息摘要站点本地"},{"isMirrored","是否镜像"},{"isMulticastAddress","是否多播地址"},{"isNaN","是否非数"},
            {"isNamed","是否命名"},{"isNativeAccessEnabled","是否本地访问已启用"},{"isNativeMethod","是否本地方法"},{"isNegative","是否负"},
            {"isNull","是否空"},{"isOpaque","是否不透明"},{"isOpen","是否打开"},{"isOptimisticReadStamp","是否乐观读时间戳"},
            {"isOutputShutdown","是否输出关闭"},{"isParallel","是否并行"},{"isParseBigDecimal","是否解析大十进制"},{"isParseIntegerOnly","是否解析整数仅"},
            {"isPointToPoint","是否点转点"},{"isPositive","是否正"},{"isQuiescent","是否静止的"},{"isReachable","是否可达"},
            {"isReadLockStamp","是否读锁时间戳"},{"isReadLocked","是否读已锁定"},{"isReadable","是否可读"},{"isRegistered","是否已注册"},
            {"isRegularFile","是否正则文件"},{"isSameFile","是否相同文件"},{"isSealed","是否密封"},{"isShutdown","是否关闭"},
            {"isSiteLocalAddress","是否站点本地地址"},{"isSpace","是否空格"},{"isSpaceChar","是否空格字符"},{"isStrict","是否严格"},
            {"isSubscribed","是否已订阅"},{"isSupplementaryCodePoint","是否补充码点"},{"isSupportedBy","是否支持按"},{"isSurrogate","是否代理项"},
            {"isSurrogatePair","是否代理项对"},{"isSymbolicLink","是否符号链接"},{"isTerminal","是否终端"},{"isTerminated","是否已终止"},
            {"isTerminating","是否终止"},{"isTimeBased","是否时间基于"},{"isTitleCase","是否标题情况"},{"isUnicodeIdentifierPart","是否统一码标识符部分"},
            {"isUnicodeIdentifierStart","是否统一码标识符开始"},{"isUp","是否向上"},{"isUpperCase","是否大写情况"},{"isValid","是否有效"},
            {"isValidCodePoint","是否有效码点"},{"isValidDay","是否有效日"},{"isValidIntValue","是否有效整数值"},{"isValidMonthDay","是否有效月日"},
            {"isValidValue","是否有效值"},{"isValidYear","是否有效年"},{"isVirtual","是否虚拟"},{"isWeekDateSupported","是否周日期支持"},
            {"isWhitespace","是否空白"},{"isWritable","是否可写"},{"isWriteLockStamp","是否写锁时间戳"},{"isWriteLocked","是否写已锁定"},
            {"isZero","是否零"},{"joinGroup","连接组"},{"joining","连接"},{"keys","键"},
            {"lastIndexOfSubList","最后索引创建子列表"},{"lastModified","最后已修改"},{"lazySet","惰性设"},{"lazySubmit","惰性提交"},
            {"leaveGroup","离开组"},{"lengthOfMonth","长度创建月"},{"lengthOfYear","长度创建年"},{"lineno","行号"},
            {"listFiles","列表文件"},{"listRoots","列表根"},{"load","加载"},{"loadFromXML","从可扩展标记语言加载"},
            {"loadInstalled","加载已安装"},{"locale","地区"},{"localizedBy","本地化按"},{"lockInterruptibly","锁可中断"},
            {"logicalAnd","逻辑与"},{"logicalOr","逻辑或"},{"logicalXor","逻辑异或"},{"longBitsToDouble","长位转双精度"},
            {"longStream","长流"},{"lookup","查找"},{"lookupTag","查找标签"},{"lowSurrogate","低代理项"},
            {"lowerCaseMode","小写情况模式"},{"lowestOneBit","最低一位"},{"malformedInputAction","格式错误输入动作"},{"managedBlock","托管块"},
            {"mapMulti","映射多"},{"mapMultiToDouble","映射多转双精度"},{"mapMultiToInt","映射多转整数"},{"mapMultiToLong","映射多转长"},
            {"mapToDouble","映射转双精度"},{"mapToLong","映射转长"},{"mapToObj","映射转对象"},{"mapping","映射"},
            {"mappingCount","映射计数"},{"match","匹配"},{"matcher","匹配器"},{"maxBy","最大按"},
            {"maxBytesPerChar","最大字节每字符"},{"maxCharsPerByte","最大字符每字节"},{"maxLength","最大长度"},{"millis","毫秒"},
            {"minBy","最小按"},{"minLength","最小长度"},{"minimalCompletionStage","最小完成阶段"},{"mismatch","不匹配"},
            {"modPow","模幂"},{"move","移动"},{"movePointLeft","移动点左"},{"movePointRight","移动点右"},
            {"multipliedBy","已乘按"},{"multiplyFull","乘完整"},{"multiplyHigh","乘高"},{"nCopies","若干副本"},
            {"nameUUIDFromBytes","名称通用唯一标识自字节"},{"namedGroups","命名组"},{"naturalOrder","自然顺序"},{"navigableKeySet","可导航键集合"},
            {"needsDictionary","需要字典"},{"needsInput","需要输入"},{"negated","取反"},{"networkInterfaces","网络接口"},
            {"newBufferedReader","新建缓冲读取器"},{"newBufferedWriter","新建缓冲写入器"},{"newByteChannel","新建字节通道"},{"newCachedThreadPool","新建已缓存线程池"},
            {"newDecoder","新建解码器"},{"newDirectoryStream","新建目录流"},{"newEncoder","新建编码器"},{"newFileSystem","新建文件系统"},
            {"newFixedThreadPool","新建固定线程池"},{"newHashMap","新建哈希映射"},{"newHashSet","新建哈希集合"},{"newIncompleteFuture","新建不完整未来"},
            {"newInputStream","新建输入流"},{"newInstance","新建实例"},{"newKeySet","新建键设"},{"newLine","新建行"},
            {"newLinkedHashMap","新建链接哈希映射"},{"newLinkedHashSet","新建有序哈希集合"},{"newOutputStream","新建输出流"},{"newScheduledThreadPool","新建已排定线程池"},
            {"newSequencedSetFromMap","由映射新建有序集合"},{"newSetFromMap","由映射新建集合"},{"newSingleThreadExecutor","新建单个线程执行器"},{"newSingleThreadScheduledExecutor","新建单个线程已排定执行器"},
            {"newThread","新建线程"},{"newThreadPerTaskExecutor","新建线程每任务执行器"},{"newVirtualThreadPerTaskExecutor","新建虚拟线程每任务执行器"},{"newWatchService","新建监视服务"},
            {"newWeakHashMap","新建弱哈希映射"},{"newWorkStealingPool","新建工作窃取池"},{"nextAfter","下一个之后"},{"nextBigDecimal","下一个大十进制"},
            {"nextBigInteger","下一个大整数"},{"nextByte","下一个字节"},{"nextClearBit","下一个清空位"},{"nextExponential","下一个指数"},
            {"nextIndex","下一个索引"},{"nextLine","下一个行"},{"nextProbablePrime","下一个可能质数"},{"nextSetBit","下一个设位"},
            {"nextShort","下一个短整数"},{"nextToken","下一个标记"},{"node","节点"},{"nonNull","非空"},
            {"noneOf","无创建"},{"normalized","已规范化"},{"notExists","非存在"},{"null","空"},
            {"nullsFirst","空值首个"},{"nullsLast","空值最后"},{"numberOfLeadingZeros","数创建前导零"},{"numberOfTrailingZeros","数创建尾部零"},
            {"nval","数值"},{"obtrudeException","强行替换异常"},{"obtrudeValue","强行替换值"},{"ofDays","创建天"},
            {"ofDefaultLocale","创建默认地区"},{"ofEntries","创建条目"},{"ofEpochDay","创建纪元日"},{"ofEpochMilli","创建纪元毫"},
            {"ofEpochSecond","创建纪元秒"},{"ofHours","创建小时"},{"ofHoursMinutes","创建小时分钟"},{"ofHoursMinutesSeconds","创建小时分钟秒"},
            {"ofInstant","创建瞬时"},{"ofLiteral","创建字面量"},{"ofLocal","创建本地"},{"ofLocalizedDate","创建本地化日期"},
            {"ofLocalizedDateTime","创建本地化日期时间"},{"ofLocalizedPattern","创建本地化模式"},{"ofLocalizedTime","创建本地化时间"},{"ofMillis","创建毫秒"},
            {"ofMinutes","创建分钟"},{"ofMonths","创建月"},{"ofNanoOfDay","创建纳秒创建日"},{"ofNanos","创建纳秒"},
            {"ofOffset","创建偏移"},{"ofPattern","创建模式"},{"ofPlatform","创建平台"},{"ofPosixLiteral","创建可移植系统字面量"},
            {"ofSecondOfDay","创建秒创建日"},{"ofSeconds","创建秒"},{"ofStrict","创建严格"},{"ofTotalSeconds","创建总计秒"},
            {"ofWeeks","创建周"},{"ofYearDay","创建年日"},{"ofYears","创建年"},{"offerFirst","头部加入"},
            {"offerLast","尾部加入"},{"offsetByCodePoints","偏移按码点"},{"onClose","当关闭"},{"onMalformedInput","当格式错误输入"},
            {"onSpinWait","当自旋等待"},{"onUnmappableCharacter","当不可映射字符"},{"openConnection","打开连接"},{"openStream","打开流"},
            {"optionalEnd","可选结束"},{"optionalStart","可选开始"},{"orTimeout","或超时"},{"ordinaryChar","普通字符"},
            {"ordinaryChars","普通字符"},{"out","输出"},{"padNext","填充下一个"},{"parallel","并行"},
            {"parallelMultiply","并行乘"},{"parallelPrefix","并行前缀"},{"parallelSetAll","并行设全部"},{"parallelSort","并行排序"},
            {"parentOf","父级创建"},{"parseBest","解析最佳"},{"parseByte","解析字节"},{"parseCaseInsensitive","解析情况不敏感"},
            {"parseCaseSensitive","解析情况敏感"},{"parseDefaulting","解析默认"},{"parseLenient","解析宽松"},{"parseNumbers","解析数"},
            {"parseObject","解析对象"},{"parseServerAuthority","解析服务端权限"},{"parseShort","解析短整数"},{"parseStrict","解析严格"},
            {"parseUnresolved","解析未解析"},{"parseUnsignedInt","解析无符号整数"},{"parseUnsignedLong","解析无符号长"},{"parsedExcessDays","已解析超额天"},
            {"parsedLeapSecond","已解析闰秒"},{"partitioningBy","分区按"},{"pathSeparator","路径分隔符"},{"pathSeparatorChar","路径分隔符字符"},
            {"pid","进程标识"},{"pollEvents","轮询事件"},{"pollFirstEntry","轮询首个条目"},{"pollLastEntry","轮询最后条目"},
            {"powExact","幂精确"},{"preceding","之前"},{"precision","精度"},{"previousClearBit","前一个清空位"},
            {"previousDouble","前一个双精度"},{"previousIndex","前一个索引"},{"previousSetBit","前一个设位"},{"print","打印"},
            {"printf","格式化打印"},{"println","换行打印"},{"privilegedCallable","特权可调用"},{"privilegedCallableUsingCurrentClassLoader","特权可调用使用当前类加载器"},
            {"privilegedThreadFactory","特权线程工厂"},{"probablePrime","可能质数"},{"probeContentType","探测内容类型"},{"propertyNames","属性名称"},
            {"provider","提供者"},{"purge","清理"},{"pushBack","推送返回"},{"putFields","放入字段"},
            {"putNextEntry","放入下一个条目"},{"query","查询"},{"queryFrom","查询自"},{"quietlyComplete","静默完成"},
            {"quietlyInvoke","静默调用"},{"quietlyJoin","静默连接"},{"quietlyJoinUninterruptibly","静默连接不可中断"},{"quoteChar","引号字符"},
            {"quoteReplacement","引号替换"},{"radix","基数"},{"randomUUID","随机通用唯一标识"},{"rangeClosed","范围已关闭"},
            {"rangeRefinedBy","范围精确按"},{"readAttributes","读属性"},{"readFields","读字段"},{"readFully","读完全"},
            {"readLockInterruptibly","读锁可中断"},{"readObject","读对象"},{"readPassword","读密码"},{"readString","读字符串"},
            {"readSymbolicLink","读符号链接"},{"readUTF","读万国码"},{"readUnshared","读非共享"},{"readUnsignedByte","读无符号字节"},
            {"readUnsignedShort","读无符号短整数"},{"reader","读取器"},{"reduceEntries","归约条目"},{"reduceEntriesToDouble","归约条目转双精度"},
            {"reduceEntriesToInt","归约条目转整数"},{"reduceEntriesToLong","归约条目转长"},{"reduceKeys","归约键"},{"reduceKeysToDouble","归约键转双精度"},
            {"reduceKeysToInt","归约键转整数"},{"reduceKeysToLong","归约键转长"},{"reduceToDouble","归约转双精度"},{"reduceToInt","归约转整数"},
            {"reduceToLong","归约转长"},{"reduceValues","归约值"},{"reduceValuesToDouble","归约值转双精度"},{"reduceValuesToInt","归约值转整数"},
            {"reduceValuesToLong","归约值转长"},{"reducing","归约"},{"region","区域"},{"regionEnd","区域结束"},
            {"regionStart","区域开始"},{"register","注册"},{"registerValidation","注册验证"},{"reinitialize","重新初始化"},
            {"relativize","相对化"},{"release","释放"},{"reload","重新加载"},{"remainderUnsigned","余数无符号"},
            {"remainingCapacity","剩余容量"},{"removeAllElements","移除全部元素"},{"removeElementAt","移除指定位置元素"},{"removeFirstOccurrence","移除首个出现"},
            {"removeLastOccurrence","移除最后出现"},{"replaceWith","替换带"},{"replacement","替换"},{"requireEnd","必需结束"},
            {"requireNonNull","必需非空"},{"requireNonNullElse","必需非空否则"},{"requireNonNullElseGet","必需非空否则取"},{"resetSyntax","重置语法"},
            {"resolve","解析"},{"resolveConstantDesc","解析常量降序"},{"resolveSibling","解析兄弟"},{"resultNow","结果当前"},
            {"results","结果"},{"reverseBytes","反向字节"},{"rint","舍入"},{"rngs","随机数生成器"},
            {"roll","滚动"},{"rotateLeft","轮转左"},{"rotateRight","轮转右"},{"runAfterBoth","运行之后两者"},
            {"runAfterBothAsync","运行之后两者异步"},{"runAfterEither","运行之后二者之一"},{"runAfterEitherAsync","运行之后二者之一异步"},{"runAsync","运行异步"},
            {"sameFile","相同文件"},{"save","保存"},{"scalb","缩放"},{"scaleByPowerOfTen","标度按幂创建十"},
            {"schedule","调度"},{"scheduleAtFixedRate","调度指定固定率"},{"scheduleWithFixedDelay","调度带固定延迟"},{"search","搜索"},
            {"searchEntries","搜索条目"},{"searchKeys","搜索键"},{"searchValues","搜索值"},{"seek","定位"},
            {"sendUrgentData","发送紧急数据"},{"separator","分隔符"},{"separatorChar","分隔符字符"},{"sequencedEntrySet","有序条目集合"},
            {"sequencedKeySet","有序键集合"},{"sequencedValues","有序值集合"},{"sequential","顺序"},{"set2DigitYearStart","设2数字年开始"},
            {"setAddress","设地址"},{"setAll","设全部"},{"setAllowUserInteraction","设允许用户交互"},{"setAmPmStrings","设上午下午字符串"},
            {"setAttribute","设属性"},{"setAuthenticator","设认证器"},{"setBeginIndex","设开始索引"},{"setBroadcast","设广播"},
            {"setCalendar","设日历"},{"setCharAt","设字符指定"},{"setChoices","设选项"},{"setChunkedStreamingMode","设分块流式模式"},
            {"setCompressedSize","设已压缩大小"},{"setConnectTimeout","设连接超时"},{"setContentHandlerFactory","设内容处理器工厂"},{"setContextClassLoader","设上下文类加载器"},
            {"setCrc","设循环冗余校验"},{"setCreationTime","设创建时间"},{"setData","设数据"},{"setDatagramSocketImplFactory","设数据报套接字实现工厂"},
            {"setDate","设日期"},{"setDateFormatSymbols","设日期格式符号"},{"setDecimalFormatSymbols","设十进制格式符号"},{"setDecimalSeparatorAlwaysShown","设十进制分隔符始终显示"},
            {"setDecomposition","设分解"},{"setDefault","设默认"},{"setDefaultAllowUserInteraction","设默认允许用户交互"},{"setDefaultUncaughtExceptionHandler","设默认未捕获异常处理器"},
            {"setDefaultUseCaches","设默认使用缓存"},{"setDictionary","设字典"},{"setDoInput","设执行输入"},{"setDoOutput","设执行输出"},
            {"setEmptyValue","设空值"},{"setEndIndex","设结束索引"},{"setEras","设纪元"},{"setErr","设错误"},
            {"setErrorIndex","设错误索引"},{"setExecutable","设可执行"},{"setExtra","设额外"},{"setFileNameMap","设文件名称映射"},
            {"setFirstDayOfWeek","设首个日创建周"},{"setFixedLengthStreamingMode","设固定长度流式模式"},{"setFollowRedirects","设跟随重定向"},{"setForkJoinTaskTag","设分支连接任务标签"},
            {"setFormat","设格式"},{"setFormatByArgumentIndex","设格式按参数索引"},{"setFormats","设格式"},{"setFormatsByArgumentIndex","设格式按参数索引"},
            {"setGregorianChange","设公历改变"},{"setGroupingSize","设分组大小"},{"setGroupingUsed","设分组已使用"},{"setHours","设小时"},
            {"setIfModifiedSince","设如果已修改自"},{"setIndex","设索引"},{"setInput","设输入"},{"setInstanceFollowRedirects","设实例跟随重定向"},
            {"setKeepAlive","设保留存活"},{"setLastAccessTime","设最后访问时间"},{"setLastModified","设最后已修改"},{"setLastModifiedTime","设最后已修改时间"},
            {"setLenient","设宽松"},{"setLineNumber","设行数"},{"setLocalPatternChars","设本地模式字符"},{"setLocale","设地区"},
            {"setMaxPriority","设最大优先级"},{"setMaximumFractionDigits","设最大小数部分数字"},{"setMaximumIntegerDigits","设最大整数数字"},{"setMinimalDaysInFirstWeek","设最小天在首个周"},
            {"setMinimumFractionDigits","设最小小数部分数字"},{"setMinimumIntegerDigits","设最小整数数字"},{"setMinutes","设分钟"},{"setMonth","设月"},
            {"setMonths","设月"},{"setMultiplier","设倍数"},{"setNegativePrefix","设负前缀"},{"setNegativeSuffix","设负后缀"},
            {"setNumberFormat","设数格式"},{"setOOBInline","设越界内联"},{"setObjectInputFilter","设对象输入过滤"},{"setOut","设输出"},
            {"setOwner","设所有者"},{"setParallelism","设并行度"},{"setParseBigDecimal","设解析大十进制"},{"setParseIntegerOnly","设解析整数仅"},
            {"setPerformancePreferences","设性能偏好"},{"setPort","设端口"},{"setPositivePrefix","设正前缀"},{"setPositiveSuffix","设正后缀"},
            {"setPosixFilePermissions","设可移植系统文件权限"},{"setProperty","设属性"},{"setReadOnly","设读仅"},{"setReadTimeout","设读超时"},
            {"setReadable","设可读"},{"setReceiveBufferSize","设接收缓冲大小"},{"setRequestMethod","设请求方法"},{"setReuseAddress","设复用地址"},
            {"setRoundingMode","设舍入模式"},{"setSeconds","设秒"},{"setSeed","设种子"},{"setSendBufferSize","设发送缓冲大小"},
            {"setShortMonths","设短整数月"},{"setShortWeekdays","设短整数工作日"},{"setSoLinger","设于是逗留"},{"setSoTimeout","设于是超时"},
            {"setSocketAddress","设套接字地址"},{"setSocketFactory","设套接字工厂"},{"setSocketImplFactory","设套接字实现工厂"},{"setStrategy","设策略"},
            {"setStrength","设强度"},{"setStrict","设严格"},{"setTcpNoDelay","设传输控制无延迟"},{"setText","设文本"},
            {"setTimeInMillis","设时间在毫秒"},{"setTimeLocal","设时间本地"},{"setTrafficClass","设流量类"},{"setURLStreamHandlerFactory","设网址流处理器工厂"},
            {"setUncaughtExceptionHandler","设未捕获异常处理器"},{"setUseCaches","设使用缓存"},{"setWeekDate","设周日期"},{"setWeekdays","设工作日"},
            {"setWritable","设可写"},{"setYear","设年"},{"setZoneStrings","设时区字符串"},{"shutdownInput","关闭输入"},
            {"shutdownOutput","关闭输出"},{"singleton","单例"},{"singletonList","单例列表"},{"singletonMap","单例映射"},
            {"skipBytes","跳过字节"},{"slashSlashComments","斜杠斜杠注释"},{"slashStarComments","斜杠星注释"},{"splitAsStream","拆分作为流"},
            {"splitWithDelimiters","拆分带分隔符"},{"splits","拆分"},{"sqrtAndRemainder","平方根与余数"},{"startVirtualThread","开始虚拟线程"},
            {"state","状态"},{"store","存储"},{"storeToXML","存储转可扩展标记语言"},{"stringPropertyNames","字符串属性名称"},
            {"stripExtensions","去除扩展"},{"stripLeading","去除前导"},{"stripTrailing","去除尾部"},{"subInterfaces","子接口"},
            {"subMap","子映射"},{"subSequence","子序列"},{"subSet","子集合"},{"submitWithTimeout","提交带超时"},
            {"subpath","子路径"},{"subscribe","订阅"},{"subtractFrom","减自"},{"summarizingDouble","汇总双精度"},
            {"summarizingInt","汇总整数"},{"summarizingLong","汇总长"},{"summaryStatistics","摘要统计"},{"summingDouble","求和双精度"},
            {"summingInt","求和整数"},{"summingLong","求和长"},{"super","父类"},{"supplyAsync","供给异步"},
            {"supportedFileAttributeViews","支持文件属性视图"},{"supportedOptions","支持选项"},{"supportsMulticast","支持多播"},{"supportsNormalTermination","支持标准终止"},
            {"sval","字符串值"},{"synchronizedCollection","同步容器"},{"synchronizedList","同步列表"},{"synchronizedMap","同步映射"},
            {"synchronizedNavigableMap","同步可导航映射"},{"synchronizedNavigableSet","同步可导航设"},{"synchronizedSet","同步集合"},{"synchronizedSortedMap","同步已排序映射"},
            {"synchronizedSortedSet","同步已排序设"},{"system","系统"},{"systemDefault","系统默认"},{"systemDefaultZone","系统默认时区"},
            {"systemUTC","系统协调世界时"},{"tailMap","尾部映射"},{"tailSet","尾集合"},{"teeing","分流"},
            {"thenAccept","之后接受"},{"thenAcceptAsync","之后接受异步"},{"thenAcceptBoth","之后接受两者"},{"thenAcceptBothAsync","之后接受两者异步"},
            {"thenApply","之后应用"},{"thenApplyAsync","之后应用异步"},{"thenCombine","之后组合"},{"thenCombineAsync","之后组合异步"},
            {"thenComparing","之后比较"},{"thenComparingDouble","之后比较双精度"},{"thenComparingInt","之后比较整数"},{"thenComparingLong","之后比较长"},
            {"thenCompose","之后组合"},{"thenComposeAsync","之后组合异步"},{"thenRun","之后运行"},{"thenRunAsync","之后运行异步"},
            {"this","此"},{"threadId","线程标识"},{"tick","刻度"},{"tickMillis","刻度毫秒"},
            {"tickMinutes","刻度分钟"},{"tickSeconds","刻度秒"},{"timeLineOrder","时间行顺序"},{"timedJoin","定时连接"},
            {"timedWait","定时等待"},{"timestamp","时间戳"},{"toASCIIString","转美标码字符串"},{"toAbsolutePath","转绝对路径"},
            {"toBigInteger","转大整数"},{"toBigIntegerExact","转大整数精确"},{"toBinaryString","转二进制字符串"},{"toByteArray","转字节数组"},
            {"toChars","转字符"},{"toChronoUnit","转计时单位"},{"toCodePoint","转码点"},{"toCollection","转容器"},
            {"toCompletableFuture","转可完成未来"},{"toConcurrentMap","转并发映射"},{"toDays","转天"},{"toDaysPart","转天部分"},
            {"toDegrees","转度"},{"toEngineeringString","转工程字符串"},{"toEpochDay","转纪元日"},{"toEpochSecond","转纪元秒"},
            {"toExternalForm","转外部形式"},{"toFile","转文件"},{"toFormat","转格式"},{"toFormatter","转格式化器"},
            {"toGMTString","转格林尼治时间字符串"},{"toHexString","转十六进制文本"},{"toHours","转小时"},{"toHoursPart","转小时部分"},
            {"toIdentityString","转同一性字符串"},{"toIntExact","转整数精确"},{"toLanguageTag","转语言标签"},{"toLocalDate","转本地日期"},
            {"toLocalDateTime","转本地日期时间"},{"toLocalTime","转本地时间"},{"toLocaleString","转地区字符串"},{"toLocalizedPattern","转本地化模式"},
            {"toLongArray","转长数组"},{"toMap","转映射"},{"toMatchResult","转匹配结果"},{"toMicros","转微秒"},
            {"toMillis","转毫秒"},{"toMillisPart","转毫秒部分"},{"toMinutes","转分钟"},{"toMinutesPart","转分钟部分"},
            {"toNanoOfDay","转纳秒创建日"},{"toNanos","转纳秒"},{"toNanosPart","转纳秒部分"},{"toOctalString","转八进制字符串"},
            {"toOffsetDateTime","转偏移日期时间"},{"toOffsetTime","转偏移时间"},{"toPath","转路径"},{"toRadians","转弧度"},
            {"toRealPath","转实际路径"},{"toSecondOfDay","转秒创建日"},{"toSeconds","转秒"},{"toSecondsPart","转秒部分"},
            {"toSet","转设"},{"toTitleCase","转标题情况"},{"toTotalMonths","转总计月"},{"toURI","转资源标识"},
            {"toURL","转网址"},{"toUnmodifiableList","转不可修改列表"},{"toUnmodifiableMap","转不可修改映射"},{"toUnmodifiableSet","转不可修改设"},
            {"toUnsignedInt","转无符号整数"},{"toUnsignedLong","转无符号长"},{"toUnsignedString","转无符号字符串"},{"toUri","转资源标识"},
            {"toZonedDateTime","转带时区日期时间"},{"tokens","标记"},{"trimToSize","缩减容量"},{"tryAcquire","尝试获取"},
            {"tryConvertToOptimisticRead","尝试转换转乐观读"},{"tryConvertToReadLock","尝试转换转读锁"},{"tryConvertToWriteLock","尝试转换转写锁"},{"tryOptimisticRead","尝试乐观读"},
            {"tryReadLock","尝试读锁"},{"tryUnfork","尝试取消分支"},{"tryUnlockRead","尝试解锁读"},{"tryUnlockWrite","尝试解锁写"},
            {"tryWriteLock","尝试写锁"},{"ttype","标记类型"},{"type","类型"},{"typeName","类型名称"},
            {"uncaughtException","未捕获异常"},{"unconfigurableExecutorService","不可配置执行器服务"},{"unconfigurableScheduledExecutorService","不可配置已排定执行器服务"},{"unlockRead","解锁读"},
            {"unlockWrite","解锁写"},{"unmappableCharacterAction","不可映射字符动作"},{"unmodifiableCollection","不可修改容器"},{"unmodifiableList","不可修改列表"},
            {"unmodifiableMap","不可修改映射"},{"unmodifiableNavigableMap","不可修改可导航映射"},{"unmodifiableNavigableSet","不可修改可导航设"},{"unmodifiableSequencedCollection","不可修改有序容器"},
            {"unmodifiableSequencedMap","不可修改有序映射"},{"unmodifiableSequencedSet","不可修改有序设"},{"unmodifiableSet","不可修改集合"},{"unmodifiableSortedMap","不可修改已排序映射"},
            {"unmodifiableSortedSet","不可修改已排序设"},{"unordered","无序"},{"unread","未读"},{"unscaledValue","未缩放值"},
            {"unsignedMultiplyExact","无符号乘精确"},{"unsignedMultiplyHigh","无符号乘高"},{"unsignedPowExact","无符号幂精确"},{"useAnchoringBounds","使用定位边界"},
            {"useDelimiter","使用分隔符"},{"useLocale","使用地区"},{"usePattern","使用模式"},{"useProtocolVersion","使用协议版本"},
            {"useRadix","使用基数"},{"useTransparentBounds","使用透明边界"},{"usingProxy","使用代理"},{"validate","验证"},
            {"variant","变体"},{"version","版本"},{"walk","遍历"},{"walkFileTree","遍历文件树"},
            {"watchable","可监视"},{"weakCompareAndSet","弱比较与设"},{"weakCompareAndSetAcquire","弱比较与设获取"},{"weakCompareAndSetPlain","弱比较与设纯文本"},
            {"weakCompareAndSetRelease","弱比较与设释放"},{"weakCompareAndSetVolatile","弱比较与设易变"},{"weekBasedYear","周基于年"},{"weekOfMonth","周创建月"},
            {"weekOfWeekBasedYear","周创建周基于年"},{"weekOfYear","周创建年"},{"whenComplete","当完成"},{"whenCompleteAsync","当完成异步"},
            {"where","其中"},{"whitespaceChars","空白字符"},{"withChronology","带纪年"},{"withDayOfMonth","带日创建月"},
            {"withDayOfYear","带日创建年"},{"withDays","带天"},{"withDecimalSeparator","带十进制分隔符"},{"withDecimalStyle","带十进制样式"},
            {"withEarlierOffsetAtOverlap","带更早偏移指定重叠"},{"withFixedOffsetZone","带固定偏移时区"},{"withHour","带小时"},{"withInitial","带初始"},
            {"withLaterOffsetAtOverlap","带之后偏移指定重叠"},{"withLocale","带地区"},{"withMinute","带分钟"},{"withMonth","带月"},
            {"withMonths","带月"},{"withNano","带纳秒"},{"withNanos","带纳秒"},{"withNegativeSign","带负符号"},
            {"withOffsetSameInstant","带偏移相同瞬时"},{"withOffsetSameLocal","带偏移相同本地"},{"withPositiveSign","带正符号"},{"withResolverFields","带解析器字段"},
            {"withResolverStyle","带解析器样式"},{"withSecond","带秒"},{"withSeconds","带秒"},{"withYear","带年"},
            {"withYears","带年"},{"withZeroDigit","带零数字"},{"withZoneSameInstant","带时区相同瞬时"},{"withZoneSameLocal","带时区相同本地"},
            {"wordChars","词字符"},{"writeChars","写字符"},{"writeFields","写字段"},{"writeLockInterruptibly","写锁可中断"},
            {"writeObject","写对象"},{"writeString","写字符串"},{"writeTo","写转"},{"writeUTF","写万国码"},
            {"writeUnshared","写非共享"},{"writer","写入器"},
        };
        for (String[] p : pairs) EXTRA_CN.putIfAbsent(p[0], p[1]);
    }

    /** 简单类名 → 中文：由 CLASS_CN 反查，供无法用 resolveFqn 消歧的简单名兜底。 */
    private static final Map<String, String> SIMPLE_CLASS_CN = new LinkedHashMap<>();
    static {
        for (Map.Entry<String, String> e : CLASS_CN.entrySet()) {
            String fqn = e.getKey();
            String simple = fqn.substring(fqn.lastIndexOf('.') + 1);
            SIMPLE_CLASS_CN.putIfAbsent(simple, e.getValue());
        }
    }

    private static final Map<String, String> PACKAGE_CN = new LinkedHashMap<>();
    static {
        String[][] pairs = {
            {"java.lang","核心语言"},{"java.util","集合与工具"},
            {"java.util.function","函数式接口"},{"java.util.concurrent","并发"},
            {"java.util.concurrent.atomic","原子类"},{"java.util.concurrent.locks","锁"},
            {"java.util.stream","流"},{"java.util.zip","压缩"},
            {"java.util.regex","正则"},{"java.util.jar","归档包"},
            {"java.io","输入输出"},{"java.nio","新输入输出"},
            {"java.nio.file","文件路径"},{"java.nio.charset","字符集"},
            {"java.net","网络"},{"java.net.http","超文本传输"},
            {"java.time","日期时间"},{"java.time.temporal","时间单位"},
            {"java.time.format","时间格式"},{"java.text","文本格式"},
            {"java.math","数学精度"},{"java.awt","图形界面"},
            {"java.rmi","远程调用"},
            {"java.util.random","随机数"}
        };
        for (String[] p : pairs) PACKAGE_CN.put(p[0], p[1]);
    }

    /** 方法名 → 中文；先查基础词典，再查逐条补全的 API 词条。 */
    public static String chineseMethod(String method) {
        String cn = METHOD_CN.get(method);
        if (cn != null) return cn;
        cn = EXTRA_CN.get(method);
        return cn == null ? method : cn;
    }

    /** 字段名 / 常量名 → 中文；与 API 词条同一自识别数据源。 */
    public static String chineseField(String field) {
        String cn = EXTRA_CN.get(field);
        return cn == null ? field : cn;
    }

    /** 简单类名 → 中文；仅作 resolveFqn 无法消歧时的兜底。 */
    public static String chineseSimpleClass(String simple) {
        String cn = SIMPLE_CLASS_CN.get(simple);
        return cn == null ? simple : cn;
    }

    /** 类全限定名 → 中文；未收录时取简单类名 */
    public static String chineseClass(String fqn) {
        String cn = CLASS_CN.get(fqn);
        if (cn != null) return cn;
        int i = fqn.lastIndexOf('.');
        return i >= 0 ? fqn.substring(i + 1) : fqn;
    }

    /** 包名 → 中文库名；未收录时返回包名 */
    public static String chinesePackage(String pkg) {
        String cn = PACKAGE_CN.get(pkg);
        return cn == null ? pkg : cn;
    }

    public static boolean hasChineseMethod(String method) { return METHOD_CN.containsKey(method); }
}
