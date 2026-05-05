<script setup>
/**
 * 智能咨询等场景：AI 答复以纯文本展示（保留换行），不使用 Markdown/HTML，避免 # * 等符号被当作格式解析。
 */
defineProps({
  content: { type: String, default: '' },
})
</script>

<template>
  <div class="plain-ai-body">{{ content }}</div>
</template>

<style scoped>
.plain-ai-body {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  text-align: justify;
  text-justify: inter-ideograph;
  line-height: 1.75;
  font-size: 15px;
  color: var(--on-surface, #191c1e);
}
</style>
