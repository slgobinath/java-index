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

import com.google.turbine.parse.Token;
import java.util.HashSet;
import java.util.Set;

/**
 * A Bloom filter over every identifier in a file, method bodies included. Lets find-references skip
 * files that cannot mention a name without reading them. About 10 bits per distinct identifier.
 */
final class IdentFilter {
  private IdentFilter() {}

  static long[] build(Tokens tokens) {
    Set<String> names = new HashSet<>();
    for (int i = 0; i < tokens.size(); i++) {
      if (tokens.kind(i) == Token.IDENT) {
        names.add(tokens.text(i));
      }
    }
    int bits = Integer.highestOneBit(Math.max(64, names.size() * 10) - 1) << 1;
    long[] words = new long[bits / 64];
    for (String name : names) {
      int h1 = name.hashCode();
      int h2 = Integer.rotateLeft(h1 * 0x9E3779B9, 16) | 1;
      for (int k = 0; k < 3; k++) {
        int bit = (h1 + k * h2) & (bits - 1);
        words[bit >>> 6] |= 1L << bit;
      }
    }
    return words;
  }

  static boolean mayContain(long[] words, String name) {
    int bits = words.length * 64;
    int h1 = name.hashCode();
    int h2 = Integer.rotateLeft(h1 * 0x9E3779B9, 16) | 1;
    for (int k = 0; k < 3; k++) {
      int bit = (h1 + k * h2) & (bits - 1);
      if ((words[bit >>> 6] & (1L << bit)) == 0) {
        return false;
      }
    }
    return true;
  }
}
