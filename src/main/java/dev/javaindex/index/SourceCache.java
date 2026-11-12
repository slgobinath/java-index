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

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Content-addressed summary cache shared by every worktree of a project. A file with the same bytes
 * in several worktrees is parsed once and the resulting {@link FileSummary} instance is shared.
 * Entries are weakly held: a summary lives exactly as long as some worktree index references it.
 */
public final class SourceCache {
  private final ConcurrentHashMap<String, Ref> byHash = new ConcurrentHashMap<>();
  private final ReferenceQueue<FileSummary> queue = new ReferenceQueue<>();
  private final java.util.concurrent.atomic.AtomicLong parsed = new java.util.concurrent.atomic.AtomicLong();

  private static final class Ref extends WeakReference<FileSummary> {
    final String hash;

    Ref(FileSummary s, ReferenceQueue<FileSummary> q) {
      super(s, q);
      this.hash = s.hash;
    }
  }

  public FileSummary summarize(String hash, String path, Supplier<String> text) {
    expunge();
    Ref ref = byHash.get(hash);
    FileSummary s = ref == null ? null : ref.get();
    if (s != null) {
      return s;
    }
    FileSummary fresh = Summarizer.summarize(hash, path, text.get());
    parsed.incrementAndGet();
    // Another thread may have raced us; keep whichever instance landed first.
    while (true) {
      Ref existing = byHash.putIfAbsent(hash, new Ref(fresh, queue));
      if (existing == null) {
        return fresh;
      }
      FileSummary other = existing.get();
      if (other != null) {
        return other;
      }
      byHash.remove(hash, existing);
    }
  }

  /** The summary held for {@code hash}, or null. */
  public FileSummary get(String hash) {
    Ref ref = byHash.get(hash);
    return ref == null ? null : ref.get();
  }

  /** Holds a summary read from disk; returns the instance held for its hash (it, or an earlier one). */
  public FileSummary adopt(FileSummary s) {
    expunge();
    while (true) {
      Ref existing = byHash.putIfAbsent(s.hash, new Ref(s, queue));
      if (existing == null) {
        return s;
      }
      FileSummary other = existing.get();
      if (other != null) {
        return other;
      }
      byHash.remove(s.hash, existing);
    }
  }

  /** How many files were parsed so far (summaries not found by hash). */
  public long parsed() {
    return parsed.get();
  }

  public int size() {
    expunge();
    return byHash.size();
  }

  private void expunge() {
    Object r;
    while ((r = queue.poll()) != null) {
      Ref ref = (Ref) r;
      byHash.remove(ref.hash, ref);
    }
  }
}
