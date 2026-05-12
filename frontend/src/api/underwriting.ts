import { http } from './http'
import type { ApiEnvelope, PageResponse } from './types'

export type UnderwritingStatus =
  | 'SUBMITTED'
  | 'UNDER_REVIEW'
  | 'PENDING_INFO'
  | 'APPROVED'
  | 'REJECTED'
  | 'WITHDRAWN'

export type UnderwritingEventType =
  | 'CASE_SUBMITTED'
  | 'CASE_CLAIMED'
  | 'INFO_REQUESTED'
  | 'CASE_RESUBMITTED'
  | 'CASE_APPROVED'
  | 'CASE_REJECTED'
  | 'CASE_WITHDRAWN'

export interface UnderwritingCase {
  id: string
  caseNumber: string
  applicantName: string
  maskedApplicantIdNumber: string
  productCode: string
  coverageAmount: number
  premium: number
  channel: string
  status: UnderwritingStatus
  nextStates?: UnderwritingStatus[]
  submittedBy: string
  reviewedBy: string | null
  reviewComment: string | null
  submittedAt: string
  reviewedAt: string | null
  createdAt: string
  updatedAt: string
}

export interface UnderwritingCaseListQuery {
  status?: UnderwritingStatus
  page?: number
  size?: number
  sort?: string[]
}

export interface UnderwritingCaseEvent {
  id: string
  caseId: string
  action: UnderwritingEventType
  fromStatus: UnderwritingStatus | null
  toStatus: UnderwritingStatus
  actor: string
  comment: string | null
  occurredAt: string
}

export type UnderwritingTransitionAction =
  | 'claim'
  | 'request-info'
  | 'resubmit'
  | 'approve'
  | 'reject'
  | 'withdraw'

export interface UnderwritingTransitionPayload {
  comment?: string
}

export async function listUnderwritingCases(
  query: UnderwritingCaseListQuery,
): Promise<PageResponse<UnderwritingCase>> {
  const response = await http.get<ApiEnvelope<PageResponse<UnderwritingCase>>>(
    '/underwriting/cases',
    {
      params: {
        status: query.status,
        page: query.page ?? 0,
        size: query.size ?? 12,
        sort: query.sort ?? ['submittedAt,desc'],
      },
    },
  )

  return response.data.data
}

export async function getUnderwritingCaseById(id: string): Promise<UnderwritingCase> {
  const response = await http.get<ApiEnvelope<UnderwritingCase>>(
    `/underwriting/cases/${id}`,
  )
  return response.data.data
}

export async function transitionUnderwritingCase(
  id: string,
  action: UnderwritingTransitionAction,
  payload: UnderwritingTransitionPayload = {},
): Promise<UnderwritingCase> {
  const response = await http.post<ApiEnvelope<UnderwritingCase>>(
    `/underwriting/cases/${id}/${action}`,
    payload,
  )
  return response.data.data
}

export async function listUnderwritingCaseEvents(
  id: string,
): Promise<UnderwritingCaseEvent[]> {
  const response = await http.get<ApiEnvelope<UnderwritingCaseEvent[]>>(
    `/underwriting/cases/${id}/events`,
  )
  return response.data.data
}
