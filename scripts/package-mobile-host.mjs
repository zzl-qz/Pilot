// 生成当前系统的便携后台：自带 Java、连接桥和 Android 安装包，不要求最终用户配置 Java。
import { cp, mkdir, access, writeFile, chmod, mkdtemp, rename, rm } from 'node:fs/promises';
import { spawn, execFile } from 'node:child_process';
import path from 'node:path';
import { promisify } from 'node:util';
import { fileURLToPath } from 'node:url';
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const finalOutput = path.resolve(process.argv[2] || path.join(root, 'pilot-mobile/build/host', `${process.platform}-${process.arch}`));
const jdk = process.env.JAVA_HOME;
if (!jdk) throw new Error('打包时需要 JAVA_HOME 指向 Java 25；安装包用户不需要配置');
try { await access(finalOutput); throw new Error(`输出目录已存在，为避免覆盖请使用新目录：${finalOutput}`); }
catch (error) { if (error.code !== 'ENOENT') throw error; }
const jar = path.join(root, 'pilot-server/target/pilot-server-0.0.1-SNAPSHOT.jar');
const bridge = path.join(root, 'pilot-mobile/bridge/build/install/bridge/lib');
const apk = path.join(root, 'pilot-mobile/app/build/outputs/apk/debug/app-debug.apk');
for (const input of [jar, bridge, apk]) await access(input);
await mkdir(path.dirname(finalOutput), {recursive:true});
const output = await mkdtemp(path.join(path.dirname(finalOutput), '.pilot-package-'));
try {
await cp(jar, path.join(output, 'pilot-server.jar'));
await cp(bridge, path.join(output, 'mobile-bridge/lib'), {recursive:true});
await cp(apk, path.join(output, 'pilot-android-preview.apk'));
const java = path.join(jdk, 'bin', process.platform === 'win32' ? 'java.exe' : 'java');
const version = await promisify(execFile)(java, ['-version']);
if (Number((version.stderr + version.stdout).match(/version\s+"(\d+)/)?.[1]) < 25) throw new Error('后台打包需要 Java 25 或更新版本');
const moduleList = await promisify(execFile)(java, ['--list-modules']);
const modules = moduleList.stdout.trim().split(/\r?\n/).map(line => line.split('@')[0]).filter(name => !['jdk.jlink','jdk.jpackage'].includes(name)).join(',');
const jlink = path.join(jdk, 'bin', process.platform === 'win32' ? 'jlink.exe' : 'jlink');
await new Promise((resolve,reject) => {
  const child = spawn(jlink, ['--add-modules',modules,'--strip-debug','--no-header-files','--no-man-pages','--output',path.join(output,'runtime')], {stdio:'inherit',windowsHide:true});
  child.once('error',reject); child.once('exit',code=>code===0?resolve():reject(new Error(`jlink 失败 ${code}`)));
});
const sh = '#!/bin/sh\nset -eu\nPILOT_BUNDLE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)\ncd "$PILOT_BUNDLE_DIR"\nexec "$PILOT_BUNDLE_DIR/runtime/bin/java" --enable-native-access=ALL-UNNAMED -jar "$PILOT_BUNDLE_DIR/pilot-server.jar" "--pilot.mobile.libraries=$PILOT_BUNDLE_DIR/mobile-bridge/lib" --server.address=127.0.0.1 "$@"\n';
await writeFile(path.join(output,'start.sh'), sh); await chmod(path.join(output,'start.sh'),0o755);
await writeFile(path.join(output,'start.cmd'), '@echo off\r\nsetlocal\r\ncd /d "%~dp0"\r\n"%~dp0runtime\\bin\\java.exe" --enable-native-access=ALL-UNNAMED -jar "%~dp0pilot-server.jar" "--pilot.mobile.libraries=%~dp0mobile-bridge\\lib" --server.address=127.0.0.1 %*\r\n');
await writeFile(path.join(output,'README.txt'), 'Pilot 移动连接预览包\n运行当前系统的 start.sh 或 start.cmd，然后在浏览器打开 http://localhost:9999。已有 Pilot 桌面可以连接此后台。\n手机安装 pilot-android-preview.apk，在电脑左侧“连接手机”中扫码。电脑需保持运行。\n默认不使用中继，仅尝试 P2P 直连；两端需有可互达地址。需要中继时必须在后端明确配置服务地址。此 APK 已由本机开发密钥签名，可直接安装；以后更新应使用相同的签名密钥。后台通知尚未启用。\n运行数据保存在此目录下 .pilot，请备份后再替换安装包。\n');
await rename(output, finalOutput);
console.log(`已生成便携后台：${finalOutput}`);
} catch (error) { await rm(output, {recursive:true,force:true}); throw error; }
