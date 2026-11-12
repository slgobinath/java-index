# Template for Formula/java-index.rb, filled in by the release workflow on every release.
# Edit this file, not Formula/java-index.rb, which is overwritten.

class JavaIndex < Formula
  desc "Java code navigation for reviewing agent-written code (LSP and MCP)"
  homepage "https://github.com/slgobinath/java-index"
  version "0.1.3"
  license "Apache-2.0"

  on_macos do
    on_arm do
      url "https://github.com/slgobinath/java-index/releases/download/v0.1.3/java-index-0.1.3-macos-arm64.tar.gz"
      sha256 "1ef0713f6daf94c2361f3028e8f57b8982ad92bec315e4c8840e820b8a844474"
    end
  end

  on_linux do
    on_intel do
      url "https://github.com/slgobinath/java-index/releases/download/v0.1.3/java-index-0.1.3-linux-x64.tar.gz"
      sha256 "fed37b240a2c117d0c9a5b21e599a9d91ebe96e8ebb7aebb061fc1fc950a4e0f"
    end
    on_arm do
      url "https://github.com/slgobinath/java-index/releases/download/v0.1.3/java-index-0.1.3-linux-arm64.tar.gz"
      sha256 "736473a4eea8278b24774da48af5987d97bd34c9c641d20bd1f0706ebee29eb6"
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
