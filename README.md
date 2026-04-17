# Project Stats — IntelliJ Plugin

A JetBrains plugin that visualizes where your project's source code size comes from.

## Features

- **Tool window** "Project Stats" on the right-hand side.
- **Group by** one of:
  - Language (by IntelliJ file type: Kotlin, Java, TypeScript, …)
  - Module
  - Source category (Sources, Tests, Resources, Test Resources, Generated, Other)
  - Directory tree (drill-down via double-click)
- **Metric** selector: Total LOC, Non-blank LOC, File size, File count.
- **Filters**: include/exclude tests, resources, generated sources, or “other”.
- **Visualizations**:
  - GitHub-style stacked bar (percent per bucket).
  - Squarified treemap with stable colors, tooltips, and drill-down.
  - Sortable table with files / LOC / non-blank / size / percent / children.
- **Background scanning** with progress, cancellation, and large-file guard (≤ 4 MiB per file for LOC counting).

Uses IntelliJ's `ProjectFileIndex`, `GeneratedSourcesFilter`, and JPS `JavaSourceRootType` to get authoritative categorization, and `VirtualFile` charsets for accurate line counts.

## Build

Requires JDK 17+.

```bash
./gradlew buildPlugin
```

The installable zip lands in `build/distributions/`.

To run an IDE sandbox with the plugin loaded:

```bash
./gradlew runIde
```

## Layout

```
src/main/kotlin/com/github/projectstats/
  Model.kt                 # FileStat, StatGroup, Metric, GroupBy, ScanResult
  ProjectScanner.kt        # Walks the project, classifies & counts lines
  StatsAggregator.kt       # Groups FileStats by the chosen dimension
  TreemapPanel.kt          # Squarified treemap (custom Swing)
  StackedBarPanel.kt       # GitHub-style percentage bar
  ProjectStatsPanel.kt     # Tool window UI: toolbar, bar, treemap, table
  ProjectStatsToolWindowFactory.kt
src/main/resources/META-INF/plugin.xml
```
