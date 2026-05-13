import axios from 'axios'
import type { ApiErrorResponse } from './types'

export function normalizeApiError(error: unknown): ApiErrorResponse | null {
  if (!axios.isAxiosError(error)) {
    return null
  }

  const payload = error.response?.data
  if (!payload || typeof payload !== 'object') {
    return null
  }

  const candidate = payload as Partial<ApiErrorResponse>
  if (
    typeof candidate.status !== 'number' ||
    typeof candidate.code !== 'string' ||
    typeof candidate.message !== 'string'
  ) {
    return null
  }

  return {
    status: candidate.status,
    error: typeof candidate.error === 'string' ? candidate.error : '',
    code: candidate.code,
    message: candidate.message,
    path: typeof candidate.path === 'string' ? candidate.path : '',
    timestamp: typeof candidate.timestamp === 'string' ? candidate.timestamp : '',
    traceId: typeof candidate.traceId === 'string' ? candidate.traceId : null,
  }
}
