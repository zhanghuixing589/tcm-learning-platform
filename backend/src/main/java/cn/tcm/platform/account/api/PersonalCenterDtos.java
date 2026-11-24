package cn.tcm.platform.account.api;

import cn.tcm.platform.community.application.CommunityReference;
import java.util.List;

public final class PersonalCenterDtos {
    private PersonalCenterDtos() {}
    public record Profile(String id,String nickname,String bio,String avatarUrl,String createdAt,List<String> roles) {}
    public record Summary(Profile profile,long receivedLikes,long followingCount,long followerCount,boolean following,boolean followsYou) {}
    public record Relationship(String id,String nickname,String bio,String avatarUrl,boolean following,boolean followsYou) {}
    public record ContentItem(String id,String resourceType,String title,String summary,boolean available,String occurredAt,
                              String ownerId,String ownerNickname,String sourceName,String visibilityStatus,String revisionState,String revisionId,
                              long likeCount,long bookmarkCount) {}
    public record ArticleInput(String title, String body, String sourceFormat, String sectionId,
                               List<CommunityReference> references, String baseRevisionId, Integer lockVersion) {}
}
