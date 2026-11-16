import { ArrowDown, ArrowUp, Plus, Trash2 } from 'lucide-react'
import type { ReactNode } from 'react'
import type { ClientContactNumberInput } from '../api/client'
import { PhoneInput } from './PhoneInput'
import './ClientContactPreferences.css'

export interface ContactNumberDraft extends ClientContactNumberInput { key: string }

function draftKey() {
  return globalThis.crypto?.randomUUID?.() ?? `contact-${Date.now()}-${Math.random()}`
}

export function contactNumberDrafts(items: ClientContactNumberInput[] = []): ContactNumberDraft[] {
  return items.map((item) => ({ ...item, key: item.id ?? draftKey() }))
}

export function ClientContactNumbersEditor({ items, onChange, disabled = false, saveAction,
  labelLabel = 'Libellé', numberLabel = 'Numéro', addLabel = 'Ajouter un numéro' }: {
  items: ContactNumberDraft[]
  onChange: (items: ContactNumberDraft[]) => void
  disabled?: boolean
  saveAction?: ReactNode
  labelLabel?: string
  numberLabel?: string
  addLabel?: string
}) {
  function update(key: string, patch: Partial<ContactNumberDraft>) {
    onChange(items.map((item) => item.key === key ? { ...item, ...patch } : item))
  }
  function move(index: number, direction: -1 | 1) {
    const target = index + direction
    if (target < 0 || target >= items.length) return
    const next = [...items]
    ;[next[index], next[target]] = [next[target], next[index]]
    onChange(next.map((item, position) => ({ ...item, priority: position + 1 })))
  }
  return <>
    <div className="client-priority-list">
      {items.map((contact, index) => <article key={contact.key}>
        <span className="client-priority-rank">Priorité {index + 1}</span>
        <label className="client-field"><span>{labelLabel}</span><input value={contact.label ?? ''} maxLength={100} placeholder="Ex. Boutique" disabled={disabled} onChange={(event) => update(contact.key, { label: event.target.value })} /></label>
        <label className="client-field"><span>{numberLabel}</span><PhoneInput value={contact.number} required disabled={disabled} onChange={(event) => update(contact.key, { number: event.target.value })} /></label>
        <label className="client-priority-active"><input type="checkbox" checked={contact.active} disabled={disabled} onChange={(event) => update(contact.key, { active: event.target.checked })} /><span>Actif</span></label>
        <div className="client-priority-actions">
          <button type="button" disabled={disabled || index === 0} aria-label={`Monter ${contact.number || `le contact ${index + 1}`}`} onClick={() => move(index, -1)}><ArrowUp aria-hidden="true" /></button>
          <button type="button" disabled={disabled || index === items.length - 1} aria-label={`Descendre ${contact.number || `le contact ${index + 1}`}`} onClick={() => move(index, 1)}><ArrowDown aria-hidden="true" /></button>
          <button type="button" className="is-danger" disabled={disabled} aria-label={`Supprimer ${contact.number || `le contact ${index + 1}`}`} onClick={() => onChange(items.filter((item) => item.key !== contact.key).map((item, position) => ({ ...item, priority: position + 1 })))}><Trash2 aria-hidden="true" /></button>
        </div>
      </article>)}
    </div>
    <footer><button className="button button--secondary" type="button" disabled={disabled || items.length >= 20} onClick={() => onChange([...items, { key: draftKey(), label: '', number: '', priority: items.length + 1, active: true }])}><Plus aria-hidden="true" />{addLabel}</button>{saveAction}</footer>
  </>
}
