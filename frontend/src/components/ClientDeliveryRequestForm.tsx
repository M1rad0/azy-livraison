import { useMemo, useState } from 'react'
import {
  AlertTriangle,
  CalendarDays,
  CheckCircle2,
  MapPin,
  PackagePlus,
  Pencil,
  Send,
  Trash2,
} from 'lucide-react'
import {
  DailyCapacityExceededError,
  createDeliveryRequest,
  type ClientDefaultPickup,
  type ClientLocation,
  type DeliveryRequestRecord,
} from '../api/client'
import type { Location } from '../api/csvImports'
import { ClientPackageDialog, emptyClientPackage, type ClientPackageDraft } from './ClientPackageDialog'
import { LocationPicker } from './LocationPicker'

function tomorrow(): string {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Indian/Antananarivo',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(new Date())
  const year = Number(parts.find((part) => part.type === 'year')?.value)
  const month = Number(parts.find((part) => part.type === 'month')?.value)
  const day = Number(parts.find((part) => part.type === 'day')?.value)
  return new Date(Date.UTC(year, month - 1, day + 1)).toISOString().slice(0, 10)
}

function nextLocalId(): string {
  return globalThis.crypto?.randomUUID?.() ?? `package-${Date.now()}-${Math.random()}`
}

function pickupLocation(defaultPickup: ClientDefaultPickup | null): Location {
  if (!defaultPickup) return {}
  return {
    label: defaultPickup.label,
    destinationQuery: defaultPickup.destinationQuery,
    latitude: defaultPickup.latitude,
    longitude: defaultPickup.longitude,
  }
}

function locationConfirmed(location: Location): boolean {
  return Number.isFinite(location.latitude) && Number.isFinite(location.longitude)
}

function apiLocation(location: Location, fallbackLabel: string): ClientLocation {
  return {
    label: location.label?.trim() || location.destinationQuery?.trim() || fallbackLabel,
    query: location.destinationQuery?.trim() || undefined,
    latitude: location.latitude as number,
    longitude: location.longitude as number,
  }
}

function formatAriary(value: number): string {
  return `${value.toLocaleString('fr-MG')} Ar`
}

export function ClientDeliveryRequestForm({
  accessToken,
  defaultPickup,
  onCreated,
  onCancel,
}: {
  accessToken: string
  defaultPickup: ClientDefaultPickup | null
  onCreated: (request: DeliveryRequestRecord, savedDefaultPickup: boolean) => void
  onCancel: () => void
}) {
  const [requestedDate, setRequestedDate] = useState(tomorrow)
  const [pickup, setPickup] = useState<Location>(() => pickupLocation(defaultPickup))
  const [saveAsDefaultPickup, setSaveAsDefaultPickup] = useState(false)
  const [packages, setPackages] = useState<ClientPackageDraft[]>([])
  const [editingIndex, setEditingIndex] = useState<number | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string>()
  const [capacityError, setCapacityError] = useState<DailyCapacityExceededError>()

  const amountTotal = useMemo(() => packages.reduce(
    (sum, item) => sum + (Number.isFinite(Number(item.amountToCollectAriary)) ? Number(item.amountToCollectAriary) : 0),
    0,
  ), [packages])
  const deliveryFeeTotal = useMemo(() => packages.reduce(
    (sum, item) => sum + (item.pricingQuote?.deliveryFeeAriary ?? 0),
    0,
  ), [packages])

  function startAdding() {
    setEditingIndex(packages.length)
  }

  function savePackage(value: ClientPackageDraft) {
    setPackages((current) => {
      const existingIndex = current.findIndex((item) => item.localId === value.localId)
      if (existingIndex < 0) return [...current, value]
      return current.map((item, index) => index === existingIndex ? value : item)
    })
    setEditingIndex(null)
    setCapacityError(undefined)
    setError(undefined)
  }

  function removePackage(index: number) {
    setPackages((current) => current.filter((_, itemIndex) => itemIndex !== index))
    setCapacityError(undefined)
  }

  async function submit() {
    setError(undefined)
    if (!requestedDate) {
      setError('Choisissez la date souhaitée pour la livraison.')
      return
    }
    if (!locationConfirmed(pickup)) {
      setError('Confirmez le point de retrait sur la carte.')
      return
    }
    if (packages.length === 0) {
      setError('Ajoutez au moins un colis à la demande.')
      return
    }

    setSubmitting(true)
    try {
      const created = await createDeliveryRequest({
        requestedDate,
        pickup: apiLocation(pickup, 'Point de retrait'),
        saveAsDefaultPickup,
        packages: packages.map((item, index) => ({
          recipientName: item.recipientName,
          faritra: item.faritra,
          destination: apiLocation(item.destination, `Livraison ${index + 1}`),
          contact: item.contact,
          amountToCollectAriary: Number(item.amountToCollectAriary),
          mobileMoneyPaid: item.mobileMoneyPaid,
          ...(item.weightKg ? { weightKg: Number(item.weightKg) } : {}),
        })),
      }, accessToken)
      onCreated(created, saveAsDefaultPickup)
    } catch (submitError) {
      if (submitError instanceof DailyCapacityExceededError) {
        setCapacityError(submitError)
      } else {
        setError(submitError instanceof Error ? submitError.message : 'La demande n’a pas pu être enregistrée.')
      }
    } finally {
      setSubmitting(false)
    }
  }

  const dialogValue = editingIndex === null
    ? undefined
    : packages[editingIndex] ?? emptyClientPackage(nextLocalId())

  return (
    <section className="client-request-builder" aria-labelledby="client-request-title">
      <header className="client-page-heading">
        <div>
          <p className="eyebrow">Nouvelle demande</p>
          <h1 id="client-request-title">Préparer une livraison</h1>
          <p>Un retrait, puis autant de colis que nécessaire. Vous pourrez suivre leur traitement depuis l’historique.</p>
        </div>
        <button className="button button--secondary" type="button" onClick={onCancel}>Retour à l’historique</button>
      </header>

      <div className="client-builder-layout">
        <div className="client-builder-main">
          <section className="client-surface client-builder-section">
            <div className="client-section-heading">
              <span aria-hidden="true"><CalendarDays /></span>
              <div><p className="eyebrow">Étape 1</p><h2>Date et retrait</h2></div>
            </div>
            <div className="client-form-grid">
              <label className="client-field">
                <span>Date souhaitée</span>
                <input
                  type="date"
                  value={requestedDate}
                  min={tomorrow()}
                  onChange={(event) => {
                    setRequestedDate(event.target.value)
                    setCapacityError(undefined)
                  }}
                />
              </label>
              <label className="client-field">
                <span>Libellé du retrait</span>
                <input
                  value={pickup.label ?? ''}
                  maxLength={200}
                  placeholder="Ex. Boutique Analakely"
                  onChange={(event) => setPickup({ ...pickup, label: event.target.value })}
                />
              </label>
            </div>
            <div className="client-location-block">
              <div className="client-location-block__heading">
                <MapPin aria-hidden="true" />
                <div><strong>Point de retrait</strong><small>Ce point sera commun à tous les colis de cette demande.</small></div>
              </div>
              <LocationPicker
                accessToken={accessToken}
                value={pickup}
                title="Point de retrait"
                description="Recherchez le lieu où nos livreurs récupéreront les colis."
                onChange={(location) => {
                  setPickup({
                    ...location,
                    label: pickup.label?.trim() || location.label || location.destinationQuery || 'Point de retrait',
                  })
                  setCapacityError(undefined)
                }}
              />
            </div>
            <label className="client-check-card client-check-card--wide">
              <input
                type="checkbox"
                checked={saveAsDefaultPickup}
                onChange={(event) => setSaveAsDefaultPickup(event.target.checked)}
              />
              <span>
                <strong>Enregistrer comme retrait par défaut</strong>
                <small>Ce lieu sera proposé automatiquement lors de vos prochaines demandes.</small>
              </span>
            </label>
          </section>

          <section className="client-surface client-builder-section">
            <div className="client-section-heading client-section-heading--actions">
              <span aria-hidden="true"><PackagePlus /></span>
              <div><p className="eyebrow">Étape 2</p><h2>Colis à livrer</h2></div>
              <button className="button" type="button" onClick={startAdding}><PackagePlus aria-hidden="true" />Ajouter un colis</button>
            </div>

            {packages.length === 0 ? (
              <button className="client-empty-state" type="button" onClick={startAdding}>
                <span aria-hidden="true"><PackagePlus /></span>
                <strong>Aucun colis pour le moment</strong>
                <small>Ajoutez le premier destinataire pour continuer.</small>
              </button>
            ) : (
              <div className="client-package-list" role="list" aria-label="Colis de la demande">
                <div className="client-package-list__header" aria-hidden="true">
                  <span>Destinataire</span><span>Destination</span><span>Encaissement</span><span>Options</span><span />
                </div>
                {packages.map((item, index) => (
                  <article className="client-package-row" role="listitem" key={item.localId}>
                    <div className="client-package-row__index">{index + 1}</div>
                    <div><strong>{item.recipientName}</strong><small>{item.contact}</small></div>
                    <div><strong>{item.destination.label || item.destination.destinationQuery}</strong><small>{item.pricingQuote ? `Zone ${item.pricingQuote.pricingZoneName}` : 'Tarif à recalculer'}{item.faritra ? ' · Faritra' : ''}</small></div>
                    <div><strong>{formatAriary(Number(item.amountToCollectAriary))}</strong><small>{item.pricingQuote ? `dont ${formatAriary(item.pricingQuote.deliveryFeeAriary)} de livraison · à encaisser` : 'À encaisser'}{item.mobileMoneyPaid ? ' · Mobile Money signalé' : ''}</small></div>
                    <div className="client-package-row__tags">
                      {item.weightKg && <span>{item.weightKg} kg</span>}
                      {item.faritra && <span>Faritra</span>}
                    </div>
                    <div className="client-package-row__actions">
                      <button type="button" aria-label={`Modifier le colis ${index + 1}`} onClick={() => setEditingIndex(index)}><Pencil aria-hidden="true" /></button>
                      <button className="is-danger" type="button" aria-label={`Supprimer le colis ${index + 1}`} onClick={() => removePackage(index)}><Trash2 aria-hidden="true" /></button>
                    </div>
                  </article>
                ))}
              </div>
            )}
          </section>
        </div>

        <aside className="client-submit-card">
          <p className="eyebrow">Récapitulatif</p>
          <h2>{packages.length} colis</h2>
          <dl>
            <div><dt>Date</dt><dd>{requestedDate ? new Date(`${requestedDate}T12:00:00`).toLocaleDateString('fr-MG', { dateStyle: 'long' }) : 'À choisir'}</dd></div>
            <div><dt>Retrait</dt><dd>{pickup.label || pickup.destinationQuery || 'À confirmer'}</dd></div>
            <div><dt>Total déclaré</dt><dd>{formatAriary(amountTotal)}</dd></div>
            <div><dt>Dont frais de livraison</dt><dd>{formatAriary(deliveryFeeTotal)}</dd></div>
          </dl>

          {locationConfirmed(pickup) && packages.length > 0 && (
            <p className="client-ready"><CheckCircle2 aria-hidden="true" />La demande est prête à être envoyée.</p>
          )}
          {capacityError && (
            <div className="client-capacity-warning" role="alert">
              <AlertTriangle aria-hidden="true" />
              <div>
                <strong>Capacité de la journée dépassée</strong>
                <p>Retirez {capacityError.excess} colis de cette demande pour pouvoir l’enregistrer.</p>
                  <small>{capacityError.current} colis déjà prévus · {capacityError.remaining} place{capacityError.remaining === 1 ? '' : 's'} restante{capacityError.remaining === 1 ? '' : 's'} · limite de {capacityError.limit}.</small>
              </div>
            </div>
          )}
          {error && <p className="client-alert client-alert--error" role="alert">{error}</p>}
          <button className="button button--wide" type="button" disabled={submitting || Boolean(capacityError)} onClick={() => void submit()}>
            <Send aria-hidden="true" />{submitting ? 'Enregistrement…' : 'Envoyer la demande'}
          </button>
          <small className="client-submit-card__note">La demande sera enregistrée en attente. L’équipe AZY confirmera ensuite son traitement.</small>
        </aside>
      </div>

      {dialogValue && editingIndex !== null && (
        <ClientPackageDialog
          key={dialogValue.localId}
          accessToken={accessToken}
          initialValue={dialogValue}
          packageNumber={editingIndex + 1}
          onSave={savePackage}
          onClose={() => setEditingIndex(null)}
        />
      )}
    </section>
  )
}
