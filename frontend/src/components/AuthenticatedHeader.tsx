import { useState } from 'react'
import { LogOut } from 'lucide-react'
import type { Account, AccountRole } from '../api/auth'
import { Brand } from './Brand'

const ROLE_LABELS: Record<AccountRole, string> = {
  ADMIN: 'Administration',
  CLIENT: 'Espace client',
  COURIER: 'Espace livreur',
}

interface AuthenticatedHeaderProps {
  account: Account
  onLogout: () => Promise<void>
}

export function AuthenticatedHeader({ account, onLogout }: AuthenticatedHeaderProps) {
  const [loggingOut, setLoggingOut] = useState(false)
  const accountName = account.displayName.trim() || account.username
  const accountInitials = accountName.trim().split(/\s+/).slice(0, 2).map((part) => part[0]).join('').toUpperCase() || 'AZ'

  async function handleLogout() {
    setLoggingOut(true)
    try {
      await onLogout()
    } finally {
      setLoggingOut(false)
    }
  }

  return (
    <header className="topbar">
      <Brand />
      <div className="account-menu">
        <div className="account-profile">
          <span className="account-avatar" aria-hidden="true">{accountInitials}</span>
          <span className="account-profile__details">
            <small><i aria-hidden="true" />{ROLE_LABELS[account.role]}</small>
            <strong>{accountName}</strong>
          </span>
        </div>
        <button className="button button--secondary account-logout" type="button" onClick={() => void handleLogout()} disabled={loggingOut}>
          <LogOut aria-hidden="true" />{loggingOut ? 'Déconnexion…' : 'Se déconnecter'}
        </button>
      </div>
    </header>
  )
}
