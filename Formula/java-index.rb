# Template for Formula/java-index.rb, filled in by the release workflow on every release.
# Edit this file, not Formula/java-index.rb, which is overwritten.

class JavaIndex < Formula
  desc "Java code navigation for reviewing agent-written code (LSP and MCP)"
  homepage "https://github.com/slgobinath/java-index"
  version "0.1.1"
  license "Apache-2.0"

  on_macos do
    on_arm do
      url "https://github.com/slgobinath/java-index/releases/download/v0.1.1/java-index-0.1.1-macos-arm64.tar.gz"
      sha256 "4468cde0e6ea50639d04fe244b3ad0554724ac962bc04f84e7a12ee146cbb0e1"
    end
  end

  on_linux do
    on_intel do
      url "https://github.com/slgobinath/java-index/releases/download/v0.1.1/java-index-0.1.1-linux-x64.tar.gz"
      sha256 "bd5daeffbd7a5d523e2f38b8a2ee8e5c0c7ca6494bef867d4b3fcb2442425d93"
    end
    on_arm do
      url "https://github.com/slgobinath/java-index/releases/download/v0.1.1/java-index-0.1.1-linux-arm64.tar.gz"
      sha256 "d70ca634bebb221148244ca371f238f7076e5cf2cdbd060a549cfcc1be20ae1c"
    end
  end

  # The relay is native; the daemon it starts runs on a JVM (Java 21+).
  depends_on "openjdk"

  def install
    libexec.install "bin", "lib"
    # Homebrew's openjdk is keg-only, so point the relay at it unless the user chose another JDK.
    (bin/"jindex").write_env_script libexec/"bin/jindex",
      JINDEX_JAVA: "${JINDEX_JAVA:-#{Formula["openjdk"].opt_bin}/java}"
    prefix.install "LICENSE", "NOTICE", "licenses"
  end

  test do
    assert_match "jindex-relay only relays", shell_output("#{bin}/jindex unknown 2>&1", 2)
  end
end
