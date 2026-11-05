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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.javaindex.index.FileSummary;
import dev.javaindex.project.Worktree;
import java.io.IOException;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.ImplementationParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ReferenceContext;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.WorkspaceSymbolParams;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DaemonEndToEndTest {
  @TempDir Path tmp;

  private Path main;
  private Path feature;
  private Daemon daemon;
  private Path socket;

  private static final String GREETER =
      """
      package demo;

      public class Greeter {
        public String greet(String name) { return "hi " + name; }
      }
      """;

  private static final String APP =
      """
      package demo;

      import java.util.List;

      public class App {
        public static void main(String[] args) {
          Greeter g = new Greeter();
          String s = g.greet("x");
          List<String> names = List.of(s);
          names.size();
        }
      }
      """;

  @BeforeEach
  void setUp() throws Exception {
    main = Files.createDirectories(tmp.resolve("repo"));
    write(main.resolve("src/demo/Greeter.java"), GREETER);
    write(main.resolve("src/demo/App.java"), APP);
    for (int i = 0; i < 20; i++) {
      write(
          main.resolve("src/demo/util/Util" + i + ".java"),
          "package demo.util;\npublic class Util" + i + " { public int value() { return " + i + "; } }\n");
    }
    git(main, "init", "-q", "-b", "main");
    git(main, "add", ".");
    git(main, "-c", "user.email=t@example.com", "-c", "user.name=t", "commit", "-q", "-m", "init");
    feature = tmp.resolve("feature");
    git(main, "worktree", "add", "-q", "-b", "feature", feature.toString());
    // On the feature branch, Greeter gains a line so greet() moves down by one.
    write(feature.resolve("src/demo/Greeter.java"), GREETER.replace("public class Greeter {\n", "public class Greeter {\n  private int calls;\n"));
    // ...and adds a subclass that only exists in the feature worktree.
    write(
        feature.resolve("src/demo/LoudGreeter.java"),
        "package demo;\n\npublic class LoudGreeter extends Greeter {\n"
            + "  @Override public String greet(String name) { return \"HI \" + name; }\n}\n");

    assertEquals(ProjectKey.of(main), ProjectKey.of(feature), "worktrees must share a project key");

    socket = tmp.resolve("d.sock");
    daemon = new Daemon(socket, Duration.ofMinutes(5), Files.createDirectories(tmp.resolve("state")));
    assertTrue(daemon.bind());
    Thread.ofPlatform().daemon().start(() -> {
      try {
        daemon.serve();
      } catch (IOException e) {
        // closed
      }
    });
  }

  @AfterEach
  void tearDown() throws IOException {
    Files.deleteIfExists(socket);
  }

  @Test
  void worktreesShareUnchangedCodeButNavigateTheirOwnChanges() throws Exception {
    Client a = connect(main);
    Client b = connect(feature);

    String appA = main.resolve("src/demo/App.java").toUri().toString();
    String appB = feature.resolve("src/demo/App.java").toUri().toString();

    // cmd+click on greet( in each worktree lands in that worktree's Greeter, at its own line.
    Position greet = position(APP, "greet(\"x\")");
    Location inA = definition(a, appA, greet).get(0);
    Location inB = definition(b, appB, greet).get(0);
    assertEquals(main.resolve("src/demo/Greeter.java").toUri().toString(), inA.getUri());
    assertEquals(feature.resolve("src/demo/Greeter.java").toUri().toString(), inB.getUri());
    assertEquals(3, inA.getRange().getStart().getLine());
    assertEquals(4, inB.getRange().getStart().getLine());

    // Find references: from a call site, across files, declaration included or not.
    String greeterA = main.resolve("src/demo/Greeter.java").toUri().toString();
    List<? extends Location> refs = references(a, appA, greet, true);
    assertEquals(List.of(appA + ":7", greeterA + ":3"), describe(refs));
    assertEquals(List.of(appA + ":7"), describe(references(a, appA, greet, false)));
    // A class: its declaration, the `Greeter g` type usage and the `new Greeter()` call.
    List<? extends Location> classRefs = references(a, appA, position(APP, "Greeter g"), true);
    assertEquals(List.of(appA + ":6", appA + ":6", greeterA + ":2"), describe(classRefs));
    // A JDK method: only our call to List.size().
    assertEquals(List.of(appA + ":9"), describe(references(a, appA, position(APP, "size()"), true)));

    // Unchanged files are parsed once and shared; the changed one is per worktree.
    Path mainReal = main.toRealPath();
    Path featureReal = feature.toRealPath();
    List<Worktree> trees = daemon.project().worktrees();
    assertEquals(2, trees.size());
    Worktree wa = trees.stream().filter(w -> w.root().equals(mainReal)).findFirst().get();
    Worktree wb = trees.stream().filter(w -> w.root().equals(featureReal)).findFirst().get();
    for (int i = 0; i < 20; i++) {
      String rel = "src/demo/util/Util" + i + ".java";
      assertSame(wa.index().file(rel), wb.index().file(rel));
    }
    FileSummary greeterSummaryA = wa.index().file("src/demo/Greeter.java");
    FileSummary greeterSummaryB = wb.index().file("src/demo/Greeter.java");
    assertTrue(greeterSummaryA != greeterSummaryB);
    assertEquals(0, wa.index().deltaSize());
    assertEquals(2, wb.index().deltaSize()); // the changed Greeter and the new LoudGreeter

    // Implementations and hover are per worktree too: LoudGreeter exists only in `feature`.
    String greeterMain = main.resolve("src/demo/Greeter.java").toUri().toString();
    String greeterFeature = feature.resolve("src/demo/Greeter.java").toUri().toString();
    assertTrue(implementations(a, greeterMain, position(GREETER, "Greeter {")).isEmpty());
    assertEquals(
        List.of(feature.resolve("src/demo/LoudGreeter.java").toUri().toString()),
        implementations(b, greeterFeature, position(GREETER, "Greeter {")).stream()
            .map(Location::getUri)
            .toList());
    // The overriding method is found from the base method, in that worktree only.
    String featureGreeter = Files.readString(feature.resolve("src/demo/Greeter.java"));
    assertEquals(
        1, implementations(b, greeterFeature, position(featureGreeter, "greet(String")).size());
    assertTrue(implementations(a, greeterMain, position(GREETER, "greet(String")).isEmpty());

    // An unsaved edit in B (rename greet -> welcome) is visible to B only.
    String greeterBUri = feature.resolve("src/demo/Greeter.java").toUri().toString();
    String greeterBText = Files.readString(feature.resolve("src/demo/Greeter.java"));
    b.server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(greeterBUri, "java", 1, greeterBText)));
    String renamed = greeterBText.replace("String greet(", "String welcome(");
    TextDocumentContentChangeEvent full = new TextDocumentContentChangeEvent(renamed);
    b.server.getTextDocumentService().didChange(new DidChangeTextDocumentParams(new VersionedTextDocumentIdentifier(greeterBUri, 2), List.of(full)));
    String appWithWelcome = APP.replace("g.greet(", "g.welcome(");
    b.server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(appB, "java", 1, appWithWelcome)));
    Location welcome = definition(b, appB, position(appWithWelcome, "welcome(\"x\")")).get(0);
    assertEquals(greeterBUri, welcome.getUri());
    assertEquals(4, welcome.getRange().getStart().getLine());
    assertEquals(3, definition(a, appA, greet).get(0).getRange().getStart().getLine());

    // JDK navigation: List.size() resolves into the JDK's sources (or a generated stub).
    Location size = definition(a, appA, position(APP, "size()")).get(0);
    assertTrue(size.getUri().endsWith("java/util/List.java"), size.getUri());

    // Workspace symbols search sources first; document symbols outline the file.
    var symbols = a.server.getWorkspaceService().symbol(new WorkspaceSymbolParams("Greet")).get(30, TimeUnit.SECONDS);
    String firstSymbol =
        symbols.isLeft() ? symbols.getLeft().get(0).getName() : symbols.getRight().get(0).getName();
    assertEquals("Greeter", firstSymbol);
    var outline = a.server.getTextDocumentService().documentSymbol(new DocumentSymbolParams(new TextDocumentIdentifier(appA))).get(30, TimeUnit.SECONDS);
    assertNotNull(outline);
    assertEquals("App", outline.get(0).isRight() ? outline.get(0).getRight().getName() : outline.get(0).getLeft().getName());

    // Closing one session leaves the other's worktree alive.
    a.close();
    for (int i = 0; i < 100 && daemon.project().worktrees().size() > 1; i++) {
      Thread.sleep(50);
    }
    assertEquals(1, daemon.project().worktrees().size());
    b.close();
  }

  @Test
  void agentsAndEditorsShareTheDaemon() throws Exception {
    Client editor = connect(main);
    try (SocketChannel ch = SocketChannel.open(UnixDomainSocketAddress.of(socket))) {
      var out = ChannelStreams.out(ch);
      var in = new java.io.BufferedReader(new java.io.InputStreamReader(ChannelStreams.in(ch), java.nio.charset.StandardCharsets.UTF_8));
      String hello = "{\"jindex\":\"mcp\",\"root\":\"" + main.toString().replace("\\", "\\\\") + "\"}\n";
      out.write(hello.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      out.write("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\"}}\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
      assertTrue(in.readLine().contains("\"name\":\"java-index\""));
      out.write("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"java_callers\",\"arguments\":{\"symbol\":\"demo.Greeter#greet\"}}}\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
      String response = in.readLine();
      assertTrue(response.contains("demo.App#main(String[])"), response);
      // The agent's session reuses the editor's worktree: one index, not two.
      assertEquals(1, daemon.project().worktrees().size());
    }
    editor.close();
  }

  // ---------------------------------------------------------------------------------------------

  private record Client(LanguageServer server, SocketChannel channel) {
    void close() throws Exception {
      server.shutdown().get(10, TimeUnit.SECONDS);
      server.exit();
      channel.close();
    }
  }

  private Client connect(Path root) throws Exception {
    SocketChannel ch = SocketChannel.open(UnixDomainSocketAddress.of(socket));
    var launcher =
        LSPLauncher.createClientLauncher(
            new NoopClient(), ChannelStreams.in(ch), ChannelStreams.out(ch));
    launcher.startListening();
    LanguageServer server = launcher.getRemoteProxy();
    InitializeParams init = new InitializeParams();
    init.setRootUri(root.toUri().toString());
    server.initialize(init).get(30, TimeUnit.SECONDS);
    server.initialized(new InitializedParams());
    return new Client(server, ch);
  }

  private static List<? extends Location> definition(Client c, String uri, Position pos) throws Exception {
    var result =
        c.server.getTextDocumentService()
            .definition(new DefinitionParams(new TextDocumentIdentifier(uri), pos))
            .get(60, TimeUnit.SECONDS);
    assertTrue(!result.getLeft().isEmpty(), "no definition at " + pos);
    return result.getLeft();
  }

  private static List<? extends Location> references(
      Client c, String uri, Position pos, boolean includeDeclaration) throws Exception {
    ReferenceParams params =
        new ReferenceParams(
            new TextDocumentIdentifier(uri), pos, new ReferenceContext(includeDeclaration));
    return c.server.getTextDocumentService().references(params).get(60, TimeUnit.SECONDS);
  }

  private static List<? extends Location> implementations(Client c, String uri, Position pos)
      throws Exception {
    return c.server.getTextDocumentService()
        .implementation(new ImplementationParams(new TextDocumentIdentifier(uri), pos))
        .get(60, TimeUnit.SECONDS)
        .getLeft();
  }

  /** "uri:line" (0-based line) for each location. */
  private static List<String> describe(List<? extends Location> locations) {
    return locations.stream().map(l -> l.getUri() + ":" + l.getRange().getStart().getLine()).toList();
  }

  private static Position position(String text, String needle) {
    int offset = text.indexOf(needle);
    assertTrue(offset >= 0, needle);
    String before = text.substring(0, offset);
    int line = (int) before.chars().filter(ch -> ch == '\n').count();
    int col = offset - (before.lastIndexOf('\n') + 1);
    return new Position(line, col + 1);
  }

  private static void write(Path file, String text) throws IOException {
    Files.createDirectories(file.getParent());
    Files.writeString(file, text);
  }

  private static void git(Path dir, String... args) throws Exception {
    List<String> cmd = new java.util.ArrayList<>(List.of("git"));
    cmd.addAll(List.of(args));
    Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
    String out = new String(p.getInputStream().readAllBytes());
    assertEquals(0, p.waitFor(), out);
  }

  private static final class NoopClient implements LanguageClient {
    @Override
    public void telemetryEvent(Object object) {}

    @Override
    public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {}

    @Override
    public void showMessage(MessageParams messageParams) {}

    @Override
    public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams requestParams) {
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public void logMessage(MessageParams message) {}
  }
}
