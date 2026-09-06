package com.rtdwh.repository;

import com.rtdwh.entity.DatasetProduction;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DatasetProductionRepository extends JpaRepository<DatasetProduction, Long> {
    java.util.List<DatasetProduction> findByOutputDatasetIdInOrderByProducedAtDesc(java.util.Collection<Long> ids, org.springframework.data.domain.Pageable pageable);
    java.util.Optional<DatasetProduction> findByDeliveryKey(String deliveryKey);
    java.util.Optional<DatasetProduction> findFirstByOutputDatasetIdAndDefinitionVersionIdAndWindowStartAndWindowEndAndStatusOrderByIdDesc(
            Long outputId, Long versionId, java.time.LocalDate windowStart, java.time.LocalDate windowEnd, String status);
    List<DatasetProduction> findByInstanceId(Long instanceId);
    List<DatasetProduction> findByOutputDatasetIdOrderByProducedAtDesc(Long outputDatasetId, Pageable pageable);
}
