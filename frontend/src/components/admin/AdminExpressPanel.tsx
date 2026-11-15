import { useCallback, useEffect, useMemo, useState } from 'react'
import { Ban, Bike, CalendarClock, Check, Clock3, Fuel, LoaderCircle, PackageCheck, Pencil, RefreshCw, Search, Send, TriangleAlert, Undo2, Zap } from 'lucide-react'
import {
  cancelExpressDelivery,
  confirmExpressDelivery,
  listExpressDeliveries,
  type Courier,
  type ExpressDelivery,
} from '../../api/admin'
import { formatOneHourWindow } from '../../deliveryWindows'
import { ConfirmDialog } from '../ConfirmDialog'
import { ExistingPackageActions, type EditablePackageContext } from '../ExistingPackageActions'
import { Button } from '../Button'
import { errorMessage, formatAriary } from './adminHelpers'

const statusLabel = { PENDING: 'En attente', CONFIRMED: 'Confirmée', COMPLETED: 'Terminée' } as const

export function AdminExpressPanel({ accessToken, couriers, refreshKey = 0, onOpenClients }: {
  accessToken: string
  couriers: Courier[]
  refreshKey?: number
  onOpenClients: () => void
}) {
  const [deliveries, setDeliveries] = useState<ExpressDelivery[]>([])
  const [loading, setLoading] = useState(true)
  const [busyId, setBusyId] = useState<string>()
  const [selected, setSelected] = useState<Record<string, string[]>>({})
  const [returnToPickup, setReturnToPickup] = useState<Record<string, boolean>>({})
  const [courierQuery, setCourierQuery] = useState<Record<string, string>>({})
  const [cancelTarget, setCancelTarget] = useState<ExpressDelivery>()
  const [message, setMessage] = useState<string>()
  const [packageAction, setPackageAction] = useState<EditablePackageContext>()

  const load = useCallback(async (signal?: AbortSignal) => {
    setLoading(true)
    try { setDeliveries(await listExpressDeliveries(accessToken, signal)) }
    catch (error) { if ((error as { name?: string }).name !== 'AbortError') setMessage(errorMessage(error)) }
    finally { if (!signal?.aborted) setLoading(false) }
  }, [accessToken])

  useEffect(() => {
    const controller = new AbortController()
    void load(controller.signal)
    return () => controller.abort()
  }, [load, refreshKey])

  const activeCouriers = useMemo(() => couriers.filter((courier) => courier.active), [couriers])

  async function confirm(delivery: ExpressDelivery) {
    const ids = selected[delivery.request.id] ?? []
    if (!ids.length) { setMessage('Sélectionnez au moins un livreur.'); return }
    setBusyId(delivery.request.id)
    try {
      const updated = await confirmExpressDelivery(
        delivery.request.id,
        ids,
        delivery.request.version,
        returnToPickup[delivery.request.id] ?? delivery.returnToPickup,
        accessToken,
      )
      setDeliveries((current) => current.map((item) => item.request.id === updated.request.id ? updated : item))
      setMessage('La demande express est confirmée. Les colis apparaissent maintenant dans le compte rendu du jour.')
    } catch (error) { setMessage(errorMessage(error)) }
    finally { setBusyId(undefined) }
  }

  async function cancel() {
    if (!cancelTarget) return
    setBusyId(cancelTarget.request.id)
    try {
      await cancelExpressDelivery(cancelTarget.request.id, cancelTarget.request.version, accessToken)
      setDeliveries((current) => current.filter((item) => item.request.id !== cancelTarget.request.id))
      setCancelTarget(undefined)
    } catch (error) { setMessage(errorMessage(error)); setCancelTarget(undefined) }
    finally { setBusyId(undefined) }
  }

  return (
    <section className="admin-panel admin-express" aria-labelledby="admin-express-title">
      <header className="admin-panel__heading">
        <div><p className="admin-eyebrow">Traitement immédiat</p><h2 id="admin-express-title">Livraisons expresses</h2><p>Confirmez une demande et mobilisez les livreurs disponibles. Les colis ne passent jamais par le dispatch automatique.</p></div>
        <div className="admin-heading-actions"><button className="admin-button admin-button--ghost" type="button" onClick={() => void load()}><RefreshCw />Actualiser</button><button className="admin-button admin-button--express" type="button" onClick={onOpenClients}><Zap />Nouvelle express</button></div>
      </header>
      {message && <div className="admin-callout" role="status"><TriangleAlert /><span>{message}</span></div>}
      {loading ? <div className="admin-empty"><LoaderCircle className="admin-spin" /><strong>Chargement des demandes expresses…</strong></div> : (
        <div className="admin-express-list">
          {deliveries.map((delivery) => {
            const request = delivery.request
            const query = (courierQuery[request.id] ?? '').toLocaleLowerCase('fr')
            const candidates = activeCouriers.filter((courier) => courier.name.toLocaleLowerCase('fr').includes(query))
            const selectedIds = selected[request.id] ?? []
            const selectedCapacity = activeCouriers.filter((courier) => selectedIds.includes(courier.id))
              .reduce((sum, courier) => sum + courier.effectiveMaxWeightKg, 0)
            const warning = selectedIds.length > 0 && delivery.totalWeightKg > selectedCapacity
            return (
              <article key={request.id} className={`admin-express-card is-${request.expressStatus?.toLowerCase()}`}>
                <header><span className="admin-express-card__icon"><Zap /></span><div><small>{new Date(request.createdAt).toLocaleString('fr-MG')}</small><h3>{request.ownerDisplayName}</h3><p>{request.pickup.label} · {request.packages.length} colis · {delivery.totalWeightKg.toLocaleString('fr-MG')} kg</p></div><span className="admin-express-status">{statusLabel[request.expressStatus ?? 'PENDING']}</span></header>
                <div className="admin-express-packages">{request.packages.map((item) => <span key={item.id}><strong>{item.recipientName}</strong><small>{item.destination.label} · {formatAriary(item.finalDeliveryFeeAriary ?? item.deliveryFeeAriary ?? 0)}{formatOneHourWindow(item.desiredDeliveryTime, item.desiredDeliveryEndTime, item.desiredDeliveryEndsNextDay, item.deliveryTimePreference) ? ` · créneau ${formatOneHourWindow(item.desiredDeliveryTime, item.desiredDeliveryEndTime, item.desiredDeliveryEndsNextDay, item.deliveryTimePreference)}` : ''}</small>
                  {item.canEdit && <div className="admin-toolbar admin-express-packages__actions"><Button tone="admin" variant="secondary" size="small" onClick={() => setPackageAction({ item, ownerAccountId: request.ownerAccountId, requestedDate: request.requestedDate, requestKind: 'EXPRESS', action: 'EDIT' })}><Pencil aria-hidden="true" />Modifier</Button>
                    <Button tone="admin" variant="secondary" size="small" onClick={() => setPackageAction({ item, ownerAccountId: request.ownerAccountId, requestedDate: request.requestedDate, requestKind: 'EXPRESS', action: 'RESCHEDULE' })}><CalendarClock aria-hidden="true" />Reprogrammer</Button></div>}
                </span>)}</div>
                {request.expressStatus === 'PENDING' && (
                  <div className="admin-express-assignment">
                    <div className="admin-express-assignment__heading"><div><strong>Livreurs mobilisés</strong><small>Le livreur réellement intervenu sera renseigné au compte rendu.</small></div><label><Search /><input placeholder="Rechercher…" value={courierQuery[request.id] ?? ''} onChange={(event) => setCourierQuery({ ...courierQuery, [request.id]: event.target.value })} /></label></div>
                    <div className="admin-express-couriers">{candidates.map((courier) => <label key={courier.id} className={selectedIds.includes(courier.id) ? 'is-selected' : undefined}><input type="checkbox" checked={selectedIds.includes(courier.id)} onChange={(event) => setSelected({ ...selected, [request.id]: event.target.checked ? [...selectedIds, courier.id] : selectedIds.filter((id) => id !== courier.id) })} /><Bike /><span><strong>{courier.name}</strong><small>{courier.vehicleType === 'MOTO' ? 'Moto' : 'Vélo'} · {courier.effectiveMaxWeightKg} kg</small></span>{selectedIds.includes(courier.id) && <Check />}</label>)}</div>
                    <label className="admin-switch admin-express-return"><input type="checkbox" checked={returnToPickup[request.id] ?? delivery.returnToPickup} onChange={(event) => setReturnToPickup({ ...returnToPickup, [request.id]: event.target.checked })} /><span><i /><span><strong><Undo2 aria-hidden="true" />Retour au retrait</strong><small>Inclure le retour après la dernière livraison dans le calcul du carburant</small></span></span></label>
                    {warning && <p className="admin-callout admin-callout--warning"><TriangleAlert />Poids total supérieur aux {selectedCapacity.toLocaleString('fr-MG')} kg supportés par la sélection. La confirmation reste possible.</p>}
                    <div className="admin-express-actions"><button className="admin-button admin-button--danger-ghost" type="button" onClick={() => setCancelTarget(delivery)}><Ban />Annuler</button><button className="admin-button" type="button" disabled={busyId === request.id || !selectedIds.length} onClick={() => void confirm(delivery)}><Send />Confirmer et envoyer</button></div>
                  </div>
                )}
                {request.expressStatus !== 'PENDING' && <footer><PackageCheck /><span><strong>{delivery.couriers.map((courier) => courier.name).join(', ')}</strong><small>{request.expressStatus === 'COMPLETED' ? 'Tous les colis ont un résultat final.' : 'Compte rendu en attente.'}</small></span><Fuel /><small>{delivery.returnToPickup ? 'Retour au retrait inclus' : 'Sans retour au retrait'}</small><Clock3 /><small>{delivery.confirmedAt ? new Date(delivery.confirmedAt).toLocaleTimeString('fr-MG', { hour: '2-digit', minute: '2-digit' }) : ''}</small></footer>}
              </article>
            )
          })}
          {!deliveries.length && <div className="admin-empty"><Zap /><strong>Aucune livraison express</strong><span>Les nouvelles demandes apparaîtront ici immédiatement.</span></div>}
        </div>
      )}
      {cancelTarget && <ConfirmDialog title="Annuler la demande express ?" confirmLabel="Annuler la demande" danger busy={busyId === cancelTarget.request.id} onClose={() => setCancelTarget(undefined)} onConfirm={() => void cancel()}><p>Cette annulation avant traitement retirera définitivement les colis des flux et des rapports.</p></ConfirmDialog>}
      {packageAction && <ExistingPackageActions context={packageAction} accessToken={accessToken} administration onSaved={() => load()} onClose={() => setPackageAction(undefined)} />}
    </section>
  )
}
