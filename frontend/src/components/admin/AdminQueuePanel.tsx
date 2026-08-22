import {
  CalendarDays,
  CheckSquare2,
  CircleDollarSign,
  Clock3,
  MapPin,
  PackageOpen,
  Phone,
  RefreshCw,
  Smartphone,
  UserRound,
} from 'lucide-react'
import type { AdminQueue } from '../../api/admin'
import {
  formatAriary,
  formatDate,
  locationLabel,
  packageStatusLabel,
  requestStatusLabel,
} from './adminHelpers'

interface AdminQueuePanelProps {
  date: string
  queue?: AdminQueue
  busy: boolean
  selectedPackageIds: Set<string>
  onDateChange: (date: string) => void
  onReload: () => void
  onPackageSelectionChange: (packageId: string, selected: boolean) => void
  onSelectAllPending: () => void
  onOpenDispatch: () => void
}

export function AdminQueuePanel({
  date,
  queue,
  busy,
  selectedPackageIds,
  onDateChange,
  onReload,
  onPackageSelectionChange,
  onSelectAllPending,
  onOpenDispatch,
}: AdminQueuePanelProps) {
  const requests = queue?.requests ?? []
  const pendingPackages = requests.flatMap((request) => request.packages)
    .filter((deliveryPackage) => deliveryPackage.status === 'PENDING')

  return (
    <section className="admin-panel" aria-labelledby="queue-title">
      <header className="admin-panel__heading">
        <div>
          <p className="admin-eyebrow">Opérations du jour</p>
          <h2 id="queue-title">File d’attente</h2>
          <p>Les demandes confirmées par les clients, avec tous les champs utiles au dispatch.</p>
        </div>
        <div className="admin-toolbar">
          <label className="admin-date-field">
            <CalendarDays aria-hidden="true" />
            <span>Date de service</span>
            <input type="date" value={date} onChange={(event) => onDateChange(event.target.value)} />
          </label>
          <button className="admin-icon-button" type="button" onClick={onReload} disabled={busy} aria-label="Actualiser la file">
            <RefreshCw className={busy ? 'admin-spin' : undefined} aria-hidden="true" />
          </button>
        </div>
      </header>

      {queue && (
        <div className="admin-stats" aria-label={`Situation du ${formatDate(queue.date)}`}>
          <article>
            <span><PackageOpen aria-hidden="true" /></span>
            <div><strong>{queue.stats.pendingPackageCount}</strong><small>colis à dispatcher</small></div>
          </article>
          <article>
            <span><Clock3 aria-hidden="true" /></span>
            <div><strong>{queue.stats.pendingRequestCount}</strong><small>demandes en attente</small></div>
          </article>
          <article className={queue.stats.capacityExceeded ? 'is-warning' : undefined}>
            <span><CheckSquare2 aria-hidden="true" /></span>
            <div>
              <strong>{queue.stats.remainingCapacity}</strong>
              <small>places restantes sur {queue.stats.dailyLimit}</small>
            </div>
          </article>
        </div>
      )}

      {busy && !queue ? (
        <div className="admin-empty"><RefreshCw className="admin-spin" aria-hidden="true" /><strong>Chargement de la file…</strong></div>
      ) : requests.length === 0 ? (
        <div className="admin-empty"><PackageOpen aria-hidden="true" /><strong>Aucun colis en attente pour cette date</strong><span>Les nouvelles demandes apparaîtront ici.</span></div>
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
                    <span className={`admin-request-date${request.requestedDate < date ? ' is-overdue' : ''}`}>
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
                        <span role="cell"><MapPin aria-hidden="true" /><span>{locationLabel(deliveryPackage.destination)}</span></span>
                        <span role="cell">{deliveryPackage.contact || '—'}</span>
                        <span role="cell"><strong>{formatAriary(deliveryPackage.amountToCollectAriary)}</strong></span>
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
