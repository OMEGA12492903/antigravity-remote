(() => {
  "use strict";

  const BRIDGE_NAME = "antigravity_bridge";
  let port = null;

  // Enable comfort CSS by default until Android sends the persisted preference
  document.documentElement.classList.add("ag-comfort-enabled");

  function applyComfortMode(enabled) {
    document.documentElement.classList.toggle("ag-comfort-enabled", Boolean(enabled));
  }

  function findBestScrollContainer() {
    const selector = 'main, section, article, div, ul, ol, [role="main"], [role="log"]';
    const candidates = Array.from(document.querySelectorAll(selector)).filter((el) => {
      if (!(el instanceof HTMLElement)) return false;
      if (el.clientHeight <= 180 || el.scrollHeight - el.clientHeight <= 80) return false;
      const overflowY = window.getComputedStyle(el).overflowY;
      return overflowY === "auto" || overflowY === "scroll";
    });

    if (candidates.length === 0) {
      return document.scrollingElement || document.documentElement;
    }

    // Prefer the largest visible scrollable container (main conversation feed)
    candidates.sort((a, b) => {
      const rectA = a.getBoundingClientRect();
      const rectB = b.getBoundingClientRect();
      const areaA = rectA.width * rectA.height;
      const areaB = rectB.width * rectB.height;
      return areaB - areaA;
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
    const selectors = [
      'textarea:not([disabled]):not([readonly])',
      '[contenteditable="true"]',
      'input[type="text"]:not([disabled])'
    ];
    const inputs = [];
    for (const sel of selectors) {
      for (const el of document.querySelectorAll(sel)) {
        const rect = el.getBoundingClientRect();
        if (rect.width > 40 && rect.height > 16 && rect.bottom > 0) {
          inputs.push({ el, bottom: rect.bottom });
        }
      }
    }
    if (inputs.length === 0) return null;
    inputs.sort((a, b) => b.bottom - a.bottom);
    return inputs[0].el;
  }

  function focusChatInput() {
    const composer = findPrimaryComposer();
    if (!composer) return false;
    composer.scrollIntoView({ behavior: "smooth", block: "center" });
    composer.focus();
    return true;
  }

  function base64ToFile(name, mimeType, base64) {
    const binary = atob(base64);
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) {
      bytes[i] = binary.charCodeAt(i);
    }
    return new File([bytes], name || "image.jpg", {
      type: mimeType || "image/jpeg",
      lastModified: Date.now()
    });
  }

  function attachFilesToChat(fileItems) {
    if (!Array.isArray(fileItems) || fileItems.length === 0) {
      safePost({ type: "attachResult", ok: false, count: 0 });
      return;
    }

    let files;
    try {
      files = fileItems.map((item) => base64ToFile(item.name, item.mimeType, item.base64));
    } catch (_) {
      safePost({ type: "attachResult", ok: false, count: 0 });
      return;
    }

    // Strategy 1: Populate existing <input type="file"> if present in the DOM
    const fileInputs = Array.from(document.querySelectorAll('input[type="file"]'))
      .filter((el) => !el.disabled);
    if (fileInputs.length > 0) {
      const targetInput =
        fileInputs.find((el) => {
          const acc = (el.getAttribute("accept") || "").toLowerCase();
          return !acc || acc.includes("image") || acc.includes("*/*");
        }) || fileInputs[0];

      try {
        const dt = new DataTransfer();
        const limit = targetInput.multiple ? files.length : 1;
        for (let i = 0; i < limit; i++) {
          dt.items.add(files[i]);
        }
        targetInput.files = dt.files;
        targetInput.dispatchEvent(new Event("input", { bubbles: true }));
        targetInput.dispatchEvent(new Event("change", { bubbles: true }));
        safePost({ type: "attachResult", ok: true, count: limit, via: "input" });
        return;
      } catch (_) {
        // Fall through to paste/drop strategies
      }
    }

    // Strategy 2: Focus composer and dispatch a native ClipboardEvent("paste")
    const composer = findPrimaryComposer();
    if (composer) {
      composer.focus();
    }
    const targetEl = composer || document.activeElement || document.body;

    try {
      const dt = new DataTransfer();
      for (const f of files) dt.items.add(f);
      const pasteEvent = new ClipboardEvent("paste", {
        bubbles: true,
        cancelable: true,
        clipboardData: dt
      });
      const notCancelled = targetEl.dispatchEvent(pasteEvent);

      // If paste wasn't intercepted (defaultPrevented === false), also try DragEvent("drop")
      if (notCancelled) {
        const dropDt = new DataTransfer();
        for (const f of files) dropDt.items.add(f);
        const dropEvent = new DragEvent("drop", {
          bubbles: true,
          cancelable: true,
          dataTransfer: dropDt
        });
        targetEl.dispatchEvent(dropEvent);
      }

      safePost({ type: "attachResult", ok: true, count: files.length, via: "paste" });
    } catch (_) {
      safePost({ type: "attachResult", ok: false, count: 0 });
    }
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
      // Ignore if disconnected
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
