<script setup lang="ts">
import axios from 'axios'
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import BaseCard from '../components/BaseCard.vue'
import StatusBadge from '../components/StatusBadge.vue'
import {
  listUnderwritingCaseEvents,
  type UnderwritingCaseEvent,
  type UnderwritingEventType,
  type UnderwritingStatus,
} from '../api/underwriting'
import type { ApiErrorResponse } from '../api/types'
import { useAuthStore } from '../stores/auth'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()

const loading = ref(false)
const errorMessage = ref<string | null>(null)
const events = ref<UnderwritingCaseEvent[]>([])

const caseId = computed(() => {
  const param = route.params.id
  return typeof param === 'string' ? param : ''
})

const hasEvents = computed(() => events.value.length > 0)

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

function formatTime(value: string): string {
  return new Date(value).toLocaleString('zh-TW')
}

function statusLabel(status: UnderwritingStatus): string {
  switch (status) {
    case 'SUBMITTED':
      return '已送件'
    case 'UNDER_REVIEW':
      return '審查中'
    case 'PENDING_INFO':
      return '補件中'
    case 'APPROVED':
      return '核准'
    case 'REJECTED':
      return '退件'
    case 'WITHDRAWN':
      return '撤件'
    default:
      return status
  }
}

function actionLabel(action: UnderwritingEventType): string {
  switch (action) {
    case 'CASE_SUBMITTED':
      return '案件送件'
    case 'CASE_CLAIMED':
      return '核保員領件'
    case 'INFO_REQUESTED':
      return '要求補件'
    case 'CASE_RESUBMITTED':
      return '補件重送'
    case 'CASE_APPROVED':
      return '核准'
    case 'CASE_REJECTED':
      return '退件'
    case 'CASE_WITHDRAWN':
      return '撤件'
    default:
      return action
  }
}

function timelineTypeByAction(
  action: UnderwritingEventType,
): 'primary' | 'success' | 'warning' | 'danger' | 'info' {
  switch (action) {
    case 'CASE_APPROVED':
      return 'success'
    case 'CASE_REJECTED':
      return 'danger'
    case 'INFO_REQUESTED':
    case 'CASE_WITHDRAWN':
      return 'warning'
    case 'CASE_CLAIMED':
    case 'CASE_RESUBMITTED':
      return 'primary'
    default:
      return 'info'
  }
}

async function fetchEvents() {
  if (!caseId.value) {
    errorMessage.value = '案件 ID 格式不正確。'
    events.value = []
    return
  }

  authStore.hydrate()
  if (authStore.authMode === 'mock') {
    errorMessage.value =
      '目前是 mock 模式，請改用後端帳號（例如 underwriter01/uw123 或 admin/admin123）登入後再讀取案件歷程。'
    events.value = []
    return
  }

  loading.value = true
  errorMessage.value = null

  try {
    events.value = await listUnderwritingCaseEvents(caseId.value)
  } catch (error) {
    const apiError = normalizeApiError(error)
    errorMessage.value = apiError?.message ?? '讀取案件歷程失敗，請稍後再試。'
    events.value = []
  } finally {
    loading.value = false
  }
}

async function goBackToDetail() {
  await router.push({
    name: 'underwriting-case-detail',
    params: { id: caseId.value },
  })
}

async function goBackToList() {
  await router.push({ name: 'underwriting-cases' })
}

watch(
  () => route.params.id,
  async () => {
    if (route.name !== 'underwriting-case-events') {
      return
    }
    await fetchEvents()
  },
)

onMounted(async () => {
  if (route.name !== 'underwriting-case-events') {
    return
  }
  await fetchEvents()
})
</script>

<template>
  <main class="min-h-screen px-4 py-8">
    <div class="mx-auto max-w-5xl space-y-6">
      <header class="rounded-lg border border-cathay-primary/20 bg-cathay-primarySoft px-6 py-5">
        <div class="flex flex-col gap-4 tablet:flex-row tablet:items-start tablet:justify-between">
          <div class="space-y-2">
            <p class="text-caption uppercase tracking-[0.14em] text-cathay-primary">Underwriting</p>
            <h1 class="text-h3 text-neutral-900">案件歷程 Timeline</h1>
            <p class="text-caption text-neutral-500">案件 ID：{{ caseId }}</p>
          </div>
          <div class="flex gap-2">
            <el-button plain @click="goBackToList">返回清單</el-button>
            <el-button plain @click="goBackToDetail">返回詳情</el-button>
            <el-button type="primary" plain :loading="loading" @click="fetchEvents">重新整理</el-button>
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
          <el-skeleton :rows="6" animated />
        </BaseCard>
      </div>

      <template v-else>
        <BaseCard v-if="hasEvents">
          <div class="space-y-3">
            <p class="text-caption text-neutral-500">共 {{ events.length }} 筆事件，依發生時間升冪排列。</p>

            <el-timeline>
              <el-timeline-item
                v-for="item in events"
                :key="item.id"
                :timestamp="formatTime(item.occurredAt)"
                placement="top"
                :type="timelineTypeByAction(item.action)"
              >
                <div class="rounded-md border border-neutral-300 bg-white p-4 shadow-card">
                  <div class="flex flex-wrap items-center gap-2">
                    <el-tag :type="timelineTypeByAction(item.action)" effect="light" round>
                      {{ actionLabel(item.action) }}
                    </el-tag>
                    <span class="text-caption text-neutral-500">操作人：{{ item.actor }}</span>
                  </div>

                  <div class="mt-3 flex flex-wrap items-center gap-2">
                    <span class="text-caption text-neutral-500">狀態變更：</span>
                    <template v-if="item.fromStatus">
                      <StatusBadge :status="item.fromStatus" />
                      <span class="text-caption text-neutral-500">→</span>
                    </template>
                    <StatusBadge :status="item.toStatus" />
                    <span class="text-caption text-neutral-500">（{{ statusLabel(item.toStatus) }}）</span>
                  </div>

                  <div class="mt-3 rounded-md bg-neutral-100/60 p-3">
                    <p class="text-caption text-neutral-500">備註</p>
                    <p class="mt-1 text-body text-neutral-900">{{ item.comment ?? '無備註' }}</p>
                  </div>
                </div>
              </el-timeline-item>
            </el-timeline>
          </div>
        </BaseCard>

        <BaseCard v-else>
          <el-empty description="此案件目前沒有可顯示的歷程事件。" />
        </BaseCard>
      </template>
    </div>
  </main>
</template>
