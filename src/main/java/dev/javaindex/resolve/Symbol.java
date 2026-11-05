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

import dev.javaindex.model.Member;
import dev.javaindex.model.Target;
import dev.javaindex.model.TypeRef;
import dev.javaindex.model.Universe;
import java.util.List;
import java.util.function.Supplier;

/**
 * What an identifier refers to.
 *
 * @param owner binary name of the declaring class (of the class itself for {@link Kind#CLASS})
 * @param hit the chosen field/method/constructor, with type bindings; null for classes and locals
 * @param overloads for calls: every candidate method or constructor, nearest first
 * @param alternate for {@code new Foo(...)} resolved to a constructor: the class {@code Foo}
 */
public record Symbol(
    Kind kind,
    String name,
    Target target,
    String owner,
    Universe.Hit hit,
    Supplier<TypeRef> typeSupplier,
    List<Universe.Hit> overloads,
    Target alternate) {

  public enum Kind {
    CLASS,
    FIELD,
    METHOD,
    CONSTRUCTOR,
    LOCAL,
    /** A package name segment (in a qualified name or import): not a reference to anything. */
    PACKAGE
  }

  /** Type of the symbol: a local's or field's type, a method's return type, a class's own type. */
  public TypeRef type() {
    return typeSupplier == null ? null : typeSupplier.get();
  }

  public boolean isStatic() {
    return hit != null && hit.member().isStatic();
  }

  static Symbol ofPackage(String name, String packageBinary) {
    return new Symbol(Kind.PACKAGE, name, null, packageBinary, null, null, List.of(), null);
  }

  static Symbol of(Universe.Hit hit, List<Universe.Hit> overloads) {
    Member m = hit.member();
    Kind kind =
        switch (m.kind()) {
          case FIELD -> Kind.FIELD;
          case CONSTRUCTOR -> Kind.CONSTRUCTOR;
          default -> Kind.METHOD;
        };
    return new Symbol(kind, m.name(), m.target(), hit.owner(), hit, hit::type, overloads, null);
  }
}
