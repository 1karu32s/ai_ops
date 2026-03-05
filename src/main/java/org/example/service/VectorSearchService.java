package org.example.service;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.SearchResults;
import io.milvus.param.R;
import io.milvus.param.dml.SearchParam;
import io.milvus.response.SearchResultsWrapper;
import lombok.Getter;
import lombok.Setter;
import org.example.constant.MilvusConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 向量搜索服务
 * 负责从 Milvus 中搜索相似向量，并使用 Rerank 进行重排
 */
@Service
public class VectorSearchService {

    private static final Logger logger = LoggerFactory.getLogger(VectorSearchService.class);

    @Autowired
    private MilvusServiceClient milvusClient;

    @Autowired
    private VectorEmbeddingService embeddingService;

    @Autowired
    private DocMetadataService docMetadataService;

    @Autowired
    private RerankService rerankService;

    /**
     * 搜索相似文档（只返回活跃版本）+ Rerank 重排
     *
     * @param query 查询文本
     * @param topK 返回最相似的K个结果
     * @return 搜索结果列表
     */
    public List<SearchResult> searchSimilarDocuments(String query, int topK) {
        try {
            logger.info("开始搜索相似文档, 查询: {}, topK: {}", query, topK);

            // 1. 获取活跃版本 ID 列表
            Set<Long> activeVersionIds = docMetadataService.getActiveVersionIds();
            logger.debug("活跃版本数量: {}", activeVersionIds.size());

            // 2. 将查询文本向量化
            List<Float> queryVector = embeddingService.generateQueryVector(query);
            logger.debug("查询向量生成成功, 维度: {}", queryVector.size());

            // 3. 构建搜索参数（多取一些，用于 Rerank）
            int recallTopK = topK * 3; // 召回阶段多取一些
            SearchParam searchParam = SearchParam.newBuilder()
                    .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                    .withVectorFieldName("vector")
                    .withVectors(Collections.singletonList(queryVector))
                    .withTopK(recallTopK)  // 多取一些，用于 Rerank
                    .withMetricType(io.milvus.param.MetricType.L2)
                    .withOutFields(List.of("id", "content", "metadata"))
                    .withParams("{\"nprobe\":10}")
                    .build();

            // 4. 执行搜索
            R<SearchResults> searchResponse = milvusClient.search(searchParam);

            if (searchResponse.getStatus() != 0) {
                throw new RuntimeException("向量搜索失败: " + searchResponse.getMessage());
            }

            // 5. 解析搜索结果并过滤活跃版本
            SearchResultsWrapper wrapper = new SearchResultsWrapper(searchResponse.getData().getResults());
            List<SearchResult> allResults = new ArrayList<>();

            for (int i = 0; i < wrapper.getRowRecords(0).size(); i++) {
                SearchResult result = new SearchResult();
                result.setId((String) wrapper.getIDScore(0).get(i).get("id"));
                result.setContent((String) wrapper.getFieldData("content", 0).get(i));
                result.setScore(wrapper.getIDScore(0).get(i).getScore());

                // 解析 metadata
                Object metadataObj = wrapper.getFieldData("metadata", 0).get(i);
                if (metadataObj != null) {
                    result.setMetadata(metadataObj.toString());
                }

                allResults.add(result);
            }

            // 6. 过滤：只保留活跃版本的结果
            List<SearchResult> filteredResults = new ArrayList<>();
            for (SearchResult result : allResults) {
                // 从 metadata 中提取 versionId
                Long versionId = extractVersionId(result.getMetadata());
                if (versionId == null || activeVersionIds.contains(versionId)) {
                    filteredResults.add(result);
                }
            }

            logger.info("向量召回完成, 召回数量: {}, 过滤后: {}", allResults.size(), filteredResults.size());

            // 打印召回结果（用于调试）
            for (int i = 0; i < Math.min(5, filteredResults.size()); i++) {
                SearchResult sr = filteredResults.get(i);
                String preview = sr.getContent() != null ?
                    (sr.getContent().length() > 50 ? sr.getContent().substring(0, 50) + "..." : sr.getContent()) : "";
                logger.info("  [召回] #{}/{} score={} content={}",
                    i + 1, filteredResults.size(), sr.getScore(), preview);
            }

            // 7. Rerank 重排
            if (filteredResults.size() > topK) {
                logger.info("开始 Rerank 重排, 待重排数量: {}", filteredResults.size());
                List<String> documents = filteredResults.stream()
                        .map(SearchResult::getContent)
                        .toList();

                List<RerankService.RerankResultItem> reranked = rerankService.rerank(query, documents);

                // 8. 组装重排后的结果
                List<SearchResult> finalResults = new ArrayList<>();
                for (RerankService.RerankResultItem rr : reranked) {
                    int originalIndex = rr.getIndex();
                    if (originalIndex < filteredResults.size()) {
                        SearchResult sr = filteredResults.get(originalIndex);
                        sr.setScore((float) rr.getRelevanceScore()); // 使用 Rerank 分数
                        finalResults.add(sr);
                    }
                }

                // 打印重排结果（用于调试）
                logger.info("Rerank 重排完成, 最终返回: {} 条", finalResults.size());
                for (int i = 0; i < finalResults.size(); i++) {
                    SearchResult sr = finalResults.get(i);
                    String preview = sr.getContent() != null ?
                        (sr.getContent().length() > 50 ? sr.getContent().substring(0, 50) + "..." : sr.getContent()) : "";
                    logger.info("  [重排] #{}/{} rerank_score={} content={}",
                        i + 1, finalResults.size(), sr.getScore(), preview);
                }

                return finalResults;
            }

            // 不需要重排，直接返回
            return filteredResults.stream().limit(topK).toList();

        } catch (Exception e) {
            logger.error("搜索相似文档失败", e);
            throw new RuntimeException("搜索失败: " + e.getMessage(), e);
        }
    }

    /**
     * 从 metadata JSON 字符串中提取 versionId
     */
    private Long extractVersionId(String metadataJson) {
        if (metadataJson == null || metadataJson.isEmpty()) {
            return null;
        }
        try {
            // 简单解析 JSON 字符串，查找 versionId
            int versionIdIndex = metadataJson.indexOf("\"versionId\"");
            if (versionIdIndex == -1) {
                return null;
            }
            int colonIndex = metadataJson.indexOf(":", versionIdIndex);
            if (colonIndex == -1) {
                return null;
            }
            int startIndex = colonIndex + 1;
            while (startIndex < metadataJson.length() && Character.isWhitespace(metadataJson.charAt(startIndex))) {
                startIndex++;
            }
            int endIndex = startIndex;
            while (endIndex < metadataJson.length() && Character.isDigit(metadataJson.charAt(endIndex))) {
                endIndex++;
            }
            if (endIndex > startIndex) {
                return Long.parseLong(metadataJson.substring(startIndex, endIndex));
            }
        } catch (Exception e) {
            logger.debug("解析 versionId 失败: {}", metadataJson);
        }
        return null;
    }

    /**
     * 搜索结果类
     */
    @Setter
    @Getter
    public static class SearchResult {
        private String id;
        private String content;
        private float score;
        private String metadata;
        private Long versionId;  // 版本 ID
    }
}
