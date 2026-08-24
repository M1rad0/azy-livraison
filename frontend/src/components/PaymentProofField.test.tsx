import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { useState } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  PAYMENT_PROOF_ACCEPT,
  PAYMENT_PROOF_MAX_SIZE_BYTES,
  PaymentProofField,
} from './PaymentProofField'

function Fixture({ onRejected }: { onRejected?: (message: string, file: File) => void }) {
  const [file, setFile] = useState<File | null>(null)
  return <PaymentProofField id="proof" value={file} onChange={setFile} onRejected={onRejected} />
}

afterEach(cleanup)

describe('PaymentProofField', () => {
  it('présente un sélecteur facultatif accessible avec les formats attendus', () => {
    render(<Fixture />)

    const picker = screen.getByRole('button', { name: /choisir un fichier/i })
    const input = document.querySelector<HTMLInputElement>('#proof')
    expect(picker).toHaveAccessibleDescription(/10 Mo maximum/i)
    expect(screen.getByText('Facultative')).toBeInTheDocument()
    expect(input).toHaveAttribute('accept', PAYMENT_PROOF_ACCEPT)
  })

  it('affiche le fichier choisi puis permet de le retirer', () => {
    render(<Fixture />)
    const input = document.querySelector<HTMLInputElement>('#proof')
    const proof = new File([new Uint8Array(1536)], 'preuve-versement.pdf', { type: 'application/pdf' })

    fireEvent.change(input as HTMLInputElement, { target: { files: [proof] } })

    expect(screen.getByText('preuve-versement.pdf')).toBeInTheDocument()
    expect(screen.getByText('1,5 Ko')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /remplacer.*preuve-versement\.pdf/i })).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: /retirer.*preuve-versement\.pdf/i }))
    expect(screen.queryByText('preuve-versement.pdf')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: /choisir un fichier/i })).toBeInTheDocument()
  })

  it('refuse un format non pris en charge sans remplacer la valeur', () => {
    const onRejected = vi.fn()
    render(<Fixture onRejected={onRejected} />)
    const input = document.querySelector<HTMLInputElement>('#proof')
    const textFile = new File(['texte'], 'preuve.txt', { type: 'text/plain' })

    fireEvent.change(input as HTMLInputElement, { target: { files: [textFile] } })

    expect(screen.getByRole('alert')).toHaveTextContent('PDF, JPEG, PNG ou WebP')
    expect(onRejected).toHaveBeenCalledWith(expect.stringContaining('PDF'), textFile)
    expect(screen.getByRole('button', { name: /choisir un fichier/i })).toBeInTheDocument()
  })

  it('refuse un fichier supérieur à 10 Mo', () => {
    render(<Fixture />)
    const input = document.querySelector<HTMLInputElement>('#proof')
    const oversized = new File(
      [new Uint8Array(PAYMENT_PROOF_MAX_SIZE_BYTES + 1)],
      'preuve.png',
      { type: 'image/png' },
    )

    fireEvent.change(input as HTMLInputElement, { target: { files: [oversized] } })

    expect(screen.getByRole('alert')).toHaveTextContent('ne peut pas dépasser 10 Mo')
    expect(screen.queryByText('preuve.png')).not.toBeInTheDocument()
  })
})
