package cn.tcm.platform.community.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public final class CommunityArticleDtos {
    private CommunityArticleDtos() {}

    public record DraftIdentity(@NotBlank String title, String sourceFormat) {}

    public record RevisionLock(@NotBlank String baseRevisionId, @NotNull @Min(0) Integer lockVersion) {}

    public record ReferenceOrder(@NotBlank String baseRevisionId, @NotNull @Min(0) Integer lockVersion,
                                 @NotNull List<String> referenceKeys) {}

    public record QuotaInput(@NotNull @Min(1) Integer requestedLimit, @NotBlank String reason) {}

    public record QuotaDecision(@NotNull @Min(0) Integer lockVersion, @NotBlank String decision,
                                Integer approvedLimit, @NotBlank String note) {}

    public record QuotaView(int bodyCharLimit, int bodyHardLimit, int maxReferences, int maxReferenceChars,
                            QuotaRequestView latestRequest) {}

    public record QuotaRequestView(String id, String resourceId, String requestedBy, int requestedLimit,
                                   String reason, String status, String reviewerId, Integer approvedLimit,
                                   String reviewNote, int lockVersion, String createdAt, String reviewedAt) {}

    public record ArticleCapabilities(boolean canEdit, boolean canSubmit, boolean canWithdraw, boolean canRevise,
                                      boolean canDelete, boolean canRequestQuota) {}
}
