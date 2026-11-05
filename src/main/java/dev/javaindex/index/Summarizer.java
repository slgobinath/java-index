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

import com.google.turbine.diag.SourceFile;
import com.google.turbine.diag.TurbineError;
import com.google.turbine.model.TurbineTyKind;
import com.google.turbine.parse.Parser;
import com.google.turbine.parse.Token;
import com.google.turbine.tree.Tree;
import com.google.turbine.tree.TurbineModifier;
import dev.javaindex.config.ConfigRefScanner;
import dev.javaindex.index.FileSummary.FieldDecl;
import dev.javaindex.index.FileSummary.ImportInfo;
import dev.javaindex.index.FileSummary.MethodDecl;
import dev.javaindex.index.FileSummary.Span;
import dev.javaindex.index.FileSummary.TypeInfo;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/** Turns Java source into a {@link FileSummary} using turbine's header parser. */
public final class Summarizer {
  private Summarizer() {}

  public static String hash(byte[] content) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-1");
      return HexFormat.of().formatHex(md.digest(content));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public static String hash(String content) {
    return hash(content.getBytes(StandardCharsets.UTF_8));
  }

  public static FileSummary summarize(String hash, String path, String text) {
    Lines lines = Lines.of(text);
    Tokens tokens = Tokens.lex(text);
    long[] filter = IdentFilter.build(tokens);
    Tree.CompUnit unit = parse(path, text);
    if (unit == null) {
      String repaired = tokens.repaired(text);
      unit = repaired == null ? null : parse(path, repaired);
      if (unit == null) {
        return new FileSummary(hash, "", List.of(), List.of(), false, lines, filter, ConfigRefScanner.scan(tokens));
      }
    }
    String pkg =
        unit.pkg()
            .map(p -> String.join("/", p.name().stream().map(Tree.Ident::value).toList()))
            .orElse("");
    List<ImportInfo> imports = new ArrayList<>();
    for (Tree.ImportDecl imp : unit.imports()) {
      imports.add(
          new ImportInfo(
              imp.type().stream().map(Tree.Ident::value).toList(), imp.stat(), imp.wild()));
    }
    List<TypeInfo> types = new ArrayList<>();
    for (Tree.TyDecl decl : unit.decls()) {
      String bin = pkg.isEmpty() ? decl.name().value() : pkg + "/" + decl.name().value();
      types.add(type(decl, bin, tokens));
    }
    return new FileSummary(
        hash, pkg, List.copyOf(imports), List.copyOf(types), true, lines, filter, ownedConfigRefs(tokens, types));
  }

  /** Config refs, with annotation-based ones (prefixes, renames) tied to what they annotate. */
  private static List<FileSummary.ConfigRef> ownedConfigRefs(Tokens tokens, List<TypeInfo> types) {
    List<FileSummary.ConfigRef> refs = ConfigRefScanner.scan(tokens);
    if (refs.isEmpty()) {
      return List.of();
    }
    List<TypeInfo> all = new ArrayList<>();
    for (TypeInfo t : types) {
      all.addAll(flatten(t));
    }
    List<FileSummary.ConfigRef> out = new ArrayList<>();
    for (FileSummary.ConfigRef r : refs) {
      if (r.kind() != FileSummary.ConfigRef.Kind.PREFIX && r.kind() != FileSummary.ConfigRef.Kind.RENAME) {
        out.add(r);
        continue;
      }
      String owner = null;
      for (TypeInfo t : all) {
        // annotations sit between a declaration's start and its name
        if (t.span.start() <= r.start() && r.start() < t.span.nameStart()) {
          owner = t.binaryName;
        }
        for (FileSummary.MethodDecl m : t.methods) {
          if (m.span().start() <= r.start() && r.start() < m.span().nameStart()) {
            owner = t.binaryName + "#" + m.name();
          }
        }
      }
      out.add(r.withOwner(owner));
    }
    return List.copyOf(out);
  }

  private static List<TypeInfo> flatten(TypeInfo t) {
    List<TypeInfo> out = new ArrayList<>();
    out.add(t);
    for (TypeInfo m : t.members) {
      out.addAll(flatten(m));
    }
    return out;
  }

  private static Tree.CompUnit parse(String path, String text) {
    try {
      return Parser.parse(new SourceFile(path, text));
    } catch (TurbineError | RuntimeException e) {
      return null;
    }
  }

  private static TypeInfo type(Tree.TyDecl decl, String bin, Tokens tokens) {
    String simple = decl.name().value();
    int nameTok = tokens.identAt(decl.name().position());
    List<FieldDecl> fields = new ArrayList<>();
    List<MethodDecl> methods = new ArrayList<>();
    List<TypeInfo> members = new ArrayList<>();
    for (Tree.VarDecl c : decl.components()) {
      fields.add(
          new FieldDecl(
              c.name().value(),
              c.ty(),
              identSpan(tokens, c.name().position(), c.position()),
              false,
              FieldDecl.Kind.RECORD_COMPONENT,
              deprecated(c.annos())));
    }
    for (Tree member : decl.members()) {
      switch (member) {
        case Tree.VarDecl v -> {
          boolean enumConstant = v.mods().contains(TurbineModifier.ACC_ENUM);
          fields.add(
              new FieldDecl(
                  v.name().value(),
                  v.ty(),
                  fieldSpan(tokens, v),
                  enumConstant || v.mods().contains(TurbineModifier.STATIC),
                  enumConstant ? FieldDecl.Kind.ENUM_CONSTANT : FieldDecl.Kind.FIELD,
                  deprecated(v.annos())));
        }
        case Tree.MethDecl m -> methods.add(method(m, simple, decl.tykind(), tokens));
        case Tree.TyDecl t -> members.add(type(t, bin + "$" + t.name().value(), tokens));
        default -> {}
      }
    }
    TypeInfo info =
        new TypeInfo(
            bin,
            simple,
            decl.tykind(),
            new Span(
                tokens.start(nameTok),
                tokens.end(nameTok),
                declarationStart(tokens, nameTok, decl.position()),
                bodyEnd(tokens, nameTok)),
            decl.typarams().stream().map(p -> p.name().value()).toList(),
            decl.typarams().stream()
                .map(p -> p.bounds().isEmpty() ? null : (Tree) p.bounds().get(0))
                .collect(java.util.stream.Collectors.toList()),
            decl.tykind() == TurbineTyKind.INTERFACE ? null : decl.xtnds().orElse(null),
            decl.tykind() == TurbineTyKind.INTERFACE && decl.xtnds().isPresent()
                ? concat(decl.xtnds().get(), decl.impls())
                : List.copyOf(decl.impls()),
            List.copyOf(fields),
            List.copyOf(methods),
            List.copyOf(members),
            deprecated(decl.annos()));
    for (TypeInfo m : members) {
      m.outer = info;
    }
    return info;
  }

  private static List<Tree.ClassTy> concat(Tree.ClassTy first, List<Tree.ClassTy> rest) {
    List<Tree.ClassTy> out = new ArrayList<>();
    out.add(first);
    out.addAll(rest);
    return List.copyOf(out);
  }

  private static MethodDecl method(
      Tree.MethDecl m, String className, TurbineTyKind owner, Tokens tokens) {
    boolean ctor = m.name().value().equals("<init>");
    int nameTok;
    if (ctor) {
      // turbine doesn't record where a constructor's name is; find "ClassName(" after the start.
      nameTok = tokens.indexAtOrAfter(m.position());
      while (nameTok < tokens.size()
          && !(tokens.isIdent(nameTok, className)
              && (tokens.is(nameTok + 1, Token.LPAREN) || tokens.is(nameTok + 1, Token.LBRACE)))) {
        nameTok++;
      }
    } else {
      nameTok = tokens.identAt(m.name().position());
    }
    Span span;
    if (nameTok < 0 || nameTok >= tokens.size()) {
      span = new Span(m.position(), m.position(), m.position(), m.position());
    } else {
      int end = nameTok;
      int i = nameTok + 1;
      if (tokens.is(i, Token.LPAREN) && tokens.match(i) > 0) {
        i = tokens.match(i) + 1;
      }
      while (i < tokens.size() && !tokens.is(i, Token.SEMI) && !tokens.is(i, Token.LBRACE)) {
        i++;
      }
      if (tokens.is(i, Token.LBRACE) && tokens.match(i) > 0) {
        end = tokens.match(i);
      } else if (i < tokens.size()) {
        end = i;
      }
      span =
          new Span(
              tokens.start(nameTok),
              tokens.end(nameTok),
              declarationStart(tokens, nameTok, m.position()),
              tokens.end(Math.min(end, tokens.size() - 1)));
    }
    List<Tree> params = new ArrayList<>();
    List<String> names = new ArrayList<>();
    for (Tree.VarDecl p : m.params()) {
      params.add(p.ty());
      names.add(p.name().value());
    }
    return new MethodDecl(
        m.name().value(),
        List.copyOf(params),
        List.copyOf(names),
        m.mods().contains(TurbineModifier.VARARGS),
        m.ret().filter(r -> !ctor).orElse(null),
        m.typarams().stream().map(p -> p.name().value()).toList(),
        m.typarams().stream().map(p -> p.bounds().isEmpty() ? null : p.bounds().get(0)).toList(),
        span,
        m.mods().contains(TurbineModifier.STATIC),
        m.mods().contains(TurbineModifier.ABSTRACT)
            || (owner == TurbineTyKind.INTERFACE
                && !ctor
                && !m.mods().contains(TurbineModifier.DEFAULT)
                && !m.mods().contains(TurbineModifier.STATIC)
                && !m.mods().contains(TurbineModifier.PRIVATE)),
        deprecated(m.annos()));
  }

  /** {@code @Deprecated} or {@code @java.lang.Deprecated}. */
  private static boolean deprecated(List<Tree.Anno> annos) {
    for (Tree.Anno a : annos) {
      if (!a.name().isEmpty() && a.name().getLast().value().equals("Deprecated")) {
        return true;
      }
    }
    return false;
  }

  private static Span fieldSpan(Tokens tokens, Tree.VarDecl v) {
    int nameTok = tokens.identAt(v.name().position());
    if (nameTok < 0) {
      return new Span(v.position(), v.position(), v.position(), v.position());
    }
    int i = nameTok + 1;
    while (i < tokens.size()) {
      Token k = tokens.kind(i);
      if (k == Token.SEMI || k == Token.COMMA || k == Token.RBRACE) {
        break;
      }
      if ((k == Token.LPAREN || k == Token.LBRACE || k == Token.LBRACK) && tokens.match(i) > i) {
        i = tokens.match(i);
      }
      i++;
    }
    int last = Math.min(i, tokens.size()) - 1;
    return new Span(
        tokens.start(nameTok),
        tokens.end(nameTok),
        declarationStart(tokens, nameTok, v.position()),
        tokens.end(Math.max(last, nameTok)));
  }

  private static Span identSpan(Tokens tokens, int namePos, int declPos) {
    int nameTok = tokens.identAt(namePos);
    if (nameTok < 0) {
      return new Span(namePos, namePos, declPos, namePos);
    }
    return new Span(
        tokens.start(nameTok),
        tokens.end(nameTok),
        Math.min(declPos, tokens.start(nameTok)),
        tokens.end(nameTok));
  }

  /**
   * Where a declaration really starts: turbine records some at their name, so walk back over the
   * type, type parameters, modifiers and annotations to the end of the previous member.
   */
  private static int declarationStart(Tokens tokens, int nameTok, int fallback) {
    if (nameTok < 0) {
      return fallback;
    }
    int j = nameTok - 1;
    int angle = 0; // inside <...>, where commas separate type arguments, not declarators
    while (j >= 0) {
      Token k = tokens.kind(j);
      switch (k) {
        case GT -> angle += 1;
        case GTGT -> angle += 2;
        case GTGTGT -> angle += 3;
        case LT -> angle = Math.max(0, angle - 1);
        default -> {}
      }
      if (k == Token.SEMI
          || k == Token.LBRACE
          || k == Token.RBRACE
          || (k == Token.COMMA && angle == 0)) {
        break;
      }
      if (k == Token.RPAREN && tokens.match(j) >= 0) {
        j = tokens.match(j) - 1; // annotation arguments, which may contain braces
        continue;
      }
      j--;
    }
    return Math.min(fallback, tokens.start(j + 1));
  }

  /** End offset of the body brace that follows a type's name. */
  private static int bodyEnd(Tokens tokens, int nameTok) {
    for (int i = nameTok + 1; i < tokens.size(); i++) {
      Token k = tokens.kind(i);
      if (k == Token.LPAREN && tokens.match(i) > i) {
        i = tokens.match(i);
      } else if (k == Token.LBRACE) {
        int close = tokens.match(i);
        return close > 0 ? tokens.end(close) : tokens.end(tokens.size() - 1);
      }
    }
    return tokens.size() == 0 ? 0 : tokens.end(tokens.size() - 1);
  }

  static Optional<TypeInfo> find(List<TypeInfo> types, String binaryName) {
    for (TypeInfo t : types) {
      if (t.binaryName.equals(binaryName)) {
        return Optional.of(t);
      }
      if (binaryName.startsWith(t.binaryName + "$")) {
        Optional<TypeInfo> nested = find(t.members, binaryName);
        if (nested.isPresent()) {
          return nested;
        }
      }
    }
    return Optional.empty();
  }
}
