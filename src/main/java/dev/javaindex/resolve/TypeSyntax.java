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

package dev.javaindex.resolve;

import com.google.turbine.parse.Token;
import dev.javaindex.index.Tokens;
import dev.javaindex.model.TypeRef;
import dev.javaindex.model.Universe;
import java.util.ArrayList;
import java.util.List;

/** Parses and resolves Java type syntax directly from a token range (for locals, casts, `new`). */
final class TypeSyntax {
  private final Tokens t;
  private int pos;
  private final int limit;
  /** A '>>' or '>>>' token only partially consumed by nested type arguments. */
  private int pendingGt;

  private TypeSyntax(Tokens t, int from, int limit) {
    this.t = t;
    this.pos = from;
    this.limit = limit;
  }

  record Parsed(List<String> names, List<Parsed> args, String prim, int dims) {}

  /** Parses tokens [from, to] as exactly one type, or returns null. */
  static Parsed parse(Tokens t, int from, int to) {
    TypeSyntax p = new TypeSyntax(t, from, to + 1);
    Parsed out = p.type();
    return out != null && p.pos == to + 1 && p.pendingGt == 0 ? out : null;
  }

  static TypeRef resolve(Parsed p, Universe u, Universe.Scope scope) {
    if (p == null) {
      return null;
    }
    TypeRef base;
    if (p.prim != null) {
      base = TypeRef.ofPrim(p.prim);
    } else if (p.names.size() == 1 && u.isTypeVar(p.names.get(0), scope)) {
      base = TypeRef.ofVar(p.names.get(0));
    } else {
      String bin = u.resolveTypeName(p.names, scope);
      if (bin == null) {
        return null;
      }
      List<TypeRef> args = new ArrayList<>();
      for (Parsed a : p.args) {
        TypeRef r = resolve(a, u, scope);
        args.add(r == null ? TypeRef.OBJECT : r);
      }
      base = TypeRef.ofClass(bin, args);
    }
    for (int i = 0; i < p.dims; i++) {
      base = base.array();
    }
    return base;
  }

  private Token kind() {
    return pos < limit ? t.kind(pos) : Token.EOF;
  }

  private void skipAnnotations() {
    while (kind() == Token.AT && t.is(pos + 1, Token.IDENT)) {
      pos += 2;
      while (kind() == Token.DOT && t.is(pos + 1, Token.IDENT)) {
        pos += 2;
      }
      if (kind() == Token.LPAREN && t.match(pos) > pos) {
        pos = t.match(pos) + 1;
      }
    }
    while (kind() == Token.FINAL) {
      pos++;
    }
  }

  private Parsed type() {
    skipAnnotations();
    String prim = primitive(kind());
    Parsed base;
    if (prim != null) {
      pos++;
      base = new Parsed(List.of(), List.of(), prim, 0);
    } else if (kind() == Token.COND) {
      // Wildcard: use the upper bound, or Object.
      pos++;
      if (kind() == Token.EXTENDS) {
        pos++;
        return type();
      }
      if (kind() == Token.SUPER) {
        pos++;
        return type();
      }
      return new Parsed(List.of("java", "lang", "Object"), List.of(), null, 0);
    } else if (kind() == Token.IDENT) {
      List<String> names = new ArrayList<>();
      List<Parsed> args = List.of();
      names.add(t.text(pos++));
      while (true) {
        if (kind() == Token.LT) {
          args = typeArgs();
          if (args == null) {
            return null;
          }
        }
        if (kind() == Token.DOT && t.is(pos + 1, Token.IDENT)) {
          pos++;
          names.add(t.text(pos++));
          args = List.of();
        } else {
          break;
        }
      }
      base = new Parsed(names, args, null, 0);
    } else {
      return null;
    }
    int dims = 0;
    while (pendingGt == 0) {
      if (kind() == Token.LBRACK && t.is(pos + 1, Token.RBRACK)) {
        pos += 2;
        dims++;
      } else if (kind() == Token.ELLIPSIS) {
        pos++;
        dims++;
      } else {
        break;
      }
    }
    return dims == 0 ? base : new Parsed(base.names, base.args, base.prim, dims);
  }

  private List<Parsed> typeArgs() {
    pos++; // '<'
    List<Parsed> args = new ArrayList<>();
    if (closeAngle()) {
      return args; // diamond
    }
    while (true) {
      Parsed a = type();
      if (a == null) {
        return null;
      }
      args.add(a);
      if (pendingGt == 0 && kind() == Token.AND) {
        // Intersection bound: keep the first.
        pos++;
        if (type() == null) {
          return null;
        }
      }
      if (pendingGt == 0 && kind() == Token.COMMA) {
        pos++;
        continue;
      }
      return closeAngle() ? args : null;
    }
  }

  private boolean closeAngle() {
    if (pendingGt > 0) {
      pendingGt--;
      if (pendingGt == 0) {
        pos++;
      }
      return true;
    }
    switch (kind()) {
      case GT -> {
        pos++;
        return true;
      }
      case GTGT -> {
        pendingGt = 1;
        return true;
      }
      case GTGTGT -> {
        pendingGt = 2;
        return true;
      }
      default -> {
        return false;
      }
    }
  }

  static String primitive(Token k) {
    return switch (k) {
      case INT -> "int";
      case LONG -> "long";
      case SHORT -> "short";
      case BYTE -> "byte";
      case CHAR -> "char";
      case BOOLEAN -> "boolean";
      case FLOAT -> "float";
      case DOUBLE -> "double";
      case VOID -> "void";
      default -> null;
    };
  }

  /**
   * Walks backwards from {@code end} over something shaped like a type ({@code a.b.C<X>[]}) and
   * returns the index of its first token, or -1.
   */
  static int startBackward(Tokens t, int end) {
    int j = end;
    while (t.is(j, Token.RBRACK) && t.is(j - 1, Token.LBRACK)) {
      j -= 2;
    }
    if (t.is(j, Token.ELLIPSIS)) {
      j--;
    }
    if (primitive(t.kind(j)) != null) {
      return j;
    }
    if (isCloseAngle(t.kind(j))) {
      j = skipTypeArgsBackward(t, j);
      if (j < 0) {
        return -1;
      }
    }
    if (!t.is(j, Token.IDENT)) {
      return -1;
    }
    while (t.is(j - 1, Token.DOT) && t.is(j - 2, Token.IDENT)) {
      j -= 2;
    }
    return j;
  }

  static boolean isCloseAngle(Token k) {
    return k == Token.GT || k == Token.GTGT || k == Token.GTGTGT;
  }

  /** From a closing '>' walks back to the matching '<' and returns the index before it, or -1. */
  static int skipTypeArgsBackward(Tokens t, int close) {
    int depth = 0;
    for (int j = close; j >= 0; j--) {
      switch (t.kind(j)) {
        case GT -> depth += 1;
        case GTGT -> depth += 2;
        case GTGTGT -> depth += 3;
        case LT -> {
          depth--;
          if (depth == 0) {
            return j - 1;
          }
          if (depth < 0) {
            return -1;
          }
        }
        case IDENT, DOT, COMMA, COND, EXTENDS, SUPER, LBRACK, RBRACK, AT, AND -> {}
        default -> {
          if (primitive(t.kind(j)) == null) {
            return -1;
          }
        }
      }
    }
    return -1;
  }
}
