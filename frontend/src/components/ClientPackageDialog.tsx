import { useEffect, useId, useState } from 'react'
import { createPortal } from 'react-dom'
import { Banknote, BadgeDollarSign, Check, LoaderCircle, MapPin, PackagePlus, Scale, TriangleAlert, X } from 'lucide-react'
import { ClientApiError, quoteDeliveryPrice, type DeliveryPricingQuote } from '../api/client'
import type { Location } from '../api/csvImports'
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
  pricingQuote?: DeliveryPricingQuote
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
    pricingQuote: undefined,
  }
}

function confirmed(location: Location): boolean {
  return Number.isFinite(location.latitude) && Number.isFinite(location.longitude)
}

export function ClientPackageDialog({
  accessToken,
  initialValue,
  packageNumber,
  onSave,
  onClose,
}: {
  accessToken: string
  initialValue: ClientPackageDraft
  packageNumber: number
  onSave: (value: ClientPackageDraft) => void
  onClose: () => void
}) {
  const titleId = useId()
  const [value, setValue] = useState<ClientPackageDraft>(initialValue)
  const [error, setError] = useState<string>()
  const [quoteBusy, setQuoteBusy] = useState(false)
  const [quoteError, setQuoteError] = useState<{ message: string; outside: boolean }>()

  useEffect(() => {
    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => {
      document.body.style.overflow = previousOverflow
    }
  }, [])

  useEffect(() => {
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', closeOnEscape)
    return () => window.removeEventListener('keydown', closeOnEscape)
  }, [onClose])

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
    void quoteDeliveryPrice({
      label: value.destination.label?.trim()
        || value.destination.destinationQuery?.trim()
        || `Livraison ${packageNumber}`,
      query: value.destination.destinationQuery?.trim() || undefined,
      latitude: value.destination.latitude as number,
      longitude: value.destination.longitude as number,
    }, accessToken, controller.signal).then((pricingQuote) => {
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
  }, [accessToken, packageNumber, value.destination.latitude, value.destination.longitude])

  function save() {
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
    onSave({
      ...value,
      recipientName: value.recipientName.trim(),
      contact: value.contact.trim(),
      destination: {
        ...value.destination,
        label: value.destination.label.trim(),
      },
    })
  }

  return createPortal(
    <div className="client-modal-overlay" role="presentation" onMouseDown={(event) => {
      if (event.target === event.currentTarget) onClose()
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
          <button className="icon-button" type="button" aria-label="Fermer" onClick={onClose}><X aria-hidden="true" /></button>
        </header>

        <div className="client-package-dialog__body">
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
                <div><small>Zone {value.pricingQuote.pricingZoneName}</small><strong>{value.pricingQuote.deliveryFeeAriary.toLocaleString('fr-MG')} Ar de livraison</strong></div>
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
        </div>

        <footer className="client-modal-footer">
          <button className="button button--secondary" type="button" onClick={onClose}>Annuler</button>
          <button className="button" type="button" disabled={quoteBusy || (confirmed(value.destination) && !value.pricingQuote)} onClick={save}><Check aria-hidden="true" />Enregistrer le colis</button>
        </footer>
      </section>
    </div>,
    document.body,
  )
}
