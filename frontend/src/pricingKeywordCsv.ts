export interface PricingKeywordRule {
  id?: string
  keyword: string
  deliveryFeeAriary: number
}

export const MAX_PRICING_KEYWORD_RULES = 100
export const MAX_PRICING_KEYWORD_CSV_BYTES = 10 * 1024 * 1024

export function normalizePricingKeyword(value: string): string {
  return value.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLocaleLowerCase('fr').trim().replace(/\s+/g, ' ')
}

function csvFailure(line: number, message: string): never {
  throw new Error(`Ligne ${line} : ${message}`)
}

// The package CSV reader is private and coupled to package validation. Keep this
// small reader independent while supporting the same quoted-cell CSV format.
function readRows(content: string): Array<{ line: number; cells: string[] }> {
  const text = content.replace(/^\uFEFF/, '')
  if (text.includes('\0')) csvFailure(1, 'le fichier contient des données non textuelles.')
  const firstLine = text.split(/\r\n|\n|\r/).find((line) => line.trim()) ?? ''
  const delimiter = firstLine.includes(';') ? ';' : firstLine.includes('\t') ? '\t' : ','
  const rows: Array<{ line: number; cells: string[] }> = []
  let cells: string[] = []
  let cell = ''
  let quoted = false
  let closedQuote = false
  let line = 1
  let rowLine = 1
  function finishCell() { cells.push(cell.trim()); cell = ''; closedQuote = false }
  function finishRow() {
    finishCell()
    if (cells.some(Boolean)) rows.push({ line: rowLine, cells })
    cells = []
  }
  for (let index = 0; index < text.length; index++) {
    const character = text[index]
    if (quoted) {
      if (character === '"') {
        if (text[index + 1] === '"') { cell += '"'; index++ }
        else { quoted = false; closedQuote = true }
      } else {
        if (character === '\n' || (character === '\r' && text[index + 1] !== '\n')) line++
        cell += character
      }
    } else if (character === delimiter) finishCell()
    else if (character === '\n' || character === '\r') {
      if (character === '\r' && text[index + 1] === '\n') index++
      finishRow(); line++; rowLine = line
    } else if (character === '"' && !cell.trim() && !closedQuote) { cell = ''; quoted = true }
    else if ((closedQuote && character.trim()) || character === '"') csvFailure(line, 'guillemets mal placés dans le fichier CSV.')
    else if (!closedQuote) cell += character
  }
  if (quoted) csvFailure(rowLine, 'une cellule entre guillemets n’est pas fermée.')
  finishRow()
  return rows
}

export function parsePricingKeywordCsv(content: string): PricingKeywordRule[] {
  const rows = readRows(content)
  if (rows.length < 2) throw new Error('Ajoutez au moins une règle sous les en-têtes clé;tarif.')
  const headers = rows[0].cells.map((value) => normalizePricingKeyword(value).replace(/[\s_-]+/g, ''))
  const keywordAliases = ['cle', 'key', 'keyword', 'motcle', 'motcles']
  const priceAliases = ['tarif', 'prix', 'price', 'deliveryfeeariary', 'tarifariary', 'prixariary', 'montant']
  const keywordColumns = headers.flatMap((header, index) => keywordAliases.includes(header) ? [index] : [])
  const priceColumns = headers.flatMap((header, index) => priceAliases.includes(header) ? [index] : [])
  if (headers.length !== 2 || keywordColumns.length !== 1 || priceColumns.length !== 1) {
    csvFailure(rows[0].line, 'utilisez deux colonnes : clé;tarif (ou keyword;delivery_fee_ariary).')
  }
  return rows.slice(1).map((row) => {
    if (row.cells.length !== headers.length) csvFailure(row.line, 'le nombre de cellules ne correspond pas aux en-têtes.')
    const keyword = row.cells[keywordColumns[0]].trim().replace(/\s+/g, ' ')
    if (!normalizePricingKeyword(keyword) || keyword.length > 200) csvFailure(row.line, 'la clé doit contenir entre 1 et 200 caractères.')
    const amount = row.cells[priceColumns[0]].replace(/\s/g, '')
    const deliveryFeeAriary = /^\d+$/.test(amount) ? Number(amount) : NaN
    if (!Number.isSafeInteger(deliveryFeeAriary) || deliveryFeeAriary < 0) csvFailure(row.line, 'le tarif doit être un entier positif ou nul en ariary.')
    return { keyword, deliveryFeeAriary }
  })
}

/** Imports are additive: an existing normalized keyword always keeps its fee. */
export function mergePricingKeywordRules<T extends { keyword: string }>(existing: T[], imported: PricingKeywordRule[]) {
  const seen = new Set(existing.map((rule) => normalizePricingKeyword(rule.keyword)))
  const added: PricingKeywordRule[] = []
  let skipped = 0
  for (const rule of imported) {
    const normalized = normalizePricingKeyword(rule.keyword)
    if (seen.has(normalized)) { skipped++; continue }
    seen.add(normalized)
    added.push(rule)
  }
  if (existing.length + added.length > MAX_PRICING_KEYWORD_RULES) throw new Error(`Une grille est limitée à ${MAX_PRICING_KEYWORD_RULES} règles par mot-clé. Aucun ajout effectué.`)
  return { added, skipped }
}
