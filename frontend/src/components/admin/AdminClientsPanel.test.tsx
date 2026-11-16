import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AdminClientsPanel } from './AdminClientsPanel'
import { PAYOUT_OPERATOR_ERROR } from '../../payoutOperators'

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

vi.mock('../LocationPicker', () => ({
  LocationPicker: ({ onChange }: { onChange: (location: unknown) => void }) => <button type="button" onClick={() => onChange({ label: 'Boutique', destinationQuery: 'Analakely', latitude: -18.9, longitude: 47.5 })}>Choisir le retrait</button>,
}))

describe('AdminClientsPanel', () => {
  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
    vi.unstubAllGlobals()
  })

  it.each(['Demande', 'Express'])('transmet le retrait et le retour vers les clients au formulaire admin : %s', async (action) => {
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
    fireEvent.click(screen.getByRole('button', { name: action }))

    await waitFor(() => expect(mocks.requestForm).toHaveBeenCalledWith(
      expect.objectContaining({ defaultPickup, backLabel: 'Retour à la liste des clients', requestKind: action === 'Express' ? 'EXPRESS' : 'NORMAL' }),
    ))
  })

  const firstPayout = { id: 'payout-1', operator: 'MVola', accountHolder: 'Soa', number: '0341111111', priority: 1, active: true }
  const secondPayout = { id: 'payout-2', operator: 'Orange Money', accountHolder: 'Soa', number: '0322222222', priority: 2, active: false }
  const client = {
    accountId: 'client-1', username: 'boutique-soa', displayName: 'Boutique Soa', phone: '034 00 000 00',
    defaultPickup: null, enabled: true, expressDeliveryEnabled: false, version: 2,
    payoutDestinations: [firstPayout, secondPayout],
  }

  async function openEditor() {
    mocks.listAdminClients.mockResolvedValue([client])
    render(<AdminClientsPanel accessToken="admin-token" />)
    fireEvent.click(await screen.findByRole('button', { name: 'Gérer' }))
    await screen.findByRole('heading', { name: 'Boutique Soa' })
    fireEvent.click(screen.getByRole('tab', { name: 'Versements Numéros par ordre de priorité' }))
  }

  it('enregistre les numéros dans la création du compte et réutilise leurs identifiants au prochain enregistrement', async () => {
    mocks.listAdminClients.mockResolvedValue([])
    mocks.createAdminClient.mockResolvedValue({ client: { ...client, payoutDestinations: [firstPayout] }, temporaryPassword: 'Temporary123' })
    mocks.updateAdminClient.mockResolvedValue({ ...client, version: 3, payoutDestinations: [firstPayout] })
    render(<AdminClientsPanel accessToken="admin-token" />)
    fireEvent.click(screen.getByRole('button', { name: 'Nouveau client' }))
    fireEvent.change(screen.getByLabelText('Nom affiché'), { target: { value: 'Boutique Soa' } })
    fireEvent.change(screen.getByLabelText('Numéro de contact'), { target: { value: '034 00 000 00' } })
    fireEvent.change(screen.getByLabelText('Identifiant'), { target: { value: 'boutique-soa' } })
    fireEvent.click(screen.getByRole('tab', { name: 'Versements Numéros par ordre de priorité' }))
    fireEvent.click(screen.getByRole('button', { name: 'Ajouter un numéro' }))
    expect(screen.getByLabelText('Opérateur')).toHaveValue('MVola')
    expect(screen.getByLabelText('Titulaire')).toHaveValue('Boutique Soa')
    fireEvent.change(screen.getByLabelText('Numéro'), { target: { value: ' 034 11 111 11 ' } })
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer' }))
    await waitFor(() => expect(mocks.createAdminClient).toHaveBeenCalledWith(expect.objectContaining({
      payoutDestinations: [{ operator: 'MVola', accountHolder: 'Boutique Soa', number: '0341111111', priority: 1, active: true }],
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
      version: 2, payoutDestinations: [{ ...firstPayout, number: '0349999999' }, secondPayout],
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

  it('bloque un ancien opérateur inconnu et permet de choisir un opérateur autorisé', async () => {
    const legacyPayout = { ...firstPayout, operator: 'Banque' }
    mocks.listAdminClients.mockResolvedValue([{ ...client, payoutDestinations: [legacyPayout] }])
    mocks.updateAdminClient.mockResolvedValue({ ...client, version: 3 })
    render(<AdminClientsPanel accessToken="admin-token" />)
    fireEvent.click(await screen.findByRole('button', { name: 'Gérer' }))
    fireEvent.click(screen.getByRole('tab', { name: 'Versements Numéros par ordre de priorité' }))
    const operator = screen.getByRole('combobox', { name: 'Opérateur' })
    expect(operator).toHaveValue('')
    expect(Array.from((operator as HTMLSelectElement).options).map((option) => option.value)).toEqual(['', 'MVola', 'Orange Money', 'Airtel Money'])
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer' }))
    expect(screen.getByRole('status')).toHaveTextContent(PAYOUT_OPERATOR_ERROR)
    expect(mocks.updateAdminClient).not.toHaveBeenCalled()
    expect(legacyPayout.operator).toBe('Banque')
    fireEvent.change(operator, { target: { value: 'Orange Money' } })
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer' }))
    await waitFor(() => expect(mocks.updateAdminClient).toHaveBeenCalledWith('client-1', expect.objectContaining({
      payoutDestinations: [{ ...firstPayout, operator: 'Orange Money' }],
    }), 'admin-token'))
  })

  it('normalise les noms d’opérateurs autorisés lors d’une modification explicite', async () => {
    mocks.listAdminClients.mockResolvedValue([{ ...client, payoutDestinations: [{ ...firstPayout, operator: 'mvola' }] }])
    mocks.updateAdminClient.mockResolvedValue({ ...client, version: 3 })
    render(<AdminClientsPanel accessToken="admin-token" />)
    fireEvent.click(await screen.findByRole('button', { name: 'Gérer' }))
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer' }))
    await waitFor(() => expect(mocks.updateAdminClient).toHaveBeenCalledWith('client-1', expect.objectContaining({ payoutDestinations: [firstPayout] }), 'admin-token'))
  })

  it('crée un compte complet dans une page avec retrait, contacts prioritaires et permissions', async () => {
    mocks.listAdminClients.mockResolvedValue([])
    mocks.createAdminClient.mockResolvedValue({ client, temporaryPassword: 'Temporary123' })
    render(<AdminClientsPanel accessToken="admin-token" />)
    fireEvent.click(screen.getByRole('button', { name: 'Nouveau client' }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(document.body.style.overflow).not.toBe('hidden')
    fireEvent.change(screen.getByLabelText('Nom affiché'), { target: { value: 'Boutique Soa' } })
    fireEvent.change(screen.getByLabelText('Identifiant'), { target: { value: 'boutique-soa' } })
    fireEvent.change(screen.getByLabelText('Numéro de contact'), { target: { value: '034 00 000 00' } })
    fireEvent.click(screen.getByRole('button', { name: 'Ajouter un contact' }))
    fireEvent.change(screen.getAllByLabelText('Libellé du contact')[1], { target: { value: 'Assistant' } })
    fireEvent.change(screen.getAllByLabelText('Numéro de contact')[1], { target: { value: '032 11 111 11' } })
    fireEvent.click(screen.getByRole('button', { name: 'Monter 0321111111' }))
    expect(screen.getByLabelText('Téléphone principal')).toHaveValue('0321111111')
    fireEvent.click(screen.getByRole('checkbox', { name: /^Compte actif/ }))
    fireEvent.click(screen.getByRole('checkbox', { name: /^Demandes expresses/ }))
    fireEvent.change(screen.getByLabelText('Libellé du retrait'), { target: { value: 'Ma boutique' } })
    fireEvent.click(screen.getByRole('button', { name: 'Choisir le retrait' }))
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer' }))
    await waitFor(() => expect(mocks.createAdminClient).toHaveBeenCalledWith(expect.objectContaining({
      phone: '0321111111', enabled: false, expressDeliveryEnabled: true,
      contactNumbers: [
        expect.objectContaining({ label: 'Assistant', number: '0321111111', priority: 1, active: true }),
        expect.objectContaining({ label: 'Principal', number: '0340000000', priority: 2, active: true }),
      ],
      defaultPickup: { label: 'Ma boutique', destinationQuery: 'Analakely', latitude: -18.9, longitude: 47.5 },
      clearDefaultPickup: false,
    }), 'admin-token'))
  })

  it('préserve les identifiants des contacts et retire explicitement le retrait par défaut', async () => {
    const contact = { id: 'contact-1', label: 'Principal', number: client.phone, priority: 1, active: true }
    mocks.listAdminClients.mockResolvedValue([{ ...client, contactNumbers: [contact], defaultPickup: { label: 'Boutique', destinationQuery: 'Analakely', latitude: -18.9, longitude: 47.5 } }])
    mocks.updateAdminClient.mockResolvedValue({ ...client, contactNumbers: [contact], version: 3 })
    render(<AdminClientsPanel accessToken="admin-token" />)
    fireEvent.click(await screen.findByRole('button', { name: 'Gérer' }))
    fireEvent.click(screen.getByRole('button', { name: 'Retirer le point par défaut' }))
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer' }))
    await waitFor(() => expect(mocks.updateAdminClient).toHaveBeenCalledWith('client-1', expect.objectContaining({
      contactNumbers: [{ ...contact, number: '0340000000' }], defaultPickup: null, clearDefaultPickup: true, version: 2,
    }), 'admin-token'))
  })

  it('refuse les contacts incomplets ou en double, même si leur onglet n’est pas actif', async () => {
    mocks.listAdminClients.mockResolvedValue([])
    render(<AdminClientsPanel accessToken="admin-token" />)
    fireEvent.click(screen.getByRole('button', { name: 'Nouveau client' }))
    fireEvent.change(screen.getByLabelText('Nom affiché'), { target: { value: 'Soa' } })
    fireEvent.change(screen.getByLabelText('Identifiant'), { target: { value: 'soa' } })
    fireEvent.click(screen.getByRole('tab', { name: 'Versements Numéros par ordre de priorité' }))
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer' }))
    expect(screen.getByRole('tab', { name: 'Informations Profil, retrait et contacts' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByText('Renseignez chaque numéro de contact et conservez au moins un contact actif.')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Numéro de contact'), { target: { value: '034 11' } })
    fireEvent.click(screen.getByRole('button', { name: 'Ajouter un contact' }))
    fireEvent.change(screen.getAllByLabelText('Numéro de contact')[1], { target: { value: '03411' } })
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer' }))
    expect(screen.getByText('Deux contacts utilisent le même numéro.')).toBeInTheDocument()
    expect(mocks.createAdminClient).not.toHaveBeenCalled()
  })
})
