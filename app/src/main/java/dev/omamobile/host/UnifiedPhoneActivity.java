package dev.omamobile.host;

/** Phone task for the opt-in shared compositor preview. */
public final class UnifiedPhoneActivity extends CompositorActivity {
    @Override protected boolean phone() { return true; }
    @Override protected CompositorEndpoint endpoint() {
        return UnifiedCompositorController.get(this).endpoint(OutputOwnership.Role.PHONE);
    }
}
