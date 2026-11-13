package com.agent.springbootrag.rag.retriever;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class GraphRagService {

    private final Neo4jClient neo4jClient;

    /**
     * ReCall
     * @param question
     * @return
     */
    public List<Document> retriever(String question) {
        log.info("[Neo4j] 输入查询文本: '{}'", question);

        String cypher = """
            MATCH (c:Chemical)-[r]->(n)
            WHERE $question CONTAINS c.name
            WITH c, r, n,
                 coalesce(n.name, n.type) AS targetName
            WHERE targetName IS NOT NULL
            RETURN 
                c.name AS source,
                type(r) AS relation,
                targetName AS target,
                count(*) AS weight
        """;

        List<Document> result = neo4jClient.query(cypher)
                .bind(question)
                .to("question")
                .fetch()
                .all()
                .stream()
                .map(this::toDocument)
                .toList();
        log.info("[Neo4j] 检索到 {} 条图谱路径", result.size());
        return result;
    }

    /* 这是图检索结果与 Spring AI RAG 生态对接的关键适配层。
    Spring AI 的向量检索返回的是 List<Document>，这个方法一次只处理一条。
    为了让图谱检索的结果能和向量检索结果"同台竞技"（比如做 RRF 融合、重排），这里把每一行图结果也包装成 Document。 */
    private Document toDocument(Map<String, Object> row) {

        // ✔ 图谱权重（关系强度 / 命中次数 / 可扩展算法）
        double graphScore = computeGraphScore(row);
        /*
            从cyppher返回的字段里提取并填入 %占位符
         */
        String text = String.format(
                "source=%s, relation=%s, target=%s",
                row.get("source"),
                row.get("relation"),
                row.get("target")
        );

        Map<String, Object> metadata = new HashMap<>();

        // ✔ 重点：统一字段名
        metadata.put("graphScore", graphScore);

        // 可选：原始权重
        metadata.put("weight", row.get("weight"));

        return Document.builder()
                .text(text)
                .metadata(metadata)
                .build();
    }

    /*
    computeGraphScore 等于是做了个频率的对数计算当做权重 靠。 
     */
    private double computeGraphScore(Map<String, Object> row) {

        // ✔ 基础版本（后续可以升级 PageRank / NodeRank）
        Object weightObj = row.get("weight");

        double weight = weightObj == null ? 1.0 :
                Double.parseDouble(weightObj.toString());

        // 这里算对数的原因：
        /* 
        为什么用 log？ 因为 count(*) 可能长尾分布很严重（某条关系出现 100 次，另一条只出现 1 次）。
        直接线性使用会让高频关系完全压制低频关系。log(1+w) 做对数压缩，让权重增长变缓，避免少数高频路径"霸榜"。
        +1 是为了处理 weight=0 的边界（虽然 count(*) 至少为 1，但防御性写法 
        */
        return Math.log(1 + weight);
    }
}
