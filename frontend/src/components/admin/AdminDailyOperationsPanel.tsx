import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  BadgeCheck,
  CalendarDays,
  CheckCircle2,
  CircleDollarSign,
  Clock3,
  Download,
  FileSpreadsheet,
  FileText,
  ImageDown,
  LoaderCircle,
  LockKeyhole,
  MapPin,
  PackageCheck,
  PackageX,
  RefreshCw,
  RotateCcw,
  Search,
  TriangleAlert,
  UsersRound,
} from 'lucide-react'
import {
  closeDeliveryClient,
  closeDeliveryDay,
  downloadClientDailyReport,
  downloadGeneralDailyReport,
  getClientClosures,
  getDeliveryDay,
  markReturnCompleted,
  reopenDeliveryClient,
  reopenDeliveryDay,
  updateDeliveryPackageFee,
  updateDeliveryPackageOutcome,
  type Courier,
  type ClientClosureItem,
  type ClientClosureOverview,
  type DailyOperation,
  type DailyOperationPackage,
  type DeliveryPackageOutcome,
  type DownloadedReport,
  type DailyReportFormat,
} from '../../api/admin'
import { ConfirmDialog } from '../ConfirmDialog'
import { AdminPackageOutcomeDialog, type PackageOutcomeInput } from './AdminPackageOutcomeDialog'
import { AdminDeliveryFeeDialog, type DeliveryFeeInput } from './AdminDeliveryFeeDialog'
import { errorMessage, formatAriary, formatDate, formatDateTime, formatTime, locationLabel } from './adminHelpers'

const OUTCOME_LABELS: Record<DeliveryPackageOutcome, string> = {
  DELIVERED: 'Livré',
  CANCELLED: 'Annulé',
  POSTPONED: 'Reporté',
  RETURN_TO_SENDER: 'À retourner',
}

const DAY_STATUS = {
  OPEN: 'En préparation',
  DISPATCHING: 'Calcul en cours',
  DISPATCHED: 'Compte rendu ouvert',
  CLOSED: 'Journée clôturée',
} as const

type OutcomeFilter = 'ALL' | 'AWAITING' | 'DELIVERED' | 'POSTPONED' | 'RETURN_TO_SENDER' | 'CANCELLED'

function saveDownloadedReport(file: DownloadedReport) {
  const url = URL.createObjectURL(file.blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = file.filename
  document.body.append(anchor)
  anchor.click()
  anchor.remove()
  URL.revokeObjectURL(url)
}

export function AdminDailyOperationsPanel({
  accessToken,
  date,
  couriers,
  onDateChange,
  onOpenDispatch,
  onChanged,
}: {
  accessToken: string
  date: string
  couriers: Courier[]
  onDateChange: (date: string) => void
  onOpenDispatch: () => void
  onChanged: () => void
}) {
  const [day, setDay] = useState<DailyOperation>()
  const [clientClosures, setClientClosures] = useState<ClientClosureOverview>()
  const [loading, setLoading] = useState(true)
  const [busyAction, setBusyAction] = useState<string>()
  const [error, setError] = useState<string>()
  const [dialogError, setDialogError] = useState<string>()
  const [editingPackage, setEditingPackage] = useState<DailyOperationPackage>()
  const [editingFeePackage, setEditingFeePackage] = useState<DailyOperationPackage>()
  const [closing, setClosing] = useState(false)
  const [closingClient, setClosingClient] = useState<ClientClosureItem>()
  const [reopening, setReopening] = useState(false)
  const [reopeningClient, setReopeningClient] = useState<ClientClosureItem>()
  const [reopenReason, setReopenReason] = useState('')
  const [clientReopenReason, setClientReopenReason] = useState('')
  const [query, setQuery] = useState('')
  const [filter, setFilter] = useState<OutcomeFilter>('ALL')

  const load = useCallback(async (signal?: AbortSignal) => {
    setLoading(true)
    setError(undefined)
    try {
      const [dayResult, closureResult] = await Promise.allSettled([
        getDeliveryDay(date, accessToken, signal),
        getClientClosures(date, accessToken, signal),
      ])
      if (dayResult.status === 'rejected') throw dayResult.reason
      const loadedDay = dayResult.value
      setDay(loadedDay)
      if (closureResult.status === 'fulfilled') {
        setClientClosures(closureResult.value)
      } else if (!loadedDay.dispatchPlanId && loadedDay.packages.length === 0) {
        setClientClosures({
          serviceDate: loadedDay.serviceDate,
          dayStatus: loadedDay.status,
          dayVersion: loadedDay.version,
          currentClosureRevision: loadedDay.closureRevision,
          clientCount: 0,
          closedClientCount: 0,
          allClientsClosed: false,
          clients: [],
        })
      } else {
        throw closureResult.reason
      }
    } catch (loadError) {
      if ((loadError as { name?: string }).name !== 'AbortError') setError(errorMessage(loadError))
    } finally {
      if (!signal?.aborted) setLoading(false)
    }
  }, [accessToken, date])

  useEffect(() => {
    setDay(undefined)
    setClientClosures(undefined)
    const controller = new AbortController()
    void load(controller.signal)
    return () => controller.abort()
  }, [load])

  const visiblePackages = useMemo(() => {
    const normalized = query.trim().toLocaleLowerCase('fr')
    return day?.packages.filter((deliveryPackage) => {
      if (filter === 'AWAITING' && deliveryPackage.finalState) return false
      if (filter !== 'ALL' && filter !== 'AWAITING' && deliveryPackage.finalOutcome !== filter) return false
      if (!normalized) return true
      return [
        deliveryPackage.ownerDisplayName,
        deliveryPackage.recipientName,
        deliveryPackage.contact,
        locationLabel(deliveryPackage.destination),
        deliveryPackage.distributorCourierName ?? '',
        deliveryPackage.actualCourierName ?? '',
      ].some((value) => value.toLocaleLowerCase('fr').includes(normalized))
    }) ?? []
  }, [day, filter, query])
  const clientClosureById = useMemo(() => new Map(
    (clientClosures?.clients ?? []).map((client) => [client.clientAccountId, client]),
  ), [clientClosures])
  const isExpressOnlyDayWithoutPlan = Boolean(
    day
    && !day.dispatchPlanId
    && day.packages.length > 0
    && day.packages.every((deliveryPackage) => deliveryPackage.requestKind === 'EXPRESS'),
  )
  const canCloseDay = Boolean(
    day
    && (day.status === 'DISPATCHED' || (day.status === 'OPEN' && isExpressOnlyDayWithoutPlan))
    && day.counts.awaitingOutcome === 0
    && clientClosures?.allClientsClosed === true,
  )

  const applyClientClosures = useCallback((overview: ClientClosureOverview) => {
    setClientClosures(overview)
    setDay((current) => current && current.serviceDate === overview.serviceDate ? {
      ...current,
      status: overview.dayStatus,
      version: overview.dayVersion,
      closureRevision: overview.currentClosureRevision,
    } : current)
  }, [])

  const refreshClientClosures = useCallback(async () => {
    const overview = await getClientClosures(date, accessToken)
    applyClientClosures(overview)
    return overview
  }, [accessToken, applyClientClosures, date])

  const refreshClientClosuresSafely = useCallback(async () => {
    try {
      await refreshClientClosures()
    } catch (refreshError) {
      setError(errorMessage(refreshError))
    }
  }, [refreshClientClosures])

  async function saveOutcome(input: PackageOutcomeInput) {
    if (!editingPackage) return
    setBusyAction(`outcome:${editingPackage.packageId}`)
    setDialogError(undefined)
    try {
      const updated = await updateDeliveryPackageOutcome(date, editingPackage.packageId, {
        ...input,
        version: editingPackage.version,
      }, accessToken)
      setDay(updated)
      setEditingPackage(undefined)
      await refreshClientClosuresSafely()
      onChanged()
    } catch (saveError) {
      setDialogError(errorMessage(saveError))
    } finally {
      setBusyAction(undefined)
    }
  }

  async function saveDeliveryFee(input: DeliveryFeeInput) {
    if (!editingFeePackage) return
    setBusyAction(`fee:${editingFeePackage.packageId}`)
    setDialogError(undefined)
    try {
      await updateDeliveryPackageFee(editingFeePackage.packageId, {
        ...input,
        version: editingFeePackage.version,
      }, accessToken)
      setEditingFeePackage(undefined)
      await load()
      onChanged()
    } catch (saveError) {
      setDialogError(errorMessage(saveError))
    } finally {
      setBusyAction(undefined)
    }
  }

  async function confirmReturn(deliveryPackage: DailyOperationPackage) {
    setBusyAction(`return:${deliveryPackage.packageId}`)
    setError(undefined)
    try {
      setDay(await markReturnCompleted(date, deliveryPackage.packageId, deliveryPackage.version, accessToken))
      await refreshClientClosuresSafely()
      onChanged()
    } catch (returnError) {
      setError(errorMessage(returnError))
    } finally {
      setBusyAction(undefined)
    }
  }

  async function closeDay() {
    if (!day || !clientClosures?.allClientsClosed) return
    setBusyAction('close')
    setError(undefined)
    try {
      setDay(await closeDeliveryDay(date, clientClosures.dayVersion, accessToken))
      setClosing(false)
      onChanged()
    } catch (closeError) {
      setClosing(false)
      setError(errorMessage(closeError))
    } finally {
      setBusyAction(undefined)
    }
  }

  async function reopenDay() {
    if (!day || !reopenReason.trim()) {
      setDialogError('Indiquez la raison de la réouverture.')
      return
    }
    setBusyAction('reopen')
    setDialogError(undefined)
    try {
      await reopenDeliveryDay(date, day.version, reopenReason.trim(), accessToken)
      setReopening(false)
      setReopenReason('')
      await load()
      onChanged()
    } catch (reopenError) {
      setDialogError(errorMessage(reopenError))
    } finally {
      setBusyAction(undefined)
    }
  }

  async function closeClient() {
    if (!closingClient || !clientClosures) return
    setBusyAction(`close-client:${closingClient.clientAccountId}`)
    setDialogError(undefined)
    try {
      applyClientClosures(await closeDeliveryClient(
        date,
        closingClient.clientAccountId,
        clientClosures.dayVersion,
        accessToken,
      ))
      setClosingClient(undefined)
      onChanged()
    } catch (closeError) {
      setDialogError(errorMessage(closeError))
    } finally {
      setBusyAction(undefined)
    }
  }

  async function reopenClient() {
    if (!reopeningClient || reopeningClient.version == null || !clientReopenReason.trim()) {
      setDialogError('Indiquez la raison de la réouverture.')
      return
    }
    setBusyAction(`reopen-client:${reopeningClient.clientAccountId}`)
    setDialogError(undefined)
    try {
      applyClientClosures(await reopenDeliveryClient(
        date,
        reopeningClient.clientAccountId,
        reopeningClient.version,
        clientReopenReason.trim(),
        accessToken,
      ))
      setReopeningClient(undefined)
      setClientReopenReason('')
      onChanged()
    } catch (reopenError) {
      setDialogError(errorMessage(reopenError))
    } finally {
      setBusyAction(undefined)
    }
  }

  async function download(kind: 'general' | string, format: DailyReportFormat) {
    setBusyAction(`report:${kind}:${format}`)
    setError(undefined)
    try {
      const file = kind === 'general'
        ? await downloadGeneralDailyReport(date, format, accessToken)
        : await downloadClientDailyReport(date, kind, format, accessToken)
      saveDownloadedReport(file)
    } catch (downloadError) {
      setError(errorMessage(downloadError))
    } finally {
      setBusyAction(undefined)
    }
  }

  return (
    <section className="admin-panel admin-day-panel" aria-labelledby="day-title">
      <header className="admin-panel__heading">
        <div><p className="admin-eyebrow">Suivi opérationnel</p><h2 id="day-title">Compte rendu de la journée</h2><p>Finalisez chaque colis, clôturez les comptes puis téléchargez les rapports.</p></div>
        <div className="admin-toolbar">
          <label className="admin-date-field"><CalendarDays aria-hidden="true" /><span>Date d’activité</span><input type="date" value={date} onChange={(event) => event.target.value && onDateChange(event.target.value)} /></label>
          <button className="admin-icon-button" type="button" disabled={loading} aria-label="Actualiser la journée" onClick={() => void load()}><RefreshCw className={loading ? 'admin-spin' : undefined} aria-hidden="true" /></button>
        </div>
      </header>

      {error && <div className="admin-callout admin-callout--error" role="alert"><TriangleAlert aria-hidden="true" /><div><strong>Opération impossible</strong><span>{error}</span></div></div>}

      {loading && !day ? (
        <div className="admin-empty"><LoaderCircle className="admin-spin" aria-hidden="true" /><strong>Chargement de la journée…</strong></div>
      ) : day && !day.dispatchPlanId && day.packages.length === 0 ? (
        <div className="admin-day-empty">
          <span><Clock3 aria-hidden="true" /></span>
          <h3>Aucune activité enregistrée pour le {formatDate(date)}</h3>
          <p>Les colis du dispatch et les demandes express confirmées apparaîtront ici automatiquement.</p>
          <button className="admin-button" type="button" onClick={onOpenDispatch}>Préparer le dispatch</button>
        </div>
      ) : day ? (
        <>
          <div className="admin-day-statusbar">
            <div>
              <span className={`admin-day-status is-${day.status.toLowerCase()}`}><i />{day.status === 'OPEN' && isExpressOnlyDayWithoutPlan ? 'Express en cours' : DAY_STATUS[day.status]}</span>
              <strong>{formatDate(day.serviceDate)}</strong>
              {day.status === 'CLOSED' && day.closedAt && <small>Clôturée le {formatDateTime(day.closedAt)} · version {day.closureRevision}</small>}
              {day.status !== 'CLOSED' && day.reopenedAt && <small>Réouverte le {formatDateTime(day.reopenedAt)}{day.reopenReason ? ` · ${day.reopenReason}` : ''}</small>}
            </div>
            <div className="admin-day-progress"><span><i style={{ width: `${day.counts.total ? (day.counts.finalized / day.counts.total) * 100 : 100}%` }} /></span><strong>{day.counts.finalized} / {day.counts.total}</strong><small>colis finalisés</small></div>
          </div>

          <div className="admin-day-stats">
            <article><span><CheckCircle2 aria-hidden="true" /></span><div><strong>{day.counts.delivered}</strong><small>livrés</small></div></article>
            <article><span><Clock3 aria-hidden="true" /></span><div><strong>{day.counts.postponed}</strong><small>reportés</small></div></article>
            <article><span><PackageX aria-hidden="true" /></span><div><strong>{day.counts.returnToSender}</strong><small>à retourner</small></div></article>
            <article><span><PackageCheck aria-hidden="true" /></span><div><strong>{day.counts.cancelled}</strong><small>annulés</small></div></article>
          </div>

          <section className="admin-day-workspace">
            <header className="admin-day-workspace__toolbar">
              <label><Search aria-hidden="true" /><span className="sr-only">Rechercher dans le compte rendu</span><input type="search" value={query} placeholder="Client, destinataire, lieu ou livreur" onChange={(event) => setQuery(event.target.value)} /></label>
              <select aria-label="Filtrer par état" value={filter} onChange={(event) => setFilter(event.target.value as OutcomeFilter)}>
                <option value="ALL">Tous les colis</option>
                <option value="AWAITING">À finaliser</option>
                <option value="DELIVERED">Livrés</option>
                <option value="POSTPONED">Reportés</option>
                <option value="RETURN_TO_SENDER">À retourner</option>
                <option value="CANCELLED">Annulés</option>
              </select>
              <strong>{visiblePackages.length} résultat{visiblePackages.length > 1 ? 's' : ''}</strong>
            </header>

            <div className="admin-day-package-list">
              {visiblePackages.map((deliveryPackage) => {
                const clientClosed = clientClosureById.get(deliveryPackage.ownerAccountId)?.closed === true
                return (
                <article className={`admin-day-package${clientClosed ? ' is-client-closed' : ''}`} key={deliveryPackage.packageId}>
                  <div className="admin-day-package__identity"><span>{deliveryPackage.sequenceNumber}</span><div><small>{deliveryPackage.ownerDisplayName}{clientClosed ? ' · compte clôturé' : ''}</small><strong>{deliveryPackage.recipientName}</strong><em>{deliveryPackage.contact}</em></div></div>
                  <div>
                    <small>Livraison</small>
                    <strong><MapPin aria-hidden="true" />{locationLabel(deliveryPackage.destination)}</strong>
                    {deliveryPackage.finalOutcome === 'DELIVERED' || deliveryPackage.finalOutcome === 'RETURN_TO_SENDER' ? (
                      <>
                        <em>Réalisé par : {deliveryPackage.actualCourierName ?? 'Non renseigné — historique'}</em>
                        {deliveryPackage.distributorCourierId === deliveryPackage.actualCourierId
                          ? deliveryPackage.estimatedDeliveryAt && (
                            <em>Passage prévu à {formatTime(deliveryPackage.estimatedDeliveryAt)}</em>
                          )
                          : deliveryPackage.distributorCourierName && (
                            <em>Prévu : {deliveryPackage.distributorCourierName}{deliveryPackage.estimatedDeliveryAt ? ` · ${formatTime(deliveryPackage.estimatedDeliveryAt)}` : ''}</em>
                          )}
                      </>
                    ) : (
                      <em>Prévu : {deliveryPackage.distributorCourierName ?? 'livreur non affecté'}{deliveryPackage.estimatedDeliveryAt ? ` · ${formatTime(deliveryPackage.estimatedDeliveryAt)}` : ''}</em>
                    )}
                  </div>
                  <div><small>{deliveryPackage.finalState ? 'Montants réalisés' : 'Montants prévus'}</small><strong>{formatAriary(deliveryPackage.finalState ? deliveryPackage.recoveredAriary : deliveryPackage.amountToCollectAriary)}</strong><em>{deliveryPackage.mobileMoneyPaid ? 'Mobile Money signalé · ' : ''}frais {formatAriary(deliveryPackage.finalState ? deliveryPackage.chargedDeliveryFeeAriary : (deliveryPackage.finalDeliveryFeeAriary ?? deliveryPackage.deliveryFeeAriary ?? 0))}{deliveryPackage.deliveryFeeOverrideReason ? ' · ajusté' : ''}</em></div>
                  <div className="admin-day-package__outcome">
                    <small>Situation</small>
                    <span className={`admin-outcome-chip is-${(deliveryPackage.finalOutcome ?? 'awaiting').toLowerCase()}`}>{deliveryPackage.finalOutcome ? OUTCOME_LABELS[deliveryPackage.finalOutcome] : 'À finaliser'}</span>
                    {deliveryPackage.finalOutcome === 'RETURN_TO_SENDER' && <em>{deliveryPackage.returnCompletedAt ? 'Remis à l’expéditeur' : 'Retour en attente'}</em>}
                    {deliveryPackage.carriedForwardToPackageId && <em>Reprogrammé</em>}
                    {deliveryPackage.finalOutcome === 'POSTPONED' && !deliveryPackage.carriedForwardToPackageId && <em>À reprogrammer dans l’onglet Reportés après clôture</em>}
                  </div>
                  <div className="admin-day-package__actions">
                    {day.status !== 'CLOSED' && deliveryPackage.finalOutcome !== 'CANCELLED' && (
                      <button className="admin-button admin-button--ghost admin-button--small" type="button" disabled={clientClosed} title={clientClosed ? 'Le compte de ce client est clôturé.' : undefined} onClick={() => { setDialogError(undefined); setEditingFeePackage(deliveryPackage) }}><CircleDollarSign aria-hidden="true" />Tarif</button>
                    )}
                    {(day.status === 'DISPATCHED' || (day.status === 'OPEN' && deliveryPackage.requestKind === 'EXPRESS'))
                      && deliveryPackage.technicalStatus === 'DISPATCHED'
                      && deliveryPackage.finalOutcome !== 'CANCELLED'
                      && !(deliveryPackage.finalOutcome === 'POSTPONED' && deliveryPackage.carriedForwardToPackageId) && (
                      <button className="admin-button admin-button--small" type="button" disabled={clientClosed} title={clientClosed ? 'Le compte de ce client est clôturé.' : undefined} onClick={() => { setDialogError(undefined); setEditingPackage(deliveryPackage) }}>{deliveryPackage.finalOutcome ? 'Modifier' : 'Renseigner'}</button>
                    )}
                    {deliveryPackage.finalOutcome === 'RETURN_TO_SENDER' && !deliveryPackage.returnCompletedAt && (
                      <button className="admin-button admin-button--ghost admin-button--small" type="button" disabled={busyAction === `return:${deliveryPackage.packageId}`} onClick={() => void confirmReturn(deliveryPackage)}><PackageCheck aria-hidden="true" />Retour effectué</button>
                    )}
                  </div>
                  {deliveryPackage.outcomeNote && <p className="admin-day-package__note">{deliveryPackage.outcomeNote}</p>}
                </article>
                )
              })}
              {visiblePackages.length === 0 && <div className="admin-empty"><Search aria-hidden="true" /><strong>Aucun colis pour ce filtre</strong></div>}
            </div>
          </section>

          <section className="admin-client-closures" aria-labelledby="client-closures-title">
            <header>
              <div><p className="admin-eyebrow">Clôture progressive</p><h3 id="client-closures-title">Comptes clients</h3><p>Clôturez chaque client dès que ses colis sont prêts. Son rapport et le montant à reverser deviennent disponibles sans attendre les autres.</p></div>
              {clientClosures && <span className={`admin-client-closures__progress${clientClosures.allClientsClosed ? ' is-complete' : ''}`}><BadgeCheck aria-hidden="true" />{clientClosures.closedClientCount}/{clientClosures.clientCount} clôturé{clientClosures.closedClientCount > 1 ? 's' : ''}</span>}
            </header>

            {clientClosures ? (
              <div className="admin-client-closure-list">
                {clientClosures.clients.map((client) => {
                  const actionBusy = busyAction === `close-client:${client.clientAccountId}` || busyAction === `reopen-client:${client.clientAccountId}`
                  return (
                    <article className={`admin-client-closure is-${client.closed ? 'closed' : client.readyToClose ? 'ready' : 'blocked'}`} key={client.clientAccountId}>
                      <div className="admin-client-closure__summary">
                        <div className="admin-client-closure__identity"><span>{client.closed ? <LockKeyhole aria-hidden="true" /> : <UsersRound aria-hidden="true" />}</span><div><strong>{client.clientDisplayName}</strong><small>{client.packageCount} colis · {client.clientPhone || 'Sans contact'}{client.closedAt ? ` · clôturé le ${formatDateTime(client.closedAt)}` : ''}</small></div></div>
                        <span className="admin-client-closure__status">{client.closed ? <><LockKeyhole aria-hidden="true" />Clôturé</> : client.readyToClose ? <><CheckCircle2 aria-hidden="true" />Prêt à clôturer</> : <><TriangleAlert aria-hidden="true" />Bloqué</>}</span>
                        <div className="admin-client-closure__money"><small>Récupéré</small><strong>{formatAriary(client.recoveredAriary)}</strong></div>
                        <div className="admin-client-closure__money"><small>Frais</small><strong>{formatAriary(client.deliveryFeesAriary)}</strong></div>
                        <div className="admin-client-closure__money"><small>À reverser</small><strong>{formatAriary(client.amountToRemitAriary)}</strong></div>
                      </div>

                      {!client.closed && client.blockers.length > 0 && (
                        <ul className="admin-client-closure__blockers" aria-label={`Points bloquants pour ${client.clientDisplayName}`}>
                          {client.blockers.map((blocker) => <li key={blocker}>{blocker}</li>)}
                        </ul>
                      )}

                      <div className="admin-client-closure__actions">
                        {client.closed ? (
                          <>
                            <button className="admin-button admin-button--ghost admin-button--small" type="button" aria-label={`Télécharger le rapport PDF de ${client.clientDisplayName}`} disabled={Boolean(busyAction?.startsWith('report:'))} onClick={() => void download(client.clientAccountId, 'pdf')}><FileText aria-hidden="true" />PDF</button>
                            <button className="admin-button admin-button--ghost admin-button--small" type="button" aria-label={`Télécharger le rapport Excel de ${client.clientDisplayName}`} disabled={Boolean(busyAction?.startsWith('report:'))} onClick={() => void download(client.clientAccountId, 'xlsx')}><FileSpreadsheet aria-hidden="true" />Excel</button>
                            <button className="admin-button admin-button--ghost admin-button--small" type="button" aria-label={`Télécharger le rapport photo de ${client.clientDisplayName}`} title="Même présentation que le PDF · plusieurs pages : images dans un ZIP" disabled={Boolean(busyAction?.startsWith('report:'))} onClick={() => void download(client.clientAccountId, 'image')}><ImageDown aria-hidden="true" />Photo</button>
                            {day.status !== 'CLOSED' && client.version != null && <button className="admin-button admin-button--ghost admin-button--small" type="button" disabled={actionBusy} onClick={() => { setDialogError(undefined); setClientReopenReason(''); setReopeningClient(client) }}><RotateCcw aria-hidden="true" />Rouvrir ce client</button>}
                          </>
                        ) : (
                          <button className="admin-button admin-button--small" type="button" aria-label={`Clôturer le compte de ${client.clientDisplayName}`} disabled={!client.readyToClose || day.status === 'CLOSED' || actionBusy} onClick={() => { setDialogError(undefined); setClosingClient(client) }}>{actionBusy ? <LoaderCircle className="admin-spin" aria-hidden="true" /> : <PackageCheck aria-hidden="true" />}Clôturer ce client</button>
                        )}
                      </div>

                    </article>
                  )
                })}
              </div>
            ) : (
              <div className="admin-empty"><LoaderCircle className="admin-spin" aria-hidden="true" /><strong>Vérification des comptes clients…</strong></div>
            )}

            <footer className="admin-day-close-card">
              <div><p className="admin-eyebrow">Clôture générale</p><h3>{day.status === 'CLOSED' ? 'Cette journée est définitivement clôturée' : clientClosures?.allClientsClosed ? 'Tous les clients sont clôturés' : `${clientClosures?.closedClientCount ?? 0}/${clientClosures?.clientCount ?? day.clients.length} clients clôturés`}</h3><p>{day.status === 'CLOSED' ? 'Le rapport général est disponible. Rouvrez la journée uniquement pour corriger une erreur.' : clientClosures?.allClientsClosed ? 'Vous pouvez maintenant figer la journée et générer le rapport général.' : 'La clôture générale sera disponible une fois tous les comptes clients clôturés.'}</p></div>
              {day.status === 'CLOSED' ? <button className="admin-button admin-button--ghost" type="button" onClick={() => { setDialogError(undefined); setReopening(true) }}><RotateCcw aria-hidden="true" />Rouvrir la journée</button> : <button className="admin-button" type="button" disabled={!canCloseDay || busyAction === 'close'} onClick={() => setClosing(true)}><PackageCheck aria-hidden="true" />Clôturer la journée</button>}
            </footer>
          </section>

          <section className="admin-reports">
            <header><div><p className="admin-eyebrow">Documents financiers</p><h3>Rapport général de la journée</h3><p>{day.status === 'CLOSED' ? 'Le document consolidé est prêt. Les rapports clients restent également accessibles depuis chaque compte clôturé.' : 'Les rapports clients sont disponibles au fil des clôtures ci-dessus. Le document général attend la clôture de toute la journée.'}</p></div></header>
            <div className="admin-report-totals">
              <article><small>Total récupéré</small><strong>{formatAriary(day.totals.recoveredAriary)}</strong></article>
              <article><small>Frais de livraison</small><strong>{formatAriary(day.totals.deliveryFeesAriary)}</strong></article>
              <article><small>Total à reverser</small><strong>{formatAriary(day.totals.amountToRemitAriary)}</strong></article>
            </div>
            <div className={`admin-general-report${day.status !== 'CLOSED' ? ' is-locked' : ''}`}>
              <div><FileText aria-hidden="true" /><span><strong>Rapport général</strong><small>Clients, livreurs, colis et totaux de la journée</small></span></div>
              <div>
                <button className="admin-button admin-button--ghost admin-button--small" type="button" disabled={day.status !== 'CLOSED' || Boolean(busyAction?.startsWith('report:'))} onClick={() => void download('general', 'pdf')}><Download aria-hidden="true" />PDF</button>
                <button className="admin-button admin-button--ghost admin-button--small" type="button" disabled={day.status !== 'CLOSED' || Boolean(busyAction?.startsWith('report:'))} onClick={() => void download('general', 'xlsx')}><FileSpreadsheet aria-hidden="true" />Excel</button>
                <button className="admin-button admin-button--ghost admin-button--small" type="button" aria-label="Télécharger le rapport général en photo" title="Même présentation que le PDF · plusieurs pages : images dans un ZIP" disabled={day.status !== 'CLOSED' || Boolean(busyAction?.startsWith('report:'))} onClick={() => void download('general', 'image')}><ImageDown aria-hidden="true" />Photo</button>
              </div>
            </div>
          </section>
        </>
      ) : null}

      {editingPackage && <AdminPackageOutcomeDialog deliveryPackage={editingPackage} couriers={couriers} busy={busyAction === `outcome:${editingPackage.packageId}`} error={dialogError} onClose={() => setEditingPackage(undefined)} onSave={(input) => void saveOutcome(input)} />}
      {editingFeePackage && <AdminDeliveryFeeDialog deliveryPackage={editingFeePackage} busy={busyAction === `fee:${editingFeePackage.packageId}`} error={dialogError} onClose={() => setEditingFeePackage(undefined)} onSave={(input) => void saveDeliveryFee(input)} />}
      {closingClient && <ConfirmDialog title={`Clôturer ${closingClient.clientDisplayName} ?`} confirmLabel="Clôturer ce client" busy={busyAction === `close-client:${closingClient.clientAccountId}`} onClose={() => { setClosingClient(undefined); setDialogError(undefined) }} onConfirm={() => void closeClient()}><p>Les {closingClient.packageCount} colis de ce client seront figés. Son rapport financier sera disponible et son montant à reverser sera enregistré.</p>{dialogError && <p className="admin-form-error" role="alert">{dialogError}</p>}</ConfirmDialog>}
      {reopeningClient && <ConfirmDialog title={`Rouvrir ${reopeningClient.clientDisplayName} ?`} confirmLabel="Rouvrir ce client" busy={busyAction === `reopen-client:${reopeningClient.clientAccountId}`} danger onClose={() => { setReopeningClient(undefined); setClientReopenReason(''); setDialogError(undefined) }} onConfirm={() => void reopenClient()}><p>Le rapport et le montant à reverser seront recalculés après correction. Les versements liés doivent d’abord être annulés.</p><label className="admin-outcome-field"><span>Raison obligatoire</span><textarea rows={3} maxLength={1000} value={clientReopenReason} placeholder="Ex. Corriger l’état final d’un colis" onChange={(event) => setClientReopenReason(event.target.value)} /></label>{dialogError && <p className="admin-form-error" role="alert">{dialogError}</p>}</ConfirmDialog>}
      {closing && <ConfirmDialog title="Clôturer cette journée ?" confirmLabel="Clôturer et activer le rapport général" busy={busyAction === 'close'} onClose={() => setClosing(false)} onConfirm={() => void closeDay()}><p>Les {clientClosures?.clientCount ?? day?.clients.length ?? 0} comptes clients sont clôturés. La journée sera figée et le rapport général deviendra téléchargeable.</p></ConfirmDialog>}
      {reopening && <ConfirmDialog title="Rouvrir cette journée ?" confirmLabel="Rouvrir la journée" busy={busyAction === 'reopen'} danger onClose={() => { setReopening(false); setDialogError(undefined) }} onConfirm={() => void reopenDay()}><label className="admin-outcome-field"><span>Raison obligatoire</span><textarea rows={3} maxLength={1000} value={reopenReason} placeholder="Ex. Corriger le montant récupéré d’un colis" onChange={(event) => setReopenReason(event.target.value)} /></label>{dialogError && <p className="admin-form-error" role="alert">{dialogError}</p>}</ConfirmDialog>}
    </section>
  )
}
