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

import com.google.turbine.type.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A minimal type: a class (binary name + type arguments), a type variable, or a primitive, with an
 * array dimension count. Enough for following member chains like {@code map.get(k).foo()}.
 */
public record TypeRef(String cls, List<TypeRef> args, String tvar, String prim, int dims) {
  public static final TypeRef OBJECT = ofClass("java/lang/Object", List.of());
  public static final TypeRef STRING = ofClass("java/lang/String", List.of());
  public static final TypeRef VOID = ofPrim("void");

  public static TypeRef ofClass(String binaryName, List<TypeRef> args) {
    return new TypeRef(binaryName, List.copyOf(args), null, null, 0);
  }

  public static TypeRef ofVar(String name) {
    return new TypeRef(null, List.of(), name, null, 0);
  }

  public static TypeRef ofPrim(String name) {
    return new TypeRef(null, List.of(), null, name, 0);
  }

  public TypeRef array() {
    return new TypeRef(cls, args, tvar, prim, dims + 1);
  }

  public TypeRef component() {
    return dims == 0 ? null : new TypeRef(cls, args, tvar, prim, dims - 1);
  }

  /** True for a non-array class type, i.e. something with members to look up. */
  public boolean isClass() {
    return cls != null && dims == 0;
  }

  public TypeRef subst(Map<String, TypeRef> bindings) {
    if (bindings.isEmpty()) {
      return this;
    }
    if (tvar != null) {
      TypeRef bound = bindings.get(tvar);
      if (bound == null) {
        return this;
      }
      return dims == 0
          ? bound
          : new TypeRef(bound.cls, bound.args, bound.tvar, bound.prim, bound.dims + dims);
    }
    if (args.isEmpty()) {
      return this;
    }
    List<TypeRef> out = new ArrayList<>(args.size());
    for (TypeRef a : args) {
      out.add(a == null ? null : a.subst(bindings));
    }
    return new TypeRef(cls, out, null, null, dims);
  }

  /** Java-like rendering with simple class names, e.g. {@code Map<String, List<Item>>[]}. */
  public String display() {
    StringBuilder sb = new StringBuilder();
    if (cls != null) {
      sb.append(simpleName(cls));
      if (!args.isEmpty()) {
        sb.append('<');
        for (int i = 0; i < args.size(); i++) {
          if (i > 0) {
            sb.append(", ");
          }
          sb.append(args.get(i) == null ? "?" : args.get(i).display());
        }
        sb.append('>');
      }
    } else if (tvar != null) {
      sb.append(tvar);
    } else {
      sb.append(prim);
    }
    sb.append("[]".repeat(dims));
    return sb.toString();
  }

  /** {@code a/b/Outer$Inner} -> {@code Outer.Inner}. */
  public static String simpleName(String binaryName) {
    return binaryName.substring(binaryName.lastIndexOf('/') + 1).replace('$', '.');
  }

  /** {@code a/b/Outer$Inner} -> {@code a.b.Outer.Inner}. */
  public static String qualifiedName(String binaryName) {
    return binaryName.replace('/', '.').replace('$', '.');
  }

  /** Converts a turbine bytecode type. */
  public static TypeRef of(Type type) {
    if (type == null) {
      return null;
    }
    return switch (type) {
      case Type.ClassTy c -> {
        if (c.classes().isEmpty()) {
          yield OBJECT;
        }
        Type.ClassTy.SimpleClassTy last = c.classes().get(c.classes().size() - 1);
        List<TypeRef> args = new ArrayList<>();
        for (Type a : last.targs()) {
          TypeRef r = of(a);
          args.add(r == null ? OBJECT : r);
        }
        yield ofClass(last.sym().binaryName(), args);
      }
      case Type.TyVar v -> ofVar(v.sym().name());
      case Type.ArrayTy a -> {
        TypeRef elem = of(a.elementType());
        yield elem == null ? null : elem.array();
      }
      case Type.PrimTy p -> ofPrim(p.primkind().toString());
      // `? extends X` and `? super X` both become X: for navigation the bound is what matters,
      // e.g. a Function<? super T, ...>'s lambda parameter is a T.
      case Type.WildUpperBoundedTy w -> of(w.bound());
      case Type.WildLowerBoundedTy w -> of(w.bound());
      case Type.WildTy w -> OBJECT;
      case Type.IntersectionTy i -> i.bounds().isEmpty() ? OBJECT : of(i.bounds().get(0));
      default -> type.tyKind() == Type.TyKind.VOID_TY ? VOID : null;
    };
  }
}
