# 关键词检查「通过」了，SQL 执行却报语法错

![报错截图](/images/sql-check-miss.png)

## 现象

给 LLM 配了一个 SQL 安全检查工具，逻辑是「只放行 `SELECT` / `WITH` / `SHOW` 开头的语句」。

然后模型生成了这么一条：

```sql
select departments
```

检查工具判定：**通过**（它是 `select` 开头，也没有任何危险关键字）。

执行时：

```
(sqlalchemy.exc.ProgrammingError) (1064, "You have an error in your SQL syntax ...
near 'departments' at line 1")
```

检查工具形同虚设——它放行了一条根本跑不通的 SQL。

## 为什么

**纯文本的关键字检查只能验证「安全性」，不能验证「正确性」。**

这两件事需要分开看：

| 维度 | 问题 | 纯文本检查能答吗 |
| --- | --- | --- |
| 安全性 | 这条语句会不会写坏数据？ | 能。看开头是 `SELECT` 还是 `DELETE` 就够 |
| 正确性 | 这条语句语法对不对、表和列存在吗？ | **不能。必须问数据库** |

`select departments` 缺了 `FROM`，是语法错误——但**判断语法需要有解析器或数据库本身**，正则表达式做不到。

所以「用一个关键字白名单」去承担两个职责，必然漏掉一半。

这个坑的教训超出 SQL 本身：**Agent 的工具如果只做「看起来像不像」，就会给模型错误的信心。** 模型收到「检查通过」的反馈，会以为 SQL 没问题，然后一路错下去。

## 怎么改

**加上第二层：让数据库用 `EXPLAIN` 干跑一遍。**

```python
def validate_sql(self, sql: str) -> str:
    """用 EXPLAIN 验证语法/表/列是否正确，不真正执行查询。"""
    first_stmt = sql.split(";")[0].strip()
    try:
        with self.engine.connect() as conn:
            conn.execute(text(f"EXPLAIN {first_stmt}"))
        return "SQL 校验通过"
    except Exception as e:
        return f"SQL 校验失败：{e}\n请修正后重新提交。"
```

**`EXPLAIN` 的关键性质：它只让 MySQL 生成执行计划，不真正跑查询。**

但在生成计划的过程中，MySQL 会完整走一遍**解析和校验阶段**：

- 语法对不对
- 表存不存在
- 列存不存在
- 有没有歧义的字段名

任何一项有问题，当场抛异常。所以 `EXPLAIN` 是一个**零副作用的「干跑」**——既能验证正确性，又不会有任何数据改动。

两层防线的分工：

| 防线 | 检查什么 | 要不要连库 | 成本 |
| --- | --- | --- | --- |
| 关键字检查 | 是不是只读语句 | 不用 | 极低（纯字符串） |
| `EXPLAIN` 干跑 | 语法、表、列是否正确 | 要 | 一次轻查询 |

**顺序不能反**：先过关键字检查（挡住危险语句，绝不能让 `DROP TABLE` 走到 `EXPLAIN` 那一步），再过 `EXPLAIN`（挡住错误的查询）。

```python
def check_and_validate(self, sql: str) -> str:
    safety = self.check_sql(sql)              # 第一层：只读检查
    if "不通过" in safety:
        return safety
    return self.validate_sql(sql)             # 第二层：EXPLAIN 干跑
```

## 怎么预防

**工具返回「通过」之前，问自己一句：这个「通过」是基于什么信息判断的？**

如果答案是「我看了字符串长什么样」，那它只能证明**安全**，不能证明**正确**。

给 Agent 设计工具时，一个有用的分类：

| 工具类型 | 判断依据 | 能给出的保证 |
| --- | --- | --- |
| 文本检查类 | 字符串本身 | 「看起来没危险」 |
| 干跑类 | 真实系统的解析器 | 「系统能接受它」 |
| 执行类 | 真实系统的完整执行 | 「它真的成功了」 |

**把「看起来没问题」包装成「没问题」是最危险的**——因为它会让模型停止怀疑。

另一个同类场景，可以照这个思路检查你自己的工具设计：

- 「文件名合法吗」→ 只看扩展名不够，要试着创建并在失败时回滚
- 「JSON 格式对吗」→ 不要用正则，直接 `json.loads()` 一次
- 「代码能跑吗」→ 不要看语法关键词，用 `ast.parse()` 或编译一次

**能交给真正的解析器验证的，就不要自己写规则去猜。**

## 环境

| 组件 | 版本 |
| --- | --- |
| Python | 3.13 |
| LangChain | 1.x |
| SQLAlchemy | 2.x |
| 数据库 | MySQL 8.0 |
| 复现场景 | 自定义 SQL 工具类 + LangChain Agent |
