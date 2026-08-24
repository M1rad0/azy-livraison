import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  downloadClientPaymentProof,
  downloadClientReport,
  getClientFinance,
  getClientReport,
  listClientReports,
  type ClientDailyReport,
  type ClientReportSummary,
} from '../api/client'
import type { ClientFinance } from '../api/admin'
import { ClientReportsPanel } from './ClientReportsPanel'

vi.mock('../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/client')>()
  return {
    ...actual,
    downloadClientPaymentProof: vi.fn(),
    downloadClientReport: vi.fn(),
    getClientFinance: vi.fn(),
    getClientReport: vi.fn(),
    listClientReports: vi.fn(),
  }
})

const totals = {
  packageCount: 1,
  deliveredCount: 1,
  cancelledCount: 0,
  postponedCount: 0,
  returnRequiredCount: 0,
  returnedToSenderCount: 0,
  amountCollectedAriary: 15_000,
  deliveryFeesAriary: 5_000,
  amountToRemitAriary: 10_000,
}

const summary = {
  reportId: 'report-1',
  serviceDate: '2026-08-24',
  revision: 2,
  closedAt: '2026-08-24T18:00:00Z',
  totals,
} satisfies ClientReportSummary

const report = {
  reportId: summary.reportId,
  serviceDate: summary.serviceDate,
  generatedAt: summary.closedAt,
  closure: { closedAt: summary.closedAt, version: summary.revision },
  totals,
  packages: [],
} satisfies ClientDailyReport

const finance = {
  clientAccountId: 'client-1',
  totalDueAriary: 10_000,
  totalConfirmedSignedPaymentsAriary: 6_000,
  netBalanceAriary: 4_000,
  amountOwedToClientAriary: 4_000,
  amountOwedByClientAriary: 0,
  settlements: [{
    id: 'settlement-1',
    reportId: summary.reportId,
    serviceDate: summary.serviceDate,
    closureRevision: summary.revision,
    clientDisplayName: 'Boutique Soa',
    amountDueAriary: 10_000,
    confirmedSignedPaymentsAriary: 6_000,
    remainingAriary: 4_000,
    status: 'PARTIALLY_PAID',
    createdAt: summary.closedAt,
  }],
  payments: [{
    id: 'payment-1',
    settlementId: 'settlement-1',
    serviceDate: summary.serviceDate,
    direction: 'AZY_TO_CLIENT',
    amountAriary: 6_000,
    signedAmountAriary: 6_000,
    paymentMethod: 'MVola',
    transactionReference: 'TX-123',
    proof: { available: true, originalFilename: 'preuve.pdf' },
    status: 'CONFIRMED',
    confirmedAt: '2026-08-24T18:30:00Z',
    confirmedByAccountId: 'admin-uuid-secret',
    version: 0,
  }, {
    id: 'payment-2',
    settlementId: 'settlement-1',
    serviceDate: summary.serviceDate,
    direction: 'CLIENT_TO_AZY',
    amountAriary: 1_000,
    signedAmountAriary: -1_000,
    paymentMethod: 'Espèces',
    proof: { available: false },
    status: 'VOIDED',
    confirmedAt: '2026-08-24T19:00:00Z',
    confirmedByAccountId: 'admin-uuid-secret',
    voidedAt: '2026-08-24T19:05:00Z',
    voidedByAccountId: 'admin-uuid-secret',
    voidReason: 'Correction',
    version: 1,
  }],
} satisfies ClientFinance

describe('ClientReportsPanel', () => {
  beforeEach(() => {
    vi.mocked(listClientReports).mockResolvedValue([summary])
    vi.mocked(getClientReport).mockResolvedValue(report)
    vi.mocked(getClientFinance).mockResolvedValue(finance)
    vi.mocked(downloadClientReport).mockResolvedValue({ blob: new Blob(), filename: 'rapport.pdf' })
    vi.mocked(downloadClientPaymentProof).mockResolvedValue({ blob: new Blob(), filename: 'preuve.pdf' })
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:preuve')
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined)
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)
  })

  afterEach(() => {
    cleanup()
    vi.restoreAllMocks()
  })

  it('affiche le règlement, les deux sens et télécharge le justificatif sans exposer les UUID', async () => {
    render(<ClientReportsPanel accessToken="client-token" />)

    expect(await screen.findByRole(
      'heading',
      { name: 'Versements liés à ce rapport' },
      { timeout: 5_000 },
    )).toBeInTheDocument()
    expect(screen.getAllByText('Partiellement réglé')).toHaveLength(2)
    expect(screen.getByText('AZY → client')).toBeInTheDocument()
    expect(screen.getByText('Client → AZY')).toBeInTheDocument()
    expect(screen.getAllByText(/confirmé par l’administration/)).toHaveLength(2)
    expect(screen.getByText(/annulé par l’administration/)).toBeInTheDocument()
    expect(screen.queryByText(/admin-uuid-secret/)).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: /Télécharger le justificatif/ }))
    await waitFor(() => expect(downloadClientPaymentProof).toHaveBeenCalledWith('payment-1', 'client-token'))
  })
})
