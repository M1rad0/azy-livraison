import { useEffect, useMemo, useRef, useState } from 'react'
import {
  ArrowLeft,
  CalendarCheck2,
  BanknoteArrowDown,
  BanknoteArrowUp,
  Download,
  Eye,
  FileSpreadsheet,
  FileText,
  ListFilter,
  LoaderCircle,
  MapPin,
  PackageCheck,
  RefreshCw,
  RotateCcw,
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

type SettlementFilter = 'ALL' | ClientSettlement['status']

function settlementForReport(finance: ClientFinance | undefined, reportId: string) {
  return finance?.settlements.find((item) => item.reportId === reportId)
}

export function ClientReportsPanel({ accessToken }: { accessToken: string }) {
  const [reports, setReports] = useState<ClientReportSummary[]>([])
  const [selectedDate, setSelectedDate] = useState<string>()
  const [report, setReport] = useState<ClientDailyReport>()
  const [finance, setFinance] = useState<ClientFinance>()
  const [fromDate, setFromDate] = useState('')
  const [toDate, setToDate] = useState('')
  const [settlementFilter, setSettlementFilter] = useState<SettlementFilter>('ALL')
  const [loading, setLoading] = useState(true)
  const [loadingDetail, setLoadingDetail] = useState(false)
  const [downloading, setDownloading] = useState<'pdf' | 'xlsx'>()
  const [downloadingProofId, setDownloadingProofId] = useState<string>()
  const [error, setError] = useState<string>()
  const [financeWarning, setFinanceWarning] = useState<string>()
  const [financeLoading, setFinanceLoading] = useState(false)
  const [listLoadFailed, setListLoadFailed] = useState(false)
  const listTitleRef = useRef<HTMLHeadingElement>(null)
  const backButtonRef = useRef<HTMLButtonElement>(null)
  const previousSelectedDateRef = useRef<string | undefined>(undefined)
  const loadRequestIdRef = useRef(0)

  async function loadList() {
    const requestId = ++loadRequestIdRef.current
    setLoading(true)
    setError(undefined)
    setFinanceWarning(undefined)
    setFinanceLoading(true)
    setListLoadFailed(false)
    void getClientFinance(accessToken)
      .then((loadedFinance) => {
        if (requestId !== loadRequestIdRef.current) return
        setFinance(loadedFinance)
        setFinanceWarning(undefined)
      })
      .catch(() => {
        if (requestId !== loadRequestIdRef.current) return
        setFinance(undefined)
        setSettlementFilter('ALL')
        setFinanceWarning('Les rapports restent accessibles, mais le suivi des règlements est momentanément indisponible.')
      })
      .finally(() => {
        if (requestId === loadRequestIdRef.current) setFinanceLoading(false)
      })
    try {
      const loadedReports = await listClientReports(accessToken)
      if (requestId !== loadRequestIdRef.current) return
      setReports(loadedReports)
    } catch (loadError) {
      if (requestId !== loadRequestIdRef.current) return
      setReports([])
      setListLoadFailed(true)
      setError(loadError instanceof Error ? loadError.message : 'Les rapports n’ont pas pu être chargés.')
    } finally {
      if (requestId === loadRequestIdRef.current) setLoading(false)
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
      setLoadingDetail(false)
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

  useEffect(() => {
    const previousSelectedDate = previousSelectedDateRef.current
    if (selectedDate) backButtonRef.current?.focus()
    else if (previousSelectedDate) listTitleRef.current?.focus()
    previousSelectedDateRef.current = selectedDate
  }, [selectedDate])

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
  const filteredReports = useMemo(() => reports.filter((item) => {
    if (fromDate && item.serviceDate < fromDate) return false
    if (toDate && item.serviceDate > toDate) return false
    if (settlementFilter === 'ALL') return true
    return settlementForReport(finance, item.reportId)?.status === settlementFilter
  }), [finance, fromDate, reports, settlementFilter, toDate])
  const hasActiveFilters = Boolean(fromDate || toDate || settlementFilter !== 'ALL')
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

  function openReport(serviceDate: string) {
    setReport(undefined)
    setSelectedDate(serviceDate)
  }

  function closeReport() {
    setSelectedDate(undefined)
    setReport(undefined)
    setError(undefined)
  }

  function resetFilters() {
    setFromDate('')
    setToDate('')
    setSettlementFilter('ALL')
  }

  return (
    <section className="client-reports-page" aria-labelledby="client-reports-title">
      <header className="client-page-heading client-page-heading--compact">
        <div><p className="eyebrow">Journées clôturées</p><h1 id="client-reports-title">Mes rapports</h1><p>Retrouvez les colis finalisés, les frais facturés, votre solde et les versements confirmés pour chaque journée terminée.</p></div>
        <button className="button button--secondary" type="button" disabled={loading} onClick={() => void loadList()}><RefreshCw aria-hidden="true" />Actualiser</button>
      </header>

      {error && <p className="client-alert client-alert--error" role="alert">{error}</p>}
      {financeWarning && <p className="client-report-finance-warning" role="status"><WalletCards aria-hidden="true" />{financeWarning}</p>}

      {loading ? (
        <div className="client-report-state"><LoaderCircle className="spin" aria-hidden="true" /><strong>Chargement des rapports…</strong></div>
      ) : listLoadFailed ? (
        <div className="client-report-state"><FileText aria-hidden="true" /><strong>Impossible de charger vos rapports</strong><span>Réessayez dans quelques instants.</span><button className="button button--secondary" type="button" onClick={() => void loadList()}>Réessayer</button></div>
      ) : reports.length === 0 ? (
        <div className="client-report-state"><CalendarCheck2 aria-hidden="true" /><strong>Aucun rapport disponible</strong><span>Un rapport apparaîtra ici dès qu’une journée comprenant vos colis aura été clôturée.</span></div>
      ) : selectedDate ? (
        <div className="client-report-view">
          <nav className="client-report-view__navigation" aria-label="Navigation du rapport">
            <button ref={backButtonRef} className="button button--secondary" type="button" onClick={closeReport}><ArrowLeft aria-hidden="true" />Retour aux rapports</button>
          </nav>
          <section className="client-report-detail" aria-live="polite" aria-busy={loadingDetail}>
            {loadingDetail ? (
              <div className="client-report-state"><LoaderCircle className="spin" aria-hidden="true" /><strong>Ouverture du rapport…</strong></div>
            ) : !report ? (
              <div className="client-report-state"><FileText aria-hidden="true" /><strong>Ce rapport ne peut pas être affiché</strong><span>Revenez à la liste puis réessayez.</span></div>
            ) : (
              <>
                <header className="client-report-detail__header">
                  <div className="client-report-detail__identity">
                    <p className="eyebrow">Rapport client</p>
                    <h2>{report.clientDisplayName}</h2>
                  </div>
                  <div className="client-report-detail__meta">
                    <time dateTime={report.serviceDate}>{formatDate(report.serviceDate)}</time>
                    <div className="client-report-detail__actions"><button className="button button--secondary" type="button" disabled={Boolean(downloading)} onClick={() => void download('pdf')}><FileText aria-hidden="true" />{downloading === 'pdf' ? 'Préparation…' : 'PDF'}</button><button className="button button--secondary" type="button" disabled={Boolean(downloading)} onClick={() => void download('xlsx')}><FileSpreadsheet aria-hidden="true" />{downloading === 'xlsx' ? 'Préparation…' : 'Excel'}</button></div>
                  </div>
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
                        <th scope="col">Commentaire</th>
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
                          <td data-label="Commentaire" className="client-report-comment">{item.comment?.trim() || '—'}</td>
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
      ) : (
        <section className="client-report-index" aria-labelledby="client-report-list-title">
          <div className="client-report-index__heading">
            <div><p className="eyebrow">Historique</p><h2 ref={listTitleRef} id="client-report-list-title" tabIndex={-1}>Rapports disponibles</h2><p>{filteredReports.length} rapport{filteredReports.length === 1 ? '' : 's'} affiché{filteredReports.length === 1 ? '' : 's'} sur {reports.length}</p></div>
          </div>

          <fieldset className="client-report-filters">
            <legend className="sr-only">Filtres des rapports</legend>
            <span className="client-report-filters__icon"><ListFilter aria-hidden="true" /></span>
            <label><span>Du</span><input type="date" value={fromDate} max={toDate || undefined} onChange={(event) => setFromDate(event.target.value)} /></label>
            <label><span>Au</span><input type="date" value={toDate} min={fromDate || undefined} onChange={(event) => setToDate(event.target.value)} /></label>
            <label><span>Règlement</span><select value={settlementFilter} disabled={financeLoading || !finance} onChange={(event) => setSettlementFilter(event.target.value as SettlementFilter)}><option value="ALL">{financeLoading ? 'Chargement du suivi…' : finance ? 'Tous les états' : 'Suivi indisponible'}</option><option value="UNPAID">Non réglé</option><option value="PARTIALLY_PAID">Partiellement réglé</option><option value="PAID">Soldé ou crédit</option></select></label>
            <button className="button button--secondary" type="button" disabled={!hasActiveFilters} onClick={resetFilters}><RotateCcw aria-hidden="true" />Réinitialiser</button>
          </fieldset>

          {filteredReports.length === 0 ? (
            <div className="client-report-state client-report-state--filtered"><ListFilter aria-hidden="true" /><strong>Aucun rapport ne correspond à ces filtres</strong><button className="button button--secondary" type="button" onClick={resetFilters}>Afficher tous les rapports</button></div>
          ) : (
            <div className="client-report-list-table">
              <table>
                <caption className="sr-only">Liste de vos rapports journaliers clôturés</caption>
                <thead><tr><th scope="col">Date</th><th scope="col">Colis</th><th scope="col" className="is-money">Récupéré</th><th scope="col" className="is-money">Frais</th><th scope="col" className="is-money">Solde du rapport</th><th scope="col">Règlement</th><th scope="col"><span className="sr-only">Actions</span></th></tr></thead>
                <tbody>{filteredReports.map((item) => {
                  const settlement = settlementForReport(finance, item.reportId)
                  return <tr key={item.reportId} onClick={() => openReport(item.serviceDate)}>
                    <td data-label="Date"><button className="client-report-list-table__date" type="button" onClick={(event) => { event.stopPropagation(); openReport(item.serviceDate) }}><CalendarCheck2 aria-hidden="true" /><strong>{formatDate(item.serviceDate)}</strong></button></td>
                    <td data-label="Colis"><strong>{item.totals.packageCount}</strong><small>{item.totals.deliveredCount} livré{item.totals.deliveredCount > 1 ? 's' : ''}</small></td>
                    <td data-label="Récupéré" className="is-money"><strong>{formatAriary(item.totals.amountCollectedAriary)}</strong></td>
                    <td data-label="Frais" className="is-money"><strong>{formatAriary(item.totals.deliveryFeesAriary)}</strong></td>
                    <td data-label="Solde du rapport" className="is-money"><strong>{item.totals.amountToRemitAriary < 0 ? 'À payer à AZY · ' : ''}{formatAriary(Math.abs(item.totals.amountToRemitAriary))}</strong></td>
                    <td data-label="Règlement">{settlement ? <span className={`client-report-list-table__status is-${settlement.status.toLowerCase()}`}>{settlementLabel(settlement)}</span> : <span className="client-report-list-table__status">{financeLoading ? 'Chargement…' : 'Indisponible'}</span>}</td>
                    <td className="client-report-list-table__action"><button className="button button--secondary" type="button" aria-label={`Ouvrir le rapport du ${formatDate(item.serviceDate)}`} onClick={(event) => { event.stopPropagation(); openReport(item.serviceDate) }}><Eye aria-hidden="true" />Voir</button></td>
                  </tr>
                })}</tbody>
              </table>
            </div>
          )}
        </section>
      )}
      {selectedSummary && <span className="sr-only">Rapport sélectionné du {selectedSummary.serviceDate}</span>}
    </section>
  )
}
