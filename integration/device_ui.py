"""Small ADB UI helper; never prints pairing credentials or raw UI XML."""
import subprocess, sys, re, xml.etree.ElementTree as ET
from pathlib import Path
sys.stdout.reconfigure(encoding='utf-8', errors='replace')
serial, action, *args = sys.argv[1:]
def adb(*parts):
    return subprocess.run(['adb', '-s', serial, *parts], check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout
def nodes():
    adb('shell', 'uiautomator', 'dump', '/sdcard/campro-ui.xml')
    return list(ET.fromstring(adb('shell', 'cat', '/sdcard/campro-ui.xml')).iter('node'))
if action == 'list':
    for n in nodes():
        text = n.get('text') or n.get('content-desc')
        if text and not re.search(r'PIN|[0-9]{6}|camproz://', text):
            print(text, n.get('bounds'), 'enabled='+n.get('enabled',''))
elif action == 'tap':
    candidates = [n for n in nodes() if args[0] in (n.get('text',''), n.get('content-desc',''))]
    if not candidates: raise SystemExit('No exact matching UI label')
    b = list(map(int, re.findall(r'\d+', candidates[0].get('bounds'))))
    adb('shell', 'input', 'tap', str((b[0]+b[2])//2), str((b[1]+b[3])//2))
    print('Tapped', args[0])
elif action == 'shot':
    target = Path(args[0]); target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(adb('exec-out', 'screencap', '-p')); print(target)
