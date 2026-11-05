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

import com.google.turbine.model.TurbineTyKind;
import com.google.turbine.tree.Tree;
import dev.javaindex.index.FileSummary;
import dev.javaindex.index.FileSummary.FieldDecl;
import dev.javaindex.index.FileSummary.MethodDecl;
import dev.javaindex.index.FileSummary.TypeInfo;
import dev.javaindex.index.Lines;
import dev.javaindex.query.Describe;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolKind;

/** Document outlines and workspace-symbol matching. */
final class Symbols {
  private Symbols() {}

  static List<DocumentSymbol> outline(FileSummary file) {
    List<DocumentSymbol> out = new ArrayList<>();
    for (TypeInfo t : file.types) {
      out.add(type(t, file.lines));
    }
    return out;
  }

  private static DocumentSymbol type(TypeInfo t, Lines lines) {
    List<DocumentSymbol> children = new ArrayList<>();
    for (FieldDecl f : t.fields) {
      DocumentSymbol s =
          symbol(
              f.name(),
              switch (f.kind()) {
                case ENUM_CONSTANT -> SymbolKind.EnumMember;
                default -> SymbolKind.Field;
              },
              f.span(),
              lines);
      if (f.kind() != FieldDecl.Kind.ENUM_CONSTANT) {
        s.setDetail(Describe.tree(f.type()));
      }
      children.add(s);
    }
    for (MethodDecl m : t.methods) {
      List<String> params = new ArrayList<>();
      for (Tree p : m.paramTypes()) {
        params.add(Describe.tree(p));
      }
      String name = (m.isConstructor() ? t.simpleName : m.name()) + "(" + String.join(", ", params) + ")";
      DocumentSymbol s =
          symbol(name, m.isConstructor() ? SymbolKind.Constructor : SymbolKind.Method, m.span(), lines);
      if (m.returnType() != null) {
        s.setDetail(Describe.tree(m.returnType()));
      }
      children.add(s);
    }
    for (TypeInfo member : t.members) {
      children.add(type(member, lines));
    }
    children.sort((a, b) -> compare(a.getRange().getStart(), b.getRange().getStart()));
    DocumentSymbol s = symbol(t.simpleName, kind(t.kind), t.span, lines);
    s.setChildren(children);
    return s;
  }

  private static int compare(Position a, Position b) {
    return a.getLine() != b.getLine()
        ? Integer.compare(a.getLine(), b.getLine())
        : Integer.compare(a.getCharacter(), b.getCharacter());
  }

  static SymbolKind kind(TurbineTyKind kind) {
    return switch (kind) {
      case INTERFACE, ANNOTATION -> SymbolKind.Interface;
      case ENUM -> SymbolKind.Enum;
      case RECORD -> SymbolKind.Struct;
      default -> SymbolKind.Class;
    };
  }

  private static DocumentSymbol symbol(
      String name, SymbolKind kind, FileSummary.Span span, Lines lines) {
    Range full = range(lines, span.start(), Math.max(span.end(), span.nameEnd()));
    Range selection = range(lines, span.nameStart(), span.nameEnd());
    return new DocumentSymbol(name, kind, full, selection);
  }

  static Range range(Lines lines, int start, int end) {
    return new Range(
        new Position(lines.line(start), lines.column(start)),
        new Position(lines.line(end), lines.column(end)));
  }

  /**
   * Scores how well {@code name} matches a workspace-symbol query, or -1 for no match. Supports
   * case-insensitive prefixes, camel-case humps ("HM" for HashMap) and substrings.
   */
  static int score(String name, String query) {
    if (query.isEmpty()) {
      return 1;
    }
    if (name.equals(query)) {
      return 100;
    }
    String lowerName = name.toLowerCase();
    String lowerQuery = query.toLowerCase();
    if (lowerName.equals(lowerQuery)) {
      return 90;
    }
    if (lowerName.startsWith(lowerQuery)) {
      return 80 - Math.min(30, name.length() - query.length());
    }
    if (camelMatch(name, query)) {
      return 40;
    }
    if (lowerName.contains(lowerQuery)) {
      return 20;
    }
    return -1;
  }

  private static boolean camelMatch(String name, String query) {
    int n = 0;
    for (int q = 0; q < query.length(); q++) {
      char c = query.charAt(q);
      if (Character.isUpperCase(c) || q == 0) {
        // jump to the next hump starting with c
        while (n < name.length()
            && !(Character.toLowerCase(name.charAt(n)) == Character.toLowerCase(c)
                && (n == 0 || Character.isUpperCase(name.charAt(n)) || name.charAt(n - 1) == '_'))) {
          n++;
        }
      } else {
        if (n < name.length() && Character.toLowerCase(name.charAt(n)) == c) {
          n++;
          continue;
        }
        return false;
      }
      if (n >= name.length()) {
        return false;
      }
      n++;
    }
    return true;
  }
}
