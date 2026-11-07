"""Temporary HTTP ingress gate inside the legacy app's network namespace.

The initial adoption executor owns this child. No business request is replayed.
Existing TCP connections retain their NAT mapping and must drain before switch.
Only health requests are forwarded to the old backend's loopback listener.
"""
import http.server
import json
import os
import sys
import urllib.request
from pathlib import Path


class Gate(http.server.BaseHTTPRequestHandler):
    def log_message(self,*_):pass
    def respond(self):
        status=503;body=b'{"success":false,"data":null,"message":"Maintenance; retry later","timestamp":null}'
        if self.command in {'GET','HEAD'} and self.path.split('?',1)[0] in {'/actuator/health','/actuator/health/liveness','/actuator/health/readiness'}:
            try:
                with urllib.request.urlopen('http://127.0.0.1:8080'+self.path,timeout=5) as response:
                    status=response.status;body=response.read(16384)
            except Exception:status=503;body=b'{"status":"DOWN"}'
        self.send_response(status);self.send_header('Content-Type','application/json')
        self.send_header('Content-Length',str(len(body)));self.send_header('Connection','close')
        self.send_header('Cache-Control','no-store')
        if status==503:self.send_header('Retry-After','30')
        self.end_headers()
        if self.command!='HEAD':self.wfile.write(body)
        self.close_connection=True
    do_GET=respond;do_HEAD=respond;do_POST=respond;do_PUT=respond
    do_DELETE=respond;do_PATCH=respond;do_OPTIONS=respond


if __name__=='__main__':
    os.umask(0o077)
    server=http.server.ThreadingHTTPServer(('0.0.0.0',0),Gate)
    server.daemon_threads=True
    path=Path(sys.argv[1]);temporary=path.with_suffix('.tmp')
    with temporary.open('w') as output:
        json.dump({'pid':os.getpid(),'port':server.server_port},output);output.flush();os.fsync(output.fileno())
    temporary.replace(path)
    server.serve_forever()
