package com.fatongai.legalassistant.law.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fatongai.legalassistant.law.entity.LawArticleTag;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface LawArticleTagMapper extends BaseMapper<LawArticleTag> {
    @Select("""
            SELECT DISTINCT lat.article_id
            FROM law_article_tag lat
            JOIN law_tag t ON t.id = lat.tag_id
            WHERE t.name LIKE CONCAT('%', #{keyword}, '%')
               OR t.code LIKE CONCAT('%', #{keyword}, '%')
            LIMIT #{limit}
            """)
    List<Long> findArticleIdsByTag(@Param("keyword") String keyword, @Param("limit") int limit);
}
