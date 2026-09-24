#!/usr/bin/env python3
"""Persistent rcon client for this mod's dev server. Python 3, standard library only.

One authenticated socket answers a command in well under a millisecond, where a fresh connection
per command costs hundreds. Used by torture.py and rig-sync.sh; run on its own it sends each
argument as a command and prints the reply:

    python3 scripts/mtxrcon.py [--run-dir DIR] "time query gametime" "list"

The run directory defaults to the main checkout's run/, found through git's common directory, so a
worktree reaches the one dev server there is. Port and password come from its server.properties.
"""

import argparse
import socket
import struct
import subprocess
import sys
from pathlib import Path

SERVERDATA_AUTH = 3
SERVERDATA_EXECCOMMAND = 2

# Vanilla's RconClient reads a request with one 1460-byte read and hangs up when that read does not
# hold exactly one packet, so a request must fit it and a second one must never be in flight.
MAX_REQUEST_BYTES = 1460

# A reply is cut into chunks of this many UTF-16 units, all under the request's id, with no
# terminator after the last.
REPLY_CHUNK = 4096

MARKER = "mtxrcon_end_of_reply"

# The server's socket keeps Nagle on, so its short reply to the marker waits for our ACK of the
# chunk before it, which the kernel otherwise delays by 40 ms. Linux only; re-armed after each read.
QUICKACK = getattr(socket, "TCP_QUICKACK", None)


class RconDead(Exception):
    """The server stopped answering: the socket closed, errored or timed out."""


class Rcon:
    """An authenticated rcon connection that sends one command at a time."""

    def __init__(self, port, password, host="127.0.0.1", timeout=30.0):
        try:
            self.sock = socket.create_connection((host, port), timeout=timeout)
            self.sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        except OSError as e:
            raise RconDead(f"cannot connect to rcon on {host}:{port}: {e}") from e
        self.next_id = 0
        auth_id = self._send(SERVERDATA_AUTH, password)
        reply_id, _ = self._recv()
        if reply_id != auth_id:
            self.close()
            raise RconDead("rcon authentication failed")

    def cmd(self, command):
        """Runs one command and returns its whole reply."""
        request_id = self._send(SERVERDATA_EXECCOMMAND, command)
        reply_id, text = self._recv()
        if reply_id != request_id:
            raise RconDead(f"reply for request {reply_id}, expected {request_id}")
        if utf16_len(text) < REPLY_CHUNK:
            return text

        # A full chunk may or may not be the last one. The server is still writing the rest and
        # reads nothing until it has, so a marker sent now is the only request it will find, and
        # its reply comes after every chunk of this one.
        parts = [text]
        marker_id = self._send(SERVERDATA_EXECCOMMAND, MARKER)
        while True:
            reply_id, text = self._recv()
            if reply_id == marker_id:
                return "".join(parts)
            if reply_id == request_id:
                parts.append(text)

    def close(self):
        """Closes the socket; the server ends its client thread when it sees the close."""
        try:
            self.sock.close()
        except OSError:
            pass

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()

    def _send(self, kind, payload):
        self.next_id += 1
        body = struct.pack("<ii", self.next_id, kind) + payload.encode("utf-8") + b"\0\0"
        packet = struct.pack("<i", len(body)) + body
        if len(packet) > MAX_REQUEST_BYTES:
            raise ValueError(f"command of {len(packet)} bytes will not fit vanilla's "
                             f"{MAX_REQUEST_BYTES}-byte read: {payload[:80]}...")
        try:
            self.sock.sendall(packet)
        except OSError as e:
            raise RconDead(f"rcon send failed: {e}") from e
        return self.next_id

    def _recv(self):
        (length,) = struct.unpack("<i", self._read(4))
        body = self._read(length)
        reply_id, _ = struct.unpack("<ii", body[:8])
        return reply_id, body[8:-2].decode("utf-8", "replace")

    def _read(self, count):
        data = b""
        while len(data) < count:
            try:
                chunk = self.sock.recv(count - len(data))
            except OSError as e:
                # A timeout mid-reply leaves the stream unframed, so the connection is done either way.
                raise RconDead(f"rcon read failed: {e}") from e
            if not chunk:
                raise RconDead("rcon connection closed")
            data += chunk
            if QUICKACK is not None:
                self.sock.setsockopt(socket.IPPROTO_TCP, QUICKACK, 1)
        return data


def utf16_len(text):
    """Length in UTF-16 units, the measure Java's String.length() cuts the reply by."""
    return len(text.encode("utf-16-le")) // 2


def main_checkout():
    """The main checkout of this repository, even when called from one of its worktrees."""
    here = Path(__file__).resolve().parent
    common = subprocess.run(
        ["git", "-C", str(here), "rev-parse", "--path-format=absolute", "--git-common-dir"],
        check=True, capture_output=True, text=True).stdout.strip()
    return Path(common).parent


def default_run_dir():
    """The main checkout's run/, where the dev server keeps its world, logs and properties."""
    return main_checkout() / "run"


def read_properties(run_dir):
    """server.properties as a dict of strings."""
    props = {}
    for line in (Path(run_dir) / "server.properties").read_text().splitlines():
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            props[key.strip()] = value.strip()
    return props


def connect(run_dir, timeout=30.0):
    """An authenticated connection to the dev server that owns run_dir."""
    props = read_properties(run_dir)
    if props.get("enable-rcon") != "true":
        raise RconDead(f"rcon is not enabled in {Path(run_dir) / 'server.properties'}")
    return Rcon(int(props["rcon.port"]), props["rcon.password"], timeout=timeout)


def main():
    parser = argparse.ArgumentParser(description="Send commands to the dev server over rcon.")
    parser.add_argument("--run-dir", type=Path, default=None,
                        help="the server's run/ directory (default: the main checkout's)")
    parser.add_argument("commands", nargs="+", help="commands to send, in order")
    args = parser.parse_args()

    run_dir = args.run_dir or default_run_dir()
    try:
        with connect(run_dir) as rcon:
            for command in args.commands:
                print(rcon.cmd(command))
    except RconDead as e:
        print(f"mtxrcon: {e}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
