# `NoClassDefFoundError: javax/servlet/http/HttpServletRequest`

![报错原文](/images/java-noclassdeffounderror.png)

> 上图按本条「报错」原文渲染，文本逐字一致 —— 可以直接拿去和你的日志比对。

## 报错

启动阶段直接炸，栈顶通常落在某个第三方 jar 的 Filter / Interceptor 里：

```
java.lang.NoClassDefFoundError: javax/servlet/http/HttpServletRequest
    at com.example.oldlib.TraceFilter.doFilter(TraceFilter.java:31)
    at org.springframework.web.filter.OncePerRequestFilter.doFilter(...)
```

也可能是 `ClassNotFoundException: javax.servlet.http.HttpServletRequest`（措辞随容器和 JDK 版本略有差别，搜 `javax/servlet` 或 `javax.servlet` 都行）。

还有一类更容易看懵的，出现在请求真正打进来之后：

```
java.lang.ClassCastException: class org.apache.catalina.connector.RequestFacade
cannot be cast to class javax.servlet.http.HttpServletRequest
```

编译期则是这个：

```
程序包 javax.servlet 不存在
package javax.servlet does not exist
```

三种表现，同一个病根。

## 为什么

**Spring Boot 3 把基线抬到 Jakarta EE 9+，`javax.servlet.*` 整体改名 `jakarta.servlet.*`。** 内嵌的 Tomcat 10 只实现 jakarta，classpath 上按理不该再有 `javax.servlet-api`。

于是取决于那个老 jar 是怎么写的，你会看到三种死法：

| classpath 上有没有 `javax.servlet-api` | 结果 |
| --- | --- |
| 没有 | 加载 `TraceFilter` 时找不到父类/接口 → `NoClassDefFoundError` |
| 有（老 jar 自己带进来了） | 类能加载，但容器交给你的是 `RequestFacade`（实现 jakarta 接口），强转 javax 接口失败 → `ClassCastException` |
| 你自己抄了 Boot 2 的代码 | 编译期就报包不存在，反而是最好的一种 |

关键一点：**带进来 javax 依赖的通常不是你写的代码**，而是某个为 Boot 2 编译的 starter 或厂商 SDK。接大模型时特别容易撞上 —— 很多第三方「一键接入」的 spring-boot-starter 是 2022 年前后写的，那时候 Boot 3 还没发布。

常见的几个来源：

- **API 文档**：`springdoc-openapi` 的 1.x 是 javax、2.x 是 jakarta，版本号跨了一个整数就是这个意思
- **ORM**：MyBatis-Plus 从 3.5.3 之后另发了 `mybatis-plus-spring-boot3-starter`，用老坐标就会带回 javax（具体版本以官方文档为准）
- **任意厂商 SDK 的 starter**：看它 POM 里有没有 `javax.servlet-api`，有就是 Boot 2 时代产物

## 怎么改

### 第一步：先揪出是谁带进来的

```bash
mvn dependency:tree -Dincludes=javax.servlet:javax.servlet-api
mvn dependency:tree | grep -i "javax.servlet"
```

看到 `javax.servlet:javax.servlet-api:3.1.0`（对应 Tomcat 8）或 `4.0.1`（对应 Tomcat 9），就是 Boot 2 时代留下的东西。顺着 `mvn dependency:tree -Dverbose` 往上翻一层，能找到引入它的那个 starter。

### 第二步：换库，而不是排依赖

优先换支持 Boot 3 的版本。判断信号有两个：

- **artifactId 里带 `spring-boot3`**（不少国产生态这么做）
- **大版本跨了一个整数**（1.x → 2.x）

换不了再考虑 `<exclusion>`，但要清醒：

```xml
<dependency>
    <groupId>com.example</groupId>
    <artifactId>some-llm-starter</artifactId>
    <exclusions>
        <exclusion>
            <groupId>javax.servlet</groupId>
            <artifactId>javax.servlet-api</artifactId>
        </exclusion>
    </exclusions>
</dependency>
```

**排除只是让它「不再往 classpath 里塞 javax 的 jar」，它自己编译出来的 class 文件里写的仍然是 javax 的接口。** 所以绝大多数情况下，排除只把 `ClassCastException` 换成 `NoClassDefFoundError`，问题没解决。真正有效的场景只有一个：这个 javax 依赖是可选传递依赖，那个 jar 里其实没有任何类用到它。

### 第三步：改自己的 import（这一步最容易改错）

迁移时**不要**用 IDE 的全局替换 `javax` → `jakarta`。J2EE 那一批确实搬了，但 `javax.crypto`、`javax.sql`、`javax.net`、`javax.naming` 这些还在 JDK 里，改了直接编译不过，而且报错信息会把你带到完全无关的方向。

只替换这几个包：

```bash
# 安全的批量替换范围
javax\.(servlet|validation|persistence|annotation|websocket|transaction)
→ jakarta.$1
```

IntelliJ 里用 `Ctrl+Shift+R`（Replace in Files）配正则，勾上 `Regex`，范围限定在 `src/main`。

### 第四步：实在绕不开

有字节码转换工具（比如 Eclipse Transformer 那一类）能在构建期把 jar 里的 `javax` 引用改写成 `jakarta`。能跑通，但代价是三样：多一个构建步骤、库一升级就得重来、出问题时堆栈里的类名和源码对不上。

**当临时方案用，别当架构。** 一个还在往里塞 javax 的 SDK，说明它自己的维护节奏跟不上 Boot 3 —— 这种依赖迟早要换掉。

## 怎么预防

**第一条：新建 Boot 3 项目，先扫一遍 `javax`。** pom 和 `mvn dependency:tree` 里除了 JDK 自带的（`javax.crypto` 这类不走 Maven），一个都不该出现。

**第二条：接新 SDK 前花 30 秒看它的 POM。** 依赖里出现 `javax.servlet-api`、`javax.annotation-api`、`javax.validation` 任意一个，基本可以判定是 Boot 2 时代产物。这个判断比读它的 README 快得多。

**第三条：CI 里卡一道。**

```bash
if mvn -q dependency:tree | grep -q "javax.servlet"; then
  echo "发现 javax.servlet 依赖，Spring Boot 3 下启动会报 NoClassDefFoundError"
  exit 1
fi
```

放在 `mvn package` 之前跑，比上线后看日志便宜。

**第四条：升级顺序写死，别边改边试。** 先把所有 starter 换成 Boot 3 版本 → 再改自己的 import → 最后跑三个冒烟：**能启动、一个流式请求、一次 Tool Calling**。顺序反了，你会同时面对「依赖冲突」和「自己代码没改完」两种报错，分不清哪个是哪个。

## 环境

| 组件 | 版本 |
| --- | --- |
| Spring Boot | 3.x（基线 Jakarta EE 9+，3.2 起用 Tomcat 10.1） |
| JDK | 17 |
| 内嵌容器 | Tomcat 10.1（Servlet 6.0，只认 `jakarta.servlet`） |
| 构建工具 | Maven 3.9+ |
| 触发条件 | 引入了一个为 Boot 2 编译的 starter / 厂商 SDK，其传递依赖或字节码仍指向 `javax.servlet` |
