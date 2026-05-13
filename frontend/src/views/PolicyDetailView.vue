<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import BaseCard from '../components/BaseCard.vue'
import HeaderQuickActions from '../components/HeaderQuickActions.vue'
import PolicyStatusBadge from '../components/PolicyStatusBadge.vue'
import { getPolicyById, type Policy, type PremiumPaymentMethod } from '../api/policy'
import { normalizeApiError } from '../api/errors'
import { useAuthStore } from '../stores/auth'
import { usePolicyStore } from '../stores/policy'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()
const policyStore = usePolicyStore()

const loading = ref(false)
const errorMessage = ref<string | null>(null)
const policy = ref<Policy | null>(null)
const etag = ref<string | null>(null)

const policyId = computed(() => {
  const id = route.params.id
  return typeof id === 'string' ? id : ''
})

const canChangePolicy = computed(() => {
  const role = authStore.user?.role
  return role === 'csr' || role === 'admin'
})

function formatCurrency(value: number): string {
  return new Intl.NumberFormat('zh-TW', {
    style: 'currency',
    currency: 'TWD',
    maximumFractionDigits: 0,
  }).format(value)
}

function formatDate(value: string | null | undefined): string {
  if (typeof value !== 'string' || value.trim().length === 0) {
    return '-'
  }
  return value
}

function formatTime(value: string): string {
  return new Date(value).toLocaleString('zh-TW')
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

function relationshipLabel(relationship: string): string {
  switch (relationship) {
    case 'SPOUSE':
      return '配偶'
    case 'CHILD':
      return '子女'
    case 'PARENT':
      return '父母'
    case 'SIBLING':
      return '兄弟姊妹'
    case 'OTHER':
      return '其他'
    default:
      return relationship
  }
}

async function fetchPolicyDetail() {
  if (!policyId.value) {
    errorMessage.value = '保單 ID 格式不正確。'
    policy.value = null
    etag.value = null
    return
  }

  authStore.hydrate()
  if (authStore.authMode === 'mock') {
    errorMessage.value =
      '目前是 mock 模式，請改用後端帳號（例如 underwriter01/uw123 或 csr01/csr123）登入後再讀取保單詳情。'
    policy.value = null
    etag.value = null
    return
  }

  loading.value = true
  errorMessage.value = null

  try {
    const response = await getPolicyById(policyId.value)
    policy.value = response.policy
    etag.value = response.etag
    policyStore.upsertPolicy(response.policy, response.etag)
  } catch (error) {
    const apiError = normalizeApiError(error)
    errorMessage.value = apiError?.message ?? '讀取保單詳情失敗，請稍後再試。'
    policy.value = null
    etag.value = null
  } finally {
    loading.value = false
  }
}

async function goBackToList() {
  await router.push('/policies')
}

async function goToChanges() {
  await router.push(`/policies/${policyId.value}/changes`)
}

async function goToChangeAddress() {
  await router.push(`/policies/${policyId.value}/change/address`)
}

async function goToChangeBeneficiaries() {
  await router.push(`/policies/${policyId.value}/change/beneficiaries`)
}

async function goToChangePaymentMethod() {
  await router.push(`/policies/${policyId.value}/change/payment-method`)
}

watch(
  () => route.params.id,
  async () => {
    if (route.name !== 'policy-detail') {
      return
    }
    await fetchPolicyDetail()
  },
)

onMounted(async () => {
  if (route.name !== 'policy-detail') {
    return
  }
  await fetchPolicyDetail()
})
</script>

<template>
  <main class="min-h-screen px-4 py-8">
    <div class="mx-auto max-w-6xl space-y-6">
      <header class="rounded-lg border border-cathay-primary/20 bg-cathay-primarySoft px-6 py-5">
        <div class="flex flex-col gap-4 tablet:flex-row tablet:items-start tablet:justify-between">
          <div class="space-y-2">
            <p class="text-caption uppercase tracking-[0.14em] text-cathay-primary">Policy Service</p>
            <h1 class="text-h3 text-neutral-900">保單詳情</h1>
            <p class="text-caption text-neutral-500">此頁會保存 ETag，供後續變更頁自動帶 `If-Match` 使用。</p>
          </div>
          <div class="flex flex-wrap gap-2">
            <HeaderQuickActions />
            <el-button plain @click="goBackToList">返回查詢</el-button>
            <el-button plain @click="goToChanges">變更歷史</el-button>
            <el-button type="primary" plain :loading="loading" @click="fetchPolicyDetail">
              重新整理
            </el-button>
          </div>
        </div>
      </header>

      <el-alert
        v-if="errorMessage"
        :title="errorMessage"
        type="warning"
        show-icon
        :closable="false"
      />

      <div v-if="loading" class="space-y-4">
        <BaseCard>
          <el-skeleton :rows="8" animated />
        </BaseCard>
      </div>

      <template v-else-if="policy">
        <BaseCard>
          <div class="space-y-4">
            <div class="flex flex-col gap-3 tablet:flex-row tablet:items-start tablet:justify-between">
              <div class="space-y-1">
                <p class="text-caption text-neutral-500">保單號</p>
                <h2 class="text-h5 text-neutral-900">{{ policy.policyNumber }}</h2>
                <p class="text-caption text-neutral-500">保單 ID：{{ policy.id }}</p>
              </div>
              <PolicyStatusBadge :status="policy.status" />
            </div>

            <div class="grid gap-3 tablet:grid-cols-2">
              <div class="space-y-1">
                <p class="text-caption text-neutral-500">要保人</p>
                <p class="text-body text-neutral-900">
                  {{ policy.holderName }}（{{ policy.maskedHolderIdNumber }}）
                </p>
              </div>
              <div class="space-y-1">
                <p class="text-caption text-neutral-500">被保險人</p>
                <p class="text-body text-neutral-900">
                  {{ policy.insuredName }}（{{ policy.maskedInsuredIdNumber }}）
                </p>
              </div>
              <div class="space-y-1">
                <p class="text-caption text-neutral-500">商品代碼 / 通路</p>
                <p class="text-body text-neutral-900">{{ policy.productCode }} / {{ policy.channel }}</p>
              </div>
              <div class="space-y-1">
                <p class="text-caption text-neutral-500">繳費方式</p>
                <p class="text-body text-neutral-900">{{ paymentMethodLabel(policy.premiumPaymentMethod) }}</p>
              </div>
              <div class="space-y-1">
                <p class="text-caption text-neutral-500">保額 / 保費</p>
                <p class="text-body text-neutral-900">{{ formatCurrency(policy.coverageAmount) }} / {{ formatCurrency(policy.premium) }}</p>
              </div>
              <div class="space-y-1">
                <p class="text-caption text-neutral-500">生效 / 到期日</p>
                <p class="text-body text-neutral-900">{{ formatDate(policy.effectiveDate) }} / {{ formatDate(policy.expiryDate) }}</p>
              </div>
              <div class="space-y-1">
                <p class="text-caption text-neutral-500">版本 / ETag</p>
                <p class="text-body text-neutral-900">v{{ policy.version }} / {{ etag ?? `"${policy.version}"` }}</p>
              </div>
              <div class="space-y-1">
                <p class="text-caption text-neutral-500">最後更新時間</p>
                <p class="text-body text-neutral-900">{{ formatTime(policy.updatedAt) }}</p>
              </div>
            </div>

            <div class="rounded-md border border-neutral-300 bg-neutral-100/60 p-3">
              <p class="text-caption text-neutral-500">帳單地址</p>
              <p class="mt-1 text-body text-neutral-900">{{ policy.billingAddress }}</p>
            </div>
          </div>
        </BaseCard>

        <BaseCard>
          <div class="space-y-4">
            <div class="space-y-1">
              <h2 class="text-h6 text-neutral-900">變更操作</h2>
              <p class="text-caption text-neutral-500">需要 CSR / ADMIN 角色。提交時會自動帶 If-Match 與 Idempotency-Key。</p>
            </div>

            <el-alert
              v-if="!canChangePolicy"
              title="目前角色僅可查詢，無法執行保單變更（需要 CSR / ADMIN）。"
              type="info"
              show-icon
              :closable="false"
            />

            <div class="flex flex-wrap gap-2">
              <el-button type="primary" plain :disabled="!canChangePolicy" @click="goToChangeAddress">
                變更地址
              </el-button>
              <el-button type="primary" plain :disabled="!canChangePolicy" @click="goToChangeBeneficiaries">
                變更受益人
              </el-button>
              <el-button type="primary" plain :disabled="!canChangePolicy" @click="goToChangePaymentMethod">
                變更繳費方式
              </el-button>
            </div>
          </div>
        </BaseCard>

        <BaseCard>
          <div class="space-y-3">
            <h2 class="text-h6 text-neutral-900">受益人清單</h2>
            <div v-if="policy.beneficiaries.length > 0" class="overflow-x-auto">
              <table class="min-w-full border-collapse">
                <thead>
                  <tr class="border-b border-neutral-300 text-left text-caption text-neutral-500">
                    <th class="px-3 py-2">順位</th>
                    <th class="px-3 py-2">姓名</th>
                    <th class="px-3 py-2">關係</th>
                    <th class="px-3 py-2">身分證</th>
                    <th class="px-3 py-2">比例(%)</th>
                  </tr>
                </thead>
                <tbody>
                  <tr
                    v-for="beneficiary in policy.beneficiaries"
                    :key="beneficiary.id"
                    class="border-b border-neutral-200 text-body text-neutral-900"
                  >
                    <td class="px-3 py-2">{{ beneficiary.priority }}</td>
                    <td class="px-3 py-2">{{ beneficiary.name }}</td>
                    <td class="px-3 py-2">{{ relationshipLabel(beneficiary.relationship) }}</td>
                    <td class="px-3 py-2">{{ beneficiary.maskedIdNumber }}</td>
                    <td class="px-3 py-2">{{ beneficiary.allocationPercentage }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
            <el-empty v-else description="此保單目前沒有受益人資料。" />
          </div>
        </BaseCard>
      </template>

      <BaseCard v-else>
        <el-empty description="找不到保單資料，請返回查詢頁重試。" />
      </BaseCard>
    </div>
  </main>
</template>
