import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { MOCK_INSTANCES, type MockInstance } from '../mock/instances'

export const useInstancesStore = defineStore('instances', () => {
  // 骨架阶段只有 mock；接入 /__mmab/api/v1/instances 后替换。
  const items = ref<MockInstance[]>(MOCK_INSTANCES)
  const byId = computed(() => new Map(items.value.map((i) => [i.id, i])))
  return { items, byId, isMock: true }
})
