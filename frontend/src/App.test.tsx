import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import type { ReactNode } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { App } from './App'

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
  fireEvent.click(await screen.findByRole('button', { name: 'Choisir ce mode' }))
  await screen.findByRole('heading', { name: 'Construisez votre tournée.' })
}

function selectWorkspaceTab(name: 'Plan' | 'Livraisons' | 'Livreurs') {
  fireEvent.click(screen.getByRole('tab', { name: new RegExp(`^${name}`) }))
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
    expect(screen.getByRole('heading', { name: 'Plusieurs clients' })).toBeInTheDocument()
    await waitFor(() => expect(screen.getByText('Session active')).toBeInTheDocument())
    expect(screen.getByText('admin')).toBeInTheDocument()
  })

  it('keeps file actions above the workspace and changes the sidebar with the active step', async () => {
    vi.stubGlobal('fetch', authenticatedFetch())
    render(<App />)

    document.documentElement.scrollTop = 240
    await openWorkspace()
    expect(document.documentElement.scrollTop).toBe(0)
    expect(screen.getByRole('main')).toHaveClass('app-shell--workspace')
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

  it('edits withdrawals inline, packages in dialogs, then shows the multi-client summary', async () => {
    vi.stubGlobal('fetch', authenticatedFetch())
    render(<App />)

    fireEvent.click(await screen.findByRole('button', { name: 'Choisir le mode multi-clients' }))
    expect(await screen.findByRole('heading', { name: 'Rassemblez toutes les courses.' })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Plan' })).toHaveAttribute('aria-selected', 'true')

    selectWorkspaceTab('Livraisons')
    expect(screen.getByRole('tab', { name: /^Livraisons/ })).toHaveAttribute('aria-selected', 'true')
    fireEvent.click(screen.getByRole('button', { name: 'Ajouter un client' }))
    fireEvent.change(screen.getByLabelText('Nom du client'), { target: { value: 'Client Alpha' } })
    fireEvent.click(screen.getByRole('button', { name: /Ajouter un retrait/ }))

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Nom de la course'), { target: { value: 'Course Alpha' } })

    expect(screen.getByText('Course Alpha')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Réduire' }))
    expect(screen.queryByLabelText('Nom de la course')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Déployer' }))
    expect(screen.getByLabelText('Nom de la course')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /Ajouter les colis de ce retrait/ }))

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
    expect(within(summaryDialog).getByText('Course Alpha')).toBeInTheDocument()
    expect(within(summaryDialog).getByText('Colis Bureau')).toBeInTheDocument()
    expect(screen.getByText('Plusieurs clients · plusieurs retraits')).toBeInTheDocument()
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

    expect(screen.getByText('-18.908900, 47.521200')).toBeInTheDocument()
    expect(screen.getByText('Analakely, Antananarivo, Madagascar')).toBeInTheDocument()
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

    expect(screen.getByText('-18.879200, 47.520700')).toBeInTheDocument()
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
