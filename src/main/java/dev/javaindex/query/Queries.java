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

package dev.javaindex.query;

import dev.javaindex.index.FileSummary;
import dev.javaindex.index.FileSummary.MethodDecl;
import dev.javaindex.index.FileSummary.TypeInfo;
import dev.javaindex.index.Lines;
import dev.javaindex.index.TypeHandle;
import dev.javaindex.model.Member;
import dev.javaindex.model.Target;
import dev.javaindex.model.TypeRef;
import dev.javaindex.model.Universe;
import dev.javaindex.project.Project;
import dev.javaindex.project.Worktree;
import dev.javaindex.resolve.Resolver;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Project-wide questions shared by the editor (LSP) and agent (MCP) front ends. */
public final class Queries {
  private final Project project;

  public Queries(Project project) {
    this.project = project;
  }

  /**
   * An occurrence of a name: where it is, which searched-for declaration it matched (null when
   * unresolved), whether it is that declaration itself, and its source line.
   */
  public record Ref(
      Path file,
      FileSummary summary,
      Lines lines,
      int start,
      int end,
      boolean declaration,
      Target target,
      String line) {}

  /**
   * Occurrences that resolve to one of the targets, and same-named occurrences that resolved to
   * nothing (they might be references the resolver couldn't follow).
   */
  public record RefSearch(List<Ref> matches, List<Ref> unresolved) {}

  /**
   * Searches the worktree for references to any of {@code targets} (all named {@code name}).
   * Only files whose identifier filter admits the name are read.
   */
  public RefSearch references(Worktree w, Universe universe, List<Target> targets, String name) {
    List<Ref> matches = Collections.synchronizedList(new ArrayList<>());
    List<Ref> unresolved = Collections.synchronizedList(new ArrayList<>());
    w.index().paths().parallelStream()
        .forEach(
            rel -> {
              FileSummary summary = w.summary(rel);
              if (summary == null || !summary.mayContain(name)) {
                return;
              }
              String text = w.text(rel);
              if (text == null || !text.contains(name)) {
                return;
              }
              Path file = w.root().resolve(rel);
              Lines lines = Lines.of(text);
              for (Resolver.Occurrence o :
                  new Resolver(universe, text, summary, file).occurrences(name)) {
                if (o.targets().isEmpty()) {
                  unresolved.add(
                      new Ref(file, summary, lines, o.start(), o.end(), false, null, line(text, lines, o.start())));
                  continue;
                }
                Target hit = null;
                for (Target t : o.targets()) {
                  for (Target wanted : targets) {
                    if (Target.same(t, wanted)) {
                      hit = wanted;
                    }
                  }
                }
                if (hit != null) {
                  boolean declaration =
                      hit instanceof Target.Source s && s.file().equals(file) && s.start() == o.start();
                  matches.add(
                      new Ref(
                          file, summary, lines, o.start(), o.end(), declaration, hit, line(text, lines, o.start())));
                }
              }
            });
    return new RefSearch(sorted(matches), sorted(unresolved));
  }

  private static String line(String text, Lines lines, int offset) {
    int start = offset - lines.column(offset);
    int end = text.indexOf('\n', offset);
    return text.substring(start, end < 0 ? text.length() : end).strip();
  }

  /** The innermost method (or, outside methods, type) whose declaration contains a reference. */
  public record Enclosing(TypeInfo type, MethodDecl method) {
    public FileSummary.Span span() {
      return method != null ? method.span() : type.span;
    }
  }

  public static Enclosing enclosing(Ref r) {
    TypeInfo bestType = null;
    MethodDecl bestMethod = null;
    for (TypeInfo type : r.summary().allTypes()) {
      if (type.span.start() <= r.start() && r.start() < type.span.end()) {
        bestType = type; // allTypes() lists outer before inner, so the last match is innermost
        bestMethod = null;
        for (MethodDecl m : type.methods) {
          if (m.span().start() <= r.start() && r.start() < m.span().end()) {
            bestMethod = m;
          }
        }
      }
    }
    return bestType == null ? null : new Enclosing(bestType, bestMethod);
  }

  /** Usage counts, keyed by target and the worktree index version they were computed against. */
  private final Map<String, Integer> usageCounts = new java.util.concurrent.ConcurrentHashMap<>();

  /**
   * How many times the targets are used (declarations excluded), or -1 when the name appears in
   * more than {@code maxFiles} files: then counting would take too long for a hover.
   */
  public int countUsages(Worktree w, Universe universe, List<Target> targets, String name, int maxFiles) {
    String key = w.root() + "#" + w.index().version() + "#" + name + "#" + targetKeys(targets);
    Integer cached = usageCounts.get(key);
    if (cached != null) {
      return cached;
    }
    long candidates =
        w.index().paths().stream()
            .map(w::summary)
            .filter(sm -> sm != null && sm.mayContain(name))
            .limit(maxFiles + 1L)
            .count();
    int count =
        candidates > maxFiles
            ? -1
            : (int) references(w, universe, targets, name).matches().stream().filter(r -> !r.declaration()).count();
    if (usageCounts.size() > 2000) {
      usageCounts.clear();
    }
    usageCounts.put(key, count);
    return count;
  }

  private static String targetKeys(List<Target> targets) {
    StringBuilder sb = new StringBuilder();
    for (Target t : targets) {
      sb.append(
              t instanceof Target.Source s ? s.file() + ":" + s.start() : String.valueOf(t))
          .append(';');
    }
    return sb.toString();
  }

  /**
   * The nearest supertype declaration that {@code hit} overrides or implements: same name and
   * parameter types after substituting the supertype's type arguments. Null if none.
   */
  public static Universe.Hit overridden(Universe universe, Universe.Hit hit) {
    Member m = hit.member();
    if (m.kind() != Member.Kind.METHOD || m.isStatic()) {
      return null;
    }
    String signature = erasedParams(hit);
    Universe.Hit[] out = new Universe.Hit[1];
    universe.walk(
        dev.javaindex.model.TypeRef.ofClass(hit.owner(), List.of()),
        (model, bindings) -> {
          if (model.binaryName().equals(hit.owner())) {
            return false;
          }
          for (Member sup : model.methods()) {
            if (sup.kind() == Member.Kind.METHOD
                && !sup.isStatic()
                && sup.name().equals(m.name())
                && sup.arity() == m.arity()) {
              Universe.Hit candidate = new Universe.Hit(sup, bindings, model.binaryName());
              if (erasedParams(candidate).equals(signature)) {
                out[0] = candidate;
                return true;
              }
            }
          }
          return false;
        });
    return out[0];
  }

  private static String erasedParams(Universe.Hit h) {
    StringBuilder sb = new StringBuilder();
    for (Member.Param p : h.params()) {
      sb.append(p.type() == null ? "?" : p.type().tvar() != null ? "?" : p.type().display().replaceAll("<.*>", "")).append(',');
    }
    return sb.toString();
  }

  private static List<Ref> sorted(List<Ref> refs) {
    List<Ref> out = new ArrayList<>(refs);
    out.sort(Comparator.comparing((Ref r) -> r.file().toString()).thenComparingInt(Ref::start));
    return out;
  }

  /** Every transitive subtype of {@code binaryName} declared in the worktree's sources. */
  public static List<TypeHandle> allSubtypes(Worktree w, String binaryName) {
    Map<String, List<TypeHandle>> direct = w.subtypes();
    List<TypeHandle> out = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    ArrayDeque<String> work = new ArrayDeque<>(List.of(binaryName));
    while (!work.isEmpty()) {
      for (TypeHandle h : direct.getOrDefault(work.poll(), List.of())) {
        if (seen.add(h.type().binaryName)) {
          out.add(h);
          work.add(h.type().binaryName);
        }
      }
    }
    return out;
  }

  /** An anonymous subclass {@code new T(...) { ... }}, located at the name {@code T}. */
  public record AnonymousType(
      Path file, String rel, FileSummary summary, int start, int end, String supertype, String line) {}

  /**
   * Anonymous classes in the worktree's sources that extend or implement {@code binaryName},
   * directly or through one of its named subtypes. Turbine skips method bodies, so they are found
   * on demand: only files whose identifier filter admits one of the types' names are read.
   */
  public static List<AnonymousType> anonymousSubtypes(Worktree w, Universe universe, String binaryName) {
    Set<String> types = new HashSet<>(List.of(binaryName));
    allSubtypes(w, binaryName).forEach(h -> types.add(h.type().binaryName));
    Set<String> names = new HashSet<>();
    for (String bin : types) {
      String simple = TypeRef.simpleName(bin);
      names.add(simple.substring(simple.lastIndexOf('.') + 1));
    }
    List<AnonymousType> out = Collections.synchronizedList(new ArrayList<>());
    w.index().paths().parallelStream()
        .forEach(
            rel -> {
              FileSummary summary = w.summary(rel);
              if (summary == null || names.stream().noneMatch(summary::mayContain)) {
                return;
              }
              String text = w.text(rel);
              if (text == null || !text.contains("new")) {
                return;
              }
              Path file = w.root().resolve(rel);
              Lines lines = null;
              for (Resolver.Anonymous a :
                  new Resolver(universe, text, summary, file).anonymousClasses(names)) {
                if (types.contains(a.supertype())) {
                  lines = lines == null ? Lines.of(text) : lines;
                  out.add(
                      new AnonymousType(
                          file, rel, summary, a.start(), a.end(), a.supertype(), line(text, lines, a.start())));
                }
              }
            });
    List<AnonymousType> sorted = new ArrayList<>(out);
    sorted.sort(Comparator.comparing(AnonymousType::rel).thenComparingInt(AnonymousType::start));
    return sorted;
  }

  /** A source position for any target; library declarations are located (extracted) on demand. */
  public Target.Source source(Target target, Universe universe) {
    return switch (target) {
      case Target.Source s -> s;
      case Target.Binary b -> project.locator().locate(b, universe);
      case null -> null;
    };
  }

  /**
   * The declaration exactly as written in its source (modifiers, type parameters, wildcards,
   * parameter names, throws), on one line, without javadoc or leading annotations. For library
   * classes without a sources jar this reads the generated stub, which is rendered from the class
   * file's full generic signatures. Null when no source can be found.
   */
  public String declarationText(Target target, Universe universe) {
    Target.Source src = source(target, universe);
    if (src == null) {
      return null;
    }
    String text = textOf(src.file());
    FileSummary summary = summaryOf(src.file());
    if (text == null || summary == null) {
      return null;
    }
    for (TypeInfo type : summary.allTypes()) {
      if (type.span.nameStart() == src.start()) {
        return clean(text, type.span.start(), text.indexOf('{', type.span.nameEnd()));
      }
      for (FileSummary.FieldDecl f : type.fields) {
        if (f.span().nameStart() == src.start()) {
          int end = f.span().nameEnd();
          while (end < text.length() && ";=,){".indexOf(text.charAt(end)) < 0) {
            end++;
          }
          return clean(text, f.span().start(), end);
        }
      }
      for (MethodDecl m : type.methods) {
        if (m.span().nameStart() == src.start()) {
          int i = text.indexOf('(', m.span().nameEnd());
          int depth = 0;
          for (; i >= 0 && i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') {
              depth++;
            } else if (c == ')' && --depth == 0) {
              break;
            }
          }
          int end = i < 0 ? -1 : i + 1;
          while (end > 0 && end < text.length() && "{;".indexOf(text.charAt(end)) < 0) {
            end++;
          }
          return clean(text, m.span().start(), end);
        }
      }
    }
    return null;
  }

  private static final java.util.regex.Pattern LEADING_ANNOTATIONS =
      java.util.regex.Pattern.compile("^(\\s*@[\\w.]+(\\((?:[^()]|\\([^()]*\\))*\\))?)+\\s*");

  private static String clean(String text, int start, int end) {
    if (end <= start || end > text.length()) {
      return null;
    }
    String decl =
        text.substring(start, end)
            .replaceAll("(?s)/\\*.*?\\*/", " ")
            .replaceAll("//[^\\n]*", " ");
    decl = LEADING_ANNOTATIONS.matcher(decl).replaceFirst("");
    decl = decl.replaceAll("\\s+", " ").strip();
    return decl.isEmpty() ? null : decl;
  }

  private FileSummary summaryOf(Path file) {
    for (Worktree w : project.worktrees()) {
      String rel = w.rel(file);
      if (rel != null) {
        return w.summary(rel);
      }
    }
    return project.locator().summary(file);
  }

  /** Javadoc of a declaration as markdown, read from its source. */
  public String javadoc(Target target, Universe universe) {
    Target.Source src = source(target, universe);
    if (src == null) {
      return null;
    }
    String text = textOf(src.file());
    return text == null ? null : Javadoc.before(text, src.start());
  }

  /** Current text of any file: the open buffer if a worktree has one, else disk. */
  public String textOf(Path file) {
    for (Worktree w : project.worktrees()) {
      String rel = w.rel(file);
      if (rel != null) {
        return w.text(rel);
      }
    }
    try {
      return Files.readString(file);
    } catch (IOException e) {
      return null;
    }
  }

  /**
   * Parameter names for a method. Class files often lack them, so for libraries they come from
   * the located sources (sources jar or src.zip) when available.
   */
  public List<String> paramNames(Universe.Hit hit, Universe universe) {
    List<String> names = new ArrayList<>();
    for (Member.Param p : hit.member().params()) {
      names.add(p.name());
    }
    if (!names.contains(null) || !(hit.member().target() instanceof Target.Binary b)) {
      return names;
    }
    Target.Source src = project.locator().locate(b, universe);
    FileSummary summary = src == null ? null : project.locator().summary(src.file());
    if (summary != null) {
      for (TypeInfo type : summary.allTypes()) {
        for (MethodDecl m : type.methods) {
          if (m.span().nameStart() == src.start() && m.paramNames().size() == names.size()) {
            return m.paramNames();
          }
        }
      }
    }
    return names;
  }
}
