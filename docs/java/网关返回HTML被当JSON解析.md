# `JsonParseException: Unexpected character ('<' (code 60))`

![报错原文](/images/java-jsonparseexception.png)

> 上图按本条「报错」原文渲染，文本逐字一致 —— 可以直接拿去和你的日志比对。

## 报错

```
com.fasterxml.jackson.core.JsonParseException: Unexpected character ('<' (code 60)): expected a valid value
 (JSON String, Number, Array, Object or token 'null', 'true' or 'false')
 at [Source: (String)"<!DOCTYPE html>
<html>
<head><title>502 Bad Gateway</title></head>
```

看 `at [Source: (String)` 后面那段 —— **`<!DOCTYPE html>`**，这就是全部线索。

## 为什么

你要的是 JSON，拿到的是 HTML。常见的三种来源：

| 来源 | 典型 HTML |
| --- | --- |
| Nginx 网关错误 | `<title>502 Bad Gateway</title>` |
| 中转站/代理的登录页 | `<title>Sign in</title>` |
| 网关的限流页 | `<title>429 Too Many Requests</title>` |

**核心问题不是「解析失败」，而是「你拿到的是错误页却当成正常响应解析」**。状态码可能已经是 200（中间层改写），所以只看状态码发现不了。

`code 60` 就是 `<` 的 ASCII 码。记住这个数字，看到它基本可以直接判定「拿到 HTML 了」。

## 怎么改

### 一、解析前先判类型

```java
HttpResponse<String> resp = client.send(req, BodyHandlers.ofString());
String ct = resp.headers().firstValue("content-type").orElse("");

if (!ct.contains("application/json")) {
    throw new UpstreamException("上游返回的不是 JSON：" + ct + " → " +
        resp.body().substring(0, Math.min(200, resp.body().length())));
}
return mapper.readTree(resp.body());
```

### 二、错误信息里带上 body 前 200 字符

上例那样。**没有这段，你只会看到 `Unexpected character '<'`，然后花半小时怀疑序列化配置。**

### 三、探测性请求放在启动时

应用启动后做一次最小的 `/models` 或 `/chat/completions` 调用，校验响应是 JSON。配错 base_url 或 key 无效的站点，在启动时暴露比在第一个用户请求时暴露好。

## 怎么预防

- 所有出站 HTTP 调用统一走一个「先判 Content-Type 再解析」的入口，不要各处 `readTree`
- 日志里记录 `status + content-type + body 前 200 字符`，这三个字段能定位 90% 的上游异常
- 看到 `code 60` 时直接查网关日志，别再改 Jackson 配置

## 环境

| 组件 | 版本 |
| --- | --- |
| JDK | 17 / 21 |
| Jackson | 2.15+ |
| 触发条件 | 网关错误页、中转站登录页、代理拦截 |
| 相关异常 | `JsonParseException`、`MismatchedInputException`、`JsonMappingException` |
