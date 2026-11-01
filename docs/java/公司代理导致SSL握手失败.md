# 公司网络里调不通：`PKIX path building failed`

![报错原文](/images/java-sslhandshakeexception.png)

> 上图按本条「报错」原文渲染，文本逐字一致 —— 可以直接拿去和你的日志比对。

## 报错

```
javax.net.ssl.SSLHandshakeException: PKIX path building failed:
 sun.security.provider.certpath.SunCertPathBuilderException:
 unable to find valid certification path to requested target
	at java.base/sun.security.ssl.Alert.createSSLException(Alert.java:130)
```

## 现象

- 同一个 key、同一段代码，**在家里能跑，在公司跑不了**
- 或者反过来：本地 IDE 里能跑，打成 jar 部署到服务器跑不了
- `curl` 能通，Java 不通

## 为什么

这是**证书链验证失败**，不是网络不通、不是 key 错。

公司网络里常见的两种情况：

1. **SSL 拦截**：网关用自己的根证书重新签发了你访问的域名证书。浏览器因为装了公司根证书所以信任，JVM 不认
2. **自建中转站用了自签证书**：证书不在 JVM 的 `cacerts` 里

JVM 信任的是**自己的** `cacerts` 文件，不是操作系统的证书存储。这就是「浏览器能开、Java 报错」的根源。

## 怎么改

### 一、把公司根证书导入 JVM 信任库（推荐）

```bash
# 1. 导出公司根证书（从浏览器或 IT 那里拿 .cer / .crt）
# 2. 导入
keytool -importcert -alias company-root -file company-root.crt \
        -keystore "$JAVA_HOME/lib/security/cacerts" -storepass changeit
```

或者不动全局，用启动参数指向自己的信任库：

```bash
java -Djavax.net.ssl.trustStore=/etc/ssl/certs/app-truststore.jks \
     -Djavax.net.ssl.trustStorePassword=changeit \
     -jar app.jar
```

**Docker 镜像里要在构建时就做这一步**，而不是部署后手动敲 —— 否则下一个 Pod 又回到原点。

### 二、排查到底卡在哪一环

```java
SSLContext ctx = SSLContext.getInstance("TLS");
ctx.init(null, null, null);
// 或者更直接：
openssl s_client -connect api.example.com:443 -showcerts
```

`openssl` 输出的证书链里如果出现公司网关的名字（而不是目标服务商），就确认了是 SSL 拦截。

### 三、绝对不要做的事

```java
// 危险：接受所有证书，等于关掉 HTTPS 的全部保护
SSLContext ctx = SSLContext.getInstance("TLS");
ctx.init(null, new TrustManager[]{ new X509TrustManager() {
    public void checkClientTrusted(...) {}
    public void checkServerTrusted(...) {}
    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
}}, new SecureRandom());
```

这段代码经常出现在「先让它跑起来」的提交里，然后再也没删掉。**内网自签环境也要导入证书，而不是关验证。**

## 怎么预防

- 把「导入公司根证书」写进开发者上手文档的第一节，比任何口头传达都有效
- 基础镜像统一带 `cacerts`，别让每个服务各自维护
- 代码里禁止出现 `TrustAll`、`NoopHostnameVerifier`，用静态检查卡住

## 环境

| 组件 | 版本 |
| --- | --- |
| JDK | 17 / 21（信任库路径 `$JAVA_HOME/lib/security/cacerts`） |
| 触发条件 | 公司 SSL 拦截、自签证书、代理重签 |
| 相关异常 | `SSLHandshakeException`、`SunCertPathBuilderException`、`SSLPeerUnverifiedException` |
