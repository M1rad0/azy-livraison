import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { App } from './App'

const session = {
  tokenType: 'Bearer',
  accessToken: 'test-access-token',
  expiresIn: 900,
  account: { id: '9ac90f82-9f3e-4ccb-b11f-d2cf804f3a1e', username: 'admin' },
}

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

function authenticatedFetch(handler?: (input: string, init?: RequestInit) => unknown) {
  return vi.fn().mockImplementation((input: string, init?: RequestInit) => {
    if (input === '/api/auth/refresh') return Promise.resolve(jsonResponse(session))
    if (input === '/api/system/status') return Promise.resolve(jsonResponse(systemStatus))
    const handled = handler?.(input, init)
    if (handled) return Promise.resolve(handled)
    throw new Error(`Unexpected request: ${input}`)
  })
}

async function openWorkspace() {
  fireEvent.click(await screen.findByRole('button', { name: 'Choisir ce pattern →' }))
  await screen.findByRole('heading', { name: 'Construisez votre tournée.' })
}

describe('App', () => {
  afterEach(() => {
    cleanup()
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('restores the session and displays the pattern choice', async () => {
    const fetchMock = authenticatedFetch()
    vi.stubGlobal('fetch', fetchMock)

    render(<App />)

    expect(await screen.findByRole('heading', { name: 'Quel trajet préparez-vous ?' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Un retrait, plusieurs livraisons' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Plusieurs groupes de livraison' })).toBeInTheDocument()
    await waitFor(() => expect(screen.getAllByText('Opérationnel')).toHaveLength(3))
    expect(screen.getByText(/Connecté/)).toHaveTextContent('admin')
  })

  it('shows the login form when no refresh session exists', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({
      code: 'INVALID_REFRESH_TOKEN',
      message: 'Session expirée.',
    }, 401)))

    render(<App />)

    expect(await screen.findByRole('heading', { name: 'Bienvenue' })).toBeInTheDocument()
    expect(screen.getByLabelText('Identifiant')).toBeInTheDocument()
    expect(screen.getByLabelText('Mot de passe')).toHaveAttribute('type', 'password')
  })

  it('logs in and opens the pattern choice', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse({ code: 'INVALID_REFRESH_TOKEN' }, 401))
      .mockResolvedValueOnce(jsonResponse(session))
      .mockResolvedValueOnce(jsonResponse(systemStatus))
    vi.stubGlobal('fetch', fetchMock)

    render(<App />)
    fireEvent.change(await screen.findByLabelText('Identifiant'), { target: { value: 'admin' } })
    fireEvent.change(screen.getByLabelText('Mot de passe'), { target: { value: 'secret-password' } })
    fireEvent.click(screen.getByRole('button', { name: 'Se connecter' }))

    await screen.findByRole('heading', { name: 'Quel trajet préparez-vous ?' })
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
    const file = new File(['id;label\ncolis-001;Client Analakely'], 'packages.csv', { type: 'text/csv' })
    fireEvent.change(screen.getByLabelText('Fichier CSV colis'), { target: { files: [file] } })

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
    fireEvent.click(screen.getByRole('button', { name: 'Rechercher' }))
    fireEvent.click(await screen.findByRole('button', { name: /Analakely, Antananarivo, Madagascar/ }))

    expect(screen.getByLabelText('Latitude')).toHaveValue(-18.9089)
    expect(screen.getByLabelText('Longitude')).toHaveValue(47.5212)
    expect(screen.getAllByDisplayValue('Analakely, Antananarivo, Madagascar')).toHaveLength(3)
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
    fireEvent.change(screen.getByLabelText('Fichier CSV colis'), {
      target: { files: [new File(['packages'], 'packages.csv', { type: 'text/csv' })] },
    })
    fireEvent.change(screen.getByLabelText('Fichier CSV livreurs'), {
      target: { files: [new File(['couriers'], 'couriers.csv', { type: 'text/csv' })] },
    })
    await screen.findByDisplayValue('Client A')
    await screen.findByDisplayValue('Soa')
    fireEvent.click(screen.getByRole('button', { name: 'Valider le plan' }))

    expect(await screen.findByText('Le plan est valide et prêt pour la génération du résultat.')).toBeInTheDocument()
    const validationCall = fetchMock.mock.calls.find(([input]) => input === '/api/plans/validate')
    const validatedPlan = JSON.parse(String(validationCall?.[1]?.body))
    expect(validatedPlan).toMatchObject({
      schemaVersion: '1.0',
      pattern: 'SINGLE_PICKUP_MULTI_DELIVERY',
      timezone: 'Indian/Antananarivo',
      depot: { destinationQuery: 'Analakely, Antananarivo' },
      packages: [{ id: 'colis-001' }],
      couriers: [{ id: 'livreur-001' }],
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
      couriers: [{ id: 'livreur-001', name: 'Soa', vehicleType: 'MOTO', maxWeightKg: 30, averageSpeedKmh: 25 }],
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
    fireEvent.change(screen.getByLabelText('Ouvrir un brouillon'), { target: { files: [file] } })

    expect(await screen.findByDisplayValue('Plan repris depuis un fichier')).toBeInTheDocument()
    expect(screen.getByDisplayValue('Soa')).toBeInTheDocument()
    expect(screen.getByDisplayValue('Client A')).toBeInTheDocument()
    expect(screen.getByText(/plan-reprise.json chargé/)).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Exporter le brouillon' }))
    expect(createObjectUrl).toHaveBeenCalledWith(expect.any(Blob))
    expect(revokeObjectUrl).toHaveBeenCalledWith('blob:test-plan')
    expect(screen.getByText('Brouillon JSON exporté.')).toBeInTheDocument()
  })
})
