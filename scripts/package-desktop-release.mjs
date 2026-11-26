#!/usr/bin/env node
/**
 * 跨平台自包含发行包构建脚本：通过 jlink 裁剪最小 Java 25 运行时并组装 macOS / Windows / Linux 发行产物。
 *
 * 用法：
 *   node scripts/package-desktop-release.mjs [--out <dir>] [--jar <path>] [--java-home <path>]
 *                                            [--platform darwin|win32|linux] [--arch arm64|x64]
 *                                            [--skip-dmg] [--verify]
 */
import { execFile, spawn } from 'node:child_process';
import { access, chmod, cp, mkdir, mkdtemp, readdir, rename, rm, stat, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { promisify } from 'node:util';

const execFileAsync = promisify(execFile);
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const DESKTOP_RUNTIME_FILES = [
  'package.json', 'main.mjs', 'preload.cjs', 'alert-preload.cjs', 'brand-host.mjs',
  'desktop-shell.mjs', 'desktop-alerts.mjs', 'browser-control.mjs', 'workbench-security.mjs',
  'protocol.mjs', 'platform-menu.mjs', 'offline-page.mjs', 'directory-picker.mjs',
  'media-permission.mjs', 'session-export.mjs', 'mobile-bridge.mjs'
];

async function copyDesktopRuntime(target) {
  const desktopDir = path.join(ROOT, 'pilot-desktop');
  const destination = path.join(target, 'pilot-desktop');
  await mkdir(destination, { recursive: true });
  for (const file of DESKTOP_RUNTIME_FILES) {
    try { await cp(path.join(desktopDir, file), path.join(destination, file)); }
    catch (error) { if (error.code !== 'ENOENT') throw error; }
  }
  try { await cp(path.join(desktopDir, 'assets'), path.join(destination, 'assets'), { recursive: true }); }
  catch (error) { if (error.code !== 'ENOENT') throw error; }
  const bridge = path.join(ROOT, 'mobile-bridge/lib');
  try { await cp(bridge, path.join(target, 'mobile-bridge/lib'), { recursive: true }); }
  catch (error) { if (error.code !== 'ENOENT') throw error; }
}

/** Spring Boot + SQLite + PDFBox + POI + Jackson + HTTP/TLS 所需的最小 JDK 模块集合。 */
export const PILOT_JLINK_MODULES = [
  'java.base',
  'java.compiler',
  'java.desktop',
  'java.instrument',
  'java.logging',
  'java.management',
  'java.naming',
  'java.net.http',
  'java.prefs',
  'java.rmi',
  'java.scripting',
  'java.security.jgss',
  'java.security.sasl',
  'java.sql',
  'java.xml',
  'jdk.charsets',
  'jdk.crypto.ec',
  'jdk.httpserver',
  'jdk.management',
  'jdk.unsupported',
  'jdk.zipfs'
];

export function buildJlinkArgs(outputDir, extraModules = []) {
  const modules = [...new Set([...PILOT_JLINK_MODULES, ...extraModules.filter(Boolean)])];
  return [
    '--add-modules', modules.join(','),
    '--strip-debug',
    '--no-header-files',
    '--no-man-pages',
    '--compress=zip-6',
    '--output', outputDir
  ];
}

export function executableName(tool, platform = process.platform) {
  return platform === 'win32' ? `${tool}.exe` : tool;
}

export function macInfoPlist(version = '0.1.0') {
  return `<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleDevelopmentRegion</key><string>zh_CN</string>
  <key>CFBundleExecutable</key><string>Pilot</string>
  <key>CFBundleIdentifier</key><string>com.pilot.desktop</string>
  <key>CFBundleInfoDictionaryVersion</key><string>6.0</string>
  <key>CFBundleName</key><string>Pilot</string>
  <key>CFBundleDisplayName</key><string>Pilot</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>${version}</string>
  <key>CFBundleVersion</key><string>${version}</string>
  <key>LSMinimumSystemVersion</key><string>13.0</string>
  <key>NSHighResolutionCapable</key><true/>
</dict>
</plist>
`;
}

export function macLauncherScript() {
  return `#!/bin/sh
set -eu
APP_MACOS_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
RESOURCES_DIR=$(CDPATH= cd -- "$APP_MACOS_DIR/../Resources" && pwd)
export PILOT_DATA_DIR="\${PILOT_DATA_DIR:-\$HOME/.pilot}"
exec "$RESOURCES_DIR/runtime/bin/java" \\
  --enable-native-access=ALL-UNNAMED \\
  -jar "$RESOURCES_DIR/app/pilot-server.jar" \\
  --server.address=127.0.0.1 \\
  "$@"
`;
}

export function windowsLauncherCmd() {
  return [
    '@echo off',
    'setlocal',
    'set "PILOT_BUNDLE_DIR=%~dp0"',
    'if "%PILOT_DATA_DIR%"=="" set "PILOT_DATA_DIR=%USERPROFILE%\\.pilot"',
    '"%PILOT_BUNDLE_DIR%runtime\\bin\\java.exe" --enable-native-access=ALL-UNNAMED -jar "%PILOT_BUNDLE_DIR%app\\pilot-server.jar" --server.address=127.0.0.1 %*',
    ''
  ].join('\r\n');
}

export function windowsNsisScript(arch = 'x64', version = '0.1.0') {
  return [
    `!define APP_NAME "Pilot"`,
    `!define APP_VERSION "${version}"`,
    `OutFile "Pilot-Windows-${arch}-Setup.exe"`,
    `InstallDir "$LOCALAPPDATA\\Pilot"`,
    `RequestExecutionLevel user`,
    `Page directory`,
    `Page instfiles`,
    `Section "Install"`,
    `  SetOutPath "$INSTDIR"`,
    `  File /r "bundle\\*.*"`,
    `  CreateShortcut "$DESKTOP\\Pilot.lnk" "$INSTDIR\\Pilot.cmd"`,
    `  CreateShortcut "$SMPROGRAMS\\Pilot.lnk" "$INSTDIR\\Pilot.cmd"`,
    `  WriteUninstaller "$INSTDIR\\Uninstall.exe"`,
    `SectionEnd`,
    `Section "Uninstall"`,
    `  Delete "$DESKTOP\\Pilot.lnk"`,
    `  Delete "$SMPROGRAMS\\Pilot.lnk"`,
    `  RMDir /r "$INSTDIR"`,
    `SectionEnd`,
    ''
  ].join('\r\n');
}

export function linuxAppRunScript() {
  return `#!/bin/sh
set -eu
HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
export PILOT_DATA_DIR="\${PILOT_DATA_DIR:-\$HOME/.pilot}"
exec "$HERE/usr/lib/pilot/runtime/bin/java" \\
  --enable-native-access=ALL-UNNAMED \\
  -jar "$HERE/usr/lib/pilot/app/pilot-server.jar" \\
  --server.address=127.0.0.1 \\
  "$@"
`;
}

export function linuxDesktopEntry() {
  return `[Desktop Entry]
Type=Application
Name=Pilot
Comment=Pilot Local Coding Agent
StartupWMClass=Pilot
Exec=AppRun
Icon=pilot
Categories=Development;IDE;
Terminal=false
`;
}

export function parseArgs(argv) {
  const options = {
    out: '',
    jar: path.join(ROOT, 'pilot-server/target/pilot-server-0.0.1-SNAPSHOT.jar'),
    javaHome: process.env.JAVA_HOME || '',
    platform: process.platform,
    arch: process.arch,
    version: '0.1.0',
    skipDmg: false,
    verify: false,
    extraModules: []
  };
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === '--out') options.out = argv[++i] || '';
    else if (arg === '--jar') options.jar = argv[++i] || options.jar;
    else if (arg === '--java-home') options.javaHome = argv[++i] || '';
    else if (arg === '--platform') options.platform = argv[++i] || options.platform;
    else if (arg === '--arch') options.arch = argv[++i] || options.arch;
    else if (arg === '--version') options.version = argv[++i] || options.version;
    else if (arg === '--extra-modules') options.extraModules = (argv[++i] || '').split(',').filter(Boolean);
    else if (arg === '--skip-dmg') options.skipDmg = true;
    else if (arg === '--verify') options.verify = true;
    else throw new Error(`未知参数：${arg}`);
  }
  if (!options.out) {
    options.out = path.join(ROOT, 'pilot-desktop/out/release', `${options.platform}-${options.arch}`);
  }
  return options;
}

async function resolveJavaHome(configured) {
  if (configured) return configured;
  if (process.platform === 'darwin') {
    try {
      const { stdout } = await execFileAsync('/usr/libexec/java_home', ['-v', '25']);
      const detected = stdout.trim();
      if (detected) return detected;
    } catch { /* fall through */ }
  }
  throw new Error('请设置 JAVA_HOME（或传入 --java-home）指向 Java 25+ JDK；最终用户安装包无需配置 Java');
}

async function verifyJdkVersion(javaHome) {
  const javaBin = path.join(javaHome, 'bin', executableName('java'));
  const { stdout, stderr } = await execFileAsync(javaBin, ['-version']);
  const major = Number((stderr + stdout).match(/version\s+"(\d+)/)?.[1]);
  if (!Number.isFinite(major) || major < 25) {
    throw new Error(`jlink 打包需要 Java 25+，当前检测到：${(stderr + stdout).split('\n')[0] || '未知版本'}`);
  }
  return javaBin;
}

async function verifyJdkTarget(javaHome, platform, arch) {
  const bin = path.join(javaHome, 'bin', executableName('java'));
  const { stdout, stderr } = await execFileAsync(bin, ['-XshowSettings:properties', '-version']);
  const details = stdout + stderr;
  const homePlatform = process.platform;
  const homeArch = process.arch === 'x64' ? 'x64' : process.arch;
  const match = details.match(/os\.arch\s*=\s*(\S+)/);
  const jdkArch = match?.[1] === 'amd64' ? 'x64' : match?.[1] === 'aarch64' ? 'arm64' : match?.[1];
  if (platform !== homePlatform || arch !== homeArch || (jdkArch && jdkArch !== arch)) {
    throw new Error(`当前 JDK 只能生成 ${homePlatform}-${jdkArch || homeArch} 运行时，不能安全地打包为 ${platform}-${arch}；请在目标平台/架构使用对应 JDK。`);
  }
}

function runCommand(bin, args, options = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(bin, args, { stdio: 'inherit', windowsHide: true, ...options });
    child.once('error', reject);
    child.once('exit', (code) => {
      if (code === 0) resolve();
      else reject(new Error(`${path.basename(bin)} 退出码 ${code}`));
    });
  });
}

async function directorySize(dir) {
  let total = 0;
  const entries = await readdir(dir, { withFileTypes: true });
  for (const entry of entries) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) total += await directorySize(full);
    else if (entry.isFile()) total += (await stat(full)).size;
  }
  return total;
}

async function commandExists(name) {
  try {
    const finder = process.platform === 'win32' ? 'where' : 'which';
    await execFileAsync(finder, [name]);
    return true;
  } catch {
    return false;
  }
}

async function verifyBundledRuntime(javaBin, jarPath) {
  const tempData = await mkdtemp(path.join(tmpdir(), 'pilot-release-verify-'));
  let child;
  try {
    child = spawn(javaBin, [
      '--enable-native-access=ALL-UNNAMED',
      '-jar', jarPath,
      '--server.address=127.0.0.1',
      '--server.port=0',
      '--pilot.desktop.launch=false'
    ], {
      env: { ...process.env, PILOT_DATA_DIR: tempData },
      stdio: ['ignore', 'pipe', 'pipe'],
      windowsHide: true
    });

    let logs = '';
    const port = await new Promise((resolve, reject) => {
      const timeout = setTimeout(() => reject(new Error(`自检启动超时。日志摘要：\n${logs.slice(-1200)}`)), 45_000);
      const onChunk = (chunk) => {
        logs += chunk.toString('utf8');
        const match = logs.match(/Tomcat started on port (\d+)/);
        if (match) {
          clearTimeout(timeout);
          resolve(Number(match[1]));
        }
      };
      child.stdout.on('data', onChunk);
      child.stderr.on('data', onChunk);
      child.once('exit', (code) => {
        clearTimeout(timeout);
        reject(new Error(`自检进程提前退出（${code}）。日志摘要：\n${logs.slice(-1200)}`));
      });
    });

    let health = null;
    for (let attempt = 0; attempt < 20; attempt++) {
      try {
        health = await fetch(`http://127.0.0.1:${port}/api/health`);
        if (health.ok) break;
      } catch { /* 等待 Servlet 就绪 */ }
      await new Promise((r) => setTimeout(r, 250));
    }
    if (!health?.ok) throw new Error(`/api/health 返回 ${health?.status ?? '无响应'}（port=${port}）`);
    const diag = await fetch(`http://127.0.0.1:${port}/api/diagnostics/export`);
    if (!diag.ok) throw new Error(`/api/diagnostics/export 返回 ${diag.status}`);
    const bundle = await diag.json();
    if (!bundle?.sanitized) throw new Error('脱敏诊断包未包含 sanitized=true');
    return { port, healthStatus: health.status, diagnosticsStatus: diag.status };
  } finally {
    if (child && child.exitCode === null) {
      child.kill('SIGTERM');
      await new Promise((resolve) => {
        const force = setTimeout(() => { try { child.kill('SIGKILL'); } catch {} resolve(); }, 5000);
        child.once('exit', () => { clearTimeout(force); resolve(); });
      });
    }
    await rm(tempData, { recursive: true, force: true });
  }
}

export async function packageRelease(rawOptions) {
  const options = { ...rawOptions };
  const javaHome = await resolveJavaHome(options.javaHome);
  await verifyJdkVersion(javaHome);
  await verifyJdkTarget(javaHome, options.platform, options.arch);
  await access(options.jar);

  const finalOut = path.resolve(options.out);
  await rm(finalOut, { recursive: true, force: true });
  await mkdir(path.dirname(finalOut), { recursive: true });
  const stage = await mkdtemp(path.join(path.dirname(finalOut), '.pilot-release-stage-'));

  try {
    let runtimeDir;
    let appDir;
    const artifacts = [];

    if (options.platform === 'darwin') {
      const appBundle = path.join(stage, 'Pilot.app');
      runtimeDir = path.join(appBundle, 'Contents/Resources/runtime');
      appDir = path.join(appBundle, 'Contents/Resources/app');
      await mkdir(path.join(appBundle, 'Contents/MacOS'), { recursive: true });
      await mkdir(appDir, { recursive: true });
      await writeFile(path.join(appBundle, 'Contents/Info.plist'), macInfoPlist(options.version));
      const launcher = path.join(appBundle, 'Contents/MacOS/Pilot');
      await writeFile(launcher, macLauncherScript());
      await chmod(launcher, 0o755);
      artifacts.push('Pilot.app');
    } else if (options.platform === 'win32') {
      const bundle = path.join(stage, 'bundle');
      runtimeDir = path.join(bundle, 'runtime');
      appDir = path.join(bundle, 'app');
      await mkdir(appDir, { recursive: true });
      await writeFile(path.join(bundle, 'Pilot.cmd'), windowsLauncherCmd());
      await writeFile(path.join(stage, 'pilot-installer.nsi'), windowsNsisScript(options.arch, options.version));
      artifacts.push('bundle/Pilot.cmd', 'pilot-installer.nsi');
    } else if (options.platform === 'linux') {
      const appImageDir = path.join(stage, 'Pilot.AppDir');
      runtimeDir = path.join(appImageDir, 'usr/lib/pilot/runtime');
      appDir = path.join(appImageDir, 'usr/lib/pilot/app');
      await mkdir(appDir, { recursive: true });
      const appRun = path.join(appImageDir, 'AppRun');
      await writeFile(appRun, linuxAppRunScript());
      await chmod(appRun, 0o755);
      await writeFile(path.join(appImageDir, 'Pilot.desktop'), linuxDesktopEntry());
      await cp(path.join(ROOT, 'pilot-desktop/assets/icon.png'), path.join(appImageDir, 'pilot.png'));
      artifacts.push('Pilot.AppDir');
    } else {
      throw new Error(`不支持的目标平台：${options.platform}`);
    }

    const jlinkBin = path.join(javaHome, 'bin', executableName('jlink'));
    await runCommand(jlinkBin, buildJlinkArgs(runtimeDir, options.extraModules));
    const bundledJar = path.join(appDir, 'pilot-server.jar');
    await cp(options.jar, bundledJar);
    await copyDesktopRuntime(appDir);

    const bundledJava = path.join(runtimeDir, 'bin', executableName('java', options.platform));
    let verifyResult = null;
    if (options.verify) {
      if (options.platform !== process.platform) {
        throw new Error(`--verify 只能在当前宿主机平台（${process.platform}）运行，不能直接执行 ${options.platform} 二进制`);
      }
      verifyResult = await verifyBundledRuntime(bundledJava, bundledJar);
    }

    if (options.platform === 'darwin' && !options.skipDmg && process.platform === 'darwin' && await commandExists('hdiutil')) {
      const dmgName = `Pilot-macOS-${options.arch}.dmg`;
      const dmgPath = path.join(stage, dmgName);
      await runCommand('hdiutil', ['create', '-volname', 'Pilot', '-srcfolder', path.join(stage, 'Pilot.app'), '-ov', '-format', 'UDZO', dmgPath]);
      artifacts.push(dmgName);
    } else if (options.platform === 'win32' && await commandExists('makensis')) {
      await runCommand('makensis', [path.join(stage, 'pilot-installer.nsi')], { cwd: stage });
      artifacts.push(`Pilot-Windows-${options.arch}-Setup.exe`);
    } else if (options.platform === 'linux' && await commandExists('appimagetool')) {
      const appImageName = `Pilot-Linux-${options.arch}.AppImage`;
      await runCommand('appimagetool', [path.join(stage, 'Pilot.AppDir'), path.join(stage, appImageName)]);
      artifacts.push(appImageName);
    }

    const jreSizeBytes = await directorySize(runtimeDir);
    const jarSizeBytes = (await stat(bundledJar)).size;
    const manifest = {
      createdAt: new Date().toISOString(),
      platform: options.platform,
      arch: options.arch,
      version: options.version,
      jreModules: [...new Set([...PILOT_JLINK_MODULES, ...options.extraModules])],
      jreSizeBytes,
      jarSizeBytes,
      artifacts,
      verifiedOnHost: verifyResult
    };
    await writeFile(path.join(stage, 'release-manifest.json'), JSON.stringify(manifest, null, 2) + '\n');
    await rename(stage, finalOut);
    return { outDir: finalOut, manifest };
  } catch (error) {
    await rm(stage, { recursive: true, force: true });
    throw error;
  }
}

const isDirect = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (isDirect) {
  packageRelease(parseArgs(process.argv.slice(2)))
    .then(({ outDir, manifest }) => {
      const jreMb = (manifest.jreSizeBytes / (1024 * 1024)).toFixed(1);
      const jarMb = (manifest.jarSizeBytes / (1024 * 1024)).toFixed(1);
      console.log(`Pilot 发行包已生成：${outDir}`);
      console.log(`- 裁剪后 JRE：${jreMb} MB（${manifest.jreModules.length} 个模块）`);
      console.log(`- 服务端 JAR：${jarMb} MB`);
      console.log(`- 产物列表：${manifest.artifacts.join(', ')}`);
      if (manifest.verifiedOnHost) {
        console.log(`- 本机启动自检：PASS（port=${manifest.verifiedOnHost.port}, health=${manifest.verifiedOnHost.healthStatus}, diagnostics=${manifest.verifiedOnHost.diagnosticsStatus}）`);
      }
    })
    .catch((error) => {
      console.error(`打包失败：${error.message}`);
      process.exitCode = 1;
    });
}
