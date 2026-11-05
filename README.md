# java-index

**Java code navigation for reviewing what your AI agents write.**

java-index answers the questions you ask while reading Java code: *where is this defined, who
calls it, what implements it, what is this library method's real signature, which config key does
this `@Value` read?* It serves those answers to your editor over the
[Language Server Protocol](https://microsoft.github.io/language-server-protocol/) (LSP), and to
agents such as Claude Code over the [Model Context Protocol](https://modelcontextprotocol.io/)
(MCP), from one shared index per repository.

## The problem

More and more Java code is written by agents, and the human's job shifts to reviewing it. Review
means navigating: jumping to a definition, finding the callers of a changed method, checking what
implements an interface. A diff view can't do that, so you open the code in an editor.

That editor usually brings a full Java language server such as jdtls, which is built for *writing*
code: it imports the build and compiles the project to give you errors, completion and
refactorings. For review that is the wrong trade:

* **It's heavy for a read-only job.** Each project costs a build import and typically gigabytes of
  memory, while all you want is cmd+click and find references.
* **Agents multiply it.** Agents often work in parallel, each in its own git worktree. A full
  language server treats every worktree as a separate project: another import, another index,
  another JVM.
* **It fights the agent's build.** jdtls compiles the project itself, and for Maven projects it
  writes to the same `target/` directory the build uses. When an agent runs `mvn` while the
  worktree is open in your editor, the two builds run at once over the same output, and you get
  failed or flaky builds, stale classes, and a language server busy rebuilding after every one.
* **The agents themselves navigate blind.** They mostly grep, guess at library signatures, and
  can't tell which of ten `save()` methods a call really goes to.

java-index is built for this workflow. It gives your editor fast, accurate navigation across every
worktree of a repository, at a fraction of the cost, and gives your agents the same index as
tools. It never builds or writes anything in your project: it reads sources and jars, keeps its
caches in its own directory, and asks the build tool only for the classpath, once per change to
the build files. It is **not** a replacement for jdtls or your IDE: keep those for writing code by hand.

## How it's different

* **It indexes, it doesn't compile.** Sources are parsed with
  [turbine](https://github.com/google/turbine), Google's header compiler, into compact declaration
  summaries. Method bodies are resolved lazily from the token stream when you ask about them.
* **One daemon per repository.** Every editor window, agent session and git worktree of a
  repository shares one background daemon and one index. Unchanged files, dependency symbols and
  library sources are held once; each worktree keeps only its own changes.
* **Same answers for people and agents.** Editors get go-to-definition, references, hover, call
  and type hierarchies. Agents get the same knowledge as name-based tools that return compact text
  with `file:line` locations.

## java-index vs jdtls

jdtls is a full language server for *writing* Java; java-index only covers *reading* it. What
each does:

| | java-index | jdtls |
| --- | :---: | :---: |
| Definition, references, hover, implementations, type and call hierarchy, symbols | ✓ | ✓ |
| Compiler errors and warnings | — | ✓ |
| Completion | — | ✓ |
| Refactoring, quick fixes (beyond imports), formatting | — | ✓ |
| Debugging, running tests | — | ✓ (with extensions) |
| Lombok and other annotation processors | — | ✓ |
| Spring Boot / Quarkus config keys linked to Java | ✓ | — (separate Spring Tools) |
| Tools for agents (MCP) | ✓ | — |
| One shared process for every worktree of a repository | ✓ | — (one per window) |
| Writes into your project (`target/`) | never | yes, builds alongside yours |

Measured with the [benchmark](benchmark/README.md) on spring-petclinic, a small Spring Boot
application (Apple M5 Pro, jdtls 1.63.0 with VS Code's default settings, java-index with the
native relay; medians):

| | java-index | jdtls |
| --- | ---: | ---: |
| Ready to answer, cold / warm start | 1.5 s / 0.4 s | 2.7 s / 1.8 s |
| Memory, one window | 250 MB | 1.8 GB |
| CPU time to open one window, cold | 7 s | 54 s |
| Query latency (17 navigation queries) | 0.3–7.5 ms | 0.6–385 ms |
| Find references on a common method | 2 ms | 385 ms |
| 8 worktrees open at once: all ready | 1.6 s | 7.8 s |
| 8 worktrees open at once: memory | 416 MB | 12.8 GB |
| 8 worktrees open at once: CPU / disk | 11 s / < 1 MB | 428 s / 1.3 GB |
| Correct answers (17 queries per window) | 17 / 17 | 17 / 17 |
| References and implementations, location by location | same as jdtls | (baseline) |

**The gap, explicitly.** The numbers above come from one small Maven project and 17 queries
chosen by java-index's author; large codebases, Gradle and multi-module builds have not been
benchmarked yet. jdtls is slower and heavier largely because it does more: it compiles the
project, which java-index never does. And where jdtls compiles, java-index approximates:

* **Lombok and annotation processors are not supported.** turbine does not run them, so members
  generated by `@Getter`, `@Data`, `@Builder` and similar don't exist for java-index: calls to them
  go unresolved.
* **Generated sources only after a build.** Code under `target/generated-sources` (MapStruct,
  protobuf, OpenAPI) is indexed once it exists; on a fresh clone it doesn't yet.
* **Resolution is approximate.** Inference that depends on the assignment target falls back to
  bounds, reflection and framework wiring are invisible, and when unsure java-index returns
  nothing rather than guess. See [Limitations](#limitations).
* **No diagnostics.** It cannot tell you that code doesn't compile; your build or CI does that.

If you write Java by hand, use jdtls or an IDE. If you mostly review what agents write, often in
several worktrees at once, java-index gives you the same navigation answers for a fraction of
the time and memory.

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
is honoured.

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

Grep is still the right tool for text search. java-index pays off for library APIs (no guessing
signatures), common method names (`save`, `get`, `execute`) and type hierarchies. Its answers are
strong evidence, not proof: reflection and framework wiring are invisible to it.

## Getting started

Requires JDK 21+.

```sh
mvn package          # builds target/java-index.jar and runs the tests
```

Optionally, build the relay as a native binary with a [GraalVM](https://www.graalvm.org/) JDK 25+.
Each editor window or agent session runs one relay; the native one uses about 12 MB instead of a
JVM's 60–80 MB and connects in milliseconds. The daemon still runs on a JVM.

```sh
JAVA_HOME=/path/to/graalvm mvn -Pnative package    # also writes target/jindex-relay
```

`target/jindex-relay` is used in place of `java -jar java-index.jar` in all the commands below. It
finds `java-index.jar` next to itself and starts the daemon with `$JINDEX_JAVA`, `$JAVA_HOME/bin/java`
or `java` from the PATH.

Prebuilt releases are planned. Until then, build the jar and run it with Java 21+. It starts (or
connects to) the repository's daemon:

```
java -jar java-index.jar          # LSP over stdio, for editors   (same as: … lsp)
java -jar java-index.jar mcp      # MCP over stdio, for agents    (--root DIR to override the working directory)
```

Below, `/path/to/java-index.jar` is the built `target/java-index.jar`.

**Claude Code** (run in your repository):

```sh
claude mcp add java-index -- java -jar /path/to/java-index.jar mcp
```

**Neovim** (0.11+):

```lua
vim.lsp.config('java-index', {
  cmd = { 'java', '-jar', '/path/to/java-index.jar' },
  filetypes = { 'java' },
  root_markers = { '.git', 'pom.xml', 'build.gradle', 'build.gradle.kts' },
})
vim.lsp.enable('java-index')
```

**Helix** (`languages.toml`):

```toml
[language-server.java-index]
command = "java"
args = ["-jar", "/path/to/java-index.jar"]

[[language]]
name = "java"
language-servers = ["java-index"]
```

**Emacs** (eglot): `(add-to-list 'eglot-server-programs '(java-mode "java" "-jar" "/path/to/java-index.jar"))`

**Zed**: Zed's Java extension hard-wires jdtls, so [`editors/zed`](editors/zed) holds a small
extension that adds `java-index` as a Java server. Keep the Java extension for syntax highlighting,
install `editors/zed` with `zed: install dev extension`, then in `settings.json`:

```json
"lsp": {
  "java-index": {
    "binary": { "path": "java", "arguments": ["-jar", "/path/to/java-index.jar"] }
  }
},
"languages": {
  "Java": { "language_servers": ["java-index", "!jdtls", "..."] }
}
```

**Sublime LSP / Kate**: register a custom stdio server running `java -jar /path/to/java-index.jar` for Java files.

## How it works

```
editor ──stdio──► java-index.jar      (relay) ──unix socket──► daemon (one per repository)
editor ──stdio──► java-index.jar      (relay) ─────────────┤
agent  ──stdio──► java-index.jar mcp  (relay) ─────────────┘
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

`java -jar java-index.jar lsp --no-daemon` and `… mcp --no-daemon` serve in-process without a daemon, which is
useful for debugging.

## Limitations

Resolution is deliberately approximate. Generic methods are inferred from their arguments and
lambda parameters from the expected functional interface, so `stream().map(...).filter(...)` and
`forEach(item -> item.save())` resolve. Inference that needs the assignment target
(`collect(Collectors.toList())`) falls back to the type variable's bound. When java-index is unsure
it returns nothing rather than guessing. References are exact matches on the resolved declaration:
a call through a subclass that overrides a method counts for the override (the MCP `java_callers`
tool reports both, in separate groups).

## License

Licensed under the [Apache License, Version 2.0](LICENSE). The built jar bundles third-party
libraries under Apache 2.0, EPL 2.0 and MIT; see [NOTICE](NOTICE).
