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

package dev.javaindex.mcp;

import dev.javaindex.config.ConfigIndex;
import dev.javaindex.config.ConfigKeys;
import dev.javaindex.config.ConfigReport;
import dev.javaindex.config.LibraryConfig;
import dev.javaindex.index.FileSummary;
import dev.javaindex.index.FileSummary.MethodDecl;
import dev.javaindex.index.FileSummary.TypeInfo;
import dev.javaindex.index.TypeHandle;
import dev.javaindex.model.ClassModel;
import dev.javaindex.model.Member;
import dev.javaindex.model.Target;
import dev.javaindex.model.TypeRef;
import dev.javaindex.model.Universe;
import dev.javaindex.project.Project;
import dev.javaindex.project.Worktree;
import dev.javaindex.query.Describe;
import dev.javaindex.query.Queries;
import dev.javaindex.query.Queries.Ref;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The agent-facing tools. Unlike LSP they take names, not cursor positions, and answer in compact
 * text with file:line locations. Everything comes from the same shared index as the editor.
 */
final class AgentTools {
  private static final int MAX_SITES = 200;
  private static final int MAX_UNRESOLVED = 30;

  private final Project project;
  private final Queries queries;

  AgentTools(Project project) {
    this.project = project;
    this.queries = new Queries(project);
  }

  /** Thrown for bad input; the message is shown to the agent. */
  static final class ToolError extends RuntimeException {
    ToolError(String message) {
      super(message);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // java_api

  String api(Worktree w, String symbol, String javadoc, boolean inherited) {
    Universe u = w.universe();
    Spec spec = spec(w, u, symbol);
    String bin = resolveClass(w, u, spec.className, spec.member != null);
    ClassModel m = u.find(bin);
    StringBuilder out = new StringBuilder();
    if (spec.member != null) {
      List<Universe.Hit> hits = members(u, m, spec);
      if (hits.isEmpty()) {
        throw new ToolError("No member '" + spec.member + "' in " + TypeRef.qualifiedName(bin) + suggestMembers(m, spec.member));
      }
      out.append("# ").append(TypeRef.qualifiedName(bin)).append('#').append(spec.member).append("\n");
      for (Universe.Hit h : hits) {
        out.append("\n```java\n").append(signature(h, u)).append("\n```\n");
        if (!h.owner().equals(bin)) {
          out.append("declared in ").append(TypeRef.qualifiedName(h.owner())).append('\n');
        }
        out.append(where(w, h.member().target(), u)).append('\n');
        String doc = "none".equals(javadoc) ? null : queries.javadoc(h.member().target(), u);
        if (doc != null) {
          out.append('\n').append(doc).append('\n');
        }
      }
      return out.toString();
    }

    String header = queries.declarationText(m.target(), u);
    out.append("# ").append(TypeRef.qualifiedName(bin)).append("\n\n");
    out.append("```java\n").append(header != null ? header : Describe.classHeader(m, bin)).append("\n```\n");
    out.append(where(w, m.target(), u)).append('\n');
    if (!"none".equals(javadoc)) {
      String doc = queries.javadoc(m.target(), u);
      if (doc != null) {
        out.append('\n').append("summary".equals(javadoc) ? firstSentence(doc) : doc).append('\n');
      }
    }
    boolean library = m.target() instanceof Target.Binary;
    List<Member> fields = m.fields().stream().filter(f -> !(library && f.hidden())).toList();
    List<Member> ctors = new ArrayList<>();
    List<Member> methods = new ArrayList<>();
    for (Member meth : m.methods()) {
      if (library && meth.hidden()) {
        continue;
      }
      (meth.kind() == Member.Kind.CONSTRUCTOR ? ctors : methods).add(meth);
    }
    section(out, "Fields", fields, bin, u, javadoc);
    section(out, "Constructors", ctors, bin, u, javadoc);
    section(out, "Methods", methods, bin, u, javadoc);
    if (inherited) {
      inheritedMembers(out, u, m, bin);
    }
    return out.toString();
  }

  private void section(
      StringBuilder out, String title, List<Member> members, String owner, Universe u, String javadoc) {
    if (members.isEmpty()) {
      return;
    }
    out.append("\n## ").append(title).append('\n');
    for (Member member : members) {
      Universe.Hit h = new Universe.Hit(member, Map.of(), owner);
      out.append("- `").append(signature(h, u)).append('`');
      if ("summary".equals(javadoc) || "full".equals(javadoc)) {
        String doc = queries.javadoc(member.target(), u);
        if (doc != null) {
          String text = "full".equals(javadoc) ? doc.replace("\n", "\n  ") : firstSentence(doc);
          out.append(" — ").append(text);
        }
      }
      out.append('\n');
    }
  }

  private void inheritedMembers(StringBuilder out, Universe u, ClassModel m, String bin) {
    Map<String, Set<String>> byOwner = new LinkedHashMap<>();
    Set<String> declared = new LinkedHashSet<>();
    for (Member meth : m.methods()) {
      declared.add(meth.name() + "/" + meth.arity());
    }
    TypeRef self = TypeRef.ofClass(bin, List.of());
    u.walk(
        self,
        (model, bindings) -> {
          if (model.binaryName().equals(bin) || model.binaryName().equals("java/lang/Object")) {
            return false;
          }
          boolean library = model.target() instanceof Target.Binary;
          for (Member meth : model.methods()) {
            if (meth.kind() != Member.Kind.METHOD || (library && meth.hidden()) || meth.isStatic()) {
              continue;
            }
            if (declared.add(meth.name() + "/" + meth.arity())) {
              byOwner.computeIfAbsent(model.binaryName(), k -> new LinkedHashSet<>()).add(meth.name());
            }
          }
          return false;
        });
    if (!byOwner.isEmpty()) {
      out.append("\n## Inherited methods\n");
      byOwner.forEach(
          (owner, names) ->
              out.append("- from ").append(TypeRef.qualifiedName(owner)).append(": ")
                  .append(String.join(", ", names)).append('\n'));
    }
  }

  private String signature(Universe.Hit h, Universe u) {
    String declared = queries.declarationText(h.member().target(), u);
    if (declared != null) {
      return declared;
    }
    Member m = h.member();
    if (m.kind() == Member.Kind.FIELD) {
      TypeRef t = h.type();
      return (m.isStatic() ? "static " : "") + (t == null ? "?" : t.display()) + " " + m.name();
    }
    return Describe.method(h, queries.paramNames(h, u));
  }

  private static String firstSentence(String doc) {
    String text = doc.split("\n\n", 2)[0].replace('\n', ' ').strip();
    int dot = text.indexOf(". ");
    if (dot > 0) {
      text = text.substring(0, dot + 1);
    }
    return text.length() > 220 ? text.substring(0, 217) + "..." : text;
  }

  // ---------------------------------------------------------------------------------------------
  // java_callers

  String callers(Worktree w, String symbol, boolean includeRelated) {
    Universe u = w.universe();
    Spec spec = spec(w, u, symbol);
    if (spec.member == null) {
      throw new ToolError("Give a member, e.g. com.example.Client#send or Client#send(String).");
    }
    String bin = resolveClass(w, u, spec.className, true);
    ClassModel m = u.find(bin);
    List<Universe.Hit> hits = members(u, m, spec);
    if (hits.isEmpty()) {
      throw new ToolError("No member '" + spec.member + "' in " + TypeRef.qualifiedName(bin) + suggestMembers(m, spec.member));
    }
    String declared =
        hits.get(0).member().kind() == Member.Kind.CONSTRUCTOR ? TypeRef.simpleName(bin) : spec.member;
    String name = declared.substring(declared.lastIndexOf('.') + 1);

    // What a call site can resolve to: the member itself, overrides below it, and the
    // declarations above it that a call through a supertype would name.
    Map<Target, String> groups = new LinkedHashMap<>();
    for (Universe.Hit h : hits) {
      groups.put(h.member().target(), "Direct");
    }
    if (includeRelated) {
      for (Universe.Hit h : hits) {
        if (h.member().kind() != Member.Kind.METHOD) {
          continue;
        }
        int arity = h.member().arity();
        for (TypeHandle sub : Queries.allSubtypes(w, h.owner())) {
          for (MethodDecl md : sub.type().methods) {
            if (md.name().equals(spec.member) && md.paramTypes().size() == arity) {
              groups.putIfAbsent(
                  new Target.Source(u.path(sub.rel()), sub.file().lines, md.span().nameStart(), md.span().nameEnd()),
                  "Override in " + TypeRef.qualifiedName(sub.type().binaryName));
            }
          }
        }
        u.walk(
            TypeRef.ofClass(h.owner(), List.of()),
            (model, b) -> {
              if (!model.binaryName().equals(h.owner())) {
                for (Member sup : model.methods()) {
                  if (sup.name().equals(spec.member) && sup.arity() == arity && sup.kind() == Member.Kind.METHOD) {
                    groups.putIfAbsent(sup.target(), "Via supertype " + TypeRef.qualifiedName(model.binaryName()));
                  }
                }
              }
              return false;
            });
      }
    }

    Queries.RefSearch search = queries.references(w, u, new ArrayList<>(groups.keySet()), name);
    StringBuilder out = new StringBuilder();
    out.append("# Usages of ").append(TypeRef.qualifiedName(bin)).append('#').append(spec.member);
    if (hits.size() > 1) {
      out.append(" (").append(hits.size()).append(" overloads; pass parameter types to narrow)");
    }
    out.append('\n');
    Map<String, List<Ref>> byGroup = new LinkedHashMap<>();
    for (String g : new LinkedHashSet<>(groups.values())) {
      byGroup.put(g, new ArrayList<>());
    }
    for (Ref r : search.matches()) {
      if (!r.declaration()) {
        byGroup.get(groups.get(r.target())).add(r);
      }
    }
    int shown = 0;
    for (Map.Entry<String, List<Ref>> g : byGroup.entrySet()) {
      if (g.getValue().isEmpty() && !g.getKey().equals("Direct")) {
        continue;
      }
      out.append("\n## ").append(g.getKey()).append(" (").append(g.getValue().size()).append(")\n");
      if (g.getValue().isEmpty()) {
        out.append("none found\n");
      }
      String lastCaller = null;
      for (Ref r : g.getValue()) {
        if (shown++ >= MAX_SITES) {
          out.append("... more omitted\n");
          break;
        }
        String caller = callerName(r);
        if (!caller.equals(lastCaller)) {
          out.append("- ").append(caller).append('\n');
          lastCaller = caller;
        }
        out.append("    ").append(rel(w, r.file())).append(':').append(r.lines().line(r.start()) + 1)
            .append("  ").append(r.line()).append('\n');
      }
    }
    boolean isField = hits.stream().allMatch(h -> h.member().kind() == Member.Kind.FIELD);
    List<Ref> unresolved =
        search.unresolved().stream().filter(r -> looksLikeUse(r, name, isField)).toList();
    if (!unresolved.isEmpty()) {
      out.append("\n## Unresolved `").append(name).append("` uses (").append(unresolved.size())
          .append(") — the index couldn't tell what these call; check them if completeness matters\n");
      for (Ref r : unresolved.subList(0, Math.min(unresolved.size(), MAX_UNRESOLVED))) {
        out.append("    ").append(rel(w, r.file())).append(':').append(r.lines().line(r.start()) + 1)
            .append("  ").append(r.line()).append('\n');
      }
      if (unresolved.size() > MAX_UNRESOLVED) {
        out.append("    ... ").append(unresolved.size() - MAX_UNRESOLVED).append(" more\n");
      }
    }
    out.append("\nNot visible to this index: reflection, framework wiring (DI, annotations), non-Java code.\n");
    return out.toString();
  }

  /**
   * Whether an unresolved same-named occurrence looks like a use of the member: a call
   * ({@code name(}) or method reference ({@code ::name}) for methods, a member access for fields.
   */
  private static boolean looksLikeUse(Ref r, String name, boolean field) {
    String line = r.line();
    if (line.startsWith("import ") || line.startsWith("package ")) {
      return false;
    }
    int i = line.indexOf(name);
    while (i >= 0) {
      int after = i + name.length();
      boolean wholeWord =
          (i == 0 || !Character.isJavaIdentifierPart(line.charAt(i - 1)))
              && (after >= line.length() || !Character.isJavaIdentifierPart(line.charAt(after)));
      String before = line.substring(0, i).stripTrailing();
      String rest = line.substring(after).stripLeading();
      boolean use =
          field
              ? before.endsWith(".") && !rest.startsWith("(")
              : rest.startsWith("(") || before.endsWith("::");
      if (wholeWord && use) {
        return true;
      }
      i = line.indexOf(name, after);
    }
    return false;
  }

  private static String callerName(Ref r) {
    Queries.Enclosing e = Queries.enclosing(r);
    if (e == null) {
      return "(top level)";
    }
    String type = TypeRef.qualifiedName(e.type().binaryName);
    if (e.method() == null) {
      return type + " (field initializer or class body)";
    }
    String method = e.method().isConstructor() ? e.type().simpleName : e.method().name();
    List<String> params = new ArrayList<>();
    e.method().paramTypes().forEach(t -> params.add(Describe.tree(t)));
    return type + "#" + method + "(" + String.join(", ", params) + ")";
  }

  // ---------------------------------------------------------------------------------------------
  // java_implementations

  String implementations(Worktree w, String symbol) {
    Universe u = w.universe();
    Spec spec = spec(w, u, symbol);
    String bin = resolveClass(w, u, spec.className, spec.member != null);
    List<TypeHandle> subs = Queries.allSubtypes(w, bin);
    StringBuilder out = new StringBuilder();
    if (spec.member == null) {
      out.append("# Subtypes of ").append(TypeRef.qualifiedName(bin)).append(" (").append(subs.size()).append(")\n");
      for (TypeHandle h : subs) {
        out.append("- ").append(Describe.keyword(h.type().kind)).append(' ')
            .append(TypeRef.qualifiedName(h.type().binaryName)).append("  ")
            .append(h.rel()).append(':').append(h.file().lines.line(h.type().span.nameStart()) + 1).append('\n');
      }
    } else {
      List<Universe.Hit> hits = members(u, u.find(bin), spec);
      if (hits.isEmpty()) {
        throw new ToolError("No member '" + spec.member + "' in " + TypeRef.qualifiedName(bin) + suggestMembers(u.find(bin), spec.member));
      }
      Set<Integer> arities = new LinkedHashSet<>();
      hits.forEach(h -> arities.add(h.member().arity()));
      out.append("# Overrides of ").append(TypeRef.qualifiedName(bin)).append('#').append(spec.member).append('\n');
      int count = 0;
      for (TypeHandle h : subs) {
        for (MethodDecl md : h.type().methods) {
          if (md.name().equals(spec.member) && arities.contains(md.paramTypes().size())) {
            count++;
            out.append("- ").append(TypeRef.qualifiedName(h.type().binaryName)).append('#').append(md.name())
                .append("  ").append(h.rel()).append(':').append(h.file().lines.line(md.span().nameStart()) + 1)
                .append(md.isAbstract() ? "  (abstract)" : "").append('\n');
          }
        }
      }
      if (count == 0) {
        out.append("none found\n");
      }
    }
    out.append("\nOnly types declared in this worktree's sources are searched (not dependency jars).\n");
    return out.toString();
  }

  // ---------------------------------------------------------------------------------------------
  // java_outline

  String outline(Worktree w, String file) {
    Path path = w.root().resolve(file).normalize();
    String rel = w.rel(path);
    if (rel == null) {
      throw new ToolError(file + " is not inside " + w.root());
    }
    FileSummary summary = w.summary(rel);
    if (summary == null || (!summary.parsed && summary.types.isEmpty())) {
      throw new ToolError("Cannot read or parse " + rel);
    }
    Universe u = w.universe();
    StringBuilder out = new StringBuilder("# ").append(rel);
    if (!summary.pkg.isEmpty()) {
      out.append("  (package ").append(summary.pkg.replace('/', '.')).append(')');
    }
    out.append('\n');
    for (TypeInfo t : summary.types) {
      outlineType(out, u, summary, path, t, "");
    }
    return out.toString();
  }

  private void outlineType(StringBuilder out, Universe u, FileSummary summary, Path path, TypeInfo t, String indent) {
    ClassModel m = u.external(t, summary, path);
    out.append(String.format(Locale.ROOT, "L%-5d", summary.lines.line(t.span.nameStart()) + 1))
        .append(indent).append(Describe.classHeader(m, t.binaryName).replace(TypeRef.qualifiedName(t.binaryName), t.simpleName))
        .append('\n');
    record Line(int line, String text) {}
    List<Line> lines = new ArrayList<>();
    for (Member f : m.fields()) {
      Target.Source src = (Target.Source) f.target();
      TypeRef type = f.type();
      lines.add(new Line(src.lines().line(src.start()) + 1,
          "field  " + (f.isStatic() ? "static " : "") + (type == null ? "?" : type.display()) + " " + f.name()));
    }
    for (Member meth : m.methods()) {
      Target.Source src = (Target.Source) meth.target();
      String kind = meth.kind() == Member.Kind.CONSTRUCTOR ? "ctor   " : "method ";
      lines.add(new Line(src.lines().line(src.start()) + 1,
          kind + Describe.method(new Universe.Hit(meth, Map.of(), t.binaryName), queries.paramNames(new Universe.Hit(meth, Map.of(), t.binaryName), u))));
    }
    lines.sort(Comparator.comparingInt(Line::line));
    for (Line l : lines) {
      out.append(String.format(Locale.ROOT, "L%-5d", l.line)).append(indent).append("  ").append(l.text).append('\n');
    }
    for (TypeInfo member : t.members) {
      outlineType(out, u, summary, path, member, indent + "  ");
    }
  }

  // ---------------------------------------------------------------------------------------------
  // java_config

  String config(Worktree w, String key) {
    String k = key == null ? "" : key.strip();
    if (k.isEmpty()) {
      throw new ToolError("Give a key (e.g. server.port) or a prefix (e.g. app. or app.*).");
    }
    ConfigIndex index = w.configIndex();
    boolean prefix = k.endsWith(".") || k.endsWith("*");
    String base = k.replaceAll("[.*]+$", "");
    boolean exact =
        !prefix
            && (!index.definitions(base).stream().filter(d -> d.entry().key().equalsIgnoreCase(base) || ConfigKeys.canonical(d.entry().key()).equals(ConfigKeys.canonical(base))).toList().isEmpty()
                || !index.bindings(base).isEmpty()
                || index.library().lookup(ConfigKeys.canonical(base)) != null);
    if (exact) {
      StringBuilder out =
          new StringBuilder(ConfigReport.describe(index, base, f -> rel(w, f), false));
      LibraryConfig.Property p = index.library().lookup(ConfigKeys.canonical(base));
      Target lib = ConfigReport.libraryTarget(p, w.universe());
      if (lib != null) {
        out.append("Declared at: ").append(where(w, lib, w.universe())).append('\n');
      }
      return out.toString();
    }
    List<String> keys = index.keysUnder(base, 200);
    List<LibraryConfig.Property> lib = index.library().withPrefix(ConfigKeys.canonical(base) + ".", 50);
    if (keys.isEmpty() && lib.isEmpty()) {
      throw new ToolError("No configuration key or prefix '" + base + "' in this project or its libraries.");
    }
    StringBuilder out = new StringBuilder("# Keys under ").append(base).append('\n');
    for (String key2 : keys) {
      String values =
          index.definitions(key2).stream()
              .filter(d -> d.entry().value() != null && ConfigKeys.canonical(d.entry().key()).equals(ConfigKeys.canonical(key2)))
              .map(d -> (d.entry().profile() == null ? "" : d.entry().profile() + ": ") + d.entry().value())
              .distinct()
              .reduce((a1, b1) -> a1 + " | " + b1)
              .orElse("(not set; bound in Java)");
      out.append("- ").append(key2).append(" = ").append(values).append('\n');
    }
    if (!lib.isEmpty()) {
      out.append("\n## Documented by libraries").append(lib.size() >= 50 ? " (first 50)" : "").append('\n');
      for (LibraryConfig.Property p : lib) {
        out.append("- ").append(p.key());
        if (p.defaultValue() != null) {
          out.append(" (default ").append(p.defaultValue()).append(')');
        }
        if (p.description() != null) {
          out.append(" — ").append(firstSentence(p.description()));
        }
        out.append('\n');
      }
    }
    return out.toString();
  }

  // ---------------------------------------------------------------------------------------------
  // java_find_type

  String findType(Worktree w, String query, int limit, String scope) {
    Universe u = w.universe();
    record Match(int score, String bin, String where, boolean project) {}
    List<Match> matches = new ArrayList<>();
    String q = query.strip();
    if (q.isEmpty()) {
      throw new ToolError("Empty query.");
    }
    boolean wantProject = !"libraries".equals(scope);
    boolean wantLibraries = !"project".equals(scope);
    if (wantProject) {
      w.index().forEachType(
          h -> {
            int score = score(h.type().simpleName, h.type().binaryName, q, true);
            if (score >= 0) {
              matches.add(new Match(score, h.type().binaryName,
                  h.rel() + ":" + (h.file().lines.line(h.type().span.nameStart()) + 1), true));
            }
          });
    }
    if (wantLibraries) {
      for (var deps : List.of(u.deps(), u.jdk())) {
        for (String bin : deps.classNames()) {
          if (bin.startsWith("jdk/internal") || bin.startsWith("sun/") || bin.startsWith("com/sun/")
              // the project's own classes, e.g. from a module jar installed in ~/.m2
              || w.index().type(bin) != null) {
            continue;
          }
          String simple = bin.substring(Math.max(bin.lastIndexOf('/'), bin.lastIndexOf('$')) + 1);
          int score = score(simple, bin, q, false);
          if (score >= 0) {
            matches.add(new Match(score, bin, deps == u.jdk() ? "JDK" : "library", false));
          }
        }
      }
    }
    if (matches.isEmpty()) {
      return "No types match '" + q + "'.\n";
    }
    matches.sort(
        Comparator.comparingInt(Match::score).reversed()
            .thenComparing(m -> !m.project)
            .thenComparing(Match::bin));
    long inProject = matches.stream().filter(Match::project).count();
    StringBuilder out = new StringBuilder();
    out.append(matches.size()).append(" match").append(matches.size() == 1 ? "" : "es")
        .append(" (").append(inProject).append(" in project, ").append(matches.size() - inProject)
        .append(" in libraries/JDK)\n");
    for (Match m : matches.subList(0, Math.min(limit, matches.size()))) {
      out.append("- ").append(TypeRef.qualifiedName(m.bin)).append("  ").append(m.where).append('\n');
    }
    if (matches.size() > limit) {
      out.append("... ").append(matches.size() - limit)
          .append(" more: refine the query, raise limit, or set scope to \"project\"\n");
    }
    return out.toString();
  }

  /**
   * Scores a type for a query; -1 for no match. Project types also match on substrings;
   * libraries (tens of thousands of classes) only on exact names, prefixes and camel humps, so a
   * short query doesn't drown the project's own types.
   */
  private static int score(String simple, String bin, String query, boolean project) {
    if (query.contains(".")) {
      String qualified = TypeRef.qualifiedName(bin);
      return qualified.equals(query) ? 100 : qualified.endsWith("." + query) ? 90 : -1;
    }
    if (simple.equals(query)) {
      return 100;
    }
    String ls = simple.toLowerCase(Locale.ROOT);
    String lq = query.toLowerCase(Locale.ROOT);
    if (ls.equals(lq)) {
      return 90;
    }
    if (simple.startsWith(query) || ls.startsWith(lq)) {
      return 70 - Math.min(20, simple.length() - query.length());
    }
    boolean allCaps = query.length() > 1 && query.chars().allMatch(c -> Character.isUpperCase(c) || Character.isDigit(c));
    if (humps(simple, query)) {
      return allCaps && humpCount(simple) == query.length() ? 60 : 45;
    }
    if (allCaps) {
      return -1; // initials: only hump matches make sense
    }
    return project && ls.contains(lq) ? 20 : -1;
  }

  /**
   * Camel-case hump match: each hump of the query ("Mes", "Em", "I" in "MesEmI") is a prefix of
   * a hump of the name, in order, starting with the name's first hump; humps may be skipped.
   */
  static boolean humps(String name, String query) {
    List<String> q = splitHumps(query);
    List<String> n = splitHumps(name);
    if (q.isEmpty() || n.isEmpty() || !startsWithIgnoreCase(n.get(0), q.get(0))) {
      return false;
    }
    int j = 1;
    for (int i = 1; i < q.size(); i++) {
      while (j < n.size() && !startsWithIgnoreCase(n.get(j), q.get(i))) {
        j++;
      }
      if (j >= n.size()) {
        return false;
      }
      j++;
    }
    return true;
  }

  private static int humpCount(String name) {
    return splitHumps(name).size();
  }

  private static List<String> splitHumps(String s) {
    List<String> out = new ArrayList<>();
    StringBuilder cur = new StringBuilder();
    for (char c : s.toCharArray()) {
      if ((Character.isUpperCase(c) || c == '_' || c == '$') && cur.length() > 0) {
        out.add(cur.toString());
        cur.setLength(0);
      }
      if (c != '_' && c != '$') {
        cur.append(c);
      }
    }
    if (cur.length() > 0) {
      out.add(cur.toString());
    }
    return out;
  }

  private static boolean startsWithIgnoreCase(String s, String prefix) {
    return s.regionMatches(true, 0, prefix, 0, prefix.length());
  }

  // ---------------------------------------------------------------------------------------------
  // Shared: names -> classes and members

  /** A parsed symbol: {@code com.foo.Outer.Inner#member(Type, Type)}. */
  record Spec(String className, String member, List<String> paramTypes) {
    static Spec parse(String symbol) {
      String s = symbol == null ? "" : symbol.strip().replace('$', '.');
      if (s.isEmpty()) {
        throw new ToolError("Empty symbol.");
      }
      List<String> params = null;
      int paren = s.indexOf('(');
      if (paren >= 0) {
        String inside = s.substring(paren + 1, s.lastIndexOf(')') < 0 ? s.length() : s.lastIndexOf(')'));
        params = new ArrayList<>();
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        for (char c : inside.toCharArray()) {
          if (c == '<') depth++;
          if (c == '>') depth--;
          if (c == ',' && depth == 0) {
            params.add(cur.toString().strip());
            cur.setLength(0);
          } else {
            cur.append(c);
          }
        }
        if (!cur.toString().isBlank()) {
          params.add(cur.toString().strip());
        }
        s = s.substring(0, paren);
      }
      int hash = s.indexOf('#');
      if (hash >= 0) {
        return new Spec(s.substring(0, hash), s.substring(hash + 1), params);
      }
      return new Spec(s, null, params);
    }
  }

  /** Resolves a class name: fully qualified, partially qualified (Outer.Inner) or simple. */
  private String resolveClass(Worktree w, Universe u, String name, boolean hasMember) {
    List<String> candidates = classCandidates(w, u, name);
    if (candidates.size() == 1) {
      return candidates.get(0);
    }
    if (candidates.isEmpty()) {
      throw new ToolError(
          "No class named '" + name + "'."
              + (hasMember ? "" : " For a member use Class#member, e.g. Client#send.")
              + " Try java_find_type.");
    }
    // Several classes share the simple name: prefer the project's own if exactly one.
    List<String> own = candidates.stream().filter(c -> w.index().type(c) != null).toList();
    if (own.size() == 1) {
      return own.get(0);
    }
    StringBuilder msg = new StringBuilder("'" + name + "' is ambiguous; use a qualified name:\n");
    for (String c : candidates.subList(0, Math.min(candidates.size(), 15))) {
      msg.append("- ").append(TypeRef.qualifiedName(c)).append('\n');
    }
    throw new ToolError(msg.toString());
  }

  private List<String> classCandidates(Worktree w, Universe u, String name) {
    List<String> segments = List.of(name.split("\\."));
    Set<String> out = new LinkedHashSet<>();
    if (segments.size() > 1) {
      String canonical = u.resolveCanonical(segments);
      if (canonical != null) {
        return List.of(canonical);
      }
    }
    // Simple or partially qualified (Outer.Inner): match by suffix.
    String suffix = "/" + name.replace('.', '$');
    String suffixPkg = "/" + name.replace('.', '/');
    w.index().forEachType(
        h -> {
          String b = h.type().binaryName;
          if (("/" + b).endsWith(suffix) || ("/" + b).endsWith(suffixPkg)) {
            out.add(b);
          }
        });
    List<String> libs = new ArrayList<>();
    for (var deps : List.of(u.deps(), u.jdk())) {
      for (String b : deps.classNames()) {
        if (("/" + b).endsWith(suffix) || ("/" + b).endsWith(suffixPkg)) {
          libs.add(b);
        }
      }
    }
    libs.sort(Comparator.comparingInt((String b) -> b.startsWith("java/") ? 0 : b.startsWith("javax/") ? 1 : 2).thenComparing(b -> b));
    for (String b : libs) {
      if (!b.startsWith("jdk/internal") && !b.startsWith("sun/") && !b.startsWith("com/sun/")) {
        out.add(b);
      }
    }
    return new ArrayList<>(out);
  }

  /** Members named in the spec (all overloads, or those matching the given parameter types). */
  private List<Universe.Hit> members(Universe u, ClassModel m, Spec spec) {
    boolean ctor = spec.member.equals("<init>") || spec.member.equals(TypeRef.simpleName(m.binaryName()).replaceAll(".*\\.", ""));
    List<Universe.Hit> out = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    TypeRef self = TypeRef.ofClass(m.binaryName(), List.of());
    u.walk(
        self,
        (model, b) -> {
          if (ctor && !model.binaryName().equals(m.binaryName())) {
            return true;
          }
          for (Member member : model.methods()) {
            boolean nameOk =
                ctor ? member.kind() == Member.Kind.CONSTRUCTOR : member.name().equals(spec.member) && member.kind() == Member.Kind.METHOD;
            Universe.Hit hit = new Universe.Hit(member, b, model.binaryName());
            // An override hides the supertype declaration it overrides: compare parameter
            // types after substituting type arguments (Repository<User>.save(T) is save(User)).
            if (nameOk && paramsMatch(member, spec.paramTypes) && seen.add(member.arity() + ":" + erased(hit))) {
              out.add(hit);
            }
          }
          if (!ctor) {
            for (Member f : model.fields()) {
              if (f.name().equals(spec.member) && spec.paramTypes == null && seen.add("field")) {
                out.add(new Universe.Hit(f, b, model.binaryName()));
              }
            }
          }
          return false;
        });
    return out;
  }

  private static String erased(Universe.Hit h) {
    StringBuilder sb = new StringBuilder();
    for (Member.Param p : h.params()) {
      sb.append(p.type() == null ? "?" : p.type().display()).append(',');
    }
    return sb.toString();
  }

  private static boolean paramsMatch(Member m, List<String> wanted) {
    if (wanted == null) {
      return true;
    }
    List<Member.Param> params = m.params();
    if (params.size() != wanted.size()) {
      return false;
    }
    for (int i = 0; i < params.size(); i++) {
      TypeRef t = params.get(i).type();
      String have = t == null ? "?" : t.display().replaceAll("<.*>", "");
      String want = wanted.get(i).replaceAll("<.*>", "").replace("...", "[]").strip();
      want = want.substring(want.lastIndexOf('.') + 1);
      if (!have.equals(want) && !have.endsWith("." + want) && t != null && t.tvar() == null) {
        return false;
      }
    }
    return true;
  }

  private static String suggestMembers(ClassModel m, String wanted) {
    Set<String> names = new LinkedHashSet<>();
    String lw = wanted.toLowerCase(Locale.ROOT);
    for (Member meth : m.methods()) {
      if (meth.name().toLowerCase(Locale.ROOT).contains(lw) || lw.contains(meth.name().toLowerCase(Locale.ROOT))) {
        names.add(meth.name());
      }
    }
    return names.isEmpty() ? ". Use java_api on the class to list its members." : ". Did you mean: " + String.join(", ", names) + "?";
  }

  // ---------------------------------------------------------------------------------------------
  // Locations

  /** "src/.../Foo.java:12", or for libraries the jar and where its source was extracted. */
  private String where(Worktree w, Target target, Universe u) {
    if (target instanceof Target.Binary b) {
      String origin = b.origin() == null ? "JDK" : Path.of(b.origin()).getFileName().toString();
      Target.Source src = queries.source(target, u);
      if (src == null) {
        return "from " + origin;
      }
      boolean stub = src.file().toString().contains("/stubs/");
      return "from " + origin + (stub ? " (no sources jar; generated stub) " : " — source: ")
          + src.file() + ":" + (src.lines().line(src.start()) + 1);
    }
    Target.Source src = (Target.Source) target;
    return rel(w, src.file()) + ":" + (src.lines().line(src.start()) + 1);
  }

  private static String rel(Worktree w, Path file) {
    String rel = w.rel(file);
    return rel != null ? rel : file.toString();
  }

  /** Reads "Client.send" (no '#') as class Client, member send when no class has that name. */
  private Spec spec(Worktree w, Universe u, String symbol) {
    Spec spec = Spec.parse(symbol);
    int dot = spec.className.lastIndexOf('.');
    if (spec.member == null
        && dot > 0
        && Character.isLowerCase(spec.className.charAt(dot + 1))
        && classCandidates(w, u, spec.className).isEmpty()) {
      return new Spec(spec.className.substring(0, dot), spec.className.substring(dot + 1), spec.paramTypes);
    }
    return spec;
  }
}
