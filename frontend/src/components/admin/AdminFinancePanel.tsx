import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  BanknoteArrowDown,
  BanknoteArrowUp,
  CalendarDays,
  CheckCircle2,
  Clock3,
  Download,
  FileCheck2,
  FileSpreadsheet,
  FileText,
  History,
  LoaderCircle,
  PackageCheck,
  ReceiptText,
  RefreshCw,
  Search,
  TriangleAlert,
  UserRound,
  UsersRound,
  WalletCards,
  XCircle,
} from 'lucide-react'
import {
  createClientPayment,
  createCourierPayment,
  downloadAdminClientPaymentProof,
  downloadAdminCourierPaymentProof,
  downloadCourierStatement,
  getAdminClientFinance,
  getCourierFinance,
  getDeliveryDay,
  voidClientPayment,
  voidCourierPayment,
  type ClientDaySummary,
  type ClientFinance,
  type ClientPaymentDirection,
  type ClientPayment,
  type ClientSettlement,
  type Courier,
  type CourierFinance,
  type CourierPayment,
  type CreatePaymentInput,
  type DailyOperation,
  type DownloadedReport,
  type PaymentDestination,
} from '../../api/admin'
import { ConfirmDialog } from '../ConfirmDialog'
import { PaymentDialog } from '../PaymentDialog'
import { CourierCombobox } from './CourierCombobox'
import { antananarivoToday, errorMessage, formatAriary, formatDate, formatDateTime } from './adminHelpers'
import './AdminFinancePanel.css'

type FinanceView = 'CLIENTS' | 'COURIERS'
type CourierHistoryView = 'DELIVERIES' | 'EARNINGS' | 'PAYMENTS'

interface ClientFinanceRow {
  client: ClientDaySummary
  finance: ClientFinance
  settlement?: ClientSettlement
}

type PendingPayment =
  | { kind: 'CLIENT'; row: ClientFinanceRow }
  | { kind: 'COURIER'; courier: Courier; finance: CourierFinance }

type PaymentToVoid =
  | { kind: 'CLIENT'; payment: ClientPayment }
  | { kind: 'COURIER'; payment: CourierPayment }

function saveFile(file: DownloadedReport) {
  const url = URL.createObjectURL(file.blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = file.filename
  document.body.append(anchor)
  anchor.click()
  anchor.remove()
  URL.revokeObjectURL(url)
}

function settlementLabel(settlement: ClientSettlement): string {
  if (settlement.status === 'PAID') return settlement.remainingAriary === 0 ? 'Soldé' : 'Crédit enregistré'
  return settlement.status === 'PARTIALLY_PAID' ? 'Partiellement versé' : 'Non versé'
}

function expectedClientDirection(settlement: ClientSettlement): ClientPaymentDirection {
  if (settlement.remainingAriary < 0) return 'CLIENT_TO_AZY'
  if (settlement.remainingAriary > 0) return 'AZY_TO_CLIENT'
  return settlement.amountDueAriary < 0 ? 'CLIENT_TO_AZY' : 'AZY_TO_CLIENT'
}

function clientDirectionLabel(direction: ClientPaymentDirection): string {
  return direction === 'AZY_TO_CLIENT' ? 'AZY → client' : 'Client → AZY'
}

function paymentDestination(courier: Courier): PaymentDestination | null {
  return courier.payoutDestination ? {
    operator: courier.payoutDestination.operator,
    accountHolder: courier.payoutDestination.accountHolder,
    number: courier.payoutDestination.number,
  } : null
}

export function AdminFinancePanel({
  accessToken,
  couriers,
  initialDate,
}: {
  accessToken: string
  couriers: Courier[]
  initialDate?: string
}) {
  const [view, setView] = useState<FinanceView>('CLIENTS')
  const [date, setDate] = useState(initialDate || antananarivoToday())
  const [day, setDay] = useState<DailyOperation>()
  const [clientRows, setClientRows] = useState<ClientFinanceRow[]>([])
  const [clientLoading, setClientLoading] = useState(true)
  const [clientQuery, setClientQuery] = useState('')
  const [selectedCourierId, setSelectedCourierId] = useState('')
  const [courierFinance, setCourierFinance] = useState<CourierFinance>()
  const [courierLoading, setCourierLoading] = useState(false)
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [historyView, setHistoryView] = useState<CourierHistoryView>('DELIVERIES')
  const [message, setMessage] = useState<string>()
  const [pendingPayment, setPendingPayment] = useState<PendingPayment>()
  const [paymentBusy, setPaymentBusy] = useState(false)
  const [paymentError, setPaymentError] = useState<string>()
  const [paymentToVoid, setPaymentToVoid] = useState<PaymentToVoid>()
  const [voidReason, setVoidReason] = useState('')
  const [voidBusy, setVoidBusy] = useState(false)
  const [downloadingStatement, setDownloadingStatement] = useState<'pdf' | 'xlsx'>()

  const loadClients = useCallback(async (signal?: AbortSignal) => {
    setClientLoading(true)
    setMessage(undefined)
    try {
      const loadedDay = await getDeliveryDay(date, accessToken, signal)
      setDay(loadedDay)
      if (loadedDay.status !== 'CLOSED') {
        setClientRows([])
        return
      }
      const finances = await Promise.all(loadedDay.clients.map((client) => (
        getAdminClientFinance(client.accountId, accessToken, signal)
      )))
      setClientRows(loadedDay.clients.map((client, index) => {
        const finance = finances[index]
        return {
          client,
          finance,
          settlement: finance.settlements.find((item) => (
            item.serviceDate === loadedDay.serviceDate
            && item.closureRevision === loadedDay.closureRevision
          )),
        }
      }))
    } catch (error) {
      if ((error as { name?: string }).name !== 'AbortError') setMessage(errorMessage(error))
    } finally {
      if (!signal?.aborted) setClientLoading(false)
    }
  }, [accessToken, date])

  useEffect(() => {
    if (view !== 'CLIENTS') return
    const controller = new AbortController()
    void loadClients(controller.signal)
    return () => controller.abort()
  }, [loadClients, view])

  const loadCourier = useCallback(async (signal?: AbortSignal) => {
    if (!selectedCourierId) {
      setCourierFinance(undefined)
      return
    }
    setCourierLoading(true)
    setMessage(undefined)
    try {
      setCourierFinance(await getCourierFinance(
        selectedCourierId,
        accessToken,
        { from: from || undefined, to: to || undefined },
        signal,
      ))
    } catch (error) {
      if ((error as { name?: string }).name !== 'AbortError') setMessage(errorMessage(error))
    } finally {
      if (!signal?.aborted) setCourierLoading(false)
    }
  }, [accessToken, from, selectedCourierId, to])

  useEffect(() => {
    if (view !== 'COURIERS') return
    const controller = new AbortController()
    void loadCourier(controller.signal)
    return () => controller.abort()
  }, [loadCourier, view])

  const visibleClientRows = useMemo(() => {
    const query = clientQuery.trim().toLocaleLowerCase('fr')
    if (!query) return clientRows
    return clientRows.filter(({ client }) => `${client.displayName} ${client.phone ?? ''}`
      .toLocaleLowerCase('fr').includes(query))
  }, [clientQuery, clientRows])

  const selectedCourier = couriers.find((courier) => courier.id === selectedCourierId)

  async function confirmPayment(
    input: CreatePaymentInput,
    proof: File | null,
    clientDirection?: ClientPaymentDirection,
  ) {
    if (!pendingPayment) return
    setPaymentBusy(true)
    setPaymentError(undefined)
    try {
      if (pendingPayment.kind === 'CLIENT') {
        const settlement = pendingPayment.row.settlement
        if (!settlement) throw new Error('Le rapport financier du client est introuvable.')
        await createClientPayment(settlement.id, {
          ...input,
          direction: clientDirection ?? expectedClientDirection(settlement),
        }, proof, accessToken)
        await loadClients()
      } else {
        await createCourierPayment(pendingPayment.courier.id, input, proof, accessToken)
        await loadCourier()
      }
      setPendingPayment(undefined)
    } catch (error) {
      setPaymentError(errorMessage(error))
    } finally {
      setPaymentBusy(false)
    }
  }

  async function confirmVoid() {
    if (!paymentToVoid || !voidReason.trim()) return
    setVoidBusy(true)
    setPaymentError(undefined)
    try {
      if (paymentToVoid.kind === 'CLIENT') {
        await voidClientPayment(paymentToVoid.payment.id, paymentToVoid.payment.version, voidReason.trim(), accessToken)
        await loadClients()
      } else {
        await voidCourierPayment(paymentToVoid.payment.id, paymentToVoid.payment.version, voidReason.trim(), accessToken)
        await loadCourier()
      }
      setPaymentToVoid(undefined)
      setVoidReason('')
    } catch (error) {
      setPaymentError(errorMessage(error))
    } finally {
      setVoidBusy(false)
    }
  }

  async function downloadProof(kind: 'CLIENT' | 'COURIER', paymentId: string) {
    setMessage(undefined)
    try {
      saveFile(kind === 'CLIENT'
        ? await downloadAdminClientPaymentProof(paymentId, accessToken)
        : await downloadAdminCourierPaymentProof(paymentId, accessToken))
    } catch (error) {
      setMessage(errorMessage(error))
    }
  }

  async function downloadStatement(format: 'pdf' | 'xlsx') {
    if (!selectedCourierId) return
    setDownloadingStatement(format)
    setMessage(undefined)
    try {
      saveFile(await downloadCourierStatement(
        selectedCourierId,
        format,
        { from: from || undefined, to: to || undefined },
        accessToken,
      ))
    } catch (error) {
      setMessage(errorMessage(error))
    } finally {
      setDownloadingStatement(undefined)
    }
  }

  return (
    <section className="admin-panel admin-finance" aria-labelledby="finance-title">
      <header className="admin-panel__heading">
        <div><p className="admin-eyebrow">Registre financier</p><h2 id="finance-title">Versements et rémunérations</h2><p>Suivez les sommes dues, les mouvements confirmés et leur justificatif sans modifier l’historique.</p></div>
      </header>

      <div className="admin-finance__tabs" role="tablist" aria-label="Type de suivi financier">
        <button type="button" role="tab" aria-selected={view === 'CLIENTS'} className={view === 'CLIENTS' ? 'is-active' : undefined} onClick={() => setView('CLIENTS')}><UsersRound aria-hidden="true" /><span><strong>Clients</strong><small>Reversements par rapport</small></span></button>
        <button type="button" role="tab" aria-selected={view === 'COURIERS'} className={view === 'COURIERS' ? 'is-active' : undefined} onClick={() => setView('COURIERS')}><UserRound aria-hidden="true" /><span><strong>Livreurs</strong><small>Gains et versements globaux</small></span></button>
      </div>

      {message && <div className="admin-callout admin-callout--error" role="alert"><TriangleAlert aria-hidden="true" /><div><strong>Chargement impossible</strong><span>{message}</span></div></div>}

      {view === 'CLIENTS' ? (
        <div className="admin-finance__workspace">
          <div className="admin-finance__toolbar">
            <label><CalendarDays aria-hidden="true" /><span>Date clôturée</span><input type="date" value={date} onChange={(event) => event.target.value && setDate(event.target.value)} /></label>
            <label className="admin-finance__search"><Search aria-hidden="true" /><span className="sr-only">Rechercher un client</span><input type="search" value={clientQuery} placeholder="Client ou numéro" onChange={(event) => setClientQuery(event.target.value)} /></label>
            <button className="admin-icon-button" type="button" disabled={clientLoading} aria-label="Actualiser les reversements" onClick={() => void loadClients()}><RefreshCw className={clientLoading ? 'admin-spin' : undefined} aria-hidden="true" /></button>
          </div>

          {clientLoading && !day ? <div className="admin-empty"><LoaderCircle className="admin-spin" aria-hidden="true" /><strong>Chargement des rapports clients…</strong></div>
            : day?.status !== 'CLOSED' ? <div className="admin-empty"><Clock3 aria-hidden="true" /><strong>La journée du {formatDate(date)} n’est pas clôturée</strong><span>Les reversements ne sont créés qu’à partir des rapports définitifs.</span></div>
              : visibleClientRows.length === 0 ? <div className="admin-empty"><UsersRound aria-hidden="true" /><strong>Aucun client à afficher</strong></div>
                : <div className="admin-finance-client-list">{visibleClientRows.map((row) => {
                  const settlement = row.settlement
                  const payments = row.finance.payments.filter((payment) => payment.settlementId === settlement?.id)
                  return <article key={row.client.accountId}>
                    <header><div><span><UsersRound aria-hidden="true" /></span><div><small>{row.client.packageCount} colis · rapport du {formatDate(date)}</small><strong>{row.client.displayName}</strong><em>{row.client.phone ?? 'Sans contact'}</em></div></div>{settlement && <b className={`is-${settlement.status.toLowerCase()}`}>{settlementLabel(settlement)}</b>}</header>
                    {settlement ? <>
                      <div className="admin-finance-client-list__totals"><div><small>Montant du rapport</small><strong>{formatAriary(settlement.amountDueAriary)}</strong></div><div><small>Versements confirmés</small><strong>{formatAriary(settlement.confirmedSignedPaymentsAriary)}</strong></div><div><small>{settlement.remainingAriary < 0 ? 'Crédit / avance' : 'Reste à régler'}</small><strong>{formatAriary(Math.abs(settlement.remainingAriary))}</strong></div></div>
                      <div className="admin-finance-payments">
                        {payments.map((payment) => <div className={payment.status === 'VOIDED' ? 'is-voided' : undefined} key={payment.id}><span>{payment.direction === 'AZY_TO_CLIENT' ? <BanknoteArrowUp aria-hidden="true" /> : <BanknoteArrowDown aria-hidden="true" />}</span><div><strong>{formatAriary(payment.amountAriary)} · {payment.paymentMethod}</strong><small>{clientDirectionLabel(payment.direction)} · {formatDateTime(payment.confirmedAt)}{payment.transactionReference ? ` · ${payment.transactionReference}` : ''}{payment.confirmedByAccountId ? ' · confirmé par l’administration' : ''}</small>{payment.status === 'VOIDED' && <em>Annulé : {payment.voidReason}{payment.voidedByAccountId ? ' · annulé par l’administration' : ''}</em>}</div><div>{payment.proof.available && <button type="button" onClick={() => void downloadProof('CLIENT', payment.id)}><Download aria-hidden="true" />Justificatif</button>}{payment.status === 'CONFIRMED' && <button className="is-danger" type="button" onClick={() => { setPaymentError(undefined); setPaymentToVoid({ kind: 'CLIENT', payment }) }}><XCircle aria-hidden="true" />Annuler</button>}</div></div>)}
                        {payments.length === 0 && <p>Aucun versement enregistré pour ce rapport.</p>}
                      </div>
                      <footer><button className="admin-button" type="button" onClick={() => { setPaymentError(undefined); setPendingPayment({ kind: 'CLIENT', row }) }}><WalletCards aria-hidden="true" />{settlement.status === 'UNPAID' ? 'Confirmer un versement' : 'Ajouter un versement'}</button></footer>
                    </> : <div className="admin-callout admin-callout--error"><TriangleAlert aria-hidden="true" /><div><strong>Rapport financier absent</strong><span>Actualisez la page avant d’enregistrer un versement.</span></div></div>}
                  </article>
                })}</div>}
        </div>
      ) : (
        <div className="admin-finance__workspace">
          <div className="admin-finance-courier-filters">
            <label><span>Livreur</span><CourierCombobox couriers={couriers} value={selectedCourierId} ariaLabel="Choisir le livreur à suivre" onChange={setSelectedCourierId} /></label>
            <label><span>Du <small>facultatif</small></span><input type="date" value={from} onChange={(event) => setFrom(event.target.value)} /></label>
            <label><span>Au <small>facultatif</small></span><input type="date" value={to} min={from || undefined} onChange={(event) => setTo(event.target.value)} /></label>
            <button className="admin-icon-button" type="button" disabled={!selectedCourierId || courierLoading} aria-label="Actualiser le suivi du livreur" onClick={() => void loadCourier()}><RefreshCw className={courierLoading ? 'admin-spin' : undefined} aria-hidden="true" /></button>
          </div>

          {!selectedCourierId ? <div className="admin-empty"><UserRound aria-hidden="true" /><strong>Choisissez un livreur</strong><span>Son historique complet, ses gains et ses versements apparaîtront ici.</span></div>
            : courierLoading && !courierFinance ? <div className="admin-empty"><LoaderCircle className="admin-spin" aria-hidden="true" /><strong>Chargement du suivi…</strong></div>
              : courierFinance && selectedCourier ? <>
                <div className="admin-finance-courier-heading"><div><span><UserRound aria-hidden="true" /></span><div><small>Suivi financier</small><h3>{courierFinance.courierName}</h3><p>{selectedCourier.payoutDestination ? `${selectedCourier.payoutDestination.operator} · ${selectedCourier.payoutDestination.number}` : 'Aucune destination de versement préenregistrée'}</p></div></div><div className="admin-finance-courier-heading__actions"><button className="admin-button admin-button--ghost" type="button" disabled={Boolean(downloadingStatement)} onClick={() => void downloadStatement('pdf')}><FileText aria-hidden="true" />{downloadingStatement === 'pdf' ? 'Préparation…' : 'PDF'}</button><button className="admin-button admin-button--ghost" type="button" disabled={Boolean(downloadingStatement)} onClick={() => void downloadStatement('xlsx')}><FileSpreadsheet aria-hidden="true" />{downloadingStatement === 'xlsx' ? 'Préparation…' : 'Excel'}</button><button className="admin-button" type="button" onClick={() => { setPaymentError(undefined); setPendingPayment({ kind: 'COURIER', courier: selectedCourier, finance: courierFinance }) }}><WalletCards aria-hidden="true" />Confirmer un versement</button></div></div>
                <div className="admin-finance-courier-totals"><article><ReceiptText aria-hidden="true" /><div><small>Total gagné</small><strong>{formatAriary(courierFinance.totalEarnedAriary)}</strong></div></article><article><CheckCircle2 aria-hidden="true" /><div><small>Total versé</small><strong>{formatAriary(courierFinance.totalPaidAriary)}</strong></div></article><article className={courierFinance.remainingToPayAriary > 0 ? 'is-due' : undefined}><WalletCards aria-hidden="true" /><div><small>Reste à verser</small><strong>{formatAriary(courierFinance.remainingToPayAriary)}</strong></div></article><article><BanknoteArrowUp aria-hidden="true" /><div><small>Avance / crédit</small><strong>{formatAriary(courierFinance.advanceCreditAriary)}</strong></div></article></div>
                <div className="admin-finance-history-tabs" role="tablist"><button type="button" role="tab" aria-selected={historyView === 'DELIVERIES'} className={historyView === 'DELIVERIES' ? 'is-active' : undefined} onClick={() => setHistoryView('DELIVERIES')}><PackageCheck aria-hidden="true" />Livraisons <b>{courierFinance.deliveries.length}</b></button><button type="button" role="tab" aria-selected={historyView === 'EARNINGS'} className={historyView === 'EARNINGS' ? 'is-active' : undefined} onClick={() => setHistoryView('EARNINGS')}><FileCheck2 aria-hidden="true" />Rémunérations <b>{courierFinance.earnings.length}</b></button><button type="button" role="tab" aria-selected={historyView === 'PAYMENTS'} className={historyView === 'PAYMENTS' ? 'is-active' : undefined} onClick={() => setHistoryView('PAYMENTS')}><History aria-hidden="true" />Versements <b>{courierFinance.payments.length}</b></button></div>
                {historyView === 'DELIVERIES' && <div className="admin-finance-table"><div className="admin-finance-table__head"><span>Date</span><span>Client / destinataire</span><span>Trajet</span><span>Situation</span><span>Gain</span></div>{courierFinance.deliveries.map((delivery) => <div key={delivery.packageId}><span>{formatDate(delivery.serviceDate)}</span><span><strong>{delivery.clientDisplayName}</strong><small>{delivery.recipientName}</small></span><span><strong>{delivery.pickupLabel}</strong><small>{delivery.destinationLabel}</small></span><span>{delivery.outcome === 'DELIVERED' ? 'Livré' : delivery.returnCompletedAt ? 'Retourné' : 'Retour en attente'}</span><span><strong>{delivery.earnedAmountAriary == null ? 'Non validé' : formatAriary(delivery.earnedAmountAriary)}</strong></span></div>)}</div>}
                {historyView === 'EARNINGS' && <div className="admin-finance-table"><div className="admin-finance-table__head"><span>Date</span><span>Fraction</span><span>Frais finaux</span><span>Suggestion</span><span>Montant retenu</span></div>{courierFinance.earnings.map((earning) => <div key={earning.id}><span>{formatDate(earning.serviceDate)}</span><span>{earning.commissionNumerator}/{earning.commissionDenominator}</span><span>{formatAriary(earning.deliveryFeeAriary)}</span><span>{formatAriary(earning.suggestedAmountAriary)}</span><span><strong>{formatAriary(earning.finalAmountAriary)}</strong>{earning.overrideReason && <small>{earning.overrideReason}</small>}</span></div>)}</div>}
                {historyView === 'PAYMENTS' && <div className="admin-finance-payments admin-finance-payments--courier">{courierFinance.payments.map((payment) => <div className={payment.status === 'VOIDED' ? 'is-voided' : undefined} key={payment.id}><span><WalletCards aria-hidden="true" /></span><div><strong>{formatAriary(payment.amountAriary)} · {payment.paymentMethod}</strong><small>{formatDateTime(payment.confirmedAt)}{payment.transactionReference ? ` · ${payment.transactionReference}` : ''}{payment.confirmedByAccountId ? ' · confirmé par l’administration' : ''}</small>{payment.status === 'VOIDED' && <em>Annulé : {payment.voidReason}{payment.voidedByAccountId ? ' · annulé par l’administration' : ''}</em>}</div><div>{payment.proof.available && <button type="button" onClick={() => void downloadProof('COURIER', payment.id)}><Download aria-hidden="true" />Justificatif</button>}{payment.status === 'CONFIRMED' && <button className="is-danger" type="button" onClick={() => { setPaymentError(undefined); setPaymentToVoid({ kind: 'COURIER', payment }) }}><XCircle aria-hidden="true" />Annuler</button>}</div></div>)}{courierFinance.payments.length === 0 && <p>Aucun versement enregistré pour ce livreur.</p>}</div>}
              </> : null}
        </div>
      )}

      {pendingPayment && <PaymentDialog
        title={pendingPayment.kind === 'CLIENT' ? 'Versement lié au rapport' : 'Versement au livreur'}
        description={pendingPayment.kind === 'CLIENT'
          ? `Ce mouvement restera lié au rapport du ${formatDate(pendingPayment.row.settlement?.serviceDate ?? date)}.`
          : 'Ce mouvement diminue le solde global du livreur, quelle que soit la période payée.'}
        recipientLabel={pendingPayment.kind === 'CLIENT' ? pendingPayment.row.client.displayName : pendingPayment.courier.name}
        defaultAmountAriary={pendingPayment.kind === 'CLIENT'
          ? Math.abs(pendingPayment.row.settlement?.remainingAriary ?? 0)
          : pendingPayment.finance.remainingToPayAriary}
        defaultDestination={pendingPayment.kind === 'CLIENT'
          ? pendingPayment.row.finance.preferredPayoutDestination
          : paymentDestination(pendingPayment.courier)}
        defaultClientDirection={pendingPayment.kind === 'CLIENT' && pendingPayment.row.settlement
          ? expectedClientDirection(pendingPayment.row.settlement)
          : undefined}
        busy={paymentBusy}
        error={paymentError}
        onClose={() => { if (!paymentBusy) setPendingPayment(undefined) }}
        onConfirm={(input, proof, clientDirection) => void confirmPayment(input, proof, clientDirection)}
      />}

      {paymentToVoid && <ConfirmDialog title="Annuler ce versement ?" confirmLabel="Enregistrer l’annulation" busy={voidBusy} danger onClose={() => { setPaymentToVoid(undefined); setVoidReason(''); setPaymentError(undefined) }} onConfirm={() => void confirmVoid()}><p>Le mouvement restera visible dans l’historique et ne participera plus au solde.</p><label className="admin-outcome-field"><span>Motif obligatoire</span><textarea rows={3} maxLength={1000} value={voidReason} onChange={(event) => setVoidReason(event.target.value)} /></label>{paymentError && <p className="admin-form-error" role="alert">{paymentError}</p>}</ConfirmDialog>}
    </section>
  )
}
