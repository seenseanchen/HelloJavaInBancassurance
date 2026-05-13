<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import BaseCard from '../components/BaseCard.vue'
import StatusBadge from '../components/StatusBadge.vue'
import {
  UNDERWRITING_STATUSES,
  useUnderwritingStore,
} from '../stores/underwriting'
import type { UnderwritingStatus } from '../api/underwriting'

const underwritingStore = useUnderwritingStore()
const router = useRouter()

const statusLabelMap: Record<UnderwritingStatus, string> = {
  SUBMITTED: '已送件',
  UNDER_REVIEW: '審查中',
  PENDING_INFO: '補件中',
  APPROVED: '核准',
  REJECTED: '退件',
  WITHDRAWN: '撤件',
}

const statusOptions = computed(() =>
  UNDERWRITING_STATUSES.map((status) => ({
    label: statusLabelMap[status],
    value: status,
  })),
)

function formatCurrency(value: number): string {
  return new Intl.NumberFormat('zh-TW', {
    style: 'currency',
    currency: 'TWD',
    maximumFractionDigits: 0,
  }).format(value)
}

function formatTime(value: string | null): string {
  if (!value) {
    return '-'
  }
  return new Date(value).toLocaleString('zh-TW')
}

async function onStatusChange(status?: UnderwritingStatus) {
  underwritingStore.applyStatusFilter(status)
  await underwritingStore.fetchCases()
}

async function onPageChange(nextPage: number) {
  underwritingStore.setPage(nextPage)
  await underwritingStore.fetchCases()
}

async function onPageSizeChange(nextSize: number) {
  underwritingStore.setPageSize(nextSize)
  await underwritingStore.fetchCases()
}

async function openCaseDetail(caseId: string) {
  await router.push(`/underwriting/cases/${caseId}`)
}

async function openCreateCase() {
  await router.push('/underwriting/cases/new')
}

onMounted(async () => {
  await underwritingStore.fetchCases()
})
</script>

<template>
  <main class="min-h-screen px-4 py-8">
    <div class="mx-auto max-w-6xl space-y-6">
      <header class="rounded-lg border border-cathay-primary/20 bg-cathay-primarySoft px-6 py-5">
        <div class="flex flex-col gap-2 tablet:flex-row tablet:items-end tablet:justify-between">
          <div class="space-y-1">
            <p class="text-caption uppercase tracking-[0.14em] text-cathay-primary">Underwriting</p>
            <h1 class="text-h3 text-neutral-900">核保案件清單</h1>
            <p class="text-caption text-neutral-500">支援狀態篩選、分頁與詳情導覽。</p>
          </div>
          <div class="flex gap-2">
            <el-button type="primary" plain @click="openCreateCase">新增案件</el-button>
            <el-button type="primary" plain @click="underwritingStore.fetchCases">重新整理</el-button>
          </div>
        </div>
      </header>

      <BaseCard>
        <div class="grid gap-4 tablet:grid-cols-[280px_1fr] tablet:items-end">
          <el-form-item label="案件狀態篩選" class="!mb-0">
            <el-select
              :model-value="underwritingStore.selectedStatus"
              clearable
              placeholder="全部狀態"
              @update:model-value="onStatusChange"
            >
              <el-option
                v-for="option in statusOptions"
                :key="option.value"
                :label="option.label"
                :value="option.value"
              />
            </el-select>
          </el-form-item>
          <p class="text-caption text-neutral-500">
            共 {{ underwritingStore.totalElements }} 筆，當前第 {{ underwritingStore.page }} / {{ underwritingStore.totalPages || 1 }} 頁
          </p>
        </div>
      </BaseCard>

      <el-alert
        v-if="underwritingStore.errorMessage"
        :title="underwritingStore.errorMessage"
        type="warning"
        show-icon
        :closable="false"
      />

      <div v-if="underwritingStore.loading" class="space-y-4">
        <BaseCard v-for="index in 3" :key="index">
          <el-skeleton :rows="4" animated />
        </BaseCard>
      </div>

      <section v-else-if="underwritingStore.hasCases" class="space-y-4">
        <BaseCard v-for="item in underwritingStore.cases" :key="item.id">
          <div class="space-y-4">
            <div class="flex flex-col gap-3 tablet:flex-row tablet:items-start tablet:justify-between">
              <div class="space-y-1">
                <p class="text-caption text-neutral-500">案件編號</p>
                <h2 class="text-h6 text-neutral-900">{{ item.caseNumber }}</h2>
                <p class="text-caption text-neutral-500">送件人：{{ item.submittedBy }}</p>
              </div>
              <StatusBadge :status="item.status" />
            </div>

            <div class="grid gap-3 tablet:grid-cols-2">
              <div class="space-y-1">
                <p class="text-caption text-neutral-500">要保人</p>
                <p class="text-body text-neutral-900">{{ item.applicantName }}（{{ item.maskedApplicantIdNumber }}）</p>
              </div>
              <div class="space-y-1">
                <p class="text-caption text-neutral-500">商品代碼 / 通路</p>
                <p class="text-body text-neutral-900">{{ item.productCode }} / {{ item.channel }}</p>
              </div>
              <div class="space-y-1">
                <p class="text-caption text-neutral-500">保額</p>
                <p class="text-body text-neutral-900">{{ formatCurrency(item.coverageAmount) }}</p>
              </div>
              <div class="space-y-1">
                <p class="text-caption text-neutral-500">保費</p>
                <p class="text-body text-neutral-900">{{ formatCurrency(item.premium) }}</p>
              </div>
              <div class="space-y-1">
                <p class="text-caption text-neutral-500">送件時間</p>
                <p class="text-body text-neutral-900">{{ formatTime(item.submittedAt) }}</p>
              </div>
              <div class="space-y-1">
                <p class="text-caption text-neutral-500">最後審查人 / 時間</p>
                <p class="text-body text-neutral-900">{{ item.reviewedBy ?? '-' }} / {{ formatTime(item.reviewedAt) }}</p>
              </div>
            </div>
            <div class="flex justify-end">
              <el-button type="primary" plain @click="openCaseDetail(item.id)">查看詳情</el-button>
            </div>
          </div>
        </BaseCard>
      </section>

      <BaseCard v-else>
        <el-empty description="目前沒有符合條件的核保案件" />
      </BaseCard>

      <BaseCard>
        <el-pagination
          background
          layout="total, sizes, prev, pager, next"
          :total="underwritingStore.totalElements"
          :current-page="underwritingStore.page"
          :page-size="underwritingStore.size"
          :page-sizes="[12, 20, 50]"
          @current-change="onPageChange"
          @size-change="onPageSizeChange"
        />
      </BaseCard>
    </div>
  </main>
</template>
