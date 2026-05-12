<script setup lang="ts">
import { computed } from 'vue'
import type { UnderwritingStatus } from '../api/underwriting'

const props = defineProps<{
  status: UnderwritingStatus
}>()

const badgeType = computed(() => {
  switch (props.status) {
    case 'APPROVED':
      return 'success'
    case 'REJECTED':
      return 'danger'
    case 'UNDER_REVIEW':
      return 'warning'
    case 'PENDING_INFO':
      return 'warning'
    default:
      return 'info'
  }
})

const label = computed(() => {
  switch (props.status) {
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
      return props.status
  }
})
</script>

<template>
  <el-tag :type="badgeType" effect="light" round>
    {{ label }}
  </el-tag>
</template>
