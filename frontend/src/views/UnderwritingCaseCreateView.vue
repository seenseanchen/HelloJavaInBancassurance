<script setup lang="ts">
import axios from 'axios'
import { ElMessage } from 'element-plus'
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import BaseCard from '../components/BaseCard.vue'
import {
  createUnderwritingCase,
  type UnderwritingChannel,
} from '../api/underwriting'
import type { ApiErrorResponse } from '../api/types'
import { useAuthStore } from '../stores/auth'

const router = useRouter()
const authStore = useAuthStore()

const submitting = ref(false)
const errorMessage = ref<string | null>(null)

const form = reactive({
  applicantName: '',
  applicantIdNumber: '',
  productCode: '',
  coverageAmount: 1000000,
  premium: 25000,
  channel: 'BANCASSURANCE' as UnderwritingChannel,
})

const channelOptions: Array<{ label: string; value: UnderwritingChannel }> = [
  { label: '銀保通路', value: 'BANCASSURANCE' },
  { label: '業務員通路', value: 'AGENT' },
  { label: '線上投保', value: 'ONLINE' },
]

const canSubmitByRole = computed(() => {
  const role = authStore.user?.role
  return role === 'csr' || role === 'admin'
})

function normalizeApiError(error: unknown): ApiErrorResponse | null {
  if (!axios.isAxiosError(error)) {
    return null
  }

  const payload = error.response?.data
  if (!payload || typeof payload !== 'object') {
    return null
  }

  const candidate = payload as Partial<ApiErrorResponse>
  if (
    typeof candidate.status !== 'number' ||
    typeof candidate.code !== 'string' ||
    typeof candidate.message !== 'string'
  ) {
    return null
  }

  return {
    status: candidate.status,
    error: typeof candidate.error === 'string' ? candidate.error : '',
    code: candidate.code,
    message: candidate.message,
    path: typeof candidate.path === 'string' ? candidate.path : '',
    timestamp: typeof candidate.timestamp === 'string' ? candidate.timestamp : '',
    traceId: typeof candidate.traceId === 'string' ? candidate.traceId : null,
  }
}

function validateForm(): string | null {
  if (form.applicantName.trim().length === 0) {
    return '要保人姓名不可為空。'
  }
  if (!/^[A-Z][0-9]{9}$/.test(form.applicantIdNumber.trim().toUpperCase())) {
    return '身分證號格式需為 1 英文字母 + 9 位數字。'
  }
  if (form.productCode.trim().length === 0) {
    return '商品代碼不可為空。'
  }
  if (form.coverageAmount <= 0) {
    return '保額必須大於 0。'
  }
  if (form.premium <= 0) {
    return '保費必須大於 0。'
  }
  return null
}

async function submitCase() {
  authStore.hydrate()

  if (authStore.authMode === 'mock') {
    errorMessage.value =
      '目前是 mock 模式，請改用後端 CSR 或 ADMIN 帳號登入後再送件（例如 csr01/csr123 或 admin/admin123）。'
    return
  }

  if (!canSubmitByRole.value) {
    errorMessage.value = '目前帳號沒有送件權限，僅 CSR / ADMIN 可建立核保案件。'
    return
  }

  const validationMessage = validateForm()
  if (validationMessage) {
    errorMessage.value = validationMessage
    return
  }

  submitting.value = true
  errorMessage.value = null

  try {
    const createdCase = await createUnderwritingCase({
      applicantName: form.applicantName.trim(),
      applicantIdNumber: form.applicantIdNumber.trim().toUpperCase(),
      productCode: form.productCode.trim(),
      coverageAmount: Number(form.coverageAmount),
      premium: Number(form.premium),
      channel: form.channel,
    })
    ElMessage.success('送件成功，已建立核保案件。')
    await router.push(`/underwriting/cases/${createdCase.id}`)
  } catch (error) {
    const apiError = normalizeApiError(error)
    const message = apiError?.message ?? '送件失敗，請稍後再試。'
    errorMessage.value = message
    ElMessage.error(message)
  } finally {
    submitting.value = false
  }
}

async function goBackToList() {
  await router.push('/underwriting/cases')
}
</script>

<template>
  <main class="min-h-screen px-4 py-8">
    <div class="mx-auto max-w-4xl space-y-6">
      <header class="rounded-lg border border-cathay-primary/20 bg-cathay-primarySoft px-6 py-5">
        <div class="flex flex-col gap-4 tablet:flex-row tablet:items-start tablet:justify-between">
          <div class="space-y-2">
            <p class="text-caption uppercase tracking-[0.14em] text-cathay-primary">Underwriting</p>
            <h1 class="text-h3 text-neutral-900">新增核保案件</h1>
            <p class="text-caption text-neutral-500">CSR / ADMIN 可建立新送件，後續由核保員領件審查。</p>
          </div>
          <el-button plain @click="goBackToList">返回清單</el-button>
        </div>
      </header>

      <el-alert
        v-if="!canSubmitByRole"
        title="目前帳號沒有送件權限（僅 CSR / ADMIN 可建立案件）。"
        type="warning"
        show-icon
        :closable="false"
      />

      <el-alert
        v-if="errorMessage"
        :title="errorMessage"
        type="warning"
        show-icon
        :closable="false"
      />

      <BaseCard>
        <el-form label-position="top" @submit.prevent="submitCase">
          <div class="grid gap-4 tablet:grid-cols-2">
            <el-form-item label="要保人姓名" required>
              <el-input v-model="form.applicantName" placeholder="例如：陳小明" />
            </el-form-item>

            <el-form-item label="要保人身分證號" required>
              <el-input
                v-model="form.applicantIdNumber"
                placeholder="例如：A123456789"
                maxlength="10"
                @input="form.applicantIdNumber = form.applicantIdNumber.toUpperCase()"
              />
            </el-form-item>

            <el-form-item label="商品代碼" required>
              <el-input v-model="form.productCode" placeholder="例如：LIFE-001" />
            </el-form-item>

            <el-form-item label="銷售通路" required>
              <el-select v-model="form.channel" placeholder="請選擇通路">
                <el-option
                  v-for="option in channelOptions"
                  :key="option.value"
                  :label="option.label"
                  :value="option.value"
                />
              </el-select>
            </el-form-item>

            <el-form-item label="保額（TWD）" required>
              <el-input-number v-model="form.coverageAmount" :min="1" :step="10000" :precision="0" controls-position="right" class="!w-full" />
            </el-form-item>

            <el-form-item label="保費（TWD）" required>
              <el-input-number v-model="form.premium" :min="1" :step="100" :precision="0" controls-position="right" class="!w-full" />
            </el-form-item>
          </div>

          <div class="mt-2 flex justify-end">
            <el-button
              type="primary"
              :loading="submitting"
              :disabled="!canSubmitByRole"
              @click="submitCase"
            >
              送件建立案件
            </el-button>
          </div>
        </el-form>
      </BaseCard>
    </div>
  </main>
</template>
