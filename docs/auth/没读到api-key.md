# `OpenAIError: The api_key client option must be set`

![报错截图](/images/api-key-not-set.png)

## 报错

```
openai.OpenAIError: The api_key client option must be set either by passing
api_key to the client or by setting the OPENAI_API_KEY environment variable
```

报错信息看着像「你没传 key」，但你明明在代码里传了 `api_key=os.getenv("DASHSCOPE_API_KEY")`。

## 为什么

`os.getenv("DASHSCOPE_API_KEY")` 返回了 `None`，于是等于没传。

返回 `None` 只有三种可能，按出现频率排：

1. **`.env` 文件不存在**，或者名字不叫 `.env`（比如叫了 `.env.txt`、`env`）
2. **没调用 `load_dotenv()`**，或者调用顺序不对——在 `import` 时就把 `llm` 对象建好了，而 `load_dotenv()` 写在后面
3. **工作目录不对**——`load_dotenv()` 默认从**当前工作目录**找 `.env`，而不是从脚本所在目录找。你在项目根目录跑 `python src/agent/xx.py` 没问题，换到 IDE 里点运行（工作目录可能是 `src/agent`）就找不到了

第 3 条最坑，因为「同一份代码昨天还能跑」。

## 怎么改

先确认是哪种情况：

```python
import os
from dotenv import load_dotenv

load_dotenv(verbose=True)               # verbose=True 会打印它到底加载了哪个文件
print(os.getcwd())                      # 打印当前工作目录
print(repr(os.getenv("DASHSCOPE_API_KEY")))   # 用 repr，None 和空串都能看出来
```

**如果第二行打印 `None`** → 是情况 1 或 2。补上 `.env` 并确保 `load_dotenv()` 在**创建任何客户端之前**执行：

```python
import os
from dotenv import load_dotenv
from langchain_openai import ChatOpenAI

load_dotenv(verbose=True)               # 必须在 ChatOpenAI 之前

llm = ChatOpenAI(
    model="qwen-plus",
    api_key=os.getenv("DASHSCOPE_API_KEY"),
    base_url=os.getenv("DASHSCOPE_BASE_URL"),
)
```

**如果第二个 `print(os.getcwd())` 打印的不是项目根目录** → 是情况 3。指定绝对路径最省事：

```python
from pathlib import Path

load_dotenv(Path(__file__).resolve().parents[2] / ".env", verbose=True)
```

把 `.env` 的路径和脚本文件绑定，从此不管从哪里运行都能找到。

顺带一句：`load_dotenv()` 默认**不覆盖已存在的环境变量**。如果你系统里已经有一个同名但值不对的变量，它会被静默留住。要强制覆盖就加 `override=True`。

## 怎么预防

**在程序的唯一入口处集中加载配置，并在加载后立刻断言。**

```python
# config.py
import os
from dotenv import load_dotenv

load_dotenv(override=True)

def _require(name: str) -> str:
    value = os.getenv(name)
    if not value:
        raise RuntimeError(f"缺少环境变量 {name}，检查 .env 是否存在且已加载")
    return value

DASHSCOPE_API_KEY = _require("DASHSCOPE_API_KEY")
DASHSCOPE_BASE_URL = _require("DASHSCOPE_BASE_URL")
```

然后在别处 `from config import DASHSCOPE_API_KEY`。

好处是：**配置错了会在程序启动的第一秒炸掉**，而不是等到跑完一堆逻辑、调模型的时候才炸。报错信息也是你自己的，比 SDK 的 `api_key must be set` 直观得多。

## 环境

| 组件 | 版本 |
| --- | --- |
| Python | 3.13 |
| python-dotenv | 最新 |
| langchain-openai | 1.x |
| 操作系统 | Windows 11 |
