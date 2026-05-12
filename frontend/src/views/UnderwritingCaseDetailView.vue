<script setup lang="ts">
import axios from 'axios'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import BaseCard from '../components/BaseCard.vue'
import StatusBadge from '../components/StatusBadge.vue'
import {
  getUnderwritingCaseById,
  transitionUnderwritingCase,
  type UnderwritingCase,
  type UnderwritingStatus,
  type UnderwritingTransitionAction,
} from '../api/underwriting'
import type { ApiErrorResponse } from '../api/types'
import { useAuthStore } from '../stores/auth'

interface TransitionOperation {
  action: UnderwritingTransitionAction
  label: string
  nextState: UnderwritingStatus
  buttonType: 'primary' | 'success' | 'warning' | 'danger' | 'info'
  requiresComment: boolean
  commentTitle?: string
  commentPlaceholder?: string
}

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()

const loading = ref(false)
const acting = ref(false)
const actingAction = ref<UnderwritingTransitionAction | null>(null)
const caseDetail = ref<UnderwritingCase | null>(null)
const errorMessage = ref<string | null>(null)

const conflictDialogVisible = ref(false)
const conflictMessage = ref('狀態已被他人變更，請重新讀取後再嘗試。')

const caseId = computed(() => {
  const param = route.params.id
  return typeof param === 'string' ? param : ''
})

const transitionOperations = computed(() => {
  const currentCase = caseDetail.value
  if (!currentCase) {
    return []
  }

  const nextStates = Array.isArray(currentCase.nextStates)
    ? currentCase.nextStates
    : []

  return nextStates
    .map((nextState) => toTransitionOperation(currentCase.status, nextState))
    .filter((operation): operation is TransitionOperation => operation !== null)
})

function toTransitionOperation(
  current: UnderwritingStatus,
  next: UnderwritingStatus,
): TransitionOperation | null {
  switch (`${current}->${next}`) {
    case 'SUBMITTED->UNDER_REVIEW':
      return {
        action: 'claim',
        label: '領件',
        nextState: next,
        buttonType: 'primary',
        requiresComment: false,
      }
    case 'SUBMITTED->WITHDRAWN':
      return {
        action: 'withdraw',
        label: '撤件',
        nextState: next,
        buttonType: 'warning',
        requiresComment: true,
        commentTitle: '撤件原因',
        commentPlaceholder: '請輸入撤件原因',
      }
    case 'UNDER_REVIEW->APPROVED':
      return {
        action: 'approve',
        label: '核准',
        nextState: next,
        buttonType: 'success',
        requiresComment: false,
      }
    case 'UNDER_REVIEW->REJECTED':
      return {
        action: 'reject',
        label: '退件',
        nextState: next,
        buttonType: 'danger',
        requiresComment: true,
        commentTitle: '退件原因',
        commentPlaceholder: '請輸入退件原因',
      }
    case 'UNDER_REVIEW->PENDING_INFO':
      return {
        action: 'request-info',
        label: '要求補件',
        nextState: next,
        buttonType: 'warning',
        requiresComment: true,
        commentTitle: '補件說明',
        commentPlaceholder: '請輸入需補件的內容與原因',
      }
    case 'UNDER_REVIEW->WITHDRAWN':
      return {
        action: 'withdraw',
        label: '撤件',
        nextState: next,
        buttonType: 'warning',
        requiresComment: true,
        commentTitle: '撤件原因',
        commentPlaceholder: '請輸入撤件原因',
      }
    case 'PENDING_INFO->UNDER_REVIEW':
      return {
        action: 'resubmit',
        label: '補件重送',
        nextState: next,
        buttonType: 'primary',
        requiresComment: false,
      }
    case 'PENDING_INFO->WITHDRAWN':
      return {
        action: 'withdraw',
        label: '撤件',
        nextState: next,
        buttonType: 'warning',
        requiresComment: true,
        commentTitle: '撤件原因',
        commentPlaceholder: '請輸入撤件原因',
      }
    default:
      return null
  }
}

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

async function fetchCaseDetail() {
  if (!caseId.value) {
    errorMessage.value = '案件 ID 格式不正確。'
    caseDetail.value = null
    return
  }

  authStore.hydrate()
  if (authStore.authMode === 'mock') {
    errorMessage.value =
      '目前是 mock 模式，請改用後端帳號（例如 underwriter01/uw123 或 admin/admin123）登入後再讀取案件詳情。'
    caseDetail.value = null
    return
  }

  loading.value = true
  errorMessage.value = null

  try {
    caseDetail.value = await getUnderwritingCaseById(caseId.value)
  } catch (error) {
    const apiError = normalizeApiError(error)
    errorMessage.value = apiError?.message ?? '讀取案件詳情失敗，請稍後再試。'
    caseDetail.value = null
  } finally {
    loading.value = false
  }
}

async function askRequiredComment(operation: TransitionOperation): Promise<string | null> {
  if (!operation.requiresComment) {
    return ''
  }

  try {
    const result = await ElMessageBox.prompt(
      operation.commentTitle ?? `${operation.label}說明`,
      `執行 ${operation.label}`,
      {
        confirmButtonText: '送出',
        cancelButtonText: '取消',
        inputType: 'textarea',
        inputPlaceholder: operation.commentPlaceholder ?? '請輸入說明',
        inputValidator: (value) =>
          value.trim().length > 0 ? true : '此動作需要填寫說明',
      },
    )
    return result.value.trim()
  } catch {
    return null
  }
}

async function handleTransition(operation: TransitionOperation) {
  if (!caseDetail.value || acting.value) {
    return
  }

  const comment = await askRequiredComment(operation)
  if (comment === null) {
    return
  }

  acting.value = true
  actingAction.value = operation.action
  errorMessage.value = null

  try {
    caseDetail.value = await transitionUnderwritingCase(caseId.value, operation.action, {
      comment: comment.length > 0 ? comment : undefined,
    })
    ElMessage.success(`${operation.label}成功`)
  } catch (error) {
    const apiError = normalizeApiError(error)
    if (apiError?.status === 409) {
      conflictMessage.value =
        apiError.message.length > 0
          ? apiError.message
          : '狀態已被他人變更，請重新讀取後再嘗試。'
      conflictDialogVisible.value = true
      return
    }

    const message = apiError?.message ?? '狀態流轉失敗，請稍後再試。'
    errorMessage.value = message
    ElMessage.error(message)
  } finally {
    acting.value = false
    actingAction.value = null
  }
}

async function reloadAfterConflict() {
  conflictDialogVisible.value = false
  await fetchCaseDetail()
}

async function goBackToList() {
  await router.push({ name: 'underwriting-cases' })
}

async function goToEvents() {
  await router.push({
    name: 'underwriting-case-events',
    params: { id: caseId.value },
  })
}

watch(
  () => route.params.id,
  async () => {
    if (route.name !== 'underwriting-case-detail') {
      return
    }
    await fetchCaseDetail()
  },
)

onMounted(async () => {
  if (route.name !== 'underwriting-case-detail') {
    return
  }
  await fetchCaseDetail()
})
</script>

<template>
  <main class="min-h-screen px-4 py-8">
    <div class="mx-auto max-w-5xl space-y-6">
      <header class="rounded-lg border border-cathay-primary/20 bg-cathay-primarySoft px-6 py-5">
        <div class="flex flex-col gap-4 tablet:flex-row tablet:items-start tablet:justify-between">
          <div class="space-y-2">
            <p class="text-caption uppercase tracking-[0.14em] text-cathay-primary">Underwriting</p>
            <h1 class="text-h3 text-neutral-900">核保案件詳情</h1>
            <p class="text-caption text-neutral-500">
              可操作按鈕由後端 `nextStates` 動態決定，避免前端寫死流程。
            </p>
          </div>
          <div class="flex gap-2">
            <el-button plain @click="goBackToList">返回清單</el-button>
            <el-button plain @click="goToEvents">查看歷程</el-button>
            <el-button type="primary" plain :loading="loading" @click="fetchCaseDetail">
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
          <el-skeleton :rows="6" animated />
        </BaseCard>
      </div>

      <template v-else-if="caseDetail">
        <BaseCard>
          <div class="space-y-4">
            <div class="flex flex-col gap-3 tablet:flex-row tablet:items-start tablet:justify-between">
              <div class="space-y-1">
                <p class="text-caption text-neutral-500">案件編號</p>
                <h2 class="text-h5 text-neutral-900">{{ caseDetail.caseNumber }}</h2>
                <p class="text-caption text-neutral-500">案件 ID：{{ caseDetail.id }}</p>
              </div>
              <StatusBadge :status="caseDetail.status" />
            </div>

            <div class="grid gap-3 tablet:grid-cols-2">
              <div class="space-y-1">
                <p class="text-caption text-neutral-500">要保人</p>
                <p class="text-body text-neutral-900">
                  {{ caseDetail.applicantName }}（{{ caseDetail.maskedApplicantIdNumber }}）
                </p>
              </div>
              <div class="space-y-1">
                <p class="text-caption text-neutral-500">商品代碼 / 通路</p>
                <p class="text-body text-neutral-900">
                  {{ caseDetail.productCode }} / {{ caseDetail.channel }}
                </p>
              </div>
              <div class="space-y-1">
                <p class="text-caption text-neutral-500">保額</p>
                <p class="text-body text-neutral-900">{{ formatCurrency(caseDetail.coverageAmount) }}</p>
              </div>
              <div class="space-y-1">
                <p class="text-caption text-neutral-500">保費</p>
                <p class="text-body text-neutral-900">{{ formatCurrency(caseDetail.premium) }}</p>
              </div>
              <div class="space-y-1">
                <p class="text-caption text-neutral-500">送件人 / 送件時間</p>
                <p class="text-body text-neutral-900">
                  {{ caseDetail.submittedBy }} / {{ formatTime(caseDetail.submittedAt) }}
                </p>
              </div>
              <div class="space-y-1">
                <p class="text-caption text-neutral-500">審查人 / 審查時間</p>
                <p class="text-body text-neutral-900">
                  {{ caseDetail.reviewedBy ?? '-' }} / {{ formatTime(caseDetail.reviewedAt) }}
                </p>
              </div>
              <div class="space-y-1">
                <p class="text-caption text-neutral-500">當前狀態</p>
                <p class="text-body text-neutral-900">{{ statusLabel(caseDetail.status) }}</p>
              </div>
              <div class="space-y-1">
                <p class="text-caption text-neutral-500">最後更新</p>
                <p class="text-body text-neutral-900">{{ formatTime(caseDetail.updatedAt) }}</p>
              </div>
            </div>

            <div class="rounded-md border border-neutral-300 bg-neutral-100/60 p-3">
              <p class="text-caption text-neutral-500">審查備註</p>
              <p class="mt-1 text-body text-neutral-900">{{ caseDetail.reviewComment ?? '目前沒有備註。' }}</p>
            </div>
          </div>
        </BaseCard>

        <BaseCard>
          <div class="space-y-4">
            <div class="space-y-1">
              <h2 class="text-h6 text-neutral-900">狀態流轉操作</h2>
              <p class="text-caption text-neutral-500">
                目前可操作：{{ transitionOperations.length }} 項（依後端 nextStates 變化）
              </p>
            </div>

            <div v-if="transitionOperations.length > 0" class="flex flex-wrap gap-2">
              <el-button
                v-for="operation in transitionOperations"
                :key="operation.action"
                :type="operation.buttonType"
                :loading="acting && actingAction === operation.action"
                :disabled="acting"
                @click="handleTransition(operation)"
              >
                {{ operation.label }}
              </el-button>
            </div>

            <el-empty v-else description="此案件已在終態，沒有可執行的流轉操作。" :image-size="90" />
          </div>
        </BaseCard>
      </template>

      <BaseCard v-else>
        <el-empty description="找不到案件資料，請返回清單重試。" />
      </BaseCard>
    </div>
  </main>

  <el-dialog v-model="conflictDialogVisible" title="狀態衝突" width="460">
    <div class="space-y-2">
      <p class="text-body text-neutral-900">{{ conflictMessage }}</p>
      <p class="text-caption text-neutral-500">建議重新讀取案件後，再執行下一步。</p>
    </div>
    <template #footer>
      <div class="flex justify-end gap-2">
        <el-button @click="conflictDialogVisible = false">先關閉</el-button>
        <el-button type="primary" :loading="loading" @click="reloadAfterConflict">
          重新讀取
        </el-button>
      </div>
    </template>
  </el-dialog>
</template>
