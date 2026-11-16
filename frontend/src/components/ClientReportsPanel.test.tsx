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

const olderSummary = {
  reportId: 'report-2',
  serviceDate: '2026-08-20',
  totals: {
    ...totals,
    packageCount: 1,
    deliveredCount: 1,
    returnRequiredCount: 0,
    returnedToSenderCount: 0,
    amountCollectedAriary: 8_000,
    deliveryFeesAriary: 3_000,
    amountToRemitAriary: 5_000,
  },
} satisfies ClientReportSummary

const settlementCreatedAt = '2026-08-24T18:00:00Z'

const report = {
  reportId: summary.reportId,
  serviceDate: summary.serviceDate,
  clientDisplayName: 'Boutique Soa',
  clientPhone: '033 00 000 00',
  totals,
  packages: [{
    packageId: 'package-1',
    recipientName: 'Miora',
    recipientContact: '034 00 000 00',
    pickupLabel: 'Boutique Analakely',
    destinationLabel: 'Ankorondrano',
    outcome: 'DELIVERED',
    comment: 'Montant confirmé avec la destinataire.',
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
    comment: 'Commande refusée à l’arrivée.',
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
    vi.clearAllMocks()
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

    fireEvent.click(await screen.findByRole('button', { name: /Ouvrir le rapport du .*24 août 2026/i }))

    expect(await screen.findByRole(
      'heading',
      { name: 'Versements liés à ce rapport' },
      { timeout: 5_000 },
    )).toBeInTheDocument()
    expect(screen.getByText('Partiellement réglé')).toBeInTheDocument()
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

    fireEvent.click(await screen.findByRole('button', { name: /Ouvrir le rapport du .*24 août 2026/i }))

    expect(await screen.findByRole('table', { name: 'Détail des colis du rapport' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Boutique Soa' })).toBeInTheDocument()
    const reportDate = screen.getByText(/24 août 2026/i, { selector: 'time' })
    expect(reportDate.tagName).toBe('TIME')
    expect(reportDate).toHaveAttribute('datetime', '2026-08-24')
    expect(screen.getAllByRole('columnheader').map((cell) => cell.textContent)).toEqual([
      'Destinataire',
      'Destination / contact',
      'État',
      'Commentaire',
      'Récupéré',
      'Frais',
      'Net',
    ])
    expect(screen.getByText('Miora')).toBeInTheDocument()
    expect(screen.getByText('Ankorondrano')).toBeInTheDocument()
    expect(screen.getByText('Retourné à l’expéditeur')).toBeInTheDocument()
    expect(screen.getByText('Montant confirmé avec la destinataire.')).toBeInTheDocument()
    expect(screen.getByText('Commande refusée à l’arrivée.')).toBeInTheDocument()
    expect(screen.queryByText('Mobile Money')).not.toBeInTheDocument()
    expect(screen.getByText('033 00 000 00')).toBeInTheDocument()
    expect(screen.getAllByText('Boutique Analakely')).toHaveLength(1)
    expect(screen.queryByText(/Livreur/)).not.toBeInTheDocument()
    expect(screen.queryByText(/version 2/i)).not.toBeInTheDocument()
    expect(screen.queryByText(/18:30|19:00/)).not.toBeInTheDocument()

    const table = screen.getByRole('table', { name: 'Détail des colis du rapport' })
    const settlement = screen.getByRole('heading', { name: 'Versements liés à ce rapport' }).closest('section')
    const summarySection = screen.getByRole('heading', { name: 'Synthèse' }).closest('section')
    expect((summarySection as Node).compareDocumentPosition(table) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(table.compareDocumentPosition(settlement as Node) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it('exports the same report as a photo without losing PDF and Excel', async () => {
    render(<ClientReportsPanel accessToken="client-token" />)
    fireEvent.click(await screen.findByRole('button', { name: /Ouvrir le rapport du .*24 août 2026/i }))

    const photo = await screen.findByRole('button', { name: 'Photo' })
    expect(screen.getByRole('button', { name: 'PDF' })).toBeEnabled()
    expect(screen.getByRole('button', { name: 'Excel' })).toBeEnabled()
    fireEvent.click(photo)
    await waitFor(() => expect(downloadClientReport).toHaveBeenCalledWith(
      '2026-08-24', 'image', 'client-token',
    ))
  })

  it('commence par une liste filtrable puis revient à cette liste sans perdre les filtres', async () => {
    vi.mocked(listClientReports).mockResolvedValue([summary, olderSummary])
    vi.mocked(getClientFinance).mockResolvedValue({
      ...finance,
      settlements: [
        ...finance.settlements,
        {
          ...finance.settlements[0],
          id: 'settlement-2',
          reportId: olderSummary.reportId,
          serviceDate: olderSummary.serviceDate,
          status: 'PAID',
          confirmedSignedPaymentsAriary: 5_000,
          remainingAriary: 0,
        },
      ],
    })

    render(<ClientReportsPanel accessToken="client-token" />)

    expect(await screen.findByRole('heading', { name: 'Rapports disponibles' })).toBeInTheDocument()
    expect(screen.getByRole('table', { name: 'Liste de vos rapports clients finalisés' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Boutique Soa' })).not.toBeInTheDocument()
    expect(getClientReport).not.toHaveBeenCalled()

    fireEvent.change(screen.getByLabelText('Du'), { target: { value: '2026-08-24' } })
    fireEvent.change(screen.getByLabelText('Règlement'), { target: { value: 'PARTIALLY_PAID' } })

    expect(screen.getByText('1 rapport affiché sur 2')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Ouvrir le rapport du .*20 août 2026/i })).not.toBeInTheDocument()

    const reportRow = screen.getByRole('button', { name: /Ouvrir le rapport du .*24 août 2026/i }).closest('tr')
    expect(reportRow).not.toBeNull()
    fireEvent.click(reportRow as HTMLTableRowElement)
    expect(screen.getByRole('button', { name: 'Retour aux rapports' })).toHaveFocus()
    expect(await screen.findByRole('heading', { name: 'Boutique Soa' })).toBeInTheDocument()
    expect(getClientReport).toHaveBeenCalledTimes(1)

    fireEvent.click(screen.getByRole('button', { name: 'Retour aux rapports' }))

    expect(screen.getByRole('heading', { name: 'Rapports disponibles' })).toHaveFocus()
    expect(screen.getByLabelText('Du')).toHaveValue('2026-08-24')
    expect(screen.getByLabelText('Règlement')).toHaveValue('PARTIALLY_PAID')
    expect(screen.getByText('1 rapport affiché sur 2')).toBeInTheDocument()
  })

  it('laisse les rapports accessibles lorsque le suivi financier échoue', async () => {
    vi.mocked(getClientFinance).mockRejectedValueOnce(new Error('Service financier indisponible'))

    render(<ClientReportsPanel accessToken="client-token" />)

    expect(await screen.findByRole('heading', { name: 'Rapports disponibles' })).toBeInTheDocument()
    expect(screen.getByRole('table', { name: 'Liste de vos rapports clients finalisés' })).toBeInTheDocument()
    expect(screen.getByText(/suivi des règlements est momentanément indisponible/i)).toBeInTheDocument()
    expect(screen.getByLabelText('Règlement')).toBeDisabled()
    expect(getClientReport).not.toHaveBeenCalled()
  })

  it('affiche la liste sans attendre la fin du chargement financier', async () => {
    let resolveFinance!: (loaded: ClientFinance) => void
    vi.mocked(getClientFinance).mockReturnValue(new Promise((resolve) => { resolveFinance = resolve }))

    render(<ClientReportsPanel accessToken="client-token" />)

    expect(await screen.findByRole('heading', { name: 'Rapports disponibles' })).toBeInTheDocument()
    expect(screen.getByRole('table', { name: 'Liste de vos rapports clients finalisés' })).toBeInTheDocument()
    expect(screen.getByLabelText('Règlement')).toBeDisabled()
    expect(screen.getByRole('option', { name: 'Chargement du suivi…' })).toBeInTheDocument()
    expect(screen.getByText('Chargement…', { selector: '.client-report-list-table__status' })).toBeInTheDocument()

    resolveFinance(finance)
    await waitFor(() => expect(screen.getByLabelText('Règlement')).not.toBeDisabled())
  })

  it('distingue une erreur de chargement d’une liste réellement vide', async () => {
    vi.mocked(listClientReports).mockRejectedValueOnce(new Error('Rapports indisponibles'))

    render(<ClientReportsPanel accessToken="client-token" />)

    expect(await screen.findByText('Impossible de charger vos rapports')).toBeInTheDocument()
    expect(screen.queryByText('Aucun rapport disponible')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Réessayer' })).toBeInTheDocument()
  })
})
