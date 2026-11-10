"""Real-device WebSocket integration probe. Credentials remain in process memory only."""
import socket, base64, os, struct, json, time, subprocess, re, xml.etree.ElementTree as ET
from pathlib import Path

class WS:
    def __init__(self, host, port, path='/'):
        self.socket = socket.create_connection((host, port), timeout=5)
        self.buffer = b''
        key = base64.b64encode(os.urandom(16)).decode()
        self.socket.sendall(f'GET {path} HTTP/1.1\r\nHost: {host}:{port}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n'.encode())
        data = b''
        while b'\r\n\r\n' not in data: data += self.socket.recv(4096)
        head, self.buffer = data.split(b'\r\n\r\n', 1)
        assert b'101' in head.split(b'\r\n')[0], 'WebSocket upgrade failed'
    def exact(self, n):
        while len(self.buffer) < n:
            data = self.socket.recv(max(4096, n-len(self.buffer)))
            if not data: raise EOFError('Socket closed')
            self.buffer += data
        data, self.buffer = self.buffer[:n], self.buffer[n:]
        return data
    def send(self, text, opcode=1):
        data = text.encode() if isinstance(text,str) else text
        mask = os.urandom(4)
        n = len(data)
        size = bytes([0x80|n]) if n<126 else bytes([0x80|126])+struct.pack('!H',n)
        self.socket.sendall(bytes([0x80|opcode])+size+mask+bytes(b ^ mask[i%4] for i,b in enumerate(data)))
    def receive(self):
        while True:
            head = self.exact(2); opcode=head[0]&15; n=head[1]&127
            if n==126: n=struct.unpack('!H',self.exact(2))[0]
            elif n==127: n=struct.unpack('!Q',self.exact(8))[0]
            assert n <= 2_000_000, f'Invalid WebSocket length {n}; header={head.hex()}'
            mask = self.exact(4) if head[1]&128 else None
            data = self.exact(n)
            if mask: data=bytes(b ^ mask[i%4] for i,b in enumerate(data))
            if opcode==9: self.send(data,10); continue
            if opcode==8: return ('close', data)
            return ('text' if opcode==1 else 'binary', data)
    def text(self, prefix):
        until=time.monotonic()+18
        while time.monotonic()<until:
            kind,data=self.receive()
            if kind=='close': raise EOFError('WebSocket closed')
            if kind=='text' and data.decode().startswith(prefix): return data.decode()[len(prefix):]
        raise TimeoutError(prefix)
    def close(self):
        try: self.send(struct.pack('!H',1000),8)
        except OSError: pass
        self.socket.close()

HOST_DEVICE='192.168.100.104:40791'
def adb(*args):
    return subprocess.run(['adb','-s',HOST_DEVICE,*args],check=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE).stdout
def nodes():
    adb('shell','uiautomator','dump','/sdcard/campro-ui.xml')
    return list(ET.fromstring(adb('shell','cat','/sdcard/campro-ui.xml')).iter('node'))
def tap(label):
    n=next(n for n in nodes() if n.get('text')==label or n.get('content-desc')==label)
    b=list(map(int,re.findall(r'\d+',n.get('bounds'))))
    adb('shell','input','tap',str((b[0]+b[2])//2),str((b[1]+b[3])//2))

def run():
    # Pairing values are read from the actual camera UI, never hard-coded or logged.
    texts=[n.get('text','') for n in nodes()]
    pin=next(re.search(r'PIN: ([0-9]{6})',t).group(1) for t in texts if re.search(r'PIN: ([0-9]{6})',t))
    host=next(re.search(r'([0-9.]+):8989',t).group(1) for t in texts if re.search(r'([0-9.]+):8989',t))
    evidence=[]
    def passed(name, **facts):
        evidence.append(dict(check=name,status='passed',**facts)); print('PASS',name,flush=True)
        target=Path(__file__).parent/'evidence'; target.mkdir(exist_ok=True)
        (target/'socket-probe.json').write_text(json.dumps(evidence,indent=2))
    unauth=WS(host,8990)
    assert unauth.receive()[0]=='close'; unauth.close(); passed('Unauthenticated preview rejected')
    wrong=WS(host,8989)
    wrong.send('PAIR:'+json.dumps(dict(pin='000000',token='',clientName='Invalid credentials probe')))
    assert not json.loads(wrong.text('PAIR_RESULT:'))['success']; wrong.close(); passed('Wrong PIN rejected')
    time.sleep(1.1)
    ws=WS(host,8989)
    ws.send('PAIR:'+json.dumps(dict(pin=pin,token='',clientName='Codex integration probe')))
    time.sleep(0.5); tap('Allow')
    pair=json.loads(ws.text('PAIR_RESULT:')); assert pair['success']; session=pair['sessionId']
    caps=json.loads(ws.text('CAPS:')); state=json.loads(ws.text('STATE:'))
    passed('Real camera approval and protocol snapshot',commands=caps['commands'],facing=state['activeFacing'])
    stream=WS(host,8990,'/?session='+session)
    frames=[]
    for _ in range(3):
        kind,data=stream.receive(); assert kind=='binary' and data[:2]==b'\xff\xd8'; frames.append(data)
    target=Path(__file__).parent/'evidence'; target.mkdir(exist_ok=True)
    (target/'probe-preview.jpg').write_bytes(frames[-1]); passed('Authenticated camera JPEG preview',frameBytes=[len(f) for f in frames])
    def command(kind,payload=None,ident=None):
        ws.send('PING'); ws.text('PONG')
        ident=ident or str(time.time_ns())
        ws.send('CMD:'+json.dumps(dict(version='1.0',sessionId=session,commandId=ident,type=kind,timestamp=int(time.time()*1000),payload=payload or {})))
        result=json.loads(ws.text('RESULT:')); assert result['commandId']==ident
        return result
    assert command('SET_ZOOM',{'zoom':min(2,caps['maxZoom'])})['success']; passed('Real camera zoom accepted')
    invalid=command('SET_ZOOM',{'zoom':10000}); assert not invalid['success']; passed('Out-of-range zoom rejected')
    unsupported=command('SET_TORCH',{'enabled':True}); assert not unsupported['success']; passed('Unsupported command rejected')
    startid='duplicate-record-test'
    assert command('START_RECORDING',ident=startid)['success']
    assert command('START_RECORDING',ident=startid)['success']
    time.sleep(2)
    assert command('REQUEST_SNAPSHOT')['success']
    observed=json.loads(ws.text('STATE:')); assert observed['isRecording']; passed('Duplicate record command preserves recording')
    assert command('STOP_RECORDING')['success']; passed('Remote recording finalized on real camera')
    assert command('STOP_RECORDING')['success']; passed('Repeated stop is idempotent')
    # Preserve only non-sensitive state and results.
    (target/'socket-probe.json').write_text(json.dumps(evidence,indent=2))
    stream.close(); ws.close()
    print('Saved sanitized evidence',flush=True)

if __name__=='__main__': run()
