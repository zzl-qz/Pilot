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
  var copiedSvg = '<svg viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="m5 13 4 4L19 7" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>';

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
    var original = button.innerHTML;
    button.classList.add("done");
    button.innerHTML = copiedSvg;
    button.setAttribute("aria-label", "已复制");
    button.title = "已复制：" + text.split("\n")[0];
    setTimeout(function () {
      button.classList.remove("done");
      button.innerHTML = original;
      button.setAttribute("aria-label", button.dataset.label || "复制");
    }, 1600);
  }

  document.querySelectorAll(".copy").forEach(function (button) {
    button.dataset.label = button.getAttribute("aria-label") || "复制";
    button.addEventListener("click", function () {
      var text = button.dataset.copy || "";
      if (!text) return;
      if (navigator.clipboard && window.isSecureContext) {
        navigator.clipboard.writeText(text)
          .then(function () { markDone(button, text); })
          .catch(function () { if (legacyCopy(text)) markDone(button, text); });
      } else if (legacyCopy(text)) {
        markDone(button, text);
      }
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
