# Local sword duel on Minecraft Java 1.21.4

Terminator runs in a separate headless Minecraft client that hosts a new
flat arena. Your regular Java 1.21.4 client connects over IPv4 loopback.
The arena keeps Minecraft's normal tick rate and player combat mechanics.
The bot uses the same Rust `SwordPolicy` and JSON checkpoint as the
held-out sword tests. The sparring checkpoint switches between movement
and attacks. You can eat the kit's golden apples during the duel.
Pass the checkpoint from `--strafe-experiment` as `-Checkpoint` to use
the policy that can also strafe in either direction.
Pass the checkpoint from `--crit-experiment` as `-Checkpoint` for the
strafing policy with learned jump and falling attack timing. The host log
prints the bot's executed policy jumps and each critical hit it lands on
the other player. The end-of-duel jump summary counts applied jump inputs
and executed jumps separately.
The arena border is aligned to whole blocks so the movement wall and
server attack boundary agree. The policy checks whether its planned aim
can pick the opponent before committing to a sword attack or a jump.
With the crit checkpoint, the controller can start a jump while the
opponent is within reach and recent movement suggests the opponent will
stay close through descent. A pause between planned jumps leaves time
for grounded attacks. The learned timing tree still handles falling
attacks outside planned jumps.
Pass the checkpoint from `--tap-experiment` as `-Checkpoint` to add
W/S tapping. The host log reports landed sprint knockback hits alongside
crits. At close range the reset may include a short backward step.
Pass `-HealingCheckpoint` with a saved healing checkpoint to let the bot
decide when to eat as well. The healing controller selects the apple and
switches back to the sword after eating.
It spends at least 40 ticks back on its sword after an eating attempt and
waits until less than one absorption point remains before starting another.

One Java account is sufficient. Terminator uses the development client's
local profile. The isolated arena accepts the configured local profile
name and binds only to `127.0.0.1`. This setup is for sparring on the
same PC. An authenticated server has different login requirements.

## Windows launch

Set `JAVA_HOME` to Java 21. Have Rust/Cargo, the HeadlessMc LWJGL agent
linked in the [bridge guide](README.md), and the sword checkpoint ready.
Run this from the bridge directory, substituting the exact profile name
displayed in the Minecraft Launcher.

```powershell
.\Start-LocalDuel.ps1 -JavaUsername MyProfile -Checkpoint C:\path\sword-checkpoint.json -HeadlessAgent C:\path\headlessmc-lwjgl-3.0.0-RC8-all.jar
```

Wait until the runner prints the connection address. Launch your regular
Java 1.21.4 installation and choose **Multiplayer → Direct Connection**.
Enter `127.0.0.1:25568` (or the port you passed via `-GamePort`). The
three-second countdown begins after joining. Both sides receive a diamond
sword, full diamond armor, and three golden apples. Press Enter in the
runner console after a bout to close the arena. Run the launcher again
for a new match. Existing single-player saves are separate.

The game ticks independently of inference. The headless host disables
Minecraft's AFK frame-rate throttle. The bridge applies a recent control
frame at most once and releases its held inputs after each tick. Frames
older than three client ticks or 225 milliseconds are dropped.
The automated two-client check uses
`runDuelGuest` with the name `DuelGuest` and default game port, while
ordinary sparring uses your own client and keyboard.

Every bout now creates a spar-recordings JSONL beside the supplied
checkpoint. It stores a checkpoint snapshot, policy observations,
requested and applied controls, tick provenance, dropped-frame status,
inference timing, and separate authoritative server metrics. Neural
decisions also record the selected skill and predicted probabilities
and value. These files support diagnosis and later explicit training.
Playing a bout runs frozen inference.

Use -SessionMetadata with a private JSON file to record a player
pseudonym, development/validation/evaluation cohort and protocol context.
This metadata stays outside policy observations. See the
[human-test plan](../../docs/HUMAN-TRAINING.md) for the schema and
recording analysis command.

The local runner recognizes the separate `neural_recovery` checkpoint format.
It embeds the accepted combat base and the experimental fight/retreat/heal
network, and accepts no additional healing file. The packaged launcher exposes
it through `-RecoveryCandidate` when `terminator-recovery-candidate.json` is
present beside the launcher. `-Neural` continues to select the accepted
checkpoint. See [recovery training](../../docs/RECOVERY-TRAINING.md) for the
learned choices and deterministic execution rules.

`-CoordinatedRecovery` selects the separate V2 candidate, with a bounded
recover-to-eat action. It preserves the same normal-speed, one-account local
join workflow. See [V2 measurements](../../docs/COORDINATED-RECOVERY.md) for its
regression checks, comparisons and current limitations.

`-EvasiveRecovery` selects the same learned recovery choices with the optional
evasive eating controller. `-CoordinatedRecovery` preserves the prior straight
eating controller for rollback. Both use the existing local join address.
See [eating movement](../../docs/EATING-MOVEMENT.md) for the frozen comparison.
