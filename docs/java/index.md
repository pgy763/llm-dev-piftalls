---
title: Java 与框架集成
---

# Java 与框架集成

Spring AI / LangChain4j / Spring Boot 3，当前主攻 —— 共 **24** 条。

> 不用从头读。把报错里最独特的那个词拿去搜，或者对着下面这张表扫一眼。

| 报错关键词 | 一句话原因 | 文件 |
| --- | --- | --- |
| `org.springframework.core.task.TaskRejectedException: Executor [java.util.concurr` | 大模型调用是「慢 IO」，和普通业务接口根本不是一类任务 | [看 →](./%E5%B9%B6%E5%8F%91%E8%B0%83%E7%94%A8%E6%A8%A1%E5%9E%8B%E6%8A%8A%E7%BA%BF%E7%A8%8B%E6%B1%A0%E6%89%93%E6%BB%A1) |
| `SLF4J: Class path contains multiple SLF4J bindings.` | slf4j-api 只是门面（facade），真正写日志的实现叫 binding | [看 →](./%E5%A4%9A%E4%B8%AASLF4J%E7%BB%91%E5%AE%9A) |
| `这一条没有异常。 它是静默错误，靠账单和用户投诉才发现的，三个典型现象：` | AiServices.builder(...).build() 出来的是一个长生命周期对象 —— 你放在 @Bean 里… | [看 →](./%E5%A4%9A%E7%94%A8%E6%88%B7%E5%85%B1%E7%94%A8%E4%B8%80%E4%BB%BDChatMemory) |
| `javax.net.ssl.SSLHandshakeException: PKIX path building failed:` | 这是证书链验证失败，不是网络不通、不是 key 错 | [看 →](./%E5%85%AC%E5%8F%B8%E4%BB%A3%E7%90%86%E5%AF%BC%E8%87%B4SSL%E6%8F%A1%E6%89%8B%E5%A4%B1%E8%B4%A5) |
| `org.springframework.web.context.request.async.AsyncRequestNotUsableException: Se` | SSE 是「服务端单向推送」，但连接的另一端随时会消失： 这不是 bug，是正常事件 | [看 →](./%E5%AE%A2%E6%88%B7%E7%AB%AF%E6%96%AD%E5%BC%80%E5%90%8E%E8%BF%98%E5%9C%A8%E5%86%99SSE) |
| `java.net.http.HttpTimeoutException: request timed out` | HttpRequest.timeout() 是「整个请求」的超时，不是「迟迟没响应」的超时 | [看 →](./%E6%B5%81%E5%BC%8F%E8%AF%B7%E6%B1%82%E8%AE%BE%E4%BA%86%E6%80%BB%E8%B6%85%E6%97%B6) |
| `<html>` | 两层各有一个 1 MB 默认值，而且互不知情 层 配置项 默认值 --- --- --- Nginx client_max_body_size 1 MB Spring Boot spring.servlet.multipart.max-file-size 1 MB Spring Boot spring.servlet.multipart.max-request-size 10 MB Tomcat server.tomcat.max-swallow-size 2 MB 前两行的 1 MB 是巧合… | [看 →](./%E4%B8%8A%E4%BC%A0%E6%96%87%E6%A1%A3%E6%8A%A5413) |
| `org.springframework.jdbc.CannotGetJdbcConnectionException: Failed to obtain JDBC` | 看这段代码，几乎每个人都写过： @Transactional 的语义是「整个方法一个事务」… | [看 →](./%E4%BA%8B%E5%8A%A1%E9%87%8C%E8%B0%83%E6%A8%A1%E5%9E%8B%E6%8B%96%E5%9E%AE%E8%BF%9E%E6%8E%A5%E6%B1%A0) |
| `java.lang.OutOfMemoryError: Java heap space` | 流式的意义是「不把整段回答放进内存」 | [看 →](./%E6%94%B6%E9%9B%86%E6%B5%81%E5%BC%8F%E5%93%8D%E5%BA%94%E5%AF%BC%E8%87%B4OOM) |
| `com.fasterxml.jackson.core.JsonParseException: Unexpected character ('<' (code 6` | 你要的是 JSON，拿到的是 HTML | [看 →](./%E7%BD%91%E5%85%B3%E8%BF%94%E5%9B%9EHTML%E8%A2%AB%E5%BD%93JSON%E8%A7%A3%E6%9E%90) |
| `APPLICATION FAILED TO START` | Spring AI 的 starter 自动配置给了你三样东西… | [看 →](./%E6%B3%A8%E5%85%A5ChatClient%E6%89%BE%E4%B8%8D%E5%88%B0Bean) |
| `com.fasterxml.jackson.databind.exc.InvalidDefinitionException: Java 8 date/time ` | 因为你用的不是 Spring 给的那个 ObjectMapper，是你自己 new 出来的那个 | [看 →](./%E8%87%AA%E5%AE%9A%E4%B9%89ObjectMapper%E4%B8%8D%E8%AE%A4%E6%97%B6%E9%97%B4%E7%B1%BB%E5%9E%8B) |
| `java.lang.LinkageError: loader constraint violation: loader (instance of` | 一句话：classpath 上同一个库出现了两份，JVM 先加载到哪份就用哪份… | [看 →](./classpath%E4%B8%8A%E4%B8%A4%E4%BB%BD%E5%90%8C%E4%B8%80%E4%B8%AA%E5%BA%93) |
| `com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException:` | 「OpenAI 兼容」不等于「字段完全一样」 | [看 →](./Jackson%E5%8F%8D%E5%BA%8F%E5%88%97%E5%8C%96%E6%9C%AA%E7%9F%A5%E5%AD%97%E6%AE%B5) |
| `java.lang.UnsupportedClassVersionError: com/example/Application has been compile` | 编译时用的是新 JDK，运行时用的是老 JDK | [看 →](./JDK%E7%89%88%E6%9C%AC%E4%B8%8D%E5%8C%B9%E9%85%8D) |
| `java.lang.NoSuchMethodError: 'dev.langchain4j.model.openai.OpenAiChatModel$Build` | LangChain4j 不是一个 jar，是一堆 jar，你把它们配成了不同的版本 | [看 →](./LangChain4j%E6%A8%A1%E5%9D%97%E7%89%88%E6%9C%AC%E4%B8%8D%E5%AF%B9%E9%BD%90) |
| `java.lang.IllegalArgumentException: No converter for` | Flux 是 Reactor 的类型，Spring MVC 不认识它 | [看 →](./MVC%E9%A1%B9%E7%9B%AE%E8%BF%94%E5%9B%9EFlux%E6%8A%A5%E9%94%99) |
| `# 直连应用端口 —— 逐字到达` | Nginx 默认开启 proxy_buffering on，它会先把上游的响应攒在自己的缓冲区里，攒够了、或者上游整个响应结束了… | [看 →](./Nginx%E7%BC%93%E5%86%B2%E5%AF%BC%E8%87%B4%E6%B5%81%E5%BC%8F%E5%A4%B1%E6%95%88) |
| `java.util.concurrent.TimeoutException: Did not observe any item or terminal sign` | Flux.timeout(Duration) 的语义常被误解，它不是「总时长上限」，而是： 两次信号之间的间隔不能超过这个值 关键点： 1. 它从订阅那一刻开始计时，不是从收到第一个字开始 2. 每次收到元素都会重置计时器 3. 超时后如果没有 fallback… | [看 →](./Reactor%E8%B6%85%E6%97%B6%E6%88%AA%E6%96%AD%E6%B5%81%E5%BC%8F) |
| `java.lang.NoClassDefFoundError: javax/servlet/http/HttpServletRequest` | Spring Boot 3 把基线抬到 Jakarta EE 9+… | [看 →](./SpringBoot3%E7%9A%84javax%E4%B8%8Ejakarta) |
| `org.springframework.web.context.request.async.AsyncRequestTimeoutException` | 第一层：SseEmitter 不给超时，用的是容器的默认值 new SseEmitter() 不传参时，timeout 是 null… | [看 →](./SSE%E8%BF%9E%E6%8E%A530%E7%A7%92%E6%96%AD%E5%BC%80) |
| `?????          ← 每个字都变成问号` | 两个独立的坑，经常同时出现： 坑一：text/event-stream 没带 charset 不带 charset 时，浏览器按 UTF-8 猜（现代浏览器默认行为），但中间某些代理/客户端库会按 ISO-8859-1 解… | [看 →](./SSE%E5%93%8D%E5%BA%94%E4%B8%AD%E6%96%87%E4%B9%B1%E7%A0%81) |
| `org.springframework.core.io.buffer.DataBufferLimitException: Exceeded limit on m` | WebClient 有个容易忽略的设计：它默认把响应读成一个完整对象 | [看 →](./WebClient%E7%BC%93%E5%86%B2%E8%B6%85%E9%99%90) |
| `java.lang.IllegalStateException: block()/blockFirst()/blockLast() are blocking,` | WebFlux 靠少量线程撑住很多并发，前提是每个线程上都不许停 | [看 →](./WebFlux%E9%87%8Cblock%E6%B5%81%E5%BC%8F%E7%BB%93%E6%9E%9C) |
