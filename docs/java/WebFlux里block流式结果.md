# WebFlux 里对 `Flux` 调 `block()`，直接抛异常

![报错原文](/images/java-illegalstateexception.png)

> 上图按本条「报错」原文渲染，文本逐字一致 —— 可以直接拿去和你的日志比对。

## 现象

```
java.lang.IllegalStateException: block()/blockFirst()/blockLast() are blocking,
which is not supported in thread reactor-http-nio-3
```

（异常信息是一整行，这里为了排版折了行；不同 Reactor 版本措辞可能略有差异。）

典型触发场景：流式接口写好了，产品要一个「同样的回答、但一次性返回 JSON」的接口，图省事就在原方法上把流收集起来：

```java
@PostMapping("/chat")
public String chat(@RequestBody Req req) {
    // 想复用流式那段代码，就把 Flux<String> 收集成一段文本
    List<String> parts = chatClient.prompt()
            .user(req.q())
            .stream()
            .content()
            .collectList()
            .block();          // ← 在这里炸

    return String.join("", parts);
}
```

报错里的线程名是线索：`reactor-http-nio-*` 说明当前逻辑跑在 Netty 的 IO 线程上，而这里不允许阻塞。

还有一种更难受的变体：**不报错，只是吞吐莫名其妙掉到几乎串行**。那通常是在 `Mono.fromCallable` / `.map()` 里做了阻塞调用（JDBC、`Thread.sleep`、读文件），Reactor 检测不到。

## 为什么

**WebFlux 靠少量线程撑住很多并发，前提是每个线程上都不许停。**

IO 线程数默认是 `max(4, CPU 核数)`（可调 `reactor.netty.ioWorkerCount`）。模型调用要几秒到几分钟 —— 在这类线程上 `block()` 一次，就等于占掉这个核的全部服务能力那么久。8 核机器上 8 个这样的请求，整个服务（包括健康检查、其他接口）一起不动。

对比一下同一个请求在两种栈上的代价：

| | Servlet（MVC） | WebFlux |
| --- | --- | --- |
| 阻塞发生的位置 | 一个请求线程 | **IO 事件循环线程** |
| 阻塞时影响范围 | 只影响这一个请求 | **同一个线程上排队的所有请求** |
| 靠什么扛并发 | 线程数（贵，但可以堆） | 少量线程不阻塞（不能堆） |
| 阻塞一次的后果 | 慢 | 慢 + 可能全线卡死 |

所以 Reactor 选择**直接抛异常**，而不是放行让你慢慢查。这行异常其实是保护，不是 bug：它通过 `Schedulers.isInNonBlockingThread()` 判断当前线程，在非阻塞线程上调 `block()` 系列方法就拒绝执行。

真正危险的是它**没抛**的时候：只要你在别处 `subscribeOn` / 换过线程，`block()` 就合法了，阻塞照旧发生，只是没人拦你。异常消失不代表问题解决。

## 怎么改

按推荐顺序，三条路。

### 一、别聚合流（优先）

确认一下：真需要「流式接口 + 非流式接口」两份吗？多数时候只是想要一个能 `curl` 一下的调试接口，那用同步 API 就行 —— `call()` 和 `stream()` 在 Spring AI 里是两个方法，同步那条路内部自己处理线程，不会碰你的 IO 线程。

```java
@PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<String> stream(@RequestBody Req req) {
    return chatClient.prompt().user(req.q()).stream().content();
}

@PostMapping("/chat")
public String once(@RequestBody Req req) {
    return chatClient.prompt().user(req.q()).call().content();   // 同步调用，不要绕回流式
}
```

用流式 API 拼一个同步结果，等于先花一次流式开销、再自己写聚合，两头都不占好。

### 二、确实要聚合：把阻塞挪到弹性线程池

`block()` 的检查看的是**当前执行线程**，所以 `subscribeOn(boundedElastic)` 单独用没用 —— 检查先抛。要让整段逻辑跑在 elastic 线程上：

```java
@PostMapping("/chat")
public Mono<String> chat(@RequestBody Req req) {
    return Mono.fromCallable(() -> {
                // 这段在 boundedElastic 线程上执行，block() 检查通过
                List<String> parts = chatClient.prompt()
                        .user(req.q())
                        .stream()
                        .content()
                        .collectList()
                        .block();
                return String.join("", parts);
            })
            .subscribeOn(Schedulers.boundedElastic());
}
```

这只解决「不抛异常」，不解决「不浪费」。而且 `boundedElastic` 默认上限是 `10 × CPU 核数`、队列容量很大 —— 几十秒一次的模型调用照样能把它占满，然后开始排队。这是**换个地方堵**，不是解决。

### 三、整个服务本来是 MVC

那就别引 `spring-boot-starter-webflux`。两个 starter 同时在 classpath 上时，Spring Boot 默认起 Servlet 栈，WebFlux 的自动配置不生效，行为变得不可预期（相关表现见 [MVC项目返回Flux报错](MVC项目返回Flux报错.md)）。同理，反过来也别在 WebFlux 项目里随手加 `spring-boot-starter-web`。

### 什么时候 `block()` 是对的

批处理、定时任务、单元测试、CLI 工具 —— 这些根本不在 IO 线程上，`block()` 完全正当。别为了「响应式纯度」把十行能写完的脚本写成链式瀑布。判据只有一条：**当前线程是不是 Reactor 的非阻塞线程**。

## 怎么预防

**第一条：看线程名。** 日志里出现 `reactor-http-nio-`、`parallel-`、`boundedElastic-`，就说明在调度器线程上，不要做任何阻塞调用 —— 网络、磁盘、锁、`Thread.sleep`、JDBC 都算。

**第二条：用 BlockHound 在测试期抓。** 它会在运行期检测所有阻塞调用（包括 `synchronized`、`Thread.sleep`、JDBC 驱动），比等到线上抛 `IllegalStateException` 早得多：

```java
@BeforeAll
static void init() {
    BlockHound.install();
}
```

（依赖 `io.projectreactor.tools:blockhound`，JDK 13 之后需要额外加 JVM 参数放行自身 instrumentation，具体参数以官方 README 为准。）

**第三条：代码评审加一条机械规则。** classpath 上有 WebFlux 时，`src/main` 里出现 `.block(`、`.blockFirst(`、`.blockLast(` 就人工确认调用点。这条规则不高级，但能挡住 90%。

**第四条：别用流式 API 拼非流式结果。** 需要整段回答就用 `call()`，需要流就用 `stream()`。选对方法，这类问题根本不会出现。

**第五条：压测时看的是并发，不是单请求。** 单发一个请求，`block()` 只是慢；发 20 个并发，才能看出整个服务卡住。

## 环境

| 组件 | 版本 |
| --- | --- |
| Spring Boot | 3.x（`spring-boot-starter-webflux`） |
| Reactor | 3.6.x（`Schedulers.isInNonBlockingThread()` 的检查机制在 3.4+ 一致，异常措辞可能因版本而异） |
| LLM 客户端 | Spring AI `ChatClient` 的 `stream()` 返回 `Flux<String>`、`call()` 返回同步结果；LangChain4j 的流式需要自己包成 `Flux`（API 在 1.x 前后有调整，以官方最新文档为准） |
| 复现条件 | WebFlux 应用，在 Controller 的 IO 线程上对 `Flux`/`Mono` 调 `block()` |
