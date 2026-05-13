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
  <div class="flex items-center gap-2">
    <el-button plain @click="goHome">回首頁</el-button>
    <el-button type="primary" plain @click="logout">登出</el-button>
    <span
      class="pointer-events-none fixed right-4 top-4 z-[1200] inline-flex items-center rounded-full px-3 py-1 text-caption font-bold tracking-[0.04em] shadow-card"
      :class="roleBadgeClass"
    >
      {{ roleLabel }}
    </span>
  </div>
</template>
