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

package dev.javaindex.resolve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.javaindex.classpath.Dependencies;
import dev.javaindex.index.FileSummary;
import dev.javaindex.index.Snapshot;
import dev.javaindex.index.Summarizer;
import dev.javaindex.index.WorktreeIndex;
import dev.javaindex.model.Member;
import dev.javaindex.model.Target;
import dev.javaindex.model.Universe;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ResolverTest {
  private static final Path ROOT = Path.of("/project");

  private final Map<String, String> sources = new LinkedHashMap<>();

  private void file(String rel, String text) {
    sources.put(rel, text);
  }

  /** Resolves at the {@code nth} occurrence of {@code needle} in {@code rel}. */
  private Target resolve(String rel, String needle, int nth) {
    Map<String, FileSummary> summaries = new HashMap<>();
    sources.forEach(
        (r, text) -> summaries.put(r, Summarizer.summarize(Summarizer.hash(text), r, text)));
    WorktreeIndex index = new WorktreeIndex(Snapshot.of(summaries));
    Universe u = new Universe(index, ROOT, Dependencies.EMPTY, Dependencies.jdk());
    String text = sources.get(rel);
    int offset = -1;
    for (int i = 0; i < nth; i++) {
      offset = text.indexOf(needle, offset + 1);
    }
    assertTrue(offset >= 0, "needle not found: " + needle);
    List<Target> targets =
        new Resolver(u, text, summaries.get(rel), ROOT.resolve(rel)).definition(offset + 1);
    return targets.isEmpty() ? null : targets.get(0);
  }

  private Target resolve(String rel, String needle) {
    return resolve(rel, needle, 1);
  }

  /** Asserts the target is the identifier {@code name} in {@code rel} (its nth occurrence). */
  private void assertSource(Target target, String rel, String name, int nth) {
    assertTrue(target instanceof Target.Source, "expected source target, got " + target);
    Target.Source s = (Target.Source) target;
    assertEquals(ROOT.resolve(rel), s.file());
    String text = sources.get(rel);
    int offset = -1;
    for (int i = 0; i < nth; i++) {
      offset = text.indexOf(name, offset + 1);
    }
    assertEquals(offset, s.start(), "wrong position for " + name);
  }

  @Test
  void resolvesTypesFieldsMethodsAndLocals() {
    file(
        "src/a/Repo.java",
        """
        package a;
        import java.util.List;
        public class Repo<T> {
          public List<T> items;
          public T first() { return null; }
          public static Repo<String> create() { return new Repo<>(); }
          public Repo() {}
          public Repo(int capacity) {}
        }
        """);
    file(
        "src/b/Service.java",
        """
        package b;
        import a.Repo;
        import java.util.*;
        public class Service {
          private final Repo<Item> repo = new Repo<>(10);
          static class Item { String label; void ping() {} }
          void run(Map<String, Item> byName) {
            Item local = repo.first();
            local.ping();
            byName.get("x").label.length();
            var list = new ArrayList<Item>();
            for (var it : list) { it.ping(); }
            repo.items.get(0).ping();
            Runnable r = () -> local.ping();
            Repo.create().first().length();
          }
        }
        """);

    assertSource(resolve("src/b/Service.java", "Repo<Item>"), "src/a/Repo.java", "Repo", 1);
    // new Repo<>(10) goes to the int constructor
    assertSource(resolve("src/b/Service.java", "Repo<>(10)"), "src/a/Repo.java", "Repo(int", 1);
    assertSource(resolve("src/b/Service.java", "first()"), "src/a/Repo.java", "first", 1);
    assertSource(resolve("src/b/Service.java", "local.ping", 1), "src/b/Service.java", "local", 1);
    assertSource(resolve("src/b/Service.java", "ping();", 1), "src/b/Service.java", "ping", 1);
    assertSource(resolve("src/b/Service.java", "label.length"), "src/b/Service.java", "label", 1);
    assertSource(resolve("src/b/Service.java", "ping(); }"), "src/b/Service.java", "ping", 1);
    assertSource(resolve("src/b/Service.java", "items.get"), "src/a/Repo.java", "items", 1);
    assertSource(resolve("src/b/Service.java", "ping();\n    Runnable"), "src/b/Service.java", "ping", 1);
    assertSource(resolve("src/b/Service.java", "create()"), "src/a/Repo.java", "create", 1);

    // JDK members resolve to bytecode targets
    Target length = resolve("src/b/Service.java", "length()", 1);
    assertTrue(length instanceof Target.Binary b && b.classBinary().equals("java/lang/String"));
    Target get = resolve("src/b/Service.java", "get(\"x\")");
    assertTrue(get instanceof Target.Binary b && b.classBinary().equals("java/util/Map"));
    assertEquals(Member.Kind.METHOD, ((Target.Binary) get).kind());
    // String from Repo<String>.first()
    Target length2 = resolve("src/b/Service.java", "length()", 2);
    assertTrue(length2 instanceof Target.Binary b && b.classBinary().equals("java/lang/String"));
  }

  @Test
  void resolvesInheritanceAnonymousClassesAndStaticImports() {
    file(
        "src/a/Base.java",
        """
        package a;
        public abstract class Base {
          protected int count;
          public static final String NAME = "n";
          protected void inherited() {}
          public interface Listener { void onEvent(String e); }
        }
        """);
    file(
        "src/a/Child.java",
        """
        package a;
        import static a.Base.NAME;
        public class Child extends Base {
          void go() {
            inherited();
            count++;
            NAME.length();
            Listener l = new Listener() {
              @Override public void onEvent(String e) { inherited(); e.trim(); }
            };
            l.onEvent(NAME);
            this.inherited();
            Base.Listener other = null;
          }
          enum Mode { ON, OFF; Mode flip() { return this == ON ? OFF : ON; } }
          record Point(int x, int y) { int sum() { return x + y(); } }
        }
        """);
    assertSource(resolve("src/a/Child.java", "inherited();", 1), "src/a/Base.java", "inherited", 1);
    assertSource(resolve("src/a/Child.java", "count++"), "src/a/Base.java", "count", 1);
    assertSource(resolve("src/a/Child.java", "NAME.length"), "src/a/Base.java", "NAME", 1);
    assertSource(resolve("src/a/Child.java", "Listener l"), "src/a/Base.java", "Listener", 1);
    assertSource(resolve("src/a/Child.java", "inherited(); e"), "src/a/Base.java", "inherited", 1);
    assertSource(resolve("src/a/Child.java", "onEvent(NAME)"), "src/a/Base.java", "onEvent", 1);
    assertSource(resolve("src/a/Child.java", "inherited();\n", 2), "src/a/Base.java", "inherited", 1);
    assertSource(resolve("src/a/Child.java", "Listener other"), "src/a/Base.java", "Listener", 1);
    assertSource(resolve("src/a/Child.java", "OFF :"), "src/a/Child.java", "OFF", 1);
    assertSource(resolve("src/a/Child.java", "x + "), "src/a/Child.java", "x,", 1);
    assertSource(resolve("src/a/Child.java", "y()"), "src/a/Child.java", "y)", 1);
    Target trim = resolve("src/a/Child.java", "trim");
    assertTrue(trim instanceof Target.Binary b && b.classBinary().equals("java/lang/String"));
  }

  @Test
  void toleratesBrokenBuffers() {
    file(
        "src/a/Ok.java",
        """
        package a;
        public class Ok {
          void m() {
            String s = "unterminated;
            Ok other = new Ok();
            other.m();
          }
        }
        """);
    assertSource(resolve("src/a/Ok.java", "m();"), "src/a/Ok.java", "m()", 1);
  }

  @Test
  void infersGenericMethodsAndLambdaParameters() {
    file(
        "src/a/Person.java",
        """
        package a;
        public class Person implements Comparable<Person> {
          public String getName() { return ""; }
          public int getAge() { return 0; }
          public Address address() { return null; }
          public int compareTo(Person o) { return 0; }
        }
        """);
    file(
        "src/a/Address.java",
        """
        package a;
        public class Address { public String city; }
        """);
    file(
        "src/a/Service.java",
        """
        package a;
        import java.util.*;
        class Service {
          void run(List<Person> people, Map<String, List<Person>> byCity) {
            people.forEach(p -> p.getName());
            people.stream().map(p -> p.address()).map(a -> a.city).filter(c -> c.isEmpty());
            people.stream().map(Person::getName).forEach(n -> n.trim());
            Optional.of(people.get(0)).get().getAge();
            List.of(new Person()).get(0).getName();
            byCity.computeIfAbsent("x", key -> new ArrayList<>()).add(null);
            people.stream().map(p -> { Address addr = p.address(); return addr; }).findFirst().get().city.length();
            Comparator<Person> cmp = (x, y) -> x.getName().compareTo(y.getName());
            Collections.max(people).getAge();
            people.forEach((Person typed) -> typed.address());
          }
        }
        """);
    String svc = "src/a/Service.java";
    // forEach(Consumer<? super T>): p is a Person
    assertSource(resolve(svc, "getName());"), "src/a/Person.java", "getName", 1);
    // map(Function<? super T, ? extends R>) binds R from the lambda body, through two maps
    assertSource(resolve(svc, "city).filter"), "src/a/Address.java", "city", 1);
    Target isEmpty = resolve(svc, "isEmpty");
    assertTrue(isEmpty instanceof Target.Binary b && b.classBinary().equals("java/lang/String"), "" + isEmpty);
    // a method reference binds R from the referenced method's return type
    Target trim = resolve(svc, "trim");
    assertTrue(trim instanceof Target.Binary b && b.classBinary().equals("java/lang/String"), "" + trim);
    // static generic factory: Optional.of(T) -> Optional<Person>
    assertSource(resolve(svc, "getAge();", 1), "src/a/Person.java", "getAge", 1);
    // varargs generic factory: List.of(E...) -> List<Person>
    assertSource(resolve(svc, "getName();\n"), "src/a/Person.java", "getName", 1);
    // lambda parameter typed from a generic parameter: key is the map's key type
    Target key = resolve(svc, "key ->");
    assertTrue(key instanceof Target.Source, "" + key);
    // block lambda: R comes from its return statement
    Target length = resolve(svc, "length()");
    assertTrue(length instanceof Target.Binary b && b.classBinary().equals("java/lang/String"), "" + length);
    // assignment target: Comparator<Person>.compare(T, T) types x and y
    Target compareTo = resolve(svc, "compareTo(y");
    assertTrue(compareTo instanceof Target.Binary b && b.classBinary().equals("java/lang/String"), "" + compareTo);
    assertSource(resolve(svc, "getName());\n    Collections"), "src/a/Person.java", "getName", 1);
    // <T extends Object & Comparable<? super T>> T max(Collection<? extends T>)
    assertSource(resolve(svc, "getAge();", 2), "src/a/Person.java", "getAge", 1);
    // explicitly typed lambda parameter
    assertSource(resolve(svc, "address());\n  }"), "src/a/Person.java", "address", 1);
  }
}
