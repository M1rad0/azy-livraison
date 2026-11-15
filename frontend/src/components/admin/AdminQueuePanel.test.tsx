import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { DeliveryRequest } from '../../api/admin'
import { AdminQueuePanel, type PendingQueueFilters } from './AdminQueuePanel'

const filters: PendingQueueFilters = { scope: 'ALL', from: '', to: '', client: '' }
const request: DeliveryRequest = {
  id: 'request-1', ownerAccountId: 'client-1', ownerDisplayName: 'Boutique Miora',
  requestKind: 'NORMAL', createdByAdministration: false, requestedDate: '2026-11-16',
  pickup: { label: 'Boutique', query: 'Analakely', latitude: -18.91, longitude: 47.52 },
  capacity: { limit: 30, current: 0, requested: 1, projected: 1, warning: false, accepted: false },
  status: 'PENDING', version: 0, createdAt: '', updatedAt: '',
  packages: [{
    id: 'package-1', sequenceNumber: 1, recipientName: 'Miora', faritra: false,
    destination: { label: 'Bureau', query: 'Ankorondrano', latitude: -18.87, longitude: 47.52 },
    contact: '034 00 000 00', amountToCollectAriary: 25000, mobileMoneyPaid: false,
    effectiveWeightKg: 1, weightSource: 'DEFAULT_SETTING', status: 'PENDING', canEdit: true,
    version: 0, createdAt: '', updatedAt: '',
  }],
}

function setup(overrides: Partial<Parameters<typeof AdminQueuePanel>[0]> = {}) {
  const props = {
    accessToken: 'admin-token', requests: [request], filters, busy: false,
    onFiltersChange: vi.fn(), onReload: vi.fn(), onCancelPackage: vi.fn().mockResolvedValue(undefined), onOpenDispatch: vi.fn(),
    ...overrides,
  }
  render(<AdminQueuePanel {...props} />)
  return props
}

describe('AdminQueuePanel action layout', () => {
  afterEach(cleanup)

  it('places package actions after all information columns', () => {
    setup()
    const table = screen.getByRole('table', { name: 'Colis de Boutique Miora' })
    const headers = within(table).getAllByRole('columnheader')
    expect(headers.map((header) => header.textContent)).toEqual([
      'Destinataire', 'Destination', 'Contact', 'À récupérer', 'Paiement', 'État', 'Actions',
    ])
    const row = within(table).getAllByRole('row')[1]
    const cells = within(row).getAllByRole('cell')
    expect(cells[0]).toHaveTextContent('Miora')
    expect(cells.at(-1)).toHaveClass('admin-package-actions')
    expect(within(cells.at(-1)!).getAllByRole('button')).toHaveLength(3)
  })

  it('keeps dispatch and filter controls accessible with shared buttons', () => {
    const props = setup()
    const dispatch = screen.getByRole('button', { name: 'Préparer la journée' })
    expect(dispatch).toHaveClass('ui-button', 'admin-button')
    fireEvent.click(dispatch)
    expect(props.onOpenDispatch).toHaveBeenCalledOnce()
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: '  Miora  ' } })
    const apply = screen.getByRole('button', { name: 'Appliquer' })
    expect(apply).toHaveAttribute('type', 'submit')
    fireEvent.click(apply)
    expect(props.onFiltersChange).toHaveBeenCalledWith({ ...filters, client: 'Miora' })
  })

  it('uses the dedicated period grid only when dates are displayed', () => {
    setup()
    const form = screen.getByRole('button', { name: 'Appliquer' }).closest('form')
    expect(form).not.toHaveClass('admin-queue-filters--period')
    fireEvent.click(screen.getByRole('button', { name: 'Une période' }))
    expect(form).toHaveClass('admin-queue-filters--period')
    expect(screen.getByLabelText('Du')).toBeVisible()
    expect(screen.getByLabelText('Au')).toBeVisible()
  })

  it('preserves disabled filter actions during loading', () => {
    setup({ busy: true })
    expect(screen.getByRole('button', { name: 'Appliquer' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Actualiser les livraisons en attente' })).toBeDisabled()
  })
})
