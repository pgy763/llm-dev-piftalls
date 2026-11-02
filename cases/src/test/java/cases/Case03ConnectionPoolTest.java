package cases;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对应手册：docs/java/事务里调模型拖垮连接池.md
 *
 * 坑：@Transactional 方法里先查库、再调模型、再写库。
 * 模型那 3 秒，数据库连接一直攥在手里不放。
 * 池子 10 个连接，第 11 个人就要等；并发一上来，所有人都在等模型，
 * 超时后报的却是「拿不到数据库连接」——看起来像数据库出了问题。
 *
 * 这里把连接池设成 1 个，让冲突在单机上一秒内复现。
 */
@DisplayName("03 事务里调模型：慢调用把连接池占满")
class Case03ConnectionPoolTest {

    @Test
    @DisplayName("复现：连接在事务里被慢调用占住，第二个请求拿不到连接")
    void 复现() throws Exception {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:pitfall_pool;DB_CLOSE_DELAY=-1");
        config.setMaximumPoolSize(1);
        // 线上默认是 30 秒，没人会盯着看；这里缩到 500ms 让测试跑得完
        config.setConnectionTimeout(500);

        try (HikariDataSource ds = new HikariDataSource(config)) {
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                // 请求 A：开启事务 → 查库 → 调模型（1.2 秒）→ 提交
                Future<?> 慢请求 = pool.submit(() -> {
                    try (Connection ignored = ds.getConnection()) {   // 事务开始，连接被借走
                        Thread.sleep(1200);                            // 模型在这 1.2 秒里还在算
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    return null;
                });

                Thread.sleep(200);   // 确保 A 先把连接拿到

                // 请求 B：只是要一个连接，跟模型毫无关系
                Exception 报错 = assertThrows(Exception.class, () -> {
                    try (Connection ignored = ds.getConnection()) {
                        // 拿不到就走不到这里
                    }
                });
                System.out.println("第二个请求: " + 报错.getClass().getSimpleName());
                System.out.println("  " + 报错.getMessage());
                // Hikari 的原话是 Connection is not available, request timed out after 500ms
                String 消息 = 报错.getMessage().toLowerCase();
                assertTrue(消息.contains("timed out") || 消息.contains("timeout")
                                || 消息.contains("not available"),
                        "线上你会看到连接超时，而不是模型超时: " + 报错.getMessage());

                慢请求.get(5, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    @DisplayName("对照：把模型调用挪出事务 —— 连接只在两条 SQL 之间被持有")
    void 对照() throws Exception {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:pitfall_pool_fixed;DB_CLOSE_DELAY=-1");
        config.setMaximumPoolSize(1);
        config.setConnectionTimeout(2000);

        try (HikariDataSource ds = new HikariDataSource(config)) {
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                // 请求 A：只在查库/写库的瞬间借连接，模型调用时手里什么都没有
                Future<?> 请求A = pool.submit(() -> {
                    用连接查一次(ds);
                    Thread.sleep(1200);          // 调模型——此时不持有连接
                    用连接写一次(ds);
                    return null;
                });

                Thread.sleep(200);

                // 请求 B 现在能顺利通过，因为池子里的 1 个连接已经还回来了
                long t0 = System.nanoTime();
                用连接查一次(ds);
                long 等待毫秒 = (System.nanoTime() - t0) / 1_000_000;

                System.out.printf("B 等待连接耗时: %d ms（对照组的 A 仍持有连接时为 500ms+）%n", 等待毫秒);
                assertTrue(等待毫秒 < 400, "连接已归还，B 不该排队，实际 " + 等待毫秒 + " ms");

                请求A.get(5, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    private static void 用连接查一次(HikariDataSource ds) throws Exception {
        try (Connection c = ds.getConnection()) {
            c.createStatement().executeQuery("select 1").close();
        }
    }

    private static void 用连接写一次(HikariDataSource ds) throws Exception {
        try (Connection c = ds.getConnection()) {
            c.createStatement().execute("create table if not exists t(id int)");
        }
    }
}