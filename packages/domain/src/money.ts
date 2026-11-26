/**
 * The farm works in euro. Amounts are shown as €1.250 or €12.450,50:
 * dot for thousands, comma for decimals, the euro sign in front.
 */

export const CURRENCY = 'EUR' as const;
export const CURRENCY_SYMBOL = '€';
/** The rate used once to move the amounts recorded in lei to euro. */
export const RON_PER_EUR = 4.97;

function group(integer: string) {
  return integer.replace(/\B(?=(\d{3})+(?!\d))/g, '.');
}

/**
 * Formats an amount in euro. Whole amounts show no decimals unless asked;
 * `digits` forces a fixed number of decimals (for example cost per hectare).
 */
export function formatEuro(value: number | null | undefined, digits?: number): string {
  if (value == null || !Number.isFinite(value)) return '-';
  const negative = value < 0;
  const abs = Math.abs(value);
  const decimals = digits ?? (Math.abs(abs - Math.round(abs)) < 0.005 ? 0 : 2);
  const fixed = abs.toFixed(decimals);
  const [integer, fraction] = fixed.split('.');
  return `${negative ? '-' : ''}${CURRENCY_SYMBOL}${group(integer!)}${fraction ? `,${fraction}` : ''}`;
}

/** €120/ha, €366,67/ha. */
export const formatEuroPerHa = (value: number | null | undefined, digits?: number) =>
  value == null || !Number.isFinite(value) ? '-' : `${formatEuro(value, digits)}/ha`;

/** Amounts stored in another currency, read in euro. */
export function toEuro(amount: number, currency: string | null | undefined): number {
  if (!currency || currency.toUpperCase() === CURRENCY) return amount;
  if (currency.toUpperCase() === 'RON') return Math.round((amount / RON_PER_EUR) * 100) / 100;
  return amount;
}
