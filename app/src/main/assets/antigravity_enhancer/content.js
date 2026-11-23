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

  function focusChatInput() {
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
    if (inputs.length === 0) return false;

    // The primary chat composer is typically the lowest visible input on screen
    inputs.sort((a, b) => b.bottom - a.bottom);
    const composer = inputs[0].el;
    composer.scrollIntoView({ behavior: "smooth", block: "center" });
    composer.focus();
    return true;
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
