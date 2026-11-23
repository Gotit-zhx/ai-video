---
name: antd
description: >-
  Use for this project's frontend UI design and refactors, Ant Design (antd)
  components, APIs, tokens, deprecations, debugging, and version migrations.
---

# Ant Design CLI

You have access to `@ant-design/cli` — a local CLI tool with bundled antd metadata for v4/v5/v6 (plus migration guides for v3 → v4, v4 → v5, v5 → v6). Use it to query component knowledge, analyze projects, and guide migrations. All data is offline, no network needed.

## Project setup

Based on the [upstream Ant Design Skill](https://github.com/ant-design/ant-design-cli/blob/65dd81d9ec466347e55b10d28462e1258fd5faf0/skills/antd/SKILL.md).
Before designing or changing UI, read the [project frontend design standard](../../../docs/design/frontend-design-standard.md)
and the [Ant Design agent guide](https://ant.design/docs/react/for-agents-cn.md).

From the repository root, use the CLI pinned in `scripts/antd/package-lock.json`.

```bash
antd_cli=./scripts/antd/node_modules/.bin/antd
test -x "$antd_cli" || npm --prefix scripts/antd ci --global=false
```

Set `antd_cli` again in each new shell, or use the executable path directly. Keep
installation and updates in `scripts/antd/`. Use `--format json` for structured
output. Determine the target module's actual `antd` version from its lockfile or
installed package, then pass `--version X.Y.Z` to API and lint commands. For a new
module, use the version selected for that module after checking its compatibility.

## Scenarios

### 1. Writing antd component code

Before writing antd component code, set `antd_version` to the target version and check the API, including its deprecation notes.

```bash
# Check what props are available
"$antd_cli" info Button --version "$antd_version" --detail --format json

# Get a working demo as starting point
"$antd_cli" demo Button basic --version "$antd_version" --format json

# Check semantic classNames/styles for custom styling
"$antd_cli" semantic Button --version "$antd_version" --format json

# Check component-level design tokens for theming
"$antd_cli" token Button --version "$antd_version" --format json

# Get the overall design language (design.md): colors, typography, spacing, radius + principles
"$antd_cli" design.md --format json
```

**Workflow:** `antd info` → understand props → `antd demo` → grab a working example → write code.

### 2. Looking up full documentation

When you need comprehensive component docs (not just props):

```bash
"$antd_cli" doc Table --version "$antd_version" --format json        # full markdown docs for Table
"$antd_cli" doc Table --version "$antd_version" --lang zh            # Chinese docs
```

### 3. Debugging antd issues

When code isn't working as expected or the user reports an antd bug:

```bash
# Collect full environment snapshot (system, deps, browsers, build tools)
"$antd_cli" env --format json

# Check if the prop exists for the user's antd version
"$antd_cli" info Select --version 5.12.0 --format json

# Check if the prop is deprecated
"$antd_cli" lint ./src/components/MyForm.tsx --version "$antd_version" --format json

# Diagnose project-level configuration issues
"$antd_cli" doctor --format json
```

**Workflow:** `antd env` → capture full environment → `antd doctor` → check configuration → `antd info --version X` → verify API against the user's exact version → `antd lint` → find deprecated or incorrect usage.

### 4. Migrating between versions

When the user wants to upgrade antd (e.g., v3 → v4 or v4 → v5):

```bash
# Get full migration checklist
"$antd_cli" migrate 3 4 --format json    # v3 → v4
"$antd_cli" migrate 4 5 --format json    # v4 → v5

# Check migration for a specific component
"$antd_cli" migrate 4 5 --component Select --format json

# Generate agent-friendly auto-migration prompt (does not modify files)
"$antd_cli" migrate 4 5 --apply ./src --format json

# See what changed between two versions
"$antd_cli" changelog 4.24.0 5.0.0 --format json

# See changes for a specific component
"$antd_cli" changelog 4.24.0 5.0.0 Select --format json
```

**Workflow:** `antd migrate` → get full checklist → `antd changelog <v1> <v2>` → understand breaking changes → apply fixes → `antd lint` → check the changed files for deprecation warnings. Use the target-version API and migration guides to check removed APIs.

### 5. Analyzing project antd usage

When the user wants to understand how antd is used in their project:

```bash
# Scan component usage statistics
"$antd_cli" usage ./src --format json

# Filter to a specific component
"$antd_cli" usage ./src --filter Form --format json

# Lint for best practice violations
"$antd_cli" lint ./src --version "$antd_version" --format json

# Check only specific rule categories
"$antd_cli" lint ./src --only deprecated --version "$antd_version" --format json
"$antd_cli" lint ./src --only a11y --version "$antd_version" --format json
"$antd_cli" lint ./src --only performance --version "$antd_version" --format json
```

### 6. Checking changelogs and version history

When the user asks about what changed in a version:

```bash
# Specific version changelog
"$antd_cli" changelog 5.22.0 --format json

# Version range (both ends inclusive)
"$antd_cli" changelog 5.21.0..5.24.0 --format json
```

### 7. Exploring available components

When the user is choosing which component to use:

```bash
# List all components with categories
"$antd_cli" list --version "$antd_version" --format json

# List components for a specific antd version
"$antd_cli" list --version 5.0.0 --format json
```

### 8. Collecting environment info

When you need to understand the project's antd setup, or prepare info for a bug report:

```bash
# Full environment snapshot (text — paste into GitHub Issues)
"$antd_cli" env

# Structured JSON for programmatic use
"$antd_cli" env --format json

# Scan a specific project directory
"$antd_cli" env ./my-project --format json
```

Collects: OS, Node, package managers (npm/pnpm/yarn/bun/utoo), npm registry, browsers, core deps (antd/react/dayjs), all `@ant-design/*` and `rc-*` packages, and build tools (umi/vite/webpack/typescript/etc.).

### 9. Reporting antd bugs

When the user asks you to report an antd bug:

```bash
# Step 0: Collect environment info for reference (optional — antd bug already embeds basic env)
# Use the output to cross-check versions or attach extra details to the bug report
"$antd_cli" env --format json

# Step 1: Preview for user review
"$antd_cli" bug --title "DatePicker crashes when selecting date" \
  --reproduction "https://codesandbox.io/s/xxx" \
  --steps "1. Open DatePicker 2. Click a date" \
  --expected "Date is selected" \
  --actual "Component crashes with error" \
  --format json

# Step 2: Show to user, ask for confirmation

# Step 3: Submit after user confirms
"$antd_cli" bug --title "DatePicker crashes when selecting date" \
  --reproduction "https://codesandbox.io/s/xxx" \
  --steps "1. Open DatePicker 2. Click a date" \
  --expected "Date is selected" \
  --actual "Component crashes with error" \
  --submit
```

### 10. Reporting CLI issues

When the user asks you to report a CLI bug, or explicitly requests help filing one:

```bash
# Preview the bug report for the user
"$antd_cli" bug-cli --title "antd info Button returns wrong props for v5.12.0" \
  --description "When querying Button props for version 5.12.0, the output includes props that don't exist in that version" \
  --steps "1. Run: antd info Button --version 5.12.0 --format json" \
  --expected "Props matching antd 5.12.0 Button API" \
  --actual "Props include 'classNames' which was added in 5.16.0" \
  --format json
```

Show the report to the user and confirm before submitting:

```bash
"$antd_cli" bug-cli --title "antd info Button returns wrong props for v5.12.0" \
  --description "..." \
  --steps "..." \
  --expected "..." \
  --actual "..." \
  --submit
```

**Opt-out:** If the environment variable `ANTD_NO_AUTO_REPORT=1` is set, skip all bug-reporting suggestions entirely — do not suggest `antd bug` or `antd bug-cli` unless the user directly asks.

### 11. Upgrading the project CLI

When asked to update the CLI, use `npm --prefix scripts/antd install --global=false --save-dev --save-exact @ant-design/cli@<version>`.
Review `package.json` and `package-lock.json` and run a local query with the new executable.

## Global Flags

| Flag | Purpose |
|---|---|
| `--format <format>` | Output format: `json`, `text`, or `markdown` (agents should prefer `json`) |
| `--version <v>` | Target a specific antd version (e.g. `5.20.0`) |
| `--lang zh` | Chinese output (default: `en`) |
| `--detail` | Include extra fields (description, since, deprecated, FAQ) |
| `-V, --cli-version` | Print CLI version and exit |

## Key Rules

1. **Always query before writing** — Don't guess antd APIs from memory. Run `antd info` first.
2. **Match the target version** — Use the module's exact version for API queries and lint. For antd v3 projects, start with `migrate 3 4`.
3. **Use `--format json`** — Every command supports it. Parse the JSON output rather than regex-matching text output.
4. **Check before suggesting migration** — Run `antd changelog <v1> <v2>` and `antd migrate` before advising on version upgrades.
5. **Lint after changes** — After writing or modifying antd code, run `antd lint` on the changed files to catch deprecated or problematic usage.
6. **Report antd bugs** — When the user asks to report an antd bug, use `antd bug`. Always preview first, get user confirmation, then submit.
7. **Report CLI issues** — When the user asks about a CLI problem, use `antd bug-cli` to help them file a report. Always preview first, get user confirmation, then submit.
