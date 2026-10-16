# Claude Code

Claude Code is a separate conversation backend alongside Codex. New conversations use the agent selected on the home screen. An existing conversation keeps its own backend, model and session ID; a Codex session is not transferred to Claude.

## Setup and login

1. Install androidagent 0.4.4. A separate Termux app, `RUN_COMMAND` permission and copied install commands are unnecessary.
2. In **Environment and accounts** (`Środowisko i konta`), select Claude Code or both agents and choose **Prepare selected agents**. The app downloads the pinned official `@anthropic-ai/claude-code-linux-{arm64,x64}-musl` package, verifies SHA256, installs it inside app-private storage and checks the actual executable.
3. Choose **Log in to Claude Code**. Complete the official `claude auth login` flow shown in the embedded terminal and browser. Never paste passwords or account tokens into a chat or issue.
4. Return to chat. The integration reads `claude auth status` and passes status, not credentials, to the UI. `/status` works without account login.

The current client version is **2.1.296**. The embedded runtime supplies pinned musl libraries; source and package details are in [terminal provenance](TERMINAL-SOURCES.md). Client setup can be cancelled or retried without replacing conversation history. Desktop self-updating is disabled in the wrapper because Android requires the pinned runtime mappings.

## Conversations and tools

- `stream-json --include-partial-messages` delivers live text and tool progress. The final response is stored once; subagent output is kept separate from the main response stream.
- Models include Default, Sonnet, Opus, Haiku and a custom identifier. The official client resolves aliases; account access and exact model versions are not guaranteed by the UI.
- Resume uses the real `session_id`. Attachments are fetched through the authenticated phone server into the conversation's work directory.
- Both agents receive the same MCP phone, USB and [15 Docker tools](DOCKER.md#agent-tools). Per-run credentials and grants are revocable.
- Normal mode supplies WebSearch/WebFetch/Read and MCP, with attachment-directory read permissions. Shell and file modification tools are removed from the tool set.
- Developer mode enables Bash/Read/Write/Edit/Glob/Grep/Skill/Agent and related coding tools as **androidagent's UID**. Approved coding tools can access its whole private environment without separate MCP approval cards. Other noninteractive permission requests are denied. MCP `run_command` still requires its own exact-command approval.
- Stop cancels the owned helper process tree and phone access without deleting the conversation.
- The Remote Control handoff opens an embedded terminal session with the conversation ID, model, MCP configuration and current restrictions. Availability depends on the official client, account and service.

Codex developer mode uses `--sandbox danger-full-access` because the desktop Linux sandbox is not supported by this Android integration. Android's UID boundary still applies. Neither client has a separate project security sandbox inside the app UID.

## Validation

Official Claude binaries, version/auth-status queries, signed-out startup and protocol/UI behavior have been tested. A logged-in Claude response and account-backed Remote Control have **not** been verified. Physical owner-authenticated conversations in [Testing](TESTING.md) currently cover Codex only.

References: [CLI](https://code.claude.com/docs/en/cli-reference), [headless streaming](https://code.claude.com/docs/en/headless), [official package](https://www.npmjs.com/package/@anthropic-ai/claude-code) and [licensing](https://code.claude.com/docs/en/legal-and-compliance).
