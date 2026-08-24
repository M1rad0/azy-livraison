import { FileImage, FileText, RefreshCw, Trash2, Upload } from 'lucide-react'
import { useId, useRef, useState, type ChangeEvent } from 'react'
import './PaymentProofField.css'

export const PAYMENT_PROOF_MAX_SIZE_BYTES = 10 * 1024 * 1024
export const PAYMENT_PROOF_ACCEPT = 'application/pdf,image/jpeg,image/png,image/webp'

const ACCEPTED_MIME_TYPES = new Set(PAYMENT_PROOF_ACCEPT.split(','))
const ACCEPTED_EXTENSIONS = /\.(pdf|jpe?g|png|webp)$/i

export interface PaymentProofFieldProps {
  value: File | null
  onChange: (file: File | null) => void
  id?: string
  label?: string
  description?: string
  disabled?: boolean
  error?: string
  maxSizeBytes?: number
  onRejected?: (message: string, file: File) => void
}

function formatFileSize(size: number): string {
  if (size < 1024) return `${size} octet${size > 1 ? 's' : ''}`
  const unit = size < 1024 * 1024 ? 'Ko' : 'Mo'
  const divisor = unit === 'Ko' ? 1024 : 1024 * 1024
  return `${new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 1 }).format(size / divisor)} ${unit}`
}

function formatMaximumSize(size: number): string {
  return formatFileSize(size)
}

function rejectionMessage(file: File, maxSizeBytes: number): string | undefined {
  const acceptedType = ACCEPTED_MIME_TYPES.has(file.type)
    || (!file.type && ACCEPTED_EXTENSIONS.test(file.name))
  if (!acceptedType) return 'Choisissez un fichier PDF, JPEG, PNG ou WebP.'
  if (file.size > maxSizeBytes) {
    return `La pièce justificative ne peut pas dépasser ${formatMaximumSize(maxSizeBytes)}.`
  }
  return undefined
}

export function PaymentProofField({
  value,
  onChange,
  id,
  label = 'Pièce justificative',
  description,
  disabled = false,
  error,
  maxSizeBytes = PAYMENT_PROOF_MAX_SIZE_BYTES,
  onRejected,
}: PaymentProofFieldProps) {
  const generatedId = useId()
  const inputId = id ?? `payment-proof-${generatedId}`
  const descriptionId = `${inputId}-description`
  const selectedFileId = `${inputId}-selected-file`
  const errorId = `${inputId}-error`
  const inputRef = useRef<HTMLInputElement>(null)
  const [validationError, setValidationError] = useState<string>()
  const [announcement, setAnnouncement] = useState('')
  const displayedError = validationError ?? error
  const describedBy = [descriptionId, value ? selectedFileId : undefined, displayedError ? errorId : undefined]
    .filter(Boolean)
    .join(' ')

  function openPicker() {
    if (disabled) return
    if (inputRef.current) {
      // Resetting allows the same file to be selected again after removal or rejection.
      inputRef.current.value = ''
      inputRef.current.click()
    }
  }

  function selectFile(event: ChangeEvent<HTMLInputElement>) {
    const file = event.currentTarget.files?.[0]
    if (!file) return

    const message = rejectionMessage(file, maxSizeBytes)
    if (message) {
      setValidationError(message)
      setAnnouncement('Pièce justificative refusée.')
      event.currentTarget.value = ''
      onRejected?.(message, file)
      return
    }

    setValidationError(undefined)
    setAnnouncement(`${file.name} sélectionné.`)
    onChange(file)
  }

  function removeFile() {
    if (disabled || !value) return
    const removedName = value.name
    if (inputRef.current) inputRef.current.value = ''
    setValidationError(undefined)
    onChange(null)
    setAnnouncement(`${removedName} retiré.`)
  }

  const helpText = description
    ?? `Ajoutez si nécessaire un PDF ou une image JPEG, PNG ou WebP de ${formatMaximumSize(maxSizeBytes)} maximum.`
  const FileIcon = value?.type === 'application/pdf' || value?.name.toLocaleLowerCase('fr').endsWith('.pdf')
    ? FileText
    : FileImage

  return (
    <section className={`payment-proof-field${displayedError ? ' has-error' : ''}`} aria-labelledby={`${inputId}-label`}>
      <div className="payment-proof-field__heading">
        <div>
          <span id={`${inputId}-label`}>{label}</span>
          <small>Facultative</small>
        </div>
        <p id={descriptionId}>{helpText}</p>
      </div>

      <input
        ref={inputRef}
        className="sr-only"
        id={inputId}
        name="paymentProof"
        type="file"
        accept={PAYMENT_PROOF_ACCEPT}
        disabled={disabled}
        tabIndex={-1}
        aria-describedby={describedBy || undefined}
        onChange={selectFile}
      />

      {value ? (
        <div className="payment-proof-field__selection" id={selectedFileId}>
          <span className="payment-proof-field__file-icon" aria-hidden="true"><FileIcon /></span>
          <span className="payment-proof-field__file-copy">
            <strong title={value.name}>{value.name}</strong>
            <small>{formatFileSize(value.size)}</small>
          </span>
          <span className="payment-proof-field__actions">
            <button
              type="button"
              disabled={disabled}
              aria-label={`Remplacer la pièce justificative ${value.name}`}
              aria-describedby={describedBy || undefined}
              onClick={openPicker}
            >
              <RefreshCw aria-hidden="true" />
              <span>Remplacer</span>
            </button>
            <button
              className="is-danger"
              type="button"
              disabled={disabled}
              aria-label={`Retirer la pièce justificative ${value.name}`}
              onClick={removeFile}
            >
              <Trash2 aria-hidden="true" />
              <span>Retirer</span>
            </button>
          </span>
        </div>
      ) : (
        <button
          className="payment-proof-field__picker"
          type="button"
          disabled={disabled}
          aria-describedby={describedBy || undefined}
          onClick={openPicker}
        >
          <span aria-hidden="true"><Upload /></span>
          <span><strong>Choisir un fichier</strong><small>PDF, JPEG, PNG ou WebP</small></span>
        </button>
      )}

      {displayedError && <p className="payment-proof-field__error" id={errorId} role="alert">{displayedError}</p>}
      <span className="sr-only" role="status" aria-live="polite">{announcement}</span>
    </section>
  )
}
