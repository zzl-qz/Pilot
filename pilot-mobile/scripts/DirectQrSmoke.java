import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.pilot.mobile.transport.PairingInvite;

import computer.iroh.EndpointTicket;

/** 实际启动默认连接桥；不给中继参数、不连接手机，也必须能快速生成可解码二维码。 */
class DirectQrSmoke {
    public static void main(String[] args) throws Exception {
        Path data = Files.createTempDirectory("pilot-direct-qr-");
        String executable = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
        boolean direct = Boolean.getBoolean("pilot.qr.direct");
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
            "--enable-native-access=ALL-UNNAMED", "-cp", System.getProperty("java.class.path"),
            "com.pilot.mobile.bridge.MainKt", "--pilot-port", "9999", "--data-dir", data.toString(), "--no-open"));
        if (direct) command.add("--no-relay");
        Process child = new ProcessBuilder(command).redirectErrorStream(true).start();
        var reader = Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());
        try {
            URI admin = reader.submit(() -> {
                try (var lines = new BufferedReader(new InputStreamReader(child.getInputStream()))) {
                    String line;
                    while ((line = lines.readLine()) != null) {
                        if (line.startsWith("PILOT_MOBILE_ADMIN ")) return URI.create(line.substring(19));
                    }
                }
                throw new IllegalStateException("默认连接桥未启动");
            }).get(20, TimeUnit.SECONDS);
            HttpClient http = HttpClient.newBuilder().proxy(java.net.ProxySelector.of(null)).connectTimeout(Duration.ofSeconds(2)).build();
            long started = System.nanoTime();
            URI pair = URI.create("http://127.0.0.1:" + admin.getPort() + "/api/pair");
            var request = HttpRequest.newBuilder(pair).timeout(Duration.ofSeconds(3))
                .header("X-Pilot-Pairing", admin.getFragment()).POST(HttpRequest.BodyPublishers.ofString("{}")).build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            long millis = (System.nanoTime() - started) / 1_000_000;
            check(response.statusCode() == 200, "未配置中继时默认出码失败");
            String json = response.body();
            check(value(json, "connectionMode").equals(direct ? "direct-only" : "automatic"), "连接模式不匹配");
            String code = value(json, "code");
            var invite = PairingInvite.Companion.decode(code, System.currentTimeMillis());
            check(invite.getRelays().isEmpty() && invite.getDevelopmentRelays() == !direct, "二维码公共服务配置不匹配");
            try (var ticket = EndpointTicket.Companion.fromString(invite.getTicket()); var address = ticket.endpointAddr()) {
                if (direct) check(address.relayUrl() == null, "显式直连票据不应包含中继");
            }
            byte[] png = Base64.getDecoder().decode(value(json, "image").substring("data:image/png;base64,".length()));
            var bitmap = new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(ImageIO.read(new ByteArrayInputStream(png)))));
            check(new MultiFormatReader().decode(bitmap, java.util.Map.of(
                com.google.zxing.DecodeHintType.POSSIBLE_FORMATS, List.of(com.google.zxing.BarcodeFormat.QR_CODE),
                com.google.zxing.DecodeHintType.TRY_HARDER, true)).getText().equals(code), "实际 PNG 二维码无法解码");
            System.out.println("PASS: " + (direct ? "显式直连" : "默认自动") + "模式无手机、无自建中继配置生成可解码二维码，耗时 " + millis + " ms；不输出配对凭证");
        } finally {
            child.destroy();
            if (!child.waitFor(5, TimeUnit.SECONDS)) child.destroyForcibly().waitFor();
            reader.shutdownNow();
            try (var paths = Files.walk(data)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static String value(String json, String name) {
        var match = Pattern.compile("\"" + name + "\":\"([^\"]+)\"").matcher(json);
        check(match.find(), "响应缺少必要字段");
        return match.group(1);
    }

    private static void check(boolean valid, String message) {
        if (!valid) throw new IllegalStateException(message);
    }
}
