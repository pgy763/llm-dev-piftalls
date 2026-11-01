# 用户关掉页面后，日志里刷 `AsyncRequestNotUsableException`

![报错原文](/images/java-asyncrequestnotusableexception.png)

> 上图按本条「报错」原文渲染，文本逐字一致 —— 可以直接拿去和你的日志比对。

## 报错

```
org.springframework.web.context.request.async.AsyncRequestNotUsableException: ServletOutputStream failed to write
	at org.springframework.web.context.request.async.StandardServletAsyncWebRequest$AsyncWebRequestWrapper...
```

有时以另一种形态出现：

```
java.io.IOException: Broken pipe
```

## 现象

**只有用户主动关页面、切网络、刷新时出现**，功能本身没坏，但日志里 ERROR 一片，告警被刷爆。

## 为什么

SSE 是「服务端单向推送」，但连接的另一端随时会消失：

```
用户关闭标签页 → TCP 断开 → 容器的输出流已关闭
        ↓
应用还在按心跳/模型分片往里写
        ↓
写失败 → 容器抛 AsyncRequestNotUsableException
```

这不是 bug，是**正常事件**。问题在于大多数人把它当成异常处理了。

顺带一个版本差异：Spring Framework 6.0 之前这里抛的是 `ClientAbortException` 或 `IOException: Broken pipe`，6.0 之后统一成了 `AsyncRequestNotUsableException`。所以搜报错时要两种都搜。

## 怎么改

### 一、注册回调，拿到「对端消失」的信号

```java
SseEmitter emitter = new SseEmitter(0L);   // 0 = 不超时，见另一条

emitter.onCompletion(() -> cancelled.set(true));
emitter.onError(e -> cancelled.set(true));
emitter.onTimeout(() -> cancelled.set(true));
```

### 二、写之前检查状态

```java
if (cancelled.get()) {
    return;                 // 别写了，也别取消上游——让它跑完记完账
}
emitter.send(SseEmitter.event().data(chunk));
```

### 三、把这个异常从 ERROR 降级

```java
@ExceptionHandler(AsyncRequestNotUsableException.class)
public void onClientGone(AsyncRequestNotUsableException e) {
    log.debug("客户端已断开，忽略：{}", e.getMessage());
}
```

**别在这里做业务回滚** —— 用户断开不代表这次生成要作废，token 已经花了，账要照记。

## 怎么预防

- 流式接口必须有「对端消失」的处理路径，作为验收项之一
- 心跳间隔别太短：**15 秒一次足够**，1 秒一次只会在断开时制造更多异常
- 监控上把这类异常单独计数，不计入错误率 —— 否则错误率会被用户行为带偏

## 环境

| 组件 | 版本 |
| --- | --- |
| JDK | 17 / 21 |
| Spring Boot | 3.x |
| 触发条件 | 客户端在流式响应过程中断开 |
| 相关异常 | `AsyncRequestNotUsableException`、`ClientAbortException`、`IOException: Broken pipe` |
