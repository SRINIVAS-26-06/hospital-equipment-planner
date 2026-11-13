package com.hospital.planner.repository;

import com.hospital.planner.entity.*;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AssetAliasRepository extends JpaRepository<AssetAlias, String> {
    @Query("select a from AssetAlias a where a.equipment.id = :asset order by a.alias")
    List<AssetAlias> findForAsset(@Param("asset") Long asset);
}
