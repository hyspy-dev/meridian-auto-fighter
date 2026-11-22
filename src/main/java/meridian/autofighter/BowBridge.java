package meridian.autofighter;

import java.util.concurrent.CompletableFuture;
import meridian.api.module.ModuleContext;
import meridian.core.api.RangedCombat;

/** Loaded only inside a LinkageError guard: the ranged API may not exist on an older core. */
final class BowBridge {
    private BowBridge() { }
    static BowSupport connect(ModuleContext ctx) {
        RangedCombat ranged = ctx.services().get(RangedCombat.class).orElse(null);
        if (ranged == null) return BowSupport.NONE;
        return new BowSupport() {
            private volatile boolean shooting;
            public void cancel() { if (shooting) ranged.cancel(); }
            public int slot() { return ranged.bowSlot().orElse(-1); }
            public CompletableFuture<Shot> shoot(int entity) { return shoot(entity, false); }
            public CompletableFuture<Shot> shoot(int entity, boolean hardest) {
                shooting = true;
                CompletableFuture<RangedCombat.Shot> shot;
                try {
                    shot = hardest ? ranged.shoot(entity, true) : ranged.shoot(entity);
                } catch (LinkageError olderCore) {
                    shot = ranged.shoot(entity);
                }
                return shot.whenComplete((r, e) -> shooting = false).thenApply(r -> new Shot(r.projectile() != null,
                        r.status() == RangedCombat.Status.HIT, r.status() + ": " + r.reason()
                        + (r.projectile() == null ? "" : String.format(java.util.Locale.ROOT, " (drawn %.2f s, flew %.2f s, damage seen %.1f)",
                                r.chargeSeconds(), r.flightSeconds(), r.damage()))));
            }
        };
    }
}
