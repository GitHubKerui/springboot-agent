---
status: 部分完成
---

# 修正指代消解取错历史轮次的问题

**影响范围**：`springboot-rag` 模块 `/chat` 与 GraphRAG 流式问答的查询构造阶段；不影响检索、融合与 LLM 调用。

## 1. 需求

`enrichWithContext()` 声称"取上一轮用户消息"，实际取到的是最早一条；且拼接的上下文没有长度限制。要求保留原实现作对照，在旁边补一份正确实现。

## 2. 改了什么

- [`springboot-rag/src/main/java/com/agent/springbootrag/rag/service/ChatService.java`](../../../../springboot-rag/src/main/java/com/agent/springbootrag/rag/service/ChatService.java)：新增 `MAX_CONTEXT_QUESTION_LENGTH` 常量；原 `enrichWithContext()` 标记 `@Deprecated` 保留；新增 `enrichWithContextV2()`；`ask()` 调用点切到 V2。
- [`springboot-rag/src/main/java/com/agent/springbootrag/rag/service/GraphChatService.java`](../../../../springboot-rag/src/main/java/com/agent/springbootrag/rag/service/GraphChatService.java)：同一逻辑同步处理，`askWithGraph()` 调用点切到 V2。

## 3. 怎么改

- 原逻辑：`history.stream().filter(USER).map(getText).findFirst()`。`MessageWindowChatMemory.get()` 按写入顺序返回（旧 → 新），`findFirst()` 命中的是**最早**一条用户消息，多轮对话会一直用第一轮的实体做指代。
- 新逻辑：保留原方法（`@Deprecated` + `@SuppressWarnings("unused")`），新增倒序遍历版本，从列表尾部向前找第一条 USER 消息；`extractOriginalQuestion()` 剥离模板后，超过 200 字符截断再拼接。异常仍降级为原始问题。

## 4. 为什么改

指代消解依赖"上一轮/最近一轮"的上下文，方向反了就会把首轮实体带进后续每一轮检索。倒序查找用 List 索引实现，比 stream 的 `findFirst` 语义更直接；截断则避免异常数据把整段模板灌进检索查询。

保留原方法是因为用户要求留作对照，且两个服务（ChatService / GraphChatService）行为需保持一致，方便后续统一删除。

## 5. 验证结果

- `mvn -B -ntp -pl springboot-rag -am -DskipTests clean package`：`BUILD SUCCESS`（强制重编译）。
- IDE 诊断报告中出现大量 `log` 找不到符号、final 字段未初始化，根因是 JDT LS 的 Lombok 处理器初始化失败（`Could not initialize class lombok.javac.Javac`，Lombok 1.18.39 在 JDK 25 下不可用），与本次改动无关；Maven 编译无此问题。
- **未验证**：多轮对话端到端效果。需要 Redis（ChatMemory）、Milvus、Ollama 同时可用。

## 剩余事项

- 启动 Redis / Milvus / Ollama 后做两轮对话验证：第一轮问"甲醇有什么危害"，第二轮问"它需要戴什么手套"，确认改写后的查询指向甲醇。
- `ChatClientConfig` 已默认挂 `MessageChatMemoryAdvisor`，`enrichWithContext` 手工拼历史属于重复注入；后续可评估删除手工拼接、交由 `rewrite` 处理。
- IDE 侧如需消除 Lombok 误报，可升级 Lombok 到支持 JDK 25 的版本（或调整 JDT LS 的 Lombok agent）。
