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

package dev.javaindex.config;

import com.google.turbine.model.TurbineTyKind;
import dev.javaindex.index.FileSummary;
import dev.javaindex.index.FileSummary.ConfigRef;
import dev.javaindex.index.FileSummary.FieldDecl;
import dev.javaindex.index.FileSummary.MethodDecl;
import dev.javaindex.index.FileSummary.TypeInfo;
import dev.javaindex.index.Lines;
import dev.javaindex.index.TypeHandle;
import dev.javaindex.model.TypeRef;
import dev.javaindex.model.Universe;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * One worktree's configuration picture: keys defined in its config files (per profile) and the
 * Java places that bind or read them. Rebuilt lazily when the worktree's Java or config files
 * change; the expensive inputs (file summaries, parsed config files) are shared.
 */
public final class ConfigIndex {
  /** A key defined in a config file. */
  public record Definition(Path file, Lines lines, ConfigFile.Entry entry) {}

  /** A Java place that names a key. */
  public record Binding(Path file, Lines lines, int start, int end, Kind kind, String key, String detail) {
    public enum Kind {
      /** {@code ${key}} in {@code @Value} and the like. */
      PLACEHOLDER,
      /** {@code @ConfigProperty(name = "key")}. */
      NAME,
      /** {@code environment.getProperty("key")}, {@code config.getValue("key", ...)}. */
      LOOKUP,
      /** The prefix of {@code @ConfigurationProperties} / {@code @ConfigMapping}. */
      PREFIX,
      /** A member of a {@code @ConfigurationProperties} class or {@code @ConfigMapping} interface. */
      PROPERTY
    }
  }

  private final Map<String, List<Definition>> definitions = new HashMap<>();
  private final Map<String, List<Binding>> bindings = new HashMap<>();
  /** Bindings whose key has wildcard segments (map-valued properties). */
  private final List<Binding> patterns = new ArrayList<>();
  private final LibraryConfig library;

  private ConfigIndex(LibraryConfig library) {
    this.library = library;
  }

  public LibraryConfig library() {
    return library;
  }

  /**
   * @param configFiles the worktree's config files, relative path -> parsed content
   * @param javaFiles the worktree's Java files, relative path -> summary
   */
  public static ConfigIndex build(
      Universe u,
      Map<String, ConfigFile> configFiles,
      Map<String, FileSummary> javaFiles,
      LibraryConfig library) {
    ConfigIndex index = new ConfigIndex(library);
    configFiles.forEach(
        (rel, file) -> {
          Path path = u.path(rel);
          for (ConfigFile.Entry e : file.entries) {
            index.definitions.computeIfAbsent(e.canonical(), k -> new ArrayList<>())
                .add(new Definition(path, file.lines, e));
          }
        });
    Map<String, String> renames = new HashMap<>();
    javaFiles.forEach(
        (rel, summary) -> {
          for (ConfigRef r : summary.configRefs) {
            if (r.kind() == ConfigRef.Kind.RENAME && r.owner() != null) {
              renames.put(r.owner(), r.key());
            }
          }
        });
    javaFiles.forEach(
        (rel, summary) -> {
          Path path = u.path(rel);
          for (ConfigRef r : summary.configRefs) {
            switch (r.kind()) {
              case PLACEHOLDER, NAME, LOOKUP ->
                  index.add(new Binding(path, summary.lines, r.start(), r.end(), Binding.Kind.valueOf(r.kind().name()), r.key(), null));
              case PREFIX -> {
                index.add(new Binding(path, summary.lines, r.start(), r.end(), Binding.Kind.PREFIX, r.key(), r.owner()));
                if (r.owner() != null) {
                  index.bindPrefix(u, r.key(), r.owner(), renames);
                }
              }
              case RENAME -> {}
            }
          }
        });
    return index;
  }

  private void add(Binding b) {
    String canonical = ConfigKeys.canonical(b.key);
    if (canonical.contains("*")) {
      patterns.add(b);
    } else {
      bindings.computeIfAbsent(canonical, k -> new ArrayList<>()).add(b);
    }
  }

  /** Binds every member of the type (or @Bean method's return type) named by {@code owner}. */
  private void bindPrefix(Universe u, String prefix, String owner, Map<String, String> renames) {
    String typeName = owner;
    int hash = owner.indexOf('#');
    if (hash >= 0) {
      // @Bean @ConfigurationProperties("app") Foo foo(): bind Foo's properties
      TypeHandle declaring = u.index().type(owner.substring(0, hash));
      typeName = null;
      if (declaring != null) {
        for (MethodDecl m : declaring.type().methods) {
          if (m.name().equals(owner.substring(hash + 1)) && m.returnType() != null) {
            TypeRef t = u.resolveType(m.returnType(), new Universe.Scope(declaring.file(), declaring.type(), Set.of()));
            typeName = t == null ? null : t.cls();
          }
        }
      }
    }
    if (typeName != null) {
      bindType(u, prefix, typeName, renames, new HashSet<>(), 0);
    }
  }

  private void bindType(
      Universe u, String prefix, String binaryName, Map<String, String> renames, Set<String> seen, int depth) {
    TypeHandle h = u.index().type(binaryName);
    if (h == null || depth > 8 || !seen.add(binaryName)) {
      return;
    }
    TypeInfo type = h.type();
    Path path = u.path(h.rel());
    Universe.Scope scope = new Universe.Scope(h.file(), type, Set.of());
    String label = TypeRef.simpleName(binaryName);
    if (type.kind == TurbineTyKind.INTERFACE) {
      // @ConfigMapping: each abstract no-arg method is a property
      for (MethodDecl m : type.methods) {
        if (!m.isAbstract() || !m.paramTypes().isEmpty() || m.returnType() == null) {
          continue;
        }
        String name = renames.getOrDefault(binaryName + "#" + m.name(), ConfigKeys.kebab(m.name()));
        member(u, prefix + "." + name, path, h.file().lines, m.span(), label + "." + m.name() + "()", u.resolveType(m.returnType(), scope), renames, seen, depth);
      }
    } else {
      for (FieldDecl f : type.fields) {
        if (f.isStatic()) {
          continue;
        }
        member(u, prefix + "." + ConfigKeys.kebab(f.name()), path, h.file().lines, f.span(), label + "." + f.name(), u.resolveType(f.type(), scope), renames, seen, depth);
      }
    }
    seen.remove(binaryName);
  }

  private void member(
      Universe u,
      String key,
      Path path,
      Lines lines,
      FileSummary.Span span,
      String detail,
      TypeRef type,
      Map<String, String> renames,
      Set<String> seen,
      int depth) {
    add(new Binding(path, lines, span.nameStart(), span.nameEnd(), Binding.Kind.PROPERTY, key, detail));
    if (type == null || type.cls() == null) {
      return;
    }
    String cls = type.cls();
    TypeRef element = type;
    String elementKey = key;
    if (type.dims() > 0 || isCollection(cls)) {
      element = type.dims() > 0 ? type.component() : type.args().isEmpty() ? null : type.args().get(0);
    } else if (cls.equals("java/util/Map") || cls.equals("java/util/SortedMap") || cls.equals("java/util/HashMap")) {
      element = type.args().size() < 2 ? null : type.args().get(1);
      elementKey = key + ".*";
      add(new Binding(path, lines, span.nameStart(), span.nameEnd(), Binding.Kind.PROPERTY, key + ".*", detail));
    }
    if (element != null && element.cls() != null && u.index().type(element.cls()) != null) {
      TypeHandle nested = u.index().type(element.cls());
      if (nested.type().kind != TurbineTyKind.ENUM) {
        bindType(u, elementKey, element.cls(), renames, seen, depth + 1);
      }
    }
  }

  private static boolean isCollection(String cls) {
    return cls.equals("java/util/List")
        || cls.equals("java/util/Set")
        || cls.equals("java/util/Collection")
        || cls.equals("java/util/Optional");
  }

  // ---------------------------------------------------------------------------------------------
  // Queries

  /** Where the key is defined, every profile; for a parent key, its nested entries too. */
  public List<Definition> definitions(String key) {
    String canonical = ConfigKeys.canonical(key);
    List<Definition> out = new ArrayList<>(definitions.getOrDefault(canonical, List.of()));
    if (out.isEmpty()) {
      // properties files have no parent entries: show the keys underneath instead
      definitions.forEach(
          (k, defs) -> {
            if (k.startsWith(canonical + ".")) {
              out.addAll(defs);
            }
          });
      out.sort((a, b) -> a.entry().key().compareTo(b.entry().key()));
    }
    return out;
  }

  /** Java places that bind or read the key (map-valued properties match their entries). */
  public List<Binding> bindings(String key) {
    String canonical = ConfigKeys.canonical(key);
    List<Binding> out = new ArrayList<>(bindings.getOrDefault(canonical, List.of()));
    String[] segments = canonical.split("\\.");
    for (Binding b : patterns) {
      String[] pattern = ConfigKeys.canonical(b.key).split("\\.");
      if (pattern.length == segments.length) {
        boolean match = true;
        for (int i = 0; i < pattern.length && match; i++) {
          match = pattern[i].equals("*") || pattern[i].equals(segments[i]);
        }
        if (match) {
          out.add(b);
        }
      }
    }
    return out;
  }

  /** The Java binding whose key text or member name spans {@code offset} in {@code file}. */
  public Binding bindingAt(Path file, int offset) {
    for (List<Binding> list : bindings.values()) {
      for (Binding b : list) {
        if (b.file.equals(file) && b.start <= offset && offset <= b.end) {
          return b;
        }
      }
    }
    return null;
  }

  /** All known keys (defined or bound) under a prefix, for listings. */
  public List<String> keysUnder(String prefix, int limit) {
    String canonical = ConfigKeys.canonical(prefix);
    Set<String> keys = new java.util.TreeSet<>();
    Function<String, Boolean> under = k -> k.equals(canonical) || k.startsWith(canonical + ".");
    definitions.forEach((k, defs) -> {
      if (under.apply(k)) {
        defs.stream().filter(d -> d.entry().value() != null).forEach(d -> keys.add(d.entry().key()));
      }
    });
    bindings.forEach((k, list) -> {
      if (under.apply(k)) {
        list.stream().filter(b -> b.kind != Binding.Kind.PREFIX).forEach(b -> keys.add(b.key));
      }
    });
    List<String> out = new ArrayList<>(keys);
    return out.size() > limit ? out.subList(0, limit) : out;
  }
}
