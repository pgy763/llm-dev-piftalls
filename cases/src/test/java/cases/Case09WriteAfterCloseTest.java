package cases;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对应手册：docs/java/客户端断开后还在写SSE.md
 *
 * 坑：用户关了页面，服务端还在往里写。
 * 每次 write 都抛 IOException，代码里一个 catch (IOException ignored) 全吞了，
 * 于是循环照跑、token 照烧，日志里干干净净，账单上一分不少。
 */
@DisplayName("09 客户端已断开：写入异常被吞，生成还在继续")
class Case09WriteAfterCloseTest {

    @Test
    @DisplayName("复现：catch 住 IOException 继续循环 → 白写 47 次")
    void 复现() {
        断开的连接 连接 = new 断开的连接();
        int 写入次数 = 0;
        int 被吞掉的异常 = 0;

        for (int i = 1; i <= 50; i++) {
            写入次数++;
            try {
                连接.write(("data: 第" + i + "条\n\n").getBytes());
            } catch (IOException e) {
                被吞掉的异常++;        // 经典写法：反正对面走了，忽略就好
            }
            if (i == 3) {
                连接.close();          // 用户收下第 3 条之后关掉了页面
            }
        }

        System.out.printf("写了 %d 次，成功 %d 次，异常全吞 %d 次，日志无输出%n",
                写入次数, 连接.成功写入, 被吞掉的异常);
        assertEquals(3, 连接.成功写入);
        assertTrue(被吞掉的异常 >= 47, "后面每一次都在对空气说话");
    }

    @Test
    @DisplayName("对照：捕获后立刻停 —— 最多多花一条的时间")
    void 对照() {
        断开的连接 连接 = new 断开的连接();
        int 尝试次数 = 0;
        boolean 已停止 = false;

        for (int i = 1; i <= 50; i++) {
            尝试次数++;
            try {
                连接.write(("data: 第" + i + "条\n\n").getBytes());
            } catch (IOException e) {
                // 记一条 WARN，然后真的停下来：模型那边取消，连接释放
                System.out.println("客户端已断开，第 " + i + " 条写入失败，停止生成: " + e.getMessage());
                已停止 = true;
                break;
            }
            if (i == 3) {
                连接.close();
            }
        }

        assertTrue(已停止);
        assertEquals(4, 尝试次数, "3 次成功 + 1 次发现断开");
    }

    /** 模拟一个已经关掉的响应流：第一次写就抛，和真实 SSE 一致。 */
    static class 断开的连接 extends OutputStream {
        private boolean closed;
        int 成功写入;

        @Override
        public void write(int b) throws IOException {
            写入(1);
        }

        /** 关键：OutputStream.write(byte[]) 默认会按字节循环调 write(int)，必须整个覆盖。 */
        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            写入(1);
        }

        private void 写入(int 次数) throws IOException {
            if (closed) {
                throw new IOException("Connection reset by peer");
            }
            成功写入 += 次数;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}