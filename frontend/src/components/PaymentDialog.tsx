import { type FormEvent, useEffect, useMemo, useState } from 'react'
import { Banknote, CalendarClock, Save, WalletCards, X } from 'lucide-react'
import type { ClientPaymentDirection, CreatePaymentInput, PaymentDestination } from '../api/admin'
import { PaymentProofField } from './PaymentProofField'
import './PaymentDialog.css'

interface PaymentDialogProps {
  title: string
  description: string
  recipientLabel: string
  defaultAmountAriary: number
  defaultDestination?: PaymentDestination | null
  defaultClientDirection?: ClientPaymentDirection
  busy?: boolean
  error?: string
  onClose: () => void
  onConfirm: (input: CreatePaymentInput, proof: File | null, clientDirection?: ClientPaymentDirection) => void
}

function localDateTimeValue(): string {
  const now = new Date()
  const local = new Date(now.getTime() - now.getTimezoneOffset() * 60_000)
  return local.toISOString().slice(0, 16)
}

function ariary(value: number): string {
  return `${new Intl.NumberFormat('fr-FR').format(value)} Ar`
}

export function PaymentDialog({
  title,
  description,
  recipientLabel,
  defaultAmountAriary,
  defaultDestination,
  defaultClientDirection,
  busy = false,
  error,
  onClose,
  onConfirm,
}: PaymentDialogProps) {
  const [amount, setAmount] = useState(String(Math.max(0, defaultAmountAriary)))
  const [paymentMethod, setPaymentMethod] = useState(defaultDestination?.operator ?? 'Espèces')
  const [confirmedAt, setConfirmedAt] = useState(localDateTimeValue)
  const [operator, setOperator] = useState(defaultDestination?.operator ?? '')
  const [accountHolder, setAccountHolder] = useState(defaultDestination?.accountHolder ?? '')
  const [number, setNumber] = useState(defaultDestination?.number ?? '')
  const [transactionReference, setTransactionReference] = useState('')
  const [note, setNote] = useState('')
  const [proof, setProof] = useState<File | null>(null)
  const [clientDirection, setClientDirection] = useState(defaultClientDirection)
  const [localError, setLocalError] = useState<string>()
  const destinationStarted = useMemo(
    () => Boolean(operator.trim() || accountHolder.trim() || number.trim()),
    [accountHolder, number, operator],
  )

  useEffect(() => {
    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !busy) onClose()
    }
    window.addEventListener('keydown', closeOnEscape)
    return () => {
      document.body.style.overflow = previousOverflow
      window.removeEventListener('keydown', closeOnEscape)
    }
  }, [busy, onClose])

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setLocalError(undefined)
    const parsedAmount = Number(amount)
    if (!Number.isSafeInteger(parsedAmount) || parsedAmount <= 0) {
      setLocalError('Indiquez un montant entier et positif en ariary.')
      return
    }
    if (!paymentMethod.trim()) {
      setLocalError('Indiquez la méthode de versement.')
      return
    }
    if (destinationStarted && (!operator.trim() || !accountHolder.trim() || !number.trim())) {
      setLocalError('Complétez les trois informations de destination ou laissez-les toutes vides.')
      return
    }
    onConfirm({
      amountAriary: parsedAmount,
      paymentMethod: paymentMethod.trim(),
      destination: destinationStarted ? {
        operator: operator.trim(),
        accountHolder: accountHolder.trim(),
        number: number.trim(),
      } : null,
      transactionReference: transactionReference.trim() || null,
      note: note.trim() || null,
      confirmedAt: confirmedAt ? new Date(confirmedAt).toISOString() : null,
    }, proof, clientDirection)
  }

  return (
    <div className="payment-dialog-backdrop" role="presentation" onMouseDown={(event) => {
      if (event.target === event.currentTarget && !busy) onClose()
    }}>
      <section className="payment-dialog" role="dialog" aria-modal="true" aria-labelledby="payment-dialog-title">
        <header>
          <div><span><WalletCards aria-hidden="true" /></span><div><small>Confirmation financière</small><h2 id="payment-dialog-title">{title}</h2><p>{description}</p></div></div>
          <button type="button" aria-label="Fermer" disabled={busy} onClick={onClose}><X aria-hidden="true" /></button>
        </header>
        <form onSubmit={submit}>
          <div className="payment-dialog__summary"><Banknote aria-hidden="true" /><div><small>Bénéficiaire</small><strong>{recipientLabel}</strong><span>Proposition : {ariary(defaultAmountAriary)}</span></div></div>
          {clientDirection && (
            <fieldset className="payment-dialog__direction">
              <legend>Sens du versement</legend>
              <div>
                <label className={clientDirection === 'AZY_TO_CLIENT' ? 'is-selected' : undefined}>
                  <input type="radio" name="client-payment-direction" value="AZY_TO_CLIENT" checked={clientDirection === 'AZY_TO_CLIENT'} onChange={() => setClientDirection('AZY_TO_CLIENT')} />
                  <span><strong>AZY → client</strong><small>AZY verse le montant au client.</small></span>
                </label>
                <label className={clientDirection === 'CLIENT_TO_AZY' ? 'is-selected' : undefined}>
                  <input type="radio" name="client-payment-direction" value="CLIENT_TO_AZY" checked={clientDirection === 'CLIENT_TO_AZY'} onChange={() => setClientDirection('CLIENT_TO_AZY')} />
                  <span><strong>Client → AZY</strong><small>Le client règle un montant dû à AZY.</small></span>
                </label>
              </div>
            </fieldset>
          )}
          <div className="payment-dialog__grid">
            <label><span>Montant confirmé</span><div className="payment-dialog__amount"><input required type="number" min="1" step="1" inputMode="numeric" value={amount} onChange={(event) => setAmount(event.target.value)} /><b>Ar</b></div></label>
            <label><span>Date et heure réelles</span><div className="payment-dialog__with-icon"><CalendarClock aria-hidden="true" /><input required type="datetime-local" value={confirmedAt} onChange={(event) => setConfirmedAt(event.target.value)} /></div></label>
            <label><span>Méthode</span><input required maxLength={80} list="payment-methods" value={paymentMethod} onChange={(event) => setPaymentMethod(event.target.value)} /><datalist id="payment-methods"><option value="MVola" /><option value="Orange Money" /><option value="Airtel Money" /><option value="Virement bancaire" /><option value="Espèces" /></datalist></label>
            <label><span>Référence <small>facultatif</small></span><input maxLength={200} value={transactionReference} onChange={(event) => setTransactionReference(event.target.value)} placeholder="Identifiant de transaction" /></label>
          </div>
          <fieldset className="payment-dialog__destination">
            <legend>Destination utilisée <small>facultatif pour les espèces</small></legend>
            <div className="payment-dialog__grid payment-dialog__grid--three">
              <label><span>Opérateur</span><input maxLength={80} value={operator} onChange={(event) => setOperator(event.target.value)} /></label>
              <label><span>Titulaire</span><input maxLength={150} value={accountHolder} onChange={(event) => setAccountHolder(event.target.value)} /></label>
              <label><span>Numéro</span><input maxLength={100} inputMode="tel" value={number} onChange={(event) => setNumber(event.target.value)} /></label>
            </div>
          </fieldset>
          <label className="payment-dialog__note"><span>Note <small>facultatif</small></span><textarea rows={3} maxLength={1000} value={note} onChange={(event) => setNote(event.target.value)} /></label>
          <PaymentProofField value={proof} onChange={setProof} disabled={busy} error={error} />
          {(localError || error) && <p className="payment-dialog__error" role="alert">{localError ?? error}</p>}
          <footer><button className="admin-button admin-button--ghost" type="button" disabled={busy} onClick={onClose}>Annuler</button><button className="admin-button" type="submit" disabled={busy}><Save aria-hidden="true" />{busy ? 'Confirmation…' : 'Confirmer le versement'}</button></footer>
        </form>
      </section>
    </div>
  )
}
