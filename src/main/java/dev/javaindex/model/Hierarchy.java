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

import dev.javaindex.index.FileSummary;
import dev.javaindex.index.FileSummary.TypeInfo;
import dev.javaindex.index.TypeHandle;
import dev.javaindex.index.WorktreeIndex;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Subtype lookups for implementation and type-hierarchy requests. Resolving a file's supertypes is
 * the expensive part, so it is memoized per summary instance: identical files across worktrees
 * share one summary and therefore one result. Each worktree only builds the reverse map.
 */
public final class Hierarchy {
  /** Summary -> (type binary name -> direct supertype binary names). Weak: lives with the summary. */
  private final Map<FileSummary, Map<String, List<String>>> direct =
      Collections.synchronizedMap(new WeakHashMap<>());

  /** Reverse map for one worktree index state: supertype -> direct subtypes. */
  public Map<String, List<TypeHandle>> subtypes(WorktreeIndex index, Universe u) {
    Map<FileSummary, List<TypeHandle>> byFile = new LinkedHashMap<>();
    index.forEachType(h -> byFile.computeIfAbsent(h.file(), f -> new ArrayList<>()).add(h));
    Map<String, List<TypeHandle>> out = new HashMap<>();
    byFile.forEach(
        (file, handles) -> {
          Map<String, List<String>> supers = supertypes(file, handles.get(0).rel(), u);
          for (TypeHandle h : handles) {
            for (String sup : supers.getOrDefault(h.type().binaryName, List.of())) {
              out.computeIfAbsent(sup, k -> new ArrayList<>()).add(h);
            }
          }
        });
    return out;
  }

  private Map<String, List<String>> supertypes(FileSummary file, String rel, Universe u) {
    Map<String, List<String>> cached = direct.get(file);
    if (cached != null) {
      return cached;
    }
    Map<String, List<String>> result = new HashMap<>();
    for (TypeInfo t : file.allTypes()) {
      List<String> supers = new ArrayList<>();
      for (TypeRef s : u.external(t, file, u.path(rel)).supertypes()) {
        if (s.cls() != null) {
          supers.add(s.cls());
        }
      }
      result.put(t.binaryName, List.copyOf(supers));
    }
    direct.put(file, result);
    return result;
  }
}
