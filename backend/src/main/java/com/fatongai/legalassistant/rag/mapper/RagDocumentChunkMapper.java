package com.fatongai.legalassistant.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fatongai.legalassistant.rag.entity.RagDocumentChunk;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface RagDocumentChunkMapper extends BaseMapper<RagDocumentChunk> {
}
