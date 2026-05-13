<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import BaseCard from '../components/BaseCard.vue'
import HeaderQuickActions from '../components/HeaderQuickActions.vue'
import {
  listPolicyChanges,
  type PolicyChangeLog,
  type PolicyChangeType,
} from '../api/policy'
import { normalizeApiError } from '../api/errors'
import { useAuthStore } from '../stores/auth'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()

const loading = ref(false)
const errorMessage = ref<string | null>(null)
const changes = ref<PolicyChangeLog[]>([])

const policyId = computed(() => {
  const param = route.params.id
  return typeof param === 'string' ? param : ''
})

const hasChanges = computed(() => changes.value.length > 0)

function changeTypeLabel(type: PolicyChangeType): string {
  switch (type) {
    case 'ADDRESS':
      return '地址變更'
    case 'BENEFICIARIES':
      return '受益人變更'
    case 'PAYMENT_METHOD':
      return '繳費方式變更'
    default:
      return type
  }
}

function timelineTypeByChangeType(
  type: PolicyChangeType,
): 'primary' | 'success' | 'warning' | 'danger' | 'info' {
  switch (type) {
    case 'ADDRESS':
      return 'primary'
    case 'BENEFICIARIES':
      return 'warning'
    case 'PAYMENT_METHOD':
      return 'success'
    default:
      return 'info'
  }
}

function formatTime(value: string): string {
  return new Date(value).toLocaleString('zh-TW')
}

function prettyJson(input: Record<string, unknown>): string {
  return JSON.stringify(input, null, 2)
}

async function fetchChanges() {
  if (!policyId.value) {
    errorMessage.value = '保單 ID 格式不正確。'
    changes.value = []
    return
  }

  authStore.hydrate()
  if (authStore.authMode === 'mock') {
    errorMessage.value = '目前是 mock 模式，請改用後端帳號登入後再讀取變更歷程。'
    changes.value = []
    return
  }

  loading.value = true
  errorMessage.value = null

  try {
    changes.value = await listPolicyChanges(policyId.value)
  } catch (error) {
    const apiError = normalizeApiError(error)
    errorMessage.value = apiError?.message ?? '讀取保單變更歷程失敗，請稍後再試。'
    changes.value = []
  } finally {
    loading.value = false
  }
}

async function goBackToDetail() {
  await router.push(`/policies/${policyId.value}`)
}

async function goBackToList() {
  await router.push('/policies')
}

watch(
  () => route.params.id,
  async () => {
    if (route.name !== 'policy-changes') {
      return
    }
    await fetchChanges()
  },
)

onMounted(async () => {
  if (route.name !== 'policy-changes') {
    return
  }
  await fetchChanges()
})
</script>

<template>
  <main class="min-h-screen px-4 py-8">
    <div class="mx-auto max-w-6xl space-y-6">
      <header class="rounded-lg border border-cathay-primary/20 bg-cathay-primarySoft px-6 py-5">
        <div class="flex flex-col gap-4 tablet:flex-row tablet:items-start tablet:justify-between">
          <div class="space-y-2">
            <p class="text-caption uppercase tracking-[0.14em] text-cathay-primary">Policy Endorsement</p>
            <h1 class="text-h3 text-neutral-900">保單變更歷程</h1>
            <p class="text-caption text-neutral-500">保單 ID：{{ policyId }}</p>
          </div>
          <div class="flex flex-wrap gap-2">
            <HeaderQuickActions />
            <el-button plain @click="goBackToList">返回查詢</el-button>
            <el-button plain @click="goBackToDetail">返回詳情</el-button>
            <el-button type="primary" plain :loading="loading" @click="fetchChanges">重新整理</el-button>
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

      <template v-else>
        <BaseCard v-if="hasChanges">
          <div class="space-y-3">
            <p class="text-caption text-neutral-500">共 {{ changes.length }} 筆，依發生時間降冪排序。</p>

            <el-timeline>
              <el-timeline-item
                v-for="item in changes"
                :key="item.id"
                :timestamp="formatTime(item.occurredAt)"
                placement="top"
                :type="timelineTypeByChangeType(item.changeType)"
              >
                <div class="rounded-md border border-neutral-300 bg-white p-4 shadow-card">
                  <div class="flex flex-wrap items-center gap-2">
                    <el-tag :type="timelineTypeByChangeType(item.changeType)" effect="light" round>
                      {{ changeTypeLabel(item.changeType) }}
                    </el-tag>
                    <span class="text-caption text-neutral-500">操作人：{{ item.actor }}</span>
                    <span class="text-caption text-neutral-500">版本：v{{ item.afterVersion }}</span>
                  </div>

                  <p v-if="item.reason" class="mt-3 text-body text-neutral-900">原因：{{ item.reason }}</p>

                  <div class="mt-3 grid gap-3 tablet:grid-cols-2">
                    <div class="rounded-md bg-neutral-100/70 p-3">
                      <p class="mb-2 text-caption text-neutral-500">Before</p>
                      <pre class="overflow-x-auto text-caption text-neutral-900">{{ prettyJson(item.beforeSnapshot) }}</pre>
                    </div>
                    <div class="rounded-md bg-neutral-100/70 p-3">
                      <p class="mb-2 text-caption text-neutral-500">After</p>
                      <pre class="overflow-x-auto text-caption text-neutral-900">{{ prettyJson(item.afterSnapshot) }}</pre>
                    </div>
                  </div>
                </div>
              </el-timeline-item>
            </el-timeline>
          </div>
        </BaseCard>

        <BaseCard v-else>
          <el-empty description="此保單目前沒有可顯示的變更歷程。" />
        </BaseCard>
      </template>
    </div>
  </main>
</template>
