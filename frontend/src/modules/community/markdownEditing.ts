export type MarkdownAction = 'heading' | 'bold' | 'italic' | 'ordered' | 'unordered' | 'quote' | 'link' | 'code';
export function applyMarkdown(source: string, start: number, end: number, action: MarkdownAction) {
  const selected = source.slice(start, end) || '文字';
  let before = '', after = '', value = selected;
  if (action === 'bold') before = after = '**';
  if (action === 'italic') before = after = '*';
  if (action === 'link') { before = '['; after = '](https://example.org)'; }
  if (action === 'code') {
    const longest = Math.max(2, ...Array.from(selected.matchAll(/`+/g), match => match[0].length));
    before = '`'.repeat(longest + 1) + '\n'; after = '\n' + '`'.repeat(longest + 1);
    if (start > 0 && source[start - 1] !== '\n') before = '\n' + before;
    if (end < source.length && source[end] !== '\n') after += '\n';
  }
  if (['heading', 'ordered', 'unordered', 'quote'].includes(action)) {
    const lineStart = start === 0 ? 0 : source.lastIndexOf('\n', start - 1) + 1;
    const nextBreak = source.indexOf('\n', end);
    const lineEnd = nextBreak < 0 ? source.length : nextBreak;
    const content = source.slice(lineStart, lineEnd) || '文字';
    value = content.split('\n').map((line, i) => (action === 'heading' ? '## ' : action === 'ordered' ? `${i + 1}. ` : action === 'unordered' ? '- ' : '> ') + line).join('\n');
    return {text: source.slice(0,lineStart) + value + source.slice(lineEnd), start: lineStart, end: lineStart + value.length};
  }
  const text = source.slice(0,start) + before + value + after + source.slice(end);
  return {text, start: start + before.length, end: start + before.length + value.length};
}
