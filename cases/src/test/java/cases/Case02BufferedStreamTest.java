package cases;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对应手册：docs/java/WebClient缓冲超限.md
 *
 * 坑：服务端明明是一条一条推的，客户端一次拿到整包。
 * 等价于 WebClient 的 bodyToMono(String.class) / RestTemplate 的 getForObject：
 * 它们在内部把流读干了再返回，前端看到的就是「等 3 秒，然后文字唰地全出来」。
 *
 * 这里用 JDK 自带的 HttpServer 起一个真会分段 flush 的接口，
 * 然后对比「读完再返回」和「按行读」两种客户端的首包时间。
 */
@DisplayName("02 流式变一次性：客户端把整个响应体读完才返回")
class Case02BufferedStreamTest {

    private static final int 每条间隔毫秒 = 300;
    private static HttpServer server;
    private static int port;

    @BeforeAll
    static void 起服务() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/stream", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream;charset=UTF-8");
            exchange.sendResponseHeaders(200, 0);          // 0 = chunked，不预先声明长度
            try (OutputStream out = exchange.getResponseBody()) {
                for (int i = 1; i <= 3; i++) {
                    out.write(("data: 第" + i + "条\n\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();                            // 关键：真的推出去
                    睡(每条间隔毫秒);
                }
            }
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterAll
    static void 关服务() {
        if (server != null) server.stop(0);
    }

    @Test
    @DisplayName("复现：readAllBytes 等价于 bodyToMono(String) —— 首包等于尾包")
    void 复现() throws IOException {
        long t0 = System.nanoTime();

        HttpURLConnection conn = 打开();
        String 整包 = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        long 拿到毫秒 = 毫秒(t0);

        System.out.printf("整包读完: %d ms，长度 %d 字%n", 拿到毫秒, 整包.length());
        // 三个事件间隔 300ms，读完必然是 ~900ms：这就是「流式失效」的可观测形态
        assertTrue(拿到毫秒 >= 750, "readAllBytes 必须等到最后一个事件，实际 " + 拿到毫秒 + " ms");
        assertTrue(整包.contains("第3条"));
    }

    @Test
    @DisplayName("对照：按行读（等价于 bodyToFlux / SseEmitter 客户端）—— 首包立刻到")
    void 对照() throws IOException {
        long t0 = System.nanoTime();

        HttpURLConnection conn = 打开();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {

            String 第一行 = reader.readLine();
            long 首包毫秒 = 毫秒(t0);

            System.out.printf("第一条到达: %d ms → %s%n", 首包毫秒, 第一行);
            assertEquals("data: 第1条", 第一行);
            assertTrue(首包毫秒 < 700, "按行读应该秒回，实际 " + 首包毫秒 + " ms");
        }
    }

    private static HttpURLConnection 打开() throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/stream").openConnection();
        conn.setConnectTimeout(2000);
        conn.setReadTimeout(5000);
        return conn;
    }

    private static long 毫秒(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static void 睡(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}