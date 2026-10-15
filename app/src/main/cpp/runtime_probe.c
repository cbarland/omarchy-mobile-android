/* Debug-only Linux guest. This is a compatibility fixture, not an Arch image. */
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

int main(int argc, char **argv) {
    char buf[128] = {0};
    int fd = open("/etc/omamobile-probe", O_RDONLY);
    if (fd < 0 || read(fd, buf, sizeof(buf) - 1) <= 0) return 20;
    close(fd);
    if (strcmp(buf, "guest-root\n")) return 21;
    fd = open("/android/shared/from-linux.txt", O_WRONLY | O_CREAT | O_TRUNC, 0600);
    if (fd < 0 || write(fd, "shared-runtime\n", 15) != 15) return 22;
    close(fd);
    errno = 0;
    fd = open("/android/readonly/forbidden", O_WRONLY | O_CREAT, 0600);
    if (fd >= 0 || errno != EROFS) return 23;
    printf("ROOTFS_OK BIND_RW_OK BIND_RO_OK guest_uid=%d\n", getuid());
    fflush(stdout);
    if (argc > 1 && !strcmp(argv[1], "--android")) {
        execl("/opt/omamobile/bin/ando", "ando", "/system/bin/id", (char *)0);
        perror("ando exec");
        return 24;
    }
    return 0;
}
