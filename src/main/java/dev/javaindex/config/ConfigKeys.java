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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Configuration key spelling: relaxed matching, placeholders and naming conventions. */
public final class ConfigKeys {
  private ConfigKeys() {}

  /**
   * The form keys are compared in. Spring's relaxed binding treats {@code app.my-prop},
   * {@code app.myProp} and {@code app.my_prop} as one key; list indexes ({@code [0]}) are dropped
   * so {@code app.servers[0].host} matches the element type's {@code host} property.
   */
  public static String canonical(String key) {
    StringBuilder sb = new StringBuilder(key.length());
    for (int i = 0; i < key.length(); i++) {
      char c = key.charAt(i);
      if (c == '[') {
        int close = key.indexOf(']', i);
        if (close > i) {
          String inside = key.substring(i + 1, close);
          if (!inside.chars().allMatch(Character::isDigit)) {
            // a map key written as [key]: keep it as a path segment
            sb.append('.').append(inside.replace("\"", "").replace("'", "").toLowerCase(Locale.ROOT));
          }
          i = close;
          continue;
        }
      }
      if (c != '-' && c != '_') {
        sb.append(Character.toLowerCase(c));
      }
    }
    return sb.toString();
  }

  /** {@code myProp} -> {@code my-prop} (Spring and Quarkus @ConfigMapping default naming). */
  public static String kebab(String name) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      if (Character.isUpperCase(c) && i > 0) {
        sb.append('-');
      }
      sb.append(Character.toLowerCase(c));
    }
    return sb.toString();
  }

  /** A key referenced inside a string, with its offsets in that string. */
  public record Placeholder(String key, int start, int end) {}

  /**
   * Keys in {@code ${key}} or {@code ${key:default}} placeholders (nested defaults included), as
   * used by Spring's {@code @Value} and Quarkus property expressions.
   */
  public static List<Placeholder> placeholders(String value) {
    List<Placeholder> out = new ArrayList<>();
    int i = value.indexOf("${");
    while (i >= 0) {
      int start = i + 2;
      int end = start;
      while (end < value.length() && ":}$".indexOf(value.charAt(end)) < 0) {
        end++;
      }
      String key = value.substring(start, end).strip();
      if (!key.isEmpty()) {
        out.add(new Placeholder(key, start, end));
      }
      i = value.indexOf("${", start);
    }
    return out;
  }

  /** True for files Spring Boot or Quarkus read configuration from. */
  public static boolean isConfigFile(String path) {
    String name = path.substring(path.lastIndexOf('/') + 1);
    boolean ext = name.endsWith(".properties") || name.endsWith(".yml") || name.endsWith(".yaml");
    return ext
        && (name.startsWith("application") || name.startsWith("bootstrap") || name.equals("microprofile-config.properties"));
  }

  /** The profile encoded in a file name: {@code application-dev.yml} -> {@code dev}, else null. */
  public static String fileProfile(String path) {
    String name = path.substring(path.lastIndexOf('/') + 1);
    int dot = name.lastIndexOf('.');
    String base = dot < 0 ? name : name.substring(0, dot);
    for (String prefix : List.of("application-", "bootstrap-")) {
      if (base.startsWith(prefix) && base.length() > prefix.length()) {
        return base.substring(prefix.length());
      }
    }
    return null;
  }
}
