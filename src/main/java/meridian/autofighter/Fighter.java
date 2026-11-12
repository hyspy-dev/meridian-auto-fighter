package meridian.autofighter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import meridian.core.api.Box;
import meridian.core.api.Combat;
import meridian.core.api.Containers;
import meridian.core.api.DebugRender;
import meridian.core.api.EntityTracker;
import meridian.core.api.Goal;
import meridian.core.api.InteractionControl;
import meridian.core.api.InteractionOutcome;
import meridian.core.api.Movement;
import meridian.core.api.NavResult;
import meridian.core.api.Navigator;
import meridian.core.api.Player;
import meridian.core.api.PlayerState;
import meridian.core.api.Vec3;
import meridian.core.api.Worker;
import meridian.core.api.World;
import org.slf4j.Logger;

/**
 * Whom to fight and when, and how: a beat at a time, about 20 a second.
 *
 * <ul>
 *   <li><b>Guard</b> watches without holding the body. When an enemy shows up it takes an urgent
 *       hold ({@link Movement#takeFirst}), so a job under way - the auto-miner's, the builder's -
 *       waits; it fights, then gives the hold back and that job goes on. With no job, it takes the
 *       body from the player playing by hand, and leaves it to them a while after a key.</li>
 *   <li><b>Sentry</b> - no mode started, "Guard the miner and builder" on - is Guard for core's
 *       jobs only: it fights only while another hold has the body ({@link Movement#holds}), and
 *       never takes the body from the player playing by hand.</li>
 *   <li><b>Farm</b> holds the body for the job and hunts mobs of a kind round where it started,
 *       picking up what they drop.</li>
 *   <li><b>Escort</b> holds the body, follows a player and fights what threatens them.</li>
 * </ul>
 *
 * <p>A fight is melee: the hotbar's best weapon ({@link Player#bestWeaponSlot}), the body taken to
 * within its reach ({@link Navigator#chase}), the head to the target, a guard raised when the target
 * begins an attack ({@link Combat#swingOf}), else a swing ({@link InteractionControl#hitEntity}).
 * The server keeps the rhythm and the stamina; a refused swing is just the next beat. Below the heal
 * threshold the hotbar's best healing item is used; below the retreat one the body backs off, heals
 * and comes back. A worn weapon is repaired with a kit, or the next best is taken.
 */
final class Fighter {

    /**
     * What the fighter is doing. {@code SENTRY}: no mode started, guarding the jobs that have the
     * body ("Guard the miner and builder").
     */
    enum Mode { OFF, GUARD, FARM, ESCORT, SENTRY }

    /**
     * How it fights.
     *
     * @param hostile   parts of the model ids of mobs that are enemies on sight ({@code Zombie})
     * @param players   players may be enemies - those who hit us, or begin attacks near us
     * @param radius    how far round the body (Escort: the player) an enemy on sight is fought
     * @param healAt    health, in percent, at or under which it heals
     * @param retreatAt health, in percent, at or under which it backs off to heal
     * @param block     raise the guard when the target begins an attack
     * @param repair    repair a worn weapon with a kit from the hotbar
     * @param pickUp    Farm: pick up what the kills drop
     * @param overpower the guard waits for our swing under way, which lands: what the server lets
     *                  through. Off, the guard cuts the swing short where its rules let it, as the
     *                  game does ({@link InteractionControl#useItem(Duration, boolean)})
     */
    record Options(List<String> hostile, boolean players, double radius, int healAt, int retreatAt, boolean block,
                   boolean repair, boolean pickUp, boolean overpower) {

        static final List<String> HOSTILE = List.of("Zombie", "Skeleton", "Spider", "Goblin", "Trork", "Scarak",
                "Wraith", "Void", "Ghoul", "Outlander");
        static final Options DEFAULT = new Options(HOSTILE, false, 12, 50, 25, true, true, true, false);
    }

    /** What a job did. */
    record Result(String status, int kills, int swings, int cut, int guards, int blocked, int heals, int retreats,
                  int repairs, int picked, int hitsTaken, double damageTaken, long millis) {}

    /** A beat, in milliseconds. */
    static final long BEAT_MS = 50;
    /** A swing is begun this much inside the weapon's reach, eyes to the target's box. */
    static final double REACH_MARGIN = 0.35;
    /** The reach of a bare hand when core cannot tell it. */
    static final double FIST_REACH = 2.0;
    /** A target farther than this past the radius is let go. */
    static final double LEASH = 8;
    /** An entity that hit us this recently is an enemy. */
    static final Duration HIT_MEMORY = Duration.ofSeconds(30);
    /** An entity that began an attack this recently, this near, is an enemy. */
    static final Duration SWING_MEMORY = Duration.ofSeconds(5);
    static final double SWING_NEAR = 6;
    /** An attack begun this recently is still coming: time to raise the guard. */
    static final Duration WINDING_UP = Duration.ofMillis(450);
    /** How long the guard is held. */
    static final Duration GUARD_HOLD = Duration.ofMillis(650);
    /** A blow this soon after the guard went up came on the guard. */
    static final long GUARD_COVERS_MS = 900;
    /**
     * With the block on, swings stop this long after the target's last attack began - its next is
     * near - until it begins one (or {@link #WAIT_FOR_BLOW_MS} passes): the guard then goes up at
     * once, with nothing of ours in the way. Learnt from the target's own rhythm once it has
     * attacked twice: the shortest gap seen, less {@link #BLOW_LEAD_MS}.
     */
    static final long SWING_WINDOW_MS = 1500;
    static final long BLOW_LEAD_MS = 500;
    /** The longest the next blow is waited for before swinging again. */
    static final long WAIT_FOR_BLOW_MS = 1500;
    /** A mob reaches this far with its blow, about: within it, a blow that is coming is guarded. */
    static final double ENEMY_REACH = 3.5;
    /** The guard costs stamina; under this much it is not raised. */
    static final double GUARD_STAMINA = 2;
    /** A kill is ours when we hit it this recently. */
    static final long KILL_CREDIT_MS = 5000;
    /** A weapon worn below this share of its durability is repaired, or put aside. */
    static final double WORN = 0.10;
    /** How far a retreat goes, at most. */
    static final double RETREAT = 8;
    /** After a retreat, none again for this long. */
    static final long RETREAT_COOLDOWN_MS = 8000;
    /** A player who takes the body back in a Guard fight is left to it for this long. */
    static final long PLAYER_TOOK_MS = 10_000;
    /** A sentry with no job to guard looks again this often. */
    static final long SENTRY_IDLE_MS = 200;
    /** Farm: with nothing of the kind about for this long, the job is over. */
    static final long FARM_IDLE_MS = 15_000;
    /** Drops are looked for this near where a kill fell. */
    static final double DROPS_NEAR = 3.0;
    /** The longest a kill's drops are waited for: a mob drops its loot when its dying is over. */
    static final long DROPS_WAIT_MS = 3500;
    /** The id of the box that outlines the target ({@link DebugRender#worldBox}). */
    static final String TARGET_BOX = "meridian-auto-fighter:target";

    private final Movement movement;
    private final Navigator navigator;
    private final EntityTracker entities;
    private final InteractionControl hands;
    private final World world;
    /** The fight as core reads it; {@code null} where core does not offer it - then no one is known to have hit us. */
    private final Combat combat;
    private final PlayerState state;
    private final Worker worker;
    private final DebugRender debug;
    private final Executor executor;
    private final Logger log;

    /** How far each item's swing reaches, as core said once ({@link InteractionControl#hitReach}). */
    private final Map<String, java.util.OptionalDouble> reaches = new java.util.concurrent.ConcurrentHashMap<>();
    /** Whether each item guards, as core said once. */
    private final Map<String, Boolean> guarding = new java.util.concurrent.ConcurrentHashMap<>();
    /** What each item heals, as core said once. */
    private final Map<String, Optional<InteractionControl.Heal>> healing = new java.util.concurrent.ConcurrentHashMap<>();

    private double reachOf(String item) {
        return reaches.computeIfAbsent(item == null ? "" : item, hands::hitReach).orElse(FIST_REACH);
    }

    private boolean guardsWith(String item) {
        return guarding.computeIfAbsent(item == null ? "" : item, hands::guards);
    }

    private Optional<InteractionControl.Heal> healOf(String item) {
        return healing.computeIfAbsent(item, hands::heal);
    }

    /** The job under way; a new one, or a stop, moves it on. */
    private final AtomicInteger job = new AtomicInteger();
    private volatile CompletableFuture<Result> running = CompletableFuture.completedFuture(null);
    private volatile Mode mode = Mode.OFF;
    private volatile String status = "Off.";
    private volatile Job current;
    private volatile boolean showTarget = true;
    /** "Guard the miner and builder": with no mode started, a sentry guards the jobs that have the body. */
    private volatile boolean guardJobs;
    /** The options as the panel has them now - what the sentry fights by. */
    private volatile java.util.function.Supplier<Options> liveOptions = () -> Options.DEFAULT;

    Fighter(Movement movement, Navigator navigator, EntityTracker entities, InteractionControl hands, World world,
            Combat combat, PlayerState state, Worker worker, DebugRender debug, Executor executor, Logger log) {
        this.movement = movement;
        this.navigator = navigator;
        this.entities = entities;
        this.hands = hands;
        this.world = world;
        this.combat = combat;
        this.state = state;
        this.worker = worker;
        this.debug = debug;
        this.executor = executor;
        this.log = log;
    }

    Mode mode() {
        return mode;
    }

    String status() {
        return status;
    }

    /** The options the sentry takes, as they are at each beat. */
    void liveOptions(java.util.function.Supplier<Options> options) {
        this.liveOptions = options == null ? () -> Options.DEFAULT : options;
    }

    /**
     * "Guard the miner and builder": on, with no mode started, a sentry fights for the jobs that have
     * the body - never taking it from the player playing by hand; off, it is stopped.
     */
    synchronized void guardJobs(boolean on) {
        guardJobs = on;
        if (on && mode == Mode.OFF) {
            sentry();
        } else if (!on && mode == Mode.SENTRY) {
            job.incrementAndGet();
        }
    }

    boolean guardsJobs() {
        return guardJobs;
    }

    /** The module is going: whatever runs stops, and the sentry is not started again. */
    synchronized void shutdown() {
        guardJobs = false;
        job.incrementAndGet();
    }

    /** Starts the sentry. Called with the monitor held, when no mode runs. */
    private void sentry() {
        start(new Job(job.incrementAndGet(), Mode.SENTRY, null, null, -1, null, 0));
    }

    void showTarget(boolean on) {
        showTarget = on;
        if (!on) {
            unmark();
        }
    }

    /** How it goes now: the mode, the target, the counts so far. */
    Map<String, Object> describe() {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("mode", mode.name());
        out.put("status", status);
        out.put("guardJobs", guardJobs);
        Job j = current;
        if (j != null) {
            out.put("target", j.target);
            if (j.target >= 0) {
                out.put("targetLabel", entities.labelOf(j.target));
            }
            out.put("fighting", j.fighting);
            out.putAll(counts(j.result("RUNNING")));
        }
        health().ifPresent(h -> out.put("health", Math.round(h * 1000) / 10.0));
        return out;
    }

    static Map<String, Object> counts(Result r) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("status", r.status());
        out.put("kills", r.kills());
        out.put("swings", r.swings());
        out.put("cut", r.cut());
        out.put("guards", r.guards());
        out.put("blocked", r.blocked());
        out.put("heals", r.heals());
        out.put("retreats", r.retreats());
        out.put("repairs", r.repairs());
        out.put("picked", r.picked());
        out.put("hitsTaken", r.hitsTaken());
        out.put("damageTaken", r.damageTaken());
        out.put("seconds", r.millis() / 1000.0);
        return out;
    }

    // ------------------------------------------------------------------
    // Jobs
    // ------------------------------------------------------------------

    /** Guards until stopped: fights whoever is an enemy round the body, taking the wheel from any job meanwhile. */
    synchronized CompletableFuture<Result> guard(Options options) {
        return start(new Job(job.incrementAndGet(), Mode.GUARD, options, null, -1, null, 0));
    }

    /**
     * Farms {@code count} mobs whose model id holds {@code kind} (under 1: as long as there are
     * any) within {@code radius} of where the body is now.
     */
    synchronized CompletableFuture<Result> farm(String kind, int count, double radius, Options options) {
        Vec3 here = movement.position();
        return start(new Job(job.incrementAndGet(), Mode.FARM, options, kind, count < 1 ? -1 : count, here, radius));
    }

    /** Escorts the player named so, keeping within {@code distance} of them, until stopped. */
    synchronized CompletableFuture<Result> escort(String player, double distance, Options options) {
        Job j = new Job(job.incrementAndGet(), Mode.ESCORT, options, null, -1, null, distance);
        j.escorted = player;
        return start(j);
    }

    /**
     * Stops the job under way; completes once it has given everything back. The sentry starts again
     * after it while "Guard the miner and builder" is on.
     */
    synchronized CompletableFuture<Result> stop() {
        job.incrementAndGet();
        return running.handle((r, e) -> r);
    }

    private synchronized CompletableFuture<Result> start(Job j) {
        CompletableFuture<Result> before = running;
        CompletableFuture<Result> done = new CompletableFuture<>();
        running = done;
        mode = j.mode;
        executor.execute(() -> {
            Result r = null;
            try {
                try {
                    before.get(30, TimeUnit.SECONDS);   // the one before gives its holds back first
                } catch (Exception ignored) {
                    // ended badly or slowly: this one goes on
                }
                current = j;
                r = run(j);
                status = text(j, r);
                log.info("auto-fighter: {}", status);
                done.complete(r);
            } catch (Throwable t) {
                log.warn("auto-fighter: the job failed", t);
                status = "Failed: " + t;
                done.completeExceptionally(t);
            } finally {
                over(done, j, r);
            }
        });
        return done;
    }

    /**
     * A job is over: with nothing started after it, the mode is off - and the sentry takes over while
     * "Guard the miner and builder" is on (a sentry that failed is left off, not started in a loop).
     */
    private synchronized void over(CompletableFuture<Result> done, Job j, Result r) {
        if (running != done) {
            return;
        }
        mode = Mode.OFF;
        boolean failed = r == null || "FAILED".equals(r.status());
        if (guardJobs && !(j.mode == Mode.SENTRY && failed)) {
            sentry();
        }
    }

    /** One job's state; only its own thread touches the most of it. */
    private final class Job {
        final int id;
        final Mode mode;
        final Options options;
        final String kind;
        final int count;
        final Vec3 home;
        final double range;
        final long began = System.currentTimeMillis();
        String escorted;
        boolean escortSeen;
        /** The hold on the body: the job's own (Farm, Escort), or the fight's (Guard). */
        Movement.Wheel wheel;
        /** Why the wheel went, when the game took it. */
        volatile Movement.Release lost;
        boolean fighting;
        volatile int target = -1;
        /** The slot in hand before a Guard fight, taken again after it. */
        int slotBefore = -1;
        CompletableFuture<NavResult> chase;
        int chased = -1;
        /** When we last hit each entity - a kill is ours if it falls soon after. */
        final Map<Integer, Long> struck = new HashMap<>();
        /** The attack (chain) each entity was last guarded against, so one attack takes one guard. */
        final Map<Integer, Integer> guarded = new java.util.concurrent.ConcurrentHashMap<>();
        /** The guard raised the moment an attack showed, if one is under way. */
        volatile CompletableFuture<?> guard;
        /** Where each entity was last seen, to find its drops when it falls. */
        final Map<Integer, Vec3> lastAt = new HashMap<>();
        /** The items lying about before a kill: not its drops. */
        final Set<Integer> lying = new HashSet<>();
        long retreatAgain;
        long playerTookUntil;
        long idleSince = System.currentTimeMillis();
        int kills;
        int swings;
        /** Our swings the guard cut short (the server cancelled them), the game's way. */
        int cut;
        /** When the guard was last raised the moment an attack showed. */
        volatile long guardRaisedAt;
        final AtomicInteger guards = new AtomicInteger();
        /** Blows that came while the guard was up and did no harm. */
        final AtomicInteger blocked = new AtomicInteger();
        /** The guards up, by the attacker: when each went up, until its blow came or the time ran out. */
        final Map<Integer, Long> guardUp = new java.util.concurrent.ConcurrentHashMap<>();
        /** What the player carried when the job began, by item - what it carries more of was picked up. */
        Map<String, Integer> carriedAtStart = Map.of();
        int heals;
        int retreats;
        int repairs;
        int picked;
        int hitsTaken;
        double damageTaken;
        final Combat.Listener hurt = new Combat.Listener() {
            @Override
            public void hit(Combat.Hit h) {
                Long up = guardUp.remove(h.attacker());
                if (up != null && h.at() - up <= GUARD_COVERS_MS && h.amount() <= 0) {
                    blocked.incrementAndGet();
                    return;
                }
                hitsTaken++;
                damageTaken += h.amount();
            }

            @Override
            public void swing(Combat.Swing w) {
                guardAgainst(Job.this, w);
            }
        };

        Job(int id, Mode mode, Options options, String kind, int count, Vec3 home, double range) {
            this.id = id;
            this.mode = mode;
            this.options = options == null ? Options.DEFAULT : options;
            this.kind = kind;
            this.count = count;
            this.home = home;
            this.range = range;
        }

        boolean live() {
            return job.get() == id && (lost == null || mode == Mode.GUARD || mode == Mode.SENTRY);
        }

        /** How it fights: the options it was started with; the sentry's, as the panel has them now. */
        Options options() {
            return mode == Mode.SENTRY ? liveOptions.get() : options;
        }

        Result result(String how) {
            return new Result(how, kills, swings, cut, guards.get(), blocked.get(), heals, retreats, repairs,
                    picked + gained(this), hitsTaken, damageTaken, System.currentTimeMillis() - began);
        }
    }

    private Result run(Job j) {
        if (j.mode != Mode.SENTRY && !movement.available() && !navigator.available()) {
            // (The sentry waits for a body: it starts with the module, before there is one.)
            return j.result("NOT_AVAILABLE");
        }
        if (combat != null) {
            combat.subscribe(j.hurt);
        }
        String end = "STOPPED";
        try {
            if (j.mode == Mode.FARM || j.mode == Mode.ESCORT) {
                // Farm and Escort hold the body for the whole job.
                j.wheel = movement.take().orElse(null);
                if (j.wheel == null) {
                    return j.result("NOT_AVAILABLE");
                }
                Job jj = j;
                j.wheel.released().thenAccept(why -> {
                    if (why != Movement.Release.GIVEN_BACK) {
                        jj.lost = why;
                    }
                });
            }
            j.carriedAtStart = carried();
            while (j.live()) {
                long t0 = System.currentTimeMillis();
                settleGuards(j);
                if (j.target >= 0 && !alive(j.target)) {
                    gone(j, j.target);
                }
                String over;
                try {
                    over = switch (j.mode) {
                        case GUARD, SENTRY -> guardBeat(j);
                        case FARM -> farmBeat(j);
                        case ESCORT -> escortBeat(j);
                        case OFF -> "STOPPED";
                    };
                } catch (java.util.concurrent.TimeoutException slow) {
                    // A swing, a guard or a switch the server took long over: the next beat sees how it stands.
                    log.info("auto-fighter: a forge took too long - on with the next beat");
                    over = null;
                }
                if (over != null) {
                    end = over;
                    break;
                }
                long beat = j.mode == Mode.SENTRY && !j.fighting && !jobHolds() ? SENTRY_IDLE_MS : BEAT_MS;
                long left = beat - (System.currentTimeMillis() - t0);
                if (left > 0) {
                    Thread.sleep(left);
                }
            }
            if (j.lost == Movement.Release.DEATH) {
                end = "DIED";
            } else if (j.lost != null && (j.mode == Mode.FARM || j.mode == Mode.ESCORT)) {
                end = "INTERRUPTED";
            }
            return j.result(end);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return j.result("STOPPED");
        } catch (Exception e) {
            log.warn("auto-fighter: the fight broke off", e);
            return j.result("FAILED");
        } finally {
            if (combat != null) {
                combat.unsubscribe(j.hurt);
            }
            stopChase(j);
            unmark();
            if (j.fighting) {
                endFight(j);
            }
            if (j.wheel != null) {
                j.wheel.release();
            }
            current = null;
        }
    }

    // ------------------------------------------------------------------
    // The modes
    // ------------------------------------------------------------------

    /**
     * A Guard beat: a fight when an enemy is about, else nothing - or a heal or a repair, quickly, if
     * one is due. The sentry's too, while a job has the body: with none, it does nothing at all, and a
     * fight of its own is over the moment the job is (the body is the player's then).
     */
    private String guardBeat(Job j) throws Exception {
        boolean sentry = j.mode == Mode.SENTRY;
        String who = sentry ? "Guarding the job" : "Guarding";
        if (sentry && !jobHolds()) {
            if (j.fighting) {
                endFight(j);
                log.info("auto-fighter: the job is over - the fight with it");
            }
            j.lost = null;
            status = "Guarding the miner and builder: no job has the body.";
            return null;
        }
        Vec3 me = movement.position();
        if (me == null) {
            status = who + ": the player is nowhere yet.";
            return null;
        }
        List<Integer> enemies = enemies(j, me, j.options().radius(), me);
        if (enemies.isEmpty()) {
            if (j.fighting) {
                endFight(j);
            }
            status = who + ": no enemy about.";
            upkeep(j, me);
            return null;
        }
        if (!j.fighting) {
            if (System.currentTimeMillis() < j.playerTookUntil) {
                status = who + ": the player has the body.";
                return null;
            }
            if (!beginFight(j)) {
                status = who + ": an enemy, but the body cannot be taken.";
                return null;
            }
        }
        if (j.lost != null) {
            // The game took the body in the fight: a key (left to the player a while), a death. The
            // sentry's job went with it; the sentry watches on.
            if (j.lost == Movement.Release.DEATH && !sentry) {
                return "DIED";
            }
            if (j.lost == Movement.Release.PLAYER && !sentry) {
                j.playerTookUntil = System.currentTimeMillis() + PLAYER_TOOK_MS;
            }
            j.lost = null;
            j.fighting = false;
            j.wheel = null;
            stopChase(j);
            unmark();
            return null;
        }
        fight(j, choose(j, enemies, me), me);
        return null;
    }

    /** A Farm beat: those that hit us first, then the nearest of the kind round home. */
    private String farmBeat(Job j) throws Exception {
        if (j.count > 0 && j.kills >= j.count) {
            return "DONE";
        }
        Vec3 me = movement.position();
        if (me == null) {
            return "NOT_AVAILABLE";
        }
        List<Integer> enemies = enemies(j, me, j.options().radius(), me);
        List<Integer> prey = new ArrayList<>();
        for (int id : sorted(me, entities.trackedEntities())) {
            Vec3 at = entities.positionOf(id).orElse(null);
            if (at != null && entities.itemOf(id).isEmpty() && kindOf(id, j.kind) && alive(id)
                    && !entities.isPlayer(id) && flat(at, j.home) <= j.range) {
                prey.add(id);
            }
        }
        List<Integer> targets = new ArrayList<>();
        for (int e : enemies) {
            if (combat != null && combat.hitBy(e, Duration.ofSeconds(8))) {
                targets.add(e);
            }
        }
        targets.addAll(prey);
        if (targets.isEmpty()) {
            stopChase(j);
            unmark();
            if (System.currentTimeMillis() - j.idleSince > FARM_IDLE_MS) {
                return j.kills > 0 ? "DONE" : "NOTHING";
            }
            status = String.format(Locale.ROOT, "Farming %s: %d killed, looking for more.", j.kind, j.kills);
            upkeep(j, me);
            return null;
        }
        j.idleSince = System.currentTimeMillis();
        fight(j, choose(j, targets, me), me);
        return null;
    }

    /** An Escort beat: what threatens the player is fought; else the body keeps near them. */
    private String escortBeat(Job j) throws Exception {
        Vec3 me = movement.position();
        int who = escorted(j.escorted);
        Vec3 them = who < 0 ? null : entities.positionOf(who).orElse(null);
        if (me == null || them == null) {
            stopChase(j);
            if (j.escortSeen) {
                log.info("auto-fighter: {} is out of sight", j.escorted);
                j.escortSeen = false;
            }
            status = "Escorting " + j.escorted + ": not in sight.";
            return null;
        }
        if (!j.escortSeen) {
            log.info("auto-fighter: escorting {} ({})", j.escorted, who);
            j.escortSeen = true;
        }
        List<Integer> threats = enemies(j, them, j.options().radius(), me);
        threats.remove((Integer) who);
        if (!threats.isEmpty()) {
            fight(j, choose(j, threats, them), me);
            return null;
        }
        unmark();
        j.target = -1;
        double d = flat(me, them);
        double keep = Math.max(1.5, j.range);
        if (d > keep + 1.5) {
            if (j.chase == null || j.chase.isDone() || j.chased != who) {
                stopChase(j);
                int w = who;
                j.chased = who;
                j.chase = navigator.chase(() -> entities.positionOf(w), keep);
            }
            status = String.format(Locale.ROOT, "Escorting %s: following, %.1f away.", j.escorted, d);
        } else {
            if (j.chased == who && j.chase != null && !j.chase.isDone() && d <= keep) {
                stopChase(j);
            }
            status = "Escorting " + j.escorted + ": by them.";
            upkeep(j, me);
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Who is an enemy
    // ------------------------------------------------------------------

    /**
     * The enemies about {@code center} within {@code radius}, nearest {@code me} first: whoever hit us
     * lately (within the leash), whoever began an attack near us or near {@code center}, and the
     * hostile kinds on sight. A player only when the options let players be enemies, and never by kind.
     */
    private List<Integer> enemies(Job j, Vec3 center, double radius, Vec3 me) {
        OptionalInt self = entities.localEntityId();
        List<Integer> out = new ArrayList<>();
        for (int id : entities.trackedEntities()) {
            if (self.isPresent() && self.getAsInt() == id || entities.itemOf(id).isPresent() || !alive(id)) {
                continue;
            }
            if (j.mode == Mode.ESCORT && id == escorted(j.escorted)) {
                continue;
            }
            Vec3 at = entities.positionOf(id).orElse(null);
            if (at == null) {
                continue;
            }
            double fromCenter = dist(at, center);
            boolean player = entities.isPlayer(id);
            boolean hitUs = combat != null && combat.hitBy(id, HIT_MEMORY);
            boolean swung = combat != null && combat.swingOf(id, SWING_MEMORY).isPresent()
                    && (dist(at, me) <= SWING_NEAR || fromCenter <= SWING_NEAR);
            boolean enemy;
            if (player) {
                enemy = j.options().players() && (hitUs || swung);
            } else {
                enemy = hitUs || swung || (fromCenter <= radius && hostileKind(id, j.options().hostile()));
            }
            if (enemy && fromCenter <= radius + LEASH) {
                out.add(id);
            }
        }
        return sorted(me, out);
    }

    /** Whether an entity's model id holds one of the parts on the list, case aside. */
    private boolean hostileKind(int id, List<String> kinds) {
        if (kinds == null || kinds.isEmpty()) {
            return false;
        }
        for (String k : kinds) {
            if (kindOf(id, k)) {
                return true;
            }
        }
        return false;
    }

    private boolean kindOf(int id, String kind) {
        if (kind == null || kind.isBlank()) {
            return false;
        }
        String model = entities.entityModelAssetId(id).orElse(entities.labelOf(id));
        return model != null && model.toLowerCase(Locale.ROOT).contains(kind.trim().toLowerCase(Locale.ROOT));
    }

    /** Alive: in sight, and its health - where it is known - above its min. */
    private boolean alive(int id) {
        if (entities.positionOf(id).isEmpty()) {
            return false;
        }
        Optional<EntityTracker.Stat> h = entities.healthOf(id);
        return h.isEmpty() || h.get().value() > h.get().min();
    }

    /**
     * The target: the one fought now while it is still an enemy; else one that hit us in the last
     * few seconds; else the one beginning an attack; else the first (the nearest).
     */
    private int choose(Job j, List<Integer> enemies, Vec3 near) {
        if (j.target >= 0 && enemies.contains(j.target)) {
            // Kept - unless it is out of reach while another is hitting us now.
            boolean hittingNow = false;
            for (int e : enemies) {
                if (e != j.target && combat != null && combat.hitBy(e, Duration.ofSeconds(2))
                        && !combat.hitBy(j.target, Duration.ofSeconds(4))) {
                    hittingNow = true;
                    if (outOfReach(j.target)) {
                        return e;
                    }
                }
            }
            if (!hittingNow || !outOfReach(j.target)) {
                return j.target;
            }
        }
        if (combat != null) {
            for (int e : enemies) {
                if (combat.hitBy(e, Duration.ofSeconds(4))) {
                    return e;
                }
            }
            for (int e : enemies) {
                if (combat.swingOf(e, Duration.ofSeconds(1)).isPresent()) {
                    return e;
                }
            }
        }
        return enemies.get(0);
    }

    private boolean outOfReach(int target) {
        return reachLeft(target) < 0;
    }

    /** The player named so in sight - by the name over their head - or {@code -1}. */
    private int escorted(String name) {
        if (name == null || name.isBlank()) {
            return -1;
        }
        OptionalInt self = entities.localEntityId();
        for (int id : entities.trackedEntities()) {
            if (self.isPresent() && self.getAsInt() == id) {
                continue;
            }
            if (entities.isPlayer(id) && name.equalsIgnoreCase(entities.nameplateOf(id).orElse(""))) {
                return id;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------
    // A fight
    // ------------------------------------------------------------------

    /** Guard: takes the body first, so a job under way waits while it fights. */
    private boolean beginFight(Job j) {
        Movement.Wheel w = movement.takeFirst().orElse(null);
        if (w == null) {
            return false;
        }
        j.wheel = w;
        j.fighting = true;
        j.lost = null;
        Player p = world.player().orElse(null);
        j.slotBefore = p == null ? -1 : p.activeHotbarSlot();
        w.released().thenAccept(why -> {
            if (why != Movement.Release.GIVEN_BACK) {
                j.lost = why;
            }
        });
        log.info("auto-fighter: an enemy - the body taken for the fight{}",
                j.mode == Mode.SENTRY ? " (a job has it: it waits)" : "");
        return true;
    }

    /** Guard and the sentry: they hold nothing between fights, and take the body first for one. */
    private static boolean guarding(Job j) {
        return j.mode == Mode.GUARD || j.mode == Mode.SENTRY;
    }

    /**
     * Whether another's job has the body - a hold of core's Movement other than ours: the auto-miner's,
     * the builder's, a trip. An urgent hold of ours, and what we start under it, are not counted
     * ({@link Movement#holds}); a core too old to tell has none.
     */
    private boolean jobHolds() {
        return movement.holds() > 0;
    }

    /** Guard: the fight is over - the slot held before taken again, the body given back to whoever had it. */
    private void endFight(Job j) {
        stopChase(j);
        unmark();
        j.target = -1;
        j.fighting = false;
        try {
            Player p = world.player().orElse(null);
            if (p != null && j.slotBefore >= 0 && p.activeHotbarSlot() != j.slotBefore) {
                hands.switchHotbarSlot(j.slotBefore).get(3, TimeUnit.SECONDS);
            }
        } catch (Exception ignored) {
            // the slot stays as it is
        }
        Movement.Wheel w = j.wheel;
        j.wheel = null;
        if (w != null) {
            w.release();
            log.info("auto-fighter: the fight is over - the body given back");
        }
    }

    /** One beat of a fight with {@code target}. */
    private void fight(Job j, int target, Vec3 me) throws Exception {
        if (j.target != target) {
            j.target = target;
            log.info("auto-fighter: fighting {} ({})", entities.labelOf(target), target);
        }
        Vec3 at = entities.positionOf(target).orElse(null);
        if (at == null) {
            gone(j, target);
            return;
        }
        j.lastAt.put(target, at);
        mark(target, at);
        double hp = health().orElse(1.0);
        int healSlot = healSlot();
        if (hp * 100 <= j.options().retreatAt() && healSlot >= 0 && System.currentTimeMillis() > j.retreatAgain) {
            retreat(j, target, me);
            return;
        }
        if (hp * 100 <= j.options().healAt() && healSlot >= 0 && !coming(target)) {
            heal(j, healSlot);
            return;
        }
        arm(j);
        String held = world.player().map(Player::heldItem).orElse(null);
        double left = reachLeft(target);
        if (left < 0) {
            status = String.format(Locale.ROOT, "Fighting %s: going after it, %.1f to go.", entities.labelOf(target), -left);
            double reach = reachOf(held);
            if (j.chase == null || j.chase.isDone() || j.chased != target) {
                stopChase(j);
                j.chased = target;
                j.chase = navigator.chase(() -> entities.positionOf(target), Math.max(0.8, reach - 0.9));
            }
            return;
        }
        stopChase(j);
        Vec3 aim = aimAt(target, at);
        try {
            movement.face(aim, Math.toRadians(6)).get(250, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException turning) {
            // swung all the same: the server takes the hit from the client
        }
        if (j.options().block() && combat != null && guardsWith(held) && stamina() > GUARD_STAMINA) {
            Optional<Combat.Swing> coming = combat.swingOf(target, WINDING_UP);
            if (coming.isPresent() && !Integer.valueOf(coming.get().chain()).equals(j.guarded.get(target))
                    && eyesTo(target) <= ENEMY_REACH) {
                j.guarded.put(target, coming.get().chain());
                status = "Fighting " + entities.labelOf(target) + ": guarding.";
                j.guards.incrementAndGet();
                j.guardUp.put(target, System.currentTimeMillis());
                hands.useItem(GUARD_HOLD, !j.options().overpower()).get(3, TimeUnit.SECONDS);
                return;
            }
            if (blowDue(j, target)) {
                // Its next blow is near: no swing of ours in the way of the guard.
                status = "Fighting " + entities.labelOf(target) + ": waiting for its blow.";
                return;
            }
        }
        CompletableFuture<?> guarding = j.guard;
        if (guarding != null && !guarding.isDone()) {
            status = "Fighting " + entities.labelOf(target) + ": guarding.";
            try {
                guarding.get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // the guard is down, however it went
            }
            return;
        }
        status = "Fighting " + entities.labelOf(target) + ".";
        j.swings++;
        long swungAt = System.currentTimeMillis();
        j.struck.put(target, swungAt);
        snapshotItems(j);
        InteractionOutcome swing = hands.hitEntity(target).get(3, TimeUnit.SECONDS);
        if (swing != null && swing.status() == InteractionOutcome.Status.CANCELLED && j.guardRaisedAt >= swungAt) {
            // The guard raised in it cut it short, as the game does: its blow did not land.
            j.cut++;
        }
        if (!alive(target)) {
            gone(j, target);
        }
    }

    /**
     * An attack shows begun - the moment the server says so, on the network's thread: when it is the
     * target's, or an enemy's that reaches us, and the held item guards, the guard is raised at once.
     * A swing of ours under way it cuts short where the swing's rules let it (a sword's, until its
     * blow), as the game does; with "Overpower", or past that point, it goes out after the swing.
     * A beat would only see the attack after that swing too, and later.
     */
    private void guardAgainst(Job j, Combat.Swing w) {
        if (!j.options().block() || j.target < 0 || job.get() != j.id) {
            return;
        }
        if (w.entity() != j.target && eyesTo(w.entity()) > ENEMY_REACH) {
            return;
        }
        if (eyesTo(w.entity()) > ENEMY_REACH || Integer.valueOf(w.chain()).equals(j.guarded.get(w.entity()))) {
            return;
        }
        String held = world.player().map(Player::heldItem).orElse(null);
        if (!guardsWith(held) || stamina() <= GUARD_STAMINA) {
            return;
        }
        CompletableFuture<?> before = j.guard;
        if (before != null && !before.isDone()) {
            return;
        }
        j.guarded.put(w.entity(), w.chain());
        j.guards.incrementAndGet();
        j.guardUp.put(w.entity(), System.currentTimeMillis());
        Vec3 at = entities.positionOf(w.entity()).orElse(null);
        if (at != null) {
            movement.face(aimAt(w.entity(), at), Math.toRadians(10));
        }
        j.guardRaisedAt = System.currentTimeMillis();
        j.guard = hands.useItem(GUARD_HOLD, !j.options().overpower());
    }

    /**
     * The guards whose time ran out without a blow getting through: blocked - a blow taken on the
     * guard does no harm, and the server tells of no harm done.
     */
    private void settleGuards(Job j) {
        long now = System.currentTimeMillis();
        for (Map.Entry<Integer, Long> e : j.guardUp.entrySet()) {
            if (now - e.getValue() > GUARD_COVERS_MS && j.guardUp.remove(e.getKey(), e.getValue())) {
                if (combat != null && combat.swingOf(e.getKey(), Duration.ofMillis(now - e.getValue() + 200)).isPresent()) {
                    j.blocked.incrementAndGet();
                }
            }
        }
    }

    /** The target is down or gone: a kill when we hit it lately; its drops looked for in Farm. */
    private void gone(Job j, int target) throws Exception {
        Long hit = j.struck.remove(target);
        Vec3 where = j.lastAt.remove(target);
        boolean ours = hit != null && System.currentTimeMillis() - hit < KILL_CREDIT_MS;
        if (ours) {
            j.kills++;
            log.info("auto-fighter: {} killed ({} so far)", target, j.kills);
        }
        if (j.target == target) {
            j.target = -1;
            stopChase(j);
            unmark();
        }
        if (ours && j.mode == Mode.FARM && j.options().pickUp() && where != null && worker != null) {
            pickUpAt(j, where);
        }
    }

    /**
     * Whether the target's next blow is near, by its rhythm: its last attack began longer ago than
     * the window it leaves (the shortest gap between its attacks, less a lead; a default before it
     * has attacked twice), and not so long ago that it has stopped attacking.
     */
    private boolean blowDue(Job j, int target) {
        if (combat == null) {
            return false;
        }
        List<Combat.Swing> its = new ArrayList<>();
        for (Combat.Swing w : combat.swings(Duration.ofSeconds(20))) {
            if (w.entity() == target) {
                its.add(w);
            }
        }
        if (its.isEmpty()) {
            return false;
        }
        long window = SWING_WINDOW_MS;
        long shortest = Long.MAX_VALUE;
        for (int i = 1; i < its.size(); i++) {
            shortest = Math.min(shortest, its.get(i).at() - its.get(i - 1).at());
        }
        if (shortest != Long.MAX_VALUE && shortest > BLOW_LEAD_MS + 200) {
            window = shortest - BLOW_LEAD_MS;
        }
        long since = System.currentTimeMillis() - its.get(its.size() - 1).at();
        return since > window && since < window + WAIT_FOR_BLOW_MS;
    }

    /** Whether the target has an attack coming at us now. */
    private boolean coming(int target) {
        return combat != null && combat.swingOf(target, WINDING_UP).isPresent() && eyesTo(target) <= ENEMY_REACH;
    }

    /**
     * How much of the weapon's reach is left beyond the target - eyes to the nearest of its box, less
     * the margin; negative when it is out of reach.
     */
    private double reachLeft(int target) {
        String held = world.player().map(Player::heldItem).orElse(null);
        return reachOf(held) - REACH_MARGIN - eyesTo(target);
    }

    /** The eyes to the nearest point of the target's box (its feet where no box is known). */
    private double eyesTo(int target) {
        Vec3 eyes = eyes();
        Vec3 at = entities.positionOf(target).orElse(null);
        if (eyes == null || at == null) {
            return Double.MAX_VALUE;
        }
        Box b = entities.boundsOf(target).orElse(null);
        if (b == null) {
            return dist(eyes, new Vec3(at.x(), at.y() + 0.9, at.z()));
        }
        double x = clamp(eyes.x(), b.min().x(), b.max().x());
        double y = clamp(eyes.y(), b.min().y(), b.max().y());
        double z = clamp(eyes.z(), b.min().z(), b.max().z());
        return dist(eyes, new Vec3(x, y, z));
    }

    /** Where to look at the target: the middle of its box, at most the eyes' height above its feet. */
    private Vec3 aimAt(int target, Vec3 at) {
        Box b = entities.boundsOf(target).orElse(null);
        double y = b == null ? at.y() + 0.9 : (b.min().y() + b.max().y()) / 2;
        return new Vec3(at.x(), y, at.z());
    }

    /** The best weapon in hand; one worn out repaired with a kit first, if asked. */
    private void arm(Job j) throws Exception {
        Player p = world.player().orElse(null);
        if (p == null) {
            return;
        }
        int slot = p.bestWeaponSlot();
        if (slot < 0) {
            return;     // no weapon: what is held, or the hand
        }
        String id = p.hotbarItem(slot);
        if (j.options().repair() && worn(slot) && !j.fighting) {
            if (Boolean.TRUE.equals(hands.repair(id).get(15, TimeUnit.SECONDS))) {
                j.repairs++;
            }
        }
        if (worn(slot)) {
            int spare = p.bestWeaponSlot(other -> !other.equals(id));
            int s2 = spare >= 0 && !worn(spare) ? spare : slot;
            slot = s2;
        }
        if (slot != p.activeHotbarSlot()) {
            hands.switchHotbarSlot(slot).get(3, TimeUnit.SECONDS);
        }
    }

    /** Whether the stack in a hotbar slot is worn past {@link #WORN} of its durability. */
    private boolean worn(int slot) {
        Containers.Item item = hotbarStack(slot);
        return item != null && item.maxDurability() > 0 && item.durability() / item.maxDurability() < WORN;
    }

    private Containers.Item hotbarStack(int slot) {
        if (state == null) {
            return null;
        }
        PlayerState.Section s = state.inventories().get("Hotbar");
        return s == null ? null : s.items().get(slot);
    }

    /** Between fights: a heal when health is low, a repair when the weapon is worn - each quickly, the body taken first. */
    private void upkeep(Job j, Vec3 me) throws Exception {
        double hp = health().orElse(1.0);
        int healSlot = healSlot();
        boolean heal = hp * 100 <= j.options().healAt() && healSlot >= 0;
        Player p = world.player().orElse(null);
        int weapon = p == null ? -1 : p.bestWeaponSlot();
        boolean repair = j.options().repair() && weapon >= 0 && worn(weapon) && kitInHotbar();
        if (!heal && !repair) {
            return;
        }
        // Guard holds nothing between fights: a job under way waits for the heal or the repair too.
        Movement.Wheel w = guarding(j) ? movement.takeFirst().orElse(null) : null;
        int back = p == null ? -1 : p.activeHotbarSlot();
        try {
            if (heal) {
                heal(j, healSlot);
            }
            if (repair && Boolean.TRUE.equals(hands.repair(p.hotbarItem(weapon)).get(15, TimeUnit.SECONDS))) {
                j.repairs++;
            }
            if (guarding(j) && back >= 0 && p.activeHotbarSlot() != back) {
                hands.switchHotbarSlot(back).get(3, TimeUnit.SECONDS);
            }
        } finally {
            if (w != null) {
                w.release();
            }
        }
    }

    private boolean kitInHotbar() {
        Player p = world.player().orElse(null);
        return p != null && p.hotbarSlotOf("Repair_Kit") >= 0;
    }

    // ------------------------------------------------------------------
    // Survival
    // ------------------------------------------------------------------

    /** The hotbar slot whose item heals the most at once (else over time), or {@code -1}. */
    private int healSlot() {
        Player p = world.player().orElse(null);
        if (p == null) {
            return -1;
        }
        int best = -1;
        double bestNow = -1;
        double bestTotal = -1;
        for (int slot = 0; slot < 9; slot++) {
            String id = p.hotbarItem(slot);
            if (id == null) {
                continue;
            }
            Optional<InteractionControl.Heal> h = healOf(id);
            if (h.isEmpty()) {
                continue;
            }
            double now = h.get().now();
            double total = h.get().total();
            if (now > bestNow || (now == bestNow && total > bestTotal)) {
                best = slot;
                bestNow = now;
                bestTotal = total;
            }
        }
        return best;
    }

    /** Uses the healing item in {@code slot}, then takes the weapon again. */
    private void heal(Job j, int slot) throws Exception {
        Player p = world.player().orElse(null);
        if (p == null) {
            return;
        }
        String id = p.hotbarItem(slot);
        status = "Healing with " + id + ".";
        int back = p.activeHotbarSlot();
        if (slot != back) {
            hands.switchHotbarSlot(slot).get(3, TimeUnit.SECONDS);
        }
        hands.useItem().get(10, TimeUnit.SECONDS);
        j.heals++;
        log.info("auto-fighter: healed with {} - health {}", id, health().map(h -> Math.round(h * 100)).orElse(-1L));
        if (back >= 0 && back != slot && !guarding(j)) {
            hands.switchHotbarSlot(back).get(3, TimeUnit.SECONDS);
        }
    }

    /**
     * Backs off from the enemies - up to {@link #RETREAT} blocks straight away from them, or to the
     * sides - heals there, and is back to the fight on the next beat.
     */
    private void retreat(Job j, int target, Vec3 me) throws Exception {
        j.retreats++;
        j.retreatAgain = System.currentTimeMillis() + RETREAT_COOLDOWN_MS;
        stopChase(j);
        Vec3 from = entities.positionOf(target).orElse(me);
        double dx = me.x() - from.x();
        double dz = me.z() - from.z();
        double len = Math.hypot(dx, dz);
        if (len < 1e-3) {
            dx = 1;
            dz = 0;
            len = 1;
        }
        dx /= len;
        dz /= len;
        status = "Backing off from " + entities.labelOf(target) + " to heal.";
        log.info("auto-fighter: health low - backing off");
        boolean away = false;
        for (double far : new double[] {RETREAT, 5, 3}) {
            for (double turn : new double[] {0, Math.PI / 4, -Math.PI / 4, Math.PI / 2, -Math.PI / 2}) {
                double rx = dx * Math.cos(turn) - dz * Math.sin(turn);
                double rz = dx * Math.sin(turn) + dz * Math.cos(turn);
                int cx = (int) Math.floor(me.x() + rx * far);
                int cz = (int) Math.floor(me.z() + rz * far);
                int cy = (int) Math.floor(me.y() + 1e-3);
                Integer y = standY(cx, cy, cz);
                if (y == null) {
                    continue;
                }
                NavResult r;
                CompletableFuture<NavResult> trip = navigator.goTo(new Goal(cx, y, cz, 1));
                try {
                    r = trip.get(8, TimeUnit.SECONDS);
                } catch (java.util.concurrent.TimeoutException slow) {
                    trip.cancel(false);     // this trip only: a job waiting on the urgent hold keeps its own
                    continue;
                }
                if (r.arrived()) {
                    away = true;
                    break;
                }
            }
            if (away) {
                break;
            }
        }
        int slot = healSlot();
        if (slot >= 0) {
            heal(j, slot);
        }
    }

    /** The height to stand at in the column near {@code y}: the cell itself, one up or one down; {@code null} for none. */
    private Integer standY(int x, int y, int z) {
        for (int dy : new int[] {0, 1, -1, 2, -2}) {
            if (world.standAt(new meridian.core.api.BlockPos(x, y + dy, z)).isPresent()) {
                return y + dy;
            }
        }
        return null;
    }

    /** The player's own health, as a share of its max; empty when core has not been told. */
    private Optional<Double> health() {
        OptionalInt self = entities.localEntityId();
        if (self.isPresent()) {
            Optional<EntityTracker.Stat> h = entities.healthOf(self.getAsInt());
            if (h.isPresent() && h.get().max() > h.get().min()) {
                return Optional.of(h.get().fraction());
            }
        }
        if (state != null) {
            Double v = state.stats().get("Health");
            if (v != null) {
                return Optional.of(v / 100.0);
            }
        }
        return Optional.empty();
    }

    private double stamina() {
        Double v = state == null ? null : state.stats().get("Stamina");
        return v == null ? Double.MAX_VALUE : v;
    }

    // ------------------------------------------------------------------
    // Drops
    // ------------------------------------------------------------------

    /** How many of each item the player carries: the hotbar, the storage and the backpack. */
    private Map<String, Integer> carried() {
        Map<String, Integer> out = new HashMap<>();
        if (state == null) {
            return out;
        }
        for (Map.Entry<String, PlayerState.Section> e : state.inventories().entrySet()) {
            if (e.getKey().equals("Hotbar") || e.getKey().equals("Storage") || e.getKey().equals("Backpack")) {
                for (Containers.Item item : e.getValue().items().values()) {
                    if (item != null) {
                        out.merge(item.id(), item.quantity(), Integer::sum);
                    }
                }
            }
        }
        return out;
    }

    /**
     * Farm: how many items the player carries more of than when the job began - what it picked up,
     * walked to or not (the server picks a drop up within reach of the feet).
     */
    private int gained(Job j) {
        if (j.mode != Mode.FARM) {
            return 0;
        }
        int n = 0;
        for (Map.Entry<String, Integer> e : carried().entrySet()) {
            n += Math.max(0, e.getValue() - j.carriedAtStart.getOrDefault(e.getKey(), 0));
        }
        return n;
    }

    /** The items lying about now: none of them a kill's drop. */
    private void snapshotItems(Job j) {
        if (j.mode != Mode.FARM) {
            return;
        }
        for (int e : entities.trackedEntities()) {
            if (entities.itemOf(e).isPresent()) {
                j.lying.add(e);
            }
        }
    }

    /**
     * What a kill dropped where it fell: the items new since it was hit, near where it was. A mob
     * drops its loot once its dying is over, a second or two after its health ran out: waited for
     * up to {@link #DROPS_WAIT_MS}, and a moment more once the first shows, for the rest.
     */
    private void pickUpAt(Job j, Vec3 where) throws Exception {
        List<Integer> drops = new ArrayList<>();
        long until = System.currentTimeMillis() + DROPS_WAIT_MS;
        while (System.currentTimeMillis() < until && j.live()) {
            boolean had = !drops.isEmpty();
            drops.clear();
            for (int e : entities.trackedEntities()) {
                if (j.lying.contains(e) || entities.itemOf(e).isEmpty()) {
                    continue;
                }
                Vec3 first = entities.firstSeenAt(e).orElse(entities.positionOf(e).orElse(null));
                if (first != null && dist(first, where) <= DROPS_NEAR) {
                    drops.add(e);
                }
            }
            if (had && !drops.isEmpty()) {
                break;      // the rest had their moment
            }
            Thread.sleep(drops.isEmpty() ? 100 : 300);
        }
        if (drops.isEmpty()) {
            return;
        }
        status = "Picking up " + drops.size() + " drop(s).";
        worker.pickUp(drops).get(2, TimeUnit.MINUTES);
        j.lying.addAll(drops);
    }

    // ------------------------------------------------------------------
    // Bits
    // ------------------------------------------------------------------

    private void stopChase(Job j) {
        CompletableFuture<NavResult> c = j.chase;
        j.chase = null;
        j.chased = -1;
        if (c != null && !c.isDone()) {
            c.cancel(false);    // this trip only: a job waiting on the urgent hold keeps its own
        }
    }

    private void mark(int target, Vec3 at) {
        if (debug == null || !showTarget) {
            return;
        }
        Box b = entities.boundsOf(target).orElse(null);
        if (b != null) {
            debug.worldBox(TARGET_BOX, (b.min().x() + b.max().x()) / 2, (b.min().y() + b.max().y()) / 2,
                    (b.min().z() + b.max().z()) / 2, b.max().x() - b.min().x() + 0.1, b.max().y() - b.min().y() + 0.1,
                    b.max().z() - b.min().z() + 0.1, 1f, 0.15f, 0.1f, 0.35f);
        } else {
            debug.worldBox(TARGET_BOX, at.x(), at.y() + 0.9, at.z(), 0.8, 1.8, 0.8, 1f, 0.15f, 0.1f, 0.35f);
        }
    }

    private void unmark() {
        if (debug != null) {
            debug.clearWorldBox(TARGET_BOX);
        }
    }

    private Vec3 eyes() {
        Player p = world.player().orElse(null);
        Vec3 e = p == null ? null : p.eyePosition();
        if (e != null) {
            return e;
        }
        Vec3 feet = movement.position();
        return feet == null ? null : new Vec3(feet.x(), feet.y() + movement.capabilities().eyeHeight(), feet.z());
    }

    private List<Integer> sorted(Vec3 from, java.util.Collection<Integer> ids) {
        List<Integer> out = new ArrayList<>(ids);
        Map<Integer, Double> d = new HashMap<>();
        for (int id : out) {
            d.put(id, entities.positionOf(id).map(p -> dist(p, from)).orElse(Double.MAX_VALUE));
        }
        out.sort((a, b) -> Double.compare(d.get(a), d.get(b)));
        return out;
    }

    private static double dist(Vec3 a, Vec3 b) {
        return Math.sqrt(a.distanceSqTo(b));
    }

    private static double flat(Vec3 a, Vec3 b) {
        return a == null || b == null ? Double.MAX_VALUE : Math.hypot(a.x() - b.x(), a.z() - b.z());
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(Math.min(lo, hi), Math.min(Math.max(lo, hi), v));
    }

    private static String text(Job j, Result r) {
        String what = switch (j.mode) {
            case GUARD -> "Guard";
            case SENTRY -> "Guarding the miner and builder";
            case FARM -> "Farm " + j.kind;
            case ESCORT -> "Escort " + j.escorted;
            case OFF -> "Off";
        };
        return String.format(Locale.ROOT, "%s over (%s): %d killed, %d swings (%d cut short), %d guards (%d blocked), "
                        + "%d heals, %d retreats, %d repairs, %d picked up, %d hits taken (%.0f damage).", what,
                r.status(), r.kills(), r.swings(), r.cut(), r.guards(), r.blocked(), r.heals(), r.retreats(),
                r.repairs(), r.picked(), r.hitsTaken(), r.damageTaken());
    }
}
