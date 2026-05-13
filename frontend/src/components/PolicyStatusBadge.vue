<script setup lang="ts">
import { computed } from 'vue'
import type { PolicyStatus } from '../api/policy'

const props = defineProps<{
  status: PolicyStatus
}>()

const badgeType = computed(() => {
  switch (props.status) {
    case 'IN_FORCE':
      return 'success'
    case 'LAPSED':
      return 'warning'
    case 'SURRENDERED':
      return 'danger'
    case 'MATURED':
    case 'TERMINATED':
      return 'info'
    default:
      return 'info'
  }
})

const label = computed(() => {
  switch (props.status) {
    case 'IN_FORCE':
      return '生效中'
    case 'LAPSED':
      return '停效'
    case 'MATURED':
      return '滿期'
    case 'SURRENDERED':
      return '解約'
    case 'TERMINATED':
      return '終止'
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
