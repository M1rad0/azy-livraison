/// <reference types="node" />
// @vitest-environment node
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const styles = readFileSync(new URL('./AdminPortal.css', import.meta.url), 'utf8')

function rulesFor(selector: string): string {
  const blocks = [...styles.replace(/\/\*[\s\S]*?\*\//g, '').matchAll(/([^{}]+)\{([^{}]*)\}/g)]
  return blocks
    .filter(([, selectors]) => selectors.split(',').some((value) => value.trim() === selector))
    .map(([, , declarations]) => declarations)
    .join('\n')
}

describe('administrative card footer layout', () => {
  it.each([
    '.admin-portal footer.admin-day-close-card',
    '.admin-portal .admin-express-card > footer',
  ])('isolates %s from the generic page footer styles', (selector) => {
    const rules = rulesFor(selector)
    expect(rules).toContain('width: 100%')
    expect(rules).toContain('min-width: 0')
    expect(rules).toContain('margin: 0')
    expect(rules).toContain('font: inherit')
    expect(rules).toContain('letter-spacing: normal')
    expect(rules).toContain('text-transform: none')
  })

  it('keeps balanced inner spacing for closure text and action, including narrow screens', () => {
    const rules = rulesFor('.admin-portal footer.admin-day-close-card')
    expect(rules).toContain('padding: 1rem')
    expect(rules).toContain('align-items: center')
    expect(rules).toContain('flex-direction: column')
    expect(rules).toContain('align-items: stretch')
    expect(rulesFor('.admin-day-close-card > div')).toContain('min-width: 0')
    expect(rulesFor('.admin-day-close-card > button')).toContain('flex: 0 0 auto')
  })

  it('lets express metadata wrap without stretching or clipping the courier names', () => {
    const rules = rulesFor('.admin-portal .admin-express-card > footer')
    expect(rules).toContain('padding: .8rem .95rem')
    expect(rules).toContain('align-items: center')
    expect(rules).toContain('flex-direction: row')
    expect(rules).toContain('flex-wrap: wrap')
    expect(rulesFor('.admin-express-card > footer > span')).toContain('overflow-wrap: anywhere')
    expect(rulesFor('.admin-express-card > footer > svg')).toContain('flex: 0 0 auto')
  })
})
