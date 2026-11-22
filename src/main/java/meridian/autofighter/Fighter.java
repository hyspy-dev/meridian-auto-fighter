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
 * <p>Beyond the configured distance an ordinary bow is used when core offers its ranged service
 * and ammunition is available. Core owns the draw, lead and flight. At close range: the hotbar's best weapon ({@link Player#bestWeaponSlot}), the body taken to
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
     * @param resumeAt  health, in percent, it rests out of the fight to after a retreat - the slow
     *                  heal of a food under way, a bite more only where that falls short
     * @param block     raise the guard when the target begins an attack
     * @param repair    repair a worn weapon with a kit from the hotbar
     * @param pickUp    Farm: pick up what the kills drop
     * @param overpower the guard waits for our swing under way, which lands: what the server lets
     *                  through. Off, the guard cuts the swing short where its rules let it, as the
     *                  game does ({@link InteractionControl#useItem(Duration, boolean)})
     */
    record Options(List<String> hostile, boolean players, double radius, int healAt, int retreatAt, int resumeAt,
                   boolean block, boolean repair, boolean pickUp, boolean overpower, boolean bow, double bowDistance) {

        static final List<String> HOSTILE = List.of("Zombie", "Skeleton", "Spider", "Goblin", "Trork", "Scarak",
                "Wraith", "Void", "Ghoul", "Outlander");
        static final Options DEFAULT = new Options(HOSTILE, false, 12, 50, 25, 80, true, true, true, false, true, 6);
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
    /**
     * A bite is taken in the thick of a fight only for this share of the max health within its first
     * {@link #FAST_SECONDS} - a potion's - or when no slow heal is under way: a food's slow heal
     * starts over with each bite, so a second one mid-fight is seconds of standing for its instant part.
     */
    static final double BIG_BITE = 0.15;
    static final double FAST_SECONDS = 6;
    /** An item that would add less than this share of the max health is not worth the bite. */
    static final double LEAST_GAIN = 0.01;

    /**
     * The last use of a healing item, and how things stood when it was made: the health, the effects
     * on the player. What a use did the server tells a moment after its chain ends - the effects it
     * put on, the health - and until it has, there is nothing new to decide on: a bite taken is not
     * followed by another (its healing is not in core's count yet), and an item the server refused
     * is not tried again while all stands as it did (it would be refused the same).
     *
     * <p>Seen once, the server's word stays seen ({@code told}): the health coming back to what it
     * was - a hit after a potion - is not the use untold again. Every beat looks ({@link #noteBite}).
     *
     * @param taken   the server took the item from the stack
     * @param health  the health then, a share of its max
     * @param effects the effects on the player then, each with the seconds it had left
     * @param told    the server has told what the use did (or, of one refused, that things changed)
     */
    private record Bite(String item, boolean taken, double health, Map<String, Double> effects, boolean told) {
    }

    private volatile Bite bite;

    /**
     * The enemies the body ran from, health low and nothing to heal with ({@link #flee}) - fallen
     * behind, or not to be outrun. None of them is run from again: one that has the body within its
     * senses once more will make it its target, and is fought at once, not waited on for its blow.
     */
    private final Set<Integer> fledFrom = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Running adds less than this to the gap in one leg: the enemy keeps up, and is not to be outrun. */
    static final double GAINED = 1.0;
    /**
     * One taken for an enemy stays one this far past where it became one - the radius, what it
     * senses: at 12.0 blocks an enemy and at 12.4 none is no way to hold a fight (the owner's run of
     * 22.11: one arrow, and the body went back to the dig).
     */
    static final double EDGE = 2.0;
    /** One left alone that has come this much nearer the body since is coming at it: left alone no more. */
    static final double COMING = 2.0;
    /** A kind that attacks from farther than this - a flame, a bomb, an arrow - is fought from afar. */
    static final double RANGED = 4.5;
    /** How far past what such a one reaches the body keeps, to shoot from: it comes on while the bow is drawn. */
    static final double STANDOFF = 4.0;
    /** One slower than this is stepped back from; a faster one closes whatever the body does. */
    static final double OUTRUN = 4.5;
    /** Nearer than this there is no drawing a bow. */
    static final double BOW_NEAR = 3.0;
    /** How far past a blast's reach the body gets, to be out of it. */
    static final double BLAST_MARGIN = 1.0;
    /** A bomb not told of anew for this long lies where it is: the server tells only what moves. */
    static final long BOMB_LYING_NS = 200_000_000L;
    /** How near the place out of a blast the body has to get. */
    static final double DODGE_GOAL = 0.5;
    /**
     * A blast taking less than this share of the body's whole health is stood: the owner's word, 22.11,
     * the bow first - a lobber close by throws its small bombs (five points, 0.6) a second apart, and a
     * body getting out of each never drew the bow once in three minutes.
     */
    static final double BLAST_WORTH = 0.10;
    /** ...unless it takes this share of what health is left. */
    static final double BLAST_WORTH_LEFT = 0.25;
    /**
     * What running along a flying bomb's way costs, in blocks, against running across it: a bomb goes
     * off on whom it hits, at once - a body backing off along its line was hit 0.9 s after the throw.
     */
    static final double ALONG_ITS_WAY = 3.0;
    /** A bomb moving faster than this over the ground is in the air: it comes down further along its way. */
    static final double BOMB_FLYING = 1.5;
    /** How far along a flying bomb's way its coming down is looked for. */
    static final double BOMB_AHEAD = 16.0;
    /** A blast point that has moved this far - the bomb bounced, rolled - is got out of anew. */
    static final double BLAST_MOVED = 1.5;
    /** A shot the bow had no flight for is tried again once the body or the enemy has moved this far. */
    static final double SHOT_AGAIN = 2.0;
    /** A player who takes the body back in a Guard fight is left to it for this long. */
    static final long PLAYER_TOOK_MS = 10_000;
    /**
     * An enemy the body cannot get at - no way to it, or a chase that drags on - is left alone this
     * long, unless it hits us: a mob behind a wall would otherwise take the body from a job, lead it
     * off, give it back, and take it again as soon as the job came back near it.
     */
    static final long PASS_MS = 20_000;
    /**
     * A chase is given up once the body has run this far and is no nearer an enemy that has itself
     * moved half as far: it keeps away, and would lead the body off for as long as it likes. One that
     * stands where it stood is gone to however long the way round is - that chase ends by getting
     * there or by there being no way (the owner's run of 22.11: a mage twelve blocks down in the pit,
     * shot twice from the rim and then "left alone" - an enemy is finished off). No clock in it.
     */
    static final double CHASE_RUN = 24.0;
    /** It hit us this lately: an enemy even if left alone, out of sight or out of reach. */
    static final Duration HIT_NOW = Duration.ofSeconds(3);
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

    private BowSupport bows = BowSupport.NONE;
    void bows(BowSupport support) { bows = support; }

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
    /** Offloaded jobs must finish before the module's class loader can be closed. */
    private final Set<CompletableFuture<Void>> jobs = new HashSet<>();
    private final Set<Thread> jobThreads = new HashSet<>();
    private boolean closed;
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
        if (closed) {
            return;
        }
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
    void shutdown() {
        CompletableFuture<?>[] ending;
        synchronized (this) {
            closed = true;
            guardJobs = false;
            job.incrementAndGet();
            ending = jobs.toArray(CompletableFuture<?>[]::new);
            jobThreads.forEach(Thread::interrupt);
        }
        bows.cancel();
        // Do not hold the monitor: each job's finally needs it to give the body back. Bounded: a
        // job stuck on something that ignores the interrupt must not hang the proxy's unload.
        try {
            CompletableFuture.allOf(ending).get(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.TimeoutException e) {
            log.warn("auto-fighter: a job did not end within 10 s of the module going - unloaded without it");
        } catch (java.util.concurrent.ExecutionException e) {
            // the jobs' futures only ever complete normally
        }
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
            out.put("resting", j.resting);
            out.put("retreating", j.retreating);
            out.put("fleeing", j.fleeFrom >= 0);
            out.put("dodging", j.dodging);
            out.put("dodges", j.dodges);
            out.put("flights", j.flights);
            out.putAll(counts(j.result("RUNNING")));
        }
        health().ifPresent(h -> out.put("health", Math.round(h * 1000) / 10.0));
        out.put("healingUnderWay", Math.round(hands.healing().overTime() * 10) / 10.0);
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
        bows.cancel();
        job.incrementAndGet();
        return running.handle((r, e) -> r);
    }

    private synchronized CompletableFuture<Result> start(Job j) {
        if (closed) {
            return CompletableFuture.failedFuture(new IllegalStateException("fighter is shut down"));
        }
        CompletableFuture<Result> before = running;
        CompletableFuture<Result> done = new CompletableFuture<>();
        CompletableFuture<Void> exited = new CompletableFuture<>();
        jobs.add(exited);
        running = done;
        mode = j.mode;
        executor.execute(() -> {
            synchronized (this) {
                jobThreads.add(Thread.currentThread());
                // The task may have been queued, but not started, when shutdown took its snapshot.
                if (closed) Thread.currentThread().interrupt();
            }
            Result r = null;
            try {
                try {
                    before.get();       // the one before gives its holds back first: told to stop, it ends its beat
                } catch (java.util.concurrent.ExecutionException | java.util.concurrent.CancellationException ignored) {
                    // it ended badly: this one goes on
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
                try {
                    over(done, j, r);
                } finally {
                    synchronized (this) {
                        jobThreads.remove(Thread.currentThread());
                        jobs.remove(exited);
                        exited.complete(null);
                    }
                }
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
        /**
         * The target the chase began after; how far off it was then and where it stood; how far the
         * body has run since, and where it was a beat ago. A new target starts them again.
         */
        int chaseFor = -1;
        double chaseGap;
        Vec3 chaseTargetFrom;
        double chaseRun;
        Vec3 chaseAt;
        /** Enemies left alone (see {@link #PASS_MS}), until when. */
        final Map<Integer, Long> passed = new HashMap<>();
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
        /**
         * Backed off once in this spell of low health: not again until the health has been back over
         * {@link Options#retreatAt} - an enemy that came after the body is fought, not run from in rounds.
         */
        boolean retreated;
        /** Backing off: the places to try, nearest the way straight from the enemy first; the trip under way. */
        final java.util.ArrayDeque<Goal> retreatTo = new java.util.ArrayDeque<>();
        CompletableFuture<NavResult> retreatTrip;
        boolean retreating;
        /** The enemies of the beat before: one of them is not dropped at the very edge ({@link #EDGE}). */
        final Set<Integer> minded = new HashSet<>();
        /** Where each enemy left alone stood when it was: one that has come nearer since is coming. */
        final Map<Integer, Vec3> passedAt = new HashMap<>();
        /** Stepping back out of the reach of one that attacks from afar: the places to try, the trip. */
        final java.util.ArrayDeque<Goal> standOffTo = new java.util.ArrayDeque<>();
        CompletableFuture<NavResult> standOffTrip;
        boolean standingOff;
        /** The foe there was nowhere to step back from, and where that was: fought where the body stands. */
        int noRoom = -1;
        Vec3 noRoomAt;
        /** Why the last shot was not made: said once, not every beat. */
        String noShot;
        /**
         * Enemies the bow had no flight to, and where the body and the enemy stood then: no shot is
         * tried again from there - the body comes nearer instead - until one of the two has moved
         * {@link #SHOT_AGAIN}.
         */
        final Map<Integer, Vec3[]> noShotFrom = new HashMap<>();
        /**
         * Enemies that attack from afar and could not be shot from afar: fought as any other from
         * then on - the bow where a shot opens, the sword in reach - not kept at a distance.
         */
        final Set<Integer> closedInOn = new HashSet<>();
        /**
         * Getting out of a bomb's blast ({@link #dodge}): where it will go off, the places being tried
         * and the trip to one; no place left to try.
         */
        boolean dodging;
        Vec3 dodgeFrom;
        final java.util.ArrayDeque<Goal> dodgeTo = new java.util.ArrayDeque<>();
        CompletableFuture<NavResult> dodgeTrip;
        /** The place {@link #dodgeTrip} goes to. */
        Goal dodgeGoal;
        boolean noWayOut;
        int dodges;
        /** Running from this enemy ({@link #flee}); {@code -1} when from none. */
        int fleeFrom = -1;
        /** The places the leg of the run under way tries, and its trip; how far the enemy was when it began. */
        final java.util.ArrayDeque<Goal> fleeTo = new java.util.ArrayDeque<>();
        CompletableFuture<NavResult> fleeTrip;
        double fleeGap;
        int flights;
        /** Out of the fight until the health is back ({@link Options#resumeAt}), or nothing heals it. */
        boolean resting;
        /**
         * The rest began with a retreat: backing off is what heals ({@link Options#retreatAt}), so it
         * takes a bite wherever the healing under way falls short. A rest after a fight that only left
         * the health low eats by the owner's mark for eating alone ({@link Options#healAt}).
         */
        boolean restBites;
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
                    noteBite();
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
            stopRetreat(j);
            stopFlee(j);
            stopStandOff(j);
            stopDodge(j);
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
        // A bomb's blast first: out of it, whatever else the beat would have been.
        if (j.lost == null && System.currentTimeMillis() >= j.playerTookUntil && dodge(j, me)) {
            return null;
        }
        List<Integer> enemies = enemies(j, me, j.options().radius(), me);
        if (enemies.isEmpty() && j.fleeFrom >= 0) {
            fled(j, "it is out of what it senses, or out of sight");
        }
        if (enemies.isEmpty() && j.fighting && j.lost == null && restsAfter(j)) {
            return null;    // the body kept until the health is back: the job would lead it off hurt
        }
        if (enemies.isEmpty()) {
            if (j.fighting) {
                if (j.target >= 0 && alive(j.target) && !j.struck.containsKey(j.target)) {
                    // Gone out of the radius alive - away, or the chase led the body off: not taken up
                    // again the moment the job brings the body back near it. (Not one we struck: that
                    // one is out of what it minds, and an enemy again the moment it is back in it.)
                    pass(j, j.target, "out of the radius alive");
                }
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
            j.resting = false;
            j.retreated = false;
            j.wheel = null;
            stopRetreat(j);
            stopFlee(j);
            stopStandOff(j);
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
        if (dodge(j, me)) {
            return null;        // a bomb's blast first
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
        if (targets.isEmpty() && restsAfter(j)) {
            j.idleSince = System.currentTimeMillis();
            return null;
        }
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
            if (self.isPresent() && self.getAsInt() == id || entities.itemOf(id).isPresent() || !alive(id)
                    || !living(id)) {
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
            double far = dist(at, me);
            boolean player = entities.isPlayer(id);
            boolean hitUs = combat != null && combat.hitBy(id, HIT_MEMORY);
            boolean swung = combat != null && combat.swingOf(id, SWING_MEMORY).isPresent()
                    && (dist(at, me) <= SWING_NEAR || fromCenter <= SWING_NEAR);
            boolean enemy;
            if (player) {
                enemy = j.options().players() && (hitUs || swung);
            } else {
                // A hostile kind: within the radius the owner set - or past it, where the body is
                // within what the mob itself senses: it will come, and is not waited on for its blow.
                // One that is an enemy already stays one a little past either ({@link #EDGE}).
                double edge = j.minded.contains(id) ? EDGE : 0;
                enemy = hitUs || swung || (hostileKind(id, j.options().hostile())
                        && (fromCenter <= radius + edge || noticedBy(id, Math.min(fromCenter, far) - edge, radius)));
            }
            // One we struck ourselves - a blow, an arrow - minds us from then on, and comes: an enemy
            // while it lives and is within what it goes on minding, whatever the radius.
            boolean struck = !player && j.struck.containsKey(id) && far <= struckReach(id, radius);
            enemy |= struck;
            if (enemy && fledFrom.contains(id) && !noticedBy(id, dist(at, me), radius)) {
                enemy = false;      // run from, and fallen behind: none of ours until it is near again
            }
            if (enemy && !(combat != null && combat.hitBy(id, HIT_NOW))) {
                // Not hitting us now: one left alone stays so, and one by its kind or its swing only is
                // an enemy when the eyes see it - a mob behind a wall is no threat to the job.
                Long until = j.passed.get(id);
                if (until != null && until > System.currentTimeMillis()) {
                    Vec3 stood = j.passedAt.get(id);
                    if (stood != null && far < dist(stood, me) - COMING) {
                        // Left alone - and it has come at the body since: not waited on for its blow.
                        j.passed.remove(id);
                        j.passedAt.remove(id);
                    } else {
                        enemy = false;
                    }
                }
                if (enemy && !hitUs && !struck && !(swung && j.mode == Mode.ESCORT) && !sees(id, at)) {
                    enemy = false;
                }
            }
            double leash = Math.max(Math.max(radius, reachOfSenses(id)), struck ? struckReach(id, radius) : 0) + LEASH;
            if (enemy && fromCenter <= leash) {
                out.add(id);
            }
        }
        fledFrom.removeIf(id -> !alive(id) || entities.positionOf(id).isEmpty());
        j.minded.clear();
        j.minded.addAll(out);
        return sorted(me, out);
    }

    /**
     * How far off one we struck stays an enemy: within what its kind goes on minding once alerted (a
     * goblin's 20-25 blocks) - but no farther than the leash past the radius: the body is not led off
     * across the world after something it hit once.
     */
    private double struckReach(int id, double radius) {
        Combat.Senses s = senses(id);
        double minds = s == null ? 0 : Math.max(s.alertedRange(), Math.max(s.viewRange(), s.hearingRange()));
        return minds > 0 ? Math.min(minds, radius + LEASH) : radius + LEASH;
    }

    /** What the entity's kind senses, as the game's files have it; {@code null} when not known. */
    private Combat.Senses senses(int id) {
        try {
            return combat == null ? null : combat.sensesOf(id).orElse(null);
        } catch (LinkageError olderCore) {
            return null;        // a core that does not tell: the radius set, as before
        }
    }

    /** How far the entity's kind notices the player from - it sees that far, or hears; {@code 0} when not known. */
    private double reachOfSenses(int id) {
        Combat.Senses s = senses(id);
        return s == null ? 0 : Math.max(s.viewRange(), s.hearingRange());
    }

    /**
     * Whether a hostile kind this far off will take the player for its target: the player is within
     * what it sees or hears (the owner's word, 21.11: such a one is fought at once, not waited on for
     * its blow). A kind the game's files do not tell of: within the radius set.
     */
    private boolean noticedBy(int id, double far, double radius) {
        double reach = reachOfSenses(id);
        return far <= (reach > 0 ? reach : radius);
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

    /**
     * A living thing: the server tells its health. What is thrown - a goblin's bomb, an arrow in flight
     * - has none, whatever its model is called: no enemy, nothing to fight and nothing to run at. The
     * owner's run of 22.11: {@code Bomb_Fire_Goblin} was "a goblin" by its name, the body ran up to
     * each bomb the lobber threw to put the sword to it, and stood in every blast.
     */
    private boolean living(int id) {
        Optional<EntityTracker.Stat> h = entities.healthOf(id);
        return h.isPresent() && h.get().max() > h.get().min();
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
        stopRetreat(j);
        stopFlee(j);
        stopStandOff(j);
        stopDodge(j);
        j.retreated = false;
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

    /**
     * A beat of stepping back from one that attacks from afar: the trip to the place being tried goes
     * on; one that did not get there gives way to the next place. Over on getting to one - then the bow
     * - or with no place left: then there is no room, and it is fought where the body stands.
     *
     * @return whether the beat went on it
     */
    private boolean stepBack(Job j, int target) {
        CompletableFuture<NavResult> trip = j.standOffTrip;
        if (trip != null && !trip.isDone()) {
            return true;
        }
        NavResult r = trip == null || trip.isCancelled() || trip.isCompletedExceptionally() ? null : trip.getNow(null);
        j.standOffTrip = null;
        boolean back = r != null && r.arrived();
        Goal next = back ? null : j.standOffTo.poll();
        if (next == null) {
            j.standOffTo.clear();
            j.standingOff = false;
            if (!back) {
                j.noRoom = target;
                j.noRoomAt = movement.position();
                log.info("auto-fighter: nowhere to step back to from {} - fought from here", target);
            }
            return false;
        }
        j.standOffTrip = navigator.goTo(next);
        return true;
    }

    private void stopStandOff(Job j) {
        CompletableFuture<NavResult> trip = j.standOffTrip;
        j.standOffTrip = null;
        j.standOffTo.clear();
        j.standingOff = false;
        j.noRoom = -1;
        if (trip != null && !trip.isDone()) {
            trip.cancel(false);     // this trip only: a job waiting on the urgent hold keeps its own
        }
    }

    /** One beat of a fight with {@code target}. */
    private void fight(Job j, int target, Vec3 me) throws Exception {
        if (j.target != target) {
            stopStandOff(j);
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
        if (hp * 100 > Math.max(j.options().retreatAt(), j.options().healAt())) {
            j.retreated = false;        // the spell of low health is over
        }
        // Under fire: something hit the body within the last second - a flame, an arrow, a blow.
        boolean underFire = combat != null
                && combat.hits(Duration.ofSeconds(1)).stream().anyMatch(h -> h.amount() > 0);
        if (j.fleeFrom >= 0) {
            if (flee(j, me)) {
                return;
            }
            if (fledFrom.contains(target) && !noticedBy(target, dist(at, me), j.options().radius())) {
                return;     // it fell behind: no enemy now, and nothing of this beat goes after it
            }
        }
        if (j.retreating && backOff(j)) {
            return;
        }
        if (j.resting && rest(j, target, hp)) {
            return;
        }
        if (hp * 100 <= j.options().retreatAt() && !fledFrom.contains(target) && !entities.isPlayer(target)
                && hands.healing().overTime() <= 0 && !carriesHeal() && !awaited()) {
            // Low, and nothing brings it back: no fight to be had with it. Run, until it falls behind.
            // (Nothing at all: a potion that is only cooling down is something to heal with.)
            beginFlight(j, target, me);
            return;
        }
        // Backing off to heal: at the mark for it - and already at the mark for eating when the body
        // is under fire (nothing is eaten in a flame: the server breaks the bite off, and the body
        // stood still for it - the owner's run of 22.11 ended there, at 3 of 100).
        if ((hp * 100 <= j.options().retreatAt() || (hp * 100 <= j.options().healAt() && underFire)) && !j.retreated
                && (hands.healing().overTime() > 0 || healSlot() >= 0 || awaited())) {
            retreat(j, target, me);
            return;
        }
        if (hp * 100 <= j.options().healAt() && !coming(target) && !underFire) {
            int slot = healSlot();
            if (slot >= 0 && worthABite(gainOf(slot), hp, j.options().resumeAt(), true)) {
                heal(j, slot);
                return;
            }
        }
        // One that attacks from afar - a flame's six blocks, a bomb's twelve - is fought from afar
        // (the owner's word, 22.11): out of what it reaches, with the bow; the sword only where there
        // is no other way - no bow, no arrow, no room and no shot.
        boolean bowAtHand = j.options().bow() && bows.slot() >= 0;
        Combat.Senses foe = senses(target);
        double far = Math.sqrt(me.distanceSqTo(at));
        if (j.noRoom == target && j.noRoomAt != null && dist(me, j.noRoomAt) > 4) {
            j.noRoom = -1;      // the body is elsewhere now: there may be room again
        }
        // Kept off: it reaches far, there is a bow, and room to step back in. With no room - or no
        // shot at it from afar - it is fought as any other: the bow where a shot opens, the sword once
        // it is near: no other way is left.
        boolean ranged = bowAtHand && foe != null && foe.attackDistance() > RANGED && j.noRoom != target
                && !j.closedInOn.contains(target);
        // The bow had no flight to it from here: not asked again until here or there is another place
        // - and the eyes have it again: the body is not stopped every two blocks of the way round a
        // wall to ask a bow that cannot see.
        Vec3[] refused = j.noShotFrom.get(target);
        boolean noShotHere = refused != null && ((dist(me, refused[0]) < SHOT_AGAIN && dist(at, refused[1]) < SHOT_AGAIN)
                || !sees(target, at));
        // One that throws bombs is not stepped back from: its bombs are got out of ({@link #dodge}), and
        // the time between them is the bow's (the owner's word, 22.11: "no need to keep farther off, but
        // the bow first" - stepping back after each blast, the body shot five arrows in a minute).
        if (ranged && !j.standingOff && !throwers.contains(target) && far < foe.attackDistance() + STANDOFF
                && foe.maxSpeed() > 0 && foe.maxSpeed() < OUTRUN) {
            // Within what it reaches, and it is slow: back first, out of it.
            Vec3 from = at;
            double dx = me.x() - from.x();
            double dz = me.z() - from.z();
            double len = Math.hypot(dx, dz);
            if (len < 1e-3) {
                dx = 1;
                dz = 0;
                len = 1;
            }
            away(me, dx / len, dz / len, j.standOffTo);
            j.standOffTrip = null;
            j.standingOff = true;
            stopChase(j);
            log.info("auto-fighter: {} ({}) reaches {} blocks and is {} off - stepping back to shoot",
                    entities.labelOf(target), target, foe.attackDistance(), String.format(Locale.ROOT, "%.1f", far));
        }
        if (j.standingOff) {
            status = "Fighting " + entities.labelOf(target) + ": stepping back out of its reach.";
            if (stepBack(j, target)) {
                return;
            }
        }
        if (bowAtHand && !noShotHere && (ranged ? far > BOW_NEAR : far > j.options().bowDistance())) {
            stopChase(j);
            movement.stop();
            status = "Fighting " + entities.labelOf(target) + ": drawing bow.";
            // The bow's full draw, always (the owner's word, 22.11: "the difference in the attack is
            // great" - 29 an arrow against the shortest draw's 3).
            BowSupport.Shot shot = drawn(bows.shoot(target, true));
            if (shot == null) {
                return;         // the draw given up for a bomb on its way: the next beat gets out of its blast
            }
            if (shot.sent()) {
                j.swings++;
                j.struck.put(target, System.currentTimeMillis());
                j.chaseFor = -1;
                j.noShot = null;
                j.noShotFrom.remove(target);
                log.info("meridian-auto-fighter: bow at {}: {}", target, shot.reason());
                return;
            }
            if (!shot.reason().equals(j.noShot)) {
                j.noShot = shot.reason();
                log.info("meridian-auto-fighter: no shot at {}: {}", target, shot.reason());
            }
            boolean noFlight = shot.reason().startsWith("UNREACHABLE") || shot.reason().startsWith("UNKNOWN_TARGET");
            if (!noFlight && ranged && bows.slot() >= 0) {
                // The bow busy, the body still moving: over by the next beat. One that reaches far is
                // not run at because one shot was not made.
                return;
            }
            if (noFlight) {
                // No line to it, no flight that gets there from here: nearer, for a shot or for the
                // sword. One that attacks from afar is not left be for that (the owner's run of 22.11:
                // a lobber on a ledge nine blocks up, "left alone", threw bomb after bomb down at the
                // body) - from now on it is fought as any other.
                j.noShotFrom.put(target, new Vec3[] {me, at});
                if (ranged) {
                    j.closedInOn.add(target);
                    stopStandOff(j);
                    log.info("auto-fighter: no shot at {} ({}) from here - closing in on it", entities.labelOf(target), target);
                }
            }
            // A wall, unknown trajectory or missing ammo: approach with the melee weapon.
        }
        arm(j);
        String held = world.player().map(Player::heldItem).orElse(null);
        double left = reachLeft(target);
        if (left < 0) {
            status = String.format(Locale.ROOT, "Fighting %s: going after it, %.1f to go.", entities.labelOf(target), -left);
            double reach = reachOf(held);
            if (j.chaseFor != target) {
                j.chaseFor = target;
                j.chaseGap = far;
                j.chaseTargetFrom = at;
                j.chaseRun = 0;
                j.chaseAt = me;
            }
            j.chaseRun += dist(me, j.chaseAt);
            j.chaseAt = me;
            if (far < j.chaseGap - 1.0) {
                // Nearer: the chase is getting somewhere - reckoned anew from here.
                j.chaseGap = far;
                j.chaseTargetFrom = at;
                j.chaseRun = 0;
            }
            boolean keepsAway = j.chaseRun >= CHASE_RUN && dist(at, j.chaseTargetFrom) >= CHASE_RUN / 2;
            if (!(combat != null && combat.hitBy(target, HIT_NOW))) {
                NavResult r = j.chased == target && j.chase != null && j.chase.isDone() && !j.chase.isCancelled()
                        ? j.chase.getNow(null) : null;
                boolean noWay = r != null && (r.status() == NavResult.Status.NO_PATH
                        || r.status() == NavResult.Status.BLOCKED);
                if (noWay || keepsAway) {
                    pass(j, target, noWay ? "no way to it (" + r.status() + ")"
                            : String.format(Locale.ROOT, "it keeps away - no nearer after %.0f blocks of chase", j.chaseRun));
                    stopChase(j);
                    unmark();
                    j.target = -1;
                    j.chaseFor = -1;
                    return;
                }
            }
            if (j.chase == null || j.chase.isDone() || j.chased != target) {
                stopChase(j);
                j.chased = target;
                j.chase = navigator.chase(() -> entities.positionOf(target), Math.max(0.8, reach - 0.9));
            }
            return;
        }
        stopChase(j);
        j.chaseFor = -1;
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
        if (world.player().map(Player::heldItem).orElse("").startsWith("Weapon_Shortbow_")) return;
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
        j.noShotFrom.remove(target);
        j.closedInOn.remove(target);
        throwers.remove(target);
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
        int healSlot = hp * 100 <= j.options().healAt() ? healSlot() : -1;
        boolean heal = healSlot >= 0 && worthABite(gainOf(healSlot), hp, j.options().resumeAt(), false);
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

    /**
     * The hotbar slot whose item would add the most to the health soon - its gain over the healing
     * under way, as core plays its use on the effects on now ({@link InteractionControl#healGain}) -
     * else the most in all; {@code -1} for none that would add {@link #LEAST_GAIN} of the max.
     */
    private int healSlot() {
        Player p = world.player().orElse(null);
        if (p == null) {
            return -1;
        }
        double least = LEAST_GAIN * maxHealth();
        int best = -1;
        double bestFast = -1;
        double bestTotal = -1;
        Bite last = noteBite();
        String refused = last != null && !last.taken() && !last.told() ? last.item() : null;
        for (int slot = 0; slot < 9; slot++) {
            String id = p.hotbarItem(slot);
            if (id == null || id.equals(refused) || healOf(id).isEmpty()) {
                continue;
            }
            InteractionControl.Heal g = hands.healGain(id).orElse(InteractionControl.Heal.NONE);
            if (g.total() < least) {
                continue;
            }
            double fast = fast(g);
            if (fast > bestFast || (fast == bestFast && g.total() > bestTotal)) {
                best = slot;
                bestFast = fast;
                bestTotal = g.total();
            }
        }
        return best;
    }

    /** What the item in {@code slot} would add to the health now, the healing under way counted. */
    private InteractionControl.Heal gainOf(int slot) {
        Player p = world.player().orElse(null);
        String id = p == null ? null : p.hotbarItem(slot);
        return id == null ? InteractionControl.Heal.NONE : hands.healGain(id).orElse(InteractionControl.Heal.NONE);
    }

    /** The part of a heal that comes within {@link #FAST_SECONDS}: the instant part and the slow one's start. */
    private static double fast(InteractionControl.Heal h) {
        double slow = h.seconds() > FAST_SECONDS ? h.overTime() * FAST_SECONDS / h.seconds() : h.overTime();
        return h.now() + slow;
    }

    /**
     * Whether a bite of what would add {@code gain} is worth it, health at {@code hp} (a share) wanted
     * back up to {@code upTo} percent: only where the healing under way falls short of it - a
     * food's slow heal runs for minutes, and another bite only starts it over. In a fight, a bite
     * that heals much at once (a potion) is taken all the same.
     */
    private boolean worthABite(InteractionControl.Heal gain, double hp, int upTo, boolean inFight) {
        if (awaited()) {
            return false;       // the last bite's healing is not told yet: judged then, on what is
        }
        double max = maxHealth();
        if (gain.total() < LEAST_GAIN * max) {
            return false;
        }
        if (inFight && fast(gain) >= BIG_BITE * max) {
            return true;
        }
        double needed = (Math.max(upTo / 100.0, 0) - hp) * max;
        return needed > 0 && hands.healing().overTime() < needed;
    }

    /** Whether the hotbar holds anything whose use heals - usable this moment or not (a potion cooling down). */
    private boolean carriesHeal() {
        Player p = world.player().orElse(null);
        if (p == null) {
            return false;
        }
        for (int slot = 0; slot < 9; slot++) {
            String id = p.hotbarItem(slot);
            if (id != null && healOf(id).isPresent()) {
                return true;
            }
        }
        return false;
    }

    /** A bite was taken and the server has not told what it did yet. */
    private boolean awaited() {
        Bite b = noteBite();
        return b != null && b.taken() && !b.told();
    }

    /** Looks whether the server has told of the last use yet, and keeps that it has. */
    private Bite noteBite() {
        Bite b = bite;
        if (b != null && !b.told() && told(b)) {
            b = new Bite(b.item(), b.taken(), b.health(), b.effects(), true);
            bite = b;
        }
        return b;
    }

    /**
     * Whether the server has told what the use did.
     *
     * <p>Of a bite taken: its own result - an effect arrived (a new one, or one put on anew: it has
     * more time left than it had), or the health went up. A food's and a potion's healing both come as
     * effects, and the server tells of them some time after the chain has ended (0.6-1.4 s in the
     * stand's logs; the stack going down is told BEFORE the chain ends, and is no word on the healing).
     * A hit in between - the health down - is not it, nor is an effect going: an overwrite takes the
     * effect off before it puts it back on.
     *
     * <p>Of a use refused: that anything stands otherwise than it did - the health either way, the
     * effects on.
     */
    private boolean told(Bite b) {
        double hp = health().orElse(b.health());
        Map<String, Double> now = effectsNow();
        boolean arrived = false;
        for (Map.Entry<String, Double> e : now.entrySet()) {
            Double had = b.effects().get(e.getKey());
            arrived |= had == null || e.getValue() > had + 1e-3;
        }
        if (b.taken()) {
            return arrived || hp > b.health() + 1e-6;
        }
        return arrived || Math.abs(hp - b.health()) > 1e-6 || !now.keySet().equals(b.effects().keySet());
    }

    /** The effects on the player now, each with the seconds it has left (infinite for one without an end). */
    private Map<String, Double> effectsNow() {
        Map<String, Double> out = new HashMap<>();
        OptionalInt self = entities.localEntityId();
        if (self.isPresent()) {
            for (EntityTracker.Effect e : entities.effectsOf(self.getAsInt())) {
                out.put(e.id(), e.infinite() ? Double.POSITIVE_INFINITY : e.secondsLeft());
            }
        }
        return out;
    }

    /** The player's max health in points - the stat's span - or 100 when core has not said. */
    private double maxHealth() {
        OptionalInt self = entities.localEntityId();
        Optional<EntityTracker.Stat> h = self.isPresent() ? entities.healthOf(self.getAsInt()) : Optional.empty();
        return h.isPresent() && h.get().max() > h.get().min() ? h.get().max() - h.get().min() : 100;
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
        double hpBefore = health().orElse(1.0);
        Map<String, Double> effectsBefore = effectsNow();
        int stackBefore = carried(id);
        InteractionOutcome used = hands.useItem().get(10, TimeUnit.SECONDS);
        boolean taken = took(used) || carried(id) < stackBefore;
        // How things stood: what the use did is told after (see Bite).
        bite = new Bite(id, taken, hpBefore, effectsBefore, false);
        if (!taken) {
            log.info("auto-fighter: {} not taken ({}) - not tried again as things stand", id,
                    used == null ? "no answer" : used.status());
        } else {
            j.heals++;
            log.info("auto-fighter: healed with {} at health {}", id, Math.round(hpBefore * 100));
        }
        if (back >= 0 && back != slot && !guarding(j)) {
            hands.switchHotbarSlot(back).get(3, TimeUnit.SECONDS);
        }
    }

    /**
     * Whether the server took the item of a use: the step of its chain that takes it from the stack
     * ({@code ModifyInventoryInteraction} - the server alone runs it, and says how it went) ran and
     * did not fail. A use refused - a potion's cooldown, a click too soon - ends before it, or fails
     * there: it heals nothing and is no heal.
     */
    private static boolean took(InteractionOutcome used) {
        return used != null && used.steps().stream()
                .anyMatch(st -> st.operation().startsWith("ModifyInventoryInteraction") && !st.failed());
    }

    /** How many of the item the hotbar holds. */
    private int carried(String id) {
        PlayerState.Section s = state == null ? null : state.inventories().get("Hotbar");
        if (s == null) {
            return 0;
        }
        int n = 0;
        for (Containers.Item item : s.items().values()) {
            if (item != null && id.equals(item.id())) {
                n += item.quantity();
            }
        }
        return n;
    }

    /**
     * Begins backing off from the enemies - up to {@link #RETREAT} blocks straight away from them, or
     * to the sides: the places to try, and the first trip. The beats after go on it ({@link #backOff}),
     * then on the rest there ({@link #rest}).
     */
    private void retreat(Job j, int target, Vec3 me) {
        j.retreats++;
        j.retreated = true;
        stopChase(j);
        stopRetreat(j);
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
        log.info("auto-fighter: health low ({}) - backing off to rest up to {}",
                health().map(h -> Math.round(h * 100)).orElse(-1L), j.options().resumeAt());
        away(me, dx, dz, j.retreatTo);
        j.retreating = true;
        backOff(j);
    }

    /** The places to back off to, {@code (dx, dz)} the way from the enemy: straight away first, then to the sides, nearer. */
    private void away(Vec3 me, double dx, double dz, java.util.ArrayDeque<Goal> into) {
        into.clear();
        for (double far : new double[] {RETREAT, 5, 3}) {
            for (double turn : new double[] {0, Math.PI / 4, -Math.PI / 4, Math.PI / 2, -Math.PI / 2}) {
                double rx = dx * Math.cos(turn) - dz * Math.sin(turn);
                double rz = dx * Math.sin(turn) + dz * Math.cos(turn);
                int cx = (int) Math.floor(me.x() + rx * far);
                int cz = (int) Math.floor(me.z() + rz * far);
                int cy = (int) Math.floor(me.y() + 1e-3);
                Integer y = standY(cx, cy, cz);
                if (y != null) {
                    into.add(new Goal(cx, y, cz, 1));
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // A bomb's blast (the owner's word, 22.11: "get out of the blast's radius - the best of them")
    // ------------------------------------------------------------------

    /**
     * A blast the body stands in, or will.
     *
     * @param id    the bomb
     * @param at    where it will go off: where it lies - or, in the air, the point of its way nearest the body
     * @param reach how far from there the body is to be: the blast's radius and {@link #BLAST_MARGIN}
     * @param off   how far the body is from there now
     * @param also  in the air: where it was reckoned to come down (it goes off there, or where it is); else null
     * @param fx    in the air: the way it flies over the ground, a unit vector; else nought
     */
    private record Danger(int id, Vec3 at, Vec3 also, double reach, double off, double fx, double fz) {
    }

    /**
     * The bombs in sight, each as last seen: {@code x, y, z}, when (ns), its speed over the ground
     * ({@code vx, vz}) - and where it was reckoned to come down when it was first seen flying
     * ({@code x, z}; NaN until then).
     */
    private final Map<Integer, double[]> bombsSeen = new HashMap<>();
    /** Those a bomb was first seen by - it left their hand: the throwers. */
    private final Set<Integer> throwers = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** A bomb first seen this near a living thing is that one's throw. */
    static final double THROWN_FROM = 3.5;

    /**
     * The blast the body is deepest in, of the bombs in sight ({@code Combat.blastOf}: what the game's
     * files say explodes, and how far round); {@code null} for none. A bomb in the air is reckoned to
     * come down further along its way - at the point of it nearest the body: a lobber throws at the
     * body, and the second it lies before it goes off is not enough to get six blocks from it.
     */
    private Danger danger(Vec3 me) {
        if (combat == null) {
            return null;
        }
        Danger worst = null;
        long now = System.nanoTime();
        double whole = maxHealth();
        double left = health().orElse(1.0) * whole;
        Set<Integer> live = new HashSet<>();
        try {
            for (int id : entities.trackedEntities()) {
                Combat.Blast blast = combat.blastOf(id).orElse(null);
                Vec3 p = blast == null ? null : entities.positionOf(id).orElse(null);
                if (p == null) {
                    continue;
                }
                live.add(id);
                double[] seen = bombsSeen.get(id);
                if (seen == null) {
                    seen = new double[] {p.x(), p.y(), p.z(), now, 0, 0, Double.NaN, Double.NaN};
                    bombsSeen.put(id, seen);
                    thrownBy(p);
                } else if (p.x() != seen[0] || p.y() != seen[1] || p.z() != seen[2]) {
                    // Told anew by the server (a beat between two of its tellings sees the same place:
                    // the speed last reckoned stands).
                    double dt = (now - seen[3]) / 1e9;
                    if (dt > 1e-3) {
                        seen[4] = (p.x() - seen[0]) / dt;
                        seen[5] = (p.z() - seen[2]) / dt;
                    }
                    seen[0] = p.x();
                    seen[1] = p.y();
                    seen[2] = p.z();
                    seen[3] = now;
                }
                Vec3 also = null;
                double fx = 0;
                double fz = 0;
                // Not told of anew for a while: it lies (the speed last reckoned is of its last move -
                // taken for flight on and on, it had the body run this way and that by it, 22.11).
                boolean lying = now - (long) seen[3] > BOMB_LYING_NS;
                double speed = lying ? 0 : Math.hypot(seen[4], seen[5]);
                if (speed > BOMB_FLYING) {
                    double ux = seen[4] / speed;
                    double uz = seen[5] / speed;
                    fx = ux;
                    fz = uz;
                    if (Double.isNaN(seen[6])) {
                        // Where it comes down: reckoned once, from where the body stands as it is
                        // thrown - the point of its way nearest that. Not anew each beat: the nearest
                        // point of its way to a body running from it runs along with the body, and the
                        // body was sent this way and that under the bomb (and died there, 22.11).
                        double along = clamp((me.x() - p.x()) * ux + (me.z() - p.z()) * uz, 0, BOMB_AHEAD);
                        seen[6] = p.x() + ux * along;
                        seen[7] = p.z() + uz * along;
                    }
                    // In the air: both that place and the bomb itself are kept clear of - it comes down
                    // short as often as not, and rolls.
                    also = new Vec3(seen[6], me.y(), seen[7]);
                }
                if (blast.damage() > 0 && blast.damage() < BLAST_WORTH * whole && blast.damage() < BLAST_WORTH_LEFT * left) {
                    continue;   // a few points: stood, the bow drawn on
                }
                double reach = blast.radius() + BLAST_MARGIN;
                double off = also == null ? dist(me, p) : Math.min(dist(me, p), dist(me, also));
                if (off < reach && (worst == null || off < worst.off())) {
                    worst = new Danger(id, p, also, reach, off, fx, fz);
                }
            }
        } catch (LinkageError olderCore) {
            return null;        // a core that does not tell what explodes: nothing to get out of
        }
        bombsSeen.keySet().retainAll(live);
        return worst;
    }

    /**
     * A draw of the bow waited out - unless a blast comes to reach the body meanwhile: a full draw is a
     * second and more of standing still, and a bomb thrown in it has two seconds to live. Then the draw
     * is let go (its arrow comes back) and {@code null} is the answer.
     */
    private BowSupport.Shot drawn(CompletableFuture<BowSupport.Shot> drawing) throws Exception {
        long until = System.currentTimeMillis() + 35_000;
        while (true) {
            try {
                return drawing.get(50, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException still) {
                Vec3 me = movement.position();
                if (me != null && danger(me) != null) {
                    // Let go, and not waited on: the fuse does not wait either.
                    bows.cancel();
                    log.info("auto-fighter: a bomb on its way - the draw let go");
                    return null;
                }
                if (System.currentTimeMillis() > until) {
                    throw still;
                }
            }
        }
    }

    /** Whose throw a bomb just seen at {@code p} is: the living thing nearest it, if one is by it. */
    private void thrownBy(Vec3 p) {
        OptionalInt self = entities.localEntityId();
        int by = -1;
        double nearest = THROWN_FROM;
        for (int id : entities.trackedEntities()) {
            if (self.isPresent() && self.getAsInt() == id || !living(id)) {
                continue;
            }
            Vec3 at = entities.positionOf(id).orElse(null);
            if (at != null && dist(at, p) < nearest) {
                nearest = dist(at, p);
                by = id;
            }
        }
        if (by >= 0 && throwers.add(by)) {
            log.info("auto-fighter: {} ({}) throws bombs - they are got out of, and it is shot between them",
                    entities.labelOf(by), by);
        }
    }

    /**
     * A beat of getting out of a blast: the body goes to a place past the blast's reach - straight away
     * from where it will go off, else to the sides - and nothing else is done meanwhile: no bow drawn,
     * no bite taken. Planned anew when the blast point moves (the bomb bounced, another came down).
     * Over when no blast reaches the body - the bomb went off, or the body is out of it: the fight goes
     * on, with the bow. Guard and the sentry take the body for it as for an enemy.
     *
     * @return whether the beat went on it
     */
    private boolean dodge(Job j, Vec3 me) {
        Danger d = danger(me);
        if (d == null) {
            if (j.dodging) {
                stopDodge(j);
                log.info("auto-fighter: out of the blast");
            }
            return false;
        }
        if (guarding(j) && !j.fighting && !beginFight(j)) {
            return false;       // the body cannot be taken: nothing to move
        }
        if (!j.dodging) {
            j.dodging = true;
            j.dodges++;
            stopChase(j);
            stopRetreat(j);
            stopStandOff(j);
            log.info("auto-fighter: a {} goes off {} blocks from the body (it reaches {}) - out of its blast",
                    entities.labelOf(d.id()), String.format(Locale.ROOT, "%.1f", d.off()),
                    String.format(Locale.ROOT, "%.1f", d.reach() - BLAST_MARGIN));
        }
        CompletableFuture<NavResult> going = j.dodgeTrip;
        boolean running = going != null && !going.isDone();
        // A way out once taken is kept to while the place it goes to stays clear of the blast. Planned
        // anew on every shift of the blast point, the body was turned this way and that under the bomb
        // for the whole of its fuse (22.11).
        if (running && j.dodgeGoal != null && clear(d, j.dodgeGoal.x(), j.dodgeGoal.z())) {
            status = "Getting out of a bomb's blast.";
            return true;
        }
        if (running || j.dodgeFrom == null || dist(d.at(), j.dodgeFrom) > BLAST_MOVED) {
            if (running) {
                going.cancel(false);
            }
            j.dodgeTrip = null;
            j.dodgeGoal = null;
            j.dodgeFrom = d.at();
            j.noWayOut = false;
            out(d, me, j.dodgeTo);
        }
        Goal next = j.dodgeTo.poll();
        while (next != null && !clear(d, next.x(), next.z())) {
            next = j.dodgeTo.poll();
        }
        j.dodgeTrip = null;
        j.dodgeGoal = null;
        if (next == null) {
            if (!j.noWayOut) {
                j.noWayOut = true;
                log.info("auto-fighter: no way out of the blast from here - it is stood");
            }
            return false;   // nowhere to go: the fight, where the body stands
        }
        j.dodgeGoal = next;
        j.dodgeTrip = navigator.goTo(next);
        status = "Getting out of a bomb's blast.";
        return true;
    }

    /** Whether a cell is clear of a blast: of where the bomb is, and of where it was reckoned to come down. */
    private static boolean clear(Danger d, int cx, int cz) {
        double need = d.reach() + DODGE_GOAL;
        double x = cx + 0.5;
        double z = cz + 0.5;
        return Math.hypot(x - d.at().x(), z - d.at().z()) >= need
                && (d.also() == null || Math.hypot(x - d.also().x(), z - d.also().z()) >= need);
    }

    /**
     * The places out of a blast nearest the body: along each of sixteen ways the first cell clear of it
     * where the body can stand, the shortest first - across a flying bomb's way before along it. Not farther than that (the owner's word, 22.11: no
     * need to keep farther off - the bow comes first).
     */
    private void out(Danger d, Vec3 me, java.util.ArrayDeque<Goal> into) {
        into.clear();
        List<double[]> found = new ArrayList<>();
        int feet = (int) Math.floor(me.y() + 1e-3);
        for (int k = 0; k < 16; k++) {
            double dx = Math.cos(k * Math.PI / 8);
            double dz = Math.sin(k * Math.PI / 8);
            for (double r = 1.0; r <= 16.0; r += 0.5) {
                int cx = (int) Math.floor(me.x() + dx * r);
                int cz = (int) Math.floor(me.z() + dz * r);
                if (!clear(d, cx, cz)) {
                    continue;
                }
                Integer y = standY(cx, feet, cz);
                if (y != null) {
                    found.add(new double[] {r + ALONG_ITS_WAY * Math.abs(dx * d.fx() + dz * d.fz()), cx, y, cz});
                    break;
                }
            }
        }
        found.sort(java.util.Comparator.comparingDouble(f -> f[0]));
        Set<Long> cells = new HashSet<>();
        for (double[] f : found) {
            if (cells.add(((long) f[1] << 32) ^ ((long) f[3] & 0xffffffffL)) && into.size() < 6) {
                into.add(new Goal((int) f[1], (int) f[2], (int) f[3], DODGE_GOAL));
            }
        }
    }

    private void stopDodge(Job j) {
        CompletableFuture<NavResult> trip = j.dodgeTrip;
        j.dodgeTrip = null;
        j.dodgeGoal = null;
        j.dodgeTo.clear();
        j.dodging = false;
        j.dodgeFrom = null;
        j.noWayOut = false;
        if (trip != null && !trip.isDone()) {
            trip.cancel(false);     // this trip only: a job waiting on the urgent hold keeps its own
        }
    }

    // ------------------------------------------------------------------
    // Flight: health low, nothing to heal with (the owner's word, 21.11)
    // ------------------------------------------------------------------

    /** Begins running from {@code target}: the first leg. */
    private void beginFlight(Job j, int target, Vec3 me) {
        j.flights++;
        j.fleeFrom = target;
        stopChase(j);
        stopRetreat(j);
        j.resting = false;
        log.info("auto-fighter: health low ({}) and nothing to heal with - running from {} ({}) until it falls behind",
                health().map(h -> Math.round(h * 100)).orElse(-1L), entities.labelOf(target), target);
        leg(j, me);
    }

    /** A leg of the run: the places away from the enemy as it stands now, and the trip to the first. */
    private void leg(Job j, Vec3 me) {
        Vec3 from = entities.positionOf(j.fleeFrom).orElse(me);
        double dx = me.x() - from.x();
        double dz = me.z() - from.z();
        double len = Math.hypot(dx, dz);
        if (len < 1e-3) {
            dx = 1;
            dz = 0;
            len = 1;
        }
        away(me, dx / len, dz / len, j.fleeTo);
        j.fleeGap = dist(from, me);
        j.fleeTrip = null;
        status = "Running from " + entities.labelOf(j.fleeFrom) + ": health low, nothing to heal with.";
    }

    /**
     * A beat of the run. The trip under way goes on. A leg over: the enemy gone, or farther than it
     * senses (than the radius set, for a kind the game's files tell nothing of) - it has fallen
     * behind, and the run is over; the gap no wider than when the leg began - it
     * keeps up, running does not shake it off, and it is fought; else the next leg. With no place to
     * run to it is fought as well.
     *
     * @return whether the beat went on running
     */
    private boolean flee(Job j, Vec3 me) {
        int from = j.fleeFrom;
        Vec3 at = entities.positionOf(from).orElse(null);
        if (at == null || !alive(from)) {
            fled(j, "it is gone");
            return false;
        }
        CompletableFuture<NavResult> trip = j.fleeTrip;
        if (trip != null && !trip.isDone()) {
            return true;        // on the way: the leg ends by getting there, or by there being no way
        }
        NavResult r = trip == null || trip.isCancelled() || trip.isCompletedExceptionally() ? null : trip.getNow(null);
        j.fleeTrip = null;
        if (r != null && r.arrived()) {
            double gap = dist(at, me);
            if (!noticedBy(from, gap, j.options().radius())) {
                fled(j, String.format(Locale.ROOT, "it fell behind, %.0f blocks off", gap));
                return false;
            }
            if (gap < j.fleeGap + GAINED) {
                fled(j, String.format(Locale.ROOT, "it keeps up (%.0f blocks off, %.0f when the leg began) - fought", gap, j.fleeGap));
                return false;
            }
            leg(j, me);         // farther, and within its senses still: on
        }
        Goal next = j.fleeTo.poll();
        if (next == null) {
            fled(j, "nowhere to run to - fought");
            return false;
        }
        j.fleeTrip = navigator.goTo(next);
        return true;
    }

    /** The run is over: its enemy is not run from again ({@link #fledFrom}). */
    private void fled(Job j, String how) {
        int from = j.fleeFrom;
        if (from < 0) {
            return;
        }
        fledFrom.add(from);
        log.info("auto-fighter: the run from {} ({}) is over - {}", entities.labelOf(from), from, how);
        stopFlee(j);
        if (j.target == from) {
            j.target = -1;
            unmark();
        }
    }

    private void stopFlee(Job j) {
        CompletableFuture<NavResult> trip = j.fleeTrip;
        j.fleeTrip = null;
        j.fleeTo.clear();
        j.fleeFrom = -1;
        if (trip != null && !trip.isDone()) {
            trip.cancel(false);     // this trip only: a job waiting on the urgent hold keeps its own
        }
    }

    /**
     * A beat of backing off: the trip to the place being tried goes on; one that did not get there
     * (no way, blocked) gives way to the next place. Over - and the rest begins - on getting to one,
     * or with no place left to try: then the rest is where the body stands.
     *
     * @return whether the beat went on it
     */
    private boolean backOff(Job j) {
        CompletableFuture<NavResult> trip = j.retreatTrip;
        if (trip != null && !trip.isDone()) {
            return true;        // on the way: it ends by getting there or by there being no way
        }
        NavResult r = trip == null || trip.isCancelled() || trip.isCompletedExceptionally() ? null : trip.getNow(null);
        j.retreatTrip = null;
        Goal next = r != null && r.arrived() ? null : j.retreatTo.poll();
        if (next == null) {
            j.retreatTo.clear();
            j.retreating = false;
            j.resting = true;
            j.restBites = true;
            return false;
        }
        j.retreatTrip = navigator.goTo(next);
        return true;
    }

    private void stopRetreat(Job j) {
        CompletableFuture<NavResult> trip = j.retreatTrip;
        j.retreatTrip = null;
        j.retreatTo.clear();
        j.retreating = false;
        if (trip != null && !trip.isDone()) {
            trip.cancel(false);     // this trip only: a job waiting on the urgent hold keeps its own
        }
    }

    /**
     * After a fight, with no enemy about: whether the body is kept to rest - the fight left the health
     * under {@link Options#resumeAt} and something is bringing it back ({@link #rest}).
     */
    private boolean restsAfter(Job j) throws Exception {
        double hp = health().orElse(1.0);
        if (j.retreating && backOff(j)) {
            return true;
        }
        if (!j.resting && hp * 100 < j.options().resumeAt()) {
            j.resting = true;
            j.restBites = false;
        }
        return j.resting && rest(j, -1, hp);
    }

    /**
     * A beat of rest - after a retreat, or after a fight that left the health low: the body stands
     * out of the fight while the healing under way brings the health back to {@link Options#resumeAt},
     * with a bite where that falls short - after a retreat whenever it does, else only with the health
     * at the owner's own mark for eating ({@link Options#healAt}). Over when the health is back, or when nothing is bringing it
     * back - no healing under way, none on its way from a bite just taken, and no bite to take. However
     * long that is: a food's slow heal runs for minutes. An enemy that came after the body
     * ({@code target}, in its reach or hitting) is fought - this beat is not a rest.
     *
     * @return whether the beat went on the rest
     */
    private boolean rest(Job j, int target, double hp) throws Exception {
        int back = j.options().resumeAt();
        int slot = hp * 100 < back && (j.restBites || hp * 100 <= j.options().healAt()) ? healSlot() : -1;
        boolean bite = slot >= 0 && worthABite(gainOf(slot), hp, back, false);
        String over = null;
        if (hp * 100 >= back) {
            over = "the health is back";
        } else if (hands.healing().overTime() <= 0 && !awaited() && !bite) {
            over = "nothing heals it";
        }
        if (over != null) {
            j.resting = false;
            j.restBites = false;
            log.info("auto-fighter: the rest is over - {} (health {})", over, Math.round(hp * 100));
            return false;
        }
        if (target >= 0 && (eyesTo(target) <= ENEMY_REACH + 1
                || combat != null && combat.hitBy(target, Duration.ofSeconds(1)))) {
            return false;   // it came after us: fought, not waited out
        }
        stopChase(j);
        movement.stop();
        if (bite) {
            heal(j, slot);
            return true;
        }
        status = String.format(Locale.ROOT, "Resting: health %d%%, back to the fight at %d%%.",
                Math.round(hp * 100), back);
        return true;
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

    /**
     * The player's own health, as a share of its max; empty when core has not been told - or tells
     * none at all: that is a dead body's, or one alive again a moment ago whose health the server has
     * not said yet (it does after the revival). Nothing to judge by: a body just back alive ate a pie
     * for the nought it had died with (22.11).
     */
    private Optional<Double> health() {
        OptionalInt self = entities.localEntityId();
        if (self.isPresent()) {
            Optional<EntityTracker.Stat> h = entities.healthOf(self.getAsInt());
            if (h.isPresent() && h.get().max() > h.get().min()) {
                double share = h.get().fraction();
                return share > 0 ? Optional.of(share) : Optional.empty();
            }
        }
        if (state != null) {
            Double v = state.stats().get("Health");
            if (v != null) {
                return v > 0 ? Optional.of(v / 100.0) : Optional.empty();
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
        worker.pickUp(drops).get();     // over when they are picked up, or cannot be got to
        j.lying.addAll(drops);
    }

    // ------------------------------------------------------------------
    // Bits
    // ------------------------------------------------------------------

    /** Leaves an enemy alone a while ({@link #PASS_MS}). */
    private void pass(Job j, int id, String why) {
        long now = System.currentTimeMillis();
        j.passed.values().removeIf(t -> t < now);
        j.passedAt.keySet().retainAll(j.passed.keySet());
        j.passed.put(id, now + PASS_MS);
        entities.positionOf(id).ifPresent(at -> j.passedAt.put(id, at));
        log.info("auto-fighter: {} ({}) left alone for {} s: {}", entities.labelOf(id), id, PASS_MS / 1000, why);
    }

    /**
     * Whether the eyes see the entity: a line from them to its middle, or to its head, that no solid
     * block cuts. A block not known (not loaded) does not cut it.
     */
    private boolean sees(int id, Vec3 at) {
        Vec3 eyes = eyes();
        if (eyes == null) {
            return true;
        }
        Box b = entities.boundsOf(id).orElse(null);
        double low = b == null ? at.y() + 0.9 : (b.min().y() + b.max().y()) / 2;
        double high = b == null ? at.y() + 1.6 : b.max().y() - 0.15;
        return clear(eyes, new Vec3(at.x(), low, at.z())) || clear(eyes, new Vec3(at.x(), high, at.z()));
    }

    /** No solid block between {@code a} and {@code b}, the cell {@code b} is in left out. */
    private boolean clear(Vec3 a, Vec3 b) {
        double dx = b.x() - a.x();
        double dy = b.y() - a.y();
        double dz = b.z() - a.z();
        int steps = Math.max(1, (int) Math.ceil(Math.sqrt(dx * dx + dy * dy + dz * dz) / 0.1));
        int ex = (int) Math.floor(b.x());
        int ey = (int) Math.floor(b.y());
        int ez = (int) Math.floor(b.z());
        int lx = Integer.MIN_VALUE;
        int ly = Integer.MIN_VALUE;
        int lz = Integer.MIN_VALUE;
        for (int i = 1; i < steps; i++) {
            double t = (double) i / steps;
            int x = (int) Math.floor(a.x() + dx * t);
            int y = (int) Math.floor(a.y() + dy * t);
            int z = (int) Math.floor(a.z() + dz * t);
            if (x == lx && y == ly && z == lz) {
                continue;
            }
            lx = x;
            ly = y;
            lz = z;
            if (x == ex && y == ey && z == ez) {
                break;
            }
            meridian.core.api.Block blk = world.blockAt(x, y, z);
            if (blk != null && blk.type() != null && !blk.isAir() && blk.type().isSolid()) {
                return false;
            }
        }
        return true;
    }

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
