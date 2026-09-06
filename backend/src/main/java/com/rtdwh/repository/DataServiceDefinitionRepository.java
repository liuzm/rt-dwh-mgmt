package com.rtdwh.repository;
import com.rtdwh.entity.DataServiceDefinition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import java.util.List;
import java.util.Optional;
public interface DataServiceDefinitionRepository extends JpaRepository<DataServiceDefinition, Long> {
    Optional<DataServiceDefinition> findByServiceCode(String code);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select service from DataServiceDefinition service where service.id = :id")
    Optional<DataServiceDefinition> findByIdForUpdate(Long id);
    boolean existsByServiceCode(String code);
    long countByStatus(DataServiceDefinition.ServiceStatus status);
    @Query("select service from DataServiceDefinition service where lower(service.serviceName) like lower(concat('%', :keyword, '%')) or lower(service.serviceCode) like lower(concat('%', :keyword, '%')) or lower(coalesce(service.description, '')) like lower(concat('%', :keyword, '%'))")
    List<DataServiceDefinition> searchByKeyword(String keyword, Pageable pageable);
}
