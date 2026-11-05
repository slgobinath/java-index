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
import java.util.concurrent.TimeUnit;
import org.eclipse.lsp4j.ImplementationParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TypeHierarchyItem;
import org.eclipse.lsp4j.TypeHierarchyPrepareParams;
import org.eclipse.lsp4j.TypeHierarchySubtypesParams;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Anonymous classes are subtypes too, though turbine never sees them (they live in bodies). */
class AnonymousTypesTest {
  @TempDir Path tmp;

  private JavaIndexServer server;
  private Path root;

  private static final String ENTITY = "package demo;\npublic class Entity {}\n";
  private static final String PET_TYPE = "package demo;\npublic class PetType extends Entity {}\n";
  private static final String FORMATTER_TEST =
      """
      package demo;

      import java.util.ArrayList;
      import java.util.List;

      class FormatterTest {
        List<PetType> types() {
          List<PetType> petTypes = new ArrayList<>();
          petTypes.add(new PetType() {
            { }
          });
          petTypes.add(new demo.PetType() {});
          Runnable r = new Runnable() {
            public void run() {}
          };
          Object plain = new PetType();
          return petTypes;
        }
      }
      """;

  @BeforeEach
  void setUp() throws Exception {
    root = tmp.resolve("proj");
    write("src/demo/Entity.java", ENTITY);
    write("src/demo/PetType.java", PET_TYPE);
    write("src/demo/FormatterTest.java", FORMATTER_TEST);
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
  void implementationsIncludeAnonymousSubclassesTransitively() throws Exception {
    List<String> impls = lines(implementations("Entity", ENTITY, "Entity {}"));
    // PetType itself, then the two anonymous PetTypes (not `new PetType()` without a body)
    assertEquals(List.of("PetType.java:2", "FormatterTest.java:9", "FormatterTest.java:12"), impls);
    assertEquals(
        List.of("FormatterTest.java:9", "FormatterTest.java:12"),
        lines(implementations("PetType", PET_TYPE, "PetType extends")));
  }

  @Test
  void subtypesListDirectAnonymousSubclasses() throws Exception {
    TypeHierarchyItem petType =
        server.prepareTypeHierarchy(new TypeHierarchyPrepareParams(doc("PetType"), pos(PET_TYPE, "PetType extends")))
            .get(30, TimeUnit.SECONDS).get(0);
    List<String> subs =
        server.typeHierarchySubtypes(new TypeHierarchySubtypesParams(petType)).get(30, TimeUnit.SECONDS)
            .stream().map(i -> i.getName() + "@" + (i.getRange().getStart().getLine() + 1)).toList();
    assertEquals(List.of("new PetType() {…}@9", "new PetType() {…}@12"), subs);

    TypeHierarchyItem entity =
        server.prepareTypeHierarchy(new TypeHierarchyPrepareParams(doc("Entity"), pos(ENTITY, "Entity {}")))
            .get(30, TimeUnit.SECONDS).get(0);
    // direct subtypes only: the anonymous classes extend PetType, not Entity
    List<String> direct =
        server.typeHierarchySubtypes(new TypeHierarchySubtypesParams(entity)).get(30, TimeUnit.SECONDS)
            .stream().map(TypeHierarchyItem::getName).toList();
    assertEquals(List.of("PetType"), direct);
  }

  private List<? extends Location> implementations(String cls, String text, String needle) throws Exception {
    return server.implementation(new ImplementationParams(doc(cls), pos(text, needle)))
        .get(30, TimeUnit.SECONDS).getLeft();
  }

  private static List<String> lines(List<? extends Location> locations) {
    return locations.stream()
        .map(l -> l.getUri().substring(l.getUri().lastIndexOf('/') + 1) + ":" + (l.getRange().getStart().getLine() + 1))
        .toList();
  }

  private TextDocumentIdentifier doc(String cls) {
    return new TextDocumentIdentifier(root.resolve("src/demo/" + cls + ".java").toUri().toString());
  }

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
