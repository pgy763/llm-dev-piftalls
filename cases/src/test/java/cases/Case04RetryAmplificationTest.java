package cases;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 对应手册：docs/agent/工具消息顺序错.md 的姊妹篇（重试与重复执行）
 *
 * 坑：给模型调用加了「超时自动重试」。
 * 第一次其实在服务端已经跑完了，只是客户端 200ms 就放弃了；
 * 重试再来一次，于是同一句话被算了两遍钱、写了两遍库。
 * 报错日志里只看得到一次 Timeout，账上却是两次。
 */
@DisplayName("04 超时重试：客户端一次超时，服务端执行两次")
class Case04RetryAmplificationTest {

    /** 一个假网关：慢 300ms，可选幂等键。 */
    static class Gateway {
        final Set<String> 已受理 = new HashSet<>();
        int 执行次数 = 0;

        String 调用(String 幂等键, long 耗时毫秒) throws InterruptedException {
            if (幂等键 != null) {
                // 幂等键在「开始执行」时就登记，重试进来的第二次直接复用结果
                if (!已受理.add(幂等键)) {
                    return "cached:" + 幂等键;
                }
            }
            执行次数++;
            Thread.sleep(耗时毫秒);
            return "answer";
        }
    }

    @Test
    @DisplayName("复现：超时重试一次 → 服务端跑了两次")
    void 复现() throws Exception {
        Gateway gateway = new Gateway();

        assertThrows(TimeoutException.class, () -> 带超时调用(gateway, null, 200));
        // 业务层的「重试一次」——看起来无懈可击
        带超时调用(gateway, null, 2000);

        System.out.printf("客户端认为: 成功 1 次%n服务端实际: %d 次%n", gateway.执行次数);
        assertEquals(2, gateway.执行次数, "超时重试 = 同一件事做了两遍");
    }

    @Test
    @DisplayName("对照：带幂等键重试 → 服务端只执行一次")
    void 对照() throws Exception {
        Gateway gateway = new Gateway();
        String 请求号 = "req-20261102-0001";

        assertThrows(TimeoutException.class, () -> 带超时调用(gateway, 请求号, 200));
        String 第二次 = 带超时调用(gateway, 请求号, 2000);

        System.out.printf("第二次返回: %s%n服务端实际: %d 次%n", 第二次, gateway.执行次数);
        assertEquals(1, gateway.执行次数, "幂等键让重试变成查询，而不是重做");
    }

    private static String 带超时调用(Gateway gateway, String 幂等键, long 超时毫秒) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<String> future = executor.submit(() -> gateway.调用(幂等键, 300));
        try {
            return future.get(超时毫秒, TimeUnit.MILLISECONDS);
        } finally {
            future.cancel(true);      // 客户端放弃，服务端可能还在跑 —— 这就是坑的来源
            executor.shutdownNow();
        }
    }
}