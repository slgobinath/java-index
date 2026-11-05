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

import dev.javaindex.index.FileSummary;
import dev.javaindex.index.FileSummary.FieldDecl;
import dev.javaindex.index.FileSummary.MethodDecl;
import dev.javaindex.index.FileSummary.TypeInfo;
import com.google.turbine.tree.Tree;
import dev.javaindex.index.Summarizer;
import dev.javaindex.model.ClassModel;
import dev.javaindex.model.Member;
import dev.javaindex.model.Target;
import dev.javaindex.model.Universe;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Maps a bytecode-only declaration to a source position: from the dependency's sources jar, the
 * JDK's {@code src.zip}, or, when neither exists, a header stub rendered from the class file. Files
 * are materialized once under the cache directory and shared by every worktree and session.
 */
public final class SourceLocator {
  private static final Logger LOG = Logger.getLogger(SourceLocator.class.getName());

  private final Path cacheDir;
  private final Map<Path, FileSummary> summaries = new ConcurrentHashMap<>();
  private volatile Map<String, String> jdkSrcIndex;

  public SourceLocator(Path cacheDir) {
    this.cacheDir = cacheDir;
  }

  public Path cacheDir() {
    return cacheDir;
  }

  public boolean owns(Path file) {
    return file.startsWith(cacheDir);
  }

  /** Summary of a materialized file (parsed once per path). */
  public FileSummary summary(Path file) {
    return summaries.computeIfAbsent(
        file,
        f -> {
          try {
            String text = Files.readString(f);
            return Summarizer.summarize(Summarizer.hash(text), f.toString(), text);
          } catch (IOException e) {
            return null;
          }
        });
  }

  public Target.Source locate(Target.Binary target, Universe universe) {
    String top = topLevel(target.classBinary());
    Path file = materialize(top, target.origin(), universe);
    if (file == null) {
      return null;
    }
    FileSummary summary = summary(file);
    if (summary == null) {
      return null;
    }
    TypeInfo type = null;
    for (TypeInfo t : summary.allTypes()) {
      if (t.binaryName.equals(target.classBinary())) {
        type = t;
      }
    }
    if (type == null) {
      return summary.types.isEmpty()
          ? new Target.Source(file, summary.lines, 0, 0)
          : source(file, summary, summary.types.get(0).span);
    }
    if (target.member() != null) {
      FileSummary.Span member = member(type, target);
      if (member != null) {
        return source(file, summary, member);
      }
    }
    return source(file, summary, type.span);
  }

  private static Target.Source source(Path file, FileSummary summary, FileSummary.Span span) {
    return new Target.Source(file, summary.lines, span.nameStart(), span.nameEnd());
  }

  private static FileSummary.Span member(TypeInfo type, Target.Binary target) {
    if (target.kind() == Member.Kind.FIELD) {
      for (FieldDecl f : type.fields) {
        if (f.name().equals(target.member())) {
          return f.span();
        }
      }
      return null;
    }
    FileSummary.Span byName = null;
    FileSummary.Span byArity = null;
    for (MethodDecl m : type.methods) {
      if (!m.name().equals(target.member())) {
        continue;
      }
      if (m.paramTypes().size() == target.arity()) {
        if (sameParams(m.paramTypes(), target.erasedParams())) {
          return m.span();
        }
        if (byArity == null) {
          byArity = m.span();
        }
      }
      if (byName == null) {
        byName = m.span();
      }
    }
    return byArity != null ? byArity : byName;
  }

  /** Compares source parameter types with a class file's erased ones ("?" = type variable). */
  private static boolean sameParams(List<Tree> source, List<String> erased) {
    if (erased.size() != source.size()) {
      return false;
    }
    for (int i = 0; i < erased.size(); i++) {
      String e = erased.get(i);
      String s = erasedSource(source.get(i));
      if (e.startsWith("?")) {
        // a type variable: any source type with the same array depth (its bound is unknown here)
        if (!e.substring(1).equals(s.replaceAll("^[^\\[]*", ""))) {
          return false;
        }
      } else if (!e.equals(s)) {
        return false;
      }
    }
    return true;
  }

  private static String erasedSource(Tree t) {
    return switch (t) {
      case Tree.ClassTy c -> c.name().value();
      case Tree.ArrTy a -> erasedSource(a.elem()) + "[]";
      case Tree.PrimTy p -> p.tykind().toString();
      default -> "?";
    };
  }

  private static String topLevel(String binaryName) {
    int slash = binaryName.lastIndexOf('/');
    int dollar = binaryName.indexOf('$', slash + 1);
    return dollar < 0 ? binaryName : binaryName.substring(0, dollar);
  }

  private Path materialize(String top, String origin, Universe universe) {
    String bucket = origin == null ? "jdk-" + Runtime.version().feature() : Summarizer.hash(origin).substring(0, 12) + "-" + baseName(origin);
    Path sourceFile = cacheDir.resolve("sources").resolve(bucket).resolve(top + ".java");
    if (Files.isRegularFile(sourceFile)) {
      return sourceFile;
    }
    Path stubFile = cacheDir.resolve("stubs").resolve(bucket).resolve(top + ".java");
    if (Files.isRegularFile(stubFile)) {
      return stubFile;
    }
    try {
      byte[] text = origin == null ? jdkSource(top) : jarSource(Path.of(origin), top);
      if (text != null) {
        return write(sourceFile, text);
      }
      ClassModel model = universe.find(top);
      if (model instanceof BinaryClassModel bin) {
        String stub =
            StubWriter.write(
                top,
                bin.bound(),
                origin,
                name -> universe.find(name) instanceof BinaryClassModel b ? b.bound() : null);
        return write(stubFile, stub.getBytes(StandardCharsets.UTF_8));
      }
    } catch (IOException | RuntimeException e) {
      LOG.log(Level.WARNING, "cannot materialize source for " + top, e);
    }
    return null;
  }

  private static String baseName(String origin) {
    String name = Path.of(origin).getFileName().toString();
    return name.endsWith(".jar") ? name.substring(0, name.length() - 4) : name;
  }

  private static Path write(Path file, byte[] content) throws IOException {
    Files.createDirectories(file.getParent());
    Path tmp = Files.createTempFile(file.getParent(), ".tmp", ".java");
    Files.write(tmp, content);
    tmp.toFile().setReadOnly();
    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    return file;
  }

  private byte[] jdkSource(String top) throws IOException {
    Path srcZip = Path.of(System.getProperty("java.home"), "lib", "src.zip");
    if (!Files.isRegularFile(srcZip)) {
      return null;
    }
    Map<String, String> index = jdkSrcIndex;
    if (index == null) {
      synchronized (this) {
        index = jdkSrcIndex;
        if (index == null) {
          index = new HashMap<>();
          try (ZipFile zip = new ZipFile(srcZip.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
              String name = entries.nextElement().getName();
              int slash = name.indexOf('/');
              if (name.endsWith(".java") && slash > 0) {
                // "java.base/java/lang/String.java" -> "java/lang/String.java"
                index.putIfAbsent(name.substring(slash + 1), name);
              }
            }
          }
          jdkSrcIndex = index;
        }
      }
    }
    String entry = index.get(top + ".java");
    return entry == null ? null : read(srcZip, entry);
  }

  private static byte[] jarSource(Path jar, String top) throws IOException {
    for (Path candidate : sourceJars(jar)) {
      byte[] content = read(candidate, top + ".java");
      if (content != null) {
        return content;
      }
    }
    return null;
  }

  /** Sources jars next to a binary jar, in Maven ({@code x-sources.jar}) and Gradle cache layouts. */
  static List<Path> sourceJars(Path jar) {
    List<Path> out = new ArrayList<>();
    String name = jar.getFileName().toString();
    if (!name.endsWith(".jar")) {
      return out;
    }
    String sourcesName = name.substring(0, name.length() - 4) + "-sources.jar";
    Path sibling = jar.resolveSibling(sourcesName);
    if (Files.isRegularFile(sibling)) {
      out.add(sibling);
    }
    // Gradle: files-2.1/<group>/<artifact>/<version>/<hash>/<artifact>-<version>.jar
    Path versionDir = jar.getParent() == null ? null : jar.getParent().getParent();
    if (versionDir != null && versionDir.toString().contains("files-2.1")) {
      try (Stream<Path> hashes = Files.list(versionDir)) {
        hashes
            .map(h -> h.resolve(sourcesName))
            .filter(Files::isRegularFile)
            .forEach(out::add);
      } catch (IOException e) {
        // ignore
      }
    }
    return out;
  }

  private static byte[] read(Path zipPath, String entryName) throws IOException {
    try (ZipFile zip = new ZipFile(zipPath.toFile())) {
      ZipEntry entry = zip.getEntry(entryName);
      if (entry == null) {
        return null;
      }
      try (InputStream in = zip.getInputStream(entry)) {
        return in.readAllBytes();
      }
    }
  }
}
