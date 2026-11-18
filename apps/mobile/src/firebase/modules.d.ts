declare module '@mapbox/shp-write' {
  const shpwrite: { zip(collection: unknown, options: Record<string, unknown>): Promise<unknown> };
  export default shpwrite;
}

declare module 'shpjs' {
  export function parseShp(shp: ArrayBuffer, prj?: string): unknown[];
  export function parseDbf(dbf: ArrayBuffer, cpg?: string): Record<string, unknown>[];
  export function combine(parts: [unknown[], Record<string, unknown>[] | undefined]): unknown;
}
