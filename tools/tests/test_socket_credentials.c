/* Hosted test of the actual production retry, with deterministic raw-syscall/usercopy stubs.
 * Run from repo root under Linux:
 * cc -D_GNU_SOURCE -O1 -g -ffunction-sections -fdata-sections \
 *   -Ivendor/tawcroot/include tools/tests/test_socket_credentials.c \
 *   -Wl,--gc-sections -o /tmp/test_socket_credentials && /tmp/test_socket_credentials
 * Unused production socket handlers are discarded by --gc-sections. */
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "../../vendor/tawcroot/src/syscalls_socket.c"

static int sends, maps, unmaps, copies, live, fail_map, fail_copy;
static long first_result, retry_result;
static unsigned char original[256], expected[256];
static struct tawc_msghdr original_header;
static tawc_identity virtual_identity;

void tawcroot_identity_get(tawc_identity *identity) { *identity = virtual_identity; }

long tawc_copy_from_guest(void *dst, size_t n, const void *src)
{
	++copies;
	if (fail_copy) return TAWC_EFAULT;
	assert(src == original && n <= sizeof original);
	memcpy(dst, src, n);
	return 0;
}

long tawcroot_raw_syscall(long nr, long a, long b, long c, long d, long e, long f)
{
	(void)d; (void)e; (void)f;
	if (nr == TAWC_SYS_getpid) return 4321;
	if (nr == TAWC_SYS_getuid) return 10209;
	if (nr == TAWC_SYS_getgid) return 10210;
	if (nr == TAWC_SYS_mmap) {
		++maps;
		if (fail_map) return TAWC_ENOMEM;
		assert(!live && b > 0 && b <= 65536);
		live = 1;
		return (long)malloc((size_t)b);
	}
	if (nr == TAWC_SYS_munmap) {
		assert(live);
		live = 0;
		++unmaps;
		free((void *)a);
		return 0;
	}
	assert(nr == TAWC_SYS_sendmsg);
	assert(a == 7 && c == 0x40);
	const struct tawc_msghdr *header = (const void *)b;
	assert(header->msg_name == original_header.msg_name);
	assert(header->msg_namelen == original_header.msg_namelen);
	assert(header->msg_iov == original_header.msg_iov);
	assert(header->msg_iovlen == original_header.msg_iovlen);
	assert(header->msg_controllen == original_header.msg_controllen);
	assert(header->msg_flags == original_header.msg_flags);
	assert(++sends <= 2);
	if (sends == 1) {
		assert(header->msg_control == (uint64_t)(uintptr_t)original);
		return first_result;
	}
	assert(live && header->msg_control != original_header.msg_control);
	assert(!memcmp((void *)(uintptr_t)header->msg_control, expected, (size_t)header->msg_controllen));
	return retry_result;
}

static void reset(size_t size)
{
	sends = maps = unmaps = copies = live = fail_map = fail_copy = 0;
	first_result = TAWC_EPERM;
	retry_result = 17;
	memset(original, 0xa5, sizeof original);
	memset(expected, 0xa5, sizeof expected);
	memset(&virtual_identity, 0, sizeof virtual_identity);
	original_header = (struct tawc_msghdr){
		.msg_name = 0x1234, .msg_namelen = 110, .msg_iov = 0x5678,
		.msg_iovlen = 2, .msg_control = (uint64_t)(uintptr_t)original,
		.msg_controllen = size, .msg_flags = 0x20,
	};
}

static void credentials(size_t offset, int pid, unsigned uid, unsigned gid, int translate)
{
	struct tawc_cmsghdr header = {28, SOL_SOCKET_LEVEL, SCM_CREDENTIALS_TYPE};
	struct tawc_ucred cred = {pid, uid, gid};
	memcpy(original + offset, &header, sizeof header);
	memcpy(original + offset + sizeof header, &cred, sizeof cred);
	memcpy(expected + offset, original + offset, 28);
	if (translate) {
		cred.uid = 10209;
		cred.gid = 10210;
		memcpy(expected + offset + sizeof header, &cred, sizeof cred);
	}
}

static void check(const char *name, long result, int expected_sends, int expected_maps)
{
	struct tawc_msghdr header = original_header;
	unsigned char before[sizeof original];
	memcpy(before, original, sizeof original);
	assert(sendmsg_with_credentials(7, &header, 0x40) == result);
	assert(sends == expected_sends && maps == expected_maps);
	assert(!memcmp(before, original, sizeof original));
	assert(!memcmp(&header, &original_header, sizeof header));
	assert(!live && unmaps == maps - fail_map);
	printf("PASS %s\n", name);
}

int main(void)
{
	reset(32); credentials(0, 4321, 0, 0, 1); first_result = 17;
	check("successful normal send never scans or allocates", 17, 1, 0); assert(copies == 0);
	reset(32); credentials(0, 4321, 0, 0, 1); first_result = TAWC_EINVAL;
	check("non-EPERM error passes through", TAWC_EINVAL, 1, 0);
	reset(32); credentials(0, 4321, 0, 0, 1);
	check("own virtual root retries exactly once with host IDs", 17, 2, 1);
	reset(28); credentials(0, 4321, 0, 0, 1);
	check("unpadded final credential is valid", 17, 2, 1);
	reset(32); credentials(0, 4321, 0, 0, 1); retry_result = TAWC_EPERM;
	check("retry failure preserves error and frees mapping", TAWC_EPERM, 2, 1);
	reset(32); credentials(0, 4321, 0, 0, 1); fail_map = 1;
	check("allocation failure retains original error", TAWC_EPERM, 1, 1);
	reset(32); credentials(0, 4321, 0, 0, 1); fail_copy = 1;
	check("invalid guest pointer frees mapping without retry", TAWC_EPERM, 1, 1);
	reset(65537);
	check("over-limit control never allocates or reads", TAWC_EPERM, 1, 0);
	reset(15);
	check("short control never allocates or reads", TAWC_EPERM, 1, 0);
	reset(32); credentials(0, 4322, 0, 0, 0);
	check("foreign PID never translated", TAWC_EPERM, 1, 1);
	reset(32); credentials(0, 4321, 1000, 0, 0);
	check("foreign UID never translated", TAWC_EPERM, 1, 1);
	reset(32); credentials(0, 4321, 0, 1000, 0);
	check("foreign GID never translated", TAWC_EPERM, 1, 1);
	reset(32); credentials(0, 4321, 1001, 1002, 1);
	virtual_identity.suid = 1001; virtual_identity.egid = 1002;
	check("own virtual saved UID and effective GID translate", 17, 2, 1);
	reset(32); credentials(0, 4321, 10209, 10210, 0);
	virtual_identity.ruid = 10209; virtual_identity.rgid = 10210;
	check("already-kernel credentials do not retry", TAWC_EPERM, 1, 1);
	reset(56); credentials(24, 4321, 0, 0, 1);
	struct tawc_cmsghdr rights = {20, SOL_SOCKET_LEVEL, 1};
	int descriptor = 83;
	memcpy(original, &rights, sizeof rights); memcpy(original + 16, &descriptor, sizeof descriptor);
	memcpy(expected, original, 24);
	check("SCM_RIGHTS and padding remain byte-for-byte intact", 17, 2, 1);
	reset(32); credentials(0, 4321, 0, 0, 1);
	((struct tawc_cmsghdr *)original)->len = UINT64_MAX;
	check("overflowing cmsg length cannot wrap", TAWC_EPERM, 1, 1);
	reset(32); credentials(0, 4321, 0, 0, 1);
	((struct tawc_cmsghdr *)original)->len = 15;
	check("cmsg shorter than header rejected", TAWC_EPERM, 1, 1);
	reset(32); credentials(0, 4321, 0, 0, 1);
	((struct tawc_cmsghdr *)original)->len = 29;
	check("wrong credential size rejected", TAWC_EPERM, 1, 1);
	reset(48); credentials(0, 4321, 0, 0, 1);
	struct tawc_cmsghdr broken = {17, SOL_SOCKET_LEVEL, 1};
	memcpy(original + 32, &broken, sizeof broken);
	check("malformed tail cancels entire retry", TAWC_EPERM, 1, 1);
	puts("19 credential retry checks passed");
	return 0;
}
