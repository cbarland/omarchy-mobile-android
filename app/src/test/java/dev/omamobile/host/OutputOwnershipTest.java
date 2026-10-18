package dev.omamobile.host;

import static org.junit.Assert.*;

import org.junit.Test;

public final class OutputOwnershipTest {
    @Test public void replacingOwnerMakesOldDetachHarmless() {
        OutputOwnership ownership = new OutputOwnership();
        Object oldOwner = new Object();
        Object newOwner = new Object();
        OutputOwnership.Lease oldLease = ownership.claim(OutputOwnership.Role.DESKTOP, oldOwner, 4);
        OutputOwnership.Lease newLease = ownership.claim(OutputOwnership.Role.DESKTOP, newOwner, 4);

        assertFalse(ownership.release(oldLease));
        assertTrue(ownership.isCurrent(newLease));
        assertEquals(newLease, ownership.current(OutputOwnership.Role.DESKTOP).orElseThrow());
    }

    @Test public void retryForSameOwnerAndDisplayPreservesLeaseAndGeneration() {
        OutputOwnership ownership = new OutputOwnership();
        Object owner = new Object();
        OutputOwnership.Lease first = ownership.claim(OutputOwnership.Role.PHONE, owner, 0);

        assertSame(first, ownership.claim(OutputOwnership.Role.PHONE, owner, 0));
        assertEquals(first.generation(), ownership.generation(OutputOwnership.Role.PHONE));
    }

    @Test public void releaseThenReclaimAdvancesEvenForSameOwner() {
        OutputOwnership ownership = new OutputOwnership();
        Object owner = new Object();
        OutputOwnership.Lease first = ownership.claim(OutputOwnership.Role.PHONE, owner, 0);

        assertTrue(ownership.release(first));
        assertTrue(ownership.current(OutputOwnership.Role.PHONE).isEmpty());
        assertTrue(ownership.isGenerationCurrent(OutputOwnership.Role.PHONE, first.generation()));
        OutputOwnership.Lease second = ownership.claim(OutputOwnership.Role.PHONE, owner, 0);
        assertTrue(second.generation() > first.generation());
    }

    @Test public void displayChangeReplacesLeaseAndAdvancesGeneration() {
        OutputOwnership ownership = new OutputOwnership();
        Object owner = new Object();
        OutputOwnership.Lease first = ownership.claim(OutputOwnership.Role.DESKTOP, owner, 2);

        OutputOwnership.Lease second = ownership.claim(OutputOwnership.Role.DESKTOP, owner, 7);
        assertTrue(second.generation() > first.generation());
        assertFalse(ownership.isCurrent(first));
        assertEquals(7, second.androidDisplayId());
    }

    @Test public void retirementInvalidatesLeasesAndEpochAcksOnlyForThatRole() {
        OutputOwnership ownership = new OutputOwnership();
        OutputOwnership.Lease phone = ownership.claim(OutputOwnership.Role.PHONE, new Object(), 0);
        OutputOwnership.Lease desktop = ownership.claim(OutputOwnership.Role.DESKTOP, new Object(), 3);

        long retiredGeneration = ownership.retire(OutputOwnership.Role.DESKTOP);
        assertFalse(ownership.isCurrent(desktop));
        assertFalse(ownership.isGenerationCurrent(OutputOwnership.Role.DESKTOP, desktop.generation()));
        assertTrue(ownership.isGenerationCurrent(OutputOwnership.Role.DESKTOP, retiredGeneration));
        assertTrue(ownership.isCurrent(phone));
        OutputOwnership.Lease replacement = ownership.claim(OutputOwnership.Role.DESKTOP, new Object(), 3);
        assertTrue(replacement.generation() > retiredGeneration);
    }

    @Test public void equalOwnersDoNotShareIdentityOwnership() {
        OutputOwnership ownership = new OutputOwnership();
        EqualOwner firstOwner = new EqualOwner();
        EqualOwner equalOwner = new EqualOwner();
        assertEquals(firstOwner, equalOwner);

        OutputOwnership.Lease first = ownership.claim(OutputOwnership.Role.PHONE, firstOwner, 0);
        OutputOwnership.Lease second = ownership.claim(OutputOwnership.Role.PHONE, equalOwner, 0);
        assertFalse(ownership.isCurrent(first));
        assertSame(equalOwner, second.owner());
        assertFalse(ownership.release(first));
        assertTrue(ownership.isCurrent(second));
    }

    @Test public void invalidInputsAreRejectedAtTheBoundary() {
        OutputOwnership ownership = new OutputOwnership();
        assertThrows(NullPointerException.class,
                () -> ownership.claim(OutputOwnership.Role.PHONE, null, 0));
        assertThrows(NullPointerException.class,
                () -> ownership.claim(null, new Object(), 0));
        assertThrows(IllegalArgumentException.class,
                () -> ownership.claim(OutputOwnership.Role.PHONE, new Object(), -1));
        assertThrows(IllegalArgumentException.class,
                () -> new OutputOwnership.Lease(OutputOwnership.Role.PHONE, new Object(), -1, 0));
        assertFalse(ownership.isGenerationCurrent(OutputOwnership.Role.PHONE, -1));
    }

    private static final class EqualOwner {
        @Override public boolean equals(Object other) { return other instanceof EqualOwner; }
        @Override public int hashCode() { return 1; }
    }
}
