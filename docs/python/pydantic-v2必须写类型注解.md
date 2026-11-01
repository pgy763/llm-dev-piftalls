# `PydanticUserError: Field 'description' defined on a base class was overridden by a non-annotated attribute`

![报错截图](/images/pydantic-v2-annotation.png)

## 报错

```
pydantic.errors.PydanticUserError: Field 'description' defined on a base class
was overridden by a non-annotated attribute. Adding a type annotation is required.
```

你照着教程继承了 `BaseTool`，写了 `name` 和 `description`，然后就报这个。

## 为什么

**这是 pydantic v2 的破坏性变更，v1 时代的老代码/老教程几乎必踩。**

`BaseTool` 底层是 pydantic 模型。`name` 和 `description` 是它**已在基类中定义过的字段**。

pydantic v2 的新规则：**子类覆盖基类已定义的字段时，必须显式写类型注解。** 不带注解地重新赋值会被认为是「用普通类属性覆盖了模型字段」，属于错误用法。

```python
# 错（pydantic v1 时代能过，v2 不行）
class QuerySQLTool(BaseTool):
    name = "sql_db_query"
    description = "对 MySQL 数据库执行只读查询"

# 对（v2 要求带注解）
class QuerySQLTool(BaseTool):
    name: str = "sql_db_query"
    description: str = "对 MySQL 数据库执行只读查询"
```

区别就是那个 `: str`。

**为什么这个坑特别容易踩：**

- 中文教程大量基于 2024 年之前的 LangChain，那时的示例都省略注解
- 你自己写的新字段（比如 `db_manager`）如果**不带注解**反而不报错——因为它不是覆盖基类字段，是新增，pydantic 会按赋值推断类型。**于是同一个类里，有的字段要注解、有的不要，很容易混乱**
- 报错信息说得挺清楚，但如果你不认识 pydantic 就会一头雾水

**统一答案：全都加注解。** 别去记哪个要哪个不要。

## 怎么改

```python
from typing import Optional
from langchain_core.tools import BaseTool
from pydantic import Field

class QuerySQLTool(BaseTool):
    name: str = "sql_db_query"
    description: str = (
        "对 MySQL 数据库执行只读查询。"
        "输入必须是单条 SELECT 语句，不要包含 DML。"
        "返回 Markdown 表格。"
    )
    db_manager: "MySQLDatabaseManager"        # 自定义字段也加注解

    def _run(self, sql: Optional[str] = None) -> str:
        return self.db_manager.execute_query(sql)
```

如果字段有默认值，用 `Field` 写得更清楚：

```python
from pydantic import Field

class QuerySQLTool(BaseTool):
    name: str = Field(default="sql_db_query", description="工具名")
    description: str = Field(default="对 MySQL 执行只读查询", description="给模型看的说明")
    max_rows: int = Field(default=50, description="最多返回多少行")
```

**排查技巧**：如果你的类有一堆字段然后报错，先给**所有**字段加 `: 类型`，通常就过了。之后再逐个摘掉注解看哪个是必须的——但没必要，全加着更好。

## 怎么预防

**看到 `PydanticUserError` 或 `non-annotated attribute`，答案永远是「加类型注解」。**

记一条更通用的规则：

**pydantic v2 的世界里，所有模型字段都应该带类型注解。** 不只是继承场景——哪怕是全新写的模型，带注解也能让 IDE 补全、类型检查、`Field` 描述都工作起来。

另外给个「版本差异」的判断信号：

| 信号 | 说明 |
| --- | --- |
| 报错里有 `pydantic` | 先怀疑 pydantic v1 / v2 差异 |
| 报错里有 `deprecated` / `has been removed` | 一定是版本差异 |
| 教程能跑、你跑不了，代码一样 | 90% 是版本差异，去查依赖版本 |

**旧教程 + 新环境 = 新坑。** 遇到「代码明明一样却报错」，第一反应应该是查版本，而不是盯着代码看。

## 环境

| 组件 | 版本 |
| --- | --- |
| Python | 3.13 |
| pydantic | **v2**（v1 不会报这个错） |
| langchain-core | 1.x |
| 复现条件 | 照抄 pydantic v1 时代的教程 |
