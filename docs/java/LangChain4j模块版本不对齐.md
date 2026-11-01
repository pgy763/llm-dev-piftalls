# `NoSuchMethodError: dev.langchain4j.model.openai.OpenAiChatModel...`

![报错原文](/images/java-nosuchmethoderror.png)

> 上图按本条「报错」原文渲染，文本逐字一致 —— 可以直接拿去和你的日志比对。

## 报错

```
java.lang.NoSuchMethodError: 'dev.langchain4j.model.openai.OpenAiChatModel$Builder
dev.langchain4j.model.openai.OpenAiChatModel$Builder.modelName(java.lang.String)'
```

或者这种：

```
java.lang.NoClassDefFoundError: dev/langchain4j/model/openai/OpenAiChatModel
```

**编译期一切正常** —— `mvn compile` 通过，IDEA 里也不飘红。一运行就炸，报的还是「方法不存在」。

而你明明照着官方文档写的。

## 为什么

**LangChain4j 不是一个 jar，是一堆 jar，你把它们配成了不同的版本。**

`dev.langchain4j` 下的模块各发各的版本号：`langchain4j`、`langchain4j-open-ai`、`langchain4j-spring-boot-starter`，以及各种 `langchain4j-xxx` 集成包。它们之间有**编译期的硬依赖关系**。

实战中最常见的三种配错：

| 配错方式 | 表现 |
| --- | --- |
| 只给一个模块写了版本，其他靠传递依赖 | Maven 替你选了个版本，但选的不是你预期的那个 |
| 主模块和集成模块跨了大版本 | 比如 `langchain4j` 用 1.x，`langchain4j-open-ai` 停在 0.x |
| 项目里同时有 Spring AI | 两边都拽了 reactor / Jackson，仲裁后选了个谁都不满意的版本 |

**「编译能过、运行报 `NoSuchMethodError`」是这类问题的典型特征** —— 编译时看到的是 A 版本的类，运行时加载的是 B 版本的类，方法签名对不上。

## 怎么改

### 第一步：先看清楚实际生效的版本

```bash
mvn dependency:tree -Dincludes=dev.langchain4j
```

输出会把每个 langchain4j 模块的版本列出来。**只要出现两个不同的版本号，就找到病根了。**

### 第二步：用 BOM 统一版本（推荐做法）

引入 BOM 之后，所有 langchain4j 模块都不用再各自写版本：

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>dev.langchain4j</groupId>
            <artifactId>langchain4j-bom</artifactId>
            <version>${langchain4j.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>dev.langchain4j</groupId>
        <artifactId>langchain4j</artifactId>
    </dependency>
    <dependency>
        <groupId>dev.langchain4j</groupId>
        <artifactId>langchain4j-open-ai</artifactId>
    </dependency>
</dependencies>
```

> `${langchain4j.version}` 以 Maven Central 上的最新稳定版为准。**不要用 `LATEST`，也不要写版本区间** —— 那只会让下次出问题时更难查。

### 第三步：还有冲突就排除

```bash
mvn dependency:tree -Dverbose | grep -A5 "dev.langchain4j"
```

`-Dverbose` 会显示「被省略的版本」，也就是 Maven 仲裁掉的那些。看到 `(version managed from X; omitted for conflict with Y)` 之类的行，就知道是谁把谁挤掉了。

## 怎么预防

**第一条：同一个生态的库，版本必须由 BOM 统一管。** 这不止是 LangChain4j 的问题，Spring AI、Jackson、Reactor 全都一样。

**第二条：看到这几个异常，第一反应是查依赖树，不是改代码。**

| 异常 | 大概率原因 |
| --- | --- |
| `NoSuchMethodError` | 版本不一致 —— 编译期和运行期看到了不同的类 |
| `NoClassDefFoundError` | 缺依赖，或依赖被 `exclude` 掉了 |
| `ClassNotFoundException` | 依赖没进 classpath，或 scope 配错（比如写成了 `provided`） |
| `AbstractMethodError` | 接口变了但实现类还是老的 |
| `IncompatibleClassChangeError` | 类变成了接口（或反过来），大版本跨越的典型症状 |

**共同点：这些全都是运行期异常，编译期不会提示。** 所以它们只会在启动或第一次调用时才暴露。

**第三条：别在一个项目里同时用 Spring AI 和 LangChain4j。** 功能重叠、依赖重叠、异常体系也重叠，排查问题时你分不清是谁抛的。二选一，写进团队规范。

**第四条：升级依赖后要跑一遍冒烟测试。** 三个必测项：基础对话、流式输出、Tool Calling。这三个是版本升级中最容易行为变化的地方。

## 环境

| 组件 | 版本 |
| --- | --- |
| LangChain4j | 1.x（模块间版本错配时触发） |
| 构建工具 | Maven 3.9+ |
| 复现条件 | 多个 langchain4j 模块各自写版本，或其中一个是传递依赖 |
