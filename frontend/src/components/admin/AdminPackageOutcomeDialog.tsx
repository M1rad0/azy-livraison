import { useEffect, useState, type FormEvent } from 'react'
import { CheckCircle2, ClockArrowUp, PackageX, X } from 'lucide-react'
import type { Courier, DailyOperationPackage, DeliveryPackageOutcome } from '../../api/admin'
import { formatAriary } from './adminHelpers'

type EditableOutcome = Exclude<DeliveryPackageOutcome, 'CANCELLED'>

const choices: Array<{
  value: EditableOutcome
  label: string
  detail: string
  icon: typeof CheckCircle2
}> = [
  { value: 'DELIVERED', label: 'Livré', detail: 'Le montant réellement récupéré sera comptabilisé.', icon: CheckCircle2 },
  { value: 'POSTPONED', label: 'Reporté', detail: 'Aucun frais ni encaissement pour cette journée.', icon: ClockArrowUp },
  { value: 'RETURN_TO_SENDER', label: 'À retourner', detail: 'Les frais restent dus, même si aucun montant n’a été récupéré.', icon: PackageX },
]

export interface PackageOutcomeInput {
  outcome: EditableOutcome
  actualCollectedAriary?: number | null
  actualCourierId?: string | null
  note?: string | null
}

export function AdminPackageOutcomeDialog({
  deliveryPackage,
  couriers,
  busy,
  error,
  onSave,
  onClose,
}: {
  deliveryPackage: DailyOperationPackage
  couriers: Courier[]
  busy: boolean
  error?: string
  onSave: (input: PackageOutcomeInput) => void
  onClose: () => void
}) {
  const initialOutcome = deliveryPackage.finalOutcome && deliveryPackage.finalOutcome !== 'CANCELLED'
    ? deliveryPackage.finalOutcome
    : 'DELIVERED'
  const [outcome, setOutcome] = useState<EditableOutcome>(initialOutcome)
  const [actualAmount, setActualAmount] = useState(() => String(
    deliveryPackage.actualCollectedAriary
      ?? (initialOutcome === 'RETURN_TO_SENDER' ? 0 : deliveryPackage.amountToCollectAriary),
  ))
  const [actualCourierId, setActualCourierId] = useState(
    deliveryPackage.actualCourierId ?? deliveryPackage.distributorCourierId ?? '',
  )
  const [note, setNote] = useState(deliveryPackage.outcomeNote ?? '')
  const [validationError, setValidationError] = useState<string>()
  const courierHelpId = `actual-courier-help-${deliveryPackage.packageId}`
  const selectedCourierIsMissing = actualCourierId !== ''
    && !couriers.some((courier) => courier.id === actualCourierId)
  const missingCourierName = actualCourierId === deliveryPackage.actualCourierId
    ? deliveryPackage.actualCourierName
    : deliveryPackage.distributorCourierName

  useEffect(() => {
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !busy) onClose()
    }
    window.addEventListener('keydown', closeOnEscape)
    return () => window.removeEventListener('keydown', closeOnEscape)
  }, [busy, onClose])

  function choose(next: EditableOutcome) {
    setOutcome(next)
    setValidationError(undefined)
    if (next === 'RETURN_TO_SENDER' && deliveryPackage.actualCollectedAriary == null) setActualAmount('0')
    if (next === 'DELIVERED' && deliveryPackage.actualCollectedAriary == null) {
      setActualAmount(String(deliveryPackage.amountToCollectAriary))
    }
  }

  function submit(event: FormEvent) {
    event.preventDefault()
    const needsAmount = outcome === 'DELIVERED' || outcome === 'RETURN_TO_SENDER'
    const parsedAmount = Number(actualAmount)
    if (needsAmount && (!Number.isSafeInteger(parsedAmount) || parsedAmount < 0)) {
      setValidationError('Indiquez un montant réellement récupéré valide, en ariary entiers.')
      return
    }
    if (needsAmount && !actualCourierId) {
      setValidationError('Choisissez le livreur réellement intervenu.')
      return
    }
    onSave({
      outcome,
      actualCollectedAriary: needsAmount ? parsedAmount : null,
      actualCourierId: needsAmount ? actualCourierId : null,
      note: note.trim() || null,
    })
  }

  const projectedFee = outcome === 'POSTPONED' ? 0 : (deliveryPackage.deliveryFeeAriary ?? 0)
  const projectedRecovered = outcome === 'POSTPONED' ? 0 : (Number(actualAmount) || 0)

  return (
    <div className="admin-modal-backdrop" role="presentation" onMouseDown={(event) => {
      if (event.target === event.currentTarget && !busy) onClose()
    }}>
      <form className="admin-outcome-dialog" role="dialog" aria-modal="true" aria-labelledby="outcome-dialog-title" onSubmit={submit}>
        <header>
          <div><p className="admin-eyebrow">Compte rendu</p><h2 id="outcome-dialog-title">Situation du colis de {deliveryPackage.recipientName}</h2></div>
          <button type="button" aria-label="Fermer" disabled={busy} onClick={onClose}><X aria-hidden="true" /></button>
        </header>

        <div className="admin-outcome-dialog__body">
          <div className="admin-outcome-package-summary">
            <div><small>Compte client</small><strong>{deliveryPackage.ownerDisplayName}</strong></div>
            <div><small>Montant prévu</small><strong>{formatAriary(deliveryPackage.amountToCollectAriary)}</strong></div>
            <div><small>Frais de livraison</small><strong>{formatAriary(deliveryPackage.deliveryFeeAriary ?? 0)}</strong></div>
          </div>

          <fieldset className="admin-outcome-choices">
            <legend>État final du colis</legend>
            {choices.map(({ value, label, detail, icon: Icon }) => (
              <label className={outcome === value ? 'is-selected' : undefined} key={value}>
                <input type="radio" name="outcome" value={value} checked={outcome === value} onChange={() => choose(value)} />
                <span><Icon aria-hidden="true" /></span>
                <div><strong>{label}</strong><small>{detail}</small></div>
              </label>
            ))}
          </fieldset>

          {outcome !== 'POSTPONED' && (
            <label className="admin-outcome-field">
              <span>Livreur réellement intervenu</span>
              <select
                aria-label="Livreur réellement intervenu"
                aria-describedby={courierHelpId}
                aria-required="true"
                value={actualCourierId}
                onChange={(event) => {
                  setActualCourierId(event.target.value)
                  setValidationError(undefined)
                }}
              >
                <option value="">Choisir un livreur</option>
                {selectedCourierIsMissing && (
                  <option value={actualCourierId}>
                    {missingCourierName ?? 'Livreur enregistré'} (fiche indisponible)
                  </option>
                )}
                {[...couriers]
                  .sort((left, right) => Number(right.active) - Number(left.active)
                    || left.name.localeCompare(right.name, 'fr'))
                  .map((courier) => (
                    <option value={courier.id} key={courier.id}>
                      {courier.name}{courier.active ? '' : ' (inactif)'}
                    </option>
                  ))}
              </select>
              <small id={courierHelpId}>
                Prévu lors du dispatch : {deliveryPackage.distributorCourierName ?? 'aucun livreur affecté'}.
                Modifiez ce choix si une autre personne a réellement effectué la livraison ou la tentative.
              </small>
            </label>
          )}

          {outcome !== 'POSTPONED' && (
            <label className="admin-outcome-field">
              <span>Montant réellement récupéré (Ar)</span>
              <input type="number" min="0" step="1" required value={actualAmount} onChange={(event) => setActualAmount(event.target.value)} />
              {deliveryPackage.mobileMoneyPaid && <small>Le signalement Mobile Money reste informatif et ne modifie pas ce montant.</small>}
            </label>
          )}

          <label className="admin-outcome-field">
            <span>Note facultative</span>
            <textarea rows={3} maxLength={1000} value={note} placeholder="Précision communiquée par le livreur…" onChange={(event) => setNote(event.target.value)} />
          </label>

          <div className="admin-outcome-projection">
            <div><small>Récupéré</small><strong>{formatAriary(projectedRecovered)}</strong></div>
            <span>−</span>
            <div><small>Frais</small><strong>{formatAriary(projectedFee)}</strong></div>
            <span>=</span>
            <div><small>À reverser</small><strong>{formatAriary(projectedRecovered - projectedFee)}</strong></div>
          </div>

          {(validationError || error) && <p className="admin-form-error" role="alert">{validationError ?? error}</p>}
        </div>

        <footer>
          <button className="admin-button admin-button--ghost" type="button" disabled={busy} onClick={onClose}>Annuler</button>
          <button className="admin-button" type="submit" disabled={busy}>{busy ? 'Enregistrement…' : 'Enregistrer cet état'}</button>
        </footer>
      </form>
    </div>
  )
}
