"""Forward the existing owner client ports to one explicitly configured backend."""

import argparse
import selectors
import socket
import socketserver
import threading


class OwnerGateway(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True

    def __init__(self, address, backend, slots):
        self.backend = (backend, address[1])
        self.slots = slots
        super().__init__(address, GatewayHandler)

    def process_request(self, request, client_address):
        if not self.slots.acquire(blocking=False):
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, client_address)
        except BaseException:
            self.slots.release()
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self.slots.release()


class GatewayHandler(socketserver.BaseRequestHandler):
    def handle(self):
        try:
            with socket.create_connection(self.server.backend, timeout=5) as upstream:
                self.request.settimeout(5)
                with selectors.DefaultSelector() as selector:
                    selector.register(self.request, selectors.EVENT_READ, upstream)
                    selector.register(upstream, selectors.EVENT_READ, self.request)
                    while events := selector.select(timeout=120):
                        for key, _ in events:
                            data = key.fileobj.recv(65536)
                            if not data:
                                return
                            key.data.sendall(data)
        except OSError:
            return


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--listen", required=True)
    parser.add_argument("--backend", required=True)
    args = parser.parse_args()
    slots = threading.BoundedSemaphore(32)
    with OwnerGateway((args.listen, 13302), args.backend, slots) as game:
        with OwnerGateway((args.listen, 7304), args.backend, slots) as cache:
            threading.Thread(target=cache.serve_forever, daemon=True).start()
            print(f"Owner gateway ready on {args.listen}:13302 and :7304", flush=True)
            game.serve_forever()


if __name__ == "__main__":
    main()
