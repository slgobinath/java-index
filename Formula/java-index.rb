# Template for Formula/java-index.rb, filled in by the release workflow on every release.
# Edit this file, not Formula/java-index.rb, which is overwritten.

class JavaIndex < Formula
  desc "Java code navigation for reviewing agent-written code (LSP and MCP)"
  homepage "https://github.com/slgobinath/java-index"
  version "0.1.4"
  license "Apache-2.0"

  on_macos do
    on_arm do
      url "https://github.com/slgobinath/java-index/releases/download/v0.1.4/java-index-0.1.4-macos-arm64.tar.gz"
      sha256 "17645982ad4b141e10cce6dc2637d75bb769d41de99c1172c62626973383288b"
    end
  end

  on_linux do
    on_intel do
      url "https://github.com/slgobinath/java-index/releases/download/v0.1.4/java-index-0.1.4-linux-x64.tar.gz"
      sha256 "2f17ba5a859a51acf32afa73214019abbe6252c234668cada559bf7d91c7c454"
    end
    on_arm do
      url "https://github.com/slgobinath/java-index/releases/download/v0.1.4/java-index-0.1.4-linux-arm64.tar.gz"
      sha256 "0fd38d3ad6aaded503d4c0290651a19e0ee1587b8fa86936f165383ef99ea2aa"
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
