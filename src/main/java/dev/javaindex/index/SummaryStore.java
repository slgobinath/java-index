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
package dev.javaindex.index;

import com.google.common.collect.ImmutableList;
import com.google.turbine.model.TurbineConstantTypeKind;
import com.google.turbine.model.TurbineTyKind;
import com.google.turbine.tree.Tree;
import dev.javaindex.Version;
import dev.javaindex.index.FileSummary.ConfigRef;
import dev.javaindex.index.FileSummary.FieldDecl;
import dev.javaindex.index.FileSummary.ImportInfo;
import dev.javaindex.index.FileSummary.MethodDecl;
import dev.javaindex.index.FileSummary.Span;
import dev.javaindex.index.FileSummary.TypeInfo;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Summaries saved on disk, so a restarted daemon parses only the files that changed. Entries are
 * keyed by content hash like {@link SourceCache}, so a stale entry is never used: a changed file
 * has a different hash. The file starts with the java-index version; any other version (and any
 * unreadable file) counts as empty, since the summary format follows the code.
 *
 * <p>Line tables aren't stored: they are rebuilt from the file text, which a scan reads anyway.
 * Type trees keep only what is read after parsing (names, type arguments, array and wildcard
 * structure); annotations on types are dropped. A file whose trees hold anything else is left out
 * and parsed again on the next start.
 */
public final class SummaryStore {
  private static final Logger LOG = Logger.getLogger(SummaryStore.class.getName());
  private static final int MAGIC = 0x4a495853; // "JIXS"

  private SummaryStore() {}

  /**
   * Summaries read back from disk, decoded one at a time on request (scans ask from many threads),
   * so a restart pays only for the files that are still there.
   */
  public static final class Stored {
    private static final Stored EMPTY = new Stored(new byte[0], Map.of());
    private final byte[] data;
    private final Map<String, int[]> entries;

    private Stored(byte[] data, Map<String, int[]> entries) {
      this.data = data;
      this.entries = entries;
    }

    public int size() {
      return entries.size();
    }

    /** The summary stored for {@code hash} with {@code lines} (they aren't stored), or null. */
    public FileSummary get(String hash, java.util.function.Supplier<Lines> lines) {
      int[] at = entries.get(hash);
      if (at == null) {
        return null;
      }
      try {
        return new Reader(
                new DataInputStream(new java.io.ByteArrayInputStream(data, at[0], at[1])))
            .summary(hash, lines.get());
      } catch (IOException | RuntimeException e) {
        LOG.log(Level.FINE, "ignoring unreadable stored summary " + hash, e);
        return null;
      }
    }
  }

  /** The stored summaries; empty if there are none or they were written by another version. */
  public static Stored load(Path file) {
    if (!Files.isRegularFile(file)) {
      return Stored.EMPTY;
    }
    try {
      byte[] data = Files.readAllBytes(file);
      DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(data));
      if (in.readInt() != MAGIC || !in.readUTF().equals(Version.VERSION)) {
        return Stored.EMPTY;
      }
      int count = in.readInt();
      Map<String, int[]> entries = new HashMap<>(count * 2);
      int offset = data.length - in.available();
      for (int i = 0; i < count; i++) {
        String hash = in.readUTF();
        int length = in.readInt();
        offset = data.length - in.available();
        entries.put(hash, new int[] {offset, length});
        if (in.skipBytes(length) != length) {
          throw new IOException("truncated");
        }
      }
      return new Stored(data, entries);
    } catch (IOException | RuntimeException e) {
      LOG.log(Level.INFO, "ignoring unreadable summary cache " + file, e);
      return Stored.EMPTY;
    }
  }

  /** Writes {@code summaries} to {@code file}, replacing it atomically. */
  public static void save(Path file, Collection<FileSummary> summaries) throws IOException {
    Files.createDirectories(file.getParent());
    Path tmp = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
    try {
      List<FileSummary> kept = new ArrayList<>(summaries.size());
      List<byte[]> encoded = new ArrayList<>(summaries.size());
      for (FileSummary s : summaries) {
        byte[] bytes = encode(s);
        if (bytes != null) {
          kept.add(s);
          encoded.add(bytes);
        }
      }
      try (DataOutputStream out =
          new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp), 1 << 16))) {
        out.writeInt(MAGIC);
        out.writeUTF(Version.VERSION);
        out.writeInt(encoded.size());
        for (int i = 0; i < encoded.size(); i++) {
          out.writeUTF(kept.get(i).hash);
          out.writeInt(encoded.get(i).length);
          out.write(encoded.get(i));
        }
      }
      Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } finally {
      Files.deleteIfExists(tmp);
    }
  }

  /** One summary on its own, or null when its trees hold something this format doesn't keep. */
  private static byte[] encode(FileSummary s) throws IOException {
    java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(1024);
    try (DataOutputStream out = new DataOutputStream(bytes)) {
      new Writer(out).summary(s);
    } catch (Unsupported e) {
      return null;
    }
    return bytes.toByteArray();
  }

  private static final class Unsupported extends RuntimeException {
    Unsupported(Object what) {
      super(String.valueOf(what), null, false, false);
    }
  }

  // Tree tags
  private static final int NONE = 0;
  private static final int CLASS = 1;
  private static final int ARRAY = 2;
  private static final int PRIM = 3;
  private static final int VOID = 4;
  private static final int WILD = 5;

  private static final class Writer {
    private final DataOutputStream out;

    Writer(DataOutputStream out) {
      this.out = out;
    }

    void summary(FileSummary s) throws IOException {
      out.writeUTF(s.pkg);
      out.writeBoolean(s.parsed);
      out.writeInt(s.imports.size());
      for (ImportInfo i : s.imports) {
        strings(i.names());
        out.writeBoolean(i.isStatic());
        out.writeBoolean(i.wild());
      }
      out.writeInt(s.types.size());
      for (TypeInfo t : s.types) {
        type(t);
      }
      long[] filter = s.identFilter();
      out.writeInt(filter.length);
      for (long w : filter) {
        out.writeLong(w);
      }
      out.writeInt(s.configRefs.size());
      for (ConfigRef c : s.configRefs) {
        out.writeByte(c.kind().ordinal());
        out.writeUTF(c.key());
        out.writeInt(c.start());
        out.writeInt(c.end());
        nullable(c.owner());
      }
    }

    void type(TypeInfo t) throws IOException {
      out.writeUTF(t.binaryName);
      out.writeUTF(t.simpleName);
      out.writeByte(t.kind.ordinal());
      span(t.span);
      strings(t.typeParams);
      trees(t.typeParamBounds);
      tree(t.superclass);
      trees(t.interfaces);
      out.writeInt(t.fields.size());
      for (FieldDecl f : t.fields) {
        out.writeUTF(f.name());
        tree(f.type());
        span(f.span());
        out.writeBoolean(f.isStatic());
        out.writeByte(f.kind().ordinal());
        out.writeBoolean(f.deprecated());
      }
      out.writeInt(t.methods.size());
      for (MethodDecl m : t.methods) {
        out.writeUTF(m.name());
        trees(m.paramTypes());
        strings(m.paramNames());
        out.writeBoolean(m.varargs());
        tree(m.returnType());
        strings(m.typeParams());
        trees(m.typeParamBounds());
        span(m.span());
        out.writeBoolean(m.isStatic());
        out.writeBoolean(m.isAbstract());
        out.writeBoolean(m.deprecated());
      }
      out.writeInt(t.members.size());
      for (TypeInfo m : t.members) {
        type(m);
      }
      out.writeBoolean(t.deprecated);
    }

    void span(Span s) throws IOException {
      out.writeInt(s.nameStart());
      out.writeInt(s.nameEnd());
      out.writeInt(s.start());
      out.writeInt(s.end());
    }

    void strings(List<String> list) throws IOException {
      out.writeInt(list.size());
      for (String v : list) {
        nullable(v);
      }
    }

    void nullable(String v) throws IOException {
      out.writeBoolean(v != null);
      if (v != null) {
        out.writeUTF(v);
      }
    }

    void trees(List<? extends Tree> list) throws IOException {
      out.writeInt(list.size());
      for (Tree t : list) {
        tree(t);
      }
    }

    void tree(Tree t) throws IOException {
      switch (t) {
        case null -> out.writeByte(NONE);
        case Tree.ClassTy c -> {
          out.writeByte(CLASS);
          out.writeInt(c.position());
          tree(c.base().orElse(null));
          out.writeInt(c.name().position());
          out.writeUTF(c.name().value());
          trees(c.tyargs());
        }
        case Tree.ArrTy a -> {
          out.writeByte(ARRAY);
          out.writeInt(a.position());
          tree(a.elem());
        }
        case Tree.PrimTy p -> {
          out.writeByte(PRIM);
          out.writeInt(p.position());
          out.writeUTF(p.tykind().name());
        }
        case Tree.VoidTy v -> {
          out.writeByte(VOID);
          out.writeInt(v.position());
        }
        case Tree.WildTy w -> {
          out.writeByte(WILD);
          out.writeInt(w.position());
          tree(w.upper().orElse(null));
          tree(w.lower().orElse(null));
        }
        default -> throw new Unsupported(t.kind());
      }
    }
  }

  private static final class Reader {
    private final DataInputStream in;

    Reader(DataInputStream in) {
      this.in = in;
    }

    FileSummary summary(String hash, Lines lines) throws IOException {
      String pkg = in.readUTF();
      boolean parsed = in.readBoolean();
      int imports = in.readInt();
      List<ImportInfo> importList = new ArrayList<>(imports);
      for (int i = 0; i < imports; i++) {
        importList.add(new ImportInfo(strings(), in.readBoolean(), in.readBoolean()));
      }
      int types = in.readInt();
      List<TypeInfo> typeList = new ArrayList<>(types);
      for (int i = 0; i < types; i++) {
        typeList.add(type(null));
      }
      long[] filter = new long[in.readInt()];
      for (int i = 0; i < filter.length; i++) {
        filter[i] = in.readLong();
      }
      int refs = in.readInt();
      List<ConfigRef> refList = new ArrayList<>(refs);
      for (int i = 0; i < refs; i++) {
        refList.add(
            new ConfigRef(
                ConfigRef.Kind.values()[in.readByte()], in.readUTF(), in.readInt(), in.readInt(), nullable()));
      }
      return new FileSummary(
          hash,
          pkg,
          List.copyOf(importList),
          List.copyOf(typeList),
          parsed,
          lines,
          filter,
          List.copyOf(refList));
    }

    TypeInfo type(TypeInfo outer) throws IOException {
      String binaryName = in.readUTF();
      String simpleName = in.readUTF();
      TurbineTyKind kind = TurbineTyKind.values()[in.readByte()];
      Span span = span();
      List<String> typeParams = strings();
      List<Tree> typeParamBounds = trees();
      Tree.ClassTy superclass = (Tree.ClassTy) tree();
      List<Tree.ClassTy> interfaces = new ArrayList<>();
      for (Tree t : trees()) {
        interfaces.add((Tree.ClassTy) t);
      }
      int fields = in.readInt();
      List<FieldDecl> fieldList = new ArrayList<>(fields);
      for (int i = 0; i < fields; i++) {
        fieldList.add(
            new FieldDecl(
                in.readUTF(),
                tree(),
                span(),
                in.readBoolean(),
                FieldDecl.Kind.values()[in.readByte()],
                in.readBoolean()));
      }
      int methods = in.readInt();
      List<MethodDecl> methodList = new ArrayList<>(methods);
      for (int i = 0; i < methods; i++) {
        methodList.add(
            new MethodDecl(
                in.readUTF(),
                trees(),
                strings(),
                in.readBoolean(),
                tree(),
                strings(),
                trees(),
                span(),
                in.readBoolean(),
                in.readBoolean(),
                in.readBoolean()));
      }
      int members = in.readInt();
      List<TypeInfo> memberList = new ArrayList<>(members);
      TypeInfo[] self = new TypeInfo[1];
      // Members need their outer, which is built after them: link them once it exists.
      for (int i = 0; i < members; i++) {
        memberList.add(type(null));
      }
      boolean deprecated = in.readBoolean();
      self[0] =
          new TypeInfo(
              binaryName,
              simpleName,
              kind,
              span,
              List.copyOf(typeParams),
              unmodifiable(typeParamBounds),
              superclass,
              List.copyOf(interfaces),
              List.copyOf(fieldList),
              List.copyOf(methodList),
              List.copyOf(memberList),
              deprecated);
      for (TypeInfo m : memberList) {
        m.outer = self[0];
      }
      self[0].outer = outer;
      return self[0];
    }

    Span span() throws IOException {
      return new Span(in.readInt(), in.readInt(), in.readInt(), in.readInt());
    }

    List<String> strings() throws IOException {
      int n = in.readInt();
      List<String> out = new ArrayList<>(n);
      for (int i = 0; i < n; i++) {
        out.add(nullable());
      }
      return unmodifiable(out);
    }

    String nullable() throws IOException {
      return in.readBoolean() ? in.readUTF() : null;
    }

    List<Tree> trees() throws IOException {
      int n = in.readInt();
      List<Tree> out = new ArrayList<>(n);
      for (int i = 0; i < n; i++) {
        out.add(tree());
      }
      return out;
    }

    Tree tree() throws IOException {
      int tag = in.readByte();
      return switch (tag) {
        case NONE -> null;
        case CLASS -> {
          int position = in.readInt();
          Tree.ClassTy base = (Tree.ClassTy) tree();
          Tree.Ident name = new Tree.Ident(in.readInt(), in.readUTF());
          ImmutableList.Builder<Tree.Type> args = ImmutableList.builder();
          for (Tree a : trees()) {
            args.add((Tree.Type) a);
          }
          yield new Tree.ClassTy(position, Optional.ofNullable(base), name, args.build(), ImmutableList.of());
        }
        case ARRAY -> {
          int position = in.readInt();
          yield new Tree.ArrTy(position, ImmutableList.of(), (Tree.Type) tree());
        }
        case PRIM ->
            new Tree.PrimTy(in.readInt(), ImmutableList.of(), TurbineConstantTypeKind.valueOf(in.readUTF()));
        case VOID -> new Tree.VoidTy(in.readInt());
        case WILD -> {
          int position = in.readInt();
          Tree.Type upper = (Tree.Type) tree();
          Tree.Type lower = (Tree.Type) tree();
          yield new Tree.WildTy(
              position, ImmutableList.of(), Optional.ofNullable(upper), Optional.ofNullable(lower));
        }
        default -> throw new IOException("bad tree tag " + tag);
      };
    }

    /** Like List.copyOf, but keeps nulls (absent bounds, unnamed parameters). */
    private static <T> List<T> unmodifiable(List<T> list) {
      return java.util.Collections.unmodifiableList(list);
    }
  }
}
