# Java Index for VS Code

Fast, lightweight Java navigation from [java-index](https://github.com/slgobinath/java-index):
go to definition, exact references, hover, implementations, type and call hierarchies, and
Spring Boot / Quarkus config keys linked to the Java that reads them.

## Why it's fast

A traditional Java language server such as jdtls imports your build and compiles the whole
project. java-index doesn't compile at all. It parses sources with Google's
[turbine](https://github.com/google/turbine) header compiler into compact declaration summaries,
resolves method bodies only when you ask about them, and serves every window, agent session and
git worktree of a repository from one shared background daemon. It never writes into your
project, so it never fights your build over `target/`.

Measured on spring-petclinic against jdtls with VS Code's default settings
([benchmark](https://github.com/slgobinath/java-index/tree/main/benchmark), medians):

| | java-index | jdtls |
| --- | ---: | ---: |
| Ready to answer, cold / warm start | 1.5 s / 0.4 s | 2.7 s / 1.8 s |
| Memory, one window | 250 MB | 1.8 GB |
| CPU time to open one window, cold | 7 s | 54 s |
| Find references on a common method | 2 ms | 385 ms |
| 8 worktrees open at once: memory | 416 MB | 12.8 GB |
| 8 worktrees open at once: CPU | 11 s | 428 s |

That benchmark is one small Maven project. jdtls is heavier partly because it does more: java-index
is built for *reading* Java, e.g. reviewing what your agents wrote, so there are no compiler
errors, completion or refactorings, and Lombok-generated members are not seen. See
[java-index vs jdtls](https://github.com/slgobinath/java-index#java-index-vs-jdtls) for the full
trade-off.

## Spring Boot and Quarkus configuration

Keys in `application*.properties`, `application*.yml`, `bootstrap*.yml` and
`microprofile-config.properties` are linked both ways with the Java that uses them: `@Value`,
`@ConfigurationProperties`, `@ConfigProperty`, `@ConfigMapping` and `getProperty("…")`.

* Go to definition on a key in Java jumps to where it is set, in every profile.
* Find references on a key in a config file lists the code that reads it.
* Hover shows the value per profile and, for library keys such as `server.port`, the documented
  type, default and description.

Spring's relaxed binding is honoured.

## Server

The extension runs, in order:

1. `javaIndex.server.path` (with `javaIndex.server.arguments`), if set;
2. `jindex` from the `PATH`, e.g. installed with Homebrew;
3. otherwise the java-index release matching this extension's version, downloaded once from
   [GitHub releases](https://github.com/slgobinath/java-index/releases) and checked against its
   `SHA256SUMS`: the native relay on Apple Silicon Macs and Linux (x64, arm64), the jar elsewhere.

The daemon runs on Java 21 or later: `javaIndex.java.home`, else `$JINDEX_JAVA`, `$JAVA_HOME`,
`java` from the `PATH`, or the JDK in `java.jdt.ls.java.home`.

## With the Red Hat Java extension

VS Code highlights Java on its own, so this extension works alone. If the Red Hat Java extension
is also enabled, both servers answer and you see results twice; disable it for workspaces you
review with java-index.

## Settings

| Setting | Default | |
| --- | --- | --- |
| `javaIndex.server.path` | | command that runs the server |
| `javaIndex.server.arguments` | `[]` | its arguments, e.g. `["-jar", "/path/to/java-index.jar"]` |
| `javaIndex.java.home` | | JDK 21+ for the daemon |
| `javaIndex.classpath` | build tool | jars or jar directories, instead of asking Maven or Gradle |
| `javaIndex.inlayHints.parameterNames` | `"literals"` | `"all"` or `"none"` |
| `javaIndex.inlayHints.types` | `true` | `var`, lambda parameter and chain type hints |
| `javaIndex.deprecations` | `true` | mark uses of deprecated APIs |
| `javaIndex.trace.server` | `"off"` | log LSP messages to the *Java Index* output |

Commands: **Java Index: Restart Server** and **Java Index: Show Output**.

## Build

```sh
npm install
npm run package    # java-index-<version>.vsix
code --install-extension java-index-<version>.vsix
```
