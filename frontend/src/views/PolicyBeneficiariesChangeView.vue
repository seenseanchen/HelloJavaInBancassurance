<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import BaseCard from '../components/BaseCard.vue'
import HeaderQuickActions from '../components/HeaderQuickActions.vue'
import {
  changePolicyBeneficiaries,
  getPolicyById,
  type BeneficiaryRelationship,
  type Policy,
} from '../api/policy'
import { normalizeApiError } from '../api/errors'
import { useAuthStore } from '../stores/auth'
import { usePolicyStore } from '../stores/policy'

interface BeneficiaryDraft {
  name: string
  idNumber: string
  relationship: BeneficiaryRelationship | ''
  allocationPercentage: number | undefined
  priority: number | undefined
}

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()
const policyStore = usePolicyStore()

const loading = ref(false)
const submitting = ref(false)
const errorMessage = ref<string | null>(null)
const businessRuleMessage = ref<string | null>(null)
const policy = ref<Policy | null>(null)
const beneficiaries = ref<BeneficiaryDraft[]>([])

const conflictDialogVisible = ref(false)
const conflictMessage = ref('資料已被他人異動，請重新讀取後再嘗試。')

const form = reactive({
  expectedVersion: 0,
  reason: '',
})

const relationshipOptions: Array<{ label: string; value: BeneficiaryRelationship }> = [
  { label: '配偶', value: 'SPOUSE' },
  { label: '子女', value: 'CHILD' },
  { label: '父母', value: 'PARENT' },
  { label: '兄弟姊妹', value: 'SIBLING' },
  { label: '其他', value: 'OTHER' },
]

const policyId = computed(() => {
  const param = route.params.id
  return typeof param === 'string' ? param : ''
})

const canChangePolicy = computed(() => {
  const role = authStore.user?.role
  return role === 'csr' || role === 'admin'
})

const allocationSum = computed(() =>
  beneficiaries.value.reduce((sum, item) => sum + Number(item.allocationPercentage ?? 0), 0),
)

const hasPriorityOne = computed(() =>
  beneficiaries.value.some((item) => item.priority === 1),
)

function buildIdempotencyKey(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return `policy-change-${Date.now()}-${Math.random().toString(16).slice(2)}`
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

function resetDraftFromPolicy(targetPolicy: Policy) {
  beneficiaries.value = targetPolicy.beneficiaries.map((item) => ({
    name: item.name,
    idNumber: '',
    relationship: item.relationship,
    allocationPercentage: Number(item.allocationPercentage),
    priority: item.priority,
  }))

  if (beneficiaries.value.length === 0) {
    beneficiaries.value = [
      {
        name: '',
        idNumber: '',
        relationship: '',
        allocationPercentage: undefined,
        priority: 1,
      },
    ]
  }
}

function addBeneficiary() {
  beneficiaries.value.push({
    name: '',
    idNumber: '',
    relationship: '',
    allocationPercentage: undefined,
    priority: beneficiaries.value.length + 1,
  })
}

function removeBeneficiary(index: number) {
  if (beneficiaries.value.length <= 1) {
    return
  }
  beneficiaries.value.splice(index, 1)
}

function validateDraft(): string | null {
  if (beneficiaries.value.length === 0) {
    return '至少需要一位受益人。'
  }

  for (const [index, item] of beneficiaries.value.entries()) {
    if (item.name.trim().length === 0) {
      return `第 ${index + 1} 位受益人姓名不可為空。`
    }
    if (!/^[A-Z][0-9]{9}$/.test(item.idNumber.trim().toUpperCase())) {
      return `第 ${index + 1} 位受益人的身分證號格式不正確。`
    }
    if (item.relationship === '') {
      return `第 ${index + 1} 位受益人需選擇關係。`
    }
    if (typeof item.allocationPercentage !== 'number' || item.allocationPercentage <= 0) {
      return `第 ${index + 1} 位受益人的比例必須大於 0。`
    }
    if (item.allocationPercentage > 100) {
      return `第 ${index + 1} 位受益人的比例不可超過 100。`
    }
    if (typeof item.priority !== 'number' || item.priority < 1) {
      return `第 ${index + 1} 位受益人的順位需大於等於 1。`
    }
  }

  if (Math.abs(allocationSum.value - 100) > 0.001) {
    return `受益人比例加總需等於 100，目前為 ${allocationSum.value.toFixed(2)}。`
  }

  if (!hasPriorityOne.value) {
    return '至少需要一位第一順位受益人（priority = 1）。'
  }

  return null
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
    errorMessage.value = '目前是 mock 模式，請改用後端 CSR / ADMIN 帳號登入後再操作受益人變更。'
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
    resetDraftFromPolicy(response.policy)
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

  const validationMessage = validateDraft()
  if (validationMessage) {
    businessRuleMessage.value = validationMessage
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
    const response = await changePolicyBeneficiaries(
      policyId.value,
      {
        expectedVersion: form.expectedVersion,
        beneficiaries: beneficiaries.value.map((item) => ({
          name: item.name.trim(),
          idNumber: item.idNumber.trim().toUpperCase(),
          relationship: item.relationship as BeneficiaryRelationship,
          allocationPercentage: Number(Number(item.allocationPercentage ?? 0).toFixed(2)),
          priority: Number(item.priority),
        })),
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
    form.reason = ''
    resetDraftFromPolicy(response.policy)
    ElMessage.success('受益人變更成功。')
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

    const message = apiError?.message ?? '受益人變更失敗，請稍後再試。'
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
    if (route.name !== 'policy-change-beneficiaries') {
      return
    }
    await fetchPolicy()
  },
)

onMounted(async () => {
  if (route.name !== 'policy-change-beneficiaries') {
    return
  }
  await fetchPolicy()
})
</script>

<template>
  <main class="min-h-screen px-4 py-8">
    <div class="mx-auto max-w-5xl space-y-6">
      <header class="rounded-lg border border-cathay-primary/20 bg-cathay-primarySoft px-6 py-5">
        <div class="flex flex-col gap-4 tablet:flex-row tablet:items-start tablet:justify-between">
          <div class="space-y-2">
            <p class="text-caption uppercase tracking-[0.14em] text-cathay-primary">Policy Endorsement</p>
            <h1 class="text-h3 text-neutral-900">受益人變更（全量替換）</h1>
            <p class="text-caption text-neutral-500">前端先檢核比例加總 = 100，再送後端做最終業務規則驗證。</p>
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
        title="目前角色沒有受益人變更權限（需要 CSR / ADMIN）。"
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
          <el-skeleton :rows="8" animated />
        </BaseCard>
      </div>

      <template v-else-if="policy">
        <BaseCard>
          <div class="space-y-3">
            <p class="text-caption text-neutral-500">目前保單版本：v{{ policy.version }}</p>
            <div class="overflow-x-auto">
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
                    v-for="item in policy.beneficiaries"
                    :key="item.id"
                    class="border-b border-neutral-200 text-body text-neutral-900"
                  >
                    <td class="px-3 py-2">{{ item.priority }}</td>
                    <td class="px-3 py-2">{{ item.name }}</td>
                    <td class="px-3 py-2">{{ relationshipLabel(item.relationship) }}</td>
                    <td class="px-3 py-2">{{ item.maskedIdNumber }}</td>
                    <td class="px-3 py-2">{{ item.allocationPercentage }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
            <p class="text-caption text-neutral-500">
              送出變更時需重填完整受益人清單（含完整身分證號），後端會做全量替換。
            </p>
          </div>
        </BaseCard>

        <BaseCard>
          <el-form label-position="top" @submit.prevent="submitChange">
            <div class="space-y-4">
              <div
                v-for="(item, index) in beneficiaries"
                :key="`beneficiary-${index}`"
                class="rounded-md border border-neutral-300 p-4"
              >
                <div class="mb-3 flex items-center justify-between">
                  <h3 class="text-body font-semibold text-neutral-900">受益人 {{ index + 1 }}</h3>
                  <el-button
                    text
                    type="danger"
                    :disabled="beneficiaries.length <= 1"
                    @click="removeBeneficiary(index)"
                  >
                    移除
                  </el-button>
                </div>

                <div class="grid gap-3 tablet:grid-cols-2">
                  <el-form-item label="姓名" required>
                    <el-input v-model="item.name" placeholder="例如：陳大美" maxlength="64" />
                  </el-form-item>
                  <el-form-item label="身分證號" required>
                    <el-input
                      v-model="item.idNumber"
                      placeholder="例如：B234567890"
                      maxlength="10"
                      @input="item.idNumber = item.idNumber.toUpperCase()"
                    />
                  </el-form-item>
                  <el-form-item label="關係" required>
                    <el-select v-model="item.relationship" placeholder="請選擇">
                      <el-option
                        v-for="option in relationshipOptions"
                        :key="option.value"
                        :label="option.label"
                        :value="option.value"
                      />
                    </el-select>
                  </el-form-item>
                  <el-form-item label="順位" required>
                    <el-input-number v-model="item.priority" :min="1" :step="1" class="!w-full" />
                  </el-form-item>
                  <el-form-item label="比例 (%)" required class="tablet:col-span-2">
                    <el-input-number
                      v-model="item.allocationPercentage"
                      :min="0.01"
                      :max="100"
                      :step="0.01"
                      :precision="2"
                      class="!w-full"
                    />
                  </el-form-item>
                </div>
              </div>

              <div class="flex justify-start">
                <el-button plain @click="addBeneficiary">新增受益人</el-button>
              </div>

              <div class="rounded-md bg-neutral-100/70 p-3">
                <p class="text-body text-neutral-900">比例加總：{{ allocationSum.toFixed(2) }}%</p>
                <p class="text-caption text-neutral-500">第一順位存在：{{ hasPriorityOne ? '是' : '否' }}</p>
              </div>

              <el-alert
                v-if="businessRuleMessage"
                :title="businessRuleMessage"
                type="error"
                show-icon
                :closable="false"
              />

              <el-form-item label="變更原因（選填）">
                <el-input
                  v-model="form.reason"
                  type="textarea"
                  :rows="2"
                  placeholder="例如：結婚，新增配偶為第一順位受益人"
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
                  送出受益人變更
                </el-button>
              </div>
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
