package cn.tcm.platform;

import cn.tcm.platform.community.application.*;
import cn.tcm.platform.common.ApiException;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class CommunityTextPolicyTest {
    final ObjectMapper json=new ObjectMapper();
    final CommunityTextValidator validator=new CommunityTextValidator();
    CommunityTextPolicy policy(){return CommunityTextPolicy.defaults();}
    @Test void countsCodePointsPreservesLfAndRejectsMalformedUnicode(){
        assertThat(validator.body("  中😀\r\n    x  \r", "PLAIN_TEXT",20,policy())).isEqualTo("  中😀\n    x  \n");
        assertThat(validator.body("😀😀", "MARKDOWN",2,policy())).isEqualTo("😀😀");
        assertThatThrownBy(()->validator.body("😀😀中","PLAIN_TEXT",2,policy())).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->validator.body("\uD800","PLAIN_TEXT",10,policy())).isInstanceOf(ApiException.class);
        assertThat(CommunityTextValidator.normalizeName("　Ａ\u0085 B  ")).isEqualTo("a b");
    }
    @Test void usesMarkdownAstAndNeverEnablesUnsafeCapabilities() throws Exception {
        for(String bad:List.of("<script>alert(1)</script>","hello <b onclick='x'>x</b>","![x](https://example.org/a)","[x](javascript:alert%281%29)","[x](//example.org)","[x](data:text/html,x)"))
            assertThatThrownBy(()->validator.body(bad,"MARKDOWN",1000,policy())).as(bad).isInstanceOf(ApiException.class);
        for(String good:List.of("```html\n<script>x</script>\n```","`<img src=x>`","[x](https://example.org)","|a|b|\n|-|-|\n|1|2|"))
            assertThat(validator.body(good,"MARKDOWN",1000,policy())).isEqualTo(good);
        assertThat(validator.body("<script>x</script>","PLAIN_TEXT",100,policy())).contains("<script>");
        var unsafe=policy().config();unsafe.withArray("allowedSyntax").add("html");
        assertThatThrownBy(()->CommunityTextPolicy.parse(unsafe)).isInstanceOf(ApiException.class);
        var oversized=policy().config();oversized.put("maxRequestBytes",9000000);
        assertThatThrownBy(()->CommunityTextPolicy.parse(oversized)).isInstanceOf(ApiException.class);
        var missing=policy().config();missing.remove("rates");assertThatThrownBy(()->CommunityTextPolicy.parse(missing)).isInstanceOf(ApiException.class);
        var copy=policy().config();copy.put("maxTitleChars",1);assertThat(policy().maxTitleChars()).isEqualTo(120);
    }
    @Test void enforcesUtf8TextCapacityAndLegacyTitleException(){
        var enlarged=CommunityTextPolicy.parse(policy().config().put("maxCommentChars",20000));
        assertThatThrownBy(()->validator.comment("😀".repeat(16384),enlarged)).isInstanceOf(ApiException.class);
        String old="中".repeat(121);assertThat(validator.title(old,old,policy())).isEqualTo(old);
        assertThatThrownBy(()->validator.title(old+"x",old,policy())).isInstanceOf(ApiException.class);
    }
    @Test void rejectsInvisibleTitlesAndPreservesExactLegacyTitles(){
        assertThatThrownBy(()->validator.title("\u0085\u00a0",null,policy())).isInstanceOf(ApiException.class);
        String prior="  "+"中".repeat(121)+"  ";assertThat(validator.title(prior,prior,policy())).isEqualTo(prior);
    }
    @Test void deeplyNestedMarkdownDoesNotOverflow(){String source="> ".repeat(7000)+"text";assertThatCode(()->validator.body(source,"MARKDOWN",100000,policy())).doesNotThrowAnyException();}
    @Test void arbitraryNestedJsonUsesSortedObjectKeysButRetainsArrayOrder() throws Exception {
        assertThat(CommunitySourceHash.canonical(json.readTree("{\"b\":{\"y\":2,\"x\":1},\"a\":null}"))).isEqualTo(CommunitySourceHash.canonical(json.readTree("{\"a\":null,\"b\":{\"x\":1,\"y\":2}}")));
        assertThat(CommunitySourceHash.canonical(List.of(1,2))).isNotEqualTo(CommunitySourceHash.canonical(List.of(2,1)));
    }
    @Test void canonicalHashMatchesMigrationContractAndRetainsNullFields(){
        assertThat(CommunitySourceHash.content("Old post","  Legacy **plain**  ","3","PLAIN_TEXT",List.of())).isEqualTo("e6b3568113428609af05f655cad61dd0b2b93ce8fe62089f13cdad0adadd62cc");
        assertThat(CommunitySourceHash.comment("a\r\nb")).isEqualTo(CommunitySourceHash.comment("a\nb"));
        var a=reference(1);var b=reference(2);
        assertThat(CommunitySourceHash.content("t","b","3","MARKDOWN",List.of(a,b))).isEqualTo(CommunitySourceHash.content("t","b","03","MARKDOWN",List.of(b,a)));
    }
    CommunityReference reference(int n){return new CommunityReference("00000000-0000-4000-8000-00000000000"+n,n,"Title "+n,null,2020,null,null,"https://example.org",null);}
    @Test void ordinarySaveCannotRenumberRetainedStableKeysButExplicitReorderCan(){
        var a=reference(1);var b=reference(2);var saved=List.of(a,b);
        assertThatThrownBy(()->CommunityReferences.preventCitationSubstitution("文献[1]",saved,"文献[1]",List.of(a.numbered(2),b.numbered(1)),"MARKDOWN"))
            .isInstanceOf(ApiException.class).extracting(e->((ApiException)e).status).isEqualTo(400);
        assertThatThrownBy(()->CommunityReferences.preventCitationSubstitution("正文",saved,"正文",List.of(a.numbered(2),b.numbered(1)),"MARKDOWN")).isInstanceOf(ApiException.class);
        assertThatCode(()->CommunityReferences.preventCitationSubstitution("文献[1]",saved,"更新正文[1]",List.of(b,a),"MARKDOWN")).doesNotThrowAnyException();
        var reordered=CommunityReferences.reorder("文献[1]",saved,List.of(b.referenceKey(),a.referenceKey()),"MARKDOWN");
        assertThat(reordered.body()).isEqualTo("文献[2]");
        assertThat(reordered.references()).containsExactly(b.numbered(1),a.numbered(2));
    }
    @Test void remapsSavedSourceByStableKeyExcludingCodeEscapesAndMarkdownLinks(){
        var refs=List.of(reference(1),reference(2));
        String source="文献[1]和[2] `code [1]` \\[1] [1](https://example.org)\n```\n[2]\n```";
        var result=CommunityReferences.reorder(source,refs,List.of(refs.get(1).referenceKey(),refs.get(0).referenceKey()),"MARKDOWN");
        assertThat(result.body()).isEqualTo("文献[2]和[1] `code [1]` \\[1] [1](https://example.org)\n```\n[2]\n```");
        assertThat(result.references().get(0).referenceKey()).isEqualTo(refs.get(1).referenceKey());
        assertThat(CommunityReferences.validate("[3]",refs,policy(),false,"MARKDOWN")).containsExactly(3);
        assertThatThrownBy(()->CommunityReferences.validate("[3]",refs,policy(),true,"MARKDOWN")).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->CommunityReferences.reorder("[1]",refs,List.of(refs.get(1).referenceKey()),"MARKDOWN")).isInstanceOf(ApiException.class);
    }
    @Test void reorderTracksNewCitationSpansAcrossDifferentNumberWidths() {
        var a = reference(1).numbered(10);
        var b = reference(2);
        String source = "😀 [10] 和 [2]，`[10]`\n";
        var result = CommunityReferences.reorder(source, List.of(a, b), List.of(b.referenceKey(), a.referenceKey()), "MARKDOWN");
        assertThat(result.body()).isEqualTo("😀 [2] 和 [1]，`[10]`\n");
    }
    @Test void rejectsReorderThatWouldConvertACitationIntoAMarkdownShortcutLink() {
        var a = reference(1);
        var b = reference(2);
        String source = "参见 [1]\n\n[2]: https://example.com";
        assertThat(CommunityReferences.citations(source, "MARKDOWN")).extracting(CommunityReferences.Citation::number)
                .containsExactly(1);
        assertThatThrownBy(() -> CommunityReferences.reorder(source, List.of(a, b),
                List.of(b.referenceKey(), a.referenceKey()), "MARKDOWN")).isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).status).isEqualTo(400);
    }
}
