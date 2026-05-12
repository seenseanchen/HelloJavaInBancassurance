import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

export const useAppStore = defineStore('app', () => {
  const initializedAt = ref(new Date().toISOString())
  const appReady = computed(() => initializedAt.value.length > 0)

  return {
    appReady,
    initializedAt,
  }
})
