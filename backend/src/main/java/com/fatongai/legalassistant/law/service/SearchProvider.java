package com.fatongai.legalassistant.law.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fatongai.legalassistant.law.entity.LawArticle;

/**
 * 检索召回抽象。当前实现由 MySQL 轻量列构造候选条件；后续可替换为 Elasticsearch/OpenSearch，
 * 服务层仍复用同一分页、详情懒加载和版本过滤逻辑。
 */
public interface SearchProvider {
    void applyKeywordRecall(LambdaQueryWrapper<LawArticle> wrapper, String query, String searchType);
}
