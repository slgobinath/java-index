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

import com.google.turbine.model.TurbineTyKind;
import dev.javaindex.model.ClassModel;
import dev.javaindex.model.Member;
import dev.javaindex.model.TypeRef;
import dev.javaindex.model.Universe;
import dev.javaindex.resolve.Symbol;
import java.util.ArrayList;
import java.util.List;

/** Java-like one-line signatures for hovers, signature help and hierarchy items. */
public final class Describe {
  private Describe() {}

  public static String signature(Symbol s, Universe u, List<String> paramNames) {
    return switch (s.kind()) {
      case CLASS -> s.owner() == null ? "class " + s.name() : classHeader(u.find(s.owner()), s.owner());
      case FIELD -> (s.isStatic() ? "static " : "") + type(s.type()) + " " + s.name();
      case LOCAL -> type(s.type()) + " " + s.name();
      case METHOD, CONSTRUCTOR -> method(s.hit(), paramNames);
      case PACKAGE -> "package " + s.owner().replace('/', '.');
    };
  }

  public static String method(Universe.Hit hit, List<String> paramNames) {
    if (hit == null) {
      return "";
    }
    Member m = hit.member();
    StringBuilder sb = new StringBuilder();
    if (m.isStatic()) {
      sb.append("static ");
    }
    if (m.kind() == Member.Kind.CONSTRUCTOR) {
      sb.append(TypeRef.simpleName(hit.owner()));
    } else {
      sb.append(type(hit.type())).append(' ').append(m.name());
    }
    sb.append('(').append(String.join(", ", params(hit, paramNames))).append(')');
    return sb.toString();
  }

  /** "Type name" for each parameter; varargs rendered as {@code T...}. */
  public static List<String> params(Universe.Hit hit, List<String> paramNames) {
    List<Member.Param> params = hit.params();
    List<String> out = new ArrayList<>();
    for (int i = 0; i < params.size(); i++) {
      String ty = type(params.get(i).type());
      if (hit.member().varargs() && i == params.size() - 1 && ty.endsWith("[]")) {
        ty = ty.substring(0, ty.length() - 2) + "...";
      }
      String name = i < paramNames.size() ? paramNames.get(i) : null;
      out.add(name == null ? ty : ty + " " + name);
    }
    return out;
  }

  public static String classHeader(ClassModel m, String binaryName) {
    if (m == null) {
      return "class " + TypeRef.qualifiedName(binaryName);
    }
    StringBuilder sb = new StringBuilder(keyword(m.kind())).append(' ');
    sb.append(TypeRef.qualifiedName(binaryName));
    if (!m.typeParams().isEmpty()) {
      sb.append('<').append(String.join(", ", m.typeParams())).append('>');
    }
    // Both models list a class's superclass first; every other supertype is an interface.
    boolean isInterface = m.kind() == TurbineTyKind.INTERFACE || m.kind() == TurbineTyKind.ANNOTATION;
    List<String> ifaces = new ArrayList<>();
    List<TypeRef> supers = m.supertypes();
    for (int i = 0; i < supers.size(); i++) {
      TypeRef s = supers.get(i);
      if (s.cls() == null || IMPLICIT.contains(s.cls())) {
        continue;
      }
      if (i == 0 && !isInterface) {
        sb.append(" extends ").append(s.display());
      } else {
        ifaces.add(s.display());
      }
    }
    if (!ifaces.isEmpty()) {
      sb.append(isInterface ? " extends " : " implements ").append(String.join(", ", ifaces));
    }
    return sb.toString();
  }

  private static final java.util.Set<String> IMPLICIT =
      java.util.Set.of(
          "java/lang/Object", "java/lang/Enum", "java/lang/Record", "java/lang/annotation/Annotation");

  public static String keyword(TurbineTyKind kind) {
    return switch (kind) {
      case INTERFACE -> "interface";
      case ENUM -> "enum";
      case ANNOTATION -> "@interface";
      case RECORD -> "record";
      default -> "class";
    };
  }

  /** Source-like rendering of a type as written: {@code Map<String, ? extends Item>[]}. */
  public static String tree(com.google.turbine.tree.Tree tree) {
    if (tree == null) {
      return "";
    }
    return switch (tree) {
      case com.google.turbine.tree.Tree.ClassTy c -> {
        String s = c.name().value();
        if (!c.tyargs().isEmpty()) {
          List<String> args = new ArrayList<>();
          c.tyargs().forEach(a -> args.add(tree(a)));
          s += "<" + String.join(", ", args) + ">";
        }
        yield s;
      }
      case com.google.turbine.tree.Tree.ArrTy a -> tree(a.elem()) + "[]";
      case com.google.turbine.tree.Tree.PrimTy p -> p.tykind().toString();
      case com.google.turbine.tree.Tree.VoidTy v -> "void";
      case com.google.turbine.tree.Tree.WildTy w ->
          w.upper()
              .map(u -> "? extends " + tree(u))
              .or(() -> w.lower().map(l -> "? super " + tree(l)))
              .orElse("?");
      default -> tree.toString();
    };
  }

  public static String type(TypeRef t) {
    return t == null ? "?" : t.display();
  }
}
