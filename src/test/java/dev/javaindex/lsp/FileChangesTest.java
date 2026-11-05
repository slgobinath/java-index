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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.javaindex.project.Project;
import dev.javaindex.project.Worktree;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.FileChangeType;
import org.eclipse.lsp4j.FileEvent;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.WorkspaceSymbolParams;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Disk changes reach the index: file events, directory events, buffers, and rescans. */
class FileChangesTest {
  @TempDir Path tmp;

  private Path root;
  private Project project;
  private JavaIndexServer server;

  @BeforeEach
  void setUp() throws Exception {
    root = tmp.resolve("proj");
    write("src/demo/Shape.java", "package demo;\npublic interface Shape { double area(); }\n");
    write("src/demo/shapes/Circle.java", "package demo.shapes;\npublic class Circle {}\n");
    write("src/demo/shapes/Square.java", "package demo.shapes;\npublic class Square {}\n");
    project = new Project(Files.createDirectories(tmp.resolve("state")));
    server = new JavaIndexServer(project, () -> {});
    InitializeParams init = new InitializeParams();
    init.setRootUri(root.toUri().toString());
    server.initialize(init).get(30, TimeUnit.SECONDS);
    worktree().ready().get(30, TimeUnit.SECONDS);
  }

  @AfterEach
  void tearDown() {
    server.dispose();
  }

  @Test
  void fileEventsUpdateTheIndex() throws Exception {
    // modified
    write("src/demo/Shape.java", "package demo;\npublic interface Shape { double perimeter(); }\n");
    event("src/demo/Shape.java", FileChangeType.Changed);
    assertTrue(symbols("perimeter").contains("perimeter()"));
    assertFalse(symbols("area").contains("area()"));

    // created
    write("src/demo/Triangle.java", "package demo;\npublic class Triangle {}\n");
    event("src/demo/Triangle.java", FileChangeType.Created);
    assertTrue(symbols("Triangle").contains("Triangle"));

    // deleted
    Files.delete(root.resolve("src/demo/Triangle.java"));
    event("src/demo/Triangle.java", FileChangeType.Deleted);
    assertFalse(symbols("Triangle").contains("Triangle"));
  }

  @Test
  void directoryEventsReconcileEverythingBelow() throws Exception {
    // A package directory deleted: editors report one event for the directory.
    deleteTree(root.resolve("src/demo/shapes"));
    event("src/demo/shapes", FileChangeType.Deleted);
    assertFalse(symbols("Circle").contains("Circle"));
    assertFalse(symbols("Square").contains("Square"));

    // A directory moved in: again a single event.
    Path staging = Files.createDirectories(tmp.resolve("staging/geo"));
    Files.writeString(staging.resolve("Point.java"), "package demo.geo;\npublic class Point {}\n");
    Files.move(staging, root.resolve("src/demo/geo"));
    event("src/demo/geo", FileChangeType.Created);
    assertTrue(symbols("Point").contains("Point"));
  }

  @Test
  void buildOutputIsIgnored() throws Exception {
    write("target/classes/demo/Generated.java", "package demo;\npublic class Ignored {}\n");
    event("target/classes", FileChangeType.Created);
    event("target/classes/demo/Generated.java", FileChangeType.Created);
    assertFalse(symbols("Ignored").contains("Ignored"));
    // ...but generated sources are indexed.
    write("target/generated-sources/demo/Gen.java", "package demo;\npublic class Gen {}\n");
    event("target/generated-sources", FileChangeType.Created);
    assertTrue(symbols("Gen").contains("Gen"));
  }

  @Test
  void openBuffersWinOverDiskUntilClosed() throws Exception {
    String uri = root.resolve("src/demo/Shape.java").toUri().toString();
    String buffer = "package demo;\npublic interface Shape { double volume(); }\n";
    server.didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(uri, "java", 1, buffer)));
    write("src/demo/Shape.java", "package demo;\npublic interface Shape { double weight(); }\n");
    event("src/demo/Shape.java", FileChangeType.Changed);
    assertTrue(symbols("volume").contains("volume()"));
    assertFalse(symbols("weight").contains("weight()"));

    server.didClose(new DidCloseTextDocumentParams(new TextDocumentIdentifier(uri)));
    assertTrue(symbols("weight").contains("weight()"));
  }

  @Test
  void rescanCatchesChangesWithoutEvents() throws Exception {
    // What the periodic poll does: no events at all, just a stat-based rescan.
    write("src/demo/Shape.java", "package demo;\npublic interface Shape { double depth(); }\n");
    write("src/demo/Hexagon.java", "package demo;\npublic class Hexagon {}\n");
    deleteTree(root.resolve("src/demo/shapes"));
    worktree().rescan();
    assertTrue(symbols("depth").contains("depth()"));
    assertTrue(symbols("Hexagon").contains("Hexagon"));
    assertFalse(symbols("Circle").contains("Circle"));
  }

  @Test
  void rapidChangesApplyInOrder() throws Exception {
    for (int i = 0; i < 20; i++) {
      write("src/demo/Shape.java", "package demo;\npublic interface Shape { double v" + i + "(); }\n");
      event("src/demo/Shape.java", FileChangeType.Changed);
    }
    assertEquals(List.of("v19()"), symbols("v1").stream().filter(n -> n.startsWith("v")).toList());
  }

  // ---------------------------------------------------------------------------------------------

  private Worktree worktree() {
    return project.worktrees().get(0);
  }

  private void event(String rel, FileChangeType type) throws Exception {
    server.didChangeWatchedFiles(
        new DidChangeWatchedFilesParams(List.of(new FileEvent(root.resolve(rel).toUri().toString(), type))));
    worktree().changesApplied().get(30, TimeUnit.SECONDS);
  }

  private List<String> symbols(String query) throws Exception {
    return server.symbol(new WorkspaceSymbolParams(query)).get(30, TimeUnit.SECONDS).getLeft().stream()
        .filter(s -> s.getLocation().getUri().startsWith(root.toUri().toString()))
        .map(s -> s.getName())
        .toList();
  }

  private void write(String rel, String text) throws Exception {
    Path f = root.resolve(rel);
    Files.createDirectories(f.getParent());
    Files.writeString(f, text);
    // Ensure a different mtime/size signature even for same-second rewrites.
    Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + (long) (Math.random() * 100000)));
  }

  private static void deleteTree(Path dir) throws Exception {
    try (Stream<Path> paths = Files.walk(dir)) {
      for (Path p : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
        Files.delete(p);
      }
    }
  }
}
