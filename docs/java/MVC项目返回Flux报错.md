# `No converter for [class reactor.core.publisher.FluxJust] with preset Content-Type 'application/json'`

![报错原文](/images/java-illegalargumentexception.png)

> 上图按本条「报错」原文渲染，文本逐字一致 —— 可以直接拿去和你的日志比对。

## 报错

```
java.lang.IllegalArgumentException: No converter for
[class reactor.core.publisher.FluxJust] with preset Content-Type 'application/json'
```

或者表现出来是另一种样子：接口返回 200，但响应体是一坨看不懂的结构；有的版本直接返回 406。

你的代码大概长这样：

```java
@GetMapping("/chat")
public Flux<String> chat(@RequestParam String q) {
    return chatClient.prompt().user(q).stream().content();
}
```

这段代码是从 Spring AI 官方文档抄的 —— 然后放进自己的 Spring MVC 项目，就炸了。

## 为什么

**`Flux` 是 Reactor 的类型，Spring MVC 不认识它。**

Spring 有两个 Web 栈：

| | Spring MVC | Spring WebFlux |
| --- | --- | --- |
| 模型 | Servlet，同步阻塞 | 响应式，`Flux` / `Mono` |
| 认识 `Flux` 吗 | **不认识** | 认识 |
| 底层 | Tomcat + 请求线程 | Netty + 事件循环 |

`HttpMessageConverter` 负责把方法返回值转成 HTTP 响应体。MVC 的转换器列表里**没有能处理 `Flux` 的**，于是它找不到任何可用转换器，直接抛错。

`FluxJust` 是 Reactor 内部的具体实现类（`Flux` 是接口）。**这个类名会随场景变化** —— 你看到 `FluxJust`、`FluxArray`、`FluxMap`，都是同一个问题，不用纠结是哪个。

> **一个常见误判**：项目里同时有 `spring-boot-starter-web` 和 `spring-boot-starter-webflux`，Spring Boot 默认**仍然走 MVC**。所以「我引入了 webflux 依赖」不等于「项目变成响应式了」。

## 怎么改

两条路，按项目实际情况选，别硬套。

### 路线一：MVC 项目 → 用 `SseEmitter` 桥接（推荐）

不换技术栈，把 `Flux` 订阅下来，逐条喂给 `SseEmitter`：

```java
@GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter stream(@RequestParam String q) {
    SseEmitter emitter = new SseEmitter(180_000L);   // 超时必须显式设置

    Disposable subscription = chatClient.prompt()
            .user(q)
            .stream()
            .content()
            .subscribe(
                    token -> {
                        try {
                            emitter.send(SseEmitter.event().data(token));
                        } catch (IOException e) {
                            emitter.completeWithError(e);   // 客户端断了
                        }
                    },
                    emitter::completeWithError,                 // 出错要通知前端
                    emitter::complete                          // 正常结束
            );

    // 客户端断开时取消上游订阅 —— 否则模型还在跑，你在为离开的用户付 token 钱
    emitter.onCompletion(subscription::dispose);
    emitter.onError(e -> subscription.dispose());

    return emitter;
}
```

三个容易漏的点：

1. **`produces = MediaType.TEXT_EVENT_STREAM_VALUE` 不能少** —— 没有它，浏览器不会按流式处理
2. **`completeWithError` 必须调** —— 不调的话前端会一直等一个永远不来的结束标志，表现为「一直转圈」
3. **记得 `dispose`** —— 这是「用户关掉页面就停止计费」的实现方式

### 路线二：项目本来就是 WebFlux → 直接用

```java
@GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<String> stream(@RequestParam String q) {
    return chatClient.prompt().user(q).stream().content();
}
```

### 不要做的事

**别为了一个流式接口把整个 MVC 项目改成 WebFlux。** 所有阻塞式代码都要重写，而 `JdbcTemplate`、MyBatis 这类在响应式栈里是反模式，改到最后你会在响应式项目里到处写 `block()` —— 比用 MVC 更糟。

**也别想着加个 `@ResponseBody` 试试。** 转换器不认识 `Flux` 这件事和注解无关，加了没用。

## 怎么预防

**记住这条边界**：`Flux` / `Mono` 只在 WebFlux 栈里是一等公民。在 MVC 项目里看到方法返回 `Flux`，第一反应就该是「要桥接」。

判断当前是什么栈：

```bash
mvn dependency:tree | grep -E "starter-web|starter-webflux"
```

| 结果 | 结论 |
| --- | --- |
| 只有 `starter-web` | MVC |
| 只有 `starter-webflux` | WebFlux |
| **两个都有** | **默认是 MVC** ← 最容易误判的情况 |

**另一条经验**：看官方文档的示例代码时，先确认它假设的是哪个 Web 栈。Spring AI 的文档两种都给了，但示例片段不会标注「这行在 MVC 里不能用」——**这个坑要从文档里看出来，得先知道有这么回事。**

## 环境

| 组件 | 版本 |
| --- | --- |
| Spring Boot | 3.x |
| Spring AI | 1.x |
| Web 栈 | Spring MVC（Servlet） |
| 复现条件 | 照抄 Spring AI 文档的 `.stream()` 示例，放进 MVC 项目 |
