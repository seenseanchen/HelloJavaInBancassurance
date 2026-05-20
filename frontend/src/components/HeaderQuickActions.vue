<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const router = useRouter()
const authStore = useAuthStore()

const roleLabel = computed(() => {
  const role = authStore.user?.role
  switch (role) {
    case 'admin':
      return '管理員'
    case 'underwriter':
      return '核保員'
    case 'csr':
      return '客服'
    case 'agent':
      return '業務員'
    default:
      return '未登入'
  }
})

const roleBadgeClass = computed(() => {
  const role = authStore.user?.role
  switch (role) {
    case 'admin':
      return 'bg-rose-600 text-white'
    case 'underwriter':
      return 'bg-indigo-600 text-white'
    case 'csr':
      return 'bg-emerald-600 text-white'
    case 'agent':
      return 'bg-amber-500 text-neutral-900'
    default:
      return 'bg-neutral-500 text-white'
  }
})

async function goHome() {
  if (router.currentRoute.value.name === 'home') {
    return
  }
  await router.push({ name: 'home' })
}

async function logout() {
  authStore.logout()
  await router.replace({ name: 'login' })
}
</script>

<template>
  <!-- 頁面 header 區的快速操作：只保留「回首頁」 -->
  <div class="flex items-center gap-2">
    <el-button plain @click="goHome">回首頁</el-button>
  </div>

  <!-- 全局浮動區塊：角色 badge + 登出按鈕，固定在右上角，所有頁面皆可見 -->
  <div class="fixed right-4 top-4 z-[1200] flex items-center gap-1.5">
    <span
      class="inline-flex items-center rounded-full px-3 py-1 text-caption font-bold tracking-[0.04em] shadow-card"
      :class="roleBadgeClass"
    >
      {{ roleLabel }}
    </span>
    <button
      class="inline-flex items-center rounded-full border border-current/30 bg-white/90 px-2.5 py-1 text-caption font-medium text-neutral-600 shadow-card backdrop-blur-sm transition-colors hover:bg-rose-50 hover:text-rose-600"
      title="登出"
      @click="logout"
    >
      登出
    </button>
  </div>
</template>
