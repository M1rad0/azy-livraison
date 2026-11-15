import type { ComponentPropsWithRef } from 'react'
import './Button.css'

export interface ButtonProps extends ComponentPropsWithRef<'button'> {
  variant?: 'primary' | 'secondary' | 'ghost' | 'danger' | 'accent'
  tone?: 'default' | 'admin'
  size?: 'default' | 'small'
  fullWidth?: boolean
}

/** Shared action button; native attributes and existing theme classes stay available. */
export function Button({
  variant = 'primary',
  tone = 'default',
  size = 'default',
  fullWidth = false,
  className,
  type = 'button',
  ...props
}: ButtonProps) {
  const theme = tone === 'admin' ? 'admin-button' : 'button'
  const themeVariant = tone === 'admin'
    ? { primary: '', secondary: 'admin-button--ghost', ghost: 'admin-button--ghost', danger: '', accent: 'admin-button--express' }[variant]
    : { primary: '', secondary: 'button--secondary', ghost: '', danger: 'button--danger', accent: '' }[variant]

  return <button
    {...props}
    type={type}
    className={[
      theme,
      themeVariant,
      'ui-button',
      `ui-button--${variant}`,
      size === 'small' && 'ui-button--small',
      fullWidth && 'ui-button--wide',
      className,
    ].filter(Boolean).join(' ')}
  />
}
