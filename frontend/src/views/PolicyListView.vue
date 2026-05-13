<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import BaseCard from '../components/BaseCard.vue'
import PolicyStatusBadge from '../components/PolicyStatusBadge.vue'
import {
  getPolicyByNumber,
  searchPolicies,
  type Policy,
  type PolicyStatus,
  type PolicySummary,
} from '../api/policy'
import { normalizeApiError } from '../api/errors'
import { useAuthStore } from '../stores/auth'
import { usePolicyStore } from '../stores/policy'

const POLICY_STATUSES: readonly PolicyStatus[] = [
  'IN_FORCE',
  'LAPSED',
  'MATURED',
  'SURRENDERED',
  'TERMINATED',
] as const

const statusLabelMap: Record<PolicyStatus, string> = {
  IN_FORCE: '生效中',
  LAPSED: '停效',
  MATURED: '滿期',
  SURRENDERED: '解約',
  TERMINATED: '終止',
}

const router = useRouter()
const authStore = useAuthStore()
const policyStore = usePolicyStore()

const loading = ref(false)
const errorMessage = ref<string | null>(null)
const policies = ref<PolicySummary[]>([])
const searchedByPolicyNumber = ref(false)

const page = ref(1)
const size = ref(12)
const totalElements = ref(0)
const totalPages = ref(0)

const filters = reactive({
  policyNumber: '',
  status: undefined as PolicyStatus | undefined,
  holderNameKeyword: '',
})

const statusOptions = computed(() =>
  POLICY_STATUSES.map((status) => ({
    label: statusLabelMap[status],
    value: status,
  })),
)

const filteredPolicies = computed(() => {
  const keyword = filters.holderNameKeyword.trim().toLowerCase()
  if (keyword.length === 0) {
    return policies.value
  }

  return policies.value.filter((item) =>
    item.holderName.toLowerCase().includes(keyword),
  )
})

function formatCurrency(value: number): string {
  return new Intl.NumberFormat('zh-TW', {
    style: 'currency',
    currency: 'TWD',
    maximumFractionDigits: 0,
  }).format(value)
}

function formatDate(value: string): string {
  if (value.length === 0) {
    return '-'
  }
  return value
}

function toSummary(policy: Policy): PolicySummary {
  return {
    id: policy.id,
    policyNumber: policy.policyNumber,
    productCode: policy.productCode,
    holderName: policy.holderName,
    coverageAmount: policy.coverageAmount,
    channel: policy.channel,
    status: policy.status,
    effectiveDate: policy.effectiveDate,
    expiryDate: policy.expiryDate,
  }
}

async function fetchPolicies() {
  authStore.hydrate()
  if (authStore.authMode === 'mock') {
    errorMessage.value =
      '目前是 mock 模式，請改用後端帳號（例如 underwriter01/uw123 或 csr01/csr123）登入後再讀取保單資料。'
    policies.value = []
    totalElements.value = 0
    totalPages.value = 0
    return
  }

  loading.value = true
  errorMessage.value = null

  try {
    const policyNumber = filters.policyNumber.trim()
    if (policyNumber.length > 0) {
      const { policy, etag } = await getPolicyByNumber(policyNumber)
      policyStore.upsertPolicy(policy, etag)
      policies.value = [toSummary(policy)]
      totalElements.value = 1
      totalPages.value = 1
      page.value = 1
      searchedByPolicyNumber.value = true
      return
    }

    searchedByPolicyNumber.value = false
    const pageData = await searchPolicies({
      status: filters.status,
      page: page.value - 1,
      size: size.value,
      sort: ['effectiveDate,desc'],
    })
    policies.value = pageData.content
    totalElements.value = pageData.totalElements
    totalPages.value = pageData.totalPages
  } catch (error) {
    const apiError = normalizeApiError(error)
    errorMessage.value = apiError?.message ?? '讀取保單清單失敗，請稍後再試。'
    policies.value = []
    totalElements.value = 0
    totalPages.value = 0
  } finally {
    loading.value = false
  }
}

async function searchWithResetPage() {
  page.value = 1
  await fetchPolicies()
}

async function resetFilters() {
  filters.policyNumber = ''
  filters.status = undefined
  filters.holderNameKeyword = ''
  page.value = 1
  await fetchPolicies()
}

async function onPageChange(nextPage: number) {
  if (searchedByPolicyNumber.value) {
    return
  }
  page.value = nextPage
  await fetchPolicies()
}

async function onPageSizeChange(nextSize: number) {
  if (searchedByPolicyNumber.value) {
    return
  }
  size.value = nextSize
  page.value = 1
  await fetchPolicies()
}

async function openPolicyDetail(policyId: string) {
  await router.push(`/policies/${policyId}`)
}

async function goHome() {
  await router.push('/home')
}

onMounted(async () => {
  await fetchPolicies()
})
</script>

<template>
  <main class="min-h-screen px-4 py-8">
    <div class="mx-auto max-w-6xl space-y-6">
      <header class="rounded-lg border border-cathay-primary/20 bg-cathay-primarySoft px-6 py-5">
        <div class="flex flex-col gap-4 tablet:flex-row tablet:items-end tablet:justify-between">
          <div class="space-y-1">
            <p class="text-caption uppercase tracking-[0.14em] text-cathay-primary">Policy Service</p>
            <h1 class="text-h3 text-neutral-900">保單查詢</h1>
            <p class="text-caption text-neutral-500">支援保單號精準查詢、狀態篩選與本頁要保人姓名關鍵字過濾。</p>
          </div>
          <div class="flex gap-2">
            <el-button plain @click="goHome">返回首頁</el-button>
            <el-button type="primary" plain :loading="loading" @click="fetchPolicies">重新整理</el-button>
          </div>
        </div>
      </header>

      <BaseCard>
        <div class="grid gap-4 tablet:grid-cols-[1.3fr_1fr_1fr_auto] tablet:items-end">
          <el-form-item label="保單號（精準）" class="!mb-0">
            <el-input
              v-model="filters.policyNumber"
              clearable
              placeholder="例如 BANK-LIFE-20260507-0001"
              @keyup.enter="searchWithResetPage"
            />
          </el-form-item>

          <el-form-item label="保單狀態" class="!mb-0">
            <el-select v-model="filters.status" clearable placeholder="全部狀態">
              <el-option
                v-for="option in statusOptions"
                :key="option.value"
                :label="option.label"
                :value="option.value"
              />
            </el-select>
          </el-form-item>

          <el-form-item label="要保人姓名（本頁過濾）" class="!mb-0">
            <el-input
              v-model="filters.holderNameKeyword"
              clearable
              placeholder="輸入關鍵字"
            />
          </el-form-item>

          <div class="flex gap-2">
            <el-button plain @click="resetFilters">清除</el-button>
            <el-button type="primary" @click="searchWithResetPage">查詢</el-button>
          </div>
        </div>

        <p class="mt-3 text-caption text-neutral-500">
          顯示 {{ filteredPolicies.length }} 筆
          <template v-if="!searchedByPolicyNumber">
            （伺服器回傳總筆數 {{ totalElements }}，第 {{ page }} / {{ totalPages || 1 }} 頁）
          </template>
          <template v-else>（保單號查詢模式）</template>
        </p>
      </BaseCard>

      <el-alert
        v-if="errorMessage"
        :title="errorMessage"
        type="warning"
        show-icon
        :closable="false"
      />

      <div v-if="loading" class="space-y-4">
        <BaseCard v-for="index in 3" :key="index">
          <el-skeleton :rows="4" animated />
        </BaseCard>
      </div>

      <section v-else-if="filteredPolicies.length > 0" class="space-y-4">
        <BaseCard v-for="item in filteredPolicies" :key="item.id">
          <div class="space-y-4">
            <div class="flex flex-col gap-3 tablet:flex-row tablet:items-start tablet:justify-between">
              <div class="space-y-1">
                <p class="text-caption text-neutral-500">保單號</p>
                <h2 class="text-h6 text-neutral-900">{{ item.policyNumber }}</h2>
                <p class="text-caption text-neutral-500">商品代碼：{{ item.productCode }}</p>
              </div>
              <PolicyStatusBadge :status="item.status" />
            </div>

            <div class="grid gap-3 tablet:grid-cols-2">
              <div class="space-y-1">
                <p class="text-caption text-neutral-500">要保人</p>
                <p class="text-body text-neutral-900">{{ item.holderName }}</p>
              </div>
              <div class="space-y-1">
                <p class="text-caption text-neutral-500">投保通路</p>
                <p class="text-body text-neutral-900">{{ item.channel }}</p>
              </div>
              <div class="space-y-1">
                <p class="text-caption text-neutral-500">保額</p>
                <p class="text-body text-neutral-900">{{ formatCurrency(item.coverageAmount) }}</p>
              </div>
              <div class="space-y-1">
                <p class="text-caption text-neutral-500">生效 / 到期日</p>
                <p class="text-body text-neutral-900">{{ formatDate(item.effectiveDate) }} / {{ formatDate(item.expiryDate) }}</p>
              </div>
            </div>

            <div class="flex justify-end">
              <el-button type="primary" plain @click="openPolicyDetail(item.id)">查看詳情</el-button>
            </div>
          </div>
        </BaseCard>
      </section>

      <BaseCard v-else-if="policies.length > 0">
        <el-empty description="本頁資料中找不到符合要保人關鍵字的保單。" />
      </BaseCard>

      <BaseCard v-else>
        <el-empty description="目前沒有符合條件的保單。" />
      </BaseCard>

      <BaseCard v-if="!searchedByPolicyNumber">
        <el-pagination
          background
          layout="total, sizes, prev, pager, next"
          :total="totalElements"
          :current-page="page"
          :page-size="size"
          :page-sizes="[12, 20, 50]"
          @current-change="onPageChange"
          @size-change="onPageSizeChange"
        />
      </BaseCard>
    </div>
  </main>
</template>
