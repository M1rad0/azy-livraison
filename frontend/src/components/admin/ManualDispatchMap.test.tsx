import { cleanup, render, within } from '@testing-library/react'
import type { ReactNode } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ManualDispatchMap } from './ManualDispatchMap'
import { draftFixture } from './manualDispatch.fixtures'

vi.mock('react-leaflet', () => ({
  MapContainer: ({ children }: { children: ReactNode }) => <div>{children}</div>,
  TileLayer: () => null,
  CircleMarker: ({ children }: { children?: ReactNode }) => <div>{children}</div>,
  Tooltip: ({ children }: { children: ReactNode }) => <div>{children}</div>,
  Popup: ({ children }: { children: ReactNode }) => <div>{children}</div>,
  useMap: () => ({ invalidateSize: vi.fn(), fitBounds: vi.fn() }),
}))

describe('résumés cartographiques du dispatch', () => {
  afterEach(cleanup)

  it('résume un retrait par libellé, adresse courte, vendeurs distincts et nombre de colis', () => {
    const draft = draftFixture()
    draft.pickups[0].location = { ...draft.pickups[0].location, label: 'Chez Soa', destinationQuery: 'Analakely, Antananarivo, Analamanga, Madagascar' }
    const { container } = render(<ManualDispatchMap draft={draft} phase="COLLECTION" onSelect={vi.fn()} />)
    const summary = container.querySelector('.manual-dispatch-map__popup') as HTMLElement
    expect(within(summary).getByText('Chez Soa')).toBeInTheDocument()
    expect(within(summary).getByText('Analakely, Antananarivo')).toBeInTheDocument()
    expect(within(summary).getAllByText('Boutique')).toHaveLength(1)
    expect(within(summary).getByText('2 colis')).toBeInTheDocument()
    expect(summary.textContent).not.toContain('Destinataire')
    expect(summary.textContent).not.toContain('Analamanga')
    expect(summary.textContent).not.toContain('034')
  })

  it('garde les informations propres à chaque colis sur la carte des livraisons', () => {
    const { container } = render(<ManualDispatchMap draft={draftFixture()} phase="DELIVERY" onSelect={vi.fn()} />)
    const summary = container.querySelector('.manual-dispatch-map__popup') as HTMLElement
    const cards = within(summary).getAllByRole('button')
    expect(cards).toHaveLength(3)
    expect(within(cards[0]).getByText('Destination 1')).toBeInTheDocument()
    cards.forEach((card) => expect(within(card).getByText('Boutique')).toBeInTheDocument())
  })
})
