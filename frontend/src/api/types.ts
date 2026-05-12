export interface ApiEnvelope<T> {
  code: string
  message: string
  data: T
  traceId: string
}

export interface PageResponse<T> {
  content: T[]
  totalElements: number
  totalPages: number
  size: number
  number: number
  numberOfElements: number
  first: boolean
  last: boolean
  empty: boolean
}
