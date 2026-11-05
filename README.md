# java-index

**java-index is a fast, shared Java code index for editors and AI coding agents.**

It answers the questions you ask while reading Java code: *where is this defined, who calls it,
what implements it, what is this library method's real signature, which config key does this
`@Value` read?* It serves those answers to editors over the
[Language Server Protocol](https://microsoft.github.io/language-server-protocol/) (LSP) and to
agents such as Claude Code over the [Model Context Protocol](https://modelcontextprotocol.io/)
(MCP), from a single index per repository.

## Why

Full Java language servers such as jdtls compile your project. That makes them accurate, but also
slow to start and expensive in memory, and each editor window and each git worktree pays that cost
again. Agents are worse off: they mostly grep, and they guess at library APIs and at which `save()`
a call really goes to.

java-index makes a different trade:

* **It indexes, it doesn't compile.** Sources are parsed with
  [turbine](https://github.com/google/turbine), Google's header compiler, into compact declaration
  summaries. Method bodies are resolved lazily from the token stream when you ask about them.
* **One daemon per repository.** Every editor window, agent session and git worktree of a
  repository shares one background daemon and one index. Unchanged files, dependency symbols and
  library sources are held once; each worktree keeps only its own changes.
* **Same answers for people and agents.** Editors get go-to-definition, references, hover, call
  and type hierarchies. Agents get the same knowledge as name-based tools that return compact text
  with `file:line` locations.

What it does **not** do: there are no compiler errors, completion or refactorings. Use it for
navigation and understanding, alongside your build for correctness.

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
