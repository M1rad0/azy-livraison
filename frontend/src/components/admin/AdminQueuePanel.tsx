import { useEffect, useState, type FormEvent } from 'react'
import {
  CalendarDays,
  CircleDollarSign,
  Clock3,
  Filter,
  MapPin,
  PackageOpen,
  Phone,
  RefreshCw,
  Search,
  Smartphone,
  UserRound,
  UsersRound,
} from 'lucide-react'
import type { DeliveryRequest } from '../../api/admin'
import {
  antananarivoToday,
  formatAriary,
  formatDate,
  locationLabel,
  packageStatusLabel,
  requestStatusLabel,
} from './adminHelpers'

export interface PendingQueueFilters {
  scope: 'ALL' | 'PERIOD'
  from: string
  to: string
  client: string
}

interface AdminQueuePanelProps {
  requests: DeliveryRequest[]
  filters: PendingQueueFilters
  busy: boolean
  selectedPackageIds: Set<string>
  onFiltersChange: (filters: PendingQueueFilters) => void
  onReload: () => void
  onPackageSelectionChange: (packageId: string, selected: boolean) => void
  onSelectAllPending: () => void
  onOpenDispatch: () => void
}

export function AdminQueuePanel({
  requests,
  filters,
  busy,
  selectedPackageIds,
  onFiltersChange,
  onReload,
  onPackageSelectionChange,
  onSelectAllPending,
  onOpenDispatch,
}: AdminQueuePanelProps) {
  const [draft, setDraft] = useState(filters)
  const [filterError, setFilterError] = useState<string>()
  const pendingPackages = requests.flatMap((request) => request.packages)
    .filter((deliveryPackage) => deliveryPackage.status === 'PENDING')
  const clientCount = new Set(requests.map((request) => request.ownerAccountId)).size
  const hasActiveFilters = filters.scope === 'PERIOD' || Boolean(filters.client)
  const today = antananarivoToday()

  useEffect(() => setDraft(filters), [filters])

  function applyFilters(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (draft.scope === 'PERIOD' && draft.from && draft.to && draft.from > draft.to) {
      setFilterError('La date de début doit précéder la date de fin.')
      return
    }
    setFilterError(undefined)
    onFiltersChange({
      ...draft,
      from: draft.scope === 'PERIOD' ? draft.from : '',
      to: draft.scope === 'PERIOD' ? draft.to : '',
      client: draft.client.trim(),
    })
  }

  function clearFilters() {
    const cleared: PendingQueueFilters = { scope: 'ALL', from: '', to: '', client: '' }
    setDraft(cleared)
    setFilterError(undefined)
    onFiltersChange(cleared)
  }

  return (
    <section className="admin-panel" aria-labelledby="queue-title">
      <header className="admin-panel__heading">
        <div>
          <p className="admin-eyebrow">Vue globale</p>
          <h2 id="queue-title">Livraisons en attente</h2>
          <p>Retrouvez tous les colis encore à dispatcher, quelle que soit leur date demandée.</p>
        </div>
        <button className="admin-icon-button" type="button" onClick={onReload} disabled={busy} aria-label="Actualiser les livraisons en attente">
          <RefreshCw className={busy ? 'admin-spin' : undefined} aria-hidden="true" />
        </button>
      </header>

      <form className="admin-queue-filters" onSubmit={applyFilters}>
        <div className="admin-queue-filters__scope">
          <span><Filter aria-hidden="true" />Dates demandées</span>
          <div className="admin-segmented" role="group" aria-label="Période des livraisons">
            <button className={draft.scope === 'ALL' ? 'is-active' : undefined} type="button" onClick={() => setDraft((current) => ({ ...current, scope: 'ALL' }))}>Toutes les dates</button>
            <button className={draft.scope === 'PERIOD' ? 'is-active' : undefined} type="button" onClick={() => setDraft((current) => ({ ...current, scope: 'PERIOD' }))}>Une période</button>
          </div>
        </div>
        {draft.scope === 'PERIOD' && (
          <div className="admin-queue-filters__period">
            <label><span>Du</span><input type="date" required value={draft.from} max={draft.to || undefined} onChange={(event) => setDraft((current) => ({ ...current, from: event.target.value }))} /></label>
            <label><span>Au</span><input type="date" required value={draft.to} min={draft.from || undefined} onChange={(event) => setDraft((current) => ({ ...current, to: event.target.value }))} /></label>
          </div>
        )}
        <label className="admin-queue-filters__search">
          <span><Search aria-hidden="true" />Rechercher un client</span>
          <input type="search" value={draft.client} maxLength={100} placeholder="Nom, identifiant ou téléphone" onChange={(event) => setDraft((current) => ({ ...current, client: event.target.value }))} />
        </label>
        <div className="admin-queue-filters__actions">
          {hasActiveFilters && <button className="admin-button admin-button--ghost" type="button" onClick={clearFilters}>Tout afficher</button>}
          <button className="admin-button" type="submit" disabled={busy}><Search aria-hidden="true" />Appliquer</button>
        </div>
        {filterError && <p className="admin-queue-filters__error" role="alert">{filterError}</p>}
      </form>

      {!busy || requests.length > 0 ? (
        <div className="admin-stats" aria-label="Situation globale des livraisons en attente">
          <article>
            <span><PackageOpen aria-hidden="true" /></span>
            <div><strong>{pendingPackages.length}</strong><small>colis en attente</small></div>
          </article>
          <article>
            <span><Clock3 aria-hidden="true" /></span>
            <div><strong>{requests.length}</strong><small>demande{requests.length > 1 ? 's' : ''} concernée{requests.length > 1 ? 's' : ''}</small></div>
          </article>
          <article>
            <span><UsersRound aria-hidden="true" /></span>
            <div><strong>{clientCount}</strong><small>client{clientCount > 1 ? 's' : ''}</small></div>
          </article>
        </div>
      ) : null}

      {busy && requests.length === 0 ? (
        <div className="admin-empty"><RefreshCw className="admin-spin" aria-hidden="true" /><strong>Chargement des livraisons…</strong></div>
      ) : requests.length === 0 ? (
        <div className="admin-empty"><PackageOpen aria-hidden="true" /><strong>{hasActiveFilters ? 'Aucun résultat pour ces filtres' : 'Aucune livraison en attente'}</strong><span>{hasActiveFilters ? 'Modifiez la période ou la recherche client.' : 'Les nouvelles demandes apparaîtront ici.'}</span></div>
      ) : (
        <>
          <div className="admin-selection-bar">
            <span><strong>{selectedPackageIds.size}</strong> colis sélectionné{selectedPackageIds.size > 1 ? 's' : ''}</span>
            <div>
              <button className="admin-button admin-button--ghost" type="button" onClick={onSelectAllPending}>
                Tout sélectionner ({pendingPackages.length})
              </button>
              <button className="admin-button" type="button" disabled={selectedPackageIds.size === 0} onClick={onOpenDispatch}>
                Préparer le dispatch
              </button>
            </div>
          </div>

          <div className="admin-request-list">
            {requests.map((request) => (
              <article className="admin-request-card" key={request.id}>
                <header>
                  <div className="admin-request-card__identity">
                    <span aria-hidden="true"><UserRound /></span>
                    <div>
                      <small>Compte client</small>
                      <strong>{request.ownerDisplayName}</strong>
                      <span><Phone aria-hidden="true" />{request.ownerPhone || 'Contact non renseigné'}</span>
                    </div>
                  </div>
                  <div className="admin-request-card__meta">
                    <span className={`admin-request-date${request.requestedDate < today ? ' is-overdue' : ''}`}>
                      <CalendarDays aria-hidden="true" />Demandée pour le {formatDate(request.requestedDate)}
                    </span>
                    {request.capacity.accepted && <span className="admin-chip admin-chip--warning">Dépassement accepté</span>}
                    <span className="admin-chip">{requestStatusLabel(request.status)}</span>
                    <strong>{request.packages.length} colis</strong>
                  </div>
                </header>

                <div className="admin-pickup-summary">
                  <MapPin aria-hidden="true" />
                  <div><small>Point de retrait</small><strong>{locationLabel(request.pickup)}</strong></div>
                </div>

                <div className="admin-package-table" role="table" aria-label={`Colis de ${request.ownerDisplayName}`}>
                  <div className="admin-package-row admin-package-row--header" role="row">
                    <span role="columnheader">Choix</span>
                    <span role="columnheader">Destinataire</span>
                    <span role="columnheader">Destination</span>
                    <span role="columnheader">Contact</span>
                    <span role="columnheader">À récupérer</span>
                    <span role="columnheader">Paiement</span>
                    <span role="columnheader">État</span>
                  </div>
                  {request.packages.map((deliveryPackage) => {
                    const selectable = deliveryPackage.status === 'PENDING'
                    return (
                      <div className="admin-package-row" role="row" key={deliveryPackage.id}>
                        <span role="cell">
                          <input
                            type="checkbox"
                            aria-label={`Sélectionner le colis de ${deliveryPackage.recipientName}`}
                            checked={selectedPackageIds.has(deliveryPackage.id)}
                            disabled={!selectable}
                            onChange={(event) => onPackageSelectionChange(deliveryPackage.id, event.target.checked)}
                          />
                        </span>
                        <span role="cell"><strong>{deliveryPackage.recipientName}</strong>{deliveryPackage.faritra && <small>Faritra</small>}</span>
                        <span role="cell"><MapPin aria-hidden="true" /><span>{locationLabel(deliveryPackage.destination)}<small>{deliveryPackage.pricingZoneName ? `Zone ${deliveryPackage.pricingZoneName}` : 'Tarif non disponible'}</small></span></span>
                        <span role="cell">{deliveryPackage.contact || '—'}</span>
                        <span role="cell"><strong>{formatAriary(deliveryPackage.amountToCollectAriary)}</strong><small>{deliveryPackage.deliveryFeeAriary != null ? `dont ${formatAriary(deliveryPackage.deliveryFeeAriary)} de livraison` : 'Tarif indisponible'}</small></span>
                        <span role="cell">
                          {deliveryPackage.mobileMoneyPaid
                            ? <><Smartphone aria-hidden="true" />Mobile Money</>
                            : <><CircleDollarSign aria-hidden="true" />À encaisser</>}
                        </span>
                        <span role="cell"><i className={`admin-status-dot admin-status-dot--${deliveryPackage.status.toLowerCase()}`} />{packageStatusLabel(deliveryPackage.status)}</span>
                      </div>
                    )
                  })}
                </div>
              </article>
            ))}
          </div>
        </>
      )}
    </section>
  )
}
