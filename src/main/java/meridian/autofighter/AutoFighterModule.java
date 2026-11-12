package meridian.autofighter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import meridian.api.module.ModuleContext;
import meridian.api.module.ProxyModule;
import meridian.api.settings.SettingsSpec;
import meridian.core.api.Combat;
import meridian.core.api.DebugRender;
import meridian.core.api.EntityTracker;
import meridian.core.api.InteractionControl;
import meridian.core.api.Movement;
import meridian.core.api.Navigator;
import meridian.core.api.PlayerState;
import meridian.core.api.Worker;
import meridian.core.api.World;

/**
 * meridian-auto-fighter: fights, with the player's own character or a headless one - Guard (whoever
 * attacks, taking the wheel from a job under way and giving it back after), Farm (mobs of a kind round
 * where it started, their drops picked up) and Escort (a player followed and defended); and with no
 * mode started, "Guard the miner and builder": a fight for core's jobs only, never taking the body from
 * the player playing by hand. Whom to fight and when to eat, block, back off or repair is decided here
 * ({@link Fighter}); the walking, the hands and the reading of the fight are core's. In the proxy's
 * window: the Fight section, its choices kept across restarts. On the command channel:
 * {@code fight <conn> guard|farm|escort|stop|status|set ...}.
 */
public class AutoFighterModule implements ProxyModule {

    private static final String USAGE = "guard [radius] | farm <kind> [count, -1 no end] [radius] | escort <player> [distance]"
            + " | stop | status | set heal|retreat <percent> | set block|repair|players|pickup|guardjobs|overpower on|off"
            + " | set hostile <kind,kind,...> | set radius <blocks>";

    /** What the Start button starts. */
    enum ModeChoice {
        GUARD("Guard: fight back whoever attacks"),
        FARM("Farm: hunt mobs of a kind here"),
        ESCORT("Escort: follow a player and defend them");

        private final String label;

        ModeChoice(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private volatile ModeChoice choice = ModeChoice.GUARD;
    private volatile List<String> hostile = Fighter.Options.HOSTILE;
    private volatile boolean players;
    private volatile int radius = 12;
    private volatile String farmKind = "Sheep";
    private volatile int farmCount = -1;
    private volatile int farmRadius = 24;
    private volatile boolean pickUp = true;
    private volatile String escortName = "";
    private volatile int escortDistance = 3;
    private volatile int healAt = 50;
    private volatile int retreatAt = 25;
    private volatile boolean block = true;
    private volatile boolean repair = true;
    /** With no mode started, fight for core's jobs that have the body (the miner's, the builder's, a trip). */
    private volatile boolean guardJobs = true;
    /** Our swing runs to its end, the guard after - what the server lets through; off, the game's way. */
    private volatile boolean overpower;
    private volatile String note = "";
    private volatile Fighter running;

    @Override
    public void onEnable(ModuleContext ctx) {
        Fighter fighter = new Fighter(
                ctx.services().require(Movement.class),
                ctx.services().require(Navigator.class),
                ctx.services().require(EntityTracker.class),
                ctx.services().require(InteractionControl.class),
                ctx.services().require(World.class),
                ctx.services().get(Combat.class).orElse(null),
                ctx.services().get(PlayerState.class).orElse(null),
                ctx.services().get(Worker.class).orElse(null),
                ctx.services().get(DebugRender.class).orElse(null),
                ctx.offloadExecutor(), ctx.getLogger());
        // The sentry fights by the panel as it is at each beat.
        fighter.liveOptions(this::options);
        running = fighter;
        try {
            ctx.registerCommand("fight", USAGE, (args, json) -> command(fighter, args));
        } catch (NoSuchMethodError olderProxy) {
            // no command channel there
        }
        SettingsSpec targets = SettingsSpec.builder()
                .stringList("fightHostile", "Hostile kinds (a part of the model id)", hostile,
                        v -> hostile = v == null ? List.of() : List.copyOf(v))
                .bool("fightPlayers", "Players too (those who hit me or attack near me)", players, v -> players = v)
                .int_("fightRadius", "Fight what comes within (blocks)", 2, 64, radius, v -> radius = v)
                .build();
        SettingsSpec farm = SettingsSpec.builder()
                .string("fightFarmKind", "Kind to farm (a part of the model id)", farmKind,
                        v -> farmKind = v == null ? "" : v.trim())
                .int_("fightFarmCount", "How many (-1: no end)", -1, 10000, farmCount, v -> farmCount = v)
                .int_("fightFarmRadius", "Around where it starts (blocks)", 4, 128, farmRadius, v -> farmRadius = v)
                .bool("fightPickUp", "Pick up the drops", pickUp, v -> pickUp = v)
                .build();
        SettingsSpec escort = SettingsSpec.builder()
                .string("fightEscortName", "Player to escort (name)", escortName,
                        v -> escortName = v == null ? "" : v.trim())
                .int_("fightEscortDistance", "Keep within (blocks)", 1, 16, escortDistance, v -> escortDistance = v)
                .build();
        SettingsSpec survival = SettingsSpec.builder()
                .int_("fightHealAt", "Heal at health (%)", 0, 100, healAt, v -> healAt = v)
                .int_("fightRetreatAt", "Back off at health (%)", 0, 100, retreatAt, v -> retreatAt = v)
                .bool("fightBlock", "Block when the enemy swings", block, v -> block = v)
                .bool("fightOverpower", "Overpower: my swing always lands, the guard after it", overpower,
                        v -> overpower = v)
                .bool("fightRepair", "Repair a worn weapon with a kit", repair, v -> repair = v)
                .build();
        SettingsSpec.Builder spec = SettingsSpec.builder()
                .bool("fightGuardJobs", "Guard the miner and builder (with no mode started)", guardJobs, v -> {
                    guardJobs = v;
                    fighter.guardJobs(v);
                })
                .enum_("fightMode", "Mode", ModeChoice.class, ModeChoice.GUARD, v -> choice = v)
                .section("Targets", targets)
                .section("Farm", farm)
                .section("Escort", escort)
                .section("Survival", survival)
                .bool("fightShowTarget", "Outline the target", true, fighter::showTarget)
                .button("Start", () -> start(fighter))
                .button("Stop", () -> {
                    note = "";
                    fighter.stop();
                })
                .liveText("Fight", () -> note.isEmpty() ? fighter.status() : note);
        // Kept across restarts, every choice of the panel: saved values come back through the same
        // setters at the start.
        spec.persistent("fightGuardJobs", "fightMode", "fightHostile", "fightPlayers", "fightRadius", "fightFarmKind",
                "fightFarmCount", "fightFarmRadius", "fightPickUp", "fightEscortName", "fightEscortDistance",
                "fightHealAt", "fightRetreatAt", "fightBlock", "fightOverpower", "fightRepair", "fightShowTarget");
        ctx.registerSettings(SettingsSpec.builder().section("Fight", spec.build()).build());
        ctx.getLogger().info("meridian-auto-fighter enabled");
    }

    @Override
    public void onDisable() {
        Fighter f = running;
        running = null;
        if (f != null) {
            f.shutdown();   // the sentry runs as long as the module: it ends with it
        }
    }

    private Fighter.Options options() {
        return new Fighter.Options(hostile, players, radius, healAt, retreatAt, block, repair, pickUp, overpower);
    }

    /** Starts what the mode says; the Fight line says how it goes. */
    private void start(Fighter fighter) {
        note = "";
        switch (choice) {
            case GUARD -> fighter.guard(options());
            case FARM -> {
                if (farmKind.isBlank()) {
                    note = "Say which kind to farm: a part of its model id, like Sheep.";
                    return;
                }
                fighter.farm(farmKind, farmCount, farmRadius, options());
            }
            case ESCORT -> {
                if (escortName.isBlank()) {
                    note = "Say whom to escort: the player's name.";
                    return;
                }
                fighter.escort(escortName, escortDistance, options());
            }
        }
    }

    private CompletableFuture<?> command(Fighter fighter, List<String> args) {
        String how = args.isEmpty() ? "" : args.get(0).toLowerCase(Locale.ROOT);
        switch (how) {
            case "status" -> {
                return CompletableFuture.completedFuture(fighter.describe());
            }
            case "stop" -> {
                return fighter.stop().thenApply(r -> {
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("stopped", true);
                    if (r != null) {
                        out.putAll(Fighter.counts(r));
                    }
                    return out;
                });
            }
            case "set" -> {
                return CompletableFuture.completedFuture(set(fighter, args));
            }
            case "guard" -> {
                if (args.size() > 1) {
                    radius = (int) Math.round(Double.parseDouble(args.get(1)));
                }
                fighter.guard(options());
                return CompletableFuture.completedFuture(Map.of("mode", "GUARD", "radius", radius));
            }
            case "escort" -> {
                if (args.size() < 2) {
                    throw new IllegalArgumentException(USAGE);
                }
                escortName = args.get(1);
                if (args.size() > 2) {
                    escortDistance = (int) Math.round(Double.parseDouble(args.get(2)));
                }
                fighter.escort(escortName, escortDistance, options());
                return CompletableFuture.completedFuture(Map.of("mode", "ESCORT", "player", escortName));
            }
            case "farm" -> {
                if (args.size() < 2) {
                    throw new IllegalArgumentException(USAGE);
                }
                String kind = args.get(1);
                int n = args.size() > 2 ? Integer.parseInt(args.get(2)) : -1;
                double r = args.size() > 3 ? Double.parseDouble(args.get(3)) : farmRadius;
                return fighter.farm(kind, n, r, options()).thenApply(Fighter::counts);
            }
            default -> throw new IllegalArgumentException(USAGE);
        }
    }

    /**
     * {@code set heal|retreat <percent> | set block|repair|players|pickup|guardjobs|overpower on|off |
     * set hostile <a,b> | set radius <n>}.
     */
    private Map<String, Object> set(Fighter fighter, List<String> args) {
        String what = args.size() > 1 ? args.get(1).toLowerCase(Locale.ROOT) : "";
        String value = args.size() > 2 ? args.get(2) : "";
        boolean on = value.equalsIgnoreCase("on") || value.equalsIgnoreCase("true");
        switch (what) {
            case "heal" -> healAt = Integer.parseInt(value);
            case "retreat" -> retreatAt = Integer.parseInt(value);
            case "block" -> block = on;
            case "repair" -> repair = on;
            case "players" -> players = on;
            case "pickup" -> pickUp = on;
            case "overpower" -> overpower = on;
            case "guardjobs" -> {
                guardJobs = on;
                fighter.guardJobs(on);
            }
            case "radius" -> radius = Integer.parseInt(value);
            case "hostile" -> {
                List<String> kinds = new ArrayList<>();
                for (String k : value.split(",")) {
                    if (!k.isBlank()) {
                        kinds.add(k.trim());
                    }
                }
                hostile = List.copyOf(kinds);
            }
            default -> throw new IllegalArgumentException(USAGE);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("heal", healAt);
        out.put("retreat", retreatAt);
        out.put("block", block);
        out.put("repair", repair);
        out.put("players", players);
        out.put("pickup", pickUp);
        out.put("overpower", overpower);
        out.put("guardJobs", guardJobs);
        out.put("radius", radius);
        out.put("hostile", hostile);
        return out;
    }
}
