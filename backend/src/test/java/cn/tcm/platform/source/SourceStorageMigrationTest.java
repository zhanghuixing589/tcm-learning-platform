package cn.tcm.platform.source;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class SourceStorageMigrationTest {
    @TempDir Path directory;
    @Test void manifestSupportsJdbcLocalDateTimeWithoutChangingGrantValues()throws Exception{
        var timestamp=LocalDateTime.of(2026,11,26,1,2,3,456000000);var grant=Map.of("file_id",1L,"user_id",2L,"original_name","来源.epub","created_at",timestamp);Path target=directory.resolve("manifest.json");SourceStorageMigration.writeManifest(target,Map.of("files",List.of(),"grants",List.of(grant)));
        var data=new com.fasterxml.jackson.databind.ObjectMapper().readTree(target.toFile()).path("grants").get(0);assertThat(data.path("created_at").asText()).isEqualTo("2026-11-26T01:02:03.456");assertThat(data.path("original_name").asText()).isEqualTo("来源.epub");assertThat(grant.get("created_at")).isEqualTo(timestamp);
    }
}
