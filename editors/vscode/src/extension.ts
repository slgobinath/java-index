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

import * as vscode from 'vscode';
import {
  DocumentFilter,
  LanguageClient,
  LanguageClientOptions,
  ServerOptions,
} from 'vscode-languageclient/node';
import { resolveServer, SetupError, stateDir } from './server';

/** Config files the server links to Java (see JavaIndexServer's file watchers). */
const CONFIG_FILES = [
  '**/application*.properties',
  '**/application*.yml',
  '**/application*.yaml',
  '**/bootstrap*.yml',
  '**/microprofile-config.properties',
];

const SERVED: vscode.DocumentFilter[] = [
  { scheme: 'file', language: 'java' },
  ...CONFIG_FILES.map((pattern) => ({ scheme: 'file', pattern })),
];

/**
 * One client per workspace folder: the relay routes a connection to a repository's daemon by
 * the root it is initialized with, so folders from different repositories need their own.
 */
const clients = new Map<string, Promise<LanguageClient | undefined>>();

/** The folder whose server also serves library sources opened from the java-index cache. */
let libraryOwner: string | undefined;

let context: vscode.ExtensionContext;
let log: vscode.LogOutputChannel;

export function activate(ctx: vscode.ExtensionContext) {
  context = ctx;
  log = vscode.window.createOutputChannel('Java Index', { log: true });
  ctx.subscriptions.push(
    log,
    vscode.commands.registerCommand('javaIndex.restart', restart),
    vscode.commands.registerCommand('javaIndex.showOutput', () => log.show()),
    vscode.workspace.onDidOpenTextDocument(didOpen),
    vscode.workspace.onDidChangeWorkspaceFolders((e) => e.removed.forEach((f) => stop(f))),
    vscode.workspace.onDidChangeConfiguration((e) => {
      const keys = ['server', 'java.home', 'classpath', 'inlayHints', 'deprecations'];
      if (keys.some((k) => e.affectsConfiguration(`javaIndex.${k}`))) {
        restart();
      }
    }),
  );
  vscode.workspace.textDocuments.forEach(didOpen);
}

export async function deactivate() {
  await Promise.all([...vscode.workspace.workspaceFolders ?? []].map(stop));
}

function didOpen(doc: vscode.TextDocument) {
  if (vscode.languages.match(SERVED, doc) === 0) {
    return;
  }
  const folder = vscode.workspace.getWorkspaceFolder(doc.uri);
  if (folder && !clients.has(folder.uri.toString())) {
    clients.set(folder.uri.toString(), start(folder));
  }
}

/** Starts the folder's client; a failure is reported once and retried only on restart. */
async function start(folder: vscode.WorkspaceFolder): Promise<LanguageClient | undefined> {
  const key = folder.uri.toString();
  const ownsLibraries = libraryOwner === undefined;
  if (ownsLibraries) {
    libraryOwner = key;
  }
  let server;
  try {
    server = await resolveServer(context, log);
  } catch (e) {
    report(e);
    return undefined;
  }

  const root = folder.uri.fsPath.replace(/\\/g, '/');
  const selector: DocumentFilter[] = [
    { scheme: 'file', language: 'java', pattern: `${root}/**/*` },
    ...CONFIG_FILES.map((glob) => ({ scheme: 'file', pattern: `${root}/${glob}` })),
  ];
  // Library sources opened by go-to-definition live in the java-index cache, outside every
  // folder; one folder's server answers for them, so they are served exactly once.
  if (ownsLibraries) {
    selector.push({ scheme: 'file', language: 'java', pattern: `${stateDir().replace(/\\/g, '/')}/**/*` });
  }

  const serverOptions: ServerOptions = {
    command: server.command,
    args: server.args,
    options: { cwd: folder.uri.fsPath, env: server.env },
  };
  const clientOptions: LanguageClientOptions = {
    documentSelector: selector,
    workspaceFolder: folder,
    outputChannel: log,
    initializationOptions: initializationOptions(folder),
  };
  const client = new LanguageClient(
    'javaIndex',
    `Java Index (${folder.name})`,
    serverOptions,
    clientOptions,
  );
  log.info(`Starting for ${folder.uri.fsPath}: ${server.command} ${server.args.join(' ')}`);
  try {
    await client.start();
  } catch (e) {
    report(e);
    return undefined;
  }
  return client;
}

/** The server's options (README: LSP initialization options); unset ones keep its defaults. */
function initializationOptions(folder: vscode.WorkspaceFolder) {
  const config = vscode.workspace.getConfiguration('javaIndex', folder);
  const options: Record<string, unknown> = {
    inlayHints: {
      parameterNames: config.get('inlayHints.parameterNames'),
      types: config.get('inlayHints.types'),
    },
    deprecations: config.get('deprecations'),
  };
  const classpath = config.get<string[]>('classpath', []);
  if (classpath.length) {
    options.classpath = classpath;
  }
  return options;
}

async function stop(folder: vscode.WorkspaceFolder) {
  const key = folder.uri.toString();
  const client = clients.get(key);
  clients.delete(key);
  if (libraryOwner === key) {
    libraryOwner = undefined;
  }
  await (await client)?.stop();
}

async function restart() {
  await Promise.all([...vscode.workspace.workspaceFolders ?? []].map(stop));
  vscode.workspace.textDocuments.forEach(didOpen);
}

function report(e: unknown) {
  const message = e instanceof Error ? e.message : String(e);
  log.error(message);
  const help = e instanceof SetupError ? e.help : undefined;
  const actions = help ? [help.label, 'Show Output'] : ['Show Output'];
  vscode.window.showErrorMessage(`Java Index: ${message}`, ...actions).then((choice) => {
    if (help && choice === help.label) {
      vscode.env.openExternal(vscode.Uri.parse(help.url));
    } else if (choice === 'Show Output') {
      log.show();
    }
  });
}
