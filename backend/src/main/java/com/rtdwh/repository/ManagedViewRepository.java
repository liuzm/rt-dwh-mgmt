package com.rtdwh.repository;
import com.rtdwh.entity.ManagedView;
import org.springframework.data.jpa.repository.*;
import jakarta.persistence.LockModeType;
import java.util.Optional;
public interface ManagedViewRepository extends JpaRepository<ManagedView,Long> {
 Optional<ManagedView> findByTableMetaId(Long tableMetaId);
 @Lock(LockModeType.PESSIMISTIC_WRITE)
 @Query("select v from ManagedView v where v.id=:id")
 Optional<ManagedView> lockById(Long id);
}
