# Recording and data boundaries

The passive 26.2 recorder is part of `terminator-arena-26.2.jar`, compiled from
`training/fabric-arena-26.2`. It requires Java 25 and Fabric Loader 0.19.3 or newer
on Minecraft Java 26.2. It starts no model, optimizer or input controller.

Commands entered in the client's chat are intercepted locally:

```text
!terminator record mode mole
!terminator record task mine_route
!terminator record start mole_practice_01
!terminator record stop
```

Output defaults to the profile's `dataset-recordings-26.2/` directory. JVM
properties `terminator.dataset.output`, `terminator.dataset.participant`,
`terminator.dataset.participantSplit` and `terminator.dataset.setup` can configure
the location, participant grouping, reserved split and declared scenario file.
The setup is metadata, not independent server attestation.

Frames record own state, canonical inventory slots, logical held inputs,
look changes, crosshair information and nearest loaded entities. Nearby loaded
blocks are sampled every five frames. Opponent health/cooldown and hidden server
information are not included. Loaded information is not necessarily line-of-sight
visible. Privileged setup/reset feedback belongs in metadata, not actor input.

These are end-of-client-tick snapshots, not pre-aligned RL transitions. An attack
invocation is not a confirmed hit. Native inputs are labeled
`unverified_client_inputs`: a passive recorder cannot establish whether another
mod or a physical device produced the logical input state. Menu frames, incomplete
sessions, dropped frames, source-game version and provenance require review.

The fireball adapter activates only with the explicit private-arena JVM flag
`terminator.trainingArena=true` and the configured player tag. It uses native
fireballs, power 1, initial speed 0.6 and a 20-tick cooldown. It is a local 26.2
variant, not a promise of another server's custom knockback or minigame rules.

Use generated reset functions only in dedicated training worlds. The companion
development workflow binds a server to localhost and disables authentication;
it must not be exposed to remote testers without a separate hosting and
authentication design. It does not require a second account for local practice.

Game binaries/assets and the private dataset pseudonymization key are deliberately
absent. The Minecraft EULA must be accepted by the person running a dedicated
server. A public repository or dataset does not supply that acceptance for users.
