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

package dev.javaindex.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.javaindex.Version;
import dev.javaindex.project.Project;
import dev.javaindex.project.Worktree;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Model Context Protocol server (stdio transport: one JSON-RPC message per line) exposing the
 * shared index to agents. One instance per agent connection; the index itself is the daemon's.
 */
public final class McpServer {
  private static final Logger LOG = Logger.getLogger(McpServer.class.getName());
  private static final List<String> PROTOCOL_VERSIONS = List.of("2025-06-18", "2025-03-26", "2024-11-05");

  private static final String INSTRUCTIONS =
      """
      Semantic index of this Java repository: its sources, the exact dependency jars on its \
      classpath, and the JDK. Symbols are written like com.example.Client, Client, \
      Client#send or Client#send(String, int).
      - Before using an unfamiliar library or JDK API, call java_api: it shows the real \
      signatures and javadoc of the version this project compiles against.
      - To find who calls or uses a method/field, prefer java_callers over grep when the name is \
      common (get, save, execute...): it resolves receivers' types. Its answer is strong \
      evidence, not proof: check the 'Unresolved' section, and remember reflection and \
      framework wiring are invisible.
      - java_implementations finds subtypes/overrides, including indirect ones grep would miss.
      - java_outline gives a file's structure with line numbers before reading it.
      - java_config shows a configuration key's values per profile and the code using it.
      - Plain text search is still best done with grep.""";

  private final Project project;
  private final Path root;
  private final AgentTools tools;
  private final Runnable onActivity;
  private final Map<Path, Worktree> worktrees = new LinkedHashMap<>();
  private final Gson gson = new Gson();

  public McpServer(Project project, Path root, Runnable onActivity) throws IOException {
    this.project = project;
    this.root = root;
    this.tools = new AgentTools(project);
    this.onActivity = onActivity;
    worktree(root); // start indexing right away
  }

  /** Serves one connection until the input ends. */
  public void serve(InputStream in, OutputStream out) throws IOException {
    BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
    Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
    try {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.isBlank()) {
          continue;
        }
        JsonObject response;
        try {
          response = handle(JsonParser.parseString(line).getAsJsonObject());
        } catch (RuntimeException e) {
          response = error(JsonNull.INSTANCE, -32700, "Parse error: " + e.getMessage());
        }
        if (response != null) {
          writer.write(gson.toJson(response));
          writer.write('\n');
          writer.flush();
        }
      }
    } finally {
      close();
    }
  }

  /** Handles one message; returns the response, or null for notifications. */
  public JsonObject handle(JsonObject msg) {
    onActivity.run();
    JsonElement id = msg.get("id");
    JsonElement methodEl = msg.get("method");
    if (methodEl == null) {
      return null; // a response to a request we never send
    }
    String method = methodEl.getAsString();
    JsonObject params = msg.has("params") && msg.get("params").isJsonObject() ? msg.getAsJsonObject("params") : new JsonObject();
    JsonObject result;
    switch (method) {
      case "initialize" -> result = initialize(params);
      case "ping" -> result = new JsonObject();
      case "tools/list" -> result = toolsList();
      case "tools/call" -> result = call(params);
      default -> {
        if (id == null || method.startsWith("notifications/")) {
          return null;
        }
        return error(id, -32601, "Method not found: " + method);
      }
    }
    if (id == null) {
      return null;
    }
    JsonObject response = new JsonObject();
    response.addProperty("jsonrpc", "2.0");
    response.add("id", id);
    response.add("result", result);
    return response;
  }

  private static JsonObject error(JsonElement id, int code, String message) {
    JsonObject err = new JsonObject();
    err.addProperty("code", code);
    err.addProperty("message", message);
    JsonObject response = new JsonObject();
    response.addProperty("jsonrpc", "2.0");
    response.add("id", id);
    response.add("error", err);
    return response;
  }

  private JsonObject initialize(JsonObject params) {
    String requested = params.has("protocolVersion") ? params.get("protocolVersion").getAsString() : null;
    JsonObject result = new JsonObject();
    result.addProperty(
        "protocolVersion", PROTOCOL_VERSIONS.contains(requested) ? requested : PROTOCOL_VERSIONS.get(0));
    JsonObject capabilities = new JsonObject();
    JsonObject toolCaps = new JsonObject();
    toolCaps.addProperty("listChanged", false);
    capabilities.add("tools", toolCaps);
    result.add("capabilities", capabilities);
    JsonObject info = new JsonObject();
    info.addProperty("name", "java-index");
    info.addProperty("version", Version.VERSION);
    result.add("serverInfo", info);
    result.addProperty("instructions", INSTRUCTIONS);
    return result;
  }

  // ---------------------------------------------------------------------------------------------
  // Tools

  private JsonObject toolsList() {
    JsonArray list = new JsonArray();
    list.add(
        tool(
            "java_api",
            "Show the real API of a class or member as compiled against by this project: "
                + "signatures, javadoc and where it is declared. Works for project classes, "
                + "dependency jars (the exact version on the classpath) and the JDK. Use before "
                + "writing code against an API you are not certain about.",
            prop("symbol", "string", "Class or member: com.example.Client, Client, Client#send, "
                + "Client#send(String), or Client#Client for constructors.", true),
            enumProp("javadoc", List.of("summary", "full", "none"),
                "Javadoc detail: first sentence per member (default), everything, or none."),
            prop("include_inherited", "boolean", "Also list inherited method names, grouped by declaring type.", false)));
    list.add(
        tool(
            "java_callers",
            "Find where a method, constructor or field is used, resolving receiver types so "
                + "unrelated same-named methods are excluded. Groups call sites by calling "
                + "method, with the source line. Also reports calls through supertypes and to "
                + "overrides, and same-named calls it could not resolve. Not visible: reflection, "
                + "framework wiring, non-Java code.",
            prop("symbol", "string", "Member: Client#send, com.example.Client#send(String), Client#Client (constructor) or Client#timeout (field).", true),
            prop("include_related", "boolean", "Include calls via supertype declarations and to overrides (default true).", false)));
    list.add(
        tool(
            "java_implementations",
            "List all subtypes of a class or interface declared in this project (transitively, "
                + "including through abstract bases), or all overrides of a method.",
            prop("symbol", "string", "Type or method: com.example.Repository or Repository#save.", true)));
    list.add(
        tool(
            "java_outline",
            "Structure of a Java file without reading it: types, fields, constructors and "
                + "methods with resolved signatures and line numbers.",
            prop("file", "string", "Path relative to the worktree root, or absolute.", true)));
    list.add(
        tool(
            "java_config",
            "Spring Boot / Quarkus configuration: for a key, its value in every profile "
                + "(application.yml, application-dev.properties, %dev. prefixes...), the Java that "
                + "reads or binds it (@Value, @ConfigProperty, @ConfigurationProperties, "
                + "@ConfigMapping, getProperty) and, for library keys like server.port, the "
                + "documented type, default and description. For a prefix, lists the keys under it.",
            prop("key", "string", "A key (app.timeout, server.port) or a prefix (app. or app.*). "
                + "Relaxed binding: my-prop, myProp and my_prop match.", true)));
    list.add(
        tool(
            "java_find_type",
            "Find classes by name across the project, its dependencies and the JDK: simple names, "
                + "prefixes, camel-case humps (e.g. 'HSR' for HttpServletRequest) or qualified "
                + "suffixes. Project types rank first; libraries only match on names, prefixes and humps.",
            prop("query", "string", "Name, prefix, camel humps (MEI, MesEmI) or qualified suffix.", true),
            enumProp("scope", List.of("all", "project", "libraries"),
                "Where to look (default all; project types are listed first)."),
            prop("limit", "integer", "Maximum results (default 20).", false)));
    JsonObject result = new JsonObject();
    result.add("tools", list);
    return result;
  }

  private record Prop(String name, JsonObject schema, boolean required) {}

  private static Prop prop(String name, String type, String description, boolean required) {
    JsonObject schema = new JsonObject();
    schema.addProperty("type", type);
    schema.addProperty("description", description);
    return new Prop(name, schema, required);
  }

  private static Prop enumProp(String name, List<String> values, String description) {
    JsonObject schema = new JsonObject();
    schema.addProperty("type", "string");
    JsonArray e = new JsonArray();
    values.forEach(e::add);
    schema.add("enum", e);
    schema.addProperty("description", description);
    return new Prop(name, schema, false);
  }

  private static JsonObject tool(String name, String description, Prop... props) {
    JsonObject properties = new JsonObject();
    JsonArray required = new JsonArray();
    for (Prop p : props) {
      properties.add(p.name, p.schema);
      if (p.required) {
        required.add(p.name);
      }
    }
    JsonObject worktree = new JsonObject();
    worktree.addProperty("type", "string");
    worktree.addProperty(
        "description",
        "Optional: absolute path of another git worktree of this repository to query instead of the default one.");
    properties.add("worktree", worktree);
    JsonObject schema = new JsonObject();
    schema.addProperty("type", "object");
    schema.add("properties", properties);
    schema.add("required", required);
    JsonObject tool = new JsonObject();
    tool.addProperty("name", name);
    tool.addProperty("description", description);
    tool.add("inputSchema", schema);
    return tool;
  }

  private JsonObject call(JsonObject params) {
    String name = params.has("name") ? params.get("name").getAsString() : "";
    JsonObject args = params.has("arguments") && params.get("arguments").isJsonObject() ? params.getAsJsonObject("arguments") : new JsonObject();
    String text;
    boolean isError = false;
    try {
      Worktree w = worktree(args.has("worktree") ? Path.of(str(args, "worktree", null)) : root);
      w.awaitClasspath(60_000);
      text =
          switch (name) {
            case "java_api" ->
                tools.api(w, str(args, "symbol", ""), str(args, "javadoc", "summary"), bool(args, "include_inherited", false));
            case "java_callers" -> tools.callers(w, str(args, "symbol", ""), bool(args, "include_related", true));
            case "java_implementations" -> tools.implementations(w, str(args, "symbol", ""));
            case "java_outline" -> tools.outline(w, str(args, "file", ""));
            case "java_config" -> tools.config(w, str(args, "key", ""));
            case "java_find_type" ->
                tools.findType(
                    w,
                    str(args, "query", ""),
                    args.has("limit") ? args.get("limit").getAsInt() : 20,
                    str(args, "scope", "all"));
            default -> throw new AgentTools.ToolError("Unknown tool: " + name);
          };
    } catch (AgentTools.ToolError e) {
      text = e.getMessage();
      isError = true;
    } catch (IOException | RuntimeException e) {
      LOG.log(Level.WARNING, "tool " + name + " failed", e);
      text = "java-index failed: " + e;
      isError = true;
    }
    JsonObject content = new JsonObject();
    content.addProperty("type", "text");
    content.addProperty("text", text);
    JsonArray contents = new JsonArray();
    contents.add(content);
    JsonObject result = new JsonObject();
    result.add("content", contents);
    result.add("isError", new JsonPrimitive(isError));
    return result;
  }

  private static String str(JsonObject args, String key, String fallback) {
    return args.has(key) && !args.get(key).isJsonNull() ? args.get(key).getAsString() : fallback;
  }

  private static boolean bool(JsonObject args, String key, boolean fallback) {
    return args.has(key) && !args.get(key).isJsonNull() ? args.get(key).getAsBoolean() : fallback;
  }

  /** The worktree at {@code path}, opened (and held) for this connection on first use. */
  private synchronized Worktree worktree(Path path) throws IOException {
    Path real = checkoutRoot(path.toRealPath());
    Worktree w = worktrees.get(real);
    if (w == null) {
      w = project.open(real, List.of());
      worktrees.put(real, w);
    }
    return w;
  }

  /** The enclosing checkout (the directory holding .git), so a subdirectory maps to its worktree. */
  static Path checkoutRoot(Path path) {
    for (Path p = path; p != null; p = p.getParent()) {
      if (java.nio.file.Files.exists(p.resolve(".git"))) {
        return p;
      }
    }
    return path;
  }

  /** Releases the worktrees this connection held. */
  public synchronized void close() {
    worktrees.values().forEach(project::release);
    worktrees.clear();
  }
}
