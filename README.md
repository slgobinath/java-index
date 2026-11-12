# java-index

Fast, lightweight Java code navigation, mainly for reviewing code that AI agents write.

It handles the everyday questions you have when reading Java: where is this defined, who calls it,
what implements it, what does this library method actually look like, which config key does this
`@Value` read. Your editor talks to it over
[LSP](https://microsoft.github.io/language-server-protocol/), and agents like Claude Code talk to
it over [MCP](https://modelcontextprotocol.io/). Both share the same index.

https://github.com/user-attachments/assets/9984c749-106f-4f31-ac43-a0c9f10f1f90

## Contents

- [Purpose](#purpose)
- [How it's different](#how-its-different)
- [java-index vs jdtls](#java-index-vs-jdtls)
- [What you get](#what-you-get) — [in your editor (LSP)](#in-your-editor-lsp), [for AI agents (MCP)](#for-ai-agents-mcp)
- [Install](#install)
- [Set up](#set-up)
- [How it works](#how-it-works)
- [Classpath](#classpath)
- [Configuration](#configuration)
- [Limitations](#limitations)
- [License](#license)

## Purpose

More and more of our Java code is written by agents, and we spend more time reviewing it than
writing it. Reviewing needs basic navigation: go to definition, find callers, check
implementations. Full language servers like jdtls can do that, but they're built for writing code.
They're heavy, they start over for every git worktree, and they compile the project into the same
`target/` folder the agent's build uses.

java-index only does the reading part. One small daemon serves every worktree of a repository,
agents get the same index as tools, and it never builds or writes anything in your project. It's
not a replacement for jdtls or your IDE. If you write Java by hand, keep using those.

## How it's different

* **No compiling.** Sources are parsed with [turbine](https://github.com/google/turbine) (Google's
  header compiler) into small declaration summaries. Method bodies are only looked at when you ask
  about something inside them.
* **One daemon per repo.** All your editor windows, agent sessions and worktrees share one
  process. Files that are the same across worktrees are stored once; each worktree only keeps
  what it changed.
* **Same answers for you and your agents.** The editor gets definitions, references, hover and
  hierarchies. Agents get the same information as simple tools that return text with `file:line`
  locations.

## java-index vs jdtls

jdtls is a full language server for writing Java. java-index only covers reading it.

| | java-index | jdtls | jdtls light |
| --- | :---: | :---: | :---: |
| Definition, references, hover, implementations, type and call hierarchy, symbols | ✓ | ✓ | partial (definition, hover and symbols in project sources; no references, implementations or hierarchies) |
| Compiler errors and warnings | — | ✓ | syntax errors only |
| Completion | — | ✓ | partial (no classpath) |
| Refactoring, quick fixes (beyond imports), formatting | — | ✓ | formatting only |
| Debugging, running tests | — | ✓ (with extensions) | — |
| Lombok and other annotation processors | — | ✓ | — |
| Spring Boot / Quarkus config keys linked to Java | ✓ | — (separate Spring Tools) | — |
| Tools for agents (MCP) | ✓ | — | — |
| One shared process for every worktree of a repository | ✓ | — (one per window) | — (one per window) |
| Writes into your project (`target/`) | never | yes, builds alongside yours | never |

Numbers from the [benchmark](benchmark/README.md) on spring-petclinic, a small Spring Boot app
(Apple M5 Pro, jdtls 1.63.0 with VS Code's default settings, java-index with the native relay;
medians). jdtls light is the same jdtls in VS Code's lightweight mode
(`java.server.launchMode: LightWeight`), which only parses sources and doesn't import the build:

| | java-index | jdtls | jdtls light |
| --- | ---: | ---: | ---: |
| Ready to answer, cold / warm start | 1.5 s / 0.4 s | 2.9 s / 1.9 s | 1.6 s / 1.3 s |
| Memory, one window | 256 MB | 1.8 GB | 337 MB |
| CPU time to open one window, cold | 7 s | 57 s | 10 s |
| Query latency (queries it answers) | 0.2–7.6 ms | 0.7–395 ms | 1.1–8.0 ms |
| Find references on a common method | 2 ms | 395 ms | not supported |
| 8 worktrees open at once: all ready | 1.6 s | 8.3 s | 2.7 s |
| 8 worktrees open at once: memory | 418 MB | 12.8 GB | 3.1 GB |
| 8 worktrees open at once: CPU / disk | 11 s / < 1 MB | 434 s / 1.3 GB | 66 s / 18 MB |
| Correct answers (17 queries per window) | 17 / 17 | 17 / 17 | 5 / 17 |
| References and implementations, location by location | same as jdtls | (baseline) | not supported |

On [jackson-databind](https://github.com/FasterXML/jackson-databind) 2.23.0, a larger library
(1,300 Java files, 260k lines, about 50 times petclinic), with the same kinds of query:

| | java-index | jdtls | jdtls light |
| --- | ---: | ---: | ---: |
| Ready to answer, cold / warm start | 1.7 s / 0.9 s | 3.1 s / 2.2 s | 2.3 s / 2.0 s |
| Memory, one window | 328 MB | 1.8 GB | 619 MB |
| CPU time to open one window, cold | 23 s | 91 s | 56 s |
| Query latency (queries it answers) | 0.6–201 ms | 3.8–505 ms | 8.9–383 ms |
| Find references on a common method (383 callers) | 27 ms | 161 ms | not supported |
| Correct answers (17 queries) | 17 / 17 | 17 / 17 | 4 / 17 |
| References and implementations, location by location | same as jdtls | (baseline) | not supported |

Lightweight mode makes jdtls almost as cheap to start as java-index on a small project, but without
the build it has no classpath, and it takes none: `java.project.referencedLibraries` is ignored in
that mode. It can't do references, implementations, type or call hierarchy, signature help or
workspace symbols, and anything that depends on a library type (definitions and hover into
libraries, and on jackson-databind even picking the right overload of a project method) comes back
empty or wrong.

To be fair about these numbers: it's two single-module Maven projects and 17 queries per project
that we picked ourselves. We haven't benchmarked very large codebases, Gradle or multi-module
builds yet. jdtls is also doing a lot
more work, since it compiles the project. Where jdtls compiles, java-index has to approximate,
which means:

* **No Lombok or annotation processors.** turbine doesn't run them, so anything generated by
  `@Getter`, `@Data`, `@Builder` and friends doesn't exist for java-index, and calls to it won't
  resolve.
* **Generated sources need a build first.** Code in `target/generated-sources` (MapStruct,
  protobuf, OpenAPI) gets indexed once it's there, but on a fresh clone it isn't.
* **Resolution isn't perfect.** Some generic inference falls back to bounds, and reflection or
  framework wiring is invisible. When it isn't sure, it returns nothing instead of guessing. See
  [Limitations](#limitations).
* **No compile errors.** It can't tell you the code doesn't compile. Your build or CI does that.

So: if you write Java by hand, use jdtls or an IDE. If you mostly review what agents write,
especially across several worktrees, java-index gets you the same navigation for a lot less time
and memory.

## What you get

### In your editor (LSP)

| Feature | What you get |
| --- | --- |
| Go to definition | into your sources, dependency sources jars, the JDK's `src.zip`, or generated stubs |
| Find references | exact: occurrences that resolve to the same declaration, not just the same name |
| Hover | signature and javadoc, overrides and implementations, usage counts, deprecation with replacement |
| Implementations, type and call hierarchy | subtypes, overrides, supertypes, incoming and outgoing calls |
| Signature help and inlay hints | overloads while typing; parameter names, `var` and lambda types, member-chain types |
| Code actions | add a missing import, organize imports |
| Diagnostics | uses of `@Deprecated` APIs, with the suggested replacement |
| Symbols | file outline; classes, methods and fields across sources and libraries |

**Spring Boot and Quarkus configuration.** Keys in `application*.properties` and `.yml` are linked
both ways with the Java that uses them (`@Value`, `@ConfigurationProperties`, `@ConfigProperty`,
`@ConfigMapping`, `getProperty("…")`). Jump from a key in Java to where it is set in every profile,
or from a config file to the code that reads it. Hover shows the value per profile and, for library
keys such as `server.port`, the documented type, default and description. Spring's relaxed binding
works too.

### For AI agents (MCP)

| Tool | What it answers |
| --- | --- |
| `java_api` | The real API of a class or member as this project compiles against it, from source, dependency jars or the JDK |
| `java_callers` | Usages of a method, constructor or field, with receiver types resolved so same-named methods on other types are excluded |
| `java_implementations` | All subtypes of a type or all overrides of a method |
| `java_outline` | A file's types and members with resolved signatures and line numbers |
| `java_find_type` | Classes by name, camel-case humps or qualified suffix |
| `java_config` | A Spring Boot / Quarkus config key: its value per profile, the Java that uses it, and library docs |

Every tool takes an optional `worktree` path to query another worktree of the same repository.

Grep is still fine for plain text search. java-index helps most with library APIs (no more
guessing signatures), common method names like `save`, `get` or `execute`, and type hierarchies.
Treat its answers as strong hints, not proof, since it can't see reflection or framework wiring.

## Install

**macOS (Apple Silicon) and Linux**, with [Homebrew](https://brew.sh). This repository is its own
tap:

```sh
brew tap slgobinath/java-index https://github.com/slgobinath/java-index
brew trust --formula slgobinath/java-index/java-index
brew install java-index
jindex --version
```

Homebrew refuses formulae from third-party taps until you trust them, hence `brew trust`. Later
releases install with `brew update && brew upgrade java-index`.

This installs the `jindex` command: a small native relay, plus the daemon it starts, which runs on
Homebrew's OpenJDK (set `JINDEX_JAVA` to use another JDK 21+).

**Anywhere else** (Intel Macs, Windows, or without Homebrew): download `java-index-<version>.jar`
from the [releases](https://github.com/slgobinath/java-index/releases) and use
`java -jar /path/to/java-index-<version>.jar` (Java 21+) wherever this README says `jindex`.

**From source** (JDK 21+):

```sh
mvn package                                        # target/java-index.jar, runs the tests
JAVA_HOME=/path/to/graalvm-25 mvn -Pnative package # also target/jindex-relay, the native relay
```

`target/jindex-relay` works like `jindex`: it finds `java-index.jar` next to itself (or in
`../target`, `../lib`) and starts the daemon with `$JINDEX_JAVA`, `$JAVA_HOME/bin/java` or `java`
from the PATH. Each editor window or agent session runs one relay; the native one uses about
12 MB instead of a JVM's 60–80 MB.

## Set up

`jindex` starts (or connects to) the repository's daemon:

```
jindex          # LSP over stdio, for editors       (same as: jindex lsp)
jindex mcp      # MCP over stdio, for agents        (--root DIR to override the working directory)
jindex --help   # usage; --version prints the version
```

If you run plain `jindex` in a terminal it will look stuck. It's just waiting for an editor to
talk to it. Press Ctrl-D to exit.

**Claude Code** (run in your repository):

```sh
claude mcp add java-index -- jindex mcp
```

**Neovim** (0.11+):

```lua
vim.lsp.config('java-index', {
  cmd = { 'jindex' },
  filetypes = { 'java' },
  root_markers = { '.git', 'pom.xml', 'build.gradle', 'build.gradle.kts' },
})
vim.lsp.enable('java-index')
```

**Helix** (`languages.toml`):

```toml
[language-server.java-index]
command = "jindex"

[[language]]
name = "java"
language-servers = ["java-index"]
```

**Emacs** (eglot): `(add-to-list 'eglot-server-programs '(java-mode "jindex"))`

**Zed**: Zed's Java extension hard-wires jdtls, so [`editors/zed`](editors/zed) holds a small
extension that adds `java-index` as a Java server. Keep the Java extension for syntax highlighting,
install `editors/zed` with `zed: install dev extension`, then in `settings.json`:

```json
"languages": {
  "Java": { "language_servers": ["java-index", "!jdtls", "..."] }
}
```

The extension runs `jindex` from the PATH; to use the jar instead, add
`"lsp": { "java-index": { "binary": { "path": "java", "arguments": ["-jar", "/path/to/java-index.jar"] } } }`.

**VS Code**: Install it from the
[Visual Studio Marketplace](https://marketplace.visualstudio.com/items?itemName=gobinath.java-index). 
Java 21 is required to run the daemon. Disable the Red Hat Java extension in
workspaces you review with java-index, or you get every answer twice. Settings are in
[its README](editors/vscode/README.md).

**Sublime LSP / Kate**: register a custom stdio server running `jindex` for Java files.

## How it works

```
editor ──stdio──► jindex      (relay) ──unix socket──► daemon (one per repository)
editor ──stdio──► jindex      (relay) ─────────────┤
agent  ──stdio──► jindex mcp  (relay) ─────────────┘
```

* **Routing.** Each client runs a small relay. It finds the repository's git common dir (shared by
  all its worktrees) and connects to that repository's daemon, starting it if needed.
* **Shared index.** Each source file becomes a declaration summary keyed by content hash. The first
  worktree indexed becomes the shared base snapshot; other worktrees store only the files that
  differ, plus unsaved editor buffers.
* **Dependencies.** Jars are read lazily with turbine's bytecode binder, shared across worktrees
  with the same build files. Definitions in libraries open their `-sources.jar`, the JDK's
  `src.zip`, or a stub generated from the class file.
* **Method bodies.** Turbine skips method bodies, so the cursor is resolved from tokens: locals,
  `var`, lambdas, anonymous classes and member chains with generic substitution, overload selection
  and lambda-based inference.
* **References.** Each file summary carries a Bloom filter of its identifiers, so a search reads only
  files that may mention the name, then keeps the occurrences that resolve to the same declaration.
* **Resources.** The relay only forwards bytes; the daemon does the work. It uses a
  shrinkable heap, compacts when quiet and exits after 15 idle minutes.

## Classpath

Resolved per worktree, in this order:

1. `initializationOptions.classpath`: a list of jars or jar directories.
2. `.java-index/classpath` in the worktree: one entry per line.
3. Maven (`pom.xml`, uses `./mvnw` if present): `dependency:build-classpath`.
4. Gradle (`build.gradle[.kts]`, uses `./gradlew`): an init script that prints the compile classpaths.

Results are cached, keyed by the content of the build files, and recomputed when a build file changes.

## Configuration

| Variable | Default | Purpose |
| --- | --- | --- |
| `JINDEX_HOME` | `$XDG_CACHE_HOME/java-index` or `~/.cache/java-index` | sockets, daemon logs (`<key>.log`), extracted sources, cached classpaths |
| `JINDEX_DAEMON_OPTS` | `-Xmx1g` | extra JVM flags for the daemon |
| `JINDEX_IDLE_MINUTES` | `15` | daemon exits after this long with no connections |
| `JINDEX_JAVA` | `java` | JDK (21+) the relay starts the daemon with |

LSP initialization options:

| Option | Default | |
| --- | --- | --- |
| `classpath` | build tool | see [Classpath](#classpath) |
| `inlayHints.parameterNames` | `"literals"` | `"all"` or `"none"` |
| `inlayHints.types` | `true` | `var`, lambda parameter and chain type hints |
| `deprecations` | `true` | mark uses of deprecated APIs |

`java -jar java-index.jar lsp --no-daemon` and `… mcp --no-daemon` serve in-process without a daemon (the jar only; the native relay always relays), which is
handy for debugging.

## Limitations

Resolution is approximate on purpose. Generic methods are inferred from their arguments and
lambda parameters from the expected functional interface, so `stream().map(...).filter(...)` and
`forEach(item -> item.save())` resolve. Inference that needs the assignment target
(`collect(Collectors.toList())`) falls back to the type variable's bound. When java-index isn't sure,
it returns nothing instead of guessing. References are exact matches on the resolved declaration:
a call through a subclass that overrides a method counts for the override (the MCP `java_callers`
tool reports both, in separate groups).

## License

Licensed under the [Apache License, Version 2.0](LICENSE). The built jar bundles third-party
libraries under Apache 2.0, EPL 2.0 and MIT; see [NOTICE](NOTICE).
