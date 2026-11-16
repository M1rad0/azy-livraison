import { useEffect, useState } from 'react'
import {
  Phone,
  Save,
  WalletCards,
} from 'lucide-react'
import {
  updateClientContactNumbers,
  updateClientPayoutDestinations,
  type ClientContactNumberInput,
  type ClientPayoutDestinationInput,
  type ClientProfile,
} from '../api/client'
import './ClientContactPreferences.css'
import { ClientPayoutDestinationsEditor } from './ClientPayoutDestinationsEditor'
import { ClientContactNumbersEditor } from './ClientContactNumbersEditor'
import { isValidPhoneNumber, normalizePhoneNumber, PHONE_NUMBER_ERROR } from '../phone'
import { normalizePayoutOperator, PAYOUT_OPERATOR_ERROR } from '../payoutOperators'

interface DraftContact extends ClientContactNumberInput {
  key: string
}

interface DraftPayout extends ClientPayoutDestinationInput {
  key: string
}

export type ClientContactPreferencesSection = 'contacts' | 'payouts'

function draftKey(prefix: string) {
  return globalThis.crypto?.randomUUID?.() ?? `${prefix}-${Date.now()}-${Math.random()}`
}

function normalizePriorities<T extends { priority: number }>(items: T[]): T[] {
  return items.map((item, index) => ({ ...item, priority: index + 1 }))
}

function contactsFrom(profile: ClientProfile): DraftContact[] {
  const source = profile.contactNumbers?.length
    ? profile.contactNumbers
    : profile.phone
      ? [{ id: undefined, label: 'Principal', number: profile.phone, priority: 1, active: true }]
      : []
  return source.map((item) => ({ ...item, key: item.id ?? draftKey('contact') }))
}

function payoutsFrom(profile: ClientProfile): DraftPayout[] {
  return (profile.payoutDestinations ?? []).map((item) => ({
    ...item,
    key: item.id ?? draftKey('payout'),
  }))
}

export function ClientContactPreferences({
  accessToken,
  profile,
  onSaved,
  section,
  disabled = false,
  onBusyChange,
}: {
  accessToken: string
  profile: ClientProfile
  onSaved: (profile: ClientProfile) => void
  section: ClientContactPreferencesSection
  disabled?: boolean
  onBusyChange?: (busy: boolean) => void
}) {
  const [contacts, setContacts] = useState<DraftContact[]>(() => contactsFrom(profile))
  const [payouts, setPayouts] = useState<DraftPayout[]>(() => payoutsFrom(profile))
  const [contactsDirty, setContactsDirty] = useState(false)
  const [payoutsDirty, setPayoutsDirty] = useState(false)
  const [busy, setBusy] = useState<'contacts' | 'payouts'>()
  const [message, setMessage] = useState<string>()
  const [error, setError] = useState<string>()
  const contactsLocked = disabled || busy === 'contacts'
  const payoutsLocked = disabled || busy === 'payouts'

  useEffect(() => {
    if (section === 'contacts' && !contactsDirty) setContacts(contactsFrom(profile))
    if (section === 'payouts' && !payoutsDirty) setPayouts(payoutsFrom(profile))
  }, [contactsDirty, payoutsDirty, profile, section])

  useEffect(() => {
    setMessage(undefined)
    setError(undefined)
  }, [section])

  function validateNumbers(items: Array<{ number: string }>, label: string): string | undefined {
    if (items.some((item) => !item.number.trim())) return `Chaque ${label} doit contenir un numéro.`
    const normalized = items.map((item) => normalizePhoneNumber(item.number).toLocaleLowerCase('fr'))
    if (new Set(normalized).size !== normalized.length) return `Un même numéro ne peut pas apparaître deux fois dans les ${label}s.`
    return undefined
  }

  function editContacts(update: (current: DraftContact[]) => DraftContact[]) {
    setContacts(update)
    setContactsDirty(true)
  }

  function editPayouts(update: (current: DraftPayout[]) => DraftPayout[]) {
    setPayouts(update)
    setPayoutsDirty(true)
  }

  async function saveContacts() {
    if (disabled) return
    if (contacts.length === 0) {
      setError('Conservez au moins un numéro de contact.')
      return
    }
    if (!contacts.some((item) => item.active)) {
      setError('Activez au moins un numéro de contact.')
      return
    }
    const validation = validateNumbers(contacts, 'contact')
    if (validation) {
      setError(validation)
      return
    }
    if (contacts.some((item) => !isValidPhoneNumber(item.number))) {
      setError(PHONE_NUMBER_ERROR)
      return
    }
    setBusy('contacts')
    onBusyChange?.(true)
    setError(undefined)
    setMessage(undefined)
    try {
      const saved = await updateClientContactNumbers(profile.version, normalizePriorities(contacts).map(({ key: _key, ...item }) => ({
        ...item,
        label: item.label?.trim() || null,
        number: normalizePhoneNumber(item.number),
      })), accessToken)
      setContacts(contactsFrom(saved))
      setContactsDirty(false)
      onSaved(saved)
      setMessage('L’ordre des numéros de contact a été enregistré.')
    } catch (saveError) {
      setError(saveError instanceof Error ? saveError.message : 'Les numéros de contact n’ont pas pu être enregistrés.')
    } finally {
      setBusy(undefined)
      onBusyChange?.(false)
    }
  }

  async function savePayouts() {
    if (disabled) return
    const validation = validateNumbers(payouts, 'versement')
    if (validation) {
      setError(validation)
      return
    }
    if (payouts.some((item) => !item.operator.trim() || !item.accountHolder.trim())) {
      setError('Indiquez l’opérateur et le titulaire de chaque numéro de versement.')
      return
    }
    if (payouts.some((item) => !normalizePayoutOperator(item.operator))) {
      setError(PAYOUT_OPERATOR_ERROR)
      return
    }
    if (payouts.some((item) => !isValidPhoneNumber(item.number))) {
      setError(PHONE_NUMBER_ERROR)
      return
    }
    setBusy('payouts')
    onBusyChange?.(true)
    setError(undefined)
    setMessage(undefined)
    try {
      const saved = await updateClientPayoutDestinations(profile.version, normalizePriorities(payouts).map(({ key: _key, ...item }) => ({
        ...item,
        operator: normalizePayoutOperator(item.operator),
        accountHolder: item.accountHolder.trim(),
        number: normalizePhoneNumber(item.number),
      })), accessToken)
      setPayouts(payoutsFrom(saved))
      setPayoutsDirty(false)
      onSaved(saved)
      setMessage('L’ordre des numéros de versement a été enregistré.')
    } catch (saveError) {
      setError(saveError instanceof Error ? saveError.message : 'Les numéros de versement n’ont pas pu être enregistrés.')
    } finally {
      setBusy(undefined)
      onBusyChange?.(false)
    }
  }

  return (
    <div className="client-preferences-grid">
      {section === 'contacts' && <section className="client-surface client-priority-card client-priority-card--contacts" aria-labelledby="client-contacts-title" aria-busy={busy === 'contacts'}>
        <header className="client-priority-card__heading">
          <span aria-hidden="true"><Phone /></span>
          <div><p className="eyebrow">Pour vous joindre</p><h2 id="client-contacts-title">Numéros de contact</h2><p>La priorité 1 est utilisée en premier. Les numéros suivants servent d’alternatives, dans l’ordre.</p></div>
        </header>
        <ClientContactNumbersEditor items={contacts} disabled={contactsLocked} onChange={(items) => editContacts(() => items)} saveAction={<button className="button" type="button" disabled={contactsLocked} onClick={() => void saveContacts()}><Save aria-hidden="true" />{busy === 'contacts' ? 'Enregistrement…' : 'Enregistrer les contacts'}</button>} />
      </section>}

      {section === 'payouts' && <section className="client-surface client-priority-card client-priority-card--payouts" aria-labelledby="client-payouts-title" aria-busy={busy === 'payouts'}>
        <header className="client-priority-card__heading">
          <span aria-hidden="true"><WalletCards /></span>
          <div><p className="eyebrow">Pour vos reversements</p><h2 id="client-payouts-title">Numéros de versement</h2><p>La priorité indique l’ordre suggéré. AZY confirme toujours le numéro réellement utilisé avant un versement.</p></div>
        </header>
        <ClientPayoutDestinationsEditor items={payouts} disabled={payoutsLocked} onChange={(items) => editPayouts(() => items)} saveAction={<button className="button" type="button" disabled={payoutsLocked} onClick={() => void savePayouts()}><Save aria-hidden="true" />{busy === 'payouts' ? 'Enregistrement…' : 'Enregistrer les versements'}</button>} />
      </section>}

      {message && <p className="client-alert client-alert--success client-preferences-feedback" role="status">{message}</p>}
      {error && <p className="client-alert client-alert--error client-preferences-feedback" role="alert">{error}</p>}
    </div>
  )
}
