<script setup lang="ts">
import { useInstancesStore } from '../stores/instances'

const store = useInstancesStore()
</script>

<template>
  <el-table :data="store.items" size="small">
    <el-table-column prop="id" label="实例" />
    <el-table-column prop="desiredState" label="期望状态" />
    <el-table-column prop="actualState" label="实际状态" />
    <el-table-column prop="blockedReason" label="阻塞 / 待确认原因" />
    <el-table-column label="操作">
      <template #default>
        <el-tooltip content="骨架阶段未实现生命周期操作" placement="top">
          <el-button size="small" disabled>确认恢复</el-button>
        </el-tooltip>
      </template>
    </el-table-column>
  </el-table>
  <p class="hint">恢复策略：期望状态 + 实际状态对账；手动停止后不自动拉起；无法安全接入时进入“待确认”，不擅自退出用户应用。</p>
</template>

<style scoped>
.hint { color: var(--el-text-color-secondary); font-size: 13px; }
</style>
