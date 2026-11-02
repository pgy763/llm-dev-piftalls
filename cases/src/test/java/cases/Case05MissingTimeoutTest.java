package cases;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对应手册：docs/java/Reactor超时截断流式.md
 *
 * 坑：HttpURLConnection / RestTemplate / WebClient 默认「读超时 = 无限」。
 * 对面不说话，你这根线程就一直等；线程池被这种线程占满后，
 * 整个服务表现为「没报错、也没响应」——最容易被判成「卡死了，重启吧」。
 */
@DisplayName("05 没设读超时：线程一直等，永远不报错")
class Case05MissingTimeoutTest {

    private static HttpServer server;
    private static int port;

    @BeforeAll
    static void 起一个慢服务() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/slow", exchange -> {
            睡(2000);                                   // 对面 2 秒才说话
            byte[] body = "{\"answer\":\"终于回来了\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
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
    @DisplayName("复现：不设 readTimeout → 老老实实等满 2 秒")
    void 复现() throws IOException {
        HttpURLConnection conn = 连接();
        // 注意这里什么都没设：默认就是 0，也就是「没有超时」
        long t0 = System.nanoTime();
        int code = conn.getResponseCode();
        long 耗时 = (System.nanoTime() - t0) / 1_000_000;

        System.out.printf("没有超时设置：等了 %d ms 才拿到 %d%n", 耗时, code);
        assertTrue(耗时 >= 1900, "它会一直等下去，实际等了 " + 耗时 + " ms");
    }

    @Test
    @DisplayName("对照：设 readTimeout=400ms → 到点抛 SocketTimeoutException")
    void 对照() {
        HttpURLConnection conn = 连接();
        conn.setReadTimeout(400);

        long t0 = System.nanoTime();
        assertThrows(SocketTimeoutException.class, conn::getResponseCode);
        long 耗时 = (System.nanoTime() - t0) / 1_000_000;

        System.out.printf("设了超时：%d ms 后放弃，并且报错可见%n", 耗时);
        assertTrue(耗时 < 1500, "应该在 400ms 左右就断开，实际 " + 耗时 + " ms");
    }

    private static HttpURLConnection 连接() {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/slow").openConnection();
            conn.setConnectTimeout(2000);     // 连得上，只是对面不回
            return conn;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void 睡(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}