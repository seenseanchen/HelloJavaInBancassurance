import { http } from './http'
import type { ApiEnvelope, PageResponse } from './types'

export type UnderwritingStatus =
  | 'SUBMITTED'
  | 'UNDER_REVIEW'
  | 'PENDING_INFO'
  | 'APPROVED'
  | 'REJECTED'
  | 'WITHDRAWN'

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
