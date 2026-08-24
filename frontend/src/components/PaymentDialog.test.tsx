import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { ClientPaymentDirection, CreatePaymentInput } from '../api/admin'
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
})
