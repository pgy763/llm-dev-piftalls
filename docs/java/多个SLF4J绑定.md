# `SLF4J: Class path contains multiple SLF4J bindings.`

![报错原文](/images/java-slf4j.png)

> 上图按本条「报错」原文渲染，文本逐字一致 —— 可以直接拿去和你的日志比对。

## 报错

SLF4J 1.7 线（`StaticLoggerBinder` 时代）大概是这个形状：

```
SLF4J: Class path contains multiple SLF4J bindings.
SLF4J: Found binding in [jar:file:/.../logback-classic-1.4.14.jar!/org/slf4j/impl/StaticLoggerBinder.class]
SLF4J: Found binding in [jar:file:/.../slf4j-simple-1.7.36.jar!/org/slf4j/impl/StaticLoggerBinder.class]
SLF4J: See http://www.slf4j.org/codes.html#multiple_bindings for an explanation.
```

SLF4J 2.x 线（换成 `ServiceLoader` 之后）**措辞不一样**：

```
SLF4J: Class path contains multiple SLF4J providers.
SLF4J: Found provider [org.slf4j.simple.SimpleServiceProvider@...]
SLF4J: Found provider [ch.qos.logback.classic.spi.LogbackServiceProvider@...]
SLF4J: See https://www.slf4j.org/codes.html#multiple_bindings for an explanation.
```

还有一种是**一个实现都没有**：

```
SLF4J: No SLF4J providers were found.
SLF4J: Defaulting to no-operation (NOP) logger implementation
```

> 以上是这类问题的典型形式，**具体措辞随 SLF4J 版本变化** —— 关键是那句 `multiple SLF4J bindings` / `multiple SLF4J providers` / `No SLF4J providers`，搜这句就能对上。

真正让人抓狂的地方：**应用不会启动失败。** 它只是日志行为整个变了 —— 控制台格式跟你配的不是一回事、`logging.level.xxx=debug` 不生效、文件 appender 一个字节都不写。

## 为什么

`slf4j-api` 只是门面（facade），真正写日志的实现叫 binding。**门面可以有多个消费者，实现只允许有一个。**

Spring Boot 的 `spring-boot-starter-logging` 已经带了 `logback-classic`。你引入某个第三方库（LLM SDK、各类工具库都常见）时，如果它自己的依赖树里也带了一个 binding，classpath 上就出现了两个。Maven 不会报错 —— 这是合法的依赖组合，冲突发生在**运行期 SLF4J 自己挑实现**的那一刻。

关键是挑的规则：**不是「后引入的赢」，是按 classpath 顺序取一个，而这个顺序你控制不了。**

所以现象往往是「本地是对的，换个环境就变了」，打包成 fat jar 之后顺序又被重排一遍，再变一次。这种「环境一变行为就变」的问题最耗排查时间。

## 怎么改

### 第一步：定位第二个 binding 是谁带进来的

```bash
mvn dependency:tree -Dincludes=org.slf4j,ch.qos.logback,org.apache.logging.log4j
```

看到两个及以上 binding（比如同时有 `ch.qos.logback:logback-classic` 和 `org.slf4j:slf4j-simple`），顺着树往上看是哪一层拽进来的。

### 第二步：排除它

```xml
<dependency>
    <groupId>com.example</groupId>
    <artifactId>some-llm-sdk</artifactId>
    <version>${sdk.version}</version>
    <exclusions>
        <exclusion>
            <groupId>org.slf4j</groupId>
            <artifactId>slf4j-simple</artifactId>
        </exclusion>
    </exclusions>
</dependency>
```

排除的是 **binding（实现）**，**不要排除 `slf4j-api`** —— 那是所有日志库共用的门面，排掉它会连锁炸一串 `ClassNotFoundException`。

### 第三步：确认只剩一个

重跑 `dependency:tree`，确认 binding 只剩 `logback-classic`。然后**必须验三件事**，缺一件都可能漏掉：控制台格式、`logging.level.xxx=debug` 是否真的生效、文件 appender 是否正常写。只看到日志有输出就以为修好了，是最容易翻车的地方。

### 兜底：不排依赖树，主动统一

如果那个 binding 是硬依赖排不掉，或者你本来就想用 log4j2，那就**主动统一**：把 `spring-boot-starter-logging` 换成 `spring-boot-starter-log4j2`，让 Spring Boot 那条配置线接管。别让两个实现平着共存 —— 那是把「能跑」建立在 classpath 顺序上。

## 怎么预防

**第一条：新增任何第三方依赖后，扫一眼依赖树里有没有第二个 binding。** 这条纪律不只对 SLF4J 成立 —— Jackson、Reactor、Guava 全都是同类问题。不是「出问题了才看树」，是「新依赖进来就看树」。

**第二条：门面和实现分开记。** 门面永远只有 `org.slf4j:slf4j-api`；实现（binding）只能有一个：

| binding | 什么时候见到它 |
| --- | --- |
| `ch.qos.logback:logback-classic` | Spring Boot 默认，绝大多数情况下应该是它 |
| `org.apache.logging.log4j:log4j-slf4j2-impl` | 用 log4j2 时的桥接实现 |
| `org.slf4j:slf4j-simple` | 简单实现，很多小库顺手带进来 |
| `org.slf4j:slf4j-jdk14` | 转发到 JDK 自带 JUL，老库常见 |

**第三条：「本地正常、打包后异常」的日志问题，先怀疑 classpath 顺序。** fat jar 会把所有 jar 平铺重组，顺序和 IDE 里不一样 —— 这正是这类坑「在我机器上没问题」的根源。

**第四条：CI 加一条启动检查。** 应用启动日志里出现 `multiple SLF4J` 或 `No SLF4J providers` 就让构建失败。这类问题不会自愈，只会在别人机器上变得更奇怪。

## 环境

| 组件 | 版本 |
| --- | --- |
| SLF4J | 2.x（1.7 线措辞不同，见「报错」一节） |
| Spring Boot | 3.x（`spring-boot-starter-logging` 默认带 logback-classic） |
| 构建工具 | Maven 3.9+ |
| 复现条件 | classpath 上同时存在多个 SLF4J binding / provider，或一个都没有 |
