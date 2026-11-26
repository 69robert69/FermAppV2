import { CROPS, type CropCode } from '../crops';
import { CROP_CALENDARS, rankCropsForParcel } from '../parcelPlan';
import { evaluateRotation } from '../rotation';
import { TILLAGE_SYSTEMS, type TillageSystem } from '../tillage';
import { blank, confidenceOf, cropName, dayLabel, euro, euroHa, list, parcelGaps, relativeDays, signedPct, t, type Context } from './compose';
import { describeSeason, expectedHarvest, parcelFacts, TEXTURE_LABELS, usualSowingDate, type ParcelFacts } from './facts';
import { addDays, daysBetween, mentions, normalize } from './language';
import { propose } from './tasks';
import type { CopilotAnswer, CopilotParcel } from './types';

const clamp = (value: number, min: number, max: number) => Math.max(min, Math.min(max, value));

// ------------------------------------------------------------------ estimates

export interface YieldEstimate {
  low: number;
  expected: number;
  high: number;
  factors: string[];
}

export function estimateYield(facts: ParcelFacts, crop: CropCode, ctx: Context): YieldEstimate {
  const profile = CROPS[crop];
  const factors: string[] = [];
  let expected = profile.referenceYieldTHa;
  const same = facts.yields.filter((item) => item.crop === crop);
  if (same.length) {
    const mean = same.reduce((sum, item) => sum + item.tHa, 0) / same.length;
    expected = mean * 0.65 + expected * 0.35;
    factors.push(`${same.length} recorded ${cropName(crop)} season${same.length > 1 ? 's' : ''} on this parcel averaged ${t(mean)} t/ha`);
  } else if (facts.productivity != null) {
    const index = clamp(facts.productivity, 0.7, 1.3);
    expected *= index;
    factors.push(`the parcel’s yields run at ${Math.round(index * 100)}% of regional reference yields`);
  }
  const soil = facts.parcel.soil;
  if (soil?.ph != null) {
    const [min, max] = profile.phRange;
    if (soil.ph < min || soil.ph > max) {
      expected *= 0.9;
      factors.push(`pH ${soil.ph} is outside the ${min}–${max} range ${cropName(crop)} prefers (−10%)`);
    }
  }
  if (facts.texture && !profile.textures.includes(facts.texture)) {
    expected *= 0.9;
    factors.push(`${TEXTURE_LABELS[facts.texture]} soil is not ideal for ${cropName(crop)} (−10%)`);
  }
  const thirsty = profile.waterMm[0] >= 450;
  if (facts.irrigated && thirsty) {
    expected *= 1.08;
    factors.push('irrigation secures water in the critical period (+8%)');
  } else if (!facts.irrigated && thirsty && ctx.weather?.past30RainMm != null && ctx.weather.past30RainMm < 25 && facts.crop === crop) {
    expected *= 0.94;
    factors.push(`only ${Math.round(ctx.weather.past30RainMm)} mm of rain in the last 30 days (−6%)`);
  }
  const spread = same.length >= 2 ? 0.1 : 0.15;
  const round = (value: number) => Math.round(value * 10) / 10;
  return { low: round(expected * (1 - spread)), expected: round(expected), high: round(expected * (1 + spread)), factors };
}

export interface ProfitEstimate {
  pricePerT: number;
  costPerHa: number;
  revenuePerHa: number;
  profitPerHa: number;
  /** True when the cost comes from the parcel's own records. */
  recordedCost: boolean;
}

export function estimateProfit(facts: ParcelFacts, crop: CropCode, yieldTHa: number): ProfitEstimate {
  const calendar = CROP_CALENDARS[crop];
  const sameSeason = facts.season?.crop === crop ? facts.season : null;
  const pricePerT = sameSeason?.actualPricePerT ?? sameSeason?.expectedPricePerT ?? calendar.priceEurPerT;
  const recorded = sameSeason && facts.parcel.economics && facts.parcel.economics.costPerHa > 0 ? facts.parcel.economics.costPerHa : null;
  const tillageShift = facts.parcel.tillage ? TILLAGE_SYSTEMS[facts.parcel.tillage].costPerHa - TILLAGE_SYSTEMS.CONVENTIONAL.costPerHa : 0;
  const costPerHa = recorded ?? Math.max(200, calendar.costEurHa + tillageShift * 0.5);
  const revenuePerHa = yieldTHa * pricePerT;
  return { pricePerT, costPerHa, revenuePerHa, profitPerHa: revenuePerHa - costPerHa, recordedCost: recorded != null };
}

/** Profit per hectare actually recorded, expected from the season data, or estimated. */
export function parcelProfitPerHa(facts: ParcelFacts, ctx: Context): { value: number; basis: 'actual' | 'expected' | 'estimate'; costPerHa: number } | null {
  const economics = facts.parcel.economics;
  if (economics?.actualProfitPerHa != null) return { value: economics.actualProfitPerHa, basis: 'actual', costPerHa: economics.costPerHa };
  if (economics?.expectedProfitPerHa != null) return { value: economics.expectedProfitPerHa, basis: 'expected', costPerHa: economics.costPerHa };
  if (!facts.crop) return null;
  const estimate = estimateProfit(facts, facts.crop, estimateYield(facts, facts.crop, ctx).expected);
  return { value: estimate.profitPerHa, basis: 'estimate', costPerHa: estimate.costPerHa };
}

const historyOf = (facts: ParcelFacts) => facts.parcel.seasons.map(({ crop, year }) => ({ crop, year }));

function rankFor(facts: ParcelFacts, year: number) {
  const soil = facts.parcel.soil?.ph != null && facts.texture ? { ph: facts.parcel.soil.ph, texture: facts.texture } : null;
  return rankCropsForParcel({ year, history: historyOf(facts), soil, climate: null });
}

/** Agronomic risks of growing a crop on the parcel. */
function cropRisks(facts: ParcelFacts, crop: CropCode, year: number, yieldTHa: number): string[] {
  const risks: string[] = [];
  const profile = CROPS[crop];
  const calendar = CROP_CALENDARS[crop];
  const last = facts.parcel.seasons.find((season) => season.year === year - 1) ?? facts.previous[0];
  const nitrogen = Math.round(calendar.nutrientsPerT[0] * yieldTHa);
  if (nitrogen >= 120) {
    const credit = last && CROPS[last.crop].family === 'LEGUME' ? 30 : 0;
    risks.push(
      credit
        ? `Nitrogen demand is about ${nitrogen} kg N/ha; the previous ${cropName(last!.crop)} leaves roughly ${credit} kg N/ha, so plan ${nitrogen - credit} kg N/ha.`
        : `Nitrogen deficiency risk: ${cropName(crop)} needs about ${nitrogen} kg N/ha for this yield${last ? `, and ${cropName(last.crop)} leaves little residual nitrogen` : ''}.`,
    );
  }
  const rotation = evaluateRotation(crop, year, historyOf(facts));
  if (rotation.score < 70) risks.push(rotation.explanation);
  if (!facts.irrigated && profile.waterMm[0] >= 450) {
    const [from, to] = calendar.criticalMonths;
    risks.push(`Drought sensitivity: water shortage in months ${from}–${to} cuts yield the most, and the parcel is not irrigated.`);
  }
  if (facts.texture === 'CLAY' && (crop === 'SUNFLOWER' || crop === 'POTATO' || crop === 'BEET')) risks.push('Heavy clay delays emergence and makes root and tuber crops harder to establish.');
  if (facts.parcel.soil?.ph != null) {
    const [min, max] = profile.phRange;
    if (facts.parcel.soil.ph < min) risks.push(`Acidic soil (pH ${facts.parcel.soil.ph}): consider liming before sowing.`);
    if (facts.parcel.soil.ph > max) risks.push(`Alkaline soil (pH ${facts.parcel.soil.ph}) can lock up phosphorus and micronutrients.`);
  }
  return risks;
}

// ------------------------------------------------------------------ builders

/** "What should I plant next year?" */
export function nextCrop(ctx: Context, parcel: CopilotParcel): CopilotAnswer {
  const facts = parcelFacts(ctx.snapshot, parcel);
  const year = mentions(ctx.text, ['this year', 'this season', 'anul acesta', 'anul asta', 'sezonul acesta']) ? facts.year : facts.year + 1;
  const ranking = rankFor(facts, year);
  const best = ranking[0]!;
  const answer = blank('NEXT_CROP', ctx, parcel.id);
  answer.crop = best.crop;
  const estimate = estimateYield(facts, best.crop, ctx);
  const profit = estimateProfit(facts, best.crop, estimate.expected);
  // Planning a later season: the crop in the field now is the "previous" one.
  const last = year > facts.year ? facts.season ?? facts.previous[0] : facts.previous[0] ?? facts.season;
  const soil = facts.parcel.soil;

  const basis = [
    last ? `the previous ${describeSeason(last)}` : null,
    soil?.ph != null ? `soil pH ${soil.ph}` : null,
    facts.texture ? `${TEXTURE_LABELS[facts.texture]} texture` : null,
    facts.yields.length ? `the yield history of ${facts.yields.length} season${facts.yields.length > 1 ? 's' : ''}` : null,
    facts.parcel.tillage ? TILLAGE_SYSTEMS[facts.parcel.tillage].name : null,
  ].filter((item): item is string => Boolean(item));
  answer.direct =
    `${CROPS[best.crop].name} is the best option for ${parcel.name} in ${year}` +
    (basis.length ? `, based on ${list(basis)}.` : '.') +
    ` Expected yield ${t(estimate.low)}–${t(estimate.high)} t/ha under normal rainfall` +
    (ctx.snapshot.canSeeFinance ? `, with a profit of about ${euroHa(profit.profitPerHa)}.` : '.');
  answer.reasoning = [best.reason, `Rotation and soil score: ${best.score}/100.`, ...estimate.factors.map((factor) => `Yield: ${factor}.`)];
  if (facts.soilScore != null) answer.reasoning.push(`Soil health score ${facts.soilScore}/100.`);
  answer.risks = cropRisks(facts, best.crop, year, estimate.expected);
  answer.alternatives = ranking.slice(1, 3).map((option) => {
    const other = estimateYield(facts, option.crop, ctx);
    const otherProfit = estimateProfit(facts, option.crop, other.expected);
    return `${CROPS[option.crop].name} (score ${option.score}/100, about ${t(other.expected)} t/ha${ctx.snapshot.canSeeFinance ? `, ${euroHa(otherProfit.profitPerHa)}` : ''}): ${option.reason}`;
  });
  const worst = ranking[ranking.length - 1];
  if (worst && worst.score < 40) answer.alternatives.push(`Avoid ${cropName(worst.crop)}: ${worst.reason}`);
  if (ctx.snapshot.canSeeFinance) {
    answer.economics = [
      `Revenue ≈ ${t(estimate.expected)} t/ha × ${euro(profit.pricePerT)}/t = ${euroHa(profit.revenuePerHa)}; cost ≈ ${euroHa(profit.costPerHa)}${profit.recordedCost ? ' (your recorded cost)' : ' (typical cost)'}.`,
      `For the whole parcel (${t(parcel.areaHa)} ha): about ${euro(profit.profitPerHa * parcel.areaHa)} profit.`,
    ];
  }

  const sowing = usualSowingDate(best.crop, ctx.today);
  if (!soil) {
    answer.proposals.push(propose(ctx.snapshot, { type: 'SOIL_SAMPLING', parcelId: parcel.id, dueDate: addDays(ctx.today, 7), priority: 'MEDIUM', reason: 'A soil analysis confirms pH and nutrients before the crop choice is final.', windowDays: 14 }));
  }
  answer.proposals.push(
    propose(ctx.snapshot, { type: 'SEED_PURCHASE', parcelId: parcel.id, dueDate: addDays(sowing, -45) < ctx.today ? addDays(ctx.today, 3) : addDays(sowing, -45), priority: 'MEDIUM', reason: `Order ${cropName(best.crop)} seed early for the best hybrid choice and price.`, windowDays: 14, title: `Buy ${cropName(best.crop)} seed – ${parcel.name}` }),
    propose(ctx.snapshot, { type: 'SOWING', parcelId: parcel.id, dueDate: sowing, priority: 'HIGH', reason: `The usual sowing window for ${cropName(best.crop)} starts on ${dayLabel(sowing, ctx.today)}.`, windowDays: 14, title: `Plant ${cropName(best.crop)} – ${parcel.name}` }),
  );
  answer.nextSteps = [
    !soil ? 'Run a soil analysis to confirm the choice.' : null,
    `Order seed by ${dayLabel(addDays(sowing, -45), ctx.today)}.`,
    `Prepare the field for sowing around ${dayLabel(sowing, ctx.today)}.`,
  ].filter((item): item is string => Boolean(item));
  answer.dataGaps = parcelGaps(facts, ctx, { soil: true, history: true });
  answer.confidence = confidenceOf(answer.dataGaps);
  return answer;
}

/** "Is corn suitable for this field?" */
export function suitability(ctx: Context, parcel: CopilotParcel, crop: CropCode): CopilotAnswer {
  const facts = parcelFacts(ctx.snapshot, parcel);
  const year = facts.season && facts.season.crop !== crop && !facts.season.harvestDate ? facts.year + 1 : facts.year + (facts.season ? 1 : 0);
  const ranking = rankFor(facts, year);
  const entry = ranking.find((item) => item.crop === crop)!;
  const estimate = estimateYield(facts, crop, ctx);
  const profit = estimateProfit(facts, crop, estimate.expected);
  const answer = blank('CROP_SUITABILITY', ctx, parcel.id);
  answer.crop = crop;
  const verdict = entry.score >= 70 ? 'suitable' : entry.score >= 40 ? 'possible, with conditions' : 'not recommended';
  answer.direct = `${CROPS[crop].name} on ${parcel.name} in ${year}: ${verdict} (score ${entry.score}/100). Expected ${t(estimate.low)}–${t(estimate.high)} t/ha${ctx.snapshot.canSeeFinance ? `, about ${euroHa(profit.profitPerHa)} profit` : ''}.`;
  answer.reasoning = [entry.reason, ...entry.reasons, ...estimate.factors.map((factor) => `Yield: ${factor}.`)];
  answer.risks = cropRisks(facts, crop, year, estimate.expected);
  const better = ranking.filter((item) => item.crop !== crop && item.score > entry.score).slice(0, 2);
  answer.alternatives = better.map((item) => `${CROPS[item.crop].name} scores higher (${item.score}/100): ${item.reason}`);
  if (!answer.alternatives.length) answer.alternatives.push(`${CROPS[crop].name} is among the best-scoring crops for this parcel.`);
  if (ctx.snapshot.canSeeFinance) {
    answer.economics = [`Revenue ≈ ${euroHa(profit.revenuePerHa)} at ${euro(profit.pricePerT)}/t; cost ≈ ${euroHa(profit.costPerHa)}; profit ≈ ${euroHa(profit.profitPerHa)} (${euro(profit.profitPerHa * parcel.areaHa)} for ${t(parcel.areaHa)} ha).`];
  }
  if (entry.score >= 40) {
    const sowing = usualSowingDate(crop, ctx.today);
    answer.proposals.push(propose(ctx.snapshot, { type: 'SOWING', parcelId: parcel.id, dueDate: sowing, priority: 'HIGH', reason: `Usual sowing window for ${cropName(crop)}.`, windowDays: 14, title: `Plant ${cropName(crop)} – ${parcel.name}` }));
    answer.nextSteps = [`Plan sowing around ${dayLabel(sowing, ctx.today)}.`, ...answer.risks.slice(0, 1).map((risk) => `Address: ${risk}`)];
  } else {
    answer.nextSteps = better.length ? [`Consider ${cropName(better[0]!.crop)} instead.`] : ['Review the rotation before choosing this crop.'];
  }
  answer.dataGaps = parcelGaps(facts, ctx, { soil: true, history: true });
  answer.confidence = confidenceOf(answer.dataGaps);
  return answer;
}

/** Days from sowing of the next nitrogen pass, and whether it has been done. */
export function nitrogenStatus(facts: ParcelFacts) {
  if (!facts.season?.sowingDate || !facts.crop) return null;
  const calendar = CROP_CALENDARS[facts.crop];
  if (calendar.topDressingDays == null) return { due: null, done: null };
  const due = addDays(facts.season.sowingDate, calendar.topDressingDays);
  const after = facts.season.sowingDate.slice(0, 10);
  const doneRun = [...facts.lastWork.entries()].find(([type, day]) => type === 'FERTILIZING' && day >= after)?.[1] ?? null;
  const doneTask = facts.done.find((task) => (task.type === 'NITROGEN' || task.type === 'FERTILIZATION') && task.dueDate.slice(0, 10) >= after);
  return { due, done: doneRun ?? doneTask?.dueDate.slice(0, 10) ?? null };
}

/** "When should I fertilize?" */
export function fertilize(ctx: Context, parcel: CopilotParcel): CopilotAnswer {
  const facts = parcelFacts(ctx.snapshot, parcel);
  const answer = blank('FERTILIZE', ctx, parcel.id);
  const weather = ctx.weather;
  const crop = facts.crop ?? ctx.crop;
  answer.crop = crop;
  if (!crop) {
    answer.direct = `${parcel.name} has no crop recorded for this season, so there is no nitrogen timing to follow yet. Base fertilization (P and K) goes in before sowing, adjusted to a soil analysis.`;
    answer.nextSteps = ['Record the planned crop, or ask “What should I plant next year?”.'];
    if (!parcel.soil) answer.proposals.push(propose(ctx.snapshot, { type: 'SOIL_SAMPLING', parcelId: parcel.id, dueDate: addDays(ctx.today, 7), priority: 'MEDIUM', reason: 'The fertilization plan needs the soil’s P, K and pH.', windowDays: 14 }));
    answer.dataGaps = parcelGaps(facts, ctx, { soil: true });
    answer.confidence = 'LOW';
    return answer;
  }
  const calendar = CROP_CALENDARS[crop];
  const estimate = estimateYield(facts, crop, ctx);
  const [nPerT, pPerT, kPerT] = calendar.nutrientsPerT;
  const legumeCredit = facts.previous[0] && CROPS[facts.previous[0].crop].family === 'LEGUME' ? 30 : 0;
  const omCredit = parcel.soil?.organicMatterPct != null ? Math.round(Math.min(40, parcel.soil.organicMatterPct * 10)) : 0;
  const nRate = Math.max(0, Math.round(nPerT * estimate.expected - legumeCredit - omCredit));
  const status = facts.season?.sowingDate ? nitrogenStatus(facts) : null;

  let target: string;
  if (!facts.season?.sowingDate) {
    const sowing = usualSowingDate(crop, ctx.today);
    target = addDays(sowing, -10);
    answer.direct = `Apply base fertilization on ${parcel.name} around ${dayLabel(target, ctx.today)}, about 10 days before ${cropName(crop)} sowing: roughly ${Math.round(pPerT * estimate.expected)} kg P₂O₅ and ${Math.round(kPerT * estimate.expected)} kg K₂O per ha, plus part of the nitrogen (${Math.round(nRate * 0.4)} of ${nRate} kg N/ha).`;
    answer.proposals.push(propose(ctx.snapshot, { type: 'FERTILIZATION', parcelId: parcel.id, dueDate: target < ctx.today ? addDays(ctx.today, 2) : target, priority: 'MEDIUM', reason: `Base P and K before ${cropName(crop)} sowing, sized for ${t(estimate.expected)} t/ha.`, windowDays: 7 }));
  } else if (status?.due == null) {
    answer.direct = `${CROPS[crop].name} on ${parcel.name} does not need top-dressing nitrogen: it fixes its own. If growth looks pale, a foliar feed with micronutrients is enough.`;
    target = addDays(ctx.today, 7);
    answer.proposals.push(propose(ctx.snapshot, { type: 'CROP_SCOUTING', parcelId: parcel.id, dueDate: target, priority: 'LOW', reason: 'Check nodulation and leaf colour before deciding on a foliar feed.' }));
  } else if (status.done) {
    answer.direct = `Nitrogen was already applied on ${parcel.name} on ${dayLabel(status.done, ctx.today)}. No further top-dressing is due; scout the crop to confirm it responded.`;
    target = addDays(ctx.today, 5);
    answer.proposals.push(propose(ctx.snapshot, { type: 'CROP_SCOUTING', parcelId: parcel.id, dueDate: target, priority: 'LOW', reason: 'Confirm the response to the nitrogen applied.' }));
  } else {
    const late = status.due < ctx.today;
    target = late ? ctx.today : status.due;
    // Avoid heavy rain (leaching and runoff) and strong wind; light rain after spreading is welcome.
    if (weather && weather.rain48h >= 15 && daysBetween(ctx.today, target) <= 2) target = weather.firstDryDay && weather.firstDryDay > ctx.today ? weather.firstDryDay : addDays(ctx.today, 3);
    const window = weather?.fertilizeWindows[0];
    answer.direct = late
      ? `Nitrogen on ${parcel.name} is overdue: the top-dressing for ${cropName(crop)} was due ${relativeDays(status.due, ctx.today)}. Apply about ${Math.round(nRate * 0.6)} kg N/ha (of a ${nRate} kg N/ha season total) as soon as possible, ideally ${dayLabel(target, ctx.today)}.`
      : `Apply nitrogen on ${parcel.name} around ${dayLabel(target, ctx.today)} (${relativeDays(target, ctx.today)}), at the ${cropName(crop)} top-dressing stage: about ${Math.round(nRate * 0.6)} kg N/ha of a ${nRate} kg N/ha season total.`;
    if (window) answer.reasoning.push(`Best spreading window in the forecast: ${window.label}.`);
    answer.proposals.push(
      propose(ctx.snapshot, {
        type: 'NITROGEN',
        parcelId: parcel.id,
        dueDate: target,
        priority: late ? 'HIGH' : daysBetween(ctx.today, target) <= 7 ? 'HIGH' : 'MEDIUM',
        reason: `${CROPS[crop].name} top-dressing, ${Math.round(nRate * 0.6)} kg N/ha${late ? ' (overdue)' : ''}.`,
        windowDays: 5,
        title: `Apply nitrogen fertilizer – ${parcel.name}`,
      }),
    );
  }
  answer.reasoning.unshift(
    `Nitrogen need ≈ ${nPerT} kg N/t × ${t(estimate.expected)} t/ha = ${Math.round(nPerT * estimate.expected)} kg N/ha${legumeCredit ? `, minus ${legumeCredit} kg from the previous legume` : ''}${omCredit ? `, minus ${omCredit} kg mineralised from ${parcel.soil!.organicMatterPct}% organic matter` : ''}.`,
  );
  if (weather) {
    if (weather.rain48h >= 15) answer.risks.push(`${weather.rain48h} mm of rain is expected within 48 hours: spreading now risks leaching and runoff.`);
    else if (weather.rain48h >= 2) answer.reasoning.push(`Light rain (${weather.rain48h} mm) in the next 48 hours will help dissolve granular nitrogen.`);
    if (weather.maxWind48h > 25) answer.risks.push(`Wind up to ${weather.maxWind48h} km/h makes spreading uneven.`);
    if ((weather.minTemp7d ?? 10) <= 0) answer.risks.push('Frost is forecast: avoid spreading on frozen soil.');
  }
  if (nRate >= 150) answer.risks.push(`High nitrogen rate (${nRate} kg N/ha): split it into two passes to limit losses.`);
  answer.alternatives = ['Split the dose into two passes 2–3 weeks apart for better uptake.', 'Use a stabilised (inhibitor) urea if rain is uncertain, to reduce volatilisation.'];
  if (ctx.snapshot.canSeeFinance) answer.economics = [`At about 1.1 €/kg N, ${nRate} kg N/ha costs ≈ ${euroHa(nRate * 1.1)} (${euro(nRate * 1.1 * parcel.areaHa)} for the parcel). Under-fertilizing by 30 kg N/ha can cost 0.5–1 t/ha.`];
  answer.nextSteps = answer.proposals.length ? [`Create the task for ${dayLabel(answer.proposals[0]!.dueDate, ctx.today)}.`, 'Check the spreader calibration before the pass.'] : [];
  answer.dataGaps = parcelGaps(facts, ctx, { soil: true, weather: true });
  answer.confidence = confidenceOf(answer.dataGaps);
  return answer;
}

/** "When should I harvest?" */
export function harvest(ctx: Context, parcel: CopilotParcel): CopilotAnswer {
  const facts = parcelFacts(ctx.snapshot, parcel);
  const answer = blank('HARVEST', ctx, parcel.id);
  const crop = facts.crop;
  answer.crop = crop;
  if (!crop || !facts.season) {
    answer.direct = `${parcel.name} has no crop recorded this season, so there is nothing to harvest.`;
    answer.confidence = 'LOW';
    return answer;
  }
  if (facts.season.harvestDate && facts.season.harvestDate.slice(0, 10) <= ctx.today) {
    answer.direct = `${CROPS[crop].name} on ${parcel.name} was harvested on ${dayLabel(facts.season.harvestDate, ctx.today)}.`;
    answer.nextSteps = ['Plan soil sampling and the tillage for the next crop.'];
    answer.proposals = propose(ctx.snapshot, { type: 'HARVEST', parcelId: parcel.id, dueDate: facts.season.harvestDate, priority: 'LOW', reason: '' }).followUps;
    return answer;
  }
  const expected = expectedHarvest(facts);
  if (!expected) {
    answer.direct = `The sowing date of ${cropName(crop)} on ${parcel.name} is not recorded, so the harvest date cannot be estimated. Record it in the parcel’s season.`;
    answer.confidence = 'LOW';
    return answer;
  }
  let start = expected < ctx.today ? ctx.today : expected;
  const weather = ctx.weather;
  const soon = daysBetween(ctx.today, start) <= 7;
  const window = weather?.harvestWindows[0];
  if (soon && window) start = window.start.slice(0, 10) >= ctx.today ? window.start.slice(0, 10) : start;
  const progress = facts.progress;
  answer.direct = `Harvest ${cropName(crop)} on ${parcel.name} around ${dayLabel(start, ctx.today)} (${relativeDays(start, ctx.today)})${progress ? `: the crop is at ${progress.percent}% of its season` : ''}.`;
  answer.reasoning = [
    facts.season.sowingDate ? `Sown on ${dayLabel(facts.season.sowingDate, ctx.today)}; ${cropName(crop)} usually needs about ${CROP_CALENDARS[crop].harvestDays} days to harvest.` : 'Estimated from the crop’s growth progress.',
  ];
  if (window && soon) answer.reasoning.push(`Dry harvesting window in the forecast: ${window.label}.`);
  if (weather && soon && weather.rain48h >= 5) answer.risks.push(`${weather.rain48h} mm of rain in the next 48 hours: grain moisture will rise and drying costs with it.`);
  if (crop === 'RAPESEED' || crop === 'SUNFLOWER') answer.risks.push(`${CROPS[crop].name} shatters or lodges when left too long: do not delay once it is ripe.`);
  if (crop === 'CORN') answer.risks.push('Harvesting corn above 25% moisture raises drying costs; below 18% field losses grow.');
  const combines = ctx.snapshot.machines.filter((machine) => machine.type === 'COMBINE');
  if (!combines.length) answer.risks.push('There is no combine in the fleet: book a contractor in time.');
  const sameDay = ctx.snapshot.tasks.filter((task) => task.type === 'HARVEST' && task.status !== 'DONE' && task.parcelId !== parcel.id && Math.abs(daysBetween(task.dueDate, start)) <= 2);
  if (combines.length && sameDay.length >= combines.length) answer.risks.push(`Machinery conflict: ${sameDay.length} other harvest task(s) fall within 2 days with ${combines.length} combine(s).`);
  const estimate = estimateYield(facts, crop, ctx);
  answer.alternatives = [`Harvest earlier at higher moisture and dry the grain, if rain threatens a ripe crop.`];
  if (ctx.snapshot.canSeeFinance) {
    const profit = estimateProfit(facts, crop, estimate.expected);
    answer.economics = [`Expected ${t(estimate.expected)} t/ha × ${t(parcel.areaHa)} ha ≈ ${t(estimate.expected * parcel.areaHa)} t, worth about ${euro(profit.revenuePerHa * parcel.areaHa)}. Each week of delay on a ripe crop can lose 1–3% to shattering and lodging.`];
  }
  const existing = facts.open.find((task) => task.type === 'HARVEST');
  if (existing) {
    answer.nextSteps.push(`A harvest task is already planned for ${dayLabel(existing.dueDate, ctx.today)}.`);
    if (Math.abs(daysBetween(existing.dueDate, start)) >= 3) {
      answer.changes.push({ key: `c-${existing.id}`, kind: 'RESCHEDULE', taskId: existing.id, title: existing.title, parcelId: parcel.id, fromDate: existing.dueDate.slice(0, 10), toDate: start, fromPriority: existing.priority, reason: 'Aligned with the estimated maturity and the forecast.' });
    }
  } else {
    if (daysBetween(ctx.today, start) >= 5) answer.proposals.push(propose(ctx.snapshot, { type: 'HARVEST_PREP', parcelId: parcel.id, dueDate: addDays(start, -5), priority: 'MEDIUM', reason: 'Service the combine and prepare trailers and storage before harvest.', windowDays: 3, withFollowUps: false }));
    answer.proposals.push(propose(ctx.snapshot, { type: 'HARVEST', parcelId: parcel.id, dueDate: start, priority: soon ? 'HIGH' : 'MEDIUM', reason: `Estimated maturity of ${cropName(crop)}${window && soon ? ' and a dry window in the forecast' : ''}.`, windowDays: 7, title: `Harvest ${parcel.name}` }));
    answer.nextSteps = ['Check grain moisture a few days before.', 'Arrange trailers and storage.'];
  }
  answer.dataGaps = parcelGaps(facts, ctx, { weather: soon });
  answer.confidence = facts.season.sowingDate ? confidenceOf(answer.dataGaps) : 'LOW';
  return answer;
}

const cropCoefficient = (percent: number) => (percent < 25 ? 0.5 : percent < 75 ? 1.1 : 0.75);

/** "Should I irrigate this week?" */
export function irrigate(ctx: Context, parcel: CopilotParcel): CopilotAnswer {
  const facts = parcelFacts(ctx.snapshot, parcel);
  const answer = blank('IRRIGATE', ctx, parcel.id);
  const weather = ctx.weather;
  const crop = facts.crop;
  answer.crop = crop;
  const running = facts.activeRuns.find((run) => run.kind === 'IRRIGATION');
  if (!weather) {
    answer.direct = `I cannot judge irrigation on ${parcel.name} without the forecast. Check soil moisture in the field.`;
    answer.proposals.push(propose(ctx.snapshot, { type: 'MOISTURE_CHECK', parcelId: parcel.id, dueDate: ctx.today, priority: 'MEDIUM', reason: 'No forecast available.' }));
    answer.dataGaps = parcelGaps(facts, ctx, { weather: true });
    answer.confidence = 'LOW';
    return answer;
  }
  const kc = facts.progress && facts.progress.status === 'GROWING' ? cropCoefficient(facts.progress.percent) : 0.4;
  const need = Math.round(weather.et0_7d * kc);
  const deficit = Math.round(need - weather.rain7d);
  const critical = crop ? (() => {
    const month = Number(ctx.today.slice(5, 7));
    const [from, to] = CROP_CALENDARS[crop].criticalMonths;
    return month >= from && month <= to;
  })() : false;
  answer.reasoning = [
    `Crop water use over 7 days ≈ ET₀ ${weather.et0_7d} mm × Kc ${kc} = ${need} mm; forecast rain ${weather.rain7d} mm; balance ${deficit > 0 ? `−${deficit}` : `+${-deficit}`} mm.`,
  ];
  if (weather.past30RainMm != null) answer.reasoning.push(`Rain in the last 30 days: ${Math.round(weather.past30RainMm)} mm.`);
  if (critical && crop) answer.reasoning.push(`${CROPS[crop].name} is in its critical water period: shortage now costs the most yield.`);
  if (running) {
    answer.direct = `Irrigation is already running on ${parcel.name}. ${deficit > 0 ? `The 7-day deficit is ${deficit} mm, so keep it going.` : 'Rain will cover the need, so you can stop after this pass.'}`;
    if (deficit <= 0) answer.proposals.push(propose(ctx.snapshot, { type: 'IRRIGATION_STOP', parcelId: parcel.id, dueDate: ctx.today, priority: 'MEDIUM', reason: `Forecast rain (${weather.rain7d} mm) covers the crop’s need.`, withFollowUps: false }));
  } else if (!facts.irrigated) {
    answer.direct = `${parcel.name} has no irrigation configured. ${deficit > 15 ? `The crop faces a ${deficit} mm water deficit this week; consider moisture-saving measures.` : 'Rain should cover the crop’s needs this week.'}`;
    answer.alternatives = ['Set the irrigated area in the parcel settings if part of it can be watered.', 'Reduce evaporation by keeping residue on the surface (minimum or no tillage).'];
  } else if (deficit > 20 && weather.rain48h < 10) {
    const dose = Math.min(35, Math.max(20, Math.round(deficit * 0.8)));
    const start = weather.rain48h >= 3 ? addDays(ctx.today, 2) : ctx.today;
    answer.direct = `Yes, irrigate ${parcel.name} this week: the crop will be ${deficit} mm short. Apply about ${dose} mm starting ${dayLabel(start, ctx.today)}.`;
    answer.proposals.push(propose(ctx.snapshot, { type: 'IRRIGATION_START', parcelId: parcel.id, dueDate: start, priority: critical ? 'HIGH' : 'MEDIUM', reason: `${deficit} mm deficit over 7 days; dose ${dose} mm.`, windowDays: 2 }));
  } else if (weather.rain48h >= 10) {
    answer.direct = `No, wait: ${weather.rain48h} mm of rain is expected within 48 hours on ${parcel.name}. Re-check after the rain.`;
    answer.proposals.push(propose(ctx.snapshot, { type: 'MOISTURE_CHECK', parcelId: parcel.id, dueDate: addDays(ctx.today, 3), priority: 'LOW', reason: 'Check soil moisture after the forecast rain.' }));
  } else {
    answer.direct = `Not needed this week on ${parcel.name}: the water balance is ${deficit > 0 ? `only ${deficit} mm short` : `${-deficit} mm in surplus`}.`;
  }
  if ((weather.maxTemp7d ?? 0) >= 32) answer.risks.push(`Heat up to ${Math.round(weather.maxTemp7d!)} °C will raise water use above the estimate.`);
  if (weather.maxWind48h > 30) answer.risks.push(`Wind up to ${weather.maxWind48h} km/h reduces sprinkler uniformity: irrigate at night or early morning.`);
  if (ctx.snapshot.canSeeFinance && facts.irrigated) answer.economics = ['Each 25 mm pass costs roughly 25–45 €/ha in energy and labour; in the critical period it protects 10–30% of yield.'];
  answer.nextSteps = answer.proposals.length ? ['Irrigate at night or early morning to cut evaporation losses.'] : [];
  answer.dataGaps = parcelGaps(facts, ctx, {});
  answer.confidence = facts.progress ? 'MEDIUM' : 'LOW';
  return answer;
}

/** Which treatment the crop stage calls for. */
export function dueTreatment(facts: ParcelFacts): { type: 'HERBICIDE' | 'FUNGICIDE' | 'INSECTICIDE'; reason: string } | null {
  if (!facts.crop || !facts.season?.sowingDate || facts.progress?.status !== 'GROWING') return null;
  const day = daysBetween(facts.season.sowingDate, facts.today);
  const calendar = CROP_CALENDARS[facts.crop];
  if (day <= 45) return { type: 'HERBICIDE', reason: `Early post-emergence weed control (day ${day} after sowing).` };
  if (Math.abs(day - calendar.diseaseDays) <= 14) return { type: 'FUNGICIDE', reason: `The disease-risk stage of ${cropName(facts.crop)} (around day ${calendar.diseaseDays}).` };
  if (Math.abs(day - calendar.pestDays) <= 14) return { type: 'INSECTICIDE', reason: `The pest-risk stage of ${cropName(facts.crop)} (around day ${calendar.pestDays}).` };
  return null;
}

/** "Can I spray?" */
export function spray(ctx: Context, parcel: CopilotParcel): CopilotAnswer {
  const facts = parcelFacts(ctx.snapshot, parcel);
  const answer = blank('SPRAY', ctx, parcel.id);
  const weather = ctx.weather;
  const treatment = dueTreatment(facts);
  const planned = facts.open.find((task) => taskIsSpray(task.type));
  const window = weather?.sprayWindows[0];
  const blocked = weather && (weather.rain48h >= 2 || weather.rainChance48h >= 60 || (weather.hoursToRain != null && weather.hoursToRain < 6));
  const windy = weather && weather.maxWind48h > 15;
  if (!weather) {
    answer.direct = `I cannot check spraying conditions on ${parcel.name} without the forecast.`;
  } else if (blocked) {
    answer.direct = `Rain is expected within 48 hours (${weather.rain48h} mm, up to ${weather.rainChance48h}% chance). Spraying ${parcel.name} is not recommended now${window ? `; the next good window is ${window.label}` : ''}.`;
  } else if (window) {
    answer.direct = `Good spraying conditions on ${parcel.name}: best window ${window.label} (score ${window.score}/100).`;
  } else {
    answer.direct = `No suitable spraying window on ${parcel.name} in the forecast${windy ? `: wind reaches ${weather.maxWind48h} km/h` : ''}.`;
  }
  answer.reasoning = weather
    ? [`Spraying needs wind under 15 km/h, no rain for a few hours after the pass, 8–25 °C and humidity above 45%.`, `Next 48 hours: ${weather.rain48h} mm of rain, wind up to ${weather.maxWind48h} km/h.`]
    : [];
  if (treatment) answer.reasoning.push(`Crop stage: ${treatment.reason}`);
  if (windy) answer.risks.push(`Drift risk: wind up to ${weather!.maxWind48h} km/h.`);
  if (blocked) answer.risks.push('Rain shortly after the pass washes off contact products and wastes the treatment.');
  if ((weather?.maxTemp7d ?? 0) > 28) answer.risks.push('Heat above 28 °C increases evaporation and crop injury: spray early morning or evening.');
  answer.alternatives = ['Spray in the early morning, when wind and temperature are lowest.', 'Use drift-reducing nozzles if the wind is borderline.'];
  const day = window ? window.start.slice(0, 10) : weather?.firstDryDay ?? addDays(ctx.today, 1);
  if (planned) {
    answer.nextSteps.push(`“${planned.title}” is planned for ${dayLabel(planned.dueDate, ctx.today)}.`);
    if (window && planned.dueDate.slice(0, 10) !== day && (blocked || planned.dueDate.slice(0, 10) < ctx.today)) {
      answer.changes.push({ key: `c-${planned.id}`, kind: 'RESCHEDULE', taskId: planned.id, title: planned.title, parcelId: parcel.id, fromDate: planned.dueDate.slice(0, 10), toDate: day, fromPriority: planned.priority, reason: `Moved to the best spraying window (${window.label}).` });
    }
  } else if (treatment) {
    answer.proposals.push(propose(ctx.snapshot, { type: treatment.type, parcelId: parcel.id, dueDate: day, priority: 'HIGH', reason: `${treatment.reason}${window ? ` Best window: ${window.label}.` : ''}`, windowDays: 3 }));
  }
  if (ctx.snapshot.canSeeFinance) answer.economics = ['A treatment typically costs 30–80 €/ha in product plus 10–15 €/ha to apply; a washed-off pass doubles that cost.'];
  answer.nextSteps.push('Follow the product label for dose and pre-harvest interval.');
  answer.dataGaps = parcelGaps(facts, ctx, { weather: true });
  answer.confidence = confidenceOf(answer.dataGaps);
  return answer;
}

export const taskIsSpray = (type: string) => type === 'HERBICIDE' || type === 'FUNGICIDE' || type === 'INSECTICIDE' || type === 'FOLIAR_FEEDING';

/** "What yield can I expect?" */
export function yieldAnswer(ctx: Context, parcel: CopilotParcel): CopilotAnswer {
  const facts = parcelFacts(ctx.snapshot, parcel);
  const crop = ctx.crop ?? facts.crop;
  const answer = blank('YIELD', ctx, parcel.id);
  answer.crop = crop;
  if (!crop) {
    answer.direct = `${parcel.name} has no crop recorded this season. Name a crop (for example “What yield can I expect from corn here?”).`;
    answer.confidence = 'LOW';
    return answer;
  }
  const estimate = estimateYield(facts, crop, ctx);
  answer.direct = `Expected ${cropName(crop)} yield on ${parcel.name}: ${t(estimate.low)}–${t(estimate.high)} t/ha (most likely ${t(estimate.expected)} t/ha), about ${t(estimate.expected * parcel.areaHa)} t in total.`;
  answer.reasoning = [`Regional reference yield for ${cropName(crop)}: ${CROPS[crop].referenceYieldTHa} t/ha.`, ...estimate.factors.map((factor) => `${factor[0]!.toUpperCase()}${factor.slice(1)}.`)];
  const history = facts.yields.filter((item) => item.crop === crop);
  if (history.length) answer.reasoning.push(`History: ${history.map((item) => `${item.year}: ${item.tHa} t/ha`).join(', ')}.`);
  answer.risks = cropRisks(facts, crop, facts.season?.year ?? facts.year, estimate.expected).slice(0, 3);
  if (ctx.snapshot.canSeeFinance) {
    const profit = estimateProfit(facts, crop, estimate.expected);
    const low = estimateProfit(facts, crop, estimate.low);
    answer.economics = [`At ${euro(profit.pricePerT)}/t: revenue ≈ ${euroHa(profit.revenuePerHa)}, profit ≈ ${euroHa(profit.profitPerHa)} (${euroHa(low.profitPerHa)} in the low case).`];
  }
  answer.nextSteps = ['Record the actual yield after harvest: future estimates use your own history.'];
  answer.alternatives = facts.irrigated ? [] : ['Irrigation in the critical period is the biggest lever on yield for water-demanding crops.'];
  answer.dataGaps = parcelGaps(facts, ctx, { soil: true, history: true });
  answer.confidence = confidenceOf(answer.dataGaps);
  return answer;
}

/** How well a parcel suits a tillage system, 0-100, with the reasons. */
export function tillageFit(facts: ParcelFacts, system: TillageSystem): { score: number; pros: string[]; cons: string[] } {
  let score = 60;
  const pros: string[] = [];
  const cons: string[] = [];
  const reduced = system === 'NO_TILL' || system === 'STRIP_TILL';
  if (facts.texture) {
    if (facts.texture === 'LOAMY') { score += 15; pros.push('loamy soil keeps its structure without tillage'); }
    if (facts.texture === 'SANDY') { score += reduced ? 10 : 0; pros.push('sandy soil gains the most water retention from residue cover'); }
    if (facts.texture === 'CLAY') { score -= reduced ? 15 : 0; cons.push('heavy clay stays cold and wet in spring and compacts without loosening'); }
    if (facts.texture === 'CLAY_LOAM') score += 5;
  }
  const om = facts.parcel.soil?.organicMatterPct;
  if (om != null) {
    if (om >= 2.5) { score += 10; pros.push(`organic matter of ${om}% supports biological soil structure`); }
    else if (reduced) { score -= 5; cons.push(`low organic matter (${om}%) means a slower transition`); }
  }
  const crops = new Set(facts.parcel.seasons.slice(0, 4).map((season) => season.crop));
  if (crops.size >= 3) { score += 10; pros.push(`a diverse rotation (${crops.size} crops in 4 years) limits weeds and disease under residue`); }
  else if (reduced && facts.parcel.seasons.length >= 2) { score -= 10; cons.push('a narrow rotation raises weed and disease pressure under residue'); }
  const weeds = facts.parcel.observations.some((item) => mentions(normalize(item.text), ['weed', 'buruien', 'samulastra', 'pir', 'costrei']));
  if (weeds && reduced) { score -= 10; cons.push('field notes mention weed problems, which tillage currently helps to control'); }
  if (!reduced && system === 'CONVENTIONAL') { score -= 5; cons.push('ploughing costs the most fuel and breaks down organic matter'); }
  return { score: clamp(Math.round(score), 0, 100), pros, cons };
}

/** "Is No-Till a better option here?" */
export function tillageAnswer(ctx: Context, parcel: CopilotParcel, target: TillageSystem): CopilotAnswer {
  const facts = parcelFacts(ctx.snapshot, parcel);
  const answer = blank('TILLAGE', ctx, parcel.id);
  const current = parcel.tillage;
  const fit = tillageFit(facts, target);
  const profile = TILLAGE_SYSTEMS[target];
  const from = current ? TILLAGE_SYSTEMS[current] : TILLAGE_SYSTEMS.CONVENTIONAL;
  const saving = (from.costPerHa - profile.costPerHa) * parcel.areaHa;
  const fuel = (from.fuelLPerHa - profile.fuelLPerHa) * parcel.areaHa;
  if (current === target) {
    answer.direct = `${parcel.name} already uses ${profile.name}. Suitability score ${fit.score}/100.`;
  } else {
    const verdict = fit.score >= 70 ? 'a good option' : fit.score >= 50 ? 'possible, with care' : 'not a good fit right now';
    answer.direct = `${profile.name} is ${verdict} for ${parcel.name} (suitability ${fit.score}/100). Compared with ${from.name}, it ${saving >= 0 ? 'saves' : 'costs'} about ${euro(Math.abs(saving))} and ${Math.round(Math.abs(fuel))} l of diesel per season on ${t(parcel.areaHa)} ha.`;
  }
  answer.reasoning = [...fit.pros.map((item) => `+ ${item}`), ...fit.cons.map((item) => `− ${item}`), `Soil health ${from.soilHealth} → ${profile.soilHealth}/100; water retention +${profile.waterGainPct - from.waterGainPct}%.`];
  answer.risks = [...fit.cons];
  if (target === 'NO_TILL' || target === 'STRIP_TILL') answer.risks.push('Yields can dip 5–10% in the first 2–3 transition years while the soil biology adapts.', 'Direct sowing needs a suitable drill (a conventional seed drill cannot cut through residue).');
  const seeders = ctx.snapshot.machines.filter((machine) => machine.type === 'SEEDER');
  if ((target === 'NO_TILL' || target === 'STRIP_TILL') && !seeders.length) answer.risks.push('There is no seed drill in the fleet.');
  answer.alternatives = (['MINIMUM_TILL', 'STRIP_TILL', 'NO_TILL', 'CONVENTIONAL'] as const)
    .filter((code) => code !== target && code !== current)
    .map((code) => ({ code, fit: tillageFit(facts, code) }))
    .sort((a, b) => b.fit.score - a.fit.score)
    .slice(0, 2)
    .map((item) => `${TILLAGE_SYSTEMS[item.code].name}: suitability ${item.fit.score}/100, ${euroHa(TILLAGE_SYSTEMS[item.code].costPerHa)}.`);
  if (ctx.snapshot.canSeeFinance) answer.economics = [`Tillage cost ${euroHa(from.costPerHa)} → ${euroHa(profile.costPerHa)}: ${saving >= 0 ? 'saving' : 'extra'} ${euro(Math.abs(saving))} per season.`, `Fuel ${from.fuelLPerHa} → ${profile.fuelLPerHa} l/ha.`];
  answer.nextSteps = current !== target && fit.score >= 50 ? ['Start on one parcel or part of it and compare yields for two seasons.', 'Change the tillage system in the parcel settings when you decide.'] : [];
  if (!parcel.soil) answer.proposals.push(propose(ctx.snapshot, { type: 'SOIL_SAMPLING', parcelId: parcel.id, dueDate: addDays(ctx.today, 7), priority: 'LOW', reason: 'Organic matter and texture decide how well reduced tillage will work.', windowDays: 14 }));
  answer.dataGaps = parcelGaps(facts, ctx, { soil: true });
  answer.confidence = confidenceOf(answer.dataGaps);
  return answer;
}

/** Profit of a parcel and how it compares with the farm. */
export function profitability(ctx: Context, parcel: CopilotParcel, farmAveragePerHa: number | null): CopilotAnswer {
  const facts = parcelFacts(ctx.snapshot, parcel);
  const answer = blank('PROFITABILITY', ctx, parcel.id);
  const profit = parcelProfitPerHa(facts, ctx);
  if (!profit) {
    answer.direct = `${parcel.name} has no crop or cost data this season, so its profitability cannot be calculated.`;
    answer.nextSteps = ['Record the crop and the expected yield and price in the parcel’s economics tab.'];
    answer.confidence = 'LOW';
    return answer;
  }
  const basis = profit.basis === 'actual' ? 'actual' : profit.basis === 'expected' ? 'expected' : 'estimated';
  const versus = farmAveragePerHa != null && farmAveragePerHa !== 0 ? ((profit.value - farmAveragePerHa) / Math.abs(farmAveragePerHa)) * 100 : null;
  answer.direct = `${parcel.name}: ${basis} profit ${euroHa(profit.value)} (${euro(profit.value * parcel.areaHa)} on ${t(parcel.areaHa)} ha)` + (versus != null && Math.abs(versus) >= 3 ? `, ${Math.abs(Math.round(versus))}% ${versus > 0 ? 'more' : 'less'} profitable than the farm average.` : '.');
  const economics = parcel.economics;
  if (economics) {
    const shares = Object.entries(economics.costShares).sort((a, b) => b[1] - a[1]);
    answer.reasoning.push(`Production cost ${euroHa(economics.costPerHa)}${shares.length ? `; biggest items: ${shares.slice(0, 3).map(([name, share]) => `${name} ${share}%`).join(', ')}` : ''}.`);
    if (economics.revenuePerHa != null) answer.reasoning.push(`Revenue ${euroHa(economics.revenuePerHa)}.`);
  } else {
    answer.reasoning.push(`No costs are recorded yet, so this uses a typical cost of ${euroHa(profit.costPerHa)} and the expected yield.`);
  }
  const roi = profit.costPerHa > 0 ? (profit.value / profit.costPerHa) * 100 : null;
  if (roi != null) answer.economics.push(`Return on cost (ROI): ${signedPct(roi)}.`);
  if (versus != null) answer.economics.push(`Farm average: ${euroHa(farmAveragePerHa!)}.`);
  if (profit.value < 0) answer.risks.push('The parcel is losing money at current yields and prices.');
  if (versus != null && versus < -15) answer.risks.push(`It earns ${Math.abs(Math.round(versus))}% less than the farm average: check costs, yield and the crop choice.`);
  answer.alternatives = facts.parcel.tillage === 'CONVENTIONAL' ? [`Switching to Minimum Till would cut tillage cost by about ${euroHa(TILLAGE_SYSTEMS.CONVENTIONAL.costPerHa - TILLAGE_SYSTEMS.MINIMUM_TILL.costPerHa)}.`] : [];
  answer.nextSteps = ['Ask “How can I reduce costs here?” for concrete savings.'];
  answer.dataGaps = parcelGaps(facts, ctx, { costs: true });
  answer.confidence = profit.basis === 'estimate' ? 'LOW' : 'HIGH';
  return answer;
}
