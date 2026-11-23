import React, { useEffect, useId, useRef, useState } from 'react';

export function Icon({ name, size = 20, ...props }) {
  const paths = {
    sun: <><circle cx="12" cy="12" r="4"/><path d="M12 2v2m0 16v2M2 12h2m16 0h2M5 5l1.5 1.5m11 11L19 19M5 19l1.5-1.5m11-11L19 5"/></>,
    home: <><path d="m3 10 9-7 9 7v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1z"/><path d="M9 21v-8h6v8"/></>,
    users: <><circle cx="9" cy="8" r="3"/><path d="M3 21v-3a6 6 0 0 1 12 0v3M17 5a3 3 0 0 1 0 6m2 10v-3a6 6 0 0 0-2-4"/></>,
    cloud: <path d="M6 18a5 5 0 0 1-.4-10 7 7 0 0 1 13.3 1A4.5 4.5 0 0 1 18.5 18H6Z"/>,
    settings: <><path d="M4 6h16M4 12h16M4 18h16"/><circle cx="8" cy="6" r="2"/><circle cx="16" cy="12" r="2"/><circle cx="10" cy="18" r="2"/></>,
    search: <><circle cx="10.5" cy="10.5" r="6.5"/><path d="m16 16 5 5"/></>,
    check: <path d="m5 12 4 4L19 6"/>,
    arrow: <path d="m9 5 7 7-7 7"/>,
    back: <path d="m15 5-7 7 7 7"/>,
    close: <path d="m6 6 12 12M6 18 18 6"/>,
    save: <><path d="M5 3h12l4 4v14H3V3h2Z"/><path d="M7 3v6h10V3M7 21v-8h10v8"/></>,
    refresh: <><path d="M20 7v5h-5M4 17v-5h5"/><path d="M6 6a8 8 0 0 1 13 3M5 15a8 8 0 0 0 13 3"/></>,
    shield: <><path d="m12 3 8 3v6c0 5-8 9-8 9s-8-4-8-9V6z"/><path d="m8 12 3 3 5-6"/></>,
    bolt: <path d="m13 2-9 12h7l-1 8 10-13h-8z"/>,
    copy: <><rect x="8" y="8" width="12" height="13" rx="2"/><path d="M16 8V3H3v13h5"/></>,
  };
  return <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" {...props}>{paths[name] || paths.settings}</svg>;
}

export function Help({ children, label = '说明' }) {
  const ref = useRef(null);
  useEffect(() => {
    if (!children) return undefined;
    const close = (event) => { if (event.key === 'Escape' || (event.type === 'pointerdown' && !ref.current?.contains(event.target))) ref.current?.removeAttribute('open'); };
    document.addEventListener('pointerdown', close);
    document.addEventListener('keydown', close);
    return () => { document.removeEventListener('pointerdown', close); document.removeEventListener('keydown', close); };
  }, [Boolean(children)]);
  if (!children) return null;
  // Native disclosure works with a mouse, touch, keyboard and screen readers.
  return <details className="help" ref={ref}><summary aria-label={label}>?</summary><div className="help-bubble">{children}</div></details>;
}

export function Modal({ title, children, onClose }) {
  const ref = useRef(null);
  const id = useId();
  useEffect(() => { ref.current?.showModal(); }, []);
  return <dialog ref={ref} className="modal" aria-labelledby={id} onCancel={onClose} onClick={(event) => { if (event.target === event.currentTarget) onClose(); }}>
    <div className="modal-heading"><h2 id={id}>{title}</h2><button type="button" className="icon-button" aria-label="关闭" onClick={onClose}><Icon name="close"/></button></div>
    {children}
  </dialog>;
}

export function EmbeddedSelect({ id, label, value, options, disabled, onChange }) {
  const popup = useRef(null);
  const popupId = useId();
  const [expanded, setExpanded] = useState(false);
  const selectedLabel = options.find((option) => option.value === value)?.label;

  useEffect(() => {
    if (!expanded) return;
    const close = () => popup.current?.close();
    if (disabled) close();
    window.addEventListener('resize', close);
    return () => window.removeEventListener('resize', close);
  }, [expanded, disabled]);

  function open(event) {
    // WebView in an Android overlay has no activity token for its native select window.
    // Position an HTML dialog next to the field within the WebView instead.
    const anchor = event.currentTarget.getBoundingClientRect();
    const dialog = popup.current;
    const viewport = document.documentElement;
    const inset = 8;
    const gap = 4;
    const width = Math.min(Math.max(anchor.width, 180), viewport.clientWidth - 2 * inset);
    const below = viewport.clientHeight - anchor.bottom - gap - inset;
    const above = anchor.top - gap - inset;
    dialog.style.width = `${width}px`;
    dialog.style.left = `${Math.min(Math.max(inset, anchor.left), viewport.clientWidth - width - inset)}px`;
    dialog.style.maxHeight = 'none';
    dialog.showModal();
    const openAbove = dialog.offsetHeight > below && above > below;
    dialog.style.maxHeight = `${openAbove ? above : below}px`;
    dialog.style.top = `${openAbove ? anchor.top - gap - dialog.offsetHeight : anchor.bottom + gap}px`;
    dialog.querySelector('[aria-pressed="true"]')?.focus();
    setExpanded(true);
  }

  return <>
    <button type="button" id={id} className="select-trigger" disabled={disabled} onClick={open}
      aria-label={`${label}，${selectedLabel}`} aria-haspopup="dialog" aria-expanded={expanded} aria-controls={popupId}>
      <span>{selectedLabel}</span><Icon name="arrow" size={14}/>
    </button>
    <dialog ref={popup} id={popupId} className="select-popup" aria-label={label}
      onClose={() => setExpanded(false)}
      onClick={(event) => { if (event.target === event.currentTarget) event.currentTarget.close(); }}>
      <div className="select-options">
        {options.map((option) => {
          const selected = option.value === value;
          return <button key={option.value} type="button" disabled={disabled} aria-pressed={selected} onClick={() => {
            if (!selected) onChange(option.value);
            popup.current.close();
          }}>
            <span>{option.label}</span>{selected && <Icon name="check" size={16}/>}
          </button>;
        })}
      </div>
    </dialog>
  </>;
}

export function Card({ title, subtitle, icon = 'settings', accent = 'blue', help, children, className = '' }) {
  return <section className={`card accent-${accent} ${className}`}>
    <div className="card-heading"><span className="card-icon"><Icon name={icon}/></span><div><h2>{title}</h2>{subtitle && <p>{subtitle}</p>}</div><Help label={`${title}说明`}>{help}</Help></div>
    {children}
  </section>;
}

export function Toggle({ checked, onChange, disabled, label }) {
  return <button type="button" role="switch" aria-checked={checked} aria-label={label} disabled={disabled} className={`switch ${checked ? 'on' : ''}`} onClick={() => onChange(!checked)}><span/></button>;
}
