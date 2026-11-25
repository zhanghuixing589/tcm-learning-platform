import MarkdownIt from 'markdown-it';
import type { SourceFormat } from './types';

export interface CommunityHeading {
  id: string;
  title: string;
  level: number;
}

const excerptParser = new MarkdownIt({ html: false, linkify: false, typographer: false });
type ExcerptToken = ReturnType<typeof excerptParser.parse>[number];

/** Return text only; never render feed snippets as HTML or fetch image destinations. */
export function communityExcerpt(source: string, format: SourceFormat): string {
  if (format === 'PLAIN_TEXT') return source.trim();
  const inline = (tokens: ExcerptToken[]): string => tokens.map(token => {
    if (token.type === 'image') return token.content;
    if (token.children) return inline(token.children);
    if (['text', 'code_inline'].includes(token.type)) return token.content;
    return ['softbreak', 'hardbreak'].includes(token.type) ? ' ' : '';
  }).join('');
  return excerptParser.parse(source, {}).map(token => {
    if (token.type === 'inline') return inline(token.children || []);
    return ['fence', 'code_block'].includes(token.type) ? token.content.trim() : '';
  }).filter(Boolean).join(' ').trim();
}
