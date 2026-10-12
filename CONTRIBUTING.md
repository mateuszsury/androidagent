# Contributing

Issues and pull requests are welcome. Read the [Code of conduct](CODE_OF_CONDUCT.md) and [architecture](docs/ARCHITECTURE.md) before making changes. Contributions to the application integration use the project's [Apache-2.0 license](LICENSE); retain each third-party component's original license.

## Development

1. Fork/clone the repository and create a focused branch.
2. Follow [Building](docs/BUILDING.md) to prepare the generated assets and development signing key. Never commit APKs, keys, credentials, runtime archives or local test dumps.
3. Keep changes within the intended feature. Preserve conversations, persistent HOME, projects and VM data when changing update paths.
4. Run checks appropriate to the change and describe the actual environment/result in your PR.

```sh
python3 tools/check-docs.py
python3 -m unittest discover -s tests -p 'test_*.py' -v
```

Docs-only changes need link/image checks, not a new 600 MB release build. Changes to Android behavior need relevant instrumentation on a dedicated emulator. Runtime/USB changes need asset validation and the corresponding integration tests. Hardware fixtures and host Linux must not be described as physical Android/USB results. See [Testing](docs/TESTING.md).

## Runtime and vendored changes

Update pinned versions and SHA manifests deliberately; do not silently run lock-update modes. Keep source URLs/recipes, patches, license texts and dependency inventories aligned. Do not broadly reformat vendored USB code or license files. Review generated binaries without adding them to Git.

Release APKs must have a new versionCode, a matching upgrade certificate and exact checksum evidence. Do not replace published bytes under the same version. Release keys remain with the maintainer.

## Pull requests

Explain the trigger, resulting behavior and validation. Include screenshots for visible changes and redact personal data, login codes and credentials. Record meaningful limits and failures rather than presenting an untested path as working. PR checks use read-only repository permissions; forks do not need maintainer signing secrets.

Report vulnerabilities privately through [Security](SECURITY.md). General bugs and feature requests belong in the issue forms.
