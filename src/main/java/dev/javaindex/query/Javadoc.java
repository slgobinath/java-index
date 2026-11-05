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

package dev.javaindex.query;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the javadoc for a declaration and renders it as markdown. Comments are read from the source
 * on demand rather than kept in the index, so they cost nothing until someone hovers.
 */
public final class Javadoc {
  private static final Pattern INLINE = Pattern.compile("\\{@(code|literal|link|linkplain|value)\\s+([^}]*)}");

  private Javadoc() {}

  /** Markdown for the {@code /** ... *\/} comment preceding the declaration named at {@code nameStart}. */
  public static String before(String text, int nameStart) {
    int end = text.lastIndexOf("*/", nameStart);
    if (end < 0) {
      return null;
    }
    // Only modifiers, annotations and the type may sit between the comment and the name.
    String between = text.substring(end + 2, nameStart);
    if (between.contains(";") || between.contains("{") || between.contains("}") || between.contains("*/")) {
      return null;
    }
    int start = text.lastIndexOf("/**", end);
    if (start < 0 || text.lastIndexOf("*/", end - 1) > start) {
      return null;
    }
    return markdown(text.substring(start + 3, end));
  }

  public static String markdown(String raw) {
    List<String> body = new ArrayList<>();
    List<String> tags = new ArrayList<>();
    for (String line : raw.split("\n")) {
      String l = line.strip();
      if (l.startsWith("*")) {
        l = l.substring(1);
        if (l.startsWith(" ")) {
          l = l.substring(1);
        }
      }
      l = inline(l);
      if (l.startsWith("@") || (!tags.isEmpty() && !l.isBlank() && !l.startsWith("@"))) {
        if (l.startsWith("@")) {
          String[] parts = l.split("\\s+", 3);
          String tag = parts[0].substring(1);
          String rest =
              switch (tag) {
                case "param", "throws", "exception" ->
                    parts.length > 1
                        ? "`" + parts[1] + "`" + (parts.length > 2 ? " " + parts[2] : "")
                        : "";
                default -> l.substring(parts[0].length()).strip();
              };
          tags.add("*@" + tag + "* " + rest);
        } else {
          tags.set(tags.size() - 1, tags.getLast() + " " + l.strip());
        }
        continue;
      }
      body.add(l);
    }
    String text =
        String.join("\n", body)
            .replaceAll("(?i)<p>\\s*", "\n\n")
            .replaceAll("(?i)</p>", "")
            .replaceAll("(?i)<pre>\\s*", "\n```java\n")
            .replaceAll("(?i)\\s*</pre>", "\n```\n")
            .replaceAll("(?i)</?(b|strong)>", "**")
            .replaceAll("(?i)</?(i|em)>", "*")
            .replaceAll("(?i)<li>\\s*", "\n- ")
            .replaceAll("(?i)</?(ul|ol|li|br/?)>", "")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .strip();
    StringBuilder out = new StringBuilder(text);
    if (!tags.isEmpty()) {
      out.append("\n\n");
      for (String tag : tags) {
        out.append("- ").append(tag).append('\n');
      }
    }
    String result = out.toString().strip();
    return result.isEmpty() ? null : result;
  }

  private static String inline(String line) {
    Matcher m = INLINE.matcher(line);
    StringBuilder sb = new StringBuilder();
    while (m.find()) {
      String value = m.group(2).strip();
      if (m.group(1).startsWith("link")) {
        // {@link Foo#bar(int) label} -> `label` or `Foo.bar(int)`
        int space = value.indexOf(' ');
        value = space > 0 && !value.substring(0, space).contains("(") ? value.substring(space + 1) : value.replace('#', '.');
        if (value.startsWith(".")) {
          value = value.substring(1);
        }
      }
      m.appendReplacement(sb, Matcher.quoteReplacement("`" + value + "`"));
    }
    m.appendTail(sb);
    return sb.toString();
  }
}
