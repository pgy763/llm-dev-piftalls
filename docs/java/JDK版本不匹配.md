# `UnsupportedClassVersionError: class file version 61.0`

![报错原文](/images/java-unsupportedclassversionerror.png)

> 上图按本条「报错」原文渲染，文本逐字一致 —— 可以直接拿去和你的日志比对。

## 报错

```
java.lang.UnsupportedClassVersionError: com/example/Application has been compiled by
a more recent version of the Java Runtime (class file version 61.0), this version of
the Java Runtime only recognizes class file versions up to 52.0
```

你把 Spring Boot 3 + Spring AI 的项目拉下来，`mvn spring-boot:run`，或者直接 `java -jar`，就得到这个。

代码一行没动，同事的机器能跑，你的不能。

## 为什么

**编译时用的是新 JDK，运行时用的是老 JDK。**

那串数字就是答案，拆开看：

- `class file version 61.0` —— 字节码的目标版本，由**编译时**的 JDK 决定
- `up to 52.0` —— **运行时**这个 JDK 能认的最高版本

对照表：

| class file version | 对应 JDK | 常见叫法 |
| --- | --- | --- |
| 52.0 | JDK 8 | 1.8 |
| 55.0 | JDK 11 | 11 |
| 61.0 | JDK 17 | 17 ← **Spring Boot 3.x 的最低要求** |
| 65.0 | JDK 21 | 21 |

所以上面那条报错的读法是：**代码是 JDK 17 编译的（61.0），但正在用 JDK 8 运行（最多认到 52.0）。**

**为什么 Java 后端接大模型时特别容易撞上这个：**

Spring Boot 3.x 要求 JDK 17 起步，Spring AI 和 LangChain4j 又都建在 Spring Boot 3 之上。而很多存量系统还停在 JDK 8 或 11 —— **也就是说，「给老系统接 AI」这件事的第一只拦路虎，往往不是模型，而是 JDK 版本。**

## 怎么改

三步，逐个确认。

### 第一步：搞清楚现在生效的到底是哪个 Java

```bash
java -version
javac -version
echo $JAVA_HOME
```

Windows 上重点看这三个。很常见的情况是：`java -version` 显示 8，`javac -version` 显示 17，而 `JAVA_HOME` 指向第三个地方 —— **三者不一致，报错就来了。**

先把机器上有几个 Java 找出来：

```bash
# Git Bash
where java
# 或者
ls "/c/Program Files/Java/"
```

### 第二步：统一到 JDK 17+

临时验证（当前终端会话有效）：

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-17"
export PATH="$JAVA_HOME/bin:$PATH"
java -version   # 确认输出 17
```

永久生效要改系统环境变量，改完**重新开一个终端**才作数。

### 第三步：构建配置也要对齐

`JAVA_HOME` 改了不代表 Maven 一定用新的。检查 pom：

```xml
<properties>
    <java.version>17</java.version>
    <maven.compiler.source>17</maven.compiler.source>
    <maven.compiler.target>17</maven.compiler.target>
</properties>
```

如果项目是 **Spring Boot 3.x，而 `java.version` 写着 8 或 11，会在编译期就报错**，根本走不到运行这一步。

> **Maven 用哪个 JDK 看的是 `JAVA_HOME`，不是 `PATH`。** 这两者不一致，就是「编译能过、运行报错」的经典成因。

## 怎么预防

**第一条：动手之前先确认三个值** —— `java -version`、`mvn -version`、pom 里的 `java.version`。三者必须一致。

**第二条：`mvn -version` 比 `java -version` 更有信息量**，它会明确打印 Maven 实际使用的 JDK：

```
Apache Maven 3.9.x
Maven home: ...
Java version: 17.0.x, vendor: ..., runtime: ...
```

**第三条：这个报错跟 AI 一点关系都没有。** 看到 `UnsupportedClassVersionError`，不要往「模型」「API」「密钥」上想，100% 是 JDK 版本问题。对着上面的数字表一查就知道差几级。

## 环境

| 组件 | 版本 |
| --- | --- |
| 报错方（运行时） | JDK 8 —— 最多认到 52.0 |
| 产出方（编译时） | JDK 17 —— 产出 61.0 |
| Spring Boot | 3.x（最低要求 JDK 17） |
| 复现条件 | 老环境 + 新的 Spring Boot 3 / Spring AI 项目 |
