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

import java.util.Arrays;

/** Line start offsets of a text, for converting between offsets and LSP line/character. */
public final class Lines {
  private final int[] starts;
  private final int length;

  private Lines(int[] starts, int length) {
    this.starts = starts;
    this.length = length;
  }

  public static Lines of(String text) {
    int count = 1;
    for (int i = 0; i < text.length(); i++) {
      if (text.charAt(i) == '\n') {
        count++;
      }
    }
    int[] starts = new int[count];
    int n = 1;
    for (int i = 0; i < text.length(); i++) {
      if (text.charAt(i) == '\n') {
        starts[n++] = i + 1;
      }
    }
    return new Lines(starts, text.length());
  }

  public int line(int offset) {
    int idx = Arrays.binarySearch(starts, offset);
    return idx >= 0 ? idx : -idx - 2;
  }

  public int column(int offset) {
    return offset - starts[line(offset)];
  }

  /** Offset of an LSP position (UTF-16 based, like Java strings), clamped to the text. */
  public int offset(int line, int column) {
    if (line < 0) {
      return 0;
    }
    if (line >= starts.length) {
      return length;
    }
    int lineEnd = line + 1 < starts.length ? starts[line + 1] - 1 : length;
    return Math.min(starts[line] + Math.max(0, column), lineEnd);
  }
}
