package clbc.multilang;

/**
 * 多语言统一门面：UI 只需按文件名或语言 id 取后端，不再写死 Java。
 * 新语言只要：① 写一个 LangSpec；② 实现 LangBackend；③ 在 of()/byExtension() 登记。
 * 画布 / 渲染 / 吸附 / 工具箱 / 语言文件 / 注释便签全部零改动。
 */
public final class MultiLang {

    private MultiLang() {}

    public static LangBackend java() { return LangBackends.java(); }
    public static LangBackend python() { return LangBackends.python(); }
    public static LangBackend cpp() { return LangBackends.cpp(); }
    public static LangBackend csharp() { return LangBackends.csharp(); }

    public static LangBackend of(String id) { return LangBackends.of(id); }
    public static LangBackend byFile(String fileName) { return of(LangSpec.byExtension(fileName).id); }

    public static String[] supportedIds() { return new String[]{"java", "python", "cpp", "csharp"}; }

    /** 是否支持某扩展名 */
    public static boolean supports(String fileName) {
        LangSpec s = LangSpec.byExtension(fileName);
        for (String id : supportedIds()) if (id.equals(s.id)) return true;
        return false;
    }

    /** 源码 → 积木 → 源码 一步往返 */
    public static String roundTrip(String code, String langId) { return of(langId).roundTrip(code); }
}
