import {get,post,patch,type Category,type KnowledgeTag,type KnowledgeType} from './api';
export type ManagedDirectory=Category & {type:KnowledgeType|null;active:boolean;legacy:boolean;sortOrder:number;lockVersion:number};
export type ManagedTag=KnowledgeTag & {lockVersion:number};
export const taxonomyApi={
 directories:()=>get<ManagedDirectory[]>('/admin/knowledge/categories'),
 createDirectory:(body:{type:KnowledgeType;name:string;sortOrder:number;active:boolean})=>post<ManagedDirectory>('/admin/knowledge/categories',body),
 updateDirectory:(id:string,body:{name:string;sortOrder:number;active:boolean;lockVersion:number})=>patch<ManagedDirectory>('/admin/knowledge/categories/'+id,body),
 tags:()=>get<ManagedTag[]>('/admin/knowledge/tags'),
 createTag:(body:{name:string;active:boolean})=>post<ManagedTag>('/admin/knowledge/tags',body),
 updateTag:(id:string,body:{name:string;active:boolean;lockVersion:number})=>patch<ManagedTag>('/admin/knowledge/tags/'+id,body),
};
