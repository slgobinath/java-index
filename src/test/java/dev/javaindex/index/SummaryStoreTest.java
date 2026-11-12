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
package dev.javaindex.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.turbine.tree.Tree;
import dev.javaindex.index.FileSummary.FieldDecl;
import dev.javaindex.index.FileSummary.MethodDecl;
import dev.javaindex.index.FileSummary.TypeInfo;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SummaryStoreTest {
  private static final String SOURCE =
      """
      package a.b;

      import java.util.*;
      import static java.util.Map.Entry;

      @Deprecated
      public abstract class Box<T extends Comparable<? super T>, U> extends AbstractList<T>
          implements java.io.Serializable, Map.Entry<String, U[]> {
        private static final int SIZE = 3;
        @Value("${app.name}") String name;
        protected Outer.Inner<T>[][] grid;

        public <R extends Number> Box(R seed, int... rest) {}
        public abstract List<? extends T> items(Map<String, ? super U> byName, char c);
        @Deprecated void old() {}

        enum Mode { ON, OFF }
        record Pair<K>(K left, long right) {}
        interface Visitor { void visit(Box<?, ?> box); }
        static class Nested {
          class Deeper {}
        }
      }
      """;

  @TempDir Path dir;

  @Test
  void roundTripsEverySummaryField() throws Exception {
    FileSummary parsed = Summarizer.summarize(Summarizer.hash(SOURCE), "a/b/Box.java", SOURCE);
    FileSummary broken =
        Summarizer.summarize(Summarizer.hash("class {"), "Broken.java", "class { void x(");
    Path file = dir.resolve("summaries/x.bin");
    SummaryStore.save(file, List.of(parsed, broken));

    SummaryStore.Stored loaded = SummaryStore.load(file);
    assertEquals(2, loaded.size());
    assertEquals(dump(parsed), dump(loaded.get(parsed.hash, () -> Lines.of(SOURCE))));
    assertEquals(dump(broken), dump(loaded.get(broken.hash, () -> Lines.of("class { void x("))));
    assertNull(loaded.get("no-such-hash", () -> Lines.of("")));
    FileSummary back = loaded.get(parsed.hash, () -> Lines.of(SOURCE));
    assertTrue(back.mayContain("SIZE") && back.mayContain("Deeper"));
    TypeInfo deeper = back.types.get(0).member("Nested").member("Deeper");
    assertEquals("Nested", deeper.outer.simpleName);
    assertEquals("Box", deeper.outer.outer.simpleName);
  }

  @Test
  void ignoresMissingAndForeignFiles() throws Exception {
    assertEquals(0, SummaryStore.load(dir.resolve("none.bin")).size());
    Path junk = dir.resolve("junk.bin");
    Files.write(junk, new byte[] {1, 2, 3});
    assertEquals(0, SummaryStore.load(junk).size());
  }

  /** Every field a summary carries, trees with their positions. */
  private static String dump(FileSummary s) {
    StringBuilder b = new StringBuilder();
    b.append(s.hash).append('|').append(s.pkg).append('|').append(s.parsed).append('\n');
    s.imports.forEach(i -> b.append("import ").append(i).append('\n'));
    for (int i = 0; i < 2000; i++) {
      b.append(s.lines.line(i)).append(',');
    }
    b.append('\n').append(java.util.Arrays.toString(s.identFilter())).append('\n');
    s.configRefs.forEach(c -> b.append("ref ").append(c).append('\n'));
    for (TypeInfo t : s.allTypes()) {
      b.append("type ")
          .append(t.binaryName)
          .append(' ')
          .append(t.simpleName)
          .append(' ')
          .append(t.kind)
          .append(' ')
          .append(t.span)
          .append(' ')
          .append(t.typeParams)
          .append(" bounds=")
          .append(trees(t.typeParamBounds))
          .append(" super=")
          .append(tree(t.superclass))
          .append(" ifaces=")
          .append(trees(t.interfaces))
          .append(" dep=")
          .append(t.deprecated)
          .append(" outer=")
          .append(t.outer == null ? null : t.outer.binaryName)
          .append('\n');
      for (FieldDecl f : t.fields) {
        b.append("  field ").append(f.name()).append(' ').append(tree(f.type())).append(' ')
            .append(f.span()).append(' ').append(f.isStatic()).append(' ').append(f.kind())
            .append(' ').append(f.deprecated()).append('\n');
      }
      for (MethodDecl m : t.methods) {
        b.append("  method ").append(m.name()).append(' ').append(trees(m.paramTypes()))
            .append(' ').append(m.paramNames()).append(' ').append(m.varargs()).append(' ')
            .append(tree(m.returnType())).append(' ').append(m.typeParams()).append(' ')
            .append(trees(m.typeParamBounds())).append(' ').append(m.span()).append(' ')
            .append(m.isStatic()).append(' ').append(m.isAbstract()).append(' ')
            .append(m.deprecated()).append('\n');
      }
    }
    return b.toString();
  }

  private static String trees(List<? extends Tree> list) {
    StringBuilder b = new StringBuilder("[");
    list.forEach(t -> b.append(tree(t)).append(';'));
    return b.append(']').toString();
  }

  private static String tree(Tree t) {
    return switch (t) {
      case null -> "null";
      case Tree.ClassTy c ->
          "C@" + c.position() + "(" + c.base().map(SummaryStoreTest::tree).orElse("") + "."
              + c.name().value() + "@" + c.name().position() + trees(c.tyargs()) + ")";
      case Tree.ArrTy a -> "A@" + a.position() + "(" + tree(a.elem()) + ")";
      case Tree.PrimTy p -> "P@" + p.position() + p.tykind();
      case Tree.VoidTy v -> "V@" + v.position();
      case Tree.WildTy w ->
          "W@" + w.position() + "(" + w.upper().map(SummaryStoreTest::tree).orElse("") + ","
              + w.lower().map(SummaryStoreTest::tree).orElse("") + ")";
      default -> "?" + t.kind();
    };
  }
}
