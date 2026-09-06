package com.rtdwh.repository;
import com.rtdwh.entity.DataServiceInvocationLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface DataServiceInvocationLogRepository extends JpaRepository<DataServiceInvocationLog, Long> {
    List<DataServiceInvocationLog> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
