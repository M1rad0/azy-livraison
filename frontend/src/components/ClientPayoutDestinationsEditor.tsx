import { ArrowDown, ArrowUp, Plus, Trash2 } from 'lucide-react'
import type { ReactNode } from 'react'
import type { ClientPayoutDestinationInput } from '../api/client'
import { normalizePayoutOperator, PAYOUT_OPERATORS } from '../payoutOperators'
import { PhoneInput } from './PhoneInput'
import './ClientContactPreferences.css'

export interface PayoutDestinationDraft extends ClientPayoutDestinationInput {
  key: string
}

function payoutDraftKey() {
  return globalThis.crypto?.randomUUID?.() ?? `payout-${Date.now()}-${Math.random()}`
}

export function payoutDestinationDrafts(items: ClientPayoutDestinationInput[] = []): PayoutDestinationDraft[] {
  return items.map((item) => ({ ...item, key: item.id ?? payoutDraftKey() }))
}

export function ClientPayoutDestinationsEditor({
  items, onChange, disabled = false, defaultOperator = '', defaultAccountHolder = '', saveAction,
}: {
  items: PayoutDestinationDraft[]
  onChange: (items: PayoutDestinationDraft[]) => void
  disabled?: boolean
  defaultOperator?: string
  defaultAccountHolder?: string
  saveAction?: ReactNode
}) {
  function update(key: string, patch: Partial<PayoutDestinationDraft>) {
    onChange(items.map((item) => item.key === key ? { ...item, ...patch } : item))
  }

  function reorder(index: number, direction: -1 | 1) {
    const target = index + direction
    if (target < 0 || target >= items.length) return
    const next = [...items]
    ;[next[index], next[target]] = [next[target], next[index]]
    onChange(next.map((item, position) => ({ ...item, priority: position + 1 })))
  }

  return <>
    <div className="client-priority-list">
      {items.map((payout, index) => <article key={payout.key}>
        <span className="client-priority-rank">Priorité {index + 1}</span>
        <label className="client-field"><span>Opérateur</span><select value={normalizePayoutOperator(payout.operator)} required disabled={disabled} onChange={(event) => update(payout.key, { operator: event.target.value })}>
          <option value="">Choisir un opérateur</option>
          {PAYOUT_OPERATORS.map((operator) => <option key={operator} value={operator}>{operator}</option>)}
        </select></label>
        <label className="client-field"><span>Titulaire</span><input value={payout.accountHolder} maxLength={150} disabled={disabled} onChange={(event) => update(payout.key, { accountHolder: event.target.value })} /></label>
        <label className="client-field"><span>Numéro</span><PhoneInput value={payout.number} required disabled={disabled} onChange={(event) => update(payout.key, { number: event.target.value })} /></label>
        <label className="client-priority-active"><input type="checkbox" checked={payout.active} disabled={disabled} onChange={(event) => update(payout.key, { active: event.target.checked })} /><span>Actif</span></label>
        <div className="client-priority-actions">
          <button type="button" disabled={disabled || index === 0} aria-label={`Monter ${payout.number || `le versement ${index + 1}`}`} onClick={() => reorder(index, -1)}><ArrowUp aria-hidden="true" /></button>
          <button type="button" disabled={disabled || index === items.length - 1} aria-label={`Descendre ${payout.number || `le versement ${index + 1}`}`} onClick={() => reorder(index, 1)}><ArrowDown aria-hidden="true" /></button>
          <button type="button" className="is-danger" disabled={disabled} aria-label={`Supprimer ${payout.number || `le versement ${index + 1}`}`} onClick={() => onChange(items.filter((item) => item.key !== payout.key).map((item, position) => ({ ...item, priority: position + 1 })))}><Trash2 aria-hidden="true" /></button>
        </div>
      </article>)}
    </div>
    {items.length === 0 && <p className="client-priority-empty">Aucun numéro de versement enregistré.</p>}
    <footer>
      <button className="button button--secondary" type="button" disabled={disabled || items.length >= 20} onClick={() => onChange([...items, { key: payoutDraftKey(), operator: defaultOperator, accountHolder: defaultAccountHolder, number: '', priority: items.length + 1, active: true }])}><Plus aria-hidden="true" />Ajouter un numéro</button>
      {saveAction}
    </footer>
  </>
}
