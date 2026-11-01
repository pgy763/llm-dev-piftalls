# 上了 Nginx 之后，流式回答整段一次性到达

![报错原文](/images/java-nginx-buffering.png)

> 上图按本条「报错」原文渲染，文本逐字一致 —— 可以直接拿去和你的日志比对。

## 现象

本地直连应用端口一切正常，`curl` 出去的字符是一个一个蹦的。一挂到 Nginx 后面，行为就变了：

```
# 直连应用端口 —— 逐字到达
curl -N -X POST http://127.0.0.1:8080/api/chat/stream -d '{"q":"写一段 200 字的产品介绍"}'

# 走 Nginx —— 点下去半天没反应，然后整段「哗」一下全出来
curl -N -X POST https://your-domain.com/api/chat/stream -d '{"q":"写一段 200 字的产品介绍"}'
```

**不报错，日志干干净净**，你只会怀疑是自己 SSE 代码写错了。

同一条链路还有第二种表现 —— 这次会报错，但报的是 Nginx 的超时：

```
2026/10/16 10:24:31 [error] 18231#0: *8 upstream timed out (110: Connection timed
out) while reading upstream, client: 10.0.0.7, server: your-domain.com,
request: "POST /api/chat/stream HTTP/1.1",
upstream: "http://127.0.0.1:8080/api/chat/stream", host: "your-domain.com"
```

浏览器侧偶尔能看到：

```
net::ERR_INCOMPLETE_CHUNKED_ENCODING 200 (OK)
```

（报错措辞可能因 Nginx 版本而异，以官方文档为准。）

## 为什么

**Nginx 默认开启 `proxy_buffering on`，它会先把上游的响应攒在自己的缓冲区里，攒够了、或者上游整个响应结束了，才往客户端转发。**

对静态资源、对普通 JSON 接口，这是纯粹的优化 —— 释放上游连接、屏蔽慢客户端。**但对 `text/event-stream` 就是阉割**：流式的全部价值就是「边生成边到达」，被中间层一攒，等于变回了同步接口。

两个默认值叠在一起，正好把这个现象解释完：

| 默认配置 | 默认值 | 对流式的后果 |
| --- | --- | --- |
| `proxy_buffering` | `on` | 响应被攒住，增量变成批量 |
| `proxy_buffer_size` / `proxy_buffers` | 4k/8k 起（随发行版略有差异） | 回答短于缓冲区 → 一次全出，看起来「完全没流式」 |
| `proxy_read_timeout` | `60s` | 大模型流式经常超过 60 秒 → 被切断 |
| `proxy_http_version` | `1.0` | 无法走 chunked，长连接/流式的传输层基础就没了 |

**「短回答完全没流式、长回答整段到达或 60 秒断」这两种表现，其实是同一个原因在两种缓冲区水位下的样子。**

顺带一个容易忽略的：如果 Nginx 开了 `gzip`，`text/event-stream` 进了 `gzip_types` 的话，压缩本身也要攒够一块数据才输出，会在 buffering 之上再加一层延迟。检查一下你的压缩白名单里有没有它。

## 怎么改

### 最小改动：只对这一个接口关掉缓冲

```nginx
location /api/chat/ {
    proxy_pass http://127.0.0.1:8080;

    proxy_http_version 1.1;                 # 用 1.1 才谈得上 chunked / keepalive
    proxy_set_header Connection "";         # 清掉，让 Nginx 自己管连接复用

    proxy_buffering off;                    # 关键这一行
    proxy_cache off;
    chunked_transfer_encoding on;

    proxy_read_timeout 300s;                # 流式回答经常 > 60s，别用默认值
    proxy_send_timeout 300s;
}
```

**别在 `http {}` 全局关 `proxy_buffering`** —— 那会让所有接口都失去上游连接释放的优化。按 `location` 精确关。

### 更稳的做法：让应用自己声明「这个响应别缓冲」

Nginx 认一个上游响应头 `X-Accel-Buffering`。应用发 `X-Accel-Buffering: no`，Nginx 就对这一个响应不做缓冲 —— 不用改运维配置，接口搬到哪个环境都自带这个行为。

Spring MVC 里要注意一点：**这个头必须在响应提交之前设好**，而 `SseEmitter` 是在方法返回之后才绑定到 `HttpServletResponse` 的，在 Controller 方法体里 `setHeader` 时机不对。用过滤器最省事：

```java
@Component
public class NoBufferHeaderFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {
        if (req.getRequestURI().startsWith("/api/chat/stream")) {
            res.setHeader("X-Accel-Buffering", "no");
        }
        chain.doFilter(req, res);
    }
}
```

Controller 照常这么写就行：

```java
@PostMapping(value = "/api/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter stream(@RequestBody ChatReq req) {
    SseEmitter emitter = new SseEmitter(0L);   // 0 = 不主动超时
    // ... 在别的线程里 emitter.send(...)
    return emitter;
}
```

`produces = TEXT_EVENT_STREAM_VALUE` 不是可选项。少了它，Spring 可能按 `application/json` 协商内容类型，前端 `EventSource` 直接不认。

### 改完怎么验证

```bash
# -N 关掉 curl 自己的输出缓冲，否则你看到的是 curl 在攒
curl -N -X POST https://your-domain.com/api/chat/stream \
     -H 'Content-Type: application/json' \
     -d '{"q":"从 1 数到 30，每个数字单独一行"}'
```

判断标准是**时间分布**，不是内容对不对：数字应该逐行、均匀地到达。如果全都挤在最后 0.1 秒，说明还有一层在缓冲。

想量化就加 `-w`：

```bash
curl -N -o /dev/null -w '首字节: %{time_starttransfer}s\n' ... 
```

## 怎么预防

**第一条：流式接口的验收必须打域名，不许直连应用端口。** 直连只能验证代码，验证不了链路。本地跑通、上线不流式，全部原因都在中间这层。

**第二条：网关和服务一起配。** 应用侧的读空闲超时（见 [`流式请求设了总超时`](流式请求设了总超时.md)）和 Nginx 侧的 `proxy_read_timeout` 是两个独立的开关，**只改一个等于没改**。记住 Nginx 默认 60 秒，它经常比你的业务超时更早生效。

**第三条：把「流式链路」写进上线检查清单。** 至少四项：`proxy_buffering off`、`proxy_read_timeout` 是否够长、`X-Accel-Buffering: no` 有没有发出去、压缩白名单里没有 `text/event-stream`。少一项就是这样一次上线。

**第四条：给「首字节时间」打点。** 服务端记「第一个 token 生成了」，网关侧记「客户端收到第一个字节」。**两个数差得离谱，中间层就在缓冲** —— 这是唯一能在没接到用户投诉前发现它的办法。

## 环境

| 组件 | 版本 |
| --- | --- |
| JDK | 17 / 21 |
| Spring Boot | 3.x（`SseEmitter` / `StreamingResponseBody` 均可复现） |
| Nginx | 1.24+（`proxy_buffering` 默认 `on`） |
| 复现条件 | 应用返回 `text/event-stream`，前面挂一层默认配置的 Nginx 反代 |
