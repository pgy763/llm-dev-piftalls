# `UnrecognizedPropertyException: Unrecognized field "usage", not marked as ignorable`

![报错原文](/images/java-unrecognizedpropertyexception.png)

> 上图按本条「报错」原文渲染，文本逐字一致 —— 可以直接拿去和你的日志比对。

## 报错

```
com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException:
Unrecognized field "usage" (class com.example.dto.ChatResponse),
not marked as ignorable (2 known properties: "id", "choices")
 at [Source: (String)"{...}"; line: 1, column: 480]
```

你把 `RestClient` / `WebClient` / `RestTemplate` 的返回类型写成自己的 DTO，然后换成某家国产模型 —— 它就报这个。

换回 OpenAI 的接口又不报了。**代码一行没改。**

## 为什么

**「OpenAI 兼容」不等于「字段完全一样」。**

那行报错信息量很大，拆开看：

- `"usage"` —— 响应里多了个你的 DTO 没定义的字段
- `not marked as ignorable` —— 你的 DTO 没告诉 Jackson「遇到不认识的字段就跳过」
- `(2 known properties: "id", "choices")` —— 你的 DTO 只声明了这两个

**Jackson 的默认行为是严格的：遇到 DTO 里没有的字段就抛异常。**

为什么 OpenAI 官方接口不报？因为它返回的字段恰好都在你的 DTO 里。而国内厂商（DeepSeek、通义、智谱、月之暗面……）虽然都实现了 OpenAI 兼容协议，实际返回仍有差异：

| 差异类型 | 具体表现 |
| --- | --- |
| **多出字段** | 额外返回 `usage`、`system_fingerprint`、`request_id`、`reasoning_content` 等 |
| 缺少字段 | 某些字段不返回，包装类型字段会变成 `null` |
| 类型不同 | 同一个字段，有的返回数字有的返回字符串 |
| 嵌套结构差异 | `delta` 里的字段名不完全一致 |

**第一类（多出字段）就是这个报错的成因，也最好解决。**

> 顺带一提：`reasoning_content` 这类字段是推理模型特有的。如果你的业务要用它，那 DTO 里就得显式加上 —— **不能只靠「忽略未知字段」蒙过去**。

## 怎么改

### 方案一：全局忽略未知字段（最省事）

一处配置解决所有厂商的字段差异：

```java
@Configuration
class JacksonConfig {
    @Bean
    Jackson2ObjectMapperBuilderCustomizer lenientMapper() {
        return builder -> builder
                .failOnUnknownProperties(false)     // ← 关键
                .serializationInclusion(JsonInclude.Include.NON_NULL);
    }
}
```

或者写在 `application.yml` 里：

```yaml
spring:
  jackson:
    deserialization:
      fail-on-unknown-properties: false
```

> **接多厂商时这是最省事的一刀切。** 代价是「字段名拼错」这类错误也会变安静 —— 对需要严格校验的项目，用方案二。

### 方案二：只给 AI 相关的 DTO 加注解（更精确）

```java
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatResponse(String id, List<Choice> choices) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Choice(Message message, String finishReason) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(String role, String content) {}
}
```

**每一层都要加** —— 嵌套的 record 不会继承外层的注解，**这是最容易漏的地方**。查这类问题时，先把 DTO 的嵌套层数数清楚。

### 方案三：先接成 `JsonNode`，手动取（最灵活）

不需要完整映射时，直接拿原始结构：

```java
JsonNode root = restClient.post()
        .uri("/chat/completions")
        .body(body)
        .retrieve()
        .body(JsonNode.class);

String content = root.path("choices").path(0).path("message").path("content").asText();
```

**用 `path()` 不用 `get()`** —— 字段不存在时 `path()` 返回 `MissingNode`（再调 `.asText()` 得到空字符串），而 `get()` 返回 `null`，链式调用直接 NPE。接多厂商时养成这个习惯能省掉一半空指针。

## 怎么预防

**第一条：接一家新厂商，先把响应原文打印出来看。**

```java
String raw = restClient.post()
        .uri("/chat/completions")
        .body(body)
        .retrieve()
        .body(String.class);          // 先接成字符串

log.info("上游原始响应: {}", raw);    // 看清楚有哪些字段
```

比对着自己的 DTO 看差异，一眼就能发现。**这一步花两分钟，能省掉后面两小时的排查。**

**第二条：AI 相关的 DTO 一律加 `@JsonIgnoreProperties(ignoreUnknown = true)`。** 厂商会加字段，你的 DTO 不会同步更新 —— 防御性写法在这里是必要的，不是洁癖。

**第三条：「OpenAI 兼容」是营销措辞，不是规范。** 每接一家的完整检查清单：

- 基础对话
- 流式输出（注意结束标志是不是 `[DONE]`）
- Tool Calling
- 错误码体系（尤其限流和额度不足，别只判断 429）

四项都过一遍，再宣布支持这家厂商。

## 环境

| 组件 | 版本 |
| --- | --- |
| Jackson | 2.x（Spring Boot 3 默认引入） |
| 客户端 | `RestClient` / `WebClient` / `RestTemplate` 均可复现 |
| 模型厂商 | 返回字段多于 OpenAI 官方接口的那些 |
| 复现条件 | DTO 字段严格按 OpenAI 官方响应定义，然后换成国产厂商接口 |
