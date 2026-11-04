import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { PostponedPackage } from '../../api/admin'
import type { Location } from '../../api/csvImports'
import { AdminPostponedPackagesPanel } from './AdminPostponedPackagesPanel'

vi.mock('../LocationPicker', () => ({
  LocationPicker: ({ onChange }: { onChange: (location: Location) => void }) => (
    <button type="button" onClick={() => onChange({ destinationQuery: 'Alasora', latitude: -18.96, longitude: 47.57 })}>Choisir un nouveau retrait</button>
  ),
}))

const item: PostponedPackage = {
  serviceDate: '2099-02-01',
  package: {
    packageId: 'package-1', deliveryRequestId: 'request-1', ownerAccountId: 'client-1',
    ownerDisplayName: 'Boutique Soa', requestedDate: '2099-02-01', requestKind: 'NORMAL',
    pickup: { label: 'Boutique', query: 'Analakely', latitude: -18.91, longitude: 47.52 },
    sequenceNumber: 1, recipientName: 'Rakoto', faritra: false,
    destination: { label: 'Ampasampito', latitude: -18.89, longitude: 47.54 },
    contact: '032 00 000 00', amountToCollectAriary: 25_000, mobileMoneyPaid: false,
    technicalStatus: 'DISPATCHED', finalOutcome: 'POSTPONED', outcomeNote: 'Destinataire absent',
    finalState: true, recoveredAriary: 0, chargedDeliveryFeeAriary: 0, amountToRemitAriary: 0, version: 3,
  },
}

function response(body: unknown, status = 200) {
  return { ok: status >= 200 && status < 300, status, json: async () => body }
}

describe('AdminPostponedPackagesPanel', () => {
  afterEach(() => {
    cleanup()
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('reprogramme un colis après clôture à la date choisie avec un nouveau retrait', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(response([item]))
      .mockResolvedValueOnce(response({ deliveryRequestId: 'request-next', packageId: 'package-next', requestedDate: '2099-02-18' }))
      .mockResolvedValueOnce(response([]))
    const onChanged = vi.fn()
    vi.stubGlobal('fetch', fetchMock)

    render(<AdminPostponedPackagesPanel accessToken="admin-token" onChanged={onChanged} />)
    expect(await screen.findByText('Rakoto')).toBeInTheDocument()
    expect(screen.getByText('Destinataire absent')).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledTimes(1)
    fireEvent.click(screen.getByRole('button', { name: 'Reprogrammer Rakoto' }))

    const date = screen.getByLabelText('Date de livraison')
    expect(date).toHaveValue('')
    expect(date).toHaveAttribute('min', '2099-02-02')
    const confirm = screen.getByRole('button', { name: 'Reprogrammer le colis' })
    expect(confirm).toBeDisabled()
    fireEvent.change(date, { target: { value: '2099-02-01' } })
    expect(confirm).toBeDisabled()
    fireEvent.change(date, { target: { value: '2099-02-18' } })
    fireEvent.change(screen.getByLabelText('Libellé du retrait'), { target: { value: 'Colis chez Hery' } })
    fireEvent.click(screen.getByRole('button', { name: 'Choisir un nouveau retrait' }))
    fireEvent.click(confirm)

    await waitFor(() => expect(onChanged).toHaveBeenCalledOnce())
    expect(fetchMock).toHaveBeenCalledWith('/api/admin/delivery-days/2099-02-01/packages/package-1/carry-forward', expect.objectContaining({
      method: 'POST',
      body: JSON.stringify({ targetDate: '2099-02-18', pickup: { label: 'Colis chez Hery', query: 'Alasora', latitude: -18.96, longitude: 47.57 }, version: 3 }),
    }))
    expect(await screen.findByText('Aucun colis à reprogrammer')).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('18 février 2099')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('conserve le colis et le formulaire lorsque la capacité de la date choisie est dépassée', async () => {
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce(response([item]))
      .mockResolvedValueOnce(response({ message: 'La capacité journalière est atteinte.' }, 409)))
    const onChanged = vi.fn()
    render(<AdminPostponedPackagesPanel accessToken="admin-token" onChanged={onChanged} />)

    fireEvent.click(await screen.findByRole('button', { name: 'Reprogrammer Rakoto' }))
    fireEvent.change(screen.getByLabelText('Date de livraison'), { target: { value: '2099-02-18' } })
    fireEvent.click(screen.getByRole('button', { name: 'Reprogrammer le colis' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('La capacité journalière est atteinte.')
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    expect(screen.getByLabelText('Date de livraison')).toHaveValue('2099-02-18')
    expect(screen.getByText('Rakoto')).toBeInTheDocument()
    expect(onChanged).not.toHaveBeenCalled()
  })

  it('recherche dans les reports et explique le passage d’un express en livraison normale', async () => {
    const express = { ...item, package: { ...item.package, requestKind: 'EXPRESS' as const } }
    const fetchMock = vi.fn().mockResolvedValue(response([express]))
    vi.stubGlobal('fetch', fetchMock)
    render(<AdminPostponedPackagesPanel accessToken="admin-token" onChanged={vi.fn()} />)
    await screen.findByText('Rakoto')
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: ' Boutique Soa ' } })
    fireEvent.click(screen.getByRole('button', { name: 'Rechercher' }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/admin/delivery-packages/postponed?query=Boutique%20Soa', expect.anything()))

    fireEvent.click(screen.getByRole('button', { name: 'Reprogrammer Rakoto' }))
    expect(screen.getByText('Cette livraison express rejoindra les livraisons normales à la date choisie. Son tarif sera conservé.')).toBeInTheDocument()
    expect(fetchMock.mock.calls.every(([, init]) => !init?.method)).toBe(true)
  })
})
