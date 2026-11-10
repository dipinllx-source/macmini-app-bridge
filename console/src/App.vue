<script setup lang="ts">
import DexIcon from './components/DexIcon.vue'
import { onMounted, ref } from 'vue'
import { fetchHealth, type PlatformHealth } from './api/health'
import { useSession } from './composables/useSession'

const health = ref<PlatformHealth | null>(null)
const { loggedIn, error, check, login, logout } = useSession()
const password = ref('')
const busy = ref(false)
const shake = ref(false)

const nav = [
  { to: '/', label: '应用总览', icon: 'home' },
  { to: '/lifecycle', label: '生命周期', icon: 'cycle' },
  { to: '/compatibility', label: '版本兼容', icon: 'layers' },
  { to: '/security', label: '安全与审计', icon: 'shield' },
]

onMounted(async () => {
  health.value = await fetchHealth()
  await check()
})

async function submit() {
  if (!password.value) return
  busy.value = true
  try {
    await login(password.value)
  } catch {
    shake.value = true
    setTimeout(() => (shake.value = false), 500)
  } finally {
    password.value = ''
    busy.value = false
  }
}
</script>

<template>
  <div class="shell">
    <!-- 图鉴机身顶栏 -->
    <header class="dex-top">
      <div class="lens" :class="{ online: !!health }" :title="health ? '平台在线' : '平台未连接'">
        <span class="lens-glint" />
      </div>
      <div class="lights">
        <i class="l red" /><i class="l yellow" /><i class="l green" />
      </div>
      <div class="brand">
        <div class="brand-name dex-title">macmini App 引桥</div>
        <div class="brand-sub">把 Mac 上的应用带进任何一个浏览器</div>
      </div>
      <div class="top-right">
        <span v-if="health" class="ver">v{{ health.version }}</span>
        <button v-if="loggedIn" class="go-btn ghost small" @click="logout">离开</button>
      </div>
    </header>

    <div class="hinge" />

    <!-- 训练家登录 -->
    <main v-if="loggedIn === false" class="login-wrap">
      <div class="dex-card login pop-in" :class="{ shake }">
        <div class="login-orb">
          <!-- 原创图标：引桥徽章（拱桥 + 星光） -->
          <svg viewBox="0 0 64 64" width="76" height="76" aria-hidden="true">
            <circle cx="32" cy="32" r="29" fill="#ffcb05" stroke="#2b2d42" stroke-width="4" />
            <path d="M12 42h40M16 42c4-12 10-18 16-18s12 6 16 18" fill="none" stroke="#2b2d42" stroke-width="4" stroke-linecap="round" />
            <path d="M22 42v-7M32 42V30M42 42v-7" stroke="#2b2d42" stroke-width="3" stroke-linecap="round" />
            <path d="M46 14l2 5 5 2-5 2-2 5-2-5-5-2 5-2z" fill="#fff" stroke="#2b2d42" stroke-width="2" stroke-linejoin="round" />
          </svg>
        </div>
        <h2 class="dex-title">训练家，请出示通行口令</h2>
        <p class="muted">这是 macmini App 引桥的平台口令，不是千问或 Codex 的账号密码。</p>
        <form @submit.prevent="submit">
          <input v-model="password" class="pw" type="password" autocomplete="current-password" placeholder="平台口令" autofocus />
          <button class="go-btn" type="submit" :disabled="busy || !password">{{ busy ? '验证中…' : '出发！' }}</button>
        </form>
        <p v-if="error" class="err">{{ error }}</p>
      </div>
    </main>

    <main v-else-if="loggedIn" class="body">
      <nav class="tabs">
        <router-link v-for="n in nav" :key="n.to" :to="n.to" class="tab" active-class="" exact-active-class="active">
          <DexIcon class="tab-icon" :name="n.icon" :size="21" />{{ n.label }}
        </router-link>
      </nav>
      <section class="screen">
        <router-view />
      </section>
    </main>

    <main v-else class="login-wrap"><div class="loading">图鉴启动中…</div></main>
  </div>
</template>

<style>
@import './styles/theme.css';
</style>

<style scoped>
.shell { min-height: 100vh; }
.dex-top {
  display: flex; align-items: center; gap: 16px;
  padding: 10px 24px 12px;
  background: linear-gradient(180deg, var(--dex-red) 0%, var(--dex-red-dark) 100%);
  border-bottom: 4px solid var(--dex-line);
  color: #fff;
}
.lens {
  width: 44px; height: 44px; border-radius: 50%;
  background: radial-gradient(circle at 35% 35%, #9bd7ff, var(--dex-blue) 55%, #1b6fb3);
  border: 4px solid #fff; box-shadow: 0 0 0 4px var(--dex-line);
  position: relative; flex: none;
}
.lens.online { animation: lens-glow 2.4s ease-in-out infinite; }
.lens:not(.online) { filter: grayscale(0.9) brightness(0.8); }
.lens-glint { position: absolute; left: 8px; top: 7px; width: 11px; height: 7px; border-radius: 50%; background: rgba(255,255,255,0.8); }
@keyframes lens-glow { 50% { box-shadow: 0 0 0 4px var(--dex-line), 0 0 18px 6px rgba(63,169,245,0.75); } }
.lights { display: flex; gap: 6px; align-self: flex-start; margin-top: 4px; }
.l { width: 12px; height: 12px; border-radius: 50%; border: 2px solid var(--dex-line); display: block; }
.l.red { background: #ff5a5a; }
.l.yellow { background: var(--dex-yellow); animation: blink 3s steps(2) infinite; }
.l.green { background: var(--dex-green); }
@keyframes blink { 50% { opacity: 0.35; } }
.brand { flex: 1; }
.brand-name { font-size: 22px; text-shadow: 0 2px 0 rgba(0,0,0,0.25); }
.brand-sub { font-size: 12px; opacity: 0.9; }
.top-right { display: flex; align-items: center; gap: 10px; }
.ver { font-size: 12px; background: rgba(0,0,0,0.2); padding: 2px 8px; border-radius: 999px; }
.go-btn.small { padding: 4px 12px; font-size: 12px; }
.hinge { height: 10px; background: repeating-linear-gradient(90deg, var(--dex-line) 0 18px, transparent 18px 24px); opacity: 0.12; }

.body { max-width: 1180px; margin: 0 auto; padding: 18px 20px 40px; }
.tabs { display: flex; gap: 10px; flex-wrap: wrap; margin-bottom: 18px; }
.tab {
  display: inline-flex; align-items: center; gap: 6px;
  padding: 7px 16px; border-radius: 999px; text-decoration: none;
  color: var(--dex-ink); font-weight: 800; font-size: 14px;
  background: #fff; border: 3px solid var(--dex-line); box-shadow: 0 3px 0 var(--dex-line);
  transition: transform 0.12s;
}
.tab:hover { transform: translateY(-2px); }
.tab.active { background: var(--dex-red); color: #fff; }
.tab-icon { color: inherit; }

.login-wrap { display: grid; place-items: center; padding: 60px 16px; }
.login { width: min(420px, 100%); padding: 28px 28px 22px; text-align: center; }
.login-orb { animation: floaty 3s ease-in-out infinite; }
.login h2 { margin: 10px 0 6px; font-size: 20px; }
.muted { color: var(--dex-ink-soft); font-size: 13px; margin: 0 0 16px; }
.pw {
  width: 100%; box-sizing: border-box; padding: 10px 14px; margin-bottom: 14px;
  border: 3px solid var(--dex-line); border-radius: 12px; font-size: 15px; outline: none;
}
.pw:focus { border-color: var(--dex-blue); }
.err { color: var(--dex-red); font-weight: 700; font-size: 13px; margin: 12px 0 0; }
.shake { animation: shake 0.45s; }
@keyframes shake { 20%, 60% { transform: translateX(-8px); } 40%, 80% { transform: translateX(8px); } }
.loading { font-weight: 800; color: var(--dex-ink-soft); animation: blink 1s steps(2) infinite; }
</style>
