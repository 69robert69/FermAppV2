import { CROP_CODES, CROPS, rankRotationOptions, type CropCode } from '@fermapp/domain';

interface ParcelFacts {
  header: { name: string; areaHa: number };
  crop: { name: string } | null;
  rotation: { history: { year: number; crop: CropCode; name: string }[] };
  soil: { status: string; ph?: number | null; organicMatterPct?: number | null };
  weather: { next7: { precipitationMm: number; tMinC: number; tMaxC: number }[] } | null;
}

/** Răspuns calculat din datele parcelei. Un model AI ar avea nevoie de un server care să îi păstreze cheia. */
export function answerQuestion(facts: ParcelFacts | null) {
  const message = (content: string) => ({
    conversationId: 'local',
    message: { id: `local-${Date.now()}`, content, source: 'rules' },
  });
  if (!facts) return message('Alege o parcelă pentru a primi o analiză bazată pe istoricul, solul și prognoza ei.');

  const { header, crop, rotation, soil, weather } = facts;
  const year = new Date().getFullYear();
  const parts: string[] = [];
  if (soil.ph != null && soil.organicMatterPct != null) parts.push(`Parcela ${header.name} are pH ${soil.ph} și materie organică ${soil.organicMatterPct}%.`);
  else parts.push(`Parcela ${header.name} are ${header.areaHa} ha, iar profilul de sol nu este încă disponibil.`);

  const last = rotation.history.find((season) => season.year < year);
  if (crop) parts.push(`Cultura din ${year} este ${crop.name.toLowerCase()}.`);
  else if (last) parts.push(`Ultima cultură înregistrată este ${last.name.toLowerCase()}, în ${last.year}.`);

  const options = rankRotationOptions(year + 1, rotation.history, CROP_CODES);
  const good = options.filter((option) => option.verdict === 'RECOMMENDED').slice(0, 2);
  if (good.length > 0) parts.push(`Pentru sezonul următor se recomandă ${good.map((option) => CROPS[option.crop].name.toLowerCase()).join(' sau ')}.`);

  const week = weather?.next7 ?? [];
  if (week.length > 0) {
    const rain = Math.round(week.reduce((sum, day) => sum + day.precipitationMm, 0) * 10) / 10;
    parts.push(`În următoarele 7 zile sunt estimate ${rain} mm de precipitații, cu temperaturi între ${Math.round(Math.min(...week.map((day) => day.tMinC)))} și ${Math.round(Math.max(...week.map((day) => day.tMaxC)))} °C.`);
  }
  return message(parts.join(' '));
}
