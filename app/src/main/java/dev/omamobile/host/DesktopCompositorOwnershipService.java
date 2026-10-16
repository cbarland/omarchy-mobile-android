package dev.omamobile.host;

/** Bound lifetime anchor for the external-display compositor process. */
public final class DesktopCompositorOwnershipService extends CompositorOwnershipService {
    @Override protected boolean phone() { return false; }
}
