package meridian.autofighter;

import java.util.concurrent.CompletableFuture;

/** Optional ranged service, isolated so an older core can still load the fighter. */
interface BowSupport {
    record Shot(boolean sent, boolean hit, String reason) { }
    int slot();
    default void cancel() { }
    CompletableFuture<Shot> shoot(int entity);
    /** {@code hardest}: the bow's full draw, where the shot is to count for all it can. */
    default CompletableFuture<Shot> shoot(int entity, boolean hardest) { return shoot(entity); }
    BowSupport NONE = new BowSupport() {
        public int slot() { return -1; }
        public CompletableFuture<Shot> shoot(int entity) {
            return CompletableFuture.completedFuture(new Shot(false, false, "Ranged API unavailable"));
        }
    };
}
