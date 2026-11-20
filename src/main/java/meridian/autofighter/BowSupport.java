package meridian.autofighter;

import java.util.concurrent.CompletableFuture;

/** Optional ranged service, isolated so an older core can still load the fighter. */
interface BowSupport {
    record Shot(boolean sent, boolean hit, String reason) { }
    int slot();
    default void cancel() { }
    CompletableFuture<Shot> shoot(int entity);
    BowSupport NONE = new BowSupport() {
        public int slot() { return -1; }
        public CompletableFuture<Shot> shoot(int entity) {
            return CompletableFuture.completedFuture(new Shot(false, false, "Ranged API unavailable"));
        }
    };
}
