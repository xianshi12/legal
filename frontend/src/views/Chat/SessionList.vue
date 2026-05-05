<script setup>
defineProps({
  sessions: { type: Array, default: () => [] },
  activeSessionId: { type: String, default: '' },
})

const emit = defineEmits(['select', 'remove'])

function onSelect(s) {
  emit('select', s.sessionId)
}

async function onRemove(s) {
  emit('remove', s.sessionId)
}
</script>

<template>
  <div class="list">
    <div v-if="sessions.length === 0" class="empty">暂无会话，点击“新建”开始。</div>
    <div
      v-for="s in sessions"
      :key="s.sessionId"
      class="item"
      :class="{ active: s.sessionId === activeSessionId }"
      @click="onSelect(s)"
    >
      <div class="meta">
        <div class="title">{{ s.title || '未命名会话' }}</div>
        <div class="time">{{ s.lastMessageTime || s.createdAt || '' }}</div>
      </div>
      <el-popconfirm title="确认删除该会话？" @confirm="onRemove(s)">
        <template #reference>
          <el-button class="del" text type="danger" @click.stop>
            <el-icon><Delete /></el-icon>
          </el-button>
        </template>
      </el-popconfirm>
    </div>
  </div>
</template>

<style scoped>
.list {
  overflow: auto;
  padding: 10px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.empty {
  color: #6b7280;
  font-size: 13px;
  padding: 10px 6px;
}
.item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 10px 10px;
  border-radius: 12px;
  border: 1px solid #eef2f7;
  cursor: pointer;
  background: #fff;
}
.item:hover {
  border-color: rgba(0, 93, 172, 0.3);
}
.item.active {
  background: rgba(0, 93, 172, 0.08);
  border-color: rgba(0, 93, 172, 0.35);
}
.meta {
  min-width: 0;
}
.title {
  font-weight: 800;
  font-size: 14px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.time {
  color: #6b7280;
  font-size: 12px;
  margin-top: 2px;
}
.del {
  opacity: 0.8;
}
</style>

