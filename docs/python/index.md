---
title: Python 与类型
---

# Python 与类型

早期 LangChain 阶段，不再新增 —— 共 **5** 条。

> 不用从头读。把报错里最独特的那个词拿去搜，或者对着下面这张表扫一眼。

| 报错关键词 | 一句话原因 | 文件 |
| --- | --- | --- |
| `TypeError: string indices must be integers, not 'str'` | 你的变量是个列表（或者字符串），但你用了字典的取法 | [看 →](./%E6%8A%8A%E8%BF%94%E5%9B%9E%E7%9A%84%E5%88%97%E8%A1%A8%E5%BD%93%E5%AD%97%E5%85%B8%E7%94%A8) |
| `langchain_core.exceptions.OutputParserException:` | 你要求模型输出 JSON，模型的"帮助性"人格又让它加上了开场白和结尾语——这是模型行为，不是 bug | [看 →](./%E7%BB%93%E6%9E%84%E5%8C%96%E8%BE%93%E5%87%BA%E8%A7%A3%E6%9E%90%E5%A4%B1%E8%B4%A5) |
| `AttributeError: 'ToolDemo' object has no attribute 'web_seacher'.` | 纯粹是拼写错误 | [看 →](./%E5%B1%9E%E6%80%A7%E5%90%8D%E6%8B%BC%E5%86%99%E9%94%99%E8%AF%AF) |
| `ModuleNotFoundError: No module named 'my_llm'` | 两种运行方式的工作目录和模块搜索路径不一样 | [看 →](./langgraph%E4%B8%8B%E6%A8%A1%E5%9D%97%E5%AF%BC%E5%85%A5%E5%A4%B1%E8%B4%A5) |
| `pydantic.errors.PydanticUserError: Field 'description' defined on a base class` | 这是 pydantic v2 的破坏性变更，v1 时代的老代码/老教程几乎必踩 | [看 →](./pydantic-v2%E5%BF%85%E9%A1%BB%E5%86%99%E7%B1%BB%E5%9E%8B%E6%B3%A8%E8%A7%A3) |
