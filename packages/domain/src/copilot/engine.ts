import { CROPS } from '../crops';
import { TILLAGE_SYSTEMS, type TillageSystem } from '../tillage';
import {
  estimateYield,
  fertilize,
  harvest,
  irrigate,
  nextCrop,
  nitrogenStatus,
  parcelProfitPerHa,
  profitability,
  spray,
  suitability,
  taskIsSpray,
  tillageAnswer,
  tillageFit,
  yieldAnswer,
} from './agronomy';
import { blank, cropName, dayLabel, euro, euroHa, list, relativeDays, signedPct, t, type Context } from './compose';
import { expectedHarvest, parcelFacts, weatherFacts, type ParcelFacts } from './facts';
import { addDays, asksAboutFarm, daysBetween, findCrop, findParcel, findTillage, mentions, normalize, parseDate, refersToCurrentParcel } from './language';
import { farmInsights } from './proactive';
import { CATEGORY_LABELS, CATEGORY_WORDS, COPILOT_TASK_TYPES, propose, taskCategory } from './tasks';
import { EMPTY_MEMORY, type CopilotAnswer, type CopilotMemory, type CopilotParcel, type CopilotPriority, type CopilotTask, type CopilotTopic, type FarmSnapshot, type TaskCategory, type TaskChange, type TaskProposal } from './types';

// ------------------------------------------------------------------ topics

const has = (text: string, words: readonly string[]) => mentions(text, words);

/** The first matching rule wins, so specific questions come before broad ones. */
const RULES: readonly [CopilotTopic, (text: string) => boolean][] = [
  ['CREATE_TASK', (s) => has(s, ['create task', 'create the task', 'create a task', 'create them', 'create these', 'create all', 'add task', 'add the task', 'add a task', 'add them', 'add these', 'add it', 'add all', 'add to', 'put them', 'put it', 'new task', 'make a task', 'remind me', 'creeaza', 'adauga', 'pune-le', 'pune-l', 'pune in', 'pune task', 'baga', 'introdu', 'salveaza task', 'programeaza', 'plan a task', 'fa task', 'fa un task'])],
  ['RESCHEDULE', (s) => has(s, ['move$', 'reschedule', 'delay$', 'postpone', 'push back', 'push it', 'bring forward', 'shift$', 'muta$', 'amana$', 'amana-', 'reprogrameaza', 'decaleaza'])],
  ['CANCEL', (s) => has(s, ['cancel', 'delete the task', 'delete task', 'remove the task', 'remove task', 'drop the', 'anuleaza', 'sterge'])],
  ['GROUP', (s) => has(s, ['group$', 'combine the tasks', 'batch', 'grupeaza'])],
  ['REPRIORITIZE', (s) => has(s, ['prioritize', 'prioritise', 'make it urgent', 'mark as urgent', 'set priority', 'high priority', 'prioritizeaza', 'urgent']) && !has(s, ['which', 'what', 'care', 'ce '])],
  ['WHY', (s) => /^(why|de ce|explain|explica|how come|on what basis)\b/.test(s) || /^(why|de ce)\??$/.test(s)],
  ['HECTARES_LEFT', (s) => has(s, ['how many hectares', 'how many ha', 'hectares still', 'hectares left', 'cate hectare', 'ha left', 'ha still'])],
  ['IDLE_MACHINES', (s) => has(s, ['idle', 'available', 'free', 'unused', 'liber', 'libere', 'disponibil', 'nefolosit']) && has(s, ['tractor', 'machine', 'machinery', 'combine', 'equipment', 'utilaj', 'combina', 'masini'])],
  ['OVERDUE', (s) => has(s, ['overdue', 'late$', 'delayed', 'behind schedule', 'missed', 'intarziat', 'intarziere', 'restant', 'depasit'])],
  ['ROI_RANKING', (s) => has(s, ['roi', 'most profitable', 'least profitable', 'highest profit', 'lowest profit', 'best return', 'rank', 'cea mai profitabila', 'cel mai profitabil', 'cea mai putin profitabila', 'clasament'])],
  ['FARM_PROFIT', (s) => has(s, ['farm profit', 'profit of the farm', 'expected profit this season', 'total profit', 'profitul fermei', 'profit total'])],
  ['REDUCE_COSTS', (s) => has(s, ['reduce cost', 'reduce my cost', 'cut cost', 'lower cost', 'save money', 'cheaper', 'savings', 'reduce costuri', 'reduc costuri', 'economisi', 'scad costuri', 'reduce cheltuieli'])],
  ['SATELLITE', (s) => has(s, ['satellite', 'satelit', 'ndvi', 'ndre', 'ndmi', 'vegetation index', 'vegetation', 'crop health', 'field health', 'health score', 'how healthy', 'vigor', 'vigour', 'vigoare', 'sanatatea', 'starea culturii', 'imagini', 'problem area', 'zone cu probleme'])],
  ['TILLAGE', (s) => findTillage(s) != null || has(s, ['tillage', 'lucrarea solului', 'lucrari ale solului'])],
  ['CROP_SUITABILITY', (s) => findCrop(s) != null && has(s, ['suitable', 'suit', 'good for', 'good idea', 'ok for', 'fit', 'can i plant', 'can i grow', 'should i plant', 'is it worth', 'potrivit', 'merge', 'pot sa', 'bun pentru', 'best for', 'cel mai bun'])],
  ['NEXT_CROP', (s) => has(s, ['plant next', 'next year', 'next season', 'what should i plant', 'what to plant', 'what should i grow', 'what to sow', 'rotation', 'anul viitor', 'sezonul viitor', 'ce sa semanez', 'ce sa cultiv', 'ce sa pun', 'ce cultura', 'rotatie', 'rotatia'])],
  ['FERTILIZE', (s) => has(s, CATEGORY_WORDS.FERTILIZATION)],
  ['HARVEST', (s) => has(s, CATEGORY_WORDS.HARVEST)],
  ['IRRIGATE', (s) => has(s, CATEGORY_WORDS.IRRIGATION) || has(s, ['water the', 'watering'])],
  ['SPRAY', (s) => has(s, CATEGORY_WORDS.CROP_PROTECTION)],
  ['YIELD', (s) => has(s, ['yield', 'production', 't/ha', 'tons', 'tonnes', 'productie', 'productia', 'randament', 'recolta estimata'])],
  ['MACHINERY', (s) => has(s, ['machine', 'machinery', 'tractor', 'equipment', 'assign', 'operator', 'utilaj', 'utilaje', 'combina', 'aloc'])],
  ['PROFITABILITY', (s) => has(s, ['profit', 'margin', 'revenue', 'economic', 'cost', 'earn', 'money', 'castig', 'venit', 'rentabil', 'cheltuiel'])],
  ['WEEK_SCHEDULE', (s) => has(s, ['this week', 'scheduled', 'schedule', 'agenda', 'calendar', 'saptamana aceasta', 'saptamana asta', 'programat', 'planificat'])],
  ['PENDING', (s) => has(s, ['pending', 'remaining', 'still to do', 'outstanding', 'left to do', 'open tasks', 'what operations', 'ramas', 'de facut', 'in asteptare', 'neterminat'])],
  ['PRIORITIZE', (s) => has(s, ['prioritize', 'prioritise', 'priority', 'first', 'most urgent', 'focus', 'prioritate', 'mai intai', 'cel mai urgent', 'prioritar'])],
  ['RISKS', (s) => has(s, ['risk', 'problem', 'danger', 'issue', 'disease', 'pest', 'status', 'overview', 'summary', 'how is', 'how are', 'riscuri', 'risc', 'probleme', 'boli', 'daunatori', 'situatia', 'rezumat'])],
];

export function detectTopic(text: string): CopilotTopic {
  for (const [topic, test] of RULES) if (test(text)) return topic;
  return 'GENERAL';
}

/** Topics that need one parcel; asked without one they cover every parcel. */
const PARCEL_TOPICS: readonly CopilotTopic[] = ['NEXT_CROP', 'CROP_SUITABILITY', 'FERTILIZE', 'HARVEST', 'IRRIGATE', 'SPRAY', 'YIELD', 'TILLAGE', 'PROFITABILITY', 'SATELLITE'];

const TOPIC_CATEGORY: Partial<Record<CopilotTopic, TaskCategory>> = {
  FERTILIZE: 'FERTILIZATION',
  HARVEST: 'HARVEST',
  IRRIGATE: 'IRRIGATION',
  SPRAY: 'CROP_PROTECTION',
  NEXT_CROP: 'PLANTING',
  TILLAGE: 'TILLAGE',
};

// ------------------------------------------------------------------ entry point

export interface CopilotTurn {
  answer: CopilotAnswer;
  memory: CopilotMemory;
}

/** Answers a question from the farm data, remembering the parcel and topic for follow-ups. */
export function askCopilot(question: string, snapshot: FarmSnapshot, memory: CopilotMemory): CopilotTurn {
  const text = normalize(question);
  const today = snapshot.now.slice(0, 10);
  const named = findParcel(text, snapshot.parcels);
  const remembered = memory.parcelId ? snapshot.parcels.find((parcel) => parcel.id === memory.parcelId) ?? null : null;
  const farmWords = asksAboutFarm(text) && !refersToCurrentParcel(text);
  const only = snapshot.parcels.length === 1 ? snapshot.parcels[0]! : null;
  const parcel = named ?? (farmWords ? null : remembered) ?? only;
  // "Yes" right after suggested tasks approves them.
  let topic: CopilotTopic = isConfirmation(text) && memory.last?.proposals.length ? 'CREATE_TASK' : detectTopic(text);
  let crop = findCrop(text);

  // Short follow-ups ("and Field 7?", "what about wheat?") continue the previous topic.
  if (topic === 'GENERAL' && memory.topic && memory.topic !== 'WHY' && text.split(' ').length <= 7 && (named || crop || farmWords)) {
    topic = crop && (memory.topic === 'NEXT_CROP' || memory.topic === 'CROP_SUITABILITY') ? 'CROP_SUITABILITY' : memory.topic;
  }
  if (!crop && (topic === 'YIELD' || topic === 'CROP_SUITABILITY') && memory.crop && !named) crop = memory.crop;

  const ctx: Context = {
    text,
    snapshot,
    today,
    parcel,
    crop,
    memory,
    farmScope: !named && (farmWords || !parcel),
    weather: weatherFacts(snapshot),
    date: parseDate(text, today),
  };

  let answer: CopilotAnswer;
  if (!snapshot.parcels.length && topic !== 'WHY') answer = noParcels(ctx);
  else if (topic === 'WHY') answer = why(ctx);
  else if (PARCEL_TOPICS.includes(topic) && (ctx.farmScope || !parcel)) answer = farmWide(topic, ctx);
  else answer = route(topic, ctx, parcel);

  return {
    answer,
    memory: {
      parcelId: answer.parcelId ?? (ctx.farmScope ? null : parcel?.id ?? memory.parcelId),
      topic: answer.topic,
      crop: answer.crop ?? crop ?? memory.crop,
      last: answer.topic === 'WHY' ? memory.last : answer,
    },
  };
}

function route(topic: CopilotTopic, ctx: Context, parcel: CopilotParcel | null): CopilotAnswer {
  const p = parcel!;
  switch (topic) {
    case 'NEXT_CROP':
      return nextCrop(ctx, p);
    case 'CROP_SUITABILITY':
      return ctx.crop ? suitability(ctx, p, ctx.crop) : nextCrop(ctx, p);
    case 'FERTILIZE':
      return fertilize(ctx, p);
    case 'HARVEST':
      return harvest(ctx, p);
    case 'IRRIGATE':
      return irrigate(ctx, p);
    case 'SPRAY':
      return spray(ctx, p);
    case 'YIELD':
      return yieldAnswer(ctx, p);
    case 'TILLAGE':
      return tillageAnswer(ctx, p, findTillage(ctx.text) ?? 'NO_TILL');
    case 'PROFITABILITY':
      return ctx.snapshot.canSeeFinance ? profitability(ctx, p, farmAverageProfit(ctx)) : financeHidden(ctx);
    case 'PENDING':
      return pending(ctx);
    case 'OVERDUE':
      return overdue(ctx);
    case 'WEEK_SCHEDULE':
      return weekSchedule(ctx);
    case 'HECTARES_LEFT':
      return hectaresLeft(ctx);
    case 'IDLE_MACHINES':
      return idleMachines(ctx);
    case 'MACHINERY':
      return machinery(ctx);
    case 'ROI_RANKING':
      return ctx.snapshot.canSeeFinance ? roiRanking(ctx) : financeHidden(ctx);
    case 'FARM_PROFIT':
      return ctx.snapshot.canSeeFinance ? farmProfit(ctx) : financeHidden(ctx);
    case 'REDUCE_COSTS':
      return ctx.snapshot.canSeeFinance ? reduceCosts(ctx) : financeHidden(ctx);
    case 'PRIORITIZE':
      return prioritize(ctx);
    case 'RISKS':
      return risks(ctx);
    case 'SATELLITE':
      return satelliteAnswer(ctx, p);
    case 'RESCHEDULE':
    case 'CANCEL':
    case 'REPRIORITIZE':
    case 'GROUP':
      return changeTasks(topic, ctx);
    case 'CREATE_TASK':
      return createTask(ctx);
    default:
      return general(ctx);
  }
}

// ------------------------------------------------------------------ helpers

const facts = (ctx: Context) => (ctx.farmScope || !ctx.parcel ? ctx.snapshot.parcels : [ctx.parcel]).map((parcel) => parcelFacts(ctx.snapshot, parcel));
const parcelName = (ctx: Context, id: string | null) => ctx.snapshot.parcels.find((parcel) => parcel.id === id)?.name ?? 'farm-wide';
const machineName = (ctx: Context, id: string | null) => ctx.snapshot.machines.find((machine) => machine.id === id)?.name ?? null;
const memberName = (ctx: Context, id: string | null) => ctx.snapshot.members.find((member) => member.id === id)?.name ?? null;
const scopeLabel = (ctx: Context) => (ctx.farmScope || !ctx.parcel ? 'the farm' : ctx.parcel.name);

function farmAverageProfit(ctx: Context): number | null {
  const all = ctx.snapshot.parcels.map((parcel) => ({ parcel, profit: parcelProfitPerHa(parcelFacts(ctx.snapshot, parcel), ctx) })).filter((item) => item.profit);
  const area = all.reduce((sum, item) => sum + item.parcel.areaHa, 0);
  return all.length >= 2 && area > 0 ? all.reduce((sum, item) => sum + item.profit!.value * item.parcel.areaHa, 0) / area : null;
}

function noParcels(ctx: Context): CopilotAnswer {
  const answer = blank('GENERAL', ctx, null);
  answer.direct = 'The farm has no parcels yet. Draw or import your fields on the map and I will analyse crops, weather, work and economics for each of them.';
  answer.nextSteps = ['Open the map and tap “New parcel”, or import a KML / Shapefile / LPIS file.'];
  answer.confidence = 'LOW';
  return answer;
}

function financeHidden(ctx: Context): CopilotAnswer {
  const answer = blank('PROFITABILITY', ctx);
  answer.direct = 'Costs and profit are visible only to managers and agronomists on this farm.';
  answer.confidence = 'HIGH';
  return answer;
}

function why(ctx: Context): CopilotAnswer {
  const last = ctx.memory.last;
  const answer = blank('WHY', ctx, last?.parcelId ?? null);
  if (!last) {
    answer.direct = 'Ask me something first, for example “What should I plant on this field next year?”, and then I can explain the reasoning.';
    answer.confidence = 'LOW';
    return answer;
  }
  answer.crop = last.crop;
  answer.direct = `Here is the reasoning behind: “${last.direct}”`;
  answer.reasoning = [...last.reasoning, ...last.dataGaps.map((gap) => `Limitation: ${gap}`)];
  answer.risks = last.risks;
  answer.alternatives = last.alternatives;
  answer.economics = last.economics;
  answer.nextSteps = last.nextSteps;
  answer.confidence = last.confidence;
  return answer;
}

// ------------------------------------------------------------------ several parcels

function farmWide(topic: CopilotTopic, ctx: Context): CopilotAnswer {
  const all = ctx.snapshot.parcels;
  const answer = blank(topic, ctx, null);
  const per = (build: (parcel: CopilotParcel) => CopilotAnswer) => all.map((parcel) => ({ parcel, result: build(parcel) }));
  switch (topic) {
    case 'NEXT_CROP': {
      const results = per((parcel) => nextCrop(ctx, parcel));
      answer.direct = `Best crop for next season on each parcel: ${results.map(({ parcel, result }) => `${parcel.name} → ${result.crop ? cropName(result.crop) : '?'}`).join('; ')}.`;
      answer.reasoning = results.map(({ parcel, result }) => `${parcel.name}: ${result.reasoning[0] ?? result.direct}`);
      answer.nextSteps = ['Ask about one parcel (for example “What should I plant on ' + all[0]!.name + '?”) for yields, profit and tasks.'];
      break;
    }
    case 'CROP_SUITABILITY': {
      const crop = ctx.crop ?? 'CORN';
      answer.crop = crop;
      const ranked = per((parcel) => suitability(ctx, parcel, crop))
        .map((item) => ({ ...item, score: Number(/score (\d+)/.exec(item.result.direct)?.[1] ?? 0) }))
        .sort((a, b) => b.score - a.score);
      answer.direct = `Best parcel for ${cropName(crop)}: ${ranked[0]!.parcel.name} (score ${ranked[0]!.score}/100).`;
      answer.reasoning = ranked.map(({ parcel, score, result }) => `${parcel.name}: ${score}/100 – ${result.reasoning[0] ?? ''}`);
      answer.alternatives = ranked.slice(1, 3).map(({ parcel, score }) => `${parcel.name} (${score}/100)`);
      break;
    }
    case 'FERTILIZE': {
      const horizon = mentions(ctx.text, ['this week', 'saptamana']) ? 7 : 14;
      const due = facts(ctx)
        .map((item) => ({ item, status: nitrogenStatus(item) }))
        .filter(({ item, status }) => (status?.due && !status.done && daysBetween(ctx.today, status.due) <= horizon) || item.open.some((task) => taskCategory(task.type) === 'FERTILIZATION' && daysBetween(ctx.today, task.dueDate) <= horizon));
      if (!due.length) {
        answer.direct = `No parcel needs fertilization in the next ${horizon} days.`;
      } else {
        answer.direct = `${due.length} parcel${due.length > 1 ? 's need' : ' needs'} fertilization in the next ${horizon} days: ${list(due.map(({ item }) => item.parcel.name))}.`;
        for (const { item } of due) {
          const result = fertilize(ctx, item.parcel);
          answer.reasoning.push(`${item.parcel.name}: ${result.direct}`);
          answer.proposals.push(...result.proposals.filter((proposal) => !item.open.some((task) => task.type === proposal.type)));
        }
      }
      break;
    }
    case 'HARVEST': {
      const ready = facts(ctx)
        .map((item) => ({ item, date: expectedHarvest(item) }))
        .filter((entry): entry is { item: ParcelFacts; date: string } => entry.date != null && !entry.item.season?.harvestDate)
        .sort((a, b) => {
          const shatter = (entry: typeof a) => (entry.item.crop === 'RAPESEED' || entry.item.crop === 'SUNFLOWER' ? -3 : 0);
          return daysBetween(ctx.today, a.date) + shatter(a) - (daysBetween(ctx.today, b.date) + shatter(b));
        });
      if (!ready.length) {
        answer.direct = 'No parcel has a crop waiting for harvest.';
      } else {
        const first = ready[0]!;
        answer.direct = `Harvest ${first.item.parcel.name} first: ${cropName(first.item.crop!)} is expected ready ${relativeDays(first.date, ctx.today)}.`;
        answer.reasoning = ready.map(({ item, date }) => `${item.parcel.name}: ${cropName(item.crop!)}, ${item.progress?.percent ?? '?'}% of season, ready ${dayLabel(date < ctx.today ? ctx.today : date, ctx.today)}.`);
        answer.reasoning.push('Shatter-prone crops (rapeseed, sunflower) are moved up the order.');
        const plan = harvest({ ...ctx, parcel: first.item.parcel }, first.item.parcel);
        answer.proposals = plan.proposals;
        answer.risks = plan.risks;
        answer.parcelId = first.item.parcel.id;
      }
      break;
    }
    case 'IRRIGATE': {
      const irrigated = all.filter((parcel) => (parcel.irrigatedHa ?? 0) > 0);
      if (!irrigated.length) {
        answer.direct = 'No parcel has irrigation configured.';
        break;
      }
      const results = irrigated.map((parcel) => ({ parcel, result: irrigate(ctx, parcel) }));
      const needs = results.filter(({ result }) => result.proposals.some((proposal) => proposal.type === 'IRRIGATION_START'));
      answer.direct = needs.length ? `Irrigate ${list(needs.map(({ parcel }) => parcel.name))} this week.` : 'None of the irrigated parcels needs water this week.';
      answer.reasoning = results.map(({ parcel, result }) => `${parcel.name}: ${result.direct}`);
      answer.proposals = needs.flatMap(({ result }) => result.proposals);
      break;
    }
    case 'SPRAY': {
      const results = all.map((parcel) => ({ parcel, result: spray(ctx, parcel) }));
      answer.direct = results[0]!.result.direct.replace(` ${all[0]!.name}`, ' your parcels');
      answer.reasoning = results[0]!.result.reasoning;
      answer.risks = results[0]!.result.risks;
      answer.proposals = results.flatMap(({ result }) => result.proposals);
      answer.changes = results.flatMap(({ result }) => result.changes);
      if (answer.proposals.length) answer.nextSteps = [`Treatments due by crop stage: ${list(answer.proposals.map((proposal) => proposal.title))}.`];
      break;
    }
    case 'YIELD': {
      const rows = facts(ctx).filter((item) => item.crop).map((item) => ({ item, estimate: estimateYield(item, item.crop!, ctx) }));
      if (!rows.length) {
        answer.direct = 'No parcel has a crop recorded this season.';
        break;
      }
      answer.direct = `Expected production this season: ${rows.map(({ item, estimate }) => `${item.parcel.name} ${t(estimate.expected * item.parcel.areaHa)} t of ${cropName(item.crop!)}`).join('; ')}.`;
      answer.reasoning = rows.map(({ item, estimate }) => `${item.parcel.name}: ${t(estimate.low)}–${t(estimate.high)} t/ha. ${estimate.factors[0] ?? ''}`);
      break;
    }
    case 'TILLAGE': {
      const target: TillageSystem = findTillage(ctx.text) ?? 'NO_TILL';
      const ranked = facts(ctx).map((item) => ({ item, fit: tillageFit(item, target) })).sort((a, b) => b.fit.score - a.fit.score);
      answer.direct = `Best parcel for ${TILLAGE_SYSTEMS[target].name}: ${ranked[0]!.item.parcel.name} (suitability ${ranked[0]!.fit.score}/100).`;
      answer.reasoning = ranked.map(({ item, fit }) => `${item.parcel.name}: ${fit.score}/100${fit.pros[0] ? ` – ${fit.pros[0]}` : ''}${fit.cons[0] ? `; but ${fit.cons[0]}` : ''}.`);
      break;
    }
    case 'SATELLITE':
      return satelliteFarm(ctx);
    case 'PROFITABILITY':
      return ctx.snapshot.canSeeFinance ? roiRanking(ctx) : financeHidden(ctx);
    default:
      return general(ctx);
  }
  answer.confidence = 'MEDIUM';
  return answer;
}

// ------------------------------------------------------------------ operations

function taskLine(ctx: Context, task: CopilotTask) {
  const who = [memberName(ctx, task.assigneeId), machineName(ctx, task.machineId)].filter(Boolean).join(', ');
  return `${task.title}${task.parcelId && !task.title.includes(parcelName(ctx, task.parcelId)) ? ` (${parcelName(ctx, task.parcelId)})` : ''} – ${dayLabel(task.dueDate, ctx.today)}${who ? `, ${who}` : ''}`;
}

/** The first day, from `from`, suitable for the kind of work. */
function suitableDay(ctx: Context, type: string, from: string): string {
  const weather = ctx.weather;
  if (!weather) return from;
  if (taskIsSpray(type)) {
    const window = weather.sprayWindows.filter((item) => item.start.slice(0, 10) >= from).sort((a, b) => a.start.localeCompare(b.start))[0];
    if (window) return window.start.slice(0, 10);
  }
  const days = ctx.snapshot.weather?.days ?? [];
  return days.find((day) => day.date >= from && day.precipitationMm < 2 && (day.windMaxKmh ?? 0) < 30)?.date ?? from;
}

function pending(ctx: Context): CopilotAnswer {
  const answer = blank('PENDING', ctx);
  const open = facts(ctx).flatMap((item) => item.open);
  const unassigned = ctx.farmScope ? ctx.snapshot.tasks.filter((task) => !task.parcelId && task.status !== 'DONE') : [];
  const all = [...open, ...unassigned].sort((a, b) => a.dueDate.localeCompare(b.dueDate));
  if (!all.length) {
    answer.direct = `No pending operations on ${scopeLabel(ctx)}.`;
    answer.nextSteps = ['Ask what to do next, for example “When should I fertilize?”.'];
    answer.confidence = 'HIGH';
    return answer;
  }
  const late = all.filter((task) => task.dueDate.slice(0, 10) < ctx.today);
  const running = facts(ctx).flatMap((item) => item.activeRuns);
  answer.direct = `${all.length} operation${all.length > 1 ? 's are' : ' is'} pending on ${scopeLabel(ctx)}${late.length ? `, ${late.length} of them overdue` : ''}${running.length ? `; ${running.length} running now` : ''}.`;
  answer.reasoning = all.slice(0, 12).map((task) => `${task.dueDate.slice(0, 10) < ctx.today ? '⚠ ' : ''}${taskLine(ctx, task)}`);
  if (all.length > 12) answer.reasoning.push(`…and ${all.length - 12} more in the To-Do list.`);
  answer.changes = rescheduleOverdue(ctx, late);
  if (answer.changes.length) answer.nextSteps.push('Reschedule the overdue work to the next suitable days (below).');
  answer.confidence = 'HIGH';
  return answer;
}

function rescheduleOverdue(ctx: Context, late: readonly CopilotTask[]): TaskChange[] {
  return late.slice(0, 8).map((task) => {
    const to = suitableDay(ctx, task.type, ctx.today);
    return { key: `c-${task.id}`, kind: 'RESCHEDULE', taskId: task.id, title: task.title, parcelId: task.parcelId, fromDate: task.dueDate.slice(0, 10), toDate: to, fromPriority: task.priority, reason: `Overdue by ${daysBetween(task.dueDate, ctx.today)} days; ${to === ctx.today ? 'today' : dayLabel(to, ctx.today)} is the next suitable day.` };
  });
}

function overdue(ctx: Context): CopilotAnswer {
  const answer = blank('OVERDUE', ctx);
  const rows = facts(ctx).filter((item) => item.overdue.length);
  if (!rows.length) {
    answer.direct = `No overdue operations on ${scopeLabel(ctx)}.`;
    answer.confidence = 'HIGH';
    return answer;
  }
  const total = rows.reduce((sum, item) => sum + item.overdue.length, 0);
  answer.direct = ctx.farmScope
    ? `${rows.length} field${rows.length > 1 ? 's have' : ' has'} overdue operations (${total} task${total > 1 ? 's' : ''}): ${list(rows.map((item) => item.parcel.name))}.`
    : `${total} overdue operation${total > 1 ? 's' : ''} on ${ctx.parcel!.name}.`;
  answer.reasoning = rows.flatMap((item) => item.overdue.map((task) => `${taskLine(ctx, task)} (${daysBetween(task.dueDate, ctx.today)} days late)`));
  answer.risks = rows.some((item) => item.overdue.some((task) => taskCategory(task.type) === 'CROP_PROTECTION')) ? ['Late crop protection lets weeds and disease get ahead; efficacy drops as they grow.'] : [];
  answer.changes = rescheduleOverdue(ctx, rows.flatMap((item) => item.overdue));
  answer.nextSteps = ['Apply the proposed new dates, or ask “Prioritize harvesting before cultivation” to reorder.'];
  answer.confidence = 'HIGH';
  return answer;
}

function weekSchedule(ctx: Context): CopilotAnswer {
  const answer = blank('WEEK_SCHEDULE', ctx);
  const end = addDays(ctx.today, 6);
  const tasks = ctx.snapshot.tasks
    .filter((task) => task.status !== 'DONE' && task.dueDate.slice(0, 10) >= ctx.today && task.dueDate.slice(0, 10) <= end && (ctx.farmScope || task.parcelId === ctx.parcel?.id))
    .sort((a, b) => a.dueDate.localeCompare(b.dueDate));
  const running = ctx.snapshot.runs.filter((run) => (run.status === 'RUNNING' || run.status === 'PAUSED') && (ctx.farmScope || run.parcelId === ctx.parcel?.id));
  answer.direct = tasks.length
    ? `${tasks.length} operation${tasks.length > 1 ? 's are' : ' is'} scheduled on ${scopeLabel(ctx)} this week${running.length ? `, and ${running.length} running now` : ''}.`
    : `Nothing is scheduled on ${scopeLabel(ctx)} this week${running.length ? `, but ${running.length} operation${running.length > 1 ? 's are' : ' is'} running` : ''}.`;
  const days = ctx.snapshot.weather?.days ?? [];
  answer.reasoning = tasks.map((task) => {
    const day = days.find((item) => item.date === task.dueDate.slice(0, 10));
    const caution = day && (day.precipitationMm >= 2 || (day.windMaxKmh ?? 0) > 25) ? ` ⚠ ${day.precipitationMm >= 2 ? `${Math.round(day.precipitationMm)} mm rain` : `wind ${Math.round(day.windMaxKmh!)} km/h`}` : '';
    return `${taskLine(ctx, task)}${caution}`;
  });
  answer.changes = tasks
    .filter((task) => {
      const day = days.find((item) => item.date === task.dueDate.slice(0, 10));
      return (taskIsSpray(task.type) || taskCategory(task.type) === 'FERTILIZATION' || taskCategory(task.type) === 'HARVEST') && day && (day.precipitationMm >= 2 || (day.windMaxKmh ?? 0) > 25);
    })
    .map((task) => {
      const to = suitableDay(ctx, task.type, addDays(task.dueDate, 1));
      return { key: `c-${task.id}`, kind: 'RESCHEDULE' as const, taskId: task.id, title: task.title, parcelId: task.parcelId, fromDate: task.dueDate.slice(0, 10), toDate: to, fromPriority: task.priority, reason: `Rain or wind on ${dayLabel(task.dueDate, ctx.today)}; ${dayLabel(to, ctx.today)} looks suitable.` };
    })
    .filter((change) => change.toDate !== change.fromDate);
  if (answer.changes.length) answer.risks.push('Some operations fall on rainy or windy days (see the proposed moves).');
  answer.confidence = 'HIGH';
  return answer;
}

function categoriesIn(text: string): TaskCategory[] {
  return (Object.keys(CATEGORY_WORDS) as TaskCategory[])
    .map((category) => {
      const positions = CATEGORY_WORDS[category].map((word) => text.search(new RegExp(`(^|[^a-z])${word}`))).filter((index) => index >= 0);
      return { category, index: positions.length ? Math.min(...positions) : -1 };
    })
    .filter((item) => item.index >= 0)
    .sort((a, b) => a.index - b.index)
    .map((item) => item.category);
}

function hectaresLeft(ctx: Context): CopilotAnswer {
  const category = categoriesIn(ctx.text)[0] ?? 'CROP_PROTECTION';
  const answer = blank('HECTARES_LEFT', ctx);
  const open = ctx.snapshot.tasks.filter((task) => task.status !== 'DONE' && taskCategory(task.type) === category && task.parcelId && (ctx.farmScope || task.parcelId === ctx.parcel?.id));
  const parcels = [...new Set(open.map((task) => task.parcelId!))].map((id) => ctx.snapshot.parcels.find((parcel) => parcel.id === id)).filter((parcel): parcel is CopilotParcel => Boolean(parcel));
  const workType: Partial<Record<TaskCategory, string>> = { CROP_PROTECTION: 'SPRAYING', FERTILIZATION: 'FERTILIZING', HARVEST: 'HARVESTING', PLANTING: 'SOWING' };
  const runningArea = ctx.snapshot.runs.filter((run) => run.type === workType[category] && run.status === 'RUNNING').reduce((sum, run) => sum + run.areaHa, 0);
  const total = parcels.reduce((sum, parcel) => sum + parcel.areaHa, 0);
  const label = CATEGORY_LABELS[category].toLowerCase();
  answer.direct = total > 0 ? `${t(total)} ha still need ${label} on ${parcels.length} parcel${parcels.length > 1 ? 's' : ''}: ${list(parcels.map((parcel) => `${parcel.name} (${t(parcel.areaHa)} ha)`))}.` : `No hectares are waiting for ${label}: there are no open ${label} tasks.`;
  if (runningArea > 0) answer.reasoning.push(`${t(runningArea)} ha are being worked right now.`);
  answer.reasoning.push(`Counted from open ${label} tasks in the To-Do list.`);
  if (category === 'CROP_PROTECTION' && ctx.weather?.sprayWindows[0]) answer.nextSteps = [`Next good spraying window: ${ctx.weather.sprayWindows[0].label}.`];
  answer.confidence = 'HIGH';
  return answer;
}

function machineBusy(ctx: Context, machineId: string, days: number) {
  const running = ctx.snapshot.runs.some((run) => run.machineId === machineId && (run.status === 'RUNNING' || run.status === 'PAUSED'));
  const planned = ctx.snapshot.tasks.filter((task) => task.machineId === machineId && task.status !== 'DONE' && daysBetween(ctx.today, task.dueDate) >= 0 && daysBetween(ctx.today, task.dueDate) < days);
  return { running, planned };
}

function idleMachines(ctx: Context): CopilotAnswer {
  const answer = blank('IDLE_MACHINES', ctx, null);
  if (!ctx.snapshot.machines.length) {
    answer.direct = 'There are no machines in the fleet yet. Add them in the Machines tab.';
    return answer;
  }
  const rows = ctx.snapshot.machines.map((machine) => ({ machine, ...machineBusy(ctx, machine.id, 3) }));
  const idle = rows.filter((row) => !row.running && !row.planned.length);
  const wantsTractors = mentions(ctx.text, ['tractor', 'tractoare']);
  const shown = wantsTractors ? idle.filter((row) => row.machine.type === 'TRACTOR') : idle;
  answer.direct = shown.length ? `${shown.length} ${wantsTractors ? 'tractor' : 'machine'}${shown.length > 1 ? 's are' : ' is'} idle now and in the next 2 days: ${list(shown.map((row) => row.machine.name))}.` : `No ${wantsTractors ? 'tractor' : 'machine'} is idle: everything is running or planned.`;
  answer.reasoning = rows.map((row) => `${row.machine.name}: ${row.running ? 'working now' : row.planned.length ? `planned – ${row.planned.map((task) => task.title).join(', ')}` : 'idle'}`);
  const unassigned = ctx.snapshot.tasks.filter((task) => task.status !== 'DONE' && !task.machineId && (COPILOT_TASK_TYPES[task.type]?.machines.length ?? 0) > 0 && daysBetween(ctx.today, task.dueDate) <= 7);
  if (unassigned.length && idle.length) answer.nextSteps = [`${unassigned.length} task${unassigned.length > 1 ? 's' : ''} this week ${unassigned.length > 1 ? 'have' : 'has'} no machine: ask “What machinery should I assign?”.`];
  answer.confidence = 'HIGH';
  return answer;
}

function machinery(ctx: Context): CopilotAnswer {
  const answer = blank('MACHINERY', ctx);
  const scope = ctx.snapshot.tasks.filter((task) => task.status !== 'DONE' && (COPILOT_TASK_TYPES[task.type]?.machines.length ?? 0) > 0 && (ctx.farmScope || task.parcelId === ctx.parcel?.id) && daysBetween(ctx.today, task.dueDate) <= 14);
  if (!ctx.snapshot.machines.length) {
    answer.direct = 'There are no machines in the fleet yet. Add them in the Machines tab and I will assign them to tasks.';
    return answer;
  }
  if (!scope.length) {
    answer.direct = `No upcoming field work on ${scopeLabel(ctx)} needs a machine in the next 2 weeks.`;
    answer.reasoning = ctx.snapshot.machines.map((machine) => `${machine.name} (${machine.type.toLowerCase()}${machine.powerHp ? `, ${machine.powerHp} hp` : ''})`);
    return answer;
  }
  const lines: string[] = [];
  const shortages = new Set<string>();
  for (const task of scope) {
    const suggested = task.machineId ?? suggestFor(ctx, task);
    const operator = task.assigneeId ?? null;
    if (!suggested) shortages.add(COPILOT_TASK_TYPES[task.type]!.machines.join(' or ').toLowerCase());
    lines.push(`${taskLine(ctx, task)} → ${suggested ? machineName(ctx, suggested) : 'no suitable machine free'}${operator ? '' : ', no operator yet'}`);
  }
  answer.direct = `Machine assignments for the next 2 weeks on ${scopeLabel(ctx)}: ${scope.filter((task) => !task.machineId).length} task${scope.length > 1 ? 's' : ''} still need a machine.`;
  answer.reasoning = lines;
  if (shortages.size) answer.risks.push(`Machinery shortage: no free ${list([...shortages])} on some days. Consider a contractor or moving the work.`);
  answer.nextSteps = ['Open each task in the To-Do list to set the machine and operator.'];
  answer.confidence = 'MEDIUM';
  return answer;
}

function suggestFor(ctx: Context, task: CopilotTask) {
  const proposal = propose(ctx.snapshot, { type: task.type, parcelId: task.parcelId, dueDate: task.dueDate, priority: task.priority, reason: '', withFollowUps: false });
  return proposal.machineId;
}

function roiRanking(ctx: Context): CopilotAnswer {
  const answer = blank('ROI_RANKING', ctx, null);
  const rows = ctx.snapshot.parcels
    .map((parcel) => ({ parcel, profit: parcelProfitPerHa(parcelFacts(ctx.snapshot, parcel), ctx) }))
    .filter((row): row is { parcel: CopilotParcel; profit: NonNullable<ReturnType<typeof parcelProfitPerHa>> } => row.profit != null)
    .map((row) => ({ ...row, roi: row.profit.costPerHa > 0 ? (row.profit.value / row.profit.costPerHa) * 100 : 0 }));
  if (!rows.length) {
    answer.direct = 'No parcel has enough crop and cost data to rank profitability. Record crops and expected yields and prices first.';
    answer.confidence = 'LOW';
    return answer;
  }
  const byRoi = [...rows].sort((a, b) => b.roi - a.roi);
  const byProfit = [...rows].sort((a, b) => b.profit.value - a.profit.value);
  const asksLeast = mentions(ctx.text, ['least', 'lowest', 'worst', 'cea mai putin', 'cel mai putin']);
  const pick = asksLeast ? byRoi[byRoi.length - 1]! : mentions(ctx.text, ['roi', 'return']) ? byRoi[0]! : byProfit[0]!;
  answer.parcelId = pick.parcel.id;
  answer.direct = `${asksLeast ? 'Least' : 'Most'} profitable: ${pick.parcel.name} – ${euroHa(pick.profit.value)} profit, ROI ${signedPct(pick.roi)}${pick.profit.basis === 'estimate' ? ' (estimate)' : ''}.`;
  answer.reasoning = byRoi.map((row) => `${row.parcel.name}: ${euroHa(row.profit.value)}, ROI ${signedPct(row.roi)} (${row.profit.basis})`);
  const estimates = rows.filter((row) => row.profit.basis === 'estimate').length;
  if (estimates) answer.dataGaps.push(`${estimates} parcel${estimates > 1 ? 's use' : ' uses'} estimated figures: record costs and prices for exact results.`);
  answer.economics = [`Total expected profit: ${euro(rows.reduce((sum, row) => sum + row.profit.value * row.parcel.areaHa, 0))}.`];
  answer.confidence = estimates ? 'MEDIUM' : 'HIGH';
  return answer;
}

function farmProfit(ctx: Context): CopilotAnswer {
  const answer = blank('FARM_PROFIT', ctx, null);
  const rows = ctx.snapshot.parcels.map((parcel) => ({ parcel, profit: parcelProfitPerHa(parcelFacts(ctx.snapshot, parcel), ctx) }));
  const known = rows.filter((row) => row.profit);
  const total = known.reduce((sum, row) => sum + row.profit!.value * row.parcel.areaHa, 0);
  const area = known.reduce((sum, row) => sum + row.parcel.areaHa, 0);
  const cost = known.reduce((sum, row) => sum + row.profit!.costPerHa * row.parcel.areaHa, 0);
  if (!known.length) {
    answer.direct = 'There is not enough crop and cost data to estimate the farm profit yet.';
    answer.confidence = 'LOW';
    return answer;
  }
  answer.direct = `Expected farm profit this season: about ${euro(total)} on ${t(area)} ha (${euroHa(area ? total / area : 0)}), for a production cost of ${euro(cost)}.`;
  answer.reasoning = known.sort((a, b) => b.profit!.value - a.profit!.value).map((row) => `${row.parcel.name}: ${euro(row.profit!.value * row.parcel.areaHa)} (${euroHa(row.profit!.value)}, ${row.profit!.basis})`);
  const missing = rows.length - known.length;
  if (missing) answer.dataGaps.push(`${missing} parcel${missing > 1 ? 's have' : ' has'} no crop or cost data and ${missing > 1 ? 'are' : 'is'} not included.`);
  answer.economics = [`Return on cost: ${signedPct(cost > 0 ? (total / cost) * 100 : 0)}.`];
  answer.confidence = known.some((row) => row.profit!.basis === 'estimate') ? 'MEDIUM' : 'HIGH';
  return answer;
}

function reduceCosts(ctx: Context): CopilotAnswer {
  const answer = blank('REDUCE_COSTS', ctx);
  const rows = facts(ctx);
  const shares = new Map<string, number>();
  let costed = 0;
  for (const item of rows) {
    const economics = item.parcel.economics;
    if (!economics) continue;
    costed++;
    for (const [name, share] of Object.entries(economics.costShares)) shares.set(name, (shares.get(name) ?? 0) + share * economics.totalCost / 100);
  }
  const top = [...shares.entries()].sort((a, b) => b[1] - a[1]);
  const tillageSavings = rows
    .filter((item) => item.parcel.tillage === 'CONVENTIONAL')
    .map((item) => ({ item, saving: (TILLAGE_SYSTEMS.CONVENTIONAL.costPerHa - TILLAGE_SYSTEMS.MINIMUM_TILL.costPerHa) * item.parcel.areaHa, fit: tillageFit(item, 'MINIMUM_TILL') }))
    .filter((row) => row.fit.score >= 55);
  const levers: string[] = [];
  if (tillageSavings.length) levers.push(`Move ${list(tillageSavings.map((row) => row.item.parcel.name))} from conventional to Minimum Till: about ${euro(tillageSavings.reduce((sum, row) => sum + row.saving, 0))} and ${Math.round(tillageSavings.reduce((sum, row) => sum + (TILLAGE_SYSTEMS.CONVENTIONAL.fuelLPerHa - TILLAGE_SYSTEMS.MINIMUM_TILL.fuelLPerHa) * row.item.parcel.areaHa, 0))} l of diesel per season.`);
  const noSoil = rows.filter((item) => !item.parcel.soil);
  if (noSoil.length) levers.push(`Fertilize to a soil analysis: on ${list(noSoil.map((item) => item.parcel.name))} it typically saves 10–20% of fertilizer cost.`);
  if (top[0]) levers.unshift(`${top[0][0]} is your biggest cost (${euro(top[0][1])}${top[1] ? `, then ${top[1][0]} ${euro(top[1][1])}` : ''}): start there.`);
  const fuel = top.find(([name]) => /fuel/i.test(name));
  if (fuel) levers.push('Cut fuel by combining passes (for example spreading with the herbicide pass) and checking tyre pressure.');
  const idle = ctx.snapshot.machines.filter((machine) => !ctx.snapshot.runs.some((run) => run.machineId === machine.id) && !ctx.snapshot.tasks.some((task) => task.machineId === machine.id));
  if (idle.length) levers.push(`${list(idle.map((machine) => machine.name))} ${idle.length > 1 ? 'have' : 'has'} no work or tasks recorded: if that is accurate, renting ${idle.length > 1 ? 'them' : 'it'} out would cover fixed costs.`);
  answer.direct = levers.length ? `The biggest savings on ${scopeLabel(ctx)}: ${levers[0]}` : `Record costs for ${scopeLabel(ctx)} in the economics tab and I will point out where the money goes.`;
  answer.reasoning = levers.slice(1);
  answer.risks = tillageSavings.length ? ['Reduced tillage can lower yields slightly in the first transition years.'] : [];
  answer.economics = costed ? [`Based on recorded costs of ${costed} parcel${costed > 1 ? 's' : ''}.`] : [];
  answer.proposals = noSoil.slice(0, 3).map((item) => propose(ctx.snapshot, { type: 'SOIL_SAMPLING', parcelId: item.parcel.id, dueDate: addDays(ctx.today, 7), priority: 'LOW', reason: 'A soil analysis lets fertilizer be applied only where it is needed.', windowDays: 21 }));
  if (!costed) answer.dataGaps.push('No costs are recorded this season.');
  answer.confidence = costed ? 'MEDIUM' : 'LOW';
  return answer;
}

function prioritize(ctx: Context): CopilotAnswer {
  const answer = blank('PRIORITIZE', ctx, null);
  const weather = ctx.weather;
  const rows = facts(ctx).map((item) => {
    const reasons: string[] = [];
    let score = 0;
    if (item.overdue.length) { score += 30 * item.overdue.length; reasons.push(`${item.overdue.length} overdue`); }
    const soon = item.upcoming.filter((task) => daysBetween(ctx.today, task.dueDate) <= 3);
    if (soon.length) { score += 15 * soon.length; reasons.push(`${soon.length} due within 3 days`); }
    const harvestDay = expectedHarvest(item);
    if (harvestDay && !item.season?.harvestDate && daysBetween(ctx.today, harvestDay) <= 7) { score += 25; reasons.push('crop ready to harvest'); }
    const nitrogen = nitrogenStatus(item);
    if (nitrogen?.due && !nitrogen.done && daysBetween(ctx.today, nitrogen.due) <= 3) { score += 15; reasons.push('nitrogen window open'); }
    if (weather && weather.rain48h >= 10 && item.upcoming.some((task) => taskIsSpray(task.type))) { score += 10; reasons.push('spraying must happen before the rain'); }
    return { item, score, reasons };
  }).filter((row) => row.score > 0).sort((a, b) => b.score - a.score);
  if (!rows.length) {
    answer.direct = 'Nothing is urgent right now: no overdue work, no crops ready and no operations due in the next 3 days.';
    answer.confidence = 'HIGH';
    return answer;
  }
  answer.parcelId = rows[0]!.item.parcel.id;
  answer.direct = `Prioritize ${rows[0]!.item.parcel.name}: ${list(rows[0]!.reasons)}.`;
  answer.reasoning = rows.map((row, index) => `${index + 1}. ${row.item.parcel.name} – ${list(row.reasons)}`);
  answer.changes = rescheduleOverdue(ctx, rows[0]!.item.overdue);
  answer.confidence = 'HIGH';
  return answer;
}

function risks(ctx: Context): CopilotAnswer {
  const answer = blank('RISKS', ctx);
  const insights = farmInsights(ctx.snapshot).filter((insight) => ctx.farmScope || insight.parcelId === ctx.parcel?.id || insight.parcelId === null);
  const items = facts(ctx);
  const agronomic: string[] = [];
  for (const item of items) {
    if (item.crop && item.season) {
      const humid = (ctx.snapshot.weather?.hours ?? []).slice(0, 72).filter((hour) => hour.humidityPct >= 85 && hour.temperatureC >= 15 && hour.temperatureC <= 25).length;
      if (humid >= 18 && item.progress?.status === 'GROWING') agronomic.push(`${item.parcel.name}: disease risk – ${humid} warm, humid hours in the next 3 days favour fungal infection of ${cropName(item.crop)}.`);
      const nitrogen = nitrogenStatus(item);
      if (nitrogen?.due && !nitrogen.done && nitrogen.due < ctx.today) agronomic.push(`${item.parcel.name}: nitrogen deficiency risk – the top-dressing is ${daysBetween(nitrogen.due, ctx.today)} days late.`);
    }
    if (item.parcel.soil?.ph != null && item.parcel.soil.ph < 5.8) agronomic.push(`${item.parcel.name}: acidic soil (pH ${item.parcel.soil.ph}) limits most crops.`);
  }
  answer.risks = [...insights.filter((insight) => insight.severity !== 'INFO').map((insight) => insight.message), ...agronomic];
  answer.reasoning = insights.filter((insight) => insight.severity === 'INFO').map((insight) => insight.message);
  answer.direct = answer.risks.length ? `${answer.risks.length} risk${answer.risks.length > 1 ? 's' : ''} on ${scopeLabel(ctx)}. The most important: ${answer.risks[0]}` : `No significant risks on ${scopeLabel(ctx)} right now.`;
  answer.proposals = insights.map((insight) => insight.proposal).filter((proposal): proposal is NonNullable<typeof proposal> => proposal != null).slice(0, 4);
  answer.nextSteps = insights.map((insight) => insight.ask).filter((ask): ask is string => Boolean(ask)).slice(0, 3);
  answer.confidence = ctx.weather ? 'HIGH' : 'MEDIUM';
  return answer;
}

// ------------------------------------------------------------------ task changes

function targetTasks(ctx: Context, categories: readonly TaskCategory[]): CopilotTask[] {
  const scoped = ctx.snapshot.tasks.filter((task) => task.status !== 'DONE' && (ctx.farmScope || !ctx.parcel || task.parcelId === ctx.parcel.id));
  const specific = ['herbicide', 'fungicide', 'insecticide', 'nitrogen', 'foliar', 'plow', 'disk', 'subsoil', 'chisel', 'sowing', 'transport', 'scouting'].find((word) => ctx.text.includes(word));
  return scoped
    .filter((task) => categories.includes(taskCategory(task.type) ?? 'MONITORING'))
    .filter((task) => !specific || normalize(`${task.type} ${task.title}`).includes(specific))
    .sort((a, b) => a.dueDate.localeCompare(b.dueDate));
}

function changeTasks(topic: CopilotTopic, ctx: Context): CopilotAnswer {
  const answer = blank(topic, ctx);
  const mentioned = categoriesIn(ctx.text);
  const remembered = ctx.memory.topic ? TOPIC_CATEGORY[ctx.memory.topic] : undefined;
  const categories = mentioned.length ? mentioned : remembered ? [remembered] : [];
  if (!categories.length) {
    answer.direct = 'Which work should I change? For example “Move spraying to Friday” or “Delay fertilization on Field 3 by one week”.';
    answer.confidence = 'LOW';
    return answer;
  }
  const label = list(categories.map((category) => CATEGORY_LABELS[category].toLowerCase()));

  if (topic === 'REPRIORITIZE' && categories.length >= 2) {
    // "Prioritize harvesting before cultivation."
    const [first, second] = categories as [TaskCategory, TaskCategory];
    const leading = targetTasks(ctx, [first]);
    const following = targetTasks(ctx, [second]);
    const lastLead = leading.reduce((max, task) => (task.dueDate > max ? task.dueDate.slice(0, 10) : max), '');
    for (const task of leading) if (task.priority !== 'HIGH' && task.priority !== 'URGENT') answer.changes.push(priorityChange(task, 'HIGH', `${CATEGORY_LABELS[first]} goes first.`));
    for (const task of following) {
      if (lastLead && task.dueDate.slice(0, 10) <= lastLead) answer.changes.push(moveChange(task, addDays(lastLead, 1), `After the last ${CATEGORY_LABELS[first].toLowerCase()} task (${dayLabel(lastLead, ctx.today)}).`));
    }
    answer.direct = answer.changes.length
      ? `${CATEGORY_LABELS[first]} before ${CATEGORY_LABELS[second].toLowerCase()}: ${answer.changes.length} change${answer.changes.length > 1 ? 's' : ''} proposed.`
      : `${CATEGORY_LABELS[first]} is already scheduled before ${CATEGORY_LABELS[second].toLowerCase()}.`;
    answer.confidence = 'HIGH';
    return answer;
  }

  const tasks = targetTasks(ctx, categories);
  if (!tasks.length) {
    answer.direct = `There are no open ${label} tasks on ${scopeLabel(ctx)}.${topic === 'RESCHEDULE' || topic === 'REPRIORITIZE' ? ' Ask me to create one, for example “Create a task to spray on Friday”.' : ''}`;
    answer.confidence = 'HIGH';
    return answer;
  }

  if (topic === 'CANCEL') {
    answer.changes = tasks.map((task) => ({ key: `c-${task.id}`, kind: 'CANCEL', taskId: task.id, title: task.title, parcelId: task.parcelId, fromDate: task.dueDate.slice(0, 10), fromPriority: task.priority, reason: 'Cancelled on request.' }));
    answer.direct = `Cancel ${tasks.length} ${label} task${tasks.length > 1 ? 's' : ''} on ${scopeLabel(ctx)}: ${list(tasks.map((task) => task.title))}.`;
    if (categories.includes('IRRIGATION') && ctx.weather && ctx.weather.rain7d < 10) answer.risks.push(`Only ${ctx.weather.rain7d} mm of rain is forecast this week: check soil moisture before cancelling irrigation.`);
  } else if (topic === 'REPRIORITIZE') {
    const level: CopilotPriority = mentions(ctx.text, ['urgent']) ? 'URGENT' : mentions(ctx.text, ['low', 'scazut', 'mica']) ? 'LOW' : 'HIGH';
    answer.changes = tasks.filter((task) => task.priority !== level).map((task) => priorityChange(task, level, 'Priority changed on request.'));
    answer.direct = answer.changes.length ? `Set ${answer.changes.length} ${label} task${answer.changes.length > 1 ? 's' : ''} to ${level.toLowerCase()} priority.` : `The ${label} tasks are already ${level.toLowerCase()} priority.`;
  } else if (topic === 'GROUP') {
    const anchor = tasks[0]!.dueDate.slice(0, 10);
    const day = suitableDay(ctx, tasks[0]!.type, anchor < ctx.today ? ctx.today : anchor);
    answer.changes = tasks.filter((task) => daysBetween(anchor, task.dueDate) <= 10 && task.dueDate.slice(0, 10) !== day).map((task) => moveChange(task, day, `Grouped with the other ${label} work to save transport and setup time.`));
    answer.direct = answer.changes.length ? `Group ${answer.changes.length + 1} ${label} tasks on ${dayLabel(day, ctx.today)}, so the machine is set up once.` : `The ${label} tasks are already grouped.`;
    const area = tasks.reduce((sum, task) => sum + (ctx.snapshot.parcels.find((parcel) => parcel.id === task.parcelId)?.areaHa ?? 0), 0);
    if (area) answer.reasoning.push(`${t(area)} ha in total.`);
  } else {
    const { date, shiftDays } = ctx.date;
    if (!date && !shiftDays) {
      answer.direct = `To when should I move the ${label} work? For example “to Friday”, “by one week” or “to 15 September”.`;
      answer.confidence = 'LOW';
      return answer;
    }
    answer.changes = tasks.map((task) => moveChange(task, date ?? addDays(task.dueDate, shiftDays!), date ? `Moved to ${dayLabel(date, ctx.today)} on request.` : `Delayed by ${shiftDays} days on request.`));
    const target = date ? dayLabel(date, ctx.today) : `${shiftDays} days later`;
    answer.direct = `Move ${tasks.length} ${label} task${tasks.length > 1 ? 's' : ''} on ${scopeLabel(ctx)} to ${target}.`;
    const day = (ctx.snapshot.weather?.days ?? []).find((item) => item.date === (date ?? ''));
    if (day && (day.precipitationMm >= 2 || (day.windMaxKmh ?? 0) > 25) && categories.some((category) => category === 'CROP_PROTECTION' || category === 'FERTILIZATION')) {
      answer.risks.push(`${dayLabel(day.date, ctx.today)} looks ${day.precipitationMm >= 2 ? `rainy (${Math.round(day.precipitationMm)} mm)` : `windy (${Math.round(day.windMaxKmh!)} km/h)`}: ${dayLabel(suitableDay(ctx, tasks[0]!.type, addDays(day.date, 1)), ctx.today)} would be better.`);
    }
  }
  answer.reasoning.unshift(...tasks.map((task) => taskLine(ctx, task)));
  answer.confidence = 'HIGH';
  return answer;
}

const moveChange = (task: CopilotTask, toDate: string, reason: string): TaskChange => ({ key: `c-${task.id}`, kind: 'RESCHEDULE', taskId: task.id, title: task.title, parcelId: task.parcelId, fromDate: task.dueDate.slice(0, 10), toDate, fromPriority: task.priority, reason });
const priorityChange = (task: CopilotTask, toPriority: CopilotPriority, reason: string): TaskChange => ({ key: `p-${task.id}`, kind: 'PRIORITY', taskId: task.id, title: task.title, parcelId: task.parcelId, fromDate: task.dueDate.slice(0, 10), fromPriority: task.priority, toPriority, reason });

/** The task type a request names, most specific first. */
const TYPE_WORDS: readonly [string, readonly string[]][] = [
  ['HERBICIDE', ['herbicide', 'erbicid', 'weed control']],
  ['FUNGICIDE', ['fungicide', 'fungicid']],
  ['INSECTICIDE', ['insecticide', 'insecticid']],
  ['FOLIAR_FEEDING', ['foliar']],
  ['NITROGEN', ['nitrogen', 'urea', 'azot', 'uree']],
  ['FERTILIZATION', ['fertiliz', 'fertilis', 'npk', 'ingrasa']],
  ['SUBSOILING', ['subsoil', 'afanare']],
  ['CHISELING', ['chisel', 'scarific']],
  ['STRIP_TILL', ['strip']],
  ['DISKING', ['disk', 'disc', 'discuit']],
  ['PLOWING', ['plow', 'plough', 'arat']],
  ['SOIL_PREP', ['seedbed', 'pregatire']],
  ['SOWING', ['sow', 'plant', 'semana', 'semanat']],
  ['GRAIN_TRANSPORT', ['transport']],
  ['HARVEST_PREP', ['harvest prep', 'prepare harvest', 'pregatire recolt']],
  ['HARVEST', ['harvest', 'recolt', 'treier']],
  ['IRRIGATION_STOP', ['stop irrigat', 'opreste irig']],
  ['IRRIGATION_CHECK', ['irrigation check', 'check irrigat', 'verifica irig']],
  ['IRRIGATION_START', ['irrigat', 'irig', 'water']],
  ['SOIL_SAMPLING', ['soil sampl', 'soil test', 'probe de sol', 'analiza sol']],
  ['DISEASE_CHECK', ['disease', 'boli']],
  ['WEED_MONITORING', ['weed', 'buruien']],
  ['CROP_SCOUTING', ['scout']],
  ['FIELD_INSPECTION', ['inspect', 'check', 'verific']],
  ['HERBICIDE', ['spray', 'stropi']],
];

/** "yes", "da", "ok" right after an answer with suggested tasks. */
export const isConfirmation = (text: string) => /^(yes|yep|ok|okay|sure|do it|go ahead|please do|da|bine|sigur|confirm|confirma|de acord|fa-o|fa asta)\b/.test(text) && text.split(' ').length <= 4;

const REFERS_TO_PREVIOUS = ['these', 'them', 'those', 'all', 'recommended', 'suggested', 'proposed', 'previous', 'to-do', 'todo', 'to do', 'toate', 'acestea', 'astea', 'acelea', 'le ', '-le', 'recomandat', 'sugerat', 'propus', 'lista', 'de mai sus'];

/** Creates the tasks the previous answer suggested, skipping any already in the To-Do list. */
function createSuggested(ctx: Context, type: string | undefined): CopilotAnswer {
  const answer = blank('CREATE_TASK', ctx, ctx.memory.last?.parcelId ?? null);
  const suggested = ctx.memory.last?.proposals ?? [];
  const category = type ? COPILOT_TASK_TYPES[type]?.category : undefined;
  const wanted = category && suggested.some((proposal) => proposal.category === category) ? suggested.filter((proposal) => proposal.category === category) : suggested;
  const exists = (proposal: (typeof suggested)[number]) =>
    ctx.snapshot.tasks.some((task) => task.status !== 'DONE' && task.parcelId === proposal.parcelId && normalize(task.title) === normalize(proposal.title));
  const fresh = wanted.filter((proposal) => !exists(proposal));
  if (!suggested.length) {
    answer.direct = 'My last answer had no tasks to add. Ask for advice first (for example “When should I fertilize Field 12?”), or name the task: “Create a task to spray Field 3 on Friday”.';
    answer.confidence = 'LOW';
    return answer;
  }
  if (!fresh.length) {
    answer.direct = `${wanted.length === 1 ? 'That task is' : 'Those tasks are'} already in the To-Do list.`;
    answer.confidence = 'HIGH';
    return answer;
  }
  answer.proposals = fresh;
  answer.execute = true;
  answer.direct = `Adding ${fresh.length} task${fresh.length > 1 ? 's' : ''} to the To-Do list: ${list(fresh.map((proposal) => `${proposal.title} (${dayLabel(proposal.dueDate, ctx.today)})`))}.`;
  if (fresh.length < wanted.length) answer.reasoning.push(`${wanted.length - fresh.length} suggested task${wanted.length - fresh.length > 1 ? 's were' : ' was'} already in the list.`);
  answer.confidence = 'HIGH';
  return answer;
}

function createTask(ctx: Context): CopilotAnswer {
  const type = TYPE_WORDS.find(([, words]) => mentions(ctx.text, words))?.[0];
  const previous = ctx.memory.last?.proposals.length ? mentions(ctx.text, REFERS_TO_PREVIOUS) || isConfirmation(ctx.text) || !type : false;
  if (previous) return createSuggested(ctx, type);
  const answer = blank('CREATE_TASK', ctx);
  answer.execute = true;
  if (!type) {
    answer.execute = false;
    answer.direct = 'What kind of task? For example “Create a task to spray Field 3 on Friday” or “Add a harvest task for next week”.';
    answer.confidence = 'LOW';
    return answer;
  }
  const crop = ctx.crop;
  const due = ctx.date.date ?? (ctx.date.shiftDays ? addDays(ctx.today, ctx.date.shiftDays) : addDays(ctx.today, 1));
  const priority: CopilotPriority = mentions(ctx.text, ['urgent']) ? 'URGENT' : mentions(ctx.text, ['high', 'important', 'ridicat']) ? 'HIGH' : mentions(ctx.text, ['low', 'scazut']) ? 'LOW' : 'MEDIUM';
  const label = COPILOT_TASK_TYPES[type]!.label;
  const title = type === 'SOWING' && crop ? `Plant ${cropName(crop)}${ctx.parcel ? ` – ${ctx.parcel.name}` : ''}` : undefined;
  answer.proposals.push(propose(ctx.snapshot, { type, parcelId: ctx.parcel?.id ?? null, dueDate: due, priority, reason: 'Requested in the chat.', title }));
  answer.direct = `${title ?? `${label}${ctx.parcel ? ` – ${ctx.parcel.name}` : ''}`} on ${dayLabel(due, ctx.today)}, ${priority.toLowerCase()} priority.`;
  const day = (ctx.snapshot.weather?.days ?? []).find((item) => item.date === due);
  if (day && (taskIsSpray(type) || COPILOT_TASK_TYPES[type]!.category === 'FERTILIZATION' || type === 'HARVEST') && (day.precipitationMm >= 2 || (day.windMaxKmh ?? 0) > 25)) {
    answer.risks.push(`${dayLabel(due, ctx.today)} looks ${day.precipitationMm >= 2 ? 'rainy' : 'windy'}; ${dayLabel(suitableDay(ctx, type, addDays(due, 1)), ctx.today)} would be better.`);
  }
  if (!ctx.parcel && ctx.snapshot.parcels.length > 1) answer.dataGaps.push('No parcel named: the task is farm-wide. Name a parcel to attach it.');
  answer.confidence = 'HIGH';
  return answer;
}

// ------------------------------------------------------------------ overview

function general(ctx: Context): CopilotAnswer {
  const answer = blank('GENERAL', ctx);
  const insights = farmInsights(ctx.snapshot).filter((insight) => ctx.farmScope || !ctx.parcel || insight.parcelId === ctx.parcel.id || insight.parcelId === null);
  const items = facts(ctx);
  const area = items.reduce((sum, item) => sum + item.parcel.areaHa, 0);
  const crops = [...new Set(items.map((item) => item.crop).filter((crop): crop is NonNullable<typeof crop> => crop != null))];
  const open = items.reduce((sum, item) => sum + item.open.length, 0);
  const late = items.reduce((sum, item) => sum + item.overdue.length, 0);
  answer.direct = ctx.parcel && !ctx.farmScope
    ? `${ctx.parcel.name}: ${t(ctx.parcel.areaHa)} ha${items[0]!.crop ? ` of ${cropName(items[0]!.crop)} (${items[0]!.progress?.percent ?? 0}% of season)` : ', no crop this season'}, ${open} open task${open === 1 ? '' : 's'}${late ? ` (${late} overdue)` : ''}.`
    : `${ctx.snapshot.farmName}: ${items.length} parcels, ${t(area)} ha${crops.length ? `, growing ${list(crops.map((crop) => CROPS[crop].name.toLowerCase()))}` : ''}; ${open} open task${open === 1 ? '' : 's'}${late ? `, ${late} overdue` : ''}.`;
  answer.reasoning = insights.slice(0, 5).map((insight) => insight.message);
  if (ctx.weather) answer.reasoning.push(`Weather: ${ctx.weather.rain7d} mm of rain over 7 days, ${Math.round(ctx.weather.minTemp7d ?? 0)}–${Math.round(ctx.weather.maxTemp7d ?? 0)} °C.`);
  answer.proposals = insights.map((insight) => insight.proposal).filter((proposal): proposal is NonNullable<typeof proposal> => proposal != null).slice(0, 3);
  answer.nextSteps = [
    'Try: “What should I plant on ' + (ctx.parcel?.name ?? ctx.snapshot.parcels[0]!.name) + ' next year?”',
    '“Which field should I prioritize?”',
    '“What operations are scheduled this week?”',
  ];
  answer.confidence = 'MEDIUM';
  return answer;
}

// ------------------------------------------------------------------ satellite

function satelliteAnswer(ctx: Context, parcel: CopilotParcel): CopilotAnswer {
  const answer = blank('SATELLITE', ctx, parcel.id);
  const sat = parcel.satellite;
  if (!sat) {
    answer.direct = `No satellite image has been analysed for ${parcel.name} yet. Open the field and choose “Satellite monitoring” to load the latest Sentinel-2 image.`;
    answer.nextSteps = ['Turn on Satellite Monitoring in Settings if it is off.'];
    answer.confidence = 'LOW';
    return answer;
  }
  const change = (value: number | null, label: string) => (value == null ? null : `${value >= 0 ? '+' : ''}${Math.round(value * 100) / 100} NDVI ${label}`);
  const trend = [change(sat.change7d, 'in a week'), change(sat.change30d, 'in a month')].filter((item): item is string => Boolean(item));
  answer.direct = sat.healthScore == null
    ? `${parcel.name}: ${sat.healthLabel.toLowerCase()} on the ${dayLabel(sat.date, ctx.today)} image (NDVI ${sat.ndvi}).`
    : `${parcel.name}: field health ${sat.healthScore}/100 (${sat.healthLabel.toLowerCase()}) on the ${dayLabel(sat.date, ctx.today)} Sentinel-2 image, NDVI ${sat.ndvi}${trend.length ? ` (${trend.join(', ')})` : ''}.${sat.findings[0] && sat.findings[0].severity !== 'INFO' ? ` ${sat.findings[0].message}` : ''}`;
  answer.reasoning = [
    `NDVI ${sat.ndvi} measures green biomass${sat.ndre != null ? `; NDRE ${sat.ndre} measures chlorophyll (nitrogen status)` : ''}${sat.ndmi != null ? `; the moisture index ${sat.ndmi} measures water in the leaves` : ''}.`,
    ...sat.findings.map((finding) => `${finding.title}: ${finding.explanation}`),
  ];
  answer.risks = sat.findings.filter((finding) => finding.severity !== 'INFO').map((finding) => finding.message);
  answer.proposals = sat.findings
    .filter((finding) => finding.task)
    .map((finding) => propose(ctx.snapshot, { type: finding.task!.type, parcelId: parcel.id, dueDate: addDays(ctx.today, finding.severity === 'CRITICAL' ? 0 : 1), priority: finding.task!.priority, reason: finding.task!.reason, title: finding.task!.title, windowDays: 3 }));
  answer.alternatives = ['Compare with older images in the field’s Satellite monitoring screen (7, 30, 90 days and last year).'];
  answer.nextSteps = answer.proposals.length ? ['Visit the highlighted zones to confirm before treating.'] : ['Nothing needs a visit; check again after the next clear image.'];
  if (daysBetween(sat.date, ctx.today) > 10) answer.dataGaps.push(`The latest clear image is from ${sat.date}: clouds have hidden the field since.`);
  answer.confidence = daysBetween(sat.date, ctx.today) > 10 ? 'MEDIUM' : 'HIGH';
  return answer;
}

function satelliteFarm(ctx: Context): CopilotAnswer {
  const answer = blank('SATELLITE', ctx, null);
  const rows = ctx.snapshot.parcels.filter((parcel) => parcel.satellite?.healthScore != null).sort((a, b) => a.satellite!.healthScore! - b.satellite!.healthScore!);
  if (!rows.length) {
    answer.direct = 'No satellite image has been analysed yet. Open a field and choose “Satellite monitoring”, or turn on automatic refresh in Settings.';
    answer.confidence = 'LOW';
    return answer;
  }
  const worst = rows[0]!;
  answer.parcelId = worst.id;
  answer.direct = `Weakest field on the latest images: ${worst.name}, health ${worst.satellite!.healthScore}/100 (NDVI ${worst.satellite!.ndvi}).`;
  answer.reasoning = rows.map((parcel) => `${parcel.name}: ${parcel.satellite!.healthScore}/100, NDVI ${parcel.satellite!.ndvi} (${parcel.satellite!.date})`);
  answer.risks = rows.flatMap((parcel) => parcel.satellite!.findings.filter((finding) => finding.severity !== 'INFO').map((finding) => finding.message)).slice(0, 5);
  answer.confidence = 'MEDIUM';
  return answer;
}

// ------------------------------------------------------------------ calendar

const SUGGESTION_HORIZON_DAYS = 21;

/**
 * Calendar entries the copilot proposes: spraying, fertilization, harvest and
 * irrigation windows that are not planned yet.
 */
export function calendarSuggestions(snapshot: FarmSnapshot): TaskProposal[] {
  const today = snapshot.now.slice(0, 10);
  const weather = weatherFacts(snapshot);
  const suggestions: TaskProposal[] = [];
  for (const parcel of snapshot.parcels) {
    const ctx: Context = { text: '', snapshot, today, parcel, crop: null, memory: EMPTY_MEMORY, farmScope: false, weather, date: { date: null, shiftDays: null } };
    const open = snapshot.tasks.filter((task) => task.parcelId === parcel.id && task.status !== 'DONE');
    const builders = [fertilize, harvest, irrigate, spray];
    for (const build of builders) {
      for (const proposal of build(ctx, parcel).proposals) {
        const days = daysBetween(today, proposal.dueDate);
        if (days < 0 || days > SUGGESTION_HORIZON_DAYS) continue;
        const sameKind = (type: string) => type === proposal.type || (taskCategory(type) === proposal.category && proposal.category !== 'MONITORING');
        if (open.some((task) => sameKind(task.type))) continue;
        if (suggestions.some((item) => item.parcelId === parcel.id && item.type === proposal.type)) continue;
        suggestions.push(proposal);
      }
    }
  }
  return suggestions.sort((a, b) => a.dueDate.localeCompare(b.dueDate));
}

export { farmInsights };
