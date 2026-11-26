import { describe, expect, it } from 'vitest';
import { askCopilot, calendarSuggestions, detectTopic, EMPTY_MEMORY, farmInsights, normalizeQuestion, parseDate, type CopilotMemory, type FarmSnapshot } from '../src';

const NOW = '2026-06-10T08:00:00.000Z';

function farm(overrides: Partial<FarmSnapshot> = {}): FarmSnapshot {
  const days = Array.from({ length: 8 }, (_, i) => ({
    date: new Date(Date.UTC(2026, 5, 10 + i)).toISOString().slice(0, 10),
    tMinC: 13,
    tMaxC: 26,
    precipitationMm: 0,
    rainProbabilityPct: 5,
    windMaxKmh: 10,
    et0Mm: 5,
  }));
  const hours = Array.from({ length: 96 }, (_, i) => ({
    time: new Date(Date.UTC(2026, 5, 10, 8 + i)).toISOString().slice(0, 16),
    temperatureC: 18,
    humidityPct: 60,
    precipitationMm: 0,
    precipitationProbabilityPct: 5,
    windKmh: 7,
  }));
  return {
    now: NOW,
    farmName: 'Test Farm',
    canSeeFinance: true,
    parcels: [
      {
        id: 'p12',
        name: 'Field 12',
        code: 'F12',
        areaHa: 40,
        tillage: 'CONVENTIONAL',
        irrigatedHa: 40,
        notes: null,
        seasons: [
          { year: 2026, crop: 'CORN', sowingDate: '2026-04-20', harvestDate: null, yieldT: null, expectedYieldTHa: 9, expectedPricePerT: 190, actualPricePerT: null },
          { year: 2025, crop: 'SUNFLOWER', sowingDate: '2025-04-05', harvestDate: '2025-09-01', yieldT: 120, expectedYieldTHa: null, expectedPricePerT: null, actualPricePerT: null },
          { year: 2024, crop: 'WHEAT', sowingDate: '2023-10-10', harvestDate: '2024-07-10', yieldT: 260, expectedYieldTHa: null, expectedPricePerT: null, actualPricePerT: null },
        ],
        soil: { ph: 6.6, organicMatterPct: 2.8, clayPct: 32, sandPct: 25, waterHoldingMmPerM: 180, texture: 'Clay loam', label: 'Chernozem' },
        economics: { year: 2026, costPerHa: 1050, totalCost: 42000, costShares: { Fertilizers: 35, Fuel: 20, Seeds: 25 }, expectedProfitPerHa: 660, actualProfitPerHa: null, revenuePerHa: 1710 },
        observations: [],
      },
      {
        id: 'p7',
        name: 'North Hill',
        code: 'NH',
        areaHa: 25,
        tillage: 'MINIMUM_TILL',
        irrigatedHa: null,
        notes: null,
        seasons: [
          { year: 2026, crop: 'WHEAT', sowingDate: '2025-10-05', harvestDate: null, yieldT: null, expectedYieldTHa: 6, expectedPricePerT: 200, actualPricePerT: null },
          { year: 2025, crop: 'SOY', sowingDate: '2025-04-25', harvestDate: '2025-09-15', yieldT: 70, expectedYieldTHa: null, expectedPricePerT: null, actualPricePerT: null },
        ],
        soil: null,
        economics: { year: 2026, costPerHa: 800, totalCost: 20000, costShares: { Fertilizers: 30, Fuel: 25 }, expectedProfitPerHa: 400, actualProfitPerHa: null, revenuePerHa: 1200 },
        observations: [],
      },
    ],
    tasks: [
      { id: 't1', parcelId: 'p12', title: 'Herbicide application – Field 12', type: 'HERBICIDE', priority: 'MEDIUM', status: 'TODO', dueDate: '2026-06-05T00:00:00.000Z', assigneeId: null, machineId: null, source: 'USER' },
      { id: 't2', parcelId: 'p7', title: 'Fertilization – North Hill', type: 'FERTILIZATION', priority: 'MEDIUM', status: 'TODO', dueDate: '2026-06-12T00:00:00.000Z', assigneeId: 'u2', machineId: 'm1', source: 'USER' },
      { id: 't3', parcelId: 'p12', title: 'Seedbed preparation – Field 12', type: 'SOIL_PREP', priority: 'LOW', status: 'TODO', dueDate: '2026-06-11T00:00:00.000Z', assigneeId: null, machineId: null, source: 'USER' },
    ],
    runs: [],
    machines: [
      { id: 'm1', name: 'Fendt 724', type: 'TRACTOR', powerHp: 240 },
      { id: 'm2', name: 'JD 6155', type: 'TRACTOR', powerHp: 155 },
      { id: 'm3', name: 'Amazone UX', type: 'SPRAYER', powerHp: null },
    ],
    members: [
      { id: 'u1', name: 'Ana', role: 'FARM_MANAGER' },
      { id: 'u2', name: 'Ion', role: 'OPERATOR' },
      { id: 'u3', name: 'Mihai', role: 'OPERATOR' },
    ],
    weather: { days, hours, past30RainMm: 45 },
    ...overrides,
  };
}

const ask = (question: string, snapshot = farm(), memory: CopilotMemory = EMPTY_MEMORY) => askCopilot(question, snapshot, memory);

describe('copilot language', () => {
  it('detects the topic of the supported questions', () => {
    const cases: [string, string][] = [
      ['What should I plant next year?', 'NEXT_CROP'],
      ['Is corn suitable for this field?', 'CROP_SUITABILITY'],
      ['When should I fertilize?', 'FERTILIZE'],
      ['When should I harvest?', 'HARVEST'],
      ['Should I irrigate this week?', 'IRRIGATE'],
      ['What yield can I expect?', 'YIELD'],
      ['What operations are still pending?', 'PENDING'],
      ['What is the profitability of this field?', 'PROFITABILITY'],
      ['What machinery should I assign?', 'MACHINERY'],
      ['How can I reduce costs?', 'REDUCE_COSTS'],
      ['Is No-Till a better option here?', 'TILLAGE'],
      ['Which field should I prioritize?', 'PRIORITIZE'],
      ['Which field has the highest ROI?', 'ROI_RANKING'],
      ['What operations are scheduled this week?', 'WEEK_SCHEDULE'],
      ['How many hectares still need spraying?', 'HECTARES_LEFT'],
      ['Which tractors are currently idle?', 'IDLE_MACHINES'],
      ['What is the expected farm profit this season?', 'FARM_PROFIT'],
      ['How many fields have overdue operations?', 'OVERDUE'],
      ['Move spraying to Friday.', 'RESCHEDULE'],
      ['Delay fertilization by one week.', 'RESCHEDULE'],
      ['Cancel irrigation.', 'CANCEL'],
      ['Prioritize harvesting before cultivation.', 'REPRIORITIZE'],
      ['Why?', 'WHY'],
      ['Ce să semănez anul viitor?', 'NEXT_CROP'],
      ['Când să recoltez?', 'HARVEST'],
      ['Can I spray later today?', 'SPRAY'],
    ];
    for (const [question, topic] of cases) expect(detectTopic(normalizeQuestion(question)), question).toBe(topic);
  });

  it('reads dates', () => {
    const today = '2026-06-10'; // a Wednesday
    expect(parseDate('move spraying to friday', today).date).toBe('2026-06-12');
    expect(parseDate('delay fertilization by one week', today).shiftDays).toBe(7);
    expect(parseDate('apply in 10 days', today).date).toBe('2026-06-20');
    expect(parseDate('harvest on 15 september', today).date).toBe('2026-09-15');
    expect(parseDate('muta pe vineri', today).date).toBe('2026-06-12');
    expect(parseDate('amana cu doua saptamani', today).shiftDays).toBe(14);
  });
});

describe('copilot answers', () => {
  it('recommends the next crop from the parcel’s own data, with every section', () => {
    const { answer } = ask('What should I plant on Field 12 next year?');
    expect(answer.topic).toBe('NEXT_CROP');
    expect(answer.parcelId).toBe('p12');
    expect(answer.direct).toContain('Field 12');
    expect(answer.direct).toMatch(/t\/ha/);
    expect(answer.direct).toMatch(/€/);
    expect(answer.reasoning.length).toBeGreaterThan(0);
    expect(answer.alternatives.length).toBeGreaterThan(0);
    expect(answer.economics.length).toBeGreaterThan(0);
    expect(answer.proposals.some((proposal) => proposal.type === 'SOWING')).toBe(true);
    // Corn after corn is a weak rotation, so it is not the recommendation.
    expect(answer.crop).not.toBe('CORN');
  });

  it('gives different answers to different questions about the same field', () => {
    const memory = ask('What should I plant on Field 12 next year?').memory;
    const answers = ['When should I fertilize?', 'When should I harvest?', 'Should I irrigate this week?', 'What yield can I expect?'].map((question) => ask(question, farm(), memory).answer.direct);
    expect(new Set(answers).size).toBe(answers.length);
    for (const direct of answers) expect(direct).toContain('Field 12');
  });

  it('remembers the field and explains the previous answer on “why?”', () => {
    const first = ask('What should I plant on Field 12?');
    const why = ask('Why?', farm(), first.memory);
    expect(why.answer.topic).toBe('WHY');
    expect(why.answer.direct).toContain(first.answer.direct);
    expect(why.answer.reasoning.length).toBeGreaterThanOrEqual(first.answer.reasoning.length);
    const follow = ask('And North Hill?', farm(), first.memory);
    expect(follow.answer.topic).toBe('NEXT_CROP');
    expect(follow.answer.parcelId).toBe('p7');
  });

  it('times nitrogen from the sowing date and plans the task', () => {
    const { answer } = ask('When should I fertilize Field 12?');
    expect(answer.direct).toMatch(/nitrogen/i);
    expect(answer.direct).toMatch(/kg N\/ha/);
    const task = answer.proposals.find((proposal) => proposal.type === 'NITROGEN');
    expect(task).toBeDefined();
    expect(task!.title).toBe('Apply nitrogen fertilizer – Field 12');
    expect(task!.machineId).not.toBeNull();
    expect(task!.followUps.map((item) => item.type)).toContain('CROP_SCOUTING');
  });

  it('advises against spraying when rain is coming', () => {
    const rainy = farm();
    rainy.weather!.hours = rainy.weather!.hours.map((hour, i) => (i >= 5 && i < 15 ? { ...hour, precipitationMm: 2, precipitationProbabilityPct: 90 } : hour));
    const { answer } = ask('Can I spray Field 12?', rainy);
    expect(answer.direct).toContain('Rain is expected within 48 hours');
    expect(answer.direct).toContain('not recommended');
  });

  it('plans harvest with follow-up tasks matched to the tillage system', () => {
    const late = farm({ now: '2026-10-27T08:00:00.000Z' });
    const { answer } = ask('When should I harvest Field 12?', late);
    const task = answer.proposals.find((proposal) => proposal.type === 'HARVEST')!;
    expect(task.priority).toBe('HIGH');
    expect(task.followUps.map((item) => item.type)).toEqual(['GRAIN_TRANSPORT', 'SOIL_SAMPLING', 'PLOWING']);
  });

  it('answers farm-wide questions', () => {
    expect(ask('Which field has the highest ROI?').answer.direct).toContain('Field 12');
    expect(ask('What is the expected farm profit this season?').answer.direct).toMatch(/€/);
    expect(ask('How many fields have overdue operations?').answer.direct).toContain('1 field has overdue');
    const idle = ask('Which tractors are currently idle?').answer;
    expect(idle.direct).toContain('JD 6155');
    expect(idle.direct).not.toContain('Fendt 724');
    expect(ask('How many hectares still need spraying?').answer.direct).toContain('40 ha');
    expect(ask('Which field should I prioritize?').answer.direct).toContain('Field 12');
  });

  it('reschedules, cancels and reorders tasks', () => {
    const move = ask('Move spraying to Friday').answer;
    expect(move.changes).toEqual([expect.objectContaining({ kind: 'RESCHEDULE', taskId: 't1', toDate: '2026-06-12' })]);
    const delay = ask('Delay fertilization by one week').answer;
    expect(delay.changes[0]).toMatchObject({ taskId: 't2', toDate: '2026-06-19' });
    const order = ask('Prioritize cultivation before spraying').answer;
    expect(order.changes).toEqual(
      expect.arrayContaining([expect.objectContaining({ kind: 'PRIORITY', taskId: 't3', toPriority: 'HIGH' }), expect.objectContaining({ kind: 'RESCHEDULE', taskId: 't1', toDate: '2026-06-12' })]),
    );
    expect(ask('Cancel the fertilization on North Hill').answer.changes[0]).toMatchObject({ kind: 'CANCEL', taskId: 't2' });
  });

  it('creates a task from a request', () => {
    const { answer } = ask('Create a task to apply fungicide on North Hill next week, high priority');
    expect(answer.proposals[0]).toMatchObject({ type: 'FUNGICIDE', parcelId: 'p7', dueDate: '2026-06-15', priority: 'HIGH', category: 'CROP_PROTECTION' });
  });

  it('hides money from operators', () => {
    expect(ask('Which field has the highest ROI?', farm({ canSeeFinance: false })).answer.direct).toContain('only to managers');
  });
});

describe('proactive insights', () => {
  it('flags overdue work, unprofitable parcels and missing soil data', () => {
    const insights = farmInsights(farm());
    expect(insights.some((insight) => insight.kind === 'OVERDUE' && insight.parcelId === 'p12')).toBe(true);
    expect(insights.some((insight) => insight.kind === 'PROFIT' && insight.message.includes('North Hill'))).toBe(true);
    expect(insights.some((insight) => insight.kind === 'DATA')).toBe(true);
  });

  it('warns about rain before planned field work', () => {
    const wet = farm();
    wet.weather!.hours = wet.weather!.hours.map((hour, i) => (i < 30 ? { ...hour, precipitationMm: 1 } : hour));
    expect(farmInsights(wet).some((insight) => insight.kind === 'WEATHER' && insight.message.includes('Heavy rainfall'))).toBe(true);
  });
});

describe('adding suggested tasks from the chat', () => {
  it('creates the previous suggestions when asked, in English or Romanian', () => {
    const advice = ask('When should I fertilize Field 12?');
    expect(advice.answer.proposals.length).toBeGreaterThan(0);
    for (const request of ['Add them to the to-do list', 'Adaugă-le în to-do', 'Da', 'yes', 'Creează task-urile recomandate']) {
      const turn = ask(request, farm(), advice.memory);
      expect(turn.answer.topic, request).toBe('CREATE_TASK');
      expect(turn.answer.execute, request).toBe(true);
      expect(turn.answer.proposals.map((proposal) => proposal.type), request).toEqual(advice.answer.proposals.map((proposal) => proposal.type));
    }
  });

  it('does not add a task twice', () => {
    const advice = ask('When should I fertilize Field 12?');
    const existing = farm();
    const proposal = advice.answer.proposals[0]!;
    existing.tasks.push({ id: 'ai1', parcelId: proposal.parcelId, title: proposal.title, type: proposal.type, priority: proposal.priority, status: 'TODO', dueDate: proposal.dueDate, assigneeId: null, machineId: null, source: 'AI' });
    const turn = ask('add them to to-do', existing, advice.memory);
    expect(turn.answer.proposals).toEqual([]);
    expect(turn.answer.direct).toContain('already in the To-Do list');
  });

  it('creates a named task directly', () => {
    const { answer } = ask('Creează un task de erbicidare pe North Hill vineri');
    expect(answer.execute).toBe(true);
    expect(answer.proposals[0]).toMatchObject({ type: 'HERBICIDE', parcelId: 'p7', dueDate: '2026-06-12' });
  });

  it('does not create tasks for questions about the list', () => {
    expect(ask('What is on my to-do list this week?').answer.execute).toBeFalsy();
  });
});

describe('satellite and calendar in the copilot', () => {
  it('answers from the latest satellite reading and proposes zone tasks', () => {
    const snapshot = farm();
    snapshot.parcels[0]!.satellite = {
      date: '2026-06-08', ndvi: 0.62, ndre: 0.31, ndmi: 0.05, healthScore: 64, healthLabel: 'Moderate', change7d: -0.09, change30d: 0.12,
      findings: [{ severity: 'WARNING', title: 'Reduced vigor – north', message: 'North section of Field 12 shows reduced vegetation vigor.', explanation: 'Lower NDVI means less biomass.', task: { type: 'FIELD_INSPECTION', title: 'Inspect north zone – Field 12', reason: 'NDVI 0.45 vs 0.62', priority: 'HIGH' } }],
    };
    const { answer } = askCopilot('What does the satellite show on Field 12?', snapshot, EMPTY_MEMORY);
    expect(answer.topic).toBe('SATELLITE');
    expect(answer.direct).toContain('64/100');
    expect(answer.proposals[0]).toMatchObject({ type: 'FIELD_INSPECTION', title: 'Inspect north zone – Field 12' });
    expect(farmInsights(snapshot).some((insight) => insight.kind === 'SATELLITE')).toBe(true);
  });

  it('suggests calendar entries that are not planned yet', () => {
    const suggestions = calendarSuggestions(farm());
    expect(suggestions.some((item) => item.type === 'NITROGEN' && item.parcelId === 'p12')).toBe(true);
    // North Hill already has a fertilization task, so no fertilizer suggestion is repeated there.
    expect(suggestions.some((item) => item.parcelId === 'p7' && item.category === 'FERTILIZATION')).toBe(false);
  });
});
