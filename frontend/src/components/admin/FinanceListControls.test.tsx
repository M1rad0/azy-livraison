import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { CashListFilters, CashPage } from '../../api/cashLedger'
import { FinanceListFilters, FinanceListPagination, useFinanceList } from './FinanceListControls'

type Item = { id: string }
function Harness({ load }: { load: (filters: CashListFilters, signal: AbortSignal) => Promise<CashPage<Item>> }) {
  const list = useFinanceList(load)
  return <><FinanceListFilters filters={list.filters} onChange={list.change} searchLabel="Rechercher" />
    {list.error && <p role="alert">{list.error}</p>}
    {list.items.map((item) => <p key={item.id}>{item.id}</p>)}
    <FinanceListPagination label="Pagination de test" filters={list.filters} onChange={list.change} data={list.data} disabled={list.loading || Boolean(list.error)} loading={list.loading} />
  </>
}
const result = (filters: CashListFilters, total = 43, items = [{ id: 'Résultat actuel' }]) => ({ items, page: filters.page, size: filters.size, totalElements: total, totalPages: Math.ceil(total / filters.size) })
afterEach(cleanup)

describe('finance list controls', () => {
  it('disables both ends and resets filters and page size without losing the search', async () => {
    const load = vi.fn(async (filters: CashListFilters) => result(filters))
    render(<Harness load={load} />)
    expect(await screen.findByText('1–20 sur 43')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Page précédente' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: 'Page suivante' }))
    expect(await screen.findByText('21–40 sur 43')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Page suivante' }))
    expect(await screen.findByText('41–43 sur 43')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Page suivante' })).toBeDisabled()
    fireEvent.change(screen.getByRole('searchbox', { name: 'Rechercher' }), { target: { value: 'Jean' } })
    expect(await screen.findByText('1–20 sur 43')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Par page'), { target: { value: '100' } })
    expect(await screen.findByText('1–43 sur 43')).toBeInTheDocument()
    expect(load).toHaveBeenLastCalledWith(expect.objectContaining({ page: 0, size: 100, q: 'Jean' }), expect.any(AbortSignal))
    fireEvent.click(screen.getByRole('button', { name: 'Réinitialiser les filtres' }))
    await waitFor(() => expect(load).toHaveBeenLastCalledWith(expect.objectContaining({ page: 0, size: 100, q: '', from: '', to: '' }), expect.any(AbortSignal)))
  })
  it('blocks inverted ranges without requesting a page and retains an inclusive same-day range', async () => {
    const load = vi.fn(async (filters: CashListFilters) => result(filters, 0, []))
    render(<Harness load={load} />)
    expect(await screen.findByText('0–0 sur 0')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Du'), { target: { value: '2026-11-16' } })
    await waitFor(() => expect(load).toHaveBeenCalledTimes(2))
    fireEvent.change(screen.getByLabelText('Au'), { target: { value: '2026-11-15' } })
    expect(await screen.findByRole('alert')).toHaveTextContent('La date de début doit précéder ou être égale à la date de fin.')
    expect(load).toHaveBeenCalledTimes(2)
    expect(screen.getByRole('button', { name: 'Page suivante' })).toBeDisabled()
    fireEvent.change(screen.getByLabelText('Au'), { target: { value: '2026-11-16' } })
    await waitFor(() => expect(load).toHaveBeenLastCalledWith(expect.objectContaining({ from: '2026-11-16', to: '2026-11-16' }), expect.any(AbortSignal)))
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })
  it('ignores aborted responses even when the server finishes an outdated search later', async () => {
    let finishOld!: (value: CashPage<Item>) => void
    let oldSignal: AbortSignal | undefined
    const load = vi.fn((filters: CashListFilters, signal: AbortSignal): Promise<CashPage<Item>> => {
      if (!filters.q) { oldSignal = signal; return new Promise((resolve) => { finishOld = resolve }) }
      return Promise.resolve(result(filters, 1, [{ id: 'Jean trouvé' }]))
    })
    const { unmount } = render(<Harness load={load} />)
    expect(screen.getByRole('button', { name: 'Page suivante' })).toBeDisabled()
    fireEvent.change(screen.getByRole('searchbox', { name: 'Rechercher' }), { target: { value: 'Jean' } })
    expect(await screen.findByText('Jean trouvé')).toBeInTheDocument()
    expect(oldSignal?.aborted).toBe(true)
    finishOld({ items: [{ id: 'Ancien résultat' }], totalElements: 1, page: 0, size: 20, totalPages: 1 })
    await waitFor(() => expect(screen.queryByText('Ancien résultat')).not.toBeInTheDocument())
    const currentSignal = load.mock.calls.at(-1)![1]
    unmount(); expect(currentSignal.aborted).toBe(true)
  })
  it('returns to a valid page if the current final page disappears after a refresh', async () => {
    const load = vi.fn(async (filters: CashListFilters) => result(filters, filters.page ? 0 : 21, filters.page ? [] : [{ id: 'Résultat actuel' }]))
    render(<Harness load={load} />)
    expect(await screen.findByText('1–20 sur 21')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Page suivante' }))
    await waitFor(() => expect(load).toHaveBeenCalledTimes(3))
    expect(load.mock.calls.at(-1)![0].page).toBe(0)
    expect(await screen.findByText('1–20 sur 21')).toBeInTheDocument()
  })
})
