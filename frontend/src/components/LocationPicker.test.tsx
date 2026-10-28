import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import type { ReactNode } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { LocationPicker } from './LocationPicker'

vi.mock('react-leaflet', () => {
  const map = { setView: vi.fn(), invalidateSize: vi.fn() }
  return {
    MapContainer: ({ children }: { children: ReactNode }) => <div data-testid="location-map">{children}</div>,
    TileLayer: () => null,
    CircleMarker: ({ children }: { children: ReactNode }) => <div>{children}</div>,
    Popup: ({ children }: { children: ReactNode }) => <div>{children}</div>,
    useMap: () => map,
    useMapEvents: () => map,
  }
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('LocationPicker', () => {
  it('lets mobile users switch explicitly between search and map panels', () => {
    render(
      <LocationPicker
        accessToken="test-token"
        value={{ label: '', destinationQuery: '' }}
        title="Point de retrait"
        description="Recherchez un lieu."
        onChange={vi.fn()}
      />,
    )

    fireEvent.click(screen.getByRole('button', { name: 'Choisir sur la carte' }))

    const dialog = screen.getByRole('dialog', { name: 'Point de retrait' })
    const navigation = within(dialog).getByRole('navigation', { name: 'Mode de sélection du lieu' })
    const body = dialog.querySelector('.location-modal__body')

    expect(body).toHaveClass('is-search-view')
    expect(within(navigation).getByRole('button', { name: 'Recherche' })).toHaveAttribute('aria-pressed', 'true')

    fireEvent.click(within(navigation).getByRole('button', { name: 'Carte' }))

    expect(body).toHaveClass('is-map-view')
    expect(within(navigation).getByRole('button', { name: 'Carte' })).toHaveAttribute('aria-pressed', 'true')

    fireEvent.click(within(navigation).getByRole('button', { name: 'Recherche' }))
    expect(body).toHaveClass('is-search-view')
  })

  it('can confirm an existing point directly from the mobile map panel', () => {
    const onChange = vi.fn()
    render(
      <LocationPicker
        accessToken="test-token"
        value={{
          label: 'Analakely',
          destinationQuery: 'Analakely, Antananarivo',
          latitude: -18.9089,
          longitude: 47.5212,
        }}
        title="Destination"
        description="Vérifiez le point."
        onChange={onChange}
      />,
    )

    fireEvent.click(screen.getByRole('button', { name: 'Modifier' }))
    const dialog = screen.getByRole('dialog', { name: 'Destination' })
    const navigation = within(dialog).getByRole('navigation', { name: 'Mode de sélection du lieu' })
    fireEvent.click(within(navigation).getByRole('button', { name: 'Carte' }))
    fireEvent.click(within(dialog).getByRole('button', { name: 'Utiliser ce point' }))

    expect(onChange).toHaveBeenCalledWith(expect.objectContaining({
      latitude: -18.9089,
      longitude: 47.5212,
    }))
    expect(screen.queryByRole('dialog', { name: 'Destination' })).not.toBeInTheDocument()
  })

  it('shows concise Nominatim labels while preserving the complete address on confirmation', async () => {
    const onChange = vi.fn()
    const completeAddress = 'Analakely, Antananarivo, Analamanga, Province d’Antananarivo, Madagascar'
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        query: 'Analakely',
        provider: 'nominatim',
        cached: false,
        resultCount: 1,
        candidates: [{
          id: 'node-1',
          osmType: 'node',
          osmId: 1,
          displayName: completeAddress,
          latitude: -18.9089,
          longitude: 47.5212,
        }],
      }),
    })
    vi.stubGlobal('fetch', fetchMock)

    render(
      <LocationPicker
        accessToken="test-token"
        value={{ label: '', destinationQuery: '' }}
        title="Destination"
        description="Recherchez un lieu."
        searchOptions={{
          purpose: 'DESTINATION',
          requestKind: 'EXPRESS',
          clientAccountId: 'client-123',
        }}
        onChange={onChange}
      />,
    )

    fireEvent.click(screen.getByRole('button', { name: 'Choisir sur la carte' }))
    const dialog = screen.getByRole('dialog', { name: 'Destination' })
    const input = within(dialog).getByRole('textbox', { name: 'Rechercher un lieu à Madagascar' })
    fireEvent.change(input, { target: { value: 'Analakely' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Rechercher' }))

    const candidate = await within(dialog).findByRole('button', { name: /Analakely, Antananarivo/ })
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/geocoding/search?q=Analakely&purpose=DESTINATION&requestKind=EXPRESS&clientAccountId=client-123',
      expect.objectContaining({ signal: expect.any(AbortSignal) }),
    )
    expect(candidate).not.toHaveTextContent('Analamanga')
    fireEvent.click(candidate)

    expect(input).toHaveValue('Analakely, Antananarivo')
    expect(within(dialog).queryByText(completeAddress)).not.toBeInTheDocument()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Confirmer ce lieu' }))

    expect(onChange).toHaveBeenCalledWith(expect.objectContaining({
      label: 'Analakely, Antananarivo',
      destinationQuery: completeAddress,
      latitude: -18.9089,
      longitude: 47.5212,
    }))
  })

  it('shortens an already saved full address in the collapsed picker', () => {
    render(
      <LocationPicker
        accessToken="test-token"
        value={{
          label: 'Analakely, Antananarivo, Analamanga, Madagascar',
          destinationQuery: 'Analakely, Antananarivo, Analamanga, Madagascar',
          latitude: -18.9089,
          longitude: 47.5212,
        }}
        title="Destination"
        description="Vérifiez le point."
        onChange={vi.fn()}
      />,
    )

    expect(screen.getByText('Analakely, Antananarivo')).toBeInTheDocument()
    expect(screen.queryByText('Analakely, Antananarivo, Analamanga, Madagascar')).not.toBeInTheDocument()
  })

  it('explains when a destination search has no result inside the served area', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        query: 'Hors zone',
        provider: 'nominatim',
        cached: false,
        resultCount: 0,
        candidates: [],
      }),
    }))

    render(
      <LocationPicker
        accessToken="test-token"
        value={{ label: '', destinationQuery: '' }}
        title="Destination"
        description="Recherchez un lieu."
        searchOptions={{ purpose: 'DESTINATION', requestKind: 'NORMAL' }}
        onChange={vi.fn()}
      />,
    )

    fireEvent.click(screen.getByRole('button', { name: 'Choisir sur la carte' }))
    const dialog = screen.getByRole('dialog', { name: 'Destination' })
    fireEvent.change(within(dialog).getByRole('textbox', { name: 'Rechercher un lieu à Madagascar' }), {
      target: { value: 'Hors zone' },
    })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Rechercher' }))

    expect(await within(dialog).findByText('Aucun lieu trouvé dans la zone desservie.')).toBeInTheDocument()
  })
})
