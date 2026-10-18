package dev.omamobile.host;

/** External-display task for the opt-in shared compositor preview. */
public final class UnifiedDesktopActivity extends CompositorActivity {
    @Override protected boolean phone() { return false; }
    @Override protected CompositorEndpoint endpoint() {
        return UnifiedCompositorController.get(this).endpoint(OutputOwnership.Role.DESKTOP);
    }
}
