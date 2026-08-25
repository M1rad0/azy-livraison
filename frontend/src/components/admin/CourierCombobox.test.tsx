import { fireEvent, render, screen } from '@testing-library/react'
import { useState } from 'react'
import { describe, expect, it } from 'vitest'
import type { Courier } from '../../api/admin'
import { CourierCombobox } from './CourierCombobox'

const couriers = [
  { id: 'courier-1', name: 'Tiana', contact: '034 11 111 11', active: true },
  { id: 'courier-2', name: 'Fara', contact: '033 22 222 22', active: true },
] as Courier[]

function Fixture() {
  const [value, setValue] = useState('courier-1')
  return <CourierCombobox couriers={couriers} value={value} inputId="courier" onChange={setValue} />
}

describe('CourierCombobox', () => {
  it('recherche sans effacer le texte puis remplace le livreur sélectionné', () => {
    render(<Fixture />)
    const input = screen.getByRole('combobox')
    expect(input).toHaveValue('Tiana')

    fireEvent.change(input, { target: { value: 'far' } })
    expect(input).toHaveValue('far')
    expect(screen.getByRole('option', { name: /Fara/ })).toBeInTheDocument()

    fireEvent.mouseDown(screen.getByRole('option', { name: /Fara/ }))
    expect(input).toHaveValue('Fara')
  })
})
