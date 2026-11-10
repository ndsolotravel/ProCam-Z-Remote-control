"""Enter the displayed host PIN on the real companion; never persist the PIN."""
import subprocess, re, time, xml.etree.ElementTree as ET
HOST='192.168.100.104:40791'
REMOTE='192.168.100.140:36625'
def adb(device,*parts):
    return subprocess.run(['adb','-s',device,*parts],check=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE).stdout
def nodes(device):
    adb(device,'shell','uiautomator','dump','/sdcard/campro-ui.xml')
    return list(ET.fromstring(adb(device,'shell','cat','/sdcard/campro-ui.xml')).iter('node'))
def tap(device,n):
    b=list(map(int,re.findall(r'\d+',n.get('bounds'))))
    adb(device,'shell','input','tap',str((b[0]+b[2])//2),str((b[1]+b[3])//2))
texts=[n.get('text','') for n in nodes(HOST)]
pin=next(re.search(r'PIN: ([0-9]{6})',t).group(1) for t in texts if re.search(r'PIN: ([0-9]{6})',t))
fields=[n for n in nodes(REMOTE) if n.get('class')=='android.widget.EditText']
assert len(fields)==3, 'Open manual pairing dialog first'
tap(REMOTE,fields[2])
adb(REMOTE,'shell','input','keyevent','KEYCODE_MOVE_END')
for _ in range(12): adb(REMOTE,'shell','input','keyevent','KEYCODE_DEL')
adb(REMOTE,'shell','input','text',pin)
adb(REMOTE,'shell','input','keyevent','4')
tap(REMOTE,next(n for n in nodes(REMOTE) if n.get('text')=='Connect'))
print('Submitted camera PIN on physical companion',flush=True)
time.sleep(1)
tap(HOST,next(n for n in nodes(HOST) if n.get('text')=='Allow'))
print('Approved physical companion on camera',flush=True)
