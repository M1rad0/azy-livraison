import { useEffect, useMemo, useState } from 'react'
import {
  CalendarCheck2,
  BanknoteArrowDown,
  BanknoteArrowUp,
  Download,
  FileSpreadsheet,
  FileText,
  LoaderCircle,
  MapPin,
  PackageCheck,
  RefreshCw,
  WalletCards,
} from 'lucide-react'
import {
  downloadClientReport,
  downloadClientPaymentProof,
  getClientFinance,
  getClientReport,
  listClientReports,
  type ClientDailyReport,
  type ClientReportPackage,
  type ClientReportSummary,
} from '../api/client'
import type { ClientFinance, ClientPaymentDirection, ClientSettlement } from '../api/admin'
import './ClientReportsPanel.css'

function formatDate(date: string) {
  return new Date(`${date}T12:00:00`).toLocaleDateString('fr-MG', {
    weekday: 'long', day: 'numeric', month: 'long', year: 'numeric',
  })
}

function formatAriary(value: number) {
  return `${value.toLocaleString('fr-MG')} Ar`
}

function formatPaymentDate(value: string) {
  return new Intl.DateTimeFormat('fr-MG', {
    day: 'numeric', month: 'long', year: 'numeric', timeZone: 'Indian/Antananarivo',
  }).format(new Date(value))
}

function saveFile(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = filename
  document.body.append(anchor)
  anchor.click()
  anchor.remove()
  URL.revokeObjectURL(url)
}

const OUTCOMES = {
  DELIVERED: 'Livré',
  CANCELLED: 'Annulé',
  POSTPONED: 'Reporté',
  RETURN_TO_SENDER: 'À retourner',
} as const

function outcomeLabel(item: ClientReportPackage) {
  if (item.outcome === 'RETURN_TO_SENDER' && item.returnedToSender) return 'Retourné à l’expéditeur'
  return OUTCOMES[item.outcome]
}

function settlementLabel(settlement: ClientSettlement): string {
  if (settlement.status === 'PAID') return settlement.remainingAriary === 0 ? 'Soldé' : 'Crédit enregistré'
  return settlement.status === 'PARTIALLY_PAID' ? 'Partiellement réglé' : 'Non réglé'
}

function paymentDirectionLabel(direction: ClientPaymentDirection): string {
  return direction === 'AZY_TO_CLIENT' ? 'AZY → client' : 'Client → AZY'
}

export function ClientReportsPanel({ accessToken }: { accessToken: string }) {
  const [reports, setReports] = useState<ClientReportSummary[]>([])
  const [selectedDate, setSelectedDate] = useState<string>()
  const [report, setReport] = useState<ClientDailyReport>()
  const [finance, setFinance] = useState<ClientFinance>()
  const [loading, setLoading] = useState(true)
  const [loadingDetail, setLoadingDetail] = useState(false)
  const [downloading, setDownloading] = useState<'pdf' | 'xlsx'>()
  const [downloadingProofId, setDownloadingProofId] = useState<string>()
  const [error, setError] = useState<string>()

  async function loadList() {
    setLoading(true)
    setError(undefined)
    try {
      const [loaded, loadedFinance] = await Promise.all([
        listClientReports(accessToken),
        getClientFinance(accessToken),
      ])
      setReports(loaded)
      setFinance(loadedFinance)
      if (!selectedDate && loaded.length > 0) setSelectedDate(loaded[0].serviceDate)
    } catch (loadError) {
      setError(loadError instanceof Error ? loadError.message : 'Les rapports n’ont pas pu être chargés.')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void loadList()
  // Only a new authenticated session changes the remote report collection.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [accessToken])

  useEffect(() => {
    if (!selectedDate) {
      setReport(undefined)
      return
    }
    let cancelled = false
    setLoadingDetail(true)
    setError(undefined)
    void getClientReport(selectedDate, accessToken)
      .then((loaded) => { if (!cancelled) setReport(loaded) })
      .catch((loadError) => {
        if (!cancelled) setError(loadError instanceof Error ? loadError.message : 'Le rapport n’a pas pu être ouvert.')
      })
      .finally(() => { if (!cancelled) setLoadingDetail(false) })
    return () => { cancelled = true }
  }, [accessToken, selectedDate])

  const selectedSummary = useMemo(
    () => reports.find((item) => item.serviceDate === selectedDate),
    [reports, selectedDate],
  )
  const selectedSettlement = useMemo(() => {
    if (!finance || !selectedSummary) return undefined
    return finance.settlements.find((item) => item.reportId === selectedSummary.reportId)
  }, [finance, selectedSummary])
  const selectedPayments = useMemo(() => finance?.payments.filter((item) => (
    item.settlementId === selectedSettlement?.id
  )) ?? [], [finance, selectedSettlement])
  const pickupLabels = useMemo(() => {
    const uniqueLabels = new Map<string, string>()
    report?.packages.forEach((item) => {
      const label = item.pickupLabel.trim()
      if (label) uniqueLabels.set(label.toLocaleLowerCase('fr'), label)
    })
    return [...uniqueLabels.values()]
  }, [report])

  async function download(format: 'pdf' | 'xlsx') {
    if (!selectedDate) return
    setDownloading(format)
    setError(undefined)
    try {
      const file = await downloadClientReport(selectedDate, format, accessToken)
      saveFile(file.blob, file.filename)
    } catch (downloadError) {
      setError(downloadError instanceof Error ? downloadError.message : 'Le téléchargement a échoué.')
    } finally {
      setDownloading(undefined)
    }
  }

  async function downloadProof(paymentId: string) {
    setDownloadingProofId(paymentId)
    setError(undefined)
    try {
      const file = await downloadClientPaymentProof(paymentId, accessToken)
      saveFile(file.blob, file.filename)
    } catch (downloadError) {
      setError(downloadError instanceof Error ? downloadError.message : 'Le téléchargement a échoué.')
    } finally {
      setDownloadingProofId(undefined)
    }
  }

  return (
    <section className="client-reports-page" aria-labelledby="client-reports-title">
      <header className="client-page-heading client-page-heading--compact">
        <div><p className="eyebrow">Journées clôturées</p><h1 id="client-reports-title">Mes rapports</h1><p>Retrouvez les colis finalisés, les frais facturés, votre solde et les versements confirmés pour chaque journée terminée.</p></div>
        <button className="button button--secondary" type="button" disabled={loading} onClick={() => void loadList()}><RefreshCw aria-hidden="true" />Actualiser</button>
      </header>

      {error && <p className="client-alert client-alert--error" role="alert">{error}</p>}

      {loading ? (
        <div className="client-report-state"><LoaderCircle className="spin" aria-hidden="true" /><strong>Chargement des rapports…</strong></div>
      ) : reports.length === 0 ? (
        <div className="client-report-state"><CalendarCheck2 aria-hidden="true" /><strong>Aucun rapport disponible</strong><span>Un rapport apparaîtra ici dès qu’une journée comprenant vos colis aura été clôturée.</span></div>
      ) : (
        <div className="client-reports-layout">
          <aside className="client-report-list" aria-label="Liste des rapports">
            {reports.map((item) => {
              const settlement = finance?.settlements.find((candidate) => candidate.reportId === item.reportId)
              return (
                <button className={item.serviceDate === selectedDate ? 'is-active' : undefined} type="button" key={item.reportId} onClick={() => setSelectedDate(item.serviceDate)}>
                  <span><CalendarCheck2 aria-hidden="true" /></span>
                  <div><strong>{formatDate(item.serviceDate)}</strong><small>{item.totals.packageCount} colis</small></div>
                  <b>{item.totals.amountToRemitAriary < 0 ? 'À payer à AZY · ' : ''}{formatAriary(Math.abs(item.totals.amountToRemitAriary))}</b>
                  {settlement && <em className={`is-${settlement.status.toLowerCase()}`}>{settlementLabel(settlement)}</em>}
                </button>
              )
            })}
          </aside>

          <section className="client-report-detail" aria-live="polite">
            {loadingDetail || !report ? (
              <div className="client-report-state"><LoaderCircle className="spin" aria-hidden="true" /><strong>Ouverture du rapport…</strong></div>
            ) : (
              <>
                <header>
                  <h2>{formatDate(report.serviceDate)}</h2>
                  <div><button className="button button--secondary" type="button" disabled={Boolean(downloading)} onClick={() => void download('pdf')}><FileText aria-hidden="true" />{downloading === 'pdf' ? 'Préparation…' : 'PDF'}</button><button className="button button--secondary" type="button" disabled={Boolean(downloading)} onClick={() => void download('xlsx')}><FileSpreadsheet aria-hidden="true" />{downloading === 'xlsx' ? 'Préparation…' : 'Excel'}</button></div>
                </header>

                {pickupLabels.length > 0 && (
                  <section className="client-report-pickups" aria-labelledby="client-report-pickups-title">
                    <span><MapPin aria-hidden="true" /></span>
                    <div>
                      <h3 id="client-report-pickups-title">Point{pickupLabels.length > 1 ? 's' : ''} de récupération</h3>
                      <p>{pickupLabels.join(' · ')}</p>
                    </div>
                  </section>
                )}

                <div className="client-report-packages">
                  <table>
                    <caption className="sr-only">Détail des colis du rapport</caption>
                    <thead>
                      <tr>
                        <th scope="col">Destinataire</th>
                        <th scope="col">Destination / contact</th>
                        <th scope="col">État</th>
                        <th scope="col">Mobile Money</th>
                        <th scope="col" className="is-money">Récupéré</th>
                        <th scope="col" className="is-money">Frais</th>
                        <th scope="col" className="is-money">Net</th>
                      </tr>
                    </thead>
                    <tbody>
                      {report.packages.map((item) => (
                        <tr key={item.packageId}>
                          <td data-label="Destinataire"><strong>{item.recipientName}</strong></td>
                          <td data-label="Destination / contact"><strong>{item.destinationLabel}</strong><small>{item.recipientContact}</small></td>
                          <td data-label="État"><span className={`client-report-package-status is-${item.outcome.toLowerCase()}`}>{outcomeLabel(item)}</span></td>
                          <td data-label="Mobile Money"><span className={`client-report-payment is-${item.mobileMoneyPaid ? 'yes' : 'no'}`}>{item.mobileMoneyPaid ? 'Oui' : 'Non'}</span></td>
                          <td data-label="Récupéré" className="is-money"><strong>{formatAriary(item.actualAmountCollectedAriary)}</strong></td>
                          <td data-label="Frais" className="is-money"><strong>{formatAriary(item.appliedDeliveryFeeAriary)}</strong></td>
                          <td data-label="Net" className="is-money is-net"><strong>{formatAriary(item.amountToRemitAriary)}</strong></td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                  {report.packages.length === 0 && <p className="client-report-packages__empty">Aucun colis dans ce rapport.</p>}
                </div>

                {selectedSettlement ? (
                  <section className="client-report-settlement" aria-labelledby="client-settlement-title">
                    <header><div><p className="eyebrow">Suivi du règlement</p><h3 id="client-settlement-title">Versements liés à ce rapport</h3></div><span className={`is-${selectedSettlement.status.toLowerCase()}`}>{settlementLabel(selectedSettlement)}</span></header>
                    <div className="client-report-settlement__totals">
                      <div><small>Confirmé</small><strong>{formatAriary(Math.abs(selectedSettlement.confirmedSignedPaymentsAriary))}</strong></div>
                      <div><small>{selectedSettlement.remainingAriary < 0 ? 'Crédit / avance' : 'Reste à régler'}</small><strong>{formatAriary(Math.abs(selectedSettlement.remainingAriary))}</strong></div>
                    </div>
                    <div className="client-report-settlement__history">
                      {selectedPayments.map((payment) => (
                        <article className={payment.status === 'VOIDED' ? 'is-voided' : undefined} key={payment.id}>
                          <span>{payment.direction === 'AZY_TO_CLIENT' ? <BanknoteArrowDown aria-hidden="true" /> : <BanknoteArrowUp aria-hidden="true" />}</span>
                          <div><strong>{formatAriary(payment.amountAriary)} · {payment.paymentMethod}</strong><small><b>{paymentDirectionLabel(payment.direction)}</b> · {formatPaymentDate(payment.confirmedAt)}{payment.transactionReference ? ` · réf. ${payment.transactionReference}` : ''}{payment.confirmedByAccountId ? ' · confirmé par l’administration' : ''}</small>{payment.status === 'VOIDED' && <em>Versement annulé : {payment.voidReason}{payment.voidedByAccountId ? ' · annulé par l’administration' : ''}</em>}</div>
                          {payment.proof.available && <button className="button button--secondary" type="button" disabled={downloadingProofId === payment.id} aria-label={`Télécharger le justificatif du versement de ${formatAriary(payment.amountAriary)}`} onClick={() => void downloadProof(payment.id)}><Download aria-hidden="true" />{downloadingProofId === payment.id ? 'Téléchargement…' : 'Justificatif'}</button>}
                        </article>
                      ))}
                      {selectedPayments.length === 0 && <p>Aucun versement n’a encore été confirmé pour ce rapport.</p>}
                    </div>
                  </section>
                ) : selectedSummary && finance ? (
                  <section className="client-report-settlement client-report-settlement--unavailable" aria-label="Suivi du règlement indisponible">
                    <WalletCards aria-hidden="true" />
                    <div><strong>Suivi du règlement indisponible</strong><p>Aucun suivi financier n’est encore disponible pour ce rapport.</p></div>
                  </section>
                ) : null}

                <section className="client-report-summary" aria-labelledby="client-report-summary-title">
                  <header><p className="eyebrow">Synthèse</p><h3 id="client-report-summary-title">Synthèse financière</h3></header>
                  <div className="client-report-totals">
                    <article><span><WalletCards aria-hidden="true" /></span><div><small>Total récupéré</small><strong>{formatAriary(report.totals.amountCollectedAriary)}</strong></div></article>
                    <article><span><Download aria-hidden="true" /></span><div><small>Frais de livraison</small><strong>{formatAriary(report.totals.deliveryFeesAriary)}</strong></div></article>
                    <article className="is-primary"><span><PackageCheck aria-hidden="true" /></span><div><small>{report.totals.amountToRemitAriary < 0 ? 'À payer à AZY' : 'À vous reverser'}</small><strong>{formatAriary(Math.abs(report.totals.amountToRemitAriary))}</strong></div></article>
                  </div>
                </section>
              </>
            )}
          </section>
        </div>
      )}
      {selectedSummary && <span className="sr-only">Rapport sélectionné du {selectedSummary.serviceDate}</span>}
    </section>
  )
}
