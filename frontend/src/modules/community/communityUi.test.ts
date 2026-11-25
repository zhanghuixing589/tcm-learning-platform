import { afterEach, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { communityApi } from './api';
import type { Post } from './types';
import PostFeed from './PostFeed.vue';
import CommunityRelated from './CommunityRelated.vue';
import CommunityCompanion from './CommunityCompanion.vue';
import { communityExcerpt } from './reading';

afterEach(() => vi.restoreAllMocks());
it('presents Markdown excerpts as text while preserving historical plain-text syntax', () => {
  expect(communityExcerpt('## 标题\n\n**重点** [出处](https://example.org)\n\n![图注](https://example.org/a.png)', 'MARKDOWN')).toBe('标题 重点 出处 图注');
  expect(communityExcerpt('# 原始标题 **原始内容**', 'PLAIN_TEXT')).toBe('# 原始标题 **原始内容**');
});
const post = (id: string, ownerId = '1'): Post => ({
  id, resourceType: 'ARTICLE', title: '分享' + id, summary: '真实摘要', body: '',
  section: { id: '7', parentId: null, code: 'section', name: '真实分区' },
  owner: { id: ownerId, nickname: '作者' + ownerId, avatarUrl: null },
  publishedAt: '2026-11-24T00:00:00Z', commentCount: 3, likeCount: 2, bookmarkCount: 1,
} as Post);
const global = { stubs: { RouterLink: { props: ['to'], template: '<a :href="to"><slot /></a>' } } };
const result = (items: Post[], page = 1) => ({ items, page, pageSize: 1, total: 3 });

it('preserves applied filters and pagination requests through the discovery tabs', async () => {
  const spy = vi.spyOn(communityApi, 'posts').mockResolvedValue(result([post('1')]));
  const wrapper = mount(PostFeed, { global }); await flushPromises();
  await wrapper.find('input[type="search"]').setValue('本草');
  await wrapper.find('select').setValue('LATEST_REPLY');
  const discussion = wrapper.findAll('button').find(node => node.text() === '学习讨论')!;
  await discussion.trigger('click'); await flushPromises();
  expect(spy).toHaveBeenLastCalledWith(expect.objectContaining({ q: '本草', type: 'POST', sort: 'LATEST_REPLY', page: 1 }));
  await wrapper.findAll('button').find(node => node.text() === '下一页')!.trigger('click'); await flushPromises();
  expect(spy).toHaveBeenLastCalledWith(expect.objectContaining({ q: '本草', type: 'POST', sort: 'LATEST_REPLY', page: 2 }));
  wrapper.unmount();
});

it('discards an older feed response after a new filter has loaded', async () => {
  let complete!: (value: ReturnType<typeof result>) => void;
  vi.spyOn(communityApi, 'posts').mockImplementationOnce(() => new Promise(resolve => { complete = resolve; })).mockResolvedValue(result([post('new')]));
  const wrapper = mount(PostFeed, { global });
  await wrapper.findAll('button').find(node => node.text() === '科普文章')!.trigger('click'); await flushPromises();
  complete(result([post('old')])); await flushPromises();
  expect(wrapper.text()).toContain('分享new'); expect(wrapper.text()).not.toContain('分享old');
  const events = wrapper.emitted('loaded')!;
  expect(events[events.length - 1][0]).toMatchObject({ items: [{ id: 'new' }] });
  wrapper.unmount();
});

it('excludes the current resource from real list-based related reading', async () => {
  const spy = vi.spyOn(communityApi, 'posts').mockResolvedValue(result([post('1'), post('2')]));
  const wrapper = mount(CommunityRelated, { props: { resourceId: '1', sectionId: '7' }, global }); await flushPromises();
  expect(spy).toHaveBeenCalledWith(expect.objectContaining({ sectionId: '7', sort: 'NEWEST' }));
  expect(wrapper.find('a[href="/community/posts/1"]').exists()).toBe(false);
  expect(wrapper.find('a[href="/community/posts/2"]').exists()).toBe(true);
  wrapper.unmount();
});

it('does not let related reading from the previous resource overwrite the current one', async () => {
  let complete!: (value: ReturnType<typeof result>) => void;
  vi.spyOn(communityApi, 'posts').mockImplementationOnce(() => new Promise(resolve => { complete = resolve; })).mockResolvedValue(result([post('3')]));
  const wrapper = mount(CommunityRelated, { props: { resourceId: '1' }, global });
  await wrapper.setProps({ resourceId: '2' }); await flushPromises();
  complete(result([post('old')])); await flushPromises();
  expect(wrapper.text()).toContain('分享3'); expect(wrapper.text()).not.toContain('分享old'); wrapper.unmount();
});

it('labels authors as current-page participants without inventing global rankings', () => {
  const wrapper = mount(CommunityCompanion, { props: { groups: [], posts: [post('1'), post('2'), post('3', '2')] }, global });
  expect(wrapper.findAll('.community-author')).toHaveLength(2);
  expect(wrapper.text()).toContain('本页 2 篇分享'); expect(wrapper.text()).toContain('来自当前列表');
  expect(wrapper.text()).not.toContain('热门作者'); wrapper.unmount();
});

it('extracts readable headings, lists, blockquotes and fenced code without mutating source',()=>{
 const source='## 标题\n\n> 引用\n\n1. 第一项\n2. 第二项\n\n```js\nconst n = 1;\n```';
 expect(communityExcerpt(source,'MARKDOWN')).toBe('标题 引用 第一项 第二项 const n = 1;');
 expect(source.startsWith('##')).toBe(true);
});

it('retries the first page of a changed filter rather than the previously displayed page',async()=>{
 const spy=vi.spyOn(communityApi,'posts').mockResolvedValueOnce(result([post('1')],1)).mockResolvedValueOnce(result([post('2')],2)).mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce(result([post('3')],1));
 const wrapper=mount(PostFeed,{global});await flushPromises();
 await wrapper.findAll('button').find(node=>node.text()==='下一页')!.trigger('click');await flushPromises();
 await wrapper.findAll('button').find(node=>node.text()==='科普文章')!.trigger('click');await flushPromises();
 await wrapper.findAll('button').find(node=>node.text()==='重新加载')!.trigger('click');await flushPromises();
 expect(spy).toHaveBeenLastCalledWith(expect.objectContaining({type:'ARTICLE',page:1}));wrapper.unmount();
});

import SectionNavigation from './SectionNavigation.vue';
import CommunityPostCard from './CommunityPostCard.vue';
it('filters a large section list without replacing the real routes',async()=>{
 const groups=Array.from({length:10},(_,index)=>({section:{id:String(index),name:'分区'+index,writable:true,joined:false},children:[]}));
 const wrapper=mount(SectionNavigation,{props:{groups:groups as never},global});
 await wrapper.get('input[type="search"]').setValue('分区9');
 expect(wrapper.findAll('nav a')).toHaveLength(2);expect(wrapper.find('a[href="/community/sections/9"]').exists()).toBe(true);
 await wrapper.get('input[type="search"]').setValue('不存在');expect(wrapper.text()).toContain('没有匹配的分区');wrapper.unmount();
});
it('shows the existing discussion bookmark count consistently with the reader',()=>{const wrapper=mount(CommunityPostCard,{props:{item:{...post('8'),resourceType:'POST',bookmarkCount:2,bookmarked:true}},global});expect(wrapper.get('.post-card-interactions').text()).toContain('2 人收藏');expect(wrapper.get('.post-card-interactions .is-active').attributes('title')).toBe('已收藏');wrapper.unmount();});
