<script setup>
import AppLayout from '../components/common/AppLayout.vue'
import { useRoute } from 'vue-router'

const route = useRoute()
</script>

<template>
  <AppLayout>
    <el-result
      icon="info"
      title="该模块将在下一阶段实现"
      :sub-title="`当前路径：${route.path}`"
    >
      <template #extra>
        <el-button type="primary" @click="$router.push('/chat')">去智能咨询</el-button>
      </template>
    </el-result>
  </AppLayout>
</template>

