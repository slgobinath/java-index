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
import dev.javaindex.config.ConfigFile;
import dev.javaindex.config.ConfigIndex;
import dev.javaindex.config.ConfigKeys;
import dev.javaindex.index.FileSummary;
import dev.javaindex.index.Lines;
import dev.javaindex.index.Snapshot;
import dev.javaindex.index.Summarizer;
import dev.javaindex.index.SummaryStore;
import dev.javaindex.index.TypeHandle;
import dev.javaindex.index.WorktreeIndex;
import dev.javaindex.model.Universe;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One checkout of the project. Holds only what is specific to it: the files whose content differs
 * from the project's shared base, unsaved editor buffers, and which classpath it uses.
 */
public final class Worktree {
  private static final Logger LOG = Logger.getLogger(Worktree.class.getName());
  /** Stored summaries of a worktree not opened for this long are deleted. */
  private static final java.time.Duration SUMMARY_MAX_AGE = java.time.Duration.ofDays(14);
  private static final Set<String> BUILD_DIRS = Set.of("target", "build", "out", "bin");

  private final Project project;
  private final Path root;
  private final List<String> configuredClasspath;
  private final AtomicInteger refs = new AtomicInteger();
  private final CompletableFuture<Void> ready;
  private final Map<String, long[]> stats = new ConcurrentHashMap<>();
  private final Map<String, Buffer> buffers = new ConcurrentHashMap<>();
  private volatile WorktreeIndex index;
  private volatile Dependencies deps = Dependencies.EMPTY;
  private volatile Subtypes subtypes;

  private record Subtypes(long version, Map<String, List<TypeHandle>> map) {}
  private volatile CompletableFuture<Void> classpathReady = CompletableFuture.completedFuture(null);

  /** An open editor buffer. Summaries are refreshed lazily, on the next request. */
  private static final class Buffer {
    String text;
    boolean dirty = true;
    FileSummary current;
    FileSummary lastGood;

    Buffer(String text) {
      this.text = text;
    }
  }

  Worktree(Project project, Path root, List<String> configuredClasspath) {
    this.project = project;
    this.root = root;
    this.configuredClasspath = configuredClasspath;
    this.ready = CompletableFuture.runAsync(this::scan, project.scanner);
    refreshClasspath();
  }

  public Path root() {
    return root;
  }

  void retain() {
    refs.incrementAndGet();
  }

  boolean release() {
    return refs.decrementAndGet() <= 0;
  }

  Dependencies dependencies() {
    return deps;
  }

  public CompletableFuture<Void> ready() {
    return ready;
  }

  /** The worktree-relative path of {@code file}, or null if it lies outside this worktree. */
  public String rel(Path file) {
    Path p = file.toAbsolutePath().normalize();
    if (!p.startsWith(root)) {
      p = canonical(p);
      if (p == null || !p.startsWith(root)) {
        return null;
      }
    }
    return root.relativize(p).toString().replace('\\', '/');
  }

  /**
   * The real path of {@code p}, which may no longer exist (a deleted file or directory): resolve
   * the nearest existing ancestor and re-append the rest. Needed when the editor's path goes
   * through a symlink (e.g. /var -> /private/var on macOS).
   */
  private static Path canonical(Path p) {
    java.util.ArrayDeque<String> missing = new java.util.ArrayDeque<>();
    Path existing = p;
    while (existing != null && !Files.exists(existing)) {
      missing.push(existing.getFileName().toString());
      existing = existing.getParent();
    }
    if (existing == null) {
      return null;
    }
    try {
      Path real = existing.toRealPath();
      while (!missing.isEmpty()) {
        real = real.resolve(missing.pop());
      }
      return real;
    } catch (IOException e) {
      return null;
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Indexing

  private void scan() {
    long start = System.nanoTime();
    Set<String> configs = new java.util.HashSet<>();
    Map<String, Path> files = walk(root, configs);
    configPaths.addAll(configs);
    configVersion.incrementAndGet();
    // Summaries saved by an earlier daemon: files whose content hasn't changed aren't parsed again.
    Path stored = project.summaryFile(root);
    SummaryStore.Stored saved = SummaryStore.load(stored);
    long parsedBefore = project.sources().parsed();
    Map<String, FileSummary> summaries = new ConcurrentHashMap<>();
    files.entrySet().parallelStream()
        .forEach(
            e -> {
              FileSummary s = readAndSummarize(e.getKey(), e.getValue(), saved);
              if (s != null) {
                summaries.put(e.getKey(), s);
              }
            });
    Snapshot base;
    synchronized (project) {
      base = project.base();
      if (base == null) {
        base = project.baseOr(Snapshot.of(summaries));
      }
    }
    WorktreeIndex idx = new WorktreeIndex(base);
    summaries.forEach(idx::put);
    for (String rel : Set.copyOf(idx.paths())) {
      if (!summaries.containsKey(rel)) {
        idx.put(rel, null);
      }
    }
    index = idx;
    buffers.values().forEach(b -> b.dirty = true);
    long parsed = project.sources().parsed() - parsedBefore;
    LOG.info(
        String.format(
            "indexed %s: %d files (%d parsed, %d differ from shared base) in %d ms",
            root,
            summaries.size(),
            parsed,
            idx.deltaSize(),
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)));
    // Rewrite when files were parsed, or when the stored set holds summaries no file has any more.
    long distinct = summaries.values().stream().map(f -> f.hash).distinct().count();
    boolean rewrite = parsed > 0 || saved.size() > distinct;
    List<FileSummary> current = rewrite ? List.copyOf(summaries.values()) : List.of();
    project.background().execute(() -> {
      try {
        if (rewrite) {
          SummaryStore.save(stored, current);
        } else {
          SummaryStore.touch(stored);
        }
        // Agents create and drop worktrees freely; their stored summaries shouldn't pile up.
        SummaryStore.prune(stored.getParent(), SUMMARY_MAX_AGE);
      } catch (IOException e) {
        LOG.log(Level.WARNING, "could not save summaries to " + stored, e);
      }
    });
    // Return parse garbage to the OS promptly; the daemon is long-lived and mostly idle.
    System.gc();
  }

  private FileSummary readAndSummarize(String rel, Path file) {
    return readAndSummarize(rel, file, null);
  }

  private FileSummary readAndSummarize(String rel, Path file, SummaryStore.Stored saved) {
    try {
      BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
      byte[] bytes = Files.readAllBytes(file);
      stats.put(rel, new long[] {attrs.lastModifiedTime().toMillis(), attrs.size()});
      String hash = Summarizer.hash(bytes);
      if (saved != null && project.sources().get(hash) == null) {
        FileSummary stored =
            saved.get(hash, () -> Lines.of(new String(bytes, StandardCharsets.UTF_8)));
        if (stored != null) {
          return project.sources().adopt(stored);
        }
      }
      return project
          .sources()
          .summarize(hash, file.toString(), () -> new String(bytes, StandardCharsets.UTF_8));
    } catch (IOException e) {
      stats.remove(rel);
      return null;
    }
  }

  private Map<String, Path> walk() {
    return walk(root, null);
  }

  private Map<String, Path> walk(Path start) {
    return walk(start, null);
  }

  /**
   * Java files under {@code start}, skipping what the index never contains; config files
   * (application.yml and friends) are collected into {@code configs} when given.
   */
  private Map<String, Path> walk(Path start, Set<String> configs) {
    Map<String, Path> out = new HashMap<>();
    try {
      Files.walkFileTree(
          start,
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
              if (dir.equals(root)) {
                return FileVisitResult.CONTINUE;
              }
              return skipDirectory(dir) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
              String name = file.getFileName().toString();
              if (name.endsWith(".java") && attrs.isRegularFile()) {
                out.put(root.relativize(file).toString().replace('\\', '/'), file);
              } else if (configs != null && attrs.isRegularFile() && ConfigKeys.isConfigFile(name)) {
                configs.add(root.relativize(file).toString().replace('\\', '/'));
              }
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
              return FileVisitResult.CONTINUE;
            }
          });
    } catch (IOException e) {
      LOG.log(Level.WARNING, "walk failed for " + root, e);
    }
    return out;
  }

  private static boolean skipDirectory(Path dir) {
    String name = dir.getFileName().toString();
    Path parent = dir.getParent();
    String parentName = parent == null || parent.getFileName() == null ? "" : parent.getFileName().toString();
    return name.startsWith(".")
        || name.equals("node_modules")
        // Generated sources are useful; the rest of a build directory is not.
        || (BUILD_DIRS.contains(parentName) && !name.startsWith("generated"))
        // A nested worktree or submodule is a different checkout.
        || Files.isRegularFile(dir.resolve(".git"));
  }

  /** True if {@code rel} lies in a directory the index skips (build output, hidden, ...). */
  private boolean excluded(String rel) {
    Path p = root;
    String[] parts = rel.split("/");
    for (int i = 0; i < parts.length - 1; i++) {
      p = p.resolve(parts[i]);
      if (skipDirectory(p)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Queues a change reported by the editor. Changes to one worktree are applied one at a time, in
   * arrival order, so a slow read of an older version can't overwrite a newer one.
   */
  public void enqueueChange(String rel) {
    synchronized (changeLock) {
      changes = changes.thenRunAsync(() -> safely(() -> fileChanged(rel)), project.background());
    }
  }

  private final Object changeLock = new Object();
  private CompletableFuture<Void> changes = CompletableFuture.completedFuture(null);

  /** Completes once every change queued so far has been applied. */
  public CompletableFuture<Void> changesApplied() {
    synchronized (changeLock) {
      return changes;
    }
  }

  private static void safely(Runnable r) {
    try {
      r.run();
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "applying a file change failed", e);
    }
  }

  /** Re-stats every file and re-indexes the ones that changed (fallback when not watching). */
  public synchronized void rescan() {
    awaitReady();
    Set<String> configs = new java.util.HashSet<>();
    Map<String, Path> files = walk(root, configs);
    boolean configChanged = !configs.equals(configPaths);
    for (String rel : configs) {
      try {
        BasicFileAttributes a = Files.readAttributes(root.resolve(rel), BasicFileAttributes.class);
        long[] now = {a.lastModifiedTime().toMillis(), a.size()};
        long[] old = configStats.put(rel, now);
        configChanged |= old == null || old[0] != now[0] || old[1] != now[1];
      } catch (IOException ex) {
        configChanged = true;
      }
    }
    if (configChanged) {
      configPaths.retainAll(configs);
      configPaths.addAll(configs);
      configVersion.incrementAndGet();
    }
    for (Map.Entry<String, Path> e : files.entrySet()) {
      long[] old = stats.get(e.getKey());
      try {
        BasicFileAttributes a = Files.readAttributes(e.getValue(), BasicFileAttributes.class);
        if (old == null || old[0] != a.lastModifiedTime().toMillis() || old[1] != a.size()) {
          fileChanged(e.getKey());
        }
      } catch (IOException ex) {
        fileChanged(e.getKey());
      }
    }
    for (String rel : Set.copyOf(stats.keySet())) {
      if (!files.containsKey(rel)) {
        fileChanged(rel);
      }
    }
  }

  /**
   * Something changed on disk: a Java file, a build file, or a directory (created, moved or
   * deleted, which editors often report as a single event for the directory).
   */
  public synchronized void fileChanged(String rel) {
    if (ClasspathProvider.isBuildFile(rel)) {
      refreshClasspath();
      return;
    }
    awaitReady();
    if (rel.endsWith(".java")) {
      updateFile(rel);
      return;
    }
    if (ConfigKeys.isConfigFile(rel)) {
      if (!excluded(rel) && Files.isRegularFile(root.resolve(rel))) {
        configPaths.add(rel);
      } else {
        configPaths.remove(rel);
      }
      configVersion.incrementAndGet();
      return;
    }
    if (excluded(rel + "/x")) {
      return;
    }
    // Reconcile everything indexed under this path with what is on disk now.
    String prefix = rel.isEmpty() ? "" : rel + "/";
    Path dir = root.resolve(rel);
    Set<String> configsNow = new java.util.HashSet<>();
    Map<String, Path> present = Files.isDirectory(dir) ? walk(dir, configsNow) : Map.of();
    configPaths.removeIf(c -> c.startsWith(prefix) && !configsNow.contains(c));
    configPaths.addAll(configsNow);
    configVersion.incrementAndGet();
    for (String known : index.paths()) {
      if (known.startsWith(prefix) && !present.containsKey(known)) {
        updateFile(known);
      }
    }
    for (Map.Entry<String, Path> e : present.entrySet()) {
      long[] old = stats.get(e.getKey());
      try {
        BasicFileAttributes a = Files.readAttributes(e.getValue(), BasicFileAttributes.class);
        if (old == null || old[0] != a.lastModifiedTime().toMillis() || old[1] != a.size()) {
          updateFile(e.getKey());
        }
      } catch (IOException ex) {
        updateFile(e.getKey());
      }
    }
  }

  private void updateFile(String rel) {
    if (excluded(rel)) {
      return;
    }
    if (buffers.containsKey(rel)) {
      return; // the editor's buffer is authoritative while open
    }
    Path file = root.resolve(rel);
    index.put(rel, Files.isRegularFile(file) ? readAndSummarize(rel, file) : null);
    if (!Files.isRegularFile(file)) {
      stats.remove(rel);
    }
  }

  private void refreshClasspath() {
    classpathReady =
        project
            .classpaths()
            .classpath(root, configuredClasspath)
            .thenAcceptAsync(
                jars -> {
                  deps = project.dependencies(jars);
                  project.pruneDependencies();
                  LOG.info(root + ": classpath has " + deps.jars().size() + " jars");
                },
                project.background)
            .exceptionally(
                e -> {
                  LOG.log(Level.WARNING, "classpath resolution failed for " + root, e);
                  return null;
                });
  }

  private void awaitReady() {
    ready.join();
  }

  /** Waits (briefly) for the classpath, so the first request after startup can see dependencies. */
  public void awaitClasspath(long millis) {
    try {
      classpathReady.get(millis, TimeUnit.MILLISECONDS);
    } catch (Exception e) {
      // answer with what we have
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Editor buffers

  public void openBuffer(String rel, String text) {
    buffers.put(rel, new Buffer(text));
    if (ConfigKeys.isConfigFile(rel)) {
      configVersion.incrementAndGet();
    }
  }

  public void changeBuffer(String rel, java.util.function.UnaryOperator<String> edit) {
    Buffer b = buffers.get(rel);
    if (b != null) {
      synchronized (b) {
        b.text = edit.apply(b.text);
        b.dirty = true;
      }
      if (ConfigKeys.isConfigFile(rel)) {
        configVersion.incrementAndGet();
      }
    }
  }

  public void closeBuffer(String rel) {
    if (buffers.remove(rel) == null) {
      return;
    }
    if (ConfigKeys.isConfigFile(rel)) {
      configVersion.incrementAndGet();
    } else if (rel.endsWith(".java") && ready.isDone()) {
      Path file = root.resolve(rel);
      index.put(rel, Files.isRegularFile(file) ? readAndSummarize(rel, file) : null);
    }
  }

  /** Pushes edited buffers into the index so cross-file navigation sees unsaved changes. */
  private void flushBuffers() {
    for (Map.Entry<String, Buffer> e : buffers.entrySet()) {
      if (!e.getKey().endsWith(".java")) {
        continue;
      }
      Buffer b = e.getValue();
      synchronized (b) {
        if (!b.dirty) {
          continue;
        }
        FileSummary s =
            project
                .sources()
                .summarize(Summarizer.hash(b.text), root.resolve(e.getKey()).toString(), () -> b.text);
        b.current = s;
        if (s.parsed) {
          b.lastGood = s;
          index.put(e.getKey(), s);
        }
        b.dirty = false;
      }
    }
  }

  /** Current text: the editor buffer if open, else the file on disk. */
  public String text(String rel) {
    Buffer b = buffers.get(rel);
    if (b != null) {
      synchronized (b) {
        return b.text;
      }
    }
    try {
      return Files.readString(root.resolve(rel));
    } catch (IOException e) {
      return null;
    }
  }

  /**
   * The summary to resolve against: for a buffer that currently fails to parse, its last parseable
   * version (declarations rarely move much while typing).
   */
  public FileSummary summary(String rel) {
    awaitReady();
    flushBuffers();
    Buffer b = buffers.get(rel);
    if (b != null) {
      synchronized (b) {
        return b.current != null && b.current.parsed ? b.current : b.lastGood != null ? b.lastGood : b.current;
      }
    }
    FileSummary s = index.file(rel);
    if (s == null) {
      String text = text(rel);
      if (text != null) {
        s = project.sources().summarize(Summarizer.hash(text), rel, () -> text);
      }
    }
    return s;
  }

  public Universe universe() {
    awaitReady();
    flushBuffers();
    return new Universe(index, root, deps, Dependencies.jdk());
  }

  // ---------------------------------------------------------------------------------------------
  // Configuration (application.properties / .yml)

  private final Set<String> configPaths = ConcurrentHashMap.newKeySet();
  private final Map<String, long[]> configStats = new ConcurrentHashMap<>();
  private final java.util.concurrent.atomic.AtomicLong configVersion = new java.util.concurrent.atomic.AtomicLong();
  private volatile CachedConfig cachedConfig;

  private record CachedConfig(long javaVersion, long configVersion, Dependencies deps, ConfigIndex index) {}

  /** The worktree's config files (relative paths). */
  public Set<String> configPaths() {
    return Set.copyOf(configPaths);
  }

  /** Config keys defined in this worktree's files and the Java that binds them (cached). */
  public ConfigIndex configIndex() {
    WorktreeIndex idx = index();
    long javaVersion = idx.version();
    long cfgVersion = configVersion.get();
    Dependencies d = deps;
    CachedConfig cached = cachedConfig;
    if (cached != null && cached.javaVersion == javaVersion && cached.configVersion == cfgVersion && cached.deps == d) {
      return cached.index;
    }
    Map<String, ConfigFile> files = new HashMap<>();
    for (String rel : configPaths) {
      String text = text(rel);
      if (text != null) {
        files.put(rel, project.configFile(rel, text));
      }
    }
    Map<String, FileSummary> java = new HashMap<>();
    for (String rel : idx.paths()) {
      FileSummary s = idx.file(rel);
      if (s != null && !s.configRefs.isEmpty()) {
        java.put(rel, s);
      }
    }
    ConfigIndex built = ConfigIndex.build(universe(), files, java, d.config());
    cachedConfig = new CachedConfig(javaVersion, cfgVersion, d, built);
    return built;
  }

  /** Supertype binary name -> direct subtypes declared in this worktree's sources. */
  public Map<String, List<TypeHandle>> subtypes() {
    WorktreeIndex idx = index();
    Subtypes cached = subtypes;
    long version = idx.version();
    if (cached == null || cached.version != version) {
      cached = new Subtypes(version, project.hierarchy().subtypes(idx, universe()));
      subtypes = cached;
    }
    return cached.map;
  }

  public WorktreeIndex index() {
    awaitReady();
    flushBuffers();
    return index;
  }

  String describe() {
    WorktreeIndex idx = index;
    return idx == null
        ? "indexing"
        : idx.deltaSize() + " files differ from base, " + buffers.size() + " open buffers, "
            + deps.jars().size() + " jars";
  }
}
