import huangqi from '../../assets/herbs/huangqi.png';
import chenpi from '../../assets/herbs/chenpi.png';
import type {KnowledgeSummary} from './api';

// User-supplied, permitted reference photographs. Match only the exact herb
// canonical name; an alias, title or similar name is not reliable identification.
const images=new Map([['黄芪',huangqi],['陈皮',chenpi]]);
export function herbImage(item:Pick<KnowledgeSummary,'type'|'canonicalName'>){
 if(item.type!=='HERB')return undefined;
 const name=item.canonicalName.trim();
 const src=images.get(name);
 return src?{src,alt:name+'药材参考图'}:undefined;
}
