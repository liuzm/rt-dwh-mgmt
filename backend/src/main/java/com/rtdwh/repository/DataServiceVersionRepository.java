package com.rtdwh.repository;

import com.rtdwh.entity.DataServiceVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface DataServiceVersionRepository extends JpaRepository<DataServiceVersion, Long> {
    List<DataServiceVersion> findByServiceIdOrderByVersionNoDesc(Long serviceId);
    Optional<DataServiceVersion> findFirstByServiceIdOrderByVersionNoDesc(Long serviceId);
}
