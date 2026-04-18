# Building

Requires JDK 17+.

## Build the installable ZIP

```bash
./gradlew buildPlugin
```

The signed/unsigned ZIP lands in `build/distributions/`.

## Run a sandbox IDE with the plugin loaded

```bash
./gradlew runIde
```

## Run tests

```bash
./gradlew test
```

## Verify against JetBrains Marketplace constraints

```bash
./gradlew verifyPlugin
```
