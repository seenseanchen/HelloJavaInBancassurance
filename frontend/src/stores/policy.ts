import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import type { Policy } from '../api/policy'

type PolicyCache = Record<string, Policy>
type EtagCache = Record<string, string>

export const usePolicyStore = defineStore('policy', () => {
  const policies = ref<PolicyCache>({})
  const etags = ref<EtagCache>({})

  function upsertPolicy(policy: Policy, etag?: string | null) {
    policies.value[policy.id] = policy
    if (etag && etag.length > 0) {
      etags.value[policy.id] = etag
      return
    }

    if (!etags.value[policy.id]) {
      etags.value[policy.id] = `"${policy.version}"`
    }
  }

  function getPolicy(policyId: string): Policy | null {
    return policies.value[policyId] ?? null
  }

  function getPolicyEtag(policyId: string): string | null {
    return etags.value[policyId] ?? null
  }

  function resolveIfMatch(policyId: string, fallbackVersion?: number): string | null {
    const cached = getPolicyEtag(policyId)
    if (cached) {
      return cached
    }
    if (typeof fallbackVersion === 'number') {
      return `"${fallbackVersion}"`
    }
    return null
  }

  const cachedPolicyCount = computed(() => Object.keys(policies.value).length)

  return {
    upsertPolicy,
    getPolicy,
    getPolicyEtag,
    resolveIfMatch,
    cachedPolicyCount,
  }
})
