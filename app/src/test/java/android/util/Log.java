package android.util;

/**
 * JVM 单测里的 `android.util.Log`。
 *
 * android.jar 的 mockable 版本把每个方法都实现成抛 `RuntimeException("not mocked")`，
 * 于是任何碰 `Log` 的类（`AsrShellServer` / `AsrShellSession` / 两个会话类 /
 * `WeTypeVoiceClient`）都没法进单测。`build.gradle.kts` 没开
 * `unitTests.isReturnDefaultValues`，所以这里在测试源集补一个能打印的实现顶上 ——
 * 测试类目录在运行时排在 android.jar 之前，会遮蔽掉那个抛异常的版本。
 *
 * 只把「抛异常」换成「打印」/「无操作」，不改变任何既有测试的判定。
 */
public final class Log {
    public static int v(String tag, String msg) { return 0; }
    public static int v(String tag, String msg, Throwable tr) { return 0; }
    public static int d(String tag, String msg) { return 0; }
    public static int d(String tag, String msg, Throwable tr) { return 0; }
    public static int i(String tag, String msg) { System.out.println("I/" + tag + ": " + msg); return 0; }
    public static int i(String tag, String msg, Throwable tr) { return i(tag, msg); }
    public static int w(String tag, String msg) { System.out.println("W/" + tag + ": " + msg); return 0; }
    public static int w(String tag, String msg, Throwable tr) { return w(tag, msg); }
    public static int w(String tag, Throwable tr) { return w(tag, String.valueOf(tr)); }
    public static int e(String tag, String msg) { System.out.println("E/" + tag + ": " + msg); return 0; }
    public static int e(String tag, String msg, Throwable tr) { return e(tag, msg); }
    public static int wtf(String tag, String msg) { return e(tag, msg); }
    public static int wtf(String tag, String msg, Throwable tr) { return e(tag, msg); }
    public static String getStackTraceString(Throwable tr) { return String.valueOf(tr); }
    public static boolean isLoggable(String tag, int level) { return false; }
    public static int println(int priority, String tag, String msg) { return 0; }
}
