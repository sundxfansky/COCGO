#!/usr/bin/env python3
"""Isolated Android smoke checks; does not install, stop, or launch the user's app."""
import argparse
import base64
import json
import os
from pathlib import Path
import secrets
import shlex
import socket
import struct
import subprocess
import tempfile
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parent.parent


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--device', required=True)
    parser.add_argument('--root', action='store_true', help='Use su when the shell already has root authorization')
    args = parser.parse_args()
    prefix = ['adb', '-s', args.device]
    def adb(*items, check=True):
        result = subprocess.run(prefix + list(items), check=False, capture_output=True, text=True, timeout=180)
        if check and result.returncode: raise RuntimeError('adb failed: ' + result.stderr.strip())
        return result.stdout.strip()
    sdk = Path(os.environ.get('ANDROID_HOME', str(Path.home()/'Library/Android/sdk')))
    for line in (ROOT/'local.properties').read_text().splitlines():
        if line.startswith('sdk.dir='): sdk = Path(line.partition('=')[2])
    d8 = sorted((sdk/'build-tools').glob('*/d8'))[-1]
    android = sdk/'platforms/android-36/android.jar'
    app = ROOT/'app/build/outputs/apk/debug/app-debug.apk'
    server = ROOT/'third_party/sunserver/app/build/outputs/apk/debug/app-debug.apk'
    plugin = ROOT/'app/build/plugin/code.jar'
    remote = '/data/local/tmp/sun-smoke-' + secrets.token_hex(6)
    token = secrets.token_hex(32)
    port = 19000 + secrets.randbelow(9000)
    forward = None
    server_pid = None
    with tempfile.TemporaryDirectory(prefix='sun-smoke-') as temporary:
        work = Path(temporary)
        def compile_dex(name, sources, classpath=None):
            folder = work/name; folder.mkdir()
            files = []
            for filename, content in sources.items():
                source = folder/filename;source.parent.mkdir(parents=True,exist_ok=True);source.write_text(content);files.append(str(source))
            classes = folder/'classes';classes.mkdir()
            command = ['javac','-source','11','-target','11','-d',str(classes),'-cp',str(android) + (os.pathsep+str(classpath) if classpath else '')] + files
            subprocess.run(command,check=True,capture_output=True)
            output = folder/(name+'.jar')
            command = [str(d8),'--min-api','24','--lib',str(android),'--output',str(output)]
            if classpath: command += ['--classpath',str(classpath)]
            command += [str(path) for path in classes.rglob('*.class')]
            subprocess.run(command,check=True,capture_output=True)
            return output
        variant = lambda number: f'package com.coc.suncode.jar.sample; public class Version {{ public static int value() {{return {number};}} }}'
        one = compile_dex('one', {'Version.java':variant(1)})
        two = compile_dex('two', {'Version.java':variant(2)})
        host_classes = ROOT/'app/build/intermediates/compile_app_classes_jar/debug/bundleDebugClassesToCompileJar/classes.jar'
        if not host_classes.exists():
            candidates = list((ROOT/'app/build/intermediates').glob('**/classes.jar'))
            host_classes = next(path for path in candidates if 'bundleDebugClassesToCompileJar' in str(path))
        harness = compile_dex('harness', {'Version.java':variant(0), 'Smoke.java':'''import com.coc.suncode.loadjar.PluginClassLoader;
public class Smoke {
 public static void main(String[] args) throws Exception {
  ClassLoader parent = Smoke.class.getClassLoader();
  PluginClassLoader one = new PluginClassLoader(args[0]+"/one.jar",args[0],parent);
  PluginClassLoader two = new PluginClassLoader(args[0]+"/two.jar",args[0],parent);
  String name="com.coc.suncode.jar.sample.Version";
  if (!Class.forName(name).getMethod("value").invoke(null).equals(0)) throw new AssertionError("parent fixture");
  if (!one.loadClass(name).getMethod("value").invoke(null).equals(1)) throw new AssertionError("first plugin");
  if (!two.loadClass(name).getMethod("value").invoke(null).equals(2)) throw new AssertionError("second plugin");
  if (one.loadClass("com.coc.suncode.interfaces.MainCode") != parent.loadClass("com.coc.suncode.interfaces.MainCode")) throw new AssertionError("host ABI duplicated");
  try { one.loadClass("com.coc.suncode.jar.ui.EnterMainCode"); throw new AssertionError("plugin fell through to bundled code"); } catch(ClassNotFoundException expected) {}
  PluginClassLoader real = new PluginClassLoader(args[0]+"/code.jar",args[0],parent);
  Class<?> entry=real.loadClass("com.coc.suncode.jar.ui.EnterMainCode");
  if(entry.getClassLoader()!=real) throw new AssertionError("real plugin shadowed");
  Object instance=entry.getConstructor().newInstance();
  if(!parent.loadClass("com.coc.suncode.interfaces.MainCode").isInstance(instance)) throw new AssertionError("incompatible ABI");
  System.out.println("PASS: two plugin versions, parent ABI, no fallback, real plugin entry");
 }
}'''},host_classes)
        try:
            adb('shell','mkdir','-p',remote)
            for source,name in [(app,'host.apk'),(server,'server.apk'),(plugin,'code.jar'),(one,'one.jar'),(two,'two.jar'),(harness,'harness.jar')]:
                adb('push',str(source),remote+'/'+name)
            adb('shell','chmod','444',remote+'/one.jar',remote+'/two.jar',remote+'/code.jar',remote+'/host.apk',remote+'/harness.jar',remote+'/server.apk')
            output = adb('shell',f'CLASSPATH={remote}/host.apk:{remote}/harness.jar app_process /system/bin Smoke {remote}')
            if 'PASS:' not in output: raise AssertionError(output)
            print(output)
            command = f'export CLASSPATH={remote}/server.apk SUN_SERVER_TOKEN={token} SUN_SERVER_PORT={port}; nohup app_process /system/bin com.coc.sunserver.ShellServer < /dev/null >{remote}/server.log 2>&1 & echo $!'
            server_pid = (adb('shell','su','-c',shlex.quote(command)) if args.root else adb('shell',command)).splitlines()[-1]
            if not server_pid.isdigit(): raise RuntimeError('Could not start isolated root server')
            forward = int(adb('forward','tcp:0',f'tcp:{port}'))
            url=f'http://127.0.0.1:{forward}/sun?actionType=connection_test'
            for attempt in range(30):
                try:
                    urllib.request.urlopen(url,timeout=1)
                    raise AssertionError('Unauthenticated root access accepted')
                except urllib.error.HTTPError as error:
                    if error.code != 401: raise
                    break
                except (OSError,urllib.error.URLError): time.sleep(.2)
            else: raise RuntimeError('Root server did not start')
            request=urllib.request.Request(url,headers={'Authorization':'Bearer '+token})
            with urllib.request.urlopen(request,timeout=3) as response:
                assert json.load(response)['data']=='connected'
            with socket.create_connection(('127.0.0.1',forward),timeout=5) as connection:
                key=base64.b64encode(secrets.token_bytes(16)).decode()
                connection.sendall((f'GET /sun HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: {key}\r\nAuthorization: Bearer {token}\r\n\r\n').encode())
                header=b''
                while not header.endswith(b'\r\n\r\n'): header+=connection.recv(1)
                assert b'101' in header.split(b'\r\n')[0],header
                def read_exact(size):
                    result=b''
                    while len(result)<size:
                        part=connection.recv(size-len(result))
                        if not part: raise EOFError()
                        result+=part
                    return result
                def action(value):
                    payload=json.dumps(value).encode(); mask=secrets.token_bytes(4)
                    frame=bytes([0x81,0x80|len(payload)]) if len(payload)<126 else bytes([0x81,0xfe])+struct.pack('!H',len(payload))
                    connection.sendall(frame+mask+bytes(v^mask[i%4] for i,v in enumerate(payload)))
                    first,second=read_exact(2);length=second&127
                    if length==126:length=struct.unpack('!H',read_exact(2))[0]
                    if length==127:length=struct.unpack('!Q',read_exact(8))[0]
                    return json.loads(read_exact(length))
                path=remote+'/config.json'
                assert action({'actionType':'file_action','subAction':'write','path':path,'content':'{"smoke":"ok"}'})['status']=='success'
                assert action({'actionType':'file_action','subAction':'read','path':path})['data']=='{"smoke":"ok"}'
                assert action({'actionType':'file_action','subAction':'check_exists','path':remote+'/missing'})['data']=='false'
                result=action({'actionType':'shell_action','command':'printf smoke'})
                assert result['exitCode']==0 and result['data']=='smoke',result
            print('PASS: unauthorized HTTP rejected; authenticated HTTP, WebSocket, atomic files and shell RPC')
        except Exception:
            print(adb('shell', 'tail', '-40', remote+'/server.log', check=False))
            raise
        finally:
            if server_pid and server_pid.isdigit():
                if args.root: adb('shell','su','-c',shlex.quote('kill '+server_pid),check=False)
                else: adb('shell','kill',server_pid,check=False)
            if forward: adb('forward','--remove',f'tcp:{forward}',check=False)
            if args.root: adb('shell','su','-c',shlex.quote('rm -rf '+remote),check=False)
            else: adb('shell','rm','-rf',remote,check=False)

if __name__=='__main__':main()
