package com.rtdwh.repository;

import com.rtdwh.entity.DwhTableMeta;
import com.rtdwh.entity.DwhTableMeta.TableLayer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DwhTableMetaRepository extends JpaRepository<DwhTableMeta, Long> {

    List<DwhTableMeta> findByLayer(TableLayer layer);
    Optional<DwhTableMeta> findByAssetId(String assetId);

    Optional<DwhTableMeta> findByCatalogNameAndPaimonDbAndPaimonTable(String catalogName, String paimonDb, String paimonTable);

    @Query("select t from DwhTableMeta t where t.paimonDb=:paimonDb and t.paimonTable=:paimonTable and t.assetType like 'paimon%'")
    Optional<DwhTableMeta> findByPaimonDbAndPaimonTable(String paimonDb, String paimonTable);

    @Query("SELECT t FROM DwhTableMeta t WHERE " +
           "(:layer IS NULL OR t.layer = :layer) AND " +
           "(:database IS NULL OR t.paimonDb = :database) AND " +
           "(:keyword IS NULL OR LOWER(t.paimonTable) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
           "OR LOWER(COALESCE(t.businessDesc, '')) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
           "OR LOWER(COALESCE(t.owner, '')) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
           "OR LOWER(COALESCE(t.businessDomain, '')) LIKE LOWER(CONCAT('%', :keyword, '%')))")
    List<DwhTableMeta> searchTables(@Param("layer") TableLayer layer,
                                    @Param("database") String database,
                                    @Param("keyword") String keyword);
}
