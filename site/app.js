/* 静态站点交互：滚动揭示、数字计数、标签切换、复制、卡片高光。
   无依赖；任一环节失败都不影响阅读。 */
(function () {
  "use strict";

  var reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  var hasIO = "IntersectionObserver" in window;

  /* ── 1. 滚动进入时揭示 ─────────────────────────────── */
  var targets = document.querySelectorAll(".reveal");
  if (reduceMotion || !hasIO) {
    targets.forEach(function (el) { el.classList.add("visible"); });
  } else {
    var revealObserver = new IntersectionObserver(function (entries) {
      entries.forEach(function (entry) {
        if (!entry.isIntersecting) return;
        var delay = Number(entry.target.dataset.delay || 0);
        setTimeout(function () { entry.target.classList.add("visible"); }, delay);
        revealObserver.unobserve(entry.target);
      });
    }, { rootMargin: "0px 0px -8% 0px", threshold: 0.12 });

    targets.forEach(function (el) {
      var siblings = el.parentElement ? el.parentElement.children : [el];
      if (siblings.length > 1 && siblings.length <= 9) {
        el.dataset.delay = String(Array.prototype.indexOf.call(siblings, el) % 6 * 55);
      }
      revealObserver.observe(el);
    });
  }

  /* ── 2. 顶栏滚动加边线 ─────────────────────────────── */
  var topbar = document.getElementById("topbar");
  if (topbar) {
    var syncTopbar = function () {
      topbar.classList.toggle("scrolled", window.scrollY > 8);
    };
    syncTopbar();
    window.addEventListener("scroll", syncTopbar, { passive: true });
  }

  /* ── 3. 数字递增 ───────────────────────────────────── */
  function countUp(el) {
    var target = Number(el.dataset.count);
    var pad = Number(el.dataset.pad || 0);
    if (!isFinite(target)) return;

    var format = function (value) {
      var text = String(Math.round(value));
      while (text.length < pad) text = "0" + text;
      return text;
    };

    if (reduceMotion) {
      el.textContent = format(target);
      return;
    }

    var duration = 900;
    var start = null;
    var step = function (timestamp) {
      if (start === null) start = timestamp;
      var progress = Math.min((timestamp - start) / duration, 1);
      // easeOutCubic，末尾收得慢一点，读数不跳
      var eased = 1 - Math.pow(1 - progress, 3);
      el.textContent = format(target * eased);
      if (progress < 1) requestAnimationFrame(step);
      else el.textContent = format(target);
    };
    requestAnimationFrame(step);
  }

  var counters = document.querySelectorAll(".count");
  if (!hasIO) {
    counters.forEach(countUp);
  } else {
    var countObserver = new IntersectionObserver(function (entries) {
      entries.forEach(function (entry) {
        if (!entry.isIntersecting) return;
        countUp(entry.target);
        countObserver.unobserve(entry.target);
      });
    }, { threshold: 0.6 });
    counters.forEach(function (el) { countObserver.observe(el); });
  }

  /* ── 4. 标签页切换（界面视图 / 代码示例 / 操作系统）── */
  function activate(list, button) {
    var tabs = list.querySelectorAll('[role="tab"]');
    tabs.forEach(function (tab) {
      var selected = tab === button;
      tab.classList.toggle("active", selected);
      tab.setAttribute("aria-selected", selected ? "true" : "false");
      tab.tabIndex = selected ? 0 : -1;

      var panel = document.getElementById(tab.dataset.panel);
      if (panel) panel.hidden = !selected;
    });
  }

  document.querySelectorAll('[role="tablist"]').forEach(function (list) {
    var tabs = Array.prototype.slice.call(list.querySelectorAll('[role="tab"]'));

    tabs.forEach(function (tab, index) {
      tab.addEventListener("click", function () { activate(list, tab); });

      // 方向键 / Home / End：规范要求的键盘导航
      tab.addEventListener("keydown", function (event) {
        var next = null;
        if (event.key === "ArrowRight" || event.key === "ArrowDown") next = tabs[(index + 1) % tabs.length];
        else if (event.key === "ArrowLeft" || event.key === "ArrowUp") next = tabs[(index - 1 + tabs.length) % tabs.length];
        else if (event.key === "Home") next = tabs[0];
        else if (event.key === "End") next = tabs[tabs.length - 1];
        if (!next) return;
        event.preventDefault();
        activate(list, next);
        next.focus();
      });
    });
  });

  /* ── 5. 复制 ───────────────────────────────────────── */
  var isEn = (document.documentElement.lang || "").toLowerCase().indexOf("en") === 0;
  var copiedSvg = '<svg viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="m5 13 4 4L19 7" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>';
  var defaultCopyLabel = isEn ? "Copy" : "复制";
  var copyState = new WeakMap();

  function getCopyState(button) {
    var state = copyState.get(button);
    if (!state) {
      state = {
        html: button.innerHTML,
        label: button.getAttribute("aria-label") || defaultCopyLabel,
        title: button.getAttribute("title") || "",
        timer: null
      };
      copyState.set(button, state);
    }
    return state;
  }

  function clearCopyTimer(state) {
    if (state.timer !== null) {
      clearTimeout(state.timer);
      state.timer = null;
    }
  }

  function restoreButton(button, state) {
    state.timer = null;
    button.classList.remove("done", "failed");
    button.innerHTML = state.html;
    button.setAttribute("aria-label", state.label);
    if (state.title) button.title = state.title;
    else button.removeAttribute("title");
  }

  function legacyCopy(text) {
    var area = document.createElement("textarea");
    area.value = text;
    area.setAttribute("readonly", "");
    area.style.position = "fixed";
    area.style.top = "-1000px";
    document.body.appendChild(area);
    area.select();
    var ok = false;
    try { ok = document.execCommand("copy"); } catch (e) { ok = false; }
    document.body.removeChild(area);
    return ok;
  }

  function markDone(button, text) {
    var state = getCopyState(button);
    clearCopyTimer(state);
    clearFailure(button);
    button.classList.add("done");
    button.innerHTML = copiedSvg;
    button.setAttribute("aria-label", isEn ? "Copied" : "已复制");
    button.title = (isEn ? "Copied: " : "已复制：") + text.split("\n")[0];
    var status = statusFor(button);
    if (status) {
      status.textContent = isEn ? "Copied to clipboard." : "已复制到剪贴板。";
      status.hidden = false;
    }
    state.timer = setTimeout(function () {
      restoreButton(button, state);
    }, 1600);
  }

  /** 复制按钮所在容器：安装命令或代码块头部。 */
  function containerFor(button) {
    return button.closest(".install") || button.closest(".code-head") || button.parentElement;
  }

  function statusFor(button) {
    var container = containerFor(button);
    if (!container) return null;
    var status = container.parentElement && container.parentElement.querySelector(".copy-status");
    if (!status) {
      status = document.createElement("p");
      status.className = "copy-status";
      status.setAttribute("role", "status");
      status.setAttribute("aria-live", "polite");
      container.insertAdjacentElement("afterend", status);
    }
    return status;
  }

  function clearFailure(button) {
    var container = containerFor(button);
    if (!container) return;
    button.classList.remove("failed");
    var parent = container.parentElement;
    if (!parent) return;
    var fallback = parent.querySelector(".copy-fallback");
    if (fallback) fallback.remove();
    var status = parent.querySelector(".copy-status");
    if (status) {
      status.textContent = "";
      status.hidden = true;
    }
  }

  /**
   * 两种复制方式都失败：给出可读状态，并把命令放进可选中的文本块，
   * 用户用键盘（Tab 聚焦后 Ctrl/Cmd+A、Ctrl/Cmd+C）也能完成复制。
   */
  function markFailed(button, text) {
    var state = getCopyState(button);
    clearCopyTimer(state);
    button.classList.remove("done");
    button.innerHTML = state.html;
    button.classList.add("failed");
    button.setAttribute("aria-label", isEn ? "Copy failed; select command manually" : "复制失败，请手动选择命令复制");
    button.title = isEn ? "Copy failed: select command manually" : "复制失败：请手动选择命令后复制";
    var status = statusFor(button);
    if (status) {
      status.hidden = false;
      status.textContent = isEn
        ? "Copy failed: clipboard access was denied. Select the command below and press Ctrl+C (Command+C on macOS)."
        : "复制失败：浏览器拒绝了剪贴板访问。请在下方选中命令，按 Ctrl+C（macOS 为 Command+C）复制。";
    }
    var container = containerFor(button);
    var parent = container && container.parentElement;
    if (parent) {
      var existing = parent.querySelector(".copy-fallback");
      if (existing) existing.remove();
      var fallback = document.createElement("pre");
      fallback.className = "copy-fallback";
      fallback.tabIndex = 0;
      fallback.setAttribute("aria-label", isEn ? "Install command, select to copy manually" : "安装命令，可手动复制");
      fallback.textContent = text;
      status ? status.insertAdjacentElement("afterend", fallback) : parent.appendChild(fallback);
      fallback.focus();
      var range = document.createRange();
      range.selectNodeContents(fallback);
      var selection = window.getSelection();
      if (selection) {
        selection.removeAllRanges();
        selection.addRange(range);
      }
    }
    state.timer = setTimeout(function () {
      restoreButton(button, state);
    }, 4000);
  }

  function copyText(button, text) {
    function fallback() {
      if (legacyCopy(text)) markDone(button, text);
      else markFailed(button, text);
    }
    if (navigator.clipboard && window.isSecureContext) {
      navigator.clipboard.writeText(text).then(function () { markDone(button, text); }, fallback);
      return;
    }
    fallback();
  }

  document.querySelectorAll(".copy").forEach(function (button) {
    var state = getCopyState(button);
    button.dataset.label = state.label;
    button.addEventListener("click", function () {
      var text = button.dataset.copy || "";
      if (!text) return;
      copyText(button, text);
    });
  });

  /* ── 6. 卡片鼠标跟随高光 ───────────────────────────── */
  if (!reduceMotion && window.matchMedia("(hover: hover)").matches) {
    document.querySelectorAll(".spot").forEach(function (card) {
      card.addEventListener("pointermove", function (event) {
        var rect = card.getBoundingClientRect();
        card.style.setProperty("--mx", (event.clientX - rect.left) + "px");
        card.style.setProperty("--my", (event.clientY - rect.top) + "px");
      });
    });
  }
})();
