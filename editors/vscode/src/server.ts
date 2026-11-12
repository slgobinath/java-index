// Copyright 2026 Gobinath Loganathan
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

import * as cp from 'child_process';
import * as crypto from 'crypto';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import { promisify } from 'util';
import * as vscode from 'vscode';

const execFile = promisify(cp.execFile);

const RELEASES = 'https://github.com/slgobinath/java-index/releases/download';

/** Platforms with a native relay in the release; elsewhere the jar runs on a JVM. */
const NATIVE: Record<string, string> = {
  'darwin-arm64': 'macos-arm64',
  'linux-x64': 'linux-x64',
  'linux-arm64': 'linux-arm64',
};

export interface ServerCommand {
  command: string;
  args: string[];
  env: NodeJS.ProcessEnv;
}

/** A failure with a message fit for the user, and optionally a page that helps fix it. */
export class SetupError extends Error {
  constructor(message: string, readonly help?: { label: string; url: string }) {
    super(message);
  }
}

/**
 * How to run the server, in order: `javaIndex.server.path`, `jindex` on the PATH, else the
 * release matching this extension's version, downloaded once into global storage.
 */
export async function resolveServer(
  context: vscode.ExtensionContext,
  log: vscode.LogOutputChannel,
): Promise<ServerCommand> {
  const config = vscode.workspace.getConfiguration('javaIndex');
  const env = { ...process.env };
  const javaHome = expandHome(config.get<string>('java.home', ''));
  if (javaHome) {
    env.JINDEX_JAVA = path.join(javaHome, 'bin', exe('java'));
  }

  const configured = expandHome(config.get<string>('server.path', ''));
  if (configured) {
    log.info(`Using javaIndex.server.path: ${configured}`);
    return { command: configured, args: config.get<string[]>('server.arguments', []), env };
  }

  const onPath = which('jindex', env);
  if (onPath) {
    log.info(`Using jindex from the PATH: ${onPath}`);
    return { command: onPath, args: [], env };
  }

  // The downloaded relay starts the daemon on a JVM, so find one before downloading anything.
  const java = await findJava(env, log);
  env.JINDEX_JAVA = java;
  const version: string = context.extension.packageJSON.version;
  const installed = await install(context, version, log);
  return installed.kind === 'native'
    ? { command: installed.path, args: [], env }
    : { command: java, args: ['-jar', installed.path, 'lsp'], env };
}

interface Installed {
  kind: 'native' | 'jar';
  path: string;
}

let installing: Promise<Installed> | undefined;

/** Downloads the release once per version; concurrent callers (one per folder) share it. */
function install(
  context: vscode.ExtensionContext,
  version: string,
  log: vscode.LogOutputChannel,
): Promise<Installed> {
  if (!installing) {
    installing = doInstall(context, version, log).finally(() => (installing = undefined));
  }
  return installing;
}

async function doInstall(
  context: vscode.ExtensionContext,
  version: string,
  log: vscode.LogOutputChannel,
): Promise<Installed> {
  const platform = NATIVE[`${process.platform}-${process.arch}`];
  const root = path.join(context.globalStorageUri.fsPath, 'server');
  const dir = path.join(root, version);
  const installed: Installed = platform
    ? { kind: 'native', path: path.join(dir, 'bin', 'jindex') }
    : { kind: 'jar', path: path.join(dir, 'java-index.jar') };
  if (fs.existsSync(installed.path)) {
    log.info(`Using downloaded java-index ${version}: ${installed.path}`);
    return installed;
  }

  const asset = platform ? `java-index-${version}-${platform}.tar.gz` : `java-index-${version}.jar`;
  log.info(`jindex is not on the PATH; downloading ${asset}`);
  await vscode.window.withProgress(
    {
      location: vscode.ProgressLocation.Notification,
      title: `Java Index: downloading java-index ${version}`,
    },
    async (progress) => {
      const sums = (await download(`${RELEASES}/v${version}/SHA256SUMS`)).toString('utf8');
      const expected = sums
        .split('\n')
        .map((line) => line.trim().split(/\s+/))
        .find((fields) => fields[1] === asset)?.[0];
      if (!expected) {
        throw new SetupError(`Release v${version} has no ${asset}.`);
      }
      const data = await download(`${RELEASES}/v${version}/${asset}`, progress);
      const actual = crypto.createHash('sha256').update(data).digest('hex');
      if (actual !== expected) {
        throw new SetupError(`Checksum mismatch for ${asset}: expected ${expected}, got ${actual}.`);
      }

      // Unpack next to the target and rename into place, so a failed or concurrent download
      // (another window) never leaves a half-written version behind.
      fs.mkdirSync(root, { recursive: true });
      const staging = fs.mkdtempSync(path.join(root, `.${version}-`));
      try {
        if (platform) {
          const archive = path.join(staging, asset);
          fs.writeFileSync(archive, data);
          await execFile('tar', ['-xzf', archive, '-C', staging, '--strip-components=1']);
          fs.rmSync(archive);
        } else {
          fs.writeFileSync(path.join(staging, 'java-index.jar'), data);
        }
        try {
          fs.renameSync(staging, dir);
        } catch (e) {
          if (!fs.existsSync(installed.path)) {
            throw e;
          }
        }
      } finally {
        fs.rmSync(staging, { recursive: true, force: true });
      }
    },
  );
  log.info(`Installed java-index ${version}: ${installed.path}`);
  removeOtherVersions(root, version, log);
  return installed;
}

function removeOtherVersions(root: string, keep: string, log: vscode.LogOutputChannel) {
  for (const name of fs.readdirSync(root)) {
    if (name !== keep && !name.startsWith('.')) {
      try {
        fs.rmSync(path.join(root, name), { recursive: true, force: true });
        log.info(`Removed java-index ${name}`);
      } catch {
        // still in use (e.g. by a running daemon on Windows); try again after the next update
      }
    }
  }
}

async function download(
  url: string,
  progress?: vscode.Progress<{ message?: string; increment?: number }>,
): Promise<Buffer> {
  let res: Response;
  try {
    res = await fetch(url);
  } catch (e) {
    throw new SetupError(`Cannot download ${url}: ${(e as Error).message}`);
  }
  if (!res.ok || !res.body) {
    throw new SetupError(`Cannot download ${url}: HTTP ${res.status}`);
  }
  const total = Number(res.headers.get('content-length')) || 0;
  const chunks: Buffer[] = [];
  let received = 0;
  const reader = res.body.getReader();
  for (;;) {
    const { done, value } = await reader.read();
    if (done) {
      break;
    }
    chunks.push(Buffer.from(value));
    received += value.length;
    if (progress && total) {
      progress.report({
        message: `${mb(received)} / ${mb(total)} MB`,
        increment: (100 * value.length) / total,
      });
    }
  }
  return Buffer.concat(chunks);
}

/**
 * A JDK 21+ for the daemon: `javaIndex.java.home` (already in `JINDEX_JAVA`), `$JINDEX_JAVA`,
 * `$JAVA_HOME`, `java` on the PATH, then the JDK configured for the Red Hat Java extension.
 */
async function findJava(env: NodeJS.ProcessEnv, log: vscode.LogOutputChannel): Promise<string> {
  const candidates: string[] = [];
  if (env.JINDEX_JAVA) {
    candidates.push(env.JINDEX_JAVA);
  }
  if (env.JAVA_HOME) {
    candidates.push(path.join(env.JAVA_HOME, 'bin', exe('java')));
  }
  const onPath = which('java', env);
  if (onPath) {
    candidates.push(onPath);
  }
  const jdtHome = vscode.workspace.getConfiguration('java').get<string>('jdt.ls.java.home');
  if (jdtHome) {
    candidates.push(path.join(expandHome(jdtHome), 'bin', exe('java')));
  }

  const tooOld: string[] = [];
  for (const java of candidates) {
    const major = await javaVersion(java);
    if (major === undefined) {
      log.warn(`Not a usable java: ${java}`);
    } else if (major < 21) {
      log.warn(`Java ${major} is too old (21+ needed): ${java}`);
      tooOld.push(`${java} (Java ${major})`);
    } else {
      log.info(`Using Java ${major}: ${java}`);
      return java;
    }
  }
  const found = tooOld.length ? ` Found only ${tooOld.join(', ')}.` : '';
  throw new SetupError(
    `java-index needs Java 21 or later.${found} Install a JDK, or set javaIndex.java.home.`,
    { label: 'Get a JDK', url: 'https://adoptium.net/' },
  );
}

/** The major version `java -version` reports, or undefined if it does not run. */
async function javaVersion(java: string): Promise<number | undefined> {
  try {
    const { stderr } = await execFile(java, ['-version'], { timeout: 10_000 });
    const match = /version "(\d+)(?:\.(\d+))?/.exec(stderr);
    if (!match) {
      return undefined;
    }
    const major = Number(match[1]);
    return major === 1 ? Number(match[2]) : major; // "1.8.0" is Java 8
  } catch {
    return undefined;
  }
}

/** The executable `name` on the PATH, honouring PATHEXT on Windows. */
function which(name: string, env: NodeJS.ProcessEnv): string | undefined {
  const exts =
    process.platform === 'win32'
      ? (env.PATHEXT ?? '.COM;.EXE;.BAT;.CMD').split(';').filter(Boolean)
      : [''];
  for (const dir of (env.PATH ?? env.Path ?? '').split(path.delimiter)) {
    if (!dir) {
      continue;
    }
    for (const ext of exts) {
      const candidate = path.join(dir, name + ext);
      try {
        if (fs.statSync(candidate).isFile()) {
          fs.accessSync(candidate, fs.constants.X_OK);
          return candidate;
        }
      } catch {
        // not here
      }
    }
  }
  return undefined;
}

/** Where java-index keeps its caches, including the library sources it opens definitions in. */
export function stateDir(): string {
  if (process.env.JINDEX_HOME) {
    return process.env.JINDEX_HOME;
  }
  const cache = process.env.XDG_CACHE_HOME || path.join(os.homedir(), '.cache');
  return path.join(cache, 'java-index');
}

function exe(name: string): string {
  return process.platform === 'win32' ? `${name}.exe` : name;
}

function expandHome(p: string): string {
  return p.startsWith('~/') || p === '~' ? path.join(os.homedir(), p.slice(1)) : p;
}

function mb(bytes: number): string {
  return (bytes / 1024 / 1024).toFixed(1);
}
