import {
  BadgeDollarSign,
  Bike,
  Boxes,
  CalendarClock,
  ClipboardCheck,
  FileText,
  LayoutDashboard,
  LogOut,
  Menu,
  MessageSquareText,
  PackagePlus,
  PanelLeftClose,
  PanelLeftOpen,
  Settings2,
  Sparkles,
  UserRound,
  UsersRound,
  WalletCards,
  X,
  Zap,
} from 'lucide-react'
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react'
import type { Account, AccountRole } from '../api/auth'
import { Brand } from './Brand'
import './PortalShell.css'

interface PortalNavigationItem {
  id: string
  label: string
  detail: string
  icon: typeof LayoutDashboard
  badge?: number
}

interface PortalNavigationValue {
  role: AccountRole
  section: string
  navigate: (section: string) => void
  setSectionVisible: (section: string, visible: boolean) => void
}

const PortalNavigationContext = createContext<PortalNavigationValue | null>(null)

export function usePortalNavigation(): PortalNavigationValue | null {
  return useContext(PortalNavigationContext)
}

const ROLE_LABELS: Record<AccountRole, string> = {
  ADMIN: 'Administration',
  CLIENT: 'Espace client',
  COURIER: 'Espace livreur',
}

const NAVIGATION: Record<AccountRole, PortalNavigationItem[]> = {
  ADMIN: [
    { id: 'queue', label: 'Livraisons', detail: 'File globale', icon: Boxes },
    { id: 'express', label: 'Express', detail: 'Traitement immédiat', icon: Zap },
    { id: 'dispatch', label: 'Dispatch', detail: 'Préparer la journée', icon: Sparkles },
    { id: 'briefing', label: 'Briefing', detail: 'Consignes des livreurs', icon: MessageSquareText },
    { id: 'day', label: 'Compte rendu', detail: 'Suivi et clôture', icon: ClipboardCheck },
    { id: 'postponed', label: 'Reportés', detail: 'Reprogrammer les colis', icon: CalendarClock },
    { id: 'finance', label: 'Finances', detail: 'Versements et soldes', icon: WalletCards },
    { id: 'clients', label: 'Clients', detail: 'Comptes et demandes', icon: UsersRound },
    { id: 'couriers', label: 'Livreurs', detail: 'Équipe et véhicules', icon: Bike },
    { id: 'pricing', label: 'Tarification', detail: 'Zones et frais', icon: BadgeDollarSign },
    { id: 'settings', label: 'Paramètres', detail: 'Règles opérationnelles', icon: Settings2 },
  ],
  CLIENT: [
    { id: 'history', label: 'Mes livraisons', detail: 'Suivi des demandes', icon: LayoutDashboard },
    { id: 'new-request', label: 'Nouvelle demande', detail: 'Préparer des colis', icon: PackagePlus },
    { id: 'express', label: 'Livraison express', detail: 'Traitement immédiat', icon: Zap },
    { id: 'pricing', label: 'Mes tarifs', detail: 'Grilles applicables', icon: BadgeDollarSign },
    { id: 'reports', label: 'Mes rapports', detail: 'Comptes rendus finalisés', icon: FileText },
    { id: 'profile', label: 'Coordonnées', detail: 'Contacts, retrait et versements', icon: UserRound },
  ],
  COURIER: [
    { id: 'home', label: 'Mon espace', detail: 'Bientôt disponible', icon: LayoutDashboard },
  ],
}

const STORAGE_KEY = 'azy.portal.sidebar-collapsed'

function pathFor(role: AccountRole, section: string): string {
  return `#/${role.toLowerCase()}/${section}`
}

function sectionFromHash(role: AccountRole, items: PortalNavigationItem[]): string | undefined {
  const match = window.location.hash.match(/^#\/(admin|client|courier)\/([^/?#]+)/)
  if (!match || match[1].toUpperCase() !== role) return undefined
  return items.some((item) => item.id === match[2]) ? match[2] : undefined
}

function initials(account: Account): string {
  const name = account.displayName.trim() || account.username
  return name.split(/\s+/).slice(0, 2).map((part) => part[0]).join('').toUpperCase() || 'AZ'
}

export interface PortalShellProps {
  account: Account
  children: ReactNode
  onLogout: () => Promise<void>
}

export function PortalShell({ account, children, onLogout }: PortalShellProps) {
  const [hiddenSections, setHiddenSections] = useState<Set<string>>(() => (
    account.role === 'CLIENT' ? new Set(['express']) : new Set()
  ))
  const items = useMemo(
    () => NAVIGATION[account.role].filter((item) => !hiddenSections.has(item.id)),
    [account.role, hiddenSections],
  )
  const defaultSection = items[0].id
  const [section, setSection] = useState(() => sectionFromHash(account.role, items) ?? defaultSection)
  const [collapsed, setCollapsed] = useState(() => {
    try {
      return window.localStorage.getItem(STORAGE_KEY) === 'true'
    } catch {
      return false
    }
  })
  const [mobileOpen, setMobileOpen] = useState(false)
  const [loggingOut, setLoggingOut] = useState(false)
  const menuButtonRef = useRef<HTMLButtonElement>(null)
  const closeButtonRef = useRef<HTMLButtonElement>(null)
  const sidebarRef = useRef<HTMLElement>(null)
  const workspaceRef = useRef<HTMLDivElement>(null)

  const navigate = useCallback((nextSection: string) => {
    if (!items.some((item) => item.id === nextSection)) return
    setSection(nextSection)
    const nextHash = pathFor(account.role, nextSection)
    if (window.location.hash !== nextHash) window.history.pushState(null, '', nextHash)
    setMobileOpen(false)
  }, [account.role, items])

  const setSectionVisible = useCallback((targetSection: string, visible: boolean) => {
    setHiddenSections((current) => {
      const alreadyHidden = current.has(targetSection)
      if (visible === !alreadyHidden) return current
      const next = new Set(current)
      if (visible) next.delete(targetSection)
      else next.add(targetSection)
      return next
    })
  }, [])

  useEffect(() => {
    const syncFromLocation = () => setSection(sectionFromHash(account.role, items) ?? defaultSection)
    window.addEventListener('hashchange', syncFromLocation)
    window.addEventListener('popstate', syncFromLocation)
    return () => {
      window.removeEventListener('hashchange', syncFromLocation)
      window.removeEventListener('popstate', syncFromLocation)
    }
  }, [account.role, defaultSection, items])

  useEffect(() => {
    const currentSection = sectionFromHash(account.role, items)
    if (!currentSection) window.history.replaceState(null, '', pathFor(account.role, defaultSection))
  }, [account.role, defaultSection, items])

  useEffect(() => {
    if (!items.some((item) => item.id === section)) navigate(defaultSection)
  }, [defaultSection, items, navigate, section])

  useEffect(() => {
    try {
      window.localStorage.setItem(STORAGE_KEY, String(collapsed))
    } catch {
      // A private browser context may reject local storage; the UI remains usable.
    }
  }, [collapsed])

  useEffect(() => {
    if (!mobileOpen) return
    closeButtonRef.current?.focus()
    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'

    function onKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        event.preventDefault()
        setMobileOpen(false)
        menuButtonRef.current?.focus()
        return
      }
      if (event.key !== 'Tab' || !sidebarRef.current) return
      const focusable = [...sidebarRef.current.querySelectorAll<HTMLElement>(
        'a[href], button:not([disabled]), input:not([disabled]), [tabindex]:not([tabindex="-1"])',
      )].filter((element) => !element.hidden)
      if (focusable.length === 0) return
      const first = focusable[0]
      const last = focusable[focusable.length - 1]
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault()
        last.focus()
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault()
        first.focus()
      }
    }

    window.addEventListener('keydown', onKeyDown)
    return () => {
      document.body.style.overflow = previousOverflow
      window.removeEventListener('keydown', onKeyDown)
    }
  }, [mobileOpen])

  const activeItem = items.find((item) => item.id === section) ?? items[0]
  const accountName = account.displayName.trim() || account.username
  const navigationValue = useMemo<PortalNavigationValue>(() => ({
    role: account.role,
    section,
    navigate,
    setSectionVisible,
  }), [account.role, navigate, section, setSectionVisible])

  async function handleLogout() {
    setLoggingOut(true)
    try {
      await onLogout()
    } finally {
      setLoggingOut(false)
    }
  }

  function closeMobileAndRestoreFocus() {
    setMobileOpen(false)
    menuButtonRef.current?.focus()
  }

  return (
    <PortalNavigationContext.Provider value={navigationValue}>
      <a className="portal-skip-link" href="#portal-workspace">Aller au contenu principal</a>
      <div className={`portal-shell${collapsed ? ' is-collapsed' : ''}${mobileOpen ? ' is-mobile-open' : ''}`}>
        <button
          className="portal-sidebar-backdrop"
          type="button"
          aria-label="Fermer le menu"
          tabIndex={mobileOpen ? 0 : -1}
          onClick={closeMobileAndRestoreFocus}
        />
        <aside
          className="portal-sidebar"
          id="portal-sidebar"
          ref={sidebarRef}
          aria-label={`Navigation · ${ROLE_LABELS[account.role]}`}
        >
          <div className="portal-sidebar__brand">
            <Brand />
            <button
              ref={closeButtonRef}
              className="portal-sidebar__mobile-close"
              type="button"
              aria-label="Fermer le menu"
              onClick={closeMobileAndRestoreFocus}
            ><X aria-hidden="true" /></button>
          </div>

          <div className="portal-sidebar__context">
            <span>{ROLE_LABELS[account.role]}</span>
            <small>AZY Livraison</small>
          </div>

          <nav className="portal-sidebar__nav" aria-label="Sections principales">
            {items.map(({ id, label, detail, icon: Icon, badge }) => {
              const active = id === section
              return (
                <a
                  key={id}
                  href={pathFor(account.role, id)}
                  className={active ? 'is-active' : undefined}
                  aria-current={active ? 'page' : undefined}
                  title={collapsed ? label : undefined}
                  onClick={(event) => {
                    event.preventDefault()
                    navigate(id)
                  }}
                >
                  <span className="portal-sidebar__nav-icon" aria-hidden="true"><Icon /></span>
                  <span className="portal-sidebar__nav-copy"><strong>{label}</strong><small>{detail}</small></span>
                  {badge != null && badge > 0 && <b aria-label={`${badge} élément${badge > 1 ? 's' : ''}`}>{badge}</b>}
                </a>
              )
            })}
          </nav>

          <div className="portal-sidebar__footer">
            <div className="portal-account">
              <span className="portal-account__avatar" aria-hidden="true">{initials(account)}</span>
              <span className="portal-account__copy"><small>{ROLE_LABELS[account.role]}</small><strong>{accountName}</strong></span>
            </div>
            <button
              className="portal-logout"
              type="button"
              title={collapsed ? 'Se déconnecter' : undefined}
              disabled={loggingOut}
              onClick={() => void handleLogout()}
            >
              <LogOut aria-hidden="true" />
              <span>{loggingOut ? 'Déconnexion…' : 'Se déconnecter'}</span>
            </button>
          </div>

          <button
            className="portal-sidebar__collapse"
            type="button"
            aria-expanded={!collapsed}
            aria-label={collapsed ? 'Déployer la navigation' : 'Réduire la navigation'}
            onClick={() => setCollapsed((current) => !current)}
          >
            {collapsed ? <PanelLeftOpen aria-hidden="true" /> : <PanelLeftClose aria-hidden="true" />}
            <span>{collapsed ? 'Déployer' : 'Réduire la barre'}</span>
          </button>
        </aside>

        <div className="portal-workspace" id="portal-workspace" ref={workspaceRef} tabIndex={-1}>
          <header className="portal-workspace__topbar">
            <button
              ref={menuButtonRef}
              className="portal-mobile-menu"
              type="button"
              aria-controls="portal-sidebar"
              aria-expanded={mobileOpen}
              aria-label="Ouvrir le menu"
              onClick={() => setMobileOpen(true)}
            ><Menu aria-hidden="true" /></button>
            <div className="portal-workspace__title" aria-live="polite">
              <small>{ROLE_LABELS[account.role]}</small>
              <strong>{activeItem.label}</strong>
              <span>{activeItem.detail}</span>
            </div>
            <div className="portal-workspace__account">
              <span className="portal-account__avatar" aria-hidden="true">{initials(account)}</span>
              <span><small>Connecté</small><strong>{accountName}</strong></span>
            </div>
          </header>
          <div className="portal-workspace__content">{children}</div>
          <footer className="portal-workspace__footer">
            <span>AZY Livraison</span><span>Antananarivo · Madagascar</span>
          </footer>
        </div>
      </div>
    </PortalNavigationContext.Provider>
  )
}
