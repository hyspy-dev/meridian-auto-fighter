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
            public CompletableFuture<Shot> shoot(int entity) {
                shooting = true;
                return ranged.shoot(entity).whenComplete((r, e) -> shooting = false).thenApply(r -> new Shot(r.projectile() != null,
                        r.status() == RangedCombat.Status.HIT, r.status() + ": " + r.reason()));
            }
        };
    }
}
