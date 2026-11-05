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

package dev.javaindex.daemon;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SocketChannel;

/**
 * Streams over a blocking socket channel. Unlike {@code Channels.newInputStream}, reads and writes
 * don't share a lock, so a reader blocked waiting for input can't stall concurrent writes.
 */
final class ChannelStreams {
  private ChannelStreams() {}

  static InputStream in(SocketChannel ch) {
    return new InputStream() {
      @Override
      public int read() throws IOException {
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        return n < 0 ? -1 : one[0] & 0xff;
      }

      @Override
      public int read(byte[] b, int off, int len) throws IOException {
        if (len == 0) {
          return 0;
        }
        try {
          return ch.read(ByteBuffer.wrap(b, off, len));
        } catch (ClosedChannelException e) {
          return -1; // closed by either side: end of stream (covers AsynchronousCloseException)
        }
      }

      @Override
      public void close() throws IOException {
        ch.close();
      }
    };
  }

  static OutputStream out(SocketChannel ch) {
    return new OutputStream() {
      @Override
      public void write(int b) throws IOException {
        write(new byte[] {(byte) b}, 0, 1);
      }

      @Override
      public synchronized void write(byte[] b, int off, int len) throws IOException {
        ByteBuffer buf = ByteBuffer.wrap(b, off, len);
        while (buf.hasRemaining()) {
          ch.write(buf);
        }
      }

      @Override
      public void close() throws IOException {
        ch.close();
      }
    };
  }
}
