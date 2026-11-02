package cases;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对应手册：docs/endpoint/工具消息顺序错.md
 *
 * 坑：max_tokens 太小 / 流式被截断，工具调用的 arguments 只吐了一半。
 * 字符串本身是合法的（没有报错），但 JSON 少了个花括号，
 * 解析时抛 "Unexpected end-of-input"，日志里看不出是「被截断」还是「模型不会写 JSON」。
 */
@DisplayName("07 工具参数被 max_tokens 截断：JSON 只到了一半")
class Case07TruncatedToolArgsTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("复现：参数串缺了结尾 → Unexpected end-of-input")
    void 复现() {
        String 完整参数 = "{\"city\":\"杭州\",\"date\":\"2026-11-02\",\"unit\":\"celsius\"}";

        // 模型生成到一半撞上 max_tokens 就停了，尾部几个字符直接没有
        String 截断 = 完整参数.substring(0, 完整参数.length() - 9);
        System.out.println("模型给的 arguments: " + 截断);

        JsonParseException 报错 = assertThrows(JsonParseException.class,
                () -> mapper.readTree(截断));
        System.out.println("解析器说: " + 报错.getOriginalMessage());
        assertTrue(报错.getOriginalMessage().toLowerCase().contains("end-of-input"),
                "典型报错是 Unexpected end-of-input，实际: " + 报错.getOriginalMessage());
    }

    @Test
    @DisplayName("对照：流式里先拼完整，判完 finish_reason 再解析")
    void 对照() throws Exception {
        // 模型真实输出是一块一块回来的，硬件上没法「只取前半段当 JSON」
        List<String> 片段 = List.of(
                "{\"city\":\"", "杭州", "\",\"date\":\"2026-11",
                "-02\",\"unit\":\"celsius\"", "}");

        StringBuilder 缓冲 = new StringBuilder();
        for (String 片段内容 : 片段) {
            缓冲.append(片段内容);
        }

        JsonNode 参数 = mapper.readTree(缓冲.toString());
        System.out.println("拼完再解析: " + 参数);

        assertEquals("杭州", 参数.get("city").asText());
        assertEquals("celsius", 参数.get("unit").asText());
        // 真实改法两条：
        // 1) finish_reason == "length" 时不要解析参数，直接按「模型没说完」处理并重试；
        // 2) arguments 用流式增量累积，不要在某个 chunk 上提前 readTree。
    }
}