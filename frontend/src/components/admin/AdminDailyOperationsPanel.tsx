import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  CalendarDays,
  CheckCircle2,
  Clock3,
  Download,
  FileSpreadsheet,
  FileText,
  LoaderCircle,
  MapPin,
  PackageCheck,
  PackageX,
  RefreshCw,
  RotateCcw,
  Search,
  TriangleAlert,
  Undo2,
  UsersRound,
  X,
} from 'lucide-react'
import {
  carryForwardDeliveryPackage,
  closeDeliveryDay,
  downloadClientDailyReport,
  downloadGeneralDailyReport,
  getDeliveryDay,
  markReturnCompleted,
  reopenDeliveryDay,
  updateDeliveryPackageOutcome,
  type Courier,
  type DailyOperation,
  type DailyOperationPackage,
  type DeliveryPackageOutcome,
  type DownloadedReport,
  type ReportFormat,
} from '../../api/admin'
import type { Location } from '../../api/csvImports'
import { ConfirmDialog } from '../ConfirmDialog'
import { LocationPicker } from '../LocationPicker'
import { AdminPackageOutcomeDialog, type PackageOutcomeInput } from './AdminPackageOutcomeDialog'
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

function asLocation(deliveryPackage: DailyOperationPackage): Location {
  return {
    label: deliveryPackage.pickup.label,
    destinationQuery: deliveryPackage.pickup.query ?? deliveryPackage.pickup.destinationQuery,
    latitude: deliveryPackage.pickup.latitude,
    longitude: deliveryPackage.pickup.longitude,
  }
}

function confirmed(location: Location): boolean {
  return Number.isFinite(location.latitude) && Number.isFinite(location.longitude)
}

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

function CarryForwardDialog({
  accessToken,
  deliveryPackage,
  busy,
  error,
  onConfirm,
  onClose,
}: {
  accessToken: string
  deliveryPackage: DailyOperationPackage
  busy: boolean
  error?: string
  onConfirm: (pickup: Location) => void
  onClose: () => void
}) {
  const [pickup, setPickup] = useState<Location>(() => asLocation(deliveryPackage))

  useEffect(() => {
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !busy) onClose()
    }
    window.addEventListener('keydown', closeOnEscape)
    return () => window.removeEventListener('keydown', closeOnEscape)
  }, [busy, onClose])

  return (
    <div className="admin-modal-backdrop" role="presentation" onMouseDown={(event) => {
      if (event.target === event.currentTarget && !busy) onClose()
    }}>
      <section className="admin-carry-dialog" role="dialog" aria-modal="true" aria-labelledby="carry-dialog-title">
        <header>
          <div><p className="admin-eyebrow">Report au lendemain</p><h2 id="carry-dialog-title">Nouveau retrait de {deliveryPackage.recipientName}</h2></div>
          <button type="button" aria-label="Fermer" disabled={busy} onClick={onClose}><X aria-hidden="true" /></button>
        </header>
        <div className="admin-carry-dialog__body">
          <p>Le nouveau colis conservera sa priorité d’origine et occupera une place dans la capacité de demain.</p>
          <label className="admin-outcome-field"><span>Libellé du retrait</span><input value={pickup.label ?? ''} maxLength={200} onChange={(event) => setPickup({ ...pickup, label: event.target.value })} /></label>
          <LocationPicker
            accessToken={accessToken}
            value={pickup}
            title="Nouveau point de retrait"
            description="Indiquez le lieu où le colis sera récupéré demain."
            onChange={(next) => setPickup({ ...next, label: pickup.label?.trim() || next.label || next.destinationQuery || 'Nouveau retrait' })}
          />
          {error && <p className="admin-form-error" role="alert">{error}</p>}
        </div>
        <footer>
          <button className="admin-button admin-button--ghost" type="button" disabled={busy} onClick={onClose}>Annuler</button>
          <button className="admin-button" type="button" disabled={busy || !confirmed(pickup)} onClick={() => onConfirm(pickup)}>{busy ? 'Programmation…' : 'Programmer pour demain'}</button>
        </footer>
      </section>
    </div>
  )
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
  const [loading, setLoading] = useState(true)
  const [busyAction, setBusyAction] = useState<string>()
  const [error, setError] = useState<string>()
  const [dialogError, setDialogError] = useState<string>()
  const [editingPackage, setEditingPackage] = useState<DailyOperationPackage>()
  const [carryPackage, setCarryPackage] = useState<DailyOperationPackage>()
  const [closing, setClosing] = useState(false)
  const [reopening, setReopening] = useState(false)
  const [reopenReason, setReopenReason] = useState('')
  const [query, setQuery] = useState('')
  const [filter, setFilter] = useState<OutcomeFilter>('ALL')

  const load = useCallback(async (signal?: AbortSignal) => {
    setLoading(true)
    setError(undefined)
    try {
      setDay(await getDeliveryDay(date, accessToken, signal))
    } catch (loadError) {
      if ((loadError as { name?: string }).name !== 'AbortError') setError(errorMessage(loadError))
    } finally {
      if (!signal?.aborted) setLoading(false)
    }
  }, [accessToken, date])

  useEffect(() => {
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
  const unscheduledPostponements = day?.packages.filter((deliveryPackage) => (
    deliveryPackage.finalOutcome === 'POSTPONED' && !deliveryPackage.carriedForwardToPackageId
  )).length ?? 0

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
      onChanged()
    } catch (returnError) {
      setError(errorMessage(returnError))
    } finally {
      setBusyAction(undefined)
    }
  }

  async function carryForward(pickup: Location) {
    if (!carryPackage || !confirmed(pickup)) return
    setBusyAction(`carry:${carryPackage.packageId}`)
    setDialogError(undefined)
    try {
      await carryForwardDeliveryPackage(date, carryPackage.packageId, {
        label: pickup.label,
        destinationQuery: pickup.destinationQuery,
        latitude: pickup.latitude as number,
        longitude: pickup.longitude as number,
      }, carryPackage.version, accessToken)
      setCarryPackage(undefined)
      await load()
      onChanged()
    } catch (carryError) {
      setDialogError(errorMessage(carryError))
    } finally {
      setBusyAction(undefined)
    }
  }

  async function closeDay() {
    if (!day) return
    setBusyAction('close')
    setError(undefined)
    try {
      setDay(await closeDeliveryDay(date, day.version, accessToken))
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
      setDay(await reopenDeliveryDay(date, day.version, reopenReason.trim(), accessToken))
      setReopening(false)
      setReopenReason('')
      onChanged()
    } catch (reopenError) {
      setDialogError(errorMessage(reopenError))
    } finally {
      setBusyAction(undefined)
    }
  }

  async function download(kind: 'general' | string, format: ReportFormat) {
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
      ) : day && !day.dispatchPlanId ? (
        <div className="admin-day-empty">
          <span><Clock3 aria-hidden="true" /></span>
          <h3>Aucun plan enregistré pour le {formatDate(date)}</h3>
          <p>Le compte rendu apparaîtra dès que le plan automatique de cette journée aura été généré.</p>
          <button className="admin-button" type="button" onClick={onOpenDispatch}>Préparer le dispatch</button>
        </div>
      ) : day ? (
        <>
          <div className="admin-day-statusbar">
            <div>
              <span className={`admin-day-status is-${day.status.toLowerCase()}`}><i />{DAY_STATUS[day.status]}</span>
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
              {visiblePackages.map((deliveryPackage) => (
                <article className="admin-day-package" key={deliveryPackage.packageId}>
                  <div className="admin-day-package__identity"><span>{deliveryPackage.sequenceNumber}</span><div><small>{deliveryPackage.ownerDisplayName}</small><strong>{deliveryPackage.recipientName}</strong><em>{deliveryPackage.contact}</em></div></div>
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
                  <div><small>{deliveryPackage.finalState ? 'Montants réalisés' : 'Montants prévus'}</small><strong>{formatAriary(deliveryPackage.finalState ? deliveryPackage.recoveredAriary : deliveryPackage.amountToCollectAriary)}</strong><em>{deliveryPackage.mobileMoneyPaid ? 'Mobile Money signalé · ' : ''}frais {formatAriary(deliveryPackage.finalState ? deliveryPackage.chargedDeliveryFeeAriary : (deliveryPackage.deliveryFeeAriary ?? 0))}</em></div>
                  <div className="admin-day-package__outcome">
                    <small>Situation</small>
                    <span className={`admin-outcome-chip is-${(deliveryPackage.finalOutcome ?? 'awaiting').toLowerCase()}`}>{deliveryPackage.finalOutcome ? OUTCOME_LABELS[deliveryPackage.finalOutcome] : 'À finaliser'}</span>
                    {deliveryPackage.finalOutcome === 'RETURN_TO_SENDER' && <em>{deliveryPackage.returnCompletedAt ? 'Remis à l’expéditeur' : 'Retour en attente'}</em>}
                    {deliveryPackage.carriedForwardToPackageId && <em>Programmé pour demain</em>}
                  </div>
                  <div className="admin-day-package__actions">
                    {day.status === 'DISPATCHED'
                      && deliveryPackage.technicalStatus === 'DISPATCHED'
                      && deliveryPackage.finalOutcome !== 'CANCELLED'
                      && !(deliveryPackage.finalOutcome === 'POSTPONED' && deliveryPackage.carriedForwardToPackageId) && (
                      <button className="admin-button admin-button--small" type="button" onClick={() => { setDialogError(undefined); setEditingPackage(deliveryPackage) }}>{deliveryPackage.finalOutcome ? 'Modifier' : 'Renseigner'}</button>
                    )}
                    {deliveryPackage.finalOutcome === 'RETURN_TO_SENDER' && !deliveryPackage.returnCompletedAt && (
                      <button className="admin-button admin-button--ghost admin-button--small" type="button" disabled={busyAction === `return:${deliveryPackage.packageId}`} onClick={() => void confirmReturn(deliveryPackage)}><PackageCheck aria-hidden="true" />Retour effectué</button>
                    )}
                    {deliveryPackage.finalOutcome === 'POSTPONED' && !deliveryPackage.carriedForwardToPackageId && (
                      <button className="admin-button admin-button--ghost admin-button--small" type="button" onClick={() => { setDialogError(undefined); setCarryPackage(deliveryPackage) }}><Undo2 aria-hidden="true" />Programmer demain</button>
                    )}
                  </div>
                  {deliveryPackage.outcomeNote && <p className="admin-day-package__note">{deliveryPackage.outcomeNote}</p>}
                </article>
              ))}
              {visiblePackages.length === 0 && <div className="admin-empty"><Search aria-hidden="true" /><strong>Aucun colis pour ce filtre</strong></div>}
            </div>
          </section>

          <section className="admin-day-close-card">
            <div><p className="admin-eyebrow">Clôture</p><h3>{day.status === 'CLOSED' ? 'Cette journée est clôturée' : day.counts.awaitingOutcome > 0 ? `${day.counts.awaitingOutcome} colis restent à finaliser` : unscheduledPostponements > 0 ? `${unscheduledPostponements} colis reporté${unscheduledPostponements > 1 ? 's restent' : ' reste'} à programmer` : 'Tous les colis ont un état final'}</h3><p>{day.status === 'CLOSED' ? 'Les rapports financiers sont disponibles. Rouvrez uniquement pour corriger une erreur.' : unscheduledPostponements > 0 ? 'Programmez chaque colis reporté pour le lendemain et confirmez son nouveau point de retrait avant de clôturer.' : 'La clôture verrouille le compte rendu et rend les rapports disponibles.'}</p></div>
            {day.status === 'CLOSED' ? <button className="admin-button admin-button--ghost" type="button" onClick={() => { setDialogError(undefined); setReopening(true) }}><RotateCcw aria-hidden="true" />Rouvrir la journée</button> : <button className="admin-button" type="button" disabled={day.status !== 'DISPATCHED' || day.counts.awaitingOutcome > 0 || unscheduledPostponements > 0} onClick={() => setClosing(true)}><PackageCheck aria-hidden="true" />Clôturer la journée</button>}
          </section>

          <section className={`admin-reports${day.status !== 'CLOSED' ? ' is-locked' : ''}`}>
            <header><div><p className="admin-eyebrow">Documents financiers</p><h3>Rapports de la journée</h3><p>{day.status === 'CLOSED' ? 'Téléchargez le rapport général ou les documents propres à chaque compte client.' : 'Les téléchargements seront disponibles après la clôture.'}</p></div></header>
            <div className="admin-report-totals">
              <article><small>Total récupéré</small><strong>{formatAriary(day.totals.recoveredAriary)}</strong></article>
              <article><small>Frais de livraison</small><strong>{formatAriary(day.totals.deliveryFeesAriary)}</strong></article>
              <article><small>Total à reverser</small><strong>{formatAriary(day.totals.amountToRemitAriary)}</strong></article>
            </div>
            <div className="admin-general-report">
              <div><FileText aria-hidden="true" /><span><strong>Rapport général</strong><small>Clients, livreurs, colis et totaux de la journée</small></span></div>
              <div><button className="admin-button admin-button--ghost admin-button--small" type="button" disabled={day.status !== 'CLOSED' || Boolean(busyAction?.startsWith('report:'))} onClick={() => void download('general', 'pdf')}><Download aria-hidden="true" />PDF</button><button className="admin-button admin-button--ghost admin-button--small" type="button" disabled={day.status !== 'CLOSED' || Boolean(busyAction?.startsWith('report:'))} onClick={() => void download('general', 'xlsx')}><FileSpreadsheet aria-hidden="true" />Excel</button></div>
            </div>
            <div className="admin-client-reports">
              {day.clients.map((client) => (
                <article key={client.accountId}>
                  <div><span><UsersRound aria-hidden="true" /></span><div><strong>{client.displayName}</strong><small>{client.finalizedCount}/{client.packageCount} colis · {client.phone || 'Sans contact'}</small></div></div>
                  <div className="admin-client-report__money"><small>Récupéré</small><strong>{formatAriary(client.totals.recoveredAriary)}</strong></div>
                  <div className="admin-client-report__money"><small>Frais</small><strong>{formatAriary(client.totals.deliveryFeesAriary)}</strong></div>
                  <div className="admin-client-report__money"><small>À reverser</small><strong>{formatAriary(client.totals.amountToRemitAriary)}</strong></div>
                  <div className="admin-client-report__actions"><button type="button" aria-label={`Télécharger le rapport PDF de ${client.displayName}`} disabled={day.status !== 'CLOSED' || Boolean(busyAction?.startsWith('report:'))} onClick={() => void download(client.accountId, 'pdf')}><FileText aria-hidden="true" />PDF</button><button type="button" aria-label={`Télécharger le rapport Excel de ${client.displayName}`} disabled={day.status !== 'CLOSED' || Boolean(busyAction?.startsWith('report:'))} onClick={() => void download(client.accountId, 'xlsx')}><FileSpreadsheet aria-hidden="true" />Excel</button></div>
                </article>
              ))}
            </div>
          </section>
        </>
      ) : null}

      {editingPackage && <AdminPackageOutcomeDialog deliveryPackage={editingPackage} couriers={couriers} busy={busyAction === `outcome:${editingPackage.packageId}`} error={dialogError} onClose={() => setEditingPackage(undefined)} onSave={(input) => void saveOutcome(input)} />}
      {carryPackage && <CarryForwardDialog accessToken={accessToken} deliveryPackage={carryPackage} busy={busyAction === `carry:${carryPackage.packageId}`} error={dialogError} onClose={() => setCarryPackage(undefined)} onConfirm={(pickup) => void carryForward(pickup)} />}
      {closing && <ConfirmDialog title="Clôturer cette journée ?" confirmLabel="Clôturer et activer les rapports" busy={busyAction === 'close'} onClose={() => setClosing(false)} onConfirm={() => void closeDay()}><p>Les {day?.counts.total ?? 0} colis ont un état final. Le compte rendu sera verrouillé et les rapports financiers deviendront téléchargeables.</p></ConfirmDialog>}
      {reopening && <ConfirmDialog title="Rouvrir cette journée ?" confirmLabel="Rouvrir la journée" busy={busyAction === 'reopen'} danger onClose={() => { setReopening(false); setDialogError(undefined) }} onConfirm={() => void reopenDay()}><label className="admin-outcome-field"><span>Raison obligatoire</span><textarea rows={3} maxLength={1000} value={reopenReason} placeholder="Ex. Corriger le montant récupéré d’un colis" onChange={(event) => setReopenReason(event.target.value)} /></label>{dialogError && <p className="admin-form-error" role="alert">{dialogError}</p>}</ConfirmDialog>}
    </section>
  )
}
