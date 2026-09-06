package com.rtdwh.repository;
import com.rtdwh.entity.DataServiceGrant;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface DataServiceGrantRepository extends JpaRepository<DataServiceGrant, Long> {
    boolean existsByAppIdAndServiceId(Long appId, Long serviceId);
    List<DataServiceGrant> findByAppId(Long appId);
    void deleteByAppIdAndServiceId(Long appId, Long serviceId);
}
