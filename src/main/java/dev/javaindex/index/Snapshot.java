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

import dev.javaindex.index.FileSummary.TypeInfo;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * An immutable index over a set of files (relative path to summary). The first worktree indexed in a
 * project becomes the shared base snapshot; every worktree then only stores its differences from it.
 */
public final class Snapshot {
  final Map<String, FileSummary> files;
  final Map<String, TypeHandle> types;
  final Set<String> packages;

  private Snapshot(
      Map<String, FileSummary> files, Map<String, TypeHandle> types, Set<String> packages) {
    this.files = files;
    this.types = types;
    this.packages = packages;
  }

  public static final Snapshot EMPTY = new Snapshot(Map.of(), Map.of(), Set.of());

  public static Snapshot of(Map<String, FileSummary> files) {
    Map<String, TypeHandle> types = new HashMap<>();
    Set<String> packages = new HashSet<>();
    files.forEach(
        (rel, summary) -> {
          addPackages(packages, summary.pkg);
          for (TypeInfo t : summary.allTypes()) {
            types.putIfAbsent(t.binaryName, new TypeHandle(rel, summary, t));
          }
        });
    return new Snapshot(Map.copyOf(files), Map.copyOf(types), Set.copyOf(packages));
  }

  static void addPackages(Set<String> packages, String pkg) {
    String p = pkg;
    while (!p.isEmpty()) {
      if (!packages.add(p)) {
        return;
      }
      int slash = p.lastIndexOf('/');
      p = slash < 0 ? "" : p.substring(0, slash);
    }
  }

  public FileSummary file(String rel) {
    return files.get(rel);
  }

  public int fileCount() {
    return files.size();
  }
}
