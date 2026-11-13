// Where a range of a textarea's text sits, measured on a hidden copy of the field: `left` and `top` are
// relative to the field's own box (scroll allowed for), `width` is the first line's share of the range.
export interface CaretBox { left: number; top: number; width: number; line: number }

const COPIED = ['boxSizing', 'width', 'paddingTop', 'paddingRight', 'paddingBottom', 'paddingLeft', 'borderTopWidth', 'borderLeftWidth', 'fontFamily', 'fontSize', 'fontWeight', 'lineHeight', 'letterSpacing', 'wordSpacing', 'textIndent', 'tabSize'] as const;

export function caretBox(el: HTMLTextAreaElement, start: number, end: number): CaretBox | null {
  try {
    const style = getComputedStyle(el);
    const copy = document.createElement('div');
    for (const p of COPIED) copy.style[p] = style[p];
    Object.assign(copy.style, { position: 'absolute', visibility: 'hidden', whiteSpace: 'pre-wrap', overflowWrap: 'break-word', top: '0', left: '-9999px' });
    copy.textContent = el.value.slice(0, start);
    const from = document.createElement('span');
    from.textContent = el.value.slice(start, end) || '.';
    copy.appendChild(from);
    document.body.appendChild(copy);
    const box = { left: from.offsetLeft, top: from.offsetTop - el.scrollTop, width: from.offsetWidth, line: parseFloat(style.lineHeight) || 24 };
    copy.remove();
    return box;
  } catch { return null; }
}
