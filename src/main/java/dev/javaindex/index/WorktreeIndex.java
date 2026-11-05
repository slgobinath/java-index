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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * One worktree's view of the project's sources: the shared base {@link Snapshot} overlaid with the
 * files this worktree changed, added or deleted (including unsaved editor buffers). Files whose
 * content matches the base are not stored here at all.
 */
public final class WorktreeIndex {
  private final Snapshot base;

  /** rel path -> summary, or empty when the file was deleted in this worktree. */
  private final Map<String, Optional<FileSummary>> delta = new HashMap<>();

  private final Map<String, TypeHandle> deltaTypes = new HashMap<>();
  private final Set<String> deltaPackages = new HashSet<>();
  /** Bumped on every effective change, so derived data (e.g. subtype maps) can be cached. */
  private long version;

  public WorktreeIndex(Snapshot base) {
    this.base = base;
  }

  public Snapshot base() {
    return base;
  }

  /** Records the current content of {@code rel}; {@code summary == null} means deleted. */
  public synchronized void put(String rel, FileSummary summary) {
    FileSummary before = file(rel);
    if (before == summary) {
      return;
    }
    version++;
    Optional<FileSummary> old = delta.remove(rel);
    if (old != null && old.isPresent()) {
      for (TypeInfo t : old.get().allTypes()) {
        deltaTypes.remove(t.binaryName);
      }
    }
    FileSummary inBase = base.files.get(rel);
    boolean sameAsBase =
        summary == null ? inBase == null : inBase != null && inBase.hash.equals(summary.hash);
    if (sameAsBase) {
      return;
    }
    delta.put(rel, Optional.ofNullable(summary));
    if (summary != null) {
      Snapshot.addPackages(deltaPackages, summary.pkg);
      for (TypeInfo t : summary.allTypes()) {
        deltaTypes.putIfAbsent(t.binaryName, new TypeHandle(rel, summary, t));
      }
    }
  }

  public synchronized long version() {
    return version;
  }

  public synchronized FileSummary file(String rel) {
    Optional<FileSummary> d = delta.get(rel);
    if (d != null) {
      return d.orElse(null);
    }
    return base.files.get(rel);
  }

  public synchronized TypeHandle type(String binaryName) {
    TypeHandle h = deltaTypes.get(binaryName);
    if (h != null) {
      return h;
    }
    h = base.types.get(binaryName);
    return h != null && !delta.containsKey(h.rel()) ? h : null;
  }

  public synchronized boolean hasPackage(String pkg) {
    return deltaPackages.contains(pkg) || base.packages.contains(pkg);
  }

  public synchronized boolean contains(String rel) {
    Optional<FileSummary> d = delta.get(rel);
    return d != null ? d.isPresent() : base.files.containsKey(rel);
  }

  /** Visits every type visible in this worktree. */
  public void forEachType(Consumer<TypeHandle> visitor) {
    List<TypeHandle> own;
    Set<String> shadowed;
    synchronized (this) {
      own = new ArrayList<>(deltaTypes.values());
      shadowed = new HashSet<>(delta.keySet());
    }
    for (TypeHandle h : base.types.values()) {
      if (!shadowed.contains(h.rel())) {
        visitor.accept(h);
      }
    }
    own.forEach(visitor);
  }

  /** Every relative path known to this worktree. */
  public synchronized Set<String> paths() {
    Set<String> out = new HashSet<>(base.files.keySet());
    delta.forEach(
        (rel, s) -> {
          if (s.isPresent()) {
            out.add(rel);
          } else {
            out.remove(rel);
          }
        });
    return out;
  }

  /** Number of files that differ from the shared base. */
  public synchronized int deltaSize() {
    return delta.size();
  }
}
