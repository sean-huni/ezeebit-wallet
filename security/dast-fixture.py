"""The DAST positive control: a stub with one planted defect per scanner, so a scanner that cannot find it fails the gate.

ZAP: a quote in any input answers a MySQL syntax error (SQL injection, High). Schemathesis: balances answers 500.
Nuclei: /actuator/env is exposed. Serves the public specs' paths under /api, on loopback only.
"""
import json
import re
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import unquote

SQL_ERROR = ("You have an error in your SQL syntax; check the manual that corresponds to your MySQL server version "
             "for the right syntax to use near ''' at line 1")
MERCHANT = re.compile(r"^/api/v1/merchants/[^/]+/(balances|entries|quotes|conversions)(/[^/?]+)?(\?.*)?$")


class Server(ThreadingHTTPServer):
    # The default backlog of 5 refuses connections under a scanner's concurrency, which reads as a dead target.
    request_queue_size = 256


class Fixture(BaseHTTPRequestHandler):
    # A scanner's malformed raw request can leave a read waiting for bytes that never come.
    timeout = 5

    def _send(self, status, body, content_type="application/json"):
        data = body if isinstance(body, bytes) else body.encode()
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _answer(self, body=b""):
        path = self.path
        if path == "/actuator/health":
            return self._send(200, '{"status":"UP"}')
        if path == "/actuator/env":
            return self._send(200, json.dumps({"activeProfiles": ["dev"], "propertySources": [
                {"name": "server.ports", "properties": {"local.server.port": {"value": 8080}}}]}),
                "application/vnd.spring-boot.actuator.v3+json")
        match = MERCHANT.match(path)
        if not match:
            return self._send(404, '{"code":"NOT_FOUND"}')
        if "'" in unquote(path) or b"'" in body:
            return self._send(500, SQL_ERROR, "text/html")
        if match.group(1) == "balances":
            return self._send(500, '{"code":"PLANTED_SERVER_ERROR"}')
        return self._send(200 if self.command == "GET" else 201, '{"data":[]}')

    def do_GET(self):
        self._answer()

    def do_POST(self):
        self._answer(self.rfile.read(int(self.headers.get("Content-Length") or 0)))

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    server = Server(("127.0.0.1", int(sys.argv[1])), Fixture)
    print(f"fixture listening on 127.0.0.1:{server.server_port}", flush=True)
    server.serve_forever()
