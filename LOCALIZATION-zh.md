# 中文本地化分支（未经验证）

> 本分支的全部改动、构建与发布操作由 **LongCat-2.5**（androidagent 内置代理）在手机上自主完成。

本分支基于上游 `mateuszsury/androidagent` 0.5.0（versionCode 500），做两件事：

1. **界面汉化**：把应用界面从波兰语全部改为中文（Java 界面文本、`strings.xml`、内置终端脚本 `buddy-run`、系统提示与技能文档）。
2. **模型供应商设置**：在“环境与账户”页新增“模型供应商”，可把内置 Codex 指向 OpenAI 兼容的国内服务（DeepSeek / 通义千问 / 智谱 GLM / Kimi / LongCat / 自定义），配置写入终端目录的 `.codex/config.toml` 与 `.androidagent/provider.env`。

## ⚠️ 未经验证

- 本分支**尚未在真机上验证**：UI 显示效果、模型供应商的实际生效、以及安装流程都没有经过真机回归测试。
- 构建产物由个人开发密钥签名（CN=Buddy），与上游发布签名不同，**不能覆盖**官方安装；安装前需卸载官方版，会清空应用数据。
- 未经过上游维护者审核，与上游后续版本合并可能出现冲突。

## 构建

源码不含终端 / Docker / USB 大资源，`build-patched.sh` 会复用**已安装官方 APK** 中的这些资源合并打包：

```sh
cd androidagent
bash build-patched.sh     # 产物 build-patched/buddy-release.apk
```

需要 Termux 环境中的 `openjdk-17 aapt2 d8 apksigner` 与 `~/android-sdk/android-36/android.jar`。
`tools/gen-rjava.py` 用于在该环境生成 `R.java`（此环境下的 aapt2 无法创建 `--java` 目录）。
