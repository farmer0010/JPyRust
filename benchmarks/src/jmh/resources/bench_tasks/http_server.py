import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def spin(n):
    total = 0
    for i in range(n):
        total += i * i
    return str(total).encode()


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_POST(self):
        body = self.rfile.read(int(self.headers["Content-Length"]))
        if self.path.startswith("/spin/"):
            body = spin(int(self.path.split("/")[2]))
        self.send_response(200)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
print(server.server_address[1], flush=True)
server.serve_forever()
