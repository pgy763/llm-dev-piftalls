package cases;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对应手册：docs/java/网关返回HTML被当JSON解析.md
 *
 * 坑：调用方把 200 之外的响应也直接丢给 ObjectMapper。
 * 网关 502 / 负载均衡 503 时返回的是一张 HTML 错误页，
 * 于是你看到 Jackson 报 "Unrecognized token '<'" ——
 * 排查方向立刻跑到「JSON 格式不对」，而真正的问题是对面根本没回 JSON。
 */
@DisplayName("06 网关 502 返回 HTML：Jackson 报认不出 '<'")
class Case06HtmlAsJsonTest {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static HttpServer server;
    private static int port;

    @BeforeAll
    static void 起网关() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat", exchange -> {
            String html = "<html><head><title>502 Bad Gateway</title></head>"
                    + "<body><center><h1>502 Bad Gateway</h1></center><hr><center>nginx</center></body></html>";
            byte[] body = html.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html");
            exchange.sendResponseHeaders(502, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterAll
    static void 关网关() {
        if (server != null) server.stop(0);
    }

    @Test
    @DisplayName("复现：不看状态码直接 parse → JsonParseException 指向错误的方向")
    void 复现() throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/v1/chat").openConnection();
        int status = conn.getResponseCode();
        String body = new String(conn.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);

        JsonParseException 报错 = assertThrows(JsonParseException.class, () -> mapper.readValue(body, JsonNode.class));
        System.out.printf("HTTP %d，Content-Type: %s%n", status, conn.getContentType());
        System.out.println("Jackson 说: " + 报错.getOriginalMessage());

        assertEquals(502, status);
        assertTrue(报错.getOriginalMessage().contains("<"), "报错里那个 '<' 就是 HTML 的开头");
    }

    @Test
    @DisplayName("对照：先看状态码和 Content-Type，再把 HTML 里的标题当错误信息")
    void 对照() throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/v1/chat").openConnection();
        int status = conn.getResponseCode();
        String contentType = String.valueOf(conn.getContentType());

        if (status / 100 != 2) {
            // 非 2xx 一律不进 JSON 解析，错误信息从 HTML 里抠出来就够定位了
            String body = new String(conn.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            String title = 提取标题(body);
            System.out.printf("上游错误: HTTP %d / %s / %s%n", status, contentType, title);
            assertEquals("502 Bad Gateway", title);
            return;
        }
        // 走到这里才解析
        mapper.readValue(new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8), JsonNode.class);
    }

    private static String 提取标题(String html) {
        Matcher m = Pattern.compile("<title>(.*?)</title>", Pattern.CASE_INSENSITIVE).matcher(html);
        return m.find() ? m.group(1) : "（HTML 里没有 title）";
    }
}