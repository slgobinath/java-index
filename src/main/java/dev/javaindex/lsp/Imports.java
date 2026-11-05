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

import com.google.turbine.parse.Token;
import dev.javaindex.index.Tokens;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Import statements of a file: which are unused, and where a new one goes. */
final class Imports {
  /** One import statement; [start, end) covers its whole line(s). */
  record Decl(String qualifiedName, String simpleName, boolean isStatic, boolean wild, int start, int end) {}

  private final String text;
  private final List<Decl> decls = new ArrayList<>();
  private final Set<String> usedIdents = new HashSet<>();
  private int packageEnd = -1;

  Imports(String text) {
    this.text = text;
    Tokens t = Tokens.lex(text);
    for (int i = 0; i < t.size(); i++) {
      if (t.is(i, Token.PACKAGE)) {
        while (i < t.size() && !t.is(i, Token.SEMI)) {
          i++;
        }
        packageEnd = i < t.size() ? t.end(i) : -1;
        continue;
      }
      if (!t.is(i, Token.IMPORT)) {
        if (t.is(i, Token.IDENT)) {
          usedIdents.add(t.text(i));
        }
        continue;
      }
      int startTok = i;
      boolean isStatic = t.is(i + 1, Token.STATIC);
      List<String> names = new ArrayList<>();
      boolean wild = false;
      while (i < t.size() && !t.is(i, Token.SEMI)) {
        if (t.is(i, Token.IDENT)) {
          names.add(t.text(i));
        } else if (t.is(i, Token.MULT)) {
          wild = true;
        }
        i++;
      }
      if (names.isEmpty() || i >= t.size()) {
        continue;
      }
      decls.add(
          new Decl(
              String.join(".", names),
              names.getLast(),
              isStatic,
              wild,
              lineStart(t.start(startTok)),
              lineEnd(t.end(i))));
    }
  }

  List<Decl> decls() {
    return decls;
  }

  /**
   * Imports whose name is never mentioned outside the import block. Conservative: a mention in a
   * comment (e.g. {@code {@link Foo}}) or string keeps the import.
   */
  List<Decl> unused() {
    List<Decl> out = new ArrayList<>();
    String body = withoutImports();
    for (Decl d : decls) {
      if (d.wild) {
        continue;
      }
      if (usedIdents.contains(d.simpleName)) {
        continue;
      }
      if (Pattern.compile("\\b" + Pattern.quote(d.simpleName) + "\\b").matcher(body).find()) {
        continue;
      }
      out.add(d);
    }
    return out;
  }

  /** True if {@code qualifiedName} is already importable by simple name here. */
  boolean covers(String qualifiedName) {
    int dot = qualifiedName.lastIndexOf('.');
    String pkg = dot < 0 ? "" : qualifiedName.substring(0, dot);
    for (Decl d : decls) {
      if (!d.isStatic && (d.qualifiedName.equals(qualifiedName) || (d.wild && d.qualifiedName.equals(pkg)))) {
        return true;
      }
    }
    return false;
  }

  /** Whether a simple name is already taken by a single-type import. */
  boolean importsSimpleName(String simpleName) {
    return decls.stream().anyMatch(d -> !d.wild && !d.isStatic && d.simpleName.equals(simpleName));
  }

  /** Offset where a new import line goes, and whether it needs a blank line before it. */
  int insertionOffset() {
    if (!decls.isEmpty()) {
      return decls.getLast().end;
    }
    return packageEnd < 0 ? 0 : lineEnd(packageEnd);
  }

  boolean hasImports() {
    return !decls.isEmpty();
  }

  private String withoutImports() {
    StringBuilder sb = new StringBuilder(text);
    for (int i = decls.size() - 1; i >= 0; i--) {
      Decl d = decls.get(i);
      sb.replace(d.start, d.end, "");
    }
    return sb.toString();
  }

  private int lineStart(int offset) {
    return text.lastIndexOf('\n', Math.max(0, offset - 1)) + 1;
  }

  private int lineEnd(int offset) {
    int nl = text.indexOf('\n', offset);
    return nl < 0 ? text.length() : nl + 1;
  }
}
