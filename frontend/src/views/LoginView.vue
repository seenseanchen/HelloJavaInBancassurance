<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import BaseCard from '../components/BaseCard.vue'
import { useAuthStore } from '../stores/auth'

const authStore = useAuthStore()
const router = useRouter()
const route = useRoute()

const loading = ref(false)
const form = reactive({
  username: 'demo',
  password: 'demo',
})

async function submitLogin() {
  loading.value = true

  const result = await authStore.login(form.username, form.password)
  if (!result.success) {
    loading.value = false
    ElMessage.error(result.message ?? '帳號或密碼錯誤')
    return
  }

  if (result.mode === 'mock') {
    ElMessage.warning('目前以 mock 模式登入。若要讀取後端資料，請改用 underwriter01/uw123 或 admin/admin123。')
  }

  const redirect =
    typeof route.query.redirect === 'string' && route.query.redirect.length > 0
      ? route.query.redirect
      : '/home'

  await router.replace(redirect)
  loading.value = false
}
</script>

<template>
  <main class="min-h-screen bg-[radial-gradient(circle_at_top_right,_#e6f7ee,_#ffffff_55%)] px-4 py-8">
    <div
      class="mx-auto grid max-w-6xl overflow-hidden rounded-lg border border-neutral-300 bg-white shadow-dialog tablet:grid-cols-[1.1fr_1fr]"
    >
      <section class="hidden bg-cathay-primary px-10 py-12 text-white tablet:flex tablet:flex-col tablet:justify-between">
        <div class="space-y-4">
          <p class="text-caption uppercase tracking-[0.16em] text-white/80">Bancassurance Platform</p>
          <h1 class="text-h2 font-display">更直覺的銀保服務入口</h1>
          <p class="text-body text-white/85">
            以情境導向與卡片化資訊，讓核保與保單服務流程更快、更清楚。
          </p>
        </div>
        <BaseCard>
          <p class="text-caption text-neutral-500">可用登入帳號</p>
          <ul class="mt-2 space-y-2 text-caption text-neutral-900">
            <li>demo / demo</li>
            <li>underwriter / 1234</li>
            <li>agent / 1234</li>
            <li>underwriter01 / uw123</li>
            <li>csr01 / csr123</li>
            <li>admin / admin123</li>
          </ul>
        </BaseCard>
      </section>

      <section class="p-4 tablet:p-10">
        <BaseCard>
          <div class="space-y-6">
            <div class="space-y-2">
              <p class="text-caption uppercase tracking-[0.14em] text-cathay-primary">Welcome</p>
              <h2 class="text-h3 text-neutral-900">登入 Hello Bancassurance</h2>
              <p class="text-caption text-neutral-500">優先走後端 JWT 登入；若後端帳號不匹配，才 fallback 到本地 mock 帳號。</p>
            </div>

            <el-form :model="form" label-position="top" @submit.prevent="submitLogin">
              <el-form-item label="帳號">
                <el-input v-model="form.username" autocomplete="username" placeholder="請輸入帳號" />
              </el-form-item>
              <el-form-item label="密碼">
                <el-input
                  v-model="form.password"
                  autocomplete="current-password"
                  show-password
                  placeholder="請輸入密碼"
                  @keyup.enter="submitLogin"
                />
              </el-form-item>

              <el-button type="primary" class="mt-2 w-full !h-11 !text-base" :loading="loading" @click="submitLogin">
                立即登入
              </el-button>
            </el-form>
          </div>
        </BaseCard>
      </section>
    </div>
  </main>
</template>
