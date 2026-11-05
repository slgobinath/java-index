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

import com.google.turbine.model.TurbineTyKind;
import com.google.turbine.parse.Token;
import dev.javaindex.index.FileSummary;
import dev.javaindex.index.FileSummary.FieldDecl;
import dev.javaindex.index.FileSummary.ImportInfo;
import dev.javaindex.index.FileSummary.MethodDecl;
import dev.javaindex.index.FileSummary.TypeInfo;
import dev.javaindex.index.Lines;
import dev.javaindex.index.Tokens;
import dev.javaindex.model.ClassModel;
import dev.javaindex.model.Member;
import dev.javaindex.model.Target;
import dev.javaindex.model.TypeRef;
import dev.javaindex.model.Universe;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves the identifier under the cursor to its declaration. Declarations come from turbine
 * summaries; method bodies (which turbine skips) are analysed from the token stream: block
 * structure, local variables, lambda parameters, anonymous classes and member-access chains.
 */
public final class Resolver {
  private final Universe u;
  private final Tokens t;
  private final FileSummary file;
  private final Path path;
  private final Lines lines;

  public Resolver(Universe universe, String text, FileSummary file, Path path) {
    this.u = universe;
    this.t = Tokens.lex(text);
    this.lines = Lines.of(text);
    this.file = file;
    this.path = path;
  }

  /** An identifier occurrence and the declarations it refers to (empty if unresolved). */
  public record Occurrence(int start, int end, List<Target> targets) {}

  public List<Target> definition(int offset) {
    Symbol s = symbolAt(offset);
    return s == null || s.target() == null ? List.of() : List.of(s.target());
  }

  /** The symbol under the cursor, or null. */
  public Symbol symbolAt(int offset) {
    int i = t.identAt(offset);
    return i < 0 ? null : resolveToken(i);
  }

  /** The identifier under the cursor, or null. */
  public String nameAt(int offset) {
    int i = t.identAt(offset);
    return i < 0 ? null : t.text(i);
  }

  /** Resolves every occurrence of identifier {@code name} in this file. */
  public List<Occurrence> occurrences(String name) {
    List<Occurrence> out = new ArrayList<>();
    for (int i = 0; i < t.size(); i++) {
      if (!t.isIdent(i, name)) {
        continue;
      }
      Symbol s = safeResolve(i);
      if (s != null && s.kind() == Symbol.Kind.PACKAGE) {
        continue; // a package name segment: neither a reference nor an unresolved use
      }
      List<Target> targets =
          s == null || s.target() == null
              ? List.of() // unresolved: may still be a reference the resolver couldn't follow
              : s.alternate() == null ? List.of(s.target()) : List.of(s.target(), s.alternate());
      out.add(new Occurrence(t.start(i), t.end(i), targets));
    }
    return out;
  }

  /** An anonymous class {@code new T(...) { ... }}: the span of the name T and T's binary name. */
  public record Anonymous(int start, int end, String supertype) {}

  /**
   * The anonymous classes in this file whose instantiated type is written with one of {@code
   * simpleNames} (the last segment of a qualified name), with that type resolved.
   */
  public List<Anonymous> anonymousClasses(Set<String> simpleNames) {
    List<Anonymous> out = new ArrayList<>();
    for (int i = 0; i < t.size(); i++) {
      if (!t.is(i, Token.NEW)) {
        continue;
      }
      int j = i + 1;
      int name = -1;
      while (t.is(j, Token.IDENT)) {
        name = j++;
        if (!t.is(j, Token.DOT)) {
          break;
        }
        j++;
      }
      if (name < 0 || !simpleNames.contains(t.text(name))) {
        continue;
      }
      if (t.is(j, Token.LT)) {
        j = angleEnd(j) + 1;
      }
      if (j <= 0 || !t.is(j, Token.LPAREN) || t.match(j) < 0 || !t.is(t.match(j) + 1, Token.LBRACE)) {
        continue;
      }
      Symbol s = safeResolve(name);
      if (s != null
          && s.owner() != null
          && (s.kind() == Symbol.Kind.CLASS || s.kind() == Symbol.Kind.CONSTRUCTOR)) {
        out.add(new Anonymous(t.start(name), t.end(name), s.owner()));
      }
    }
    return out;
  }

  /** The token closing the type-argument list opened at {@code open}, or -1. */
  private int angleEnd(int open) {
    int depth = 0;
    for (int j = open; j < t.size(); j++) {
      Token k = t.kind(j);
      if (k == Token.LT) {
        depth++;
      } else if (TypeSyntax.isCloseAngle(k)) {
        depth -= k == Token.GT ? 1 : k == Token.GTGT ? 2 : 3;
        if (depth <= 0) {
          return j;
        }
      } else if (k != Token.IDENT && k != Token.DOT && k != Token.COMMA && k != Token.COND
          && k != Token.EXTENDS && k != Token.SUPER && k != Token.LBRACK && k != Token.RBRACK) {
        return -1;
      }
    }
    return -1;
  }

  private Symbol safeResolve(int i) {
    try {
      return resolveToken(i);
    } catch (RuntimeException e) {
      return null; // one unresolvable occurrence shouldn't fail a whole search
    }
  }

  private Symbol resolveToken(int i) {
    Symbol self = declarationAt(t.start(i));
    if (self != null) {
      return self;
    }
    int stmt = statementStart(i);
    if (t.is(stmt, Token.IMPORT)) {
      return importSymbol(stmt, i);
    }
    if (t.is(stmt, Token.PACKAGE)) {
      List<String> names = new ArrayList<>();
      for (int j = stmt + 1; j <= i; j++) {
        if (t.is(j, Token.IDENT)) {
          names.add(t.text(j));
        }
      }
      return Symbol.ofPackage(t.text(i), String.join("/", names));
    }
    return resolveIdent(new Context(i), i);
  }

  // ---------------------------------------------------------------------------------------------
  // Whole-file resolution

  /** An identifier and what it refers to. */
  public record Use(int start, int end, Symbol symbol) {}

  /**
   * Resolves the identifiers in [from, to) that {@code wanted} accepts, in a single pass over the
   * file: the block structure is folded in incrementally instead of being rebuilt per identifier,
   * so the cost grows with file size, not with its square.
   */
  public List<Use> resolveAll(int from, int to, java.util.function.IntPredicate wanted) {
    List<Use> out = new ArrayList<>();
    Context ctx = new Context();
    for (int i = 0; i < t.size() && t.start(i) < to; i++) {
      ctx.advanceTo(i);
      if (!t.is(i, Token.IDENT) || t.start(i) < from || !wanted.test(i)) {
        continue;
      }
      Symbol s;
      try {
        s = declarationAt(t.start(i));
        if (s == null) {
          int stmt = statementStart(i);
          s =
              t.is(stmt, Token.IMPORT) || t.is(stmt, Token.PACKAGE)
                  ? null
                  : resolveIdent(ctx, i);
        } else {
          s = null; // a declaration is not a use
        }
      } catch (RuntimeException e) {
        s = null;
      }
      if (s != null) {
        out.add(new Use(t.start(i), t.end(i), s));
      }
    }
    return out;
  }

  /** Identifiers worth checking for deprecation: member accesses, calls and type names. */
  public boolean isApiUse(int i) {
    return t.is(i - 1, Token.DOT)
        || t.is(i - 1, Token.COLONCOLON)
        || t.is(i + 1, Token.LPAREN)
        || (t.text(i) != null && !t.text(i).isEmpty() && Character.isUpperCase(t.text(i).charAt(0)));
  }

  /** Whether the symbol's declaration (or, for a constructor, its class) is {@code @Deprecated}. */
  public boolean isDeprecated(Symbol s) {
    if (s.hit() != null && s.hit().member().deprecated()) {
      return true;
    }
    if ((s.kind() == Symbol.Kind.CLASS || s.kind() == Symbol.Kind.CONSTRUCTOR) && s.owner() != null) {
      ClassModel m = u.find(s.owner());
      return m != null && m.deprecated();
    }
    return false;
  }

  // ---------------------------------------------------------------------------------------------
  // Calls: signature help, inlay hints, call hierarchy

  /** The call whose argument list contains the cursor. */
  public record Signature(
      Symbol symbol, List<Universe.Hit> overloads, int activeSignature, int activeParameter) {}

  public Signature signatureAt(int offset) {
    int cursor = t.indexAtOrAfter(offset);
    for (int j = cursor - 1; j >= 0; j--) {
      Token k = t.kind(j);
      if (k == Token.SEMI || k == Token.LBRACE || k == Token.RBRACE) {
        return null;
      }
      if (k == Token.RPAREN && t.match(j) >= 0) {
        j = t.match(j);
        continue;
      }
      if (k != Token.LPAREN || !t.is(j - 1, Token.IDENT)) {
        continue;
      }
      Symbol s = safeResolve(j - 1);
      if (s == null || s.overloads().isEmpty() || declarationAt(t.start(j - 1)) != null) {
        continue;
      }
      int active = 0;
      for (int a = j + 1; a < cursor; a++) {
        Token ak = t.kind(a);
        if ((ak == Token.LPAREN || ak == Token.LBRACE || ak == Token.LBRACK)
            && t.match(a) > a
            && t.match(a) < cursor) {
          a = t.match(a);
        } else if (ak == Token.COMMA) {
          active++;
        }
      }
      List<Universe.Hit> overloads = s.overloads();
      int argc = argCount(j);
      int best = 0;
      for (int o = 0; o < overloads.size(); o++) {
        Member m = overloads.get(o).member();
        if (m.arity() == argc && m.arity() > active) {
          best = o;
          break;
        }
        if ((m.arity() > active || m.varargs()) && !overloads.get(best).member().accepts(active + 1)) {
          best = o;
        }
      }
      if (s.hit() != null) {
        int chosen = overloads.indexOf(s.hit());
        if (chosen >= 0 && overloads.get(chosen).member().accepts(active + 1)) {
          best = chosen;
        }
      }
      return new Signature(s, overloads, best, active);
    }
    return null;
  }

  /** An inlay hint: a parameter name before an argument, or a {@code var}'s inferred type. */
  public record Hint(int offset, String label, boolean parameter) {}

  /**
   * Inlay hints for [from, to). {@code names} supplies parameter names for a call (bytecode often
   * lacks them); {@code allArguments} hints every argument rather than just literals.
   */
  public List<Hint> inlayHints(
      int from, int to, java.util.function.Function<Universe.Hit, List<String>> names, boolean allArguments) {
    return inlayHints(from, to, names, allArguments, true);
  }

  /**
   * As above; {@code types} adds inferred types: {@code var} locals, untyped lambda parameters
   * ({@code p -> } shows {@code p: Person}) and, in a member chain split over lines, the type at
   * the end of each line ({@code .map(...)  Stream<String>}).
   */
  public List<Hint> inlayHints(
      int from,
      int to,
      java.util.function.Function<Universe.Hit, List<String>> names,
      boolean allArguments,
      boolean types) {
    List<Hint> out = new ArrayList<>();
    for (int i = t.indexAtOrAfter(from); i < t.size() && t.start(i) < to; i++) {
      if (types && t.is(i, Token.ARROW)) {
        lambdaHints(i, out);
      }
      if (types && endsChainLine(i)) {
        TypeRef type = chainType(i);
        if (type != null) {
          out.add(new Hint(t.end(i), type.display(), false));
        }
      }
      if (t.isIdent(i, "var") && t.is(i + 1, Token.IDENT)
          && (t.is(i + 2, Token.ASSIGN) || t.is(i + 2, Token.COLON))) {
        Symbol local = safeResolve(i + 1);
        TypeRef type = local == null ? null : local.type();
        if (type != null) {
          out.add(new Hint(t.end(i + 1), ": " + type.display(), false));
        }
        continue;
      }
      if (!t.is(i, Token.IDENT) || !t.is(i + 1, Token.LPAREN) || t.match(i + 1) < 0) {
        continue;
      }
      if (declarationAt(t.start(i)) != null) {
        continue;
      }
      Symbol s = safeResolve(i);
      Universe.Hit callee = s == null ? null : s.hit() != null && s.kind() != Symbol.Kind.FIELD
          ? s.hit()
          : s.kind() == Symbol.Kind.CLASS ? Universe.pick(s.overloads(), argCount(i + 1)) : null;
      if (callee == null) {
        continue;
      }
      List<String> params = names.apply(callee);
      List<int[]> args = arguments(i + 1);
      for (int a = 0; a < args.size() && a < params.size(); a++) {
        String name = params.get(a);
        int[] arg = args.get(a);
        if (name == null
            || (arg[0] == arg[1] && name.equals(t.text(arg[0])))
            || (!allArguments && !isLiteral(arg[0], arg[1]))) {
          continue;
        }
        out.add(new Hint(t.start(arg[0]), name + ":", true));
        if (callee.member().varargs() && a == params.size() - 1) {
          break;
        }
      }
    }
    return out;
  }

  /** Types of the untyped parameters of the lambda whose arrow is {@code arrow}. */
  private void lambdaHints(int arrow, List<Hint> out) {
    List<Integer> params = new ArrayList<>();
    if (t.is(arrow - 1, Token.IDENT)) {
      params.add(arrow - 1);
    } else if (t.is(arrow - 1, Token.RPAREN) && t.match(arrow - 1) >= 0) {
      int open = t.match(arrow - 1);
      for (int j = open + 1; j < arrow - 1; j++) {
        if (t.is(j, Token.IDENT) && t.is(j - 1, Token.IDENT)) {
          return; // typed parameters: nothing to add
        }
        if (t.is(j, Token.IDENT)) {
          params.add(j);
        }
      }
    }
    for (int p : params) {
      Symbol s = safeResolve(p);
      TypeRef type = s == null || s.kind() != Symbol.Kind.LOCAL ? null : s.type();
      if (type != null) {
        out.add(new Hint(t.end(p), ": " + type.display(), false));
      }
    }
  }

  /** The last token of a line whose chain continues with '.' on the next line. */
  private boolean endsChainLine(int i) {
    if (!t.is(i + 1, Token.DOT) || !(t.is(i, Token.RPAREN) || t.is(i, Token.IDENT) || t.is(i, Token.RBRACK))) {
      return false;
    }
    return lines.line(t.start(i + 1)) > lines.line(t.start(i));
  }

  /** Type of the member chain ending at token {@code end}, or null. */
  private TypeRef chainType(int end) {
    Context saved = active;
    try {
      Context ctx = new Context(end);
      State s = evalChain(ctx, end);
      return s instanceof Value v ? v.type : null;
    } catch (RuntimeException e) {
      return null;
    } finally {
      active = saved;
    }
  }

  private boolean isLiteral(int from, int to) {
    int i = from;
    if (t.is(i, Token.MINUS) && i < to) {
      i++;
    }
    if (i != to) {
      return false;
    }
    return switch (t.kind(i)) {
      case INT_LITERAL, LONG_LITERAL, FLOAT_LITERAL, DOUBLE_LITERAL, CHAR_LITERAL, STRING_LITERAL,
          TRUE, FALSE, NULL -> true;
      default -> false;
    };
  }

  /** Token ranges [first, last] of each argument of the call whose '(' is {@code open}. */
  private List<int[]> arguments(int open) {
    List<int[]> out = new ArrayList<>();
    int close = t.match(open);
    int start = open + 1;
    int angle = 0;
    for (int j = open + 1; j <= close; j++) {
      Token k = t.kind(j);
      if ((k == Token.LPAREN || k == Token.LBRACE || k == Token.LBRACK) && t.match(j) > j && j < close) {
        j = t.match(j);
      } else if (k == Token.LT && t.is(j - 1, Token.IDENT)) {
        angle++;
      } else if (TypeSyntax.isCloseAngle(k) && angle > 0) {
        angle = Math.max(0, angle - (k == Token.GT ? 1 : k == Token.GTGT ? 2 : 3));
      } else if ((k == Token.COMMA && angle == 0) || j == close) {
        if (j > start) {
          out.add(new int[] {start, j - 1});
        }
        start = j + 1;
      }
    }
    return out;
  }

  /** A call site: the callee and the span of its name. */
  public record Call(Symbol callee, int start, int end) {}

  /** Method and constructor calls whose name lies in [from, to). */
  public List<Call> callsIn(int from, int to) {
    List<Call> out = new ArrayList<>();
    for (int i = t.indexAtOrAfter(from); i < t.size() && t.start(i) < to; i++) {
      if (!t.is(i, Token.IDENT)
          || !(t.is(i + 1, Token.LPAREN) || (isAfterNew(i) && t.is(i + 1, Token.LT)))
          || declarationAt(t.start(i)) != null) {
        continue;
      }
      Symbol s = safeResolve(i);
      if (s != null
          && s.target() != null
          && (s.kind() == Symbol.Kind.METHOD
              || s.kind() == Symbol.Kind.CONSTRUCTOR
              || (s.kind() == Symbol.Kind.CLASS && isAfterNew(i)))) {
        out.add(new Call(s, t.start(i), t.end(i)));
      }
    }
    return out;
  }

  // ---------------------------------------------------------------------------------------------
  // Declarations and imports

  private Symbol declarationAt(int start) {
    for (TypeInfo type : file.allTypes()) {
      if (type.span.nameStart() == start) {
        return new Symbol(
            Symbol.Kind.CLASS,
            type.simpleName,
            declared(start, type.span.nameEnd()),
            type.binaryName,
            null,
            () -> u.thisType(type),
            List.of(),
            null);
      }
      boolean field = type.fields.stream().anyMatch(f -> f.span().nameStart() == start);
      boolean method = type.methods.stream().anyMatch(m -> m.span().nameStart() == start);
      if (!field && !method) {
        continue;
      }
      ClassModel model = u.external(type, file, path);
      List<Member> members = field ? model.fields() : model.methods();
      for (Member m : members) {
        if (m.target() instanceof Target.Source src && src.start() == start) {
          Universe.Hit hit = new Universe.Hit(m, Map.of(), type.binaryName);
          List<Universe.Hit> overloads = new ArrayList<>();
          for (Member o : members) {
            if (o.name().equals(m.name()) && !field) {
              overloads.add(new Universe.Hit(o, Map.of(), type.binaryName));
            }
          }
          return Symbol.of(hit, overloads);
        }
      }
      // Members declared but not modelled (shouldn't happen): fall back to the bare location.
      return new Symbol(
          field ? Symbol.Kind.FIELD : Symbol.Kind.METHOD,
          t.text(t.identAt(start)),
          declared(start, start),
          type.binaryName,
          null,
          null,
          List.of(),
          null);
    }
    return null;
  }

  /** A position in the current buffer. */
  private Target here(int start, int end) {
    return new Target.Source(path, lines, start, end);
  }

  /** A position taken from the summary, which may describe an older version of the buffer. */
  private Target declared(int start, int end) {
    return new Target.Source(path, file.lines, start, end);
  }

  private int statementStart(int i) {
    int j = i;
    while (j > 0) {
      Token k = t.kind(j - 1);
      if (k == Token.SEMI || k == Token.LBRACE || k == Token.RBRACE) {
        break;
      }
      j--;
    }
    return j;
  }

  private Symbol importSymbol(int stmt, int i) {
    boolean isStatic = t.is(stmt + 1, Token.STATIC);
    List<String> names = new ArrayList<>();
    for (int j = isStatic ? stmt + 2 : stmt + 1; j <= i; j++) {
      if (t.is(j, Token.IDENT)) {
        names.add(t.text(j));
      }
    }
    String bin = u.resolveCanonical(names);
    if (bin != null) {
      return classSymbol(u.find(bin));
    }
    if (u.packageExists(String.join("/", names))) {
      return Symbol.ofPackage(t.text(i), String.join("/", names));
    }
    if (isStatic && names.size() > 1) {
      String owner = u.resolveCanonical(names.subList(0, names.size() - 1));
      if (owner != null) {
        TypeRef ownerType = TypeRef.ofClass(owner, List.of());
        String name = names.getLast();
        Universe.Hit field = u.findField(ownerType, name);
        if (field != null) {
          return Symbol.of(field, List.of());
        }
        List<Universe.Hit> methods = u.findMethods(ownerType, name);
        if (!methods.isEmpty()) {
          return Symbol.of(methods.get(0), methods);
        }
      }
    }
    return null;
  }

  private Symbol classSymbol(ClassModel m) {
    if (m == null) {
      return null;
    }
    return new Symbol(
        Symbol.Kind.CLASS,
        TypeRef.simpleName(m.binaryName()),
        m.target(),
        m.binaryName(),
        null,
        () -> classType(m),
        List.of(),
        null);
  }

  private static TypeRef classType(ClassModel m) {
    List<TypeRef> args = new ArrayList<>();
    m.typeParams().forEach(p -> args.add(TypeRef.ofVar(p)));
    return TypeRef.ofClass(m.binaryName(), args);
  }

  // ---------------------------------------------------------------------------------------------
  // Block structure

  private enum LocalKind {
    TYPED,
    VAR,
    FOREACH_VAR,
    UNTYPED,
    /** A lambda parameter: {@code from} is the arrow token, {@code to} the parameter's index. */
    LAMBDA,
    LOCAL_CLASS
  }

  private record Local(String name, int nameTok, LocalKind kind, int from, int to) {}

  private static final class Frame {
    final boolean code;
    final TypeInfo info;
    /** For anonymous classes: token range of the instantiated supertype. */
    final int anonFrom;

    final int anonTo;
    final String anonEnum;
    final List<Local> locals = new ArrayList<>();
    final Set<String> tvars = new HashSet<>();
    /** Token ranges of each type variable's bounds ({@code T extends A & B}), declared here. */
    final Map<String, List<int[]>> tvarBounds = new HashMap<>();
    int parenDepth;
    int declFrom = -1;
    int declTo = -1;
    int declParenDepth;
    boolean enumConstantsDone;

    Frame(boolean code, TypeInfo info, int anonFrom, int anonTo, String anonEnum) {
      this.code = code;
      this.info = info;
      this.anonFrom = anonFrom;
      this.anonTo = anonTo;
      this.anonEnum = anonEnum;
    }

    static Frame code() {
      return new Frame(true, null, -1, -1, null);
    }

    boolean isAnonymous() {
      return !code && (anonFrom >= 0 || anonEnum != null);
    }
  }

  /** The frame stack in effect at a token, computed by scanning the file up to it. */
  private final class Context {
    final Deque<Frame> frames = new ArrayDeque<>();
    final int at;
    private int pendingTypeName = -1;

    /** Tokens [0, scanned) have been folded into the frames (streaming use). */
    private int scanned;

    /** An empty context, to be moved forward with {@link #advanceTo}. */
    Context() {
      this.at = -1;
      frames.push(new Frame(false, null, -1, -1, null));
    }

    /** Folds tokens up to and including {@code upTo} into the frames (streaming use). */
    void advanceTo(int upTo) {
      for (; scanned <= upTo && scanned < t.size(); scanned++) {
        step(scanned, false);
      }
    }

    Context(int upTo) {
      this.at = upTo;
      frames.push(new Frame(false, null, -1, -1, null));
      for (int i = 0; i <= upTo && i < t.size(); i++) {
        step(i, i == upTo);
      }
      // A lambda parameter is declared by the '->' after it: look ahead when on one.
      Frame top = frames.peek();
      if (top.code && t.is(upTo, Token.IDENT)) {
        if (t.is(upTo + 1, Token.ARROW)) {
          lambdaParams(top, upTo + 1);
        } else if (t.is(upTo + 1, Token.COMMA) || t.is(upTo + 1, Token.RPAREN)) {
          int open = enclosingParen(upTo);
          int close = open < 0 ? -1 : t.match(open);
          if (close > 0 && t.is(close + 1, Token.ARROW)) {
            lambdaParams(top, close + 1);
          }
        }
      }
    }

    private void step(int i, boolean last) {
      Frame top = frames.peek();
      Token k = t.kind(i);
      switch (k) {
        case CLASS, INTERFACE, ENUM -> {
          if (!t.is(i - 1, Token.DOT) && t.is(i + 1, Token.IDENT)) {
            pendingTypeName = i + 1;
          }
        }
        case IDENT -> {
          if (t.isIdent(i, "record")
              && t.is(i + 1, Token.IDENT)
              && (t.is(i + 2, Token.LPAREN) || t.is(i + 2, Token.LT))) {
            pendingTypeName = i + 1;
          } else if (top.code && i != pendingTypeName) {
            detectLocal(top, i);
          }
        }
        case ARROW -> {
          if (top.code) {
            lambdaParams(top, i);
          }
        }
        case LPAREN -> top.parenDepth++;
        case RPAREN -> top.parenDepth--;
        case SEMI -> {
          top.declFrom = -1;
          if (!top.code && top.parenDepth <= 0) {
            top.enumConstantsDone = true;
          }
        }
        case LBRACE -> {
          if (!last) {
            open(top, i);
          }
        }
        case RBRACE -> {
          if (!last && frames.size() > 1) {
            frames.pop();
          }
        }
        default -> {}
      }
    }

    private void open(Frame top, int brace) {
      if (pendingTypeName >= 0) {
        int nameTok = pendingTypeName;
        pendingTypeName = -1;
        TypeInfo info = null;
        if (!top.code) {
          info = typeInfoFor(namedInfo(), nameTok);
        } else {
          top.locals.add(new Local(t.text(nameTok), nameTok, LocalKind.LOCAL_CLASS, -1, -1));
        }
        Frame f = new Frame(false, info, -1, -1, null);
        if (t.is(nameTok + 1, Token.LT)) {
          typeParams(f, nameTok + 1);
        }
        frames.push(f);
        return;
      }
      int p = brace - 1;
      int header = methodHeader(brace);
      if (!top.code && header >= 0 && !(isEnum(top) && !top.enumConstantsDone)) {
        Frame f = Frame.code();
        methodParams(f, header, t.match(header));
        methodTypeParams(f, header - 1);
        frames.push(f);
        return;
      }
      int anonStart = anonymousType(p);
      if (anonStart >= 0) {
        frames.push(new Frame(false, null, anonStart, t.match(p) - 1, null));
        return;
      }
      if (!top.code
          && isEnum(top)
          && !top.enumConstantsDone
          && (t.is(p, Token.IDENT) || t.is(p, Token.RPAREN))) {
        frames.push(new Frame(false, null, -1, -1, top.info.binaryName));
        return;
      }
      frames.push(Frame.code());
    }

    private boolean isEnum(Frame f) {
      return f.info != null && f.info.kind == TurbineTyKind.ENUM;
    }

    /** For `new a.b.Foo<X>(...) {`, the index of the first type token; else -1. */
    private int anonymousType(int p) {
      if (!t.is(p, Token.RPAREN) || t.match(p) < 0) {
        return -1;
      }
      int end = t.match(p) - 1;
      int start = TypeSyntax.startBackward(t, end);
      return start >= 0 && t.is(start - 1, Token.NEW) ? start : -1;
    }

    /** For a method body brace, the index of the parameter list's '('; else -1. */
    private int methodHeader(int brace) {
      int j = brace - 1;
      int k = j;
      while (k > 0
          && (t.is(k, Token.IDENT)
              || t.is(k, Token.DOT)
              || t.is(k, Token.COMMA)
              || TypeSyntax.isCloseAngle(t.kind(k))
              || t.is(k, Token.LT))) {
        k--;
      }
      if (t.is(k, Token.THROWS)) {
        j = k - 1;
      }
      if (!t.is(j, Token.RPAREN)) {
        return -1;
      }
      int open = t.match(j);
      if (open < 1 || !t.is(open - 1, Token.IDENT)) {
        return -1;
      }
      Token before = t.kind(open - 2);
      return before == Token.NEW || before == Token.DOT ? -1 : open;
    }

    private void methodParams(Frame f, int open, int close) {
      int start = open + 1;
      int depth = 0;
      for (int j = open + 1; j <= close; j++) {
        Token k = t.kind(j);
        if (k == Token.LT) {
          depth++;
        } else if (TypeSyntax.isCloseAngle(k)) {
          depth -= k == Token.GT ? 1 : k == Token.GTGT ? 2 : 3;
        } else if ((k == Token.LPAREN) && t.match(j) > j) {
          j = t.match(j);
        } else if ((k == Token.COMMA && depth <= 0) || j == close) {
          int nameTok = j - 1;
          if (nameTok > start && t.is(nameTok, Token.IDENT)) {
            f.locals.add(new Local(t.text(nameTok), nameTok, LocalKind.TYPED, start, nameTok - 1));
          }
          start = j + 1;
        }
      }
    }

    /** Collects `<T, U extends X>` declared before a method's return type. */
    private void methodTypeParams(Frame f, int nameTok) {
      int j = nameTok - 1;
      int floor = statementStart(nameTok);
      for (int k = floor; k < j; k++) {
        if (t.is(k, Token.LT)) {
          typeParams(f, k);
          return;
        }
        if (t.is(k, Token.IDENT) || t.is(k, Token.LPAREN)) {
          if (!(t.is(k - 1, Token.AT) || t.is(k - 1, Token.DOT))) {
            return; // reached the return type: no type parameters
          }
        }
      }
    }

    /** Records the type parameters, and their bounds, of the list opened by '<' at {@code open}. */
    private void typeParams(Frame f, int open) {
      int depth = 0;
      String current = null;
      int boundFrom = -1;
      for (int m = open; m < t.size(); m++) {
        Token k = t.kind(m);
        if (k == Token.LT) {
          depth++;
        } else if (TypeSyntax.isCloseAngle(k)) {
          int closes = k == Token.GT ? 1 : k == Token.GTGT ? 2 : 3;
          if (depth - closes <= 0) {
            addBound(f, current, boundFrom, m - 1);
            return;
          }
          depth -= closes;
        } else if (depth == 1 && (k == Token.COMMA || k == Token.AND)) {
          addBound(f, current, boundFrom, m - 1);
          boundFrom = m + 1;
          if (k == Token.COMMA) {
            current = null;
            boundFrom = -1;
          }
        } else if (depth == 1 && current == null && k == Token.IDENT) {
          current = t.text(m);
          f.tvars.add(current);
        } else if (depth == 1 && k == Token.EXTENDS) {
          boundFrom = m + 1;
        } else if (k == Token.LBRACE || k == Token.SEMI || k == Token.LPAREN) {
          return;
        }
      }
    }

    private void addBound(Frame f, String name, int from, int to) {
      if (name != null && from >= 0 && from <= to) {
        f.tvarBounds.computeIfAbsent(name, n -> new ArrayList<>()).add(new int[] {from, to});
      }
    }

    /**
     * The bounds of type variable {@code name} declared in an enclosing method or class of this
     * file, innermost first: {@code Object} when it has none, empty when it is not declared here.
     */
    List<TypeRef> bounds(String name, int depth) {
      for (Frame f : frames) {
        List<int[]> ranges = f.tvarBounds.get(name);
        if (ranges != null) {
          List<TypeRef> out = new ArrayList<>();
          for (int[] r : ranges) {
            TypeRef b = TypeSyntax.resolve(TypeSyntax.parse(t, r[0], r[1]), u, scope());
            if (b != null && b.tvar() != null && depth < 4) {
              out.addAll(bounds(b.tvar(), depth + 1));
            } else if (b != null) {
              out.add(b);
            }
          }
          return out.isEmpty() ? List.of(TypeRef.OBJECT) : out;
        }
        if (f.tvars.contains(name)) {
          return List.of(TypeRef.OBJECT);
        }
      }
      return List.of();
    }

    private void detectLocal(Frame top, int i) {
      Token next = t.kind(i + 1);
      int typeEnd = i - 1;
      if (t.is(typeEnd, Token.COMMA)
          && top.declFrom >= 0
          && top.parenDepth == top.declParenDepth
          && (next == Token.ASSIGN || next == Token.SEMI || next == Token.COMMA)) {
        top.locals.add(new Local(t.text(i), i, LocalKind.TYPED, top.declFrom, top.declTo));
        return;
      }
      int typeStart = TypeSyntax.startBackward(t, typeEnd);
      if (typeStart < 0) {
        return;
      }
      Token pred = t.kind(typeStart - 1);
      boolean pattern = pred == Token.INSTANCEOF || pred == Token.CASE;
      if (!pattern
          && next != Token.ASSIGN
          && next != Token.SEMI
          && next != Token.COMMA
          && next != Token.COLON
          && next != Token.RPAREN) {
        return;
      }
      boolean annotated = pred == Token.IDENT && t.is(typeStart - 2, Token.AT);
      boolean allowed =
          switch (pred) {
            case LBRACE, RBRACE, SEMI, LPAREN, COMMA, FINAL, RPAREN, COLON, INSTANCEOF, CASE ->
                true;
            default -> annotated || typeStart == 0;
          };
      if (!allowed) {
        return;
      }
      if (typeStart == typeEnd && t.is(typeStart, Token.IDENT)) {
        String typeName = t.text(typeStart);
        if (typeName.equals("yield")) {
          return;
        }
        if (typeName.equals("var")) {
          if (next == Token.ASSIGN) {
            top.locals.add(new Local(t.text(i), i, LocalKind.VAR, i + 2, expressionEnd(i + 2)));
          } else if (next == Token.COLON) {
            int forOpen = enclosingParen(i);
            int end = forOpen >= 0 ? t.match(forOpen) - 1 : expressionEnd(i + 2);
            top.locals.add(new Local(t.text(i), i, LocalKind.FOREACH_VAR, i + 2, end));
          } else {
            top.locals.add(new Local(t.text(i), i, LocalKind.UNTYPED, -1, -1));
          }
          return;
        }
      }
      top.locals.add(new Local(t.text(i), i, LocalKind.TYPED, typeStart, typeEnd));
      if (next == Token.ASSIGN || next == Token.COMMA) {
        top.declFrom = typeStart;
        top.declTo = typeEnd;
        top.declParenDepth = top.parenDepth;
      }
    }

    private void lambdaParams(Frame top, int arrow) {
      int p = arrow - 1;
      if (t.is(p, Token.IDENT)) {
        // Not a lambda when it's a switch label: `case A ->`, `case A, B ->`, `case X.Y ->`.
        int j = p - 1;
        while (t.is(j, Token.IDENT) || t.is(j, Token.COMMA) || t.is(j, Token.DOT)) {
          j--;
        }
        if (!t.is(j, Token.CASE) && !t.is(p - 1, Token.DOT)) {
          top.locals.add(new Local(t.text(p), p, LocalKind.LAMBDA, arrow, 0));
        }
        return;
      }
      if (t.is(p, Token.RPAREN) && t.match(p) >= 0) {
        int open = t.match(p);
        for (int j = open + 1; j < p; j++) {
          // `(a, b)` alternates names and commas; anything else, e.g. `(String s)`, is typed and
          // its parameters were already picked up as declarations.
          boolean untyped =
              t.is(j, Token.IDENT) ? !t.is(j - 1, Token.IDENT) : t.is(j, Token.COMMA);
          if (!untyped) {
            return;
          }
        }
        int index = 0;
        for (int j = open + 1; j < p; j++) {
          if (t.is(j, Token.IDENT)) {
            top.locals.add(new Local(t.text(j), j, LocalKind.LAMBDA, arrow, index++));
          }
        }
      }
    }

    TypeInfo namedInfo() {
      for (Frame f : frames) {
        if (f.info != null) {
          return f.info;
        }
      }
      return null;
    }

    Universe.Scope scope() {
      Set<String> tvars = new HashSet<>();
      for (Frame f : frames) {
        tvars.addAll(f.tvars);
      }
      return new Universe.Scope(file, namedInfo(), tvars);
    }
  }

  private TypeInfo typeInfoFor(TypeInfo parent, int nameTok) {
    int start = t.start(nameTok);
    String name = t.text(nameTok);
    List<TypeInfo> candidates = parent == null ? file.types : parent.members;
    for (TypeInfo c : candidates) {
      if (c.span.nameStart() == start) {
        return c;
      }
    }
    // The summary may be from an older (parseable) version of the buffer: fall back to names.
    for (TypeInfo c : candidates) {
      if (c.simpleName.equals(name)) {
        return c;
      }
    }
    return null;
  }

  private int expressionEnd(int from) {
    for (int j = from; j < t.size(); j++) {
      Token k = t.kind(j);
      if ((k == Token.LPAREN || k == Token.LBRACE || k == Token.LBRACK) && t.match(j) > j) {
        j = t.match(j);
        continue;
      }
      if (k == Token.SEMI || k == Token.COMMA || k == Token.RPAREN || k == Token.RBRACE) {
        return j - 1;
      }
    }
    return t.size() - 1;
  }

  private int enclosingParen(int i) {
    for (int j = i; j >= 0; j--) {
      if (t.is(j, Token.LPAREN) && t.match(j) > i) {
        return j;
      }
      if (t.is(j, Token.RPAREN) && t.match(j) >= 0) {
        j = t.match(j);
      }
    }
    return -1;
  }

  // ---------------------------------------------------------------------------------------------
  // Resolution

  private sealed interface State {}

  private record Pkg(String name) implements State {}

  private record Static(String binaryName) implements State {}

  /** A value of {@code type}; {@code more} holds the other bounds of an intersection type. */
  private record Value(TypeRef type, List<TypeRef> more) implements State {
    Value(TypeRef type) {
      this(type, List.of());
    }

    List<TypeRef> types() {
      if (more.isEmpty()) {
        return List.of(type);
      }
      List<TypeRef> all = new ArrayList<>();
      all.add(type);
      all.addAll(more);
      return all;
    }
  }

  private Symbol resolveIdent(Context ctx, int i) {
    active = ctx;
    int dot = qualifierDot(i);
    boolean call = t.is(i + 1, Token.LPAREN);
    if (dot >= 0) {
      State q = evalChain(ctx, dot - 1);
      if (q == null) {
        return null;
      }
      return member(q, i, call, t.is(dot, Token.COLONCOLON), isAfterNew(i));
    }
    return unqualified(ctx, i, call);
  }

  /** The '.' or '::' qualifying identifier {@code i}, skipping explicit type arguments. */
  private int qualifierDot(int i) {
    int p = i - 1;
    if (TypeSyntax.isCloseAngle(t.kind(p))) {
      int before = TypeSyntax.skipTypeArgsBackward(t, p);
      if (before >= 0 && t.is(before, Token.DOT)) {
        return before;
      }
      return -1;
    }
    return t.is(p, Token.DOT) || t.is(p, Token.COLONCOLON) ? p : -1;
  }

  /** True when {@code i} is the last name in {@code new a.b.Name(}. */
  private boolean isAfterNew(int i) {
    int j = i;
    while (t.is(j - 1, Token.DOT) && t.is(j - 2, Token.IDENT)) {
      j -= 2;
    }
    return t.is(j - 1, Token.NEW);
  }

  private Symbol member(State q, int i, boolean call, boolean methodRef, boolean ctor) {
    String name = t.text(i);
    switch (q) {
      case Pkg p -> {
        String bin = p.name + "/" + name;
        if (u.classExists(bin)) {
          return classOrConstructor(bin, i, ctor);
        }
        return u.packageExists(bin) ? Symbol.ofPackage(name, bin) : null;
      }
      case Static s -> {
        TypeRef owner = TypeRef.ofClass(s.binaryName, List.of());
        if (call || methodRef) {
          return method(owner, name, call ? i + 1 : -1);
        }
        Universe.Hit f = u.findField(owner, name);
        if (f != null) {
          return Symbol.of(f, List.of());
        }
        String nested = u.findMemberType(s.binaryName, name);
        return nested == null ? null : classOrConstructor(nested, i, ctor);
      }
      case Value v -> {
        if (call || methodRef) {
          List<Universe.Hit> hits = overridesRemoved(methodsOf(v, name));
          Universe.Hit h = choose(hits, call ? i + 1 : -1);
          return h == null ? null : callSymbol(h, hits, call ? i + 1 : -1);
        }
        Universe.Hit f = fieldOf(v, name);
        return f == null ? null : Symbol.of(f, List.of());
      }
    }
  }

  /** {@code open} is the call's '(' token, or -1 for a method reference. */
  private Symbol method(TypeRef owner, String name, int open) {
    List<Universe.Hit> hits = overridesRemoved(u.findMethods(owner, name));
    Universe.Hit h = choose(hits, open);
    return h == null ? null : callSymbol(h, hits, open);
  }

  // ---------------------------------------------------------------------------------------------
  // Overload selection

  /** The context of the identifier being resolved, for typing call arguments. */
  private Context active;

  /** Picks the overload that best fits the arguments of the call whose '(' is {@code open}. */
  private Universe.Hit choose(List<Universe.Hit> hits, int open) {
    int argc = open < 0 ? -1 : argCount(open);
    List<Universe.Hit> fitting = new ArrayList<>();
    for (Universe.Hit h : hits) {
      if (h.member().accepts(argc)) {
        fitting.add(h);
      }
    }
    if (fitting.size() <= 1 || open < 0) {
      return Universe.pick(hits, argc);
    }
    List<TypeRef> args = new ArrayList<>();
    for (int[] a : arguments(open)) {
      args.add(argType(a[0], a[1]));
    }
    Universe.Hit best = null;
    int bestScore = Integer.MIN_VALUE;
    for (Universe.Hit h : fitting) {
      List<Member.Param> params = h.params();
      int score = h.member().arity() == argc ? 1 : 0;
      for (int k = 0; k < args.size() && !params.isEmpty(); k++) {
        TypeRef param = params.get(Math.min(k, params.size() - 1)).type();
        boolean spread =
            h.member().varargs()
                && k >= params.size() - 1
                && (argc != params.size() || (args.get(k) != null && args.get(k).dims() == 0));
        if (spread && param != null) {
          param = param.component();
        }
        score += score(args.get(k), param);
      }
      if (score > bestScore) {
        bestScore = score;
        best = h;
      }
    }
    return best;
  }

  private TypeRef argType(int from, int to) {
    int i = from;
    boolean negative = t.is(i, Token.MINUS) && i < to;
    if (negative) {
      i++;
    }
    if (i == to) {
      String prim =
          switch (t.kind(i)) {
            case INT_LITERAL -> "int";
            case LONG_LITERAL -> "long";
            case FLOAT_LITERAL -> "float";
            case DOUBLE_LITERAL -> "double";
            case CHAR_LITERAL -> "char";
            case TRUE, FALSE -> "boolean";
            default -> null;
          };
      if (prim != null) {
        return TypeRef.ofPrim(prim);
      }
      if (t.is(i, Token.STRING_LITERAL)) {
        return TypeRef.STRING;
      }
    }
    if (active == null) {
      return null;
    }
    try {
      State s = evalRange(active, from, to);
      if (s instanceof Value v) {
        return v.type;
      }
    } catch (RuntimeException e) {
      // unknown argument type
    }
    // value() hides primitives (they have no members); recover a primitive local's type.
    if (from == to && t.is(from, Token.IDENT)) {
      Object var = variable(active, t.text(from), from);
      if (var instanceof Local l) {
        return localType(active, l);
      }
      if (var instanceof Universe.Hit h) {
        return h.type();
      }
    }
    return null;
  }

  private static final List<String> NUMERIC = List.of("byte", "short", "char", "int", "long", "float", "double");

  private static boolean widens(String from, String to) {
    if (from.equals(to)) {
      return true;
    }
    if (from.equals("boolean") || to.equals("boolean") || to.equals("char")) {
      return false;
    }
    if (from.equals("char") && to.equals("short")) {
      return false;
    }
    return NUMERIC.indexOf(from) < NUMERIC.indexOf(to) && !(from.equals("byte") && to.equals("char"));
  }

  private static String boxed(String prim) {
    return switch (prim) {
      case "int" -> "java/lang/Integer";
      case "char" -> "java/lang/Character";
      default ->
          "java/lang/" + Character.toUpperCase(prim.charAt(0)) + prim.substring(1);
    };
  }

  /** How well an argument fits a parameter: higher is better, negative is a mismatch. */
  private int score(TypeRef arg, TypeRef param) {
    if (arg == null || param == null) {
      return 0;
    }
    if (param.tvar() != null) {
      return 1;
    }
    boolean argPrim = arg.prim() != null && arg.dims() == 0;
    boolean paramPrim = param.prim() != null && param.dims() == 0;
    if (argPrim && paramPrim) {
      return arg.prim().equals(param.prim()) ? 4 : widens(arg.prim(), param.prim()) ? 3 : -10;
    }
    if (argPrim) {
      if (param.isClass() && param.cls().equals(boxed(arg.prim()))) {
        return 2;
      }
      return param.isClass()
              && (param.cls().equals("java/lang/Object") || param.cls().equals("java/lang/Number"))
          ? 1
          : -10;
    }
    if (paramPrim) {
      return arg.isClass() && arg.cls().equals(boxed(param.prim())) ? 2 : -10;
    }
    if (param.isClass() && param.cls().equals("java/lang/Object")) {
      return 1;
    }
    if (arg.dims() != param.dims() || arg.cls() == null || param.cls() == null) {
      return -10;
    }
    if (arg.cls().equals(param.cls())) {
      return 4;
    }
    return u.asSuper(TypeRef.ofClass(arg.cls(), List.of()), param.cls()) != null ? 3 : -10;
  }

  /** Drops supertype methods overridden by a nearer one with the same parameter types. */
  private static List<Universe.Hit> overridesRemoved(List<Universe.Hit> hits) {
    List<Universe.Hit> out = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (Universe.Hit h : hits) {
      StringBuilder key = new StringBuilder();
      for (Member.Param p : h.params()) {
        key.append(p.type() == null ? "?" : p.type().display()).append(',');
      }
      if (seen.add(key.toString())) {
        out.add(h);
      }
    }
    return out;
  }

  private Symbol classOrConstructor(String bin, int i, boolean ctor) {
    ClassModel m = u.find(bin);
    if (m == null) {
      return null;
    }
    Symbol cls = classSymbol(m);
    if (ctor && (t.is(i + 1, Token.LPAREN) || t.is(i + 1, Token.LT))) {
      int open = t.is(i + 1, Token.LPAREN) ? i + 1 : constructorParen(i + 1);
      List<Universe.Hit> ctors = new ArrayList<>();
      for (Member meth : m.methods()) {
        if (meth.kind() == Member.Kind.CONSTRUCTOR) {
          ctors.add(new Universe.Hit(meth, Map.of(), bin));
        }
      }
      Universe.Hit h = choose(ctors, open);
      // Prefer the class itself when it declares no constructor (or for bytecode-only classes,
      // where jumping to the class header is more useful than a stub constructor).
      if (h != null && h.member().target() instanceof Target.Source) {
        Symbol c = Symbol.of(h, ctors);
        return new Symbol(
            c.kind(), c.name(), c.target(), bin, h, cls.typeSupplier(), ctors, cls.target());
      }
      return new Symbol(
          Symbol.Kind.CLASS, cls.name(), cls.target(), bin, null, cls.typeSupplier(), ctors, null);
    }
    return cls;
  }

  private int constructorParen(int angle) {
    int depth = 0;
    for (int j = angle; j < t.size(); j++) {
      Token k = t.kind(j);
      if (k == Token.LT) {
        depth++;
      } else if (TypeSyntax.isCloseAngle(k)) {
        depth -= k == Token.GT ? 1 : k == Token.GTGT ? 2 : 3;
        if (depth <= 0) {
          return t.is(j + 1, Token.LPAREN) ? j + 1 : -1;
        }
      }
    }
    return -1;
  }

  private int argCount(int open) {
    int close = t.match(open);
    if (close < 0) {
      return -1;
    }
    if (close == open + 1) {
      return 0;
    }
    int count = 1;
    int angle = 0;
    for (int j = open + 1; j < close; j++) {
      Token k = t.kind(j);
      if ((k == Token.LPAREN || k == Token.LBRACE || k == Token.LBRACK) && t.match(j) > j) {
        j = t.match(j);
      } else if (k == Token.LT && t.is(j - 1, Token.IDENT)) {
        angle++;
      } else if (TypeSyntax.isCloseAngle(k) && angle > 0) {
        angle = Math.max(0, angle - (k == Token.GT ? 1 : k == Token.GTGT ? 2 : 3));
      } else if (k == Token.COMMA && angle == 0) {
        count++;
      }
    }
    return count;
  }

  private Symbol unqualified(Context ctx, int i, boolean call) {
    String name = t.text(i);
    if (isAfterNew(i)) {
      String bin = typeName(ctx, name);
      return bin == null ? null : classOrConstructor(bin, i, true);
    }
    if (t.is(i - 1, Token.AT)) {
      String bin = typeName(ctx, name);
      return bin == null ? null : classSymbol(u.find(bin));
    }
    if (call) {
      List<Universe.Hit> hits = overridesRemoved(unqualifiedMethods(ctx, name));
      Universe.Hit h = choose(hits, i + 1);
      return h == null ? null : callSymbol(h, hits, i + 1);
    }
    Object v = variable(ctx, name, i);
    if (v instanceof Local local) {
      return new Symbol(
          Symbol.Kind.LOCAL,
          name,
          here(t.start(local.nameTok), t.end(local.nameTok)),
          null,
          null,
          () -> localType(ctx, local),
          List.of(),
          null);
    }
    if (v instanceof Universe.Hit h) {
      return Symbol.of(h, List.of());
    }
    for (Frame f : ctx.frames) {
      for (Local l : f.locals) {
        if (l.kind == LocalKind.LOCAL_CLASS && l.name.equals(name)) {
          return new Symbol(
              Symbol.Kind.CLASS,
              name,
              here(t.start(l.nameTok), t.end(l.nameTok)),
              null,
              null,
              null,
              List.of(),
              null);
        }
      }
    }
    String bin = typeName(ctx, name);
    if (bin == null && t.is(i + 1, Token.DOT) && u.packageExists(name)) {
      return Symbol.ofPackage(name, name); // first segment of a qualified name: ai.signal1...
    }
    return bin == null ? null : classSymbol(u.find(bin));
  }

  private String typeName(Context ctx, String name) {
    Universe.Scope scope = ctx.scope();
    if (u.isTypeVar(name, scope)) {
      return null;
    }
    return u.resolveSimpleTypeName(name, scope);
  }

  /** The types whose members are in scope, innermost first, interleaved with code frames. */
  private TypeRef frameType(Context ctx, Frame f) {
    if (f.info != null) {
      return u.thisType(f.info);
    }
    if (f.anonEnum != null) {
      return TypeRef.ofClass(f.anonEnum, List.of());
    }
    if (f.anonFrom >= 0) {
      return TypeSyntax.resolve(TypeSyntax.parse(t, f.anonFrom, f.anonTo), u, ctx.scope());
    }
    return null;
  }

  /** A local (Local) or field (Universe.Hit) named {@code name} visible at token {@code at}. */
  private Object variable(Context ctx, String name, int at) {
    for (Frame f : ctx.frames) {
      if (f.code) {
        for (int j = f.locals.size() - 1; j >= 0; j--) {
          Local l = f.locals.get(j);
          if (l.kind != LocalKind.LOCAL_CLASS && l.name.equals(name) && l.nameTok <= at) {
            return l;
          }
        }
      } else {
        TypeRef type = frameType(ctx, f);
        if (type != null) {
          Universe.Hit h = u.findField(type, name);
          if (h != null) {
            return h;
          }
        }
      }
    }
    return staticImportField(name);
  }

  /** Candidate methods for an unqualified call: the nearest enclosing class that has any. */
  private List<Universe.Hit> unqualifiedMethods(Context ctx, String name) {
    for (Frame f : ctx.frames) {
      if (f.code) {
        continue;
      }
      TypeRef type = frameType(ctx, f);
      if (type == null) {
        continue;
      }
      List<Universe.Hit> hits = u.findMethods(type, name);
      if (!hits.isEmpty()) {
        return hits;
      }
    }
    List<Universe.Hit> hits = new ArrayList<>();
    for (String owner : staticImportOwners(name)) {
      hits.addAll(u.findMethods(TypeRef.ofClass(owner, List.of()), name));
    }
    return hits;
  }

  private Universe.Hit staticImportField(String name) {
    for (String owner : staticImportOwners(name)) {
      Universe.Hit h = u.findField(TypeRef.ofClass(owner, List.of()), name);
      if (h != null) {
        return h;
      }
    }
    return null;
  }

  private List<String> staticImportOwners(String name) {
    List<String> exact = new ArrayList<>();
    List<String> wild = new ArrayList<>();
    for (ImportInfo imp : file.imports) {
      if (!imp.isStatic()) {
        continue;
      }
      if (imp.wild()) {
        String owner = u.resolveCanonical(imp.names());
        if (owner != null) {
          wild.add(owner);
        }
      } else if (imp.names().getLast().equals(name)) {
        String owner = u.resolveCanonical(imp.names().subList(0, imp.names().size() - 1));
        if (owner != null) {
          exact.add(owner);
        }
      }
    }
    exact.addAll(wild);
    return exact;
  }

  // ---------------------------------------------------------------------------------------------
  // Expression typing

  private enum SegKind {
    NAME,
    CALL,
    INDEX,
    THIS,
    SUPER,
    NEW,
    PAREN,
    STRING,
    CLASS_LITERAL
  }

  private record Seg(SegKind kind, int tok, int from, int to) {}

  /** Parses the member-access chain ending at {@code end} into segments (in source order). */
  private List<Seg> chainBackward(int end, int[] startOut) {
    List<Seg> segs = new ArrayList<>();
    int j = end;
    while (j >= 0) {
      Token k = t.kind(j);
      int segStart;
      switch (k) {
        case IDENT -> {
          segs.add(new Seg(SegKind.NAME, j, j, j));
          segStart = j;
        }
        case RPAREN -> {
          int open = t.match(j);
          if (open < 1) {
            return null;
          }
          int b = open - 1;
          int typeStart =
              t.is(b, Token.IDENT) || TypeSyntax.isCloseAngle(t.kind(b))
                  ? TypeSyntax.startBackward(t, b)
                  : -1;
          if (typeStart >= 0 && t.is(typeStart - 1, Token.NEW)) {
            segs.add(new Seg(SegKind.NEW, b, typeStart, b));
            segStart = typeStart - 1;
          } else if (t.is(b, Token.IDENT)) {
            segs.add(new Seg(SegKind.CALL, b, open, j));
            segStart = b;
            if (TypeSyntax.isCloseAngle(t.kind(b - 1))) {
              int before = TypeSyntax.skipTypeArgsBackward(t, b - 1);
              if (before < 0) {
                return null;
              }
              segStart = before + 1;
            }
          } else if (t.is(b, Token.THIS) || t.is(b, Token.SUPER)) {
            return null;
          } else {
            segs.add(new Seg(SegKind.PAREN, j, open, j));
            segStart = open;
          }
        }
        case RBRACK -> {
          int open = t.match(j);
          if (open < 1) {
            return null;
          }
          segs.add(new Seg(SegKind.INDEX, j, open, j));
          j = open - 1;
          continue;
        }
        case RBRACE -> {
          // `new Foo() { ... }.bar()`: skip the anonymous body.
          int open = t.match(j);
          if (open < 1) {
            return null;
          }
          j = open - 1;
          continue;
        }
        case THIS -> {
          segs.add(new Seg(SegKind.THIS, j, j, j));
          segStart = j;
        }
        case SUPER -> {
          segs.add(new Seg(SegKind.SUPER, j, j, j));
          segStart = j;
        }
        case STRING_LITERAL -> {
          segs.add(new Seg(SegKind.STRING, j, j, j));
          segStart = j;
        }
        case CLASS -> {
          segs.add(new Seg(SegKind.CLASS_LITERAL, j, j, j));
          segStart = j;
        }
        default -> {
          return null;
        }
      }
      if (t.is(segStart - 1, Token.DOT) && segs.getLast().kind != SegKind.STRING) {
        j = segStart - 2;
      } else {
        startOut[0] = segStart;
        List<Seg> forward = new ArrayList<>(segs);
        Collections.reverse(forward);
        return forward;
      }
    }
    return null;
  }

  private State evalChain(Context ctx, int end) {
    int[] start = new int[1];
    List<Seg> segs = chainBackward(end, start);
    return segs == null || segs.isEmpty() ? null : fold(ctx, segs);
  }

  private State fold(Context ctx, List<Seg> segs) {
    Context saved = active;
    active = ctx; // argument typing during overload choice happens in this context
    try {
      Iterator<Seg> it = segs.iterator();
      State s = first(ctx, it.next());
      while (s != null && it.hasNext()) {
        s = next(ctx, s, it.next());
      }
      return s;
    } finally {
      active = saved;
    }
  }

  private State first(Context ctx, Seg seg) {
    return switch (seg.kind) {
      case NAME -> {
        String name = t.text(seg.tok);
        Object v = variable(ctx, name, seg.tok);
        if (v instanceof Local l) {
          yield value(ctx, localType(ctx, l));
        }
        if (v instanceof Universe.Hit h) {
          yield value(ctx, memberType(h, h.type()));
        }
        String bin = typeName(ctx, name);
        yield bin != null ? new Static(bin) : new Pkg(name);
      }
      case CALL -> {
        Universe.Hit h = choose(overridesRemoved(unqualifiedMethods(ctx, t.text(seg.tok))), seg.from);
        yield h == null ? null : value(ctx, callType(h, seg.from));
      }
      case THIS -> {
        for (Frame f : ctx.frames) {
          if (!f.code) {
            TypeRef type = frameType(ctx, f);
            if (type != null) {
              yield new Value(type);
            }
          }
        }
        yield null;
      }
      case SUPER -> {
        TypeInfo info = ctx.namedInfo();
        ClassModel m = info == null ? null : u.find(info.binaryName);
        yield m == null || m.supertypes().isEmpty() ? null : new Value(m.supertypes().get(0));
      }
      case NEW ->
          value(TypeSyntax.resolve(TypeSyntax.parse(t, seg.from, seg.to), u, ctx.scope()));
      case PAREN -> evalRange(ctx, seg.from + 1, seg.to - 1);
      case STRING -> new Value(TypeRef.STRING);
      default -> null;
    };
  }

  private State next(Context ctx, State s, Seg seg) {
    switch (seg.kind) {
      case NAME -> {
        String name = t.text(seg.tok);
        return switch (s) {
          case Pkg p -> {
            String bin = p.name + "/" + name;
            yield u.classExists(bin) ? new Static(bin) : new Pkg(bin);
          }
          case Static st -> {
            Universe.Hit f = u.findField(TypeRef.ofClass(st.binaryName, List.of()), name);
            if (f != null) {
              yield value(f.type());
            }
            String nested = u.findMemberType(st.binaryName, name);
            yield nested == null ? null : new Static(nested);
          }
          case Value v -> {
            Universe.Hit f = fieldOf(v, name);
            yield f == null ? null : value(ctx, memberType(f, f.type()));
          }
        };
      }
      case CALL -> {
        List<Universe.Hit> hits =
            switch (s) {
              case Static st -> u.findMethods(TypeRef.ofClass(st.binaryName, List.of()), t.text(seg.tok));
              case Value v -> methodsOf(v, t.text(seg.tok));
              case Pkg p -> null;
            };
        if (hits == null) {
          return null;
        }
        Universe.Hit h = choose(overridesRemoved(hits), seg.from);
        return h == null ? null : value(ctx, callType(h, seg.from));
      }
      case INDEX -> {
        return s instanceof Value v && v.type.dims() > 0 ? value(v.type.component()) : null;
      }
      case THIS -> {
        if (s instanceof Static st) {
          ClassModel m = u.find(st.binaryName);
          List<TypeRef> args = new ArrayList<>();
          if (m != null) {
            m.typeParams().forEach(p -> args.add(TypeRef.ofVar(p)));
          }
          return new Value(TypeRef.ofClass(st.binaryName, args));
        }
        return null;
      }
      case SUPER -> {
        return s instanceof Static st ? new Value(TypeRef.ofClass(st.binaryName, List.of())) : null;
      }
      case CLASS_LITERAL -> {
        if (s instanceof Static st) {
          return new Value(
              TypeRef.ofClass(
                  "java/lang/Class", List.of(TypeRef.ofClass(st.binaryName, List.of()))));
        }
        return null;
      }
      default -> {
        return null;
      }
    }
  }

  private static Value value(TypeRef type) {
    if (TypeRef.WILDCARD.equals(type)) {
      return new Value(TypeRef.OBJECT);
    }
    return type == null || type.tvar() != null ? null : new Value(type);
  }

  /** Like {@link #value(TypeRef)}, but a type variable declared in scope stands for its bounds. */
  private static Value value(Context ctx, TypeRef type) {
    if (type == null || type.tvar() == null || type.dims() > 0 || TypeRef.WILDCARD.equals(type)) {
      return value(type);
    }
    List<TypeRef> bounds = ctx.bounds(type.tvar(), 0);
    return bounds.isEmpty() ? null : new Value(bounds.get(0), bounds.subList(1, bounds.size()));
  }

  /**
   * A member's type where the owner's type parameter it is declared as was filled in by {@code ?}:
   * that parameter's declared bound, as seen from the owner (so a self type {@code SELF extends
   * AbstractAssert<SELF, ...>} stays usable along a chain of calls).
   */
  private TypeRef memberType(Universe.Hit h, TypeRef type) {
    if (!TypeRef.WILDCARD.equals(type) || h.member().type() == null) {
      return type;
    }
    String declared = h.member().type().tvar();
    ClassModel owner = declared == null ? null : u.find(h.owner());
    TypeRef bound = owner == null ? null : owner.typeParamBounds().get(declared);
    return bound == null ? TypeRef.OBJECT : bound.subst(h.bindings());
  }

  private List<Universe.Hit> methodsOf(Value v, String name) {
    for (TypeRef type : v.types()) {
      List<Universe.Hit> hits = u.findMethods(type, name);
      if (!hits.isEmpty()) {
        return hits;
      }
    }
    return List.of();
  }

  private Universe.Hit fieldOf(Value v, String name) {
    for (TypeRef type : v.types()) {
      Universe.Hit f = u.findField(type, name);
      if (f != null) {
        return f;
      }
    }
    return null;
  }

  /** Types an expression occupying exactly tokens [from, to]. */
  private State evalRange(Context ctx, int from, int to) {
    if (from > to) {
      return null;
    }
    if (t.is(from, Token.LPAREN) && t.match(from) > from && t.match(from) < to) {
      // A cast: (Type) expr
      TypeSyntax.Parsed cast = TypeSyntax.parse(t, from + 1, t.match(from) - 1);
      if (cast != null) {
        return value(TypeSyntax.resolve(cast, u, ctx.scope()));
      }
    }
    int[] start = new int[1];
    List<Seg> segs = chainBackward(to, start);
    if (segs == null || segs.isEmpty() || start[0] != from) {
      if (segs != null && !segs.isEmpty() && start[0] == from + 1 && t.is(from, Token.NEW)) {
        return fold(ctx, segs);
      }
      return null;
    }
    return fold(ctx, segs);
  }

  private TypeRef localType(Context ctx, Local l) {
    return switch (l.kind) {
      case TYPED -> TypeSyntax.resolve(TypeSyntax.parse(t, l.from, l.to), u, ctx.scope());
      case VAR -> evalRange(ctx, l.from, l.to) instanceof Value v ? v.type : null;
      case FOREACH_VAR -> {
        if (!(evalRange(ctx, l.from, l.to) instanceof Value v)) {
          yield null;
        }
        if (v.type.dims() > 0) {
          yield v.type.component();
        }
        TypeRef iterable = u.asSuper(v.type, "java/lang/Iterable");
        yield iterable == null || iterable.args().isEmpty() ? null : iterable.args().get(0);
      }
      case LAMBDA -> lambdaParamType(l);
      default -> null;
    };
  }

  // ---------------------------------------------------------------------------------------------
  // Generic method inference
  //
  // Not JLS 18, but the common cases: a method's own type variables are bound by matching each
  // parameter type against its argument's type; lambda parameters get the parameter types of the
  // expected functional interface's single abstract method; a lambda's body (or a method
  // reference's return type) then binds the type variables in that method's result. Anything left
  // unbound falls back to the type variable's declared bound.

  private static final int MAX_INFERENCE_DEPTH = 16;
  private int inferenceDepth;

  /** A call's result type with the callee's own type variables inferred from the arguments. */
  private TypeRef callType(Universe.Hit h, int open) {
    TypeRef ret = memberType(h, h.type());
    if (ret == null || !hasTypeVars(ret)) {
      return ret;
    }
    if (open < 0 || inferenceDepth >= MAX_INFERENCE_DEPTH) {
      return withBounds(ret, h);
    }
    inferenceDepth++;
    Context saved = active;
    try {
      return withBounds(ret.subst(infer(h, open, true)), h);
    } catch (RuntimeException e) {
      return null;
    } finally {
      inferenceDepth--;
      active = saved;
    }
  }

  /** A call's symbol whose type is computed (lazily) with inference, in the current context. */
  private Symbol callSymbol(Universe.Hit h, List<Universe.Hit> overloads, int open) {
    Symbol s = Symbol.of(h, overloads);
    Context ctx = active;
    return new Symbol(
        s.kind(),
        s.name(),
        s.target(),
        s.owner(),
        h,
        () -> {
          Context saved = active;
          active = ctx;
          try {
            return s.kind() == Symbol.Kind.CONSTRUCTOR ? h.type() : callType(h, open);
          } finally {
            active = saved;
          }
        },
        overloads,
        null);
  }

  private static boolean hasTypeVars(TypeRef t) {
    if (t == null) {
      return false;
    }
    if (t.tvar() != null) {
      return true;
    }
    for (TypeRef a : t.args()) {
      if (hasTypeVars(a)) {
        return true;
      }
    }
    return false;
  }

  /** Replaces the callee's still-unbound type variables by their declared bounds. */
  private static TypeRef withBounds(TypeRef t, Universe.Hit h) {
    Map<String, TypeRef> bounds = h.member().typeParamBounds();
    if (bounds.isEmpty() || !hasTypeVars(t)) {
      return t;
    }
    Map<String, TypeRef> erased = new java.util.HashMap<>();
    // A bound may mention type variables itself (T extends Comparable<T>): use its raw class.
    bounds.forEach(
        (name, bound) ->
            erased.put(
                name,
                hasTypeVars(bound) && bound.cls() != null
                    ? TypeRef.ofClass(bound.cls(), List.of())
                    : bound));
    return t.subst(erased);
  }

  /**
   * Binds the callee's type variables from the call's arguments: plain arguments first, then
   * (when {@code functional}) lambdas and method references, which can rely on those bindings.
   */
  private Map<String, TypeRef> infer(Universe.Hit h, int open, boolean functional) {
    Map<String, TypeRef> b = new java.util.HashMap<>();
    List<Member.Param> params = h.params();
    if (params.isEmpty() || t.match(open) < 0) {
      return b;
    }
    List<int[]> args = arguments(open);
    for (int pass = 0; pass < 2; pass++) {
      for (int k = 0; k < args.size(); k++) {
        int[] a = args.get(k);
        boolean isFunctional = isLambda(a) || isMethodRef(a);
        if (isFunctional != (pass == 1) || (isFunctional && !functional)) {
          continue;
        }
        TypeRef argType = isFunctional ? null : argType(a[0], a[1]);
        TypeRef param = paramType(h, params, k, args.size(), argType);
        if (param == null) {
          continue;
        }
        if (!isFunctional) {
          unify(param, argType, b);
          continue;
        }
        Universe.Hit sam = sam(param.subst(b));
        TypeRef result = sam == null ? null : functionalResult(a);
        if (result != null) {
          unify(sam.type(), result, b);
        }
      }
    }
    return b;
  }

  /** The parameter type that argument {@code k} is matched against (varargs spread aware). */
  private static TypeRef paramType(
      Universe.Hit h, List<Member.Param> params, int k, int argc, TypeRef argType) {
    TypeRef param = params.get(Math.min(k, params.size() - 1)).type();
    boolean last = k >= params.size() - 1;
    if (param != null
        && h.member().varargs()
        && last
        && param.dims() > 0
        && !(argc == params.size() && argType != null && argType.dims() == param.dims())) {
      param = param.component();
    }
    return param;
  }

  /** Records what {@code param}'s type variables must be for {@code arg} to fit it. */
  private void unify(TypeRef param, TypeRef arg, Map<String, TypeRef> b) {
    if (param == null || arg == null) {
      return;
    }
    if (param.tvar() != null) {
      if (arg.dims() < param.dims()) {
        return;
      }
      TypeRef bound =
          arg.prim() != null && arg.dims() == param.dims()
              ? TypeRef.ofClass(boxed(arg.prim()), List.of())
              : new TypeRef(arg.cls(), arg.args(), arg.tvar(), arg.prim(), arg.dims() - param.dims());
      if (bound.tvar() == null) {
        b.putIfAbsent(param.tvar(), bound);
      }
      return;
    }
    if (param.cls() == null || param.args().isEmpty() || param.dims() != arg.dims()) {
      return;
    }
    TypeRef a = arg.prim() != null && arg.dims() == 0 ? TypeRef.ofClass(boxed(arg.prim()), List.of()) : arg;
    if (a.cls() == null) {
      return;
    }
    TypeRef plain = TypeRef.ofClass(a.cls(), a.args());
    TypeRef view = a.cls().equals(param.cls()) ? plain : u.asSuper(plain, param.cls());
    if (view == null || view.args().size() != param.args().size()) {
      return;
    }
    for (int i = 0; i < param.args().size(); i++) {
      unify(param.args().get(i), view.args().get(i), b);
    }
  }

  /** The single abstract method of a functional interface type, with its type bindings. */
  private Universe.Hit sam(TypeRef fn) {
    if (fn == null || !fn.isClass()) {
      return null;
    }
    ClassModel m = u.find(fn.cls());
    if (m == null || m.kind() != TurbineTyKind.INTERFACE) {
      return null;
    }
    Universe.Hit[] out = new Universe.Hit[1];
    u.walk(
        fn,
        (model, bindings) -> {
          for (Member meth : model.methods()) {
            if (meth.isAbstract() && meth.kind() == Member.Kind.METHOD && !isObjectMethod(meth)) {
              out[0] = new Universe.Hit(meth, bindings, model.binaryName());
              return true;
            }
          }
          return false;
        });
    return out[0];
  }

  /** Interfaces may redeclare Object's methods (e.g. Comparator.equals); those aren't the SAM. */
  private static boolean isObjectMethod(Member m) {
    return (m.name().equals("equals") && m.arity() == 1)
        || (m.name().equals("hashCode") && m.arity() == 0)
        || (m.name().equals("toString") && m.arity() == 0);
  }

  private boolean isLambda(int[] a) {
    int i = a[0];
    if (t.is(i, Token.IDENT) && t.is(i + 1, Token.ARROW)) {
      return true;
    }
    return t.is(i, Token.LPAREN) && t.match(i) > i && t.match(i) < a[1] && t.is(t.match(i) + 1, Token.ARROW);
  }

  private boolean isMethodRef(int[] a) {
    return a[1] > a[0]
        && t.is(a[1] - 1, Token.COLONCOLON)
        && (t.is(a[1], Token.IDENT) || t.is(a[1], Token.NEW));
  }

  /** What a lambda body evaluates to, or what a method reference returns. */
  private TypeRef functionalResult(int[] a) {
    if (isMethodRef(a)) {
      if (t.is(a[1], Token.NEW)) {
        return TypeSyntax.resolve(TypeSyntax.parse(t, a[0], a[1] - 2), u, active.scope());
      }
      Symbol s = safeResolve(a[1]);
      return s == null || s.hit() == null || s.kind() != Symbol.Kind.METHOD ? null : s.hit().type();
    }
    int arrow = t.is(a[0], Token.IDENT) ? a[0] + 1 : t.match(a[0]) + 1;
    int body = arrow + 1;
    if (!t.is(body, Token.LBRACE)) {
      return typeAt(a[1], body, a[1]);
    }
    int close = t.match(body);
    for (int j = body + 1; j < close; j++) {
      if (t.is(j, Token.LBRACE) && t.match(j) > j && t.is(j - 1, Token.ARROW)) {
        j = t.match(j); // a nested lambda's returns are its own
      } else if (t.is(j, Token.RETURN)) {
        int semi = j + 1;
        while (semi < close && !t.is(semi, Token.SEMI)) {
          if (t.match(semi) > semi) {
            semi = t.match(semi);
          }
          semi++;
        }
        TypeRef type = typeAt(semi - 1, j + 1, semi - 1);
        if (type != null) {
          return type;
        }
        j = semi;
      }
    }
    return null;
  }

  /** Types tokens [from, to] in the scope visible at token {@code at}. */
  private TypeRef typeAt(int at, int from, int to) {
    Context saved = active;
    try {
      Context ctx = new Context(at);
      State s = evalRange(ctx, from, to);
      return s instanceof Value v ? v.type : null;
    } finally {
      active = saved;
    }
  }

  /** A lambda parameter's type, from the functional interface its lambda is expected to be. */
  private TypeRef lambdaParamType(Local l) {
    int arrow = l.from;
    int start = t.is(arrow - 1, Token.IDENT) ? arrow - 1 : t.match(arrow - 1);
    if (start < 0 || inferenceDepth >= MAX_INFERENCE_DEPTH) {
      return null;
    }
    Universe.Hit sam = sam(expectedType(start));
    if (sam == null) {
      return null;
    }
    List<Member.Param> params = sam.params();
    if (l.to >= params.size()) {
      return null;
    }
    TypeRef p = params.get(l.to).type();
    return p == null || p.tvar() != null ? null : p;
  }

  /** The type expected of the expression (a lambda) starting at token {@code start}. */
  private TypeRef expectedType(int start) {
    inferenceDepth++;
    Context saved = active;
    try {
      Token before = t.kind(start - 1);
      if (before == Token.ASSIGN) {
        // Type name = <lambda>  (a local or a field)
        Symbol s = t.is(start - 2, Token.IDENT) ? safeResolve(start - 2) : null;
        return s == null ? null : s.type();
      }
      if (before != Token.LPAREN && before != Token.COMMA) {
        return null;
      }
      int open = enclosingParen(start);
      if (open < 1 || !t.is(open - 1, Token.IDENT)) {
        return null;
      }
      List<int[]> args = arguments(open);
      int k = -1;
      for (int i = 0; i < args.size(); i++) {
        if (args.get(i)[0] == start) {
          k = i;
        }
      }
      Symbol callee = k < 0 ? null : safeResolve(open - 1);
      Universe.Hit h =
          callee == null
              ? null
              : callee.hit() != null && callee.kind() != Symbol.Kind.FIELD
                  ? callee.hit()
                  : callee.kind() == Symbol.Kind.CLASS ? Universe.pick(callee.overloads(), args.size()) : null;
      if (h == null || h.params().isEmpty()) {
        return null;
      }
      TypeRef param = paramType(h, h.params(), k, args.size(), null);
      // Only plain arguments constrain a lambda's inputs (its body can't, it needs them).
      return param == null ? null : param.subst(infer(h, open, false));
    } catch (RuntimeException e) {
      return null;
    } finally {
      inferenceDepth--;
      active = saved;
    }
  }
}
