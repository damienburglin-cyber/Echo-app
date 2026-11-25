/* Echo app: a page map in the same text format as the Claude desktop browser's read_page,
   built from what the page SHOWS (visible elements, their text). Each line gets a ref that
   later taps and typing use. Hidden data (scripts, embedded JSON) is never read. */
(() => {
  const refs = window.__echoRefs = {}; let n = 0; const out = [];
  const shown = el => { const cs = getComputedStyle(el); if (cs.display === 'none' || cs.visibility === 'hidden') return false; const r = el.getBoundingClientRect(); return r.width > 0 || r.height > 0 || el.children.length > 0; };
  const roleOf = el => {
    const r = el.getAttribute('role'); if (r && r !== 'presentation' && r !== 'none') return r;
    const t = el.tagName.toLowerCase();
    if (['script', 'style', 'noscript', 'template', 'head', 'svg', 'path', 'iframe'].includes(t)) return null;
    if (t === 'button') return 'button';
    if (t === 'a' && el.hasAttribute('href')) return 'link';
    if (t === 'input') { const ty = (el.type || 'text').toLowerCase(); if (ty === 'hidden') return null; if (ty === 'checkbox') return 'checkbox'; if (ty === 'radio') return 'radio'; if (['button', 'submit', 'reset'].includes(ty)) return 'button'; return 'textbox'; }
    if (t === 'textarea' || el.getAttribute('contenteditable') === 'true') return 'textbox';
    if (/^h[1-6]$/.test(t)) return 'heading';
    if (t === 'label') return 'label';
    if (t === 'dialog') return 'dialog';
    if (t === 'img') return 'img';
    return 'generic';
  };
  const clean = s => String(s || '').replace(/\s+/g, ' ').trim();
  const ownText = el => clean([...el.childNodes].filter(c => c.nodeType === 3).map(c => c.textContent).join(' '));
  const nameOf = (el, role) => {
    const a = el.getAttribute('aria-label'); if (a) return clean(a);
    if (['button', 'link', 'heading', 'label', 'tab', 'option', 'menuitem'].includes(role)) return clean(el.innerText || el.value);
    if (role === 'img') return clean(el.alt);
    if (role === 'textbox') return clean(el.getAttribute('placeholder'));
    return ownText(el);
  };
  const walk = (el, depth) => {
    if (!shown(el)) return;
    const role = roleOf(el); if (role === null) return;
    const name = nameOf(el, role);
    const leafBox = el.children.length === 0 && !name && el.getBoundingClientRect().width > 8;
    const keep = role !== 'generic' || name || leafBox || el.children.length > 1;
    let d = depth;
    if (keep) {
      const ref = 'ref_' + (++n); refs[ref] = el;
      out.push(' '.repeat(depth) + role + (name ? ' "' + name.replace(/"/g, "'").slice(0, 400) + '"' : '') + ' [' + ref + ']' + (el.checked ? ' checked' : '') + (el.disabled ? ' disabled' : ''));
      d = depth + 1;
    }
    for (const c of el.children) walk(c, d);
  };
  walk(document.body, 0);
  return out.join('\n');
})()
