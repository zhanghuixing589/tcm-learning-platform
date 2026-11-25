import {expect,it} from 'vitest';
import {createPinia,setActivePinia} from 'pinia';
import {useFormulaShelf} from './formulaShelf';
import {useSessionStore} from '../../shared/stores/session';
import type {KnowledgeSummary} from './api';
const item=(id:string)=>({id,type:'FORMULA',title:'方'+id,canonicalName:'方'+id,revisionId:id,versionNo:1} as KnowledgeSummary);
it('uses one account-scoped selection, rejects duplicates and limits it to three formulas',()=>{setActivePinia(createPinia());const session=useSessionStore();session.user={id:'1'} as never;const shelf=useFormulaShelf();expect(shelf.add(item('1'))).toBe('added');expect(shelf.add(item('1'))).toBe('duplicate');shelf.add(item('2'));shelf.add(item('3'));expect(shelf.add(item('4'))).toBe('full');shelf.remove('2');expect(shelf.add(item('4'))).toBe('added');expect(shelf.selected.map(x=>x.id)).toEqual(['1','3','4']);session.user={id:'2'} as never;expect(shelf.selected).toHaveLength(0);});
