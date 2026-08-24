import { useEffect, useMemo, useState } from 'react'
import {
  CalendarCheck2,
  BanknoteArrowDown,
  BanknoteArrowUp,
  Download,
  FileSpreadsheet,
  FileText,
  LoaderCircle,
  PackageCheck,
  RefreshCw,
  RotateCcw,
  WalletCards,
  XCircle,
} from 'lucide-react'
import {
  downloadClientReport,
  downloadClientPaymentProof,
  getClientFinance,
  getClientReport,
  listClientReports,
  type ClientDailyReport,
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
    return finance.settlements.find((item) => (
      item.reportId === selectedSummary.reportId
      || (item.serviceDate === selectedSummary.serviceDate
        && item.closureRevision === selectedSummary.revision)
    ))
  }, [finance, selectedSummary])
  const selectedPayments = useMemo(() => finance?.payments.filter((item) => (
    item.settlementId === selectedSettlement?.id
  )) ?? [], [finance, selectedSettlement])

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
              const settlement = finance?.settlements.find((candidate) => (
                candidate.reportId === item.reportId
                || (candidate.serviceDate === item.serviceDate && candidate.closureRevision === item.revision)
              ))
              return (
                <button className={item.serviceDate === selectedDate ? 'is-active' : undefined} type="button" key={item.reportId} onClick={() => setSelectedDate(item.serviceDate)}>
                  <span><CalendarCheck2 aria-hidden="true" /></span>
                  <div><strong>{formatDate(item.serviceDate)}</strong><small>{item.totals.packageCount} colis · version {item.revision}</small></div>
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
                  <div><p className="eyebrow">Rapport du jour</p><h2>{formatDate(report.serviceDate)}</h2><small>Clôturé · version {report.closure.version}</small></div>
                  <div><button className="button button--secondary" type="button" disabled={Boolean(downloading)} onClick={() => void download('pdf')}><FileText aria-hidden="true" />{downloading === 'pdf' ? 'Préparation…' : 'PDF'}</button><button className="button button--secondary" type="button" disabled={Boolean(downloading)} onClick={() => void download('xlsx')}><FileSpreadsheet aria-hidden="true" />{downloading === 'xlsx' ? 'Préparation…' : 'Excel'}</button></div>
                </header>

                <div className="client-report-totals">
                  <article><span><WalletCards aria-hidden="true" /></span><div><small>Total récupéré</small><strong>{formatAriary(report.totals.amountCollectedAriary)}</strong></div></article>
                  <article><span><Download aria-hidden="true" /></span><div><small>Frais de livraison</small><strong>{formatAriary(report.totals.deliveryFeesAriary)}</strong></div></article>
                  <article className="is-primary"><span><PackageCheck aria-hidden="true" /></span><div><small>{report.totals.amountToRemitAriary < 0 ? 'À payer à AZY' : 'À vous reverser'}</small><strong>{formatAriary(Math.abs(report.totals.amountToRemitAriary))}</strong></div></article>
                </div>

                {selectedSettlement ? (
                  <section className="client-report-settlement" aria-labelledby="client-settlement-title">
                    <header><div><p className="eyebrow">Suivi du règlement</p><h3 id="client-settlement-title">Versements liés à ce rapport</h3></div><span className={`is-${selectedSettlement.status.toLowerCase()}`}>{settlementLabel(selectedSettlement)}</span></header>
                    <div className="client-report-settlement__totals">
                      <div><small>Montant du rapport</small><strong>{formatAriary(Math.abs(selectedSettlement.amountDueAriary))}</strong></div>
                      <div><small>Confirmé</small><strong>{formatAriary(Math.abs(selectedSettlement.confirmedSignedPaymentsAriary))}</strong></div>
                      <div><small>{selectedSettlement.remainingAriary < 0 ? 'Crédit / avance' : 'Reste à régler'}</small><strong>{formatAriary(Math.abs(selectedSettlement.remainingAriary))}</strong></div>
                    </div>
                    <div className="client-report-settlement__history">
                      {selectedPayments.map((payment) => (
                        <article className={payment.status === 'VOIDED' ? 'is-voided' : undefined} key={payment.id}>
                          <span>{payment.direction === 'AZY_TO_CLIENT' ? <BanknoteArrowDown aria-hidden="true" /> : <BanknoteArrowUp aria-hidden="true" />}</span>
                          <div><strong>{formatAriary(payment.amountAriary)} · {payment.paymentMethod}</strong><small><b>{paymentDirectionLabel(payment.direction)}</b> · {new Date(payment.confirmedAt).toLocaleString('fr-MG')}{payment.transactionReference ? ` · réf. ${payment.transactionReference}` : ''}{payment.confirmedByAccountId ? ' · confirmé par l’administration' : ''}</small>{payment.status === 'VOIDED' && <em>Versement annulé : {payment.voidReason}{payment.voidedByAccountId ? ' · annulé par l’administration' : ''}</em>}</div>
                          {payment.proof.available && <button className="button button--secondary" type="button" disabled={downloadingProofId === payment.id} aria-label={`Télécharger le justificatif du versement de ${formatAriary(payment.amountAriary)}`} onClick={() => void downloadProof(payment.id)}><Download aria-hidden="true" />{downloadingProofId === payment.id ? 'Téléchargement…' : 'Justificatif'}</button>}
                        </article>
                      ))}
                      {selectedPayments.length === 0 && <p>Aucun versement n’a encore été confirmé pour ce rapport.</p>}
                    </div>
                  </section>
                ) : selectedSummary && finance ? (
                  <section className="client-report-settlement client-report-settlement--unavailable" aria-label="Suivi du règlement indisponible">
                    <WalletCards aria-hidden="true" />
                    <div><strong>Suivi du règlement indisponible</strong><p>Aucun registre financier ne correspond encore à cette version du rapport.</p></div>
                  </section>
                ) : null}

                <div className="client-report-outcomes">
                  <span><PackageCheck aria-hidden="true" />{report.totals.deliveredCount} livré{report.totals.deliveredCount > 1 ? 's' : ''}</span>
                  <span><RotateCcw aria-hidden="true" />{report.totals.postponedCount} reporté{report.totals.postponedCount > 1 ? 's' : ''}</span>
                  <span><XCircle aria-hidden="true" />{report.totals.cancelledCount} annulé{report.totals.cancelledCount > 1 ? 's' : ''}</span>
                </div>

                <div className="client-report-packages">
                  {report.packages.map((item) => (
                    <article key={item.packageId}>
                      <span>{item.sequenceNumber}</span>
                      <div><strong>{item.recipientName}</strong><small>{item.destinationLabel} · {item.recipientContact}</small></div>
                      <div><strong>{OUTCOMES[item.outcome]}</strong><small>{item.actualCourierName ? `Livreur : ${item.actualCourierName}` : item.pickupLabel}</small></div>
                      <div><strong>{formatAriary(item.actualAmountCollectedAriary)}</strong><small>Frais {formatAriary(item.appliedDeliveryFeeAriary)} · net {formatAriary(item.amountToRemitAriary)}</small></div>
                    </article>
                  ))}
                </div>
              </>
            )}
          </section>
        </div>
      )}
      {selectedSummary && <span className="sr-only">Rapport sélectionné du {selectedSummary.serviceDate}</span>}
    </section>
  )
}
