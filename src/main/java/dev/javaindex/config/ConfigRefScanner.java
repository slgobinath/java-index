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

import com.google.turbine.parse.Token;
import dev.javaindex.index.FileSummary.ConfigRef;
import dev.javaindex.index.Tokens;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Finds configuration keys named in Java source: Spring {@code @Value("${...}")} and
 * {@code @ConfigurationProperties}, MicroProfile/Quarkus {@code @ConfigProperty},
 * {@code @ConfigMapping} and {@code @WithName}, and lookups like
 * {@code environment.getProperty("app.timeout")}.
 */
public final class ConfigRefScanner {
  private ConfigRefScanner() {}

  private static final Set<String> LOOKUPS =
      Set.of(
          "getProperty",
          "getRequiredProperty",
          "containsProperty",
          "getValue",
          "getOptionalValue",
          "getConfigValue",
          "getOptionalValues",
          "getValues");

  public static List<ConfigRef> scan(Tokens t) {
    List<ConfigRef> out = new ArrayList<>();
    for (int i = 0; i < t.size(); i++) {
      if (t.is(i, Token.AT) && t.is(i + 1, Token.IDENT)) {
        int nameTok = i + 1;
        while (t.is(nameTok + 1, Token.DOT) && t.is(nameTok + 2, Token.IDENT)) {
          nameTok += 2;
        }
        String annotation = t.text(nameTok);
        int open = nameTok + 1;
        if (!t.is(open, Token.LPAREN) || t.match(open) < 0) {
          continue;
        }
        annotation(t, annotation, open, t.match(open), out);
        i = nameTok;
      } else if (t.is(i, Token.IDENT)
          && LOOKUPS.contains(t.text(i))
          && t.is(i + 1, Token.LPAREN)
          && t.is(i + 2, Token.STRING_LITERAL)
          && t.is(i - 1, Token.DOT)) {
        literalKey(t, i + 2, ConfigRef.Kind.LOOKUP, out);
      }
    }
    return out;
  }

  private static void annotation(Tokens t, String name, int open, int close, List<ConfigRef> out) {
    switch (name) {
      case "Value" -> {
        for (int j = open + 1; j < close; j++) {
          if (t.is(j, Token.STRING_LITERAL)) {
            placeholders(t, j, out);
          }
        }
      }
      case "ConfigProperty" -> {
        int lit = attribute(t, open, close, "name", false);
        if (lit >= 0) {
          literalKey(t, lit, ConfigRef.Kind.NAME, out);
        }
      }
      case "ConfigurationProperties" -> {
        int lit = attribute(t, open, close, "prefix", true);
        if (lit < 0) {
          lit = attribute(t, open, close, "value", true);
        }
        if (lit >= 0) {
          literalKey(t, lit, ConfigRef.Kind.PREFIX, out);
        }
      }
      case "ConfigMapping" -> {
        int lit = attribute(t, open, close, "prefix", false);
        if (lit >= 0) {
          literalKey(t, lit, ConfigRef.Kind.PREFIX, out);
        }
      }
      case "WithName" -> {
        int lit = attribute(t, open, close, "value", true);
        if (lit >= 0) {
          literalKey(t, lit, ConfigRef.Kind.RENAME, out);
        }
      }
      default -> {}
    }
  }

  /**
   * The string literal given for {@code attr = "..."}, or (when {@code positional}) the sole
   * unnamed argument; -1 if absent.
   */
  private static int attribute(Tokens t, int open, int close, String attr, boolean positional) {
    for (int j = open + 1; j < close; j++) {
      if (t.isIdent(j, attr) && t.is(j + 1, Token.ASSIGN) && t.is(j + 2, Token.STRING_LITERAL)) {
        return j + 2;
      }
    }
    if (positional && t.is(open + 1, Token.STRING_LITERAL) && (open + 2 == close)) {
      return open + 1;
    }
    return -1;
  }

  private static void literalKey(Tokens t, int lit, ConfigRef.Kind kind, List<ConfigRef> out) {
    String value = t.stringValue(lit);
    if (value != null && !value.isBlank()) {
      int start = t.start(lit) + 1;
      out.add(new ConfigRef(kind, value.strip(), start, start + value.length(), null));
    }
  }

  private static void placeholders(Tokens t, int lit, List<ConfigRef> out) {
    String value = t.stringValue(lit);
    if (value == null) {
      return;
    }
    int base = t.start(lit) + 1;
    for (ConfigKeys.Placeholder p : ConfigKeys.placeholders(value)) {
      out.add(new ConfigRef(ConfigRef.Kind.PLACEHOLDER, p.key(), base + p.start(), base + p.end(), null));
    }
  }
}
