#!/usr/bin/env python3
"""Isolated plugin class-loader checks; never installs, stops, or launches the app."""
import argparse
import os
from pathlib import Path
import secrets
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent.parent


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--device', required=True)
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
    plugin = ROOT/'app/build/plugin/code.jar'
    remote = '/data/local/tmp/sun-smoke-' + secrets.token_hex(6)
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
            for source,name in [(app,'host.apk'),(plugin,'code.jar'),(one,'one.jar'),(two,'two.jar'),(harness,'harness.jar')]:
                adb('push',str(source),remote+'/'+name)
            adb('shell','chmod','444',remote+'/one.jar',remote+'/two.jar',remote+'/code.jar',remote+'/host.apk',remote+'/harness.jar')
            output = adb('shell',f'CLASSPATH={remote}/host.apk:{remote}/harness.jar app_process /system/bin Smoke {remote}')
            if 'PASS:' not in output: raise AssertionError(output)
            print(output)
        finally:
            # Remove only the shell-owned temporary fixtures created by this run.
            adb('shell', 'rm', '-rf', remote, check=False)

if __name__ == '__main__':
    main()
