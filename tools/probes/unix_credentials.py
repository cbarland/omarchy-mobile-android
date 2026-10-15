#!/usr/bin/env python3
"""Run inside the guest: report real UNIX ancillary credential transport.

No settings or privileges change. A forked sender and receiver communicate only
over an anonymous socketpair. --expect-translated checks the proposed outbound
adapter while still requiring the receiver to see the unchanged kernel identity.
"""
import argparse
import array
import errno
import json
import os
import socket
import struct


UCRED = struct.Struct("=iII")


def receive(sock):
    data, ancillary, flags, _ = sock.recvmsg(4096, 512)
    result = {"label": data.decode(), "flags": flags, "credentials": [], "rights": []}
    for level, kind, value in ancillary:
        if (level, kind) == (socket.SOL_SOCKET, socket.SCM_CREDENTIALS):
            result["credentials"].append(list(UCRED.unpack(value)))
        elif (level, kind) == (socket.SOL_SOCKET, socket.SCM_RIGHTS):
            fds = array.array("i")
            fds.frombytes(value[:len(value) - len(value) % fds.itemsize])
            for fd in fds:
                try:
                    result["rights"].append(os.read(fd, 4096).decode())
                finally:
                    os.close(fd)
    return result


def sender(sock, report_fd):
    result = {"pid": os.getpid(), "virtual_uid": os.getuid(), "virtual_gid": os.getgid(), "attempts": []}
    try:
        sock.send(b"implicit")
        kernel_pid, kernel_uid, kernel_gid = json.loads(sock.recv(4096))
        result["kernel_identity"] = [kernel_pid, kernel_uid, kernel_gid]
        cases = [
            ("explicit_virtual", UCRED.pack(os.getpid(), os.getuid(), os.getgid()), False),
            ("explicit_kernel", UCRED.pack(os.getpid(), kernel_uid, kernel_gid), False),
            ("wrong_pid", UCRED.pack(os.getppid(), kernel_uid, kernel_gid), False),
            ("invalid_uid", UCRED.pack(os.getpid(), 0xffffffff, kernel_gid), False),
            ("short_credentials", b"abc", False),
            ("rights_only", None, True),
            ("virtual_with_rights", UCRED.pack(os.getpid(), os.getuid(), os.getgid()), True),
        ]
        for label, credentials, rights in cases:
            ancillary = []
            read_fd = None
            if credentials is not None:
                ancillary.append((socket.SOL_SOCKET, socket.SCM_CREDENTIALS, credentials))
            if rights:
                read_fd, write_fd = os.pipe()
                try:
                    os.write(write_fd, label.encode())
                finally:
                    os.close(write_fd)
                ancillary.append((socket.SOL_SOCKET, socket.SCM_RIGHTS, array.array("i", [read_fd])))
            attempt = {"label": label}
            try:
                attempt["bytes"] = sock.sendmsg([label.encode()], ancillary)
            except OSError as error:
                attempt.update(errno=error.errno, error=error.strerror)
            finally:
                if read_fd is not None:
                    os.close(read_fd)
            result["attempts"].append(attempt)
    except Exception as error:
        result["error"] = repr(error)
    finally:
        os.write(report_fd, json.dumps(result).encode())
        os.close(report_fd)
        sock.close()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--expect-translated", action="store_true")
    args = parser.parse_args()
    receiver, child_sock = socket.socketpair(socket.AF_UNIX, socket.SOCK_DGRAM)
    receiver.setsockopt(socket.SOL_SOCKET, socket.SO_PASSCRED, 1)
    receiver.settimeout(5)
    child_sock.settimeout(5)
    read_fd, report_fd = os.pipe()
    child = os.fork()
    if child == 0:
        receiver.close()
        os.close(read_fd)
        sender(child_sock, report_fd)
        os._exit(0)
    child_sock.close()
    os.close(report_fd)
    try:
        implicit = receive(receiver)
        receiver.send(json.dumps(implicit["credentials"][0]).encode())
        with os.fdopen(read_fd, "rb") as report:
            sent = json.load(report)
        _, status = os.waitpid(child, 0)
        child = 0
        attempts = {attempt["label"]: attempt for attempt in sent.get("attempts", [])}
        received = [implicit]
        for attempt in attempts.values():
            if "bytes" in attempt:
                received.append(receive(receiver))
        result = {"sender": sent, "received": received, "child_status": status}
        checks = {
            "sender_completed": status == 0 and "error" not in sent,
            "pid_is_real": implicit["credentials"][0][0] == sent["pid"],
            "kernel_identity_send_works": "bytes" in attempts.get("explicit_kernel", {}),
            "wrong_pid_rejected": attempts.get("wrong_pid", {}).get("errno") == errno.EPERM,
            "invalid_uid_rejected": attempts.get("invalid_uid", {}).get("errno") == errno.EINVAL,
            "short_credentials_rejected": attempts.get("short_credentials", {}).get("errno") == errno.EINVAL,
            "rights_payload_intact": any(item["label"] == "rights_only" and item["rights"] == ["rights_only"] for item in received),
            "receiver_sees_kernel_identity": all(item["credentials"] == implicit["credentials"] for item in received),
        }
        if args.expect_translated:
            checks["explicit_virtual_accepted"] = "bytes" in attempts.get("explicit_virtual", {})
            checks["virtual_credentials_and_rights_accepted"] = any(
                item["label"] == "virtual_with_rights" and item["rights"] == ["virtual_with_rights"] for item in received)
        result["checks"] = checks
        print(json.dumps(result, indent=2, sort_keys=True))
        return 0 if all(checks.values()) else 1
    finally:
        receiver.close()
        if child:
            try:
                os.kill(child, 9)
            except ProcessLookupError:
                pass
            os.waitpid(child, 0)


if __name__ == "__main__":
    raise SystemExit(main())
