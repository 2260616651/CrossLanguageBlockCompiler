package clbc.ui;

/**
 * 一次"运行"的实例数据。
 * id / projectPath 不可变；status / output / process / endTime 随运行过程变化。
 * 由 CLBC_Renderer 维护，按项目分组存于运行历史。
 */
public class CLBC_RunInstance {
    public final int id;
    public final String projectPath;
    public Process process;
    public String status = "运行中";
    public final StringBuilder output = new StringBuilder();
    public final long startTime = System.currentTimeMillis();
    public long endTime = 0;

    public CLBC_RunInstance(int id, String projectPath) {
        this.id = id;
        this.projectPath = projectPath;
    }
}
