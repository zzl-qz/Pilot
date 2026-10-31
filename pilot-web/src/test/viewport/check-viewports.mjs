#!/usr/bin/env node
/**
 * 窄屏/移动端布局回归检查。
 *
 * 桌面宽度下正常不代表手机宽度可用：横向滚动、输入框跑出视口、发送按钮被挤出、
 * 设置面板撑破屏幕，这些问题只有固定视口跑一遍才能稳定发现。
 *
 * 用法：
 *   npm run check:viewports -- --base http://127.0.0.1:8787
 *   PILOT_VIEWPORT_STRICT=1 npm run check:viewports   # 没装 playwright 时视为失败
 *
 * 需要 `npm install` 安装 playwright（浏览器可先 `npx playwright install chromium`）。
 */
import { mkdir, writeFile } from "node:fs/promises";

const VIEWPORTS = [
  { name: "mobile-390", width: 390, height: 844 },
  { name: "tablet-768", width: 768, height: 1024 },
  { name: "desktop-1440", width: 1440, height: 900 }
];

function parseArgs(argv) {
  const args = { base: "http://127.0.0.1:8787", out: "pilot-web/target/viewport" };
  for (let index = 0; index < argv.length; index++) {
    if (argv[index] === "--base") args.base = argv[++index];
    else if (argv[index] === "--out") args.out = argv[++index];
  }
  return args;
}

async function loadChromium() {
  try {
    const playwright = await import("playwright");
    return playwright.chromium;
  } catch {
    return null;
  }
}

/** 页面级断言：没有横向溢出，关键控件完整落在视口内。 */
async function inspect(page) {
  return page.evaluate(() => {
    const root = document.documentElement;
    const overflow = root.scrollWidth - root.clientWidth;
    const boxes = {};
    for (const id of ["composer", "send", "app", "messages"]) {
      const element = document.getElementById(id);
      if (!element) {
        boxes[id] = null;
        continue;
      }
      const rect = element.getBoundingClientRect();
      const style = window.getComputedStyle(element);
      boxes[id] = {
        x: rect.x, y: rect.y, width: rect.width, height: rect.height,
        visible: style.display !== "none" && style.visibility !== "hidden" && rect.width > 0 && rect.height > 0
      };
    }
    return { overflow, viewportWidth: root.clientWidth, boxes };
  });
}

function violations(name, report) {
  const problems = [];
  if (report.overflow > 1) {
    problems.push(`${name}: 横向溢出 ${report.overflow}px（视口 ${report.viewportWidth}px）`);
  }
  const composer = report.boxes.composer;
  if (!composer || !composer.visible) {
    problems.push(`${name}: 输入框不可见`);
  } else if (composer.x < -1 || composer.x + composer.width > report.viewportWidth + 1) {
    problems.push(`${name}: 输入框超出视口（x=${composer.x}, w=${composer.width}）`);
  }
  const send = report.boxes.send;
  if (!send || !send.visible) {
    problems.push(`${name}: 发送按钮不可见`);
  }
  return problems;
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const chromium = await loadChromium();
  if (!chromium) {
    const message = "未安装 playwright：执行 `cd pilot-web && npm install` 后重试。";
    if (process.env.PILOT_VIEWPORT_STRICT === "1") {
      console.error(`FAIL ${message}`);
      process.exit(1);
    }
    console.log(`SKIP ${message}`);
    return;
  }

  await mkdir(args.out, { recursive: true });
  const browser = await chromium.launch();
  const problems = [];
  try {
    for (const viewport of VIEWPORTS) {
      const context = await browser.newContext({ viewport: { width: viewport.width, height: viewport.height } });
      const page = await context.newPage();
      await page.goto(args.base, { waitUntil: "domcontentloaded" });
      await page.waitForSelector("#composer", { timeout: 15_000 });
      const report = await inspect(page);
      problems.push(...violations(viewport.name, report));
      await page.screenshot({ path: `${args.out}/${viewport.name}.png`, fullPage: false });

      // 设置面板是窄屏最容易撑破的界面，单独打开再检查一次
      await page.click("#settings-open");
      await page.waitForSelector("#settings:not([hidden])", { timeout: 5_000 });
      const modal = await page.evaluate(() => {
        const panel = document.querySelector("#settings .modal-card, #settings .settings-card, #settings");
        const rect = panel.getBoundingClientRect();
        return { x: rect.x, width: rect.width, viewportWidth: document.documentElement.clientWidth,
          overflow: document.documentElement.scrollWidth - document.documentElement.clientWidth };
      });
      if (modal.overflow > 1) problems.push(`${viewport.name}: 设置面板导致横向溢出 ${modal.overflow}px`);
      if (modal.x < -1 || modal.x + modal.width > modal.viewportWidth + 1) {
        problems.push(`${viewport.name}: 设置面板超出视口`);
      }
      await page.screenshot({ path: `${args.out}/${viewport.name}-settings.png`, fullPage: false });
      await context.close();
    }
  } finally {
    await browser.close();
  }

  await writeFile(`${args.out}/report.json`, JSON.stringify({ base: args.base, problems }, null, 2), "utf8");
  if (problems.length > 0) {
    for (const problem of problems) console.error(`FAIL ${problem}`);
    process.exit(1);
  }
  console.log(`PASS ${VIEWPORTS.length} 个视口均无横向溢出，输入框与设置面板可用（截图见 ${args.out}）`);
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
