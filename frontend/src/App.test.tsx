import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import type { ReactNode } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { App } from './App'
import type { AuthSession } from './api/auth'
import { LegacyPlanningTools } from './components/LegacyPlanningTools'

vi.mock('react-leaflet', () => {
  const map = { fitBounds: vi.fn(), setView: vi.fn() }
  return {
    MapContainer: ({ children }: { children: ReactNode }) => <div data-testid="plan-preview-map">{children}</div>,
    TileLayer: () => null,
    CircleMarker: ({ children }: { children: ReactNode }) => <div>{children}</div>,
    GeoJSON: () => <div data-testid="preview-route" />,
    Popup: ({ children }: { children: ReactNode }) => <div>{children}</div>,
    Tooltip: ({ children }: { children: ReactNode }) => <div>{children}</div>,
    useMap: () => map,
    useMapEvents: () => map,
  }
})

vi.mock('./components/ClientPortal', () => ({
  ClientPortal: ({ accessToken }: { accessToken: string }) => (
    <section data-access-token={accessToken}>
      <p>Espace client</p>
      <h1>Bonjour, Soa Rakoto</h1>
      <h2>Nouvelle demande</h2>
    </section>
  ),
}))

vi.mock('./components/admin/AdminPortal', () => ({
  AdminPortal: ({ accessToken }: { accessToken: string }) => (
    <section data-access-token={accessToken}>
      <p>Administration</p>
      <h1>Piloter les livraisons</h1>
      <h2>Demandes en attente</h2>
    </section>
  ),
}))

const session = {
  tokenType: 'Bearer',
  accessToken: 'test-access-token',
  expiresIn: 900,
  account: {
    id: '9ac90f82-9f3e-4ccb-b11f-d2cf804f3a1e',
    username: 'admin',
    role: 'ADMIN',
    displayName: 'Équipe AZY',
    phone: '+261340000000',
  },
} satisfies AuthSession

const clientSession = {
  ...session,
  account: {
    id: 'cb10572f-9c2b-4efd-9731-4bc138dd70aa',
    username: 'soa.client',
    role: 'CLIENT',
    displayName: 'Soa Rakoto',
    phone: '+261341234567',
  },
} satisfies AuthSession

const systemStatus = {
  service: 'UP',
  database: 'UP',
  checkedAt: '2026-08-02T12:00:00Z',
}

function jsonResponse(body: unknown, status = 200) {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
  }
}

function authenticatedFetch(
  handler?: (input: string, init?: RequestInit) => unknown,
  activeSession: AuthSession = session,
) {
  return vi.fn().mockImplementation((input: string, init?: RequestInit) => {
    if (input === '/api/auth/refresh') return Promise.resolve(jsonResponse(activeSession))
    if (input === '/api/system/status') return Promise.resolve(jsonResponse(systemStatus))
    const handled = handler?.(input, init)
    if (handled) return Promise.resolve(handled)
    throw new Error(`Unexpected request: ${input}`)
  })
}

async function openHistoricalTools() {
  render(<LegacyPlanningTools accessToken={session.accessToken} onClose={() => undefined} />)
  await screen.findByRole('heading', { name: 'Quel trajet préparez-vous ?' })
}

async function openWorkspace() {
  await openHistoricalTools()
  fireEvent.click(await screen.findByRole('button', { name: 'Choisir ce mode' }))
  await screen.findByRole('heading', { name: 'Construisez votre tournée.' })
}

function selectWorkspaceTab(name: 'Plan' | 'Livraisons' | 'Retraits' | 'Livreurs') {
  fireEvent.click(screen.getByRole('tab', { name: new RegExp(`^${name}`) }))
}

describe('App', () => {
  afterEach(() => {
    cleanup()
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('restores an admin session on the new administration dashboard', async () => {
    const fetchMock = authenticatedFetch()
    vi.stubGlobal('fetch', fetchMock)

    render(<App />)

    expect(await screen.findByRole('heading', { name: 'Piloter les livraisons' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Demandes en attente' })).toBeInTheDocument()
    expect(screen.queryByText('Outils historiques')).not.toBeInTheDocument()
    await waitFor(() => expect(screen.getAllByText('Administration').length).toBeGreaterThan(0))
    expect(screen.getAllByText('Équipe AZY').length).toBeGreaterThan(0)
  })

  it('routes a client session to a separate client dashboard', async () => {
    vi.stubGlobal('fetch', authenticatedFetch(undefined, clientSession))

    render(<App />)

    expect(await screen.findByRole('heading', { name: /Bonjour,? Soa/ })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Nouvelle demande' })).toBeInTheDocument()
    expect(screen.getAllByText('Espace client').length).toBeGreaterThan(0)
    expect(screen.queryByRole('button', { name: 'Ouvrir les outils historiques' })).not.toBeInTheDocument()
  })

  it('keeps file actions above the workspace and changes the sidebar with the active step', async () => {
    vi.stubGlobal('fetch', authenticatedFetch())
    render(<App />)

    await openWorkspace()
    expect(screen.getByRole('region', { name: 'Fichier de travail' })).toBeInTheDocument()
    expect(screen.getByText('Poids total')).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Prêt à préparer les tournées ?' })).not.toBeInTheDocument()

    selectWorkspaceTab('Livraisons')
    expect(screen.getByText('Poids total')).toBeInTheDocument()
    expect(screen.getByLabelText('Ouvrir une sauvegarde')).toBeInTheDocument()

    selectWorkspaceTab('Livreurs')
    expect(screen.getByRole('heading', { name: 'Prêt à préparer les tournées ?' })).toBeInTheDocument()
    expect(screen.queryByText('Poids total')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Générer les tournées' })).toBeInTheDocument()
  })

  it('edits a flat withdrawal, its packages, then shows the plan summary', async () => {
    vi.stubGlobal('fetch', authenticatedFetch())
    render(<App />)

    await openHistoricalTools()
    fireEvent.click(await screen.findByRole('button', { name: 'Choisir le mode plusieurs retraits' }))
    expect(await screen.findByRole('heading', { name: 'Préparez tous les retraits.' })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Plan' })).toHaveAttribute('aria-selected', 'true')

    selectWorkspaceTab('Retraits')
    expect(screen.getByRole('tab', { name: /^Retraits/ })).toHaveAttribute('aria-selected', 'true')
    fireEvent.click(screen.getByRole('button', { name: 'Ajouter un retrait' }))

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    const withdrawal = screen.getByRole('article', { name: 'Retrait 1' })
    fireEvent.change(within(withdrawal).getByLabelText('Client'), { target: { value: 'Client Alpha' } })
    fireEvent.change(within(withdrawal).getByLabelText('Libellé'), { target: { value: 'Retrait Alpha' } })

    expect(within(withdrawal).getByText('Retrait Alpha')).toBeInTheDocument()
    fireEvent.click(within(withdrawal).getByRole('button', { name: /Ajouter les colis de ce retrait/ }))

    const packageDialog = screen.getByRole('dialog', { name: 'Nouveau colis' })
    fireEvent.change(within(packageDialog).getByLabelText('Libellé'), { target: { value: 'Colis Bureau' } })
    fireEvent.change(within(packageDialog).getByLabelText('Poids (kg)'), { target: { value: '2.5' } })
    fireEvent.click(within(packageDialog).getByRole('button', { name: 'Terminer' }))

    expect(screen.getByText('Colis Bureau')).toBeInTheDocument()
    expect(screen.getAllByText('2,5 kg').length).toBeGreaterThan(0)
    fireEvent.click(screen.getByRole('button', { name: 'Modifier Colis Bureau' }))
    expect(screen.getByRole('dialog', { name: 'Colis Bureau' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Fermer' }))

    fireEvent.click(screen.getByRole('button', { name: 'Résumé' }))
    const summaryDialog = screen.getByRole('dialog', { name: 'Résumé du plan' })
    expect(within(summaryDialog).getByText('Client Alpha')).toBeInTheDocument()
    expect(within(summaryDialog).getAllByText('Retrait Alpha').length).toBeGreaterThan(0)
    expect(within(summaryDialog).getByText('Colis Bureau')).toBeInTheDocument()
    expect(screen.getByText('Plan · Plusieurs retraits')).toBeInTheDocument()
  })

  it('starts new multi-withdrawal plans with a hub and required courier departures', async () => {
    vi.stubGlobal('fetch', authenticatedFetch())
    render(<App />)

    await openHistoricalTools()
    fireEvent.click(await screen.findByRole('button', { name: 'Choisir le mode plusieurs retraits' }))
    expect(await screen.findByRole('heading', { name: 'Siège / point de rencontre' })).toBeInTheDocument()
    expect(screen.getByText('Siège à confirmer')).toBeInTheDocument()
    const planDeparture = screen.getByLabelText('Départ · heure d’Antananarivo') as HTMLInputElement

    selectWorkspaceTab('Livreurs')
    fireEvent.click(screen.getByRole('button', { name: 'Ajouter' }))

    const courierDeparture = screen.getByLabelText('Heure de départ')
    expect(courierDeparture).toHaveValue(planDeparture.value)
    expect(courierDeparture).toBeRequired()
    expect(screen.getByText('Départ du livreur')).toBeInTheDocument()
    expect(screen.getByText(/Requis · le trajet jusqu’au premier retrait ou au siège/)).toBeInTheDocument()
    expect(screen.queryByText(/sinon la tournée commence au premier retrait/)).not.toBeInTheDocument()
  })

  it('keeps two withdrawals for the same client independent', async () => {
    vi.stubGlobal('fetch', authenticatedFetch())
    render(<App />)

    await openHistoricalTools()
    fireEvent.click(await screen.findByRole('button', { name: 'Choisir le mode plusieurs retraits' }))
    await screen.findByRole('heading', { name: 'Préparez tous les retraits.' })
    selectWorkspaceTab('Retraits')

    fireEvent.click(screen.getByRole('button', { name: 'Ajouter un retrait' }))
    fireEvent.click(screen.getByRole('button', { name: 'Ajouter un retrait' }))

    const firstWithdrawal = screen.getByRole('article', { name: 'Retrait 1' })
    const secondWithdrawal = screen.getByRole('article', { name: 'Retrait 2' })
    fireEvent.change(within(firstWithdrawal).getByLabelText('Client'), { target: { value: 'Client commun' } })
    fireEvent.change(within(secondWithdrawal).getByLabelText('Client'), { target: { value: 'Client commun' } })
    fireEvent.change(within(firstWithdrawal).getByLabelText('Libellé'), { target: { value: 'Retrait Analakely' } })
    fireEvent.change(within(secondWithdrawal).getByLabelText('Libellé'), { target: { value: 'Retrait Ampasapito' } })

    expect(within(firstWithdrawal).getByLabelText('Client')).toHaveValue('Client commun')
    expect(within(secondWithdrawal).getByLabelText('Client')).toHaveValue('Client commun')
    expect(within(firstWithdrawal).getByLabelText('Libellé')).toHaveValue('Retrait Analakely')
    expect(within(secondWithdrawal).getByLabelText('Libellé')).toHaveValue('Retrait Ampasapito')

    fireEvent.change(within(firstWithdrawal).getByLabelText('Client'), { target: { value: 'Client renommé' } })
    expect(within(firstWithdrawal).getByLabelText('Client')).toHaveValue('Client renommé')
    expect(within(secondWithdrawal).getByLabelText('Client')).toHaveValue('Client commun')
  })

  it('flattens withdrawals grouped under one client in a historical draft', async () => {
    vi.stubGlobal('fetch', authenticatedFetch())
    const draft = {
      schemaVersion: '2.0',
      pattern: 'MULTI_CLIENT_MULTI_PICKUP_DELIVERY',
      planId: 'plan-historique-001',
      name: 'Plan historique',
      departureAt: '2026-08-05T08:00:00+03:00',
      timezone: 'Indian/Antananarivo',
      settings: { returnToDepot: false, routingPreset: 'MAIN_ROADS' },
      couriers: [],
      clients: [{
        id: 'client-001',
        name: 'Client historique',
        deliveryGroups: [
          {
            id: 'retrait-001',
            name: 'Retrait Analakely',
            pickup: {
              label: 'Boutique historique',
              destinationQuery: 'Analakely, Antananarivo',
              latitude: -18.9089,
              longitude: 47.5212,
            },
            packages: [],
          },
          { id: 'retrait-002', name: 'Retrait Ampasapito', pickup: { destinationQuery: 'Ampasapito' }, packages: [] },
        ],
      }],
    }
    const file = new File([JSON.stringify(draft)], 'plan-historique.json', { type: 'application/json' })
    Object.defineProperty(file, 'text', { value: async () => JSON.stringify(draft) })

    render(<App />)
    await openHistoricalTools()
    fireEvent.click(await screen.findByRole('button', { name: 'Choisir le mode plusieurs retraits' }))
    fireEvent.change(screen.getByLabelText('Ouvrir une sauvegarde'), { target: { files: [file] } })
    expect(await screen.findByText('plan-historique.json chargé : 2 retraits et 0 colis.')).toBeInTheDocument()
    selectWorkspaceTab('Retraits')

    const firstWithdrawal = screen.getByRole('article', { name: 'Retrait 1' })
    const secondWithdrawal = screen.getByRole('article', { name: 'Retrait 2' })
    expect(within(firstWithdrawal).getByLabelText('Client')).toHaveValue('Client historique')
    expect(within(secondWithdrawal).getByLabelText('Client')).toHaveValue('Client historique')
    expect(within(firstWithdrawal).getByLabelText('Libellé')).toHaveValue('Retrait Analakely')
    expect(within(secondWithdrawal).getByLabelText('Libellé')).toHaveValue('Retrait Ampasapito')
    expect(within(firstWithdrawal).getByText('Lieu confirmé')).toBeInTheDocument()
    expect(within(firstWithdrawal).getByText('Analakely, Antananarivo')).toBeInTheDocument()
    expect(within(firstWithdrawal).getByText('Libellé : Boutique historique')).toBeInTheDocument()
    expect(within(firstWithdrawal).getByText('Coordonnées : -18.908900, 47.521200')).toBeInTheDocument()
  })

  it('shows validation errors once with stable counters and user-facing labels', async () => {
    vi.stubGlobal('fetch', authenticatedFetch((input) => {
      if (input === '/api/plans/validate') {
        return jsonResponse({
          valid: false,
          errors: [{
            path: 'couriers',
            code: 'NotEmpty',
            message: 'Ajouter au moins un livreur.',
          }],
        }, 400)
      }
      return undefined
    }))

    render(<App />)
    await openHistoricalTools()
    fireEvent.click(await screen.findByRole('button', { name: 'Choisir le mode plusieurs retraits' }))
    selectWorkspaceTab('Livreurs')
    fireEvent.click(screen.getByRole('button', { name: 'Valider le plan' }))

    expect(await screen.findByText('Validation impossible.')).toBeInTheDocument()
    expect(screen.getAllByText('Ajouter au moins un livreur.')).toHaveLength(1)
    const detailedError = screen.getByText('Ajouter au moins un livreur.').closest('li')
    expect(detailedError).not.toBeNull()
    expect(within(detailedError as HTMLLIElement).getByText('Livreurs')).toBeInTheDocument()
    expect(screen.queryByText('couriers')).not.toBeInTheDocument()

    const courierTab = screen.getByRole('tab', { name: 'Livreurs · 1 erreur' })
    expect(within(courierTab).getByText('0 livreur').parentElement).toHaveClass('workspace-tabs__content')
    expect(within(courierTab).getByText('1')).toHaveAttribute('aria-hidden', 'true')
  })

  it('shows the login form when no refresh session exists', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({
      code: 'INVALID_REFRESH_TOKEN',
      message: 'Session expirée.',
    }, 401)))

    render(<App />)

    expect(await screen.findByRole('heading', { name: 'Bienvenue' })).toBeInTheDocument()
    expect(screen.getByLabelText('Identifiant')).toHaveAttribute('autocapitalize', 'none')
    expect(screen.getByLabelText('Identifiant')).toHaveAttribute('autocorrect', 'off')
    expect(screen.getByLabelText('Identifiant')).toHaveAttribute('spellcheck', 'false')
    expect(screen.getByLabelText('Mot de passe')).toHaveAttribute('type', 'password')
    const pricingButton = screen.getByRole('button', { name: /Calculer un tarif/ })
    expect(pricingButton).toBeVisible()
    expect(screen.queryByRole('button', { name: /Créer un compte/ })).not.toBeInTheDocument()
    expect(screen.queryByText(/Première livraison avec AZY/)).not.toBeInTheDocument()
    const loginButton = screen.getByRole('button', { name: 'Se connecter' })
    expect(loginButton.compareDocumentPosition(pricingButton) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    fireEvent.click(pricingButton)
    expect(screen.getByRole('dialog', { name: 'Calculer un tarif' })).toBeInTheDocument()
  })

  it('logs in and opens the dashboard matching the account role', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse({ code: 'INVALID_REFRESH_TOKEN' }, 401))
      .mockResolvedValueOnce(jsonResponse(session))
      .mockResolvedValueOnce(jsonResponse(systemStatus))
    vi.stubGlobal('fetch', fetchMock)

    render(<App />)
    fireEvent.change(await screen.findByLabelText('Identifiant'), { target: { value: 'admin' } })
    fireEvent.change(screen.getByLabelText('Mot de passe'), { target: { value: 'secret-password' } })
    fireEvent.click(screen.getByRole('button', { name: 'Se connecter' }))

    await screen.findByRole('heading', { name: 'Piloter les livraisons' })
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      '/api/auth/login',
      expect.objectContaining({
        method: 'POST',
        credentials: 'include',
        body: JSON.stringify({ username: 'admin', password: 'secret-password' }),
      }),
    )
  })

  it('uses credentials filled natively by a mobile password manager', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse({ code: 'INVALID_REFRESH_TOKEN' }, 401))
      .mockResolvedValueOnce(jsonResponse(session))
      .mockResolvedValueOnce(jsonResponse(systemStatus))
    vi.stubGlobal('fetch', fetchMock)

    render(<App />)
    const usernameInput = await screen.findByLabelText('Identifiant')
    const passwordInput = screen.getByLabelText('Mot de passe')
    const nativeValueSetter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set
    nativeValueSetter?.call(usernameInput, ' admin ')
    nativeValueSetter?.call(passwordInput, 'secret-password')

    fireEvent.submit(usernameInput.closest('form') as HTMLFormElement)

    await screen.findByRole('heading', { name: 'Piloter les livraisons' })
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      '/api/auth/login',
      expect.objectContaining({
        body: JSON.stringify({ username: 'admin', password: 'secret-password' }),
      }),
    )
  })

  it('injects an imported package into the plan form', async () => {
    const fetchMock = authenticatedFetch((input) => {
      if (input === '/api/import/packages/csv') {
        return jsonResponse({
          valid: true,
          importType: 'PACKAGES',
          filename: 'packages.csv',
          delimiter: ';',
          importedCount: 1,
          items: [{
            id: 'colis-001',
            label: 'Client Analakely',
            weightKg: 4.5,
            destination: { destinationQuery: 'Analakely, Antananarivo' },
          }],
          errors: [],
        })
      }
    })
    vi.stubGlobal('fetch', fetchMock)

    render(<App />)
    await openWorkspace()
    selectWorkspaceTab('Livraisons')
    expect(screen.getByRole('button', { name: 'Changer de mode' }).closest('nav')).toHaveClass('workspace-context-bar')
    expect(screen.getByRole('button', { name: 'Changer de mode' }).closest('header')).toBeNull()
    const file = new File(['id;label\ncolis-001;Client Analakely'], 'packages.csv', { type: 'text/csv' })
    fireEvent.change(screen.getByLabelText('Liste de colis à importer'), { target: { files: [file] } })

    expect(await screen.findByDisplayValue('Client Analakely')).toBeInTheDocument()
    expect(screen.getByText('packages.csv a remplacé la liste des colis.')).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/import/packages/csv',
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({ Authorization: 'Bearer test-access-token' }),
        body: expect.any(FormData),
      }),
    )
  })

  it('searches and selects the pickup point inside the plan', async () => {
    const fetchMock = authenticatedFetch((input) => {
      if (input === '/api/geocoding/search?q=Analakely%2C+Antananarivo') {
        return jsonResponse({
          query: 'Analakely, Antananarivo',
          provider: 'NOMINATIM',
          cached: false,
          resultCount: 1,
          candidates: [{
            id: 'N12345',
            osmType: 'NODE',
            osmId: 12345,
            displayName: 'Analakely, Antananarivo, Madagascar',
            latitude: -18.9089,
            longitude: 47.5212,
          }],
        })
      }
    })
    vi.stubGlobal('fetch', fetchMock)

    render(<App />)
    await openWorkspace()
    selectWorkspaceTab('Livraisons')
    fireEvent.click(screen.getByRole('button', { name: 'Choisir sur la carte' }))
    fireEvent.click(screen.getByRole('button', { name: 'Rechercher' }))
    fireEvent.click(await screen.findByRole('button', { name: /Analakely, Antananarivo, Madagascar/ }))
    fireEvent.click(screen.getByRole('button', { name: 'Confirmer ce lieu' }))

    expect(screen.getByText('Coordonnées : -18.908900, 47.521200')).toBeInTheDocument()
    expect(screen.getByText('Analakely, Antananarivo, Madagascar')).toBeInTheDocument()
  })

  it('does not duplicate a location search while the first request is pending', async () => {
    let resolveSearch!: (response: ReturnType<typeof jsonResponse>) => void
    const pendingSearch = new Promise<ReturnType<typeof jsonResponse>>((resolve) => {
      resolveSearch = resolve
    })
    const searchUrl = '/api/geocoding/search?q=Analakely%2C+Antananarivo'
    const fetchMock = authenticatedFetch((input) => input === searchUrl ? pendingSearch : undefined)
    vi.stubGlobal('fetch', fetchMock)

    render(<App />)
    await openWorkspace()
    selectWorkspaceTab('Livraisons')
    fireEvent.click(screen.getByRole('button', { name: 'Choisir sur la carte' }))
    const searchInput = screen.getByRole('textbox', { name: /Rechercher un lieu/ })
    fireEvent.click(screen.getByRole('button', { name: 'Rechercher' }))
    fireEvent.keyDown(searchInput, { key: 'Enter' })

    expect(fetchMock.mock.calls.filter(([input]) => input === searchUrl)).toHaveLength(1)

    resolveSearch(jsonResponse({
      query: 'Analakely, Antananarivo',
      provider: 'NOMINATIM',
      cached: false,
      resultCount: 1,
      candidates: [{
        id: 'N12345',
        osmType: 'NODE',
        osmId: 12345,
        displayName: 'Analakely, Antananarivo, Madagascar',
        latitude: -18.9089,
        longitude: 47.5212,
      }],
    }))
    expect(await screen.findByRole('button', { name: /Analakely, Antananarivo, Madagascar/ })).toBeInTheDocument()
  })

  it('searches and selects a destination from a package card', async () => {
    const fetchMock = authenticatedFetch((input) => {
      if (input === '/api/geocoding/search?q=Ankorondrano%2C+Antananarivo') {
        return jsonResponse({
          query: 'Ankorondrano, Antananarivo',
          provider: 'NOMINATIM',
          cached: false,
          resultCount: 1,
          candidates: [{
            id: 'W67890',
            osmType: 'WAY',
            osmId: 67890,
            displayName: 'Ankorondrano, Antananarivo, Madagascar',
            latitude: -18.8792,
            longitude: 47.5207,
          }],
        })
      }
    })
    vi.stubGlobal('fetch', fetchMock)

    render(<App />)
    await openWorkspace()
    selectWorkspaceTab('Livraisons')
    fireEvent.click(screen.getByRole('button', { name: /Ajouter votre premier colis/ }))

    fireEvent.click(within(screen.getByRole('region', { name: 'Colis' })).getByRole('button', { name: 'Choisir sur la carte' }))
    const destinationInput = screen.getByLabelText('Rechercher un lieu à Madagascar')
    fireEvent.change(destinationInput, { target: { value: 'Ankorondrano, Antananarivo' } })
    fireEvent.click(screen.getByRole('button', { name: 'Rechercher' }))
    fireEvent.click(await screen.findByRole('button', { name: /Ankorondrano, Antananarivo, Madagascar/ }))
    fireEvent.click(screen.getByRole('button', { name: 'Confirmer ce lieu' }))

    expect(screen.getByText('Coordonnées : -18.879200, 47.520700')).toBeInTheDocument()
    expect(screen.getByText('Ankorondrano, Antananarivo, Madagascar')).toBeInTheDocument()
  })

  it('previews the road route on a map before validation', async () => {
    const fetchMock = authenticatedFetch((input) => {
      if (input === '/api/routing/route') {
        return jsonResponse({
          provider: 'OSRM',
          preset: 'MAIN_ROADS',
          pointCount: 2,
          distanceMeters: 2900,
          durationSeconds: 540,
          geometry: {
            type: 'LineString',
            coordinates: [[47.5212, -18.9089], [47.5207, -18.8792]],
          },
          waypoints: [],
        })
      }
    })
    vi.stubGlobal('fetch', fetchMock)
    const draft = {
      schemaVersion: '1.0',
      pattern: 'SINGLE_PICKUP_MULTI_DELIVERY',
      planId: 'plan-carte-001',
      name: 'Contrôle des trajets',
      departureAt: '2026-08-05T08:00:00+03:00',
      timezone: 'Indian/Antananarivo',
      settings: { returnToDepot: false, routingPreset: 'MAIN_ROADS' },
      depot: { label: 'Analakely', destinationQuery: 'Analakely', latitude: -18.9089, longitude: 47.5212 },
      couriers: [],
      packages: [{
        id: 'colis-001',
        label: 'Client Ankorondrano',
        weightKg: 2.5,
        destination: { destinationQuery: 'Ankorondrano', latitude: -18.8792, longitude: 47.5207 },
      }],
    }
    const file = new File([JSON.stringify(draft)], 'plan-carte.json', { type: 'application/json' })
    Object.defineProperty(file, 'text', { value: async () => JSON.stringify(draft) })

    render(<App />)
    await openWorkspace()
    fireEvent.change(screen.getByLabelText('Ouvrir une sauvegarde'), { target: { files: [file] } })
    await screen.findByDisplayValue('Contrôle des trajets')
    selectWorkspaceTab('Livreurs')
    fireEvent.click(screen.getByRole('button', { name: 'Visualiser les trajets' }))

    expect(await screen.findByRole('heading', { name: 'Aperçu des trajets' })).toBeInTheDocument()
    expect(screen.getByTestId('plan-preview-map')).toBeInTheDocument()
    expect(await screen.findByTestId('preview-route')).toBeInTheDocument()
    expect(screen.getByText('2,9 km')).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/routing/route',
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({ Authorization: 'Bearer test-access-token' }),
      }),
    )
  })

  it('validates the unified plan after both CSV imports', async () => {
    const fetchMock = authenticatedFetch((input, init) => {
      if (input === '/api/import/packages/csv') {
        return jsonResponse({
          valid: true,
          importType: 'PACKAGES',
          filename: 'packages.csv',
          importedCount: 1,
          items: [{ id: 'colis-001', label: 'Client A', weightKg: 4.5, destination: { destinationQuery: 'Analakely' } }],
          errors: [],
        })
      }
      if (input === '/api/import/couriers/csv') {
        return jsonResponse({
          valid: true,
          importType: 'COURIERS',
          filename: 'couriers.csv',
          importedCount: 1,
          items: [{ id: 'livreur-001', name: 'Soa', vehicleType: 'MOTO', maxWeightKg: 30, averageSpeedKmh: 25 }],
          errors: [],
        })
      }
      if (input === '/api/plans/validate') {
        const plan = JSON.parse(String(init?.body))
        return jsonResponse({ valid: true, plan, errors: [] })
      }
    })
    vi.stubGlobal('fetch', fetchMock)

    render(<App />)
    await openWorkspace()
    selectWorkspaceTab('Livraisons')
    fireEvent.change(screen.getByLabelText('Liste de colis à importer'), {
      target: { files: [new File(['packages'], 'packages.csv', { type: 'text/csv' })] },
    })
    await screen.findByDisplayValue('Client A')
    fireEvent.click(screen.getByRole('button', { name: 'Ajouter un créneau' }))
    fireEvent.change(screen.getByLabelText('Livrer à partir de'), { target: { value: '2026-08-05T09:00' } })
    fireEvent.change(screen.getByLabelText('Livrer avant'), { target: { value: '2026-08-05T11:00' } })

    selectWorkspaceTab('Livreurs')
    fireEvent.change(screen.getByLabelText('Liste de livreurs à importer'), {
      target: { files: [new File(['couriers'], 'couriers.csv', { type: 'text/csv' })] },
    })
    await screen.findByDisplayValue('Soa')
    fireEvent.click(screen.getByRole('button', { name: 'Personnaliser' }))
    fireEvent.change(screen.getByLabelText('Heure de départ'), { target: { value: '2026-08-05T07:30' } })
    fireEvent.click(screen.getByRole('button', { name: 'Valider le plan' }))

    expect(await screen.findByText('Le plan est valide et prêt pour la génération du résultat.')).toBeInTheDocument()
    const validationCall = fetchMock.mock.calls.find(([input]) => input === '/api/plans/validate')
    const validatedPlan = JSON.parse(String(validationCall?.[1]?.body))
    expect(validatedPlan).toMatchObject({
      schemaVersion: '1.0',
      pattern: 'SINGLE_PICKUP_MULTI_DELIVERY',
      timezone: 'Indian/Antananarivo',
      depot: { destinationQuery: 'Analakely, Antananarivo' },
      packages: [{
        id: 'colis-001',
        deliveryWindow: {
          startAt: '2026-08-05T09:00:00+03:00',
          endAt: '2026-08-05T11:00:00+03:00',
        },
      }],
      couriers: [{
        id: 'livreur-001',
        departureAt: '2026-08-05T07:30:00+03:00',
        departureLocation: { destinationQuery: 'Analakely, Antananarivo' },
      }],
    })
  })

  it('loads and exports a portable JSON draft', async () => {
    vi.stubGlobal('fetch', authenticatedFetch())
    const draft = {
      schemaVersion: '1.0',
      pattern: 'SINGLE_PICKUP_MULTI_DELIVERY',
      planId: 'plan-reprise-001',
      name: 'Plan repris depuis un fichier',
      departureAt: '2026-08-05T08:00:00+03:00',
      timezone: 'Indian/Antananarivo',
      settings: { returnToDepot: true, routingPreset: 'MAIN_ROADS' },
      depot: { label: 'Dépôt Analakely', destinationQuery: 'Analakely' },
      couriers: [{ id: 'livreur-001', name: 'Soa', vehicleType: 'MOTO' }],
      packages: [{ id: 'colis-001', label: 'Client A', weightKg: 2.5, destination: { destinationQuery: 'Ankorondrano' } }],
    }
    const file = new File([JSON.stringify(draft)], 'plan-reprise.json', { type: 'application/json' })
    Object.defineProperty(file, 'text', { value: async () => JSON.stringify(draft) })
    const createObjectUrl = vi.fn().mockReturnValue('blob:test-plan')
    const revokeObjectUrl = vi.fn()
    vi.stubGlobal('URL', { ...URL, createObjectURL: createObjectUrl, revokeObjectURL: revokeObjectUrl })
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)

    render(<App />)
    await openWorkspace()
    fireEvent.change(screen.getByLabelText('Ouvrir une sauvegarde'), { target: { files: [file] } })

    expect(await screen.findByDisplayValue('Plan repris depuis un fichier')).toBeInTheDocument()
    selectWorkspaceTab('Livreurs')
    expect(screen.getByDisplayValue('Soa')).toBeInTheDocument()
    expect(screen.getByDisplayValue('30')).toBeInTheDocument()
    expect(screen.getByDisplayValue('25')).toBeInTheDocument()
    selectWorkspaceTab('Livraisons')
    expect(screen.getByDisplayValue('Client A')).toBeInTheDocument()
    expect(screen.getByText(/plan-reprise.json chargé/)).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Télécharger la sauvegarde' }))
    expect(createObjectUrl).toHaveBeenCalledWith(expect.any(Blob))
    expect(revokeObjectUrl).toHaveBeenCalledWith('blob:test-plan')
    expect(screen.getByText('Sauvegarde téléchargée.')).toBeInTheDocument()
  })

  it('generates and displays optimized courier routes', async () => {
    vi.spyOn(window, 'scrollTo').mockImplementation(() => undefined)
    const fetchMock = authenticatedFetch((input, init) => {
      if (input === '/api/plans/validate') {
        return jsonResponse({ valid: true, plan: JSON.parse(String(init?.body)), errors: [] })
      }
      if (input === '/api/plans/optimize') {
        return jsonResponse({
          schemaVersion: '1.0',
          sourcePlanId: 'plan-resultat-001',
          generatedAt: '2026-08-05T08:00:03+03:00',
          status: 'SUCCESS_WITH_WARNINGS',
          solverStatus: 'ROUTING_SUCCESS',
          computationTimeMillis: 321,
          dataContext: {
            routingProvider: 'OSRM',
            routingPreset: 'MAIN_ROADS',
            optimizer: 'OR_TOOLS',
            optimizerVersion: '9.15.6755',
          },
          summary: {
            packageCount: 2,
            assignedPackageCount: 1,
            unassignedPackageCount: 1,
            courierCount: 1,
            usedCourierCount: 1,
            totalAssignedWeightKg: 2.5,
            totalDistanceMeters: 2900,
            totalDurationSeconds: 418,
          },
          routes: [{
            courierId: 'livreur-001',
            courierName: 'Soa',
            vehicleType: 'MOTO',
            loadedWeightKg: 2.5,
            maxWeightKg: 30,
            remainingCapacityKg: 27.5,
            capacityUtilizationPercent: 8.33,
            departureLocation: { label: 'Itaosy', latitude: -18.92, longitude: 47.5 },
            departureAt: '2026-08-05T07:45:00+03:00',
            completedAt: '2026-08-05T08:06:58+03:00',
            distanceMeters: 2900,
            durationSeconds: 418,
            stops: [{
              order: 1,
              packageId: 'colis-001',
              packageLabel: 'Client Ankorondrano',
              phone: '+261340000000',
              weightKg: 2.5,
              destination: { label: 'Ankorondrano', latitude: -18.8792, longitude: 47.5207 },
              estimatedArrivalAt: '2026-08-05T08:06:58+03:00',
              distanceFromPreviousMeters: 2900,
              durationFromPreviousSeconds: 418,
              cumulativeDistanceMeters: 2900,
              cumulativeDurationSeconds: 418,
            }],
          }],
          unassignedPackages: [{
            packageId: 'colis-lourd',
            packageLabel: 'Colis très lourd',
            weightKg: 50,
            reason: 'OVERWEIGHT_FOR_ALL_COURIERS',
            message: 'Aucun livreur disponible ne peut porter ce colis.',
            minimumRequiredCapacityKg: 50,
          }],
          warnings: [],
        })
      }
      if (input === '/api/routing/route') {
        return jsonResponse({
          provider: 'OSRM',
          preset: 'MAIN_ROADS',
          pointCount: 3,
          distanceMeters: 2900,
          durationSeconds: 418,
          geometry: {
            type: 'LineString',
            coordinates: [[47.5, -18.92], [47.5212, -18.9089], [47.5207, -18.8792]],
          },
          waypoints: [],
        })
      }
    })
    vi.stubGlobal('fetch', fetchMock)
    const createObjectUrl = vi.fn().mockReturnValue('blob:dispatch-export')
    const revokeObjectUrl = vi.fn()
    vi.stubGlobal('URL', { ...URL, createObjectURL: createObjectUrl, revokeObjectURL: revokeObjectUrl })
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)
    const print = vi.spyOn(window, 'print').mockImplementation(() => undefined)
    const draft = {
      schemaVersion: '1.0',
      pattern: 'SINGLE_PICKUP_MULTI_DELIVERY',
      planId: 'plan-resultat-001',
      name: 'Tournée résultat',
      departureAt: '2026-08-05T08:00:00+03:00',
      timezone: 'Indian/Antananarivo',
      settings: { returnToDepot: false, routingPreset: 'MAIN_ROADS' },
      depot: { label: 'Analakely', latitude: -18.9089, longitude: 47.5212 },
      couriers: [{
        id: 'livreur-001',
        name: 'Soa',
        vehicleType: 'MOTO',
        maxWeightKg: 30,
        averageSpeedKmh: 25,
        departureAt: '2026-08-05T07:45:00+03:00',
        departureLocation: { label: 'Itaosy', latitude: -18.92, longitude: 47.5 },
      }],
      packages: [
        { id: 'colis-001', label: 'Client Ankorondrano', weightKg: 2.5, destination: { label: 'Ankorondrano', latitude: -18.8792, longitude: 47.5207 } },
        { id: 'colis-lourd', label: 'Colis très lourd', weightKg: 50, destination: { label: 'Ivandry', latitude: -18.865, longitude: 47.52 } },
      ],
    }
    const file = new File([JSON.stringify(draft)], 'plan-resultat.json', { type: 'application/json' })
    Object.defineProperty(file, 'text', { value: async () => JSON.stringify(draft) })

    render(<App />)
    await openWorkspace()
    fireEvent.change(screen.getByLabelText('Ouvrir une sauvegarde'), { target: { files: [file] } })
    await screen.findByDisplayValue('Tournée résultat')
    selectWorkspaceTab('Livreurs')
    fireEvent.click(screen.getByRole('button', { name: 'Générer les tournées' }))

    expect(await screen.findByRole('heading', { name: 'Les tournées sont prêtes.' })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: /Liste des livraisons/ })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByRole('heading', { name: 'Colis par livreur' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Soa' })).toBeInTheDocument()
    expect(screen.getByText('Client Ankorondrano')).toBeInTheDocument()
    expect(screen.getByText('Ankorondrano')).toBeInTheDocument()
    expect(screen.getAllByText(/08.*06/).length).toBeGreaterThan(0)
    expect(screen.getByText('Colis très lourd')).toBeInTheDocument()
    expect(screen.getByText('Colis trop lourd')).toBeInTheDocument()
    expect(screen.queryByText(/Le livreur s’organise/)).not.toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Carte des tournées' })).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Excel' }))
    expect(createObjectUrl).toHaveBeenCalledWith(expect.any(Blob))
    expect(revokeObjectUrl).toHaveBeenCalledWith('blob:dispatch-export')
    fireEvent.click(screen.getByRole('button', { name: 'PDF / Imprimer' }))
    expect(print).toHaveBeenCalledOnce()

    fireEvent.click(screen.getByRole('tab', { name: 'Détails' }))
    expect(screen.getByRole('tab', { name: 'Détails' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByRole('heading', { name: 'Carte des tournées' })).toBeInTheDocument()
    expect(screen.queryByText('Distance totale')).not.toBeInTheDocument()
    expect(screen.queryByText('Calcul')).not.toBeInTheDocument()
    expect(await screen.findByTestId('preview-route')).toBeInTheDocument()

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/plans/optimize',
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({ Authorization: 'Bearer test-access-token' }),
      }),
    )
    const routeCall = fetchMock.mock.calls.find(([input]) => input === '/api/routing/route')
    expect(JSON.parse(String(routeCall?.[1]?.body))).toEqual({ points: [
      { id: 'start-livreur-001', latitude: -18.92, longitude: 47.5 },
      { id: 'pickup-livreur-001', latitude: -18.9089, longitude: 47.5212 },
      { id: 'livreur-001-colis-001', latitude: -18.8792, longitude: 47.5207 },
    ] })

    fireEvent.click(screen.getByRole('button', { name: 'Modifier le plan' }))
    expect(await screen.findByRole('heading', { name: 'Construisez votre tournée.' })).toBeInTheDocument()
  })
})
