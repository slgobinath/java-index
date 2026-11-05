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

package dev.javaindex.daemon;

import dev.javaindex.index.Summarizer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * Identifies the project a workspace belongs to. All git worktrees of a repository share one git
 * "common dir", so they map to the same key, and therefore to the same daemon.
 */
public final class ProjectKey {
  private ProjectKey() {}

  public static String of(Path workspaceRoot) {
    Path identity = commonGitDir(workspaceRoot);
    if (identity == null) {
      identity = workspaceRoot;
    }
    try {
      identity = identity.toRealPath();
    } catch (IOException e) {
      identity = identity.toAbsolutePath().normalize();
    }
    return Summarizer.hash(identity.toString()).substring(0, 16);
  }

  /**
   * The repository's shared git directory: {@code .git} of the main worktree, which linked
   * worktrees reference via {@code .git} files ({@code gitdir: .../.git/worktrees/name}) and a
   * {@code commondir} file. Null when {@code root} is not inside a git checkout.
   */
  public static Path commonGitDir(Path root) {
    for (Path dir = root.toAbsolutePath().normalize(); dir != null; dir = dir.getParent()) {
      Path git = dir.resolve(".git");
      if (Files.isDirectory(git)) {
        return git;
      }
      if (Files.isRegularFile(git)) {
        try {
          String content = Files.readString(git).strip();
          if (!content.startsWith("gitdir:")) {
            return null;
          }
          Path gitDir = dir.resolve(content.substring("gitdir:".length()).strip()).normalize();
          Path commonDirFile = gitDir.resolve("commondir");
          if (Files.isRegularFile(commonDirFile)) {
            return gitDir.resolve(Files.readString(commonDirFile).strip()).normalize();
          }
          return gitDir; // a submodule: its own repository
        } catch (IOException e) {
          return null;
        }
      }
    }
    return null;
  }

  /**
   * The daemon socket for a project. Unix socket paths are limited to ~104 bytes, so when the state
   * directory is too deep the socket goes in a private per-user directory under /tmp instead.
   */
  public static Path socketPath(Path stateDir, String key) throws IOException {
    Path preferred = stateDir.resolve(key + ".sock");
    if (preferred.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 100) {
      return preferred;
    }
    Path dir = Path.of("/tmp", "java-index-" + System.getProperty("user.name"));
    if (!Files.isDirectory(dir)) {
      Files.createDirectories(dir);
      Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
    }
    if (!Files.getOwner(dir).getName().equals(System.getProperty("user.name"))) {
      throw new IOException(dir + " is not owned by the current user");
    }
    return dir.resolve(key + ".sock");
  }

  /** Per-user state directory (sockets, logs, extracted sources, cached classpaths). */
  public static Path stateDir() {
    String override = System.getenv("JINDEX_HOME");
    Path dir;
    if (override != null && !override.isBlank()) {
      dir = Path.of(override);
    } else {
      String xdg = System.getenv("XDG_CACHE_HOME");
      Path base =
          xdg != null && !xdg.isBlank()
              ? Path.of(xdg)
              : Path.of(System.getProperty("user.home"), ".cache");
      dir = base.resolve("java-index");
    }
    try {
      if (!Files.isDirectory(dir)) {
        Files.createDirectories(dir);
        try {
          Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException e) {
          // non-POSIX file system
        }
      }
    } catch (IOException e) {
      throw new IllegalStateException("cannot create " + dir, e);
    }
    return dir;
  }
}
