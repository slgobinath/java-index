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

package dev.javaindex.daemon;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.javaindex.lsp.JavaIndexServer;
import dev.javaindex.mcp.McpServer;
import dev.javaindex.project.Project;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;

/**
 * The per-project daemon: accepts LSP connections on a Unix domain socket, one per editor window /
 * worktree, all served from a single shared {@link Project}. Exits after sitting idle with no
 * connections.
 */
public final class Daemon {
  private static final Logger LOG = Logger.getLogger(Daemon.class.getName());

  private final Path socket;
  private final Duration idleTimeout;
  private final Project project;
  private final AtomicInteger connections = new AtomicInteger();
  private final AtomicLong lastActivity = new AtomicLong(System.nanoTime());
  private ServerSocketChannel server;
  private volatile boolean compactedSinceActivity;

  public Daemon(Path socket, Duration idleTimeout, Path stateDir) {
    this.socket = socket;
    this.idleTimeout = idleTimeout;
    this.project = new Project(stateDir);
  }

  public Project project() {
    return project;
  }

  /** Binds the socket. Returns false if another live daemon already owns it. */
  public boolean bind() throws IOException {
    if (Files.exists(socket)) {
      try (SocketChannel probe = SocketChannel.open(UnixDomainSocketAddress.of(socket))) {
        LOG.info("another daemon is already serving " + socket);
        return false;
      } catch (IOException stale) {
        Files.deleteIfExists(socket);
      }
    }
    server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
    server.bind(UnixDomainSocketAddress.of(socket));
    return true;
  }

  /** Accepts connections until idle for {@link #idleTimeout}, then cleans up. */
  public void serve() throws IOException {
    ScheduledExecutorService reaper =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "java-index-reaper");
              t.setDaemon(true);
              return t;
            });
    reaper.scheduleWithFixedDelay(this::exitIfIdle, 30, 30, TimeUnit.SECONDS);
    Runtime.getRuntime().addShutdownHook(new Thread(this::cleanup));
    LOG.info("daemon listening on " + socket);
    while (server.isOpen()) {
      SocketChannel ch;
      try {
        ch = server.accept();
      } catch (IOException e) {
        if (!server.isOpen()) {
          break;
        }
        throw e;
      }
      connections.incrementAndGet();
      touch();
      Thread.ofPlatform().daemon().name("java-index-conn").start(() -> handle(ch));
    }
  }

  /**
   * Serves one connection. Editors speak LSP (the stream starts with "Content-Length:"); agent
   * relays speak MCP and open with a one-line JSON hello naming the worktree root.
   */
  private void handle(SocketChannel ch) {
    try {
      PushbackInputStream in = new PushbackInputStream(ChannelStreams.in(ch), 1);
      OutputStream out = ChannelStreams.out(ch);
      int first = in.read();
      if (first == '{') {
        serveMcp(in, out);
      } else if (first >= 0) {
        in.unread(first);
        serveLsp(ch, in, out);
      }
    } catch (Exception e) {
      LOG.log(Level.FINE, "connection ended", e);
    } finally {
      closeQuietly(ch);
      connections.decrementAndGet();
      touch();
      LOG.info("session closed; " + connections.get() + " remaining\n" + project.stats());
    }
  }

  private void serveLsp(SocketChannel ch, InputStream in, OutputStream out) throws Exception {
    JavaIndexServer session = new JavaIndexServer(project, () -> closeQuietly(ch));
    session.onActivity(this::touch);
    try {
      Launcher<LanguageClient> launcher =
          new LSPLauncher.Builder<LanguageClient>()
              .setLocalService(session)
              .setRemoteInterface(LanguageClient.class)
              .setInput(in)
              .setOutput(out)
              .setExecutorService(project.background())
              .create();
      session.connect(launcher.getRemoteProxy());
      launcher.startListening().get();
    } finally {
      session.dispose();
    }
  }

  private void serveMcp(InputStream in, OutputStream out) throws IOException {
    ByteArrayOutputStream hello = new ByteArrayOutputStream();
    hello.write('{');
    int b;
    while ((b = in.read()) >= 0 && b != '\n') {
      hello.write(b);
    }
    JsonObject greeting = JsonParser.parseString(hello.toString(StandardCharsets.UTF_8)).getAsJsonObject();
    Path root = Path.of(greeting.get("root").getAsString());
    LOG.info("agent (MCP) session for " + root);
    new McpServer(project, root, this::touch).serve(in, out);
  }

  private void touch() {
    lastActivity.set(System.nanoTime());
    compactedSinceActivity = false;
  }

  private void exitIfIdle() {
    long idleNanos = System.nanoTime() - lastActivity.get();
    if (idleNanos > TimeUnit.MINUTES.toNanos(1) && !compactedSinceActivity) {
      // Quiet for a minute: compact once so the (Serial GC) heap shrinks back for the OS.
      compactedSinceActivity = true;
      System.gc();
    }
    if (connections.get() == 0
        && idleNanos > idleTimeout.toNanos()) {
      LOG.info("idle for " + idleTimeout.toMinutes() + " min; exiting");
      System.exit(0);
    }
  }

  private void cleanup() {
    try {
      if (server != null) {
        server.close();
      }
      Files.deleteIfExists(socket);
    } catch (IOException e) {
      // best effort
    }
  }

  private static void closeQuietly(SocketChannel ch) {
    try {
      ch.close();
    } catch (IOException e) {
      // ignore
    }
  }
}
