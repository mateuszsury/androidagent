# Repository maintenance

## GitHub discovery settings

The README uses descriptive Android/Codex/Claude keywords, image alt text, a direct APK link and an English documentation index. GitHub's About description and Topics additionally help repository discovery; they are administrative settings, not files automatically read from Git.

Recommended About description:

> Run Codex and Claude Code on Android: embedded terminal, MCP phone tools, optional Docker environments, and USB UART/ADB access.

Homepage: `https://github.com/mateuszsury/androidagent/releases/latest`.

Topics:

```text
android, android-app, codex, claude-code, ai-agents, mcp, termux,
docker, usb, usb-serial, usb-otg, automation, java, mobile
```

An administrator can set these through the gear beside **About**, or from their own normally authenticated GitHub CLI:

```sh
gh repo edit mateuszsury/androidagent \
  --description 'Run Codex and Claude Code on Android: embedded terminal, MCP phone tools, optional Docker environments, and USB UART/ADB access.' \
  --homepage https://github.com/mateuszsury/androidagent/releases/latest \
  --add-topic android,android-app,codex,claude-code,ai-agents,mcp,termux,docker,usb,usb-serial,usb-otg,automation,java,mobile
```

Enable **Private vulnerability reporting** in repository Settings → Security, or use `gh api --method PUT repos/mateuszsury/androidagent/private-vulnerability-reporting` with an account authorized to administer the repository. The documentation-maintenance session received `403: Resource not accessible by integration` for these settings; they were not changed or represented as applied. Do not copy an access token into an issue or documentation to resolve this.

## Releases and tags

Use semantic-version tags `vMAJOR.MINOR.PATCH` matching `BuildInfo.VERSION` and an increasing versionCode. Existing `v0.4.2`, `v0.4.3`, `v0.4.4` match codes402/403/404. Keep published tag targets and APK bytes unchanged. A docs-only change does not need a new APK tag.

Releases contain the actual signed APK, byte length, SHA256, upgrade certificate and validation evidence. CI development-signing artifacts are not production releases. Keep English release notes honest about device/model/hardware scope; old failures remain part of the record.

## Source-tree hygiene

Generated APKs, runtime archives, build caches and raw test/transfer outputs belong in ignored directories, release assets or CI artifacts. The `v0.4.4` source snapshot retains the original validation records; [Testing](TESTING.md) links its exact immutable commit. Do not force-rewrite history or remove those tags to tidy the current tree.

Pinned source manifests, package inventories, build recipes, vendored dependencies and original license notices are required provenance—not disposable output. Keep [third-party notices](../THIRD_PARTY_NOTICES.md) current when dependencies change.

Run `python3 tools/check-docs.py` after changing guide paths, anchors or screenshots. Screenshots must be real, disclose fixtures and omit credentials or personal content. Full application CI runs for implementation changes or manual dispatch; documentation changes run the fast host/link checks.
