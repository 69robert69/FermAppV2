/** Files kept in memory, so tests can check what the app generates. */
export const files = new Map<string, Uint8Array | string>();

export const Paths = { document: 'document', cache: 'cache' };

export class File {
  uri: string;
  constructor(...parts: unknown[]) {
    this.uri = parts.map(String).join('/');
  }
  get exists() {
    return files.has(this.uri);
  }
  create() {
    files.set(this.uri, '');
  }
  write(content: Uint8Array | string) {
    files.set(this.uri, content);
  }
  delete() {
    files.delete(this.uri);
  }
  move(target: File) {
    files.set(target.uri, files.get(this.uri) ?? '%PDF');
    files.delete(this.uri);
  }
  async bytes() {
    const content = files.get(this.uri) ?? '';
    return typeof content === 'string' ? new TextEncoder().encode(content) : content;
  }
}
