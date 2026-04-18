# Releases & CI

## Continuous integration

**CI** (`.github/workflows/ci.yml`) — on every push and pull request:

- Builds the plugin and runs tests.
- Runs `verifyPlugin` against marketplace-recommended IDE versions.
- Uploads coverage to Codecov.

## Automated releases

**Release** (`.github/workflows/release.yml`) — on every push to `master`:

1. Derives the next semver bump from [Conventional Commits](https://www.conventionalcommits.org/) messages.
2. Builds the plugin ZIP with the version and generated changelog injected.
3. Signs the ZIP with **cosign** (keyless OIDC signing).
4. Produces a SLSA-style **build provenance attestation**.
5. Creates a GitHub release with the ZIP and attestation attached.

## Publishing to JetBrains Marketplace

**Publish** (`.github/workflows/publish-marketplace.yml`) — manual `workflow_dispatch`:

1. Downloads the chosen release's signed ZIP.
2. Previews the changelog.
3. Waits for `marketplace` environment approval.
4. Uploads via the JetBrains Marketplace API.

## Security scanning

- **CodeQL** — static analysis of source code.
- **OpenSSF Scorecard** — repository security posture.
- **Zizmor** — GitHub Actions workflow security analysis.

## Required secrets / settings

| Secret / setting | Required by | Notes |
|---|---|---|
| `JETBRAINS_MARKETPLACE_TOKEN` | publish workflow | Set on the `marketplace` environment |
| `CODECOV_TOKEN` | CI | Optional; CI tolerates its absence |
| Environment `release` | release workflow | Auto-approved |
| Environment `marketplace` | publish workflow | Requires manual reviewer approval |

## Security policy

See [SECURITY.md](SECURITY.md) for how to report vulnerabilities.
