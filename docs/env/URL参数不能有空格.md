# `unexpected keyword argument 'charset '` —— URL 里等号两边的空格

![报错截图](/images/url-param-space.png)

## 报错

```
TypeError: create_engine() got an unexpected keyword argument 'charset '
```

报错里 `'charset '` 结尾有个**空格**，这就是全部线索。

## 为什么

连接串里的查询参数，等号两边不能有空格：

```python
# 错：等号两边有空格
"mysql+pymysql://root:123456@localhost:3306/graduation?charset = utf8mb4"
                                                                 ↑   ↑

# 错：连字符后有多余空格
"mysql+pymysql://root:123456@localhost:3306/graduation?charset= utf8mb4"
```

SQLAlchemy 解析 URL 时，**把参数名按字面量原样截取**，不做 trim。所以等号左边的 `charset ` （带尾空格）就成了参数名。

而 SQLAlchemy 处理「无法识别的查询参数」的方式是：**把它当作关键字参数传给底层驱动**。于是 `charset ` 变成了一个关键字参数，而 `create_engine` 不认识这个名字 → `unexpected keyword argument`。

**报错信息里参数名带空格，就是这个坑的标志。** 正常的关键字参数不会带空格。

同类变体：

| 错误写法 | 参数名变成 |
| --- | --- |
| `?charset = utf8mb4` | `charset ` |
| `?charset= utf8mb4` | 值变成 ` utf8mb4`（可能被忽略，更难查） |
| `? charset=utf8mb4` | ` charset` |
| `?charset=utf8mb4 &pool_size=5` | `charset` 的值变成 `utf8mb4 ` |

第 2 种和第 4 种最阴——**参数名对了，值里混进了空格**，不报错，但静默失效。

## 怎么改

```python
# 对：等号两边、& 两边、? 后面都不留空格
connection = "mysql+pymysql://root:123456@localhost:3306/graduation?charset=utf8mb4"
```

拼装时如果想可读一点，用 f-string 把参数部分单独拎出来：

```python
params = "charset=utf8mb4&pool_pre_ping=true"
connection = f"mysql+pymysql://{user}:{password}@{host}:{port}/{database}?{params}"
```

**改完先打印再传：**

```python
print(connection)
engine = create_engine(connection)
```

打印能立刻看出空格问题——`?charset = utf8mb4` 在终端里非常显眼。

## 怎么预防

**URL 是「格式敏感」的字符串，不是自然语言。拼完之后一定要打印确认。**

一条实用规则：**在 URL 里，任何空格都是 bug。** 无论是参数名、等号、值、还是 `&` 分隔符，都不该有空格。看到空格就去掉。

另外，如果参数多，可以用字典拼装避开手写：

```python
from urllib.parse import urlencode

params = {"charset": "utf8mb4", "pool_pre_ping": "true"}
connection = f"mysql+pymysql://{user}:{password}@{host}:{port}/{database}?{urlencode(params)}"
```

`urlencode` 会做正确的转义和拼接，从根上避免这类手滑。

## 环境

| 组件 | 版本 |
| --- | --- |
| Python | 3.13 |
| SQLAlchemy | 2.x |
| 驱动 | PyMySQL |
