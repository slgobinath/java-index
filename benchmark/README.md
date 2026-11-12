# Benchmark

Scripts to reproduce the [comparison in the main README](../README.md#java-index-vs-jdtls):
java-index against Java language servers on
[spring-petclinic](https://github.com/spring-projects/spring-petclinic), for the navigation a
reviewer needs. The servers:

| Name | What it is |
| --- | --- |
| `java-index` | java-index with the JVM relay (`java -jar java-index.jar`) |
| `java-index-native` | java-index with the GraalVM native relay (`target/jindex-relay`) |
| `jdtls` | [Eclipse JDT LS](https://github.com/eclipse-jdtls/eclipse.jdt.ls), the server behind VS Code's Java extension and most editors. It compiles the project, so its answers are the baseline for correctness. |
| `jdtls-light` | jdtls in vscode-java's lightweight mode (`java.server.launchMode: LightWeight`): the syntax server, which parses sources without importing the build, so it has no classpath and no project-wide queries |
| `java-language-server` | [georgewfraser/java-language-server](https://github.com/georgewfraser/java-language-server), a lighter server built on javac |

## Requirements

macOS or Linux, Python 3.10+ (standard library only), git, curl, Maven, and:

* `JAVA21_HOME`: a JDK 21+ (java-index)
* `JAVA25_HOME`: a JDK 25+ (jdtls 1.63 and java-language-server need 25)
* `GRAALVM_HOME` (optional): a GraalVM JDK 25+, to build the native relay for `java-index-native`

About 16 GB of free memory for the 8-worktree run with jdtls.

## Run

```sh
cd benchmark
export JAVA21_HOME=/path/to/jdk-21 JAVA25_HOME=/path/to/jdk-25 GRAALVM_HOME=/path/to/graalvm-25
./setup.sh

python3 bench.py --servers java-index,java-index-native,jdtls,jdtls-light
python3 bench.py --project jackson-databind --servers java-index,java-index-native,jdtls,jdtls-light
python3 worktrees.py --servers java-index,java-index-native,jdtls,jdtls-light
```

`setup.sh` builds java-index (and its native relay when `GRAALVM_HOME` is set), downloads jdtls
1.63.0, builds java-language-server and clones spring-petclinic and jackson-databind, all at
pinned versions, into `.work/`. It also downloads both projects' dependencies, so no measured startup includes network
downloads. Each script writes `results/<timestamp>/` with one JSON file and one server log per
run, and a `report.md` with the tables. `python3 report.py DIR...` rebuilds a report and can
merge several results directories.

### `bench.py`: one repository

3 cold and 3 warm runs per server (`--runs`), each query timed 10 times (`--reps`).

* `--spaces` runs on a copy of petclinic indented with spaces instead of tabs, the only
  difference. java-language-server needs it: it reads LSP character offsets as javac columns,
  which expand tabs to 8, so in tab-indented files it resolves the wrong token and never becomes
  ready.
* `--servers java-language-server --spaces` adds the third server.
* `--project jackson-databind` runs on [jackson-databind](https://github.com/FasterXML/jackson-databind)
  2.23.0 (1,300 Java files, 260k lines) instead of petclinic, with its own queries in
  [`cases_jackson.py`](cases_jackson.py).

### `worktrees.py`: parallel worktrees

Agents often work in parallel, each in its own git worktree, and a reviewer opens several of
them. `worktrees.py` makes N worktrees of petclinic (`--counts`, default 1, 2, 4 and 8), gives
each its own change (two methods appended to `OwnerController`, as if an agent had written them)
and opens them all at the same time, one editor window each. As in an editor, every window starts
its own language server process: N jdtls instances with N workspaces, or N java-index relays
sharing one daemon. All queries run in every window, plus two that check isolation:
go-to-definition on the worktree's own change, and a symbol search that must find that
worktree's change and no other's. (jdtls searches only types unless
`java.symbols.includeSourceMethodDeclarations` is on, which it is not by default, so the second
check fails for it without meaning a leak.)

## What is measured

A small LSP client (`lsp.py`) starts each server on the repository and sends the same queries,
defined in [`cases.py`](cases.py) and [`cases_jackson.py`](cases_jackson.py) before any server was run.

* **Ready**: time from launch until a go-to-definition into project code returns the right
  answer. Servers report readiness differently, so this is measured the same way for all.
  *Cold* starts with empty caches; *warm* restarts on the caches the previous run left.
  jdtls-light is the exception: without the build it can resolve the probe to the wrong overload
  and never improve, so it is ready once the probe returns any location in project code (the
  probe's answer is still judged with the other queries).
* **Query latency**: each query once, then repeated; the report shows the median.
* **Correctness**: each answer is checked against a hand-written expectation. These checks are
  loose (for example "at least 3 references"), so references, implementations and highlights are
  also compared location by location with jdtls.
* **Memory and CPU**: resident memory and CPU time summed over every process the server uses,
  sampled every 250 ms. For java-index that is the relays and the detached daemon.
* **Disk**: the size of each server's cache or workspace directory.

## Fairness

* Each server runs with the defaults its usual launcher applies: java-index's daemon flags, jdtls
  with vscode-java's default VM arguments (`-Xmx2G`, parallel GC) and settings (signature help
  on), jdtls-light with the same arguments and the syntax server's configuration, as vscode-java
  starts it, and java-language-server with its launcher's flags (on a stock JDK 25 rather than its
  jlinked one). A JVM grows into the heap it is allowed, so memory figures are what users get, not
  the minimum each server needs.
* jdtls does much more than the others: compiler errors, completion, refactorings. Lower memory
  for java-index means less work done, not the same work done more cheaply.
* Gradle import is disabled for jdtls because petclinic has both a `pom.xml` and a
  `build.gradle`; all servers use Maven.
* The queries were written by java-index's author. They cover each feature once or twice on a
  small Spring application; they say nothing about scale, Gradle, multi-module builds or Lombok.
