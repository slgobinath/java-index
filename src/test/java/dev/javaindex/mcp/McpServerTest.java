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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.javaindex.project.Project;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpServerTest {
  @TempDir Path tmp;

  private McpServer server;
  private int nextId;

  @BeforeEach
  void setUp() throws Exception {
    Path root = tmp.resolve("proj");
    write(root, "src/demo/Repository.java",
        """
        package demo;

        public interface Repository<T> {
          /**
           * Persists the entity.
           *
           * @param entity the entity to store
           */
          void save(T entity);
        }
        """);
    write(root, "src/demo/AbstractRepo.java",
        "package demo;\npublic abstract class AbstractRepo<T> implements Repository<T> {}\n");
    write(root, "src/demo/UserRepo.java",
        """
        package demo;

        public class UserRepo extends AbstractRepo<User> {
          @Override
          public void save(User user) {}
        }
        """);
    write(root, "src/demo/User.java",
        "package demo;\npublic class User {\n  public void save() {}\n}\n");
    write(root, "src/demo/Service.java",
        """
        package demo;

        import demo.save.Thing;

        public class Service {
          private final demo.save.Thing thing = new demo.save.Thing();
          private final UserRepo users = new UserRepo();
          private final Repository<User> generic = users;

          void register(User u) {
            users.save(u);
            u.save();
          }

          void store(User u) {
            generic.save(u);
          }

          void unknown(Object o) {
            mystery().save(o);
          }
        }
        """);
    write(root, "src/demo/Repos.java",
        """
        package demo;

        class Repos {
          static Repository<User> inMemory() {
            return new AbstractRepo<User>() {};
          }
        }
        """);
    // A package named like the method (as in ai.signal1.commons.error): never a "use" of it.
    write(root, "src/demo/save/Thing.java", "package demo.save;\npublic class Thing {}\n");
    // The project's own classes also on the classpath, e.g. a module jar installed in ~/.m2.
    Path jar = tmp.resolve("own-module.jar");
    try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(jar))) {
      zip.putNextEntry(new java.util.zip.ZipEntry("demo/UserRepo.class"));
      zip.write(new byte[] {(byte) 0xCA, (byte) 0xFE});
      zip.closeEntry();
    }
    write(root, ".java-index/classpath", jar.toString() + "\n");
    server = new McpServer(new Project(Files.createDirectories(tmp.resolve("state"))), root, () -> {});
    JsonObject init = request("initialize", "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{}}");
    assertEquals("2025-06-18", init.getAsJsonObject("result").get("protocolVersion").getAsString());
  }

  @AfterEach
  void tearDown() {
    server.close();
  }

  @Test
  void listsTools() {
    var tools = request("tools/list", "{}").getAsJsonObject("result").getAsJsonArray("tools");
    assertEquals(6, tools.size());
  }

  @Test
  void apiShowsRealSignaturesAndJavadoc() {
    String orElse = call("java_api", "{\"symbol\":\"java.util.Optional#orElse\"}");
    assertTrue(orElse.contains("T orElse(T other)"), orElse);
    assertTrue(orElse.contains("from JDK"), orElse);
    assertTrue(orElse.toLowerCase().contains("return"), orElse); // javadoc present

    String optional = call("java_api", "{\"symbol\":\"Optional\",\"javadoc\":\"none\"}");
    // Declarations are quoted from the real source: generics and wildcards intact.
    assertTrue(optional.startsWith("# java.util.Optional"), optional);
    assertTrue(optional.contains("public final class Optional<T>"), optional);
    assertTrue(optional.contains("public static <T> Optional<T> ofNullable(T value)"), optional);
    assertTrue(optional.contains("public <U> Optional<U> map(Function<? super T, ? extends U> mapper)"), optional);
    assertFalse(optional.contains("EMPTY"), "private members of libraries are hidden: " + optional);

    // Type parameters with several arguments survive intact.
    String collector = call("java_api", "{\"symbol\":\"java.util.stream.Collectors#toMap\",\"javadoc\":\"none\"}");
    assertTrue(collector.contains("public static <T, K, U> Collector<T, ?, Map<K,U>> toMap("), collector);

    String repo = call("java_api", "{\"symbol\":\"Repository\"}");
    assertTrue(repo.contains("# demo.Repository") && repo.contains("public interface Repository<T>"), repo);
    assertTrue(repo.contains("void save(T entity)"), repo);
    assertTrue(repo.contains("Persists the entity."), repo);
    assertTrue(repo.contains("src/demo/Repository.java:3"), repo);
  }

  @Test
  void callersExcludeSameNamedMethodsAndShowUncertainty() {
    String out = call("java_callers", "{\"symbol\":\"UserRepo#save\"}");
    // the direct call, grouped under its caller with the source line
    assertTrue(out.contains("demo.Service#register"), out);
    assertTrue(out.contains("users.save(u);"), out);
    // User#save is a different method with the same name
    assertFalse(out.contains("u.save();"), out);
    // a call through the interface could dispatch here: reported in its own group
    assertTrue(out.contains("Via supertype demo.Repository"), out);
    assertTrue(out.contains("generic.save(u);"), out);
    // the resolver can't type mystery(): flagged, not silently dropped
    assertTrue(out.contains("Unresolved `save` uses (1)"), out); // not the demo.save package
    assertFalse(out.contains("import demo.save"), out);
    assertTrue(out.contains("mystery().save(o);"), out);
  }

  @Test
  void implementationsOutlineAndFindType() {
    String subs = call("java_implementations", "{\"symbol\":\"demo.Repository\"}");
    assertTrue(subs.contains("demo.AbstractRepo") && subs.contains("demo.UserRepo"), subs);
    assertTrue(subs.contains("## Anonymous classes (1)"), subs);
    assertTrue(subs.contains("- new demo.AbstractRepo() {…}  src/demo/Repos.java:5  return new AbstractRepo<User>() {};"), subs);

    String overrides = call("java_implementations", "{\"symbol\":\"Repository#save\"}");
    assertTrue(overrides.contains("demo.UserRepo#save  src/demo/UserRepo.java:5"), overrides);

    String outline = call("java_outline", "{\"file\":\"src/demo/Service.java\"}");
    assertTrue(outline.contains("class Service"), outline);
    assertTrue(outline.contains("method void register(User u)"), outline);
    assertTrue(outline.contains("field  UserRepo users"), outline);

    String found = call("java_find_type", "{\"query\":\"UserRe\"}");
    assertTrue(found.contains("\n- demo.UserRepo  src/demo/UserRepo.java:3\n"), found);
    // listed once: the copy in the jar on the classpath is the same class
    assertEquals(1, found.split(java.util.regex.Pattern.quote("- demo.UserRepo")).length - 1, found);
    // camel humps, initials first
    assertTrue(call("java_find_type", "{\"query\":\"UR\"}").contains("\n- demo.UserRepo  src"), "UR");
    assertTrue(call("java_find_type", "{\"query\":\"UsRe\"}").contains("\n- demo.UserRepo  src"), "UsRe");
    // initials never fall back to substrings ("SER" is inside "User")
    assertFalse(call("java_find_type", "{\"query\":\"SER\",\"scope\":\"project\"}").contains("demo.User"));
    String jdk = call("java_find_type", "{\"query\":\"java.util.Optional\"}");
    assertTrue(jdk.contains("java.util.Optional  JDK"), jdk);
  }

  @Test
  void errorsAreExplained() {
    JsonObject missing = callRaw("java_api", "{\"symbol\":\"NoSuchThing\"}");
    assertTrue(missing.get("isError").getAsBoolean());
    assertTrue(text(missing).contains("No class named 'NoSuchThing'"), text(missing));

    JsonObject ambiguous = callRaw("java_api", "{\"symbol\":\"List\"}");
    assertTrue(ambiguous.get("isError").getAsBoolean());
    assertTrue(text(ambiguous).contains("java.util.List") && text(ambiguous).contains("java.awt.List"), text(ambiguous));

    JsonObject member = callRaw("java_api", "{\"symbol\":\"UserRepo#sav\"}");
    assertTrue(text(member).contains("Did you mean: save"), text(member));

    // "Class.member" without '#' is understood too
    assertTrue(call("java_api", "{\"symbol\":\"UserRepo.save\"}").contains("void save(User user)"));
  }

  // ---------------------------------------------------------------------------------------------

  private JsonObject request(String method, String params) {
    JsonObject msg = new JsonObject();
    msg.addProperty("jsonrpc", "2.0");
    msg.addProperty("id", ++nextId);
    msg.addProperty("method", method);
    msg.add("params", JsonParser.parseString(params));
    return server.handle(msg);
  }

  private JsonObject callRaw(String tool, String args) {
    JsonObject params = new JsonObject();
    params.addProperty("name", tool);
    params.add("arguments", JsonParser.parseString(args));
    return request("tools/call", params.toString()).getAsJsonObject("result");
  }

  private String call(String tool, String args) {
    JsonObject result = callRaw(tool, args);
    assertFalse(result.get("isError").getAsBoolean(), text(result));
    return text(result);
  }

  private static String text(JsonObject result) {
    return result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
  }

  private static void write(Path root, String rel, String text) throws Exception {
    Path f = root.resolve(rel);
    Files.createDirectories(f.getParent());
    Files.writeString(f, text);
  }
}
