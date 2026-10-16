#!/usr/bin/env python3
"""Broadcast line messages between the phone and Wear OS Android emulators."""
from __future__ import annotations

import socket
import threading

HOST = "0.0.0.0"
PORT = 8767
clients: set[socket.socket] = set()
lock = threading.Lock()


def broadcast(line: bytes, sender: socket.socket) -> None:
    stale: list[socket.socket] = []
    with lock:
        peers = tuple(clients)
    for peer in peers:
        if peer is sender:
            continue
        try:
            peer.sendall(line)
        except OSError:
            stale.append(peer)
    if stale:
        with lock:
            for peer in stale:
                clients.discard(peer)
                try:
                    peer.close()
                except OSError:
                    pass


def client_loop(conn: socket.socket, address: tuple[str, int]) -> None:
    with lock:
        clients.add(conn)
    try:
        buffer = b""
        while True:
            chunk = conn.recv(4096)
            if not chunk:
                break
            buffer += chunk
            while b"\n" in buffer:
                line, buffer = buffer.split(b"\n", 1)
                if line:
                    broadcast(line + b"\n", conn)
    except OSError:
        pass
    finally:
        with lock:
            clients.discard(conn)
        try:
            conn.close()
        except OSError:
            pass


def main() -> None:
    with socket.create_server((HOST, PORT), reuse_port=False) as server:
        print(f"워치짐 emulator relay listening on {HOST}:{PORT}", flush=True)
        while True:
            conn, address = server.accept()
            threading.Thread(target=client_loop, args=(conn, address), daemon=True).start()


if __name__ == "__main__":
    main()
