# SSE 里英文正常、中文变乱码

![报错原文](/images/java-sse-charset.png)

> 上图按本条「报错」原文渲染，文本逐字一致 —— 可以直接拿去和你的日志比对。

## 现象

**没有异常，也不报错。** 流式接口返回的英文完全正常，中文变成下面两种样子之一：

```
?????          ← 每个字都变成问号
æµ‹è¯•æˆåŠŸ    ← UTF-8 字节被按 Latin-1 解释（mojibake）
```

第二种特别有迷惑性 —— 它看起来像「编码问题」，但你会先怀疑前端。

## 为什么

两个独立的坑，经常同时出现：

### 坑一：`text/event-stream` 没带 charset

```java
// 错
emitter.send(SseEmitter.event().data(chinese));
// 或
return Flux.just(chinese);   // 按默认 MediaType
```

不带 charset 时，浏览器按 **UTF-8 猜**（现代浏览器默认行为），但中间某些代理/客户端库会按 ISO-8859-1 解，于是出现 `æµ‹è¯•` 这种 mojibake。

### 坑二：写之前字符串被「转字节再转回来」

```java
// 错：默认平台编码
String s = new String(bytes);              // Windows 上就是 GBK
emitter.send(new String(chinese.getBytes(), StandardCharsets.ISO_8859_1));
```

`new String(bytes)` 不指定编码 = 用平台默认。**Linux 容器里是 UTF-8，Windows 开发机是 GBK** —— 所以「本地好的，上线也好的，某台机器上乱码」。

## 怎么改

### 一、响应类型显式带 charset

```java
private static final MediaType SSE_UTF8 =
        MediaType.valueOf("text/event-stream;charset=UTF-8");

@GetMapping(value = "/stream", produces = "text/event-stream;charset=UTF-8")
public SseEmitter stream() {
    SseEmitter emitter = new SseEmitter(0L);
    emitter.send(SseEmitter.event().data(chunk, MediaType.TEXT_PLAIN));
    return emitter;
}
```

WebFlux 里：

```java
@GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<String>> stream() { ... }
```

`MediaType.TEXT_EVENT_STREAM_VALUE` 同样不带 charset，复杂一点的场景手动写全。

### 二、所有字符串↔字节转换都指定编码

```java
new String(bytes, StandardCharsets.UTF_8)
str.getBytes(StandardCharsets.UTF_8)
```

**代码里不该出现无参的 `new String(bytes)` 和 `getBytes()`**，这条可以用静态检查卡住。

### 三、验证方式

```bash
curl -N -H "Accept: text/event-stream" http://localhost:8080/api/stream | head -c 200 | hexdump -C
```

中文「测试成功」的 UTF-8 字节是 `e6 b5 8b e8 af 95 e6 88 90 e5 8a 9f`。如果看到 `e6 b5 8b` 这种，说明字节是对的，问题在解的一端；如果看到 `3f 3f`，说明在写之前就已经变成问号了。

## 怎么预防

- `produces` 一律写全 charset，别用常量偷懒
- 无参 `new String(bytes)` / `getBytes()` 当成 lint 错误
- 上线前用中文做一次端到端冒烟（很多团队只在本地测英文用例）

> `llm-lint` 的 [LLM006](https://github.com/pgy763/llm-lint/blob/main/docs/rules.md#llm006) 专门找 `produces` 里少了 charset 的 SSE 接口，一条命令列出所有漏网的。

## 环境

| 组件 | 版本 |
| --- | --- |
| JDK | 17 / 21 |
| Spring Boot | 3.x |
| 触发条件 | SSE 响应未声明 charset，或跨平台编码不一致 |
| 相关异常 | 无（静默错误） |
