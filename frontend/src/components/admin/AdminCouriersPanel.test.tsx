import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createCourier, updateCourier, type Courier } from '../../api/admin'
import { PHONE_NUMBER_ERROR } from '../../phone'
import { PAYOUT_OPERATOR_ERROR } from '../../payoutOperators'
import { AdminCouriersPanel } from './AdminCouriersPanel'

vi.mock('../../api/admin', () => ({ createCourier: vi.fn(), updateCourier: vi.fn(), disableCourier: vi.fn() }))
vi.mock('../LocationPicker', () => ({ LocationPicker: ({ onChange }: { onChange: (value: unknown) => void }) => <button type="button" onClick={() => onChange({ label: 'Départ', latitude: -18.9, longitude: 47.5 })}>Choisir le départ</button> }))

afterEach(() => { cleanup(); vi.clearAllMocks() })

function openEditor() {
  render(<AdminCouriersPanel accessToken="token" couriers={[]} presets={[]} onSaved={vi.fn()} />)
  fireEvent.click(screen.getByRole('button', { name: 'Ajouter un livreur' }))
  fireEvent.change(screen.getByLabelText('Nom du livreur'), { target: { value: 'Hery' } })
  fireEvent.click(screen.getByRole('button', { name: 'Choisir le départ' }))
}

describe('courier contact formats', () => {
  it('normalise contact et versement lors de la création', async () => {
    vi.mocked(createCourier).mockResolvedValue({ id: 'courier' } as Courier)
    openEditor()
    fireEvent.change(screen.getByLabelText('Numéro de téléphone'), { target: { value: '+261 34 16 793 43' } })
    fireEvent.change(screen.getByLabelText('Opérateur'), { target: { value: 'MVola' } })
    fireEvent.change(screen.getByLabelText('Titulaire'), { target: { value: 'Hery' } })
    fireEvent.change(screen.getByLabelText('Numéro', { exact: true }), { target: { value: '032 12 345 67' } })
    fireEvent.submit(screen.getByLabelText('Nom du livreur').closest('form')!)
    await waitFor(() => expect(createCourier).toHaveBeenCalledWith(expect.objectContaining({ contact: '0341679343', phoneNumber: '0341679343', payoutDestination: { operator: 'MVola', accountHolder: 'Hery', number: '0321234567' } }), 'token'))
  })

  it('valide également les contacts lorsque la validation native est contournée', () => {
    openEditor()
    fireEvent.change(screen.getByLabelText('Numéro de téléphone'), { target: { value: '03416793431' } })
    fireEvent.submit(screen.getByLabelText('Nom du livreur').closest('form')!)
    expect(screen.getByText(PHONE_NUMBER_ERROR)).toBeInTheDocument()
    expect(createCourier).not.toHaveBeenCalled()
  })

  it('propose uniquement les trois opérateurs mobiles sans imposer de destination', async () => {
    vi.mocked(createCourier).mockResolvedValue({ id: 'courier' } as Courier)
    openEditor()
    const operator = screen.getByRole('combobox', { name: 'Opérateur' })
    expect(operator).toHaveValue('')
    expect(Array.from(operator.querySelectorAll('option')).map((option) => option.value)).toEqual(['', 'MVola', 'Orange Money', 'Airtel Money'])
    expect(screen.getByLabelText('Numéro', { exact: true })).toHaveAttribute('type', 'tel')
    fireEvent.submit(screen.getByLabelText('Nom du livreur').closest('form')!)
    await waitFor(() => expect(createCourier).toHaveBeenCalledWith(expect.objectContaining({ payoutDestination: null }), 'token'))
  })

  it('refuse une destination commencée sans opérateur mobile sélectionné', () => {
    openEditor()
    fireEvent.change(screen.getByLabelText('Titulaire'), { target: { value: 'Hery' } })
    fireEvent.change(screen.getByLabelText('Numéro', { exact: true }), { target: { value: '0381415216' } })
    fireEvent.submit(screen.getByLabelText('Nom du livreur').closest('form')!)
    expect(screen.getByText(PAYOUT_OPERATOR_ERROR)).toBeInTheDocument()
    expect(createCourier).not.toHaveBeenCalled()
  })

  it('normalise un ancien nom d’opérateur mobile et ne modifie pas les données sources', async () => {
    const courier = {
      id: 'courier', name: 'Hery', active: true, vehicleType: 'MOTO',
      defaultDepartureTime: '06:00', departureLocation: { label: 'Départ', latitude: -18.9, longitude: 47.5 },
      payoutDestination: { operator: 'mvola', accountHolder: 'Hery', number: '0381415216' },
      version: 1,
    } as Courier
    vi.mocked(updateCourier).mockResolvedValue(courier)
    render(<AdminCouriersPanel accessToken="token" couriers={[courier]} presets={[]} onSaved={vi.fn()} />)
    fireEvent.click(screen.getByRole('button', { name: 'Modifier' }))
    expect(screen.getByRole('combobox', { name: 'Opérateur' })).toHaveValue('MVola')
    fireEvent.submit(screen.getByLabelText('Nom du livreur').closest('form')!)
    await waitFor(() => expect(updateCourier).toHaveBeenCalledWith('courier', expect.objectContaining({ payoutDestination: { operator: 'MVola', accountHolder: 'Hery', number: '0381415216' } }), 'token'))
    expect(courier.payoutDestination?.operator).toBe('mvola')
  })

  it('demande de remplacer une ancienne destination non mobile avant enregistrement', () => {
    const courier = {
      id: 'courier', name: 'Hery', active: true, vehicleType: 'MOTO',
      defaultDepartureTime: '06:00', departureLocation: { label: 'Départ', latitude: -18.9, longitude: 47.5 },
      payoutDestination: { operator: 'Banque', accountHolder: 'Hery', number: 'FR76 1234' },
      version: 1,
    } as Courier
    render(<AdminCouriersPanel accessToken="token" couriers={[courier]} presets={[]} onSaved={vi.fn()} />)
    fireEvent.click(screen.getByRole('button', { name: 'Modifier' }))
    expect(screen.getByRole('combobox', { name: 'Opérateur' })).toHaveValue('')
    fireEvent.submit(screen.getByLabelText('Nom du livreur').closest('form')!)
    expect(screen.getByText(PAYOUT_OPERATOR_ERROR)).toBeInTheDocument()
    expect(updateCourier).not.toHaveBeenCalled()
    expect(courier.payoutDestination?.operator).toBe('Banque')
  })
})
