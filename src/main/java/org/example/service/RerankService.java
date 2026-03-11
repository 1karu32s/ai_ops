package org.example.service;

import com.alibaba.dashscope.rerank.TextReRank;
import com.alibaba.dashscope.rerank.TextReRankParam;
import com.alibaba.dashscope.rerank.TextReRankResult;
import com.alibaba.dashscope.rerank.TextReRankOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;

/**
 * Rerank 服务
 * 使用百炼 gte-rerank 模型进行语义重排
 * 采用 SDK 方式调用
 */
@Service
public class RerankService {

    private static final Logger logger = LoggerFactory.getLogger(RerankService.class);

    @Value("${dashscope.api.key}")
    private String apiKey;

    @Value("${dashscope.rerank.model:gte-rerank}")
    private String model;

    @Value("${dashscope.rerank.top-n:3}")
    private int topN;

    private TextReRank textReRank;

    @PostConstruct
    public void init() {
        textReRank = new TextReRank();
        logger.info("Rerank 服务初始化完成，model: {}, topN: {}", model, topN);
    }

    /**
     * 重排搜索结果
     *
     * @param query     查询文本
     * @param documents 待重排的文档列表
     * @return 重排后的结果列表
     */
    public List<RerankResultItem> rerank(String query, List<String> documents) {
        if (documents == null || documents.isEmpty()) {
            logger.warn("待重排文档列表为空");
            return new ArrayList<>();
        }

        try {
            logger.info("开始 Rerank 重排, query: {}, 文档数量: {}", query, documents.size());

            // 构建请求参数
            TextReRankParam param = TextReRankParam.builder()
                    .apiKey(apiKey)
                    .model(model)
                    .query(query)
                    .documents(documents)
                    .topN(topN)
                    .build();

            // 调用 Rerank API
            TextReRankResult result = textReRank.call(param);

            // 解析结果
            List<RerankResultItem> items = new ArrayList<>();
            if (result != null && result.getOutput() != null && result.getOutput().getResults() != null) {
                for (TextReRankOutput.Result rerankItem : result.getOutput().getResults()) {
                    RerankResultItem item = new RerankResultItem();

                    // 获取原始索引（在输入文档列表中的位置）
                    Integer idx = rerankItem.getIndex();
                    item.setIndex(idx != null ? idx : items.size());

                    // 获取文本内容（通过 Document 对象）
                    TextReRankOutput.Document doc = rerankItem.getDocument();
                    if (doc != null) {
                        item.setText(doc.getText());
                    }

                    // 获取相关性分数
                    Double score = rerankItem.getRelevanceScore();
                    item.setRelevanceScore(score != null ? score : 0.0);

                    items.add(item);
                }
            }

            if (items.isEmpty()) {
                logger.warn("Rerank 解析结果为空");
                return fallbackToOriginalOrder(documents);
            }

            logger.info("Rerank 重排完成, 返回结果数量: {}", items.size());
            return items;

        } catch (Exception e) {
            logger.error("Rerank 重排失败: {}", e.getMessage(), e);
            // 降级处理：返回原始顺序
            return fallbackToOriginalOrder(documents);
        }
    }

    /**
     * 降级处理：返回原始顺序
     */
    private List<RerankResultItem> fallbackToOriginalOrder(List<String> documents) {
        logger.info("Rerank 降级处理，返回原始顺序");
        List<RerankResultItem> items = new ArrayList<>();
        for (int i = 0; i < Math.min(documents.size(), topN); i++) {
            RerankResultItem item = new RerankResultItem();
            item.setIndex(i);
            item.setText(documents.get(i));
            item.setRelevanceScore(1.0 - (i * 0.1)); // 递减分数
            items.add(item);
        }
        return items;
    }

    /**
     * 重排结果项
     */
    public static class RerankResultItem {
        private int index;
        private String text;
        private double relevanceScore;

        public int getIndex() {
            return index;
        }

        public void setIndex(int index) {
            this.index = index;
        }

        public String getText() {
            return text;
        }

        public void setText(String text) {
            this.text = text;
        }

        public double getRelevanceScore() {
            return relevanceScore;
        }

        public void setRelevanceScore(double relevanceScore) {
            this.relevanceScore = relevanceScore;
        }
    }
}
