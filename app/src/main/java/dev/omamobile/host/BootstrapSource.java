package dev.omamobile.host;

import java.io.IOException;

/** Official bootstrap policies. Signer rotation requires an explicit app update. */
enum BootstrapSource {
    ARCH_ARM(183, "https://fl.us.mirror.archlinuxarm.org/os/ArchLinuxARM-aarch64-latest.tar.gz",
            "68B3537F39A313B3E574D06777193F152BDBE6A6", R.raw.archlinuxarm_signing_key,
            RootfsImporter.Layout.ROOT),
    ARCH_X86(62, "https://geo.mirror.pkgbuild.com/iso/latest/archlinux-bootstrap-x86_64.tar.zst",
            "3E80CA1A8B89F69CBA57D98A76A5EF9054449A5C", R.raw.arch_signing_key,
            RootfsImporter.Layout.ARCH_BOOTSTRAP);

    final int machine;
    final String url;
    final String fingerprint;
    final int keyResource;
    final RootfsImporter.Layout layout;

    BootstrapSource(int machine, String url, String fingerprint, int keyResource, RootfsImporter.Layout layout) {
        this.machine = machine;
        this.url = url;
        this.fingerprint = fingerprint;
        this.keyResource = keyResource;
        this.layout = layout;
    }

    static BootstrapSource forMachine(int machine) throws IOException {
        for (BootstrapSource source : values()) if (source.machine == machine) return source;
        throw new IOException("No authenticated bootstrap policy for architecture " + machine);
    }
}
