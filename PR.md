## 这个 PR

把应用界面从波兰语全部汉化，并新增“模型供应商”设置，让内置 Codex 可以连接 OpenAI 兼容的国内服务（DeepSeek / 通义千问 / 智谱 GLM / Kimi / LongCat / 自定义）。

- 汉化范围：Java 界面文本、`strings.xml`、内置终端脚本 `buddy-run`、`system-prompt.md` 与技能文档。
- 新功能：`ModelProvider.java` + “环境与账户 → 模型供应商”，配置写入 `.codex/config.toml` 与 `.androidagent/provider.env`。
- 附 `build-patched.sh`、`tools/gen-rjava.py`，便于在手机上重建 APK。

## ⚠️ 未经验证

- **尚未在真机上验证**：UI 显示效果、模型供应商的实际生效、安装流程都没有经过真机回归测试。
- 构建产物使用个人开发密钥签名（CN=Buddy），与官方发布签名不同，不能覆盖官方安装。
- 未经过上游维护者审核，可能不适合直接合并；仅作为本地化的参考实现。

详见仓库根目录的 `LOCALIZATION-zh.md`。

本分支的全部改动、构建与发布操作由 **LongCat-2.5**（androidagent 内置代理）在手机上自主完成。
