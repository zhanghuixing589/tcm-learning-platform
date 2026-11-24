import { expect, it } from "vitest";
import { renderMarkdown, safeExternalUrl } from "./markdown";
import { testPolicy } from "./testFixtures";

const key = "376f0571-0706-410e-9041-3d95160dae85";
const refs = [{ referenceKey: key, displayNumber: 1 }];

it("renders only safe markup and absolute permitted external links", () => {
  const source = '<img src="https://test.invalid/x" onerror="alert(1)">\n\n' +
    '![x](https://test.invalid/x) [js](javascript:alert(1)) [bad](/relative) [good](https://example.org)';
  const html = renderMarkdown(source, testPolicy.config, refs);
  expect(html).not.toMatch(/<img|href="(?:javascript:|\/relative|https:\/\/test\.invalid)/);
  expect(html).toContain('href="https://example.org/"');
  expect(html).toContain('rel="nofollow ugc noopener noreferrer"');
  expect(safeExternalUrl("//example.org", testPolicy.config)).toBeNull();
});

it("links only independent citations outside code, escaped text and Markdown links", () => {
  const source = "文献[1] \`[1]\` \\[1] [1](https://example.org) [内部 [1]](https://example.org)\n\n    [1]";
  const html = renderMarkdown(source, testPolicy.config, refs);
  expect(html.match(new RegExp('href="#reference-' + key + '"', "g"))).toHaveLength(1);
  expect(html).toContain("<code>[1]</code>");
  expect(renderMarkdown("[1]\n\n[1]: https://example.org", testPolicy.config, refs)).not.toContain("#reference-");
  expect(renderMarkdown("[1](/relative) ![1](https://example.org/image)", testPolicy.config, refs)).not.toContain("#reference-");
});

it("controls inline code and block code independently without turning their text into citations", () => {
  const source = "\x60[1]\x60\n\n    [1]";
  const blockOnly = renderMarkdown(source, { ...testPolicy.config, allowedSyntax: ["paragraph", "codeBlock"] }, refs);
  expect(blockOnly.match(/<code>/g)).toHaveLength(1);
  expect(blockOnly).toContain("<pre><code>");
  expect(blockOnly).not.toContain("#reference-");
  const inlineOnly = renderMarkdown(source, { ...testPolicy.config, allowedSyntax: ["paragraph", "code"] }, refs);
  expect(inlineOnly.match(/<code>/g)).toHaveLength(1);
  expect(inlineOnly).not.toContain("<pre>");
});

it("respects restricted syntax and protocols", () => {
  const config = { ...testPolicy.config, allowedSyntax: ["paragraph", "link"] as const, allowedProtocols: ["https"] as const };
  const html = renderMarkdown("# 标题\n\n**字** [链接](http://example.org)", config, refs);
  expect(html).not.toMatch(/<h1|<strong|href=/);
  expect(html).toContain("标题");
});
