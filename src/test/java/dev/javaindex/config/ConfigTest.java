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

package dev.javaindex.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.javaindex.lsp.JavaIndexServer;
import dev.javaindex.mcp.McpServer;
import dev.javaindex.project.Project;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.ReferenceContext;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Spring Boot and Quarkus configuration keys, between config files and Java, both ways. */
class ConfigTest {
  @TempDir Path tmp;

  private Path root;
  private Project project;
  private JavaIndexServer server;
  private McpServer mcp;

  private static final String YAML =
      """
      app:
        timeout: 30
        retry:
          max-attempts: 3
        endpoints:
          orders:
            url: http://orders
        hosts:
          - a.example
          - b.example
      server:
        port: 9090
      ---
      spring:
        config:
          activate:
            on-profile: dev
      app:
        timeout: 5
      """;
  private static final String PROD = "app.timeout=60\n";
  private static final String QUARKUS =
      """
      greeting.message=hello
      %dev.greeting.message=hi
      mail.host=smtp.example
      mail.smtp-port=25
      """;
  private static final String SERVICE =
      """
      package demo;

      public class OrderService {
        @Value("${app.timeout:10}")
        int timeout;

        @Value("${app.retry.maxAttempts}")
        int attempts;

        void reload(Environment env) {
          String t = env.getProperty("app.timeout");
        }
      }
      """;
  private static final String APP_PROPERTIES =
      """
      package demo;

      import java.util.List;
      import java.util.Map;

      @ConfigurationProperties(prefix = "app")
      public class AppProperties {
        private int timeout;
        private Retry retry;
        private Map<String, Endpoint> endpoints;
        private List<String> hosts;

        public static class Retry {
          private int maxAttempts;
        }

        public static class Endpoint {
          private String url;
        }
      }
      """;
  private static final String GREETING =
      """
      package demo;

      public class Greeting {
        @ConfigProperty(name = "greeting.message")
        String message;
      }
      """;
  private static final String MAIL =
      """
      package demo;

      @ConfigMapping(prefix = "mail")
      public interface MailConfig {
        String host();

        @WithName("smtp-port")
        int port();
      }
      """;

  @BeforeEach
  void setUp() throws Exception {
    root = tmp.resolve("proj");
    write("src/main/resources/application.yml", YAML);
    write("src/main/resources/application-prod.properties", PROD);
    write("quarkus/src/main/resources/application.properties", QUARKUS);
    // another module (its own application) setting the same key
    write("other/src/main/resources/application.properties", "app.timeout=99\n");
    write("src/main/java/demo/OrderService.java", SERVICE);
    write("src/main/java/demo/AppProperties.java", APP_PROPERTIES);
    write("src/main/java/demo/Greeting.java", GREETING);
    write("src/main/java/demo/MailConfig.java", MAIL);
    // A dependency that documents library keys the way Spring Boot and Quarkus jars do.
    Path jar = tmp.resolve("lib-config.jar");
    try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
      zip.putNextEntry(new ZipEntry("META-INF/spring-configuration-metadata.json"));
      zip.write(
          """
          {"properties": [{"name": "server.port", "type": "java.lang.Integer",
            "description": "Server HTTP port.", "defaultValue": 8080,
            "sourceType": "org.springframework.boot.autoconfigure.web.ServerProperties"}]}
          """.getBytes(StandardCharsets.UTF_8));
      zip.putNextEntry(new ZipEntry("META-INF/quarkus-config-doc/quarkus-config-model.json"));
      zip.write(
          """
          {"configRoots": {"x": {"items": [{"path": {"property": "quarkus.http.port"},
            "sourceType": "io.quarkus.vertx.http.runtime.HttpConfig", "sourceElementName": "port",
            "sourceElementType": "METHOD", "type": "int", "defaultValue": "8080"},
            {"path": {"property": "quarkus.datasource.\\"datasource-name\\".jdbc.url"},
            "sourceType": "io.quarkus.agroal.Jdbc", "sourceElementName": "url", "type": "java.lang.String"}]}}}
          """.getBytes(StandardCharsets.UTF_8));
      zip.putNextEntry(new ZipEntry("META-INF/quarkus-config-doc/quarkus-config-javadoc.json"));
      zip.write(
          """
          {"elements": {"io.quarkus.vertx.http.runtime.HttpConfig.port": {"description": "The HTTP port."}}}
          """.getBytes(StandardCharsets.UTF_8));
    }
    write(".java-index/classpath", jar + "\n");

    project = new Project(Files.createDirectories(tmp.resolve("state")));
    server = new JavaIndexServer(project, () -> {});
    InitializeParams init = new InitializeParams();
    init.setRootUri(root.toUri().toString());
    server.initialize(init).get(30, TimeUnit.SECONDS);
    mcp = new McpServer(project, root, () -> {});
  }

  @AfterEach
  void tearDown() {
    mcp.close();
    server.dispose();
  }

  @Test
  void javaToConfigAcrossProfiles() throws Exception {
    // ${app.timeout} -> the value in application.yml, its dev document and application-prod;
    // not the other module's application.properties
    List<String> defs = definition("src/main/java/demo/OrderService.java", SERVICE, "app.timeout:10");
    assertEquals(
        Set.of("application.yml:2", "application.yml:19", "application-prod.properties:1"), Set.copyOf(defs));
    // relaxed binding: maxAttempts in Java, max-attempts in YAML
    assertEquals(List.of("application.yml:4"), definition("src/main/java/demo/OrderService.java", SERVICE, "maxAttempts"));
    // a @ConfigurationProperties field leads to its key
    assertEquals(List.of("application.yml:4"), definition("src/main/java/demo/AppProperties.java", APP_PROPERTIES, "maxAttempts;"));
    // Quarkus @ConfigProperty, including the %dev profile
    assertEquals(2, definition("src/main/java/demo/Greeting.java", GREETING, "greeting.message").size());
  }

  @Test
  void configToJava() throws Exception {
    String yml = "src/main/resources/application.yml";
    // the three Java places reading app.timeout
    assertEquals(
        Set.of("OrderService.java:4", "OrderService.java:11", "AppProperties.java:8"),
        Set.copyOf(definition(yml, YAML, "timeout: 30")));
    // nested and map-valued properties
    // relaxed binding: the @Value("${app.retry.maxAttempts}") reads it too
    assertEquals(
        Set.of("OrderService.java:7", "AppProperties.java:14"), Set.copyOf(definition(yml, YAML, "max-attempts")));
    assertEquals(List.of("AppProperties.java:18"), definition(yml, YAML, "url:"));
    // Quarkus @ConfigMapping with @WithName
    String props = "quarkus/src/main/resources/application.properties";
    assertEquals(List.of("MailConfig.java:8"), definition(props, QUARKUS, "smtp-port"));
    assertEquals(List.of("MailConfig.java:5"), definition(props, QUARKUS, "host"));

    // references from a key: its definitions (all modules) plus its uses
    var refs =
        server.references(new ReferenceParams(doc(yml), pos(YAML, "timeout: 30"), new ReferenceContext(true)))
            .get(30, TimeUnit.SECONDS);
    assertEquals(7, refs.size(), refs.toString());
  }

  @Test
  void hoverShowsValuesPerProfileAndLibraryDocs() throws Exception {
    String md = hover("src/main/java/demo/OrderService.java", SERVICE, "app.timeout:10");
    assertTrue(md.contains("| (default) | `30` | src/main/resources/application.yml:2 |"), md);
    assertTrue(md.contains("| dev | `5` |"), md);
    assertTrue(md.contains("| prod | `60` |"), md);
    assertTrue(md.contains("`AppProperties.timeout`"), md);

    String port = hover("src/main/resources/application.yml", YAML, "port: 9090");
    assertTrue(port.contains("| (default) | `9090` |"), port);
    assertTrue(port.contains("`Integer` · default `8080` · from lib-config.jar"), port);
    assertTrue(port.contains("Server HTTP port."), port);
  }

  @Test
  void agentToolCoversKeysPrefixesAndLibraries() {
    String timeout = call("{\"key\":\"app.timeout\"}");
    assertTrue(timeout.contains("- dev: 5"), timeout);
    assertTrue(timeout.contains("- prod: 60"), timeout);
    assertTrue(timeout.contains("other/src/main/resources/application.properties:1"), timeout); // agents see all modules
    assertTrue(timeout.contains("AppProperties.timeout"), timeout);

    String prefix = call("{\"key\":\"app.\"}");
    assertTrue(prefix.contains("- app.retry.max-attempts = 3"), prefix);
    assertTrue(prefix.contains("- app.endpoints.orders.url = http://orders"), prefix);

    String quarkusLib = call("{\"key\":\"quarkus.http.port\"}");
    assertTrue(quarkusLib.contains("default 8080") && quarkusLib.contains("The HTTP port."), quarkusLib);
    // Quarkus "named" keys: quarkus.datasource."name".jdbc.url matches any name
    String named = call("{\"key\":\"quarkus.datasource.orders.jdbc.url\"}");
    assertTrue(named.contains("Library property"), named);

    String unknown = call("{\"key\":\"app.nope\"}");
    assertTrue(unknown.contains("No configuration key or prefix 'app.nope'"), unknown);
  }

  // ---------------------------------------------------------------------------------------------

  private List<String> definition(String rel, String text, String needle) throws Exception {
    var result =
        server.definition(new DefinitionParams(doc(rel), pos(text, needle))).get(30, TimeUnit.SECONDS);
    return result.getLeft().stream().map(ConfigTest::describe).collect(Collectors.toList());
  }

  private String hover(String rel, String text, String needle) throws Exception {
    return server.hover(new HoverParams(doc(rel), pos(text, needle))).get(30, TimeUnit.SECONDS)
        .getContents().getRight().getValue();
  }

  private String call(String args) {
    JsonObject params = new JsonObject();
    params.addProperty("name", "java_config");
    params.add("arguments", JsonParser.parseString(args));
    JsonObject msg = new JsonObject();
    msg.addProperty("jsonrpc", "2.0");
    msg.addProperty("id", 1);
    msg.addProperty("method", "tools/call");
    msg.add("params", params);
    JsonObject result = mcp.handle(msg).getAsJsonObject("result");
    String text = result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
    assertFalse(result.get("isError").getAsBoolean() && !text.contains("No configuration key"), text);
    return text;
  }

  private static String describe(Location l) {
    return l.getUri().substring(l.getUri().lastIndexOf('/') + 1) + ":" + (l.getRange().getStart().getLine() + 1);
  }

  private TextDocumentIdentifier doc(String rel) {
    return new TextDocumentIdentifier(root.resolve(rel).toUri().toString());
  }

  private static Position pos(String text, String needle) {
    int offset = text.indexOf(needle);
    assertTrue(offset >= 0, needle);
    String before = text.substring(0, offset);
    return new Position((int) before.chars().filter(c -> c == '\n').count(), offset - (before.lastIndexOf('\n') + 1) + 1);
  }

  private void write(String rel, String text) throws Exception {
    Path f = root.resolve(rel);
    Files.createDirectories(f.getParent());
    Files.writeString(f, text);
  }
}
