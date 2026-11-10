<script setup lang="ts">
/**
 * 控制台统一图标（原创）：24×24 网格、2px 圆头描边 + 局部色块，跟随 currentColor。
 * 替代平台相关、渲染不一致的 emoji。
 */
defineProps<{ name: string; size?: number; title?: string }>()
</script>

<template>
  <svg class="dex-icon" :width="size ?? 20" :height="size ?? 20" viewBox="0 0 24 24" fill="none" stroke="currentColor"
    stroke-width="2" stroke-linecap="round" stroke-linejoin="round" :aria-label="title" :aria-hidden="title ? undefined : 'true'"
    role="img">
    <title v-if="title">{{ title }}</title>
    <!-- 导航 -->
    <template v-if="name === 'home'">
      <path d="M3.5 11 12 4l8.5 7" /><path d="M5.5 9.5V20h13V9.5" /><path d="M10 20v-5.5h4V20" fill="var(--dex-yellow)" />
    </template>
    <template v-else-if="name === 'cycle'">
      <path d="M20 12a8 8 0 0 1-13.7 5.6" /><path d="M4 12a8 8 0 0 1 13.7-5.6" />
      <path d="M17.7 2.8v3.8h-3.8" /><path d="M6.3 21.2v-3.8h3.8" />
    </template>
    <template v-else-if="name === 'layers'">
      <path d="m12 3.5 8.5 4.25L12 12 3.5 7.75z" fill="var(--dex-yellow)" fill-opacity="0.45" /><path d="m3.5 12 8.5 4.25L20.5 12" /><path d="m3.5 16.25 8.5 4.25 8.5-4.25" />
    </template>
    <template v-else-if="name === 'shield'">
      <path d="M12 3 4.5 6v5.5c0 4.6 3.1 8.2 7.5 9.5 4.4-1.3 7.5-4.9 7.5-9.5V6z" fill="var(--dex-blue)" fill-opacity="0.25" />
      <path d="m8.8 12.2 2.3 2.3 4.2-4.6" />
    </template>
    <!-- 统计徽章 -->
    <template v-else-if="name === 'medal'">
      <path d="M8.5 3.5 10.5 9M15.5 3.5 13.5 9" /><circle cx="12" cy="14.5" r="6" fill="var(--dex-yellow)" fill-opacity="0.55" /><path d="m9.5 14.6 1.7 1.7 3.3-3.6" />
    </template>
    <template v-else-if="name === 'play'">
      <circle cx="12" cy="12" r="9" /><path d="m10 8.5 5.5 3.5-5.5 3.5z" fill="var(--dex-red)" />
    </template>
    <template v-else-if="name === 'heart'">
      <path d="M12 20s-7.5-4.6-7.5-10A4.3 4.3 0 0 1 12 7.4 4.3 4.3 0 0 1 19.5 10c0 5.4-7.5 10-7.5 10z" fill="var(--dex-hp-green)" fill-opacity="0.4" />
    </template>
    <!-- 冒险日志 -->
    <template v-else-if="name === 'spark'">
      <path d="M12 3.5c.6 4.2 2.3 6 6.5 6.5-4.2.6-5.9 2.3-6.5 6.5-.6-4.2-2.3-5.9-6.5-6.5 4.2-.5 5.9-2.3 6.5-6.5z" fill="var(--dex-yellow)" fill-opacity="0.6" /><path d="M18.5 16.5v4M16.5 18.5h4" />
    </template>
    <template v-else-if="name === 'revive'">
      <path d="M12 20s-7.5-4.6-7.5-10A4.3 4.3 0 0 1 12 7.4 4.3 4.3 0 0 1 19.5 10c0 5.4-7.5 10-7.5 10z" fill="var(--dex-hp-green)" fill-opacity="0.35" />
      <path d="M12 10.5v5M9.5 13h5" />
    </template>
    <template v-else-if="name === 'pause'">
      <circle cx="12" cy="12" r="8.5" fill="var(--dex-blue)" fill-opacity="0.18" /><path d="M10 9v6M14 9v6" />
    </template>
    <template v-else-if="name === 'moon'">
      <path d="M19.5 14.5A8 8 0 0 1 9.5 4.5a8 8 0 1 0 10 10z" fill="var(--dex-blue)" fill-opacity="0.25" />
    </template>
    <template v-else-if="name === 'exit'">
      <path d="M13 4H6v16h7" /><path d="M16 8.5 19.5 12 16 15.5" /><path d="M10 12h9.5" />
    </template>
    <template v-else-if="name === 'alert'">
      <path d="M10.3 4.3 2.8 17.5A2 2 0 0 0 4.5 20.5h15a2 2 0 0 0 1.7-3L13.7 4.3a2 2 0 0 0-3.4 0z" fill="var(--dex-yellow)" fill-opacity="0.45" />
      <path d="M12 9.5v4.5M12 17.2v.1" />
    </template>
    <template v-else-if="name === 'door'">
      <path d="M6 20V4h9l3 2v14" /><path d="M3.5 20h17" /><circle cx="13" cy="12.5" r="1" fill="currentColor" />
    </template>
    <template v-else-if="name === 'key'">
      <circle cx="8" cy="15" r="4" fill="var(--dex-yellow)" /><path d="m11 12 8-8M16 7l2 2M14 9l2 2" />
    </template>
    <template v-else-if="name === 'lock'">
      <rect x="5" y="10.5" width="14" height="10" rx="2" fill="var(--dex-red)" fill-opacity="0.2" /><path d="M8 10.5V8a4 4 0 0 1 8 0v2.5" />
      <path d="M12 14.5v2" />
    </template>
    <template v-else-if="name === 'flag'">
      <path d="M5 21V4" /><path d="M5 4h11l-2 3.5L16 11H5" fill="var(--dex-red)" fill-opacity="0.35" />
    </template>
    <template v-else-if="name === 'broom'">
      <path d="M19 4 12.5 10.5" /><path d="M11.5 9.5 14.5 12.5 11 20H4l1-7z" fill="var(--dex-yellow)" fill-opacity="0.5" /><path d="M7.5 20l1.5-4M10.5 20l1-3" />
    </template>
    <template v-else-if="name === 'plug'">
      <path d="M9 3.5v4M15 3.5v4" /><path d="M6.5 7.5h11v3.5a5.5 5.5 0 0 1-11 0z" fill="var(--dex-green)" fill-opacity="0.35" /><path d="M12 16.5v4" />
    </template>
    <template v-else-if="name === 'evolve'">
      <path d="M12 3v6M12 15v6" /><path d="m9 6 3-3 3 3M9 18l3 3 3-3" /><circle cx="12" cy="12" r="3" fill="var(--dex-blue)" fill-opacity="0.35" />
    </template>
    <template v-else-if="name === 'scroll'">
      <path d="M7 4h11a2 2 0 0 1 2 2v1h-4" /><path d="M16 7v11a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2v-1h10" />
      <path d="M7 4a2 2 0 0 0-2 2v11" /><path d="M9 9h4M9 12.5h4" />
    </template>
    <!-- 应用头像兜底 -->
    <template v-else-if="name === 'flask'">
      <path d="M9.5 3h5M10.5 3v6L5 18.5A1.7 1.7 0 0 0 6.5 21h11a1.7 1.7 0 0 0 1.5-2.5L13.5 9V3" />
      <path d="M7.4 15h9.2l2.4 3.5a1.7 1.7 0 0 1-1.5 2.5h-11A1.7 1.7 0 0 1 5 18.5z" fill="var(--dex-green)" fill-opacity="0.45" stroke="none" />
    </template>
    <template v-else-if="name === 'app'">
      <rect x="4" y="4" width="16" height="16" rx="4.5" fill="var(--dex-blue)" fill-opacity="0.25" /><path d="M9 12h6M12 9v6" />
    </template>
    <template v-else-if="name === 'broom-small'">
      <path d="M19 4 12.5 10.5" />
    </template>
    <template v-else>
      <circle cx="12" cy="12" r="8" /><path d="M12 8v5M12 16v.1" />
    </template>
  </svg>
</template>

<style scoped>
.dex-icon { display: inline-block; flex: none; vertical-align: -0.2em; }
</style>
