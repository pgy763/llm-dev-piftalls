package cases;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对应手册：docs/java/多用户共用一份ChatMemory.md
 *
 * 坑：ChatMemory 做成单例 Bean 注进 Service。
 * 一个人测的时候完全正常；两个人同时用，B 会看到 A 的上一句，
 * 模型开始用 A 的名字回答 B —— 用户视角是「AI 串台了」。
 */
@DisplayName("08 多用户共用一份 ChatMemory：上下文互相串台")
class Case08SharedMemoryTest {

    @Test
    @DisplayName("复现：单例记忆 → B 的上下文里有 A 说的话")
    void 复现() {
        共享记忆 memory = new 共享记忆();

        memory.记下("user-A", "我叫张三，做财务的");
        memory.记下("user-B", "今天天气怎么样");

        String B的上下文 = memory.上下文();
        System.out.println("B 看到的上下文: " + B的上下文);

        assertTrue(B的上下文.contains("张三"), "B 不该知道 A 叫什么");
        assertFalse(B的上下文.contains("天气") && !B的上下文.contains("张三"), "两句被拼在一起了");
    }

    @Test
    @DisplayName("对照：按会话隔离 → 各人只看各人的")
    void 对照() {
        隔离记忆 memory = new 隔离记忆();

        memory.记下("conv-A", "我叫张三，做财务的");
        memory.记下("conv-B", "今天天气怎么样");

        System.out.println("A 的上下文: " + memory.上下文("conv-A"));
        System.out.println("B 的上下文: " + memory.上下文("conv-B"));

        assertTrue(memory.上下文("conv-A").contains("张三"));
        assertFalse(memory.上下文("conv-B").contains("张三"));
        // 真实改法：ChatMemory 用 ChatMemoryProvider，key 取 conversationId / userId，
        // 不要用一个 @Bean 的 ChatMemory 给所有请求共用；
        // 多实例部署时还要把它挪到 Redis，否则换台机器记忆就丢了。
    }

    /** 错误示范：一份 List 全场共用。 */
    static class 共享记忆 {
        private final List<String> 历史 = new ArrayList<>();

        void 记下(String 用户, String 内容) {
            历史.add(用户 + ": " + 内容);
        }

        String 上下文() {
            return String.join(" / ", 历史);
        }
    }

    /** 正确示范：按会话号分开存。 */
    static class 隔离记忆 {
        private final Map<String, List<String>> 按会话 = new HashMap<>();

        void 记下(String 会话号, String 内容) {
            按会话.computeIfAbsent(会话号, k -> new ArrayList<>()).add(内容);
        }

        String 上下文(String 会话号) {
            return String.join(" / ", 按会话.getOrDefault(会话号, List.of()));
        }
    }
}