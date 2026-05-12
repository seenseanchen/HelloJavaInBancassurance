import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import axios from 'axios'
import {
  listUnderwritingCases,
  type UnderwritingCase,
  type UnderwritingStatus,
} from '../api/underwriting'
import { useAuthStore } from './auth'

export const UNDERWRITING_STATUSES: readonly UnderwritingStatus[] = [
  'SUBMITTED',
  'UNDER_REVIEW',
  'PENDING_INFO',
  'APPROVED',
  'REJECTED',
  'WITHDRAWN',
] as const

export const useUnderwritingStore = defineStore('underwriting', () => {
  const loading = ref(false)
  const errorMessage = ref<string | null>(null)
  const cases = ref<UnderwritingCase[]>([])

  const selectedStatus = ref<UnderwritingStatus | undefined>(undefined)
  const page = ref(1)
  const size = ref(12)
  const totalElements = ref(0)
  const totalPages = ref(0)

  const hasCases = computed(() => cases.value.length > 0)

  async function fetchCases() {
    const authStore = useAuthStore()
    authStore.hydrate()

    if (authStore.authMode === 'mock') {
      errorMessage.value =
        '目前是 mock 模式，請改用後端帳號（例如 underwriter01/uw123 或 admin/admin123）登入後再讀取案件清單。'
      cases.value = []
      totalElements.value = 0
      totalPages.value = 0
      return
    }

    loading.value = true
    errorMessage.value = null

    try {
      const pageData = await listUnderwritingCases({
        status: selectedStatus.value,
        page: page.value - 1,
        size: size.value,
      })
      cases.value = pageData.content
      totalElements.value = pageData.totalElements
      totalPages.value = pageData.totalPages
    } catch (error) {
      if (axios.isAxiosError(error)) {
        const message = error.response?.data?.message
        errorMessage.value =
          typeof message === 'string' && message.length > 0
            ? message
            : '讀取案件清單失敗，請稍後再試。'
      } else {
        errorMessage.value = '讀取案件清單失敗，請稍後再試。'
      }
      cases.value = []
      totalElements.value = 0
      totalPages.value = 0
    } finally {
      loading.value = false
    }
  }

  function applyStatusFilter(status?: UnderwritingStatus) {
    selectedStatus.value = status
    page.value = 1
  }

  function setPage(nextPage: number) {
    page.value = nextPage
  }

  function setPageSize(nextSize: number) {
    size.value = nextSize
    page.value = 1
  }

  return {
    loading,
    errorMessage,
    cases,
    hasCases,
    page,
    size,
    totalElements,
    totalPages,
    selectedStatus,
    fetchCases,
    applyStatusFilter,
    setPage,
    setPageSize,
  }
})
