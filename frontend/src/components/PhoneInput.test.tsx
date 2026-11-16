import { useState } from 'react'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { PHONE_NUMBER_ERROR } from '../phone'
import { PhoneInput } from './PhoneInput'

afterEach(cleanup)

function Field() {
  const [value, setValue] = useState('')
  return <label>Contact<PhoneInput required value={value} onChange={(event) => setValue(event.target.value)} /></label>
}

describe('PhoneInput', () => {
  it('keeps native styling, a telephone keyboard and a clear format example', () => {
    render(<Field />)
    const field = screen.getByLabelText('Contact')
    expect(field).toHaveAttribute('type', 'tel')
    expect(field).toHaveAttribute('inputmode', 'tel')
    expect(field).toHaveAttribute('pattern', '0[0-9]{9}')
    expect(field).toHaveAttribute('placeholder', '0381415216')
    expect(field).toHaveAttribute('title', PHONE_NUMBER_ERROR)
    expect(field).not.toHaveAttribute('maxlength')
  })

  it('accepts national and international pastes without truncating', () => {
    render(<Field />)
    const field = screen.getByLabelText('Contact')
    fireEvent.change(field, { target: { value: '+261 34 16 793 43' } })
    expect(field).toHaveValue('0341679343')
    fireEvent.change(field, { target: { value: '03416793431' } })
    expect(field).toHaveValue('03416793431')
    expect(field).toBeInvalid()
  })

  it('waits for blur before marking an incomplete entry visually invalid', () => {
    render(<Field />)
    const field = screen.getByLabelText('Contact')
    fireEvent.change(field, { target: { value: '034' } })
    expect(field).not.toHaveAttribute('aria-invalid')
    fireEvent.blur(field)
    expect(field).toHaveAttribute('aria-invalid', 'true')
    fireEvent.invalid(field)
    expect((field as HTMLInputElement).validationMessage).toBe(PHONE_NUMBER_ERROR)
    fireEvent.change(field, { target: { value: '0341679343' } })
    expect(field).toBeValid()
    expect(field).not.toHaveAttribute('aria-invalid')
  })

  it('displays old values in national format without emitting any change', () => {
    const onChange = vi.fn()
    render(<PhoneInput aria-label="Ancien contact" value="+261 34 16 793 43" readOnly onChange={onChange} />)
    expect(screen.getByLabelText('Ancien contact')).toHaveValue('0341679343')
    expect(onChange).not.toHaveBeenCalled()
  })
})
