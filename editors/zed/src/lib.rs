// Copyright 2026 Gobinath Loganathan
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

use zed_extension_api::{self as zed, serde_json::Value, settings::LspSettings, LanguageServerId, Result};

struct JavaIndex;

impl zed::Extension for JavaIndex {
    fn new() -> Self {
        JavaIndex
    }

    /// Runs `lsp.java-index.binary.path` from settings, else `jindex` on PATH.
    fn language_server_command(
        &mut self,
        id: &LanguageServerId,
        worktree: &zed::Worktree,
    ) -> Result<zed::Command> {
        let binary = LspSettings::for_worktree(id.as_ref(), worktree)
            .ok()
            .and_then(|s| s.binary);
        let command = binary
            .as_ref()
            .and_then(|b| b.path.clone())
            .or_else(|| worktree.which("jindex"))
            .ok_or_else(|| {
                "jindex not found: set lsp.java-index.binary.path in settings \
                 or put bin/jindex on PATH"
                    .to_string()
            })?;
        Ok(zed::Command {
            command,
            args: binary.and_then(|b| b.arguments).unwrap_or_default(),
            env: worktree.shell_env(),
        })
    }

    /// Passes `lsp.java-index.initialization_options` through (e.g. an explicit classpath).
    fn language_server_initialization_options(
        &mut self,
        id: &LanguageServerId,
        worktree: &zed::Worktree,
    ) -> Result<Option<Value>> {
        Ok(LspSettings::for_worktree(id.as_ref(), worktree)
            .ok()
            .and_then(|s| s.initialization_options))
    }
}

zed::register_extension!(JavaIndex);
