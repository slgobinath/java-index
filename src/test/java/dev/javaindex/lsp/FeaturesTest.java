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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.javaindex.project.Project;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.eclipse.lsp4j.CallHierarchyIncomingCall;
import org.eclipse.lsp4j.CallHierarchyIncomingCallsParams;
import org.eclipse.lsp4j.CallHierarchyItem;
import org.eclipse.lsp4j.CallHierarchyOutgoingCall;
import org.eclipse.lsp4j.CallHierarchyOutgoingCallsParams;
import org.eclipse.lsp4j.CallHierarchyPrepareParams;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionContext;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.DocumentHighlight;
import org.eclipse.lsp4j.DocumentHighlightKind;
import org.eclipse.lsp4j.DocumentHighlightParams;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.ImplementationParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InlayHint;
import org.eclipse.lsp4j.InlayHintParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SignatureHelp;
import org.eclipse.lsp4j.SignatureHelpParams;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TypeDefinitionParams;
import org.eclipse.lsp4j.TypeHierarchyItem;
import org.eclipse.lsp4j.TypeHierarchyPrepareParams;
import org.eclipse.lsp4j.TypeHierarchySubtypesParams;
import org.eclipse.lsp4j.TypeHierarchySupertypesParams;
import org.eclipse.lsp4j.WorkspaceSymbolParams;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FeaturesTest {
  @TempDir Path tmp;

  private JavaIndexServer server;
  private Path root;

  private static final String SHAPE =
      """
      package demo;

      /** A shape. */
      public interface Shape {
        /**
         * Area of the shape, in {@code units}.
         *
         * @return the area
         */
        double area();
      }
      """;
  private static final String CIRCLE =
      """
      package demo;

      public class Circle implements Shape {
        private final double r;

        public Circle(double radius) {
          this.r = radius;
        }

        @Override
        public double area() {
          return Math.PI * r * r;
        }
      }
      """;
  private static final String SQUARE =
      """
      package demo;

      public class Square implements Shape {
        private final double side;

        public Square(double side) {
          this.side = side;
        }

        public double area() {
          return side * side;
        }
      }
      """;
  private static final String BIG =
      """
      package demo;

      public class Big extends Circle {
        public Big() {
          super(10);
        }
      }
      """;
  private static final String APP =
      """
      package demo;

      import java.util.List;
      import java.util.Map;

      public class App {
        static double total(List<Shape> shapes) {
          double sum = 0;
          for (var s : shapes) {
            sum += s.area();
          }
          return sum;
        }

        void run() {
          Circle c = new Circle(2.0);
          total(List.of(c, new Square(3)));
          String text = String.valueOf(42);
          c.area();
          Optional<String> missing = null;
        }
      }
      """;

  @BeforeEach
  void setUp() throws Exception {
    root = tmp.resolve("proj");
    write("src/demo/Shape.java", SHAPE);
    write("src/demo/Circle.java", CIRCLE);
    write("src/demo/Square.java", SQUARE);
    write("src/demo/Big.java", BIG);
    write("src/demo/App.java", APP);
    server = new JavaIndexServer(new Project(Files.createDirectories(tmp.resolve("state"))), () -> {});
    InitializeParams init = new InitializeParams();
    init.setRootUri(root.toUri().toString());
    server.initialize(init).get(30, TimeUnit.SECONDS);
  }

  @AfterEach
  void tearDown() {
    server.dispose();
  }

  @Test
  void hoverShowsSignatureAndJavadoc() throws Exception {
    // s is inferred (var over List<Shape>), so this is Shape.area() with its javadoc.
    String md = hover("App", APP, "area();\n    }").getContents().getRight().getValue();
    assertTrue(md.contains("double area()"), md);
    assertTrue(md.contains("Area of the shape, in `units`."), md);
    assertTrue(md.contains("*@return* the area"), md);
    assertTrue(md.contains("demo.Shape"), md);

    String cls = hover("App", APP, "Circle c").getContents().getRight().getValue();
    assertTrue(cls.contains("class demo.Circle implements Shape"), cls);

    String local = hover("App", APP, "sum +=").getContents().getRight().getValue();
    assertTrue(local.contains("double sum"), local);

    // JDK method: parameter names come from src.zip.
    String jdk = hover("App", APP, "valueOf").getContents().getRight().getValue();
    assertTrue(jdk.contains("static String valueOf(int i)"), jdk);
  }

  @Test
  void typeDefinitionImplementationAndHierarchy() throws Exception {
    Location type =
        server.typeDefinition(new TypeDefinitionParams(doc("App"), pos(APP, "c.area")))
            .get(30, TimeUnit.SECONDS).getLeft().get(0);
    assertTrue(type.getUri().endsWith("Circle.java"), type.getUri());

    List<String> impls =
        files(server.implementation(new ImplementationParams(doc("Shape"), pos(SHAPE, "Shape {")))
            .get(30, TimeUnit.SECONDS).getLeft());
    assertEquals(Set.of("Circle.java", "Square.java", "Big.java"), Set.copyOf(impls));

    List<? extends Location> methodImpls =
        server.implementation(new ImplementationParams(doc("Shape"), pos(SHAPE, "area();")))
            .get(30, TimeUnit.SECONDS).getLeft();
    assertEquals(Set.of("Circle.java", "Square.java"), Set.copyOf(files(methodImpls)));

    TypeHierarchyItem circle =
        server.prepareTypeHierarchy(new TypeHierarchyPrepareParams(doc("App"), pos(APP, "Circle c")))
            .get(30, TimeUnit.SECONDS).get(0);
    assertEquals("Circle", circle.getName());
    List<String> supers =
        server.typeHierarchySupertypes(new TypeHierarchySupertypesParams(circle))
            .get(30, TimeUnit.SECONDS).stream().map(TypeHierarchyItem::getName).toList();
    assertTrue(supers.contains("Shape"), supers.toString());
    List<String> subs =
        server.typeHierarchySubtypes(new TypeHierarchySubtypesParams(circle))
            .get(30, TimeUnit.SECONDS).stream().map(TypeHierarchyItem::getName).toList();
    assertEquals(List.of("Big"), subs);
  }

  @Test
  void callHierarchy() throws Exception {
    CallHierarchyItem total =
        server.prepareCallHierarchy(new CallHierarchyPrepareParams(doc("App"), pos(APP, "total(List")))
            .get(30, TimeUnit.SECONDS).get(0);
    List<CallHierarchyIncomingCall> incoming =
        server.callHierarchyIncomingCalls(new CallHierarchyIncomingCallsParams(total)).get(30, TimeUnit.SECONDS);
    assertEquals(List.of("run()"), incoming.stream().map(c -> c.getFrom().getName()).toList());

    CallHierarchyItem run =
        server.prepareCallHierarchy(new CallHierarchyPrepareParams(doc("App"), pos(APP, "run()")))
            .get(30, TimeUnit.SECONDS).get(0);
    Set<String> outgoing =
        server.callHierarchyOutgoingCalls(new CallHierarchyOutgoingCallsParams(run)).get(30, TimeUnit.SECONDS)
            .stream().map(c -> c.getTo().getName()).collect(Collectors.toSet());
    assertTrue(outgoing.containsAll(Set.of("Circle()", "Square()", "total()", "of()", "valueOf()", "area()")), outgoing.toString());

    // Incoming calls to Shape.area() come through the var-typed loop variable.
    CallHierarchyItem area =
        server.prepareCallHierarchy(new CallHierarchyPrepareParams(doc("Shape"), pos(SHAPE, "area();")))
            .get(30, TimeUnit.SECONDS).get(0);
    List<CallHierarchyOutgoingCall> none = List.of();
    assertEquals(
        List.of("total()"),
        server.callHierarchyIncomingCalls(new CallHierarchyIncomingCallsParams(area)).get(30, TimeUnit.SECONDS)
            .stream().map(c -> c.getFrom().getName()).toList());
    assertTrue(none.isEmpty());
  }

  @Test
  void highlightSignatureHelpAndInlayHints() throws Exception {
    List<? extends DocumentHighlight> sums =
        server.documentHighlight(new DocumentHighlightParams(doc("App"), pos(APP, "sum = 0")))
            .get(30, TimeUnit.SECONDS);
    assertEquals(3, sums.size());
    assertEquals(
        List.of(DocumentHighlightKind.Write, DocumentHighlightKind.Write, DocumentHighlightKind.Read),
        sums.stream().map(DocumentHighlight::getKind).toList());

    SignatureHelp ctor =
        server.signatureHelp(new SignatureHelpParams(doc("App"), pos(APP, "2.0)")))
            .get(30, TimeUnit.SECONDS);
    assertEquals("Circle(double radius)", ctor.getSignatures().get(0).getLabel());
    assertEquals(0, ctor.getActiveParameter());

    SignatureHelp valueOf =
        server.signatureHelp(new SignatureHelpParams(doc("App"), pos(APP, "42)")))
            .get(30, TimeUnit.SECONDS);
    assertTrue(valueOf.getSignatures().size() > 3);
    assertEquals(
        "valueOf(int i)", valueOf.getSignatures().get(valueOf.getActiveSignature()).getLabel());

    List<InlayHint> hints =
        server.inlayHint(new InlayHintParams(doc("App"), new Range(new Position(0, 0), new Position(40, 0))))
            .get(30, TimeUnit.SECONDS);
    List<String> labels = hints.stream().map(h -> h.getLabel().getLeft()).toList();
    assertTrue(labels.contains(": Shape"), labels.toString());
    assertTrue(labels.contains("radius:"), labels.toString());
    assertTrue(labels.contains("i:"), labels.toString());
    assertTrue(labels.contains("side:"), labels.toString());
  }

  @Test
  void codeActionsAndWorkspaceMembers() throws Exception {
    Position optional = pos(APP, "Optional<");
    List<String> titles =
        server.codeAction(
                new CodeActionParams(doc("App"), new Range(optional, optional), new CodeActionContext(List.of())))
            .get(30, TimeUnit.SECONDS).stream()
            .map(e -> e.getRight().getTitle())
            .toList();
    assertTrue(titles.contains("Import 'java.util.Optional'"), titles.toString());
    assertTrue(titles.contains("Remove unused import 'java.util.Map'"), titles.toString());

    CodeAction add =
        server.codeAction(
                new CodeActionParams(doc("App"), new Range(optional, optional), new CodeActionContext(List.of())))
            .get(30, TimeUnit.SECONDS).stream()
            .map(e -> e.getRight())
            .filter(a -> a.getTitle().equals("Import 'java.util.Optional'"))
            .findFirst().get();
    var edit = add.getEdit().getChanges().values().iterator().next().get(0);
    assertEquals("import java.util.Optional;\n", edit.getNewText());
    assertEquals(4, edit.getRange().getStart().getLine()); // right after the last import

    var symbols = server.symbol(new WorkspaceSymbolParams("area")).get(30, TimeUnit.SECONDS).getLeft();
    assertNotNull(symbols);
    assertTrue(
        symbols.stream().filter(s -> s.getName().equals("area()")).count() >= 3, symbols.toString());
  }

  // ---------------------------------------------------------------------------------------------

  private org.eclipse.lsp4j.Hover hover(String cls, String text, String needle) throws Exception {
    return server.hover(new HoverParams(doc(cls), pos(text, needle))).get(30, TimeUnit.SECONDS);
  }

  private TextDocumentIdentifier doc(String cls) {
    return new TextDocumentIdentifier(root.resolve("src/demo/" + cls + ".java").toUri().toString());
  }

  private static List<String> files(List<? extends Location> locations) {
    return locations.stream().map(l -> l.getUri().substring(l.getUri().lastIndexOf('/') + 1)).toList();
  }

  /** Position of the first character of {@code needle} (inside the identifier it starts). */
  private static Position pos(String text, String needle) {
    int offset = text.indexOf(needle);
    assertTrue(offset >= 0, needle);
    String before = text.substring(0, offset);
    int line = (int) before.chars().filter(c -> c == '\n').count();
    return new Position(line, offset - (before.lastIndexOf('\n') + 1));
  }

  private void write(String rel, String text) throws Exception {
    Path f = root.resolve(rel);
    Files.createDirectories(f.getParent());
    Files.writeString(f, text);
  }
}
