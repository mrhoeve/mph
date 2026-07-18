import { rm, mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';

export default async function globalSetup(): Promise<void> {
  const e2eRoot = __dirname;
  const runtimeDirectory = resolve(e2eRoot, '.runtime');
  await rm(runtimeDirectory, { recursive: true, force: true });
  await mkdir(runtimeDirectory, { recursive: true });
}
