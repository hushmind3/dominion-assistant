"""Explicit private LAN relay for mobile API only; never proxy admin/SDK routes.

Run on the PC, not in the managed container. Trusted Wi-Fi only (HTTP).
"""
import argparse
import http.client
import ipaddress
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROUTES = {'GET': {'/mobile/v1/history'}, 'POST': {'/mobile/v1/pair','/mobile/v1/send','/mobile/v1/cancel','/mobile/v1/disconnect'}}
MAX_BODY = 16384

class Relay(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def forward(self):
        peer = ipaddress.ip_address(self.client_address[0])
        if not private(peer) or self.headers.get('Origin') is not None or self.path not in ROUTES.get(self.command, set()):
            self.send_error(403)
            return
        try:
            size = int(self.headers.get('Content-Length','0'))
            if not 0 <= size <= MAX_BODY or self.headers.get('Transfer-Encoding'):
                self.send_error(413)
                return
            body = self.rfile.read(size) if size else None
            headers = {'Host':f'127.0.0.1:{self.server.target_port}'}
            for name in ('Authorization','Content-Type'):
                if self.headers.get(name): headers[name] = self.headers[name]
            conn = http.client.HTTPConnection('127.0.0.1', self.server.target_port, timeout=10)
            try:
                conn.request(self.command, self.path, body, headers)
                response = conn.getresponse()
                data = response.read(4*1024*1024+1)
                if len(data)>4*1024*1024: raise ValueError('Response too large')
                self.send_response(response.status)
                self.send_header('Content-Type',response.getheader('Content-Type','application/json'))
                self.send_header('Cache-Control','no-store')
                self.send_header('Content-Length',str(len(data)))
                self.end_headers()
                self.wfile.write(data)
            finally:
                conn.close()
        except (OSError,ValueError,http.client.HTTPException):
            self.send_error(502)

    do_GET = do_POST = forward

def private(ip):
    return ip.version == 4 and any(ip in ipaddress.ip_network(n) for n in ('10.0.0.0/8','172.16.0.0/12','192.168.0.0/16'))

if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--bind',required=True,help='PC private IPv4 address')
    p.add_argument('--target-port',required=True,type=int,help='Assistant loopback port')
    p.add_argument('--port',default=8765,type=int)
    args = p.parse_args()
    if not private(ipaddress.ip_address(args.bind)) or not all(1<=x<=65535 for x in (args.port,args.target_port)):
        p.error('Private IPv4 and valid ports required')
    server = ThreadingHTTPServer((args.bind,args.port),Relay)
    server.target_port = args.target_port
    print(f'Mobile endpoint: http://{args.bind}:{args.port} (trusted Wi-Fi only)')
    server.serve_forever()
