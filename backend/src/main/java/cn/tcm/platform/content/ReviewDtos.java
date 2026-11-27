package cn.tcm.platform.content;

import jakarta.validation.constraints.*;

public final class ReviewDtos {
    private ReviewDtos(){}
    public record Claim(@NotNull @Min(0) Integer lockVersion){}
    public record Decision(@NotNull @Min(0) Integer lockVersion,@NotBlank String decision,@NotBlank @Size(max=10000) String note,java.util.List<String> acknowledgedWarnings,String approvedUse,java.util.Map<String,Object> approvedCoverage,String coverageSnapshotHash){
        public Decision(Integer lockVersion,String decision,String note){this(lockVersion,decision,note,null,null,null,null);}
    }
}
