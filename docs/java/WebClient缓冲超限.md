# `DataBufferLimitException: Exceeded limit on max bytes to buffer`

![报错原文](/images/java-databufferlimitexception.png)

> 上图按本条「报错」原文渲染，文本逐字一致 —— 可以直接拿去和你的日志比对。

## 报错

```
org.springframework.core.io.buffer.DataBufferLimitException: Exceeded limit on max bytes to buffer : 262144
	at org.springframework.core.io.buffer.LimitedDataBufferList.raiseLimitException(LimitedDataBufferList.java:101)
	at org.springframework.core.io.buffer.LimitedDataBufferList.updateCount(LimitedDataBufferList.java:94)
```

262144 就是 256 KB —— Spring 默认的聚合上限。数字可能因版本不同，但异常类名和 `max bytes to buffer` 这段是稳定的。

## 为什么

WebClient 有个容易忽略的设计：**它默认把响应读成一个完整对象**。当你写

```java
webClient.post().uri("/chat/completions").bodyValue(req)
         .retrieve()
         .bodyToMono(String.class)
```

Reactor Netty 会把所有 DataBuffer 聚合成一块，超过 256 KB 就抛这个异常。

模型接口恰恰是最容易超的类型：**一段几千字的回答加上 JSON 包装，轻松过 256 KB**；如果是流式接口，你还在用 `bodyToMono` 接，那更是一定炸。

注意它**不是内存不够**，是「策略不允许」—— 所以不会 OOM，只会直接拒。

## 怎么改

### 首选：别再聚合，逐段处理

```java
webClient.post().uri("/chat/completions").bodyValue(req)
         .retrieve()
         .bodyToFlux(String.class)      // 注意是 Flux，不是 Mono
         .doOnNext(chunk -> sendToClient(chunk))
         .blockLast();
```

流式接口本来就不该聚合。改成 `bodyToFlux` 之后，每个 SSE 事件处理完即丢，**内存占用与回答长度无关**。

### 次选：确实需要整段，就抬上限

```yaml
spring:
  codec:
    max-in-memory-size: 2MB
```

或者在代码里：

```java
WebClient.builder()
    .codecs(c -> c.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
    .build();
```

**抬到 2MB 是合理的，抬到 100MB 是把问题藏起来** —— 一旦某个请求真返回 100MB，你的堆会被它咬掉一块。

### 不要做：`-1`（不设限）

`maxInMemorySize(-1)` 表示无上限，等于把「策略保护」换成「等 OOM」。见 [收集流式响应导致 OOM](/java/收集流式响应导致OOM)。

## 怎么预防

- 流式路径上**禁止出现 `bodyToMono`** —— 这是 Code Review 的一条硬规则
- 需要整段的地方（比如离线摘要任务）显式设上限，并在超限时降级成「分段处理」
- 监控里加一条：`DataBufferLimitException` 出现即说明有人把流式写成了聚合

## 环境

| 组件 | 版本 |
| --- | --- |
| JDK | 17 / 21 |
| Spring Boot | 3.x（默认 256 KB） |
| 触发条件 | 用 `bodyToMono` 接流式或大响应 |
| 相关异常 | `DataBufferLimitException`、`OutOfMemoryError` |
