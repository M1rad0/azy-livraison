import { useEffect, useState } from 'react'
import { ChevronLeft, ChevronRight, Search } from 'lucide-react'
import type { CashListFilters, CashPage } from '../../api/cashLedger'
import { Button } from '../Button'
import { errorMessage } from './adminHelpers'
import './FinanceListControls.css'

export function useFinanceList<T>(loadPage: (filters: CashListFilters, signal: AbortSignal) => Promise<CashPage<T>>, enabled = true) {
  const [filters, setFilters] = useState<CashListFilters>({ page: 0, size: 20, from: '', to: '', q: '' })
  const [data, setData] = useState<CashPage<T>>()
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string>()
  const [reload, setReload] = useState(0)
  const invalidRange = Boolean(filters.from && filters.to && filters.from > filters.to)
  useEffect(() => {
    if (!enabled || invalidRange) return
    const controller = new AbortController()
    setLoading(true); setError(undefined)
    void loadPage(filters, controller.signal).then((value) => {
      if (controller.signal.aborted) return
      if (filters.page > 0 && filters.page >= value.totalPages) {
        setFilters((current) => ({ ...current, page: Math.max(0, value.totalPages - 1) }))
      } else setData(value)
    }).catch((failure) => { if (!controller.signal.aborted) setError(errorMessage(failure)) })
      .finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [enabled, filters, invalidRange, loadPage, reload])
  const change = (patch: Partial<CashListFilters>) => setFilters((current) => ({ ...current, page: 0, ...patch }))
  return { filters, change, data, loading: enabled && !invalidRange && loading, error: invalidRange ? 'La date de début doit précéder ou être égale à la date de fin.' : error,
    items: !enabled || loading || error || invalidRange ? [] : data?.items ?? [], refresh: () => setReload((value) => value + 1), invalidRange }
}

export function FinanceListFilters({ filters, onChange, searchLabel, placeholder }: {
  filters: CashListFilters
  onChange: (patch: Partial<CashListFilters>) => void
  searchLabel?: string
  placeholder?: string
}) {
  return <div className="finance-list-filters">
    {searchLabel && <label className="finance-list-filters__search"><span>{searchLabel}</span><div><Search aria-hidden="true" /><input type="search" value={filters.q} placeholder={placeholder ?? 'Rechercher…'} onChange={(event) => onChange({ q: event.target.value })} /></div></label>}
    <label><span>Du</span><input type="date" value={filters.from} max={filters.to || undefined} onChange={(event) => onChange({ from: event.target.value })} /></label>
    <label><span>Au</span><input type="date" value={filters.to} min={filters.from || undefined} onChange={(event) => onChange({ to: event.target.value })} /></label>
    {(filters.from || filters.to || (searchLabel && filters.q)) && <Button tone="admin" variant="ghost" size="small" onClick={() => onChange({ from: '', to: '', ...(searchLabel ? { q: '' } : {}) })}>Réinitialiser les filtres</Button>}
  </div>
}

export function FinanceListPagination({ label, filters, data, disabled, loading = disabled, onChange }: {
  label: string
  filters: CashListFilters
  data?: CashPage<unknown>
  disabled: boolean
  loading?: boolean
  onChange: (patch: Partial<CashListFilters>) => void
}) {
  const total = data?.totalElements ?? 0
  const start = total ? filters.page * filters.size + 1 : 0
  const end = Math.min((filters.page + 1) * filters.size, total)
  return <nav className="finance-list-pagination" aria-label={label}>
    <span className="finance-list-pagination__count" aria-live="polite">{loading ? 'Chargement…' : `${start}–${end} sur ${total}`}</span>
    <label>Par page<select value={filters.size} onChange={(event) => onChange({ size: Number(event.target.value) })}>{[20, 50, 100].map((size) => <option key={size} value={size}>{size}</option>)}</select></label>
    <div><Button tone="admin" variant="secondary" size="small" disabled={disabled || filters.page === 0} aria-label="Page précédente" onClick={() => onChange({ page: filters.page - 1 })}><ChevronLeft aria-hidden="true" />Précédent</Button>
      <span>Page {filters.page + 1} / {Math.max(1, data?.totalPages ?? 0)}</span>
      <Button tone="admin" variant="secondary" size="small" disabled={disabled || filters.page + 1 >= (data?.totalPages ?? 0)} aria-label="Page suivante" onClick={() => onChange({ page: filters.page + 1 })}>Suivant<ChevronRight aria-hidden="true" /></Button></div>
  </nav>
}
