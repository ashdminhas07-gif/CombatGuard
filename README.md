# CombatGuard

Paper 1.21.11 combat-tag plugin built with Java 21.

## Features
- Tags both players for 20 seconds after player-vs-player damage; hits refresh the timer.
- Action bar countdown: `&c{seconds}s &fCombat`.
- Safe message when the tag expires.
- Blocks Ender Pearls and Ender Chest access while tagged.
- Combat disconnects enter a configurable 30-second reconnect grace period.
- Reconnecting during the grace period restores the saved inventory and cancels the logout drop.
- If the grace period expires, the saved inventory drops at the disconnect location.
- Pending inventories persist in plugins/CombatGuard/pending.yml across restarts.

## Build
Requires Java 21 and Maven: `mvn clean package`. The jar is target/CombatGuard.jar.
GitHub Actions builds on every push to main. Download the jar from the latest successful workflow run under Artifacts.

## Network disconnect limitation
Paper cannot reliably distinguish a deliberate disconnect from a network failure such as a Java Connection reset / Internal Exception. The plugin therefore applies the same short reconnect grace period to every combat disconnect. It cannot prove the cause of a disconnect.
