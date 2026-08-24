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
  packageCount: 2,
  deliveredCount: 1,
  cancelledCount: 0,
  postponedCount: 0,
  returnRequiredCount: 1,
  returnedToSenderCount: 1,
  amountCollectedAriary: 15_000,
  deliveryFeesAriary: 5_000,
  amountToRemitAriary: 10_000,
}

const summary = {
  reportId: 'report-1',
  serviceDate: '2026-08-24',
  totals,
} satisfies ClientReportSummary

const settlementCreatedAt = '2026-08-24T18:00:00Z'

const report = {
  reportId: summary.reportId,
  serviceDate: summary.serviceDate,
  totals,
  packages: [{
    packageId: 'package-1',
    recipientName: 'Miora',
    recipientContact: '034 00 000 00',
    pickupLabel: 'Boutique Analakely',
    destinationLabel: 'Ankorondrano',
    outcome: 'DELIVERED',
    actualAmountCollectedAriary: 15_000,
    appliedDeliveryFeeAriary: 5_000,
    amountToRemitAriary: 10_000,
    mobileMoneyPaid: true,
    returnedToSender: false,
  }, {
    packageId: 'package-2',
    recipientName: 'Fara',
    recipientContact: '032 00 000 00',
    pickupLabel: 'Boutique Analakely',
    destinationLabel: 'Ivandry',
    outcome: 'RETURN_TO_SENDER',
    actualAmountCollectedAriary: 0,
    appliedDeliveryFeeAriary: 0,
    amountToRemitAriary: 0,
    mobileMoneyPaid: false,
    returnedToSender: true,
  }],
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
    closureRevision: 2,
    clientDisplayName: 'Boutique Soa',
    amountDueAriary: 10_000,
    confirmedSignedPaymentsAriary: 6_000,
    remainingAriary: 4_000,
    status: 'PARTIALLY_PAID',
    createdAt: settlementCreatedAt,
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

  it('présente les colis en tableau sans répéter le retrait ni exposer les détails opérationnels', async () => {
    render(<ClientReportsPanel accessToken="client-token" />)

    expect(await screen.findByRole('table')).toBeInTheDocument()
    expect(screen.getAllByRole('columnheader').map((cell) => cell.textContent)).toEqual([
      'Destinataire',
      'Destination / contact',
      'État',
      'Mobile Money',
      'Récupéré',
      'Frais',
      'Net',
    ])
    expect(screen.getByText('Miora')).toBeInTheDocument()
    expect(screen.getByText('Ankorondrano')).toBeInTheDocument()
    expect(screen.getByText('Retourné à l’expéditeur')).toBeInTheDocument()
    expect(screen.getByText('Oui')).toBeInTheDocument()
    expect(screen.getByText('Non')).toBeInTheDocument()
    expect(screen.getAllByText('Boutique Analakely')).toHaveLength(1)
    expect(screen.queryByText(/Livreur/)).not.toBeInTheDocument()
    expect(screen.queryByText(/version 2/i)).not.toBeInTheDocument()
    expect(screen.queryByText(/18:30|19:00/)).not.toBeInTheDocument()

    const table = screen.getByRole('table')
    const settlement = screen.getByRole('heading', { name: 'Versements liés à ce rapport' }).closest('section')
    const summarySection = screen.getByRole('heading', { name: 'Synthèse financière' }).closest('section')
    expect(table.compareDocumentPosition(settlement as Node) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect((settlement as Node).compareDocumentPosition(summarySection as Node) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })
})
