/*
 * Copyright 2026 Gobinath Loganathan
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.javaindex;

import dev.javaindex.daemon.Daemon;
import dev.javaindex.daemon.ProjectKey;
import dev.javaindex.daemon.Proxy;
import dev.javaindex.lsp.JavaIndexServer;
import dev.javaindex.mcp.McpServer;
import dev.javaindex.project.Project;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;

/**
 * Entry point.
 *
 * <pre>
 *   jindex [lsp]                  LSP over stdio, backed by the repository's shared daemon
 *   jindex mcp [--root DIR]       MCP over stdio for agents (root defaults to the working dir)
 *   jindex lsp|mcp --no-daemon    serve in this process instead (no sharing)
 *   jindex daemon --socket PATH [--idle-minutes N]
 * </pre>
 */
public final class Main {
  private Main() {}

  public static void main(String[] args) throws Exception {
    String command = args.length > 0 && !args[0].startsWith("-") ? args[0] : "lsp";
    List<String> rest = Arrays.asList(args).subList(args.length > 0 && !args[0].startsWith("-") ? 1 : 0, args.length);
    boolean noDaemon = rest.contains("--no-daemon");
    switch (command) {
      case "daemon" -> daemon(rest);
      case "mcp" -> {
        Path root = Path.of(option(rest, "--root", ".")).toAbsolutePath().normalize();
        if (noDaemon) {
          new McpServer(new Project(ProjectKey.stateDir()), root, () -> {}).serve(System.in, System.out);
        } else {
          Proxy.runMcp(root);
        }
      }
      case "lsp" -> {
        if (noDaemon) {
          Project project = new Project(ProjectKey.stateDir());
          JavaIndexServer server = new JavaIndexServer(project, () -> System.exit(0));
          Launcher<LanguageClient> launcher = LSPLauncher.createServerLauncher(server, System.in, System.out);
          server.connect(launcher.getRemoteProxy());
          launcher.startListening().get();
        } else {
          Proxy.run();
        }
      }
      default -> {
        System.err.println("usage: jindex [lsp|mcp|daemon] ...  (unknown command: " + command + ")");
        System.exit(2);
      }
    }
    System.exit(0);
  }

  private static void daemon(List<String> args) throws Exception {
    Path socket = Path.of(option(args, "--socket", null));
    long idleMinutes = Long.parseLong(option(args, "--idle-minutes", "15"));
    Daemon daemon = new Daemon(socket, Duration.ofMinutes(idleMinutes), ProjectKey.stateDir());
    if (daemon.bind()) {
      daemon.serve();
    }
  }

  private static String option(List<String> args, String name, String fallback) {
    int i = args.indexOf(name);
    if (i >= 0 && i + 1 < args.size()) {
      return args.get(i + 1);
    }
    if (fallback == null) {
      System.err.println(name + " is required");
      System.exit(2);
    }
    return fallback;
  }
}
