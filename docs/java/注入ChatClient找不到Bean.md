# `NoSuchBeanDefinitionException: No qualifying bean of type 'org.springframework.ai.chat.client.ChatClient'`

![报错原文](/images/java-chatclient-bean.png)

> 上图按本条「报错」原文渲染，文本逐字一致 —— 可以直接拿去和你的日志比对。

## 报错

启动阶段的典型形状（Spring Boot 的失败分析器会替你改写成中文式的大白话）：

```
***************************
APPLICATION FAILED TO START
***************************

Description:

Parameter 0 of constructor in com.example.llm.ChatService required a bean of type
'org.springframework.ai.chat.client.ChatClient' that could not be found.
```

注入点不同（字段注入、`@Bean` 方法参数）时，露出的是底层那句：

```
org.springframework.beans.factory.NoSuchBeanDefinitionException:
No qualifying bean of type 'org.springframework.ai.chat.client.ChatClient' available:
expected at least 1 bean which qualifies as autowire candidate. Dependency annotations: {}
```

如果你引入了两个模型 starter，报的会是**另一句**，别混淆：

```
NoUniqueBeanDefinitionException: No qualifying bean of type
'org.springframework.ai.chat.client.ChatClient$Builder' available:
expected single matching bean but found 2: openAiChatClientBuilder,ollamaChatClientBuilder
```

> 措辞随 Spring AI / Spring Boot 版本略有差异。认准 `NoSuchBeanDefinitionException` + `ChatClient` 这个组合，以及 `found 2:` 后面列出的 bean 名。

## 为什么

Spring AI 的 starter 自动配置给了你三样东西：`ChatModel`（具体厂商的实现）、`ChatClient.Builder`（prototype 作用域）、一堆 `*Options`。**`ChatClient` 本身不在其中。**

这是刻意的，不是漏配。`ChatClient` 是不可变的成品 —— 一旦 `build()`，它就固定了当时的默认 system prompt、advisors、tools。让自动配置注册一个全局单例，等于框架替你决定了这些默认值，而几乎每个真实项目都要按场景给不同的默认值（客服要短、代码助手要长、RAG 场景要挂 advisor）。

所以下面这行注定失败：

```java
@Service
@RequiredArgsConstructor
public class ChatService {
    private final ChatClient chatClient;   // 没有这个 Bean
}
```

两个常见的连带变体：

- **连 `ChatModel` 都注入不到** —— starter 根本没进来。Spring AI 1.0 起 artifact 改过名（`spring-ai-openai-spring-boot-starter` → `spring-ai-starter-model-openai` 这种风格，以官方最新文档为准），照着半年前的博客抄坐标，很容易抄成不存在或者不生效的写法。表现就是「依赖有、类能 import、Bean 没有」。
- **`ChatClient.Builder` 找到两个** —— 引入 openai 和 ollama 两个 starter，两个自动配置各注册一个 Builder，注入点没写 `@Qualifier` 就炸成 `NoUniqueBeanDefinitionException`。

## 怎么改

### 单模型：自己声明

```java
@Configuration
public class ChatClientConfig {

    @Bean
    ChatClient chatClient(ChatClient.Builder builder) {
        return builder
            .defaultSystem("你是一个严谨的技术助手，回答不超过三句话。")
            .build();
    }
}
```

`ChatClient.Builder` 是 prototype 的，每次注入都是新实例。这意味着你可以按场景各自 `build()` 出行为不同的客户端，互不影响 —— 这也是别把它做成单例的实际理由，不只是「框架没给」。

### 多模型：按名字区分，别用 `@Primary`

```java
@Bean
ChatClient openAiChatClient(@Qualifier("openAiChatClientBuilder") ChatClient.Builder b) {
    return b.defaultSystem("...").build();
}

@Bean
ChatClient ollamaChatClient(@Qualifier("ollamaChatClientBuilder") ChatClient.Builder b) {
    return b.defaultSystem("...").build();
}
```

Builder 的 bean 名由 starter 决定，**直接抄报错里 `found 2:` 后面列出的那两个名字**，别猜。

`@Primary` 也能让启动通过，但半年后没人记得哪个是主，换模型时改的是注解而不是调用点 —— 出问题的时候你会在十几个 `@Autowired` 里找那个隐式生效的默认值。

### 更省事：直接注入 Builder

只有一个模型、也不打算定制默认值时，连 `ChatClient` 这个 Bean 都不必声明：

```java
@Service
@RequiredArgsConstructor
public class ChatService {
    private final ChatClient.Builder builder;

    public String ask(String q) {
        return builder.build().chat().user(q).call().content();
    }
}
```

一个前提：`build()` 别写在循环里。每次 build 会重新组装 advisors 和默认值，有实打实的开销 —— 放在构造器或 `@PostConstruct` 里缓存住。

## 怎么预防

**第一条：把 `ChatClient` 当「自己写的 Bean」，和 `RestTemplate` 一样对待。** 看到 `@Autowired ChatClient` 就该停一下 —— 这两个都是「框架不替你造单例」的东西。

**第二条：新项目先写一个启动冒烟测试。**

```java
@SpringBootTest
class AiWiringTest {
    @Autowired ChatModel chatModel;

    @Test
    void modelIsWired() {
        assertNotNull(chatModel);
    }
}
```

启动期就能发现的问题，别留到发版前。加 starter、升版本、换 BOM 之后跑一次，成本几秒钟。

**第三条：多个 starter 共存时，在配置里关掉不用的那个，而不是到处 `@Qualifier`。** Spring AI 有按类型选模型的开关属性（形如 `spring.ai.model.chat=openai`，属性名以官方文档为准）。配置一处比在十几个注入点标 `@Qualifier` 可靠 —— 后者一定会漏。

**第四条：starter 坐标别散落在多个 pom 里。** artifact 名变过一次（0.8 → 1.0），散落多处的坐标升级时必漏一个，漏的那个通常就是启动报 `NoSuchBeanDefinitionException` 的那个。

## 环境

| 组件 | 版本 |
| --- | --- |
| Spring Boot | 3.x |
| Spring AI | 1.0.x（0.8 线的 starter 坐标与 `ChatClient` 创建方式不同，以官方最新文档为准） |
| JDK | 17+ |
| 复现条件 | 直接注入 `ChatClient`；或引入两个模型 starter 后直接注入 `ChatClient.Builder` |
