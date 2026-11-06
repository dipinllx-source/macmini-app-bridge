<script setup lang="ts">
import { useInstancesStore } from '../stores/instances'

const store = useInstancesStore()
</script>

<template>
  <el-table :data="store.items" size="small">
    <el-table-column prop="displayName" label="应用" />
    <el-table-column prop="appVersion" label="应用版本" />
    <el-table-column prop="strategy" label="策略族" />
    <el-table-column prop="profileVersion" label="适配器 profile" />
  </el-table>
  <p class="hint">版本指纹变化时禁止新会话，候选 profile 离线测试并人工批准后才切换；保留上一套 profile 以便回退。</p>
</template>

<style scoped>
.hint { color: var(--el-text-color-secondary); font-size: 13px; }
</style>
