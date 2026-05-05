-- AI智能法律助手 - MySQL 初始化脚本（Phase 1：模块1）
CREATE DATABASE IF NOT EXISTS `legal_assistant` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE `legal_assistant`;

-- 用户表（Phase 1 仅预留）
CREATE TABLE IF NOT EXISTS `user` (
  `id` bigint PRIMARY KEY AUTO_INCREMENT,
  `username` varchar(50) UNIQUE,
  `password` varchar(255),
  `phone` varchar(20),
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP
);

-- 会话表（会话内容存 Redis，这里存元数据）
CREATE TABLE IF NOT EXISTS `chat_session` (
  `id` bigint PRIMARY KEY AUTO_INCREMENT,
  `session_id` varchar(64) UNIQUE,
  `user_id` bigint,
  `title` varchar(200),
  `last_message_time` datetime,
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_user_id (`user_id`)
);

