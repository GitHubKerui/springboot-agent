---
status: 部分完成
---

# 按 Spring AI 架构新增总 RAG 入口

**影响范围**：`springboot-rag` 模块新增 `/rag/v2/ask` 接口与一套 RAG 编排 service；现有 `ChatService`、`GraphChatService`、`ChatController` 及所有配置文件零改动。

## 1. 需求

按 `ChatService.java` 的业务要求，用 Spring AI 官方架构重新写一个更完善的总 RAG 入口 service。原源码保持不动。

## 2. 改了什么

- [`springboot-rag/src/main/java/com/agent/springbootrag/rag/service/SpringAiRagEntryService.java`](../../../../springboot-rag/src/main/java/com/agent/springbootrag/rag/service/SpringAiRagEntryService.java)：新增。构造器组装 `RetrievalAugmentationAdvisor`，`ask()` 负责发起调用并传 `ChatMemory.CONVERSATION_ID`。内部实现 `HybridDocumentRetriever`、`FusionRerankTopK`、`EhsQueryAugmenter` 三个官方扩展点。
- [`springboot-rag/src/main/java/com/agent/springbootrag/controller/SpringAiRagController.java`](../../../../springboot-rag/src/main/java/com/agent/springbootrag/controller/SpringAiRagController.java)：新增。暴露 `/rag/v2/ask`，与 `/rag/ask` 并列，方便对照。

`ChatService.java` 未做任何修改。

## 3. 怎么改

- 原逻辑：`ChatService.ask()` 手工串起 11 步。先自己读 `ChatMemory` 拼"上一轮问题"，再用 `extractOriginalQuestion()` 从完整模板里裁字符串；改写、扩展、检索、融合、精排、topK、上下文拼装、模板渲染、LLM 调用全部写在一个方法里。
- 新逻辑：按官方 Modular RAG 的扩展点拆开，交给 `RetrievalAugmentationAdvisor` 调度。

| 原步骤 | 新实现 |
| --- | --- |
| 手工拼历史 | `Query.history(...)` + `CompressionQueryTransformer`（项目已有 `compression` bean，此前未接线）；首轮跳过、多轮只取最近 6 条 |
| rewrite | `queryTransformers(...)` 里的 `rewrite` bean |
| expand | `queryExpander(expander)` |
| hybrid retrieve | `HybridDocumentRetriever`，把 Milvus + Neo4j 两路结果摊平 |
| join | 官方 `ConcatenationDocumentJoiner`（默认） |
| fusion / rerank / topK | `FusionRerankTopK` 实现 `DocumentPostProcessor` |
| semantic / graph 上下文 | `EhsQueryAugmenter` 实现 `QueryAugmenter`，沿用原 EHS/SDS 模板 |
| LLM 历史 | 仍由 `MessageChatMemoryAdvisor` 负责 |

关键点：`MessageChatMemoryAdvisor` 的 order 是 `HIGHEST_PRECEDENCE + 200`，远小于 RAG advisor 的默认 0。所以它先执行，把**原始用户问题**写入 `ChatMemory`，并注入历史；RAG advisor 之后才把检索上下文拼进最后一条 user message。下一轮历史里是干净问题，`extractOriginalQuestion()` 这类字符串裁剪不再需要。

## 4. 为什么改

- 手写拼接和官方 `ChatMemory` 重复注入历史，是之前发现的问题根源；官方 transformer 用 LLM 做指代消解，比拼一句"上一轮用户问的是…"更可靠。
- 各阶段落到官方接口后，替换检索源、换精排模型、改模板都只动一个组件，不必改主流程。
- 新增独立接口而不是替换 `ChatService`：用户要求原源码不动，且两套流程可对照验证。
- 只新增两个文件，不改配置、不加依赖。

## 5. 验证结果

- `mvn -B -ntp -pl springboot-rag -am -DskipTests clean package`：`BUILD SUCCESS`，29 个源文件全部编译通过。
- `git status`：确认仅多出上述两个未跟踪文件，原有 3 个已修改文件（`ChatService`、`GraphChatService`、`GraphRagService`）为本轮之前的用户改动。
- IDE 诊断：无 ERROR；4 条 WARNING。其中 2 条是全项目已有的 Lombok 注解处理器初始化失败（`Could not initialize class lombok.javac.Javac`），另 2 条是 JDT LS 对 `List::stream` / `Document::getText` 方法引用的 null-safety 提示。Maven `javac` 无警告。
- **未验证**：端到端问答效果。需要 Redis（ChatMemory）、Milvus、Neo4j、Ollama、BGE rerank 服务同时可用。

## 剩余事项与风险

- 启动外部依赖后，用同一个 `conversationId` 连问两轮，确认二代接口的指代消解与答案质量，并和 `/rag/ask` 对照。
- LLM 调用次数增加：压缩 + 改写 + 扩展 + 最终回答，最多 4 次（首轮跳过压缩为 3 次）。本地 Ollama 下延迟会明显上升。
- 官方 advisor 用 `CompletableFuture` 并行跑扩展后的多个查询；`RagService` / `GraphRagService` 若有共享可变状态需确认线程安全。
- `MultiQueryExpander` 产出的变体若与原始 query 文本完全相同，官方 `Collectors.toMap(Map.Entry::getKey, ...)` 会抛 duplicate key。这是官方实现的已知边界，未额外包裹。
- 旧会话（由 `ChatService` 产生）的 `ChatMemory` 里仍存着完整模板文本，压缩时会读到；需要新开 `conversationId` 才能看到干净效果。
