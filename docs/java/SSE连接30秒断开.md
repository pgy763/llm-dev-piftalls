# SSE 连了 30 秒就断，前端还自己重连了一遍

![报错原文](/images/java-asyncrequesttimeoutexception.png)

> 上图按本条「报错」原文渲染，文本逐字一致 —— 可以直接拿去和你的日志比对。

## 现象

**主症状是「没有任何报错」。**

前端发起流式请求，回答写到一半，连接自己关了。`EventSource` 默认会自动重连（间隔由浏览器决定，经验值 3 秒左右），于是你看到的是：**同一段回答重新从头写一遍**，或者同一个问题在日志里出现了两次。

后端如果没挂超时回调，日志里能看到（类名是准的，措辞可能因版本而异）：

```
org.springframework.web.context.request.async.AsyncRequestTimeoutException
	at org.springframework.web.context.request.async.WebAsyncManager$...
```

另一个形态出现在心跳线程还在往里写的时候：

```
java.lang.IllegalStateException: ResponseBodyEmitter has already completed
```

它的意思是「这条连接已经结束了」，不是「发送失败」。这两个报错看起来是两件事，实际是同一个原因的两个阶段。

## 为什么

### 第一层：`SseEmitter` 不给超时，用的是容器的默认值

`new SseEmitter()` 不传参时，`timeout` 是 `null`，走容器的 async 超时。**内嵌 Tomcat 的默认值是 30000 毫秒** —— 30 秒。

也就是说，什么都不配的情况下，任何一次生成超过 30 秒的流式回答**必然被切断**。而模型回答「帮我写一份详细的设计文档」要一两分钟，这是常态不是异常。

三层超时，任何一层先到都会断：

| 层 | 默认值 | 在哪改 |
| --- | --- | --- |
| 容器 async 超时（Tomcat） | **30 秒** | `new SseEmitter(ms)` 或 `spring.mvc.async.request-timeout` |
| 反向代理读超时（Nginx） | 60 秒 | `proxy_read_timeout` |
| 浏览器 / `EventSource` | 不主动断 | — |

### 第二层：断开之后的三个连锁反应

这一层比超时本身更贵。

**一、上游还在跑，钱照付。** 连接断了，你的生成线程并不知道 —— 它还在往一个已经关闭的 response 里写。这一次调用的 token 一个不少地计费，用户拿到的却是半截。

**二、前端自动重连，等于再付一次。** `EventSource` 断线后会自动重发请求。如果这个请求是「生成一份报告」，那就是两份报告的钱，一份被丢掉。

**三、重连可能直接被拒。** `EventSource` 只能发 GET，**也不能带自定义 header**。如果你的接口是 POST，或者鉴权靠 `Authorization`，重连会拿到 405 / 401，然后按浏览器的重试策略一直撞下去 —— 日志里是一串 401，看着像鉴权 bug。

还有一个小坑：`EventSource` 重连时会带上 `Last-Event-ID`（前提是服务端发过 `id:` 字段）。如果你的服务端没实现续传，这个 header 会被心安理得地忽略掉，连接从第 0 个字重新开始。

## 怎么改

### 一、把超时显式写出来

```java
// 不传 → 容器默认（Tomcat 30 秒）
SseEmitter bad = new SseEmitter();

// 0 → 不超时（Servlet 规范语义：0 或负数表示无超时）
SseEmitter alsoBad = new SseEmitter(0L);

// 推荐：比「最长生成时间」大一倍左右
SseEmitter good = new SseEmitter(300_000L);   // 5 分钟
```

`0L` 确实能解决问题，但**不建议**：不超时的连接泄漏了最难发现，客户端崩溃、网络半开的时候，这条连接会一直挂着。给一个明确上限，超了就断，比无限等好。

想全局改也行，`application.yml`：

```yaml
spring:
  mvc:
    async:
      request-timeout: 300s
```

它管的是所有 async 请求，不只是 SSE。单条连接要单独控制时，还是用 `new SseEmitter(ms)`。

### 二、发心跳，而且要能安全地停

心跳有两个作用：让中间的代理看到「这条连接还活着」，以及让前端知道连接没死。

```java
@Configuration
class SseConfig {
    // 共享一个调度器，别为每条连接 new 一个线程池
    @Bean(destroyMethod = "shutdown")
    ScheduledExecutorService sseScheduler() {
        return Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "sse-ping");
            t.setDaemon(true);
            return t;
        });
    }
}
```

```java
@RestController
@RequiredArgsConstructor
public class ChatStreamController {

    private final ScheduledExecutorService sseScheduler;
    private final ChatService chatService;

    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam("q") String q) {
        SseEmitter emitter = new SseEmitter(300_000L);

        ScheduledFuture<?> ping = sseScheduler.scheduleAtFixedRate(() -> {
            try {
                emitter.send(SseEmitter.event().comment("ping"));   // 注释帧，前端 onmessage 不会收到
            } catch (IOException | IllegalStateException ex) {
                // 客户端已断 / emitter 已结束，这一拍就到此为止
            }
        }, 15, 15, TimeUnit.SECONDS);

        emitter.onCompletion(() -> {
            ping.cancel(false);          // ← 不取消的话，连接断了心跳还在发
            chatService.cancelIfClientGone(...);
        });
        emitter.onTimeout(() -> {
            ping.cancel(false);
            emitter.complete();
        });
        emitter.onError(e -> ping.cancel(false));

        chatService.startStreaming(q, emitter);
        return emitter;
    }
}
```

三个回调都要挂，缺一个就有资源漏在外面。`onCompletion` 是唯一保证会执行的收尾点（正常结束、超时、报错最终都会走到它）。

### 三、断线时，决定「停」还是「继续」

这是设计问题，不是代码问题，先想清楚：

| 场景 | 客户端断开时该怎么做 |
| --- | --- |
| 一问一答的对话 | **取消上游**。没人看的结果没有价值，继续跑纯烧钱 |
| 生成报告 / 长文，用户可以稍后回来取 | **继续跑，结果落库**。SSE 只是「订阅」，断线只是退订 |

第二种就是任务化：生成过程放进独立任务（第 7 章的状态机），SSE 接口只负责订阅任务输出。这样重连时能接着推，而不是重新生成 —— 顺带把重复计费也解决了。

要在第一种场景下真的取消上游，得把「取消句柄」传下去。JDK 的 `HttpClient.sendAsync` 返回 `CompletableFuture`，`cancel(true)` 会中断这次交换（不同 JDK 版本的处理细节有差异，验证方式是断开后看上游连接数和账单）。Spring AI 的流式接口同理，取消订阅即可。

### 四、前端别用 `EventSource` 硬扛

如果接口需要鉴权，`EventSource` 基本没法用 —— 它带不了 header。

```js
// 只能 GET，只能靠 cookie 或 query 传身份
const es = new EventSource(`/api/chat/stream?q=${encodeURIComponent(q)}`);
es.onmessage = e => append(e.data);
```

把 token 放 query 字符串里能跑，但**它会落进 Nginx 的 access log** —— 一条拼好的 URL 就是一份明文密钥。真要这么干，至少确认访问日志里已经把 query 打了码。

需要 header 鉴权、或者要 POST，用 `fetch` + `ReadableStream` 自己解析 SSE 帧，大约是四十行代码，换来对「重试、取消、鉴权」的完全控制。

## 怎么预防

**一、超时值写进配置，别留默认。** 默认值 30 秒这个数字，是给「短请求」定的，不是给生成式接口定的。

**二、用长回答验收。** 「1+1 等于几」永远测不出这个 bug —— 它在 3 秒内就答完了。上线前让模型写一篇 1000 字的东西，盯着它跑完。

**三、监控连接时长分布。** 如果时长直方图在 30 秒处有个尖峰，或者有大量连接时长**精确等于**某个整数，那就是被超时切的，不是模型慢。这个指标比错误率有效得多 —— 因为整个过程中一条错误日志都没有。

**四、断线率和重连率分开打点。** 只统计「请求成功」会把「断线后重连成功」也算成成功，用户实际经历的是看了一遍重复内容。

**五、重连要有幂等键。** 同一个 `requestId` 重连，服务端应该识别出「这是同一次生成」，返回同一份结果，而不是再调一次模型。

> 这行代码很容易机器识别：`llm-lint` 的 [LLM004](https://github.com/pgy763/llm-lint/blob/main/docs/rules.md#llm004) 找没有超时的 `new SseEmitter()`，`--explain LLM004` 会给出改法和原因。

## 环境

| 组件 | 版本 |
| --- | --- |
| JDK | 17 / 21 |
| Spring Boot | 3.x（Spring MVC，非 WebFlux） |
| 容器 | 内嵌 Tomcat 10.1（async 默认超时 30 秒） |
| 前端 | `EventSource`（Chrome / Edge 实测） |
| 复现条件 | `new SseEmitter()` 不传超时，让模型输出超过 30 秒 |

> 30 秒是内嵌 Tomcat 的默认值，换容器（Jetty / Undertow）或换版本可能不同 —— 以你实际用的容器文档为准。
