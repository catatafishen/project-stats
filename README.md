# CodeScape — IntelliJ Plugin

[![CI](https://github.com/catatafishen/codescape/actions/workflows/ci.yml/badge.svg)](https://github.com/catatafishen/codescape/actions/workflows/ci.yml)
[![CodeQL](https://github.com/catatafishen/codescape/actions/workflows/codeql.yml/badge.svg)](https://github.com/catatafishen/codescape/actions/workflows/codeql.yml)
[![OpenSSF Scorecard](https://api.securityscorecards.dev/projects/github.com/catatafishen/codescape/badge)](https://scorecard.dev/viewer/?uri=github.com/catatafishen/codescape)
[![codecov](https://codecov.io/gh/catatafishen/codescape/branch/master/graph/badge.svg)](https://codecov.io/gh/catatafishen/codescape)
[![License: Apache 2.0](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

CodeScape turns a codebase into an interactive map. See where size, complexity, and churn live at a glance, then
drill from a whole-project overview into a single directory without losing context.

![CodeScape screenshot](img.png)

## Features

### Why CodeScape

- See codebase shape, complexity, and file churn in one place.
- Switch between language, module, source category, and directory-tree views.
- Drill down without losing your place, then zoom back out instantly.

### What it shows

- **Treemap** for fast visual scanning and directory drill-down.
- **Stacked bar** for a compact share-of-project overview.
- **Sortable table** for exact counts, sizes, and per-group totals.
- **Metrics** for LOC, code LOC, complexity, file size, file count, and git commit count.

### Filters

- Include or exclude tests, resources, generated files, and other files with one click.
- Filters update immediately; no rescan needed.

### Under the hood

- Background scanning with cancel support.
- Authoritative source-root classification via IntelliJ APIs.
- PSI-based complexity counting where available, with a plain-text fallback.

## Build & contributing

See [BUILDING.md](BUILDING.md).

## Releases & security

See [RELEASES.md](RELEASES.md) and [SECURITY.md](SECURITY.md).

## License

Apache License 2.0 — see [LICENSE](LICENSE) and [NOTICE](NOTICE).
