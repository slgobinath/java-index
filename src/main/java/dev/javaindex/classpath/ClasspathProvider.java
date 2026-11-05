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

import dev.javaindex.index.Summarizer;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Works out a worktree's dependency jars. In order of preference: an explicit list from the client's
 * initializationOptions, a {@code .java-index/classpath} file, or Maven/Gradle. Build-tool
 * results are keyed by the content of the build files, so worktrees with identical build files
 * share one computation, and results are cached on disk across daemon restarts.
 */
public final class ClasspathProvider {
  private static final Logger LOG = Logger.getLogger(ClasspathProvider.class.getName());
  private static final String MARKER = "JINDEX_CLASSPATH=";

  private static final String GRADLE_INIT =
      """
      allprojects {
        tasks.register('javaIndexClasspath') {
          doLast {
            ['compileClasspath', 'testCompileClasspath'].each { name ->
              def conf = project.configurations.findByName(name)
              if (conf != null && conf.canBeResolved) {
                try {
                  conf.resolvedConfiguration.lenientConfiguration.files.each {
                    println '%s' + it.absolutePath
                  }
                } catch (Exception ignored) {}
              }
            }
          }
        }
      }
      """
          .formatted(MARKER);

  private final Path cacheDir;
  private final ExecutorService executor;
  private final Map<String, CompletableFuture<List<Path>>> inflight = new ConcurrentHashMap<>();

  public ClasspathProvider(Path cacheDir, ExecutorService executor) {
    this.cacheDir = cacheDir;
    this.executor = executor;
  }

  public CompletableFuture<List<Path>> classpath(Path root, List<String> configured) {
    if (configured != null && !configured.isEmpty()) {
      return CompletableFuture.completedFuture(expand(root, configured));
    }
    Path file = root.resolve(".java-index").resolve("classpath");
    if (Files.isRegularFile(file)) {
      try {
        List<String> entries = new ArrayList<>();
        for (String line : Files.readAllLines(file)) {
          for (String part : line.split(File.pathSeparator)) {
            if (!part.isBlank() && !part.strip().startsWith("#")) {
              entries.add(part.strip());
            }
          }
        }
        return CompletableFuture.completedFuture(expand(root, entries));
      } catch (IOException e) {
        LOG.log(Level.WARNING, "cannot read " + file, e);
      }
    }
    Tool tool = detect(root);
    if (tool == null) {
      return CompletableFuture.completedFuture(List.of());
    }
    // v2: per-module classpaths read from Maven's output (v1 kept only the last module's)
    String key = tool.name() + "-v2-" + buildFilesHash(root, tool);
    Path cached = cacheDir.resolve("classpath").resolve(key + ".txt");
    if (Files.isRegularFile(cached)) {
      try {
        return CompletableFuture.completedFuture(
            Files.readAllLines(cached).stream().filter(l -> !l.isBlank()).map(Path::of).toList());
      } catch (IOException e) {
        // recompute
      }
    }
    return inflight.computeIfAbsent(
        key,
        k ->
            CompletableFuture.supplyAsync(
                () -> {
                  List<Path> jars = tool == Tool.MAVEN ? maven(root) : gradle(root);
                  try {
                    Files.createDirectories(cached.getParent());
                    Files.write(cached, jars.stream().map(Path::toString).toList());
                  } catch (IOException e) {
                    LOG.log(Level.WARNING, "cannot cache classpath", e);
                  }
                  inflight.remove(k);
                  return jars;
                },
                executor));
  }

  /** True if {@code rel} is a file whose change can alter the classpath. */
  public static boolean isBuildFile(String rel) {
    String name = rel.substring(rel.lastIndexOf('/') + 1);
    return name.equals("pom.xml")
        || name.startsWith("build.gradle")
        || name.startsWith("settings.gradle")
        || name.equals("libs.versions.toml")
        || rel.endsWith(".java-index/classpath");
  }

  private enum Tool {
    MAVEN,
    GRADLE
  }

  private static Tool detect(Path root) {
    if (Files.isRegularFile(root.resolve("pom.xml"))) {
      return Tool.MAVEN;
    }
    for (String f :
        List.of("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts")) {
      if (Files.isRegularFile(root.resolve(f))) {
        return Tool.GRADLE;
      }
    }
    return null;
  }

  private static List<Path> expand(Path root, List<String> entries) {
    Set<Path> out = new LinkedHashSet<>();
    for (String e : entries) {
      Path p = root.resolve(e.replaceFirst("^~", System.getProperty("user.home"))).normalize();
      if (Files.isDirectory(p)) {
        // A directory of jars (e.g. lib/).
        try (var files = Files.list(p)) {
          files.filter(f -> f.toString().endsWith(".jar")).sorted().forEach(out::add);
        } catch (IOException ex) {
          // ignore
        }
      } else {
        out.add(p);
      }
    }
    return List.copyOf(out);
  }

  private static String buildFilesHash(Path root, Tool tool) {
    TreeMap<String, String> files = new TreeMap<>();
    try {
      Files.walkFileTree(
          root,
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
              String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
              boolean skip =
                  !dir.equals(root)
                      && (name.startsWith(".")
                          || name.equals("target")
                          || name.equals("build")
                          || name.equals("node_modules")
                          || name.equals("src"));
              return skip ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                throws IOException {
              String rel = root.relativize(file).toString().replace('\\', '/');
              if (isBuildFile(rel)) {
                files.put(rel, Summarizer.hash(Files.readAllBytes(file)));
              }
              return FileVisitResult.CONTINUE;
            }
          });
    } catch (IOException e) {
      LOG.log(Level.FINE, "walk failed", e);
    }
    return Summarizer.hash((tool + files.toString()).getBytes(StandardCharsets.UTF_8));
  }

  private List<Path> maven(Path root) {
    try {
      Path wrapper = root.resolve("mvnw");
      String mvn = Files.isExecutable(wrapper) ? wrapper.toString() : "mvn";
      Set<Path> jars = new LinkedHashSet<>();
      // Each module prints "Dependencies classpath:" and then its classpath on the next line.
      // Read stdout rather than -Dmdep.outputFile: with appendOutput, each module of a
      // multi-module build overwrites the file, so only the last module's jars survived.
      boolean[] classpathNext = {false};
      run(
          root,
          List.of(mvn, "-B", "-fae", "dependency:build-classpath"),
          line -> {
            if (classpathNext[0]) {
              classpathNext[0] = false;
              for (String part : line.split(File.pathSeparator)) {
                if (part.endsWith(".jar")) {
                  jars.add(Path.of(part.strip()));
                }
              }
            } else if (line.endsWith("Dependencies classpath:")) {
              classpathNext[0] = true;
            }
          });
      return List.copyOf(jars);
    } catch (IOException e) {
      LOG.log(Level.WARNING, "maven classpath failed", e);
      return List.of();
    }
  }

  private List<Path> gradle(Path root) {
    try {
      Path init = Files.createTempFile("java-index", ".gradle");
      Files.writeString(init, GRADLE_INIT);
      try {
        Path wrapper = root.resolve("gradlew");
        String gradle = Files.isExecutable(wrapper) ? wrapper.toString() : "gradle";
        Set<Path> jars = new LinkedHashSet<>();
        run(
            root,
            List.of(
                gradle,
                "-q",
                "--console=plain",
                "--no-configuration-cache",
                "--init-script",
                init.toString(),
                "javaIndexClasspath"),
            line -> {
              if (line.startsWith(MARKER) && line.endsWith(".jar")) {
                jars.add(Path.of(line.substring(MARKER.length()).strip()));
              }
            });
        return List.copyOf(jars);
      } finally {
        Files.deleteIfExists(init);
      }
    } catch (IOException e) {
      LOG.log(Level.WARNING, "gradle classpath failed", e);
      return List.of();
    }
  }

  private static void run(Path dir, List<String> command, java.util.function.Consumer<String> lines)
      throws IOException {
    LOG.info("resolving classpath: " + String.join(" ", command) + " in " + dir);
    Process p =
        new ProcessBuilder(command)
            .directory(dir.toFile())
            .redirectErrorStream(true)
            .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")))
            .start();
    try (var reader = p.inputReader()) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (lines != null) {
          lines.accept(line);
        }
      }
    }
    try {
      if (!p.waitFor(15, TimeUnit.MINUTES)) {
        p.destroyForcibly();
      }
    } catch (InterruptedException e) {
      p.destroyForcibly();
      Thread.currentThread().interrupt();
    }
  }
}
