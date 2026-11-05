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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Configuration keys that libraries define, from the metadata they ship: Spring Boot's
 * {@code META-INF/spring-configuration-metadata.json} and Quarkus's
 * {@code META-INF/quarkus-config-doc/quarkus-config-model.json} (+ its javadoc file). Loaded once
 * per classpath, shared by every worktree using it.
 */
public final class LibraryConfig {
  private static final Logger LOG = Logger.getLogger(LibraryConfig.class.getName());

  /**
   * A documented property. {@code sourceType} is a binary class name ({@code a/b/C$D}) and
   * {@code member} the field or method declaring it, when known.
   */
  public record Property(
      String key,
      String type,
      String description,
      String defaultValue,
      String sourceType,
      String member,
      boolean method,
      boolean deprecated,
      String origin) {}

  public static final LibraryConfig EMPTY = new LibraryConfig();

  private final Map<String, Property> exact = new HashMap<>();
  /** Keys with wildcard segments (Quarkus "named" maps, e.g. quarkus.datasource."name".jdbc.url). */
  private final List<Map.Entry<String[], Property>> patterns = new ArrayList<>();

  private LibraryConfig() {}

  public static LibraryConfig load(List<Path> jars) {
    LibraryConfig out = new LibraryConfig();
    for (Path jar : jars) {
      try (ZipFile zip = new ZipFile(jar.toFile())) {
        String origin = jar.getFileName().toString();
        for (String name :
            List.of(
                "META-INF/spring-configuration-metadata.json",
                "META-INF/additional-spring-configuration-metadata.json")) {
          JsonObject json = json(zip, name);
          if (json != null) {
            out.spring(json, origin);
          }
        }
        JsonObject model = json(zip, "META-INF/quarkus-config-doc/quarkus-config-model.json");
        if (model != null) {
          JsonObject javadoc = json(zip, "META-INF/quarkus-config-doc/quarkus-config-javadoc.json");
          out.quarkus(model, javadoc, origin);
        }
      } catch (IOException | RuntimeException e) {
        LOG.log(Level.FINE, "cannot read config metadata from " + jar, e);
      }
    }
    return out;
  }

  private static JsonObject json(ZipFile zip, String name) throws IOException {
    ZipEntry entry = zip.getEntry(name);
    if (entry == null) {
      return null;
    }
    try (InputStream in = zip.getInputStream(entry)) {
      JsonElement e = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
      return e.isJsonObject() ? e.getAsJsonObject() : null;
    }
  }

  private void spring(JsonObject json, String origin) {
    if (!(json.get("properties") instanceof JsonArray props)) {
      return;
    }
    for (JsonElement el : props) {
      if (!el.isJsonObject()) {
        continue;
      }
      JsonObject p = el.getAsJsonObject();
      String key = str(p, "name");
      if (key == null) {
        continue;
      }
      String source = str(p, "sourceType");
      String last = key.substring(key.lastIndexOf('.') + 1);
      add(
          new Property(
              key,
              str(p, "type"),
              str(p, "description"),
              p.has("defaultValue") ? p.get("defaultValue").toString().replaceAll("^\"|\"$", "") : null,
              source == null ? null : source.replace('.', '/'),
              camel(last),
              false,
              p.has("deprecation") || p.has("deprecated"),
              origin));
    }
  }

  private void quarkus(JsonObject model, JsonObject javadoc, String origin) {
    JsonObject docs =
        javadoc != null && javadoc.get("elements") instanceof JsonObject e ? e : new JsonObject();
    collectQuarkus(model, docs, origin);
  }

  private void collectQuarkus(JsonElement node, JsonObject docs, String origin) {
    if (node instanceof JsonArray array) {
      for (JsonElement e : array) {
        collectQuarkus(e, docs, origin);
      }
      return;
    }
    if (!(node instanceof JsonObject obj)) {
      return;
    }
    if (obj.get("path") instanceof JsonObject path && path.has("property") && obj.has("sourceType")) {
      String key = str(path, "property");
      String source = str(obj, "sourceType");
      String member = str(obj, "sourceElementName");
      String doc =
          docs.get(source + "." + member) instanceof JsonObject d ? str(d, "description") : null;
      add(
          new Property(
              key,
              str(obj, "type"),
              doc,
              str(obj, "defaultValue"),
              source == null ? null : source.replace('.', '/'),
              member,
              "METHOD".equals(str(obj, "sourceElementType")),
              obj.has("deprecated") && obj.get("deprecated").getAsBoolean(),
              origin));
      return;
    }
    for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
      collectQuarkus(e.getValue(), docs, origin);
    }
  }

  private void add(Property p) {
    // Quarkus marks map keys with quotes: quarkus.datasource."datasource-name".jdbc.url
    String pattern = p.key.replaceAll("\"[^\"]*\"", "*");
    String canonical = ConfigKeys.canonical(pattern);
    if (canonical.contains("*")) {
      patterns.add(Map.entry(canonical.split("\\."), p));
    } else {
      exact.putIfAbsent(canonical, p);
    }
  }

  /** The library property for a key (canonical form), or null. */
  public Property lookup(String canonicalKey) {
    Property p = exact.get(canonicalKey);
    if (p != null) {
      return p;
    }
    String[] segments = canonicalKey.split("\\.");
    for (Map.Entry<String[], Property> e : patterns) {
      if (matches(e.getKey(), segments)) {
        return e.getValue();
      }
    }
    return null;
  }

  /** Library properties whose key starts with {@code canonicalPrefix}. */
  public List<Property> withPrefix(String canonicalPrefix, int limit) {
    List<Property> out = new ArrayList<>();
    for (Map.Entry<String, Property> e : exact.entrySet()) {
      if (e.getKey().startsWith(canonicalPrefix) && out.size() < limit) {
        out.add(e.getValue());
      }
    }
    out.sort((a, b) -> a.key.compareTo(b.key));
    return out;
  }

  public int size() {
    return exact.size() + patterns.size();
  }

  private static boolean matches(String[] pattern, String[] key) {
    if (pattern.length != key.length) {
      return false;
    }
    for (int i = 0; i < pattern.length; i++) {
      if (!pattern[i].equals("*") && !pattern[i].equals(key[i])) {
        return false;
      }
    }
    return true;
  }

  private static String str(JsonObject o, String key) {
    JsonElement e = o.get(key);
    return e == null || e.isJsonNull() ? null : e.isJsonPrimitive() ? e.getAsString() : e.toString();
  }

  /** {@code max-threads} -> {@code maxThreads}. */
  static String camel(String kebab) {
    StringBuilder sb = new StringBuilder();
    boolean upper = false;
    for (char c : kebab.toCharArray()) {
      if (c == '-' || c == '_') {
        upper = true;
      } else {
        sb.append(upper ? Character.toUpperCase(c) : c);
        upper = false;
      }
    }
    return sb.toString();
  }
}
