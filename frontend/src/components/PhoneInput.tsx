import { forwardRef, useState, type InputHTMLAttributes } from 'react'
import { isValidPhoneNumber, normalizePhoneNumber, PHONE_NUMBER_ERROR, PHONE_NUMBER_EXAMPLE, PHONE_NUMBER_PATTERN } from '../phone'

type Props = Omit<InputHTMLAttributes<HTMLInputElement>, 'type' | 'inputMode' | 'pattern' | 'value' | 'maxLength'> & { value: string }

/** A native input, deliberately without a wrapper to retain each form's existing layout. */
export const PhoneInput = forwardRef<HTMLInputElement, Props>(function PhoneInput({ value, onChange, onBlur, onInvalid, ...props }, ref) {
  const [touched, setTouched] = useState(false)
  const displayed = normalizePhoneNumber(value)
  return <input {...props} ref={ref} type="tel" inputMode="tel" autoComplete={props.autoComplete ?? 'tel-national'}
    placeholder={props.placeholder ?? PHONE_NUMBER_EXAMPLE} pattern={PHONE_NUMBER_PATTERN} title={PHONE_NUMBER_ERROR}
    value={displayed} aria-invalid={props['aria-invalid'] ?? (touched && displayed && !isValidPhoneNumber(displayed) ? true : undefined)}
    onChange={(event) => {
      event.target.value = normalizePhoneNumber(event.target.value)
      event.currentTarget.setCustomValidity('')
      onChange?.(event)
    }}
    onBlur={(event) => {
      setTouched(true)
      if (!event.currentTarget.value || isValidPhoneNumber(event.currentTarget.value)) event.currentTarget.setCustomValidity('')
      onBlur?.(event)
    }}
    onInvalid={(event) => {
      setTouched(true)
      if (event.currentTarget.value && !isValidPhoneNumber(event.currentTarget.value)) event.currentTarget.setCustomValidity(PHONE_NUMBER_ERROR)
      onInvalid?.(event)
    }} />
})
