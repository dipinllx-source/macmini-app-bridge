import { createRouter, createWebHistory } from 'vue-router'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'overview', component: () => import('../views/OverviewView.vue'), meta: { title: '应用总览' } },
    { path: '/launch', redirect: '/' },
    { path: '/instances/:id', name: 'instance', component: () => import('../views/InstanceDetailView.vue'), meta: { title: '实例详情' }, props: true },
    { path: '/lifecycle', name: 'lifecycle', component: () => import('../views/LifecycleView.vue'), meta: { title: '生命周期' } },
    { path: '/compatibility', name: 'compatibility', component: () => import('../views/CompatibilityView.vue'), meta: { title: '版本兼容' } },
    { path: '/security', name: 'security', component: () => import('../views/SecurityView.vue'), meta: { title: '安全与审计' } },
  ],
})
