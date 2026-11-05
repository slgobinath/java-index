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
import dev.javaindex.index.FileSummary;
import dev.javaindex.index.FileSummary.FieldDecl;
import dev.javaindex.index.FileSummary.MethodDecl;
import dev.javaindex.index.FileSummary.TypeInfo;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A {@link ClassModel} over a source summary; member types are resolved in the file's scope. */
final class SourceClassModel implements ClassModel {
  private final Universe universe;
  private final TypeInfo info;
  private final FileSummary file;
  private final Path path;
  private List<TypeRef> supertypes;
  private List<Member> fields;
  private List<Member> methods;

  SourceClassModel(Universe universe, TypeInfo info, FileSummary file, Path path) {
    this.universe = universe;
    this.info = info;
    this.file = file;
    this.path = path;
  }

  TypeInfo info() {
    return info;
  }

  FileSummary file() {
    return file;
  }

  @Override
  public String binaryName() {
    return info.binaryName;
  }

  @Override
  public TurbineTyKind kind() {
    return info.kind;
  }

  @Override
  public List<String> typeParams() {
    return info.typeParams;
  }

  private Universe.Scope scope() {
    return new Universe.Scope(file, info, Set.of());
  }

  @Override
  public synchronized List<TypeRef> supertypes() {
    if (supertypes != null) {
      return supertypes;
    }
    if (!universe.beginSupertypes(info.binaryName)) {
      // Cyclic lookup (e.g. resolving `extends` needs inherited member types): answer nothing.
      return List.of();
    }
    try {
      List<TypeRef> out = new ArrayList<>();
      if (info.superclass != null) {
        add(out, universe.resolveType(info.superclass, scope()));
      } else {
        switch (info.kind) {
          case ENUM ->
              out.add(
                  TypeRef.ofClass(
                      "java/lang/Enum", List.of(TypeRef.ofClass(info.binaryName, List.of()))));
          case RECORD -> out.add(TypeRef.ofClass("java/lang/Record", List.of()));
          case CLASS -> out.add(TypeRef.OBJECT);
          case ANNOTATION ->
              out.add(TypeRef.ofClass("java/lang/annotation/Annotation", List.of()));
          default -> {}
        }
      }
      for (var i : info.interfaces) {
        add(out, universe.resolveType(i, scope()));
      }
      supertypes = List.copyOf(out);
      return supertypes;
    } finally {
      universe.endSupertypes(info.binaryName);
    }
  }

  private static void add(List<TypeRef> out, TypeRef t) {
    if (t != null && t.isClass()) {
      out.add(t);
    }
  }

  private Target.Source target(FileSummary.Span span) {
    return new Target.Source(path, file.lines, span.nameStart(), span.nameEnd());
  }

  @Override
  public synchronized List<Member> fields() {
    if (fields == null) {
      List<Member> out = new ArrayList<>();
      for (FieldDecl f : info.fields) {
        out.add(
            new Member(
                f.name(),
                Member.Kind.FIELD,
                0,
                false,
                f.isStatic(),
                target(f.span()),
                () -> universe.resolveType(f.type(), scope()),
                List::of,
                false,
                Map::of,
                false,
                f.deprecated()));
      }
      fields = List.copyOf(out);
    }
    return fields;
  }

  @Override
  public synchronized List<Member> methods() {
    if (methods == null) {
      List<Member> out = new ArrayList<>();
      Set<String> declaredNoArg = new HashSet<>();
      for (MethodDecl m : info.methods) {
        if (m.paramTypes().isEmpty()) {
          declaredNoArg.add(m.name());
        }
        Set<String> tvars = Set.copyOf(m.typeParams());
        out.add(
            new Member(
                m.name(),
                m.isConstructor() ? Member.Kind.CONSTRUCTOR : Member.Kind.METHOD,
                m.paramTypes().size(),
                m.varargs(),
                m.isStatic(),
                target(m.span()),
                () ->
                    m.returnType() == null
                        ? null
                        : universe.resolveType(
                            m.returnType(), new Universe.Scope(file, info, tvars)),
                () -> {
                  List<Member.Param> params = new ArrayList<>();
                  Universe.Scope scope = new Universe.Scope(file, info, tvars);
                  for (int i = 0; i < m.paramTypes().size(); i++) {
                    params.add(
                        new Member.Param(
                            m.paramNames().get(i),
                            universe.resolveType(m.paramTypes().get(i), scope)));
                  }
                  return params;
                },
                m.isAbstract(),
                () -> {
                  Map<String, TypeRef> bounds = new java.util.LinkedHashMap<>();
                  Universe.Scope scope = new Universe.Scope(file, info, tvars);
                  for (int i = 0; i < m.typeParams().size(); i++) {
                    var tree = m.typeParamBounds().get(i);
                    TypeRef bound = tree == null ? null : universe.resolveType(tree, scope);
                    bounds.put(m.typeParams().get(i), bound == null ? TypeRef.OBJECT : bound);
                  }
                  return bounds;
                },
                false,
                m.deprecated()));
      }
      if (info.kind == TurbineTyKind.RECORD) {
        // Implicit accessors navigate to the record component.
        for (FieldDecl f : info.fields) {
          if (f.kind() == FieldDecl.Kind.RECORD_COMPONENT && !declaredNoArg.contains(f.name())) {
            out.add(
                new Member(
                    f.name(),
                    Member.Kind.METHOD,
                    0,
                    false,
                    false,
                    target(f.span()),
                    () -> universe.resolveType(f.type(), scope()),
                    List::of,
                    false,
                    Map::of,
                    false,
                    f.deprecated()));
          }
        }
      }
      methods = List.copyOf(out);
    }
    return methods;
  }

  @Override
  public boolean deprecated() {
    return info.deprecated;
  }

  @Override
  public String memberType(String simpleName) {
    TypeInfo m = info.member(simpleName);
    return m == null ? null : m.binaryName;
  }

  @Override
  public Target target() {
    return target(info.span);
  }
}
