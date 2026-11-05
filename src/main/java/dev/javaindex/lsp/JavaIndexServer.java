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

package dev.javaindex.lsp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.javaindex.classpath.SourceLocator;
import dev.javaindex.config.ConfigFile;
import dev.javaindex.config.ConfigIndex;
import dev.javaindex.config.ConfigKeys;
import dev.javaindex.config.ConfigRefScanner;
import dev.javaindex.config.ConfigReport;
import dev.javaindex.index.Tokens;
import dev.javaindex.index.FileSummary;
import dev.javaindex.index.FileSummary.MethodDecl;
import dev.javaindex.index.FileSummary.TypeInfo;
import dev.javaindex.index.TypeHandle;
import dev.javaindex.model.Member;
import dev.javaindex.model.TypeRef;
import dev.javaindex.resolve.Symbol;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.eclipse.lsp4j.CallHierarchyIncomingCall;
import org.eclipse.lsp4j.CallHierarchyIncomingCallsParams;
import org.eclipse.lsp4j.CallHierarchyItem;
import org.eclipse.lsp4j.CallHierarchyOutgoingCall;
import org.eclipse.lsp4j.CallHierarchyOutgoingCallsParams;
import org.eclipse.lsp4j.CallHierarchyPrepareParams;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionKind;
import org.eclipse.lsp4j.CodeActionOptions;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DiagnosticTag;
import org.eclipse.lsp4j.DocumentHighlight;
import org.eclipse.lsp4j.DocumentHighlightKind;
import org.eclipse.lsp4j.DocumentHighlightParams;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.ImplementationParams;
import org.eclipse.lsp4j.InlayHint;
import org.eclipse.lsp4j.InlayHintKind;
import org.eclipse.lsp4j.InlayHintParams;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.ParameterInformation;
import org.eclipse.lsp4j.SignatureHelp;
import org.eclipse.lsp4j.SignatureHelpOptions;
import org.eclipse.lsp4j.SignatureHelpParams;
import org.eclipse.lsp4j.SignatureInformation;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.TypeDefinitionParams;
import org.eclipse.lsp4j.TypeHierarchyItem;
import org.eclipse.lsp4j.TypeHierarchyPrepareParams;
import org.eclipse.lsp4j.TypeHierarchySubtypesParams;
import org.eclipse.lsp4j.TypeHierarchySupertypesParams;
import org.eclipse.lsp4j.WorkspaceEdit;
import dev.javaindex.index.Lines;
import dev.javaindex.index.Summarizer;
import dev.javaindex.model.ClassModel;
import dev.javaindex.model.Target;
import dev.javaindex.model.Universe;
import dev.javaindex.project.Project;
import dev.javaindex.project.Worktree;
import dev.javaindex.query.Describe;
import dev.javaindex.query.Queries;
import dev.javaindex.query.Queries.Ref;
import dev.javaindex.resolve.Resolver;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.DeclarationParams;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesRegistrationOptions;
import org.eclipse.lsp4j.DidChangeWorkspaceFoldersParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.FileEvent;
import org.eclipse.lsp4j.FileSystemWatcher;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.Registration;
import org.eclipse.lsp4j.RegistrationParams;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.ServerInfo;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentPositionParams;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.TextDocumentSyncOptions;
import org.eclipse.lsp4j.WatchKind;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.WorkspaceFoldersOptions;
import org.eclipse.lsp4j.WorkspaceServerCapabilities;
import org.eclipse.lsp4j.WorkspaceSymbol;
import org.eclipse.lsp4j.WorkspaceSymbolParams;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;

/**
 * One editor session. Supports go-to-definition/declaration, document symbols and workspace
 * symbols. All heavy state lives in the shared {@link Project}; a session only knows which
 * worktrees it has open.
 */
public final class JavaIndexServer
    implements LanguageServer, TextDocumentService, WorkspaceService, LanguageClientAware {
  private static final Logger LOG = Logger.getLogger(JavaIndexServer.class.getName());
  private static final int MAX_WORKSPACE_SYMBOLS = 100;
  private static final int MAX_LIBRARY_SYMBOLS = 30;
  private static final ScheduledExecutorService POLLER =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread t = new Thread(r, "java-index-poll");
            t.setDaemon(true);
            return t;
          });

  private final Project project;
  private final Queries queries;
  private final Runnable onExit;
  private Runnable onActivity = () -> {};
  private final List<Worktree> worktrees = new CopyOnWriteArrayList<>();
  /** Buffers this session opened, so they can be released if the connection drops. */
  private final Map<String, Worktree> openDocs = new ConcurrentHashMap<>();
  /** Canonical worktree root -> the root as this client spells it (e.g. /var vs /private/var). */
  private final Map<Path, Path> clientRoots = new ConcurrentHashMap<>();

  private List<String> configuredClasspath = List.of();
  private String parameterHints = "literals";
  private boolean typeHints = true;
  private boolean deprecationHints = true;
  private ClientCapabilities capabilities;
  private LanguageClient client;
  private ScheduledFuture<?> polling;

  public JavaIndexServer(Project project, Runnable onExit) {
    this.project = project;
    this.queries = new Queries(project);
    this.onExit = onExit;
  }

  /** Called on every request, so the daemon knows when it is idle. */
  public void onActivity(Runnable listener) {
    this.onActivity = listener;
  }

  @Override
  public void connect(LanguageClient client) {
    this.client = client;
  }

  // ---------------------------------------------------------------------------------------------
  // Lifecycle

  @Override
  public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
    capabilities = params.getCapabilities();
    configuredClasspath = readClasspathOption(params.getInitializationOptions());
    parameterHints = readParameterHintsOption(params.getInitializationOptions());
    typeHints = readBoolOption(params.getInitializationOptions(), "inlayHints", "types", true);
    deprecationHints = readBoolOption(params.getInitializationOptions(), null, "deprecations", true);
    List<Path> roots = new ArrayList<>();
    if (params.getWorkspaceFolders() != null && !params.getWorkspaceFolders().isEmpty()) {
      for (WorkspaceFolder f : params.getWorkspaceFolders()) {
        roots.add(path(f.getUri()));
      }
    } else if (params.getRootUri() != null) {
      roots.add(path(params.getRootUri()));
    } else if (params.getRootPath() != null) {
      roots.add(Path.of(params.getRootPath()));
    }
    for (Path root : roots) {
      addWorktree(root);
    }

    ServerCapabilities caps = new ServerCapabilities();
    TextDocumentSyncOptions sync = new TextDocumentSyncOptions();
    sync.setOpenClose(true);
    sync.setChange(TextDocumentSyncKind.Incremental);
    caps.setTextDocumentSync(sync);
    caps.setDefinitionProvider(true);
    caps.setDeclarationProvider(true);
    caps.setReferencesProvider(true);
    caps.setDocumentHighlightProvider(true);
    caps.setHoverProvider(true);
    caps.setTypeDefinitionProvider(true);
    caps.setImplementationProvider(true);
    caps.setTypeHierarchyProvider(true);
    caps.setCallHierarchyProvider(true);
    caps.setSignatureHelpProvider(new SignatureHelpOptions(List.of("(", ",")));
    caps.setInlayHintProvider(true);
    caps.setCodeActionProvider(
        new CodeActionOptions(List.of(CodeActionKind.QuickFix, CodeActionKind.SourceOrganizeImports)));
    caps.setDocumentSymbolProvider(true);
    caps.setWorkspaceSymbolProvider(true);
    WorkspaceFoldersOptions folders = new WorkspaceFoldersOptions();
    folders.setSupported(true);
    folders.setChangeNotifications(true);
    caps.setWorkspace(new WorkspaceServerCapabilities(folders));
    return CompletableFuture.completedFuture(
        new InitializeResult(caps, new ServerInfo("java-index", "0.1.1")));
  }

  /** {@code inlayHints.parameterNames}: "literals" (default), "all" or "none". */
  /** A boolean option, optionally nested one level ({@code inlayHints.types}). */
  private static boolean readBoolOption(Object options, String group, String key, boolean fallback) {
    if (!(options instanceof JsonObject obj)) {
      return fallback;
    }
    JsonObject scope = group == null ? obj : obj.get(group) instanceof JsonObject g ? g : null;
    if (scope != null && scope.get(key) instanceof JsonElement e && e.isJsonPrimitive()) {
      return e.getAsBoolean();
    }
    return fallback;
  }

  private static String readParameterHintsOption(Object options) {
    if (options instanceof JsonObject obj
        && obj.get("inlayHints") instanceof JsonObject hints
        && hints.get("parameterNames") instanceof JsonElement mode
        && mode.isJsonPrimitive()) {
      return mode.getAsString();
    }
    return "literals";
  }

  private static List<String> readClasspathOption(Object options) {
    if (!(options instanceof JsonObject obj) || !obj.has("classpath")) {
      return List.of();
    }
    List<String> out = new ArrayList<>();
    JsonElement cp = obj.get("classpath");
    if (cp.isJsonArray()) {
      for (JsonElement e : (JsonArray) cp) {
        out.add(e.getAsString());
      }
    } else if (cp.isJsonPrimitive()) {
      out.addAll(List.of(cp.getAsString().split(java.io.File.pathSeparator)));
    }
    return out;
  }

  private void addWorktree(Path root) {
    try {
      Worktree w = project.open(root, configuredClasspath);
      worktrees.add(w);
      clientRoots.putIfAbsent(w.root(), root.toAbsolutePath().normalize());
      LOG.info("session attached to " + w.root());
    } catch (IOException e) {
      LOG.log(Level.WARNING, "cannot open worktree " + root, e);
    }
  }

  @Override
  public void initialized(InitializedParams params) {
    boolean canWatch =
        capabilities != null
            && capabilities.getWorkspace() != null
            && capabilities.getWorkspace().getDidChangeWatchedFiles() != null
            && Boolean.TRUE.equals(
                capabilities.getWorkspace().getDidChangeWatchedFiles().getDynamicRegistration());
    if (canWatch && client != null) {
      List<FileSystemWatcher> watchers = new ArrayList<>();
      for (String glob :
          List.of(
              "**/*.java",
              "**/pom.xml",
              "**/*.gradle",
              "**/*.gradle.kts",
              "**/libs.versions.toml",
              "**/application*.properties",
              "**/application*.yml",
              "**/application*.yaml",
              "**/bootstrap*.yml",
              "**/microprofile-config.properties",
              "**/.java-index/classpath")) {
        watchers.add(new FileSystemWatcher(Either.forLeft(glob)));
      }
      // Directories created, moved or deleted arrive as one event for the directory itself.
      watchers.add(
          new FileSystemWatcher(Either.forLeft("**/*"), WatchKind.Create | WatchKind.Delete));
      client.registerCapability(
          new RegistrationParams(
              List.of(
                  new Registration(
                      "java-index-watch",
                      "workspace/didChangeWatchedFiles",
                      new DidChangeWatchedFilesRegistrationOptions(watchers)))));
    }
    // Stat-based rescans: the only change detection for clients without file watching, and a
    // safety net (for missed or coalesced events) for those with it.
    long every = canWatch ? 300 : 20;
    polling =
        POLLER.scheduleWithFixedDelay(
            () -> worktrees.forEach(this::safeRescan), every, every, TimeUnit.SECONDS);
  }

  private void safeRescan(Worktree w) {
    try {
      w.rescan();
    } catch (RuntimeException e) {
      LOG.log(Level.FINE, "rescan failed", e);
    }
  }

  @Override
  public CompletableFuture<Object> shutdown() {
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public void exit() {
    onExit.run();
  }

  /** Releases everything this session holds; called when the connection ends. */
  public void dispose() {
    if (polling != null) {
      polling.cancel(false);
    }
    openDocs.forEach((uri, w) -> w.closeBuffer(w.rel(path(uri))));
    openDocs.clear();
    for (Worktree w : worktrees) {
      project.release(w);
    }
    worktrees.clear();
  }

  @Override
  public TextDocumentService getTextDocumentService() {
    return this;
  }

  @Override
  public WorkspaceService getWorkspaceService() {
    return this;
  }

  // ---------------------------------------------------------------------------------------------
  // Documents

  /** Finds (or attaches) the worktree containing {@code file}. */
  private Worktree worktreeFor(Path file) {
    Worktree best = null;
    for (Worktree w : worktrees) {
      if (w.rel(file) != null && (best == null || w.root().getNameCount() > best.root().getNameCount())) {
        best = w;
      }
    }
    if (best != null) {
      return best;
    }
    for (Worktree w : project.worktrees()) {
      if (w.rel(file) != null) {
        addWorktree(w.root());
        return w;
      }
    }
    return null;
  }

  @Override
  public void didOpen(DidOpenTextDocumentParams params) {
    String uri = params.getTextDocument().getUri();
    Path file = path(uri);
    Worktree w = worktreeFor(file);
    if (w != null) {
      w.openBuffer(w.rel(file), params.getTextDocument().getText());
      openDocs.put(uri, w);
      scheduleDeprecations(uri, 0);
    }
  }

  @Override
  public void didChange(DidChangeTextDocumentParams params) {
    String uri = params.getTextDocument().getUri();
    Worktree w = openDocs.get(uri);
    if (w == null) {
      return;
    }
    List<TextDocumentContentChangeEvent> changes = params.getContentChanges();
    w.changeBuffer(w.rel(path(uri)), text -> apply(text, changes));
    scheduleDeprecations(uri, 500);
  }

  static String apply(String text, List<TextDocumentContentChangeEvent> changes) {
    for (TextDocumentContentChangeEvent c : changes) {
      if (c.getRange() == null) {
        text = c.getText();
        continue;
      }
      Lines lines = Lines.of(text);
      int start = lines.offset(c.getRange().getStart().getLine(), c.getRange().getStart().getCharacter());
      int end = lines.offset(c.getRange().getEnd().getLine(), c.getRange().getEnd().getCharacter());
      text = text.substring(0, start) + c.getText() + text.substring(Math.max(start, end));
    }
    return text;
  }

  @Override
  public void didClose(DidCloseTextDocumentParams params) {
    String uri = params.getTextDocument().getUri();
    Worktree w = openDocs.remove(uri);
    if (w != null) {
      w.closeBuffer(w.rel(path(uri)));
    }
    ScheduledFuture<?> pending = deprecationChecks.remove(uri);
    if (pending != null) {
      pending.cancel(false);
    }
    if (client != null && deprecationHints) {
      client.publishDiagnostics(new PublishDiagnosticsParams(uri, List.of()));
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Deprecation hints: uses of @Deprecated APIs in open files, shown struck through

  private final Map<String, ScheduledFuture<?>> deprecationChecks = new ConcurrentHashMap<>();

  /** Re-checks a file after edits settle for {@code delayMillis}. */
  private void scheduleDeprecations(String uri, long delayMillis) {
    if (!deprecationHints || client == null) {
      return;
    }
    ScheduledFuture<?> next =
        POLLER.schedule(
            () -> project.background().execute(() -> publishDeprecations(uri)),
            delayMillis,
            TimeUnit.MILLISECONDS);
    ScheduledFuture<?> previous = deprecationChecks.put(uri, next);
    if (previous != null) {
      previous.cancel(false);
    }
  }

  private void publishDeprecations(String uri) {
    try {
      if (!openDocs.containsKey(uri)) {
        return;
      }
      long start = System.nanoTime();
      Doc doc = doc(uri);
      if (doc == null) {
        return;
      }
      Resolver resolver = new Resolver(doc.universe, doc.text, doc.summary, doc.file);
      Lines lines = Lines.of(doc.text);
      Map<String, String> notes = new java.util.HashMap<>();
      List<Diagnostic> diagnostics = new ArrayList<>();
      for (Resolver.Use use : resolver.resolveAll(0, doc.text.length(), resolver::isApiUse)) {
        Symbol s = use.symbol();
        if (!resolver.isDeprecated(s)) {
          continue;
        }
        String what =
            s.kind() == Symbol.Kind.CLASS || s.owner() == null
                ? TypeRef.qualifiedName(s.owner() == null ? s.name() : s.owner())
                : TypeRef.simpleName(s.owner()) + "." + s.name();
        String note =
            notes.computeIfAbsent(
                what,
                k -> {
                  Target target = s.hit() != null ? s.hit().member().target() : s.target();
                  String why = deprecationNote(target == null ? null : queries.javadoc(target, doc.universe));
                  return why == null ? "" : ": " + why;
                });
        Diagnostic d =
            new Diagnostic(
                Symbols.range(lines, use.start(), use.end()),
                "`" + what + "` is deprecated" + note,
                DiagnosticSeverity.Hint,
                "java-index");
        d.setTags(List.of(DiagnosticTag.Deprecated));
        diagnostics.add(d);
      }
      if (openDocs.containsKey(uri)) {
        client.publishDiagnostics(new PublishDiagnosticsParams(uri, diagnostics));
      }
      LOG.fine(() -> "deprecations for " + uri + ": " + diagnostics.size() + " in "
          + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) + " ms");
    } catch (RuntimeException e) {
      LOG.log(Level.FINE, "deprecation check failed for " + uri, e);
    }
  }

  @Override
  public void didSave(DidSaveTextDocumentParams params) {}

  /** The text, summary and resolution universe for a document. */
  /** {@code file} is canonical (worktree root + rel) so targets compare equal across files. */
  private record Doc(String text, FileSummary summary, Universe universe, Path file, Worktree worktree) {}

  private Doc doc(String uri) {
    if (!uri.endsWith(".java")) {
      return null; // config files are handled by the config queries
    }
    Path file = path(uri);
    Worktree w = worktreeFor(file);
    if (w != null) {
      String rel = w.rel(file);
      w.awaitClasspath(2000);
      String text = w.text(rel);
      FileSummary summary = w.summary(rel);
      return text == null || summary == null
          ? null
          : new Doc(text, summary, w.universe(), w.root().resolve(rel), w);
    }
    // A file outside every worktree, e.g. a library source we materialized: resolve it against
    // this session's worktree so its references lead back into the same dependencies.
    if (worktrees.isEmpty()) {
      return null;
    }
    try {
      String text = Files.readString(file);
      SourceLocator locator = project.locator();
      FileSummary summary =
          locator.owns(file)
              ? locator.summary(file)
              : Summarizer.summarize(Summarizer.hash(text), file.toString(), text);
      return summary == null
          ? null
          : new Doc(text, summary, worktrees.get(0).universe(), file, worktrees.get(0));
    } catch (IOException e) {
      return null;
    }
  }

  @Override
  public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(
      DefinitionParams params) {
    return async(() -> Either.forLeft(locate(params)));
  }

  @Override
  public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>>
      declaration(DeclarationParams params) {
    return async(() -> Either.forLeft(locate(params)));
  }

  private List<Location> locate(TextDocumentPositionParams params) {
    ConfigHit config = configKeyAt(params.getTextDocument().getUri(), params.getPosition());
    if (config != null) {
      return configDefinition(config, path(params.getTextDocument().getUri()));
    }
    Doc doc = doc(params.getTextDocument().getUri());
    if (doc == null) {
      return List.of();
    }
    Position pos = params.getPosition();
    int offset = Lines.of(doc.text).offset(pos.getLine(), pos.getCharacter());
    List<Location> out = new ArrayList<>();
    for (Target target : new Resolver(doc.universe, doc.text, doc.summary, doc.file).definition(offset)) {
      Location loc = location(target, doc.universe);
      if (loc != null) {
        out.add(loc);
      }
    }
    return out;
  }

  private Location location(Target target, Universe universe) {
    Target.Source source =
        switch (target) {
          case Target.Source s -> s;
          case Target.Binary b -> project.locator().locate(b, universe);
        };
    if (source == null) {
      return null;
    }
    return new Location(
        uri(source.file()), Symbols.range(source.lines(), source.start(), source.end()));
  }

  /** A URI for {@code file} using this client's spelling of the worktree root. */
  private String uri(Path file) {
    for (Map.Entry<Path, Path> e : clientRoots.entrySet()) {
      if (file.startsWith(e.getKey())) {
        return e.getValue().resolve(e.getKey().relativize(file)).toUri().toString();
      }
    }
    return file.toUri().toString();
  }

  // ---------------------------------------------------------------------------------------------
  // Configuration keys: application.properties/yml <-> @Value, @ConfigProperty, ...

  /** A configuration key under the cursor, in a config file or in Java. */
  private record ConfigHit(String key, boolean inConfigFile, Worktree worktree) {}

  private ConfigHit configKeyAt(String uri, Position pos) {
    Path file = path(uri);
    Worktree w = worktreeFor(file);
    String rel = w == null ? null : w.rel(file);
    if (rel == null) {
      return null;
    }
    boolean configFile = ConfigKeys.isConfigFile(rel);
    if (!configFile && !rel.endsWith(".java")) {
      return null;
    }
    String text = w.text(rel);
    if (text == null) {
      return null;
    }
    int offset = Lines.of(text).offset(pos.getLine(), pos.getCharacter());
    if (configFile) {
      ConfigFile.Entry e = project.configFile(rel, text).entryAt(offset);
      return e == null ? null : new ConfigHit(e.key(), true, w);
    }
    // A key written in Java: ${...} in @Value, @ConfigProperty(name=...), getProperty("...")
    if (!text.contains("\"")) {
      return null;
    }
    for (FileSummary.ConfigRef r : ConfigRefScanner.scan(Tokens.lex(text))) {
      if (r.start() <= offset && offset <= r.end() && r.kind() != FileSummary.ConfigRef.Kind.RENAME) {
        return new ConfigHit(r.key(), false, w);
      }
    }
    // A member of a @ConfigurationProperties class / @ConfigMapping interface
    ConfigIndex.Binding b = w.configIndex().bindingAt(w.root().resolve(rel), offset);
    if (b != null && b.kind() == ConfigIndex.Binding.Kind.PROPERTY && !b.key().endsWith(".*")) {
      return new ConfigHit(b.key(), false, w);
    }
    return null;
  }

  /**
   * From Java: where the key is set (every profile). From a config file: the Java using it.
   * In multi-module repositories each module is usually its own application, so results from
   * the same module (the directory above {@code src/}) win when there are any.
   */
  private List<Location> configDefinition(ConfigHit hit, Path from) {
    Worktree w = hit.worktree();
    ConfigIndex index = w.configIndex();
    List<Location> out = new ArrayList<>();
    String rel = w.rel(from);
    String module = module(rel == null ? from : w.root().resolve(rel)); // canonical, like the index
    if (!hit.inConfigFile()) {
      List<ConfigIndex.Definition> defs = index.definitions(hit.key());
      List<ConfigIndex.Definition> local = defs.stream().filter(d -> module(d.file()).equals(module)).toList();
      for (ConfigIndex.Definition d : local.isEmpty() ? defs : local) {
        out.add(configLocation(d));
      }
    } else {
      List<ConfigIndex.Binding> uses = index.bindings(hit.key());
      List<ConfigIndex.Binding> local = uses.stream().filter(b -> module(b.file()).equals(module)).toList();
      for (ConfigIndex.Binding b : local.isEmpty() ? uses : local) {
        out.add(new Location(uri(b.file()), Symbols.range(b.lines(), b.start(), b.end())));
      }
    }
    if (out.isEmpty()) {
      // Not in the project: the library that defines the key (e.g. server.port -> ServerProperties)
      Universe universe = w.universe();
      Target lib = ConfigReport.libraryTarget(index.library().lookup(ConfigKeys.canonical(hit.key())), universe);
      Location loc = lib == null ? null : location(lib, universe);
      if (loc != null) {
        out.add(loc);
      }
    }
    return out;
  }

  /** Every definition and every use of a key, from either side. */
  private List<Location> configReferences(ConfigHit hit) {
    ConfigIndex index = hit.worktree().configIndex();
    List<Location> out = new ArrayList<>();
    for (ConfigIndex.Definition d : index.definitions(hit.key())) {
      out.add(configLocation(d));
    }
    for (ConfigIndex.Binding b : index.bindings(hit.key())) {
      out.add(new Location(uri(b.file()), Symbols.range(b.lines(), b.start(), b.end())));
    }
    return out;
  }

  private Location configLocation(ConfigIndex.Definition d) {
    return new Location(
        uri(d.file()), Symbols.range(d.lines(), d.entry().keyStart(), d.entry().keyEnd()));
  }

  /** The build module of a file: everything before its {@code /src/} directory. */
  private static String module(Path file) {
    String p = file.toAbsolutePath().normalize().toString().replace('\\', '/');
    int src = p.lastIndexOf("/src/");
    return src < 0 ? "" : p.substring(0, src);
  }

  private static String relOrPath(Worktree w, Path file) {
    String rel = w.rel(file);
    return rel != null ? rel : file.toString();
  }

  /** References to the symbol at {@code offset} (resolved first, then searched by name). */
  private List<Ref> searchReferences(Doc doc, int offset) {
    Resolver here = new Resolver(doc.universe, doc.text, doc.summary, doc.file);
    String name = here.nameAt(offset);
    List<Target> resolved = here.definition(offset);
    if (name == null || resolved.isEmpty()) {
      return List.of();
    }
    return queries.references(doc.worktree, doc.universe, resolved, name).matches();
  }

  @Override
  public CompletableFuture<List<? extends Location>> references(ReferenceParams params) {
    return async(
        () -> {
          ConfigHit config = configKeyAt(params.getTextDocument().getUri(), params.getPosition());
          if (config != null) {
            return configReferences(config);
          }
          Doc doc = doc(params.getTextDocument().getUri());
          if (doc == null) {
            return List.of();
          }
          boolean includeDeclaration =
              params.getContext() == null || params.getContext().isIncludeDeclaration();
          List<Location> out = new ArrayList<>();
          for (Ref r : searchReferences(doc, offset(doc, params.getPosition()))) {
            if (includeDeclaration || !r.declaration()) {
              out.add(new Location(uri(r.file()), Symbols.range(r.lines(), r.start(), r.end())));
            }
          }
          return out;
        });
  }

  private static int offset(Doc doc, Position pos) {
    return Lines.of(doc.text).offset(pos.getLine(), pos.getCharacter());
  }

  private Symbol symbolAt(Doc doc, Position pos) {
    return new Resolver(doc.universe, doc.text, doc.summary, doc.file).symbolAt(offset(doc, pos));
  }

  // ---------------------------------------------------------------------------------------------
  // Highlight, hover, type definition

  @Override
  public CompletableFuture<List<? extends DocumentHighlight>> documentHighlight(
      DocumentHighlightParams params) {
    return async(
        () -> {
          Doc doc = doc(params.getTextDocument().getUri());
          if (doc == null) {
            return List.of();
          }
          Resolver here = new Resolver(doc.universe, doc.text, doc.summary, doc.file);
          int offset = offset(doc, params.getPosition());
          String name = here.nameAt(offset);
          Symbol s = here.symbolAt(offset);
          if (name == null || s == null || s.target() == null) {
            return List.of();
          }
          Lines lines = Lines.of(doc.text);
          List<DocumentHighlight> out = new ArrayList<>();
          for (Resolver.Occurrence o : here.occurrences(name)) {
            if (o.targets().stream().anyMatch(t -> Target.same(t, s.target()))) {
              boolean write =
                  (s.target() instanceof Target.Source src && src.file().equals(doc.file) && src.start() == o.start())
                      || isAssignment(doc.text, o.end());
              out.add(
                  new DocumentHighlight(
                      Symbols.range(lines, o.start(), o.end()),
                      write ? DocumentHighlightKind.Write : DocumentHighlightKind.Read));
            }
          }
          return out;
        });
  }

  private static boolean isAssignment(String text, int after) {
    int i = after;
    while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
      i++;
    }
    if (i >= text.length()) {
      return false;
    }
    char c = text.charAt(i);
    char next = i + 1 < text.length() ? text.charAt(i + 1) : ' ';
    return (c == '=' && next != '=')
        || ("+-*/%&|^".indexOf(c) >= 0 && next == '=')
        || (c == '+' && next == '+')
        || (c == '-' && next == '-');
  }

  @Override
  public CompletableFuture<Hover> hover(HoverParams params) {
    return async(
        () -> {
          ConfigHit config = configKeyAt(params.getTextDocument().getUri(), params.getPosition());
          if (config != null) {
            Worktree cw = config.worktree();
            String md =
                ConfigReport.describe(cw.configIndex(), config.key(), f -> relOrPath(cw, f), true);
            return new Hover(new MarkupContent(MarkupKind.MARKDOWN, md));
          }
          Doc doc = doc(params.getTextDocument().getUri());
          if (doc == null) {
            return null;
          }
          Symbol s = symbolAt(doc, params.getPosition());
          if (s == null) {
            return null;
          }
          List<String> names = s.hit() == null ? List.of() : queries.paramNames(s.hit(), doc.universe);
          String javadoc =
              s.kind() == Symbol.Kind.LOCAL || s.kind() == Symbol.Kind.PACKAGE
                  ? null
                  : queries.javadoc(s.target(), doc.universe);
          StringBuilder md = new StringBuilder();
          Resolver resolver = new Resolver(doc.universe, doc.text, doc.summary, doc.file);
          if (resolver.isDeprecated(s)) {
            String why = deprecationNote(javadoc);
            md.append("**Deprecated**").append(why == null ? "" : ": " + why).append("\n\n");
          }
          md.append("```java\n").append(Describe.signature(s, doc.universe, names)).append("\n```");
          if (s.owner() != null && s.kind() != Symbol.Kind.CLASS && s.kind() != Symbol.Kind.PACKAGE) {
            md.append("\n\n*in* `").append(TypeRef.qualifiedName(s.owner())).append('`');
          }
          List<String> facts = hoverFacts(doc, s);
          if (!facts.isEmpty()) {
            md.append("\n\n").append(String.join(" · ", facts));
          }
          if (javadoc != null) {
            md.append("\n\n---\n\n").append(javadoc);
          }
          return new Hover(new MarkupContent(MarkupKind.MARKDOWN, md.toString()));
        });
  }

  private static final int MAX_HOVER_COUNT_FILES = 300;

  /**
   * What a reviewer wants to know at a glance: what a method overrides, where it is overridden,
   * how many subtypes a type has, and how often a symbol is used.
   */
  private List<String> hoverFacts(Doc doc, Symbol s) {
    List<String> facts = new ArrayList<>();
    Worktree w = doc.worktree;
    try {
      if (s.kind() == Symbol.Kind.METHOD && s.hit() != null) {
        Universe.Hit sup = Queries.overridden(doc.universe, s.hit());
        if (sup != null) {
          ClassModel owner = doc.universe.find(sup.owner());
          boolean implement = sup.member().isAbstract() || (owner != null && owner.kind() == com.google.turbine.model.TurbineTyKind.INTERFACE);
          facts.add((implement ? "Implements `" : "Overrides `") + TypeRef.simpleName(sup.owner()) + "." + sup.member().name() + "`");
        }
        int overrides = 0;
        for (TypeHandle h : Queries.allSubtypes(w, s.owner())) {
          for (MethodDecl m : h.type().methods) {
            if (m.name().equals(s.name()) && m.paramTypes().size() == s.hit().member().arity()) {
              overrides++;
            }
          }
        }
        if (overrides > 0) {
          facts.add("Overridden in " + overrides + (overrides == 1 ? " subtype" : " subtypes"));
        }
      }
      if (s.kind() == Symbol.Kind.CLASS && s.owner() != null) {
        int subtypes = Queries.allSubtypes(w, s.owner()).size();
        if (subtypes > 0) {
          ClassModel m = doc.universe.find(s.owner());
          boolean iface = m != null && m.kind() == com.google.turbine.model.TurbineTyKind.INTERFACE;
          facts.add((iface ? "Implemented by " : "Extended by ") + subtypes + (subtypes == 1 ? " type" : " types"));
        }
      }
      if (s.target() != null && s.kind() != Symbol.Kind.LOCAL && s.kind() != Symbol.Kind.PACKAGE) {
        String name = s.kind() == Symbol.Kind.CONSTRUCTOR || s.kind() == Symbol.Kind.CLASS
            ? TypeRef.simpleName(s.owner()).replaceAll(".*\\.", "")
            : s.name();
        List<Target> targets = s.alternate() == null ? List.of(s.target()) : List.of(s.target(), s.alternate());
        int uses = queries.countUsages(w, doc.universe, targets, name, MAX_HOVER_COUNT_FILES);
        facts.add(uses < 0 ? "Used widely (Find References for all)" : uses + (uses == 1 ? " usage" : " usages"));
      }
    } catch (RuntimeException e) {
      LOG.log(Level.FINE, "hover facts failed", e);
    }
    return facts;
  }

  /** The text of a javadoc {@code @deprecated} tag, e.g. "use {@link #bar()} instead". */
  private static String deprecationNote(String javadoc) {
    if (javadoc == null) {
      return null;
    }
    for (String line : javadoc.split("\n")) {
      if (line.startsWith("- *@deprecated*")) {
        String note = line.substring("- *@deprecated*".length()).strip();
        return note.isEmpty() ? null : note;
      }
    }
    return null;
  }

  @Override
  public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>>
      typeDefinition(TypeDefinitionParams params) {
    return async(
        () -> {
          Doc doc = doc(params.getTextDocument().getUri());
          Symbol s = doc == null ? null : symbolAt(doc, params.getPosition());
          if (s == null) {
            return Either.forLeft(List.of());
          }
          if (s.kind() == Symbol.Kind.CLASS) {
            Location loc = s.target() == null ? null : location(s.target(), doc.universe);
            return Either.forLeft(loc == null ? List.of() : List.of(loc));
          }
          TypeRef type = s.type();
          ClassModel m = type == null || type.cls() == null ? null : doc.universe.find(type.cls());
          Location loc = m == null ? null : location(m.target(), doc.universe);
          return Either.forLeft(loc == null ? List.of() : List.of(loc));
        });
  }

  // ---------------------------------------------------------------------------------------------
  // Implementations and type hierarchy

  @Override
  public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>>
      implementation(ImplementationParams params) {
    return async(
        () -> {
          Doc doc = doc(params.getTextDocument().getUri());
          Symbol s = doc == null ? null : symbolAt(doc, params.getPosition());
          if (s == null || s.owner() == null) {
            return Either.forLeft(List.of());
          }
          List<Location> out = new ArrayList<>();
          for (TypeHandle h : Queries.allSubtypes(doc.worktree, s.owner())) {
            if (s.kind() == Symbol.Kind.CLASS) {
              out.add(location(h, h.type().span, doc.universe));
            } else if (s.kind() == Symbol.Kind.METHOD && s.hit() != null) {
              for (MethodDecl m : h.type().methods) {
                if (m.name().equals(s.name()) && m.paramTypes().size() == s.hit().member().arity()) {
                  out.add(location(h, m.span(), doc.universe));
                }
              }
            }
          }
          if (s.kind() == Symbol.Kind.CLASS) {
            for (Queries.AnonymousType a : Queries.anonymousSubtypes(doc.worktree, doc.universe, s.owner())) {
              out.add(location(a));
            }
          }
          return Either.forLeft(out);
        });
  }

  private Location location(Queries.AnonymousType a) {
    return new Location(uri(a.file()), Symbols.range(a.summary().lines, a.start(), a.end()));
  }

  private Location location(TypeHandle h, FileSummary.Span span, Universe universe) {
    return new Location(
        uri(universe.path(h.rel())), Symbols.range(h.file().lines, span.nameStart(), span.nameEnd()));
  }

  @Override
  public CompletableFuture<List<TypeHierarchyItem>> prepareTypeHierarchy(
      TypeHierarchyPrepareParams params) {
    return async(
        () -> {
          Doc doc = doc(params.getTextDocument().getUri());
          Symbol s = doc == null ? null : symbolAt(doc, params.getPosition());
          if (s == null) {
            return List.of();
          }
          String bin =
              s.kind() == Symbol.Kind.LOCAL
                  ? (s.type() == null ? null : s.type().cls())
                  : s.owner();
          TypeHierarchyItem item = bin == null ? null : typeItem(bin, doc.universe);
          return item == null ? List.of() : List.of(item);
        });
  }

  @Override
  public CompletableFuture<List<TypeHierarchyItem>> typeHierarchySupertypes(
      TypeHierarchySupertypesParams params) {
    return async(
        () -> {
          Universe universe = universeFor(params.getItem().getUri());
          String bin = itemData(params.getItem().getData());
          ClassModel m = universe == null || bin == null ? null : universe.find(bin);
          List<TypeHierarchyItem> out = new ArrayList<>();
          if (m != null) {
            for (TypeRef sup : m.supertypes()) {
              TypeHierarchyItem item = sup.cls() == null ? null : typeItem(sup.cls(), universe);
              if (item != null) {
                out.add(item);
              }
            }
          }
          return out;
        });
  }

  @Override
  public CompletableFuture<List<TypeHierarchyItem>> typeHierarchySubtypes(
      TypeHierarchySubtypesParams params) {
    return async(
        () -> {
          Path file = path(params.getItem().getUri());
          Worktree w = worktreeFor(file);
          if (w == null && !worktrees.isEmpty()) {
            w = worktrees.get(0);
          }
          String bin = itemData(params.getItem().getData());
          if (w == null || bin == null) {
            return List.of();
          }
          Universe universe = w.universe();
          List<TypeHierarchyItem> out = new ArrayList<>();
          for (TypeHandle h : w.subtypes().getOrDefault(bin, List.of())) {
            TypeHierarchyItem item = typeItem(h.type().binaryName, universe);
            if (item != null) {
              out.add(item);
            }
          }
          for (Queries.AnonymousType a : Queries.anonymousSubtypes(w, universe, bin)) {
            if (a.supertype().equals(bin)) {
              Location loc = location(a);
              // No data: an anonymous class has no subtypes of its own to expand.
              out.add(
                  new TypeHierarchyItem(
                      "new " + TypeRef.simpleName(bin) + "() {…}",
                      SymbolKind.Class,
                      loc.getUri(),
                      loc.getRange(),
                      loc.getRange(),
                      container(bin)));
            }
          }
          return out;
        });
  }

  private TypeHierarchyItem typeItem(String binaryName, Universe universe) {
    ClassModel m = universe.find(binaryName);
    Location loc = m == null ? null : location(m.target(), universe);
    if (loc == null) {
      return null;
    }
    TypeHierarchyItem item =
        new TypeHierarchyItem(
            TypeRef.simpleName(binaryName),
            Symbols.kind(m.kind()),
            loc.getUri(),
            loc.getRange(),
            loc.getRange(),
            container(binaryName));
    item.setData(binaryName);
    return item;
  }

  private static String itemData(Object data) {
    if (data instanceof JsonElement e && e.isJsonPrimitive()) {
      return e.getAsString();
    }
    return data instanceof String str ? str : null;
  }

  private Universe universeFor(String uri) {
    Worktree w = worktreeFor(path(uri));
    if (w == null && !worktrees.isEmpty()) {
      w = worktrees.get(0);
    }
    return w == null ? null : w.universe();
  }

  // ---------------------------------------------------------------------------------------------
  // Call hierarchy

  @Override
  public CompletableFuture<List<CallHierarchyItem>> prepareCallHierarchy(
      CallHierarchyPrepareParams params) {
    return async(
        () -> {
          Doc doc = doc(params.getTextDocument().getUri());
          Symbol s = doc == null ? null : symbolAt(doc, params.getPosition());
          if (s == null || (s.kind() != Symbol.Kind.METHOD && s.kind() != Symbol.Kind.CONSTRUCTOR)) {
            return List.of();
          }
          CallHierarchyItem item = callItem(s, doc.universe);
          return item == null ? List.of() : List.of(item);
        });
  }

  @Override
  public CompletableFuture<List<CallHierarchyIncomingCall>> callHierarchyIncomingCalls(
      CallHierarchyIncomingCallsParams params) {
    return async(
        () -> {
          CallHierarchyItem target = params.getItem();
          Doc doc = doc(target.getUri());
          if (doc == null) {
            return List.of();
          }
          Map<String, CallHierarchyIncomingCall> byCaller = new java.util.LinkedHashMap<>();
          for (Ref r : searchReferences(doc, offset(doc, target.getSelectionRange().getStart()))) {
            if (r.declaration()) {
              continue;
            }
            CallHierarchyItem caller = enclosingItem(r);
            if (caller == null) {
              continue;
            }
            String key = caller.getUri() + "#" + caller.getSelectionRange().getStart();
            byCaller
                .computeIfAbsent(key, k -> new CallHierarchyIncomingCall(caller, new ArrayList<>()))
                .getFromRanges()
                .add(Symbols.range(r.lines(), r.start(), r.end()));
          }
          return new ArrayList<>(byCaller.values());
        });
  }

  /** The method (or, outside methods, the type) whose declaration encloses a reference. */
  private CallHierarchyItem enclosingItem(Ref r) {
    Queries.Enclosing e = Queries.enclosing(r);
    if (e == null) {
      return null;
    }
    String uri = uri(r.file());
    FileSummary.Span span = e.span();
    if (e.method() != null) {
      String name = (e.method().isConstructor() ? e.type().simpleName : e.method().name()) + "()";
      return new CallHierarchyItem(
          name,
          e.method().isConstructor() ? SymbolKind.Constructor : SymbolKind.Method,
          uri,
          Symbols.range(r.summary().lines, span.start(), span.end()),
          Symbols.range(r.summary().lines, span.nameStart(), span.nameEnd()));
    }
    return new CallHierarchyItem(
        e.type().simpleName,
        Symbols.kind(e.type().kind),
        uri,
        Symbols.range(r.summary().lines, span.start(), span.end()),
        Symbols.range(r.summary().lines, span.nameStart(), span.nameEnd()));
  }

  @Override
  public CompletableFuture<List<CallHierarchyOutgoingCall>> callHierarchyOutgoingCalls(
      CallHierarchyOutgoingCallsParams params) {
    return async(
        () -> {
          CallHierarchyItem source = params.getItem();
          Doc doc = doc(source.getUri());
          if (doc == null) {
            return List.of();
          }
          int nameStart = offset(doc, source.getSelectionRange().getStart());
          FileSummary.Span body = null;
          for (TypeInfo type : doc.summary.allTypes()) {
            for (MethodDecl m : type.methods) {
              if (m.span().nameStart() == nameStart) {
                body = m.span();
              }
            }
          }
          if (body == null) {
            return List.of();
          }
          Lines lines = Lines.of(doc.text);
          Map<String, CallHierarchyOutgoingCall> byCallee = new java.util.LinkedHashMap<>();
          for (Resolver.Call call :
              new Resolver(doc.universe, doc.text, doc.summary, doc.file).callsIn(body.nameEnd(), body.end())) {
            CallHierarchyItem callee = callItem(call.callee(), doc.universe);
            if (callee == null) {
              continue;
            }
            String key = callee.getUri() + "#" + callee.getSelectionRange().getStart();
            byCallee
                .computeIfAbsent(key, k -> new CallHierarchyOutgoingCall(callee, new ArrayList<>()))
                .getFromRanges()
                .add(Symbols.range(lines, call.start(), call.end()));
          }
          return new ArrayList<>(byCallee.values());
        });
  }

  private CallHierarchyItem callItem(Symbol s, Universe universe) {
    Location loc = s.target() == null ? null : location(s.target(), universe);
    if (loc == null) {
      return null;
    }
    String name =
        switch (s.kind()) {
          case CONSTRUCTOR, CLASS -> s.owner() == null ? s.name() : TypeRef.simpleName(s.owner());
          default -> s.name();
        };
    SymbolKind kind =
        switch (s.kind()) {
          case CONSTRUCTOR -> SymbolKind.Constructor;
          case CLASS -> SymbolKind.Class;
          default -> SymbolKind.Method;
        };
    CallHierarchyItem item =
        new CallHierarchyItem(name + "()", kind, loc.getUri(), loc.getRange(), loc.getRange());
    if (s.owner() != null) {
      item.setDetail(TypeRef.qualifiedName(s.owner()));
    }
    return item;
  }

  // ---------------------------------------------------------------------------------------------
  // Signature help and inlay hints

  @Override
  public CompletableFuture<SignatureHelp> signatureHelp(SignatureHelpParams params) {
    return async(
        () -> {
          Doc doc = doc(params.getTextDocument().getUri());
          if (doc == null) {
            return null;
          }
          Resolver.Signature sig =
              new Resolver(doc.universe, doc.text, doc.summary, doc.file)
                  .signatureAt(offset(doc, params.getPosition()));
          if (sig == null) {
            return null;
          }
          List<SignatureInformation> signatures = new ArrayList<>();
          for (int i = 0; i < sig.overloads().size(); i++) {
            Universe.Hit h = sig.overloads().get(i);
            List<String> params2 = Describe.params(h, queries.paramNames(h, doc.universe));
            String name =
                h.member().kind() == Member.Kind.CONSTRUCTOR
                    ? TypeRef.simpleName(h.owner())
                    : h.member().name();
            SignatureInformation info =
                new SignatureInformation(name + "(" + String.join(", ", params2) + ")");
            List<ParameterInformation> paramInfos = new ArrayList<>();
            for (String p : params2) {
              paramInfos.add(new ParameterInformation(p));
            }
            info.setParameters(paramInfos);
            if (i == sig.activeSignature()) {
              String javadoc = queries.javadoc(h.member().target(), doc.universe);
              if (javadoc != null) {
                info.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN, javadoc));
              }
            }
            signatures.add(info);
          }
          return new SignatureHelp(signatures, sig.activeSignature(), sig.activeParameter());
        });
  }

  @Override
  public CompletableFuture<List<InlayHint>> inlayHint(InlayHintParams params) {
    return async(
        () -> {
          Doc doc = doc(params.getTextDocument().getUri());
          if (doc == null || ("none".equals(parameterHints) && !typeHints)) {
            return List.of();
          }
          Lines lines = Lines.of(doc.text);
          int from = lines.offset(params.getRange().getStart().getLine(), params.getRange().getStart().getCharacter());
          int to = lines.offset(params.getRange().getEnd().getLine(), params.getRange().getEnd().getCharacter());
          List<InlayHint> out = new ArrayList<>();
          for (Resolver.Hint h :
              new Resolver(doc.universe, doc.text, doc.summary, doc.file)
                  .inlayHints(
                      from,
                      to,
                      hit -> "none".equals(parameterHints) ? List.of() : queries.paramNames(hit, doc.universe),
                      "all".equals(parameterHints),
                      typeHints)) {
            InlayHint hint =
                new InlayHint(
                    new Position(lines.line(h.offset()), lines.column(h.offset())),
                    Either.forLeft(h.label()));
            hint.setKind(h.parameter() ? InlayHintKind.Parameter : InlayHintKind.Type);
            if (h.parameter()) {
              hint.setPaddingRight(true);
            } else if (!h.label().startsWith(":")) {
              hint.setPaddingLeft(true); // a chain hint at the end of a line
            }
            out.add(hint);
          }
          return out;
        });
  }

  // ---------------------------------------------------------------------------------------------
  // Code actions: organize (remove unused) imports, add a missing import

  @Override
  public CompletableFuture<List<Either<Command, CodeAction>>> codeAction(CodeActionParams params) {
    return async(
        () -> {
          String uri = params.getTextDocument().getUri();
          Doc doc = doc(uri);
          if (doc == null) {
            return List.of();
          }
          List<String> only = params.getContext() == null ? null : params.getContext().getOnly();
          Lines lines = Lines.of(doc.text);
          Imports imports = new Imports(doc.text);
          List<Either<Command, CodeAction>> out = new ArrayList<>();

          if (wants(only, CodeActionKind.QuickFix)) {
            int offset = offset(doc, params.getRange().getStart());
            for (String qualified : importCandidates(doc, imports, offset)) {
              String line = "import " + qualified + ";\n";
              int at = imports.insertionOffset();
              String text = imports.hasImports() ? line : "\n" + line;
              CodeAction action = new CodeAction("Import '" + qualified + "'");
              action.setKind(CodeActionKind.QuickFix);
              action.setEdit(
                  new WorkspaceEdit(
                      Map.of(uri, List.of(new TextEdit(Symbols.range(lines, at, at), text)))));
              out.add(Either.forRight(action));
            }
          }
          if (wants(only, CodeActionKind.SourceOrganizeImports)) {
            List<Imports.Decl> unused = imports.unused();
            if (!unused.isEmpty()) {
              List<TextEdit> edits = new ArrayList<>();
              for (Imports.Decl d : unused) {
                edits.add(new TextEdit(Symbols.range(lines, d.start(), d.end()), ""));
              }
              CodeAction action =
                  new CodeAction(
                      unused.size() == 1
                          ? "Remove unused import '" + unused.get(0).qualifiedName() + "'"
                          : "Remove " + unused.size() + " unused imports");
              action.setKind(CodeActionKind.SourceOrganizeImports);
              action.setEdit(new WorkspaceEdit(Map.of(uri, edits)));
              out.add(Either.forRight(action));
            }
          }
          return out;
        });
  }

  private static boolean wants(List<String> only, String kind) {
    return only == null
        || only.isEmpty()
        || only.stream().anyMatch(o -> kind.equals(o) || kind.startsWith(o + "."));
  }

  /** Classes named like the unresolved identifier at {@code offset}, best candidates first. */
  private List<String> importCandidates(Doc doc, Imports imports, int offset) {
    int start = offset;
    while (start > 0 && Character.isJavaIdentifierPart(doc.text.charAt(start - 1))) {
      start--;
    }
    int end = offset;
    while (end < doc.text.length() && Character.isJavaIdentifierPart(doc.text.charAt(end))) {
      end++;
    }
    if (start >= end || !Character.isUpperCase(doc.text.charAt(start))) {
      return List.of();
    }
    int before = start - 1;
    while (before >= 0 && Character.isWhitespace(doc.text.charAt(before))) {
      before--;
    }
    if (before >= 0 && doc.text.charAt(before) == '.') {
      return List.of(); // qualified: not an import problem
    }
    String name = doc.text.substring(start, end);
    if (imports.importsSimpleName(name)) {
      return List.of();
    }
    Resolver here = new Resolver(doc.universe, doc.text, doc.summary, doc.file);
    if (here.symbolAt(start) != null) {
      return List.of(); // already resolves
    }
    String ownPkg = doc.summary.pkg;
    java.util.Set<String> found = new java.util.LinkedHashSet<>();
    doc.worktree.index().forEachType(
        h -> {
          if (h.type().simpleName.equals(name)) {
            found.add(h.type().binaryName);
          }
        });
    List<String> libs = new ArrayList<>();
    for (var deps : List.of(doc.universe.deps(), doc.universe.jdk())) {
      for (String bin : deps.classNames()) {
        if (bin.endsWith("/" + name) || bin.endsWith("$" + name)) {
          libs.add(bin);
        }
      }
    }
    libs.sort(Comparator.comparingInt((String b) -> b.startsWith("java/") ? 0 : b.startsWith("javax/") ? 1 : 2).thenComparing(b -> b));
    found.addAll(libs);
    List<String> out = new ArrayList<>();
    for (String bin : found) {
      String pkg = bin.lastIndexOf('/') < 0 ? "" : bin.substring(0, bin.lastIndexOf('/'));
      String qualified = TypeRef.qualifiedName(bin);
      if (pkg.equals(ownPkg)
          || pkg.equals("java/lang") && !bin.contains("$")
          || pkg.startsWith("jdk/internal")
          || pkg.startsWith("sun/")
          || pkg.startsWith("com/sun/")
          || imports.covers(qualified)) {
        continue;
      }
      out.add(qualified);
      if (out.size() >= 8) {
        break;
      }
    }
    return out;
  }

  @Override
  public CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> documentSymbol(
      DocumentSymbolParams params) {
    return async(
        () -> {
          String uri = params.getTextDocument().getUri();
          Doc doc = doc(uri);
          if (doc == null) {
            return List.of();
          }
          List<DocumentSymbol> outline = Symbols.outline(doc.summary);
          List<Either<SymbolInformation, DocumentSymbol>> out = new ArrayList<>();
          if (hierarchicalSymbols()) {
            outline.forEach(s -> out.add(Either.forRight(s)));
          } else {
            flatten(outline, uri, null, out);
          }
          return out;
        });
  }

  private boolean hierarchicalSymbols() {
    return capabilities != null
        && capabilities.getTextDocument() != null
        && capabilities.getTextDocument().getDocumentSymbol() != null
        && Boolean.TRUE.equals(
            capabilities.getTextDocument().getDocumentSymbol().getHierarchicalDocumentSymbolSupport());
  }

  @SuppressWarnings("deprecation")
  private static void flatten(
      List<DocumentSymbol> symbols,
      String uri,
      String container,
      List<Either<SymbolInformation, DocumentSymbol>> out) {
    for (DocumentSymbol s : symbols) {
      out.add(
          Either.forLeft(
              new SymbolInformation(
                  s.getName(), s.getKind(), new Location(uri, s.getSelectionRange()), container)));
      if (s.getChildren() != null) {
        flatten(s.getChildren(), uri, s.getName(), out);
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Workspace

  private record Candidate(int score, String name, String container, Supplier<Location> location, SymbolKind kind) {}

  @Override
  @SuppressWarnings("deprecation")
  public CompletableFuture<Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>>>
      symbol(WorkspaceSymbolParams params) {
    return async(
        () -> {
          String query = params.getQuery() == null ? "" : params.getQuery().strip();
          if (worktrees.isEmpty()) {
            return Either.forLeft(List.of());
          }
          Worktree w = worktrees.get(0);
          Universe universe = w.universe();
          List<Candidate> sources = new ArrayList<>();
          w.index()
              .forEachType(
                  h -> {
                    int score = Symbols.score(h.type().simpleName, query);
                    if (score >= 0) {
                      sources.add(
                          new Candidate(
                              score + 10, // sources rank above libraries
                              h.type().simpleName,
                              container(h.type().binaryName),
                              () ->
                                  new Location(
                                      uri(universe.path(h.rel())),
                                      Symbols.range(
                                          h.file().lines,
                                          h.type().span.nameStart(),
                                          h.type().span.nameEnd())),
                              Symbols.kind(h.type().kind)));
                    }
                    if (query.length() >= 2) {
                      members(h, query, universe, sources);
                    }
                  });
          List<Candidate> all = new ArrayList<>(sources);
          if (query.length() >= 2) {
            List<Candidate> libs = new ArrayList<>();
            for (var deps : List.of(universe.deps(), universe.jdk())) {
              for (String bin : deps.classNames()) {
                String simple = bin.substring(Math.max(bin.lastIndexOf('/'), bin.lastIndexOf('$')) + 1);
                int score = Symbols.score(simple, query);
                if (score >= 0) {
                  libs.add(
                      new Candidate(
                          score - 10, // below source types and members
                          simple,
                          container(bin),
                          () -> {
                            ClassModel m = universe.find(bin);
                            return m == null ? null : location(m.target(), universe);
                          },
                          SymbolKind.Class));
                }
              }
            }
            libs.sort(Comparator.comparingInt(Candidate::score).reversed());
            all.addAll(libs.subList(0, Math.min(libs.size(), MAX_LIBRARY_SYMBOLS)));
          }
          all.sort(
              Comparator.comparingInt(Candidate::score)
                  .reversed()
                  .thenComparing(Candidate::name));
          List<SymbolInformation> out = new ArrayList<>();
          for (Candidate c : all) {
            if (out.size() >= MAX_WORKSPACE_SYMBOLS) {
              break;
            }
            Location loc = c.location.get();
            if (loc != null) {
              out.add(new SymbolInformation(c.name, c.kind, loc, c.container));
            }
          }
          return Either.forLeft(out);
        });
  }

  /** Methods and fields of a source type matching the query; they rank below types. */
  private void members(TypeHandle h, String query, Universe universe, List<Candidate> out) {
    String owner = TypeRef.qualifiedName(h.type().binaryName);
    for (MethodDecl m : h.type().methods) {
      int score = m.isConstructor() ? -1 : Symbols.score(m.name(), query);
      if (score >= 0) {
        out.add(memberCandidate(h, m.name() + "()", m.span(), owner, score - 5, SymbolKind.Method, universe));
      }
    }
    for (FileSummary.FieldDecl f : h.type().fields) {
      int score = Symbols.score(f.name(), query);
      if (score >= 0) {
        SymbolKind kind =
            f.kind() == FileSummary.FieldDecl.Kind.ENUM_CONSTANT ? SymbolKind.EnumMember : SymbolKind.Field;
        out.add(memberCandidate(h, f.name(), f.span(), owner, score - 5, kind, universe));
      }
    }
  }

  private Candidate memberCandidate(
      TypeHandle h, String name, FileSummary.Span span, String owner, int score, SymbolKind kind, Universe universe) {
    return new Candidate(score, name, owner, () -> location(h, span, universe), kind);
  }

  private static String container(String binaryName) {
    int end = Math.max(binaryName.lastIndexOf('/'), binaryName.lastIndexOf('$'));
    return end < 0 ? "" : binaryName.substring(0, end).replace('/', '.').replace('$', '.');
  }

  @Override
  public void didChangeConfiguration(DidChangeConfigurationParams params) {}

  @Override
  public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
    for (FileEvent e : params.getChanges()) {
      Path file = path(e.getUri());
      for (Worktree w : worktrees) {
        String rel = w.rel(file);
        if (rel != null) {
          w.enqueueChange(rel);
        }
      }
    }
  }

  @Override
  public void didChangeWorkspaceFolders(DidChangeWorkspaceFoldersParams params) {
    for (WorkspaceFolder f : params.getEvent().getRemoved()) {
      Path root = path(f.getUri());
      for (Worktree w : worktrees) {
        if (w.rel(root) != null && w.rel(root).isEmpty()) {
          worktrees.remove(w);
          project.release(w);
        }
      }
    }
    for (WorkspaceFolder f : params.getEvent().getAdded()) {
      addWorktree(path(f.getUri()));
    }
  }

  // ---------------------------------------------------------------------------------------------

  private <T> CompletableFuture<T> async(Supplier<T> work) {
    onActivity.run();
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            return work.get();
          } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "request failed", e);
            throw e;
          }
        },
        project.background());
  }

  static Path path(String uri) {
    try {
      return Path.of(URI.create(uri)).toAbsolutePath().normalize();
    } catch (IllegalArgumentException e) {
      return Path.of(uri);
    }
  }
}
