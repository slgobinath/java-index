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

import com.google.turbine.model.TurbineTyKind;
import com.google.turbine.tree.Tree;
import dev.javaindex.classpath.Dependencies;
import dev.javaindex.index.FileSummary;
import dev.javaindex.index.FileSummary.ImportInfo;
import dev.javaindex.index.FileSummary.TypeInfo;
import dev.javaindex.index.TypeHandle;
import dev.javaindex.index.WorktreeIndex;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;

/**
 * Everything visible from one worktree: its sources (shared base + its own delta), its dependency
 * jars and the JDK. Implements just enough of Java's name resolution for navigation.
 */
public final class Universe {
  private final WorktreeIndex index;
  private final Path root;
  private final Dependencies deps;
  private final Dependencies jdk;
  private final ThreadLocal<Set<String>> resolvingSupertypes = ThreadLocal.withInitial(HashSet::new);

  /** Source models memoize resolved supertypes and members; a Universe lives for one request. */
  private final Map<String, ClassModel> sourceModels = new ConcurrentHashMap<>();

  /** A lexical type scope: the file, the innermost enclosing type, and type variables in scope. */
  public record Scope(FileSummary file, TypeInfo enclosing, Set<String> tvars) {}

  /** A member found in a type hierarchy, with the type-argument bindings in effect there. */
  public record Hit(Member member, Map<String, TypeRef> bindings, String owner) {
    public TypeRef type() {
      TypeRef t = member.type();
      return t == null ? null : t.subst(bindings);
    }

    /** Parameters with the owner's type arguments substituted. */
    public List<Member.Param> params() {
      List<Member.Param> out = new ArrayList<>();
      for (Member.Param p : member.params()) {
        out.add(new Member.Param(p.name(), p.type() == null ? null : p.type().subst(bindings)));
      }
      return out;
    }
  }

  public Universe(WorktreeIndex index, Path root, Dependencies deps, Dependencies jdk) {
    this.index = index;
    this.root = root;
    this.deps = deps;
    this.jdk = jdk;
  }

  public WorktreeIndex index() {
    return index;
  }

  public Path root() {
    return root;
  }

  public Dependencies deps() {
    return deps;
  }

  public Dependencies jdk() {
    return jdk;
  }

  public Path path(String rel) {
    return root.resolve(rel);
  }

  // ---------------------------------------------------------------------------------------------
  // Classes and packages

  public ClassModel find(String binaryName) {
    if (binaryName == null) {
      return null;
    }
    ClassModel cached = sourceModels.get(binaryName);
    if (cached != null) {
      return cached;
    }
    TypeHandle h = index.type(binaryName);
    if (h != null) {
      ClassModel m = new SourceClassModel(this, h.type(), h.file(), path(h.rel()));
      sourceModels.put(binaryName, m);
      return m;
    }
    ClassModel m = deps.model(binaryName);
    return m != null ? m : jdk.model(binaryName);
  }

  /** A model for a type declared in a file outside the index (e.g. an extracted library source). */
  public ClassModel external(TypeInfo info, FileSummary file, Path path) {
    return new SourceClassModel(this, info, file, path);
  }

  public boolean classExists(String binaryName) {
    return index.type(binaryName) != null
        || deps.model(binaryName) != null
        || jdk.model(binaryName) != null;
  }

  public boolean packageExists(String pkg) {
    return index.hasPackage(pkg) || deps.hasPackage(pkg) || jdk.hasPackage(pkg);
  }

  boolean beginSupertypes(String binaryName) {
    return resolvingSupertypes.get().add(binaryName);
  }

  void endSupertypes(String binaryName) {
    resolvingSupertypes.get().remove(binaryName);
  }

  /** A member type declared in {@code owner} or inherited from its supertypes. */
  public String findMemberType(String owner, String simple) {
    ArrayDeque<String> work = new ArrayDeque<>();
    Set<String> seen = new HashSet<>();
    work.add(owner);
    while (!work.isEmpty()) {
      String cur = work.poll();
      if (!seen.add(cur)) {
        continue;
      }
      ClassModel m = find(cur);
      if (m == null) {
        continue;
      }
      String found = m.memberType(simple);
      if (found != null) {
        return found;
      }
      if (resolvingSupertypes.get().contains(cur)) {
        continue;
      }
      for (TypeRef s : m.supertypes()) {
        if (s.cls() != null) {
          work.add(s.cls());
        }
      }
    }
    return null;
  }

  // ---------------------------------------------------------------------------------------------
  // Type names

  public TypeRef resolveType(Tree tree, Scope scope) {
    if (tree == null) {
      return null;
    }
    return switch (tree) {
      case Tree.ClassTy c -> {
        List<Tree.ClassTy> parts = c.flatten();
        List<String> names = new ArrayList<>(parts.size());
        for (Tree.ClassTy p : parts) {
          names.add(p.name().value());
        }
        if (names.size() == 1 && isTypeVar(names.get(0), scope)) {
          yield TypeRef.ofVar(names.get(0));
        }
        String bin = resolveTypeName(names, scope);
        if (bin == null) {
          yield null;
        }
        List<TypeRef> args = new ArrayList<>();
        for (Tree.Type a : c.tyargs()) {
          TypeRef r = resolveType(a, scope);
          args.add(r == null ? TypeRef.OBJECT : r);
        }
        yield TypeRef.ofClass(bin, args);
      }
      case Tree.ArrTy a -> {
        TypeRef elem = resolveType(a.elem(), scope);
        yield elem == null ? null : elem.array();
      }
      case Tree.PrimTy p -> TypeRef.ofPrim(p.tykind().toString());
      case Tree.VoidTy v -> TypeRef.VOID;
      case Tree.WildTy w ->
          w.upper()
              .or(w::lower)
              .map(bound -> resolveType(bound, scope))
              .orElse(TypeRef.WILDCARD);
      default -> null;
    };
  }

  public boolean isTypeVar(String name, Scope scope) {
    if (scope.tvars().contains(name)) {
      return true;
    }
    for (TypeInfo t = scope.enclosing(); t != null; t = t.outer) {
      if (t.simpleName.equals(name) || t.member(name) != null) {
        return false;
      }
      if (t.typeParams.contains(name)) {
        return true;
      }
    }
    return false;
  }

  /** Resolves a possibly-qualified type name ({@code Map.Entry}, {@code java.util.List}). */
  public String resolveTypeName(List<String> names, Scope scope) {
    String first = resolveSimpleTypeName(names.get(0), scope);
    if (first != null) {
      String cur = first;
      for (int i = 1; i < names.size() && cur != null; i++) {
        cur = findMemberType(cur, names.get(i));
      }
      if (cur != null) {
        return cur;
      }
    }
    return names.size() > 1 ? resolveCanonical(names) : null;
  }

  /** Resolves a fully qualified name, preferring the longest package prefix that exists. */
  public String resolveCanonical(List<String> names) {
    for (int split = names.size() - 1; split >= 0; split--) {
      String pkg = String.join("/", names.subList(0, split));
      String top = pkg.isEmpty() ? names.get(split) : pkg + "/" + names.get(split);
      if (!classExists(top)) {
        continue;
      }
      String cur = top;
      for (int i = split + 1; i < names.size() && cur != null; i++) {
        cur = findMemberType(cur, names.get(i));
      }
      if (cur != null) {
        return cur;
      }
    }
    return null;
  }

  public String resolveSimpleTypeName(String name, Scope scope) {
    for (TypeInfo t = scope.enclosing(); t != null; t = t.outer) {
      if (t.simpleName.equals(name)) {
        return t.binaryName;
      }
      String member = findMemberType(t.binaryName, name);
      if (member != null) {
        return member;
      }
    }
    FileSummary file = scope.file();
    if (file == null) {
      return classExists("java/lang/" + name) ? "java/lang/" + name : null;
    }
    for (TypeInfo t : file.types) {
      if (t.simpleName.equals(name)) {
        return t.binaryName;
      }
    }
    for (ImportInfo imp : file.imports) {
      if (!imp.wild() && !imp.isStatic() && imp.names().getLast().equals(name)) {
        String bin = resolveCanonical(imp.names());
        if (bin != null) {
          return bin;
        }
      }
    }
    String samePkg = file.pkg.isEmpty() ? name : file.pkg + "/" + name;
    if (classExists(samePkg)) {
      return samePkg;
    }
    for (ImportInfo imp : file.imports) {
      if (!imp.wild()) {
        if (imp.isStatic() && imp.names().getLast().equals(name)) {
          // import static a.B.Inner;
          String owner = resolveCanonical(imp.names().subList(0, imp.names().size() - 1));
          String member = owner == null ? null : findMemberType(owner, name);
          if (member != null) {
            return member;
          }
        }
        continue;
      }
      String asPkg = String.join("/", imp.names()) + "/" + name;
      if (!imp.isStatic() && classExists(asPkg)) {
        return asPkg;
      }
      String owner = resolveCanonical(imp.names());
      if (owner != null) {
        String member = findMemberType(owner, name);
        if (member != null) {
          return member;
        }
      }
    }
    String lang = "java/lang/" + name;
    return classExists(lang) ? lang : null;
  }

  // ---------------------------------------------------------------------------------------------
  // Members

  public Map<String, TypeRef> bindings(ClassModel model, TypeRef ref) {
    List<String> params = model.typeParams();
    if (params.isEmpty() || ref.args().size() != params.size()) {
      return Map.of();
    }
    Map<String, TypeRef> out = new HashMap<>();
    for (int i = 0; i < params.size(); i++) {
      out.put(params.get(i), ref.args().get(i));
    }
    return out;
  }

  /**
   * Visits {@code type} and its supertypes, nearest first, with type arguments substituted along
   * the way. Interfaces implicitly end in {@code Object}. Stops when the visitor returns true.
   */
  public void walk(TypeRef type, BiPredicate<ClassModel, Map<String, TypeRef>> visitor) {
    if (type == null || !type.isClass()) {
      return;
    }
    ArrayDeque<TypeRef> work = new ArrayDeque<>();
    Set<String> seen = new HashSet<>();
    work.add(type);
    boolean sawInterface = false;
    while (!work.isEmpty()) {
      TypeRef cur = work.poll();
      if (!seen.add(cur.cls())) {
        continue;
      }
      ClassModel m = find(cur.cls());
      if (m == null) {
        continue;
      }
      Map<String, TypeRef> b = bindings(m, cur);
      if (visitor.test(m, b)) {
        return;
      }
      sawInterface |= m.kind() == TurbineTyKind.INTERFACE;
      for (TypeRef s : m.supertypes()) {
        work.add(s.subst(b));
      }
      if (work.isEmpty() && sawInterface && !seen.contains("java/lang/Object")) {
        work.add(TypeRef.OBJECT);
      }
    }
  }

  public Hit findField(TypeRef owner, String name) {
    Hit[] out = new Hit[1];
    walk(
        owner,
        (m, b) -> {
          for (Member f : m.fields()) {
            if (f.name().equals(name)) {
              out[0] = new Hit(f, b, m.binaryName());
              return true;
            }
          }
          return false;
        });
    return out[0];
  }

  /** All methods named {@code name} in the hierarchy, nearest declarations first. */
  public List<Hit> findMethods(TypeRef owner, String name) {
    List<Hit> out = new ArrayList<>();
    walk(
        owner,
        (m, b) -> {
          for (Member meth : m.methods()) {
            if (meth.name().equals(name)) {
              out.add(new Hit(meth, b, m.binaryName()));
            }
          }
          return false;
        });
    return out;
  }

  /** Picks the overload matching {@code argCount} (or the first one when none match). */
  public static Hit pick(List<Hit> hits, int argCount) {
    for (Hit h : hits) {
      if (h.member().arity() == argCount) {
        return h;
      }
    }
    for (Hit h : hits) {
      if (h.member().accepts(argCount)) {
        return h;
      }
    }
    return hits.isEmpty() ? null : hits.get(0);
  }

  /** Views {@code type} as an instance of {@code superBinary}, e.g. {@code List<X>} as Iterable. */
  public TypeRef asSuper(TypeRef type, String superBinary) {
    TypeRef[] out = new TypeRef[1];
    walk(
        type,
        (m, b) -> {
          if (m.binaryName().equals(superBinary)) {
            List<TypeRef> args = new ArrayList<>();
            for (String p : m.typeParams()) {
              args.add(b.getOrDefault(p, TypeRef.ofVar(p)));
            }
            out[0] = TypeRef.ofClass(superBinary, args);
            return true;
          }
          return false;
        });
    return out[0];
  }

  public TypeRef thisType(TypeInfo info) {
    List<TypeRef> args = new ArrayList<>();
    for (String p : info.typeParams) {
      args.add(TypeRef.ofVar(p));
    }
    return TypeRef.ofClass(info.binaryName, args);
  }
}
