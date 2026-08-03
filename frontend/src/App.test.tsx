import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'

vi.mock('react-leaflet', () => ({
  CircleMarker: ({ children }: { children?: ReactNode }) => <div>{children}</div>,
  GeoJSON: () => <div data-testid="route-geometry" />,
  MapContainer: ({ children }: { children?: ReactNode }) => <div data-testid="routing-map">{children}</div>,
  Marker: ({ children }: { children?: ReactNode }) => <div>{children}</div>,
  Popup: ({ children }: { children?: ReactNode }) => <div>{children}</div>,
  TileLayer: () => null,
  useMap: () => ({ fitBounds: vi.fn() }),
  useMapEvents: () => undefined,
}))

import { App } from './App'

const session = {
  tokenType: 'Bearer',
  accessToken: 'test-access-token',
  expiresIn: 900,
  account: { id: '9ac90f82-9f3e-4ccb-b11f-d2cf804f3a1e', username: 'admin' },
}

function jsonResponse(body: unknown, status = 200) {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
  }
}

describe('App', () => {
  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
  })

  it('restores the session and confirms that the platform is operational', async () => {
    const fetchMock = vi.fn().mockImplementation((input: string) => {
      if (input === '/api/auth/refresh') return Promise.resolve(jsonResponse(session))
      if (input === '/api/system/status') {
        return Promise.resolve(jsonResponse({
          service: 'UP',
          database: 'UP',
          checkedAt: '2026-08-02T12:00:00Z',
        }))
      }
      throw new Error(`Unexpected request: ${input}`)
    })
    vi.stubGlobal('fetch', fetchMock)

    render(<App />)

    await waitFor(() => expect(screen.getAllByText('Opérationnel')).toHaveLength(3))
    expect(screen.getByText(/Connecté/)).toHaveTextContent('admin')
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/system/status',
      expect.objectContaining({
        headers: expect.objectContaining({ Authorization: 'Bearer test-access-token' }),
      }),
    )
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

  it('logs in from the form', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse({ code: 'INVALID_REFRESH_TOKEN' }, 401))
      .mockResolvedValueOnce(jsonResponse(session))
      .mockResolvedValueOnce(jsonResponse({
        service: 'UP',
        database: 'UP',
        checkedAt: '2026-08-02T12:00:00Z',
      }))
    vi.stubGlobal('fetch', fetchMock)

    render(<App />)
    fireEvent.change(await screen.findByLabelText('Identifiant'), { target: { value: 'admin' } })
    fireEvent.change(screen.getByLabelText('Mot de passe'), { target: { value: 'secret-password' } })
    fireEvent.click(screen.getByRole('button', { name: 'Se connecter' }))

    await screen.findByRole('heading', { name: 'Préparez vos livraisons.' })
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

  it('imports a package file and displays its preview', async () => {
    const fetchMock = vi.fn().mockImplementation((input: string) => {
      if (input === '/api/auth/refresh') return Promise.resolve(jsonResponse(session))
      if (input === '/api/system/status') {
        return Promise.resolve(jsonResponse({
          service: 'UP',
          database: 'UP',
          checkedAt: '2026-08-02T12:00:00Z',
        }))
      }
      if (input === '/api/import/packages/csv') {
        return Promise.resolve(jsonResponse({
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
        }))
      }
      throw new Error(`Unexpected request: ${input}`)
    })
    vi.stubGlobal('fetch', fetchMock)

    render(<App />)
    const input = await screen.findByLabelText('Fichier colis')
    const file = new File([
      'id;label;weight_kg;destination_query;latitude;longitude;phone\n',
      'colis-001;Client Analakely;4.5;Analakely, Antananarivo;;;\n',
    ], 'packages.csv', { type: 'text/csv' })

    fireEvent.change(input, { target: { files: [file] } })
    fireEvent.click(screen.getByRole('button', { name: 'Importer les colis' }))

    expect(await screen.findByText('Client Analakely')).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('1 ligne prête')
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/import/packages/csv',
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({ Authorization: 'Bearer test-access-token' }),
        body: expect.any(FormData),
      }),
    )
  })

  it('searches and selects a geocoding candidate', async () => {
    const fetchMock = vi.fn().mockImplementation((input: string) => {
      if (input === '/api/auth/refresh') return Promise.resolve(jsonResponse(session))
      if (input === '/api/system/status') {
        return Promise.resolve(jsonResponse({
          service: 'UP',
          database: 'UP',
          checkedAt: '2026-08-02T12:00:00Z',
        }))
      }
      if (input === '/api/geocoding/search?q=Analakely%2C+Antananarivo') {
        return Promise.resolve(jsonResponse({
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
      }
      throw new Error(`Unexpected request: ${input}`)
    })
    vi.stubGlobal('fetch', fetchMock)

    render(<App />)
    const addressInput = await screen.findByLabelText('Adresse, quartier ou point de repère')
    fireEvent.change(addressInput, { target: { value: 'Analakely, Antananarivo' } })
    fireEvent.click(screen.getByRole('button', { name: 'Rechercher' }))

    const candidate = await screen.findByRole('button', { name: /Analakely, Antananarivo, Madagascar/ })
    fireEvent.click(candidate)

    expect(screen.getByText('Destination sélectionnée').parentElement)
      .toHaveTextContent('-18.908900, 47.521200')
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/geocoding/search?q=Analakely%2C+Antananarivo',
      expect.objectContaining({
        headers: expect.objectContaining({ Authorization: 'Bearer test-access-token' }),
      }),
    )
  })

  it('calculates and displays the sandbox route', async () => {
    const fetchMock = vi.fn().mockImplementation((input: string) => {
      if (input === '/api/auth/refresh') return Promise.resolve(jsonResponse(session))
      if (input === '/api/system/status') {
        return Promise.resolve(jsonResponse({
          service: 'UP', database: 'UP', checkedAt: '2026-08-02T12:00:00Z',
        }))
      }
      if (input === '/api/routing/route') {
        return Promise.resolve(jsonResponse({
          provider: 'OSRM',
          preset: 'MAIN_ROADS',
          pointCount: 2,
          distanceMeters: 3142.5,
          durationSeconds: 482,
          geometry: {
            type: 'LineString',
            coordinates: [[47.5262845, -18.9083181], [47.522924, -18.8859714]],
          },
          waypoints: [
            {
              id: 'start',
              inputLatitude: -18.9083181,
              inputLongitude: 47.5262845,
              snappedLatitude: -18.9083,
              snappedLongitude: 47.5263,
              distanceToRoadMeters: 2.4,
              roadName: 'Lalana Andrianampoinimerina',
            },
            {
              id: 'end',
              inputLatitude: -18.8859714,
              inputLongitude: 47.522924,
              snappedLatitude: -18.886,
              snappedLongitude: 47.5229,
              distanceToRoadMeters: 3.1,
              roadName: 'Route des Hydrocarbures',
            },
          ],
          legs: [{
            fromIndex: 0,
            toIndex: 1,
            fromId: 'start',
            toId: 'end',
            distanceMeters: 3142.5,
            durationSeconds: 482,
          }],
        }))
      }
      throw new Error(`Unexpected request: ${input}`)
    })
    vi.stubGlobal('fetch', fetchMock)

    render(<App />)
    fireEvent.click(await screen.findByRole('button', { name: 'Calculer l’itinéraire' }))

    expect(await screen.findByText('OSRM · MAIN_ROADS')).toBeInTheDocument()
    expect(screen.getAllByText(/Lalana Andrianampoinimerina/)).toHaveLength(2)
    expect(screen.getByTestId('route-geometry')).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/routing/route',
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({ Authorization: 'Bearer test-access-token' }),
        body: JSON.stringify({
          points: [
            { id: 'start', latitude: -18.9083181, longitude: 47.5262845 },
            { id: 'end', latitude: -18.8859714, longitude: 47.522924 },
          ],
        }),
      }),
    )
  })
})
