import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { ClientPaymentDirection, CreatePaymentInput } from '../api/admin'
import { PAYOUT_OPERATOR_ERROR } from '../payoutOperators'
import { PaymentDialog } from './PaymentDialog'

afterEach(cleanup)

describe('PaymentDialog', () => {
  it('préselectionne le sens client attendu et transmet le sens modifié', () => {
    const onConfirm = vi.fn<(input: CreatePaymentInput, proof: File | null, direction?: ClientPaymentDirection) => void>()
    render(
      <PaymentDialog
        title="Versement lié au rapport"
        description="Rapport du jour"
        recipientLabel="Boutique Soa"
        defaultAmountAriary={12_000}
        defaultClientDirection="CLIENT_TO_AZY"
        onClose={vi.fn()}
        onConfirm={onConfirm}
      />,
    )

    expect(screen.getByRole('radio', { name: /Client → AZY/i })).toBeChecked()
    fireEvent.click(screen.getByRole('radio', { name: /AZY → client/i }))
    fireEvent.click(screen.getByRole('button', { name: 'Confirmer le versement' }))

    expect(onConfirm).toHaveBeenCalledWith(
      expect.objectContaining({ amountAriary: 12_000, paymentMethod: 'Espèces' }),
      null,
      'AZY_TO_CLIENT',
    )
  })

  it('ne montre pas le choix du sens pour un versement livreur', () => {
    render(
      <PaymentDialog
        title="Versement au livreur"
        description="Solde global"
        recipientLabel="Tiana"
        defaultAmountAriary={8_000}
        onClose={vi.fn()}
        onConfirm={vi.fn()}
      />,
    )

    expect(screen.queryByRole('group', { name: 'Sens du versement' })).not.toBeInTheDocument()
    expect(screen.queryByRole('radio')).not.toBeInTheDocument()
  })

  it('limite la destination aux opérateurs mobiles sans changer les modes de règlement', () => {
    const onConfirm = vi.fn()
    render(<PaymentDialog title="Versement" description="Rapport" recipientLabel="Tiana" defaultAmountAriary={8000} onClose={vi.fn()} onConfirm={onConfirm} />)
    const operator = screen.getByRole('combobox', { name: 'Opérateur' })
    expect(Array.from(operator.querySelectorAll('option')).map((option) => option.value)).toEqual(['', 'MVola', 'Orange Money', 'Airtel Money'])
    expect(screen.getByLabelText('Numéro', { exact: true })).toHaveAttribute('type', 'tel')
    fireEvent.change(screen.getByLabelText('Méthode'), { target: { value: 'Virement bancaire' } })
    fireEvent.click(screen.getByRole('button', { name: 'Confirmer le versement' }))
    expect(onConfirm).toHaveBeenCalledWith(expect.objectContaining({ paymentMethod: 'Virement bancaire', destination: null }), null, undefined)
  })

  it('transmet une destination mobile canonique avec un numéro local', () => {
    const onConfirm = vi.fn()
    render(<PaymentDialog title="Versement" description="Rapport" recipientLabel="Tiana" defaultAmountAriary={8000} defaultDestination={{ operator: 'orange money', accountHolder: 'Tiana', number: '+261 38 14 152 16' }} onClose={vi.fn()} onConfirm={onConfirm} />)
    expect(screen.getByRole('combobox', { name: 'Opérateur' })).toHaveValue('Orange Money')
    fireEvent.click(screen.getByRole('button', { name: 'Confirmer le versement' }))
    expect(onConfirm).toHaveBeenCalledWith(expect.objectContaining({ destination: { operator: 'Orange Money', accountHolder: 'Tiana', number: '0381415216' } }), null, undefined)
  })

  it('ne réutilise pas silencieusement une ancienne destination bancaire', () => {
    const onConfirm = vi.fn()
    const destination = { operator: 'Banque', accountHolder: 'Tiana', number: 'FR76 1234' }
    render(<PaymentDialog title="Versement" description="Rapport" recipientLabel="Tiana" defaultAmountAriary={8000} defaultDestination={destination} onClose={vi.fn()} onConfirm={onConfirm} />)
    expect(screen.getByRole('combobox', { name: 'Opérateur' })).toHaveValue('')
    fireEvent.submit(screen.getByRole('dialog').querySelector('form')!)
    expect(screen.getByText(PAYOUT_OPERATOR_ERROR)).toBeInTheDocument()
    expect(onConfirm).not.toHaveBeenCalled()
    expect(destination.operator).toBe('Banque')
  })
})
