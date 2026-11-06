<script setup lang="ts">
import { useInstancesStore } from '../stores/instances'

const store = useInstancesStore()
</script>

<template>
  <el-row :gutter="16">
    <el-col v-for="i in store.items" :key="i.id" :xs="24" :md="12">
      <el-card shadow="hover">
        <template #header>
          <div class="card-head">
            <span>{{ i.displayName }} <small>（{{ i.id }}）</small></span>
            <el-tag :type="i.actualState === 'READY' ? 'success' : 'warning'">{{ i.actualState }}</el-tag>
          </div>
        </template>
        <el-descriptions :column="1" size="small">
          <el-descriptions-item label="策略族">{{ i.strategy }}</el-descriptions-item>
          <el-descriptions-item label="端口">{{ i.port }}</el-descriptions-item>
          <el-descriptions-item label="风险等级">{{ i.risk }}</el-descriptions-item>
          <el-descriptions-item label="占用">{{ i.lease ? i.lease.holder : '空闲' }}</el-descriptions-item>
          <el-descriptions-item v-if="i.blockedReason" label="等待原因">{{ i.blockedReason }}</el-descriptions-item>
        </el-descriptions>
        <div class="actions">
          <el-button size="small" @click="$router.push(`/instances/${i.id}`)">详情</el-button>
          <el-tooltip content="骨架阶段未实现开窗票据" placement="top">
            <el-button size="small" type="primary" disabled>打开应用</el-button>
          </el-tooltip>
        </div>
      </el-card>
    </el-col>
  </el-row>
</template>

<style scoped>
.card-head { display: flex; justify-content: space-between; align-items: center; }
.actions { margin-top: 12px; display: flex; gap: 8px; }
</style>
