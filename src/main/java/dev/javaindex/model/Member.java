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

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** A field or method of a {@link ClassModel}. Types are resolved lazily. */
public final class Member {
  public enum Kind {
    CLASS,
    FIELD,
    METHOD,
    CONSTRUCTOR
  }

  /** A method parameter; {@code name} is null when unknown (class files without -parameters). */
  public record Param(String name, TypeRef type) {}

  private final String name;
  private final Kind kind;
  private final int arity;
  private final boolean varargs;
  private final boolean isStatic;
  private final Target target;
  private Supplier<TypeRef> typeSupplier;
  private TypeRef type;
  private Supplier<List<Param>> paramsSupplier;
  private List<Param> params;
  private final boolean isAbstract;
  private final boolean hidden;
  private final boolean deprecated;
  private Supplier<Map<String, TypeRef>> boundsSupplier;
  private Map<String, TypeRef> bounds;

  public Member(
      String name,
      Kind kind,
      int arity,
      boolean varargs,
      boolean isStatic,
      Target target,
      Supplier<TypeRef> type,
      Supplier<List<Param>> params,
      boolean isAbstract,
      Supplier<Map<String, TypeRef>> typeParamBounds) {
    this(name, kind, arity, varargs, isStatic, target, type, params, isAbstract, typeParamBounds, false, false);
  }

  /** {@code hidden}: not usable from outside its package (private or package-private). */
  public Member(
      String name,
      Kind kind,
      int arity,
      boolean varargs,
      boolean isStatic,
      Target target,
      Supplier<TypeRef> type,
      Supplier<List<Param>> params,
      boolean isAbstract,
      Supplier<Map<String, TypeRef>> typeParamBounds,
      boolean hidden,
      boolean deprecated) {
    this.hidden = hidden;
    this.deprecated = deprecated;
    this.name = name;
    this.kind = kind;
    this.arity = arity;
    this.varargs = varargs;
    this.isStatic = isStatic;
    this.target = target;
    this.typeSupplier = type;
    this.paramsSupplier = params;
    this.isAbstract = isAbstract;
    this.boundsSupplier = typeParamBounds;
  }

  public boolean deprecated() {
    return deprecated;
  }

  public boolean hidden() {
    return hidden;
  }

  public boolean isAbstract() {
    return isAbstract;
  }

  /** The method's own type parameters and their (first) bounds; Object when unbounded. */
  public synchronized Map<String, TypeRef> typeParamBounds() {
    if (boundsSupplier != null) {
      bounds = boundsSupplier.get();
      boundsSupplier = null;
    }
    return bounds == null ? Map.of() : bounds;
  }

  public String name() {
    return name;
  }

  public Kind kind() {
    return kind;
  }

  public int arity() {
    return arity;
  }

  public boolean varargs() {
    return varargs;
  }

  public boolean isStatic() {
    return isStatic;
  }

  public Target target() {
    return target;
  }

  /** Field type or method return type; null if unknown or void. */
  public synchronized TypeRef type() {
    if (typeSupplier != null) {
      type = typeSupplier.get();
      typeSupplier = null;
    }
    return type;
  }

  /** Method parameters (empty for fields). Types may be null when they cannot be resolved. */
  public synchronized List<Param> params() {
    if (paramsSupplier != null) {
      params = paramsSupplier.get();
      paramsSupplier = null;
    }
    return params == null ? List.of() : params;
  }

  public boolean accepts(int argCount) {
    return argCount < 0 || arity == argCount || (varargs && argCount >= arity - 1);
  }
}
