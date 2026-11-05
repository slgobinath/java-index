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

package dev.javaindex.lsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.javaindex.project.Project;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.eclipse.lsp4j.DiagnosticTag;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InlayHint;
import org.eclipse.lsp4j.InlayHintParams;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Features aimed at reading and reviewing code: type hints, richer hovers, deprecations. */
class ReviewFeaturesTest {
  @TempDir Path tmp;

  private Path root;
  private JavaIndexServer server;
  private final List<PublishDiagnosticsParams> published = new CopyOnWriteArrayList<>();

  private static final String SHAPE =
      """
      package demo;

      public interface Shape {
        double area();
      }
      """;
  private static final String CIRCLE =
      """
      package demo;

      public class Circle implements Shape {
        public double area() { return 3.14; }

        /**
         * Old name.
         *
         * @deprecated use {@link #area()} instead
         */
        @Deprecated
        public double size() { return area(); }
      }
      """;
  private static final String SQUARE =
      """
      package demo;

      public class Square implements Shape {
        public double area() { return 1; }
      }
      """;
  private static final String APP =
      """
      package demo;

      import java.util.Date;
      import java.util.List;

      public class App {
        void run(List<Shape> shapes, Circle c) {
          shapes.forEach(s -> s.area());
          List<String> names = shapes.stream()
              .map(s -> s.toString())
              .toList();
          c.size();
          int year = new Date().getYear();
          c.area();
        }
      }
      """;

  @BeforeEach
  void setUp() throws Exception {
    root = tmp.resolve("proj");
    write("src/demo/Shape.java", SHAPE);
    write("src/demo/Circle.java", CIRCLE);
    write("src/demo/Square.java", SQUARE);
    write("src/demo/App.java", APP);
    server = new JavaIndexServer(new Project(Files.createDirectories(tmp.resolve("state"))), () -> {});
    server.connect(new CapturingClient());
    InitializeParams init = new InitializeParams();
    init.setRootUri(root.toUri().toString());
    server.initialize(init).get(30, TimeUnit.SECONDS);
  }

  @AfterEach
  void tearDown() {
    server.dispose();
  }

  @Test
  void typeHintsForLambdasAndChains() throws Exception {
    List<InlayHint> hints =
        server.inlayHint(new InlayHintParams(doc("App"), new Range(new Position(0, 0), new Position(30, 0))))
            .get(30, TimeUnit.SECONDS);
    List<String> labels = hints.stream().map(h -> h.getLabel().getLeft()).toList();
    // s -> ... in forEach(Consumer<? super Shape>) and in map(Function<? super Shape, ...>)
    assertEquals(2, labels.stream().filter(": Shape"::equals).count(), labels.toString());
    // the multi-line chain: the type at the end of each continued line
    assertTrue(labels.contains("Stream<Shape>"), labels.toString());
    assertTrue(labels.contains("Stream<String>"), labels.toString());
    InlayHint chain = hints.stream().filter(h -> h.getLabel().getLeft().equals("Stream<Shape>")).findFirst().get();
    assertEquals(8, chain.getPosition().getLine());
    assertTrue(chain.getPaddingLeft());
  }

  @Test
  void hoverShowsOverridesSubtypesUsagesAndDeprecation() throws Exception {
    String iface = hover("Shape", SHAPE, "Shape {");
    assertTrue(iface.contains("Implemented by 2 types"), iface);
    assertTrue(iface.contains("usages"), iface);

    String abstractMethod = hover("Shape", SHAPE, "area();");
    assertTrue(abstractMethod.contains("Overridden in 2 subtypes"), abstractMethod);

    String impl = hover("Circle", CIRCLE, "area() {");
    assertTrue(impl.contains("Implements `Shape.area`"), impl);

    String deprecated = hover("App", APP, "size();");
    assertTrue(deprecated.startsWith("**Deprecated**: use `area()` instead"), deprecated);
    assertTrue(deprecated.contains("1 usage"), deprecated);
  }

  @Test
  void deprecatedUsesAreMarked() throws Exception {
    String uri = root.resolve("src/demo/App.java").toUri().toString();
    server.didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(uri, "java", 1, APP)));
    PublishDiagnosticsParams params = null;
    for (int i = 0; i < 100 && params == null; i++) {
      params = published.stream().filter(p -> p.getUri().equals(uri)).findFirst().orElse(null);
      Thread.sleep(50);
    }
    assertTrue(params != null, "no diagnostics published");
    List<String> messages = params.getDiagnostics().stream().map(d -> d.getMessage()).toList();
    assertEquals(2, messages.size(), messages.toString());
    assertTrue(messages.get(0).startsWith("`Circle.size` is deprecated: use `area()` instead"), messages.toString());
    assertTrue(messages.get(1).startsWith("`Date.getYear` is deprecated"), messages.toString());
    assertTrue(params.getDiagnostics().stream().allMatch(d -> d.getTags().contains(DiagnosticTag.Deprecated)));
    assertEquals(11, params.getDiagnostics().get(0).getRange().getStart().getLine()); // c.size()
  }

  // ---------------------------------------------------------------------------------------------

  private String hover(String cls, String text, String needle) throws Exception {
    int offset = text.indexOf(needle);
    String before = text.substring(0, offset);
    Position pos = new Position((int) before.chars().filter(c -> c == '\n').count(), offset - (before.lastIndexOf('\n') + 1));
    return server.hover(new HoverParams(doc(cls), pos)).get(30, TimeUnit.SECONDS).getContents().getRight().getValue();
  }

  private TextDocumentIdentifier doc(String cls) {
    return new TextDocumentIdentifier(root.resolve("src/demo/" + cls + ".java").toUri().toString());
  }

  private void write(String rel, String text) throws Exception {
    Path f = root.resolve(rel);
    Files.createDirectories(f.getParent());
    Files.writeString(f, text);
  }

  private final class CapturingClient implements LanguageClient {
    @Override
    public void telemetryEvent(Object object) {}

    @Override
    public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {
      published.add(diagnostics);
    }

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
