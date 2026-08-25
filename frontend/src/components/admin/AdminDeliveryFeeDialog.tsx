import { useEffect, useState, type FormEvent } from 'react'
import { Calculator, CircleDollarSign, PencilLine, X } from 'lucide-react'
import type { DailyOperationPackage } from '../../api/admin'
import { formatAriary } from './adminHelpers'
import './AdminDeliveryFeeDialog.css'

export interface DeliveryFeeInput {
  mode: 'CALCULATED' | 'MANUAL'
  manualFeeAriary?: number | null
  reason?: string | null
}

export function AdminDeliveryFeeDialog({
  deliveryPackage,
  busy,
  error,
  onSave,
  onClose,
}: {
  deliveryPackage: DailyOperationPackage
  busy: boolean
  error?: string
  onSave: (input: DeliveryFeeInput) => void
  onClose: () => void
}) {
  const calculatedFee = deliveryPackage.calculatedDeliveryFeeAriary
    ?? deliveryPackage.deliveryFeeAriary
    ?? null
  const finalFee = deliveryPackage.finalDeliveryFeeAriary
    ?? deliveryPackage.deliveryFeeAriary
    ?? calculatedFee
  const hasManualOverride = Boolean(deliveryPackage.deliveryFeeOverrideReason)
    || (finalFee != null && calculatedFee != null && finalFee !== calculatedFee)
  const [mode, setMode] = useState<'CALCULATED' | 'MANUAL'>(hasManualOverride ? 'MANUAL' : 'CALCULATED')
  const [manualFee, setManualFee] = useState(String(finalFee ?? 0))
  const [reason, setReason] = useState(deliveryPackage.deliveryFeeOverrideReason ?? '')
  const [validationError, setValidationError] = useState<string>()

  useEffect(() => {
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !busy) onClose()
    }
    window.addEventListener('keydown', closeOnEscape)
    return () => window.removeEventListener('keydown', closeOnEscape)
  }, [busy, onClose])

  function submit(event: FormEvent) {
    event.preventDefault()
    if (mode === 'CALCULATED') {
      if (calculatedFee == null) {
        setValidationError('Aucun tarif automatique n’est disponible pour ce colis. Choisissez un tarif manuel.')
        return
      }
      onSave({ mode: 'CALCULATED', manualFeeAriary: null, reason: null })
      return
    }

    const amount = Number(manualFee)
    if (!Number.isSafeInteger(amount) || amount < 0) {
      setValidationError('Indiquez un tarif final valide, en ariary entiers.')
      return
    }
    if (!reason.trim()) {
      setValidationError('Indiquez pourquoi le tarif annoncé diffère du tarif calculé.')
      return
    }
    onSave({ mode: 'MANUAL', manualFeeAriary: amount, reason: reason.trim() })
  }

  const preview = mode === 'CALCULATED' ? calculatedFee : Number(manualFee)

  return (
    <div className="admin-modal-backdrop" role="presentation" onMouseDown={(event) => {
      if (event.target === event.currentTarget && !busy) onClose()
    }}>
      <form className="admin-fee-dialog" role="dialog" aria-modal="true" aria-labelledby="fee-dialog-title" onSubmit={submit}>
        <header>
          <div><p className="admin-eyebrow">Tarif final</p><h2 id="fee-dialog-title">Frais pour {deliveryPackage.recipientName}</h2></div>
          <button type="button" aria-label="Fermer" disabled={busy} onClick={onClose}><X aria-hidden="true" /></button>
        </header>

        <div className="admin-fee-dialog__body">
          <div className="admin-fee-summary">
            <span><CircleDollarSign aria-hidden="true" /></span>
            <div><small>Tarif utilisé pour la clôture</small><strong>{preview != null && Number.isFinite(preview) ? formatAriary(preview) : 'Non disponible'}</strong></div>
          </div>

          <fieldset className="admin-fee-choices">
            <legend>Choisissez l’une des deux valeurs</legend>
            <label className={mode === 'CALCULATED' ? 'is-selected' : undefined}>
              <input type="radio" name="fee-mode" checked={mode === 'CALCULATED'} onChange={() => { setMode('CALCULATED'); setValidationError(undefined) }} />
              <span><Calculator aria-hidden="true" /></span>
              <div><strong>Tarif calculé</strong><small>Valeur figée lors de l’enregistrement · {calculatedFee == null ? 'indisponible' : formatAriary(calculatedFee)}</small></div>
            </label>
            <label className={mode === 'MANUAL' ? 'is-selected' : undefined}>
              <input type="radio" name="fee-mode" checked={mode === 'MANUAL'} onChange={() => { setMode('MANUAL'); setValidationError(undefined) }} />
              <span><PencilLine aria-hidden="true" /></span>
              <div><strong>Écrasement manuel</strong><small>À utiliser si un autre prix a été annoncé au client.</small></div>
            </label>
          </fieldset>

          {mode === 'MANUAL' && (
            <div className="admin-fee-manual-fields">
              <label><span>Tarif final (Ar)</span><input type="number" min="0" step="1" required value={manualFee} onChange={(event) => setManualFee(event.target.value)} /></label>
              <label><span>Raison obligatoire</span><textarea rows={3} maxLength={1000} required value={reason} placeholder="Ex. Prix de 4 000 Ar annoncé au client" onChange={(event) => setReason(event.target.value)} /></label>
            </div>
          )}

          <p className="admin-fee-note">Le tarif calculé reste conservé dans l’historique. Revenir au choix « Tarif calculé » supprime uniquement l’écrasement manuel.</p>
          {(validationError || error) && <p className="admin-form-error" role="alert">{validationError ?? error}</p>}
        </div>

        <footer>
          <button className="admin-button admin-button--ghost" type="button" disabled={busy} onClick={onClose}>Annuler</button>
          <button className="admin-button" type="submit" disabled={busy}>{busy ? 'Enregistrement…' : 'Enregistrer le tarif'}</button>
        </footer>
      </form>
    </div>
  )
}
