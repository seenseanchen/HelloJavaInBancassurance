<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import BaseCard from '../components/BaseCard.vue'
import HeaderQuickActions from '../components/HeaderQuickActions.vue'

const router = useRouter()
const authStore = useAuthStore()

async function goUnderwritingCases() {
  await router.push('/underwriting/cases')
}

async function goPolicies() {
  await router.push('/policies')
}
</script>

<template>
  <main class="min-h-screen px-4 py-8">
    <div class="mx-auto max-w-6xl space-y-6">
      <header class="rounded-lg border border-cathay-primary/20 bg-cathay-primarySoft px-6 py-5">
        <div class="flex flex-col gap-4 tablet:flex-row tablet:items-center tablet:justify-between">
          <div class="space-y-2">
            <p class="text-caption uppercase tracking-[0.14em] text-cathay-primary">Dashboard</p>
            <h1 class="text-h3 text-neutral-900">Welcome, {{ authStore.user?.displayName }}</h1>
            <p class="text-caption text-neutral-500">你已成功登入，接下來可進入核保或保單作業模組。</p>
          </div>
          <HeaderQuickActions />
        </div>
      </header>

      <section class="grid gap-4 tablet:grid-cols-3">
        <BaseCard class="space-y-2">
          <h2 class="text-h6 text-neutral-900">核保案件</h2>
          <p class="text-caption text-neutral-500">M17 已接上清單與詳情頁（含狀態流轉與 409 衝突提示）。</p>
          <el-button type="primary" plain class="!mt-2" @click="goUnderwritingCases">
            前往核保案件
          </el-button>
        </BaseCard>

        <BaseCard class="space-y-2">
          <h2 class="text-h6 text-neutral-900">保單服務</h2>
          <p class="text-caption text-neutral-500">M18 已串接查詢、變更與歷程頁（含 If-Match 與冪等鍵）。</p>
          <el-button type="primary" plain class="!mt-2" @click="goPolicies">
            前往保單服務
          </el-button>
        </BaseCard>

        <BaseCard class="space-y-2">
          <h2 class="text-h6 text-neutral-900">操作摘要</h2>
          <p class="text-caption text-neutral-500">目前帳號角色：{{ authStore.user?.role }}</p>
          <p class="text-caption text-neutral-500">
            認證模式：{{ authStore.authMode === 'server' ? 'backend JWT' : 'mock fallback' }}
          </p>
        </BaseCard>
      </section>
    </div>
  </main>
</template>
