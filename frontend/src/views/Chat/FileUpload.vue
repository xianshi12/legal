<script setup>
import { computed } from 'vue'
import { ElMessage } from 'element-plus'

const props = defineProps({
  modelValue: { type: Array, default: () => [] },
})
const emit = defineEmits(['update:modelValue'])

const fileList = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v),
})

function beforeUpload(file) {
  const okTypes = [
    'image/png',
    'image/jpeg',
    'image/webp',
    'image/bmp',
    'application/pdf',
    'text/plain',
    'application/msword',
    'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  ]
  const ok = okTypes.includes(file.type) || /\.(png|jpe?g|webp|bmp|pdf|txt|docx?)$/i.test(file.name)
  if (!ok) {
    ElMessage.error('不支持的文件类型')
    return false
  }
  const max = 20 * 1024 * 1024
  if (file.size > max) {
    ElMessage.error('文件过大（最大 20MB）')
    return false
  }
  return false
}
</script>

<template>
  <el-upload
    v-model:file-list="fileList"
    drag
    multiple
    :auto-upload="false"
    :before-upload="beforeUpload"
    action="#"
    class="uploader"
  >
    <div class="hint">
      <el-icon><UploadFilled /></el-icon>
      <span>拖拽或点击上传（图片 / PDF / Word / TXT，≤20MB）</span>
    </div>
  </el-upload>
</template>

<style scoped>
.uploader :deep(.el-upload-dragger) {
  padding: 12px;
}
.hint {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  color: #4b5563;
}
</style>

