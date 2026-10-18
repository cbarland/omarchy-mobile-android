package dev.omamobile.host;

import java.util.Objects;
import java.util.Optional;

/** Thread-confined ownership of the app's two compositor output roles. */
public final class OutputOwnership {
    public enum Role {
        PHONE(0), DESKTOP(1);

        private final int id;

        Role(int id) { this.id = id; }

        public int id() { return id; }
    }

    public record Lease(Role role, Object owner, long generation, int androidDisplayId) {
        public Lease {
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(owner, "owner");
            if (generation < 0) throw new IllegalArgumentException("generation must be nonnegative");
            if (androidDisplayId < 0) throw new IllegalArgumentException("display id must be nonnegative");
        }
    }

    private static final class Slot {
        private long generation;
        private Lease lease;
    }

    private final Slot[] slots = { new Slot(), new Slot() };

    /** Claims a role, preserving its generation only for the same owner object and display. */
    public Lease claim(Role role, Object owner, int androidDisplayId) {
        Objects.requireNonNull(owner, "owner");
        if (androidDisplayId < 0) throw new IllegalArgumentException("display id must be nonnegative");
        Slot slot = slot(role);
        Lease current = slot.lease;
        if (current != null && current.owner() == owner && current.androidDisplayId() == androidDisplayId) {
            return current;
        }
        long generation = nextGeneration(slot);
        Lease replacement = new Lease(role, owner, generation, androidDisplayId);
        slot.generation = generation;
        slot.lease = replacement;
        return replacement;
    }

    /** Releases only the lease that still owns its role. */
    public boolean release(Lease lease) {
        if (lease == null) return false;
        Slot slot = slot(lease.role());
        Lease current = slot.lease;
        if (!matches(current, lease)) return false;
        slot.lease = null;
        return true;
    }

    /** Invalidates any lease and advances the epoch for a physically removed display. */
    public long retire(Role role) {
        Slot slot = slot(role);
        long generation = nextGeneration(slot);
        slot.generation = generation;
        slot.lease = null;
        return generation;
    }

    public Optional<Lease> current(Role role) {
        return Optional.ofNullable(slot(role).lease);
    }

    /** Returns the last assigned epoch; releasing a lease does not advance it. */
    public long generation(Role role) {
        return slot(role).generation;
    }

    public boolean isCurrent(Lease lease) {
        if (lease == null) return false;
        return matches(slot(lease.role()).lease, lease);
    }

    /** Epoch equality remains true after release and becomes false after the next claim or retirement. */
    public boolean isGenerationCurrent(Role role, long generation) {
        return generation >= 0 && slot(role).generation == generation;
    }

    private static boolean matches(Lease current, Lease candidate) {
        return current != null
                && current.role() == candidate.role()
                && current.owner() == candidate.owner()
                && current.generation() == candidate.generation()
                && current.androidDisplayId() == candidate.androidDisplayId();
    }

    private Slot slot(Role role) {
        return slots[Objects.requireNonNull(role, "role").id()];
    }

    private static long nextGeneration(Slot slot) {
        return Math.incrementExact(slot.generation);
    }
}
