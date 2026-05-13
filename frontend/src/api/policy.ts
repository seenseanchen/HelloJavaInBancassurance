import { http } from './http'
import type { ApiEnvelope, PageResponse } from './types'

export type PolicyStatus =
  | 'IN_FORCE'
  | 'LAPSED'
  | 'MATURED'
  | 'SURRENDERED'
  | 'TERMINATED'

export type PolicyChannel = 'BANCASSURANCE' | 'AGENT' | 'ONLINE'

export type PremiumPaymentMethod =
  | 'MONTHLY'
  | 'QUARTERLY'
  | 'SEMI_ANNUAL'
  | 'ANNUAL'
  | 'SINGLE_PAY'

export type BeneficiaryRelationship =
  | 'SPOUSE'
  | 'CHILD'
  | 'PARENT'
  | 'SIBLING'
  | 'OTHER'

export type PolicyChangeType = 'BENEFICIARIES' | 'ADDRESS' | 'PAYMENT_METHOD'

export interface Beneficiary {
  id: string
  name: string
  maskedIdNumber: string
  relationship: BeneficiaryRelationship
  allocationPercentage: number
  priority: number
}

export interface PolicySummary {
  id: string
  policyNumber: string
  productCode: string
  holderName: string
  coverageAmount: number
  channel: PolicyChannel
  status: PolicyStatus
  effectiveDate: string
  expiryDate: string
}

export interface Policy {
  id: string
  policyNumber: string
  productCode: string
  underwritingCaseId: string | null
  holderName: string
  maskedHolderIdNumber: string
  insuredName: string
  maskedInsuredIdNumber: string
  coverageAmount: number
  premium: number
  premiumPaymentMethod: PremiumPaymentMethod
  channel: PolicyChannel
  status: PolicyStatus
  effectiveDate: string
  expiryDate: string
  billingAddress: string
  beneficiaries: Beneficiary[]
  version: number
  createdAt: string
  updatedAt: string
}

export interface PolicyChangeLog {
  id: string
  policyId: string
  changeType: PolicyChangeType
  beforeSnapshot: Record<string, unknown>
  afterSnapshot: Record<string, unknown>
  reason: string | null
  actor: string
  afterVersion: number
  occurredAt: string
}

export interface PolicySearchQuery {
  status?: PolicyStatus
  page?: number
  size?: number
  sort?: string[]
}

export interface PolicyWithEtag {
  policy: Policy
  etag: string | null
}

export interface ChangeAddressPayload {
  expectedVersion: number
  newAddress: string
  reason?: string
}

export interface BeneficiaryUpsertPayload {
  name: string
  idNumber: string
  relationship: BeneficiaryRelationship
  allocationPercentage: number
  priority: number
}

export interface ChangeBeneficiariesPayload {
  expectedVersion: number
  beneficiaries: BeneficiaryUpsertPayload[]
  reason?: string
}

export interface ChangePaymentMethodPayload {
  expectedVersion: number
  newPaymentMethod: PremiumPaymentMethod
  reason?: string
}

export interface MutationHeaders {
  ifMatch?: string
  idempotencyKey?: string
}

function extractEtag(headers: unknown): string | null {
  if (!headers || typeof headers !== 'object') {
    return null
  }

  const bag = headers as Record<string, unknown>
  const etag = bag.etag

  if (typeof etag === 'string' && etag.length > 0) {
    return etag
  }
  if (Array.isArray(etag) && etag.length > 0 && typeof etag[0] === 'string') {
    return etag[0]
  }

  return null
}

function buildMutationHeaders(headers: MutationHeaders): Record<string, string> {
  const result: Record<string, string> = {}
  if (headers.ifMatch && headers.ifMatch.length > 0) {
    result['If-Match'] = headers.ifMatch
  }
  if (headers.idempotencyKey && headers.idempotencyKey.length > 0) {
    result['Idempotency-Key'] = headers.idempotencyKey
  }
  return result
}

export async function searchPolicies(
  query: PolicySearchQuery,
): Promise<PageResponse<PolicySummary>> {
  const response = await http.get<ApiEnvelope<PageResponse<PolicySummary>>>('/policies', {
    params: {
      status: query.status,
      page: query.page ?? 0,
      size: query.size ?? 12,
      sort: query.sort ?? ['effectiveDate,desc'],
    },
  })

  return response.data.data
}

export async function getPolicyById(policyId: string): Promise<PolicyWithEtag> {
  const response = await http.get<ApiEnvelope<Policy>>(`/policies/${policyId}`)
  return {
    policy: response.data.data,
    etag: extractEtag(response.headers),
  }
}

export async function getPolicyByNumber(policyNumber: string): Promise<PolicyWithEtag> {
  const response = await http.get<ApiEnvelope<Policy>>(
    `/policies/by-number/${encodeURIComponent(policyNumber)}`,
  )
  return {
    policy: response.data.data,
    etag: extractEtag(response.headers),
  }
}

export async function changePolicyAddress(
  policyId: string,
  payload: ChangeAddressPayload,
  mutationHeaders: MutationHeaders,
): Promise<PolicyWithEtag> {
  const response = await http.patch<ApiEnvelope<Policy>>(
    `/policies/${policyId}/address`,
    payload,
    { headers: buildMutationHeaders(mutationHeaders) },
  )

  return {
    policy: response.data.data,
    etag: extractEtag(response.headers),
  }
}

export async function changePolicyBeneficiaries(
  policyId: string,
  payload: ChangeBeneficiariesPayload,
  mutationHeaders: MutationHeaders,
): Promise<PolicyWithEtag> {
  const response = await http.patch<ApiEnvelope<Policy>>(
    `/policies/${policyId}/beneficiaries`,
    payload,
    { headers: buildMutationHeaders(mutationHeaders) },
  )

  return {
    policy: response.data.data,
    etag: extractEtag(response.headers),
  }
}

export async function changePolicyPaymentMethod(
  policyId: string,
  payload: ChangePaymentMethodPayload,
  mutationHeaders: MutationHeaders,
): Promise<PolicyWithEtag> {
  const response = await http.patch<ApiEnvelope<Policy>>(
    `/policies/${policyId}/payment-method`,
    payload,
    { headers: buildMutationHeaders(mutationHeaders) },
  )

  return {
    policy: response.data.data,
    etag: extractEtag(response.headers),
  }
}

export async function listPolicyChanges(policyId: string): Promise<PolicyChangeLog[]> {
  const response = await http.get<ApiEnvelope<PolicyChangeLog[]>>(`/policies/${policyId}/changes`)
  return response.data.data
}
