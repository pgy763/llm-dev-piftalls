package cases;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 对应手册：docs/auth/401有两种key错和钱没了.md
 *
 * 坑：两种完全不同的故障，HTTP 状态码都是 401。
 * 「key 写错了」换 key 就能解决；「余额不足」换多少 key 都没用，
 * 而按 401 统一处理的代码会一直重试 / 一直提示用户「检查密钥」。
 */
@DisplayName("10 两种 401：一个是 key 错，一个是钱没了")
class Case10UnauthorizedAmbiguityTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    private static final String KEY错误 = """
            {"error":{"type":"invalid_request_error","code":"invalid_api_key","message":"Incorrect API key provided"}}
            """;

    private static final String 余额不足 = """
            {"error":{"type":"insufficient_quota","code":"insufficient_quota","message":"You exceeded your current quota"}}
            """;

    @Test
    @DisplayName("复现：只看状态码 → 两种 401 判成同一件事")
    void 复现() {
        assertEquals(处置.换KEY, 按状态码判断(401, KEY错误));
        assertEquals(处置.换KEY, 按状态码判断(401, 余额不足));   // 这里就已经错了

        System.out.println("用户看到的提示：密钥无效，请检查 API Key");
        System.out.println("实际上：余额不足，换 key 也没用");
    }

    @Test
    @DisplayName("对照：读 error.code → 两种处置分开")
    void 对照() throws Exception {
        assertEquals(处置.换KEY, 按错误体判断(401, KEY错误));
        assertEquals(处置.充值, 按错误体判断(401, 余额不足));
        assertNotEquals(按错误体判断(401, KEY错误), 按错误体判断(401, 余额不足));
    }

    enum 处置 { 换KEY, 充值, 稍后重试 }

    /** 常见写法：401 就是 key 不对。 */
    private static 处置 按状态码判断(int status, String body) {
        return status == 401 ? 处置.换KEY : 处置.稍后重试;
    }

    /** 正确做法：状态码只用来分流，真正的含义在 error.code 里。 */
    private static 处置 按错误体判断(int status, String body) throws Exception {
        JsonNode 错误 = mapper.readTree(body).path("error").path("code");
        String code = 错误.asText("");
        return switch (code) {
            case "invalid_api_key", "authentication_error" -> 处置.换KEY;
            case "insufficient_quota", "billing_hard_limit_reached" -> 处置.充值;
            default -> status >= 500 ? 处置.稍后重试 : 处置.稍后重试;
        };
    }
}