package dev.omamobile.host;

/** Bound lifetime anchor for the phone compositor process. */
public final class PhoneCompositorOwnershipService extends CompositorOwnershipService {
    @Override protected boolean phone() { return true; }
}
