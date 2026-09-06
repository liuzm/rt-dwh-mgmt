package com.rtdwh.repository;
import com.rtdwh.entity.DataServiceApp;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
public interface DataServiceAppRepository extends JpaRepository<DataServiceApp, Long> {
    Optional<DataServiceApp> findByAppKey(String appKey);
}
