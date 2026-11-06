<script setup lang="ts">
import { MOCK_AUDIT } from '../mock/instances'
</script>

<template>
  <el-alert type="info" :closable="false" show-icon class="note"
    title="审计只记录元数据（谁、何时、对哪个实例、做了什么、结果），不记录消息正文、口令、Cookie 或票据。" />
  <el-table :data="MOCK_AUDIT" size="small">
    <el-table-column prop="at" label="时间" />
    <el-table-column prop="actor" label="操作者" />
    <el-table-column prop="action" label="动作" />
    <el-table-column prop="instance" label="实例" />
    <el-table-column prop="result" label="结果" />
  </el-table>
</template>

<style scoped>
.note { margin-bottom: 12px; }
</style>
