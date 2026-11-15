import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AdminClientsPanel } from './AdminClientsPanel'

const mocks = vi.hoisted(() => ({
  listAdminClients: vi.fn(),
  createAdminClient: vi.fn(),
  updateAdminClient: vi.fn(),
  requestForm: vi.fn(),
}))

vi.mock('../../api/admin', () => ({
  createAdminClient: mocks.createAdminClient,
  createAdminDeliveryRequest: vi.fn(),
  listAdminClients: mocks.listAdminClients,
  quoteAdminDeliveryPrice: vi.fn(),
  resetAdminClientPassword: vi.fn(),
  updateAdminClient: mocks.updateAdminClient,
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
    vi.unstubAllGlobals()
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

  const firstPayout = { id: 'payout-1', operator: 'MVola', accountHolder: 'Soa', number: '034 11 111 11', priority: 1, active: true }
  const secondPayout = { id: 'payout-2', operator: 'Orange Money', accountHolder: 'Soa', number: '032 22 222 22', priority: 2, active: false }
  const client = {
    accountId: 'client-1', username: 'boutique-soa', displayName: 'Boutique Soa', phone: '034 00 000 00',
    defaultPickup: null, enabled: true, expressDeliveryEnabled: false, version: 2,
    payoutDestinations: [firstPayout, secondPayout],
  }

  async function openEditor() {
    mocks.listAdminClients.mockResolvedValue([client])
    render(<AdminClientsPanel accessToken="admin-token" />)
    fireEvent.click(await screen.findByRole('button', { name: 'Gérer' }))
    await screen.findByRole('dialog', { name: 'Modifier le client' })
  }

  it('enregistre les numéros dans la création du compte et réutilise leurs identifiants au prochain enregistrement', async () => {
    mocks.listAdminClients.mockResolvedValue([])
    mocks.createAdminClient.mockResolvedValue({ client: { ...client, payoutDestinations: [firstPayout] }, temporaryPassword: 'Temporary123' })
    mocks.updateAdminClient.mockResolvedValue({ ...client, version: 3, payoutDestinations: [firstPayout] })
    render(<AdminClientsPanel accessToken="admin-token" />)
    fireEvent.click(screen.getByRole('button', { name: 'Nouveau client' }))
    fireEvent.change(screen.getByLabelText('Nom affiché'), { target: { value: 'Boutique Soa' } })
    fireEvent.change(screen.getByLabelText('Téléphone'), { target: { value: '034 00 000 00' } })
    fireEvent.change(screen.getByLabelText('Identifiant'), { target: { value: 'boutique-soa' } })
    fireEvent.click(screen.getByRole('button', { name: 'Ajouter un numéro' }))
    expect(screen.getByLabelText('Opérateur')).toHaveValue('MVola')
    expect(screen.getByLabelText('Titulaire')).toHaveValue('Boutique Soa')
    fireEvent.change(screen.getByLabelText('Numéro'), { target: { value: ' 034 11 111 11 ' } })
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer' }))
    await waitFor(() => expect(mocks.createAdminClient).toHaveBeenCalledWith(expect.objectContaining({
      payoutDestinations: [{ operator: 'MVola', accountHolder: 'Boutique Soa', number: '034 11 111 11', priority: 1, active: true }],
    }), 'admin-token'))
    await screen.findByText('Temporary123')
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer' }))
    await waitFor(() => expect(mocks.updateAdminClient).toHaveBeenCalledWith('client-1', expect.objectContaining({
      version: 2, payoutDestinations: [firstPayout],
    }), 'admin-token'))
  })

  it('modifie un numéro sans perdre les autres numéros, leurs identifiants ou leur activation', async () => {
    mocks.updateAdminClient.mockResolvedValue({ ...client, version: 3, payoutDestinations: [{ ...firstPayout, number: '034 99 999 99' }, secondPayout] })
    await openEditor()
    fireEvent.change(screen.getByDisplayValue(firstPayout.number), { target: { value: '034 99 999 99' } })
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer' }))
    await waitFor(() => expect(mocks.updateAdminClient).toHaveBeenCalledWith('client-1', expect.objectContaining({
      version: 2, payoutDestinations: [{ ...firstPayout, number: '034 99 999 99' }, secondPayout],
    }), 'admin-token'))
  })

  it('conserve tous les numéros lorsqu’une autre information du compte change', async () => {
    mocks.updateAdminClient.mockResolvedValue({ ...client, version: 3 })
    await openEditor()
    fireEvent.change(screen.getByLabelText('Nom affiché'), { target: { value: 'Nouvelle boutique' } })
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer' }))
    await waitFor(() => expect(mocks.updateAdminClient).toHaveBeenCalledWith('client-1', expect.objectContaining({
      displayName: 'Nouvelle boutique', payoutDestinations: [firstPayout, secondPayout],
    }), 'admin-token'))
  })

  it('permet de réordonner les priorités et renumérote les priorités après suppression', async () => {
    mocks.updateAdminClient.mockResolvedValue({ ...client, version: 3, payoutDestinations: [{ ...secondPayout, priority: 1 }] })
    await openEditor()
    expect(screen.getByRole('button', { name: `Monter ${firstPayout.number}` })).toBeDisabled()
    expect(screen.getByRole('button', { name: `Descendre ${secondPayout.number}` })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: `Monter ${secondPayout.number}` }))
    expect(screen.getAllByLabelText('Numéro')[0]).toHaveValue(secondPayout.number)
    fireEvent.click(screen.getByRole('button', { name: `Supprimer ${firstPayout.number}` }))
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer' }))
    await waitFor(() => expect(mocks.updateAdminClient).toHaveBeenCalledWith('client-1', expect.objectContaining({
      payoutDestinations: [{ ...secondPayout, priority: 1 }],
    }), 'admin-token'))
  })

  it('permet de retirer tous les numéros du compte', async () => {
    mocks.updateAdminClient.mockResolvedValue({ ...client, version: 3, payoutDestinations: [] })
    await openEditor()
    fireEvent.click(screen.getByRole('button', { name: `Supprimer ${firstPayout.number}` }))
    fireEvent.click(screen.getByRole('button', { name: `Supprimer ${secondPayout.number}` }))
    expect(screen.getByText('Aucun numéro de versement enregistré.')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer' }))
    await waitFor(() => expect(mocks.updateAdminClient).toHaveBeenCalledWith('client-1', expect.objectContaining({ payoutDestinations: [] }), 'admin-token'))
  })

  it('bloque un numéro incomplet ou dupliqué avant d’envoyer les informations', async () => {
    await openEditor()
    fireEvent.click(screen.getByRole('button', { name: 'Ajouter un numéro' }))
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer' }))
    expect(await screen.findByText('Indiquez l’opérateur, le titulaire et le numéro de chaque versement.')).toBeInTheDocument()
    expect(mocks.updateAdminClient).not.toHaveBeenCalled()
    fireEvent.change(screen.getAllByLabelText('Numéro')[2], { target: { value: firstPayout.number.replaceAll(' ', '') } })
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer' }))
    expect(await screen.findByText('Deux numéros de versement utilisent le même opérateur et le même numéro.')).toBeInTheDocument()
    expect(mocks.updateAdminClient).not.toHaveBeenCalled()
  })

  it('reste utilisable sans crypto.randomUUID sur une connexion mobile locale', async () => {
    vi.stubGlobal('crypto', {})
    await openEditor()
    fireEvent.click(screen.getByRole('button', { name: 'Ajouter un numéro' }))
    expect(screen.getAllByLabelText('Numéro')).toHaveLength(3)
  })
})
