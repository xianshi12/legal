package com.fatongai.legalassistant.casebase.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fatongai.legalassistant.casebase.entity.LegalCase;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface LegalCaseMapper extends BaseMapper<LegalCase> {
}
