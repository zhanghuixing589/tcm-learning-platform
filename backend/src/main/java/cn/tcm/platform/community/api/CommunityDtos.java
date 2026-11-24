package cn.tcm.platform.community.api;

import cn.tcm.platform.community.application.CommunityReference;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public final class CommunityDtos {
    private CommunityDtos() {}

    public record SectionCreate(String parentId, @NotBlank String name, @NotBlank String code,
                                String description, String rulesText, String ownerId, @NotNull Integer sortOrder) {}
    public record SectionUpdate(@NotNull @Min(0) Integer lockVersion, String description, String rulesText,
                                Boolean active, Integer sortOrder) {}
    public record SectionProfile(@NotNull @Min(0) Integer lockVersion, String description, String rulesText) {}
    public record PinInput(@NotNull @Min(0) Integer lockVersion, @NotNull Boolean pinned) {}
    public record PinView(String resourceId, String sectionId, boolean pinned, int lockVersion) {}
    public record SectionRef(String id, String parentId, String code, String name) {}
    public record SectionCapabilities(boolean canJoin, boolean canLeave, boolean canEditProfile, boolean canManage,
                                      boolean canPin, boolean canPost, boolean canSubmitArticle) {}
    public record SectionView(String id, String parentId, String name, String code, String description,
                              String rulesText, String ownerId, String lifecycle, String managementStatus,
                              boolean active, boolean writable, int sortOrder, int lockVersion,
                              boolean joined, long memberCount, SectionCapabilities capabilities) {}
    public record MembershipView(String sectionId, boolean joined, long memberCount) {}

    public record PostCreate(@NotBlank String sectionId, @NotBlank String title, @NotNull String body,
                             String sourceFormat) {}
    public record PostEdit(@NotBlank String baseRevisionId, @NotNull @Min(0) Integer lockVersion,
                           @NotBlank String title, @NotNull String body, String changeNote) {}
    public record Person(String id, String nickname, String avatarUrl) {}
    public record PostCapabilities(boolean canEdit, boolean canComment, boolean canPin) {}
    public record PostView(String id, String resourceType, SectionRef section, String revisionId, String title,
                           String body, String summary, String sourceFormat, String formatPolicyId, String releaseMethod,
                           List<CommunityReference> references, Person owner, String publishedAt, String publicEditedAt,
                           String lastPublicCommentAt, long commentCount, long likeCount, long bookmarkCount,
                           boolean liked, boolean bookmarked, boolean commentsEnabled, boolean pinned, int pinLockVersion,
                           PostCapabilities capabilities) {}
    public record RevisionHeader(String revisionId, int versionNo, String baseRevisionId, String state,
                                 String title, String changeNote, String releaseMethod, String createdAt) {}
    public record RevisionSource(String revisionId, int versionNo, String state, String title, String body,
                                 String sectionId, String sourceFormat, String formatPolicyId, String releaseMethod,
                                 String sourceHash, int bodyCharCount, List<CommunityReference> references) {}
    public record OwnPost(String id, String resourceType, String visibilityStatus, String publishedRevisionId,
                          PostView published, RevisionSource latestRevision, int lockVersion,
                          PostCapabilities capabilities) {}
    public record SourceChange(int commonPrefixChars, int commonSuffixChars, int fromChangedChars, int toChangedChars) {}
    public record RevisionDiff(String resourceId, RevisionSource before, RevisionSource after,
                               SourceChange titleChange, SourceChange bodyChange) {}

    public record CommentCreate(@NotNull String body, String rootCommentId, String replyToCommentId) {}
    public record CommentEdit(@NotBlank String baseRevisionId, @NotNull @Min(0) Integer lockVersion, @NotNull String body) {}
    public record CommentCapabilities(boolean canReply, boolean canEdit, boolean canDelete) {}
    public record CommentView(String id, String resourceId, String rootCommentId, String replyToCommentId,
                              String revisionId, Person author, String body, String displayState,
                              String sourceFormat, String formatPolicyId, String createdAt, String editedAt,
                              long replyCount, int lockVersion, CommentCapabilities capabilities) {}
}
