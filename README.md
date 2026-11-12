# meridian-auto-fighter

Fights, with the player's own character or a headless one: fights back whoever attacks (Guard),
hunts mobs in an area (Farm), or follows a player and defends them (Escort). It decides whom to
fight and when to eat, block, run or repair. Walking, the hands and the reading of the fight are
core's: `Navigator` (with its chase), `InteractionControl`, `EntityTracker` (health of every
entity), `Combat` (who hit us, who winds up an attack) and `Movement` (the wheel, with urgent
holds).

It is a Layer-2 module: meridian-api and meridian-core-api only, no packets.

## Design

### What core gives (added on core's `combat` branch)

| Need | Core API | Where the truth comes from |
|---|---|---|
| Another entity's health | `EntityTracker.statOf(id, name)`, `healthOf(id)` | `EntityStatsUpdate` per entity - the server shares `Health` with every viewer (`Health.json`: `Shared`), the bars the game draws over mobs |
| What an entity holds | `EntityTracker.heldItemOf(id)` | `EquipmentUpdate.rightHandItemId` |
| Who hit us | `Combat.hits(within)`, `Combat.Listener.hit` | `DamageInfo` - the server sends it to a player hurt by an entity, with the attacker's position at that moment (`DamageSystems`, line ~1448); core names the tracked entity standing there |
| Who winds up an attack | `Combat.swingOf(id, within)`, `Combat.Listener.swing` | `PlayInteractionFor` - the server plays every interaction an NPC or another player starts to the players in view (`Interaction.sendPlayInteract`); the first one of a `Primary`/`Secondary` chain is the start of an attack. A zombie's blow lands about 0.3 s after its attack shows (measured on the stand; its asset: `RunTime` 0.2 before its selector) |
| How far a weapon reaches | `InteractionControl.hitReach(item)` | the far end of the item's own swing selector (`Weapon_Sword_Primary_Swing_Left_Selector`: `EndDistance` 2.75), followed through the item's `InteractionVars` |
| Whether an item guards | `InteractionControl.guards(item)` | its `Secondary` reaches a `WieldingInteraction` with `AngledWielding` / damage modifiers (sword, shield, spear, bow guard) |
| What heals | `InteractionControl.heal(item)` | its `Secondary` applies an effect raising `Health` (`Food_Instant_Heal_T1`, `Food_Health_Regen_Small`, `Potion_Health_*`) or changes `Health` itself |
| Repair | `InteractionControl.repair(item)` | see "Repair" below |
| A moving goal | `Navigator.chase(target, within)` | core's own `Walker`: the way is found again when the target has moved off the cell it was found to; done once within reach |
| Wheel priority | `Movement.takeFirst()` | core: while an urgent hold is held, a trip, a dig or a swing loop begun before it waits, and goes on from where the body is when the last urgent hold is given back |
| The best weapon | `Player.bestWeaponSlot()` | the item catalog's `weapon` and `itemLevel`, the swing's reach, the hotbar's durability |

Core's own write-up: [meridian-core/docs/combat.md](../meridian-core/docs/combat.md) (on the
`combat` branch).

### Hostility

There is no attitude on the wire (no NPC role, no "aggro" flag reaches the client). So an entity is
an enemy when, by the settings:

- it hit us (`Combat.hits`) - always, in every mode;
- it began an attack within 6 blocks of us or of the escorted player (`Combat.swingOf`);
- it is of a kind on the "hostile kinds" list (its model id: `Zombie`, `Skeleton_Fighter`...),
  within the guard radius;
- it is a player, only when "Players too" is on. Players are never chosen by kind alone; a player
  who hit us is an enemy when "Players too" is on. (A player is an entity with a skin, or wearing
  the player model: a headless one on an insecure server has no skin.) PvP itself is the server's:
  off in a new world (`/world config pvp true`).

In Farm mode the kinds to farm are enemies by kind regardless of their attitude (a sheep, a cow).

### The fight loop (melee)

One target at a time - the one hitting us, else the nearest enemy. Each beat (about 50 ms):

1. **Weapon**: the hotbar's best melee weapon (`Player.bestWeaponSlot`: a weapon whose swing
   reaches entities, the highest item level, not broken). It is taken when it is not in hand.
2. **Approach**: farther than its reach (eyes to the nearest of the target's box, less 0.35) the
   body chases it (`Navigator.chase`, to within the reach less 0.9 across). Out of the radius + 8,
   dead or gone from sight, the target is let go.
3. **Face**: the head turns to the middle of the target's box (`Movement.face`).
4. **Block** (with a weapon that guards, stamina above 2):
   - the moment the target begins an attack (`Combat.Listener.swing`, on the network's thread), the
     guard goes up for 0.65 s (`useItem(hold)`) - after our swing under way, if there is one;
   - by its rhythm: once it has attacked, swings go on only within the window it leaves - the
     shortest gap seen between its attacks less 0.5 s (1.5 s before it has attacked twice); past
     that its next blow is near, so the body waits with nothing of its own in the guard's way, for
     up to 1.5 s more. A zombie's blow lands about 0.3 s after its attack shows: a guard raised
     then is in time, one queued behind a swing often is not.
   - A blow that comes on the guard does no harm: the server's hit says 0 damage. Those are
     counted as `blocked`.
5. **Swing**: otherwise `hitEntity`. The server keeps the rhythm and the stamina (the
   dagger's 3 hits then a wait are the server's, not ours) - the forge queue plays a swing, then
   the next, as a held button does. A refused swing is just the next beat.

A kill is a target that falls (its health at its min, or gone) within 5 s of our last hit on it.

### Survival

- **Eat/heal** at the heal threshold (default 50 %): the hotbar's healing item that heals the most
  right away (`heal`), taken, used, and the weapon taken back. Not while the target is winding up
  within reach.
- **Retreat** at the retreat threshold (default 25 %): away from the enemies, 8 blocks, the way
  the navigator finds; there it heals; back to the fight once above the heal threshold. With
  nothing that heals, a retreat is pointless (players do not regenerate): the fight goes on, and
  Farm stops once it is over.
- **Repair** between fights: the best weapon worn below 10 % is repaired with a repair kit from the
  hotbar (`InteractionControl.repair`); without a kit, the next best weapon that is not worn is
  taken, and a broken one is never taken.
- Between fights Guard takes an urgent hold for a heal or a repair too, so a job under way does not
  swing with the potion in hand.

### How Guard takes the wheel from another job

Guard watches all the time (it holds nothing while idle). When an enemy shows up it takes an
**urgent hold** (`Movement.takeFirst()`): the job under way - the auto-miner's dig, the blueprint's
placing, a "Go to a point" trip - is held back by core, not ended: its move under way stops, its
trip waits, its swing loop waits between swings. Guard fights, then gives the hold back, and the
job goes on from where the body is (its trip finds the way again). The job's own hold stays all
the time, so the body never goes back to the game in between. A key the player presses ends
everything, as always.

### Modes, settings and commands

In the proxy's window, the **Fight** section:

- **Mode**: Guard / Farm / Escort (Stop is off).
- **Targets**: hostile kinds (a list), players too (off by default), the guard radius.
- **Farm**: which kind (a part of the model id), how many (-1: no end), the area (around where
  it started, a radius), pick up the drops. A kill's drops are waited for (up to 3.5 s: a mob
  drops its loot when its dying is over) and walked to (`Worker.pickUp`); `picked` counts what
  the player carries more of since the job began. Farm ends after 15 s with nothing of the kind
  about.
- **Escort**: the player's name, the distance kept.
- **Survival**: heal at %, retreat at %, block (on), repair (on).
- **Outline the target** (on): the target's box in red, through walls (`DebugRender`).
- **Start**, **Stop**, and a status line.

On the command channel:

```
fight <conn> guard [radius]                    -> starts guarding; {mode, radius}
fight <conn> farm <kind> [count] [radius]      -> answered when done: {status, kills, picked, ...}
fight <conn> escort <player> [distance]        -> starts escorting; {mode, player}
fight <conn> stop                              -> {stopped, status, kills, swings, guards, blocked, ...}
fight <conn> status                            -> {mode, status, target, fighting, kills, guards, blocked,
                                                   heals, retreats, repairs, picked, hitsTaken, damageTaken, health}
fight <conn> set heal|retreat <percent> | set block|repair|players|pickup on|off
fight <conn> set hostile <kind,kind,...> | set radius <blocks>

status: DONE | NOTHING | STOPPED | INTERRUPTED (the player took the body) | DIED | NOT_AVAILABLE | FAILED
```

### Repair

The server's repair is the repair kit (`Tool_Repair_Kit_*`, item set `Repair_Kit`): its use
(Primary or Secondary) runs `OpenCustomUI` with the page `ItemRepair` (`ItemRepairPageSupplier`).
The server sends the client a page (`Pages/ItemRepairPage.ui`) listing the damaged items of the
hotbar and utility, most worn first, one button per item (`#ElementList[i]`, data
`{"Index":"i"}`); a click runs `RepairItemInteraction`: one kit used up, durability back to a
new, lower maximum (`RepairPenalty` 0.2 crude, 0.1 iron), the page closed. No bench repairs.

From the client this is possible as a player does it: core uses the kit, waits for that page,
takes the element whose icon is the item, waits until the page is acknowledged (a game's client
does it; for a headless one core answers pages as a client does), and sends the button's event.
That is `InteractionControl.repair(item)`.

## Not yet

- **Bows and other ranged weapons.** The fight is melee only. `hitReach` is empty for a bow, so
  `Player.bestWeaponSlot` never picks one, and a ranged fight would slot in as another way to
  attack, beside the swing: `swing(hold)` already draws and shoots.
- **A guard does not cut a swing short.** The client lets a guard interrupt a sword's swing
  (`InterruptedBy: Secondary`); core plays one chain after another, so a guard raised during our
  own swing goes out when that swing is over. Against a zombie (its blow lands about 0.3 s after
  its attack shows) many attacks are seen too late to block.
- **A shield in the utility slot on a headless player.** Core forges with the utility item once it
  knows which utility slot is active; the server never tells a headless player that.
- **Food out of the hotbar.** Only the hotbar is looked at for healing items, weapons and kits.
- **Mobs that run.** A chase goes on while the target moves on; a mob faster than the body is
  never caught. The target is let go only when it is out of the radius plus 8.
