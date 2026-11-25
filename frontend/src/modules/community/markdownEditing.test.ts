import { expect, it } from 'vitest';
import { applyMarkdown } from './markdownEditing';
it('wraps the selection and preserves its cursor range',()=>{expect(applyMarkdown('甲乙丙',1,2,'bold')).toEqual({text:'甲**乙**丙',start:3,end:4});});
it('inserts a selected placeholder at an empty cursor',()=>{const edit=applyMarkdown('',0,0,'italic');expect(edit.text).toBe('*文字*');expect(edit.text.slice(edit.start,edit.end)).toBe('文字');});
it('prefixes complete selected lines for ordered lists',()=>{expect(applyMarkdown('甲\n乙\n丙',0,3,'ordered').text).toBe('1. 甲\n2. 乙\n丙');});
it('uses a safe longer code fence when the selection contains fences',()=>{expect(applyMarkdown('```js\nx\n```',0,11,'code').text).toContain('````\n');});
it('creates a separate fenced block when code is inserted in a paragraph',()=>{expect(applyMarkdown('甲乙丙',1,2,'code').text).toBe('甲\n```\n乙\n```\n丙');});
it('edits the empty first line rather than skipping to the next line',()=>{expect(applyMarkdown('\n正文',0,0,'heading').text).toBe('## 文字\n正文');});
