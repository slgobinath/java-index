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

import com.google.turbine.model.TurbineTyKind;
import com.google.turbine.tree.Tree;
import java.util.ArrayList;
import java.util.List;

/**
 * The header-level view of one Java source file, as parsed by turbine. Summaries are immutable and
 * keyed by content hash, so a file that is identical across worktrees is parsed and held once.
 * Source text and turbine's javadoc-bearing trees are not retained, only the declaration skeleton.
 */
public final class FileSummary {
  public final String hash;
  /** Package in binary form ({@code com/example}), empty for the default package. */
  public final String pkg;

  public final List<ImportInfo> imports;
  /** Top-level types; nested types hang off {@link TypeInfo#members}. */
  public final List<TypeInfo> types;

  public final boolean parsed;
  public final Lines lines;
  /** Configuration keys this file names (see {@link dev.javaindex.config.ConfigRefScanner}). */
  public final List<ConfigRef> configRefs;
  private final long[] identFilter;

  FileSummary(
      String hash,
      String pkg,
      List<ImportInfo> imports,
      List<TypeInfo> types,
      boolean parsed,
      Lines lines,
      long[] identFilter,
      List<ConfigRef> configRefs) {
    this.hash = hash;
    this.pkg = pkg;
    this.imports = imports;
    this.types = types;
    this.parsed = parsed;
    this.lines = lines;
    this.identFilter = identFilter;
    this.configRefs = configRefs;
  }

  /** False if the file definitely contains no identifier {@code name} (anywhere, bodies included). */
  public boolean mayContain(String name) {
    return IdentFilter.mayContain(identFilter, name);
  }

  /** All types in the file, outer before inner. */
  public List<TypeInfo> allTypes() {
    List<TypeInfo> out = new ArrayList<>();
    for (TypeInfo t : types) {
      t.collect(out);
    }
    return out;
  }

  /** An import declaration; {@code names} are the dotted segments. */
  public record ImportInfo(List<String> names, boolean isStatic, boolean wild) {}

  /**
   * A configuration key named in this file: a {@code ${key}} placeholder, an annotation
   * attribute, or a lookup call's argument. [start, end) spans the key text. For PREFIX and
   * RENAME, {@code owner} is the annotated type ({@code a/b/C}) or method ({@code a/b/C#m}).
   */
  public record ConfigRef(Kind kind, String key, int start, int end, String owner) {
    public enum Kind {
      PLACEHOLDER,
      NAME,
      LOOKUP,
      PREFIX,
      RENAME
    }

    public ConfigRef withOwner(String owner) {
      return new ConfigRef(kind, key, start, end, owner);
    }
  }

  /** A source span: the identifier being declared and the whole declaration. */
  public record Span(int nameStart, int nameEnd, int start, int end) {}

  public static final class TypeInfo {
    public final String binaryName;
    public final String simpleName;
    public final TurbineTyKind kind;
    public final Span span;
    public final List<String> typeParams;
    public final Tree.ClassTy superclass;
    public final List<Tree.ClassTy> interfaces;
    public final List<FieldDecl> fields;
    public final List<MethodDecl> methods;
    public final List<TypeInfo> members;
    public final boolean deprecated;
    public TypeInfo outer;

    TypeInfo(
        String binaryName,
        String simpleName,
        TurbineTyKind kind,
        Span span,
        List<String> typeParams,
        Tree.ClassTy superclass,
        List<Tree.ClassTy> interfaces,
        List<FieldDecl> fields,
        List<MethodDecl> methods,
        List<TypeInfo> members,
        boolean deprecated) {
      this.binaryName = binaryName;
      this.simpleName = simpleName;
      this.kind = kind;
      this.span = span;
      this.typeParams = typeParams;
      this.superclass = superclass;
      this.interfaces = interfaces;
      this.fields = fields;
      this.methods = methods;
      this.members = members;
      this.deprecated = deprecated;
    }

    void collect(List<TypeInfo> out) {
      out.add(this);
      for (TypeInfo m : members) {
        m.collect(out);
      }
    }

    public TypeInfo member(String simple) {
      for (TypeInfo m : members) {
        if (m.simpleName.equals(simple)) {
          return m;
        }
      }
      return null;
    }
  }

  /** A field, enum constant or record component. */
  public record FieldDecl(
      String name, Tree type, Span span, boolean isStatic, Kind kind, boolean deprecated) {
    public enum Kind {
      FIELD,
      ENUM_CONSTANT,
      RECORD_COMPONENT
    }
  }

  /**
   * A method or constructor ({@code <init>}); {@code returnType} is null for constructors.
   * {@code typeParamBounds} holds each type parameter's first bound, or null.
   */
  public record MethodDecl(
      String name,
      List<Tree> paramTypes,
      List<String> paramNames,
      boolean varargs,
      Tree returnType,
      List<String> typeParams,
      List<Tree> typeParamBounds,
      Span span,
      boolean isStatic,
      boolean isAbstract,
      boolean deprecated) {
    public boolean isConstructor() {
      return name.equals("<init>");
    }
  }
}
