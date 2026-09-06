package com.rtdwh.repository;
import com.rtdwh.entity.ManagedViewVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface ManagedViewVersionRepository extends JpaRepository<ManagedViewVersion,Long> {
 List<ManagedViewVersion> findByViewIdOrderByVersionNoDesc(Long viewId);
}
