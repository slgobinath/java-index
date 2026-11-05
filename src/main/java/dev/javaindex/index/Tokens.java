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

import com.google.turbine.diag.SourceFile;
import com.google.turbine.diag.TurbineError;
import com.google.turbine.parse.StreamLexer;
import com.google.turbine.parse.Token;
import com.google.turbine.parse.UnicodeEscapePreprocessor;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A flat token stream produced by turbine's lexer, with bracket matching. Lexing errors (e.g. an
 * unterminated string while the user is typing) are recovered from by restarting on the next line,
 * so the stream is usable for any buffer content.
 */
public final class Tokens {
  private Token[] kinds = new Token[256];
  private int[] starts = new int[256];
  private String[] texts = new String[256];
  /** For string literals: the (unescaped) value and the offset just past the closing quote. */
  private String[] strings = new String[256];
  private int[] stringEnds = new int[256];
  private int[] match;
  private int size;
  /** [start, end) source ranges skipped because they could not be lexed. */
  private final List<int[]> errors = new ArrayList<>();

  private Tokens() {}

  public static Tokens lex(String source) {
    Tokens t = new Tokens();
    String text = source;
    int resumeAt = 0;
    for (int attempt = 0; attempt < 64; attempt++) {
      StreamLexer lexer = new StreamLexer(new UnicodeEscapePreprocessor(new SourceFile(null, text)));
      try {
        while (true) {
          Token tok = lexer.next();
          if (tok == Token.EOF) {
            break;
          }
          int pos = lexer.position();
          if (pos < resumeAt) {
            continue;
          }
          t.add(tok, pos, tok == Token.IDENT ? lexer.stringValue() : null);
          if (tok == Token.STRING_LITERAL) {
            t.strings[t.size - 1] = lexer.stringValue();
            t.stringEnds[t.size - 1] = literalEnd(source, pos);
          }
        }
        break;
      } catch (TurbineError | IllegalArgumentException | StringIndexOutOfBoundsException e) {
        int errorPos = t.size == 0 ? resumeAt : Math.max(resumeAt, t.starts[t.size - 1] + 1);
        int nl = source.indexOf('\n', errorPos);
        int lineStart = Math.max(resumeAt, source.lastIndexOf('\n', Math.max(0, errorPos - 1)) + 1);
        t.errors.add(new int[] {lineStart, nl < 0 ? source.length() : nl});
        // Drop the partial tokens of the broken line so they don't glue onto the next statement.
        while (t.size > 0 && t.starts[t.size - 1] >= lineStart) {
          t.size--;
        }
        if (nl < 0) {
          break;
        }
        resumeAt = nl + 1;
        // Blank everything before the restart point so positions stay aligned with the source.
        char[] blanked = source.toCharArray();
        for (int i = 0; i < resumeAt; i++) {
          if (blanked[i] != '\n') {
            blanked[i] = ' ';
          }
        }
        text = new String(blanked);
      }
    }
    t.computeMatches();
    return t;
  }

  private void add(Token kind, int start, String text) {
    if (size == kinds.length) {
      int n = size * 2;
      kinds = Arrays.copyOf(kinds, n);
      starts = Arrays.copyOf(starts, n);
      texts = Arrays.copyOf(texts, n);
      strings = Arrays.copyOf(strings, n);
      stringEnds = Arrays.copyOf(stringEnds, n);
    }
    kinds[size] = kind;
    starts[size] = start;
    texts[size] = text;
    strings[size] = null;
    size++;
  }

  private void computeMatches() {
    match = new int[size];
    Arrays.fill(match, -1);
    ArrayDeque<Integer> stack = new ArrayDeque<>();
    for (int i = 0; i < size; i++) {
      switch (kinds[i]) {
        case LPAREN, LBRACE, LBRACK -> stack.push(i);
        case RPAREN, RBRACE, RBRACK -> {
          Token open = opener(kinds[i]);
          // Tolerate unbalanced input: unwind to the nearest opener of the right kind.
          while (!stack.isEmpty() && kinds[stack.peek()] != open) {
            stack.pop();
          }
          if (!stack.isEmpty()) {
            int o = stack.pop();
            match[o] = i;
            match[i] = o;
          }
        }
        default -> {}
      }
    }
  }

  private static Token opener(Token close) {
    return switch (close) {
      case RPAREN -> Token.LPAREN;
      case RBRACE -> Token.LBRACE;
      default -> Token.LBRACK;
    };
  }

  /**
   * The source with unlexable lines blanked out, or null if everything lexed. Lets turbine still
   * parse the declarations of a buffer that is mid-edit (e.g. an unterminated string literal).
   */
  public String repaired(String source) {
    if (errors.isEmpty()) {
      return null;
    }
    char[] chars = source.toCharArray();
    for (int[] range : errors) {
      for (int i = range[0]; i < range[1] && i < chars.length; i++) {
        if (chars[i] != '\n') {
          chars[i] = ' ';
        }
      }
    }
    return new String(chars);
  }

  public int size() {
    return size;
  }

  public Token kind(int i) {
    return i >= 0 && i < size ? kinds[i] : Token.EOF;
  }

  public boolean is(int i, Token k) {
    return kind(i) == k;
  }

  public int start(int i) {
    return starts[i];
  }

  /** The value of a string literal token, or null. */
  public String stringValue(int i) {
    return i >= 0 && i < size ? strings[i] : null;
  }

  /** Offset just past a string literal's closing quote(s). */
  private static int literalEnd(String source, int start) {
    if (source.startsWith("\"\"\"", start)) {
      int close = source.indexOf("\"\"\"", start + 3);
      return close < 0 ? source.length() : close + 3;
    }
    for (int i = start + 1; i < source.length(); i++) {
      char c = source.charAt(i);
      if (c == '\\') {
        i++;
      } else if (c == '"' || c == '\n') {
        return i + 1;
      }
    }
    return source.length();
  }

  public int end(int i) {
    if (i >= 0 && i < size && strings[i] != null) {
      return stringEnds[i];
    }
    return texts[i] != null ? starts[i] + texts[i].length() : starts[i] + 1;
  }

  /** The identifier text, or null for non-identifier tokens. */
  public String text(int i) {
    return i >= 0 && i < size ? texts[i] : null;
  }

  public boolean isIdent(int i, String value) {
    return kind(i) == Token.IDENT && value.equals(texts[i]);
  }

  /** Index of the matching bracket, or -1. */
  public int match(int i) {
    return i >= 0 && i < size ? match[i] : -1;
  }

  /** The identifier token whose span contains (or ends at) {@code offset}, or -1. */
  public int identAt(int offset) {
    int lo = 0;
    int hi = size - 1;
    while (lo <= hi) {
      int mid = (lo + hi) >>> 1;
      if (starts[mid] <= offset) {
        lo = mid + 1;
      } else {
        hi = mid - 1;
      }
    }
    for (int i = hi; i >= 0 && i >= hi - 1; i--) {
      if (kinds[i] == Token.IDENT && starts[i] <= offset && offset <= end(i)) {
        return i;
      }
    }
    return -1;
  }

  /** First token index whose start is >= offset. */
  public int indexAtOrAfter(int offset) {
    int lo = 0;
    int hi = size;
    while (lo < hi) {
      int mid = (lo + hi) >>> 1;
      if (starts[mid] < offset) {
        lo = mid + 1;
      } else {
        hi = mid;
      }
    }
    return lo;
  }
}
