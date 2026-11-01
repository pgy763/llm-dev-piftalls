# 流式回答固定 30 秒断开：`Did not observe any item or terminal signal`

![报错原文](/images/java-timeoutexception.png)

> 上图按本条「报错」原文渲染，文本逐字一致 —— 可以直接拿去和你的日志比对。

## 报错

```
java.util.concurrent.TimeoutException: Did not observe any item or terminal signal within 30000ms in 'timeout' (and no fallback has been configured)
	at reactor.core.publisher.FluxTimeout$TimeoutMainSubscriber.handleTimeout(FluxTimeout.java:293)
```

## 现象

前 20 秒还在正常出字，然后**固定在第 30 秒**断掉，前端报网络错误。

把超时从 30 秒改成 60 秒，就变成固定第 60 秒断 —— 说明不是上游的问题，是**本地某个 timeout 到了**。

## 为什么

`Flux.timeout(Duration)` 的语义常被误解，它**不是「总时长上限」**，而是：

> 两次信号之间的间隔不能超过这个值

关键点：

1. **它从订阅那一刻开始计时**，不是从收到第一个字开始
2. 每次收到元素都会重置计时器
3. 超时后如果没有 fallback，整个流以 `TimeoutException` 终止

所以「模型首字等了 35 秒」和「模型两次输出间卡了 35 秒」表现完全一样。而长回答本身不触发它 —— 只要一直在出字。

## 怎么改

### 一、先分清三种超时

| 想限制什么 | 用什么 | 说明 |
| --- | --- | --- |
| 连接建立 | `HttpClient.connectTimeout(5s)` | 连不上就别耗 |
| 首字节 | `Flux.timeout(Duration.ofSeconds(60))` 只作用于第一个元素 | 见下 |
| 字间空闲 | `Flux.timeout(Duration.ofSeconds(30))` | 卡住不动才算超时 |
| 总时长 | **不要设在流式上** | 见 [流式请求设了总超时](/java/流式请求设了总超时) |

### 二、只对首字节设超时

```java
Flux<String> chunks = client.stream(req)
    .timeout(Duration.ofSeconds(60), Flux.empty())   // 给 fallback，别让流直接炸
    .skipUntil(chunk -> !chunk.isBlank());
```

给 fallback（`Flux.empty()` 或一条「模型响应超时，请重试」）比直接抛异常好 —— 至少客户端能收到一条完整的 SSE 结束事件，而不是「半句话 + 连接断」。

### 三、字间超时单独设，且要更长

```java
.timeout(Duration.ofSeconds(30))    // 两次 chunk 之间的上限
```

模型在长回答里偶尔会有几秒的停顿（尤其是推理型模型），30 秒内的停顿应该容忍。**别设 5 秒** —— 那会把正常的慢思考判成故障。

## 怎么预防

- 日志里分开记录「首字节耗时」和「总耗时」，两个指标混在一起永远看不出是哪种超时
- 流式路径上的 timeout 一律带 fallback，避免异常穿透到客户端
- 压测时故意注入「上游卡住 40 秒」，验证 fallback 生效（第 11 章的做法）

## 环境

| 组件 | 版本 |
| --- | --- |
| JDK | 17 / 21 |
| Spring Boot | 3.x |
| Reactor | 2023.x 及以上 |
| 触发条件 | 在流式 Flux 上加了 `timeout()`，且没配 fallback |
| 相关异常 | `TimeoutException`、`TimeoutException: Did not observe any item` |
