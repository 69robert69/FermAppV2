export const COST_CATEGORIES = ['SEED', 'FERTILIZER', 'PESTICIDE', 'FUEL', 'LABOR', 'IRRIGATION', 'OTHER'] as const;
export type CostCategory = (typeof COST_CATEGORIES)[number];

export interface CostSummaryInput {
  areaHa: number;
  costs: readonly { category: CostCategory; amount: number }[];
  expectedYieldTHa?: number | null;
  expectedPricePerT?: number | null;
  actualYieldT?: number | null;
  actualPricePerT?: number | null;
}

export interface CostSummary {
  totalCost: number;
  costPerHa: number;
  byCategory: Record<CostCategory, number>;
  estimatedRevenue: number | null;
  estimatedProfit: number | null;
  actualRevenue: number | null;
  actualProfit: number | null;
}

const money = (value: number) => Math.round(value * 100) / 100;

export function summarizeCosts(input: CostSummaryInput): CostSummary {
  const byCategory = Object.fromEntries(COST_CATEGORIES.map((c) => [c, 0])) as Record<CostCategory, number>;
  for (const cost of input.costs) byCategory[cost.category] += cost.amount;
  for (const category of COST_CATEGORIES) byCategory[category] = money(byCategory[category]);

  const totalCost = money(input.costs.reduce((sum, cost) => sum + cost.amount, 0));
  const estimatedRevenue =
    input.expectedYieldTHa != null && input.expectedPricePerT != null
      ? money(input.expectedYieldTHa * input.areaHa * input.expectedPricePerT)
      : null;
  const actualRevenue =
    input.actualYieldT != null && input.actualPricePerT != null
      ? money(input.actualYieldT * input.actualPricePerT)
      : null;

  return {
    totalCost,
    costPerHa: input.areaHa > 0 ? money(totalCost / input.areaHa) : 0,
    byCategory,
    estimatedRevenue,
    estimatedProfit: estimatedRevenue == null ? null : money(estimatedRevenue - totalCost),
    actualRevenue,
    actualProfit: actualRevenue == null ? null : money(actualRevenue - totalCost),
  };
}
