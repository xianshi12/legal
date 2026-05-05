package com.fatongai.legalassistant.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fatongai.legalassistant.chat.entity.ChatSession;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChatSessionMapper extends BaseMapper<ChatSession> {
}

