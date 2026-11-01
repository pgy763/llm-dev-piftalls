# `LinkageError: loader constraint violation ... for a different type`

![报错原文](/images/java-linkageerror.png)

> 上图按本条「报错」原文渲染，文本逐字一致 —— 可以直接拿去和你的日志比对。

## 报错

```
java.lang.LinkageError: loader constraint violation: loader (instance of
org/springframework/boot/devtools/restart/classloader/RestartClassLoader)
previously initiated loading for a different type with name
"com/fasterxml/jackson/databind/ObjectMapper"
```

措辞可能因 JDK / Spring Boot 版本而异，但 `LinkageError` + `loader constraint violation` + 那个类名这三段是稳定的，看到就能定性。

也可能长这样（同一个病根的另一种表现）：

```
java.lang.NoSuchMethodError: '...' 
	at com.example.llm.ChatService.call(ChatService.java:42)
```

具体是哪个方法取决于被顶掉的是哪个库 —— 但共同点是：**你代码里的调用没问题，编译期也过了**，是运行期的类和你以为的那个类不是同一个。

## 为什么

一句话：**classpath 上同一个库出现了两份，JVM 先加载到哪份就用哪份，另一份里那个方法/构造器自然对不上。**

Maven 不会让同一个 `groupId:artifactId` 的两个版本同时进 classpath —— 它会仲裁，只留一个。所以真正的原因是仲裁结果和你预期的不一样，或者压根没进仲裁：

| 来源 | 怎么发生的 |
| --- | --- |
| 手写版本号 | 为了让某个 CVE 补丁生效，在 `<dependencies>` 里给 `jackson-databind` 写了 `<version>`，但这个库本来由 Spring Boot 的 BOM 管着。你钉的那个版本和其它 Boot 组件（`jackson-datatype-jsr310`、`jackson-module-parameter-names`）版本错开，`ObjectMapper` 和它的模块互相不认识 |
| 传递依赖没被管到 | 某个老 SDK 或国内厂商的 Java SDK 把 `jackson-databind` 以 `compile` scope 带进来，而它不在 Boot 的 BOM 列表里 —— 仲裁时按「路径最短」选，你的直接依赖本来没写，就选了老 SDK 那条路径上的版本 |
| 多个 BOM 打架 | 父 POM `import` 了 `spring-boot-dependencies`，模块里又 `import` 了 `spring-ai-bom` 或 `langchain4j-bom`。**`dependencyManagement` 里先声明的赢**，import 的先后顺序会改变最终版本 |

第三个是最阴的：两个 BOM 都在「正确使用」，代码评审时谁看都觉得没问题，实际生效的版本由 `<dependencyManagement>` 里那几行的顺序决定 —— 而顺序是后来某个顺手调整缩进的人改的。

**为什么编译期发现不了：** 编译器只看得到你声明的那一个版本，javac 的 classpath 和运行时 fat jar 里的东西不是一回事；IDEA 里 Ctrl+点击跳进去的也是它以为的那个。真正做链接的是 JVM，链接发生在类加载时，所以它只会在启动或第一次调用到那个类时炸。

带 devtools 的项目会更早显形 —— `RestartClassLoader` 和父加载器各持有一份，JVM 链接时发现同一个类名对应两个不同的 `Class` 对象，直接抛 `LinkageError`。**这反而是好事**，比在生产上等一个 `NoSuchMethodError` 强。

## 怎么改

### 第一步：让冲突显形

```bash
mvn dependency:tree -Dincludes=com.fasterxml.jackson.core -Dverbose
```

不加 `-Dverbose` 只能看到仲裁后的版本，一列干干净净，看不出任何问题。加了之后会出现类似：

```
+- com.fasterxml.jackson.core:jackson-databind:jar:2.15.4:compile
|  \- (version managed from 2.15.4; omitted for duplicate)
\- com.vendor:some-sdk:jar:1.2.3:compile
   \- com.fasterxml.jackson.core:jackson-databind:jar:2.9.8:compile (omitted for conflict with 2.15.4)
```

措辞随 Maven 版本略有差异，`omitted for conflict` / `omitted for duplicate` / `version managed from` 这几个词是关键词。**看到同一个 artifact 出现两个版本号，病根就找到了。**

### 第二步：按冲突来源选一种修法

**① 删掉手写版本，交回 BOM（首选）。** 绝大多数情况你根本不需要指定 `jackson` 的版本。

**② 真要覆盖，写进 `<dependencyManagement>`，别写在 `<dependencies>`。**

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
            <version>2.17.2</version>
        </dependency>
    </dependencies>
</dependencyManagement>
```

区别很关键：写在 `<dependencies>` 里只钉住**直接依赖**，传递进来的那份照样是老版本；写进 `dependencyManagement` 才能管住整个依赖树。

**③ 排掉老 SDK 带进来的那份。**

```xml
<dependency>
    <groupId>com.vendor</groupId>
    <artifactId>some-sdk</artifactId>
    <version>1.2.3</version>
    <exclusions>
        <exclusion>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
        </exclusion>
    </exclusions>
</dependency>
```

**这一条有前提，别条件反射地用。** 如果那个 SDK 内部是靠反射调 Jackson 的（很多国内厂商 SDK 都这么干），你把它降到和 Boot 一致的版本后，它自己会在运行时炸 —— 只是异常栈换到了它的字节码里，更难查。排之前先用 `javap` 或源码确认它到底用了哪些 API。

**④ 多个 BOM 时，用 `dependencyManagement` 显式钉版本，别靠调整 import 顺序。** import 顺序的规则不会写在代码评审清单里，下一个人重排一下 pom 就全变了；显式钉一行，谁看都知道这是有意为之。

### 第三步：确认真的只剩一份

```bash
java -verbose:class -jar target/app.jar | grep ObjectMapper
```

真实输出形如 `[Loaded com.fasterxml.jackson.databind.ObjectMapper from file:/.../jackson-databind-2.17.2.jar]`。**同一个类出现两次、来自两个不同 jar，就是没修干净。**

## 怎么预防

**第一条：pom 里不出现第三方版本号。** Spring Boot 的 BOM 已经把 Jackson、Reactor、Netty、SLF4J 都管了；Spring AI 和 LangChain4j 也各有 BOM。三个生态各引一个 BOM，`<dependencies>` 里全部不写 `<version>` —— 这条规则能消掉这类问题的大半。

**第二条：打安全补丁时改 property，不要改 dependency。** `spring-boot-dependencies` 里的很多版本是通过 property 定义的（`jackson-bom.version` 这类），覆盖 property 是官方支持的姿势，能让整组相关库一起升 —— 具体 property 名以你用的 Boot 版本文档为准。逐个改 `dependency` 的写法，一定会漏掉 `jackson-datatype-jsr310` 这种配套模块。

**第三条：把重复类检查放进 CI。** `maven-enforcer-plugin` 的 `banDuplicateClasses` 规则能在构建阶段就拦下这类问题，比等到运行时抛 `LinkageError` 便宜得多。规则配置以 enforcer 官方文档为准，别照抄过时的 XML。

**第四条：升级依赖后跑冒烟测试，至少覆盖「基础对话 + 流式 + Tool Calling」。** 这三个是最容易被 Jackson / Reactor 版本变化影响的路径。

**第五条：看到 `NoSuchMethodError` / `LinkageError` 的第一反应是 `mvn dependency:tree`，不是改代码。** 改代码这条路走不通 —— 你以为在调 A，运行时拿到的是 B，怎么改都还是调 B。

## 环境

| 组件 | 版本 |
| --- | --- |
| JDK | 17 / 21 |
| Spring Boot | 3.x（BOM 生效） |
| 构建工具 | Maven 3.9+ |
| 触发条件 | 手写第三方版本号、老 SDK 传递依赖、多个 BOM 同时 import |
| 相关异常 | `LinkageError`、`NoSuchMethodError`、`AbstractMethodError`、`IncompatibleClassChangeError` |
