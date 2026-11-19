package cn.tcm.platform.content;

import jakarta.validation.constraints.*;

public final class ReviewDtos {
    private ReviewDtos(){}
    public record Claim(@NotNull @Min(0) Integer lockVersion){}
    public record Decision(@NotNull @Min(0) Integer lockVersion,@NotBlank String decision,@NotBlank @Size(max=10000) String note){}
}
