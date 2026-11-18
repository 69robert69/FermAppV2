declare module '@mapbox/shp-write' {
  const shpwrite: {
    zip(geojson: unknown, options?: Record<string, unknown>): Promise<unknown>;
  };
  export default shpwrite;
}

declare module 'shpjs' {
  export function parseZip(buffer: Buffer | ArrayBuffer): Promise<unknown>;
}
