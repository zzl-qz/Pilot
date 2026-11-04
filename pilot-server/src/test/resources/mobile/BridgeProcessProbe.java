import java.nio.file.Path;
import java.util.Arrays;

/** 仅测试使用：限定父进程与数据目录，绝不扫描或终止其他 Pilot / Java 进程。 */
class BridgeProcessProbe {
    public static void main(String[] args) {
        long parent = Long.parseLong(args[0]);
        String directory = Path.of(args[1]).toAbsolutePath().normalize().toString();
        if ("alive".equals(args[2])) {
            System.out.println(ProcessHandle.of(parent).map(ProcessHandle::isAlive).orElse(false));
            return;
        }
        boolean stop = "stop".equals(args[2]);
        ProcessHandle.of(parent).orElseThrow().descendants().filter(ProcessHandle::isAlive).filter(process -> {
            String[] values = process.info().arguments().orElse(new String[0]);
            int index = Arrays.asList(values).indexOf("--data-dir");
            return Arrays.asList(values).contains("com.pilot.mobile.bridge.MainKt") && index >= 0
                && index + 1 < values.length && directory.equals(values[index + 1]);
        }).forEach(process -> {
            System.out.println(process.pid());
            if (stop) {
                process.destroyForcibly();
            }
        });
    }
}
