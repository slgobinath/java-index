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

import dev.javaindex.index.Lines;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;

/**
 * The entries of one configuration file ({@code .properties} or YAML), with key positions and
 * the profile each entry applies to: from the file name ({@code application-dev.yml}), a YAML
 * document's {@code spring.config.activate.on-profile}, or a Quarkus {@code %dev.} prefix.
 */
public final class ConfigFile {
  /**
   * One key/value. {@code key} is as written (without a Quarkus profile prefix); {@code value} is
   * null for keys that only hold nested entries.
   */
  public record Entry(String key, String canonical, String value, String profile, int keyStart, int keyEnd) {}

  public final List<Entry> entries;
  public final Lines lines;

  private ConfigFile(List<Entry> entries, Lines lines) {
    this.entries = List.copyOf(entries);
    this.lines = lines;
  }

  public static ConfigFile parse(String path, String text) {
    String profile = ConfigKeys.fileProfile(path);
    List<Entry> entries = new ArrayList<>();
    Lines lines = Lines.of(text);
    if (path.endsWith(".properties")) {
      properties(text, profile, entries);
    } else {
      yaml(text, profile, lines, entries);
    }
    return new ConfigFile(entries, lines);
  }

  /** The entry whose key spans {@code offset}, or null. */
  public Entry entryAt(int offset) {
    for (Entry e : entries) {
      if (e.keyStart <= offset && offset <= e.keyEnd) {
        return e;
      }
    }
    return null;
  }

  // ---------------------------------------------------------------------------------------------

  private static void properties(String text, String fileProfile, List<Entry> out) {
    int i = 0;
    int n = text.length();
    while (i < n) {
      int lineStart = i;
      int lineEnd = text.indexOf('\n', i);
      if (lineEnd < 0) {
        lineEnd = n;
      }
      int k = lineStart;
      while (k < lineEnd && (text.charAt(k) == ' ' || text.charAt(k) == '\t' || text.charAt(k) == '\f')) {
        k++;
      }
      if (k >= lineEnd || text.charAt(k) == '#' || text.charAt(k) == '!') {
        i = lineEnd + 1;
        continue;
      }
      // key: up to an unescaped '=', ':' or whitespace
      int keyStart = k;
      StringBuilder key = new StringBuilder();
      while (k < lineEnd) {
        char c = text.charAt(k);
        if (c == '\\' && k + 1 < lineEnd) {
          key.append(text.charAt(k + 1));
          k += 2;
          continue;
        }
        if (c == '=' || c == ':' || Character.isWhitespace(c)) {
          break;
        }
        key.append(c);
        k++;
      }
      int keyEnd = k;
      while (k < lineEnd && Character.isWhitespace(text.charAt(k))) {
        k++;
      }
      if (k < lineEnd && (text.charAt(k) == '=' || text.charAt(k) == ':')) {
        k++;
      }
      // value, joining continuation lines ending in an odd number of backslashes
      StringBuilder value = new StringBuilder();
      int end = lineEnd;
      String chunk = text.substring(k, lineEnd).stripLeading();
      while (chunk.endsWith("\\") && end < n) {
        value.append(chunk, 0, chunk.length() - 1);
        int next = text.indexOf('\n', end + 1);
        next = next < 0 ? n : next;
        chunk = text.substring(end + 1, next).stripLeading();
        end = next;
      }
      value.append(chunk);
      add(out, key.toString(), value.toString().strip(), fileProfile, keyStart, keyEnd);
      i = end + 1;
    }
  }

  private static void yaml(String text, String fileProfile, Lines lines, List<Entry> out) {
    try {
      LoaderOptions options = new LoaderOptions();
      options.setAllowDuplicateKeys(true);
      for (Node doc : new Yaml(options).composeAll(new StringReader(text))) {
        List<Entry> docEntries = new ArrayList<>();
        walk(doc, "", fileProfile, lines, docEntries);
        // Spring: a document can apply to one profile only.
        String docProfile = fileProfile;
        for (Entry e : docEntries) {
          if (e.value != null
              && (e.canonical.equals("spring.config.activate.onprofile") || e.canonical.equals("spring.profiles"))) {
            docProfile = e.value;
          }
        }
        for (Entry e : docEntries) {
          String p = e.profile != null && !e.profile.equals(fileProfile) ? e.profile : docProfile;
          out.add(new Entry(e.key, e.canonical, e.value, p, e.keyStart, e.keyEnd));
        }
      }
    } catch (RuntimeException e) {
      // a YAML syntax error (typically mid-edit): keep the documents parsed before it
    }
  }

  private static void walk(Node node, String prefix, String profile, Lines lines, List<Entry> out) {
    if (node instanceof MappingNode map) {
      for (NodeTuple tuple : map.getValue()) {
        if (!(tuple.getKeyNode() instanceof ScalarNode keyNode)) {
          continue;
        }
        String segment = keyNode.getValue();
        String entryProfile = profile;
        String key;
        if (prefix.isEmpty() && segment.startsWith("%")) {
          // Quarkus YAML: "%dev": { ... } or "%dev.app.timeout": ...
          int dot = segment.indexOf('.');
          entryProfile = dot < 0 ? segment.substring(1) : segment.substring(1, dot);
          if (dot < 0) {
            walk(tuple.getValueNode(), "", entryProfile, lines, out);
            continue;
          }
          key = segment.substring(dot + 1);
        } else {
          key = prefix.isEmpty() ? segment : prefix + "." + segment;
        }
        int start = offset(lines, keyNode.getStartMark().getLine(), keyNode.getStartMark().getColumn());
        int end = offset(lines, keyNode.getEndMark().getLine(), keyNode.getEndMark().getColumn());
        Node value = tuple.getValueNode();
        if (value instanceof ScalarNode scalar) {
          add(out, key, scalar.getValue(), entryProfile, start, end);
        } else {
          add(out, key, null, entryProfile, start, end);
          walk(value, key, entryProfile, lines, out);
        }
      }
    } else if (node instanceof SequenceNode seq) {
      List<Node> items = seq.getValue();
      for (int i = 0; i < items.size(); i++) {
        Node item = items.get(i);
        if (item instanceof ScalarNode scalar) {
          int start = offset(lines, scalar.getStartMark().getLine(), scalar.getStartMark().getColumn());
          int end = offset(lines, scalar.getEndMark().getLine(), scalar.getEndMark().getColumn());
          add(out, prefix + "[" + i + "]", scalar.getValue(), profile, start, end);
        } else {
          walk(item, prefix + "[" + i + "]", profile, lines, out);
        }
      }
    }
  }

  private static int offset(Lines lines, int line, int column) {
    return lines.offset(line, column);
  }

  private static void add(List<Entry> out, String key, String value, String profile, int start, int end) {
    String p = profile;
    String k = key;
    if (k.startsWith("%")) {
      // Quarkus properties: %dev.app.timeout=5
      int dot = k.indexOf('.');
      if (dot > 1) {
        p = k.substring(1, dot);
        k = k.substring(dot + 1);
        start += dot + 1;
      }
    }
    out.add(new Entry(k, ConfigKeys.canonical(k), value, p, start, end));
  }
}
