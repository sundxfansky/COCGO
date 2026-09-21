#!/usr/bin/env python3
"""Exercise config conflicts, signed publication, and session revocation against an isolated backend."""
import base64
import hashlib
import json
import os
from pathlib import Path
import socket
import sqlite3
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
from release_plugin import chacha20, legacy_key

ROOT = Path(__file__).resolve().parent.parent

def main():
    with tempfile.TemporaryDirectory(prefix='sun-cloud-smoke-') as temporary:
        work=Path(temporary);key=work/'signing.pem';binary=work/'server';database=work/'sun.db'
        subprocess.run(['openssl','genpkey','-algorithm','RSA','-pkeyopt','rsa_keygen_bits:2048','-out',str(key)],check=True,capture_output=True)
        public=subprocess.check_output(['openssl','pkey','-in',str(key),'-pubout','-outform','DER'])
        subprocess.run(['go','build','-o',str(binary),'.'],cwd=ROOT/'backend',check=True)
        with socket.socket() as reservation:
            reservation.bind(('127.0.0.1',0));port=reservation.getsockname()[1]
        endpoint=f'http://127.0.0.1:{port}/api/v1'
        environment=os.environ.copy();environment.update({'DB_PATH':str(database),'HTTP_ADDR':f'127.0.0.1:{port}','PLUGIN_PUBLIC_KEY':base64.b64encode(public).decode()})
        for name in ['SMTP_HOST','SMTP_USER','SMTP_PASSWORD','SMTP_FROM','JWT_SECRET']: environment.pop(name,None)
        with (work/'backend.log').open('w') as logs:
            server=subprocess.Popen([str(binary)],env=environment,stdout=logs,stderr=logs)
            try:
                def request(path,method='GET',body=None,token='',version=None):
                    headers={'Content-Type':'application/json'}
                    if token:headers['Authorization']='Bearer '+token
                    if version is not None:headers['If-Match']=str(version)
                    item=urllib.request.Request(endpoint+path,data=json.dumps(body).encode() if body is not None else None,headers=headers,method=method)
                    try:
                        with urllib.request.urlopen(item,timeout=10) as response:
                            data=response.read();return response.status,json.loads(data) if response.headers.get('Content-Type','').startswith('application/json') else data
                    except urllib.error.HTTPError as error:return error.code,error.read()
                for _ in range(50):
                    try:
                        if request('/setup')[0]==200:break
                    except OSError:time.sleep(.1)
                else:raise RuntimeError('isolated backend did not start')
                with sqlite3.connect(database) as db: setup=db.execute("SELECT value FROM settings WHERE key='setup_token'").fetchone()[0]
                code,body=request('/setup','POST',{'email':'smoke@example.com','password':'test-only-password','setupToken':setup})
                assert code==201;token=body['token']
                assert request('/config','PUT',{'config':{'test':'first'}},token,0)[0]==200
                assert request('/config','PUT',{'config':{'test':'stale'}},token,0)[0]==409
                assert request('/config',token=token)[1]['config']=={'test':'first'}
                environment['SUN_UPLOAD_TOKEN']=token
                subprocess.run(['python3',str(ROOT/'scripts/release_plugin.py'),'--input',str(ROOT/'app/build/plugin/code.jar'),'--key',str(key),'--output',str(work/'release'),'--url',endpoint],env=environment,check=True)
                code,manifest=request('/plugins/latest',token=token);assert code==200
                code,payload=request('/plugins/'+str(manifest['version']),token=token);assert code==200
                assert hashlib.sha256(payload).hexdigest()==manifest['sha256']
                assert chacha20(payload[12:],legacy_key(ROOT),payload[:12])==(ROOT/'app/build/plugin/code.jar').read_bytes()
                assert request('/auth/logout','POST',{},token)[0]==200
                assert request('/auth/me',token=token)[0]==401
                print('PASS: stale config rejected, real JAR signed/uploaded/downloaded/decrypted, session revoked')
            finally:
                server.terminate()
                try:server.wait(timeout=5)
                except subprocess.TimeoutExpired:server.kill();server.wait()

if __name__=='__main__':main()
