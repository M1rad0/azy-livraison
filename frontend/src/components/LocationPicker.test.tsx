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

afterEach(() => cleanup())

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
})
