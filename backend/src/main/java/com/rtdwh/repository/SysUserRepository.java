package com.rtdwh.repository;

import com.rtdwh.entity.SysUser;
import com.rtdwh.entity.SysUser.UserStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SysUserRepository extends JpaRepository<SysUser, Long> {

    Optional<SysUser> findByUsername(String username);

    Optional<SysUser> findByUsernameAndStatus(String username, UserStatus status);

    boolean existsByUsername(String username);

    boolean existsByRoles_Id(Long roleId);
}
