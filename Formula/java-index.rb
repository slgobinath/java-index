# Template for Formula/java-index.rb, filled in by the release workflow on every release.
# Edit this file, not Formula/java-index.rb, which is overwritten.

class JavaIndex < Formula
  desc "Java code navigation for reviewing agent-written code (LSP and MCP)"
  homepage "https://github.com/slgobinath/java-index"
  version "0.1.2"
  license "Apache-2.0"

  on_macos do
    on_arm do
      url "https://github.com/slgobinath/java-index/releases/download/v0.1.2/java-index-0.1.2-macos-arm64.tar.gz"
      sha256 "c146c3156437640c42f1d29fe70154e202f6d8bd9ffbeca027fc4529f6eab234"
    end
  end

  on_linux do
    on_intel do
      url "https://github.com/slgobinath/java-index/releases/download/v0.1.2/java-index-0.1.2-linux-x64.tar.gz"
      sha256 "b273c4eb9aaac1af92da5b82bbb145c256250d17961e703439cc199e1a054d60"
    end
    on_arm do
      url "https://github.com/slgobinath/java-index/releases/download/v0.1.2/java-index-0.1.2-linux-arm64.tar.gz"
      sha256 "24d7717b62206ff0ce753156edfdaab8d1dc66dcdcd5090386be39ddcc3a769e"
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
