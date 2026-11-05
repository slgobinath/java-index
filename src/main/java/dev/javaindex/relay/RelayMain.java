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

package dev.javaindex.relay;

import dev.javaindex.daemon.Proxy;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Entry point of the native relay (GraalVM native image): only the stdio-to-daemon relay for
 * editors and agents, so the image contains none of the indexing code and starts in milliseconds.
 * The daemon itself always runs on a JVM.
 *
 * <pre>
 *   jindex-relay [lsp]              LSP over stdio
 *   jindex-relay mcp [--root DIR]   MCP over stdio
 * </pre>
 */
public final class RelayMain {
  private RelayMain() {}

  public static void main(String[] args) throws Exception {
    String command = args.length > 0 && !args[0].startsWith("-") ? args[0] : "lsp";
    List<String> rest = Arrays.asList(args);
    switch (command) {
      case "lsp" -> Proxy.run();
      case "mcp" -> {
        int i = rest.indexOf("--root");
        String root = i >= 0 && i + 1 < rest.size() ? rest.get(i + 1) : ".";
        Proxy.runMcp(Path.of(root).toAbsolutePath().normalize());
      }
      default -> {
        System.err.println(
            "jindex-relay only relays (lsp, mcp); run '" + command + "' with the jar instead");
        System.exit(2);
      }
    }
    System.exit(0);
  }
}
