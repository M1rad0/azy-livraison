import { AdminApiError } from './admin'
import type { ClientPayoutDestination } from './client'
import { cashListQuery, type CashListFilters, type CashPage } from './cashLedger'

export interface CashBriefingTransfer {
  id: string; sellerAccountId: string; sellerName: string; sellerPayoutNumber?: string | null
  sellerPayoutNumberOverride?: boolean
  sellerPayoutDestinationId?: string | null
  sellerPayoutAccountHolder?: string | null
  sellerPayoutOperator?: string | null
  courierAccountId: string; courierName: string; amountAriary: number
  status: 'PENDING' | 'CONFIRMED' | 'REPLACED' | 'VOIDED'; movementId?: string | null
  readyToConfirm?: boolean; blockedReason?: string | null
}
export interface CashBriefingCourierTransfer {
  id: string; fromCourierAccountId: string; fromCourierName: string
  toCourierAccountId: string; toCourierName: string; amountAriary: number
  status: 'PENDING' | 'CONFIRMED' | 'REPLACED' | 'VOIDED'; movementId?: string | null
  readyToConfirm: boolean; blockedReason?: string | null
}
export interface CashBriefingBatch {
  id: string; planId: string; serviceDate: string
  status: 'ACTIVE' | 'STALE' | 'RELEASED' | 'EXPIRED' | 'COMPLETED' | 'PLAN_INVALID'
  ledgerRevision: number; expiresAt: string; algorithm: string; message: string; mode?: 'AUTO' | 'MANUAL'
  transfers: CashBriefingTransfer[]
  courierTransfers?: CashBriefingCourierTransfer[]; workflowVersion?: number
  balances: { courierAccountId: string; courierName: string; balanceAriary: number; availableAriary: number; reservedAriary: number }[]
}
interface CashBriefingTransferRowBase {
  batchId: string
  serviceDate: string
  batchStatus: CashBriefingBatch['status']
  ledgerRevision: number
  batchMessage: string
}
export type CashBriefingTransferRow = CashBriefingTransferRowBase & (
  | { kind?: 'SELLER_PAYOUT'; transfer: CashBriefingTransfer; courierTransfer?: null }
  | { kind: 'COURIER_TRANSFER'; transfer?: null; courierTransfer: CashBriefingCourierTransfer }
)
export interface CashBriefingPlanning {
  planId: string; serviceDate: string; ledgerRevision: number; batch?: CashBriefingBatch | null
  sellers: { sellerAccountId: string; sellerName: string; sellerPayoutNumber?: string | null; payoutDestinations?: ClientPayoutDestination[]; balanceAriary: number; plannedAriary: number; availableAriary: number }[]
  couriers: { courierAccountId: string; courierName: string; balanceAriary: number; reservedElsewhereAriary: number; capacityAriary: number; plannedAriary: number; remainingAriary: number; incomingAriary?: number; outgoingAriary?: number }[]
}
export interface ManualCashBriefingInput {
  planId: string; idempotencyKey: string; expectedLedgerRevision: number; expectedBatchId?: string | null
  transfers: { sellerAccountId: string; courierAccountId: string; amountAriary: number; sellerPayoutDestinationId?: string; sellerPayoutOperator?: string; sellerPayoutNumber?: string; sellerPayoutAccountHolder?: string }[]
  courierTransfers?: { fromCourierAccountId: string; toCourierAccountId: string; amountAriary: number }[]
}
const BASE = `${import.meta.env.VITE_API_BASE_URL ?? '/api'}/admin/cash-briefings`
async function request<T>(path: string, token: string, init: RequestInit = {}): Promise<T> {
  const response = await fetch(`${BASE}${path}`, { ...init, headers: { Accept: 'application/json', Authorization: `Bearer ${token}`, ...init.headers } })
  if (!response.ok) {
    const error = await response.json().catch(() => ({})) as { message?: string; code?: string }
    throw new AdminApiError(error.message ?? 'Les propositions de versement sont indisponibles.', response.status, error.code)
  }
  return response.json() as Promise<T>
}
const post = (data?: unknown): RequestInit => ({ method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data ?? {}) })
export const listCashBriefings = (token: string, planId?: string, signal?: AbortSignal) => request<CashBriefingBatch[]>(planId ? `?planId=${encodeURIComponent(planId)}` : '', token, { signal })
export const listCashBriefingTransfers = (token: string, filters: CashListFilters, signal?: AbortSignal) => request<CashPage<CashBriefingTransferRow>>(`/transfers?${cashListQuery(filters)}`, token, { signal })
export const getCashBriefingPlanning = (planId: string, token: string, signal?: AbortSignal) => request<CashBriefingPlanning>(`/planning?planId=${encodeURIComponent(planId)}`, token, { signal })
export const saveManualCashBriefing = (input: ManualCashBriefingInput, token: string) => request<CashBriefingBatch>('/manual', token, post(input))
export const prepareCashBriefing = (planId: string, token: string, idempotencyKey: string = crypto.randomUUID(), replacement?: { replaceExisting: true; expectedLedgerRevision: number; expectedBatchId: string | null }) => request<CashBriefingBatch>('/prepare', token, post({ planId, idempotencyKey, ...replacement }))
export const releaseCashBriefing = (id: string, token: string) => request<CashBriefingBatch>(`/${encodeURIComponent(id)}/release`, token, post())
export const revalidateCashBriefing = (id: string, token: string) => request<CashBriefingBatch>(`/${encodeURIComponent(id)}/revalidate`, token, post())
interface CashBriefingConfirmation { paymentMethod: string; reference?: string; note?: string; expectedLedgerRevision: number }
function confirmationRequest(input: CashBriefingConfirmation, proof: File | null): RequestInit {
  let init = post(input)
  if (proof) {
    const body = new FormData()
    body.append('transfer', new Blob([JSON.stringify(input)], { type: 'application/json' }))
    body.append('proof', proof)
    init = { method: 'POST', body }
  }
  return init
}
export const confirmCashBriefingTransfer = (id: string, input: CashBriefingConfirmation, proof: File | null, token: string) => request<CashBriefingBatch>(`/transfers/${encodeURIComponent(id)}/confirm`, token, confirmationRequest(input, proof))
export const confirmCashBriefingCourierTransfer = (id: string, input: CashBriefingConfirmation, proof: File | null, token: string) => request<CashBriefingBatch>(`/courier-transfers/${encodeURIComponent(id)}/confirm`, token, confirmationRequest(input, proof))
