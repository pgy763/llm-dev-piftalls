package cases;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对应手册：docs/java/SSE响应中文乱码.md
 *
 * 坑：响应头只写 text/event-stream，没写 charset。
 * 字节是 UTF-8，对面按 GBK / ISO-8859-1 解，中文全变问号或方块。
 * 本地看日志一切正常（服务端字符串没坏），坏在「两端约定了不同的编码」。
 */
@DisplayName("01 SSE 中文乱码：响应头没写 charset")
class Case01SseCharsetTest {

    private static final String 原文 = "你好，这里是第一条流式消息";

    @Test
    @DisplayName("复现：UTF-8 字节按 GBK 解 → 乱码")
    void 复现() {
        byte[] wire = 原文.getBytes(StandardCharsets.UTF_8);

        // 真实链路里这一步发生在浏览器/客户端：
        // MediaType.TEXT_EVENT_STREAM_VALUE 本身不带 charset，
        // 对面就退回自己的默认编码（国内 Windows 常见 GBK）。
        String 收到 = new String(wire, Charset.forName("GBK"));

        System.out.println("服务端以为发的是: " + 原文);
        System.out.println("客户端实际收到: " + 收到);

        assertNotEquals(原文, 收到, "编码对了就不会乱，这条测试的意义就是不相等");
        // 乱码的典型特征：出现了原文里根本没有的汉字/替换符
        assertTrue(收到.chars().anyMatch(c -> c == '\uFFFD' || (c > 0x4E00 && 原文.indexOf(c) < 0)),
                "GBK 解码 UTF-8 必然产生错字，实际: " + 收到);
    }

    @Test
    @DisplayName("对照：两端都用 UTF-8 → 正常")
    void 对照() {
        byte[] wire = 原文.getBytes(StandardCharsets.UTF_8);

        // 改法一：响应头补 charset —— produces = "text/event-stream;charset=UTF-8"
        // 改法二：客户端按 UTF-8 解 —— new InputStreamReader(in, UTF_8)
        assertEquals(原文, new String(wire, StandardCharsets.UTF_8));

        // 顺带说明：只要「写」和「读」用同一个 charset，用 GBK 也不会乱。
        // 乱码从来不是 UTF-8 的错，是两端不一致。
        assertEquals(原文, new String(原文.getBytes(Charset.forName("GBK")), Charset.forName("GBK")));
    }
}