# Terminator training data

Minecraft Java combat-data tooling and a versioned experience snapshot for the
[Terminator project](https://github.com/CocoThePirate/terminator).

This repository contains reusable kits, maps, scenario compilers, passive recording
source, validation tools and a public dataset index. The larger pseudonymized
recordings are distributed as a [GitHub Release](https://github.com/CocoThePirate/terminator-training-data/releases),
keeping ordinary Git history small.

## Included

- **10 practice modes:** sword, UHC, bow, crossbow, axe/shield, underground mole,
  local fireball fight, bridge, buckets and sumo.
- **11 kits, 13 maps, 52 reflected map variants and 60 supported mode configurations.**
- Minecraft **1.21.4** recording/bridge source and a separate **26.2** data-arena
  helper and passive client recorder.
- Frozen-bot traces, historical experiment records, infrastructure captures and
  checkpoint provenance, with categories kept separate.

These are practice/collection environments, not implementations of another
server's complete minigames. Fireball fight uses a versioned local adapter;
bridge and sumo do not add custom goal scoring or server-specific knockback.

## Get the data

Download the archive and checksums from the latest Release, or run:

```sh
node scripts/download-data.cjs
tar -xzf downloads/terminator-training-data-v0.1.0.tar.gz -C downloads
node scripts/materialize-data.cjs downloads/terminator-public-data datasets
```

The extracted `manifest.json` maps published content objects to their relative
source aliases, categories, source game versions and split metadata. Materializing
restores readable folders, including adjacent session metadata and JSONL files.
An optional final argument, `26.2` or `1.21.4`, selects one source version.
The Release also includes the compiled 26.2 scenario/data-pack ZIP and the
recorder/helper JAR. The ZIP contains the generated functions and all 60 setup
definitions, so using those files does not require regenerating them locally.

**This is an unreviewed experience archive, not an expert imitation dataset.**
A bot fighting a human does not record that human's control inputs. Infrastructure
tests demonstrate recording integrity, not PvP strength. Entries default to
`expert_demonstration: false` and `on_policy_rl_eligible: false`. Unknown data and
missing split labels need explicit review before training.

Historical trajectories retain their **1.21.4 physics labels**. Rebuilding their
kits/maps for 26.2 does not turn old outcomes into native 26.2 experience. Native
26.2 captures are versioned separately. File hashes deduplicate identical bytes;
file and row totals are not counts of unique fights.

## Scenarios and tests

Node.js 22 or newer; no npm dependencies:

```sh
node training/tools/arena_scenarios.cjs list
node training/tools/arena_scenarios.cjs mode mole 0 mole-setup.json
node --test training/tools/arena_scenarios.test.cjs training/tools/data_port.test.cjs training/tools/prepare_public_training_data.test.cjs
```

The catalog is `training/scenarios/catalog.json`. Scenarios include explicit
equipment, geometry, task labels and train/validation/evaluation map reservations.
Different seeds can produce identical reflected layouts; compare geometry hashes.
Keep a participant and map family in the same reserved split across sessions.

`training/fabric-arena-26.2` contains the Java 25 recorder/helper source and build
scripts. Java/Fabric/Minecraft dependencies must be prepared separately; game
binaries, assets, accounts, world saves and toolchain caches are not distributed.
Workspace launch/build tools retain the companion project's directory conventions;
they are not a standalone Minecraft installer. See [recording notes](docs/RECORDING.md).

## Validation scope

Native Minecraft Java 26.2 checks passed for all 11 kits and 52 map variants,
including food/health reset parity, placed-block/projectile cleanup, and fireball
consumption, cooldown and mode scoping. Native bow and mole client captures each
recorded 60 consecutive frames without drops. Reports are in `validation/26.2/`.
The catalog and data-privacy tests also run in GitHub Actions.

No expert PvP, successful fireball jump, multiplayer balance or HT1-level strength
is established by these tests. The agent's own-client combat/HUD/input-takeover
pipeline remains a separate part of the companion project.

## Privacy and provenance

The Release copy pseudonymizes player identifiers and entity UUIDs consistently,
removes local absolute paths, and redacts email/IP/credential-shaped values.
Pseudonymization is not a guarantee of anonymity. Original local files are not
modified, and the private pseudonymization key is not published. Historical ZIPs
are expanded for data-only JSON/JSONL members rather than uploaded unchecked.
Game-state numbers, resource IDs, mode labels and source-version metadata are
preserved. Published SHA-256 values identify the actual distributed bytes.

Checkpoint files are provenance artifacts, not training samples or claims of
target-version performance. Feedback and server-only setup information must remain
separate from observations available to a deployed client policy.
