# 把流式响应收集成整段，然后把堆撑爆

![报错原文](/images/java-outofmemoryerror.png)

> 上图按本条「报错」原文渲染，文本逐字一致 —— 可以直接拿去和你的日志比对。

## 报错

```
java.lang.OutOfMemoryError: Java heap space
	at java.base/java.util.Arrays.copyOf(Arrays.java:3537)
```

在它之前通常还有：

```
[GC (Allocation Failure) ... 0.8s]
java.lang.OutOfMemoryError: GC overhead limit exceeded
```

## 现象

单个请求测得好好的，**并发一上来就 OOM，重启后又能跑一阵**。堆 dump 里最大的一块是 `String` 或 `byte[]`，来源指向你的 controller。

## 为什么

流式的意义是「不把整段回答放进内存」。而这行代码把它又塞回去了：

```java
List<String> all = client.stream(req)
    .collectList()                 // ← 这里
    .block();
String answer = String.join("", all);
```

算一笔账：

| 项 | 数量 |
| --- | --- |
| 一段回答 | 约 3000 字 ≈ 6 KB |
| 一个 chunk 包装成 String 对象 | 额外几百字节 × 几百个 chunk |
| 并发 200 | 6 KB × 200 = 1.2 MB（看着不多） |

真正的杀手不是回答本身，而是**每个 chunk 都成了独立对象**，加上 Jackson 解析中间态、日志把每段都打出来、以及「顺便再存一份到内存缓存里对照」。堆里最贵的往往是这些旁路。

再加一个常见的放大器：**把每段 chunk 都打进日志**。

```java
.doOnNext(c -> log.info("chunk: {}", c))   // 200 并发 × 每秒几十条
```

日志框架的字符串拼接和缓冲，内存开销比 chunk 本身高一个数量级。

## 怎么改

### 一、边收边发，不留全量

```java
client.stream(req)
    .map(this::extractDelta)
    .filter(s -> !s.isEmpty())
    .doOnNext(emitter::send)      // 立刻转发给客户端
    .doOnComplete(() -> emitter.complete())
    .subscribe();
```

内存占用变成「一个 chunk 的大小」，与回答长度无关。

### 二、确实要全文，落存储不落堆

```java
StringBuilder sb = new StringBuilder();   // 只累计字符，不保留 chunk 对象
.doOnNext(c -> sb.append(c))
.doOnComplete(() -> objectStore.put(key, sb.toString()))
```

`StringBuilder` 只占最终大小的内存；比 `List<String>` 省得多。**如果回答有几 MB，就直接流式写文件。**

### 三、日志别再记全文

```java
.doOnNext(c -> {
    if (log.isDebugEnabled()) log.debug("chunk len={}", c.length());   // 只记长度
})
```

## 怎么预防

- 流式路径上禁止 `collectList` / `collectMap` / `String.join`，Review 时搜这几个词
- 单条回答长度设上限（比如 8K 字），超了就截断并告警 —— 这是产品决策，也是内存保护
- 压测必做一项：**200 并发各要一段 3000 字回答**，看堆曲线是否随并发线性上升。正常应该是平的

## 环境

| 组件 | 版本 |
| --- | --- |
| JDK | 17 / 21 |
| 堆 | 容器里常见 `-Xmx1g` |
| 触发条件 | 流式响应被聚合成整段 + 高并发 |
| 相关异常 | `OutOfMemoryError`、`GC overhead limit exceeded` |
