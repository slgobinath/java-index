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
import java.util.List;

/** A class or interface, backed either by a source summary or by bytecode. */
public interface ClassModel {
  String binaryName();

  TurbineTyKind kind();

  List<String> typeParams();

  /** Direct supertypes, expressed in terms of this class's type parameters. */
  List<TypeRef> supertypes();

  List<Member> fields();

  /** Methods and constructors (named {@code <init>}). */
  List<Member> methods();

  /** Binary name of a directly declared member type, or null. */
  String memberType(String simpleName);

  Target target();

  /** Annotated {@code @Deprecated}. */
  default boolean deprecated() {
    return false;
  }
}
