package com.rtdwh.repository;
import com.rtdwh.entity.DatasetProductionCheck;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface DatasetProductionCheckRepository extends JpaRepository<DatasetProductionCheck, Long> {
    List<DatasetProductionCheck> findByProductionIdOrderByIdDesc(Long productionId);
}
