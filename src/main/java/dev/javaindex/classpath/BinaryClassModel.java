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

package dev.javaindex.classpath;

import com.google.turbine.binder.bound.TypeBoundClass;
import com.google.turbine.binder.bytecode.BytecodeBoundClass;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.model.TurbineFlag;
import com.google.turbine.model.TurbineTyKind;
import com.google.turbine.type.Type;
import dev.javaindex.model.ClassModel;
import dev.javaindex.model.Member;
import dev.javaindex.model.Target;
import dev.javaindex.model.TypeRef;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A {@link ClassModel} over a class file read by turbine's bytecode binder. */
final class BinaryClassModel implements ClassModel {
  private final BytecodeBoundClass cls;
  private final String binaryName;
  private final String origin;
  private List<Member> fields;
  private List<Member> methods;
  private List<TypeRef> supertypes;

  BinaryClassModel(BytecodeBoundClass cls, String binaryName, String origin) {
    this.cls = cls;
    this.binaryName = binaryName;
    this.origin = origin;
  }

  @Override
  public String binaryName() {
    return binaryName;
  }

  @Override
  public TurbineTyKind kind() {
    return cls.kind();
  }

  @Override
  public List<String> typeParams() {
    return List.copyOf(cls.typeParameters().keySet());
  }

  @Override
  public Map<String, TypeRef> typeParamBounds() {
    Map<String, TypeRef> out = new java.util.LinkedHashMap<>();
    for (var e : cls.typeParameterTypes().entrySet()) {
      var bounds = e.getValue().upperBound().bounds();
      TypeRef bound = bounds.isEmpty() ? null : TypeRef.of(bounds.get(0));
      if (bound != null) {
        out.put(e.getKey().name(), bound);
      }
    }
    return out;
  }

  @Override
  public synchronized List<TypeRef> supertypes() {
    if (supertypes == null) {
      List<TypeRef> out = new ArrayList<>();
      Type.ClassTy sup = cls.superClassType();
      if (sup != null) {
        out.add(TypeRef.of(sup));
      } else if (cls.superclass() != null) {
        out.add(TypeRef.ofClass(cls.superclass().binaryName(), List.of()));
      }
      for (Type i : cls.interfaceTypes()) {
        TypeRef r = TypeRef.of(i);
        if (r != null) {
          out.add(r);
        }
      }
      supertypes = List.copyOf(out);
    }
    return supertypes;
  }

  @Override
  public synchronized List<Member> fields() {
    if (fields == null) {
      List<Member> out = new ArrayList<>();
      for (TypeBoundClass.FieldInfo f : cls.fields()) {
        if ((f.access() & TurbineFlag.ACC_SYNTHETIC) != 0) {
          continue;
        }
        out.add(
            new Member(
                f.name(),
                Member.Kind.FIELD,
                0,
                false,
                (f.access() & TurbineFlag.ACC_STATIC) != 0,
                new Target.Binary(origin, binaryName, f.name(), Member.Kind.FIELD, 0, List.of()),
                () -> TypeRef.of(f.type()),
                List::of,
                false,
                Map::of,
                (f.access() & (TurbineFlag.ACC_PUBLIC | TurbineFlag.ACC_PROTECTED)) == 0,
                deprecated(f.annotations())));
      }
      fields = List.copyOf(out);
    }
    return fields;
  }

  @Override
  public synchronized List<Member> methods() {
    if (methods == null) {
      List<Member> out = new ArrayList<>();
      for (TypeBoundClass.MethodInfo m : cls.methods()) {
        if ((m.access() & (TurbineFlag.ACC_SYNTHETIC | TurbineFlag.ACC_BRIDGE)) != 0
            || m.name().equals("<clinit>")) {
          continue;
        }
        int arity = 0;
        for (TypeBoundClass.ParamInfo p : m.parameters()) {
          if (!p.synthetic()) {
            arity++;
          }
        }
        boolean ctor = m.name().equals("<init>");
        Member.Kind kind = ctor ? Member.Kind.CONSTRUCTOR : Member.Kind.METHOD;
        out.add(
            new Member(
                m.name(),
                kind,
                arity,
                (m.access() & TurbineFlag.ACC_VARARGS) != 0,
                (m.access() & TurbineFlag.ACC_STATIC) != 0,
                new Target.Binary(origin, binaryName, m.name(), kind, arity, erasedParams(m)),
                () -> ctor ? null : TypeRef.of(m.returnType()),
                () -> params(m),
                (m.access() & TurbineFlag.ACC_ABSTRACT) != 0,
                () -> bounds(m),
                (m.access() & (TurbineFlag.ACC_PUBLIC | TurbineFlag.ACC_PROTECTED)) == 0,
                deprecated(m.annotations())));
      }
      methods = List.copyOf(out);
    }
    return methods;
  }

  private static Map<String, TypeRef> bounds(TypeBoundClass.MethodInfo m) {
    Map<String, TypeRef> out = new java.util.LinkedHashMap<>();
    for (var e : m.tyParams().entrySet()) {
      var bounds = e.getValue().upperBound().bounds();
      TypeRef bound = bounds.isEmpty() ? TypeRef.OBJECT : TypeRef.of(bounds.get(0));
      out.put(e.getKey().name(), bound == null ? TypeRef.OBJECT : bound);
    }
    return out;
  }

  private static List<String> erasedParams(TypeBoundClass.MethodInfo m) {
    List<String> out = new ArrayList<>();
    for (TypeBoundClass.ParamInfo p : m.parameters()) {
      if (!p.synthetic()) {
        out.add(Target.erased(TypeRef.of(p.type())));
      }
    }
    return List.copyOf(out);
  }

  private static List<Member.Param> params(TypeBoundClass.MethodInfo m) {
    List<Member.Param> out = new ArrayList<>();
    for (TypeBoundClass.ParamInfo p : m.parameters()) {
      if (p.synthetic()) {
        continue;
      }
      // turbine invents argN when the class file has no MethodParameters attribute
      String name = p.name() == null || p.name().matches("arg\\d+") ? null : p.name();
      out.add(new Member.Param(name, TypeRef.of(p.type())));
    }
    return out;
  }

  @Override
  public boolean deprecated() {
    return deprecated(cls.annotations());
  }

  private static boolean deprecated(List<com.google.turbine.type.AnnoInfo> annotations) {
    try {
      for (com.google.turbine.type.AnnoInfo a : annotations) {
        if (a.sym() != null && a.sym().binaryName().equals("java/lang/Deprecated")) {
          return true;
        }
      }
    } catch (RuntimeException e) {
      // unreadable annotation: treat as not deprecated
    }
    return false;
  }

  @Override
  public String memberType(String simpleName) {
    ClassSymbol child = cls.children().get(simpleName);
    return child == null ? null : child.binaryName();
  }

  @Override
  public Target target() {
    return new Target.Binary(origin, binaryName, null, Member.Kind.CLASS, 0, List.of());
  }

  BytecodeBoundClass bound() {
    return cls;
  }
}
