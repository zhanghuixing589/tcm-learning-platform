import DOMPurify from "dompurify";
import MarkdownIt from "markdown-it";
import type { Reference, Syntax, TextPolicyConfig } from "./types";

type Citation = Pick<Reference, "referenceKey" | "displayNumber">;
const tags: Record<Syntax, string[]> = {
  paragraph: ["p", "br"], heading: ["h1", "h2", "h3", "h4", "h5", "h6"],
  emphasis: ["em"], strong: ["strong"], blockquote: ["blockquote"], code: ["code"],
  codeBlock: ["pre", "code"], list: ["ul", "ol", "li"], link: ["a"],
  table: ["table", "thead", "tbody", "th", "tr", "td"], hr: ["hr"],
};
const referenceKey = /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i;

export function referenceAnchor(key: string): string | undefined {
  return referenceKey.test(key) ? "reference-" + key : undefined;
}

export function safeExternalUrl(value: unknown, config: TextPolicyConfig): string | null {
  if (typeof value !== "string" || !/^https?:\/\//i.test(value) || /[\s\\\u0000-\u001f\u007f]/.test(value)) return null;
  try {
    const url = new URL(value);
    if (url.username || url.password) return null;
    const scheme = url.protocol === "https:" ? "https" : "http";
    return config.allowedProtocols.includes(scheme) ? url.href : null;
  } catch {
    return null;
  }
}

export function renderMarkdown(source: string, config: TextPolicyConfig, references: readonly Citation[]): string {
  const parser = new MarkdownIt({ html: false, linkify: false, typographer: false });
  parser.disable("strikethrough");
  const allowLinks = config.allowedSyntax.includes("link");
  // Parse links and images as units even when their destination cannot be rendered.
  // Otherwise bracketed text in a forbidden link can be mistaken for a citation.
  parser.validateLink = () => true;
  const escape = parser.utils.escapeHtml;
  parser.renderer.rules.image = (tokens, index) => escape(tokens[index].content);
  if (!config.allowedSyntax.includes("code")) {
    parser.renderer.rules.code_inline = (tokens, index) => escape(tokens[index].content);
  }
  if (!config.allowedSyntax.includes("codeBlock")) {
    const block = (tokens: Parameters<NonNullable<typeof parser.renderer.rules.fence>>[0], index: number) =>
      "<p>" + escape(tokens[index].content) + "</p>\n";
    parser.renderer.rules.fence = block;
    parser.renderer.rules.code_block = block;
  }
  const byNumber = new Map(references.map((ref) => [ref.displayNumber, referenceAnchor(ref.referenceKey)]));
  parser.inline.ruler.after("link", "article_citation", (state, silent) => {
    // Silent parsing belongs to Markdown-it's link label lookahead.
    if (silent || state.src[state.pos] !== "[" || state.linkLevel > 0) return false;
    const match = /^\[([1-9][0-9]{0,8})\]/.exec(state.src.slice(state.pos, Math.min(state.pos + 11, state.posMax)));
    const anchor = match && byNumber.get(Number(match[1]));
    if (!match || !anchor) return false;
    const token = state.push("article_citation", "a", 0);
    token.content = match[0];
    token.attrSet("href", "#" + anchor);
    state.pos += match[0].length;
    return true;
  });
  parser.renderer.rules.article_citation = (tokens, index) => {
    const token = tokens[index];
    return '<a href="' + token.attrGet("href") + '">' + token.content + "</a>";
  };
  const linkStack: boolean[] = [];
  parser.renderer.rules.link_open = (tokens, index, options, _env, renderer) => {
    const href = safeExternalUrl(tokens[index].attrGet("href"), config);
    linkStack.push(allowLinks && !!href);
    if (!allowLinks || !href) return "";
    tokens[index].attrSet("href", href);
    tokens[index].attrSet("target", "_blank");
    tokens[index].attrSet("rel", "nofollow ugc noopener noreferrer");
    return renderer.renderToken(tokens, index, options);
  };
  parser.renderer.rules.link_close = () => linkStack.pop() ? "</a>" : "";

  return DOMPurify.sanitize(parser.render(source), {
    ALLOWED_TAGS: Array.from(new Set([...config.allowedSyntax.flatMap((syntax) => tags[syntax] || []), "a"])),
    ALLOWED_ATTR: ["href", "rel", "target", "start"],
    ALLOW_ARIA_ATTR: false,
    ALLOW_DATA_ATTR: false,
  });
}
