<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { fetchHealth, type PlatformHealth } from './api/health'

const health = ref<PlatformHealth | null>(null)
onMounted(async () => {
  health.value = await fetchHealth()
})
</script>

<template>
  <el-container class="layout">
    <el-aside width="200px" class="aside">
      <div class="brand">macmini App 引桥</div>
      <el-menu router :default-active="$route.path">
        <el-menu-item index="/">应用总览</el-menu-item>
        <el-menu-item index="/launch">开窗（M1）</el-menu-item>
        <el-menu-item index="/lifecycle">生命周期</el-menu-item>
        <el-menu-item index="/compatibility">版本兼容</el-menu-item>
        <el-menu-item index="/security">安全与审计</el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="header">
        <span>{{ $route.meta.title }}</span>
        <el-tag v-if="health" type="info">服务端 {{ health.version }} · {{ health.status }}</el-tag>
        <el-tag v-else type="warning">服务端未连接</el-tag>
      </el-header>
      <el-main>
        <el-alert
          type="warning"
          :closable="false"
          show-icon
          title="M1 离线阶段：除「开窗（M1）」外页面数据仍为 MOCK；平台只接入离线 mock 适配器，未接入任何真实应用，不可用于远程访问。"
          class="mock-banner"
        />
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<style>
body { margin: 0; font-family: -apple-system, BlinkMacSystemFont, 'PingFang SC', sans-serif; }
.layout { min-height: 100vh; }
.aside { border-right: 1px solid var(--el-border-color); }
.brand { padding: 16px; font-weight: 600; }
.header { display: flex; align-items: center; justify-content: space-between; border-bottom: 1px solid var(--el-border-color); }
.mock-banner { margin-bottom: 16px; }
</style>
