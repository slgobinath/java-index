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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What the editor actually launches: a tiny stdio-to-socket relay. It peeks at the LSP
 * {@code initialize} request to learn the workspace root, maps it to its project (all worktrees of a
 * repository map to the same one), starts that project's daemon if needed, then just shuttles bytes.
 */
public final class Proxy {
  private Proxy() {}

  /** Editor mode: LSP over stdio, routed by the workspace root in the initialize request. */
  public static void run() throws IOException, InterruptedException {
    InputStream stdin = System.in;
    byte[] first = readMessage(stdin);
    if (first == null) {
      return;
    }
    relay(workspaceRoot(first), first, stdin);
  }

  /**
   * Agent mode: MCP over stdio (newline-delimited JSON-RPC) for the worktree at {@code root}. The
   * daemon recognizes the connection by the hello line sent ahead of the agent's messages.
   */
  public static void runMcp(Path root) throws IOException, InterruptedException {
    JsonObject hello = new JsonObject();
    hello.addProperty("jindex", "mcp");
    hello.addProperty("root", root.toAbsolutePath().normalize().toString());
    relay(root, (hello + "\n").getBytes(StandardCharsets.UTF_8), System.in);
  }

  private static void relay(Path root, byte[] first, InputStream stdin)
      throws IOException, InterruptedException {
    OutputStream stdout = new FileOutputStream(FileDescriptor.out);
    String key = ProjectKey.of(root);
    Path state = ProjectKey.stateDir();
    Path socket = ProjectKey.socketPath(state, key);

    try (SocketChannel ch = connectOrStart(socket, key, state)) {
      writeFully(ch, first);
      Thread upstream =
          Thread.ofPlatform()
              .daemon()
              .start(
                  () -> {
                    byte[] buf = new byte[64 * 1024];
                    try {
                      int n;
                      while ((n = stdin.read(buf)) >= 0) {
                        writeFully(ch, buf, n);
                      }
                      ch.shutdownOutput();
                    } catch (IOException e) {
                      // client or daemon went away
                    }
                  });
      ByteBuffer buf = ByteBuffer.allocate(64 * 1024);
      while (ch.read(buf) >= 0) {
        try {
          stdout.write(buf.array(), 0, buf.position());
          stdout.flush();
        } catch (IOException e) {
          break; // the client closed its end; nothing left to relay to
        }
        buf.clear();
      }
      upstream.interrupt();
    }
  }

  private static SocketChannel connectOrStart(Path socket, String key, Path state)
      throws IOException, InterruptedException {
    SocketChannel ch = tryConnect(socket);
    if (ch != null) {
      return ch;
    }
    // Serialize startup across concurrently launched editors so only one daemon is spawned.
    try (FileChannel lockFile =
            FileChannel.open(
                state.resolve(key + ".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock = lockFile.lock()) {
      ch = tryConnect(socket);
      if (ch != null) {
        return ch;
      }
      startDaemon(socket, state.resolve(key + ".log"));
      long deadline = System.nanoTime() + 60_000_000_000L;
      while (System.nanoTime() < deadline) {
        ch = tryConnect(socket);
        if (ch != null) {
          return ch;
        }
        Thread.sleep(50);
      }
    }
    throw new IOException("daemon did not start; see " + state.resolve(key + ".log"));
  }

  private static SocketChannel tryConnect(Path socket) {
    try {
      return SocketChannel.open(UnixDomainSocketAddress.of(socket));
    } catch (IOException e) {
      return null;
    }
  }

  private static void startDaemon(Path socket, Path log) throws IOException {
    List<String> command = new ArrayList<>();
    command.add(daemonJava());
    // Small, shrinkable heap: the daemon is long-lived and mostly idle.
    command.add("-XX:+UseSerialGC");
    command.add("-Xms16m");
    command.add("-XX:MinHeapFreeRatio=10");
    command.add("-XX:MaxHeapFreeRatio=20");
    command.add("-XX:+ExitOnOutOfMemoryError");
    String opts = System.getenv("JINDEX_DAEMON_OPTS");
    for (String o : (opts == null || opts.isBlank() ? "-Xmx1g" : opts).trim().split("\\s+")) {
      command.add(o);
    }
    command.add("-cp");
    command.add(daemonClasspath());
    command.add("dev.javaindex.Main");
    command.add("daemon");
    command.add("--socket");
    command.add(socket.toString());
    String idle = System.getenv("JINDEX_IDLE_MINUTES");
    if (idle != null && !idle.isBlank()) {
      command.add("--idle-minutes");
      command.add(idle.strip());
    }
    boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    ProcessBuilder pb;
    if (windows) {
      pb = new ProcessBuilder(command);
      pb.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
    } else {
      // Background through sh so the daemon is re-parented and outlives this editor's process
      // tree (editors commonly kill the whole tree of the server they launched).
      List<String> sh = new ArrayList<>(List.of("/bin/sh", "-c", "nohup \"$@\" </dev/null >>\"$0\" 2>&1 &", log.toString()));
      sh.addAll(command);
      pb = new ProcessBuilder(sh);
      pb.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
    }
    Process p = pb.start();
    if (!windows) {
      try {
        p.waitFor();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /** True when running as a GraalVM native image (the native relay). */
  static boolean isNativeImage() {
    return System.getProperty("org.graalvm.nativeimage.imagecode") != null;
  }

  /**
   * The JVM that runs the daemon. On a JVM, the same one as this relay; from the native relay,
   * {@code $JINDEX_JAVA}, then {@code $JAVA_HOME/bin/java}, then {@code java} on the PATH.
   */
  private static String daemonJava() {
    if (!isNativeImage()) {
      return ProcessHandle.current()
          .info()
          .command()
          .orElse(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    }
    String configured = System.getenv("JINDEX_JAVA");
    if (configured != null && !configured.isBlank()) {
      return configured;
    }
    String javaHome = System.getenv("JAVA_HOME");
    if (javaHome != null && Files.isExecutable(Path.of(javaHome, "bin", "java"))) {
      return Path.of(javaHome, "bin", "java").toString();
    }
    return "java";
  }

  /**
   * The daemon's classpath. On a JVM, this relay's own; the native relay has none, so it uses
   * {@code $JINDEX_JAR} or finds java-index.jar next to (or in ../target, ../lib of) its binary.
   */
  private static String daemonClasspath() throws IOException {
    if (!isNativeImage()) {
      return System.getProperty("java.class.path");
    }
    String configured = System.getenv("JINDEX_JAR");
    if (configured != null && !configured.isBlank()) {
      return configured;
    }
    Path exe = ProcessHandle.current().info().command().map(Path::of).orElse(null);
    if (exe != null) {
      Path dir = exe.toRealPath().getParent();
      for (Path candidate :
          List.of(
              dir.resolve("java-index.jar"),
              dir.resolve("../target/java-index.jar"),
              dir.resolve("../lib/java-index.jar"))) {
        if (Files.isRegularFile(candidate)) {
          return candidate.normalize().toString();
        }
      }
    }
    throw new IOException("cannot find java-index.jar for the daemon; set JINDEX_JAR");
  }

  /** Reads one LSP message (headers + body) verbatim. */
  static byte[] readMessage(InputStream in) throws IOException {
    ByteArrayOutputStream raw = new ByteArrayOutputStream();
    int contentLength = -1;
    StringBuilder line = new StringBuilder();
    while (true) {
      int b = in.read();
      if (b < 0) {
        return null;
      }
      raw.write(b);
      if (b == '\n') {
        String header = line.toString().strip();
        line.setLength(0);
        if (header.isEmpty()) {
          break;
        }
        int colon = header.indexOf(':');
        if (colon > 0 && header.substring(0, colon).equalsIgnoreCase("Content-Length")) {
          contentLength = Integer.parseInt(header.substring(colon + 1).strip());
        }
      } else if (b != '\r') {
        line.append((char) b);
      }
    }
    if (contentLength < 0) {
      throw new IOException("missing Content-Length");
    }
    byte[] body = in.readNBytes(contentLength);
    raw.write(body);
    return raw.toByteArray();
  }

  /** The workspace root named by an initialize request, or the working directory. */
  static Path workspaceRoot(byte[] message) {
    Path cwd = Path.of("").toAbsolutePath();
    try {
      String text = new String(message, StandardCharsets.UTF_8);
      JsonObject msg = JsonParser.parseString(text.substring(text.indexOf('{'))).getAsJsonObject();
      JsonObject params = msg.getAsJsonObject("params");
      if (params == null) {
        return cwd;
      }
      JsonElement folders = params.get("workspaceFolders");
      if (folders != null && folders.isJsonArray() && !((JsonArray) folders).isEmpty()) {
        return Path.of(URI.create(((JsonArray) folders).get(0).getAsJsonObject().get("uri").getAsString()));
      }
      JsonElement rootUri = params.get("rootUri");
      if (rootUri != null && !rootUri.isJsonNull()) {
        return Path.of(URI.create(rootUri.getAsString()));
      }
      JsonElement rootPath = params.get("rootPath");
      if (rootPath != null && !rootPath.isJsonNull()) {
        return Path.of(rootPath.getAsString());
      }
    } catch (RuntimeException e) {
      // fall through
    }
    return cwd;
  }

  private static void writeFully(SocketChannel ch, byte[] data) throws IOException {
    writeFully(ch, data, data.length);
  }

  private static void writeFully(SocketChannel ch, byte[] data, int len) throws IOException {
    ByteBuffer buf = ByteBuffer.wrap(data, 0, len);
    while (buf.hasRemaining()) {
      ch.write(buf);
    }
  }
}
