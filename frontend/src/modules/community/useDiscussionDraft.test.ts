import {expect,it} from 'vitest';
import {discussionDraftKey,parseDiscussionDraft} from './useDiscussionDraft';
it('isolates drafts by account and editing resource',()=>{expect(new Set([discussionDraftKey('1',''),discussionDraftKey('2',''),discussionDraftKey('1','8')]).size).toBe(3);});
it('accepts only the permitted draft fields and ignores any credential fields',()=>{const record=parseDiscussionDraft(JSON.stringify({version:1,data:{title:'题',body:'文',sectionId:'3',changeNote:'',token:'secret'}}));expect(record?.data).toEqual({title:'题',body:'文',sectionId:'3',changeNote:''});});
it('rejects malformed draft field types',()=>{expect(parseDiscussionDraft('{"version":1,"data":{"title":null}}')).toBeNull();});

import { afterEach, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { computed, defineComponent, reactive, ref } from 'vue';
import { createPinia } from 'pinia';
import { useSessionStore } from '../../shared/stores/session';
import { useDiscussionDraft } from './useDiscussionDraft';
afterEach(()=>{localStorage.clear();vi.restoreAllMocks();});
function editor(){const pinia=createPinia();const session=useSessionStore(pinia);session.user={id:'1',permissions:[]} as never;const form=reactive({title:'',body:'',sectionId:'',changeNote:''});const ready=ref(true);let draft!:ReturnType<typeof useDiscussionDraft>;const wrapper=mount(defineComponent({setup(){draft=useDiscussionDraft(ref('new'),ready,form,computed(()=>!!form.body));return()=>null;}}),{global:{plugins:[pinia]}});return {wrapper,form,session,get draft(){return draft;}};}
it('restores only on request, clears after success, and refuses writes under another account',async()=>{
 const key=discussionDraftKey('1','new');localStorage.setItem(key,JSON.stringify({version:1,data:{title:'本地标题',body:'本地正文',sectionId:'3',changeNote:''}}));
 const state=editor();await flushPromises();expect(state.form.body).toBe('');expect(state.draft.recovery.value).not.toBeNull();state.draft.restoreDraft();expect(state.form.body).toBe('本地正文');
 state.draft.clearDraft();expect(localStorage.getItem(key)).toBeNull();state.session.user={id:'2'} as never;state.form.body='不应写给另一账户';await flushPromises();expect(localStorage.getItem(discussionDraftKey('2','new'))).toBeNull();state.wrapper.unmount();
});
it('retains input and reports storage failures',async()=>{const state=editor();await flushPromises();vi.spyOn(Storage.prototype,'setItem').mockImplementation(()=>{throw Error('disabled');});state.form.body='仍在编辑器';await flushPromises();expect(state.form.body).toBe('仍在编辑器');expect(state.draft.draftError.value).toContain('存储');state.wrapper.unmount();});

it('reports a successful browser write only after storage succeeds',async()=>{const state=editor();await flushPromises();expect(state.draft.draftState.value).toBe('empty');state.form.body='当前草稿';await flushPromises();expect(state.draft.draftState.value).toBe('saved');state.draft.clearDraft();expect(state.draft.draftState.value).toBe('empty');state.wrapper.unmount();});
