package cn.tcm.platform.knowledge.api;

import java.math.BigDecimal;
import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

public final class KnowledgeDtos {
    private KnowledgeDtos() {}
    public record Herb(@Size(max=160) String nature,@Size(max=160) String flavor,@Size(max=10000) String originNote,@Size(max=10000) String precautions) {}
    public record Formula(@NotBlank @Size(max=500) String originText,@Size(max=10000) String functionSummary,@Size(max=10000) String indicationSummary,@Size(max=10000) String precautions) {}
    public record Evidence(@NotBlank String documentId,String segmentId,@Size(max=255) String locationLabel,@NotBlank @Size(max=20000) String quoteText) {}
    public record Ingredient(@NotBlank String herbResourceId,@NotBlank String herbRevisionId,@NotBlank String role,@DecimalMin(value="0",inclusive=false) @Digits(integer=8,fraction=4) BigDecimal doseValue,@Size(max=32) String doseUnit,@Size(max=160) String doseText,@NotNull @Min(0) Integer evidenceIndex) {}
    public record Relation(@NotBlank String targetResourceId,@NotBlank String targetRevisionId,@NotBlank String relationType,@Size(max=10000) String explanation,@NotNull @Min(0) Integer evidenceIndex) {}
    public record KnowledgeInput(@NotBlank String type,@NotBlank @Size(max=160) String canonicalName,@NotBlank @Size(max=255) String title,@NotBlank @Size(max=100000) String body,String categoryId,@Size(max=1000) String changeNote,@Size(max=50) List<@NotBlank @Size(max=160) String> aliases,@Valid Herb herb,@Valid Formula formula,@Size(max=100) List<@NotNull @Valid Evidence> evidence,@Size(max=100) List<@NotNull @Valid Ingredient> ingredients,@Size(max=100) List<@NotNull @Valid Relation> relations,@Size(max=20) List<@NotBlank String> tagIds) {}
    public record ImportInput(@NotNull @Size(min=1,max=50) List<@NotNull @Valid KnowledgeInput> items) {}
    public record ExtractionInput(@NotBlank String documentId,@NotEmpty @Size(max=50) List<@NotBlank String> segmentIds,@NotBlank String type) {}
    public record CompareInput(@NotNull @Size(min=2,max=3) List<@NotBlank String> ids) {}
    public record Reason(@NotBlank @Size(max=1000) String reason) {}
    public record FeedbackInput(@NotBlank @Size(max=10000) String content) {}
    public record FeedbackResolution(@NotBlank String status,@NotBlank @Size(max=10000) String resolution) {}
}
