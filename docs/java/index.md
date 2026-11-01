---
title: Java 与框架集成
---

# Java 与框架集成

Spring AI / LangChain4j / Spring Boot 3，当前主攻 —— 共 **16** 条。

> 不用从头读。把报错里最独特的那个词拿去搜，或者对着下面这张表扫一眼。

| 报错关键词 | 一句话原因 | 文件 |
| --- | --- | --- |
| `org.springframework.core.task.TaskRejectedException: Executor [java.util.concurr` | 大模型调用是「慢 IO」，和普通业务接口根本不是一类任务 | [看 →](./%E5%B9%B6%E5%8F%91%E8%B0%83%E7%94%A8%E6%A8%A1%E5%9E%8B%E6%8A%8A%E7%BA%BF%E7%A8%8B%E6%B1%A0%E6%89%93%E6%BB%A1) |
| `SLF4J: Class path contains multiple SLF4J bindings.` | slf4j-api 只是门面（facade），真正写日志的实现叫 binding | [看 →](./%E5%A4%9A%E4%B8%AASLF4J%E7%BB%91%E5%AE%9A) |
| `这一条没有异常。 它是静默错误，靠账单和用户投诉才发现的，三个典型现象：` | AiServices.builder(...).build() 出来的是一个长生命周期对象 —— 你放在 @Bean 里… | [看 →](./%E5%A4%9A%E7%94%A8%E6%88%B7%E5%85%B1%E7%94%A8%E4%B8%80%E4%BB%BDChatMemory) |
| `java.net.http.HttpTimeoutException: request timed out` | HttpRequest.timeout() 是「整个请求」的超时，不是「迟迟没响应」的超时 | [看 →](./%E6%B5%81%E5%BC%8F%E8%AF%B7%E6%B1%82%E8%AE%BE%E4%BA%86%E6%80%BB%E8%B6%85%E6%97%B6) |
| `<html>` | 两层各有一个 1 MB 默认值，而且互不知情 层 配置项 默认值 --- --- --- Nginx client_max_body_size 1 MB Spring Boot spring.servlet.multipart.max-file-size 1 MB Spring Boot spring.servlet.multipart.max-request-size 10 MB Tomcat server.tomcat.max-swallow-size 2 MB 前两行的 1 MB 是巧合… | [看 →](./%E4%B8%8A%E4%BC%A0%E6%96%87%E6%A1%A3%E6%8A%A5413) |
| `APPLICATION FAILED TO START` | Spring AI 的 starter 自动配置给了你三样东西… | [看 →](./%E6%B3%A8%E5%85%A5ChatClient%E6%89%BE%E4%B8%8D%E5%88%B0Bean) |
| `com.fasterxml.jackson.databind.exc.InvalidDefinitionException: Java 8 date/time ` | 因为你用的不是 Spring 给的那个 ObjectMapper，是你自己 new 出来的那个 | [看 →](./%E8%87%AA%E5%AE%9A%E4%B9%89ObjectMapper%E4%B8%8D%E8%AE%A4%E6%97%B6%E9%97%B4%E7%B1%BB%E5%9E%8B) |
| `java.lang.LinkageError: loader constraint violation: loader (instance of` | 一句话：classpath 上同一个库出现了两份，JVM 先加载到哪份就用哪份… | [看 →](./classpath%E4%B8%8A%E4%B8%A4%E4%BB%BD%E5%90%8C%E4%B8%80%E4%B8%AA%E5%BA%93) |
| `com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException:` | 「OpenAI 兼容」不等于「字段完全一样」 | [看 →](./Jackson%E5%8F%8D%E5%BA%8F%E5%88%97%E5%8C%96%E6%9C%AA%E7%9F%A5%E5%AD%97%E6%AE%B5) |
| `java.lang.UnsupportedClassVersionError: com/example/Application has been compile` | 编译时用的是新 JDK，运行时用的是老 JDK | [看 →](./JDK%E7%89%88%E6%9C%AC%E4%B8%8D%E5%8C%B9%E9%85%8D) |
| `java.lang.NoSuchMethodError: 'dev.langchain4j.model.openai.OpenAiChatModel$Build` | LangChain4j 不是一个 jar，是一堆 jar，你把它们配成了不同的版本 | [看 →](./LangChain4j%E6%A8%A1%E5%9D%97%E7%89%88%E6%9C%AC%E4%B8%8D%E5%AF%B9%E9%BD%90) |
| `java.lang.IllegalArgumentException: No converter for` | Flux 是 Reactor 的类型，Spring MVC 不认识它 | [看 →](./MVC%E9%A1%B9%E7%9B%AE%E8%BF%94%E5%9B%9EFlux%E6%8A%A5%E9%94%99) |
| `# 直连应用端口 —— 逐字到达` | Nginx 默认开启 proxy_buffering on，它会先把上游的响应攒在自己的缓冲区里，攒够了、或者上游整个响应结束了… | [看 →](./Nginx%E7%BC%93%E5%86%B2%E5%AF%BC%E8%87%B4%E6%B5%81%E5%BC%8F%E5%A4%B1%E6%95%88) |
| `java.lang.NoClassDefFoundError: javax/servlet/http/HttpServletRequest` | Spring Boot 3 把基线抬到 Jakarta EE 9+… | [看 →](./SpringBoot3%E7%9A%84javax%E4%B8%8Ejakarta) |
| `org.springframework.web.context.request.async.AsyncRequestTimeoutException` | 第一层：SseEmitter 不给超时，用的是容器的默认值 new SseEmitter() 不传参时，timeout 是 null… | [看 →](./SSE%E8%BF%9E%E6%8E%A530%E7%A7%92%E6%96%AD%E5%BC%80) |
| `java.lang.IllegalStateException: block()/blockFirst()/blockLast() are blocking,` | WebFlux 靠少量线程撑住很多并发，前提是每个线程上都不许停 | [看 →](./WebFlux%E9%87%8Cblock%E6%B5%81%E5%BC%8F%E7%BB%93%E6%9E%9C) |
