package com.rtdwh.repository;
import com.rtdwh.entity.AssetSchemaRevision;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import java.util.*;
public interface AssetSchemaRevisionRepository extends JpaRepository<AssetSchemaRevision, Long> {
    Optional<AssetSchemaRevision> findFirstByTableMetaIdOrderByRevisionNoDesc(Long tableMetaId);
    List<AssetSchemaRevision> findByTableMetaIdOrderByRevisionNoDesc(Long tableMetaId, Pageable pageable);
}
