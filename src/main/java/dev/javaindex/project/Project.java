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

package dev.javaindex.project;

import dev.javaindex.classpath.ClasspathProvider;
import dev.javaindex.classpath.Dependencies;
import dev.javaindex.classpath.SourceLocator;
import dev.javaindex.index.Snapshot;
import dev.javaindex.index.SourceCache;
import dev.javaindex.model.Hierarchy;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * State shared by all worktrees of one repository: the content-addressed summary cache, the base
 * snapshot every worktree diffs against, dependency symbols (one instance per distinct classpath)
 * and materialized library sources.
 */
public final class Project {
  private static final Logger LOG = Logger.getLogger(Project.class.getName());

  private final SourceCache sources = new SourceCache();
  private final Map<Path, Worktree> worktrees = new ConcurrentHashMap<>();
  private final Map<List<Path>, Dependencies> dependencies = new ConcurrentHashMap<>();
  private final ClasspathProvider classpaths;
  private final SourceLocator locator;
  private final Hierarchy hierarchy = new Hierarchy();

  /** Serializes worktree scans so a second worktree can reuse the first one's summaries. */
  final ExecutorService scanner = Executors.newSingleThreadExecutor(daemon("scan"));

  final ExecutorService background = Executors.newCachedThreadPool(daemon("bg"));
  private volatile Snapshot base;

  public Project(Path cacheDir) {
    this.classpaths = new ClasspathProvider(cacheDir, background);
    this.locator = new SourceLocator(cacheDir);
  }

  private static java.util.concurrent.ThreadFactory daemon(String name) {
    return r -> {
      Thread t = new Thread(r, "java-index-" + name);
      t.setDaemon(true);
      return t;
    };
  }

  public SourceCache sources() {
    return sources;
  }

  /** Pool for requests and background work; threads are reclaimed when idle. */
  public ExecutorService background() {
    return background;
  }

  /** Parsed config files, by content and name (the name carries the profile); shared. */
  private final Map<String, dev.javaindex.config.ConfigFile> configFiles = new ConcurrentHashMap<>();

  public dev.javaindex.config.ConfigFile configFile(String rel, String text) {
    String name = rel.substring(rel.lastIndexOf('/') + 1);
    String key = dev.javaindex.index.Summarizer.hash(text) + ":" + name;
    if (configFiles.size() > 500) {
      configFiles.clear();
    }
    return configFiles.computeIfAbsent(key, k -> dev.javaindex.config.ConfigFile.parse(rel, text));
  }

  public Hierarchy hierarchy() {
    return hierarchy;
  }

  public SourceLocator locator() {
    return locator;
  }

  ClasspathProvider classpaths() {
    return classpaths;
  }

  /** Returns the worktree rooted at {@code root}, creating and indexing it on first use. */
  public Worktree open(Path root, List<String> configuredClasspath) throws IOException {
    Path real = root.toRealPath();
    Worktree w =
        worktrees.computeIfAbsent(real, r -> new Worktree(this, r, configuredClasspath));
    w.retain();
    return w;
  }

  public void release(Worktree w) {
    if (w.release()) {
      worktrees.remove(w.root(), w);
      LOG.info("closed worktree " + w.root());
    }
  }

  public List<Worktree> worktrees() {
    return new ArrayList<>(worktrees.values());
  }

  /** The base snapshot, adopting {@code candidate} if no base exists yet. */
  synchronized Snapshot baseOr(Snapshot candidate) {
    if (base == null) {
      base = candidate;
    }
    return base;
  }

  Snapshot base() {
    return base;
  }

  Dependencies dependencies(List<Path> jars) {
    if (jars.isEmpty()) {
      return Dependencies.EMPTY;
    }
    return dependencies.computeIfAbsent(List.copyOf(jars), Dependencies::of);
  }

  /** Drops dependency sets no live worktree uses any more. */
  void pruneDependencies() {
    List<Dependencies> live = new ArrayList<>();
    for (Worktree w : worktrees.values()) {
      live.add(w.dependencies());
    }
    dependencies.values().removeIf(d -> live.stream().noneMatch(l -> l == d));
  }

  public String stats() {
    StringBuilder sb = new StringBuilder();
    Snapshot b = base;
    sb.append("base files: ").append(b == null ? 0 : b.fileCount());
    sb.append(", unique summaries: ").append(sources.size());
    sb.append(", classpaths: ").append(dependencies.size());
    for (Worktree w : worktrees.values()) {
      sb.append("\n  ").append(w.root()).append(": ").append(w.describe());
    }
    return sb.toString();
  }
}
