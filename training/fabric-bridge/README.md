# Terminator training bridge

The first profile is Minecraft Java 1.21.4, Fabric Loader 0.16.10, Fabric
Networking API 4.4.0+db5e668204, and Java 21. It uses the vanilla integrated
server and real Minecraft client in one isolated process. HeadlessMc's LWJGL agent
supplies the headless client runtime.

See [Local sword duel](LOCAL-DUEL.md) to spar against a saved checkpoint
at normal game speed from a separate Java 1.21.4 client.

## Run a recorded episode

Set `JAVA_HOME` to a Java 21 JDK. The wrapper uses Gradle 8.12.1. Download the
[HeadlessMc 3.0.0-RC8 LWJGL agent](https://github.com/headlesshq/headlessmc/releases/download/3.0.0-RC8/headlessmc-lwjgl-3.0.0-RC8-all.jar)
and pass its absolute path. From this directory, run the following command.

```powershell
.\gradlew.bat runClient -PbridgePort=28764 "-PheadlessAgent=C:\path\headlessmc-lwjgl-3.0.0-RC8-all.jar"
```

The verified run used a fresh `run/` directory with
`onboardAccessibility:false`, `pauseOnLostFocus:false`, and `narrator:0`
in `run/options.txt`.

From the Rust workspace root, run the scripted controller after the bridge
announces its loopback port.

```powershell
New-Item -ItemType Directory -Force runs
cargo run -p terminator-training --example reach_and_stop -- 28764 runs/reach-and-stop.jsonl
```

Use a new trajectory filename on each run. The controller checks duplicate
and stale step handling, records the episode, and shuts down the game.
The bridge keeps generated saves in the isolated Gradle run directory.

The first verified run completed the movement task in 15 ticks. Audio and
automatic world thumbnails can emit warnings under the headless agent.

## Tick and observation contract

The listener is opt-in and binds to loopback. It exchanges one UTF-8 JSON
request and response per line using the existing Rust protocol. A reset
generates a fresh, structure-free superflat world from a fixed vanilla
generator profile and verifies the resolved spawn in the client and server.
The Reach and Stop arena covers X and Z in [0, 16), with player feet at Y=-60.

For each request, the client applies held movement and yaw for one tick.
It sends a marker on the same local connection after vanilla movement packets.
That marker authorizes one integrated-server tick. The bridge verifies world
time and player position agreement before returning the client observation
and server-scored metrics. Scoring measures speed as authoritative
horizontal displacement over the accepted tick. This synchronization order is specific to the
movement profile. Combat will need verified hit/interaction ordering and
opponent observations before its trajectories are suitable for training.

Retries with the same episode, step ID, starting tick, and controls return
the original response. Reusing a step ID with different inputs and sending a
new step at a stale tick are protocol errors. The Rust recorder writes one
row per accepted tick.

## Sword drill

`sword_drill/v1` uses the same worker protocol and flat arena. Set
`generator_version` to `sword_drill/v1` and supply `spawn_xyz`,
`spawn_yaw_degrees`, and `opponent_xz` in `resolved_setup`. The player
spawns with a diamond sword in the first hotbar slot, full diamond armor,
and three golden apples in the second slot. The default target is a
stationary husk. Set `moving_opponent` to `true` to equip it with a diamond
sword and enable its vanilla pursuit and melee AI on the first step.
Movement, jump, yaw, pitch, attack, hotbar selection, and item use are supported.
A rising attack edge triggers Minecraft's normal crosshair attack.
`hotbar_slot` is optional and zero-based. An absent slot preserves the
selection. Hold `use_item` across steps to eat and release it to cancel.
Select the sword again before resuming attacks.

The observation contains client-visible player state, attack cooldown,
and opponent position when tracked. The server scores damage and defeat
and returns damage dealt, damage taken, health, and opponent displacement
in the step metrics. Position in the policy payload comes from the client.
The client payload is sampled before the server tick. On an incoming hit,
use the server metrics for the damage label and resulting health. The
client health in that same row can still show the pre-hit value. The
server metrics also record selected slot, remaining apples, absorption,
and whether Regeneration is active. Inventory count refers to the apple
stack in the second hotbar slot for this fixed kit.

With a fresh worker listening, run the scripted policy on three spawn
layouts and record each bout separately. A fourth episode holds inputs
neutral to check incoming damage. Its file starts with `damage-probe-`.
Interrupted recordings retain the `.partial` suffix.

```powershell
cargo run -p terminator-training --example sword_bout -- 28764 runs/sword-bouts
```

To train a healing decision alongside a frozen sword checkpoint, use a
fresh worker and run:

```powershell
cargo run -p terminator-training --example sword_bout -- 28764 runs/healing-experiment --healing-experiment C:\path\sword-checkpoint.json
```

The experiment varies spawn layout, starting health, and opponent
equipment or pursuit speed. Wounded scenarios disable natural
regeneration. The learned tree decides when to start a healing attempt
from health, absorption, and distance, with apple availability checked
beforehand. A shared controller selects the apple, holds use, backs away
when close, and switches back after consumption. Only the initiation
decision is learned in this experiment.

To evaluate saved weights in a fresh worker, use `--healing-eval`
followed by the sword and healing checkpoint paths in that order.

Run the healing check in a fresh worker to verify that releasing use
cancels eating, then that continued use consumes one apple after taking
damage and applies Regeneration and Absorption.

```powershell
cargo run -p terminator-training --example sword_bout -- 28764 runs/healing --healing-check
```

To collect varied demonstrations, fit a checkpoint, and evaluate it in-game,
start a fresh worker and run:

```powershell
cargo run -p terminator-training --example sword_bout -- 28764 runs/sword-experiment --experiment
```

The runner attempts 12 demonstrations and fits a decision tree to movement
and attack actions from successful episodes. Aiming is geometric for both
controllers. It saves a versioned JSON checkpoint and reloads it through
`terminator_models::sword::SwordPolicy` before playing six reserved layouts
with each controller. Keep the `demo-`, `baseline-`, and `learned-`
recordings associated with that checkpoint. Layouts vary spawn angle,
distance, and player heading against the same pursuing husk.

Use `--strafe-experiment` in place of `--experiment` to record a
demonstrator that strafes between attacks and fit a checkpoint that can
choose either lateral direction. It adds a feature for space toward the
arena center and favors continuing the last strafe. The experiment uses
ordinary pursuit, faster pursuit, and an armored opponent in training and
reserved layouts. Geometric aiming is shared with the original policy.

To train jump and attack timing alongside a saved strafing policy, start a
fresh worker and run:

```powershell
cargo run -p terminator-training --example sword_bout -- 28764 runs/crit-experiment --crit-experiment C:\path\strafe.json C:\path\healing.json
```

The timing tree learns from scripted demonstrations using horizontal range,
height difference, sword cooldown, and jump phase. The existing tree still
selects movement and the default attack. Held-out bouts compare both
checkpoints with the same healing policy. Each recording's
`critical_hits` metric is a cumulative count from Minecraft's server.

To train W/S-tap selection using a saved crit checkpoint, start a fresh
worker and run:

```powershell
cargo run -p terminator-training --example sword_bout -- 28764 runs/tap-experiment --tap-experiment C:\path\crit.json C:\path\healing.json
```

The demonstrator releases forward after a sprint swing and adds a short
back input when the opponent remains close. It resumes the normal
movement policy after the reset. The experiment fits a tree from the
first reset frames after server-confirmed sprint hits and compares it
against the crit checkpoint with healing enabled on both. The recording
stores cumulative `sprint_hits` from the server's knockback branch.

The follow-on UHC kit calls for sword and axe selection, learned healing
decisions, water and lava buckets, cobblestone placement, and bow and
crossbow actions.
Its scenarios will exercise those mechanics separately before combining
them in full fights.

## Neural training

The neural supervisor and actor-critic runner use the same Minecraft
worker and control frame. Start a fresh bridge as above, then run from
the Rust repository root with the matching bridge port.

```powershell
cargo run --offline -p terminator-training --example neural_combat -- 28764 runs/neural-first bootstrap --train 6 --eval 3 --ticks 200
```

Use a fresh output directory for every run. Bootstrap reads the three
technique checkpoints and healing checkpoint from the sibling outputs
directory, and successful demonstration JSONLs from the sibling
toolchains directory. Override those locations with --assets and --demos
when working in another checkout.

The output directory contains before.json, after.json, manifest.json,
tick trajectories, and macro experience with rewards and behavior
probabilities. Six stochastic training bouts update weights explicitly.
Three reserved layouts each run the frozen crit baseline, bootstrap,
and trained candidate. The held-out armored_rush opponent configuration
is absent from bootstrap demonstrations and training. This is a
combination of known mechanics, and human styles remain future tests.

Use fine-tune --checkpoint PATH for additional explicit updates, or
eval --checkpoint PATH for evaluation without updates. Saved candidates
are kept alongside prior weights. Pass the neural checkpoint through
the ordinary local duel launcher's -Checkpoint argument. It contains its
own healing checkpoint, so omit -HealingCheckpoint.

For coordination experiments, --reference PATH evaluates a frozen
previous neural checkpoint on the same reserved scenarios and saves a
reference.json snapshot. --seed-offset N changes seeds and headings for
a fresh matched suite. Reports include within-bout skill switches,
decision probabilities and entropy, alongside combat outcomes.

Bootstrap presents every demonstration with four previous-skill labels,
so the source label cannot be recovered directly from the previous-skill
one-hot feature. Actor-critic updates include an entropy bonus to support
exploration. The encoder and checkpoint format remain compatible.

The [architecture assessment](../../docs/NEURAL-TRAINING.md) explains the
observation boundary, skill extension process, and next milestones.
