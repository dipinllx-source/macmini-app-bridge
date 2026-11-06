<script setup lang="ts">
import { computed } from 'vue'
import { useInstancesStore } from '../stores/instances'
import { CAPABILITY_LABEL, capabilityTagType } from './stateTag'
import type { HealthLevel } from '../mock/instances'

const props = defineProps<{ id: string }>()
const store = useInstancesStore()
const inst = computed(() => store.byId.get(props.id))
const LEVELS: HealthLevel[] = ['L0_PROCESS', 'L1_LISTENER', 'L2_BRIDGE', 'L3_RENDER', 'L4_BUSINESS']
</script>

<template>
  <el-empty v-if="!inst" description="未找到实例" />
  <div v-else>
    <el-descriptions :title="`${inst.displayName}（${inst.id}）`" :column="2" border>
      <el-descriptions-item label="期望状态">{{ inst.desiredState }}</el-descriptions-item>
      <el-descriptions-item label="实际状态">{{ inst.actualState }}</el-descriptions-item>
      <el-descriptions-item label="generation">{{ inst.generation }}</el-descriptions-item>
      <el-descriptions-item label="端口">{{ inst.port }}</el-descriptions-item>
      <el-descriptions-item label="应用版本">{{ inst.appVersion }}</el-descriptions-item>
      <el-descriptions-item label="profile">{{ inst.profileVersion }}</el-descriptions-item>
    </el-descriptions>

    <h4>分层健康（未验证即不展示为健康）</h4>
    <el-space wrap>
      <el-tag v-for="l in LEVELS" :key="l" :type="inst.healthVerified[l] ? 'success' : 'info'">
        {{ l }}：{{ inst.healthVerified[l] ? '已验证' : '未验证' }}
      </el-tag>
    </el-space>

    <h4>能力清单</h4>
    <el-table :data="Object.entries(inst.capabilities).map(([id, s]) => ({ id, s }))" size="small">
      <el-table-column prop="id" label="能力" />
      <el-table-column label="状态">
        <template #default="{ row }">
          <el-tag :type="capabilityTagType(row.s)">{{ CAPABILITY_LABEL[row.s as keyof typeof CAPABILITY_LABEL] }}</el-tag>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>
