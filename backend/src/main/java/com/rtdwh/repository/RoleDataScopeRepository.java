package com.rtdwh.repository;

import com.rtdwh.entity.RoleDataScope;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface RoleDataScopeRepository extends JpaRepository<RoleDataScope, Long> {
    List<RoleDataScope> findByRoleIdIn(Collection<Long> roleIds);
    List<RoleDataScope> findByRoleIdOrderById(Long roleId);
    void deleteByRoleId(Long roleId);
}
