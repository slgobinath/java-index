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

import com.google.turbine.binder.ClassPath;
import com.google.turbine.binder.ClassPathBinder;
import com.google.turbine.binder.JimageClassBinder;
import com.google.turbine.binder.bytecode.BytecodeBoundClass;
import com.google.turbine.binder.sym.ClassSymbol;
import dev.javaindex.model.ClassModel;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Bytecode symbols for a set of jars (or the JDK), bound lazily by turbine. One instance per distinct
 * classpath is shared by every worktree that resolves to that classpath.
 */
public final class Dependencies {
  private static final Logger LOG = Logger.getLogger(Dependencies.class.getName());

  public static final Dependencies EMPTY = new Dependencies(null, List.of(), false);
  private static volatile Dependencies jdk;

  private final ClassPath classPath;
  private final List<Path> jars;
  private final boolean isJdk;
  private final ConcurrentHashMap<String, Optional<ClassModel>> models = new ConcurrentHashMap<>();
  private volatile List<String> classNames;
  private volatile dev.javaindex.config.LibraryConfig config;

  private Dependencies(ClassPath classPath, List<Path> jars, boolean isJdk) {
    this.classPath = classPath;
    this.jars = jars;
    this.isJdk = isJdk;
  }

  public static Dependencies of(List<Path> jars) {
    List<Path> existing = jars.stream().filter(Files::isRegularFile).toList();
    if (existing.isEmpty()) {
      return EMPTY;
    }
    try {
      return new Dependencies(ClassPathBinder.bindClasspath(existing), existing, false);
    } catch (IOException e) {
      LOG.log(Level.WARNING, "failed to open classpath", e);
      return EMPTY;
    }
  }

  /** The running JVM's platform classes, shared by every project in the process. */
  public static Dependencies jdk() {
    Dependencies d = jdk;
    if (d == null) {
      synchronized (Dependencies.class) {
        d = jdk;
        if (d == null) {
          try {
            d = new Dependencies(JimageClassBinder.bindDefault(), List.of(), true);
          } catch (IOException e) {
            LOG.log(Level.WARNING, "failed to open JDK image", e);
            d = EMPTY;
          }
          jdk = d;
        }
      }
    }
    return d;
  }

  public List<Path> jars() {
    return jars;
  }

  public ClassModel model(String binaryName) {
    if (classPath == null) {
      return null;
    }
    return models
        .computeIfAbsent(
            binaryName,
            name -> {
              BytecodeBoundClass bound;
              try {
                bound = classPath.env().get(new ClassSymbol(name));
              } catch (RuntimeException e) {
                bound = null;
              }
              if (bound == null) {
                return Optional.empty();
              }
              String origin = isJdk ? null : bound.jarFile();
              return Optional.of(new BinaryClassModel(bound, name, origin));
            })
        .orElse(null);
  }

  /** Configuration keys documented by the jars (Spring Boot / Quarkus metadata), on first use. */
  public dev.javaindex.config.LibraryConfig config() {
    dev.javaindex.config.LibraryConfig c = config;
    if (c == null) {
      synchronized (this) {
        c = config;
        if (c == null) {
          c = isJdk || jars.isEmpty() ? dev.javaindex.config.LibraryConfig.EMPTY : dev.javaindex.config.LibraryConfig.load(jars);
          config = c;
        }
      }
    }
    return c;
  }

  public boolean hasPackage(String pkg) {
    if (classPath == null) {
      return false;
    }
    try {
      return classPath.index().lookupPackage(pkg) != null;
    } catch (RuntimeException e) {
      return false;
    }
  }

  /** Binary names of all non-anonymous classes, loaded on first use (for workspace/symbol). */
  public List<String> classNames() {
    List<String> names = classNames;
    if (names == null) {
      synchronized (this) {
        names = classNames;
        if (names == null) {
          names = isJdk ? listJdk() : listJars();
          classNames = names;
        }
      }
    }
    return names;
  }

  private List<String> listJars() {
    List<String> out = new ArrayList<>();
    for (Path jar : jars) {
      try (ZipFile zip = new ZipFile(jar.toFile())) {
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
          addClassName(out, entries.nextElement().getName());
        }
      } catch (IOException e) {
        LOG.log(Level.FINE, "cannot list " + jar, e);
      }
    }
    return List.copyOf(out);
  }

  private static List<String> listJdk() {
    List<String> out = new ArrayList<>();
    try {
      FileSystem jrt = FileSystems.getFileSystem(URI.create("jrt:/"));
      try (Stream<Path> modules = Files.list(jrt.getPath("/modules"))) {
        for (Path module : modules.toList()) {
          try (Stream<Path> files = Files.walk(module)) {
            files.forEach(
                f -> addClassName(out, module.relativize(f).toString().replace('\\', '/')));
          }
        }
      }
    } catch (IOException | RuntimeException e) {
      LOG.log(Level.FINE, "cannot list JDK classes", e);
    }
    return List.copyOf(out);
  }

  private static void addClassName(List<String> out, String entry) {
    if (!entry.endsWith(".class")
        || entry.startsWith("META-INF/")
        || entry.endsWith("module-info.class")
        || entry.endsWith("package-info.class")) {
      return;
    }
    String name = entry.substring(0, entry.length() - ".class".length());
    int dollar = name.lastIndexOf('$');
    if (dollar >= 0 && dollar + 1 < name.length() && Character.isDigit(name.charAt(dollar + 1))) {
      return;
    }
    out.add(name);
  }
}
