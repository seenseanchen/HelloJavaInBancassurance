<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import BaseCard from '../components/BaseCard.vue'
import HeaderQuickActions from '../components/HeaderQuickActions.vue'
import {
  changePolicyPaymentMethod,
  getPolicyById,
  type Policy,
  type PremiumPaymentMethod,
} from '../api/policy'
import { normalizeApiError } from '../api/errors'
import { useAuthStore } from '../stores/auth'
import { usePolicyStore } from '../stores/policy'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()
const policyStore = usePolicyStore()

const loading = ref(false)
const submitting = ref(false)
const errorMessage = ref<string | null>(null)
const businessRuleMessage = ref<string | null>(null)
const policy = ref<Policy | null>(null)

const conflictDialogVisible = ref(false)
const conflictMessage = ref('資料已被他人異動，請重新讀取後再嘗試。')

const form = reactive({
  expectedVersion: 0,
  newPaymentMethod: 'MONTHLY' as PremiumPaymentMethod,
  reason: '',
})

const policyId = computed(() => {
  const param = route.params.id
  return typeof param === 'string' ? param : ''
})

const canChangePolicy = computed(() => {
  const role = authStore.user?.role
  return role === 'csr' || role === 'admin'
})

const paymentOptions: Array<{ label: string; value: PremiumPaymentMethod }> = [
  { label: '月繳', value: 'MONTHLY' },
  { label: '季繳', value: 'QUARTERLY' },
  { label: '半年繳', value: 'SEMI_ANNUAL' },
  { label: '年繳', value: 'ANNUAL' },
]

function buildIdempotencyKey(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return `policy-change-${Date.now()}-${Math.random().toString(16).slice(2)}`
}

function paymentMethodLabel(method: PremiumPaymentMethod): string {
  switch (method) {
    case 'MONTHLY':
      return '月繳'
    case 'QUARTERLY':
      return '季繳'
    case 'SEMI_ANNUAL':
      return '半年繳'
    case 'ANNUAL':
      return '年繳'
    case 'SINGLE_PAY':
      return '躉繳'
    default:
      return method
  }
}

function formatTime(value: string): string {
  return new Date(value).toLocaleString('zh-TW')
}

async function fetchPolicy() {
  if (!policyId.value) {
    policy.value = null
    errorMessage.value = '保單 ID 格式不正確。'
    return
  }

  authStore.hydrate()
  if (authStore.authMode === 'mock') {
    policy.value = null
    errorMessage.value = '目前是 mock 模式，請改用後端 CSR / ADMIN 帳號登入後再操作繳費方式變更。'
    return
  }

  loading.value = true
  errorMessage.value = null
  businessRuleMessage.value = null

  try {
    const response = await getPolicyById(policyId.value)
    policy.value = response.policy
    policyStore.upsertPolicy(response.policy, response.etag)
    form.expectedVersion = response.policy.version
    form.newPaymentMethod =
      response.policy.premiumPaymentMethod === 'SINGLE_PAY'
        ? 'MONTHLY'
        : response.policy.premiumPaymentMethod
  } catch (error) {
    const apiError = normalizeApiError(error)
    policy.value = null
    errorMessage.value = apiError?.message ?? '讀取保單資料失敗，請稍後再試。'
  } finally {
    loading.value = false
  }
}

async function submitChange() {
  if (!policy.value || submitting.value) {
    return
  }

  if (!canChangePolicy.value) {
    errorMessage.value = '目前角色沒有變更權限，僅 CSR / ADMIN 可執行。'
    return
  }

  const ifMatch = policyStore.resolveIfMatch(policyId.value, policy.value.version)
  if (!ifMatch) {
    errorMessage.value = '找不到可用的 ETag，請先重新讀取保單後再試。'
    return
  }

  submitting.value = true
  errorMessage.value = null
  businessRuleMessage.value = null

  try {
    const response = await changePolicyPaymentMethod(
      policyId.value,
      {
        expectedVersion: form.expectedVersion,
        newPaymentMethod: form.newPaymentMethod,
        reason: form.reason.trim() || undefined,
      },
      {
        ifMatch,
        idempotencyKey: buildIdempotencyKey(),
      },
    )

    policy.value = response.policy
    policyStore.upsertPolicy(response.policy, response.etag)
    form.expectedVersion = response.policy.version
    form.newPaymentMethod =
      response.policy.premiumPaymentMethod === 'SINGLE_PAY'
        ? 'MONTHLY'
        : response.policy.premiumPaymentMethod
    form.reason = ''
    ElMessage.success('繳費方式變更成功。')
  } catch (error) {
    const apiError = normalizeApiError(error)
    if (apiError && (apiError.status === 409 || apiError.status === 412)) {
      conflictMessage.value =
        apiError.message.length > 0
          ? apiError.message
          : '資料已被他人異動，請重新讀取後再嘗試。'
      conflictDialogVisible.value = true
      return
    }

    if (apiError?.status === 422) {
      businessRuleMessage.value = apiError.message
      return
    }

    const message = apiError?.message ?? '繳費方式變更失敗，請稍後再試。'
    errorMessage.value = message
    ElMessage.error(message)
  } finally {
    submitting.value = false
  }
}

async function recoverFromConflict() {
  conflictDialogVisible.value = false
  await fetchPolicy()
}

async function goBackToDetail() {
  await router.push(`/policies/${policyId.value}`)
}

watch(
  () => route.params.id,
  async () => {
    if (route.name !== 'policy-change-payment-method') {
      return
    }
    await fetchPolicy()
  },
)

onMounted(async () => {
  if (route.name !== 'policy-change-payment-method') {
    return
  }
  await fetchPolicy()
})
</script>

<template>
  <main class="min-h-screen px-4 py-8">
    <div class="mx-auto max-w-4xl space-y-6">
      <header class="rounded-lg border border-cathay-primary/20 bg-cathay-primarySoft px-6 py-5">
        <div class="flex flex-col gap-4 tablet:flex-row tablet:items-start tablet:justify-between">
          <div class="space-y-2">
            <p class="text-caption uppercase tracking-[0.14em] text-cathay-primary">Policy Endorsement</p>
            <h1 class="text-h3 text-neutral-900">繳費方式變更</h1>
            <p class="text-caption text-neutral-500">支援月繳 / 季繳 / 半年繳 / 年繳切換，禁止改為躉繳。</p>
          </div>
          <div class="flex flex-wrap gap-2">
            <HeaderQuickActions />
            <el-button plain @click="goBackToDetail">返回詳情</el-button>
            <el-button type="primary" plain :loading="loading" @click="fetchPolicy">重新讀取</el-button>
          </div>
        </div>
      </header>

      <el-alert
        v-if="!canChangePolicy"
        title="目前角色沒有繳費方式變更權限（需要 CSR / ADMIN）。"
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

      <div v-if="loading" class="space-y-4">
        <BaseCard>
          <el-skeleton :rows="6" animated />
        </BaseCard>
      </div>

      <template v-else-if="policy">
        <BaseCard>
          <div class="grid gap-3 tablet:grid-cols-2">
            <div>
              <p class="text-caption text-neutral-500">保單號</p>
              <p class="text-body text-neutral-900">{{ policy.policyNumber }}</p>
            </div>
            <div>
              <p class="text-caption text-neutral-500">目前繳費方式</p>
              <p class="text-body text-neutral-900">{{ paymentMethodLabel(policy.premiumPaymentMethod) }}</p>
            </div>
            <div>
              <p class="text-caption text-neutral-500">目前版本</p>
              <p class="text-body text-neutral-900">v{{ policy.version }}</p>
            </div>
            <div>
              <p class="text-caption text-neutral-500">最後更新</p>
              <p class="text-body text-neutral-900">{{ formatTime(policy.updatedAt) }}</p>
            </div>
          </div>
        </BaseCard>

        <BaseCard>
          <el-form label-position="top" @submit.prevent="submitChange">
            <el-form-item label="expectedVersion">
              <el-input :model-value="form.expectedVersion" disabled />
            </el-form-item>

            <el-form-item label="新繳費方式" required>
              <el-select v-model="form.newPaymentMethod" placeholder="請選擇">
                <el-option
                  v-for="option in paymentOptions"
                  :key="option.value"
                  :label="option.label"
                  :value="option.value"
                />
              </el-select>
            </el-form-item>

            <el-alert
              v-if="businessRuleMessage"
              :title="businessRuleMessage"
              type="error"
              show-icon
              :closable="false"
              class="mb-4"
            />

            <el-form-item label="變更原因（選填）">
              <el-input
                v-model="form.reason"
                type="textarea"
                :rows="2"
                placeholder="例如：客戶申請改為年繳，減少扣款次數"
                maxlength="500"
                show-word-limit
              />
            </el-form-item>

            <div class="flex justify-end">
              <el-button
                type="primary"
                :loading="submitting"
                :disabled="!canChangePolicy"
                @click="submitChange"
              >
                送出繳費方式變更
              </el-button>
            </div>
          </el-form>
        </BaseCard>
      </template>

      <BaseCard v-else>
        <el-empty description="找不到保單資料，請返回詳情頁重試。" />
      </BaseCard>
    </div>
  </main>

  <el-dialog v-model="conflictDialogVisible" title="資料版本衝突" width="460">
    <div class="space-y-2">
      <p class="text-body text-neutral-900">{{ conflictMessage }}</p>
      <p class="text-caption text-neutral-500">系統會重新讀取最新保單內容，請確認後再送一次。</p>
    </div>
    <template #footer>
      <div class="flex justify-end gap-2">
        <el-button @click="conflictDialogVisible = false">先關閉</el-button>
        <el-button type="primary" :loading="loading" @click="recoverFromConflict">
          重新讀取
        </el-button>
      </div>
    </template>
  </el-dialog>
</template>
