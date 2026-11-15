import { useEffect, useId, useState } from 'react'
import { createPortal } from 'react-dom'
import { Banknote, BadgeDollarSign, Check, Clock3, LoaderCircle, MapPin, PackagePlus, Scale, TriangleAlert, X } from 'lucide-react'
import { ClientApiError, quoteDeliveryPrice, type DeliveryPricingOptions, type DeliveryPricingQuote, type DeliveryRequestKind, type DeliveryTimePreference } from '../api/client'
import type { Location } from '../api/csvImports'
import { formatPricingDistanceSummary } from '../api/pricing'
import { endOfOneHourWindow } from '../deliveryWindows'
import { LocationPicker } from './LocationPicker'

export interface ClientPackageDraft {
  localId: string
  recipientName: string
  faritra: boolean
  destination: Location
  contact: string
  amountToCollectAriary: string
  mobileMoneyPaid: boolean
  weightKg: string
  desiredDeliveryTime: string
  desiredDeliveryEndTime?: string
  deliveryTimePreference?: DeliveryTimePreference
  pricingQuote?: DeliveryPricingQuote
  importWarning?: string
  importResolvedPlace?: string
}

export function packageWindowInput(value: ClientPackageDraft) {
  const preference = value.deliveryTimePreference ?? (value.desiredDeliveryTime ? 'CUSTOM' : 'NONE')
  if (preference === 'MORNING' || preference === 'AFTERNOON') return { deliveryTimePreference: preference }
  return value.desiredDeliveryTime ? { deliveryTimePreference: 'CUSTOM' as const, desiredDeliveryTime: value.desiredDeliveryTime,
    desiredDeliveryEndTime: value.desiredDeliveryEndTime || endOfOneHourWindow(value.desiredDeliveryTime) } : {}
}

export function emptyClientPackage(localId: string): ClientPackageDraft {
  return {
    localId,
    recipientName: '',
    faritra: false,
    destination: {},
    contact: '',
    amountToCollectAriary: '0',
    mobileMoneyPaid: false,
    weightKg: '',
    desiredDeliveryTime: '',
    pricingQuote: undefined,
  }
}

function confirmed(location: Location): boolean {
  return Number.isFinite(location.latitude) && Number.isFinite(location.longitude)
}

function currentTimeInAntananarivo(): string {
  return new Intl.DateTimeFormat('fr-FR', {
    timeZone: 'Indian/Antananarivo',
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
  }).format(new Date())
}

function nextMinuteInAntananarivo(): { value: string; available: boolean } {
  const [hours, minutes] = currentTimeInAntananarivo().split(':').map(Number)
  const nextMinute = hours * 60 + minutes + 1
  if (nextMinute >= 24 * 60) return { value: '23:59', available: false }
  return {
    value: `${String(Math.floor(nextMinute / 60)).padStart(2, '0')}:${String(nextMinute % 60).padStart(2, '0')}`,
    available: true,
  }
}

export function ClientPackageDialog({
  accessToken,
  initialValue,
  packageNumber,
  requestKind = 'NORMAL',
  geocodingClientAccountId,
  quotePrice,
  onSave,
  onClose,
}: {
  accessToken: string
  initialValue: ClientPackageDraft
  packageNumber: number
  requestKind?: DeliveryRequestKind
  geocodingClientAccountId?: string
  quotePrice?: (
    destination: Parameters<typeof quoteDeliveryPrice>[0],
    signal: AbortSignal,
    options: DeliveryPricingOptions & { requestKind: DeliveryRequestKind },
  ) => Promise<DeliveryPricingQuote>
  onSave: (value: ClientPackageDraft) => void | Promise<void>
  onClose: () => void
}) {
  const titleId = useId()
  const [value, setValue] = useState<ClientPackageDraft>(initialValue)
  const [error, setError] = useState<string>()
  const [quoteBusy, setQuoteBusy] = useState(false)
  const [quoteError, setQuoteError] = useState<{ message: string; outside: boolean }>()
  const [saving, setSaving] = useState(false)
  const expressMinimum = nextMinuteInAntananarivo()
  const preference = value.deliveryTimePreference ?? (value.desiredDeliveryTime ? 'CUSTOM' : 'NONE')
  const preset = preference === 'MORNING' || preference === 'AFTERNOON'
  const desiredDeliveryEndTime = preset ? preference === 'MORNING' ? '12:00' : '23:59'
    : value.desiredDeliveryEndTime || endOfOneHourWindow(value.desiredDeliveryTime)

  useEffect(() => {
    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => {
      document.body.style.overflow = previousOverflow
    }
  }, [])

  useEffect(() => {
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !saving) onClose()
    }
    window.addEventListener('keydown', closeOnEscape)
    return () => window.removeEventListener('keydown', closeOnEscape)
  }, [onClose, saving])

  useEffect(() => {
    if (!confirmed(value.destination)) {
      setQuoteBusy(false)
      setQuoteError(undefined)
      setValue((current) => current.pricingQuote ? { ...current, pricingQuote: undefined } : current)
      return
    }

    const controller = new AbortController()
    setQuoteBusy(true)
    setQuoteError(undefined)
    const destination = {
      label: value.destination.label?.trim()
        || value.destination.destinationQuery?.trim()
        || `Livraison ${packageNumber}`,
      query: value.destination.destinationQuery?.trim() || undefined,
      latitude: value.destination.latitude as number,
      longitude: value.destination.longitude as number,
    }
    const options = {
      requestKind,
      ...(value.weightKg ? { weightKg: Number(value.weightKg) } : {}),
      ...packageWindowInput(value),
    }
    void (quotePrice
      ? quotePrice(destination, controller.signal, options)
      : quoteDeliveryPrice(destination, accessToken, controller.signal, options)).then((pricingQuote) => {
      if (!controller.signal.aborted) {
        setValue((current) => ({ ...current, pricingQuote }))
      }
    }).catch((quoteFailure: unknown) => {
      if (controller.signal.aborted) return
      setValue((current) => ({ ...current, pricingQuote: undefined }))
      setQuoteError({
        message: quoteFailure instanceof Error ? quoteFailure.message : 'Le tarif de cette destination est indisponible.',
        outside: quoteFailure instanceof ClientApiError
          && (quoteFailure.code.includes('OUTSIDE') || quoteFailure.code.includes('NOT_COVERED')),
      })
    }).finally(() => {
      if (!controller.signal.aborted) setQuoteBusy(false)
    })

    return () => controller.abort()
  }, [accessToken, packageNumber, quotePrice, requestKind, value.destination.latitude, value.destination.longitude, value.destination.label, value.destination.destinationQuery, value.weightKg, value.desiredDeliveryTime, value.desiredDeliveryEndTime, value.deliveryTimePreference])

  async function save() {
    if (saving) return
    if (!value.recipientName.trim()) {
      setError('Indiquez le nom du destinataire.')
      return
    }
    if (!value.contact.trim()) {
      setError('Indiquez un contact pour la livraison.')
      return
    }
    if (!value.destination.label?.trim()) {
      setError('Indiquez un libellé ou un repère pour la livraison.')
      return
    }
    if (!confirmed(value.destination)) {
      setError('Confirmez le lieu de livraison sur la carte.')
      return
    }
    if (quoteBusy) {
      setError('Attendez le calcul du tarif de livraison.')
      return
    }
    if (!value.pricingQuote) {
      setError(quoteError?.message ?? 'Cette destination ne se trouve dans aucune zone couverte.')
      return
    }
    const amount = Number(value.amountToCollectAriary)
    if (!Number.isInteger(amount) || amount < 0) {
      setError('Le total à récupérer doit être un montant entier positif ou nul.')
      return
    }
    if (value.weightKg && (!(Number(value.weightKg) > 0) || Number(value.weightKg) > 9_999_999.999)) {
      setError('Le poids doit être supérieur à zéro.')
      return
    }
    if (!preset && value.desiredDeliveryTime && desiredDeliveryEndTime === value.desiredDeliveryTime) {
      setError('La fin du créneau doit être différente du début.')
      return
    }
    if (requestKind === 'EXPRESS' && preference === 'MORNING' && currentTimeInAntananarivo() >= '12:00') {
      setError('La période du matin est déjà terminée.')
      return
    }
    if (requestKind === 'EXPRESS' && !preset && value.desiredDeliveryTime
      && value.desiredDeliveryTime <= currentTimeInAntananarivo()) {
      setError('L’heure souhaitée doit être postérieure à l’heure actuelle.')
      return
    }
    setSaving(true)
    try { await onSave({
      ...value,
      importWarning: undefined,
      importResolvedPlace: undefined,
      recipientName: value.recipientName.trim(),
      contact: value.contact.trim(),
      destination: {
        ...value.destination,
        label: value.destination.label.trim(),
      },
    }) } catch (saveError) {
      setError(saveError instanceof Error ? saveError.message : 'Le colis n’a pas pu être enregistré.')
    } finally { setSaving(false) }
  }

  return createPortal(
    <div className="client-modal-overlay" role="presentation" onMouseDown={(event) => {
      if (event.target === event.currentTarget && !saving) onClose()
    }}>
      <section className="client-package-dialog" role="dialog" aria-modal="true" aria-labelledby={titleId}>
        <header className="client-modal-header">
          <div className="client-modal-title">
            <span aria-hidden="true"><PackagePlus /></span>
            <div>
              <p className="eyebrow">Colis {packageNumber}</p>
              <h2 id={titleId}>{initialValue.recipientName ? 'Modifier le colis' : 'Ajouter un colis'}</h2>
            </div>
          </div>
          <button className="icon-button" type="button" aria-label="Fermer" disabled={saving} onClick={onClose}><X aria-hidden="true" /></button>
        </header>

        <fieldset className="client-package-dialog__body client-package-dialog__fields" disabled={saving}>
          <div className="client-form-grid">
            <label className="client-field">
              <span>Nom du client destinataire</span>
              <input
                autoFocus
                value={value.recipientName}
                maxLength={200}
                placeholder="Ex. Rakoto"
                onChange={(event) => setValue({ ...value, recipientName: event.target.value })}
              />
            </label>
            <label className="client-field">
              <span>Contact client</span>
              <input
                value={value.contact}
                maxLength={100}
                inputMode="tel"
                placeholder="Ex. 034 00 000 00"
                onChange={(event) => setValue({ ...value, contact: event.target.value })}
              />
            </label>
          </div>

          <div className="client-location-block">
            <div className="client-location-block__heading">
              <MapPin aria-hidden="true" />
              <div><strong>Lieu de livraison</strong><small>Donnez un repère court pour les rapports, puis confirmez sa position sur la carte.</small></div>
            </div>
            <label className="client-field client-location-label-field">
              <span>Libellé / repère de livraison</span>
              <input
                value={value.destination.label ?? ''}
                maxLength={200}
                placeholder="Ex. Bureau Ankorondrano"
                onChange={(event) => setValue({
                  ...value,
                  destination: { ...value.destination, label: event.target.value },
                })}
              />
              <small>Ce libellé court sera affiché dans les listes et les rapports à la place de l’adresse complète.</small>
            </label>
            <LocationPicker
              accessToken={accessToken}
              value={value.destination}
              title="Lieu de livraison"
              description="Recherchez une adresse, un quartier ou un point de repère à Madagascar."
              searchOptions={{
                purpose: 'DESTINATION',
                requestKind,
                ...(geocodingClientAccountId ? { clientAccountId: geocodingClientAccountId } : {}),
              }}
              onChange={(destination) => setValue({
                ...value,
                pricingQuote: undefined,
                destination: {
                  ...destination,
                  label: value.destination.label ?? '',
                },
              })}
            />
            {quoteBusy && (
              <div className="client-pricing-preview is-loading" role="status">
                <LoaderCircle className="spin" aria-hidden="true" />
                <div><strong>Calcul du tarif…</strong><small>La destination est comparée aux zones couvertes.</small></div>
              </div>
            )}
            {!quoteBusy && value.pricingQuote && (
              <div className="client-pricing-preview is-covered" role="status">
                <BadgeDollarSign aria-hidden="true" />
                <div>
                  <small>Zone {value.pricingQuote.pricingZoneName}{value.pricingQuote.timeSlotLabel ? ` · ${value.pricingQuote.timeSlotLabel}` : ''}</small>
                  <strong>{value.pricingQuote.deliveryFeeAriary.toLocaleString('fr-MG')} Ar de livraison</strong>
                  <small>{formatPricingDistanceSummary(value.pricingQuote.distanceFromCenterMeters, value.pricingQuote.distanceMode)}</small>
                  {value.pricingQuote.finalFeeOverridden ? <small>Tarif convenu conservé</small> : (value.pricingQuote.weightSurchargeAriary || value.pricingQuote.timeSurchargeAriary) ? (
                    <small>Base {(value.pricingQuote.baseDeliveryFeeAriary ?? 0).toLocaleString('fr-MG')} Ar
                      {value.pricingQuote.weightSurchargeAriary ? ` · poids +${value.pricingQuote.weightSurchargeAriary.toLocaleString('fr-MG')} Ar` : ''}
                      {value.pricingQuote.timeSurchargeAriary ? ` · créneau +${value.pricingQuote.timeSurchargeAriary.toLocaleString('fr-MG')} Ar` : ''}
                    </small>
                  ) : null}
                </div>
              </div>
            )}
            {!quoteBusy && quoteError && (
              <div className="client-pricing-preview is-uncovered" role="alert">
                <TriangleAlert aria-hidden="true" />
                <div><strong>{quoteError.outside ? 'Zone non couverte' : 'Tarif indisponible'}</strong><small>{quoteError.message}</small></div>
              </div>
            )}
          </div>

          <div className="client-form-grid client-form-grid--three">
            <label className="client-field client-field--icon">
              <span>Total à récupérer (Ar)</span>
              <div><Banknote aria-hidden="true" /><input
                value={value.amountToCollectAriary}
                type="number"
                min="0"
                step="1"
                inputMode="numeric"
                onChange={(event) => setValue({ ...value, amountToCollectAriary: event.target.value })}
              /></div>
            </label>
            <div
              className="client-field client-field--time-window"
              role="group"
              aria-labelledby={`${titleId}-delivery-window-label`}
            >
              <span id={`${titleId}-delivery-window-label`}>Créneau de livraison · facultatif</span>
              <div className="client-delivery-periods" role="group" aria-label="Choix du créneau">
                {(['CUSTOM', 'MORNING', 'AFTERNOON'] as const).map((mode) => <button key={mode} type="button"
                  aria-pressed={mode === 'CUSTOM' ? !preset : preference === mode}
                  disabled={requestKind === 'EXPRESS' && mode === 'MORNING' && currentTimeInAntananarivo() >= '12:00'}
                  onClick={() => setValue({ ...value, deliveryTimePreference: mode, desiredDeliveryTime: '', desiredDeliveryEndTime: '' })}>
                  {mode === 'CUSTOM' ? 'Heures précises' : mode === 'MORNING' ? 'Matin' : 'Soir'}</button>)}
              </div>
              <div className="client-time-window">
                <span><Clock3 aria-hidden="true" /><input
                  aria-label="Début du créneau"
                  value={preset ? preference === 'MORNING' ? '00:00' : '12:00' : value.desiredDeliveryTime}
                  type="time"
                  min={requestKind === 'EXPRESS' ? expressMinimum.value : '00:00'}
                  disabled={preset || (requestKind === 'EXPRESS' && !expressMinimum.available)}
                  onChange={(event) => setValue({ ...value, deliveryTimePreference: event.target.value ? 'CUSTOM' : 'NONE', desiredDeliveryTime: event.target.value,
                    desiredDeliveryEndTime: value.desiredDeliveryEndTime || endOfOneHourWindow(event.target.value) })}
                /></span>
                <i aria-hidden="true">à</i>
                <span><Clock3 aria-hidden="true" /><input
                  aria-label="Fin du créneau"
                  value={desiredDeliveryEndTime}
                  type="time"
                  disabled={preset}
                  onChange={(event) => setValue({ ...value, desiredDeliveryEndTime: event.target.value })}
                /></span>
              </div>
              <small>{preset ? preference === 'MORNING' ? 'Livraison avant midi.' : 'Livraison après midi.' : requestKind === 'EXPRESS'
                ? expressMinimum.available
                  ? 'Le début doit être postérieur à maintenant.'
                  : 'Aucun nouveau créneau ne peut commencer avant minuit.'
                : 'Choisissez librement le début et la fin, ou laissez les heures vides.'}{!preset && value.desiredDeliveryTime && desiredDeliveryEndTime < value.desiredDeliveryTime ? ' La fin se situe le lendemain.' : ''}</small>
            </div>
            <label className="client-field client-field--icon">
              <span>Poids (kg) · facultatif</span>
              <div><Scale aria-hidden="true" /><input
                value={value.weightKg}
                type="number"
                min="0.001"
                step="0.1"
                inputMode="decimal"
                placeholder="Valeur par défaut"
                onChange={(event) => setValue({ ...value, weightKg: event.target.value })}
              /></div>
            </label>
            <div className="client-choice-stack">
              <label className="client-check-card">
                <input type="checkbox" checked={value.faritra} onChange={(event) => setValue({ ...value, faritra: event.target.checked })} />
                <span><strong>Livraison Faritra</strong><small>Marquer ce colis comme livraison Faritra</small></span>
              </label>
              <label className="client-check-card">
                <input type="checkbox" checked={value.mobileMoneyPaid} onChange={(event) => setValue({ ...value, mobileMoneyPaid: event.target.checked })} />
                <span><strong>Mobile Money signalé</strong><small>Information uniquement · le montant à récupérer reste inchangé</small></span>
              </label>
            </div>
          </div>

          {error && <p className="client-alert client-alert--error" role="alert">{error}</p>}
        </fieldset>

        <footer className="client-modal-footer">
          <button className="button button--secondary" type="button" disabled={saving} onClick={onClose}>Annuler</button>
          <button className="button" type="button" disabled={saving || quoteBusy || (confirmed(value.destination) && !value.pricingQuote)} onClick={() => void save()}>{saving ? <LoaderCircle className="spin" aria-hidden="true" /> : <Check aria-hidden="true" />}{saving ? 'Enregistrement…' : 'Enregistrer le colis'}</button>
        </footer>
      </section>
    </div>,
    document.body,
  )
}
