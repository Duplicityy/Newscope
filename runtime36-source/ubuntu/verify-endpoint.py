"""Check the Newscape game and cache handshakes without creating a login session."""

import argparse
import socket
import time


def verify(host):
    for port, payload in (
        (13302, b"\x0e" + bytes(8)),
        (7304, b"\x0f" + (184729).to_bytes(4, "big")),
    ):
        with socket.create_connection((host, port), timeout=5) as connection:
            connection.sendall(payload)
            response = connection.recv(1)
        if response != b"\x00":
            raise RuntimeError(f"{host}:{port} returned {response.hex() or 'EOF'}")
        print(f"Handshake passed on {host}:{port}", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("host")
    parser.add_argument("--attempts", type=int, default=1, choices=range(1, 61))
    args = parser.parse_args()
    for attempt in range(1, args.attempts + 1):
        try:
            verify(args.host)
            return
        except (OSError, RuntimeError) as error:
            if attempt == args.attempts:
                raise SystemExit(str(error)) from error
            time.sleep(2)


if __name__ == "__main__":
    main()
