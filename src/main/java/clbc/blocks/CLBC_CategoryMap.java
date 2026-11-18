package clbc.blocks;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JDK 自带库 → 工具箱分类 的硬编码映射（356 条，覆盖 CLBC_BuiltinCatalog 全部支持类）。
 *
 * 设计约定：分类是稳定语义，必须由人逐条审定并写死，禁止运行时按类名 contains()
 * 启发式猜测（旧实现把 194 个类全丢进"系统"，且随 JDK 升级漂移）。本表是纯 JDK
 * 代码，不读任何 json / 不使用任何外部库，离线、headless 均可确定工作。
 *
 * 新增支持类：在 CLBC_BuiltinCatalog 加入后，补一条 put 即可；漏写的类由
 * {@link CLBC_BlockLibrary#resolveCategory} 兜底进"系统"，不会报错。
 */
public final class CLBC_CategoryMap {
    private CLBC_CategoryMap() {}

    private static final Map<String, String> MAP = build();

    private static Map<String, String> build() {
        Map<String, String> m = new LinkedHashMap<>();

        // ---- java.io ----
        m.put("java.io.BufferedInputStream", "文件IO");
        m.put("java.io.BufferedOutputStream", "文件IO");
        m.put("java.io.BufferedReader", "文件IO");
        m.put("java.io.BufferedWriter", "文件IO");
        m.put("java.io.ByteArrayInputStream", "文件IO");
        m.put("java.io.ByteArrayOutputStream", "文件IO");
        m.put("java.io.CharArrayReader", "文件IO");
        m.put("java.io.CharArrayWriter", "文件IO");
        m.put("java.io.Console", "文件IO");
        m.put("java.io.DataInputStream", "文件IO");
        m.put("java.io.DataOutputStream", "文件IO");
        m.put("java.io.EOFException", "文件IO");
        m.put("java.io.File", "文件IO");
        m.put("java.io.FileFilter", "文件IO");
        m.put("java.io.FileInputStream", "文件IO");
        m.put("java.io.FileNotFoundException", "文件IO");
        m.put("java.io.FileOutputStream", "文件IO");
        m.put("java.io.FileReader", "文件IO");
        m.put("java.io.FileWriter", "文件IO");
        m.put("java.io.FilenameFilter", "文件IO");
        m.put("java.io.FilterInputStream", "文件IO");
        m.put("java.io.FilterOutputStream", "文件IO");
        m.put("java.io.IOException", "文件IO");
        m.put("java.io.InputStream", "文件IO");
        m.put("java.io.InputStreamReader", "文件IO");
        m.put("java.io.LineNumberReader", "文件IO");
        m.put("java.io.ObjectInputStream", "文件IO");
        m.put("java.io.ObjectOutputStream", "文件IO");
        m.put("java.io.OutputStream", "文件IO");
        m.put("java.io.OutputStreamWriter", "文件IO");
        m.put("java.io.PipedInputStream", "文件IO");
        m.put("java.io.PipedOutputStream", "文件IO");
        m.put("java.io.PrintStream", "文件IO");
        m.put("java.io.PrintWriter", "文件IO");
        m.put("java.io.PushbackInputStream", "文件IO");
        m.put("java.io.PushbackReader", "文件IO");
        m.put("java.io.RandomAccessFile", "文件IO");
        m.put("java.io.Reader", "文件IO");
        m.put("java.io.SequenceInputStream", "文件IO");
        m.put("java.io.StreamTokenizer", "文件IO");
        m.put("java.io.StringReader", "文件IO");
        m.put("java.io.StringWriter", "文件IO");
        m.put("java.io.Writer", "文件IO");

        // ---- java.lang ----
        m.put("java.lang.AbstractMethodError", "系统");
        m.put("java.lang.Appendable", "系统");
        m.put("java.lang.ArithmeticException", "系统");
        m.put("java.lang.ArrayIndexOutOfBoundsException", "系统");
        m.put("java.lang.ArrayStoreException", "系统");
        m.put("java.lang.AssertionError", "系统");
        m.put("java.lang.AutoCloseable", "系统");
        m.put("java.lang.Boolean", "数学");
        m.put("java.lang.BootstrapMethodError", "系统");
        m.put("java.lang.Byte", "数学");
        m.put("java.lang.CharSequence", "字符串");
        m.put("java.lang.Character", "字符串");
        m.put("java.lang.ClassCastException", "系统");
        m.put("java.lang.ClassCircularityError", "系统");
        m.put("java.lang.ClassFormatError", "系统");
        m.put("java.lang.ClassNotFoundException", "系统");
        m.put("java.lang.CloneNotSupportedException", "系统");
        m.put("java.lang.Cloneable", "系统");
        m.put("java.lang.Comparable", "系统");
        m.put("java.lang.Double", "数学");
        m.put("java.lang.Enum", "系统");
        m.put("java.lang.EnumConstantNotPresentException", "系统");
        m.put("java.lang.Error", "系统");
        m.put("java.lang.Exception", "系统");
        m.put("java.lang.ExceptionInInitializerError", "系统");
        m.put("java.lang.Float", "数学");
        m.put("java.lang.IllegalAccessError", "系统");
        m.put("java.lang.IllegalAccessException", "系统");
        m.put("java.lang.IllegalArgumentException", "系统");
        m.put("java.lang.IllegalCallerException", "系统");
        m.put("java.lang.IllegalMonitorStateException", "系统");
        m.put("java.lang.IllegalStateException", "系统");
        m.put("java.lang.IllegalThreadStateException", "系统");
        m.put("java.lang.IncompatibleClassChangeError", "系统");
        m.put("java.lang.IndexOutOfBoundsException", "系统");
        m.put("java.lang.InstantiationError", "系统");
        m.put("java.lang.InstantiationException", "系统");
        m.put("java.lang.Integer", "数学");
        m.put("java.lang.InternalError", "系统");
        m.put("java.lang.InterruptedException", "系统");
        m.put("java.lang.Iterable", "系统");
        m.put("java.lang.LinkageError", "系统");
        m.put("java.lang.Long", "数学");
        m.put("java.lang.MatchException", "系统");
        m.put("java.lang.Math", "数学");
        m.put("java.lang.Module", "系统");
        m.put("java.lang.NegativeArraySizeException", "系统");
        m.put("java.lang.NoClassDefFoundError", "系统");
        m.put("java.lang.NoSuchFieldError", "系统");
        m.put("java.lang.NoSuchFieldException", "系统");
        m.put("java.lang.NoSuchMethodError", "系统");
        m.put("java.lang.NoSuchMethodException", "系统");
        m.put("java.lang.NullPointerException", "系统");
        m.put("java.lang.Number", "数学");
        m.put("java.lang.NumberFormatException", "系统");
        m.put("java.lang.Object", "系统");
        m.put("java.lang.OutOfMemoryError", "系统");
        m.put("java.lang.Package", "系统");
        m.put("java.lang.ProcessHandle", "系统");
        m.put("java.lang.Readable", "系统");
        m.put("java.lang.Record", "系统");
        m.put("java.lang.ReflectiveOperationException", "系统");
        m.put("java.lang.Runnable", "系统");
        m.put("java.lang.RuntimeException", "系统");
        m.put("java.lang.ScopedValue", "系统");
        m.put("java.lang.SecurityException", "系统");
        m.put("java.lang.Short", "数学");
        m.put("java.lang.StackOverflowError", "系统");
        m.put("java.lang.StackTraceElement", "系统");
        m.put("java.lang.StackWalker", "系统");
        m.put("java.lang.StrictMath", "数学");
        m.put("java.lang.String", "字符串");
        m.put("java.lang.StringBuffer", "字符串");
        m.put("java.lang.StringBuilder", "字符串");
        m.put("java.lang.StringIndexOutOfBoundsException", "系统");
        m.put("java.lang.System", "系统");
        m.put("java.lang.Thread", "系统");
        m.put("java.lang.ThreadGroup", "系统");
        m.put("java.lang.ThreadLocal", "系统");
        m.put("java.lang.Throwable", "系统");
        m.put("java.lang.TypeNotPresentException", "系统");
        m.put("java.lang.UnknownError", "系统");
        m.put("java.lang.UnsatisfiedLinkError", "系统");
        m.put("java.lang.UnsupportedClassVersionError", "系统");
        m.put("java.lang.UnsupportedOperationException", "系统");
        m.put("java.lang.VerifyError", "系统");
        m.put("java.lang.VirtualMachineError", "系统");
        m.put("java.lang.Void", "数学");

        // ---- java.math ----
        m.put("java.math.BigDecimal", "数学");
        m.put("java.math.BigInteger", "数学");
        m.put("java.math.MathContext", "数学");
        m.put("java.math.RoundingMode", "数学");

        // ---- java.net ----
        m.put("java.net.DatagramPacket", "网络");
        m.put("java.net.DatagramSocket", "网络");
        m.put("java.net.HttpURLConnection", "网络");
        m.put("java.net.Inet4Address", "网络");
        m.put("java.net.Inet6Address", "网络");
        m.put("java.net.InetAddress", "网络");
        m.put("java.net.MalformedURLException", "网络");
        m.put("java.net.NetworkInterface", "网络");
        m.put("java.net.Proxy", "网络");
        m.put("java.net.ServerSocket", "网络");
        m.put("java.net.Socket", "网络");
        m.put("java.net.SocketException", "网络");
        m.put("java.net.URI", "网络");
        m.put("java.net.URL", "网络");
        m.put("java.net.URLConnection", "网络");
        m.put("java.net.UnknownHostException", "网络");

        // ---- java.nio ----
        m.put("java.nio.Buffer", "文件IO");
        m.put("java.nio.ByteBuffer", "文件IO");
        m.put("java.nio.CharBuffer", "文件IO");
        m.put("java.nio.DoubleBuffer", "文件IO");
        m.put("java.nio.FloatBuffer", "文件IO");
        m.put("java.nio.IntBuffer", "文件IO");
        m.put("java.nio.LongBuffer", "文件IO");
        m.put("java.nio.MappedByteBuffer", "文件IO");
        m.put("java.nio.ShortBuffer", "文件IO");

        // ---- java.nio.charset ----
        m.put("java.nio.charset.Charset", "文本格式");
        m.put("java.nio.charset.CharsetDecoder", "文本格式");
        m.put("java.nio.charset.CharsetEncoder", "文本格式");
        m.put("java.nio.charset.StandardCharsets", "文本格式");

        // ---- java.nio.file ----
        m.put("java.nio.file.DirectoryStream", "文件IO");
        m.put("java.nio.file.FileSystem", "文件IO");
        m.put("java.nio.file.FileSystems", "文件IO");
        m.put("java.nio.file.Files", "文件IO");
        m.put("java.nio.file.LinkOption", "文件IO");
        m.put("java.nio.file.Path", "文件IO");
        m.put("java.nio.file.PathMatcher", "文件IO");
        m.put("java.nio.file.Paths", "文件IO");
        m.put("java.nio.file.StandardCopyOption", "文件IO");
        m.put("java.nio.file.StandardOpenOption", "文件IO");
        m.put("java.nio.file.WatchKey", "文件IO");
        m.put("java.nio.file.WatchService", "文件IO");

        // ---- java.text ----
        m.put("java.text.BreakIterator", "文本格式");
        m.put("java.text.ChoiceFormat", "文本格式");
        m.put("java.text.Collator", "文本格式");
        m.put("java.text.DateFormat", "文本格式");
        m.put("java.text.DateFormatSymbols", "文本格式");
        m.put("java.text.DecimalFormat", "文本格式");
        m.put("java.text.FieldPosition", "文本格式");
        m.put("java.text.MessageFormat", "文本格式");
        m.put("java.text.NumberFormat", "文本格式");
        m.put("java.text.ParsePosition", "文本格式");
        m.put("java.text.SimpleDateFormat", "文本格式");

        // ---- java.time ----
        m.put("java.time.Clock", "日期时间");
        m.put("java.time.DayOfWeek", "日期时间");
        m.put("java.time.Duration", "日期时间");
        m.put("java.time.Instant", "日期时间");
        m.put("java.time.LocalDate", "日期时间");
        m.put("java.time.LocalDateTime", "日期时间");
        m.put("java.time.LocalTime", "日期时间");
        m.put("java.time.Month", "日期时间");
        m.put("java.time.MonthDay", "日期时间");
        m.put("java.time.OffsetDateTime", "日期时间");
        m.put("java.time.OffsetTime", "日期时间");
        m.put("java.time.Period", "日期时间");
        m.put("java.time.Year", "日期时间");
        m.put("java.time.YearMonth", "日期时间");
        m.put("java.time.ZoneId", "日期时间");
        m.put("java.time.ZoneOffset", "日期时间");
        m.put("java.time.ZonedDateTime", "日期时间");

        // ---- java.time.format ----
        m.put("java.time.format.DateTimeFormatter", "日期时间");
        m.put("java.time.format.DateTimeFormatterBuilder", "日期时间");
        m.put("java.time.format.DecimalStyle", "日期时间");
        m.put("java.time.format.ResolverStyle", "日期时间");
        m.put("java.time.format.SignStyle", "日期时间");

        // ---- java.time.temporal ----
        m.put("java.time.temporal.ChronoUnit", "日期时间");
        m.put("java.time.temporal.Temporal", "日期时间");
        m.put("java.time.temporal.TemporalAccessor", "日期时间");
        m.put("java.time.temporal.TemporalAdjuster", "日期时间");
        m.put("java.time.temporal.TemporalAmount", "日期时间");
        m.put("java.time.temporal.TemporalField", "日期时间");
        m.put("java.time.temporal.TemporalQuery", "日期时间");
        m.put("java.time.temporal.TemporalUnit", "日期时间");
        m.put("java.time.temporal.ValueRange", "日期时间");
        m.put("java.time.temporal.WeekFields", "日期时间");

        // ---- java.util ----
        m.put("java.util.ArrayDeque", "数据");
        m.put("java.util.ArrayList", "数据");
        m.put("java.util.Arrays", "数据");
        m.put("java.util.BitSet", "数据");
        m.put("java.util.Calendar", "数据");
        m.put("java.util.Collection", "数据");
        m.put("java.util.Collections", "数据");
        m.put("java.util.Comparator", "数据");
        m.put("java.util.Date", "数据");
        m.put("java.util.Deque", "数据");
        m.put("java.util.EnumMap", "数据");
        m.put("java.util.EnumSet", "数据");
        m.put("java.util.Formatter", "字符串");
        m.put("java.util.GregorianCalendar", "数据");
        m.put("java.util.HashMap", "数据");
        m.put("java.util.HashSet", "数据");
        m.put("java.util.Hashtable", "数据");
        m.put("java.util.IdentityHashMap", "数据");
        m.put("java.util.Iterator", "数据");
        m.put("java.util.LinkedHashMap", "数据");
        m.put("java.util.LinkedHashSet", "数据");
        m.put("java.util.LinkedList", "数据");
        m.put("java.util.List", "数据");
        m.put("java.util.ListIterator", "数据");
        m.put("java.util.Locale", "数据");
        m.put("java.util.Map", "数据");
        m.put("java.util.NavigableMap", "数据");
        m.put("java.util.NavigableSet", "数据");
        m.put("java.util.Objects", "数据");
        m.put("java.util.Optional", "数据");
        m.put("java.util.OptionalDouble", "数据");
        m.put("java.util.OptionalInt", "数据");
        m.put("java.util.OptionalLong", "数据");
        m.put("java.util.PriorityQueue", "数据");
        m.put("java.util.Properties", "数据");
        m.put("java.util.Queue", "数据");
        m.put("java.util.Random", "数学");
        m.put("java.util.Scanner", "数据");
        m.put("java.util.SequencedCollection", "数据");
        m.put("java.util.SequencedMap", "数据");
        m.put("java.util.SequencedSet", "数据");
        m.put("java.util.ServiceLoader", "数据");
        m.put("java.util.Set", "数据");
        m.put("java.util.SortedMap", "数据");
        m.put("java.util.SortedSet", "数据");
        m.put("java.util.SplittableRandom", "数学");
        m.put("java.util.Stack", "数据");
        m.put("java.util.StringJoiner", "字符串");
        m.put("java.util.StringTokenizer", "字符串");
        m.put("java.util.Timer", "数据");
        m.put("java.util.TreeMap", "数据");
        m.put("java.util.TreeSet", "数据");
        m.put("java.util.UUID", "数据");
        m.put("java.util.Vector", "数据");
        m.put("java.util.WeakHashMap", "数据");

        // ---- java.util.concurrent ----
        m.put("java.util.concurrent.AbstractExecutorService", "并发");
        m.put("java.util.concurrent.ArrayBlockingQueue", "并发");
        m.put("java.util.concurrent.BlockingQueue", "并发");
        m.put("java.util.concurrent.Callable", "并发");
        m.put("java.util.concurrent.CompletableFuture", "并发");
        m.put("java.util.concurrent.ConcurrentHashMap", "并发");
        m.put("java.util.concurrent.ConcurrentLinkedQueue", "并发");
        m.put("java.util.concurrent.CopyOnWriteArrayList", "并发");
        m.put("java.util.concurrent.CountDownLatch", "并发");
        m.put("java.util.concurrent.CyclicBarrier", "并发");
        m.put("java.util.concurrent.Exchanger", "并发");
        m.put("java.util.concurrent.Executor", "并发");
        m.put("java.util.concurrent.ExecutorService", "并发");
        m.put("java.util.concurrent.Executors", "并发");
        m.put("java.util.concurrent.Flow", "并发");
        m.put("java.util.concurrent.ForkJoinPool", "并发");
        m.put("java.util.concurrent.ForkJoinTask", "并发");
        m.put("java.util.concurrent.Future", "并发");
        m.put("java.util.concurrent.FutureTask", "并发");
        m.put("java.util.concurrent.LinkedBlockingQueue", "并发");
        m.put("java.util.concurrent.Phaser", "并发");
        m.put("java.util.concurrent.PriorityBlockingQueue", "并发");
        m.put("java.util.concurrent.RecursiveAction", "并发");
        m.put("java.util.concurrent.RecursiveTask", "并发");
        m.put("java.util.concurrent.ScheduledExecutorService", "并发");
        m.put("java.util.concurrent.Semaphore", "并发");
        m.put("java.util.concurrent.SubmissionPublisher", "并发");
        m.put("java.util.concurrent.ThreadFactory", "并发");
        m.put("java.util.concurrent.TimeUnit", "并发");

        // ---- java.util.concurrent.atomic ----
        m.put("java.util.concurrent.atomic.AtomicBoolean", "并发");
        m.put("java.util.concurrent.atomic.AtomicInteger", "并发");
        m.put("java.util.concurrent.atomic.AtomicLong", "并发");
        m.put("java.util.concurrent.atomic.AtomicReference", "并发");

        // ---- java.util.concurrent.locks ----
        m.put("java.util.concurrent.locks.Lock", "并发");
        m.put("java.util.concurrent.locks.ReadWriteLock", "并发");
        m.put("java.util.concurrent.locks.ReentrantLock", "并发");
        m.put("java.util.concurrent.locks.StampedLock", "并发");

        // ---- java.util.function ----
        m.put("java.util.function.BiConsumer", "函数式");
        m.put("java.util.function.BiFunction", "函数式");
        m.put("java.util.function.BiPredicate", "函数式");
        m.put("java.util.function.BinaryOperator", "函数式");
        m.put("java.util.function.BooleanSupplier", "函数式");
        m.put("java.util.function.Consumer", "函数式");
        m.put("java.util.function.DoubleBinaryOperator", "函数式");
        m.put("java.util.function.DoubleUnaryOperator", "函数式");
        m.put("java.util.function.Function", "函数式");
        m.put("java.util.function.IntBinaryOperator", "函数式");
        m.put("java.util.function.IntConsumer", "函数式");
        m.put("java.util.function.IntFunction", "函数式");
        m.put("java.util.function.IntSupplier", "函数式");
        m.put("java.util.function.IntUnaryOperator", "函数式");
        m.put("java.util.function.LongConsumer", "函数式");
        m.put("java.util.function.LongFunction", "函数式");
        m.put("java.util.function.LongSupplier", "函数式");
        m.put("java.util.function.LongUnaryOperator", "函数式");
        m.put("java.util.function.Predicate", "函数式");
        m.put("java.util.function.Supplier", "函数式");
        m.put("java.util.function.ToDoubleFunction", "函数式");
        m.put("java.util.function.ToIntFunction", "函数式");
        m.put("java.util.function.UnaryOperator", "函数式");

        // ---- java.util.random ----
        m.put("java.util.random.RandomGenerator", "函数式");

        // ---- java.util.regex ----
        m.put("java.util.regex.Matcher", "正则");
        m.put("java.util.regex.Pattern", "正则");

        // ---- java.util.stream ----
        m.put("java.util.stream.BaseStream", "函数式");
        m.put("java.util.stream.Collectors", "函数式");
        m.put("java.util.stream.DoubleStream", "函数式");
        m.put("java.util.stream.IntStream", "函数式");
        m.put("java.util.stream.LongStream", "函数式");
        m.put("java.util.stream.Stream", "函数式");
        m.put("java.util.stream.StreamSupport", "函数式");

        // ---- java.util.zip ----
        m.put("java.util.zip.Adler32", "压缩");
        m.put("java.util.zip.CRC32", "压缩");
        m.put("java.util.zip.CheckedInputStream", "压缩");
        m.put("java.util.zip.CheckedOutputStream", "压缩");
        m.put("java.util.zip.Deflater", "压缩");
        m.put("java.util.zip.GZIPInputStream", "压缩");
        m.put("java.util.zip.GZIPOutputStream", "压缩");
        m.put("java.util.zip.Inflater", "压缩");
        m.put("java.util.zip.ZipEntry", "压缩");
        m.put("java.util.zip.ZipFile", "压缩");
        m.put("java.util.zip.ZipInputStream", "压缩");
        m.put("java.util.zip.ZipOutputStream", "压缩");
        return Collections.unmodifiableMap(m);
    }

    /** 查类全限定名的分类；未收录返回 null（由调用方兜底） */
    public static String categoryOf(String fqn) {
        return MAP.get(fqn);
    }

    /** 全部硬编码条目数（自测用） */
    public static int size() { return MAP.size(); }
}
