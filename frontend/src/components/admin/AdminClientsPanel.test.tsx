import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AdminClientsPanel } from './AdminClientsPanel'

const mocks = vi.hoisted(() => ({
  listAdminClients: vi.fn(),
  requestForm: vi.fn(),
}))

vi.mock('../../api/admin', () => ({
  createAdminClient: vi.fn(),
  createAdminDeliveryRequest: vi.fn(),
  listAdminClients: mocks.listAdminClients,
  quoteAdminDeliveryPrice: vi.fn(),
  resetAdminClientPassword: vi.fn(),
  updateAdminClient: vi.fn(),
}))

vi.mock('../ClientDeliveryRequestForm', () => ({
  ClientDeliveryRequestForm: (props: unknown) => {
    mocks.requestForm(props)
    return <div>Formulaire de demande</div>
  },
}))

describe('AdminClientsPanel', () => {
  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
  })

  it('transmet le point de retrait par défaut du client au formulaire admin', async () => {
    const defaultPickup = {
      label: 'Boutique Analakely',
      destinationQuery: 'Analakely, Antananarivo',
      latitude: -18.9089,
      longitude: 47.5212,
    }
    mocks.listAdminClients.mockResolvedValue([{
      accountId: 'client-1',
      username: 'boutique-soa',
      displayName: 'Boutique Soa',
      phone: '034 00 000 00',
      defaultPickup,
      enabled: true,
      expressDeliveryEnabled: true,
      version: 2,
    }])

    render(<AdminClientsPanel accessToken="admin-token" />)

    await screen.findByText('Boutique Soa')
    fireEvent.click(screen.getByRole('button', { name: 'Demande' }))

    await waitFor(() => expect(mocks.requestForm).toHaveBeenCalledWith(
      expect.objectContaining({ defaultPickup }),
    ))
  })
})
