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

import dev.javaindex.model.ClassModel;
import dev.javaindex.model.Member;
import dev.javaindex.model.Target;
import dev.javaindex.model.Universe;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Describes a configuration key: its values per profile, its Java bindings, its library docs. */
public final class ConfigReport {
  private ConfigReport() {}

  /** The declaration of a library property (its field, or accessor method), or null. */
  public static Target libraryTarget(LibraryConfig.Property p, Universe u) {
    if (p == null || p.sourceType() == null) {
      return null;
    }
    ClassModel m = u.find(p.sourceType());
    if (m == null) {
      return null;
    }
    String member = p.member() == null ? "" : p.member();
    String cap = member.isEmpty() ? "" : Character.toUpperCase(member.charAt(0)) + member.substring(1);
    if (!p.method()) {
      for (Member f : m.fields()) {
        if (f.name().equals(member)) {
          return f.target();
        }
      }
    }
    for (Member meth : m.methods()) {
      if (meth.arity() == 0
          && (meth.name().equals(member) || meth.name().equals("get" + cap) || meth.name().equals("is" + cap))) {
        return meth.target();
      }
    }
    return m.target();
  }

  /**
   * A markdown summary of {@code key}. {@code where} renders a file path (e.g. relative to the
   * worktree) and {@code markdown} selects tables vs. plain lines (for agents).
   */
  public static String describe(ConfigIndex index, String key, Function<Path, String> where, boolean markdown) {
    StringBuilder out = new StringBuilder();
    List<ConfigIndex.Definition> defs =
        index.definitions(key).stream().filter(d -> d.entry().value() != null).toList();
    out.append(markdown ? "**`" + key + "`**\n" : "# " + key + "\n");
    if (defs.isEmpty()) {
      out.append("\nNot set in this project's config files.\n");
    } else {
      out.append(markdown ? "\n| profile | value | where |\n|---|---|---|\n" : "\nValues:\n");
      for (ConfigIndex.Definition d : defs) {
        String profile = d.entry().profile() == null ? "(default)" : d.entry().profile();
        String value = d.entry().value().isEmpty() ? "\"\"" : d.entry().value();
        String loc = where.apply(d.file()) + ":" + (d.lines().line(d.entry().keyStart()) + 1);
        String shownKey = d.entry().key().equals(key) ? "" : " (" + d.entry().key() + ")";
        if (markdown) {
          out.append("| ").append(profile).append(" | `").append(value.replace("|", "\\|")).append("`")
              .append(shownKey).append(" | ").append(loc).append(" |\n");
        } else {
          out.append("- ").append(profile).append(": ").append(value).append(shownKey).append("  ").append(loc).append('\n');
        }
      }
    }
    List<ConfigIndex.Binding> bindings = index.bindings(key);
    if (!bindings.isEmpty()) {
      List<String> parts = new ArrayList<>();
      for (ConfigIndex.Binding b : bindings) {
        String loc = where.apply(b.file()) + ":" + (b.lines().line(b.start()) + 1);
        String what =
            switch (b.kind()) {
              case PROPERTY -> "`" + b.detail() + "`";
              case PLACEHOLDER -> "`${…}` placeholder";
              case NAME -> "`@ConfigProperty`";
              case LOOKUP -> "lookup call";
              case PREFIX -> "prefix of `" + (b.detail() == null ? "?" : b.detail().replaceAll(".*/", "")) + "`";
            };
        parts.add(what + " " + loc);
      }
      if (markdown) {
        out.append("\nUsed by: ").append(String.join(" · ", parts)).append('\n');
      } else {
        out.append("\nUsed by:\n");
        parts.forEach(p -> out.append("- ").append(p.replace("`", "")).append('\n'));
      }
    }
    LibraryConfig.Property p = index.library().lookup(ConfigKeys.canonical(key));
    if (p != null) {
      out.append(markdown ? "\n---\n\n" : "\nLibrary property:\n");
      List<String> facts = new ArrayList<>();
      if (p.type() != null) {
        facts.add((markdown ? "`" : "") + p.type().replaceAll("\\b(java\\.lang|java\\.util)\\.", "") + (markdown ? "`" : ""));
      }
      if (p.defaultValue() != null) {
        facts.add("default " + (markdown ? "`" + p.defaultValue() + "`" : p.defaultValue()));
      }
      if (p.deprecated()) {
        facts.add(markdown ? "**deprecated**" : "deprecated");
      }
      facts.add("from " + p.origin());
      out.append(String.join(" · ", facts)).append('\n');
      if (p.description() != null) {
        out.append('\n').append(p.description().strip()).append('\n');
      }
    } else if (defs.isEmpty() && bindings.isEmpty()) {
      out.append("\nUnknown key: not defined, bound, or documented by any library on the classpath.\n");
    }
    return out.toString();
  }
}
