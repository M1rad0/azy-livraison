import { useEffect, useState } from 'react'
import {
  ArrowDown,
  ArrowUp,
  Phone,
  Plus,
  Save,
  Trash2,
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

interface DraftContact extends ClientContactNumberInput {
  key: string
}

interface DraftPayout extends ClientPayoutDestinationInput {
  key: string
}

function draftKey(prefix: string) {
  return globalThis.crypto?.randomUUID?.() ?? `${prefix}-${Date.now()}-${Math.random()}`
}

function normalizePriorities<T extends { priority: number }>(items: T[]): T[] {
  return items.map((item, index) => ({ ...item, priority: index + 1 }))
}

function move<T extends { priority: number }>(items: T[], index: number, direction: -1 | 1): T[] {
  const destination = index + direction
  if (destination < 0 || destination >= items.length) return items
  const next = [...items]
  ;[next[index], next[destination]] = [next[destination], next[index]]
  return normalizePriorities(next)
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
}: {
  accessToken: string
  profile: ClientProfile
  onSaved: (profile: ClientProfile) => void
}) {
  const [contacts, setContacts] = useState<DraftContact[]>(() => contactsFrom(profile))
  const [payouts, setPayouts] = useState<DraftPayout[]>(() => payoutsFrom(profile))
  const [busy, setBusy] = useState<'contacts' | 'payouts'>()
  const [message, setMessage] = useState<string>()
  const [error, setError] = useState<string>()

  useEffect(() => {
    setContacts(contactsFrom(profile))
    setPayouts(payoutsFrom(profile))
  }, [profile.accountId, profile.version])

  function validateNumbers(items: Array<{ number: string }>, label: string): string | undefined {
    if (items.some((item) => !item.number.trim())) return `Chaque ${label} doit contenir un numéro.`
    const normalized = items.map((item) => item.number.replace(/\s+/g, '').toLocaleLowerCase('fr'))
    if (new Set(normalized).size !== normalized.length) return `Un même numéro ne peut pas apparaître deux fois dans les ${label}s.`
    return undefined
  }

  async function saveContacts() {
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
    setBusy('contacts')
    setError(undefined)
    setMessage(undefined)
    try {
      const saved = await updateClientContactNumbers(profile.version, normalizePriorities(contacts).map(({ key: _key, ...item }) => ({
        ...item,
        label: item.label?.trim() || null,
        number: item.number.trim(),
      })), accessToken)
      onSaved(saved)
      setMessage('L’ordre des numéros de contact a été enregistré.')
    } catch (saveError) {
      setError(saveError instanceof Error ? saveError.message : 'Les numéros de contact n’ont pas pu être enregistrés.')
    } finally {
      setBusy(undefined)
    }
  }

  async function savePayouts() {
    const validation = validateNumbers(payouts, 'versement')
    if (validation) {
      setError(validation)
      return
    }
    if (payouts.some((item) => !item.operator.trim() || !item.accountHolder.trim())) {
      setError('Indiquez l’opérateur et le titulaire de chaque numéro de versement.')
      return
    }
    setBusy('payouts')
    setError(undefined)
    setMessage(undefined)
    try {
      const saved = await updateClientPayoutDestinations(profile.version, normalizePriorities(payouts).map(({ key: _key, ...item }) => ({
        ...item,
        operator: item.operator.trim(),
        accountHolder: item.accountHolder.trim(),
        number: item.number.trim(),
      })), accessToken)
      onSaved(saved)
      setMessage('L’ordre des numéros de versement a été enregistré.')
    } catch (saveError) {
      setError(saveError instanceof Error ? saveError.message : 'Les numéros de versement n’ont pas pu être enregistrés.')
    } finally {
      setBusy(undefined)
    }
  }

  return (
    <div className="client-preferences-grid">
      <section className="client-surface client-priority-card" aria-labelledby="client-contacts-title">
        <header className="client-priority-card__heading">
          <span aria-hidden="true"><Phone /></span>
          <div><p className="eyebrow">Pour vous joindre</p><h2 id="client-contacts-title">Numéros de contact</h2><p>La priorité 1 est utilisée en premier. Les numéros suivants servent d’alternatives, dans l’ordre.</p></div>
        </header>
        <div className="client-priority-list">
          {contacts.map((contact, index) => (
            <article key={contact.key}>
              <span className="client-priority-rank">Priorité {index + 1}</span>
              <label className="client-field"><span>Libellé</span><input value={contact.label ?? ''} maxLength={80} placeholder="Ex. Boutique" onChange={(event) => setContacts((current) => current.map((item) => item.key === contact.key ? { ...item, label: event.target.value } : item))} /></label>
              <label className="client-field"><span>Numéro</span><input value={contact.number} maxLength={50} inputMode="tel" onChange={(event) => setContacts((current) => current.map((item) => item.key === contact.key ? { ...item, number: event.target.value } : item))} /></label>
              <label className="client-priority-active"><input type="checkbox" checked={contact.active} onChange={(event) => setContacts((current) => current.map((item) => item.key === contact.key ? { ...item, active: event.target.checked } : item))} /><span>Actif</span></label>
              <div className="client-priority-actions">
                <button type="button" disabled={index === 0} aria-label={`Monter ${contact.number || `le contact ${index + 1}`}`} onClick={() => setContacts((current) => move(current, index, -1))}><ArrowUp aria-hidden="true" /></button>
                <button type="button" disabled={index === contacts.length - 1} aria-label={`Descendre ${contact.number || `le contact ${index + 1}`}`} onClick={() => setContacts((current) => move(current, index, 1))}><ArrowDown aria-hidden="true" /></button>
                <button type="button" className="is-danger" aria-label={`Supprimer ${contact.number || `le contact ${index + 1}`}`} onClick={() => setContacts((current) => normalizePriorities(current.filter((item) => item.key !== contact.key)))}><Trash2 aria-hidden="true" /></button>
              </div>
            </article>
          ))}
        </div>
        <footer>
          <button className="button button--secondary" type="button" onClick={() => setContacts((current) => [...current, { key: draftKey('contact'), label: '', number: '', priority: current.length + 1, active: true }])}><Plus aria-hidden="true" />Ajouter un numéro</button>
          <button className="button" type="button" disabled={busy === 'contacts'} onClick={() => void saveContacts()}><Save aria-hidden="true" />{busy === 'contacts' ? 'Enregistrement…' : 'Enregistrer les contacts'}</button>
        </footer>
      </section>

      <section className="client-surface client-priority-card" aria-labelledby="client-payouts-title">
        <header className="client-priority-card__heading">
          <span aria-hidden="true"><WalletCards /></span>
          <div><p className="eyebrow">Pour vos reversements</p><h2 id="client-payouts-title">Numéros de versement</h2><p>La priorité indique l’ordre suggéré. AZY confirme toujours le numéro réellement utilisé avant un versement.</p></div>
        </header>
        <div className="client-priority-list">
          {payouts.map((payout, index) => (
            <article key={payout.key}>
              <span className="client-priority-rank">Priorité {index + 1}</span>
              <label className="client-field"><span>Opérateur</span><input value={payout.operator} maxLength={80} placeholder="Ex. MVola" onChange={(event) => setPayouts((current) => current.map((item) => item.key === payout.key ? { ...item, operator: event.target.value } : item))} /></label>
              <label className="client-field"><span>Titulaire</span><input value={payout.accountHolder} maxLength={150} onChange={(event) => setPayouts((current) => current.map((item) => item.key === payout.key ? { ...item, accountHolder: event.target.value } : item))} /></label>
              <label className="client-field"><span>Numéro</span><input value={payout.number} maxLength={50} inputMode="tel" onChange={(event) => setPayouts((current) => current.map((item) => item.key === payout.key ? { ...item, number: event.target.value } : item))} /></label>
              <label className="client-priority-active"><input type="checkbox" checked={payout.active} onChange={(event) => setPayouts((current) => current.map((item) => item.key === payout.key ? { ...item, active: event.target.checked } : item))} /><span>Actif</span></label>
              <div className="client-priority-actions">
                <button type="button" disabled={index === 0} aria-label={`Monter ${payout.number || `le versement ${index + 1}`}`} onClick={() => setPayouts((current) => move(current, index, -1))}><ArrowUp aria-hidden="true" /></button>
                <button type="button" disabled={index === payouts.length - 1} aria-label={`Descendre ${payout.number || `le versement ${index + 1}`}`} onClick={() => setPayouts((current) => move(current, index, 1))}><ArrowDown aria-hidden="true" /></button>
                <button type="button" className="is-danger" aria-label={`Supprimer ${payout.number || `le versement ${index + 1}`}`} onClick={() => setPayouts((current) => normalizePriorities(current.filter((item) => item.key !== payout.key)))}><Trash2 aria-hidden="true" /></button>
              </div>
            </article>
          ))}
        </div>
        {payouts.length === 0 && <p className="client-priority-empty">Aucun numéro de versement enregistré.</p>}
        <footer>
          <button className="button button--secondary" type="button" onClick={() => setPayouts((current) => [...current, { key: draftKey('payout'), operator: '', accountHolder: '', number: '', priority: current.length + 1, active: true }])}><Plus aria-hidden="true" />Ajouter un numéro</button>
          <button className="button" type="button" disabled={busy === 'payouts'} onClick={() => void savePayouts()}><Save aria-hidden="true" />{busy === 'payouts' ? 'Enregistrement…' : 'Enregistrer les versements'}</button>
        </footer>
      </section>

      {message && <p className="client-alert client-alert--success client-preferences-feedback" role="status">{message}</p>}
      {error && <p className="client-alert client-alert--error client-preferences-feedback" role="alert">{error}</p>}
    </div>
  )
}
