import { useEffect, useId, useState } from 'react'
import { createPortal } from 'react-dom'
import { Banknote, Check, MapPin, PackagePlus, Scale, X } from 'lucide-react'
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

  useEffect(() => {
    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => {
      document.body.style.overflow = previousOverflow
    }
  }, [])

  function save() {
    if (!value.recipientName.trim()) {
      setError('Indiquez le nom du destinataire.')
      return
    }
    if (!value.contact.trim()) {
      setError('Indiquez un contact pour la livraison.')
      return
    }
    if (!confirmed(value.destination)) {
      setError('Confirmez le lieu de livraison sur la carte.')
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
        label: value.destination.label?.trim()
          || value.destination.destinationQuery?.trim()
          || `Livraison ${value.recipientName.trim()}`,
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
              <div><strong>Lieu de livraison</strong><small>Recherchez l’adresse ou placez précisément le point sur la carte.</small></div>
            </div>
            <LocationPicker
              accessToken={accessToken}
              value={value.destination}
              title="Lieu de livraison"
              description="Recherchez une adresse, un quartier ou un point de repère à Madagascar."
              onChange={(destination) => setValue({
                ...value,
                destination: {
                  ...destination,
                  label: destination.label?.trim()
                    || destination.destinationQuery?.trim()
                    || `Livraison ${value.recipientName.trim() || packageNumber}`,
                },
              })}
            />
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
                <span><strong>Déjà payé par Mobile Money</strong><small>Aucun encaissement du prix déjà réglé</small></span>
              </label>
            </div>
          </div>

          {error && <p className="client-alert client-alert--error" role="alert">{error}</p>}
        </div>

        <footer className="client-modal-footer">
          <button className="button button--secondary" type="button" onClick={onClose}>Annuler</button>
          <button className="button" type="button" onClick={save}><Check aria-hidden="true" />Enregistrer le colis</button>
        </footer>
      </section>
    </div>,
    document.body,
  )
}
