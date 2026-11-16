/// <reference types="node" />
// @vitest-environment node
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'

const clientStyles = readFileSync(new URL('./ClientPortal.css', import.meta.url), 'utf8')
const adminStyles = readFileSync(new URL('./admin/AdminPortal.css', import.meta.url), 'utf8')
const appStyles = readFileSync(new URL('../App.css', import.meta.url), 'utf8')

function rule(styles: string, selector: string): string {
  const escapedSelector = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  return styles.match(new RegExp(`${escapedSelector}\\s*\\{([^}]+)\\}`))?.[1] ?? ''
}

describe('package list layout safeguards', () => {
  it('keeps package numbers in a dedicated column, never over the recipient', () => {
    const numberRule = rule(clientStyles, '.client-package-row__index')
    expect(numberRule).toContain('grid-area: index')
    expect(numberRule).not.toMatch(/position:\s*absolute|left:|transform:/)
    expect(rule(clientStyles, '.client-package-row')).toContain('grid-template-columns: 2rem')
    expect(clientStyles).toContain('"index recipient destination amount options actions"')
  })

  it('preserves number, recipient and actions in separate tracks on small screens', () => {
    expect(clientStyles).toContain('"index recipient destination actions"')
    expect(clientStyles).toContain('"index recipient actions"')
    expect(clientStyles).toContain('"index destination actions"')
    expect(clientStyles).toContain('"index amount actions"')
    expect(clientStyles).not.toContain('.client-package-row > div:nth-of-type')
  })

  it('lets history and compact text columns shrink without hiding neighboring cells', () => {
    expect(rule(clientStyles, '.client-history-packages article')).toContain('2rem repeat(3, minmax(0, 1fr))')
    expect(rule(appStyles, '.compact-package-row')).toContain('2rem minmax(0, 0.9fr) minmax(0, 1.2fr)')
    expect(rule(clientStyles, '.client-package-row small')).toContain('overflow-wrap: anywhere')
    expect(rule(clientStyles, '.client-package-status')).not.toContain('grid-column')
    expect(clientStyles.match(/\.client-package-status\s*\{/g)).toHaveLength(1)
  })

  it('contains the wide administrative table inside its own horizontal scroll area', () => {
    const tableRule = rule(adminStyles, '.admin-package-table')
    expect(tableRule).toContain('min-width: 0')
    expect(tableRule).toContain('max-width: 100%')
    expect(tableRule).toContain('overflow-x: auto')
    expect(tableRule).toContain('overscroll-behavior-x: contain')
  })
})
