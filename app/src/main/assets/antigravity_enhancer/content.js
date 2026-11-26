(() => {
  "use strict";

  const BRIDGE_NAME = "antigravity_bridge";
  const pageWin = window.wrappedJSObject || window;
  let port = null;

  // Enable comfort CSS by default until Android sends the persisted preference
  document.documentElement.classList.add("ag-comfort-enabled");

  function applyComfortMode(enabled) {
    document.documentElement.classList.toggle("ag-comfort-enabled", Boolean(enabled));
  }

  /**
   * Deeply queries Light DOM + all open Shadow Roots (Angular / Lit / Material web components).
   */
  function querySelectorAllDeep(selector, root = document) {
    const results = [];
    const visited = new Set();

    function walk(node) {
      if (!node || visited.has(node)) return;
      visited.add(node);

      if (typeof node.querySelectorAll === "function") {
        try {
          const matches = node.querySelectorAll(selector);
          for (const m of matches) {
            results.push(m);
          }
        } catch (_) {
          // Ignore invalid selector in unusual nodes
        }

        try {
          const all = node.querySelectorAll("*");
          for (const el of all) {
            if (el.shadowRoot) {
              walk(el.shadowRoot);
            }
          }
        } catch (_) {
        }
      }
    }

    walk(root);
    return results;
  }

  function findBestScrollContainer() {
    const selector = 'main, section, article, div, ul, ol, [role="main"], [role="log"]';
    const candidates = querySelectorAllDeep(selector).filter((el) => {
      if (!(el instanceof HTMLElement)) return false;
      if (el.clientHeight <= 180 || el.scrollHeight - el.clientHeight <= 80) return false;
      const overflowY = window.getComputedStyle(el).overflowY;
      return overflowY === "auto" || overflowY === "scroll";
    });

    if (candidates.length === 0) {
      return document.scrollingElement || document.documentElement;
    }

    candidates.sort((a, b) => {
      const rectA = a.getBoundingClientRect();
      const rectB = b.getBoundingClientRect();
      return rectB.width * rectB.height - rectA.width * rectA.height;
    });
    return candidates[0];
  }

  function scrollToBottom() {
    const target = findBestScrollContainer();
    if (!target) return;
    try {
      target.scrollTo({ top: target.scrollHeight, behavior: "smooth" });
    } catch (_) {
      target.scrollTop = target.scrollHeight;
    }
  }

  function findPrimaryComposer() {
    const selector = [
      'textarea:not([disabled]):not([readonly])',
      '[contenteditable="true"]',
      '[role="textbox"][contenteditable="true"]',
      '[role="textbox"]',
      'input[type="text"]:not([disabled])'
    ].join(", ");

    const inputs = [];
    for (const el of querySelectorAllDeep(selector)) {
      if (!(el instanceof HTMLElement)) continue;
      const rect = el.getBoundingClientRect();
      if (rect.width > 40 && rect.height > 16 && rect.bottom > 0 && rect.top < window.innerHeight) {
        inputs.push({ el, bottom: rect.bottom, area: rect.width * rect.height });
      }
    }
    if (inputs.length === 0) return null;

    // Prefer the lowest visible interactive composer on screen
    inputs.sort((a, b) => b.bottom - a.bottom || b.area - a.area);
    return inputs[0].el;
  }

  function focusChatInput() {
    const composer = findPrimaryComposer();
    if (!composer) return false;
    composer.scrollIntoView({ behavior: "smooth", block: "center" });
    composer.focus();
    return true;
  }

  /**
   * Builds a genuine page-compartment File object (bypassing Firefox Xray isolation
   * so Angular/React/Lit page listeners can read .name, .type, .size, and bytes cleanly).
   */
  function base64ToPageFile(name, mimeType, base64) {
    const binary = atob(base64);
    const len = binary.length;
    const pageBytes = new pageWin.Uint8Array(len);
    for (let i = 0; i < len; i++) {
      pageBytes[i] = binary.charCodeAt(i);
    }
    const parts = new pageWin.Array();
    parts.push(pageBytes);

    const opts = new pageWin.Object();
    opts.type = mimeType || "image/jpeg";
    opts.lastModified = Date.now();
    return new pageWin.File(parts, name || "image.jpg", opts);
  }

  function buildPageDataTransfer(pageFiles, maxCount) {
    const dt = new pageWin.DataTransfer();
    const limit = Math.min(pageFiles.length, maxCount || pageFiles.length);
    for (let i = 0; i < limit; i++) {
      dt.items.add(pageFiles[i]);
    }
    return dt;
  }

  function createPageEvent(type, extraProps) {
    const init = new pageWin.Object();
    init.bubbles = true;
    init.composed = true;
    init.cancelable = true;
    if (extraProps) {
      for (const key of Object.keys(extraProps)) {
        init[key] = extraProps[key];
      }
    }
    return new pageWin.Event(type, init);
  }

  function createPagePasteEvent(dt) {
    let ev = null;
    try {
      const init = new pageWin.Object();
      init.bubbles = true;
      init.composed = true;
      init.cancelable = true;
      init.clipboardData = dt;
      ev = new pageWin.ClipboardEvent("paste", init);
    } catch (_) {
      ev = null;
    }

    const hasFiles =
      ev &&
      ev.clipboardData &&
      ev.clipboardData.files &&
      ev.clipboardData.files.length > 0;

    if (!hasFiles) {
      ev = createPageEvent("paste");
      const desc = new pageWin.Object();
      desc.value = dt;
      desc.configurable = true;
      pageWin.Object.defineProperty(ev, "clipboardData", desc);
    }
    return ev;
  }

  function createPageDragEvent(type, dt) {
    let ev = null;
    try {
      const init = new pageWin.Object();
      init.bubbles = true;
      init.composed = true;
      init.cancelable = true;
      init.dataTransfer = dt;
      ev = new pageWin.DragEvent(type, init);
    } catch (_) {
      ev = null;
    }

    const hasFiles =
      ev &&
      ev.dataTransfer &&
      ev.dataTransfer.files &&
      ev.dataTransfer.files.length > 0;

    if (!hasFiles) {
      ev = createPageEvent(type);
      const desc = new pageWin.Object();
      desc.value = dt;
      desc.configurable = true;
      pageWin.Object.defineProperty(ev, "dataTransfer", desc);
    }
    return ev;
  }

  /**
   * Finds Antigravity's native attach / add-media button near the composer as a Layer-3 trigger
   * for our auto-supplied native GeckoView FilePrompt fallback.
   */
  function findAttachButtonNear(composer) {
    const keywords = [
      "attach", "upload", "add file", "add media", "add image", "add photo",
      "image", "photo", "adjuntar", "subir", "imagen", "archivo"
    ];
    const composerRect = composer ? composer.getBoundingClientRect() : null;
    const buttons = querySelectorAllDeep('button:not([disabled]), [role="button"]:not([aria-disabled="true"])');

    const candidates = [];
    for (const btn of buttons) {
      if (!(btn instanceof HTMLElement)) continue;
      const rect = btn.getBoundingClientRect();
      if (rect.width < 12 || rect.height < 12 || rect.bottom <= 0) continue;

      const label = [
        btn.getAttribute("aria-label") || "",
        btn.getAttribute("title") || "",
        btn.getAttribute("data-tooltip") || "",
        btn.textContent || ""
      ].join(" ").toLowerCase();

      const matched = keywords.some((kw) => label.includes(kw));
      if (!matched) continue;

      let dist = Math.abs(rect.bottom - window.innerHeight);
      if (composerRect) {
        dist = Math.hypot(
          rect.left - composerRect.left,
          rect.top - composerRect.top
        );
      }
      candidates.push({ btn, dist });
    }

    if (candidates.length === 0) return null;
    candidates.sort((a, b) => a.dist - b.dist);
    return candidates[0].btn;
  }

  function attachFilesToChat(fileItems) {
    if (!Array.isArray(fileItems) || fileItems.length === 0) {
      safePost({ type: "attachResult", ok: false, count: 0, via: "empty" });
      return;
    }

    let pageFiles;
    try {
      pageFiles = fileItems.map((item) =>
        base64ToPageFile(item.name, item.mimeType, item.base64)
      );
    } catch (e) {
      safePost({ type: "attachResult", ok: false, count: 0, via: "decode_error" });
      return;
    }

    // LAYER 1: Real page-realm <input type="file"> across Light + Shadow DOM
    const fileInputs = querySelectorAllDeep('input[type="file"]').filter((el) => !el.disabled);
    if (fileInputs.length > 0) {
      const targetInput =
        fileInputs.find((el) => {
          const acc = (el.getAttribute("accept") || "").toLowerCase();
          return !acc || acc.includes("image") || acc.includes("*/*");
        }) || fileInputs[0];

      try {
        const rawInput = targetInput.wrappedJSObject || targetInput;
        const limit = rawInput.multiple ? pageFiles.length : 1;
        const dt = buildPageDataTransfer(pageFiles, limit);
        rawInput.files = dt.files;
        rawInput.dispatchEvent(createPageEvent("input"));
        rawInput.dispatchEvent(createPageEvent("change"));
        if (rawInput.files && rawInput.files.length > 0) {
          safePost({ type: "attachResult", ok: true, count: rawInput.files.length, via: "input" });
          return;
        }
      } catch (_) {
        // Fall through to Layer 2
      }
    }

    // LAYER 2: Real page-realm ClipboardEvent("paste") + DragEvent("drop") across Shadow DOM
    const composer = findPrimaryComposer();
    if (composer) {
      try {
        composer.focus();
      } catch (_) {
      }
    }

    const rawComposer = composer ? (composer.wrappedJSObject || composer) : null;
    const rawTarget = rawComposer || pageWin.document.activeElement || pageWin.document.body;

    try {
      const pasteDt = buildPageDataTransfer(pageFiles);
      const pasteEv = createPagePasteEvent(pasteDt);
      rawTarget.dispatchEvent(pasteEv);
      if (pasteEv.defaultPrevented) {
        safePost({ type: "attachResult", ok: true, count: pageFiles.length, via: "paste" });
        return;
      }
    } catch (_) {
    }

    // Try drag-and-drop sequence (dragenter -> dragover -> drop) on composer and main root
    const dropTargets = [rawComposer, pageWin.document.querySelector("main") || pageWin.document.body]
      .filter(Boolean);
    for (const target of dropTargets) {
      try {
        const dropDt = buildPageDataTransfer(pageFiles);
        target.dispatchEvent(createPageDragEvent("dragenter", dropDt));
        target.dispatchEvent(createPageDragEvent("dragover", dropDt));
        const dropEv = createPageDragEvent("drop", dropDt);
        target.dispatchEvent(dropEv);
        if (dropEv.defaultPrevented) {
          safePost({ type: "attachResult", ok: true, count: pageFiles.length, via: "drop" });
          return;
        }
      } catch (_) {
      }
    }

    // LAYER 3: Click Antigravity's native attach button so Android auto-supplies the staged file:// URIs
    const attachBtn = findAttachButtonNear(composer);
    if (attachBtn) {
      try {
        const rawBtn = attachBtn.wrappedJSObject || attachBtn;
        rawBtn.click();
        safePost({ type: "attachResult", ok: true, count: pageFiles.length, via: "native_button" });
        return;
      } catch (_) {
      }
    }

    // Honest failure: never claim success if nothing intercepted the image
    safePost({ type: "attachResult", ok: false, count: 0, via: "no_target" });
  }

  function handleNativeMessage(msg) {
    if (!msg || typeof msg !== "object") return;
    switch (msg.action) {
      case "setComfortMode":
        applyComfortMode(msg.enabled);
        break;
      case "scrollToBottom":
        scrollToBottom();
        break;
      case "focusInput":
        focusChatInput();
        break;
      case "attachImages":
        attachFilesToChat(msg.files);
        break;
      case "ping":
        safePost({ type: "pong", url: window.location.href });
        break;
      default:
        break;
    }
  }

  function safePost(payload) {
    try {
      if (port) port.postMessage(payload);
    } catch (_) {
    }
  }

  let reconnectAttempts = 0;

  function connectBridge() {
    try {
      port = browser.runtime.connectNative(BRIDGE_NAME);
      reconnectAttempts = 0;
      port.onMessage.addListener(handleNativeMessage);
      port.onDisconnect.addListener(() => {
        port = null;
        if (reconnectAttempts < 5) {
          reconnectAttempts += 1;
          setTimeout(connectBridge, 1500 * reconnectAttempts);
        }
      });
      safePost({ type: "ready", url: window.location.href });
    } catch (_) {
      if (reconnectAttempts < 5) {
        reconnectAttempts += 1;
        setTimeout(connectBridge, 1500 * reconnectAttempts);
      }
    }
  }

  connectBridge();
})();
