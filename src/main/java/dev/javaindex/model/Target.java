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

package dev.javaindex.model;

import dev.javaindex.index.Lines;
import java.nio.file.Path;
import java.util.List;

/** Where a symbol is declared. */
public sealed interface Target {

  /** A declaration in a source file we have a summary for. */
  record Source(Path file, Lines lines, int start, int end) implements Target {}

  /**
   * A declaration that only exists as bytecode (a dependency jar or the JDK). {@code origin} is the
   * jar path, or null for the JDK. Turned into a source location lazily, on request.
   */
  record Binary(
      String origin,
      String classBinary,
      String member,
      Member.Kind kind,
      int arity,
      List<String> erasedParams)
      implements Target {}

  /**
   * Erased simple name of a parameter type, used to tell overloads apart when matching a class
   * file against its source: {@code int}, {@code String[]}, or {@code ?} for a type variable.
   */
  static String erased(TypeRef t) {
    if (t == null || t.tvar() != null) {
      return "?" + (t == null ? "" : "[]".repeat(t.dims()));
    }
    String base = t.prim() != null ? t.prim() : TypeRef.simpleName(t.cls());
    base = base.substring(base.lastIndexOf('.') + 1);
    return base + "[]".repeat(t.dims());
  }

  /** True if both denote the same declaration. */
  static boolean same(Target a, Target b) {
    if (a instanceof Source x && b instanceof Source y) {
      return x.start == y.start && x.file.equals(y.file);
    }
    return a.equals(b);
  }
}
