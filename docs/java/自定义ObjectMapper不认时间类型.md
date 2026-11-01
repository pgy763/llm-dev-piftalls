# `InvalidDefinitionException: Java 8 date/time type ... not supported by default`

![报错原文](/images/java-invaliddefinitionexception.png)

> 上图按本条「报错」原文渲染，文本逐字一致 —— 可以直接拿去和你的日志比对。

## 报错

```
com.fasterxml.jackson.databind.exc.InvalidDefinitionException: Java 8 date/time type `java.time.LocalDateTime` not supported by default: add Module "com.fasterxml.jackson.datatype:jackson-datatype-jsr310" to enable handling (through reference chain: com.example.ai.model.ScheduleResult["deadline"])
```

换成 `Instant` / `LocalDate` 也一样，只是括号里那行换了个类名。

> 措辞可能因 Jackson 小版本而异，但 `Java 8 date/time type ... not supported by default` 和 `jackson-datatype-jsr310` 这两段是稳定的，搜这个就行。

**最迷惑的地方在于：同一个项目里，Controller 返回 `LocalDateTime` 一点问题没有，只有你那一段代码炸。**

## 为什么

**因为你用的不是 Spring 给的那个 `ObjectMapper`，是你自己 `new` 出来的那个。**

Spring Boot 的 `JacksonAutoConfiguration` 会构造一个 `ObjectMapper`，并且**把所有 classpath 上能找到的 Jackson Module 全注册进去** —— 其中就包括 `jackson-datatype-jsr310`（`spring-boot-starter-web` 已经带上了）。你 `@Autowired` 或构造器注入拿到的那个，是配好的。

而你 `new ObjectMapper()` 拿到的是一张白纸：只认 Java 基本类型和 `java.util.Date`，不认 `java.time.*`。JSR-310 的序列化器不在里面，于是它诚实地告诉你「默认不支持」。

哪些场景会写出手动 `new` 的那个：

| 场景 | 典型写法 |
| --- | --- |
| 解析模型返回的 JSON | `new ObjectMapper().readValue(llmOutput, Foo.class)` |
| 给 HTTP 客户端配序列化器 | `RestClient.builder().messageConverters(...)` 时自己 new |
| 给 Redis 缓存配 value 序列化 | `GenericJackson2JsonRedisSerializer(new ObjectMapper())` |
| 写单元测试造 fixture | 测试类里 `new ObjectMapper()` 转换期望值 |

**这件事在「Java 接大模型」里格外容易撞上**，因为大模型的输出是**文本**，你必须自己把它转成对象 —— 这一步通常是全项目里唯一一处手写 `readValue` 的地方。

第二层原因：**就算注册了 `JavaTimeModule`，默认输出也不是 ISO 字符串。**

`WRITE_DATES_AS_TIMESTAMPS` 默认是开的，`LocalDateTime` 会被序列化成数组：

```json
{"deadline":[2026,9,30,9,44,14]}
```

这个数组扔给前端或者别的服务去解析，比报错还难查 —— 它不报错。

## 怎么改

### 第一步：判断你是哪种情况

- **只有一个配置类 / 工具类炸** → 那就是自己 new 的，按下面第二步改。
- **全项目的接口都变成时间戳数组了** → 你（或者某个依赖）定义了一个 `ObjectMapper` 类型的 `@Bean`，把 Boot 的默认配置顶掉了，看第三步。

### 第二步：优先别自己 new —— 注入 Spring 的那个

```java
@Service
public class LlmOutputParser {

    private final ObjectMapper mapper;

    public LlmOutputParser(ObjectMapper mapper) {   // 注入容器里的那个
        this.mapper = mapper;
    }

    public ScheduleResult parse(String llmOutput) throws JsonProcessingException {
        return mapper.readValue(llmOutput, ScheduleResult.class);
    }
}
```

### 需要独立配置（比如 LLM 输出要宽松模式）？用 builder 派生，别从零 new

```java
@Component
public class LlmJsonParser {

    private final ObjectMapper mapper;

    public LlmJsonParser(Jackson2ObjectMapperBuilder builder) {
        // 继承 Boot 的全部配置（含 JavaTimeModule），但这是独立的一份
        this.mapper = builder.build()
                .copy()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }
}
```

`Jackson2ObjectMapperBuilder` 是 Boot 自己注册的 bean，可以直接注入。这样你既拿到了独立实例，又不会丢掉自动装配的那些模块。

### 如果确实要手动 new，那就自己补齐

```java
ObjectMapper mapper = JsonMapper.builder()
        .addModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)   // 输出 ISO 字符串而不是数组
        .build();
```

`jackson-datatype-jsr310` 的依赖，Boot 的 `dependencyManagement` 已经管了版本，不用写：

```xml
<dependency>
    <groupId>com.fasterxml.jackson.datatype</groupId>
    <artifactId>jackson-datatype-jsr310</artifactId>
</dependency>
```

独立项目（没有 Boot 的 BOM）要自己写版本，或者引入 `jackson-bom` —— 具体版本以 Maven Central 上的最新稳定版为准。

### 第三步：如果你 @Bean 掉了一个 ObjectMapper，注意它是全局的

```java
@Bean
public ObjectMapper llmObjectMapper() {   // ⚠️ 这一个 bean 会顶掉 Boot 的默认 ObjectMapper
    return new ObjectMapper();
}
```

Boot 的自动配置带 `@ConditionalOnMissingBean`，只要容器里出现了任意一个 `ObjectMapper` 类型的 bean，它就不干活了。后果是所有 `@RestController` 的返回体、所有 `@RequestBody` 的解析都走你这份 —— 包括你可能忘了配的那些东西。

想改全局行为，用 `Jackson2ObjectMapperBuilderCustomizer`：

```java
@Bean
public Jackson2ObjectMapperBuilderCustomizer llmFriendlyJackson() {
    return builder -> builder.postConfigurer(
            m -> m.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false));
}
```

想只给某一处用，用上面的 `builder.build()` 派生。

## 怎么预防

**第一条：看到 `InvalidDefinitionException: Java 8 date/time type ...`，第一反应是「这个 mapper 不是 Spring 的那个」，不要去改 DTO。**

改 DTO 是常见的错误方向 —— 把 `LocalDateTime` 换成 `String` 能让报错消失，但你也顺手把「时间是个结构化的值」这件事弄丢了，后面每个用它的地方都要自己 parse 一遍。

**第二条：模型输出的时间字段，建议故意先接成 `String`。**

这条是取舍，说清楚为什么：

- 大模型返回的时间格式**本来就不稳定** —— 可能给你 `2026-09-30`、可能给 `2026-09-30T09:44:14+08:00`、可能给 `2026年9月30日下午3点`。映射到 `LocalDateTime`，前两种里就有一半会抛 `DateTimeParseException`。
- 接成 `String` 加一个显式的、带兜底的解析方法，你至少知道「模型给的东西不干净」，能在解析失败时走降级或重新提问。
- 反过来，如果你的 prompt 里已经用 JSON Schema 约束了时间格式，那直接映射成 `LocalDateTime` 也没问题 —— **前提是你有 schema 约束**，不是靠赌。

**第三条：单元测试里的 `new ObjectMapper()` 是同一个坑。** 造 fixture 时用它转换 `LocalDateTime` 字段，测试自己先挂了，你会以为是业务代码的问题。要么注入 `Jackson2ObjectMapperBuilder`，要么在测试基类里统一提供一个配好的 mapper。

## 环境

| 组件 | 版本 |
| --- | --- |
| Jackson | 2.x（`jackson-datatype-jsr310` 与 `databind` 同版本；错误措辞可能因小版本而异） |
| Spring Boot | 3.x（`spring-boot-starter-web` 自带 jsr310） |
| 触发条件 | 自己 `new ObjectMapper()`，或用未注册 `JavaTimeModule` 的独立 mapper |
| 涉及字段 | DTO 里出现 `LocalDateTime` / `Instant` / `LocalDate` |
