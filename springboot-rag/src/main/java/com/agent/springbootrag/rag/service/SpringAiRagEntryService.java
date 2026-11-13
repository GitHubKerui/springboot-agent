package com.agent.springbootrag.rag.service;

import com.agent.springbootrag.rag.rerank.RerankService;
import com.agent.springbootrag.rag.retriever.HybridRagService;
import com.agent.springbootrag.rag.until.ScoreFusion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.QueryAugmenter;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import org.springframework.ai.rag.preretrieval.query.expansion.MultiQueryExpander;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 按 Spring AI Modular RAG 架构实现的混合检索总入口。
 *
 * 历史不再手工拼接，交给 advisor 链与 CompressionQueryTransformer；
 * 检索、精排、上下文拼装分别落到官方扩展点。
 * 原 {@link ChatService} 保持不动，两者可并行对照。
 */
@Service
public class SpringAiRagEntryService {

    private static final Logger log = LoggerFactory.getLogger(SpringAiRagEntryService.class);

    private static final int TOP_K = 10;
    private static final int CONTEXT_LIMIT = 8;
    private static final int MAX_HISTORY_MESSAGES = 6;

    private static final PromptTemplate EHS_TEMPLATE = new PromptTemplate("""
            你是化学品安全专家（EHS / SDS 领域）。

            【问题】
            {question}

            【语义信息（Vector Knowledge）】
            {semantic}

            【知识图谱路径（Graph Knowledge）】
            {graph}

            -----------------------------
            请输出：
            1. 风险说明
            2. 处理步骤
            3. 法规依据
            """);

    private final ChatClient chatClient;
    private final RetrievalAugmentationAdvisor ragAdvisor;
    /* 
       当 Spring 容器中存在多个相同类型的 Bean 时@Qualifier 注解用于消除依赖注入（DI）时的歧义性     
    */
    public SpringAiRagEntryService(
            ChatClient chatClient,
            HybridRagService hybridRagService,
            RerankService rerankService,
            @Qualifier("compression") QueryTransformer compression,
            @Qualifier("rewrite") QueryTransformer rewrite,
            MultiQueryExpander expander
    ) {
        this.chatClient = chatClient;
        this.ragAdvisor = RetrievalAugmentationAdvisor.builder()
                // 查询转换层 (Query Transformers) —— 优化你的提问，首先通过 boundedCompression(compression) 对长对话上下文进行压缩，提取核心意图，防止历史无用信息干扰检索
                // 接着通过 rewrite 将其重写为更适合知识库检索的专业学术语或干净的关键词。
                .queryTransformers(List.of(boundedCompression(compression), rewrite))
                // 查询扩展层 (Query Expander) —— 查漏补缺 基于上一步优化后的问题，使用 MultiQueryExpander 生成 3~5 个意思相近但表述不同的新问题。
                // 目的：防止用户提问角度太单一而漏掉标准答案。例如，用户问“如何请假”，扩展器会同时生成“请假流程是什么”、“怎么提交请假申请”
                .queryExpander(expander)
                // （混合检索）milvus + neo4j
                .documentRetriever(new HybridDocumentRetriever(hybridRagService))
                // 文档后处理层 (Document Post-Processors) —— 精准精简 ，FusionRerankTopK 内部会利用倒排融合（RRF）算法将所有文档去重，
                // 然后调用外部的 rerankService（如 BAAI/bge-reranker 或 Cohere）对文档进行二次打分
                .documentPostProcessors(new FusionRerankTopK(rerankService))
                // 提示词增强层 (Query Augmenter) —— 组装上下文，将重排后保留的最精华文档，和用户的原始问题按照特定的模板（Template）拼接在一起。
                .queryAugmenter(new EhsQueryAugmenter())
                .build();
    }

    /**
     * 一次完整问答：指代消解 → 改写 → 扩展 → 混合检索 → 融合/精排 → EHS 提示词 → LLM。
     */
    public String ask(String question, String conversationId) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question 不能为空");
        }
        String cid = (conversationId == null || conversationId.isBlank())
                ? UUID.randomUUID().toString()
                : conversationId;

        log.info("[RAG-V2] 问题: {}, 会话: {}", question, cid);

        // Advisor一个智能拦截器（Interceptor）。当你调用大模型时，
        // Advisor 会在大模型真正接收到消息之前，或者大模型回答完之后，自动帮你插入一些额外的业务逻辑。
        // 记忆 advisor 的 order 远小于 RAG advisor，它先执行：注入历史、把「原始问题」写入 ChatMemory；
        // RAG advisor 随后才把检索上下文拼进最后一条 user message。
        // 所以下一轮历史里是干净的问题，不必再从整段模板里裁字符串。
        String answer = chatClient.prompt()
                .user(question)
                .advisors(spec -> spec
                        .param(ChatMemory.CONVERSATION_ID, cid)
                        .advisors(ragAdvisor))
                .call()
                .content();

        log.info("[RAG-V2] 回答长度: {}", answer == null ? 0 : answer.length());
        return answer;
    }

    /**
     * 首轮没有历史时跳过压缩，省一次无意义的 LLM 调用；
     * 多轮时只取最近若干条，避免 maxMessages=1000 把改写提示词撑爆。
     */
    private static QueryTransformer boundedCompression(QueryTransformer delegate) {
        return query -> {
            List<Message> history = query.history();
            if (history.size() <= 1) {
                return query;
            }
            int from = Math.max(0, history.size() - MAX_HISTORY_MESSAGES);
            Query trimmed = query.mutate().history(history.subList(from, history.size())).build();
            return delegate.transform(trimmed);
        };
    }

    /** 把 Milvus + Neo4j 两路结果摊平，交给官方 joiner 按 id 去重。 */
    private record HybridDocumentRetriever(HybridRagService hybridRagService) implements DocumentRetriever {
        @Override
        public List<Document> retrieve(Query query) {
            return hybridRagService.retrieve(query.text()).stream()
                    .flatMap(List::stream)
                    .toList();
        }
    }

    /** 粗排融合 + BGE 精排 + topK，对应原流程的 6~8 步。 */
    private record FusionRerankTopK(RerankService rerankService) implements DocumentPostProcessor {
        @Override
        public List<Document> process(Query query, List<Document> documents) {
            if (documents.isEmpty()) {
                return documents;
            }
            documents.forEach(doc -> doc.getMetadata().put("finalScore", ScoreFusion.calculate(doc)));
            List<Document> reranked = rerankService.rerank(query.text(), documents);
            return reranked.stream().limit(TOP_K).toList();
        }
    }

    /** 官方 ContextualQueryAugmenter 只支持单个 {context}，这里按业务拆成语义/图谱两段。 */
    private static final class EhsQueryAugmenter implements QueryAugmenter {
        @Override
        public Query augment(Query query, List<Document> documents) {
            String semantic = formatContext(documents, "milvus", "vectorScore", "");
            String graph = formatContext(documents, "neo4j", "graphScore", "GRAPH_PATH: ");
            return query.mutate()
                    .text(EHS_TEMPLATE.render(Map.of(
                            "question", query.text(),
                            "semantic", semantic,
                            "graph", graph)))
                    .build();
        }
    }

    private static String formatContext(List<Document> documents, String source,
                                        String scoreKey, String prefix) {
        return documents.stream()
                .filter(doc -> source.equals(doc.getMetadata().get("source")))
                .collect(Collectors.toMap(Document::getText, doc -> score(doc, scoreKey), (a, b) -> a))
                .entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(CONTEXT_LIMIT)
                .map(entry -> String.format("[%.3f] %s%s", entry.getValue(), prefix, entry.getKey()))
                .collect(Collectors.joining("\n"));
    }

    private static double score(Document doc, String key) {
        Object value = doc.getMetadata().get(key);
        return value == null ? 0.0 : Double.parseDouble(value.toString());
    }
}
